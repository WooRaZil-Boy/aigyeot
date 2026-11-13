package kr.aigyeot.engine

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 명세서 17장 C251·C257: 인계에 묶인 줄은 꺼낼 때 지금 경로로 고른 종류와 맞는지 보고 다르면 다시 고른다. 알림 분은 발화와 같다.
class C251Test {
    private fun Sim.reroute(sec: Int?, dest: Boolean = true, vararg rests: RestCandidate) = ev(RouteState(t + 1_000, dest, sec, rests.toList(), null))
    private fun Sim.alerts() = all<SendToPhone>().filter { it.type == "handoff_alert" }
    private fun Sim.asked() = all<Ask>().map { it.sid }

    @Test fun c251_repickedS10_thenArrivalWithin8s_speaksS15() {
        val sim = session().route(rest("a", 5.0), dest = true, remainingSec = 360).handoff()
        sim.reroute(3600, true).done(2_000)
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S10 }, "먼저 S10으로 다시 골랐다")
        sim.reroute(360, true).tick(P.speechGapMs)
        assertTrue(sim.all<Speak>().any { it.sid == Sid.S15 })
        assertTrue(sim.all<Speak>().none { it.sid == Sid.S10 }, "도착 안내 대신 쉼터 없음 안내를 하지 않는다")
        assertEquals(HandoffKind.ARRIVAL_NOTICE, sim.ep.handoff!!.kind)
    }

    @Test fun c251_boundS08_restGone_otherRest_asksNewRest() {
        val sim = session().route(rest("a", 8.0), dest = true, remainingSec = 3600).handoff()
        sim.reroute(3600, true, rest("b", 9.0)).done(2_000).tick(P.speechGapMs)
        val ask = sim.all<Ask>().last()
        assertEquals("b 휴게소", ask.vars["restName"])
        assertEquals("b", sim.ses.pendingAsk?.restId)
        sim.done(2_000)
        assertEquals("b 휴게소", sim.alerts().last().body["rest_name"]!!.jsonPrimitive.content)
    }

    @Test fun c251_boundS08_nearDestination_s15() {
        val sim = session().route(rest("a", 8.0), dest = true, remainingSec = 3600).handoff()
        sim.reroute(360, true, rest("a", 8.0)).done(2_000).tick(P.speechGapMs)
        assertTrue(sim.all<Speak>().any { it.sid == Sid.S15 })
        assertTrue(sim.asked().isEmpty(), "쉼터를 묻지 않고 도착 안내")
        assertEquals(HandoffKind.ARRIVAL_NOTICE, sim.ep.handoff!!.kind)
    }

    @Test fun c251_unboundS08_restGone_repickedNewRest() {   // C272: 예전에는 버렸다. 인계가 아닌 줄도 지금 경로의 쉼터로 다시 묻는다
        val sim = session().route(rest("a", 8.0), dest = true, remainingSec = 3600).handoff()
        sim.s = sim.s.copy(handoffs = emptyList(), speech =sim.s.speech.copy(queue = sim.s.speech.queue.map { it.copy(handoff = false) }))   // C258: 표시 없는 줄
        val before = sim.asked().size
        sim.reroute(3600, true, rest("b", 9.0)).done(2_000).tick(P.speechGapMs)
        assertEquals(before + 1, sim.asked().size)
        assertEquals("b 휴게소", sim.all<Ask>().last().vars["restName"])
    }

    @Test fun c257_repickedS15_alertMinutesFollowSpeech() {
        val sim = session().handoff()   // 경로 없음: S10이 묶인다
        sim.reroute(360, true).done(2_000)
        sim.reroute(299, true).tick(P.speechGapMs)
        assertEquals("5", sim.all<Speak>().last { it.sid == Sid.S15 }.vars["minutes"])
        sim.done(2_000)
        assertEquals(5, sim.alerts().last().body["minutes_to_arrival"]!!.jsonPrimitive.content.toInt())
    }
}
