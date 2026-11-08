package kr.aigyeot.app.link

import kotlinx.coroutines.runBlocking
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.FeedingFrom
import kr.aigyeot.engine.fresh
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.LinkChanged
import kr.aigyeot.shared.InboxResponse
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.Role
import kr.aigyeot.shared.Sender
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// contracts-link 8.3 받기 순서와 8.4 seq 규칙 표의 행마다.
@RunWith(RobolectricTestRunner::class)
class LinkReceiveTest : LinkKit() {
    private fun rx(e: kr.aigyeot.shared.Envelope) = runBlocking { link.receive(e) } == Link.Rx.ACK
    private fun sync() = runBlocking { link.credential(); link.syncInbox() }
    private val at1 = "2026-11-05T08:12:21+09:00"
    private val at1Ms = instantOf(at1)!!

    // ---------------------------------------------------------------- feeding_started

    @Test fun feedingStarted_later_updatesProfileSendsFeedingStateAndEvent() {
        profile(lastFeeding = at1Ms - 60_000); phone()
        val e = env("feeding_started", 412, """{"at":"$at1"}""")
        assertTrue(rx(e))
        assertEquals(at1Ms, lastFeeding())
        assertEquals(FeedingStarted(T0, at1Ms, FeedingFrom.PHONE, e.id), events.single())
        val fs = outbox("feeding_state").single().body()
        assertEquals(at1Ms, instantOf(fs.str("last_feeding_start")))
        assertEquals(at1Ms + (180 * 60_000 * 0.8).toLong(), instantOf(fs.str("window_start_expected")))   // 시작 + 간격 × 0.8
    }

    @Test fun receive_oldRoomEnvelope_afterInvalidate_notApplied() {
        profile(lastFeeding = at1Ms - 60_000); phone()
        val old = runBlocking { link.credential() }!!
        runBlocking { link.invalidate() }   // 소켓 프레임·받은 함이 처리되기 전에 다른 길의 401로 방이 바뀌었다
        assertEquals(Link.Rx.FAIL, runBlocking { link.receive(env("feeding_started", 3000, """{"at":"$at1"}"""), old) })   // 거짓: ack하지 않고 멈춘다
        assertEquals(at1Ms - 60_000, lastFeeding())
        assertTrue(events.none { it is FeedingStarted })
    }

    // C322 V3: 차 화면 수유도 폰 feeding_started와 같은 수유 함수(Room 먼저 → feeding_state → 사건)
    @Test fun c322_carFeeding_sameInputFunction_roomFirstThenFeedingStateThenEvent() {
        profile(lastFeeding = at1Ms - 60_000); phone()
        runBlocking { link.feedingFromCar(at1Ms).join() }
        assertEquals(at1Ms, lastFeeding())
        assertEquals(FeedingStarted(T0, at1Ms, FeedingFrom.CAR_SCREEN, null), events.single())
        assertEquals(at1Ms, instantOf(outbox("feeding_state").single().body().str("last_feeding_start")))
        runBlocking { link.feedingFromCar(at1Ms - 1_000).join() }   // 같거나 이르면 무시(C303 A14)
        assertEquals(at1Ms, lastFeeding()); assertEquals(1, events.size); assertEquals(1, outbox("feeding_state").size)
    }

