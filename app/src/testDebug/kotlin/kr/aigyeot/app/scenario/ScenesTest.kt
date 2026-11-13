package kr.aigyeot.app.scenario

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.aigyeot.engine.PlaybackEnded
import kr.aigyeot.engine.SaveSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 가짜 호스트 + 진짜 판단 코어로 assets/scenario의 장면을 끝까지 돌린다. */
class ScenesTest {
    private val dir = File("src/debug/assets/scenario")
    private fun scene(id: String) = parseScene(File(dir, "$id.json").readText())
    /** 계약 7.5 뼈대의 녹음: 'stop'은 울음이 시작 20초 뒤 그침(rt_outcome.py 정답 '그침' 5~25초), 나머지는 녹음 내내 운다. */
    private val cryLen = mapOf("cry/stop_40s.pcm" to 20.0, "cry/stop_50s.pcm" to 20.0)

    private fun runAll(id: String): List<RunRow> {
        val s = scene(id)
        return (0 until s.repeat).map { k -> FakeWorld(cryLen).play(s, k) }
            .also { File("build/scenario-jvm").apply { mkdirs() }.resolve("$id.csv").writeText(csv(it)) }   // 측정표(가짜 세상)
    }

    /** 15장 시나리오 기준: 끝까지 처리, 경보 지연 3초 이하, 시도 2회·에피소드 112초·세션 20분 이하, 운전자 조작 0, 이득 위반 0. */
    private fun assertMeetsLimits(id: String, rows: List<RunRow>, alarm: Boolean = true) {
        rows.forEach { r ->
            assertTrue("$id run ${r.run}: ${r.failures}", r.completed)
            if (alarm) assertTrue("$id 경보 지연 ${r.alarmLatencyMs}", r.alarmLatencyMs != null && r.alarmLatencyMs!! <= 3_000)
            assertTrue(r.maxAttempts <= 2)
            assertTrue(r.maxEpisodePlayMs <= 112_000)
            assertTrue(r.sessionPlayMs <= 1_200_000)
            assertEquals("$id 운전자 조작", 0, r.driverOps)
            assertEquals(0, r.gainViolations)
            assertEquals(0, r.systemVolumeChanges)
        }
    }

    @Test fun `장면 A 등원 20분 - 1차에 그침`() {
        val rows = runAll("A")
        assertEquals(5, rows.size)
        assertMeetsLimits("A", rows)
        rows.forEach { assertEquals(1, it.maxAttempts); assertEquals("bus100_audio_zone_1", it.routedDevice) }
    }

    @Test fun `장면 B 실내 27도 - 쾌적 조정 2차`() {
        val rows = runAll("B")
        assertMeetsLimits("B", rows)
        rows.forEach { assertEquals(2, it.maxAttempts); assertEquals(1.0, it.controlOkRate!!, 0.0) }
        // S04는 실제로 낮춘 값(22℃ → 20℃)을 말한다(C102)
        val w = FakeWorld(cryLen); w.play(scene("B"))
        assertEquals("2", w.commands.map { it.second }.filterIsInstance<kr.aigyeot.engine.Speak>().first { it.sid == kr.aigyeot.engine.Sid.S04 }.vars["loweredC"])
    }

    @Test fun `장면 C 수유 시간대 - 쉼터 응 뒤 경유지`() {
        val rows = runAll("C")
        assertMeetsLimits("C", rows)   // 쉼터 '응'은 운전자 조작에서 뺀다
    }

    @Test fun `장면 C2 출발 - 수유실 제안 응 뒤 경유지`() {
        val rows = runAll("C2")
        assertMeetsLimits("C2", rows, alarm = false)
        val w = FakeWorld(cryLen); w.play(scene("C2"))
        val ask = w.commands.map { it.second }.filterIsInstance<kr.aigyeot.engine.Ask>().single { it.sid == kr.aigyeot.engine.Sid.S07_DEPART }
        assertEquals("안성휴게소(부산방향)", ask.vars["restName"]); assertEquals("30", ask.vars["distanceKm"])
    }

