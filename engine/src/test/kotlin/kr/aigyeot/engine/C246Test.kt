package kr.aigyeot.engine

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 명세서 17장 C246(11-07 42회차 검토): 기다리는 S15·S10은 꺼낼 때 지금 경로로 다시 본다.
class C246Test {
    /** 8초 간격(S1)에 막혀 인계 줄이 큐에서 기다리는 상태. */
    private fun waiting(sim: Sim): Sim = sim.handoff()   // 앞 발화(S03)가 말하는 중이라 인계 줄은 그 뒤에서 기다린다
    private fun Sim.queued() = s.speech.queue.map { it.sid }.filter { it == Sid.S15 || it == Sid.S10 }
    private fun Sim.release() = done(2_000).tick(P.speechGapMs)
    private fun Sim.reroute(sec: Int?, dest: Boolean = true, vararg rests: RestCandidate) = ev(RouteState(t + 1_000, dest, sec, rests.toList(), null))
    private fun Sim.text() = all<SendToPhone>().filter { it.type == "handoff_alert" }.map { it.body["text"]!!.jsonPrimitive.content }

    @Test fun c246_s15_routeNowLong_notSpokenAsS15() {   // C250: 버리지 않고 다시 고른다(쉼터 있으면 S08). 다시 고른 줄은 C250Test
        val sim = waiting(session().route(rest("a", 5.0), dest = true, remainingSec = 360))
        assertEquals(listOf(Sid.S15), sim.queued())
        sim.reroute(3600, true, rest("a", 5.0)).release()
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S15 }); assertTrue(sim.all<Speak>().none { it.sid == Sid.S15 })
        assertEquals(listOf(Sid.S08), sim.all<Ask>().map { it.sid })   // C250: 인계 안내가 대신 나간다
    }

    @Test fun c246_s15_minutesRefreshed() {
        val sim = waiting(session().route(rest("a", 5.0), dest = true, remainingSec = 360))
        sim.reroute(240, true, rest("a", 5.0)).release()
        assertEquals("4", sim.all<Speak>().single { it.sid == Sid.S15 }.vars["minutes"])
    }

    @Test fun c246_s15_sameRoute_spoken() {
        val sim = waiting(session().route(rest("a", 5.0), dest = true, remainingSec = 360))
        sim.release()
        assertEquals("6", sim.all<Speak>().single { it.sid == Sid.S15 }.vars["minutes"])
    }

    @Test fun c246_s10_restAppeared_notSpokenAsS10_forgotten() {   // C250: S10 대신 쉼터 제안(S08)을 다시 고른다
        val sim = waiting(session())
        assertEquals(listOf(Sid.S10), sim.queued())
        assertTrue(!sim.ses.spokenAt.containsKey(Sid.S10), "결정 ②: 큐에 든 동안은 금지 시각이 없다")
        sim.reroute(null, false, rest("n", 8.0)).release()
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S10 }); assertTrue(sim.all<Speak>().none { it.sid == Sid.S10 })
        assertEquals(listOf(Sid.S08), sim.all<Ask>().map { it.sid })
        assertTrue(!sim.ses.spokenAt.containsKey(Sid.S10))
    }

    @Test fun c246_s10_sameRoute_spoken() {
        val sim = waiting(session()).release()
        assertTrue(sim.all<Speak>().any { it.sid == Sid.S10 })
    }

    // ---- 복원 직후 경로 기다림: 앞 버림(dropWhile)도 S15를 지킨다 ----
    private fun restoredWaiting(): Sim {
        val sim = waiting(session().route(rest("a", 5.0), dest = true, remainingSec = 360))
        sim.s = sim.s.copy(bootAt = sim.t, car = sim.s.car.copy(route = null))
        sim.done(2_000).tick(P.speechGapMs)
        return sim
    }

    @Test fun c246_restoredS15_heldThenSpokenWithNewMinutes() {
        val sim = restoredWaiting()
        assertEquals(listOf(Sid.S15), sim.queued(), "route == null이어도 바로 버리지 않는다")
        sim.ev(RouteState(sim.t + 1_000, true, 240, listOf(rest("a", 5.0)), null)).tick(P.speechGapMs)
        assertEquals("4", sim.all<Speak>().single { it.sid == Sid.S15 }.vars["minutes"])
    }

    @Test fun c246_restoredS15_longRoute_notSpokenAsS15() {   // C250: 다시 고른다
        val sim = restoredWaiting()
        sim.ev(RouteState(sim.t + 1_000, true, 3600, listOf(rest("a", 5.0)), null)).tick(P.speechGapMs)
        assertTrue(sim.queued().isEmpty()); assertTrue(sim.all<Speak>().none { it.sid == Sid.S15 })
        assertEquals(listOf(Sid.S08), sim.all<Ask>().map { it.sid })
    }

    @Test fun c246_restoredS15_noRoute_s10AfterWait() {   // C250: 경로가 끝내 없으면 S10으로 다시 고른다
        val sim = restoredWaiting()
        sim.ev(Tick(sim.s.bootAt!! + P.routeWaitMs + 1_000))
        assertTrue(sim.all<Speak>().none { it.sid == Sid.S15 })
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind)
    }
}
