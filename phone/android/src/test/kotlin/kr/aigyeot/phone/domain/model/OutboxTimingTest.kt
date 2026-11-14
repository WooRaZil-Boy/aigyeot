package kr.aigyeot.phone.domain.model

import kr.aigyeot.shared.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 보낼 함이 다시 보내는 때: 실패 종류별 기다림과 망 실패 기다림 당기기. */
class OutboxTimingTest {
    private val now = 1_762_300_000_000L
    private val owner = PhoneState.paired("c_7F3AK2N9X0Q4M8T1V6B5R3H2CD", "d_5KQ2M8T1V6B5R3H2CD7F3AK2N9", Role.OWNER, now)

    @Test
    fun `재시도 기본값은 iOS와 같다`() {
        val s = owner.selectSong("a", now)
        val item = s.outbox.single()
        // 429에 Retry-After가 없으면 60초(메시지는 기기 기다림도), 0이어도 1초(iOS `max(retryAfter ?? 60, 1)`).
        val held = s.afterSend(item, SendResult.Failed(429, "rate_limited"), now)
        assertEquals(now + 60_000, held.outbox.single().nextTryAt)
        assertEquals(now + 60_000, held.messageHoldUntil)
        assertEquals(now + 1_000, s.afterSend(item, SendResult.Failed(429, "rate_limited", 0), now).outbox.single().nextTryAt)
        // 노래 PUT 429도 60초(그 항목만).
        val song = owner.addSong("s_1", "a", 30.0, "s_1.m4a", now)
        val put = song.afterSend(song.outbox.single(), SendResult.Failed(429, "rate_limited"), now)
        assertEquals(now + 60_000, put.outbox.single().nextTryAt)
        assertNull(put.messageHoldUntil)
        // 409 song_quota: 차가 켜져 파일을 처리한 뒤 다시, 60초(iOS `songQuota`). 시도 횟수와 상관없다.
        val up = song.afterSend(song.outbox.single(), SendResult.Uploaded, now)
        val quota = up.afterSend(up.outbox.single(), SendResult.Failed(409, "song_quota"), now)
        assertEquals(now + 60_000, quota.outbox.single().nextTryAt)
        assertEquals(now + 60_000, quota.afterSend(quota.outbox.single(), SendResult.Failed(409, "song_quota"), now).outbox.single().nextTryAt)
    }

    @Test
    fun `망 실패 기다림만 당길 수 있다`() {
        val s = owner.selectSong("a", now)
        val item = s.outbox.single()
        fun after(r: SendResult) = s.afterSend(item, r, now).outbox.single()
        // 기다림 이유: 망 실패(응답 없음·닿지 않음)만 참. 중계가 준 기다림(429·song_quota)과 5xx는 거짓(iOS `scheduleRetry`).
        assertTrue(after(SendResult.Offline).networkWait)
        assertTrue(after(SendResult.Unreached).networkWait)
        assertFalse(after(SendResult.Failed(503, "unavailable")).networkWait)
        assertFalse(after(SendResult.Failed(429, "rate_limited", 30)).networkWait)
        assertFalse(after(SendResult.Failed(409, "song_quota")).networkWait)
        // 이유는 마지막 결과를 따른다.
        val offline = s.afterSend(item, SendResult.Offline, now)
        assertFalse(offline.afterSend(offline.outbox.single(), SendResult.Failed(503, "unavailable"), now).outbox.single().networkWait)
        // 당기기: 망 실패 기다림만 지금으로, 5xx 백오프는 그대로.
        val two = owner.selectSong("a", now, id = "01JC90A0Q4M8T1V6B5R3H2CD7A").selectSong("b", now, id = "01JC90A0Q4M8T1V6B5R3H2CD7B")
        val waited = two.afterSend(two.outbox[0], SendResult.Offline, now).afterSend(two.outbox[1], SendResult.Failed(503, "unavailable"), now)
        assertTrue(waited.hasNetworkWait(now))
        val hurried = waited.hurriedNetworkWaits(now + 1)
        assertEquals(now + 1, hurried.outbox[0].nextTryAt)
        assertEquals(waited.outbox[1], hurried.outbox[1])
        assertFalse(hurried.hasNetworkWait(now + 1))
        assertEquals(waited, waited.hurriedNetworkWaits(now + 60_000)) // 이미 지난 기다림은 그대로
    }
}
