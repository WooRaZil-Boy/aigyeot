package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 돌봄 지우기 때 질문 창, 제안 TTS 실패 뒤 반복 금지, 인계 알림 문장.
class AlertTextAndTtsFailureTest {

    @Test fun careClearedClosesQaWindow() {
        val sim = idle()
        val qaLine = Line(Sid.QA, mapOf("text" to "답"), null, qaAnswer = true)
        val other = Line(Sid.S12, emptyMap(), null)
        sim.s = sim.s.copy(
            speech = sim.s.speech.copy(
                speaking = "u1",
                queue = listOf(qaLine.copy(vars = mapOf("text" to "말하는 줄")), qaLine, other),
                qa = QaWindow("l1", QaStep.THINKING, T0 + 10_000, false)
            )
        )
        sim.ev(CareCleared(T0 + 1_000))
        assertEquals(true, sim.s.speech.qa?.careStale, "질문 창은 두고 지난 요약 표시")
        assertEquals(listOf("말하는 줄", null), sim.s.speech.queue.map { it.vars["text"] }, "말하는 줄은 두고 말하지 않은 질문 답 줄은 뺀다")
        assertEquals(Sid.S12, sim.s.speech.queue.last().sid)
    }

    @Test fun offerTtsFailure_forgetsRepeatBlock() {
        fun run(spoken: Boolean): Sim {
            val sim = session()
            val l = Line(Sid.S07_DEPART, emptyMap(), "n", queuedAt = sim.t, sessionId = sim.ses.id)
            sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(l))) // 넣을 때 금지 시각은 없다
            return sim.ev(SpeechDone(sim.t + 1_000, "u1", spoken))
        }
        assertTrue(Sid.S07_DEPART !in run(false).ses.spokenAt, "말하지 못했으면 10분 금지를 지운다")
        assertTrue(Sid.S07_DEPART in run(true).ses.spokenAt, "말했으면 남긴다")
    }

    @Test fun handoffAlertTexts() {
        val noRest = session().handoff().flush()
        assertEquals("아기가 계속 울어요. 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요.", text(noRest.alerts().single()))
        val parked = session().route(rest("a", 5.0)).gear(Gear.P, MIN).handoff()
        assertEquals("차가 선 채로 아기가 계속 울어요.", text(parked.alerts().single()))
        val cap = session().route(rest("a", 5.0)).gear(Gear.P, MIN).apply {
            s = s.copy(session = ses.copy(playedMs = P.sessionPlayCapMs - 56_000))
        }.attempt1().toGap()
        assertEquals(HandoffCause.SESSION_CAP, cap.ep.handoff!!.cause)
        assertEquals("차가 선 채로 달래기 시간이 끝났어요. 아기를 살펴봐 주세요.", text(cap.alerts().single()))
    }
}

// 장면 시작이 말하는 인계 줄을 끊을 때의 알림, 보호자가 사라진 경우.
class SceneAlertTest {
    private val h = HandoffTest()
    private fun Sim.alertsOf() = all<SendToPhone>().filter { it.type == "handoff_alert" }
    private fun speakingOffer(): Sim = h.speakingOffer().first

    // ---- 코어 함수: 말하는 중인 인계 줄을 끊으면 이름 없는 알림·false, 반복 금지 지움 ----
    @Test fun cancelForScene_plainAlertAndToldFalse() { // cancelForScene → sceneStart의 실제 상태 Step(수명 표 SCENE_START)
        val sim = speakingOffer()
        val step = Engine().sceneStart(sim.s, sim.s, sim.t + 100).realStep
        assertTrue(CancelSpeech in step.commands)
        assertEquals(1, step.commands.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" })
        assertEquals("아기가 계속 울어요.", step.commands.filterIsInstance<SendToPhone>().single().body["text"].toString().trim('"'))
        assertEquals(null, step.state.speech.speaking)
        assertTrue(step.state.speech.queue.isEmpty())
        assertEquals(false, step.commands.filterIsInstance<SettleHandoff>().last().toldDriver)
        assertEquals(null, step.state.openLedger())
    }

    // ---- 알림을 낼 때 보호자가 이미 없으면 보내지 않는다(guardian_notified는 link가 넣을 때만 참) ----
    @Test fun guardianGoneBeforeSend_notNotified() {
        val sim = speakingOffer()
        sim.ev(LinkChanged(sim.t + 500, 0)) // 인계 발화 중 마지막 보호자 해제
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertTrue(sim.alertsOf().isEmpty(), "받는 사람이 없으면 알림을 내지 않는다")
        assertEquals(true, sim.told())
    }

    @Test fun guardianGoneBeforeSend_flushPlain_notNotified() {
        val sim = speakingOffer()
        sim.ev(LinkChanged(sim.t + 500, 0))
        sim.ev(SpeechDone(sim.t + 1_000, "u1", false)) // 말하지 못함: 이름 없는 알림 길
        assertTrue(sim.alertsOf().isEmpty())
        assertEquals(false, sim.told())
    }
}

// 복원된 쉼터 제안과 경로, 상한 인계 문장.
class RestOfferRouteTest {
    private val alerts: Sim.() -> List<SendToPhone> = { all<SendToPhone>().filter { it.type == "handoff_alert" } }
    private fun Sim.cap(leftMs: Long) = apply { s = s.copy(session = ses.copy(playedMs = P.sessionPlayCapMs - leftMs)) }

