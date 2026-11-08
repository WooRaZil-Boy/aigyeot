package kr.aigyeot.phone.domain.model

import kr.aigyeot.shared.HandoffKind
import kr.aigyeot.shared.StopInfo
import kr.aigyeot.shared.StopReason
import org.junit.Assert.assertEquals
import org.junit.Test

class StopTextTest {
    private fun t(m: HandoffKind, told: Boolean?) = stopText(StopReason.ATTEMPT_LIMIT, m, "휴게소", told)

    @Test fun `운전자에게 말하지 못한 인계는 제안했다고 보이지 않는다`() {
        assertEquals("두 번 시도 뒤 · 쉼터는 묻지 못했어요", t(HandoffKind.REST_OFFER, false))
        assertEquals("두 번 시도 뒤 · 도착 안내는 하지 못했어요", t(HandoffKind.ARRIVAL_NOTICE, false))
        assertEquals("두 번 시도 뒤 · 가까운 쉼터가 없었어요", t(HandoffKind.NO_REST, false))
        assertEquals("두 번 시도 뒤 · 휴게소에 들르자고 제안했어요", t(HandoffKind.REST_OFFER, true))
        assertEquals("두 번 시도 뒤 · 휴게소에 들르자고 제안했어요", t(HandoffKind.REST_OFFER, null))
        assertEquals("두 번 시도 뒤 · 도착 안내를 했어요", t(HandoffKind.ARRIVAL_NOTICE, true))
        assertEquals("두 번 시도 뒤 · 가까운 쉼터가 없어 안전한 곳에 세우도록 안내했어요", t(HandoffKind.NO_REST, null))
        assertEquals("두 번 시도 뒤 · 차가 선 채로 알렸어요", t(HandoffKind.PARKED, null))
        assertEquals("두 번 시도 뒤 · 쉼터에 들르자고 제안했어요", stopText(StopReason.ATTEMPT_LIMIT, HandoffKind.REST_OFFER, null, true))
    }

    @Test fun `뒤붙임까지 전체 줄이 iOS와 같다`() {
        assertEquals(
            "세션 20분 상한 · 휴게소에 들르자고 제안했어요 · 경유지 추가 · 다른 보호자에게 알림",
            stopText(StopReason.SESSION_CAP, HandoffKind.REST_OFFER, "휴게소", true, true, true),
        )
    }

    @Test fun `정차 안내 횟수는 운전자에게 말하지 못한 정차를 세지 않는다`() {
        fun info(told: Boolean?) = StopInfo(true, StopReason.ATTEMPT_LIMIT, HandoffKind.ARRIVAL_NOTICE, null, false, false, told)
        assertEquals(0, stopNoticeCount(emptyList()))
        assertEquals(2, stopNoticeCount(listOf(null, info(true), info(null), info(false))))
    }
}
