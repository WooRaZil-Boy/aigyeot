package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 5.5 → WAIT, 통화, 포커스, Suspend(3-14·3-15·3-16). 복원 행(14q~14x)은 호스트가 Room으로 맞춘 상태를 흉내 낸다.
class S55WaitTest {
    private fun Sim.waitedFrom(e: Event, r: Unjudged, end: PlayEndReason) {
        val id = play().id
        ev(e)
        assertEquals(Phase.WAIT, ses.phase)
        assertEquals(0, ses.quietMs)
        assertEquals(r, ep.first!!.unjudged)
        assertEquals(StopPlayback(id, false), step<StopPlayback>().single())
        assertEquals(end, step<SavePlay>().single().record.endReason)
    }
    private val audio = setOf(Speak::class, Ask::class, StartPlayback::class)
    private fun Sim.noAudio() = assertTrue(cmds.none { it::class in audio }, "$cmds")

    @Test fun r14a_driverStop_wait() = session().attempt1().waitedFrom(DriverStop(T0 + 10_000, Via.VOICE), Unjudged.DRIVER_STOP, PlayEndReason.DRIVER_STOP)

    @Test fun r14b_gearPark_wait() {
        val sim = session().tick(6 * MIN).attempt1()
        sim.waitedFrom(GearChanged(sim.t + 1_000, Gear.P), Unjudged.GEAR_P, PlayEndReason.GEAR_P)
        assertTrue(Sid.S12 in sim.sids())
    }

    @Test fun r14c_focusLoss_wait() {
        val sim = session().attempt1()
        sim.waitedFrom(FocusChanged(sim.t + 1_000, sim.play().id, FocusChange.LOSS), Unjudged.FOCUS_LOSS, PlayEndReason.FOCUS_LOSS)
        // GAP·CONFIRM: 재생은 없지만 앞 재생(이전 playId)의 영구 상실도 WAIT로 보낸다
        for (phase in listOf(Phase.GAP, Phase.CONFIRM)) {
            val g = session().attempt1()
            val old = g.play().id
            if (phase == Phase.GAP) g.toGap() else g.toConfirm()
            assertEquals(phase, g.ses.phase)
            g.ev(FocusChanged(g.t + 1_000, old, FocusChange.LOSS))
            assertEquals(Phase.WAIT, g.ses.phase)
            assertEquals(Unjudged.FOCUS_LOSS, g.ep.first!!.unjudged)
            g.recry()                                           // 그 뒤 울음은 2차 자동 재생을 부르지 않는다
            assertTrue(g.step<StartPlayback>().isEmpty())
            assertEquals(AlarmOnly.WAITING, g.alarm().step<LogAlarm>().single().reason)
        }
    }

    @Test fun r14d_switchSound_waitAndRequestPlay() {
        val sim = session().attempt1()
        sim.waitedFrom(SwitchSound(sim.t + 1_000, LL), Unjudged.SWITCH_SOUND, PlayEndReason.SWITCH_SOUND)
        val start = sim.step<StartPlayback>().single()
        assertEquals(PlayKind.REQUEST, start.kind); assertEquals(LL, start.method)
        assertTrue(sim.cmds.indexOfFirst { it is StopPlayback } < sim.cmds.indexOf(start))
    }

    @Test fun r14e_suspend_waitCancelSpeechReleaseSave() {
        val sim = session().attempt1()
        sim.waitedFrom(Suspend(sim.t + 1_000), Unjudged.SUSPEND, PlayEndReason.SUSPEND)
        val names = sim.cmds.map { it::class.simpleName }
        val i = listOf("StopPlayback", "CancelSpeech", "ReleaseAudio", "SaveSnapshot").map { names.indexOf(it) }
        assertEquals(i.sorted(), i); assertEquals(names.lastIndex, i.last())
        assertEquals(sim.t, (sim.cmds.last() as SaveSnapshot).state.ign.suspendedAt)
        assertTrue(sim.s.speech.queue.isEmpty()); assertNull(sim.s.speech.speaking)
    }

    @Test fun r14f_suspendWithoutPlay_cancelsSpeechReleasesSaves() {
        for (sim in listOf(idle(), session(), session().handoff())) {
            val phase = sim.s.session?.phase
            sim.ev(Suspend(sim.t + 1_000))
            assertEquals(listOf("CancelSpeech", "ReleaseAudio", "SaveSnapshot"), sim.cmds.map { it::class.simpleName })
            assertEquals(phase, sim.s.session?.phase)
            assertEquals(sim.t, sim.s.ign.suspendedAt)
        }
    }

