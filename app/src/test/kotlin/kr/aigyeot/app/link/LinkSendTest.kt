package kr.aigyeot.app.link

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kr.aigyeot.app.link.db.OutboxEntity
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.shared.SendMessageResponse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// contracts-link 8.5 보내기(만들지 않는 때, 응답 표)와 차 계약 4.3 SendToPhone.
@RunWith(RobolectricTestRunner::class)
class LinkSendTest : LinkKit() {
    private fun pump() = runBlocking { link.pumpOnce() }
    private fun exec(c: Command) = runBlocking { link.exec(c) }
    private fun item(type: String, id: String = ulid(now), body: String = "{}", to: String? = null, createdAt: Long = now) =
        OutboxEntity(id, type, to, body, createdAt, 0, 0).also { dao.insertOutbox(it) }

    @Test fun sendToPhone_feedingState_bodyFromRoomNotEngine() {
        profile(lastFeeding = T0 - 3_600_000); phone()
        exec(SendToPhone("feeding_state", buildJsonObject { put("last_feeding_start", 1L); put("window_start_expected", JsonNull) }))
        assertEquals(T0 - 3_600_000, instantOf(outbox("feeding_state").single().body().str("last_feeding_start")))   // 3.3 '경합 방지'
    }

    @Test fun sendToPhone_handoffAlert_onlyWithAlertGuardian_timesToRfc3339() {
        val body = buildJsonObject { put("text", "차가 선 채로 아기가 계속 울어요."); put("kind", "parked"); put("occurred_at", T0) }
        phone()   // owner만
        exec(SendToPhone("handoff_alert", body))
        assertTrue(outbox().isEmpty())
        phone(GUARD, "guardian", alerts = false)
        exec(SendToPhone("handoff_alert", body))
        assertTrue(outbox().isEmpty())
        phone(GUARD, "guardian", alerts = true)
        exec(SendToPhone("handoff_alert", body))
        assertEquals(T0, instantOf(outbox("handoff_alert").single().body().str("occurred_at")))   // C48
    }

    @Test fun pump_sendsInInsertOrder_sameIdAndCreatedAtTs_toDevice_thenDeletes() {
        val a = item("feeding_state", "01JC9D10Q4M8T1V6B5R3H2CD7Z", to = PHONE, createdAt = T0 - 5_000)
        val b = item("song_status", "01JC9D10Q4M8T1V6B5R3H2CD70", createdAt = T0 - 5_000)   // id가 더 작아도 넣은 순서가 뒤
        pump()
        assertEquals(listOf(a.id, b.id), relay.sent.map { it.id })
        assertEquals(PHONE, relay.sent[0].to); assertNull(relay.sent[1].to)
        assertEquals(rfc3339(T0 - 5_000), relay.sent[0].ts)
        assertTrue(outbox().isEmpty())
    }

    @Test fun selectSong_sentWithoutPhones_andReturnedSeqApplied() {
        song(SA, ACTIVE, 10); song(SB, ACTIVE, 11)
        link.selectSong(SA)
        eventually { outbox("song_selected").isNotEmpty() }   // 연결된 폰이 없어도 만든다(8.5 예외)
        relay.seq = 76
        pump()
        assertEquals(SA, dao.selection()!!.songId); assertEquals(77L, dao.selection()!!.seq)
        runBlocking { link.receive(env("song_selected", 76, """{"song_id":"$SB"}""")) }   // 폰의 더 작은 seq 선택
        assertEquals(SA, dao.selection()!!.songId)
    }

    @Test fun pump_permanentFailure_dropped() {
        for (code in listOf(400, 403, 409, 413, 422)) {
            relay.sendResult = { Res.Err(code, "x", null) }
            item("song_status")
            pump()
            assertTrue(outbox().isEmpty(), "status $code")
        }
    }

    @Test fun pump_429_waitsRetryAfter_andFrontBlocksRest() {
        relay.sendResult = { Res.Err(429, "rate_limited", 30) }
        val a = item("song_status"); item("feeding_state")
        pump()
        assertEquals(1, relay.sent.size)
        val front = outbox().first()
        assertEquals(a.id, front.id); assertEquals(1, front.attempts); assertEquals(now + 30_000, front.nextTryAt)
        pump()
        assertEquals(1, relay.sent.size)   // 맨 앞이 기다리면 뒤도 기다린다(넣은 순서)
    }

