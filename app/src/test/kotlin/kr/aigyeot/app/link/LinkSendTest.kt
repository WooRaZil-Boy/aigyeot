package kr.aigyeot.app.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kr.aigyeot.app.common.instantOf
import kr.aigyeot.app.common.rfc3339
import kr.aigyeot.app.common.ulid
import kr.aigyeot.app.link.db.OutboxEntity
import kr.aigyeot.app.store.DB_TRIES
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.WORKER_RETRY_MS
import kr.aigyeot.app.store.dbPause
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.fresh
import kr.aigyeot.shared.SendMessageResponse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 보내기(만들지 않는 때, 응답 표)와 SendToPhone.
@RunWith(RobolectricTestRunner::class)
class LinkSendTest : LinkKit() {
    private fun pump() = runBlocking { link.pumpOnce() }
    private fun exec(c: Command) = runBlocking { link.exec(c) }
    private fun item(type: String, id: String = ulid(now), body: String = "{}", to: String? = null, createdAt: Long = now) =
        OutboxEntity(id, type, to, body, createdAt, 0, 0).also { dao.insertOutbox(it) }

    @Test fun sendToPhone_feedingState_bodyFromRoomNotEngine() {
        profile(lastFeeding = T0 - 3_600_000)
        phone()
        exec(
            SendToPhone(
                "feeding_state",
                buildJsonObject {
                    put("last_feeding_start", 1L)
                    put("window_start_expected", JsonNull)
                }
            )
        )
        assertEquals(T0 - 3_600_000, instantOf(outbox("feeding_state").single().body().str("last_feeding_start"))) // '경합 방지'
    }

    @Test fun sendToPhone_handoffAlert_onlyWithAlertGuardian_timesToRfc3339() {
        val body = buildJsonObject {
            put("text", "차가 선 채로 아기가 계속 울어요.")
            put("kind", "parked")
            put("occurred_at", T0)
        }
        phone() // owner만
        exec(SendToPhone("handoff_alert", body))
        assertTrue(outbox().isEmpty())
        phone(GUARD, "guardian", alerts = false)
        exec(SendToPhone("handoff_alert", body))
        assertTrue(outbox().isEmpty())
        phone(GUARD, "guardian", alerts = true)
        exec(SendToPhone("handoff_alert", body))
        assertEquals(T0, instantOf(outbox("handoff_alert").single().body().str("occurred_at")))
    }

    @Test fun pump_sendsInInsertOrder_sameIdAndCreatedAtTs_toDevice_thenDeletes() {
        val a = item("feeding_state", "01JC9D10Q4M8T1V6B5R3H2CD7Z", to = PHONE, createdAt = T0 - 5_000)
        val b = item("song_status", "01JC9D10Q4M8T1V6B5R3H2CD70", createdAt = T0 - 5_000) // id가 더 작아도 넣은 순서가 뒤
        pump()
        assertEquals(listOf(a.id, b.id), relay.sent.map { it.id })
        assertEquals(PHONE, relay.sent[0].to)
        assertNull(relay.sent[1].to)
        assertEquals(rfc3339(T0 - 5_000), relay.sent[0].ts)
        assertTrue(outbox().isEmpty())
    }

    @Test fun selectSong_sentWithoutPhones_andReturnedSeqApplied() {
        song(SA, ACTIVE, 10)
        song(SB, ACTIVE, 11)
        link.selectSong(SA)
        eventually { outbox("song_selected").isNotEmpty() } // 연결된 폰이 없어도 만든다(예외)
        relay.seq = 76
        pump()
        assertEquals(SA, dao.selection()!!.songId)
        assertEquals(77L, dao.selection()!!.seq)
        runBlocking { link.receive(env("song_selected", 76, """{"song_id":"$SB"}""")) } // 폰의 더 작은 seq 선택
        assertEquals(SA, dao.selection()!!.songId)
    }

