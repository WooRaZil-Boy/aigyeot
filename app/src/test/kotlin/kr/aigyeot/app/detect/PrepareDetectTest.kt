package kr.aigyeot.app.detect

import java.io.Closeable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// MicService 준비 단계: 판별층 JSON이 깨지거나 입력원·모델 준비가 실패하면 null(서비스는 running=true 없이 MIC_ERROR로 끝난다)이고, 먼저 만든 자원은 닫힌다.
class PrepareDetectTest {
    private class FakeSource : PcmSource {
        var closed = 0
        override fun start() {}
        override fun read(out: ShortArray) = PcmSource.END
        override fun isSilenced() = false
        override fun close() { closed++ }
    }
    private class FakeScorer : WindowScorer, Closeable {
        override fun score(window: ShortArray) = FloatArray(521)
        override fun close() {}
    }
    private val good = File("src/main/assets/cry_head24_rt.json").readText()

    @Test fun `판별층 JSON이 깨지면 입력원도 모델도 만들지 않는다`() {
        var made = 0
        assertNull(prepareDetect({ "{깨짐" }, { made++; FakeSource() }, { made++; FakeScorer() }))
        assertEquals(0, made)
    }

    @Test fun `assets를 못 열어도 null`() {
        assertNull(prepareDetect({ throw java.io.FileNotFoundException() }, { FakeSource() }, { FakeScorer() }))
    }

    @Test fun `모델 준비가 실패하면 입력원을 닫고 null`() {
        val src = FakeSource()
        assertNull(prepareDetect<FakeScorer>({ good }, { src }, { throw IllegalStateException("모델 없음") }))
        assertEquals(1, src.closed)
    }

    @Test fun `모두 되면 준비물을 돌려준다`() {
        assertNotNull(prepareDetect({ good }, { FakeSource() }, { FakeScorer() }))
    }
}
