package kr.aigyeot.app.ui

import kr.aigyeot.app.link.LinkStatus
import kr.aigyeot.app.link.LinkedPhone
import kr.aigyeot.app.link.db.SongSelectionEntity
import kr.aigyeot.app.store.db.SessionEntity
import kr.aigyeot.app.store.db.SongEntity
import kr.aigyeot.engine.AskButton
import kr.aigyeot.engine.DAY_MS
import kr.aigyeot.engine.Engine
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.FirstTry
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.OpenTrigger
import kr.aigyeot.engine.SessionState
import kr.aigyeot.engine.Stage
import kr.aigyeot.engine.fresh
import kr.aigyeot.shared.MethodStat
import kr.aigyeot.shared.SoundMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val T0 = 1_800_000_000_000L

// 화면이 하던 계산을 옮긴 순수 매핑 함수(U1). 기대값은 옮기기 전과 같다.
class UiStateMappingTest {
    private fun fresh() = EngineState.fresh(1, null, emptyList(), emptyList(), null, 0)

    /** C303 ⑤(B7): 홈 화면 물어보기 버튼은 판단 코어가 AskButton을 받는 조건(canAsk) 그대로. 말하는 중·통화 중에는 켜지 않는다(눌러도 버려지지 않게). */
    @Test fun c303_b7_askEnabledFromEngine() {
        val f = fresh()
        val parked = f.copy(car = f.car.copy(gear = Gear.P, micRunning = true))
        assertTrue(askEnabled(parked))
        assertFalse(askEnabled(parked.copy(speech = parked.speech.copy(speaking = "u1"))), "말하는 중이면 판단 코어가 버린다")
        assertFalse(askEnabled(parked.copy(car = parked.car.copy(inCall = true))), "통화 중")
        assertFalse(askEnabled(parked.copy(ign = parked.ign.copy(suspendedAt = T0))), "Suspend 중")
        assertFalse(askEnabled(null))
        val asked = Engine().step(parked, AskButton(T0)).state
        assertEquals(askEnabled(parked), asked.speech.qa != null, "켜진 버튼은 판단 코어가 듣기를 연다")
    }

