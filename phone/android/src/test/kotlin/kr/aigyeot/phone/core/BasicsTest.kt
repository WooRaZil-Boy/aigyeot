package kr.aigyeot.phone.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BasicsTest {
    @Test fun 연결429_잠금은_RetryAfter를_따르고_없으면_10초() {
        assertEquals(1_000_000L + 30_000, pairLockUntil(1_000_000L, 30))
        assertEquals(1_000_000L + 10_000, pairLockUntil(1_000_000L, null))
        assertEquals(1_000_000L + 10_000, pairLockUntil(1_000_000L, 0))
    }

    @Test fun 조향_비교불가_사유_문구() {
        assertEquals("울음이 없어 비교하지 않았어요", steeringUnavailableText(true))
        assertEquals("비교할 구간이 없어요", steeringUnavailableText(false))
    }
}
