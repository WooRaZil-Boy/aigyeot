package kr.aigyeot.app.detect

import kr.aigyeot.engine.Event
import kr.aigyeot.engine.InputLost
import kr.aigyeot.engine.PauseReason.DETECTOR_TIMEOUT
import kr.aigyeot.engine.PauseReason.MIC_ERROR
import kr.aigyeot.engine.PauseReason.SILENCED
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// 0단계 감지 연결점: 호스트의 C82 대조(runningPauses·assumePausedNow)와 exec STT의 pcmTap
class MicServiceHooksTest {
    private val head = CryHead(intArrayOf(0), doubleArrayOf(30.0, 0.0), -10.0)
    private val events = CopyOnWriteArrayList<Event>()
    private fun detector() = CryDetector(head, { FloatArray(521) }, { 0L }, { events += it })

    @AfterTest fun reset() { MicService.running = null; MicService.pendingPauses = emptySet(); MicService.pcmTap = null }

    @Test fun `서비스가 없으면 runningPauses는 null이고 assumePausedNow는 다음 서비스 몫으로 남긴다`() {
        assertNull(MicService.runningPauses())
        MicService.assumePausedNow(setOf(SILENCED))
        assertEquals(setOf(SILENCED), MicService.pendingPauses)
    }

    @Test fun `돌고 있는 감지기에 엔진에만 있는 사유를 넣는다`() {
        val d = detector().also { it.assumePaused(setOf(MIC_ERROR)) }
        MicService.running = d
        assertEquals(setOf(MIC_ERROR), MicService.runningPauses())
        MicService.assumePausedNow(setOf(SILENCED, DETECTOR_TIMEOUT))
        assertEquals(setOf(MIC_ERROR, SILENCED, DETECTOR_TIMEOUT), d.pauses())
        assertEquals(emptySet(), MicService.pendingPauses, "돌고 있으면 pendingPauses는 건드리지 않는다")
        assertEquals(emptyList(), events, "이어받은 사유로 InputLost를 다시 내지 않는다")
    }

    @Test fun `pcmTap은 읽은 창을 받고 받는 쪽이 던져도 감지는 이어진다`() {
        val got = mutableListOf<Int>()
        MicService.pcmTap = { pcm, n -> got += n; check(pcm.size >= n); error("받는 쪽 오류") }
        var left = 2
        val source = object : PcmSource {
            override fun start() {}
            override fun read(out: ShortArray) = if (left-- > 0) { out.fill(100); out.size } else PcmSource.END
            override fun isSilenced() = false
            override fun close() {}
        }
        var ended: Boolean? = null
        DetectorRunner(source, detector()) { ended = it }.apply { start(); join() }
        assertEquals(listOf(Detect.WINDOW_SAMPLES, Detect.WINDOW_SAMPLES), got)
        assertEquals(false, ended, "파일 끝까지 읽었다(오류로 멈추지 않음)")
        assertEquals(emptyList(), events.filterIsInstance<InputLost>())
    }
}