    @Test fun feedingStarted_sameOrEarlier_ignored() {
        profile(lastFeeding = at1Ms); phone()
        rx(env("feeding_started", 412, """{"at":"$at1"}"""))
        rx(env("feeding_started", 413, """{"at":"2026-11-05T08:00:00+09:00"}"""))
        assertEquals(at1Ms, lastFeeding())
        assertTrue(events.isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test fun feedingStarted_noPhoneLink_eventButNoOutbox() {
        profile()   // 8.5 '만들지 않는 때': PhoneLink가 하나도 없으면 feeding_state를 만들지 않는다
        rx(env("feeding_started", 412, """{"at":"$at1"}"""))
        assertEquals(1, events.filterIsInstance<FeedingStarted>().size)
        assertTrue(outbox().isEmpty())
    }

    // ---------------------------------------------------------------- care_summary(L60, 8.3·8.4)

    private fun care(asOf: String = "2026-11-06T15:00:00+09:00", enabled: Boolean = true, sleepMin: String = "430", feeds: String = "3", sleepStart: String = "\"2026-11-06T12:20:00+09:00\"", seq: Long = 430) =
        env("care_summary", seq, """{"as_of":"$asOf","zone":"Asia/Seoul","sleep_diaper_enabled":$enabled,"last_sleep_start_at":$sleepStart,"last_sleep_end_at":null,"sleeping_since":null,"today_sleep_min":$sleepMin,"night_wakeups":null,"last_diaper_at":null,"today_diaper_count":null,"today_feed_count":$feeds}""")
    private val careAt = instantOf("2026-11-06T15:00:00+09:00")!!

    @Test fun l60_newerSummary_postsCareSummaryEvent_notInRoom() {
        now = careAt                                                         // as_of가 미래면 버리므로(3.3) 시험 시계를 as_of에 맞춘다
        val e = care()
        assertTrue(rx(e))
        assertEquals(CareSummary(careAt, CareView(careAt, true, instantOf("2026-11-06T12:20:00+09:00"), null, null, 430, null, null, null, 3, "Asia/Seoul"), seq = 430, room = "c_TESTCAR"), events.single())   // C126: 중계 seq와 요약 시간대를 싣는다
        assertTrue(dao.processed(e.id))
        assertTrue(outbox().isEmpty())
    }

    @Test fun l60_fixtures_apply_offAllNull() {
        now = careAt
        for (f in listOf("care_summary.json", "care_summary_off.json")) {
            events.clear(); dao.clearCareApplied()
            runBlocking { link.restoreEvents(kr.aigyeot.engine.EngineState.fresh(1, null, emptyList(), emptyList(), null, 0), now) }   // 두 예제는 같은 as_of·seq라 따로 받는다
            assertTrue(rx(ProtocolJson.decodeFromString(kr.aigyeot.shared.Envelope.serializer(), java.io.File("../shared/src/test/resources/protocol/$f").readText())), f)
            assertEquals(1, events.size, f)
        }
        val off = events.single() as CareSummary
        assertFalse(off.summary.sleepDiaperEnabled)
        assertEquals(3, off.summary.todayFeedCount); assertNull(off.summary.todaySleepMin)
    }

    @Test fun l60_olderOrSameAsOf_ackedNoEvent() {
        now = careAt
        putRow(CareView(careAt, true, null, null, null, null, null, null, null, 9), 430)   // 판정 기준은 Room 행(L69)
        val e = care()                                                       // 같은 as_of, 같은 seq
        assertTrue(rx(e)); assertTrue(events.isEmpty()); assertTrue(dao.processed(e.id))
        assertTrue(rx(care(asOf = "2026-11-06T15:00:01+09:00"))); assertTrue(events.isEmpty())   // 같은 방 같은 seq는 as_of가 늦어도 버린다(L77)
        assertTrue(rx(care(asOf = "2026-11-06T14:59:59+09:00", seq = 431))); assertEquals(1, events.size)   // 더 큰 seq는 as_of가 이르러도 낸다
    }

    @Test fun c126_sameSecond_largerRelaySeqReplaces() {
        now = careAt
        assertTrue(rx(care(feeds = "3", seq = 430))); assertTrue(rx(care(feeds = "4", seq = 431)))   // 같은 as_of, 더 큰 seq
        assertEquals(listOf(430L, 431L), events.map { (it as CareSummary).seq })
        assertEquals(4, (events.last() as CareSummary).summary.todayFeedCount)
        assertTrue(rx(care(feeds = "5", seq = 429))); assertEquals(2, events.size)               // 작은 seq는 낸 것이 없다
    }

    @Test fun c126_sceneValueInState_doesNotBlockRealSummary() {
        now = careAt
        val fresh = kr.aigyeot.engine.EngineState.fresh(1, null, emptyList(), emptyList(), null, 0)
        val real = CareView(careAt - 60_000, true, null, null, null, null, null, null, null, 1)
        runBlocking { link.restoreEvents(fresh.copy(care = real), now) }        // 장면 전 실제 값
        engineState = fresh.copy(care = CareView(careAt + 60_000, true, null, null, null, null, null, null, null, 9))   // 장면이 넣은 더 늦은 값
        assertTrue(rx(care(asOf = "2026-11-06T14:59:30+09:00")))                  // 장면 값보다 이르고 실제 값보다 늦은 실제 요약
        assertEquals(1, events.size)
    }

    @Test fun c128_noRealSummary_notComparedToSceneValue() {
        now = careAt
        val fresh = kr.aigyeot.engine.EngineState.fresh(1, null, emptyList(), emptyList(), null, 0)
        runBlocking { link.restoreEvents(fresh, now) }             // 실제 요약 없이 복원
        engineState = fresh.copy(care = CareView(careAt + 60_000, true, null, null, null, null, null, null, null, 9))   // 장면 값
        assertTrue(rx(care())); assertEquals(1, events.size, "장면 값이 아니라 실제 요약 없음(null)과 견준다")
    }

    // ---- C130 (연결 계약 8.4 care_summary 줄)
    private fun freshState() = kr.aigyeot.engine.EngineState.fresh(1, null, emptyList(), emptyList(), null, 0)

    @Test fun c132_stateUnknownBeforeRestore_linkDoesNotStartReceiving() {   // 복원 전에는 받지 않고, 복원 뒤에 받는다
        now = careAt
        profile(); phone()
        engineState = null
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(care(seq = 430)), false))
        relay.onSocket = { ev -> ev.onClosed(-1) }
        link.start()
        Thread.sleep(400)
        assertEquals(0, relay.socketCalls, "복원 전이면 연결·받기를 시작하지 않는다"); assertTrue(relay.inboxCalls.isEmpty())
        engineState = freshState()
        val end = System.currentTimeMillis() + 5_000
        while (events.none { it is CareSummary } && System.currentTimeMillis() < end) Thread.sleep(20)
        link.stop()
        assertEquals(1, events.count { it is CareSummary }, "복원 뒤에 받는다")
    }

    @Test fun c130_newRoom_sameAsOf_smallerSeq_applies_sameRoom_stillComparesSeq() {
        now = careAt
        assertTrue(rx(care(feeds = "3", seq = 9000))); events.clear()     // 옛 방(c_TESTCAR)
        assertTrue(rx(care(feeds = "4", seq = 2).copy(car = "c_NEWROOM")))   // 지우지 않고 방 재등록, 같은 asOf·내용이 바뀐 요약
        assertEquals(4, (events.single() as CareSummary).summary.todayFeedCount); assertEquals("c_NEWROOM", (events.single() as CareSummary).room)
        events.clear()
        assertTrue(rx(care(feeds = "5", seq = 1).copy(car = "c_NEWROOM"))); assertTrue(events.isEmpty(), "같은 방은 seq로 견준다")
    }

    @Test fun c130_snapshotRestore_keepsCareRoom_forRealComparison() {
        now = careAt
        val c = CareView(careAt, true, null, null, null, null, null, null, null, 3)
        putRow(c, 9000)
        runBlocking { link.restoreEvents(freshState().copy(care = c, careSeq = 9000, careRoom = "c_TESTCAR"), now) }
        assertTrue(rx(care(feeds = "4", seq = 2))); assertTrue(events.isEmpty(), "같은 방이라 옛 seq 9000과 견줘 버린다")
        assertTrue(rx(care(feeds = "4", seq = 2).copy(car = "c_B"))); assertEquals(1, events.size, "다른 방이면 나중에 받은 것")
    }

