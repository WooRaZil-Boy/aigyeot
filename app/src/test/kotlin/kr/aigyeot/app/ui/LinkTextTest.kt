package kr.aigyeot.app.ui

import kr.aigyeot.app.link.LinkConn
import kr.aigyeot.app.link.LinkStatus
import kr.aigyeot.app.link.LinkedPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 정차 화면 연결 표시와 "연결됐어요"
class LinkTextTest {
    @Test fun connLine_fourStates() {
        assertNull(connText(LinkConn.NONE, false)) // 연결된 폰이 없으면 표시 안 함
        assertEquals("휴대폰과 연결하는 중", connText(LinkConn.CONNECTING, false))
        assertEquals("휴대폰 연결됨", connText(LinkConn.CONNECTED, true))
        assertEquals("휴대폰과 다시 연결하는 중", connText(LinkConn.BACKOFF, false))
    }

    // 중계 소켓만 붙었고 폰이 없으면 "연결됨"이라 쓰지 않는다
    @Test fun connLine_connectedWithoutPhone_saysNoPhone() {
        assertEquals("연결된 휴대폰 없음", connText(LinkConn.CONNECTED, false))
        assertEquals(listOf("연결된 휴대폰 없음"), linkLines(LinkStatus(conn = LinkConn.CONNECTED), paired = false).map { it.text })
        val st = LinkStatus(conn = LinkConn.CONNECTED, phones = listOf(LinkedPhone("d1", "owner", null)))
        assertEquals(listOf("휴대폰 연결됨"), linkLines(st, paired = false).map { it.text })
    }

    @Test fun justPaired_shownOnceOnStopScreen_evenIfPairedWhileAway() {
        val n = PairedNotice()
        assertFalse(n.take(null))
        assertTrue(n.take("d1")) // 정차 화면에서 새로 붙음
        assertFalse(n.take("d1")) // 다시 들어오면 안 보인다
        // 우리 노래·설정 화면에 있거나 주행 제한으로 가려진 동안 d2가 붙음(정차 화면을 그리지 않아 take가 불리지 않음)
        assertTrue(n.take("d2")) // 정차 화면에 오면 보인다
        assertFalse(n.take("d2")) // 그다음 들어오면 안 보인다
    }

    @Test fun linkLines_errorAsIs() {
        val st = LinkStatus(conn = LinkConn.BACKOFF, error = "휴대폰을 다시 연결해 주세요")
        assertEquals(listOf("휴대폰과 다시 연결하는 중", "휴대폰을 다시 연결해 주세요"), linkLines(st, paired = false).map { it.text })
        assertTrue(linkLines(st, false)[1].error)
        assertEquals(
            listOf("휴대폰 연결됨", "연결됐어요"),
            linkLines(LinkStatus(conn = LinkConn.CONNECTED, justPaired = "d1", phones = listOf(LinkedPhone("d1", "owner", null))), paired = true).map {
                it.text
            }
        )
        assertEquals(emptyList<String>(), linkLines(LinkStatus(), paired = false).map { it.text })
    }
}

// 인계 문장을 못 말했으면(told_driver=false) 정차 문구에서 "했다"고 쓰지 않는다
class StopTextToldDriverTest {
    private fun st(mode: kr.aigyeot.shared.HandoffKind, told: Boolean?, rest: String? = null) =
        kr.aigyeot.shared.StopInfo(true, kr.aigyeot.shared.StopReason.ATTEMPT_LIMIT, mode, rest, false, false, told)
    private fun tail(s: kr.aigyeot.shared.StopInfo) = stopText(s).substringAfter(" · ")

    @Test fun stopText_notTold_usesU53Phrases() {
        assertEquals("쉼터는 묻지 못했어요", tail(st(kr.aigyeot.shared.HandoffKind.REST_OFFER, false)))
        assertEquals("도착 안내는 하지 못했어요", tail(st(kr.aigyeot.shared.HandoffKind.ARRIVAL_NOTICE, false)))
        assertEquals("안전한 곳에 세우도록 안내하지 못했어요", tail(st(kr.aigyeot.shared.HandoffKind.NO_REST, false)))
        assertEquals("차가 선 채로 달래기를 멈췄어요", tail(st(kr.aigyeot.shared.HandoffKind.PARKED, null)))
    }

    @Test fun stopText_parkedNoSound_saysCouldNotPlay() {
        val s = kr.aigyeot.shared.StopInfo(true, kr.aigyeot.shared.StopReason.NO_SOUND, kr.aigyeot.shared.HandoffKind.PARKED, null, false, false, null)
        assertEquals("뒷좌석 출력 없음 · 차가 선 채로 소리를 틀지 못했어요", stopText(s))
    }

    @Test fun stopText_toldOrNull_keepsOldPhrases() {
        for (t in listOf(true, null)) {
            assertEquals("쉼터에 들르자고 제안했어요", tail(st(kr.aigyeot.shared.HandoffKind.REST_OFFER, t)))
            assertEquals("도착 안내를 했어요", tail(st(kr.aigyeot.shared.HandoffKind.ARRIVAL_NOTICE, t)))
            assertEquals("경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요", tail(st(kr.aigyeot.shared.HandoffKind.NO_REST, t)))
        }
    }

    @Test fun stopText_namedRest_stillShownWhenTold() {
        assertEquals("n 휴게소에 들르자고 제안했어요", tail(st(kr.aigyeot.shared.HandoffKind.REST_OFFER, true, "n 휴게소")))
    }

    @Test fun pendingSessionShowsSettling_thenUpdates() {
        val pendingLine = stopText(st(kr.aigyeot.shared.HandoffKind.REST_OFFER, null), settling = true) // 인계 TTS 도중: toldDriver 아직 null
        assertEquals("정리하는 중이에요", pendingLine.substringAfter(" · "))
        assertEquals("쉼터는 묻지 못했어요", tail(st(kr.aigyeot.shared.HandoffKind.REST_OFFER, false))) // TTS 실패로 확정되면 바뀐다
    }
}