    @Test fun pump_5xxAndOffline_backoff() {
        relay.sendResult = { Res.Err(503, "internal", null) }
        item("song_status")
        pump()
        assertEquals(now + 1_000, outbox().single().nextTryAt)
        now += 1_000
        relay.sendResult = { Res.Offline }
        pump()
        assertEquals(now + 2_000, outbox().single().nextTryAt)
        assertEquals(2, outbox().single().attempts)
    }

    @Test fun pump_401_invalidatesAndStops() {
        phone()
        relay.sendResult = { Res.Err(401, "unauthorized", null) }
        item("song_status"); item("feeding_state")
        pump()
        assertEquals(1, relay.sent.size)
        assertNull(dao.credential())
        assertTrue(dao.phoneLinks().isEmpty())
        assertEquals(2, outbox().size)   // 보낼 함은 남긴다
        assertEquals("휴대폰을 다시 연결해 주세요.", link.status.value.error)
    }

    @Test fun pump_401_frontWaits60s_noReRegisterChain() {
        phone()
        relay.sendResult = { Res.Err(401, "unauthorized", null) }
        item("song_status")
        pump(); pump()
        assertEquals(1, relay.registers)
        assertEquals(now + 60_000, outbox().single().nextTryAt)
    }

    @Test fun credential_registerInterval_noRegisterBeforeBackoff() {
        phone()
        relay.sendResult = { Res.Err(401, "unauthorized", null) }
        item("song_status")
        pump()                                           // 등록 1번, 401로 무효
        assertEquals(1, relay.registers)
        assertNull(runBlocking { link.credential() })    // 같은 시각: 간격 안이라 등록하지 않는다
        assertEquals(1, relay.registers)
        now += 1_000
        assertTrue(runBlocking { link.credential() } != null)
        assertEquals(2, relay.registers)
    }

    @Test fun pump_429_retryAfterZero_waitsAtLeast1s() {
        phone()
        relay.sendResult = { Res.Err(429, "rate_limited", 0) }
        item("song_status")
        pump()
        assertEquals(now + 1_000, outbox().single().nextTryAt)
    }

    @Test fun pump_recordsClear_usesDeleteRecords() {
        item("records_clear", body = "")
        relay.recordsRes = Res.Err(503, "internal", null)
        pump()
        assertEquals(1, relay.recordsCalls); assertEquals(1, outbox().size)
        now += 1_000
        relay.recordsRes = Res.Ok(204, Unit)
        pump()
        assertEquals(2, relay.recordsCalls); assertTrue(outbox().isEmpty())
        assertTrue(relay.sent.isEmpty())   // 메시지가 아니다
    }

    @Test fun pump_okFromOldRoom_afterInvalidate_seqNotApplied_resentToNewRoom() {
        song(SA, ACTIVE, 10); song(SB, ACTIVE, 11)
        item("song_selected", body = """{"song_id":"$SA"}""")
        relay.sendResult = { e ->   // 옛 방에 보내는 사이 다른 길의 401로 방이 바뀌고, 옛 방의 답(seq 3000)이 늦게 온다
            link.invalidate()
            relay.sendResult = { Res.Ok(201, SendMessageResponse(it.id, ++relay.seq, 1, it.body)) }
            Res.Ok(201, SendMessageResponse(e.id, 3000, 1, e.body))
        }
        pump()
        assertEquals(0L, dao.selection()?.seq ?: 0L)   // 옛 seq 3000을 새 방에 적용하지 않는다
        assertEquals(1, outbox("song_selected").size)  // 새 방에 다시 보낸다
        now += 1_000                                   // 등록 간격 뒤
        relay.seq = 4
        pump()
        assertEquals(SA, dao.selection()!!.songId); assertEquals(5L, dao.selection()!!.seq)
        assertTrue(outbox().isEmpty())
    }

    @Test fun pump_sevenDayOldItem_droppedWithoutSending() {
        item("report_summary", createdAt = now - 7 * 24 * 3_600_000L - 1)
        pump()
        assertTrue(relay.sent.isEmpty()); assertTrue(outbox().isEmpty())
    }
}

/** 비동기로 넣는 동작(scope.launch)이 끝날 때까지 2초 기다린다. */
fun eventually(cond: () -> Boolean) {
    val until = System.currentTimeMillis() + 2_000
    while (!cond()) {
        check(System.currentTimeMillis() < until) { "2초 안에 조건이 맞지 않음" }
        Thread.sleep(10)
    }
}
