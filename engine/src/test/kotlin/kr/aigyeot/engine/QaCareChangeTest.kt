package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 질문 중 돌봄 요약이 바뀌었을 때의 질문 답 처리.
class CareChangeDuringQaTest {
    private fun care(asOf: Long, feeds: Int = 1) = CareView(asOf, true, null, null, null, null, null, null, null, feeds)

    private fun withQa(sim: Sim, c: CareView?) {
        val qaLine = Line(Sid.QA, mapOf("text" to "답"), null, qaAnswer = true)
        sim.s = sim.s.copy(
            care = c,
            careSeq = 1,
            speech = sim.s.speech.copy(
                speaking = "u1",
                queue = listOf(qaLine.copy(vars = mapOf("text" to "말하는 줄")), qaLine, Line(Sid.S12, emptyMap(), null)),
                qa = QaWindow("l1", QaStep.THINKING, T0 + 10_000, false)
            )
        )
    }

    @Test fun newCareSummaryDuringQa_dropsOldAnswer() {
        val sim = idle()
        withQa(sim, care(T0 + 1_000))
        sim.ev(CareSummary(T0 + 500, care(T0 + 2_000, 2), 2))
        assertEquals(true, sim.s.speech.qa?.careStale, "질문 창은 두고 지난 요약 표시")
        assertEquals(listOf("말하는 줄", null), sim.s.speech.queue.map { it.vars["text"] }, "말하는 줄은 두고 말하지 않은 질문 답 줄은 뺀다")
    }

    // ---- 질문 창은 두고 그 질문의 글만 버린다. 도구는 처리 ----
    private fun thinking(): Pair<Sim, String> {
        val sim = session().handoff().flush().gear(Gear.P, MIN)
        sim.ev(AskButton(sim.t))
        val id = sim.s.speech.qa!!.listenId
        sim.ev(Heard(sim.t, id, "백색소음으로 바꿔 줘"))
        return sim to id
    }

