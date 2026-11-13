package kr.aigyeot.engine

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 명세서 17장 C250: 경로 때문에 무효가 된 인계 줄(S15·S10)은 버리지 않고 지금 경로로 다시 고른다.
class C250Test {
    private fun waiting(sim: Sim): Sim = sim.handoff()
    private fun Sim.release() = done(2_000).tick(P.speechGapMs)
    private fun Sim.reroute(sec: Int?, dest: Boolean = true, vararg rests: RestCandidate) = ev(RouteState(t + 1_000, dest, sec, rests.toList(), null))
    private fun Sim.alerts() = all<SendToPhone>().filter { it.type == "handoff_alert" }
    private fun Sim.kinds() = alerts().map { it.body["kind"]!!.jsonPrimitive.content }
    private fun Sim.asked() = all<Ask>().map { it.sid }

    @Test fun c250_s10_restAppeared_asksRest_oneAlertWithName() {
        val sim = waiting(session())
        assertTrue(sim.alerts().isEmpty())
        sim.reroute(null, false, rest("n", 8.0)).release()
        assertEquals(listOf(Sid.S08), sim.asked())
        assertTrue(sim.all<Speak>().none { it.sid == Sid.S10 })
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind)
        assertEquals(Sid.S08, sim.s.speech.queue.single { it.isHandoffOf(sim.ses.id) }.sid, "장부가 묶인 줄")
        sim.done(2_000)
        assertEquals(listOf("rest_offer"), sim.kinds())
        assertEquals("n 휴게소", sim.alerts().single().body["rest_name"]!!.jsonPrimitive.content)
        assertEquals(true, sim.told())
        assertEquals(null, sim.ledger)
    }

    @Test fun c250_s10_restBlockedByRepeatBan_noLine_unnamedAlert() {
        val sim = waiting(session())
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(spokenAt = spokenAt + (Sid.S08 to sim.t)) })   // S08은 10분 안에 냈다
        sim.reroute(null, false, rest("n", 8.0)).release()
        assertTrue(sim.all<Speak>().none { it.sid == Sid.S10 } && sim.asked().isEmpty(), "쉼터가 있는데 '못 찾았다'고 하지 않는다")
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind)
        assertEquals(listOf("rest_offer"), sim.kinds())
        assertEquals(null, sim.alerts().single().body["rest_name"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content)
        assertEquals(false, sim.told())
        assertEquals(null, sim.ledger)
    }

    @Test fun c250_s15_restBlocked_noExtraAlert_toldDriverFalse() {
        val sim = waiting(session().route(rest("a", 5.0), dest = true, remainingSec = 360))
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(spokenAt = spokenAt + (Sid.S08 to sim.t)) })
        sim.reroute(3600, true, rest("a", 5.0)).release()
        assertTrue(sim.asked().isEmpty() && sim.all<Speak>().none { it.sid == Sid.S10 || it.sid == Sid.S15 })
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind)
        assertEquals(listOf("arrival_notice"), sim.kinds(), "이미 보낸 H2 알림만")
        assertEquals(false, sim.told())
    }

    @Test fun c250_s15_routeLonger_restExists_asksRest_noSecondAlert() {
        val sim = waiting(session().route(rest("a", 5.0), dest = true, remainingSec = 360))
        assertEquals(listOf("arrival_notice"), sim.kinds())   // H2 알림은 바로 갔다
        sim.reroute(3600, true, rest("a", 5.0)).release()
        assertEquals(listOf(Sid.S08), sim.asked())
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind)
        assertEquals(false, sim.ledger!!.alert)
        sim.done(2_000)
        assertEquals(listOf("arrival_notice"), sim.kinds(), "다시 보내지 않는다")
        assertEquals(true, sim.told())
    }

    @Test fun c250_s15_routeLonger_noRest_s10() {
        val sim = waiting(session().route(rest("a", 5.0), dest = true, remainingSec = 360))
        sim.reroute(3600, true).release()
        assertTrue(sim.all<Speak>().any { it.sid == Sid.S10 })
        assertTrue(sim.all<Speak>().none { it.sid == Sid.S15 })
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind)
        sim.done(2_000)
        assertEquals(listOf("arrival_notice"), sim.kinds())
        assertEquals(true, sim.told())
    }

    @Test fun c250_gearP_stillDropped() {
        val sim = waiting(session())
        sim.reroute(null, false, rest("n", 8.0)).gear(Gear.P).done(2_000).tick(P.speechGapMs)
        assertTrue(sim.all<Speak>().none { it.sid == Sid.S10 } && sim.asked().isEmpty())
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S10 })
        assertEquals(false, sim.s.session?.episode?.let { sim.told(it.id) } ?: false)
    }

    @Test fun c250_unboundS10_restAppeared_repickedS08() {   // C272: 예전에는 버렸다. 인계가 아닌 줄도 지금 경로로 다시 고른다(알림 없음)
        val sim = waiting(session())
        sim.s = sim.s.copy(handoffs = emptyList(), speech =sim.s.speech.copy(queue = sim.s.speech.queue.map { it.copy(handoff = false) }))   // 인계에 묶이지 않은 줄(C258: 표시 없는 줄)
        sim.reroute(null, false, rest("n", 8.0)).release()
        assertTrue(sim.all<Speak>().none { it.sid == Sid.S10 }); assertEquals(listOf(Sid.S08), sim.asked())
        assertTrue(!sim.ses.spokenAt.containsKey(Sid.S10))
        assertTrue(sim.alerts().isEmpty())
    }
}
