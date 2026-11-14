package kr.aigyeot.app.detect

import kr.aigyeot.engine.Params
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// 판별층 계산이 파이썬(rt_train.py 식)과 같은 값을 내는가. 기대값은 실제 cry_head24_rt.json으로 파이썬에서 계산했다.
class CryHeadTest {
    private val headFile = File("src/main/assets/cry_head24_rt.json")

    // 항상 같은 가짜 점수: 창 k의 클래스 c 점수 = ((7c + 13k + 3) mod 97) / 97 * 0.1
    private fun scores(k: Int) = FloatArray(521) { c -> ((c * 7 + k * 13 + 3) % 97) / 97f * 0.1f }

    @Test fun `파이썬과 같은 확률`() {
        val head = CryHead.parse(headFile.readText())
        val expected = listOf(0.5173621696, 0.6042965819, 0.3498476126)
        expected.forEachIndexed { i, e -> assertEquals(e, head.prob(listOf(scores(i), scores(i + 1), scores(i + 2))), 1e-5, "구간 $i") }
    }

    /** 판별기 문턱·구간 수·창 길이는 판단 코어 Params 하나. 판별층 JSON의 학습 문턱이 다르면 실패한다. */
    @Test fun detectorThresholdIsParams() {
        assertEquals(Params().alarmThreshold, CryHead.trainedThreshold(headFile.readText()), "판별층을 다시 학습해 문턱이 바뀌면 Params.alarmThreshold를 먼저 고친다")
        assertEquals(Params().yamnetHopMs, Detect.WINDOW_MS)
        assertEquals(Detect.WINDOW_SAMPLES * 1000L / Detect.SAMPLE_RATE, Detect.WINDOW_MS, "창 한 걸음은 15600표본 ÷ 16 kHz")
        val head = CryHead(intArrayOf(0), doubleArrayOf(0.0, 0.0), 0.0)   // 늘 p = 0.5
        fun alarms(p: Params) = SegmentTracker(head, p).let { t -> (1..4).mapNotNull { t.push(FloatArray(521), it * Detect.WINDOW_MS, 0) }.map { it.alarm } }
        assertEquals(listOf(false, true), alarms(Params(alarmThreshold = 0.5)), "Params 문턱 이상 두 구간이면 경보")
        assertEquals(listOf(false, false), alarms(Params(alarmThreshold = 0.51)))
        assertEquals(listOf(false, false), alarms(Params(alarmThreshold = 0.5, alarmSegments = 3)), "구간 수도 Params")
    }
}