    @Test fun careChangeKeepsWindow_toolStillRuns_textDropped() {
        val (sim, id) = thinking()
        sim.ev(CareSummary(sim.t + 10, care(sim.t + 5, 2), 2))
        assertEquals(true, sim.s.speech.qa?.careStale, "창은 열려 있다")
        sim.ev(SwitchSound(sim.t, WN, id))
        assertTrue(sim.s.speech.qa!!.ruleSaid, "도구 사건이 버려지지 않고 처리된다")
        assertEquals(Sid.S18, sim.s.speech.queue.first().sid)
        sim.ev(QaReply(sim.t, id, "지난 요약으로 만든 답"))
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.QA }, "글은 말하지 않는다")
    }

    @Test fun careChange_textOnlyReplyDropped() {
        val (sim, id) = thinking()
        sim.ev(CareSummary(sim.t + 10, care(sim.t + 5, 2), 2))
        sim.ev(QaReply(sim.t, id, "지난 요약으로 만든 답", llm = true)) // LLM 글만 버린다(대체 글이 없으면 말하지 않는다)
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.QA } && sim.cmds.none { it is Speak }, "도구 없이 글만 와도 말하지 않는다")
    }

    @Test fun careChangeDuringHearing_answerStillSpoken() {
        val sim = session().handoff().flush().gear(Gear.P, MIN)
        sim.ev(AskButton(sim.t))
        val id = sim.s.speech.qa!!.listenId
        sim.ev(CareSummary(sim.t + 10, care(sim.t + 5, 2), 2)) // 듣는 중에 요약이 바뀐다: 그 질문은 아직 요약을 보내지 않았다
        assertEquals(false, sim.s.speech.qa?.careStale)
        sim.ev(Heard(sim.t, id, "오늘 몇 번 먹었어"))
        sim.ev(QaReply(sim.t, id, "새 요약으로 만든 답"))
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.QA }, "새 요약으로 만든 답은 말한다")
    }

    @Test fun careChangeKeepsNonCareAnswer() {
        val (sim, id) = thinking()
        sim.ev(CareSummary(sim.t + 10, care(sim.t + 5, 2), 2))
        sim.ev(QaReply(sim.t, id, "실내 온도는 23도예요.", careUsed = false))
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.QA } || sim.all<Speak>().any { it.sid == Sid.QA }, "돌봄 값을 쓰지 않은 답은 말한다")
    }

    @Test fun careChangeDropsCareAnswer() {
        val (sim, id) = thinking()
        sim.ev(CareSummary(sim.t + 10, care(sim.t + 5, 2), 2))
        sim.ev(QaReply(sim.t, id, "기록상 오늘 1번 먹었어요.", careUsed = true, llm = true))
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.QA } && sim.all<Speak>().none { it.sid == Sid.QA })
    }

    // ---- 사실 답은 도착 때 상태로 만든 것이라 버리지 않고, 버려진 LLM 글은 대체 글로 말한다 ----
    @Test fun careChangeDuringThinking_factAnswerSpoken() {
        val (sim, id) = thinking()
        sim.ev(CareSummary(sim.t + 10, care(sim.t + 5, 2), 2))
        sim.ev(QaReply(sim.t, id, "기록상 오늘 2번 먹었어요.", careUsed = true))
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.QA && it.vars["text"] == "기록상 오늘 2번 먹었어요." } || sim.all<Speak>().any { it.sid == Sid.QA })
        assertEquals(null, sim.s.speech.qa, "창은 닫힌다")
    }

    @Test fun staleLlmText_replacedByFallback() {
        val (sim, id) = thinking()
        sim.ev(CareSummary(sim.t + 10, care(sim.t + 5, 2), 2))
        sim.ev(QaReply(sim.t, id, "지난 LLM 글", careUsed = true, llm = true, fallback = "왜 우는지는 알 수 없어요.", fallbackCare = true))
        val said = sim.s.speech.queue.filter { it.sid == Sid.QA }.map { it.vars["text"] } + sim.all<Speak>().filter { it.sid == Sid.QA }.map { it.vars["text"] }
        assertEquals(listOf("왜 우는지는 알 수 없어요."), said.distinct())
    }

    @Test fun dropQa_keepsNonCareQueuedAnswer() {
        val sim = idle()
        withQa(sim, care(T0 + 1_000))
        sim.s =
            sim.s.copy(
                speech = sim.s.speech.copy(
                    queue = sim.s.speech.queue.map {
                        if (it.sid == Sid.QA &&
                            it.vars["text"] == "답"
                        ) {
                            it.copy(careUsed = false)
                        } else {
                            it
                        }
                    }
                )
            )
        sim.ev(CareSummary(T0 + 500, care(T0 + 2_000, 2), 2))
        assertEquals(listOf("말하는 줄", "답", null), sim.s.speech.queue.map { it.vars["text"] }, "돌봄 값을 쓰지 않은 줄은 남긴다")
    }

    @Test fun freshQuestion_replyStillSpoken() {
        val (sim, id) = thinking()
        sim.ev(QaReply(sim.t, id, "답입니다"))
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.QA })
    }

    @Test fun sameCareSummary_keepsQa() {
        val sim = idle()
        val c = care(T0 + 1_000)
        withQa(sim, c)
        sim.ev(CareSummary(T0 + 500, c, 2)) // 같은 내용(seq만 새것): care가 바뀌지 않는다
        assertTrue(sim.s.speech.qa != null)
        assertEquals(3, sim.s.speech.queue.size)
    }

    @Test fun callCancelsSpeakingDepartOffer_doesNotBlockS07() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN)
        sim.route(nurse, dest = true, remainingSec = 9000, remainingKm = 150.0)
        sim.step<Ask>().single()
        sim.ev(CallMode(sim.t + 100, true)).ev(CallMode(sim.t + 100, false))
        assertNull(sim.ses.spokenAt[Sid.S07_DEPART])
        sim.attempt1().toConfirm().closeQuiet().flush().attempt1().flush()
        assertTrue(Sid.S07 in sim.sids(), sim.sids().toString())
    }

    // ---- 도구 결과 안내(qaAnswer, Sid.QA 아님)는 두고 LLM 답(Sid.QA)만 뺀다 ----
    private fun withToolResult(sim: Sim) {
        val tool = Line(Sid.S09, mapOf("restName" to "n 휴게소"), null, qaAnswer = true)
        sim.s = sim.s.copy(
            care = care(T0 + 1_000),
            careSeq = 1,
            speech = sim.s.speech.copy(
                speaking = "u1",
                queue = listOf(
                    Line(Sid.S12, emptyMap(), null),
                    Line(Sid.S12, emptyMap(), null).copy(sid = Sid.QA, vars = mapOf("text" to "답"), qaAnswer = true),
                    tool
                ),
                qa = QaWindow("l1", QaStep.THINKING, T0 + 10_000, false)
            )
        )
    }
    private fun Sim.sidsInQueue() = s.speech.queue.map { it.sid }

    @Test fun dropQaKeepsToolResultLine_careSummary() {
        val sim = idle()
        withToolResult(sim)
        sim.ev(CareSummary(T0 + 500, care(T0 + 2_000, 2), 2))
        assertEquals(listOf(Sid.S12, Sid.S09), sim.sidsInQueue())
    }

    @Test fun dropQaKeepsToolResultLine_careCleared() {
        val sim = idle()
        withToolResult(sim)
        sim.ev(CareCleared(T0 + 1_000))
        assertEquals(listOf(Sid.S12, Sid.S09), sim.sidsInQueue())
    }

    @Test fun dropQaKeepsToolResultLine_clearRecords() {
        val sim = idle()
        withToolResult(sim)
        sim.ev(ClearRecords(T0 + 1_000))
        assertEquals(listOf(Sid.S12, Sid.S09), sim.sidsInQueue()) // 기록 지우기도 이미 실행한 도구의 결과 안내는 둔다
    }
}
