package kr.aigyeot.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// LlmCry.unavailable은 거짓이면 보내지 않고 참이면 "unavailable":true로 쓴다.
class LlmCryTest {
    private fun enc(c: LlmCry) = ProtocolJson.encodeToString(LlmCry.serializer(), c)

    @Test fun unavailableFalse_omitted() = assertEquals(false, "unavailable" in enc(LlmCry(2, 1)))
    @Test fun unavailableTrue_written() = assertTrue("\"unavailable\":true" in enc(LlmCry(null, 1, unavailable = true)), enc(LlmCry(null, 1, unavailable = true)))
}
