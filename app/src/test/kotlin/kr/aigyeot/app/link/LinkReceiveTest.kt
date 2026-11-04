package kr.aigyeot.app.link

import kotlinx.coroutines.runBlocking
import kr.aigyeot.engine.FeedingFrom
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
