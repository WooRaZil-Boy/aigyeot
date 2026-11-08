package kr.aigyeot.app.detect

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 구간 나누기·경보 규칙. 판별층은 클래스 0의 3창 평균만 쓰는 작은 가짜다: p = sigmoid(-10 + 30 * mean). p ≥ 0.7 ⇔ mean ≥ 0.362
class SegmentTrackerTest {
    private val head = CryHead(intArrayOf(0), doubleArrayOf(30.0, 0.0), -10.0)   // 문턱은 Params.alarmThreshold 0.7(C303 ④)
    private fun win(s: Float) = FloatArray(521).also { it[0] = s }
    private fun p(vararg w: Float) = 1 / (1 + exp(-(-10 + 30 * w.average())))

    // 창 점수들을 0.975초 간격으로 밀어 넣고 나온 구간을 모은다
    private fun run(t: SegmentTracker, vararg s: Float, from: Int = 1) =
        s.toList().mapIndexedNotNull { i, v -> val e = (from + i) * 975L; t.push(win(v), e, e) }

    @Test fun `3창이 차야 첫 구간이 나온다`() {
        val t = SegmentTracker(head)
        assertNull(t.push(win(0f), 975, 975))
        assertNull(t.push(win(0f), 1950, 1950))
        val s = assertNotNull(t.push(win(0f), 2925, 2925))
        assertEquals(0L, s.start); assertEquals(2925L, s.end); assertFalse(s.alarm)
    }

    @Test fun `구간은 창 하나씩 밀리고 시각은 창 끝에서 센다`() {
        val a = run(SegmentTracker(head), 0f, 0f, 0f, 0f)
        assertEquals(2, a.size)
        assertEquals(975L, a[1].start); assertEquals(3900L, a[1].end); assertEquals(3900L, a[1].at)
    }

    @Test fun `경보는 이 구간과 바로 앞 구간이 모두 문턱 이상일 때`() {
        val a = run(SegmentTracker(head), 0f, 0f, 1f, 1f, 1f, 1f)
        assertEquals(listOf(p(0f, 0f, 1f), p(0f, 1f, 1f), p(1f, 1f, 1f), p(1f, 1f, 1f)), a.map { it.p })
        // 둘째 구간부터 문턱을 넘지만 앞 구간(0.5)이 낮아 경보는 셋째부터
        assertEquals(listOf(false, false, true, true), a.map { it.alarm })
    }

    @Test fun `한 구간만 높으면 경보가 아니다`() {
        val a = run(SegmentTracker(head), 0f, 1f, 0f, 1f, 0f) // 구간 평균 .33, .67, .33
        assertEquals(listOf(false, true, false), a.map { it.p >= 0.7 })
        assertEquals(listOf(false, false, false), a.map { it.alarm })
    }

    @Test fun `reset 뒤에는 앞 구간을 잇지 않는다`() {
        val t = SegmentTracker(head)
        assertEquals(listOf(false, true), run(t, 1f, 1f, 1f, 1f).map { it.alarm })
        t.reset()
        val a = run(t, 1f, 1f, 1f, from = 5)
        assertEquals(1, a.size); assertTrue(a[0].p >= 0.7); assertFalse(a[0].alarm)
    }

    @Test fun `짧은 입력은 앞 창을 반복해 한 구간을 만든다`() {
        val two = SegmentTracker(head)
        run(two, 1f, 0f)
        val s = assertNotNull(two.flushShort(2000))
        assertEquals(p(1f, 0f, 1f), s.p, 1e-12) // 1·2·1번 창
        assertEquals(0L, s.start); assertEquals(1950L, s.end) // 시각은 실제 입력 범위(점수만 반복)
        assertFalse(s.alarm)
        assertNull(two.flushShort(2000)) // 한 번뿐

        val one = SegmentTracker(head)
        run(one, 1f)
        assertEquals(p(1f, 1f, 1f), assertNotNull(one.flushShort(1000)).p, 1e-12)

        val full = SegmentTracker(head)
        run(full, 0f, 0f, 0f)
        assertNull(full.flushShort(3000)) // 3창이 있으면 채우지 않는다
    }

    @Test fun `injectedCryAt은 구간에 실린다`() {
        val t = SegmentTracker(head)
        val s = (1..3).mapNotNull { t.push(win(0f), it * 975L, it * 975L, injectedCryAt = 123L) }.single()
        assertEquals(123L, s.injectedCryAt)
    }
}
