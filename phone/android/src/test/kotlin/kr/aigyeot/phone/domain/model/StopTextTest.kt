package kr.aigyeot.phone.domain.model

import kr.aigyeot.shared.Gear
import kr.aigyeot.shared.HandoffKind
import kr.aigyeot.shared.OutputRoute
import kr.aigyeot.shared.PreCryContext
import kr.aigyeot.shared.StopInfo
import kr.aigyeot.shared.StopReason
import org.junit.Assert.assertEquals
import org.junit.Test

class StopTextTest {
    @Test fun `리포트 출력 경로·울음 직전 덧줄·실내 온도는 문구표와 iOS 글 그대로`() {
        assertEquals("소리 나간 곳 · 뒷좌석", outputRouteText(OutputRoute.REAR_ZONE))
        assertEquals("소리 나간 곳 · 앞에서도 들림", outputRouteText(OutputRoute.DEFAULT_ZONE_FALLBACK))
        assertEquals(null, outputRouteText(OutputRoute.NO_PLAYBACK))
        val c = PreCryContext("e", 30, 58.0, 71.0, 2, 1, turnSignalUsed = true, gear = Gear.D, cabinTempC = 27.5, windowOpen = true, playing = true)
        assertEquals("기어 D, 방향지시등 씀, 소리 재생 중, 창 열림", preCryDrive(c))
        assertEquals("기어 D", preCryDrive(c.copy(turnSignalUsed = false, windowOpen = null, playing = false)))
        assertEquals("27.5℃", cabinTempText(27.5))
        assertEquals("24.0℃", cabinTempText(24.0))
    }

    private fun t(m: HandoffKind, told: Boolean?) = stopText(StopReason.ATTEMPT_LIMIT, m, "휴게소", told)

    @Test fun `운전자에게 말하지 못한 인계는 제안했다고 보이지 않는다`() {
        assertEquals("두 번 시도 뒤 · 쉼터는 묻지 못했어요", t(HandoffKind.REST_OFFER, false))
        assertEquals("두 번 시도 뒤 · 도착 안내는 하지 못했어요", t(HandoffKind.ARRIVAL_NOTICE, false))
        assertEquals("두 번 시도 뒤 · 안전한 곳에 세우도록 안내하지 못했어요", t(HandoffKind.NO_REST, false))
        assertEquals("두 번 시도 뒤 · 휴게소에 들르자고 제안했어요", t(HandoffKind.REST_OFFER, true))
        assertEquals("두 번 시도 뒤 · 휴게소에 들르자고 제안했어요", t(HandoffKind.REST_OFFER, null))
        assertEquals("두 번 시도 뒤 · 도착 안내를 했어요", t(HandoffKind.ARRIVAL_NOTICE, true))
        assertEquals("두 번 시도 뒤 · 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요", t(HandoffKind.NO_REST, null))
        assertEquals("두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요", t(HandoffKind.PARKED, null))
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

    @Test fun `정차 인계는 알렸다고 보이지 않는다`() {
        assertEquals("두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요", stopText(StopReason.ATTEMPT_LIMIT, HandoffKind.PARKED, null, null, false, false))
        assertEquals("두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요 · 다른 보호자에게 알림", stopText(StopReason.ATTEMPT_LIMIT, HandoffKind.PARKED, null, null, false, true))
        val parked = StopInfo(true, StopReason.ATTEMPT_LIMIT, HandoffKind.PARKED, null, false, false, null)
        val told = StopInfo(true, StopReason.ATTEMPT_LIMIT, HandoffKind.ARRIVAL_NOTICE, null, false, false, true)
        assertEquals(1, stopNoticeCount(listOf(parked, told)))
    }

    @Test fun `뒷좌석 출력이 없어 정차한 인계는 소리를 틀지 못했다고 쓴다`() {   // U59
        assertEquals("뒷좌석 출력 없음 · 차가 선 채로 소리를 틀지 못했어요", stopText(StopReason.NO_SOUND, HandoffKind.PARKED, null, null, false, false))
    }

    @Test fun `정차 문구는 확인된 사실만 쓴다`() {
        val a = StopReason.ATTEMPT_LIMIT
        assertEquals("두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요 · 다른 보호자에게 알림", stopText(a, HandoffKind.PARKED, null, null, false, true))
        assertEquals("두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요", stopText(a, HandoffKind.PARKED, null, null, false, false))
        assertEquals("두 번 시도 뒤 · 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요", stopText(a, HandoffKind.NO_REST, null, true))
        assertEquals("두 번 시도 뒤 · 안전한 곳에 세우도록 안내하지 못했어요", stopText(a, HandoffKind.NO_REST, null, false))
        assertEquals("두 번 시도 뒤 · 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요", stopText(a, HandoffKind.NO_REST, null, null))
    }

    @Test fun `차 타일 마지막 연락은 U20 경과로 쓴다`() {
        // 연결 계약 차 타일 값(명세 17장 L100): 1분 미만 '방금', 그 밖은 U20 경과 + ' 전'
        val now = 1_793_944_800_000L
        fun tile(agoMs: Long) = carTileText(
            listOf(kr.aigyeot.shared.DeviceInfo("d_car", kr.aigyeot.shared.Role.CAR, kr.aigyeot.shared.Platform.PLEOS, rfc3339(now - DAY), rfc3339(now - agoMs), false)),
            online = true, now = now,
        )
        assertEquals("차 꺼짐 · 마지막 연락 방금", tile(30_000))
        assertEquals("차 꺼짐 · 마지막 연락 5분 전", tile(5 * MINUTE))
        assertEquals("차 꺼짐 · 마지막 연락 1시간 전", tile(60 * MINUTE))
        assertEquals("차 꺼짐 · 마지막 연락 1시간 15분 전", tile(75 * MINUTE))
    }
}
