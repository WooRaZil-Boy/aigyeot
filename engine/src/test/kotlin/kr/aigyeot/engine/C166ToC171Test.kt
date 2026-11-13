package kr.aigyeot.engine

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 명세서 17장 C170·C171·U56. C166~C168은 호스트 시험(HostTest).
class C166ToC171Test {
    private fun Sim.alerts() = all<SendToPhone>().filter { it.type == "handoff_alert" }
    private fun text(c: SendToPhone) = c.body["text"]!!.jsonPrimitive.content

    @Test fun c170_careClearedClosesQaWindow() {
        val sim = idle()
        val qaLine = Line(Sid.QA, mapOf("text" to "답"), null, qaAnswer = true)
        val other = Line(Sid.S12, emptyMap(), null)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(qaLine.copy(vars = mapOf("text" to "말하는 줄")), qaLine, other),
            qa = QaWindow("l1", QaStep.THINKING, T0 + 10_000, false)))
        sim.ev(CareCleared(T0 + 1_000))
        assertEquals(true, sim.s.speech.qa?.careStale, "질문 창은 두고 옛 요약 표시(C196)")
        assertEquals(listOf("말하는 줄", null), sim.s.speech.queue.map { it.vars["text"] }, "말하는 줄은 두고 말하지 않은 ④ 답 줄은 뺀다")
        assertEquals(Sid.S12, sim.s.speech.queue.last().sid)
    }

    @Test fun c171_offerTtsFailure_forgetsRepeatBlock() {
        fun run(spoken: Boolean): Sim {
            val sim = session()
            val l = Line(Sid.S07_DEPART, emptyMap(), "n", queuedAt = sim.t, sessionId = sim.ses.id)
            sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(l)))   // 결정 ②: 넣을 때 금지 시각은 없다
            return sim.ev(SpeechDone(sim.t + 1_000, "u1", spoken))
        }
        assertTrue(Sid.S07_DEPART !in run(false).ses.spokenAt, "말하지 못했으면 10분 금지를 지운다")
        assertTrue(Sid.S07_DEPART in run(true).ses.spokenAt, "말했으면 남긴다")
    }

    @Test fun u56_handoffAlertTexts() {
        val noRest = session().handoff().flush()
        assertEquals("아기가 계속 울어요. 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요.", text(noRest.alerts().single()))
        val parked = session().route(rest("a", 5.0)).gear(Gear.P, MIN).handoff()
        assertEquals("차가 선 채로 아기가 계속 울어요.", text(parked.alerts().single()))
        val cap = session().route(rest("a", 5.0)).gear(Gear.P, MIN).apply { s = s.copy(session = ses.copy(playedMs = P.sessionPlayCapMs - 56_000)) }.attempt1().toGap()
        assertEquals(HandoffCause.SESSION_CAP, cap.ep.handoff!!.cause)
        assertEquals("차가 선 채로 달래기 시간이 끝났어요. 아기를 살펴봐 주세요.", text(cap.alerts().single()))
    }
}
