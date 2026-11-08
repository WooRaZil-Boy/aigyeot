package kr.aigyeot.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 5.7 인계와 상한(3-5·3-12·4.3).
class S57HandoffTest {
    private fun Sim.alert() = all<SendToPhone>().single { it.type == "handoff_alert" }.body
    /** 쉼터 제안 인계 뒤 Ask까지 낸 상태. */
    private fun asked(vararg rests: RestCandidate) = session().route(*rests).handoff().flush()
    private fun Sim.ask() = all<Ask>().last()
    private fun Sim.cap(leftMs: Long) = apply { s = s.copy(session = ses.copy(playedMs = P.sessionPlayCapMs - leftMs)) }

    @Test fun h1_handoffInPark_onlyGuardianAlert() {
        val sim = session().route(rest("a", 5.0)).gear(Gear.P, MIN).handoff()
        assertEquals(HandoffKind.PARKED, sim.ep.handoff!!.kind)
        assertEquals(JsonPrimitive("parked"), sim.alert()["kind"])
        sim.flush()
        assertTrue(sim.all<Ask>().isEmpty())
        assertTrue(sim.sids().none { it in setOf(Sid.S07, Sid.S08, Sid.S10, Sid.S15) })
    }

    @Test fun h2_nearDestination_saysS15() {
        val sim = session().route(rest("a", 5.0), dest = true, remainingSec = 530).handoff()
        assertEquals(HandoffKind.ARRIVAL_NOTICE, sim.ep.handoff!!.kind)
        assertEquals(JsonPrimitive(9), sim.alert()["minutes_to_arrival"])
        sim.flush()
        assertEquals(mapOf("minutes" to "9"), sim.all<Speak>().single { it.sid == Sid.S15 }.vars)
        assertTrue(sim.all<Ask>().isEmpty())
    }

    @Test fun h3_restPick_30kmAnd5km() {
        val sim = asked(rest("a", 10.0), rest("n", 14.0, nursing = true), rest("far", 31.0, nursing = true))
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        val ask = sim.ask()
        assertEquals(Sid.S07, ask.sid); assertEquals(6_000, ask.listenMs)
        assertEquals(PendingAsk(ask.askId, Sid.S07, "n", sim.ep.id, true, restLogIndex = 0), sim.ses.pendingAsk)
        assertEquals("rest_offer", sim.alert()["kind"]!!.jsonPrimitive.content)
        assertEquals("n 휴게소", sim.alert()["rest_name"]!!.jsonPrimitive.content)
        assertEquals("아기가 계속 울어서 n 휴게소에 들르자고 운전자에게 제안했어요.", sim.alert()["text"]!!.jsonPrimitive.content)
        val b = asked(rest("a", 10.0), rest("n", 15.5, nursing = true))
        assertEquals(Sid.S08, b.ask().sid); assertEquals("a", b.ses.pendingAsk!!.restId)
    }