    @Test fun r14g_resume_noAutoReplay() {
        val sim = session().attempt1().ev(Suspend(T0 + 5_000)).ev(Resume(T0 + 10 * MIN))
        assertNull(sim.s.ign.suspendedAt)
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertTrue(sim.cmds.isEmpty())
        sim.alarm(1_000)
        assertTrue(sim.step<StartPlayback>().isEmpty())   // WAIT는 자동 재생 없음
        // 장시간 제안 시점을 넘겨 Resume해도 그 Step에는 말하지 않는다. 다음 Tick에 새로 요청한다.
        val long = session().route(rest("a", 10.0)).ev(Suspend(T0 + MIN)).ev(Resume(T0 + 121 * MIN))
        assertTrue(long.cmds.isEmpty())
        long.tick(1_000)
        assertEquals(Sid.S11, long.step<Ask>().single().sid)
    }

    @Test fun r14p_resumeMicOff_nextParkShowsNotice() {
        val sim = session().ev(Suspend(T0 + MIN)).ev(MicService(T0 + MIN, false, null)).ev(Resume(T0 + 20 * MIN))
        assertTrue(sim.ses.micNoticeArmed); assertTrue(!sim.ses.s17Said)
        sim.gear(Gear.P, MIN)
        assertEquals(Notice.BABY_ABOARD, sim.step<ShowNotice>().single().kind)
        assertTrue(sim.step<Speak>().none { it.sid == Sid.S16 || it.sid == Sid.S17 })
    }

    // 엔진 쪽 동작만 본다, Room 대조는 호스트 시험
    @Test fun r14q_restoreWithStaleSnapshot_roomCapAndEndWin() {
        val sim = session()
        sim.s = sim.s.copy(session = sim.ses.copy(playedMs = P.sessionPlayCapMs, capReached = true))   // ② Room 누적이 이김
        sim.ev(Boot(sim.t + MIN, Gear.D, 10)).alarm()
        assertTrue(sim.step<StartPlayback>().isEmpty())
        assertEquals(AlarmOnly.SESSION_CAP, sim.step<LogAlarm>().single().reason)
        val ended = session()
        ended.s = ended.s.copy(session = null)                                                          // ②(a) Room에서 끝난 세션
        ended.ev(Boot(ended.t + MIN, Gear.D, 10))
        assertTrue(ended.cmds.isEmpty())
    }

    @Test fun r14r_restoreWhilePlaying_treatedAsPlaybackError() {
        val sim = session().attempt1()
        val id = sim.play().id
        sim.ev(PlaybackEnded(sim.t + 20_000, id, EndReason.ERROR))
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertEquals(20_000, sim.ses.playedMs)
        val rec = sim.step<SavePlay>().single().record
        assertEquals(PlayEndReason.ERROR, rec.endReason); assertEquals(sim.t, rec.endedAt)
        // endAt 뒤 복원: 56초로 자르지 않고 복원 시각까지 센다(2.4 '상한 쪽으로 보수적')
        val late = session().attempt1()
        late.ev(PlaybackEnded(late.t + 70_000, late.play().id, EndReason.ERROR))
        assertEquals(Phase.WAIT, late.ses.phase)
        assertEquals(70_000, late.ses.playedMs); assertEquals(70_000, late.ep.playedMs)
        assertEquals(70_000, late.step<SavePlay>().single().record.playedMs)
    }

    @Test fun r14s_restoreWithPlayAlreadyLogged_noDoubleCount() {
        val sim = session().attempt1()
        val id = sim.play().id
        // ②(b) Room 누적 56초를 세션·에피소드 모두에 옮긴 스냅숏
        sim.s = sim.s.copy(session = sim.ses.copy(playedMs = 56_000, episode = sim.ep.copy(playedMs = 56_000)))
        sim.ev(PlaybackEnded(sim.t + 70_000, id, EndReason.ERROR, recorded = true))
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertEquals(56_000, sim.ses.playedMs)
        assertEquals(56_000, sim.ep.playedMs)
        assertTrue(sim.step<SavePlay>().isEmpty())
    }

