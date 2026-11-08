package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 명세서 17장 C353 / 계약 H4d: 묻거나 큐에 둔 뒤 경로가 바뀌어 addable=false가 된 쉼터는 행동 경로에서도 넣지 않는다.
class C353ActionGateTest {
    private val n = rest("n", 12.0, nursing = true)
    private val bad = n.copy(addable = false)

    private fun asked(): Pair<Sim, Ask> {
        val sim = session().route(n).ev(Tick(T0 + 120 * MIN))
        return sim to sim.step<Ask>().single()
    }

    @Test fun c353_answerYes_restBecameUnaddable_saysS19() {
        val (sim, ask) = asked()
        sim.route(bad)
        sim.clear().ev(Answer(sim.t, ask.askId, YesNo.YES))
        assertTrue(sim.all<AddWaypoint>().isEmpty())
        assertTrue(Sid.S19 in sim.sids())
        assertNull(sim.ses.pendingAsk)
        assertNull(sim.ses.waypointPending)
    }

    @Test fun c353_answerYes_alreadyAddedRest_keepsH4cWins() {
        val (sim, ask) = asked()
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(addedRestIds = setOf("n")) })
        sim.route(bad)
        sim.clear().ev(Answer(sim.t, ask.askId, YesNo.YES))
        assertTrue(sim.all<AddWaypoint>().isEmpty())
        assertTrue(Sid.S19 !in sim.sids())
        assertEquals(true, sim.ses.restLog[0].waypointOk)
    }

    @Test fun c353_answerYes_stillAddable_addsWaypoint() {
        val (sim, ask) = asked()
        sim.route(n)
        sim.ev(Answer(sim.t, ask.askId, YesNo.YES))
        assertEquals("n", sim.step<AddWaypoint>().single().restId)
    }

    @Test fun c353_qaTool_unaddableRest_saysS19() {
        val sim = session().route(bad).attempt1()
        sim.ev(SpeechDone(sim.t, sim.s.speech.speaking!!, true)).ev(AskButton(sim.t))
        val listenId = sim.s.speech.qa!!.listenId
        sim.ev(Heard(sim.t, listenId, "수유실 경유지 넣어 줘"))
        sim.ev(DriverAddWaypoint(sim.t, "n", sim.s.speech.qa!!.listenId))
        assertTrue(sim.all<AddWaypoint>().isEmpty())
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S19 && it.qaAnswer })
    }

    @Test fun c353_queuedDepartLine_unaddableAtPop_dropped() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN)
        sim.ev(CallMode(sim.t, true)).route(n, dest = true, remainingSec = 9000, remainingKm = 150.0)
        val l = Line(Sid.S07_DEPART, emptyMap(), "n", queuedAt = sim.t, sessionId = sim.ses.id)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = listOf(l)))
        sim.route(bad, dest = true, remainingSec = 9000, remainingKm = 150.0)
        sim.clear().ev(CallMode(sim.t + 1_000, false)).ev(Tick(sim.t + 3_000))
        assertTrue(sim.all<Ask>().isEmpty(), "addable=false인 줄은 묻지 않는다")
        assertTrue(sim.s.speech.queue.none { it.askRestId == "n" })
    }

    @Test fun c353_answerYes_restAbsent_c213_saysS19() {
        val (sim, ask) = asked()
        sim.route()
        sim.clear().ev(Answer(sim.t, ask.askId, YesNo.YES))
        assertTrue(sim.all<AddWaypoint>().isEmpty())
        assertTrue(Sid.S19 in sim.sids())
    }
}