    @Test fun h3_offerBlocked_alertWithoutRestName() {
        val sim = session().route(rest("n", 14.0, nursing = true))
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(spokenAt = mapOf(Sid.S07_DEPART to sim.t)) })   // 출발 제안 직후(S3a 10분 금지)
        sim.handoff().flush()
        assertTrue(sim.all<Ask>().none { it.sid == Sid.S07 })
        val b = sim.alert()
        assertEquals("rest_offer", b["kind"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, b["rest_name"])
        assertEquals("아기가 계속 울어요.", b["text"]!!.jsonPrimitive.content)
    }

    // ---- H10: 인계 알림은 그 줄을 실제로 낼 때 보낸다 ----
    private fun Sim.busyNow() = ev(VehicleSample(t, 60.0, true, 0.0, TurnSignal.NONE))
    internal fun Sim.alerts() = all<SendToPhone>().filter { it.type == "handoff_alert" }
    /** 바쁨 중에 인계해 쉼터 질문 S07이 큐에서 미뤄진 상태. */
    private fun heldOffer(): Sim {
        val sim = session().route(rest("n", 14.0, nursing = true))
        sim.attempt2().toGap().busyNow().recry()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertTrue(sim.all<Ask>().isEmpty())
        assertNotNull(sim.ledger)
        return sim
    }

    @Test fun h10_alertWaitsForAsk() {
        val sim = heldOffer()
        assertTrue(sim.alerts().isEmpty())
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        if (sim.all<Ask>().isEmpty()) sim.flush()
        assertEquals(Sid.S07, sim.all<Ask>().single().sid)
        val b = sim.alerts().single().body                      // 바로 + 나중 두 번이 아니라 한 번
        assertEquals("n 휴게소", b["rest_name"]!!.jsonPrimitive.content)
        assertEquals("rest_offer", b["kind"]!!.jsonPrimitive.content)
        assertNull(sim.ledger)
        sim.flush(); sim.tick(60_000)
        assertEquals(1, sim.alerts().size)
    }

    @Test fun h10_droppedOfferSendsPlainAlert() {
        // C251: 인계에 묶인 줄은 경로에서 쉼터가 빠져도 버리지 않고 다시 고른다(쉼터가 없으면 S10, 알림은 no_rest). 이름 없는 알림은 통화로 버려진 줄에서 본다
        val a = heldOffer()
        a.ev(RouteState(a.t, false, null, emptyList()))
        a.ev(Tick(a.s.steer.driveBusyAt!! + P.holdStableMs))
        a.flush()
        assertTrue(a.all<Ask>().isEmpty() && a.all<Speak>().any { it.sid == Sid.S10 })
        val b = a.alerts().single().body
        assertEquals(JsonNull, b["rest_name"]); assertEquals("no_rest", b["kind"]!!.jsonPrimitive.content)
        assertEquals(a.ep.id, b["episode_id"]!!.jsonPrimitive.content)
        // 통화로 버려짐(cancelSpeech) — 그 Step에 이름 없는 알림
        val c = heldOffer()
        c.ev(CallMode(c.t, true))
        val b2 = c.alerts().single().body
        assertEquals(JsonNull, b2["rest_name"]); assertEquals("아기가 계속 울어요.", b2["text"]!!.jsonPrimitive.content)
        assertNull(c.ledger)
        c.ev(CallMode(c.t, false)).flush()
        assertEquals(1, c.alerts().size)
    }

    @Test fun h10_noQueuedOfferSendsPlainAlertNow() {
        val sim = session().route(rest("n", 14.0, nursing = true))
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(spokenAt = mapOf(Sid.S07_DEPART to sim.t)) })   // 10분 금지: 질문이 큐에 못 든다
        sim.handoff()
        assertNull(sim.ledger)
        val b = sim.alerts().single().body
        assertEquals(JsonNull, b["rest_name"]); assertEquals("아기가 계속 울어요.", b["text"]!!.jsonPrimitive.content)
        sim.flush()
        assertEquals(1, sim.alerts().size)
    }

    @Test fun h10_existingQueuedOffer_alertWaitsForIt() {
        // 이미 큐에 같은 S07이 있어 offer()가 새 줄을 넣지 않아도, 알림은 그 줄에 묶인다
        val sim = session().route(rest("n", 14.0, nursing = true))
        sim.attempt2().toGap().busyNow()
        val old = Line(Sid.S07, mapOf("restName" to "n 휴게소", "distanceKm" to "14"), "n", queuedAt = sim.t)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = sim.s.speech.queue + old))
        sim.recry()
        assertTrue(sim.alerts().isEmpty())
        assertEquals(old.queuedAt, sim.s.speech.queue.single { it.isHandoffOf(sim.ses.id) }.queuedAt, "장부가 그 줄에 묶인다")
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs)); sim.flush()
        assertEquals(Sid.S07, sim.all<Ask>().single().sid)
        assertEquals("n 휴게소", sim.alerts().single().body["rest_name"]!!.jsonPrimitive.content)
    }

    @Test fun h10_noRest_droppedS10_sendsPlainNoRestAlert() {
        val sim = session()
        sim.attempt2().toGap().busyNow().recry()               // 쉼터 없음 H7: S10이 바쁨으로 미뤄짐
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind)
        assertTrue(sim.alerts().isEmpty()); assertNotNull(sim.ledger)
        sim.ev(CallMode(sim.t, true))                          // 통화로 버려짐
        val b = sim.alerts().single().body
        assertEquals("no_rest", b["kind"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, b["rest_name"]); assertEquals("아기가 계속 울어요.", b["text"]!!.jsonPrimitive.content)
        assertNull(sim.ledger)
        // 말하게 되면 기존 no_rest 문장 한 번
        val ok = session(); ok.attempt2().toGap().busyNow().recry()
        ok.ev(Tick(ok.s.steer.driveBusyAt!! + P.holdStableMs)); ok.flush()
        assertTrue(Sid.S10 in ok.sids())
        assertEquals("아기가 계속 울어요. 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요.", ok.alerts().single().body["text"]!!.jsonPrimitive.content)
    }

    // C357: 쉼터가 있어도 넣을 수 없으면(addable=false) 후보가 비므로 "넣을 수 있는" 쉼터를 못 찾았다고 말한다
    @Test fun c357_onlyUnaddableRest_saysS10WithAddableWording() {
        val sim = session().route(RestCandidate("x", "x 휴게소", RestKind.REST_AREA, 5.0, true, addable = false))
        sim.attempt2().toGap().recry().flush()
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind)
        assertTrue(Sid.S10 in sim.sids())
        assertEquals("아기가 계속 울어요. 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요.", sim.alerts().single().body["text"]!!.jsonPrimitive.content)
    }

    @Test fun h10_sessionClosedWithPending_sendsPlainAlert() {
        val sim = heldOffer()
        val sid = sim.ses.id
        // P 처리 단계에서 큐 줄이 먼저 버려지지 않게 기어만 P로 둔 채 뒷문을 연다: closeSession()의 강제 처리만 알림을 보낼 수 있다
        sim.s = sim.s.copy(car = sim.s.car.copy(gear = Gear.P))
        assertNotNull(sim.ledger); assertTrue(sim.alerts().isEmpty())
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session)
        val b = sim.alerts().single().body
        assertEquals(JsonNull, b["rest_name"]); assertEquals("아기가 계속 울어요.", b["text"]!!.jsonPrimitive.content)
        assertEquals(sid, b["session_id"]!!.jsonPrimitive.content)
        sim.flush()
        assertEquals(1, sim.alerts().size)
    }

    /** C140: 말하던 S12가 끝나기 전에 세션이 닫히면 대기 인계의 S07이 큐에 남아 다음 세션에서 나가면 안 된다. */
    private fun closedWhileSpeaking(vararg extra: Line): Sim {
        val sim = heldOffer()
        val s12 = Line(Sid.S12, emptyMap(), null)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(s12) + sim.s.speech.queue + extra), car = sim.s.car.copy(gear = Gear.P))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session)
        return sim
    }

    @Test fun c140_closeSession_dropsBoundHandoffLine_keepsSpeaking() {
        val sim = closedWhileSpeaking()
        assertEquals(listOf(Sid.S12, Sid.S02, Sid.S03), sim.s.speech.queue.map { it.sid }, "말하던 S12와 세션 끝 안내만 남는다")
        assertEquals("u1", sim.s.speech.speaking)
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true)); sim.ev(Tick(sim.t + 8_000)); sim.flush()
        assertTrue(sim.all<Ask>().isEmpty(), "새 세션에서 Ask(S07)이 나가지 않는다")
        assertEquals(1, sim.alerts().size)
    }

    @Test fun c140_closeSession_dropsPlainOffers_keepsUnrelated() {
        val sim = closedWhileSpeaking(Line(Sid.S10, emptyMap(), null, queuedAt = 1), Line(Sid.S11, emptyMap(), null, queuedAt = 1), Line(Sid.S17, emptyMap(), null))
        assertEquals(listOf(Sid.S12, Sid.S02, Sid.S03, Sid.S17), sim.s.speech.queue.map { it.sid })
    }

    @Test fun c140_closeSession_speakingOfferLine_keptAndToldRecorded() {
        val (sim, _) = speakingOffer()   // C131: 말하는 중인 S07은 이미 told=true로 기록됐다
        sim.s = sim.s.copy(car = sim.s.car.copy(gear = Gear.P))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session)
        assertEquals(listOf(Sid.S07, Sid.S02, Sid.S03), sim.s.speech.queue.map { it.sid }, "말하던 줄은 끝까지 둔다")
        assertTrue(sim.alerts().isEmpty(), "C152: 말하는 줄이 끝날 때까지 알림을 미룬다")
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertEquals(1, sim.alerts().size)
    }

    @Test fun h10_nullQueuedAtOldLine_boundAndToldDriverTrue() {
        // C137: queuedAt이 없는 옛 S10이 큐에서 기다려도 같은 줄로 묶는다(null도 값). 말하면 toldDriver = true
        val sim = session()
        sim.attempt2().toGap().busyNow()
        val old = Line(Sid.S10, emptyMap(), null, queuedAt = null)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = sim.s.speech.queue + old))
        sim.recry()
        assertNotNull(sim.ledger, "null 줄도 묶는다"); assertNull(sim.s.speech.queue.single { it.isHandoffOf(sim.ses.id) }.queuedAt)
        assertNull(sim.told()); assertTrue(sim.alerts().isEmpty())
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs)); sim.flush()
        assertTrue(Sid.S10 in sim.sids()); assertEquals(1, sim.alerts().size)
        assertNull(sim.ledger)
        assertEquals(true, sim.told())
    }

    @Test fun h10_boundToReturnedLine_notOldQueued() {
        // 수유 시간대 A(nursing) 질문 S07이 미뤄져 있고, 경로는 B 일반 쉼터만 남은 채 인계 → 새 S08(B)에 묶는다(C130)
        val sim = session().route(rest("b", 10.0))
        sim.attempt2().toGap().busyNow()
        val old = Line(Sid.S07, mapOf("restName" to "a 휴게소", "distanceKm" to "14"), "a", queuedAt = sim.t)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = sim.s.speech.queue + old))
        sim.recry()
        assertNotNull(sim.ledger)
        val bound = sim.s.speech.queue.single { it.isHandoffOf(sim.ses.id) }
        assertEquals(Sid.S08, bound.sid); assertEquals("b", bound.askRestId)
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs)); sim.flush()
        assertEquals(Sid.S08, sim.all<Ask>().single().sid)
        assertEquals("b 휴게소", sim.alerts().single().body["rest_name"]!!.jsonPrimitive.content)
    }

    /** C131·C146: 인계가 돌려받은 줄이 이미 말하는 중(큐 맨 앞)이면 그 발화 id로 묶고 그 SpeechDone을 기다린다. */
    internal fun speakingOffer(notifyGuardians: Boolean = true, rests: Array<RestCandidate> = arrayOf(rest("n", 14.0, nursing = true))): Pair<Sim, Line> {
        val sim = session().route(*rests)
        sim.attempt2().toGap().busyNow()
        if (!notifyGuardians) sim.s = sim.s.copy(otherGuardians = 0)
        val sid = if (rests.isEmpty()) Sid.S10 else Sid.S07
        val old = Line(sid, if (rests.isEmpty()) emptyMap() else mapOf("restName" to "n 휴게소", "distanceKm" to "14"), rests.firstOrNull()?.id, queuedAt = sim.t)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(old) + sim.s.speech.queue))
        sim.recry()
        return sim to old
    }

    @Test fun h10_returnedLineAlreadySpeaking_boundUntilDone() {
        val (sim, _) = speakingOffer()
        // C146: 바로 보내지 않고 말하는 줄의 SpeechDone을 기다린다
        assertEquals("u1", sim.ledger!!.utteranceId)
        assertTrue(sim.alerts().isEmpty()); assertNull(sim.told())
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertNull(sim.ledger)
        val b = sim.alerts().single().body
        assertEquals("rest_offer", b["kind"]!!.jsonPrimitive.content)
        assertEquals("n 휴게소", b["rest_name"]!!.jsonPrimitive.content)
        assertEquals(true, sim.told()); assertEquals("n 휴게소", sim.settled()?.restName)
        sim.doneMarks(sim.saved(), true, "n 휴게소")
        sim.flush()
        assertEquals(1, sim.alerts().size)
    }

    /** C133 ①: 수유 시간대 S07이 큐에 있어(결정 ②: 큐가 같은 문장을 막는다) 상한 인계가 와도, 10분 금지로 놓치지 않고 그 줄에 묶거나(대기) 지금 알린다(말하는 중). */
    private fun capAfterOffer(waitSpeech: Int): Sim {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("n", 14.0, nursing = true)).cap(35_000).alarm()
        sim.started().tick(1_000)
        assertEquals(listOf(Sid.S02, Sid.S06, Sid.S07), sim.sids())
        assertTrue(offerBanned(sim.ses.spokenAt, sim.s.speech.queue, sim.ses.id, Sid.S07, sim.t))
        repeat(waitSpeech) { sim.done().tick(P.speechGapMs) }
        sim.ev(Tick(sim.play().endAt))
        assertEquals(HandoffCause.SESSION_CAP, sim.ep.handoff!!.cause)
        return sim
    }

    @Test fun c133_lastOfferRecorded_queuedLine_boundNotMissed() {
        val sim = capAfterOffer(0)
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind)
        assertEquals(Sid.S07, sim.s.speech.queue.single { it.isHandoffOf(sim.ses.id) }.sid)
        sim.flush()
        assertEquals(true, sim.told()); assertEquals("n 휴게소", sim.settled()?.restName)
        assertEquals("n 휴게소", sim.alert()["rest_name"]!!.jsonPrimitive.content)
    }

    @Test fun c133_lastOfferRecorded_speakingLine_boundUntilDone() {
        val sim = capAfterOffer(2)
        assertEquals("S07", sim.s.speech.queue.first().sid.name)
        assertNotNull(sim.ledger!!.utteranceId)   // C146: 말하는 줄이 끝나야 알림
        assertTrue(sim.alerts().isEmpty())
        sim.done()
        assertNull(sim.ledger)
        assertEquals(true, sim.told()); assertEquals("n 휴게소", sim.settled()?.restName)
        assertEquals("n 휴게소", sim.alerts().single().body["rest_name"]!!.jsonPrimitive.content)
    }

    @Test fun h10_returnedLineAlreadySpeaking_boundUntilDone_noRestAndNoGuardian() {
        val (h7, _) = speakingOffer(rests = emptyArray())
        assertEquals(HandoffKind.NO_REST, h7.ep.handoff!!.kind)
        assertTrue(h7.alerts().isEmpty()); assertNull(h7.told())   // C146
        h7.ev(SpeechDone(h7.t + 1_000, "u1", true)); h7.flush()
        assertEquals("아기가 계속 울어요. 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요.", h7.alerts().single().body["text"]!!.jsonPrimitive.content)
        assertEquals(true, h7.told())
        val (h8, _) = speakingOffer(notifyGuardians = false)
        assertNull(h8.told())
        h8.ev(SpeechDone(h8.t + 1_000, "u1", true)); h8.flush()
        assertTrue(h8.alerts().isEmpty())
        assertEquals(true, h8.told()); assertEquals("n 휴게소", h8.settled()?.restName)
        h8.doneMarks(h8.saved(), true, "n 휴게소")
    }

    // ---- H11: 인계 문장을 냈는지 기록 ----
    /** 마지막 인계 확정(SettleHandoff). 아직 없으면 null(옛 '행의 두 칸이 null'). */
    private fun Sim.saved() = all<SettleHandoff>().lastOrNull()
    private fun Sim.doneMarks(r: SettleHandoff?, told: Boolean?, name: String?) {
        assertEquals(told, r?.toldDriver); assertEquals(name, r?.restName)
    }

    @Test fun h11_toldDriverAndRestName_recorded() {
        val a = asked(rest("n", 14.0, nursing = true))                       // H3 바로 냄
        assertEquals(true, a.told()); assertEquals("n 휴게소", a.settled()?.restName)
        a.doneMarks(a.saved(), true, "n 휴게소")
        val b = heldOffer()                                                  // 미뤘다가 냄: 그때까지 기록은 null
        b.doneMarks(b.saved(), null, null)
        b.ev(Tick(b.s.steer.driveBusyAt!! + P.holdStableMs)); b.flush()
        b.doneMarks(b.saved(), true, "n 휴게소")
        val h2 = session().route(rest("a", 5.0), dest = true, remainingSec = 530).handoff().flush()
        h2.doneMarks(h2.saved(), true, null)
        val h7 = session(); h7.attempt2().toGap().busyNow().recry()          // H7 S10 미뤘다가 냄
        h7.ev(Tick(h7.s.steer.driveBusyAt!! + P.holdStableMs)); h7.flush()
        assertTrue(Sid.S10 in h7.sids()); h7.doneMarks(h7.saved(), true, null)
        val h1 = session().route(rest("a", 5.0)).gear(Gear.P, MIN).handoff()
        h1.doneMarks(h1.saved(), null, null)
    }

    @Test fun h11_droppedLine_toldDriverFalse() {
        val call = heldOffer(); call.ev(CallMode(call.t, true))               // 통화로 버려짐
        call.doneMarks(call.saved(), false, null)
        val blocked = session().route(rest("n", 14.0, nursing = true))        // 10분 금지로 큐에 못 듦
        blocked.s = blocked.s.copy(session = blocked.ses.withOffers { copy(spokenAt = mapOf(Sid.S07_DEPART to blocked.t)) })
        blocked.handoff()
        blocked.doneMarks(blocked.saved(), false, null); assertEquals(false, blocked.told())
        val h7 = session(); h7.attempt2().toGap().busyNow().recry(); h7.ev(CallMode(h7.t, true))   // S10 버려짐
        h7.doneMarks(h7.saved(), false, null)
        val noAlert = session().route(rest("a", 5.0), dest = true, remainingSec = 530)   // S15를 통화 중이라 못 냄
        noAlert.attempt2().toGap(); noAlert.s = noAlert.s.copy(car = noAlert.s.car.copy(inCall = true)); noAlert.recry()
        noAlert.doneMarks(noAlert.saved(), false, null)
    }

    @Test fun h11_sessionClosed_toldDriverFalse() {
        val sim = heldOffer()
        sim.s = sim.s.copy(car = sim.s.car.copy(gear = Gear.P))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session)
        sim.doneMarks(sim.saved(), false, null)
    }

    @Test fun h11_episodeAlreadyClosed_rowUpdated() {
        val sim = heldOffer()
        val epId = sim.ep.id
        // 에피소드가 먼저 닫힘(운전자 정지 요청 등): 닫힌 행에는 아직 null
        sim.ev(Tick(sim.t + 6 * MIN))
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs)); sim.flush()
        val closedAt = sim.log.indexOfFirst { it is SaveEpisode && it.record.id == epId && it.record.endedAt != null }
        assertTrue(closedAt >= 0)
        assertTrue(sim.log.indexOfLast { it is SettleHandoff && it.episodeId == epId } > closedAt, "닫힌 행이 확정된다")
        assertNotNull(sim.told(epId))
    }

    /**
     * C319 V19(C135 ①을 바꿈, 옛 c135_clearRecords_dropsPendingHandoffAlert): 지우기는 대기 인계 알림을 버리지 않는다. 알림은 기록이 아니라 지금 우는 아기에 대한 통지다.
     * 장부는 묶인 줄을 따라 그 줄을 말한 뒤 쉼터 이름을 담아 나가고, 에피소드 행에는 되쓰지 않는다(recordsCleared).
     */
    @Test fun c319_clearRecords_keepsPendingHandoffAlert_noRowRewrite() {
        val sim = heldOffer()
        sim.ev(ClearRecords(sim.t))
        assertNotNull(sim.ledger)
        val saved = sim.log.count { it is SaveEpisode || it is SettleHandoff }
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs)); sim.flush()
        assertEquals(1, sim.alerts().size)
        assertEquals(saved, sim.log.count { it is SaveEpisode || it is SettleHandoff })
    }

    /** C319 V19(옛 c135_clearRecords_thenSessionClosed_noAlert): 지운 뒤 줄을 말하기 전에 세션이 닫히면 13a가 이름 없는 알림으로 확정한다. 행에는 되쓰지 않는다. */
    @Test fun c319_clearRecords_thenSessionClosed_plainAlert_noRowRewrite() {
        val sim = heldOffer()
        sim.ev(ClearRecords(sim.t))
        val saved = sim.log.count { it is SaveEpisode || it is SettleHandoff }
        sim.s = sim.s.copy(car = sim.s.car.copy(gear = Gear.P))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session)
        assertEquals(1, sim.alerts().size); assertEquals(saved, sim.log.count { it is SaveEpisode || it is SettleHandoff })
    }

    @Test fun c135_clearRecords_newHandoffAfterwardStillAlerts() {
        val sim = heldOffer()
        sim.ev(ClearRecords(sim.t))
        sim.s = sim.s.copy(car = sim.s.car.copy(gear = Gear.P))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session); assertEquals(1, sim.alerts().size, "C319 V19: 지우기 전 인계 알림은 세션 닫힘에서 나간다")
        sim.doorCycle().gear(Gear.D, 1_000).route(rest("n", 14.0, nursing = true))   // 지운 뒤 새 세션의 인계는 정상(H10·H11)
        sim.attempt2().toGap().busyNow().recry()
        assertNotNull(sim.ledger)
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs)); sim.flush()
        assertEquals(2, sim.alerts().size)
    }

    @Test fun h4_yes_addsWaypoint() {
        val sim = asked(rest("n", 12.0, nursing = true))
        sim.ev(Answer(sim.t, sim.ask().askId, YesNo.YES))
        val add = sim.step<AddWaypoint>().single()
        assertEquals("n", add.restId)
        assertNull(sim.ses.pendingAsk)
        assertEquals(WaypointPending(add.cmdId, "n", sim.ses.id, sim.ep.id, restLogIndex = 0, restName = "n 휴게소"), sim.ses.waypointPending)
        assertEquals(NursingStop("n", null, null, false), sim.s.ign.nursingStop)
    }

    @Test fun h4a_yesAfterPark_discarded() {
        val sim = asked(rest("a", 12.0)).gear(Gear.P, 1_000)
        sim.ev(Answer(sim.t, sim.ask().askId, YesNo.YES))
        assertNull(sim.ses.pendingAsk)
        assertTrue(sim.all<AddWaypoint>().isEmpty())
    }

    @Test fun c339_restOfferYesInReverse_stillAdds() {
        val sim = asked(rest("n", 12.0, nursing = true)).gear(Gear.R, 1_000)
        assertNotNull(sim.ses.pendingAsk)
        sim.ev(Answer(sim.t, sim.ask().askId, YesNo.YES))
        assertEquals("n", sim.step<AddWaypoint>().single().restId)
    }

    @Test fun h4b_staleAskId_keepsPendingAsk() {
        val sim = asked(rest("a", 12.0))
        val pending = sim.ses.pendingAsk
        sim.ev(Answer(sim.t, "old-1", YesNo.NO))
        assertEquals(pending, sim.ses.pendingAsk)
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun h5_waypointOk_saysS09() {
        val sim = asked(rest("n", 12.0, nursing = true))
        sim.ev(Answer(sim.t, sim.ask().askId, YesNo.YES))
        val add = sim.step<AddWaypoint>().single()
        sim.ev(WaypointResult(sim.t + 500, "other", "n", true))      // 다른 cmdId는 무시
        sim.ev(WaypointResult(sim.t + 500, add.cmdId, "x", true))    // 쉼터가 달라도 무시(C65)
        assertNotNull(sim.ses.waypointPending); assertTrue(sim.ses.addedRestIds.isEmpty())
        sim.ev(WaypointResult(sim.t + 500, add.cmdId, "n", true))
        assertNull(sim.ses.waypointPending)
        assertEquals(setOf("n"), sim.ses.addedRestIds)
        assertEquals(sim.t, sim.s.ign.nursingStop!!.addedAt)
        assertTrue(sim.ep.waypointAdded)
        sim.flush()
        assertEquals(mapOf("restName" to "n 휴게소"), sim.all<Speak>().single { it.sid == Sid.S09 }.vars)
    }

    @Test fun h5a_waypointFail_clearsNursingStop() {
        val sim = asked(rest("n", 12.0, nursing = true))
        sim.ev(Answer(sim.t, sim.ask().askId, YesNo.YES))
        sim.ev(WaypointResult(sim.t, sim.step<AddWaypoint>().single().cmdId, "n", false))
        assertNull(sim.s.ign.nursingStop); assertNull(sim.ses.waypointPending)
        sim.flush()
        assertTrue(sim.sids().none { it == Sid.S09 || it == Sid.S19 })
    }

    @Test fun h6_noAnswer_noRepeat10min() {
        val sim = asked(rest("a", 12.0))
        sim.ev(Answer(sim.t, sim.ask().askId, YesNo.NONE))
        assertNull(sim.ses.pendingAsk)
        assertTrue(sim.cmds.isEmpty())
        sim.tick(5 * MIN)                                            // 경보 없이 5분 → LISTENING
        assertEquals(Phase.LISTENING, sim.ses.phase)
        sim.handoff().flush()                                       // 10분 안에 다시 인계
        assertEquals(1, sim.all<Ask>().size)
    }

    @Test fun h7_noRest_saysS10() {
        val sim = session().handoff().flush()
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind)
        assertTrue(Sid.S10 in sim.sids())
        assertEquals("no_rest", sim.alert()["kind"]!!.jsonPrimitive.content)
    }

    @Test fun h8_noGuardian_noAlert() {
        val sim = session(guardians = 0).handoff()
        assertTrue(sim.all<SendToPhone>().isEmpty())   // 결정 ③: guardian_notified는 link가 넣을 때만 참(판단 코어에 칸이 없다)
    }

    @Test fun h9_restBeyond30km_ignored() {
        val sim = session().route(rest("far", 30.5, nursing = true)).handoff()
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind)
        assertTrue(Sid.S10 in sim.sids())
    }

    @Test fun r12a_capDuringAttempt_handoffNoPlay() {
        val sim = session().cap(56_000).attempt1().toGap()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertTrue(sim.ses.capReached(P))
        assertEquals(Unjudged.SESSION_CAP, sim.ep.first!!.unjudged)
        assertEquals(HandoffCause.SESSION_CAP, sim.ep.handoff!!.cause)
        assertTrue(sim.step<StartPlayback>().isEmpty())
        assertTrue(sim.step<SendToPhone>().isEmpty())               // H10: S10을 낼 때 보낸다(앞 문장 S03이 말하는 중)
        sim.flush()
        assertEquals("handoff_alert", sim.all<SendToPhone>().single().type)
    }

    @Test fun r12b_shortBudget_attemptPlaysRemaining() {
        val sim = session().cap(30_000).alarm()
        assertEquals(sim.t + 30_000, sim.step<StartPlayback>().single().endAt)
    }

    @Test fun r12c_afterCap_zeroAutoPlay() {
        val sim = session().cap(56_000).attempt1().toGap().tick(5 * MIN)
        assertEquals(Phase.LISTENING, sim.ses.phase)
        sim.clear()
        repeat(5) { sim.alarm(MIN) }
        sim.tick(10 * MIN)
        assertTrue(sim.all<StartPlayback>().none { it.kind == PlayKind.AUTO })
        assertEquals(5, sim.all<LogAlarm>().count { it.reason == AlarmOnly.SESSION_CAP })
    }

    @Test fun h5_waypointWithoutHandoff_recordedOnEpisode() {
        val sim = session().route(rest("a", 8.0)).attempt1().ev(DriverAddWaypoint(T0, "a"))
        sim.ev(WaypointResult(sim.t, sim.step<AddWaypoint>().single().cmdId, "a", true))
        assertNull(sim.ep.handoff); assertTrue(sim.ep.waypointAdded)
        sim.toConfirm().closeQuiet()
        assertTrue(sim.step<SaveEpisode>().single().record.waypointAdded)
    }

    @Test fun restLog_recordsOfferAnswerWaypoint() {
        val sim = session().route(rest("a", 12.0)).handoff().done()
        sim.tick(P.speechGapMs)                                   // 이 Step에서 Ask(S08)를 낸다
        val ask = sim.step<Ask>().single()
        val askAt = sim.t
        sim.ev(Answer(sim.t + 3_000, ask.askId, YesNo.YES))
        sim.ev(WaypointResult(sim.t + 1_000, sim.step<AddWaypoint>().single().cmdId, "a", true))
        sim.gear(Gear.P, MIN).ev(RearDoor(sim.t, Side.LEFT, true))
        val log = Json.decodeFromString<List<RestLogEntry>>(sim.all<SaveSession>().last().record.restLog)
        assertEquals(listOf(RestLogEntry(askAt, Sid.S08, "a", YesNo.YES, true)), log)
    }

    @Test fun restLog_waypointResultLinkedToItsRequest() {
        // 2시간 직전 수유 제안 S08 요청이 진행 중이면 2시간 제안은 같은 쉼터를 묻지 않고(C89), 쉼터 없는 S10이 사이에 끼어도 결과는 S08 줄에만 붙는다
        // (C273: 2시간 경계에 함께 고르면 S08 한 줄이 장시간 출처를 넘겨받으므로 S08을 먼저 묻는다)
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 12.0)).alarm(119 * MIN)
        sim.started().tick(1_000)
        assertEquals(listOf(Sid.S02, Sid.S06, Sid.S08), sim.sids())
        sim.done().tick(P.speechGapMs).done().tick(P.speechGapMs)
        val ask8 = sim.step<Ask>().single(); assertEquals(Sid.S08, ask8.sid)
        sim.ev(Answer(sim.t + 2_000, ask8.askId, YesNo.YES))
        val cmd = sim.step<AddWaypoint>().single().cmdId
        sim.done().ev(Tick(T0 + 120 * MIN + 1_000))                    // 2시간: 같은 쉼터는 요청이 진행 중이라 묻지 않는다(C89·H4c)
        assertEquals(0, sim.step<Ask>().size); assertTrue(sim.s.speech.queue.none { it.askRestId == "a" })
        assertTrue(sim.ses.waypointPending != null)
        sim.ev(RouteState(sim.t, false, null, emptyList())).tick(1_000)   // 쉼터가 없어지면 2시간 제안은 쉼터 없음 S10
        sim.flush()
        assertEquals(Sid.S10, sim.all<Speak>().last().sid)             // S10은 묻지 않고 말만 한다
        assertEquals(listOf(Sid.S08, Sid.S10), sim.ses.restLog.map { it.sid })
        assertTrue(sim.ses.waypointPending != null)
        sim.ev(WaypointResult(sim.t + 500, cmd, "a", true))           // 앞 요청(S08)의 결과
        assertEquals(listOf(Sid.S08, Sid.S10), sim.ses.restLog.map { it.sid })
        assertEquals(listOf<Boolean?>(true, null), sim.ses.restLog.map { it.waypointOk })
    }

    // ---- C146: 인계 알림·toldDriver는 실제로 말한 뒤 ----
    private fun plainAlert(sim: Sim, text: String = "아기가 계속 울어요.") {
        val b = sim.alerts().single().body
        assertEquals(JsonNull, b["rest_name"]); assertEquals(text, b["text"]!!.jsonPrimitive.content)
    }

    /** 앞 문장(S02·S06 등)을 끝내며 [sid] 줄이 말하는 중(큐 맨 앞)이 될 때까지 간다. */
    private fun Sim.untilSpeaking(sid: Sid): Sim {
        repeat(12) {
            if (s.speech.speaking != null && s.speech.queue.first().sid == sid) return this
            if (s.speech.speaking != null) done()
            tick(P.speechGapMs)
        }
        throw AssertionError("$sid 줄이 말하는 중이 되지 않음: ${s.speech}")
    }

    @Test fun c146_spokenSendsNamedAlertAndToldTrue() {
        val sim = session().route(rest("n", 14.0, nursing = true)).handoff().untilSpeaking(Sid.S07)
        assertEquals(Sid.S07, sim.all<Ask>().single().sid)
        assertTrue(sim.alerts().isEmpty()); assertNull(sim.told())   // 낼 때는 아무것도 하지 않는다
        assertNotNull(sim.ledger!!.utteranceId)
        sim.done()
        assertEquals("n 휴게소", sim.alerts().single().body["rest_name"]!!.jsonPrimitive.content)
        assertEquals(true, sim.told()); assertEquals("n 휴게소", sim.settled()?.restName)
        sim.doneMarks(sim.saved(), true, "n 휴게소")
        assertNull(sim.ledger)
    }

    @Test fun c146_ttsFailurePlainAlertToldFalse() {
        val sim = session().route(rest("n", 14.0, nursing = true)).handoff().untilSpeaking(Sid.S07)
        sim.done(spoken = false)
        plainAlert(sim)
        assertEquals(false, sim.told()); sim.doneMarks(sim.saved(), false, null)
        assertNull(sim.ledger)
        sim.flush(); assertEquals(1, sim.alerts().size)
    }

    @Test fun c146_speakingLineBoundWaitsForDone() {
        val (sim, _) = speakingOffer()
        assertTrue(sim.alerts().isEmpty()); assertNull(sim.told())
        sim.ev(SpeechDone(sim.t + 1_000, "u1", false))
        plainAlert(sim); sim.doneMarks(sim.saved(), false, null)
    }

    @Test fun c146_arrivalToldAfterSpoken() {
        val sim = session().route(rest("a", 5.0), dest = true, remainingSec = 530).handoff()
        assertEquals(1, sim.alerts().size)                                  // H2 알림은 바로
        assertEquals("arrival_notice", sim.alerts().single().body["kind"]!!.jsonPrimitive.content)
        assertNull(sim.told())
        sim.untilSpeaking(Sid.S15).done()
        assertEquals(true, sim.told()); sim.doneMarks(sim.saved(), true, null)
        assertEquals(1, sim.alerts().size)
        val f = session().route(rest("a", 5.0), dest = true, remainingSec = 530).handoff()
        f.untilSpeaking(Sid.S15).done(spoken = false)
        assertEquals(false, f.told()); assertEquals(1, f.alerts().size)
    }

    @Test fun c146_restoreDropSpeakingFlushesPlain() {
        val (sim, _) = speakingOffer()
        val st = Engine().dropSpeaking(sim.s, sim.t + 1_000)
        assertNull(st.speech.speaking)
        assertNotNull(st.openLedger())                                      // 끝내지 않고 남긴다
        assertNull(sim.told())
        val after = Sim(st).tick()
        plainAlert(after)
        assertEquals(false, after.told()); after.doneMarks(after.saved(), false, null)
        assertNull(after.ledger)
    }

    @Test fun c146_callCancelsBoundLinePlain() {
        val (sim, _) = speakingOffer()
        sim.ev(CallMode(sim.t, true))
        plainAlert(sim); sim.doneMarks(sim.saved(), false, null)
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))                       // 늦은 SpeechDone은 무시
        assertEquals(1, sim.alerts().size); assertEquals(false, sim.told())
    }

    /** 인계 줄을 말하는 중(묶음에 utteranceId)에 에피소드가 먼저 닫힌 상태. */
    private fun episodeClosedWhileSpeaking(): Sim {
        val sim = session().route(rest("n", 14.0, nursing = true)).handoff().untilSpeaking(Sid.S07)
        val epId = sim.ep.id
        sim.ev(Tick(sim.t + 6 * MIN))
        assertNull(sim.ses.episode, "에피소드가 닫혔다")
        assertNotNull(sim.all<SaveEpisode>().last { it.record.id == epId }.record.endedAt)
        assertEquals(epId, sim.ledger!!.episodeId)
        assertNotNull(sim.ledger!!.utteranceId)
        assertTrue(sim.alerts().isEmpty())
        return sim
    }

    @Test fun c146_closedEpisodeSpokenUpdatesClosedRow() {
        val sim = episodeClosedWhileSpeaking()
        val epId = sim.ledger!!.episodeId
        sim.ev(SpeechDone(sim.t + 1_000, sim.s.speech.speaking!!, true))
        val r = sim.saved()
        assertEquals(epId, r!!.episodeId); sim.doneMarks(r, true, "n 휴게소")
        assertEquals("n 휴게소", sim.alerts().single().body["rest_name"]!!.jsonPrimitive.content)
        assertNull(sim.ledger)
    }

    @Test fun c146_closedEpisodeRestoreFlushesPlain() {
        val sim = episodeClosedWhileSpeaking()
        val epId = sim.ledger!!.episodeId
        val st = Engine().dropSpeaking(sim.s, sim.t + 1_000)
        assertNotNull(st.openLedger())
        val after = Sim(st).tick()
        val r = after.saved()
        assertEquals(epId, r!!.episodeId); after.doneMarks(r, false, null)
        plainAlert(after)
        assertNull(after.ledger)
    }
}