    @Test fun r14t_restoreInGapOrConfirm_pausesAndRestartsWindow() {
        val sim = session().attempt1().toGap()
        val oldEnd = sim.ses.windowEnd!!
        sim.ev(InputLost(oldEnd + MIN, PauseReason.MIC_ERROR))
        assertEquals(Phase.GAP, sim.ses.phase)
        assertNull(sim.ses.judgeFrom); assertNull(sim.ses.windowEnd)
        assertEquals(Unjudged.MIC_ERROR, sim.ep.first!!.unjudged)
        sim.seg(oldEnd, 0.1)                                    // 멈춘 동안 들어온 구간은 버린다
        assertEquals(Phase.GAP, sim.ses.phase)
        sim.ev(MicService(sim.t, true, MicPath.APP_LAUNCH)).ev(InputRestored(sim.t + 1_000, PauseReason.MIC_ERROR))
        assertEquals(sim.t, sim.ses.judgeFrom)
        sim.seg(sim.t, 0.1)                                     // 새 창 안의 첫 구간만으로는 넘어가지 않는다
        assertEquals(Phase.GAP, sim.ses.phase)
    }

    // 엔진 쪽 동작만 본다, Room 대조는 호스트 시험
    @Test fun r14u_restoreAfterEndedAtBeforeReport_rebuildsReportOnly() {
        // CloseSession 재발행은 호스트 몫(2.4). 판단 코어는 복원 뒤 Boot에서 기록을 다시 내지 않는다.
        val sim = idle()
        sim.ev(Boot(sim.t + MIN, Gear.P, 10))
        assertTrue(sim.cmds.none { it is SaveSession || it is SaveEpisode || it is SavePlay || it is SendToPhone || it is CloseSession })
    }

    @Test fun r14v_suspended_noNewAudioCommandsUntilResume() {
        val sim = session().route(rest("a", 5.0)).ev(Suspend(T0 + MIN)).clear()
        sim.ev(SwitchSound(sim.t, LL)); sim.noAudio()
        sim.ev(Tick(T0 + 3 * 60 * MIN)); sim.noAudio()        // 장시간 제안 시점
        sim.alarm(); sim.noAudio()
        sim.ev(RearDoor(sim.t, Side.LEFT, false)); sim.noAudio()
        assertTrue(sim.s.speech.queue.isEmpty())
        assertTrue(sim.all<Speak>().isEmpty() && sim.all<StartPlayback>().isEmpty())
        sim.ev(Resume(sim.t + 1_000)).ev(SwitchSound(sim.t + 2_000, LL))
        assertEquals(1, sim.step<StartPlayback>().size)
    }

    // 엔진 쪽 동작만 본다, Room 대조는 호스트 시험
    @Test fun r14w_restoreKeepsRoomFirstResult() {
        val sim = session().attempt2()
        assertEquals(TryResult.FAIL, sim.ep.first!!.result)
        sim.ev(PlaybackEnded(sim.t + 5_000, sim.play().id, EndReason.ERROR))   // C58
        assertEquals(TryResult.FAIL, sim.ep.first!!.result)
        assertEquals(listOf(Unjudged.FOCUS_LOSS), sim.ep.laterReasons)
        val g = session().attempt2().toGap().ev(InputLost(T0 + 10 * MIN, PauseReason.MIC_ERROR))  // C59
        assertEquals(TryResult.FAIL, g.ep.first!!.result)
    }

    // 엔진 쪽 동작만 본다, Room 대조는 호스트 시험
    @Test fun r14x_restoreEndedEpisode_toListening() {
        val sim = session().attempt1()
        val old = sim.ep.id
        // 호스트가 Room의 에피소드 끝을 보고 LISTENING으로 복원했다
        sim.s = sim.s.copy(session = sim.ses.copy(phase = Phase.LISTENING, attemptNo = 0, play = null, episode = null))
        sim.ev(Boot(sim.t + MIN, Gear.D, 10)).attempt1()
        assertEquals(1, sim.ses.attemptNo)
        assertNotEquals(old, sim.ep.id)
    }

    @Test fun r14h_transientLoss_pausesKeepsClock() {
        val sim = session().attempt1()
        sim.ev(FocusChanged(sim.t + 5_000, sim.play().id, FocusChange.LOSS_TRANSIENT))
        assertEquals(AdjustPlayback(sim.play().id, PlayMode.PAUSE), sim.step<AdjustPlayback>().single())
        assertEquals(Phase.ATTEMPT, sim.ses.phase)
        sim.toGap()
        assertEquals(56_000, sim.ses.playedMs)                  // 멈춘 동안도 센다(G6)
    }