    // 곡 선택 쓰기의 첫 시도만 실패하면 다시 해 보낼 함에 넣는다(조용히 유실하지 않는다)
    @Test fun selectSongDbErrorOnce_retriedAndQueued() {
        song(SA, ACTIVE, 10)
        val w = db.openHelper.writableDatabase
        w.execSQL("CREATE TRIGGER fail_once BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'x'); END")
        var pauses = 0
        val orig = dbPause
        dbPause = {
            pauses++
            w.execSQL("DROP TRIGGER fail_once")
        }
        try {
            link.selectSong(SA)
            eventually { outbox("song_selected").isNotEmpty() }
        } finally {
            dbPause = orig
        }
        assertEquals(1, pauses, "재시도가 한 번 일어났다")
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
        val a = item("song_status")
        item("feeding_state")
        pump()
        assertEquals(1, relay.sent.size)
        val front = outbox().first()
        assertEquals(a.id, front.id)
        assertEquals(1, front.attempts)
        assertEquals(now + 30_000, front.nextTryAt)
        pump()
        assertEquals(1, relay.sent.size) // 맨 앞이 기다리면 뒤도 기다린다(넣은 순서)
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
        item("song_status")
        item("feeding_state")
        pump()
        assertEquals(1, relay.sent.size)
        assertNull(dao.credential())
        assertTrue(dao.phoneLinks().isEmpty())
        assertEquals(2, outbox().size) // 보낼 함은 남긴다
        assertEquals("휴대폰을 다시 연결해 주세요", link.status.value.error)
    }

    @Test fun pump_401_frontWaits60s_noReRegisterChain() {
        phone()
        relay.sendResult = { Res.Err(401, "unauthorized", null) }
        item("song_status")
        pump()
        pump()
        assertEquals(1, relay.registers)
        assertEquals(now + 60_000, outbox().single().nextTryAt)
    }

    @Test fun credential_registerInterval_noRegisterBeforeBackoff() {
        phone()
        relay.sendResult = { Res.Err(401, "unauthorized", null) }
        item("song_status")
        pump() // 등록 1번, 401로 무효
        assertEquals(1, relay.registers)
        assertNull(runBlocking { link.credential() }) // 같은 시각: 간격 안이라 등록하지 않는다
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
        val clearId = ulid(now)
        item("records_clear", id = clearId, body = "")
        relay.recordsRes = Res.Err(503, "internal", null)
        pump()
        assertEquals(1, relay.recordsCalls)
        assertEquals(1, outbox().size)
        now += 1_000
        relay.recordsRes = Res.Ok(204, Unit)
        pump()
        assertEquals(2, relay.recordsCalls)
        assertTrue(outbox().isEmpty())
        assertEquals(listOf(clearId, clearId), relay.recordsIds) // 재시도도 같은 id
        assertTrue(relay.sent.isEmpty()) // 메시지가 아니다
    }

    @Test fun pump_recordsClear_sendsDropIds() {
        item("records_clear", body = """{"drop_ids":["A1","B2"]}""")
        relay.recordsRes = Res.Err(503, "internal", null)
        pump()
        now += 5_000
        relay.recordsRes = Res.Ok(204, Unit)
        pump()
        // 본문의 id 목록을 보내고 재시도도 같다. 옛 빈 본문은 빈 목록
        assertEquals(listOf(listOf("A1", "B2"), listOf("A1", "B2")), relay.recordsDropIds)
        item("records_clear", body = "")
        pump()
        assertEquals(emptyList<String>(), relay.recordsDropIds.last())
    }

    @Test fun pump_okFromOldRoom_afterInvalidate_seqNotApplied_resentToNewRoom() {
        song(SA, ACTIVE, 10)
        song(SB, ACTIVE, 11)
        item("song_selected", body = """{"song_id":"$SA"}""")
        relay.sendResult = { e ->
            // 옛 방에 보내는 사이 다른 길의 401로 방이 바뀌고, 옛 방의 답(seq 3000)이 늦게 온다
            link.invalidate()
            relay.sendResult = { Res.Ok(201, SendMessageResponse(it.id, ++relay.seq, 1, it.body)) }
            Res.Ok(201, SendMessageResponse(e.id, 3000, 1, e.body))
        }
        pump()
        assertEquals(0L, dao.selection()?.seq ?: 0L) // 옛 seq 3000을 새 방에 적용하지 않는다
        assertEquals(1, outbox("song_selected").size) // 새 방에 다시 보낸다
        now += 1_000 // 등록 간격 뒤
        relay.seq = 4
        pump()
        assertEquals(SA, dao.selection()!!.songId)
        assertEquals(5L, dao.selection()!!.seq)
        assertTrue(outbox().isEmpty())
    }

