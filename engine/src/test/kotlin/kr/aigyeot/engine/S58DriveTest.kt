package kr.aigyeot.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 5.8 장시간 주행·도착·수유 시계·말하기·④ 묻기·에피소드·우선순위·조향.
class S58DriveTest {
    private val nurse = rest("n", 9.0, nursing = true)
    /** 수유실 경유지를 넣은 세션(장면 흉내 DriverAddWaypoint, listenId 없음). */
    private fun added(): Sim {
        val sim = session().route(nurse).ev(DriverAddWaypoint(T0, "n"))
        return sim.ev(WaypointResult(sim.t + 500, sim.step<AddWaypoint>().single().cmdId, "n", true))
    }
    /** ④ 질문을 던져 답을 기다리는 상태(THINKING). */
    private fun Sim.asking(text: String = "왜 울어?"): Sim {
        if (s.speech.qa == null) ev(AskButton(t))
        return ev(Heard(t, s.speech.qa!!.listenId, text))
    }
    private val Sim.qa get() = s.speech.qa!!

    @Test fun l1_longDrive_offersS11() {
        val sim = session().route(rest("a", 12.0)).ev(Tick(T0 + 119 * MIN + 30_000)).attempt1().done()
        sim.ev(Tick(T0 + 120 * MIN))
        assertEquals(Phase.ATTEMPT, sim.ses.phase)               // 재생 그대로
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S11, ask.sid); assertEquals("2시간 0분", ask.vars["drove"])
        assertTrue(sim.all<SendToPhone>().isEmpty())
        assertFalse(sim.ses.longDriveOffered)                    // 말하는 중: 아직 아니다(C84)
        sim.done()
        assertTrue(sim.ses.longDriveOffered)
        sim.tick(30 * MIN)
        assertEquals(1, sim.all<Ask>().count { it.sid == Sid.S11 })
    }

    @Test fun l2_park15min_resetsDrive() {
        val sim = session().tick(100 * MIN).gear(Gear.P).tick(14 * MIN)
        assertNotNull(sim.ses.driveFrom)
        sim.tick(MIN)
        assertNull(sim.ses.driveFrom); assertFalse(sim.ses.longDriveOffered)
        sim.gear(Gear.D, MIN)
        assertEquals(sim.t, sim.ses.driveFrom)
    }

    @Test fun a1_parkAfter5min_saysS12() {
        val sim = session().gear(Gear.P, 5 * MIN)
        assertTrue(Sid.S12 in sim.sids())
        sim.flush().clear().gear(Gear.D, 20 * MIN).gear(Gear.P, 2 * MIN)   // 마지막 P 뒤 2분만 달림
        assertTrue(Sid.S12 !in sim.sids())
    }

    @Test fun f1_feedingLatestWins() {
        val sim = idle(feedingStart = T0 - 60 * MIN).ev(FeedingStarted(T0, T0 - 10 * MIN, FeedingFrom.CAR_SCREEN, null))
        assertEquals(T0 - 10 * MIN, sim.s.feedingStart)
        assertEquals(SaveFeedingStart(T0 - 10 * MIN), sim.step<SaveFeedingStart>().single())
        assertEquals("feeding_state", sim.step<SendToPhone>().single().type)
        sim.ev(FeedingStarted(T0, T0 - 5 * MIN, FeedingFrom.PHONE, "m1"))
        assertEquals(T0 - 5 * MIN, sim.s.feedingStart)
        assertTrue(sim.cmds.isEmpty())                       // 폰 입력은 link가 저장·전송했다
    }

    @Test fun f2_olderFeedingIgnored() {
        val sim = idle(feedingStart = T0).ev(FeedingStarted(T0, T0 - MIN, FeedingFrom.CAR_SCREEN, null))
        assertEquals(T0, sim.s.feedingStart); assertTrue(sim.cmds.isEmpty())
        sim.ev(FeedingStarted(T0, T0, FeedingFrom.PHONE, null))
        assertEquals(T0, sim.s.feedingStart)
    }

    @Test fun f3_feedingWindowBounds() {
        val s = 0L
        val iv = 100 * MIN
        assertFalse(feedingWindow(s, 100, (iv * 0.8).toLong() - 1))
        assertTrue(feedingWindow(s, 100, (iv * 0.8).toLong()))
        assertTrue(feedingWindow(s, 100, iv * 2 - 1))
        assertFalse(feedingWindow(s, 100, iv * 2))
        assertFalse(feedingWindow(iv * 2 - 10 * MIN, 100, iv * 2 - 1))   // 새 입력 뒤에는 새 시작부터
    }

    @Test fun f4_noInterval_feedingOff() {
        for (now in listOf(0L, 60 * MIN, 600 * MIN)) assertFalse(feedingWindow(0, null, now))
        assertFalse(feedingWindow(null, 120, 100 * MIN))
    }

    @Test fun f5_departure_offersNursingRest() {
        // C31: 휴게소별 도착 예상을 못 얻으면 출발 제안을 뺀다. 지금 입력(RouteState)에는 도착 예상이 없다.
        val sim = idle(prof = profile(interval = 180), feedingStart = T0 - 100 * MIN)   // 시간대는 44분 뒤
            .route(rest("a", 5.0), nurse, dest = true, remainingSec = 2 * 3600).doorCycle().gear(Gear.D)
        assertEquals(Phase.LISTENING, sim.ses.phase)
        assertTrue(sim.all<Ask>().isEmpty()); assertTrue(Sid.S07_DEPART !in sim.sids())
        assertNull(sim.ses.pendingAsk)
    }

    @Test fun f6_confirmFeeding_updatesStart() {
        val sim = added().gear(Gear.P, MIN)
        val pAt = sim.t
        sim.tick(15 * MIN).ev(FeedingConfirmed(sim.t, true))
        assertEquals(pAt, sim.s.feedingStart)
        assertNull(sim.ses.nursingStop)
        assertEquals(SaveFeedingStart(pAt), sim.step<SaveFeedingStart>().single())
        assertEquals("feeding_state", sim.step<SendToPhone>().single().type)
    }

    @Test fun f7_firstParkAfterNursingWaypoint_tracked() {
        val sim = added()
        assertNotNull(sim.ses.nursingStop!!.addedAt)
        sim.gear(Gear.P, MIN)
        assertEquals(sim.t, sim.ses.nursingStop!!.firstPAt)
        sim.ev(GearChanged(sim.t + MIN, Gear.P))
        assertEquals(sim.t - MIN, sim.ses.nursingStop!!.firstPAt)
    }

    @Test fun f8_firstPark15min_showsFeedingConfirm() {
        val sim = added().gear(Gear.P, MIN)
        val pAt = sim.t
        assertEquals(pAt + 15 * MIN, sim.last.wakeAt)
        sim.tick(15 * MIN - 1)
        assertTrue(sim.step<ShowNotice>().isEmpty())
        sim.tick(1)
        assertEquals(Notice.FEEDING_CONFIRM, sim.step<ShowNotice>().single().kind)
        assertTrue(sim.ses.nursingStop!!.confirmShown)
    }

    @Test fun f9_driveBeforeConfirm_clearsNursingStop() {
        val sim = added().gear(Gear.P, MIN).tick(5 * MIN).gear(Gear.D)
        assertNull(sim.ses.nursingStop)
        assertTrue(sim.all<ShowNotice>().none { it.kind == Notice.FEEDING_CONFIRM })
    }

    @Test fun f10_confirmNoOrStale_ignored() {
        val sim = added().gear(Gear.P, MIN).ev(FeedingConfirmed(T0, true))   // 버튼을 띄우기 전
        assertNotNull(sim.ses.nursingStop); assertTrue(sim.cmds.isEmpty())
        sim.tick(15 * MIN).ev(FeedingConfirmed(sim.t, false))
        assertNull(sim.ses.nursingStop); assertNull(sim.s.feedingStart); assertTrue(sim.cmds.isEmpty())
        val none = session().ev(FeedingConfirmed(T0, true))
        assertTrue(none.cmds.isEmpty())
    }

    @Test fun s1_speechGap8s() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 5.0)).attempt1()
        sim.done()
        val doneAt = sim.t
        assertEquals(doneAt + 8_000, sim.last.wakeAt)
        sim.ev(Tick(doneAt + 7_999))
        assertTrue(sim.step<Speak>().isEmpty())
        sim.ev(Tick(doneAt + 8_000))
        assertEquals(Sid.S06, sim.step<Speak>().single().sid)
    }

    @Test fun s2_attemptAndS18_alwaysSpoken() {
        val sim = session().attempt1().toConfirm().closeQuiet().flush().attempt1().flush()
        assertEquals(2, sim.all<Speak>().count { it.sid == Sid.S02 })
        val h = session().handoff().flush()
        h.ev(SwitchSound(h.t, LL)).flush().ev(SwitchSound(h.t + MIN, LL)).flush()
        assertEquals(2, h.all<Speak>().count { it.sid == Sid.S18 })
    }

    @Test fun s3_offerNotRepeatedIn10min() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 5.0))
        sim.attempt1().toConfirm().closeQuiet().flush().attempt1().flush()
        assertEquals(2, sim.all<Speak>().count { it.sid == Sid.S02 })
        assertEquals(1, sim.sids().count { it == Sid.S08 })
    }

    @Test fun q1_afterSpeech_listens5s() {
        val sim = session().attempt1()
        sim.ev(SpeechDone(sim.t + 4_000, sim.s.speech.speaking!!, true))
        val l = sim.step<Listen>().single()
        assertEquals(5_000, l.ms)
        assertEquals(QaWindow(l.listenId, QaStep.HEARING, sim.t + 6_000, false), sim.qa)
        val ask = session().route(rest("a", 5.0)).handoff().flush()   // Ask 뒤에는 열지 않는다
        assertTrue(ask.all<Listen>().size == 1)                       // S02 뒤 한 번만
    }

    @Test fun q2_parkButton_listens() {
        assertTrue(session().ev(AskButton(T0)).cmds.isEmpty())        // D에서는 안 연다
        val p = idle().ev(AskButton(T0))                               // 세션 밖이어도 된다
        assertEquals(5_000, p.step<Listen>().single().ms)
        assertTrue(idle().ev(CallMode(T0, true)).ev(AskButton(T0)).cmds.isEmpty())
        assertTrue(idle(mic = false).ev(AskButton(T0)).cmds.isEmpty())
    }

    @Test fun q3_silenceOrTimeout_resumesQueue() {
        // 대기 문장(S06·S08)이 있는 채로 S02 뒤 듣기 창이 열린다
        fun opened(): Pair<Sim, Long> {
            val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 5.0)).attempt1()
            sim.ev(SpeechDone(sim.t + 3_000, sim.s.speech.speaking!!, true))
            assertEquals(QaStep.HEARING, sim.qa.step)
            return sim to sim.t
        }
        val (heard, d) = opened()
        heard.ev(Heard(d + 2_000, heard.qa.listenId, null))          // 들은 말 없음
        assertNull(heard.s.speech.qa); assertTrue(heard.cmds.isEmpty())
        assertEquals(d + 8_000, heard.last.wakeAt)                     // 8초 규칙대로 이어 간다
        heard.ev(Tick(d + 7_999)); assertTrue(heard.cmds.isEmpty())
        heard.ev(Tick(d + 8_000))
        assertEquals(listOf(Sid.S06), heard.cmds.map { (it as Speak).sid })
        val (timeout, d2) = opened()
        timeout.ev(Tick(d2 + 6_000))                                   // until = 끝 + 5초 + 1초
        assertNull(timeout.s.speech.qa); assertTrue(timeout.cmds.isEmpty())
        timeout.ev(Tick(d2 + 8_000))
        assertEquals(listOf(Sid.S06), timeout.cmds.map { (it as Speak).sid })
        val (think, d3) = opened()                                     // 답 기다림이 8초를 넘으면 그 끝에 재개
        think.ev(Heard(d3 + 5_000, think.qa.listenId, "왜 울어?"))
        think.ev(Tick(d3 + 8_000)); assertTrue(think.step<Speak>().isEmpty())
        think.ev(Tick(d3 + 9_000))
        assertNull(think.s.speech.qa)
        assertEquals(listOf(Sid.S06), think.cmds.map { (it as Speak).sid })
    }

    @Test fun q4_voiceStop_noLlm() {
        val sim = session().attempt1().done(1_000, spoken = true)
        val s2 = session().attempt1()
        s2.ev(SpeechDone(s2.t, s2.s.speech.speaking!!, true)).ev(Heard(s2.t + 2_000, s2.qa.listenId, "그만 해!"))
        assertEquals(Phase.WAIT, s2.ses.phase)
        assertEquals(Unjudged.DRIVER_STOP, s2.ep.first!!.unjudged)
        assertTrue(s2.all<Qa>().isEmpty()); assertNull(s2.s.speech.qa)
        assertEquals(Phase.ATTEMPT, sim.ses.phase)
    }

    @Test fun q5_question_callsQa() {
        val sim = idle().asking("언제 그만 울까?")
        assertEquals(Qa(sim.qa.listenId, "언제 그만 울까?"), sim.step<Qa>().single())
        assertEquals(QaStep.THINKING, sim.qa.step); assertEquals(sim.t + 4_000, sim.qa.until)
    }

    @Test fun q5a_toolDuringQa_marksRuleSaid() {
        val h = session().handoff().flush().gear(Gear.P, MIN).asking()
        h.ev(SwitchSound(h.t, LL, h.qa.listenId))
        assertTrue(h.qa.ruleSaid)
        assertEquals(Line(Sid.S18, emptyMap(), null, qaAnswer = true), h.s.speech.queue.first())
        val i = idle().asking()
        i.ev(SwitchSound(i.t, LL, i.qa.listenId))
        assertEquals(Sid.S20, i.s.speech.queue.single().sid); assertTrue(i.qa.ruleSaid)
        val w = session().attempt1().ev(DriverStop(T0, Via.VOICE)).gear(Gear.P, MIN).flush().asking()
        w.ev(SwitchSound(w.t, LL, w.qa.listenId))
        w.ev(PlaybackRejected(w.t, w.play().id, RejectReason.FOCUS_DENIED))
        assertTrue(w.s.speech.queue.first().let { it.sid == Sid.S18 && it.qaAnswer }); assertTrue(w.qa.ruleSaid)
    }

    @Test fun q5b_qaWaypoint_adds() {
        val sim = session().route(nurse).attempt1()
        sim.ev(SpeechDone(sim.t, sim.s.speech.speaking!!, true)).asking("수유실 경유지 넣어 줘")
        sim.ev(DriverAddWaypoint(sim.t, "n", sim.qa.listenId))
        val add = sim.step<AddWaypoint>().single()
        assertEquals(WaypointPending(add.cmdId, "n", sim.ses.id, sim.ep.id, fromQa = true, restLogIndex = 0), sim.ses.waypointPending)
        assertTrue(sim.qa.ruleSaid)
        assertEquals(NursingStop("n", null, null, false), sim.ses.nursingStop)
        sim.ev(WaypointResult(sim.t, add.cmdId, "n", true))
        assertEquals(Line(Sid.S09, mapOf("restName" to "n 휴게소"), null, qaAnswer = true), sim.s.speech.queue.first())
    }

    @Test fun q5c_qaWaypointRejected_saysS19() {
        val sim = session().route(nurse).gear(Gear.P, MIN).flush().asking()
        sim.ev(DriverAddWaypoint(sim.t, "n", sim.qa.listenId))       // P에서는 넣지 않는다
        assertTrue(sim.step<AddWaypoint>().isEmpty())
        assertTrue(sim.qa.ruleSaid)
        assertEquals(Line(Sid.S19, emptyMap(), null, qaAnswer = true), sim.s.speech.queue.single())
    }

    @Test fun q6_reply_spokenFirst() {
        // 대기 문장(S06·S08)이 있는데 ④ 답이 온다 → 답이 먼저, 기존 문장은 답이 끝난 뒤 8초
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 5.0)).attempt1()
        sim.ev(SpeechDone(sim.t + 3_000, sim.s.speech.speaking!!, true))
        sim.ev(Heard(sim.t + 1_000, sim.qa.listenId, "왜 울어?"))
        sim.ev(QaReply(sim.t + 1_000, sim.qa.listenId, "추정이에요."))
        assertNull(sim.s.speech.qa)
        assertEquals(listOf<Command>(Speak(sim.s.speech.speaking!!, Sid.QA, mapOf("text" to "추정이에요."))), sim.cmds) // 8초를 기다리지 않는다
        sim.ev(SpeechDone(sim.t + 2_000, sim.s.speech.speaking!!, true))
        assertTrue(sim.step<Listen>().isEmpty())                    // 답 뒤에는 듣지 않는다
        val qaDone = sim.t
        sim.ev(Tick(qaDone + 7_999)); assertTrue(sim.cmds.isEmpty())
        sim.ev(Tick(qaDone + 8_000))
        assertEquals(listOf(Sid.S06), sim.cmds.map { (it as Speak).sid })
        assertEquals(listOf(Sid.S02, Sid.QA, Sid.S06), sim.all<Speak>().map { it.sid })
        val r = session().handoff().flush().gear(Gear.P, MIN).asking()
        r.ev(SwitchSound(r.t, LL, r.qa.listenId)).ev(QaReply(r.t, r.qa.listenId, "LLM 글"))
        assertTrue(r.s.speech.queue.none { it.sid == Sid.QA })        // ruleSaid면 버린다
    }

    @Test fun q7_staleReplyOrTool_dropped() {
        val sim = session().attempt1()
        sim.ev(QaReply(sim.t, "x-1", "늦은 답")).ev(Heard(sim.t, "x-1", "왜"))
        sim.ev(SwitchSound(sim.t, LL, "x-1"))
        assertTrue(sim.cmds.isEmpty()); assertEquals(Phase.ATTEMPT, sim.ses.phase)
        val h = idle().ev(AskButton(T0))                               // HEARING인데 도구가 옴
        h.ev(DriverStop(T0, Via.TOOL, h.qa.listenId))
        assertTrue(h.cmds.isEmpty()); assertEquals(QaStep.HEARING, h.qa.step)
    }

    @Test fun q8_callOrSuspend_dropsQaAndAsk() {
        val sim = session().route(rest("a", 5.0)).handoff().flush()
        val ask = sim.all<Ask>().single()
        sim.ev(Answer(sim.t, ask.askId, YesNo.NONE)).gear(Gear.P, MIN).flush().asking()
        val lid = sim.qa.listenId
        sim.s = sim.s.copy(session = sim.ses.copy(pendingAsk = PendingAsk(ask.askId, Sid.S08, "a", null, false)))
        sim.ev(CallMode(sim.t, true))
        assertNull(sim.s.speech.qa); assertNull(sim.ses.pendingAsk)
        sim.ev(CallMode(sim.t, false)).ev(QaReply(sim.t, lid, "답")).ev(SwitchSound(sim.t, LL, lid)).ev(Answer(sim.t, ask.askId, YesNo.YES))
        assertTrue(sim.cmds.isEmpty())
        val sus = idle().asking().ev(Suspend(T0 + 1_000))
        assertNull(sus.s.speech.qa)
    }

    @Test fun e1_limitsResetOnlyOnListening() {
        val w = session().attempt2().ev(DriverStop(T0 + 2 * MIN, Via.VOICE))
        assertEquals(2, w.ses.attemptNo); assertNotNull(w.ses.episode)      // WAIT에서는 그대로
        val sim = session().attempt1().toConfirm().closeQuiet()
        assertEquals(0, sim.ses.attemptNo); assertNull(sim.ses.episode)
        assertEquals(56_000, sim.ses.playedMs)                              // 세션 누적은 그대로
        w.tick(5 * MIN)
        assertEquals(0, w.ses.attemptNo); assertTrue(w.ses.playedMs > 0)
    }

    @Test fun e2_cryAfterSuccess_newEpisode() {
        val sim = session().attempt1()
        val first = sim.ep.id
        sim.toConfirm().closeQuiet().attempt1()
        assertEquals(1, sim.ses.attemptNo)
        assertTrue(sim.ep.id != first)
    }

    @Test fun p1_priorityOrder() {
        // 2차 GAP에 든 때는 120분 전, 그 GAP을 깨는 울음 구간이 120분 뒤에 온다
        val sim = session().route(rest("a", 12.0)).ev(Tick(T0 + 118 * MIN + 3_000)).attempt2().toGap()
        assertTrue(sim.t < T0 + 120 * MIN); assertFalse(sim.ses.longDriveOffered)
        sim.recry()                                                          // 같은 Step에 인계와 장시간 시점
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertFalse(sim.ses.longDriveOffered)
        assertEquals(listOf(Sid.S08), sim.s.speech.queue.drop(1).filter { it.askRestId != null }.map { it.sid })
        sim.flush().tick(1_000)
        assertTrue(sim.ses.longDriveOffered)                                 // 다음 Step에 장시간 제안
        // 수유 > 장시간: 120분 경계의 경보 → 시작 결과(S02·S06·S08)가 장시간 제안(S11)보다 앞선다
        val f = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 12.0)).alarm(120 * MIN)
        assertTrue(f.step<Ask>().isEmpty()); assertFalse(f.ses.longDriveOffered)
        f.started().tick(1_000)
        assertEquals(listOf(Sid.S02, Sid.S06, Sid.S08, Sid.S11), f.sids())
    }

    @Test fun v1_steerReversal_definedSignal() {
        // 사인파 ±10°, 주기 10초, 10 Hz 표본, 60초. 극값 12개(2.5초, 7.5초, …, 57.5초) → 반전 기대 12.
        var st = SteerState(null, null, null, 0, null)
        var closed: SteerWindowRecord? = null
        for (i in 0..600) {
            val at = i * 100L
            val o = steer(st, VehicleSample(at, 50.0, false, 10 * sin(2 * PI * at / 10_000.0), TurnSignal.NONE), false, false, "s")
            st = o.state; closed = closed ?: o.closed
        }
        val rec = closed!!
        val err = rec.reversals - 12
        println("v1 조향 반전: 기대 12, 계산 ${rec.reversals}, 오차 $err")
        assertTrue(abs(err) <= 1)
        assertEquals(2, rec.speedBand)
        // 계단(0° → 20°)은 반전 0, 방향지시등이 켜진 창은 뺀다
        var s2 = SteerState(null, null, null, 0, null)
        var c2: SteerWindowRecord? = null
        for (i in 0..600) {
            val o = steer(s2, VehicleSample(i * 100L, 30.0, false, if (i < 300) 0.0 else 20.0, if (i == 10) TurnSignal.LEFT else TurnSignal.NONE), false, false, "s")
            s2 = o.state; c2 = c2 ?: o.closed
        }
        assertEquals(0, c2!!.reversals); assertTrue(c2.excluded)
    }

    @Test fun v2_steerWindowClosed_savesAndContinues() {
        idle().ev(VehicleSample(T0, 40.0, false, 1.0, TurnSignal.NONE)).let { assertNull(it.s.steer.window) }  // 세션 밖은 누적하지 않는다
        val sim = session().attempt1()
        val t0 = sim.t
        sim.ev(VehicleSample(t0, 40.0, false, 1.0, TurnSignal.NONE)).alarm(30_000)
        sim.ev(VehicleSample(t0 + 30_000, 40.0, false, 5.0, TurnSignal.NONE))
        val filtered = sim.s.steer.filtered
        sim.ev(VehicleSample(t0 + 60_000, 40.0, false, 5.0, TurnSignal.NONE))
        val rec = sim.step<SaveSteerWindow>().single().record
        assertEquals(SteerWindowRecord(sim.ses.id, t0, 2, false, true, true, 0), rec)
        assertEquals(t0 + 60_000, sim.s.steer.window!!.startAt)
        assertTrue(sim.s.steer.filtered!! > filtered!!)                      // 필터는 이어진다
    }

    @Test fun s1_staleAttemptNoticeDroppedForNewPlay() {
        val sim = session().tick(6 * MIN).gear(Gear.P).done().gear(Gear.D)   // S12 끝 → 8초 안
        sim.attempt1()                                                     // 백색소음 1차, S02는 간격을 기다림
        assertTrue(sim.step<Speak>().isEmpty())
        sim.ev(SwitchSound(sim.t + 1_000, LL)).started()                   // 자장가로 바꿈
        sim.tick(8_000)
        assertTrue(sim.all<Speak>().none { it.sid == Sid.S02 })
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S02 })
    }

    @Test fun l1_longDriveNursingRest_leadsToFeedingConfirm() {
        val sim = session().route(nurse).ev(Tick(T0 + 120 * MIN))
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S11, ask.sid)
        assertTrue(sim.ses.pendingAsk!!.hasNursingRoom)            // 문장이 아니라 고른 쉼터의 속성
        sim.ev(Answer(sim.t, ask.askId, YesNo.YES))
        sim.ev(WaypointResult(sim.t, sim.step<AddWaypoint>().single().cmdId, "n", true))
        sim.gear(Gear.P, MIN).tick(15 * MIN)
        assertEquals(Notice.FEEDING_CONFIRM, sim.step<ShowNotice>().single().kind)
    }

    @Test fun q5d_songPlaying_onlyStopWords() {
        /** 1차 음원이 m인 시도 중, S02가 끝나 듣기 창이 열린 상태. */
        fun listening(m: Method): Sim {
            val h = listOf(WN, LL).filter { it != m }.flatMap { o -> List(2) { FirstTry(T0, o, false) } }
            val sim = session(methods = listOf(WN, LL, SONG), history = h).attempt1()
            assertEquals(m, sim.play().method)
            sim.ev(SpeechDone(sim.t + 3_000, sim.s.speech.speaking!!, true))
            assertEquals(QaStep.HEARING, sim.qa.step)
            return sim
        }
        for (m in listOf(LL, SONG)) {                                 // 노래 중 질문은 버린다
            val sim = listening(m)
            sim.ev(Heard(sim.t + 1_000, sim.qa.listenId, "왜 울어?"))
            assertNull(sim.s.speech.qa); assertTrue(sim.cmds.isEmpty())
            assertEquals(Phase.ATTEMPT, sim.ses.phase)
        }
        val stop = listening(LL)                                       // 같은 상황의 '그만'은 멈춘다
        stop.ev(Heard(stop.t + 1_000, stop.qa.listenId, "그만"))
        assertEquals(Phase.WAIT, stop.ses.phase); assertEquals(Unjudged.DRIVER_STOP, stop.ep.first!!.unjudged)
        assertTrue(stop.all<Qa>().isEmpty())
        val wn = listening(WN)                                         // 백색소음 중 질문은 Qa로
        wn.ev(Heard(wn.t + 1_000, wn.qa.listenId, "왜 울어?"))
        assertEquals(Qa(wn.qa.listenId, "왜 울어?"), wn.step<Qa>().single())
        assertEquals(QaStep.THINKING, wn.qa.step)
    }

    @Test fun q5d_songEndedBeforeHeard_stillDropped() {
        for (word in listOf("왜 울어?", "그만")) {
            val h = listOf(WN).flatMap { o -> List(2) { FirstTry(T0, o, false) } }
            val sim = session(methods = listOf(WN, LL), history = h).attempt1()
            assertEquals(LL, sim.play().method)
            sim.ev(SpeechDone(sim.t + 3_000, sim.s.speech.speaking!!, true))
            val id = sim.qa.listenId; assertTrue(sim.qa.songHeard)
            sim.ev(PlaybackEnded(sim.t + 500, sim.play().id, EndReason.DEADLINE))   // 재생이 먼저 끝난다
            sim.ev(Heard(sim.t + 500, id, word))
            assertNull(sim.s.speech.qa); assertTrue(sim.all<Qa>().isEmpty())
        }
    }

    @Test fun q5d_songStartedWhileHearing_dropped() {
        val sim = session(methods = listOf(WN, LL), history = emptyList()).attempt1()
        sim.ev(SpeechDone(sim.t + 3_000, sim.s.speech.speaking!!, true))
        val id = sim.qa.listenId
        assertEquals(WN, sim.play().method)
        assertFalse(sim.qa.songHeard)
        sim.ev(SwitchSound(sim.t, LL)).started()
        assertTrue(sim.qa.songHeard)
        sim.ev(PlaybackEnded(sim.t + 500, sim.play().id, EndReason.DEADLINE))
        assertNotNull(sim.s.speech.qa); assertNull(sim.ses.play)
        sim.ev(Heard(sim.t + 100, id, "왜 울어?"))
        assertNull(sim.s.speech.qa); assertTrue(sim.all<Qa>().isEmpty())
    }

    @Test fun v1_smallSineFromZero_countsReversals() {
        // 0°에서 시작하는 ±2° 사인파(주기 10초, 10 Hz, 60초). 필터 뒤 극값 차이 ≈ 3.9° ≥ 3°라 반이 돌 때마다 반전이다.
        // 극값 12개(2.5초, …, 57.5초) 중 마지막은 3° 되돌아오기 전에 창이 닫혀 기대 11.
        var st = SteerState(null, null, null, 0, null)
        var closed: SteerWindowRecord? = null
        for (i in 0..600) {
            val at = i * 100L
            val o = steer(st, VehicleSample(at, 50.0, false, 2 * sin(2 * PI * at / 10_000.0), TurnSignal.NONE), false, false, "s")
            st = o.state; closed = closed ?: o.closed
        }
        val n = closed!!.reversals
        println("작은 사인파 조향 반전: 기대 11, 계산 $n, 오차 ${n - 11}")
        assertTrue(abs(n - 11) <= 1, "반전 $n")
        assertTrue(st.dir != 0)
        // 잡음 수준(±0.2°)만 있으면 방향도 반전도 생기지 않는다
        var q = SteerState(null, null, null, 0, null)
        var qc: SteerWindowRecord? = null
        for (i in 0..600) {
            val o = steer(q, VehicleSample(i * 100L, 50.0, false, if (i % 2 == 0) 0.2 else -0.2, TurnSignal.NONE), false, false, "s")
            q = o.state; qc = qc ?: o.closed
        }
        assertEquals(0, qc!!.reversals); assertEquals(0, q.dir)
    }

}