    // 같은 방이고 seq가 있으면 보낸 기기와 관계없이 seq로 견준다(L77)
    private fun putRow(c: CareView, seq: Long, room: String? = "c_TESTCAR", sender: String? = other) =
        dao.putCareApplied(kr.aigyeot.app.link.db.CareAppliedEntity(1, room, seq, kr.aigyeot.app.store.SnapshotJson.encodeToString(CareView.serializer(), c), sender))

    private fun careView(at: Long) = CareView(at, true, null, null, null, null, null, null, null, 1)

    private fun clearRow() = kr.aigyeot.app.link.db.OutboxEntity(ulid(now), "records_clear", null, "", now, 0, now).also { dao.insertOutbox(it) }

    private fun frameText(e: kr.aigyeot.shared.Envelope) = ProtocolJson.encodeToString(kr.aigyeot.shared.Envelope.serializer(), e)

    private fun wakingUntil(cond: () -> Boolean) = eventually { link.wake(); cond() }

    // ---- C157: '모든 기록 지우기'는 돌봄 요약을 지우지 않는다. 지우기 전후·중계 삭제 대기 중·완료 뒤 받은 요약이 모두 보통대로 적용·ack
    @Test fun c157_clearDoesNotTouchCareSummary() {
        now = careAt
        assertTrue(rx(care(feeds = "3", seq = 430))); events.clear()
        val row = clearRow()                                                   // 지우기 직후: 중계 삭제는 보낼 함에서 기다린다
        assertEquals(430L, dao.careApplied()!!.seq, "Room 행은 그대로")
        val a = care(feeds = "4", seq = 431)
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(a), false)); sync()   // 삭제 대기 중 받은 요약도 보통대로
        assertEquals(listOf(a.id), relay.acked); assertTrue(dao.processed(a.id)); assertEquals(431L, dao.careApplied()!!.seq)
        assertEquals(1, events.count { it is CareSummary })
        dao.deleteOutbox(row.id)                                      // 삭제 완료 뒤
        val b = care(feeds = "5", seq = 432)
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(b), false)); sync()
        assertEquals(listOf(a.id, b.id), relay.acked); assertEquals(432L, dao.careApplied()!!.seq)
        assertEquals(2, events.count { it is CareSummary })
        val got = runBlocking { link.restoreEvents(freshState(), now) }        // 복원도 Room 행을 그대로 쓴다
        assertEquals(432L, got.filterIsInstance<CareSummary>().single().seq)
    }

    @Test fun c144_processedAndCareRowSameTransaction() {   // 행 쓰기가 실패하면 처리 완료도 남지 않는다
        now = careAt
        val e = care(seq = 430)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_care BEFORE INSERT ON care_applied BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertFalse(rx(e)); assertFalse(dao.processed(e.id)); assertTrue(events.isEmpty())
    }

    // ---- C144: 적용한 요약은 처리 완료와 같은 트랜잭션으로 Room 한 행에 둔다
    @Test fun c144_receiveWritesCareRowWithProcessed() {
        now = careAt
        val e = care(seq = 430)
        assertTrue(rx(e))
        val row = dao.careApplied()!!
        assertEquals(430L, row.seq); assertEquals("c_TESTCAR", row.carId); assertTrue(dao.processed(e.id))
        assertTrue(rx(care(asOf = "2026-11-06T14:00:00+09:00", seq = 429).copy(from = Sender(Role.OWNER, other))))   // 다른 기기의 작은 seq 요약은 버리고 ack: 행은 그대로
        assertEquals(430L, dao.careApplied()!!.seq)
        val bad = care(sleepMin = "-1", seq = 432)                             // 읽지 못하는 요약: 처리 완료만, 행 없음 변화 없음
        assertTrue(rx(bad)); assertEquals(430L, dao.careApplied()!!.seq)
    }

    @Test fun c144_restoreRoomCareNewerThanSnapshot_reapplied() {   // 처리 완료 뒤 스냅숏 전에 죽었다
        now = careAt
        assertTrue(rx(care(feeds = "3", seq = 430))); events.clear()
        val got = runBlocking { link.restoreEvents(freshState(), now) }       // 스냅숏에는 요약이 없다
        val cs = got.filterIsInstance<CareSummary>().single()
        assertEquals(430L, cs.seq); assertEquals(3, cs.summary.todayFeedCount); assertEquals("c_TESTCAR", cs.room)
        assertTrue(rx(care(feeds = "9", seq = 429))); assertTrue(events.isEmpty(), "복원한 요약이 실제 기준이다")
    }

    @Test fun c144_restoreSnapshotSameOrNewer_noEvent() {
        now = careAt
        assertTrue(rx(care(seq = 430)))
        val snap = freshState().copy(care = (dao.careApplied()!!.let { kr.aigyeot.app.store.SnapshotJson.decodeFromString(CareView.serializer(), it.summaryJson) }), careSeq = 430, careRoom = "c_TESTCAR")
        assertTrue(runBlocking { link.restoreEvents(snap, now) }.none { it is CareSummary })
        // 스냅숏 값이 행보다 늦어도(seq 없음) 행이 정답이라 비우고 행으로 바꾼다(C169)
        val got = runBlocking { link.restoreEvents(freshState().copy(care = snap.care!!.copy(asOf = careAt + 1_000)), now) }
        assertEquals(listOf("CareCleared", "CareSummary"), got.filter { it is CareSummary || it is kr.aigyeot.engine.CareCleared }.map { it::class.simpleName })
    }

    @Test fun c169_restoreRowEqualsSnapshot_noEvent() {
        now = careAt
        assertTrue(rx(care(seq = 430)))
        val rowCare = kr.aigyeot.app.store.SnapshotJson.decodeFromString(CareView.serializer(), dao.careApplied()!!.summaryJson)
        val got = runBlocking { link.restoreEvents(freshState().copy(care = rowCare, careSeq = 430, careRoom = "c_TESTCAR"), now) }
        assertTrue(got.none { it is CareSummary || it is kr.aigyeot.engine.CareCleared })
    }

    @Test fun c169_restoreRoomOlderRowAfterUnpair_replacesSnapshot() {   // A 15:00 스냅숏 -> A 해제로 행 삭제 -> B 14:59 행 -> 죽음
        now = careAt
        dao.clearCareApplied()
        assertTrue(rx(care(asOf = "2026-11-06T14:59:00+09:00", feeds = "1", seq = 431))); events.clear()
        val snap = freshState().copy(care = careView(careAt), careSeq = 430, careRoom = "c_TESTCAR")
        val got = runBlocking { link.restoreEvents(snap, now) }.filter { it is CareSummary || it is kr.aigyeot.engine.CareCleared }
        assertEquals(listOf("CareCleared", "CareSummary"), got.map { it::class.simpleName })
        assertEquals(431L, (got[1] as CareSummary).seq); assertEquals(1, (got[1] as CareSummary).summary.todayFeedCount)
    }

    // ---- L69: 판정 기준은 Room 행. 복원(restoreEvents) 전에 늦게 온 옛 요약이 행을 덮지 않는다
    @Test fun l69_lateOlderSummaryBeforeRestore_doesNotOverwriteRoomRow() {
        now = careAt
        val a = care(feeds = "7", seq = 430)                                    // 15:00 요약 A: Room 행과 처리 완료까지 가고 스냅숏 전에 죽었다
        assertTrue(rx(a)); events.clear()
        // 재시작: 복원 전이라 메모리 값·스냅숏(요약 없음)은 비었다. 늦게 온 옛 seq 요약 B
        val b = care(asOf = "2026-11-06T14:59:00+09:00", feeds = "1", seq = 429).copy(from = Sender(Role.OWNER, other))
        assertTrue(rx(b)); assertTrue(events.isEmpty(), "B는 행 A보다 seq가 작다")
        assertEquals(430L, dao.careApplied()!!.seq);
        val cs = runBlocking { link.restoreEvents(freshState(), now) }.filterIsInstance<CareSummary>().single()
        assertEquals(7, cs.summary.todayFeedCount, "복원은 A를 살린다")
    }

    @Test fun l69_roomRow_sameRoomComparesSeqOnly_otherRoomLaterReceivedWins() {
        now = careAt
        putRow(careView(careAt), 430)
        assertTrue(rx(care(asOf = "2026-11-06T15:00:01+09:00", seq = 400))); assertTrue(events.isEmpty(), "같은 방은 as_of가 늦어도 seq가 작으면 버린다(L77)")
        assertTrue(rx(care(asOf = "2026-11-06T14:59:00+09:00", seq = 431, feeds = "8"))); assertEquals(1, events.size, "같은 방 큰 seq는 as_of가 이르러도 적용")
        assertEquals(431L, dao.careApplied()!!.seq)
        assertTrue(rx(care(asOf = "2026-11-06T14:59:00+09:00", seq = 431, feeds = "9"))); assertEquals(1, events.size)
        assertTrue(rx(care(asOf = "2026-11-06T15:00:01+09:00", seq = 399, feeds = "9").copy(car = "c_B"))); assertEquals(2, events.size, "다른 방은 늦은 as_of일 때(나중에 받은 것)")
    }

    // ---- C158: 요약을 보낸 owner가 연결에서 빠지면 요약을 지운다
    private val other = "d_OTHER"
    private fun unpaired(device: String, seq: Long) = env("unpaired", seq, """{"device_id":"$device","role":"${if (device == PHONE) "owner" else "guardian"}","by":"self"}""", Sender(Role.RELAY, null))

    @Test fun l75_sameSenderHigherSeqReplacesEvenIfEarlierAsOf() {
        now = careAt; phone()
        assertTrue(rx(care(asOf = "2026-11-06T15:04:00+09:00", seq = 430, feeds = "3"))); events.clear()
        assertTrue(rx(care(asOf = "2026-11-06T15:01:00+09:00", seq = 431, feeds = "9")))
        assertEquals(1, events.filterIsInstance<CareSummary>().size, "같은 기기 큰 seq는 as_of가 이르러도 바꾼다")
        assertEquals(431L, dao.careApplied()!!.seq)
        events.clear()
        assertTrue(rx(care(asOf = "2026-11-06T15:05:00+09:00", seq = 429, feeds = "1")))
        assertTrue(events.isEmpty(), "같은 기기 작은 seq는 as_of가 늦어도 버린다")
    }

    @Test fun l77_differentSenderHigherSeqReplaces_regardlessOfAsOf() {
        now = careAt; phone(); phone(other)
        assertTrue(rx(care(asOf = "2026-11-06T15:04:00+09:00", seq = 430, feeds = "3"))); events.clear()
        assertTrue(rx(care(asOf = "2026-11-06T15:01:00+09:00", seq = 431, feeds = "9").copy(from = Sender(Role.OWNER, other))))
        assertEquals(1, events.filterIsInstance<CareSummary>().size, "다른 기기도 큰 seq는 as_of가 이르러도 바꾼다")
        assertEquals(431L, dao.careApplied()!!.seq)
        events.clear()
        assertTrue(rx(care(asOf = "2026-11-06T15:05:00+09:00", seq = 429, feeds = "1")))
        assertTrue(events.isEmpty(), "작은 seq는 보낸 기기가 달라도 버린다")
    }

    @Test fun c223_expiredSummaryAckedNotApplied() {
        now = careAt
        assertTrue(rx(care(seq = 10)))
        events.clear()
        val other = care(asOf = "2026-11-05T14:00:00+09:00", seq = 11).copy(from = Sender(Role.OWNER, other))   // 25시간 전, 다른 owner, 더 큰 seq
        assertTrue(rx(other)); assertTrue(dao.processed(other.id))
        assertTrue(events.isEmpty()); assertEquals(10L, dao.careApplied()!!.seq)
        assertTrue(rx(care(asOf = "2026-11-05T15:00:00+09:00", seq = 12)))   // 정확히 24시간은 적용
        assertEquals(12L, dao.careApplied()!!.seq)
    }

    private fun careSet(): Boolean { now = careAt; phone(); return rx(care(seq = 430)).also { events.clear() } }

    @Test fun c158_ownerUnpaired_clearsCareSummary() {
        assertTrue(careSet()); assertEquals(PHONE, dao.careApplied()!!.senderDevice)
        assertTrue(rx(unpaired(PHONE, 431)))
        assertNull(dao.careApplied()); assertTrue(events.any { it is kr.aigyeot.engine.CareCleared })
        assertTrue(runBlocking { link.restoreEvents(freshState(), now) }.none { it is CareSummary }, "재시작해도 없다")
        assertTrue(rx(care(feeds = "9", seq = 440))); assertTrue(events.none { it is CareSummary }, "끊긴 기기의 늦은 요약은 받지 않는다")
    }

    @Test fun c158_disconnectHere_clearsCareSummary() {   // 차 화면에서 끊기
        assertTrue(careSet())
        val out = kr.aigyeot.app.link.Applied()
        LinkRoom(db).tx { LinkRoom(db).unpair(PHONE, 0, now, out) }
        assertNull(dao.careApplied()); assertTrue(out.careCleared)
    }

    @Test fun c158_ownerMissingInDeviceList_clears() {
        assertTrue(careSet())
        val out = kr.aigyeot.app.link.Applied()
        LinkRoom(db).reconcile(kr.aigyeot.shared.DevicesResponse(500, emptyList()), now, out)
        assertNull(dao.careApplied()); assertTrue(out.careCleared)
    }

    @Test fun c158_newRoom_clears() {
        assertTrue(careSet())
        val out = kr.aigyeot.app.link.Applied()
        LinkRoom(db).resetForNewRoom(now, out)
        assertNull(dao.careApplied()); assertTrue(out.careCleared)
    }

    @Test fun c158_guardianUnpaired_keepsCare() {
        assertTrue(careSet()); phone(other, "guardian")
        assertTrue(rx(unpaired(other, 431)))
        assertEquals(430L, dao.careApplied()!!.seq); assertTrue(events.none { it is kr.aigyeot.engine.CareCleared })
    }

    @Test fun c158_restoreAfterRowClearedBeforeSnapshot_clears() {   // 행을 지운 뒤 스냅숏 전에 죽었다
        assertTrue(careSet())
        dao.clearCareApplied()
        val snap = freshState().copy(care = careView(careAt), careSeq = 430, careRoom = "c_TESTCAR")
        assertTrue(runBlocking { link.restoreEvents(snap, now) }.any { it is kr.aigyeot.engine.CareCleared })
        assertTrue(rx(care(asOf = "2026-11-06T14:00:00+09:00", seq = 431))); assertEquals(1, events.count { it is CareSummary }, "실제 기준은 비었다")
    }

    private fun assertDropped(e: kr.aigyeot.shared.Envelope) {
        now = careAt
        assertTrue(rx(e)); assertTrue(events.isEmpty(), e.toString()); assertTrue(dao.processed(e.id))
    }
    @Test fun l60_unreadableTime_droppedAndAcked() = assertDropped(care(asOf = "어제 3시"))
    @Test fun l60_negativeInt_droppedAndAcked() = assertDropped(care(feeds = "-1"))
    @Test fun l60_disabledButSleepValue_droppedAndAcked() = assertDropped(care(enabled = false))
    @Test fun l60_unreadableSleepTime_droppedAndAcked() = assertDropped(care(sleepStart = "\"abc\""))
    @Test fun l60_invalidCalendarDate_droppedAndAcked() = assertDropped(care(asOf = "2026-02-30T15:00:00+09:00"))
    @Test fun l60_intOverflow_droppedAndAcked() = assertDropped(care(feeds = "2147483648"))

    @Test fun l60_futureAsOf_droppedAndAcked() {
        now = careAt - 6 * 60_000                                            // as_of가 6분 미래: 폰 시계가 앞섰다
        val e = care(); assertTrue(rx(e)); assertTrue(events.isEmpty()); assertTrue(dao.processed(e.id))
        now = careAt - 4 * 60_000                                            // 4분은 오차로 받는다
        assertTrue(rx(care())); assertEquals(1, events.size)
    }

    // ---------------------------------------------------------------- 1·2단계

    @Test fun duplicateId_ackOnlyNoSecondEffect() {
        profile(); phone()
        val e = env("feeding_started", 412, """{"at":"$at1"}""")
        assertTrue(rx(e)); assertTrue(rx(e))
        assertEquals(1, events.size)
        assertEquals(1, outbox().size)
    }

    @Test fun unknownVersionOrType_ackOnlyNotRecorded() {
        val bad = env("feeding_started", 1, """{"at":"$at1"}""", v = 2)
        assertTrue(rx(bad))
        assertTrue(rx(env("future_type", 2, "{}")))
        assertFalse(dao.processed(bad.id))
        assertTrue(events.isEmpty())
    }

    @Test fun nonUlidId_ackOnlyNotRecorded() {
        profile(); phone()
        val e = env("feeding_started", 412, """{"at":"$at1"}""", id = "")
        assertTrue(rx(e))
        assertFalse(dao.processed(e.id))
        assertTrue(events.isEmpty())
    }

    @Test fun badBody_recordedAndAckedWithoutEffect() {
        val e = env("song_added", 5, """{"song_id":"../../etc","name":"x","duration_s":1}""")   // 곡 id는 ULID만(파일 이름에 들어간다)
        assertTrue(rx(e))
        assertTrue(dao.processed(e.id))
        assertTrue(dao.songs().isEmpty())
    }

    // ---------------------------------------------------------------- 노래(8.4, 3곡 G40)

    @Test fun songAdded_new_pendingAndDownloadQueued() {
        rx(env("song_added", 10, """{"song_id":"$SA","name":"나비야","duration_s":94.5}"""))
        val s = dao.song(SA)!!
        assertEquals(PENDING, s.status); assertEquals(10L, s.addedSeq); assertEquals(10L, s.lastSeq)
        assertEquals(listOf(SA), dao.downloads().map { it.songId })
    }

    @Test fun songAdded_fourthHolder_rejectedLimitReached_deletedEarlierDoesNotHold() {
        phone()
        song(SA, ACTIVE, 10); song(SB, PENDING, 11); song(SC, DELETED, 12, last = 13)   // C는 13에 지워짐
        rx(env("song_added", 14, """{"song_id":"$SD","name":"d","duration_s":30}"""))     // 자리: A·B → 받는 중
        assertEquals(PENDING, dao.song(SD)!!.status)
        rx(env("song_added", 15, """{"song_id":"$SE","name":"e","duration_s":30}"""))     // 자리: A·B·D = 3 → 거절
        val e = dao.song(SE)!!
        assertEquals(REJECTED, e.status); assertEquals("limit_reached", e.rejectReason)
        val st = outbox("song_status").single().body()
        assertEquals(SE, st.str("song_id")); assertEquals("rejected", st.str("status")); assertEquals("limit_reached", st.str("reason"))
        assertFalse(dao.downloads().any { it.songId == SE })
    }

    @Test fun songRemoved_beforeAdded_keepsDeletedAndLateAddDoesNotRevive() {
        phone()
        rx(env("song_removed", 21, """{"song_id":"$SA"}"""))
        val s = dao.song(SA)!!
        assertEquals(DELETED, s.status); assertNull(s.name); assertEquals(21L, s.lastSeq)
        val st = outbox("song_status").single().body()
        assertEquals("removed", st.str("status")); assertNull(st.str("name"))
        rx(env("song_added", 20, """{"song_id":"$SA","name":"늦게","duration_s":30}"""))
        assertEquals(DELETED, dao.song(SA)!!.status)
        assertTrue(dao.downloads().isEmpty())
    }

    @Test fun songRemoved_smallerSeq_ignored() {
        song(SA, DELETED, 10, last = 30)
        rx(env("song_removed", 25, """{"song_id":"$SA"}"""))
        assertEquals(30L, dao.song(SA)!!.lastSeq)
    }

    @Test fun songRemoved_candidateMoves_statusForBothSongs() {
        phone()
        song(SA, ACTIVE, 10); song(SB, ACTIVE, 11)   // ③ = B(추가 순번이 가장 큼)
        rx(env("song_removed", 12, """{"song_id":"$SB"}"""))
        val bodies = outbox("song_status").map { it.body() }
        assertEquals(setOf(SB, SA), bodies.map { it.str("song_id") }.toSet())
        assertEquals("true", bodies.single { it.str("song_id") == SA }.str("candidate"))
        assertEquals("removed", bodies.single { it.str("song_id") == SB }.str("status"))
        assertEquals(SA, candidateSong(dao.profile(), dao.songs(), dao.selection()))
    }

    @Test fun songSelected_largerSeqWins_andCandidateChangeSendsBoth() {
        phone()
        song(SA, ACTIVE, 10); song(SB, ACTIVE, 11)
        rx(env("song_selected", 30, """{"song_id":"$SA"}"""))
        assertEquals(30L, dao.selection()!!.seq)
        assertEquals(setOf(SA, SB), outbox("song_status").map { it.body().str("song_id") }.toSet())
        rx(env("song_selected", 29, """{"song_id":"$SB"}"""))   // 늦게 온 작은 seq
        assertEquals(SA, dao.selection()!!.songId)
    }

    // 장면 'phone' 봉투는 seq가 비어 온다(계약 7.3, C96). seq 검사 없이 적용하고 실제 seq 기록과 섞지 않는다.
    private fun scenarioRx(e: kr.aigyeot.shared.Envelope) = runBlocking { link.receiveForScenario(ProtocolJson.encodeToString(kr.aigyeot.shared.Envelope.serializer(), e)) }

    @Test fun scenarioFeedingStarted_postsOneEvent_roomUntouched() {
        profile(lastFeeding = at1Ms - 60_000); phone()
        val e = env("feeding_started", null, """{"at":"$at1"}""")
        scenarioRx(e)
        assertEquals(FeedingStarted(T0, at1Ms, FeedingFrom.PHONE, e.id), events.single())
        assertEquals(at1Ms - 60_000, lastFeeding())
        assertFalse(dao.processed(e.id))
        assertTrue(outbox().isEmpty())
    }

    @Test fun scenarioOtherTypes_ignored_roomUntouched_andRealRelayStillApplies() {
        phone()
        song(SA, ACTIVE, 10); song(SB, ACTIVE, 11)
        rx(env("song_selected", 30, """{"song_id":"$SA"}"""))
        val before = dao.selection()
        val outboxBefore = outbox().size
        scenarioRx(env("song_selected", null, """{"song_id":"$SB"}"""))
        scenarioRx(env("song_removed", null, """{"song_id":"$SA"}"""))
        assertEquals(before, dao.selection())
        assertEquals(setOf(SA, SB), dao.songs().map { it.id }.toSet())
        assertEquals(outboxBefore, outbox().size)
        assertTrue(events.isEmpty())
        rx(env("song_selected", 31, """{"song_id":"$SB"}"""))          // 장면 뒤 실제 메시지는 평소대로
        assertEquals(SB, dao.selection()!!.songId)
    }

    /** C303 ⑥(B9): 폰에 보이는 후보 ③은 store와 같은 함수(프로필의 부모 노래 끔 포함). 차 설정에서 켬·끔을 바꾸면 같은 트랜잭션에서 song_status를 넣는다. */
    @Test fun c303_b9_candidateRespectsParentSongOff() {
        phone(); song(SA, ACTIVE, 10)
        val store = kr.aigyeot.app.store.Store(db, { now })
        fun prof(song: Boolean) = kr.aigyeot.app.store.db.ChildProfileEntity("c", "콩이", "2026-03", "LEFT", null, null,
            kr.aigyeot.engine.DEFAULT_METHODS + listOfNotNull(kr.aigyeot.engine.Method(kr.aigyeot.engine.MethodKind.SONG).takeIf { song }), false, false, T0, kr.aigyeot.app.store.db.AI_NOTICE_VERSION)
        store.saveProfile(prof(song = true))
        assertTrue(outbox("song_status").isEmpty(), "프로필이 없을 때도 켬이라 ③은 그대로")
        store.saveProfile(prof(song = false))
        assertEquals("false", outbox("song_status").single { it.body().str("song_id") == SA }.body().str("candidate"), "끄면 바로 후보가 아님을 알린다")
        assertNull(store.methods().firstOrNull { it.kind == kr.aigyeot.engine.MethodKind.SONG }, "store도 같은 함수")
        dao.outbox().forEach { dao.deleteOutbox(it.id) }
        rx(env("state_request", 11, "{}"))
        assertEquals("false", outbox("song_status").single().body().str("candidate"), "부모 노래를 끄면 폰 전체 상태에서도 후보가 아니다")
        dao.outbox().forEach { dao.deleteOutbox(it.id) }
        store.saveProfile(prof(song = true))
        assertEquals("true", outbox("song_status").single { it.body().str("song_id") == SA }.body().str("candidate"), "켜면 바로 후보로 알린다")
    }

    @Test fun songSelected_deletedSongKeepsSelectionRecord() {
        song(SA, ACTIVE, 10); song(SB, ACTIVE, 11)
        rx(env("song_selected", 30, """{"song_id":"$SA"}"""))
        rx(env("song_removed", 31, """{"song_id":"$SA"}"""))
        assertEquals(SA, dao.selection()!!.songId)                    // 선택 기록은 곡을 지워도 남긴다
        assertEquals(SB, candidateSong(dao.profile(), dao.songs(), dao.selection()))
    }

    // ---------------------------------------------------------------- paired·unpaired(8.4)

    @Test fun paired_newOwner_createsLinkSendsFullStateAndLinkChanged() {
        profile(lastFeeding = at1Ms)
        song(SA, ACTIVE, 10); song(SB, PENDING, 11); song(SC, REJECTED, 12)
        rx(relayEnv("paired", 410, """{"device_id":"$PHONE","role":"owner","platform":"android","paired_at":"2026-11-05T08:13:02Z","handoff_alerts":false}"""))
        val p = dao.phoneLink(PHONE)!!
        assertEquals("owner", p.role); assertEquals("android", p.platform)
        assertEquals(410L, dao.mark(PHONE)!!.lastSeq)
        assertEquals(LinkChanged(T0, 0), events.single())
        assertEquals(1, outbox("feeding_state").size)
        assertEquals(setOf(SA, SC), outbox("song_status").map { it.body().str("song_id") }.toSet())   // PENDING 제외(G23, L50)
        assertTrue(outbox().all { it.toDevice == null })
        assertEquals(PHONE, link.status.value.justPaired)
    }

    @Test fun paired_alertsChange_overwritesOnlyWithLargerSeq() {
        val body = { alerts: Boolean -> """{"device_id":"$GUARD","role":"guardian","platform":"ios","paired_at":"2026-11-05T09:01:44Z","handoff_alerts":$alerts}""" }
        rx(relayEnv("paired", 500, body(true)))
        assertEquals(LinkChanged(T0, 1), events.last())
        rx(relayEnv("paired", 499, body(false)))   // 낡은 것
        assertTrue(dao.phoneLink(GUARD)!!.handoffAlerts)
        rx(relayEnv("paired", 501, body(false)))
        assertFalse(dao.phoneLink(GUARD)!!.handoffAlerts)
        assertEquals(LinkChanged(T0, 0), events.last())
        assertTrue(outbox().isEmpty())   // guardian은 G23 대상이 아니다
    }

    @Test fun unpaired_deletesLinkAndBlocksLatePaired() {
        phone(GUARD, "guardian", alerts = true)
        rx(relayEnv("unpaired", 600, """{"device_id":"$GUARD","role":"guardian","by":"self"}"""))
        assertNull(dao.phoneLink(GUARD))
        assertTrue(dao.mark(GUARD)!!.unpaired)
        assertEquals(LinkChanged(T0, 0), events.last())
        rx(relayEnv("paired", 700, """{"device_id":"$GUARD","role":"guardian","platform":"ios","paired_at":"2026-11-05T09:01:44Z","handoff_alerts":true}"""))
        assertNull(dao.phoneLink(GUARD))   // 기기 id는 다시 쓰지 않는다: 끊긴 기기의 paired는 모두 낡은 것
    }

    // ---------------------------------------------------------------- state_request(8.4, G39)

    @Test fun stateRequest_owner_answersOnlyThatPhoneWithoutPending() {
        profile(lastFeeding = at1Ms)
        song(SA, ACTIVE, 10); song(SB, PENDING, 11); song(SC, REJECTED, 12); song(SD, DELETED, 13, last = 14)
        rx(env("state_request", 800, "{}"))   // PhoneLink가 아직 없어도 답한다
        assertEquals(1, outbox("feeding_state").size)
        assertEquals(setOf(SA, SC, SD), outbox("song_status").map { it.body().str("song_id") }.toSet())
        assertTrue(outbox().all { it.toDevice == PHONE })
        val st = outbox("song_status").associate { it.body().str("song_id") to it.body().str("status") }
        assertEquals<Map<String?, String?>>(mapOf(SA to "accepted", SC to "rejected", SD to "removed"), st)
    }

    @Test fun stateRequest_guardian_noAnswer() {
        assertTrue(rx(env("state_request", 801, "{}", from = Sender(Role.GUARDIAN, GUARD))))
        assertTrue(outbox().isEmpty())
    }

    // ---------------------------------------------------------------- 받은 함: seq 순서, 커밋 뒤 ack, 저장 실패면 ack 없음(8.3)

    @Test fun inbox_processesInSeqOrder_soThreeSongRuleIsDeterministic() {
        song(SA, ACTIVE, 10); song(SB, ACTIVE, 11); song(SC, ACTIVE, 12)
        val add = env("song_added", 14, """{"song_id":"$SD","name":"d","duration_s":30}""")
        val del = env("song_removed", 13, """{"song_id":"$SC"}""")
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(add, del), false))   // 순서가 뒤집혀 와도
        assertEquals(Link.Sync.DONE, sync())
        assertEquals(PENDING, dao.song(SD)!!.status)                               // 13(지움)을 먼저 처리 → 자리 2곡
        assertEquals(listOf(del.id, add.id), relay.acked)
    }

    @Test fun inbox_saveFailure_noAckForFailedAndStops() {
        profile(); phone()
        val ok = env("feeding_started", 412, """{"at":"$at1"}""", id = "01JC8Y2K6N3T5Q9W0ZB4H7X2D1")
        val bad = env("feeding_started", 413, """{"at":"2026-11-05T09:00:00+09:00"}""", id = "01JC8Y2K6N3T5Q9W0ZB4H7X2D2")
        // 두 번째 메시지의 트랜잭션만 실패하게 한다(커밋 전에 죽은 것과 같다)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_once BEFORE INSERT ON processed_message WHEN NEW.id = '${bad.id}' BEGIN SELECT RAISE(ABORT, 'test'); END",
        )
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(ok, bad), false))
        assertEquals(Link.Sync.STORE_FAILED, sync())
        assertEquals(listOf(ok.id), relay.acked)                 // 커밋한 것만 ack
        assertEquals(at1Ms, lastFeeding())                       // 실패한 트랜잭션의 수유 시각은 되돌려졌다
        assertEquals(1, events.size)                             // 실패한 메시지는 사건도 없다
        assertFalse(dao.processed(bad.id))
    }

    // C302 A: ack 뒤 만료 정리가 다 실패해도 받기는 DONE이다(던져서 열린 소켓·연결 반복을 끊지 않는다)
    @Test fun c302_expireFailureAfterAck_stillDone() {
        profile(); phone()
        val w = db.openHelper.writableDatabase
        w.execSQL("INSERT INTO processed_message (id, seq, processed_at) VALUES ('old', 1, 0)")   // 만료 정리가 지울 옛 행
        w.execSQL("CREATE TRIGGER fail_expire BEFORE DELETE ON processed_message BEGIN SELECT RAISE(ABORT, 'test'); END")
        val ok = env("feeding_started", 412, """{"at":"$at1"}""")
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(ok), false))
        assertEquals(Link.Sync.DONE, sync())
        assertEquals(listOf(ok.id), relay.acked)
        assertEquals(at1Ms, lastFeeding())
    }

    @Test fun inbox_401_invalidatesCredential() {
        phone()
        relay.inboxPages += Res.Err(401, "unauthorized", null)
        assertEquals(Link.Sync.LOST, sync())
        assertNull(dao.credential())
        assertTrue(dao.phoneLinks().isEmpty())
    }

    @Test fun inbox_unreadableEnvelope_acked_normalOneApplied() {
        profile(); phone()
        val good = env("feeding_started", 413, """{"at":"$at1"}""")
        val unreadable = env("unreadable", 412, "{}", id = "01JC8Y2K6N3T5Q9W0ZB4H7X2D9", v = 0)   // OkRelay.parseInbox가 못 읽는 봉투를 바꾼 모양
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(good, unreadable), false))
        assertEquals(Link.Sync.DONE, sync())
        assertEquals(listOf(unreadable.id, good.id), relay.acked)   // 둘 다 ack(안 그러면 함이 영원히 막힌다)
        assertEquals(at1Ms, lastFeeding())                          // 정상 것은 적용
    }

    @Test fun parseInbox_brokenElementsReplacedOrDropped() {
        val good = ProtocolJson.encodeToString(kr.aigyeot.shared.Envelope.serializer(), env("feeding_started", 413, """{"at":"$at1"}"""))
        val json = """{"messages":[$good,{"v":"x","id":"01JC8Y2K6N3T5Q9W0ZB4H7X2D9","seq":412},{"v":1,"seq":5},7,{"x":1}],"more":true}"""
        val r = parseInbox(json)
        assertEquals(3, r.messages.size)                         // 객체가 아닌 것은 버린다. id만 없는 것은 seq를 위해 남긴다
        assertEquals("", r.messages[2].id); assertEquals(5L, r.messages[2].seq)
        assertEquals(413L, r.messages[0].seq)
        val u = r.messages[1]
        assertEquals(0, u.v); assertEquals("unreadable", u.type); assertEquals("01JC8Y2K6N3T5Q9W0ZB4H7X2D9", u.id); assertEquals(412L, u.seq)
        assertTrue(r.more)
        assertFailsWith<kotlinx.serialization.SerializationException> { parseInbox("not json") }   // OkRelay.exec가 bad_response로 바꾼다
        assertFailsWith<kotlinx.serialization.SerializationException> { parseInbox("[]") }
    }

    @Test fun inbox_idlessUnreadable_advancesCursor_notAcked() {
        val a = ProtocolJson.encodeToString(kr.aigyeot.shared.Envelope.serializer(), env("feeding_started", 3, """{"at":"$at1"}""", id = "01JC8Y2K6N3T5Q9W0ZB4H7X2D1"))
        relay.inboxPages += Res.Ok(200, parseInbox("""{"messages":[$a,{"v":1,"seq":900}],"more":true}"""))
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false))
        sync()
        assertEquals(listOf(0L, 900L), relay.inboxCalls)         // 버려질 뻔한 큰 seq까지 넘어간다
        assertEquals(listOf("01JC8Y2K6N3T5Q9W0ZB4H7X2D1"), relay.acked)
    }
}