    // 리포트·알림을 보낼 때마다 id를 적고(보내는 동안에도), 10분 넘은 행은 지운다. 다른 type은 적지 않는다
    @Test fun pump_recordsSentIds_prunesOld() {
        phone()
        db.openHelper.writableDatabase.execSQL("INSERT INTO sent_id (id, sent_at) VALUES ('OLD', ${now - SENT_ID_KEEP_MS - 1})")
        val a = ulid(now)
        val b = ulid(now)
        item("report_summary", id = a)
        item("song_status")
        item("handoff_alert", id = b)
        var during: List<String> = emptyList()
        relay.sendResult = { env ->
            if (env.id == a) during = sentIds()
            Res.Offline
        } // 결과를 모르는 것만 남는다
        pump()
        assertEquals(listOf(a), during, "보내는 동안 이미 적혀 있다")
        assertEquals(listOf(a), sentIds(), "오래된 행은 지워지고 결과를 모르는 a만 남는다")
    }

    // 확정 응답(2xx·4xx)이면 보낸 id를 지운다. 5xx·네트워크 오류·429는 결과를 모르니 남긴다
    @Test fun sentIdRemovedOnDefiniteResponse() {
        phone()
        for ((res, kept) in listOf(
            Res.Err(400, "bad_request", null) to false,
            Res.Err(500, "x", null) to true,
            Res.Err(429, "r", 30L) to true,
            Res.Offline to true,
            null to false
        )) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM sent_id")
            db.openHelper.writableDatabase.execSQL("DELETE FROM outbox")
            val a = ulid(now)
            item("report_summary", id = a)
            relay.sendResult = { env -> res ?: Res.Ok(201, SendMessageResponse(env.id, 1, 1, env.body)) }
            pump()
            assertEquals(if (kept) listOf(a) else emptyList(), sentIds(), "응답 $res")
        }
    }

    // 시계가 되돌아가도 개수 상한 100개가 우선(넣은 순서로 오래된 것부터)
    @Test fun sentIdCappedAt100() {
        phone()
        val w = db.openHelper.writableDatabase
        // 앞 절반은 미래 시각(시계 되돌림)
        for (i in 0 until 150) w.execSQL("INSERT INTO sent_id (id, sent_at) VALUES ('X${"%03d".format(i)}', ${now + (if (i < 75) 3_600_000L else 0L)})")
        val a = ulid(now)
        item("report_summary", id = a)
        relay.sendResult = { Res.Offline }
        pump()
        assertEquals(setOf(a) + (51..149).map { "X${"%03d".format(it)}" }, sentIds().toSet()) // 151개 중 최근 100개
    }

    // 맨 앞을 고르는 일과 id 적기는 한 트랜잭션이라, 그 사이에 다른 스레드가 항목을 지우고 기록 지우기(drop_ids 계산)를 해도
    // 고른 항목의 id가 drop_ids에 들어간다. 트랜잭션이 없으면 id가 적히기 전에 계산돼 빠진다
    @Test fun frontSelectAndSentIdAtomic() {
        phone()
        val x = ulid(now)
        item("report_summary", id = x)
        relay.sendResult = { Res.Offline }
        val store = Store(db, { now })
        var clearer: Thread? = null
        link.afterFrontSelect = {
            link.afterFrontSelect = null
            val t = Thread {
                dao.deleteOutbox(x) // 고른 항목이 다른 곳에서 지워진다
                store.clearRecords(EngineState.Companion.fresh(1, null, emptyList(), emptyList(), null, 0))
            }
            clearer = t
            t.start()
            t.join(500) // 트랜잭션이 있으면 이 스레드는 pump가 끝날 때까지 기다린다
        }
        pump()
        clearer!!.join(5_000)
        pump() // 기록 지우기 항목(records_clear)을 중계로 보낸다
        assertTrue(x in relay.recordsDropIds.single(), "지운 리포트 id가 drop_ids에 없다: ${relay.recordsDropIds}")
    }

    // 보낼 함 일꾼이 Room 예외로 한 바퀴(재시도 DB_TRIES번)를 다 실패해도 새 신호 없이 WORKER_RETRY_MS 뒤 다시 깨어 맨 앞을 보낸다(시계는 가짜 now를 따른다)
    @Test fun pumpDbErrorOnce_retriesWithoutNewSignal() {
        // 폰을 연결하지 않아 소켓 반복(재연결·대체 확인)이 보낼 함을 몰래 깨우지 않는다
        relay.sendResult = { env -> Res.Ok(201, SendMessageResponse(env.id, 1, 1, env.body)) }
        link.start()
        Thread.sleep(1_000) // 시작 때의 신호를 먼저 흘려보낸다
        var fails = 0
        var thrownAt = 0L
        link.afterFrontSelect = {
            if (fails < DB_TRIES) {
                fails++
                thrownAt = System.currentTimeMillis()
                error("Room 예외(시험)")
            }
        }
        val a = ulid(now)
        item("handoff_alert", id = a) // 이 한 번의 신호만 일꾼을 깨운다
        eventually { fails == DB_TRIES }
        assertTrue(relay.sent.isEmpty())
        eventually(waitMs = WORKER_RETRY_MS + 30_000) { relay.sent.map { it.id } == listOf(a) }
        assertTrue(System.currentTimeMillis() - thrownAt >= WORKER_RETRY_MS - 1_000, "새 신호가 아니라 설계값 10초 뒤 깨우기로 보냈다")
    }

    // 한 번 실패는 같은 바퀴 안에서 다시 해 10초를 기다리지 않고 보낸다
    @Test fun pumpDbErrorOnce_retriedWithinRound() {
        relay.sendResult = { env -> Res.Ok(201, SendMessageResponse(env.id, 1, 1, env.body)) }
        link.start()
        Thread.sleep(1_000)
        var thrownAt = 0L
        link.afterFrontSelect = {
            if (thrownAt == 0L) {
                thrownAt = System.currentTimeMillis()
                error("Room 예외(시험)")
            }
        }
        val a = ulid(now)
        item("handoff_alert", id = a)
        eventually { relay.sent.map { it.id } == listOf(a) }
        assertTrue(thrownAt != 0L, "첫 시도가 실제로 던졌다")
        assertTrue(System.currentTimeMillis() - thrownAt < WORKER_RETRY_MS - 1_000, "다시 깨우기를 기다리지 않았다")
    }

    // 만료로 사라진 항목은 고르지도 적지도 보내지도 않는다
    @Test fun expiredFrontNotSelectedNorSent() {
        phone()
        val gone = ulid(now)
        item("report_summary", id = gone, createdAt = now - 7 * 24 * 3_600_000L - 1)
        val live = ulid(now)
        item("report_summary", id = live)
        relay.sendResult = { Res.Offline }
        pump()
        assertEquals(listOf(live), relay.sent.map { it.id })
        assertEquals(listOf(live), sentIds())
    }
    private fun sentIds() = db.openHelper.readableDatabase.query("SELECT id FROM sent_id").use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    @Test fun pump_sevenDayOldItem_droppedWithoutSending() {
        item("report_summary", createdAt = now - 7 * 24 * 3_600_000L - 1)
        pump()
        assertTrue(relay.sent.isEmpty())
        assertTrue(outbox().isEmpty())
    }
}

/** 비동기로 넣는 동작(scope.launch)이 끝나기를 기다린다. 한도는 느린 러너에서도 넘지 않게 길게 둔다: 느리면 오래 걸릴 뿐이고, 실패하면 진짜 멈춘 것이다. */
fun eventually(waitMs: Long = 30_000, cond: () -> Boolean) {
    val until = System.currentTimeMillis() + waitMs
    while (!cond()) {
        check(System.currentTimeMillis() < until) { "${waitMs}ms 안에 조건이 맞지 않음" }
        Thread.sleep(10)
    }
}