    @Test fun `장면 C2의 setup care는 장면 상태 care에 들어간다(C119)`() {
        val w = FakeWorld(cryLen); w.play(scene("C2"))
        val care = w.events.filterIsInstance<kr.aigyeot.engine.CareSummary>().single().summary
        assertEquals(FakeWorld.T0 - 60_000, care.asOf)
        assertEquals(FakeWorld.T0 - 12_600_000, care.lastSleepStartAt); assertEquals(FakeWorld.T0 - 3_600_000, care.lastDiaperAt)
        assertEquals(2, care.todayFeedCount); assertEquals(null, care.sleepingSince)
        assertEquals(care, w.steps.value!!.state.care)
    }

    @Test fun `장면 D 계속 울음 - 두 번 뒤 인계`() {
        val rows = runAll("D")
        assertMeetsLimits("D", rows)
        rows.forEach { assertEquals(2, it.maxAttempts); assertTrue(it.handoffMs!! in 100_000..130_000); assertTrue(it.maxEpisodePlayMs in 110_000..112_000) }   // 시도마다 재생 시작이 0.2초 늦다
    }

    @Test fun `장면 D2 도심 - 인계인데 6분 남음`() = assertMeetsLimits("D2", runAll("D2"))

    @Test fun `장면 E 도착 - 뒷창 되돌리고 리포트`() = assertMeetsLimits("E", runAll("E"), alarm = false)

    @Test fun `F14 자동 시작·종료 각본 20개 - 95퍼센트 이상`() {
        val ids = dir.listFiles()!!.map { it.nameWithoutExtension }.filter { it.startsWith("F14-") }.sorted()
        assertEquals(20, ids.size)
        val rows = ids.associateWith { FakeWorld().play(scene(it)) }
        val bad = rows.filterValues { !it.completed }
        assertTrue("어긋난 각본 $bad", rows.size - bad.size >= 19)
        assertTrue("가짜 차에서는 모두 맞아야 한다: $bad", bad.isEmpty())
        rows.forEach { (id, r) -> assertEquals("$id 운전자 조작", 0, r.driverOps) }
    }

    @Test fun `F7 재생 도중 끝난 장면도 진행 중 재생 시간을 센다`() {
        // A를 경보 뒤 재생 중(t=330)까지만 담는다: 재생은 끝나지 않았다
        val o = Json.parseToJsonElement(File(dir, "A.json").readText()).jsonObject
        val cut = JsonObject(o + ("steps" to JsonArray(o["steps"]!!.jsonArray.filter { it.jsonObject["t"]!!.jsonPrimitive.double <= 330.0 })))
        val s = parseScene(cut.toString())
        val w = FakeWorld(cryLen)
        val r = w.play(s)
        assertTrue("진행 중이던 재생이 있어야 한다", w.events.none { it is PlaybackEnded })
        assertTrue("max_episode_play_ms ${r.maxEpisodePlayMs}", r.maxEpisodePlayMs >= 15_000)
        assertTrue("session_play_ms ${r.sessionPlayMs}", r.sessionPlayMs >= 15_000)
    }

    @Test fun `F19 F14-04·10은 세션이 하나, F14-11은 둘`() {
        fun sessions(id: String) = FakeWorld().also { assertTrue(id, it.play(scene(id)).completed) }
            .commands.mapNotNull { (it.second as? SaveSession)?.record?.id }.toSet().size
        assertEquals(1, sessions("F14-04"))
        assertEquals(1, sessions("F14-10"))
        assertEquals(2, sessions("F14-11"))
    }

    @Test fun `H3 녹음 시작이 2초 늦어도 경보 지연은 실제 시작부터 잰다`() {
        val a = scene("A")
        val base = FakeWorld(cryLen).play(a).alarmLatencyMs!!
        val late = FakeWorld(cryLen, audioDelayMs = 2_000).play(a).alarmLatencyMs!!
        assertTrue("지연 없음 $base, 2초 지연 $late", Math.abs(late - base) < 1_500)   // 예정 시각을 기준으로 재면 2초 더 길게 잰다
    }
}
