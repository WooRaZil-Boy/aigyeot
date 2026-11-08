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
    private fun rx(e: kr.aigyeot.shared.Envelope) = runBlocking { link.receive(e) }
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
        assertFalse(runBlocking { link.receive(env("feeding_started", 3000, """{"at":"$at1"}"""), old) })   // 거짓: ack하지 않고 멈춘다
        assertEquals(at1Ms - 60_000, lastFeeding())
        assertTrue(events.none { it is FeedingStarted })
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
            events.clear()
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
        engineState = kr.aigyeot.engine.EngineState.fresh(1, null, emptyList(), emptyList(), null, 0).copy(care = CareView(careAt, true, null, null, null, null, null, null, null, 9))
        val e = care()                                                       // 같은 as_of
        assertTrue(rx(e)); assertTrue(events.isEmpty()); assertTrue(dao.processed(e.id))
        assertTrue(rx(care(asOf = "2026-11-06T14:59:59+09:00"))); assertTrue(events.isEmpty())
        assertTrue(rx(care(asOf = "2026-11-06T15:00:01+09:00"))); assertEquals(1, events.size)   // 더 늦으면 낸다
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

    @Test fun c128_newRoom_resetsMaxSeenSeq() {
        now = careAt
        assertTrue(rx(care(seq = 9000))); events.clear()           // 옛 방에서 큰 seq를 받았다
        runBlocking { link.invalidate(); link.credential() }       // 방이 바뀌고 새 방에 등록
        link.handle(kr.aigyeot.engine.DeleteRelayRecords)          // 새 방에서 기록 지우기
        assertTrue((dao.mark("care_clear")?.lastSeq ?: 0L) < 2L, "옛 방 seq가 남지 않는다")
        events.clear(); assertTrue(rx(care(seq = 2))); assertEquals(1, events.size, "새 요약을 버리지 않는다")
    }

    @Test fun c128_noRealSummary_notComparedToSceneValue() {
        now = careAt
        val fresh = kr.aigyeot.engine.EngineState.fresh(1, null, emptyList(), emptyList(), null, 0)
        runBlocking { link.restoreEvents(fresh, now) }             // 실제 요약 없이 복원
        engineState = fresh.copy(care = CareView(careAt + 60_000, true, null, null, null, null, null, null, null, 9))   // 장면 값
        assertTrue(rx(care())); assertEquals(1, events.size, "장면 값이 아니라 실제 요약 없음(null)과 견준다")
    }

    @Test fun c128_event_carriesCareGen() {
        now = careAt
        engineState = kr.aigyeot.engine.EngineState.fresh(1, null, emptyList(), emptyList(), null, 0).copy(careGen = 3)
        assertTrue(rx(care())); assertEquals(3, (events.single() as CareSummary).gen)
    }

    // ---- C130 (연결 계약 8.4 care_summary 줄)
    private fun freshState(gen: Int) = kr.aigyeot.engine.EngineState.fresh(1, null, emptyList(), emptyList(), null, 0).copy(careGen = gen)

    @Test fun c130_receiveInProgress_clearInBetween_oldSummaryNotRevivedInNewGen() {   // 수신 도중 지우기
        now = careAt
        engineState = freshState(0)
        runBlocking { link.restoreEvents(engineState!!, now) }            // 실제 요약 없음으로 시작(restored)
        val old = care(asOf = "2026-11-06T15:00:30+09:00", seq = 430)
        engineState = freshState(1); link.handle(kr.aigyeot.engine.DeleteRelayRecords)   // 받기를 시작한 뒤 지우기가 끝났다
        assertTrue(runBlocking { link.receive(old, null, gen = 0) })      // 받기를 시작할 때의 세대
        assertTrue(events.isEmpty(), "새 세대로 되살리지 않는다"); assertTrue(dao.processed(old.id), "버려도 ack")
        assertTrue(rx(care(asOf = "2026-11-06T15:00:00+09:00", seq = 431))); assertEquals(1, events.size, "옛 요약이 실제 값을 바꾸지 않았다")
        assertEquals(1, (events.single() as CareSummary).gen)
    }

    @Test fun c130_inboxRequestStartedBeforeClear_lateResponseDropped() {   // 삭제 전에 시작한 받은 함 요청
        now = careAt
        engineState = freshState(0)
        runBlocking { link.restoreEvents(engineState!!, now) }
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(care(seq = 430)), false))
        relay.onInbox = { engineState = freshState(1); link.handle(kr.aigyeot.engine.DeleteRelayRecords) }   // 응답이 오기 전에 지우고 삭제까지 끝났다
        sync()
        relay.onInbox = {}
        assertTrue(events.none { it is CareSummary }); assertEquals(1, relay.acked.size, "버려도 ack")
    }

    @Test fun c130_socketFrame_usesGenAtArrival() {   // WebSocket 프레임도 받은 시점의 세대
        now = careAt
        profile(); phone()
        engineState = freshState(0)
        val f = care(seq = 430)
        relay.onInbox = { engineState = freshState(1) }                   // 프레임은 먼저 도착했고(gen 0) 첫 동기화 중 지우기가 끝난다
        relay.onSocket = { ev -> if (relay.socketCalls == 1) { ev.onOpen(); ev.onText(ProtocolJson.encodeToString(kr.aigyeot.shared.Envelope.serializer(), f)) } else ev.onClosed(-1) }
        link.start()
        val end = System.currentTimeMillis() + 5_000
        while (!dao.processed(f.id) && System.currentTimeMillis() < end) Thread.sleep(20)
        link.stop()
        assertTrue(dao.processed(f.id)); assertTrue(events.none { it is CareSummary })
    }

    @Test fun c132_genUnknownBeforeRestore_linkDoesNotStartReceiving() {   // 복원 전에는 받지 않고, 복원 뒤 복원 세대로 받는다
        now = careAt
        profile(); phone()
        engineState = null
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(care(seq = 430)), false))
        relay.onSocket = { ev -> ev.onClosed(-1) }
        link.start()
        Thread.sleep(400)
        assertEquals(0, relay.socketCalls, "세대를 모르면 연결·받기를 시작하지 않는다"); assertTrue(relay.inboxCalls.isEmpty())
        engineState = freshState(1)
        val end = System.currentTimeMillis() + 5_000
        while (events.none { it is CareSummary } && System.currentTimeMillis() < end) Thread.sleep(20)
        link.stop()
        assertEquals(1, (events.single { it is CareSummary } as CareSummary).gen, "복원 세대로 받는다")
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
        runBlocking { link.restoreEvents(freshState(0).copy(care = c, careSeq = 9000, careRoom = "c_TESTCAR"), now) }
        assertTrue(rx(care(feeds = "4", seq = 2))); assertTrue(events.isEmpty(), "같은 방이라 옛 seq 9000과 견줘 버린다")
        assertTrue(rx(care(feeds = "4", seq = 2).copy(car = "c_B"))); assertEquals(1, events.size, "다른 방이면 나중에 받은 것")
    }

    private fun careView(at: Long) = CareView(at, true, null, null, null, null, null, null, null, 1)

    @Test fun c133_sceneResume_noRefill_lateRealSummaryStillApplies() {   // 실제 14:50 -> 장면 14:59 -> 장면 중 Resume 대조 -> 지연된 실제 14:58
        now = careAt
        val real = freshState(0).copy(care = careView(careAt - 600_000))
        runBlocking { link.restoreEvents(real, now) }
        val scene = real.copy(care = careView(careAt - 60_000))
        engineState = scene
        runBlocking { link.restoreEvents(scene, now, refillCare = false) }
        assertTrue(rx(care(asOf = "2026-11-06T14:58:00+09:00", seq = 430)))
        assertEquals(1, events.size, "장면 값 14:59에 덮이지 않아 실제 14:50보다 늦은 14:58을 낸다")
    }

    @Test fun c133_clearBetweenRequestStartAndApply_oldSummaryNotRefilled_nextOneApplies() {   // 지우기 표시와 새 세대 공개 사이에 끝난 옛 수신
        now = careAt
        engineState = freshState(0)
        runBlocking { link.restoreEvents(engineState!!, now) }
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(care(asOf = "2026-11-06T15:00:30+09:00", seq = 430)), false))
        relay.onInbox = { link.handle(kr.aigyeot.engine.DeleteRelayRecords) }   // 공개된 세대는 아직 0
        sync(); relay.onInbox = {}
        assertTrue(events.none { it is CareSummary }, "옛 요약은 실제 값에 들어가지 않는다")
        assertTrue(rx(care(asOf = "2026-11-06T15:00:00+09:00", seq = 431))); assertEquals(1, events.count { it is CareSummary }, "삭제 뒤 새 요약은 받는다")
    }

    @Test fun c135_clearDoneBeforeNewGenPublished_oldGenSummary_doesNotBlockNewGenSummary() {   // 지우기 표시 뒤 새 세대 공개 전 요약(asOf 15:00:30) -> 새 세대 정상 요약(15:00)
        now = careAt
        engineState = freshState(0)
        runBlocking { link.restoreEvents(engineState!!, now) }
        link.handle(kr.aigyeot.engine.DeleteRelayRecords)
        assertTrue(rx(care(asOf = "2026-11-06T15:00:30+09:00", seq = 430)))   // 옛 세대(0)로 실제 기준을 채운다
        engineState = freshState(1); events.clear()                         // 새 세대 공개
        assertTrue(rx(care(asOf = "2026-11-06T15:00:00+09:00", seq = 431)))
        assertEquals(1, events.count { it is CareSummary }, "다른 세대의 기준과는 견주지 않는다")
    }

    @Test fun c133_clearThenNewSummary_noScene_applies_andSameAsOfSeqCompared() {
        now = careAt
        assertTrue(rx(care(feeds = "3", seq = 430))); events.clear()
        link.handle(kr.aigyeot.engine.DeleteRelayRecords)
        assertTrue(rx(care(feeds = "4", seq = 431))); assertEquals(1, events.size)
        assertTrue(rx(care(feeds = "5", seq = 430))); assertEquals(1, events.size, "같은 방 같은 asOf는 seq로 견준다")
    }

    private fun clearRow() = kr.aigyeot.app.link.db.OutboxEntity(ulid(now), "records_clear", null, "", now, 0, now).also { dao.insertOutbox(it) }

    @Test fun c126_clearPendingInOutbox_dropsCareSummaryUntilDeleteDone() {
        now = careAt
        val row = clearRow()                                                      // 지웠고 중계 삭제는 아직 보낼 함에 있다(오프라인 등)
        val e = care(seq = 430); assertTrue(rx(e)); assertTrue(events.isEmpty()); assertTrue(dao.processed(e.id))   // 버리고 ack
        dao.deleteOutbox(row.id)                                                  // 삭제가 끝난 뒤
        assertTrue(rx(care(seq = 431))); assertEquals(1, events.size)             // 새 요약은 적용
    }

    @Test fun c126_fetchedBatch_processedAfterClear_dropped_thenNewSummaryApplies() {
        now = careAt
        profile(lastFeeding = at1Ms - 60_000)
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(env("feeding_started", 429, """{"at":"$at1"}"""), care(seq = 430)), false))
        onPost = { if (it is FeedingStarted) { val r = clearRow(); link.handle(kr.aigyeot.engine.DeleteRelayRecords); dao.deleteOutbox(r.id) } }   // 받아 온 묶음을 처리하는 중에 지우고 삭제까지 끝났다
        sync()
        onPost = {}
        assertTrue(events.none { it is CareSummary }, "묶음 안 옛 요약은 버린다")
        assertEquals(430L, dao.mark("care_clear")!!.lastSeq)
        assertTrue(relay.acked.size == 2, "버려도 ack")
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(care(seq = 431)), false)); sync()
        assertEquals(1, events.filterIsInstance<CareSummary>().size)              // 지운 뒤 새 요약은 적용
        assertEquals(431L, (events.last() as CareSummary).seq)
    }

    // ---- 삭제 완료(pumpOnce)도 지우기다: 완료 전에 시작한 요청·도착한 프레임의 옛 요약은 되살아나지 않는다(C134 ①)
    private fun prepClearPending() {
        now = careAt
        engineState = freshState(0)
        runBlocking { link.restoreEvents(engineState!!, now) }
        link.handle(kr.aigyeot.engine.DeleteRelayRecords); clearRow().let { dao.deleteOutbox(it.id); dao.insertOutbox(it.copy(nextTryAt = now + 60_000)) }   // 지웠고 records_clear는 보낼 함에서 기다린다(백그라운드 펌프가 먼저 보내지 않게)
    }

    @Test fun c134_inboxRequestStartedWhileClearPending_deleteDoneBeforeResponse_oldSummaryDropped() {
        prepClearPending()
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(care(seq = 430)), false))
        relay.onInbox = { now += 61_000; link.pumpOnce(); relay.onInbox = {} }                  // 응답이 늦는 사이 중계 삭제가 끝나 clearPending이 풀렸다
        sync()
        assertTrue(outbox("records_clear").isEmpty())
        assertTrue(events.none { it is CareSummary }, "지운 요약이 되살아나지 않는다"); assertEquals(1, relay.acked.size, "버려도 ack")
        assertTrue(rx(care(seq = 431))); assertEquals(1, events.count { it is CareSummary }, "삭제 완료 뒤 시작한 새 요약은 적용")
    }

    private fun frameText(e: kr.aigyeot.shared.Envelope) = ProtocolJson.encodeToString(kr.aigyeot.shared.Envelope.serializer(), e)

    @Test fun c134_socketFrameQueuedWhileClearPending_deleteDone_oldSummaryDropped() {
        prepClearPending(); profile(); phone()
        val f = care(seq = 430)
        relay.onInbox = { now += 61_000; link.pumpOnce(); relay.onInbox = {} }                  // 프레임은 삭제 대기 중 도착해 큐에서 기다리고, 첫 동기화 중 삭제가 끝난다
        relay.onSocket = { ev -> if (relay.socketCalls == 1) { ev.onOpen(); ev.onText(frameText(f)) } else ev.onClosed(-1) }
        link.start()
        wakingUntil { relay.socketCalls >= 2 }                                                  // C139: 삭제 완료가 소켓을 닫아 대기 프레임을 버리고 다시 연결한다
        link.stop()
        assertFalse(dao.processed(f.id)); assertTrue(events.none { it is CareSummary })
    }

    private fun wakingUntil(cond: () -> Boolean) = eventually { link.wake(); cond() }

    @Test fun c139_deleteDone_thenOldSocketFrameFirstArrives_ignored_newSocketSummaryApplies() {
        prepClearPending(); profile(); phone()
        val old = care(seq = 430); val fresh = care(seq = 431)
        lateinit var ev1: kr.aigyeot.app.link.SocketEvents; lateinit var ev2: kr.aigyeot.app.link.SocketEvents
        relay.onSocket = { ev -> if (relay.socketCalls == 1) ev1 = ev else ev2 = ev; ev.onOpen() }
        link.start()
        eventually { relay.socketCalls >= 1 && relay.inboxCalls.isNotEmpty() }
        now += 61_000; runBlocking { link.pumpOnce() }                                          // 중계 삭제 204 처리(clearEpoch 올림): 프레임은 아직 안 왔다
        ev1.onText(frameText(old))                                                              // 닫은 소켓의 늦은 프레임이 이제 처음 도착
        eventually { relay.socketCalls >= 2 }
        Thread.sleep(300)
        assertFalse(dao.processed(old.id)); assertTrue(events.none { it is CareSummary }, "지운 기록이 복원되지 않는다")
        assertEquals(2, relay.socketCalls)
        ev2.onText(frameText(fresh))                                                            // 새 소켓의 정상 프레임은 소켓 세대 검사를 통과해 적용
        eventually { dao.processed(fresh.id) && relay.socketSent.any { fresh.id in it } }   // ack는 처리 뒤 소켓 코루틴에서 나간다
        assertEquals(1, events.count { it is CareSummary }, "삭제 완료 뒤 새 요약은 적용"); assertTrue(relay.socketSent.any { fresh.id in it }, "새 소켓으로 ack")
        link.stop()
    }

    @Test fun c139_droppedFrame_otherMessage_redeliveredAfterReconnect_noLoss_socketReopenedOnce() {
        prepClearPending(); profile(lastFeeding = at1Ms - 60_000); phone()
        val care430 = care(seq = 430); val feed = env("feeding_started", 412, """{"at":"$at1"}""")
        relay.onSocket = { ev -> if (relay.socketCalls == 1) { ev.onOpen(); ev.onText(frameText(care430)); ev.onText(frameText(feed)) } else ev.onOpen() }
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false))                     // 첫 동기화는 비어 있다
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(feed), false))                    // 다시 연결 뒤 받은 함은 ack되지 않은 feed를 다시 준다
        relay.onInbox = { now += 61_000; link.pumpOnce(); link.invalidate(); phone(); relay.onInbox = {} }   // 삭제 완료와 방 바뀜이 겹친다
        link.start()
        eventually { dao.processed(feed.id) }
        Thread.sleep(300)
        link.stop()
        assertEquals(at1Ms, lastFeeding(), "버린 프레임의 다른 메시지는 다시 받아 적용")
        assertFalse(dao.processed(care430.id)); assertTrue(events.none { it is CareSummary })
        assertEquals(2, relay.socketCalls, "삭제 완료·invalidate가 겹쳐도 소켓은 한 번만 다시 열린다")
    }

    @Test fun c134_noScene_clearThenDeleteDone_newSummaryApplies_sameAsOfSeqCompared() {   // C126 ②·같은 방 같은 asOf
        prepClearPending()
        now += 61_000; runBlocking { link.credential(); link.pumpOnce() }; now = careAt
        assertTrue(rx(care(feeds = "4", seq = 431))); assertEquals(1, events.count { it is CareSummary })
        assertTrue(rx(care(feeds = "5", seq = 430))); assertEquals(1, events.count { it is CareSummary }, "같은 방 같은 asOf는 seq로 견준다")
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
        assertEquals(SA, candidateSong(dao.songs(), dao.selection()))
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

    @Test fun songSelected_deletedSongKeepsSelectionRecord() {
        song(SA, ACTIVE, 10); song(SB, ACTIVE, 11)
        rx(env("song_selected", 30, """{"song_id":"$SA"}"""))
        rx(env("song_removed", 31, """{"song_id":"$SA"}"""))
        assertEquals(SA, dao.selection()!!.songId)                    // 선택 기록은 곡을 지워도 남긴다
        assertEquals(SB, candidateSong(dao.songs(), dao.selection()))
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
