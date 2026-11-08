package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// C312: 조향 창 observed(보지 못함) 칸. C313: 답의 결과는 질문 당시 문맥(PendingAsk)으로 정한다
class C312C313Test {
    @Test fun c312_unobservedWindow_markedNotObserved() {
        val sim = session()
        val w0 = sim.t
        fun v(at: Long) = sim.ev(VehicleSample(at, 60.0, false, 0.0, TurnSignal.NONE))
        v(w0)                                     // 창 1
        sim.tick(59_000).attempt1()               // 창 경계 직전에 자동 시도 재생 시작
        v(w0 + 60_000)                            // 창 1 닫힘, 창 2 열림(재생 중)
        sim.ev(CrySegment(w0 + 90_000, w0 + 87_000, w0 + 90_000, 0.9, false))   // 다음 창에도 울음이 이어지나 보지 않는 구간
        v(w0 + 120_000)                           // 창 2 닫힘
        val recs = sim.all<SaveSteerWindow>().map { it.record }
        assertEquals(2, recs.size)
        assertFalse(recs[1].cryPresent); assertTrue(recs[1].playing)
        assertFalse(recs[1].observed, "보지 못한 창은 울음 없음이 아니다")
        assertTrue(recs[0].observed, "창 1은 재생 시작 전까지 봤다")
    }

    @Test fun c312_observedWindow_staysObserved() {
        val sim = session()
        sim.ev(VehicleSample(sim.t, 60.0, false, 0.0, TurnSignal.NONE))
        sim.ev(VehicleSample(sim.t + 60_000, 60.0, false, 0.0, TurnSignal.NONE))
        assertTrue(sim.all<SaveSteerWindow>().single().record.observed)
    }

    // C341: 자동 달래기 주행의 조향 창. 울음을 본 창은 재생이 시작돼 미관측이 되고, 재생이 끝나고 조용해진 뒤 창은 관측·재생 아님·울음 없음이다(Report.steering이 이 가정에 기댄다)
    @Test fun c341_autoSoothe_steerWindowFlags() {
        val sim = session()
        val w0 = sim.t
        fun v(at: Long) = sim.ev(VehicleSample(at, 60.0, false, 0.0, TurnSignal.NONE))
        v(w0)
        var at = w0; var quiet = false
        while (at < w0 + 8 * 60_000) {
            at += 5_000; v(at)
            if (at == w0 + 30_000) sim.ev(CrySegment(at, at - 3_000, at, 0.9, true)).started()   // 창 1 가운데쯤 울음 → 자동 시도 재생
            if (!quiet && sim.s.session?.play != null && at >= w0 + 30_000 + P.attemptMs) { quiet = true; sim.toGap().closeQuiet() }   // 재생 끝 → 조용함
        }
        val recs = sim.all<SaveSteerWindow>().map { it.record }
        val cry = recs.first { it.cryPresent }
        assertEquals(w0, cry.startAt); assertTrue(cry.playing); assertFalse(cry.observed)
        assertTrue(recs.any { it.startAt > cry.startAt && it.observed && !it.playing && !it.cryPresent }, "재생이 끝난 뒤 조용한 관측 창: $recs")
    }

    // C325 ①: 마이크 없이 연 세션(1e)의 창은 울음 없음이 아니라 보지 못함이다
    @Test fun c325_micOffSession_windowNotObserved() {
        val sim = session(mic = false)
        assertFalse(sim.s.hears())
        sim.ev(VehicleSample(sim.t, 60.0, false, 0.0, TurnSignal.NONE))
        sim.ev(VehicleSample(sim.t + 60_000, 60.0, false, 0.0, TurnSignal.NONE))
        assertFalse(sim.all<SaveSteerWindow>().single().record.observed)
    }