    @Test fun r14i_canDuck_ducks() {
        val sim = session().attempt1()
        sim.ev(FocusChanged(sim.t, sim.play().id, FocusChange.LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(PlayMode.DUCK, sim.step<AdjustPlayback>().single().mode)
        assertEquals(PlayMode.DUCK, sim.play().mode)
    }

    @Test fun r14j_gain_resumesWithinRemaining() {
        val sim = session().attempt1()
        val end = sim.play().endAt
        sim.ev(FocusChanged(sim.t, sim.play().id, FocusChange.LOSS_TRANSIENT))
        sim.ev(FocusChanged(sim.t + 10_000, sim.play().id, FocusChange.GAIN))
        assertEquals(PlayMode.NORMAL, sim.step<AdjustPlayback>().single().mode)
        assertEquals(end, sim.play().endAt)                     // 끝 시각은 그대로
    }

    @Test fun r14k_decidedFirstNotOverwritten() {
        val sim = session().attempt2()
        sim.ev(DriverStop(sim.t + 1_000, Via.TOOL))
        assertEquals(TryResult.FAIL, sim.ep.first!!.result); assertNull(sim.ep.first!!.unjudged)
        assertEquals(listOf(Unjudged.DRIVER_STOP), sim.ep.laterReasons)
        val g = session().attempt2().toGap().gear(Gear.P, 1_000)
        assertEquals(Phase.WAIT, g.ses.phase)
        assertEquals(listOf(Unjudged.GEAR_P), g.ep.laterReasons)
    }

    @Test fun r14l_suspend_freezesQuietTimer() {
        val sim = session().handoff().tick(MIN).ev(Suspend(T0 + 5 * MIN))
        val q = sim.ses.quietMs
        repeat(5) { sim.tick(5 * MIN) }
        assertEquals(q, sim.ses.quietMs); assertEquals(Phase.HANDOFF, sim.ses.phase)
    }

    @Test fun r14m_resume_doesNotCountSuspendedTime() {
        val sim = session().attempt1().ev(DriverStop(T0 + 1_000, Via.VOICE)).tick(4 * MIN)
        sim.ev(Suspend(sim.t)).ev(Resume(sim.t + 10 * MIN))
        assertEquals(4 * MIN, sim.ses.quietMs)
        sim.tick(59_000)
        assertEquals(Phase.WAIT, sim.ses.phase)
        sim.tick(1_000)
        assertEquals(Phase.LISTENING, sim.ses.phase)
    }

    @Test fun r14n_everyStopPath_accumulatesOnce() {
        val autoStops: List<(Sim) -> Event> = listOf(
            { DriverStop(it.t, Via.VOICE) }, { GearChanged(it.t, Gear.P) }, { FocusChanged(it.t, it.play().id, FocusChange.LOSS) },
            { SwitchSound(it.t, LL) }, { Suspend(it.t) }, { CallMode(it.t, true) }, { PlaybackEnded(it.t, it.play().id, EndReason.ERROR) },
        )
        for (stop in autoStops) {
            val sim = session().attempt1().tick(10_000)
            val id = sim.play().id
            sim.ev(stop(sim))
            assertEquals(10_000, sim.ses.playedMs)
            assertEquals(1, sim.all<SavePlay>().count { it.record.id == id })
            sim.ev(PlaybackEnded(sim.t + 2_000, id, EndReason.STOPPED))
            assertEquals(10_000, sim.ses.playedMs)
        }
        val requestStops: List<(Sim) -> Event> = listOf(
            { DriverStop(it.t, Via.TOOL) }, { CallMode(it.t, true) }, { FocusChanged(it.t, it.play().id, FocusChange.LOSS) },
            { GearChanged(it.t, Gear.P) }, { Suspend(it.t) }, { SwitchSound(it.t, WN) }, { PlaybackEnded(it.t, it.play().id, EndReason.ERROR) },
        )
        for (stop in requestStops) {
            val sim = session().ev(SwitchSound(T0, LL)).started().tick(7_000)
            val id = sim.play().id
            sim.ev(stop(sim)).ev(PlaybackEnded(sim.t + 1_000, id, EndReason.STOPPED))
            assertEquals(7_000, sim.ses.playedMs)
            assertEquals(7_000, sim.ep.playedMs)
        }
    }

    @Test fun r14o_playbackError_attemptToWait() {
        val sim = session().attempt1()
        sim.ev(PlaybackEnded(sim.t + 3_000, sim.play().id, EndReason.ERROR))
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertEquals(Unjudged.FOCUS_LOSS, sim.ep.first!!.unjudged)
        assertEquals(PlayEndReason.ERROR, sim.step<SavePlay>().single().record.endReason)
        assertTrue(sim.step<Speak>().isEmpty() && sim.step<StopPlayback>().isEmpty())
    }

    @Test fun r15a_callDuringAttempt_wait() {
        val sim = session().attempt1()
        sim.waitedFrom(CallMode(sim.t + 1_000, true), Unjudged.CALL, PlayEndReason.CALL)
        assertTrue(sim.step<CancelSpeech>().isNotEmpty())
        assertTrue(sim.s.speech.queue.isEmpty()); assertNull(sim.s.speech.speaking)
    }

    @Test fun r15b_callStopsRequestPlay() {
        val sim = session().ev(SwitchSound(T0, LL)).started()
        val id = sim.play().id
        sim.ev(CallMode(sim.t + 1_000, true))
        assertEquals(Phase.WAIT, sim.ses.phase); assertNull(sim.ses.play)
        assertEquals(StopPlayback(id, false), sim.step<StopPlayback>().single())
        assertEquals(1, sim.step<CancelSpeech>().size)
    }

    @Test fun r15d_noSpeechInCall() {
        val sim = session().tick(6 * MIN).handoff().ev(CallMode(T0 + 10 * MIN, true)).clear()
        sim.ev(SwitchSound(sim.t, LL))                        // HANDOFF의 S18도 말하지 않는다
        sim.gear(Gear.P, 1_000)                                // S12도
        sim.tick(10_000)
        assertTrue(sim.s.speech.queue.isEmpty())
        assertTrue(sim.all<Speak>().isEmpty() && sim.all<Ask>().isEmpty())
    }

    @Test fun r15e_callEnds_backToNormal() {
        val sim = session().ev(CallMode(T0, true)).ev(CallMode(T0 + MIN, false))
        assertTrue(sim.cmds.isEmpty()); assertTrue(!sim.s.car.inCall)
        sim.alarm()
        assertEquals(1, sim.step<StartPlayback>().size)
    }

    @Test fun restoreGap_notCountedAsQuiet() {
        // 스냅숏: HANDOFF, 조용 4분, Suspend 없음 → 프로세스가 죽고 10분 뒤 복원
        fun snap() = session().handoff().tick(4 * MIN).also { assertEquals(4 * MIN, it.ses.quietMs) }
        val boot = snap()
        boot.ev(Boot(boot.t + 10 * MIN, Gear.D, 10))
        assertEquals(Phase.HANDOFF, boot.ses.phase); assertEquals(4 * MIN, boot.ses.quietMs)
        boot.tick(59_000)
        assertEquals(Phase.HANDOFF, boot.ses.phase)
        boot.tick(1_000)
        assertEquals(Phase.LISTENING, boot.ses.phase)       // 복원 뒤 1분만 더 조용하면 돌아간다
        val lost = snap()                                    // 보정 InputLost를 먼저 넣어도 같다
        lost.ev(InputLost(lost.t + 10 * MIN, PauseReason.MIC_ERROR)).ev(Boot(lost.t + 10 * MIN, Gear.D, 10))
        assertEquals(Phase.HANDOFF, lost.ses.phase); assertEquals(4 * MIN, lost.ses.quietMs)
        assertEquals(2, lost.ses.attemptNo)                  // 에피소드 한도도 그대로
        val res = snap()
        res.ev(Resume(res.t + 10 * MIN))
        assertEquals(4 * MIN, res.ses.quietMs)
        // 2.4 ③ 마이크 상태 맞추기 순서: MicService(false) → InputLost(MIC_ERROR) → Boot
        val mic = snap()
        val at = mic.t + 10 * MIN
        mic.ev(MicService(at, false, null)).ev(InputLost(at, PauseReason.MIC_ERROR)).ev(Boot(at, Gear.D, 10))
        assertEquals(Phase.HANDOFF, mic.ses.phase); assertEquals(4 * MIN, mic.ses.quietMs)
        assertEquals(2, mic.ses.attemptNo); assertNotNull(mic.ses.episode)
        assertTrue(mic.log.none { it is SaveEpisode && it.record.endedAt != null })
    }
}
