package kr.aigyeot.app.ui

import kr.aigyeot.app.link.LinkConn
import kr.aigyeot.app.link.LinkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// contracts-link 8.6 정차 화면 표시·8.7 "연결됐어요"
class LinkTextTest {
    @Test fun connLine_fourStates() {
        assertNull(connText(LinkConn.NONE))   // 연결된 폰이 없으면 표시 안 함
        assertEquals("휴대폰 연결: 붙는 중", connText(LinkConn.CONNECTING))
        assertEquals("휴대폰 연결됨", connText(LinkConn.CONNECTED))
        assertEquals("휴대폰 연결: 다시 붙는 중", connText(LinkConn.BACKOFF))
    }

    @Test fun justPaired_shownOnceOnStopScreen_evenIfPairedWhileAway() {
        val n = PairedNotice()
        assertFalse(n.take(null))
        assertTrue(n.take("d1"))      // 정차 화면에서 새로 붙음
        assertFalse(n.take("d1"))     // 다시 들어오면 안 보인다
        // 부모 노래·설정 화면에 있거나 주행 제한으로 가려진 동안 d2가 붙음(정차 화면을 그리지 않아 take가 불리지 않음)
        assertTrue(n.take("d2"))      // 정차 화면에 오면 보인다
        assertFalse(n.take("d2"))     // 그다음 들어오면 안 보인다
    }

    @Test fun linkLines_errorAsIs() {
        val st = LinkStatus(conn = LinkConn.BACKOFF, error = "휴대폰을 다시 연결해 주세요.")
        assertEquals(listOf("휴대폰 연결: 다시 붙는 중", "휴대폰을 다시 연결해 주세요."), linkLines(st, paired = false).map { it.text })
        assertTrue(linkLines(st, false)[1].error)
        assertEquals(listOf("휴대폰 연결됨", "연결됐어요"), linkLines(LinkStatus(conn = LinkConn.CONNECTED, justPaired = "d1"), paired = true).map { it.text })
        assertEquals(emptyList<String>(), linkLines(LinkStatus(), paired = false).map { it.text })
    }
}