    // C332: 질문의 에피소드 문맥은 말하는 때가 아니라 큐에 넣은 때. 브레이크로 미뤄진 출발 제안이 그사이 열린 에피소드에 붙지 않는다
    @Test fun c332_deferredDepartAsk_notAttachedToLaterEpisode() {
        val nurse = rest("n", 9.0, nursing = true)
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN)
        sim.ev(VehicleSample(sim.t, 60.0, true, 0.0, TurnSignal.NONE))   // 브레이크: 바쁨
        sim.route(nurse, dest = true, remainingSec = 9000, remainingKm = 150.0)
        assertTrue(sim.all<Ask>().isEmpty()); assertFalse(sim.s.speech.queue.single { it.sid == Sid.S07_DEPART }.handoff)
        sim.alarm(1_000).started()
        val epId = sim.ep.id
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        sim.done(2_000).tick(P.speechGapMs)   // 앞 발화(S02)를 끝낸 뒤 미뤄진 제안이 나온다
        val ask = sim.all<Ask>().single { it.sid == Sid.S07_DEPART }
        assertEquals(null, sim.ses.pendingAsk!!.episodeId)
        sim.ev(SpeechDone(sim.t + 1_000, sim.s.speech.speaking!!, true))
        sim.ev(Answer(sim.t + 500, ask.askId, YesNo.YES))
        val add = sim.step<AddWaypoint>().single()
        sim.ev(WaypointResult(sim.t + 500, add.cmdId, "n", true))
        assertEquals(epId, sim.ep.id)
        assertFalse(sim.ep.waypointAdded, "묻지 않은 경유지 추가가 에피소드에 남지 않는다")
        assertTrue(sim.ses.restLog.any { it.sid == Sid.S07_DEPART && it.waypointOk == true })
    }

    private fun heldOffer(): Sim {
        val sim = session().route(rest("n", 14.0, nursing = true))
        sim.attempt2().toGap().ev(VehicleSample(sim.t, 60.0, true, 0.0, TurnSignal.NONE)).recry()   // 바쁨 중 인계: S07이 큐에서 미뤄짐
        assertTrue(sim.s.speech.queue.single { it.sid == Sid.S07 }.isHandoffOf(sim.ses.id), "인계 줄")
        return sim
    }

    // C334: 인계 줄만 에피소드에 속한다. 그 에피소드는 인계 장부 한 곳에서 읽는다
    @Test fun c334_handoffOffer_yes_waypointAddedOnLedgerEpisode() {
        val sim = heldOffer()
        val epId = sim.ep.id
        assertEquals(epId, sim.s.handoffs.single { it.sessionId == sim.ses.id }.episodeId)
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        if (sim.all<Ask>().isEmpty()) sim.flush()
        val ask = sim.all<Ask>().single { it.sid == Sid.S07 }
        assertEquals(epId, sim.ses.pendingAsk!!.episodeId)
        sim.ev(Answer(sim.t + 500, ask.askId, YesNo.YES))
        val add = sim.step<AddWaypoint>().single()
        sim.ev(WaypointResult(sim.t + 500, add.cmdId, "n", true))
        assertTrue(sim.ep.waypointAdded)
    }

    // C333: 같은 질문 갱신(같은 sid로 바꿔 넣기)은 세션 문맥과 인계 표시를 지킨다
    @Test fun c333_sameQuestionUpdate_keepsOriginalContext() {
        val sim = heldOffer()
        sim.ev(RouteState(sim.t + 1_000, false, null, listOf(rest("m", 12.0, nursing = true)), null))
        val offers = sim.s.speech.queue.filter { it.askRestId != null }
        assertTrue(offers.isNotEmpty() && offers.all { it.isHandoffOf(sim.ses.id) }, "인계 줄 그대로: $offers")
    }

    private fun routeAt(sim: Sim, dt: Long, vararg rests: RestCandidate) =
        sim.ev(RouteState(sim.t + dt, true, 9000, rests.toList(), 150.0))

    /** 브레이크로 미뤄진 2시간 제안(S11)이 에피소드 밖에서 큐에 들고, 그 뒤 울음 에피소드가 열렸다. */
    private fun deferredLong(): Sim {
        val sim = session()
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(driveFrom = sim.t - 121 * MIN) })   // 2시간을 넘겼다
        sim.ev(VehicleSample(sim.t, 60.0, true, 0.0, TurnSignal.NONE))   // 브레이크: 바쁨
        routeAt(sim, 100, rest("a", 12.0, nursing = true))
        val q = sim.s.speech.queue.singleOrNull { it.sid == Sid.S11 } ?: error("${sim.s.speech} ${sim.log.takeLast(4)}")
        assertFalse(q.handoff)
        sim.alarm(1_000).started()
        return sim
    }

    // C333: 에피소드 밖에서 큐에 든 제안이 경로 갱신으로 바뀌어도 새 에피소드 문맥이 붙지 않는다
    @Test fun c333_deferredAsk_routeUpdate_staysOutsideEpisode() {
        val sim = deferredLong()
        routeAt(sim, 1_000, rest("a", 9.0, nursing = true))   // 같은 쉼터의 거리 갱신
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        sim.done(2_000).tick(P.speechGapMs)   // 꺼낼 때 다시 고른다(C258): 같은 S11을 m으로 갱신
        val ask = sim.all<Ask>().single { it.sid == Sid.S11 }
        assertEquals("a", sim.ses.pendingAsk!!.restId)
        assertEquals(null, sim.ses.pendingAsk!!.episodeId, "갱신해도 에피소드 밖 문맥")
        sim.ev(SpeechDone(sim.t + 1_000, sim.s.speech.speaking!!, true))
        sim.ev(Answer(sim.t + 500, ask.askId, YesNo.YES))
        val add = sim.step<AddWaypoint>().single()
        sim.ev(WaypointResult(sim.t + 500, add.cmdId, "a", true))
        assertFalse(sim.ep.waypointAdded)
        assertTrue(sim.ses.restLog.any { it.sid == Sid.S11 && it.waypointOk == true })
    }

    // C334: 다른 쉼터로 다시 골라져도 인계 줄이 아니면 에피소드에 붙지 않는다
    @Test fun c334_deferredAsk_repickedToOtherRest_staysOutsideEpisode() {
        val sim = deferredLong()
        routeAt(sim, 1_000, rest("m", 20.0, nursing = true))
        assertTrue(sim.all<Ask>().isEmpty(), "아직 묻지 않았다 ${sim.s.speech}")
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        sim.done(2_000).tick(P.speechGapMs)
        assertEquals("m", sim.ses.pendingAsk!!.restId, "다른 쉼터로 새로 물음 ${sim.all<Ask>()} ${sim.s.car.route}")
        assertEquals(null, sim.ses.pendingAsk!!.episodeId, "인계 줄이 아니면 에피소드 밖")
        val ask = sim.all<Ask>().single { it.sid == Sid.S11 }
        sim.ev(SpeechDone(sim.t + 1_000, sim.s.speech.speaking!!, true))
        sim.ev(Answer(sim.t + 500, ask.askId, YesNo.YES))
        val add = sim.step<AddWaypoint>().single()
        sim.ev(WaypointResult(sim.t + 500, add.cmdId, "m", true))
        assertFalse(sim.ep.waypointAdded)
    }

    @Test fun c313_departYesAfterCryStarts_notAttachedToHandoff() {

        val nurse = rest("n", 9.0, nursing = true)
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN)
        sim.route(nurse, dest = true, remainingSec = 9000, remainingKm = 150.0)
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S07_DEPART, ask.sid)
        assertEquals(null, sim.ses.pendingAsk!!.episodeId)
        sim.ev(SpeechDone(sim.t + 1_000, sim.s.speech.speaking!!, true))   // 질문을 다 말함(듣는 중)
        sim.alarm(1_000).started()                // 질문을 들은 뒤 '응' 전에 울음 → 첫 시도로 에피소드 열림
        val epId = sim.ep.id
        sim.ev(Answer(sim.t + 500, ask.askId, YesNo.YES))
        val add = sim.step<AddWaypoint>().single()
        assertEquals(null, sim.ses.waypointPending!!.episodeId, "질문 당시 문맥(에피소드 없음)")
        sim.ev(WaypointResult(sim.t + 500, add.cmdId, "n", true))
        assertFalse(sim.ep.waypointAdded); assertEquals(epId, sim.ep.id)
        sim.toGap().recry().started().toGap().recry()   // 달래기 두 번 실패 → 인계
        sim.flush()
        val rec = sim.all<SaveEpisode>().last().record
        assertEquals(epId, rec.id); assertTrue(sim.ep.handoff != null, "인계까지 갔다")
        assertFalse(rec.waypointAdded, "인계 기록에 경유지 추가가 붙지 않는다")
        assertTrue(sim.ses.restLog.any { it.sid == Sid.S07_DEPART && it.waypointOk == true }, "세션 휴식 기록에는 남는다")
    }
}
