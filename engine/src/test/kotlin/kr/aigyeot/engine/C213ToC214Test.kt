package kr.aigyeot.engine

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 명세서 17장 C213·C214.
class C213ToC214Test {
    private val alerts: Sim.() -> List<SendToPhone> = { all<SendToPhone>().filter { it.type == "handoff_alert" } }
    private fun text(c: SendToPhone) = c.body["text"]!!.jsonPrimitive.content
    private fun Sim.cap(leftMs: Long) = apply { s = s.copy(session = ses.copy(playedMs = P.sessionPlayCapMs - leftMs)) }

    // ---- C213 ----
    private fun restoredS08(): Sim {
        val sim = session().route(rest("old", 14.0, nursing = true))
        val q = Line(Sid.S08, mapOf("restName" to "old 휴게소", "distanceKm" to "14"), "old", queuedAt = sim.t, sessionId = sim.ses.id)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = listOf(q)), bootAt = sim.t, car = sim.s.car.copy(route = null))
        return sim
    }

    @Test fun c213_restoredS08WaitsForRoute() {
        val sim = restoredS08()
        sim.ev(Tick(sim.t + 1_000))
        assertEquals(listOf(Sid.S08), sim.s.speech.queue.map { it.sid }, "경로 기다림 동안 큐에 둔다")
        assertTrue(sim.all<Ask>().isEmpty())
    }

    @Test fun c213_yesForRestNotInRoute_noWaypoint() {
        val sim = restoredS08()
        sim.ev(RouteState(sim.t + 500, true, 3000, listOf(rest("old", 14.0, nursing = true)), 40.0))   // 유효한 경로: 질문이 나간다
        sim.ev(Tick(sim.t + 1_000))
        val ask = sim.all<Ask>().single()
        sim.ev(RouteState(sim.t + 2_000, true, 3000, listOf(rest("new", 9.0, nursing = true)), 40.0))   // 질문 뒤 경로가 바뀌어 쉼터가 빠졌다
        sim.ev(Answer(sim.t + 3_000, ask.askId, YesNo.YES))
        assertTrue(sim.all<AddWaypoint>().isEmpty(), "새 경로에 없는 쉼터는 넣지 않는다")
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S19 } || sim.all<Speak>().any { it.sid == Sid.S19 })
    }

    // ---- C215: 복원된 S08도 지금 경로와 대조한다 ----
    @Test fun c215_restoredS08_notInRoute_dropped() {
        val sim = restoredS08()
        sim.ev(Tick(sim.t + 1_000))
        sim.ev(RouteState(sim.t + 2_000, true, 3000, listOf(rest("new", 9.0, nursing = true)), 40.0))
        sim.ev(Tick(sim.t + 3_000))
        assertTrue(sim.all<Ask>().none { it.vars["restName"] == "old 휴게소" || it.vars["distanceKm"] == "14" }, "옛 쉼터를 묻지 않는다")
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S08 })
    }

    @Test fun c215_s08_inRoute_refreshedDistance() {
        val sim = restoredS08()
        sim.ev(Tick(sim.t + 1_000))
        sim.ev(RouteState(sim.t + 2_000, true, 3000, listOf(rest("old", 9.0, nursing = true)), 40.0))
        sim.ev(Tick(sim.t + 3_000))
        val ask = sim.all<Ask>().single()
        assertEquals("9", ask.vars["distanceKm"], "지금 경로의 거리로 묻는다")
    }

    // ---- C214 ----
    @Test fun c214_capHandoffTextsAllKinds() {
        val n = rest("n", 14.0, nursing = true)
        // rest_offer 이름 있음: 말한 뒤 알림
        val named = session().route(n).cap(56_000).attempt1().toGap().flush()
        assertEquals("달래기 시간이 끝나 n 휴게소에 들르자고 운전자에게 제안했어요.", text(named.alerts().single()))
        // 이름 없음: 말하지 못한 채 닫힘
        val plain = session().route(n).cap(56_000).attempt1().toGap()
        plain.ev(SpeechDone(plain.t, "none", false)); plain.s = plain.s.copy(speech = plain.s.speech.copy(queue = emptyList())); plain.ev(Tick(plain.t + 1_000))
        assertEquals("달래기 시간이 끝났어요. 아기를 살펴봐 주세요.", text(plain.alerts().single()))
        val arr = session().route(rest("a", 5.0), dest = true, remainingSec = 530).cap(56_000).attempt1().toGap()
        assertEquals("달래기 시간이 끝났어요. 9분 뒤 도착해요.", text(arr.alerts().single()))
        val noRest = session().route().cap(56_000).attempt1().toGap()
        assertEquals("달래기 시간이 끝나 안전한 곳에 세우도록 안내했어요.", text(noRest.flush().alerts().single()))
    }

    @Test fun c214_pendingAlertKeepsCause() {
        val sim = session().route(rest("n", 14.0, nursing = true)).cap(56_000).attempt1().toGap()
        assertEquals(HandoffCause.SESSION_CAP, sim.ledger!!.cause)
        val two = session().route(rest("n", 14.0, nursing = true)).handoff()
        assertEquals(HandoffCause.TWO_ATTEMPTS, two.ledger!!.cause)
        two.flush()
        assertEquals("아기가 계속 울어서 n 휴게소에 들르자고 운전자에게 제안했어요.", text(two.alerts().single()))
    }
}
