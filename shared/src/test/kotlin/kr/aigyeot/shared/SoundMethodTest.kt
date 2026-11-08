package kr.aigyeot.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class SoundMethodTest {
    // 새 픽스처 파일 없이 report_summary 픽스처의 method만 바꿔 읽는다(iOS 시험에 영향이 없게)
    @Test fun unknownMethod_decodesAsUnknown() {
        val raw = java.io.File("src/test/resources/protocol/report_summary.json").readText().replace("\"method\":\"lullaby\"", "\"method\":\"harp\"")
        val e = ProtocolJson.decodeFromString(Envelope.serializer(), raw)
        val b = ProtocolJson.decodeFromJsonElement(ReportSummaryBody.serializer(), e.body)
        assertEquals(SoundMethod.UNKNOWN, b.timeline[0].sound!!.method)
        assertEquals(SoundMethod.UNKNOWN, b.timeline[0].plays[0].method)
        assertEquals(SoundMethod.UNKNOWN, b.methodStats!![0].method)
    }
}
