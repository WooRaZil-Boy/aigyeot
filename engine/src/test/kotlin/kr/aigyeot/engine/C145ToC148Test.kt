package kr.aigyeot.engine

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 명세서 17장 C145~C148(11-07 외부 7차 검토).
class C145ToC148Test {
    // ---- C145: 복원 뒤 목적지 있는 경로를 기다린다 ----
    private fun restored(): Sim {
        val sim = session()
        sim.s = sim.s.copy(bootAt = T0 + 120 * MIN + 30_000, car = sim.s.car.copy(route = null))
        return sim
    }

    @Test fun c145_restoreNoDestinationRouteKeepsWaiting() {
        val sim = restored()
        sim.ev(Tick(T0 + 121 * MIN))
        sim.ev(RouteState(T0 + 121 * MIN + 1_000, false, null, emptyList()))   // TBT만 먼저: 목적지 없음·쉼터 없음
        assertTrue(sim.sids().isEmpty(), "목적지 없는 경로는 기다림을 풀지 않는다")
        sim.ev(RouteState(T0 + 121 * MIN + 5_000, true, 3600, listOf(rest("a", 10.0, nursing = true)), 50.0))
        assertTrue(sim.sids().isNotEmpty() && Sid.S10 !in sim.sids(), "목적지·쉼터 경로로 제안: ${sim.sids()}")
    }

    @Test fun c145_restoreWaitEndsAfter60s() {
        val sim = restored()
        sim.ev(RouteState(T0 + 121 * MIN, false, null, emptyList()))
        assertTrue(sim.sids().isEmpty())
        sim.ev(Tick(T0 + 120 * MIN + 90_000))
        assertTrue(Sid.S10 in sim.sids(), "60초 뒤에는 지금처럼 그때 경로로")
    }

    @Test fun c145_wakeAtCoversRouteWaitEnd() {
        val sim = restored()
        sim.ev(RouteState(T0 + 121 * MIN, false, null, emptyList()))
        assertEquals(T0 + 120 * MIN + 90_000, sim.last.wakeAt)
    }

    // ---- C147: offer는 같은 세션 줄만 돌려준다 ----
    @Test fun c147_otherSessionSpeakingLineNotBound() {
        val sim = session().route(rest("n", 14.0, nursing = true))
        val a = sim.ses.id
        val old = Line(Sid.S07, mapOf("restName" to "n 휴게소", "distanceKm" to "14"), "n", queuedAt = sim.t, sessionId = a)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(old)))
        sim.gear(Gear.P).ev(RearDoor(sim.t, Side.LEFT, true)).ev(RearDoor(sim.t, Side.LEFT, false)).gear(Gear.D)
        assertNotEquals(a, sim.ses.id)
        sim.attempt2().toGap().recry()
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind)
        val pa = sim.ledger
        assertTrue(pa == null || pa.utteranceId != "u1", "A의 줄에 묶이지 않는다")
        assertNotEquals(true, sim.told())
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))   // A 줄의 SpeechDone은 B의 기록을 바꾸지 않는다
        assertNotEquals(true, sim.told())
        assertTrue(sim.all<SendToPhone>().none { it.type == "handoff_alert" && it.body["rest_name"]?.toString() == "\"n 휴게소\"" })
    }

    // ---- C148: 기록 지우기는 ④ 질문 창을 닫는다 ----
    private fun Sim.asking(): Sim {
        ev(AskButton(t))
        return ev(Heard(t, s.speech.qa!!.listenId, "왜 울어?"))
    }

    @Test fun c40f_clearRecords_closesQaWindow() {
        val sim = session().handoff().flush().gear(Gear.P, MIN).asking()
        val lid = sim.s.speech.qa!!.listenId
        sim.ev(ClearRecords(sim.t))
        assertNull(sim.s.speech.qa)
        sim.ev(QaReply(sim.t, lid, "지우기 전 요약 답"))
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.QA } && sim.cmds.none { it is Speak })
        sim.ev(SwitchSound(sim.t, LL, lid))   // 도구 사건도 Q7대로 무시
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun c40f_clearRecords_dropsQueuedQaAnswer_keepsSpeakingOne() {
        val sim = session()
        val qa = Line(Sid.QA, mapOf("text" to "답"), null, qaAnswer = true)
        val other = Line(Sid.S06, emptyMap(), null)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(qa, other, qa.copy(vars = mapOf("text" to "대기")))))
        sim.ev(ClearRecords(sim.t))
        assertEquals(listOf(Sid.QA, Sid.S06), sim.s.speech.queue.map { it.sid })
        assertEquals("답", sim.s.speech.queue.first().vars["text"])   // 말하는 줄은 남는다
    }
}
