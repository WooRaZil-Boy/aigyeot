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
        assertEquals(PendingAsk(ask.askId, Sid.S07, "n", sim.ep.id, true), sim.ses.pendingAsk)
        assertEquals("rest_offer", sim.alert()["kind"]!!.jsonPrimitive.content)
        assertEquals("n 휴게소", sim.alert()["rest_name"]!!.jsonPrimitive.content)
        assertEquals("아기가 계속 울어서 n 휴게소에 들르자고 운전자에게 제안했어요.", sim.alert()["text"]!!.jsonPrimitive.content)
        val b = asked(rest("a", 10.0), rest("n", 15.5, nursing = true))
        assertEquals(Sid.S08, b.ask().sid); assertEquals("a", b.ses.pendingAsk!!.restId)
    }

    @Test fun h3_offerBlocked_alertWithoutRestName() {
        val sim = session().route(rest("n", 14.0, nursing = true))
        sim.s = sim.s.copy(session = sim.ses.copy(lastOffer = mapOf(Sid.S07_DEPART to sim.t)))   // 출발 제안 직후(S3a 10분 금지)
        sim.handoff().flush()
        assertTrue(sim.all<Ask>().none { it.sid == Sid.S07 })
        val b = sim.alert()
        assertEquals("rest_offer", b["kind"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, b["rest_name"])
        assertEquals("아기가 계속 울어요.", b["text"]!!.jsonPrimitive.content)
    }

    @Test fun h4_yes_addsWaypoint() {
        val sim = asked(rest("n", 12.0, nursing = true))
        sim.ev(Answer(sim.t, sim.ask().askId, YesNo.YES))
        val add = sim.step<AddWaypoint>().single()
        assertEquals("n", add.restId)
        assertNull(sim.ses.pendingAsk)
        assertEquals(WaypointPending(add.cmdId, "n", sim.ses.id, sim.ep.id, restLogIndex = 0), sim.ses.waypointPending)
        assertEquals(NursingStop("n", null, null, false), sim.ses.nursingStop)
    }

    @Test fun h4a_yesAfterPark_discarded() {
        val sim = asked(rest("a", 12.0)).gear(Gear.P, 1_000)
        sim.ev(Answer(sim.t, sim.ask().askId, YesNo.YES))
        assertNull(sim.ses.pendingAsk)
        assertTrue(sim.all<AddWaypoint>().isEmpty())
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
        assertEquals(sim.t, sim.ses.nursingStop!!.addedAt)
        assertTrue(sim.ep.handoff!!.waypointAdded)
        sim.flush()
        assertEquals(mapOf("restName" to "n 휴게소"), sim.all<Speak>().single { it.sid == Sid.S09 }.vars)
    }

    @Test fun h5a_waypointFail_clearsNursingStop() {
        val sim = asked(rest("n", 12.0, nursing = true))
        sim.ev(Answer(sim.t, sim.ask().askId, YesNo.YES))
        sim.ev(WaypointResult(sim.t, sim.step<AddWaypoint>().single().cmdId, "n", false))
        assertNull(sim.ses.nursingStop); assertNull(sim.ses.waypointPending)
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
        val sim = session().handoff()
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind)
        assertTrue(Sid.S10 in sim.sids())
        assertEquals("no_rest", sim.alert()["kind"]!!.jsonPrimitive.content)
    }

    @Test fun h8_noGuardian_noAlert() {
        val sim = session(guardians = 0).handoff()
        assertTrue(sim.all<SendToPhone>().isEmpty())
        assertFalse(sim.ep.handoff!!.guardianNotified)
        assertFalse(sim.step<SaveEpisode>().last().record.guardianNotified)
    }

    @Test fun h9_restBeyond30km_ignored() {
        val sim = session().route(rest("far", 30.5, nursing = true)).handoff()
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind)
        assertTrue(Sid.S10 in sim.sids())
    }

    @Test fun r12a_capDuringAttempt_handoffNoPlay() {
        val sim = session().cap(56_000).attempt1().toGap()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertTrue(sim.ses.capReached)
        assertEquals(Unjudged.SESSION_CAP, sim.ep.first!!.unjudged)
        assertEquals(HandoffCause.SESSION_CAP, sim.ep.handoff!!.cause)
        assertTrue(sim.step<StartPlayback>().isEmpty())
        assertEquals("handoff_alert", sim.step<SendToPhone>().single().type)
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
        // 120분 경계: 수유 제안 S08과 장시간 제안 S11이 같은 쉼터로 대기. S08 요청이 진행 중이면 S11은 버려지고, 쉼터 없는 S10이 사이에 끼어도 결과는 S08 줄에만 붙는다
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 12.0)).alarm(120 * MIN)
        sim.started().tick(1_000)
        assertEquals(listOf(Sid.S02, Sid.S06, Sid.S08, Sid.S11), sim.sids())
        sim.done().tick(P.speechGapMs).done().tick(P.speechGapMs)
        val ask8 = sim.step<Ask>().single(); assertEquals(Sid.S08, ask8.sid)
        sim.ev(Answer(sim.t + 2_000, ask8.askId, YesNo.YES))
        val cmd = sim.step<AddWaypoint>().single().cmdId
        sim.done().tick(P.speechGapMs)                                 // 큐의 S11은 진행 중 요청(C89) 때문에 버려진다
        assertEquals(0, sim.step<Ask>().size)
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
}
