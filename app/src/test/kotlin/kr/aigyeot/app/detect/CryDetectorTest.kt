package kr.aigyeot.app.detect

import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.InputLost
import kr.aigyeot.engine.InputRestored
import kr.aigyeot.engine.PauseReason.DETECTOR_TIMEOUT
import kr.aigyeot.engine.PauseReason.MIC_ERROR
import kr.aigyeot.engine.PauseReason.SILENCED
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CryDetectorTest {
    // 점수 s인 창 → 구간 평균 s → p = sigmoid(-10 + 30 s)
    private val head = CryHead(intArrayOf(0), doubleArrayOf(30.0, 0.0), -10.0)
    private var now = 0L
    private var score = 1f // 가짜 YAMNet 점수(클래스 0)
    private val events = CopyOnWriteArrayList<Event>()
    private val detector = CryDetector(head, { FloatArray(521).also { it[0] = score } }, { now }, { events += it })

    private val sound = ShortArray(Detect.WINDOW_SAMPLES) { 100 }
    private val zeros = ShortArray(Detect.WINDOW_SAMPLES)

    private fun window(w: ShortArray = sound, silenced: Boolean = false) { now += Detect.WINDOW_MS; detector.onWindow(w, silenced) }
    private fun lost() = events.filterIsInstance<InputLost>().map { it.reason }
    private fun restored() = events.filterIsInstance<InputRestored>().map { it.reason }
    private fun segs() = events.filterIsInstance<CrySegment>()

    @Test fun `3창이 모이면 구간을 내고 울음이 이어지면 경보`() {
        detector.start()
        repeat(4) { window() }
        assertEquals(2, segs().size)
        assertEquals(listOf(false, true), segs().map { it.alarm })
        assertEquals(now, segs().last().at)
        assertEquals(now - 3 * Detect.WINDOW_MS, segs().last().start)
    }

    @Test fun `무음 rms 0점0이 3초 이어지면 SILENCED, 소리가 돌아오면 복귀`() {
        detector.start()
        window(); window(); window()
        window(zeros); window(zeros); window(zeros)
        assertEquals(emptyList(), lost(), "2.9초는 아직") // 3창 46800표본 < 48000
        window(zeros)
        assertEquals(listOf(SILENCED), lost())
        window(zeros); window(zeros)
        assertEquals(listOf(SILENCED), lost(), "같은 사유는 한 번만")
        val before = segs().size
        window(); // 복귀
        assertEquals(listOf(SILENCED), restored())
        assertEquals(before, segs().size, "무음 동안과 복귀 창 하나로는 구간이 없다")
        window(); window()
        assertEquals(before + 1, segs().size)
    }

    @Test fun `무음 동안에는 구간을 내지 않는다`() {
        detector.start()
        repeat(14) { window(zeros) }
        assertEquals(emptyList(), segs(), "SILENCED 전 3창(2.9초)도 구간을 내지 않는다(그침으로 확정되면 되돌릴 수 없다)")
        assertEquals(listOf(SILENCED), lost())
    }

    @Test fun `rms 0점0 창이 낀 구간은 내지 않고 추적기를 비운다`() {
        detector.start()
        window(); window(); window(zeros); window(); window()
        assertEquals(emptyList(), segs(), "0 창 앞의 창 2개와 뒤의 2개를 이어 구간을 만들지 않는다")
        window()
        assertEquals(1, segs().size)
    }

    @Test fun `무음이 3초 차기 전에 판별기 무응답으로 치지 않는다`() {
        detector.start()
        window(); window(); window()
        window(zeros); window(zeros); window(zeros)
        detector.checkTimeout()
        assertEquals(emptyList(), lost())
    }

    @Test fun `창 끝쪽 0이 이어져도 3초가 안 되면 무음이 아니다`() {
        detector.start()
        val tail = ShortArray(Detect.WINDOW_SAMPLES) { if (it < 100) 5 else 0 }
        window(tail); window(zeros); window(zeros) // 15500 + 15600 + 15600 = 46700
        assertEquals(emptyList(), lost())
        window(zeros)
        assertEquals(listOf(SILENCED), lost())
    }

    @Test fun `isClientSilenced가 참이면 바로 SILENCED, 풀리면 복귀`() {
        detector.start()
        window(silenced = true)
        assertEquals(listOf(SILENCED), lost())
        window(silenced = true)
        assertEquals(listOf(SILENCED), lost())
        window()
        assertEquals(listOf(SILENCED), restored())
    }

    @Test fun `무음 처리된 동안의 창은 앞뒤 구간에 섞이지 않는다`() {
        detector.start()
        window(); window()
        window(silenced = true)
        window(); window()
        assertEquals(emptyList(), segs(), "복귀 뒤 창이 2개뿐이다")
        window()
        assertEquals(1, segs().size)
    }

    @Test fun `판별기 무응답은 구간이 3초 넘게 안 올 때`() {
        detector.start() // t=0, 첫 구간 기준은 2.925초
        now = 5_925; detector.checkTimeout()
        assertEquals(emptyList(), lost(), "첫 구간 기준 + 3000ms까지는 정상")
        now = 5_926; detector.checkTimeout()
        assertEquals(listOf(DETECTOR_TIMEOUT), lost())
        now = 9_000; detector.checkTimeout()
        assertEquals(listOf(DETECTOR_TIMEOUT), lost(), "한 번만")
    }

    @Test fun `구간이 나온 뒤에는 그 시각부터 3초를 잰다`() {
        detector.start()
        repeat(3) { window() } // 구간 at = 2925
        now = 2_925 + 3_000; detector.checkTimeout()
        assertEquals(emptyList(), lost())
        now += 1; detector.checkTimeout()
        assertEquals(listOf(DETECTOR_TIMEOUT), lost())
    }

    @Test fun `무응답 뒤 구간이 다시 오면 복귀하고 앞 창은 잇지 않는다`() {
        detector.start()
        window(); window(); window()
        now += 3_500; detector.checkTimeout()
        assertEquals(listOf(DETECTOR_TIMEOUT), lost())
        window(); window()
        assertEquals(emptyList(), restored(), "구간이 아직 없다")
        window()
        assertEquals(listOf(DETECTOR_TIMEOUT), restored())
        val i = events.indexOfFirst { it is InputRestored }
        assertIs<CrySegment>(events[i + 1]) // 복귀가 구간보다 먼저
        assertEquals(2, segs().size)
        assertTrue(!segs().last().alarm, "끊기기 전 구간과 이어 경보를 내지 않는다")
    }

    @Test fun `다른 사유로 멈춘 동안에는 무응답으로 치지 않는다`() {
        detector.start()
        window(silenced = true)
        now += 10_000; detector.checkTimeout()
        assertEquals(listOf(SILENCED), lost())
    }

    @Test fun `읽기 오류는 MIC_ERROR 한 번, 새 입력이 오면 복귀`() {
        detector.start()
        detector.onMicError(); detector.onMicError()
        assertEquals(listOf(MIC_ERROR), lost())
        window()
        assertEquals(listOf(MIC_ERROR), restored())
    }

    @Test fun `서비스가 다시 뜬 뒤 assumePaused로 시작하면 첫 창에서 복귀`() {
        detector.assumePaused(setOf(MIC_ERROR)); detector.start()
        assertEquals(emptyList(), lost()) // 끊김은 앞 서비스가 이미 냈다
        window()
        assertEquals(listOf(MIC_ERROR), restored())
    }

    @Test fun `이어받은 사유는 사유마다 실제 입력·구간이 돌아올 때 복귀`() {
        detector.assumePaused(setOf(SILENCED, DETECTOR_TIMEOUT, MIC_ERROR)); detector.start()
        assertEquals(setOf(SILENCED, DETECTOR_TIMEOUT, MIC_ERROR), detector.pauses())
        window()
        assertEquals(setOf(SILENCED, MIC_ERROR), restored().toSet(), "소리가 오면 MIC_ERROR·SILENCED")
        assertEquals(setOf(DETECTOR_TIMEOUT), detector.pauses())
        window(); window()
        assertEquals(setOf(SILENCED, MIC_ERROR, DETECTOR_TIMEOUT), restored().toSet(), "구간이 나오면 DETECTOR_TIMEOUT")
        assertEquals(emptySet(), detector.pauses())
        assertEquals(1, segs().size)
    }

    @Test fun `이어받은 SILENCED는 입력이 아직 무음이면 그대로`() {
        detector.assumePaused(setOf(SILENCED)); detector.start()
        window(silenced = true)
        assertEquals(emptyList(), restored()); assertEquals(emptyList(), lost())
        assertEquals(setOf(SILENCED), detector.pauses())
    }

    @Test fun `shutdown 뒤에는 점수 계산 중이던 결과도 내지 않는다`() {
        lateinit var d: CryDetector
        d = CryDetector(head, { d.shutdown(); FloatArray(521).also { it[0] = 1f } }, { now }, { events += it })
        d.start()
        repeat(5) { now += Detect.WINDOW_MS; d.onWindow(sound, false) }
        assertEquals(emptyList(), events)
        d.onMicError() // 러너가 보내는 오류는 종료 뒤에는 버린다
        assertEquals(emptyList(), lost())
        d.onMicError(force = true) // onDestroy의 마지막 알림만 낸다
        assertEquals(listOf(MIC_ERROR), lost())
    }

    @Test fun `injectedCryAt은 구간에 실린다`() {
        detector.start()
        detector.injectedCryAt = 42L
        repeat(3) { window() }
        assertEquals(42L, segs().single().injectedCryAt)
    }

    @Test fun `입력이 끝났을 때 창이 2개뿐이면 채운 구간 하나`() {
        detector.start(); window(); window()
        detector.finish()
        assertEquals(1, segs().size)
        assertEquals(false, segs().single().alarm)
    }

    // 러너: 가짜 입력원
    private class FakeSource(private val reads: MutableList<Int>, val silenced: Boolean = false, val failStart: Boolean = false, val onRead: (Int) -> Unit = {}) : PcmSource {
        var closed = false
        var n = 0
        override fun start() { if (failStart) error("열기 실패") }
        override fun read(out: ShortArray): Int { onRead(++n); val r = reads.removeFirstOrNull() ?: PcmSource.END; if (r > 0) out.fill(100); return r }
        override fun isSilenced() = silenced
        override fun close() { closed = true }
    }

    @Test fun `러너 - 파일 끝은 정상 종료`() {
        val src = FakeSource(mutableListOf(15600, 15600, 15600, 15600))
        var unexpected: Boolean? = null
        val r = DetectorRunner(src, detector) { unexpected = it }
        r.start(); r.join()
        assertEquals(false, unexpected)
        assertEquals(2, segs().size)
        assertTrue(src.closed)
        assertEquals(emptyList(), lost())
    }

    @Test fun `러너 - 읽기 오류와 열기 실패는 MIC_ERROR`() {
        var unexpected: Boolean? = null
        DetectorRunner(FakeSource(mutableListOf(15600, PcmSource.ERROR)), detector) { unexpected = it }.apply { start(); join() }
        assertEquals(true, unexpected)
        assertEquals(listOf(MIC_ERROR), lost())

        events.clear(); unexpected = null
        val fresh = CryDetector(head, { FloatArray(521) }, { now }, { events += it })
        DetectorRunner(FakeSource(mutableListOf(), failStart = true), fresh) { unexpected = it }.apply { start(); join() }
        assertEquals(true, unexpected)
        assertEquals(listOf(MIC_ERROR), lost())
    }

    @Test fun `러너 - stop 뒤에는 알리지 않고 입력원·판별기를 놓고 결과를 버린다`() {
        var ended = false; var closes = 0
        lateinit var runner: DetectorRunner
        val src = FakeSource(MutableList(10) { 15600 }, onRead = { if (it == 4) runner.stop() }) // 4번째 읽기 도중 멈춤
        runner = DetectorRunner(src, detector, onClose = { closes++ }) { ended = true }
        runner.start(); runner.join()
        assertEquals(false, ended)
        assertEquals(1, closes); assertTrue(src.closed)
        assertEquals(1, segs().size, "멈춘 뒤 읽은 4번째 창은 버린다(앞 3창이 낸 구간 하나만)")
        assertEquals(emptyList(), restored())
    }

    @Test fun `무응답 복귀 구간 뒤에는 유예 없이 3초 뒤에 다시 잡는다`() {
        detector.start()
        window(); window(); window()
        now += 3_500; detector.checkTimeout() // 무응답
        window(); window(); window() // 복귀 구간, at = now
        assertEquals(listOf(DETECTOR_TIMEOUT), restored())
        val at = segs().last().at
        now = at + 3_000; detector.checkTimeout()
        assertEquals(listOf(DETECTOR_TIMEOUT), lost())
        now = at + 3_001; detector.checkTimeout()
        assertEquals(listOf(DETECTOR_TIMEOUT, DETECTOR_TIMEOUT), lost())
    }

    @Test fun `이어받은 SILENCED는 전부 0인 창으로는 풀리지 않고 소리가 오면 복귀`() {
        detector.assumePaused(setOf(SILENCED)); detector.start()
        window(zeros); window(zeros)
        assertEquals(emptyList(), restored())
        assertEquals(setOf(SILENCED), detector.pauses())
        window()
        assertEquals(listOf(SILENCED), restored())
    }

    @Test fun `짧은 입력의 마지막 구간도 무응답 복귀를 먼저 낸다`() {
        detector.assumePaused(setOf(DETECTOR_TIMEOUT)); detector.start()
        window(); window()
        detector.finish()
        assertEquals(listOf(DETECTOR_TIMEOUT), restored())
        val i = events.indexOfFirst { it is InputRestored }
        assertIs<CrySegment>(events[i + 1])
        assertEquals(emptySet(), detector.pauses())
    }

    private class BlockingSource : PcmSource {
        val gate = CountDownLatch(1)
        val reading = CountDownLatch(1)
        @Volatile var closed = false
        override fun start() {}
        override fun read(out: ShortArray): Int { reading.countDown(); gate.await(); return PcmSource.ERROR }
        override fun isSilenced() = false
        override fun cancel() { gate.countDown() }
        override fun close() { closed = true }
    }

    @Test fun `러너 - stop은 막힌 읽기를 깨우고 판별기는 읽기가 끝난 뒤 닫는다`() {
        val src = BlockingSource()
        var closes = 0; var ended = false
        val r = DetectorRunner(src, detector, onClose = { assertTrue(src.closed); closes++ }) { ended = true }
        r.start()
        src.reading.await()
        r.stop(); r.join()
        assertEquals(1, closes); assertEquals(false, ended)
        assertEquals(emptyList(), lost(), "일부러 멈춘 것은 MIC_ERROR가 아니다")
    }

    @Test fun `복귀하는 창의 앞쪽 0까지 합쳐 3초가 되면 SILENCED 뒤 복귀`() {
        detector.start()
        window(zeros); window(zeros); window(zeros) // 46800표본
        assertEquals(emptyList(), lost())
        window(ShortArray(Detect.WINDOW_SAMPLES) { if (it < 1_201) 0 else 100 }) // 46800 + 1201 = 48001
        assertEquals(listOf(SILENCED), lost())
        assertEquals(listOf(SILENCED), restored())
        assertEquals(emptyList(), segs())
    }

    @Test fun `앞쪽 0이 모자라 3초가 안 되면 무음이 아니다`() {
        detector.start()
        window(zeros); window(zeros); window(zeros)
        window(ShortArray(Detect.WINDOW_SAMPLES) { if (it < 1_199) 0 else 100 }) // 47999
        assertEquals(emptyList(), lost())
    }

    @Test fun `가끔 끼는 0 창이 무응답 감시를 미루지 못한다`() {
        detector.start()
        repeat(3) { window() } // 구간 하나(at 2925)
        val triggered = (1..20).any { // [0 창, 일반 창, 일반 창]을 되풀이: 추적기가 계속 비워져 구간이 안 나온다
            val w = if (it % 3 == 1) zeros else sound
            window(w); detector.checkTimeout()
            lost().contains(DETECTOR_TIMEOUT)
        }
        assertTrue(triggered, "구간이 없는데 20창(19초) 동안 DETECTOR_TIMEOUT이 안 나왔다")
        assertEquals(1, segs().size)
        assertEquals(listOf(DETECTOR_TIMEOUT), lost())
    }

    @Test fun `0 창 하나 뒤 입력이 끊기면 확인 대기가 만료된 뒤 무응답으로 친다`() {
        detector.start()
        repeat(3) { window() } // 구간 at 2925
        window(zeros) // 시작 2925, 만료 5925. 이후 표본이 오지 않는다
        now = 5_925; detector.checkTimeout()
        assertEquals(emptyList(), lost(), "만료 전까지는 무음 확인 중")
        now = 5_926; detector.checkTimeout()
        assertEquals(listOf(DETECTOR_TIMEOUT), lost())
    }

    @Test fun `복귀 유예는 첫 정상 구간을 받으면 끝난다`() {
        detector.assumePaused(setOf(MIC_ERROR, SILENCED)); detector.start()
        window(); window(); window() // 975ms에 두 사유 복귀(유예 3900), 2925ms에 구간
        assertEquals(setOf(MIC_ERROR, SILENCED), restored().toSet())
        assertEquals(1, segs().size)
        now = 5_925; detector.checkTimeout()
        assertEquals(emptyList(), lost())
        now = 5_926; detector.checkTimeout()
        assertEquals(listOf(DETECTOR_TIMEOUT), lost(), "6901이 아니라 구간 시각 + 3초")
    }
}
