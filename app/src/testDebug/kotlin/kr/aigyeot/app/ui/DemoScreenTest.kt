package kr.aigyeot.app.ui

import kr.aigyeot.app.scenario.RunRow
import kr.aigyeot.app.scenario.ScenarioProgress
import kr.aigyeot.app.scenario.SceneResults
import kr.aigyeot.app.scenario.SceneInfo
import kr.aigyeot.app.scenario.parseScene
import kr.aigyeot.app.scenario.sceneInfo
import kr.aigyeot.engine.CrySegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

// 시연·측정 화면(debug 빌드 전용)이 계산하는 값
class DemoScreenTest {
    @Test fun demoTexts_keepCurrentWording() {
        assertEquals("A 2/5번 · 37초", progressText(ScenarioProgress("A", 2, 5, 37.9, null, null)))
        assertEquals("C 1/1번 · 3초 · 어긋남 t=12.0", progressText(ScenarioProgress("C", 1, 1, 3.0, 12.0, "x")))
        assertEquals("반복 장면 기본", repeatText(null))
        assertEquals("반복 3", repeatText(3))
    }

    @Test fun sceneInfo_titleAndAudioFileNames() {
        val a = sceneInfo("A", parseScene(File("src/debug/assets/scenario/A.json").readText()))
        assertEquals(SceneInfo("A", "등원 20분: 출발 5분 뒤 울음, 1차에 그침", listOf("stop_40s.pcm"), 5), a)
        // 반복 단추는 그 장면 파일의 repeat에서 한 칸(기본 1인 장면에서 줄이면 1, 늘리면 2)
        assertEquals(1, repeatDown(null, 1)); assertEquals(2, repeatUp(null, 1)); assertEquals(4, repeatDown(null, 5)); assertEquals(3, repeatDown(4, 1))
        assertEquals(SceneInfo("X", null, emptyList()), sceneInfo("X", null))   // 읽지 못한 파일
    }

    private fun row(latMs: Long?) = RunRow(1, 1, true, null, latMs, null, null, false, 0, 0, 0, 0, null, 0, 0, null, null, emptyList())

    @Test fun median_oddEvenEmpty() {
        assertEquals(2_400.0, median(listOf(3_100L, 2_400L, 1_900L))!!, 0.0)
        assertEquals(2_250.0, median(listOf(2_600L, 1_900L))!!, 0.0)
        assertNull(median(emptyList()))
    }

    /** /proc/self/stat: 이름에 빈칸·괄호가 있어도 utime(14)+stime(15)을 센다. 증가분 → 코어 하나 기준 %. */
    @Test fun cpu_ticksFromStat_andPercentOfOneCore() {
        assertEquals(325L, cpuTicks("1234 (kr.aigyeot (x) app) S 1 1234 0 0 -1 4194624 100 0 0 0 250 75 0 0 20 0 30 0 99"))
        assertNull(cpuTicks("garbage"))
        assertEquals(5.0, cpuPercent(ProcSample(100, 0, null), ProcSample(110, 2_000, null), hz = 100)!!, 1e-9)   // 2초에 0.1초 CPU
        assertNull(cpuPercent(ProcSample(null, 0, null), ProcSample(110, 2_000, null), 100))
        assertNull(cpuPercent(ProcSample(100, 2_000, null), ProcSample(110, 2_000, null), 100))   // 같은 시각
    }

    @Test fun metrics_realValuesOrDash() {
        val none = metrics(null, null, null)
        assertEquals(listOf("보통 감지 지연", "회차 기준", "CPU", "메모리"), none.map { it.name })
        assertEquals(listOf("—", "3.6초 이하", "—", "—"), none.map { it.value })
        assertEquals(listOf("", "모든 장면 합쳐 보통 3초 이하", "예산 5% 미만", "예산 400MB 이하"), none.map { it.sub })
        val m = metrics(SceneResults("A", listOf(row(2_400), row(null), row(1_800))), 3.14, 217_600)
        assertEquals("2.1초", m[0].value)   // 경보가 난 회차 둘의 중앙값
        assertEquals("A 2/3번 · 최대 2.4초", m[0].sub)   // C394: 회차 기준과 비교할 가장 늦은 회차
        assertEquals("A 1/1번 · 최대 3.7초", metrics(SceneResults("A", listOf(row(3_601))), null, null)[0].sub)   // 상한과 비교하는 값이라 올림(3.6초로 보이지 않게)
        assertEquals("E 0/1번", metrics(SceneResults("E", listOf(row(null))), null, null)[0].sub)
        assertEquals("3.1%", m[2].value)
        assertEquals("213MB", m[3].value)   // 217600KB / 1024 반올림
        assertEquals("—", metrics(SceneResults("E", listOf(row(null))), null, null)[0].value)   // 울음 없는 장면
    }

    @Test fun prob_lastMinuteOnly_rightEdgeIsNewest() {
        fun seg(endS: Long, p: Double) = CrySegment(endS * 1000, endS * 1000 - 2_925, endS * 1000, p, false)
        val segs = recentSegments(listOf(seg(0, 0.1), seg(30, 0.9), seg(90, 0.4)))
        assertEquals(listOf(30_000L, 90_000L), segs.map { it.end })   // 90초 기준 60초 밖(0초)은 버린다
        val pts = probPoints(segs)
        assertEquals(listOf(0f, 1f), pts.map { it.x })
        assertEquals(listOf(0.9f, 0.4f), pts.map { it.p })
        assertEquals(emptyList<ProbPoint>(), probPoints(emptyList()))
    }
}