    // ---- 복원된 쉼터 제안과 경로 ----
    private fun restoredS08(): Sim {
        val sim = session().route(rest("old", 14.0, nursing = true))
        val q = Line(Sid.S08, mapOf("restName" to "old 휴게소", "distanceKm" to "14"), "old", queuedAt = sim.t, sessionId = sim.ses.id)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = listOf(q)), bootAt = sim.t, car = sim.s.car.copy(route = null))
        return sim
    }

    @Test fun restoredS08WaitsForRoute() {
        val sim = restoredS08()
        sim.ev(Tick(sim.t + 1_000))
        assertEquals(listOf(Sid.S08), sim.s.speech.queue.map { it.sid }, "경로 기다림 동안 큐에 둔다")
        assertTrue(sim.all<Ask>().isEmpty())
    }

    @Test fun yesForRestNotInRoute_noWaypoint() {
        val sim = restoredS08()
        sim.ev(RouteState(sim.t + 500, true, 3000, listOf(rest("old", 14.0, nursing = true)), 40.0)) // 유효한 경로: 질문이 나간다
        sim.ev(Tick(sim.t + 1_000))
        val ask = sim.all<Ask>().single()
        sim.ev(RouteState(sim.t + 2_000, true, 3000, listOf(rest("new", 9.0, nursing = true)), 40.0)) // 질문 뒤 경로가 바뀌어 쉼터가 빠졌다
        sim.ev(Answer(sim.t + 3_000, ask.askId, YesNo.YES))
        assertTrue(sim.all<AddWaypoint>().isEmpty(), "새 경로에 없는 쉼터는 넣지 않는다")
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S19 } || sim.all<Speak>().any { it.sid == Sid.S19 })
    }

    // ---- 복원된 S08도 지금 경로와 대조한다 ----
    @Test fun restoredS08_notInRoute_dropped() {
        val sim = restoredS08()
        sim.ev(Tick(sim.t + 1_000))
        sim.ev(RouteState(sim.t + 2_000, true, 3000, listOf(rest("new", 9.0, nursing = true)), 40.0))
        sim.ev(Tick(sim.t + 3_000))
        assertTrue(sim.all<Ask>().none { it.vars["restName"] == "old 휴게소" || it.vars["distanceKm"] == "14" }, "지난 쉼터를 묻지 않는다")
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S08 })
    }

    @Test fun s08_inRoute_refreshedDistance() {
        val sim = restoredS08()
        sim.ev(Tick(sim.t + 1_000))
        sim.ev(RouteState(sim.t + 2_000, true, 3000, listOf(rest("old", 9.0, nursing = true)), 40.0))
        sim.ev(Tick(sim.t + 3_000))
        val ask = sim.all<Ask>().single()
        assertEquals("9", ask.vars["distanceKm"], "지금 경로의 거리로 묻는다")
    }

    // ---- 상한 인계 문장 ----
    @Test fun capHandoffTextsAllKinds() {
        val n = rest("n", 14.0, nursing = true)
        // rest_offer 이름 있음: 말한 뒤 알림
        val named = session().route(n).cap(56_000).attempt1().toGap().flush()
        assertEquals("달래기 시간이 끝나 n 휴게소에 들르자고 운전자에게 제안했어요.", text(named.alerts().single()))
        // 이름 없음: 말하지 못한 채 닫힘
        val plain = session().route(n).cap(56_000).attempt1().toGap()
        plain.ev(SpeechDone(plain.t, "none", false))
        plain.s = plain.s.copy(speech = plain.s.speech.copy(queue = emptyList()))
        plain.ev(Tick(plain.t + 1_000))
        assertEquals("달래기 시간이 끝났어요. 아기를 살펴봐 주세요.", text(plain.alerts().single()))
        val arr = session().route(rest("a", 5.0), dest = true, remainingSec = 530).cap(56_000).attempt1().toGap()
        assertEquals("달래기 시간이 끝났어요. 9분 뒤 도착해요.", text(arr.alerts().single()))
        val noRest = session().route().cap(56_000).attempt1().toGap()
        assertEquals("달래기 시간이 끝나 안전한 곳에 세우도록 안내했어요.", text(noRest.flush().alerts().single()))
    }

    @Test fun pendingAlertKeepsCause() {
        val sim = session().route(rest("n", 14.0, nursing = true)).cap(56_000).attempt1().toGap()
        assertEquals(HandoffCause.SESSION_CAP, sim.ledger!!.cause)
        val two = session().route(rest("n", 14.0, nursing = true)).handoff()
        assertEquals(HandoffCause.TWO_ATTEMPTS, two.ledger!!.cause)
        two.flush()
        assertEquals("아기가 계속 울어서 n 휴게소에 들르자고 운전자에게 제안했어요.", text(two.alerts().single()))
    }
}