    @Test fun stats_onlyCurrentSongAndLast28Days() {
        val third = Method(MethodKind.SONG, "b")
        val methods = listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.WOMB), Method(MethodKind.LULLABY), third)
        val d = DAY_MS
        val h = listOf(
            *Array(3) { FirstTry(T0 - it * d, Method(MethodKind.SONG, "a"), true) },          // 다른 곡의 성공
            *Array(2) { FirstTry(T0 - it * d, third, false) },                                // 지금 곡은 2번뿐
            *Array(3) { FirstTry(T0 - 29 * d - it, Method(MethodKind.LULLABY), true) },       // 29일 전
        )
        val out = stats(h, methods, T0)
        assertEquals("부모 노래: 아직 모름", out.last(), "다른 곡의 성공이 섞이지 않는다")
        assertEquals("자장가: 아직 모름", out[2], "29일 전 시도는 세지 않는다")
        assertEquals(3, stats(h, methods.dropLast(1), T0).size, "③이 없으면 부모 노래 줄을 뺀다")
        val more = h + FirstTry(T0, third, true)
        assertEquals("부모 노래: 3번 중 1번", stats(more, methods, T0).last())
    }

    @Test fun homeUiState_beforeDrive_andMicAndFeeding() {
        val link = LinkStatus()
        val u = homeUiState(fresh(), T0, link)
        assertEquals("출발 전이에요", u.status)
        assertEquals("듣기 꺼짐", u.micText)
        assertEquals("마지막 수유: 기록 없음", u.feeding)
        assertFalse(u.pausesWarning); assertFalse(u.confirmShown); assertFalse(u.askEnabled)
        assertEquals(link, u.link)
        val s = fresh().let { it.copy(car = it.car.copy(micRunning = true), feedingStart = T0 - 90 * 60_000) }
        assertEquals("듣기 켜짐", homeUiState(s, T0, link).micText)
        assertTrue(homeUiState(s, T0, link).feeding.endsWith("(1시간 30분 전)"))
        assertEquals("출발 전이에요", homeUiState(null, T0, link).status)
        assertEquals("마지막 수유: 기록 없음", homeUiState(null, T0, link).feeding)
    }

    @Test fun songRows_markThirdAndSelected() {
        fun song(id: String, name: String?, dur: Double?) = SongEntity(id, name, null, "m4a", dur, null, null, "ACTIVE", null, 1, 1)
        val rows = songRows(listOf(song("a", "자장", 61.7), song("b", null, null)), SongSelectionEntity("b", 1), listOf(Method(MethodKind.SONG, "a")))
        assertEquals(listOf("자장 · 61초 · 실험에 쓰는 곡", "이름 없는 노래"), rows.map { it.label })
        assertEquals(listOf(false, true), rows.map { it.selected })
        assertEquals(listOf(false, false), songRows(listOf(song("a", "x", null), song("b", "y", null)), null, emptyList()).map { it.selected })
    }

    @Test fun reportRows_openSessionDisabled_simulationMarked() {
        fun s(id: String, end: Long?, scene: String?) = SessionEntity(id, T0, end, "T", null, null, 0, 0, false, "[]", null, null, false, scene, 10)
        val rows = reportRows(listOf(s("a", null, null), s("b", T0 + 60_000, "sc")))
        assertEquals(listOf(false, true), rows.map { it.enabled })
        assertTrue(rows[0].label.endsWith(" · 주행 중"))
        assertTrue(rows[1].label.contains(" ~ ") && rows[1].label.endsWith(" · 시뮬레이션"))
        assertEquals(listOf("a", "b"), rows.map { it.id })
    }

    @Test fun methodStatLines_underMinShown_saysUnknown() {
        val lines = methodStatLines(listOf(MethodStat(SoundMethod.LULLABY, "자장가", 2, 2), MethodStat(SoundMethod.WOMB, "뱃속 소리", 1, 4)))
        assertEquals(listOf("자장가: 아직 모름", "뱃속 소리: 4번 중 1번 그침"), lines)   // 3번 미만이면 아직 모름(statMinShown)
        assertEquals(emptyList<String>(), methodStatLines(null))
    }

    @Test fun connectUiState_remainingClampedAtZero_andPhoneLabel() {
        assertNull(connectUiState(LinkStatus(), T0).remainingS)
        assertEquals(90L, connectUiState(LinkStatus(pairExpiresAt = T0 + 90_000), T0).remainingS)
        assertEquals(0L, connectUiState(LinkStatus(pairExpiresAt = T0 - 5_000), T0).remainingS)
        assertEquals("남은 시간 1분 30초", remainingText(90))
        assertEquals("함께 타는 보호자", phoneLabel(LinkedPhone("d", "owner", null)))
        assertTrue(phoneLabel(LinkedPhone("d", "guardian", T0)).startsWith("다른 보호자 · 마지막 연락 "))
    }

    /** C356: HANDOFF 홈 상태는 안내 여부를 말하지 않는다(쉼터 안내 없이도 들어간다). */
    @Test fun c356_handoffStatusText_doesNotClaimGuidance() {
        val f = fresh()
        val ses = SessionState(
            id = "S1", openedAt = T0, trigger = OpenTrigger.DOOR_THEN_D, methods = emptyList(), stage = Stage.Handoff(1),
            episode = null, playedMs = 0, s17Said = false, micNoticeArmed = false, recordsCleared = false,
        )
        assertEquals("자동 달래기를 멈췄어요", statusText(f.copy(session = ses)))
    }

    @Test fun route_keysRoundTrip() {
        for (r in listOf(Route.Home, Route.Setup, Route.Connect, Route.Songs, Route.Reports, Route.Report("S1"), Route.Demo, Route.DemoScenes)) assertEquals(r, Route.parse(r.key))
        assertEquals(Route.Home, Route.parse("모르는 값"))
    }
}
