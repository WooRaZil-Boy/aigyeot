package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 5.6 운전자 요청 재생과 대기(3-11·3-17~3-20).
class S56RequestTest {
    /** 재생 없는 WAIT(1차를 '그만'으로 멈춤, 에피소드 재생 10초). */
    private fun waiting() = session().attempt1().tick(10_000).ev(DriverStop(T0 + 10_000, Via.VOICE))
    private fun Sim.epPlayed(ms: Long) = apply { s = s.copy(session = ses.copy(episode = ep.copy(playedMs = ms))) }
    private fun Sim.quiet(ms: Long) = apply { s = s.copy(session = ses.copy(stage = ses.stage.withQuiet(ms))) }
    private fun Sim.request(m: Method = LL) = ev(SwitchSound(t, m))
    private fun Sim.end() = ev(Tick(play().endAt))

    @Test fun r17a_switchInListening_newEpisodeWait() {
        val sim = session().request()
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertEquals(EpisodeTrigger.SWITCH_SOUND, sim.ep.trigger)
        assertNull(sim.ep.first); assertNull(sim.ep.alarmAt)
        assertEquals(EpisodeTrigger.SWITCH_SOUND, sim.step<SaveEpisode>().single().record.trigger)
        assertEquals(StartPlayback(sim.play().id, PlayKind.REQUEST, LL, sim.t + 56_000, 2_000), sim.step<StartPlayback>().single())
    }

    @Test fun r17b_switchInIdle_noop() {
        val sim = idle().request()
        assertTrue(sim.cmds.isEmpty()); assertNull(sim.s.session)
    }

    @Test fun r17c_switchInCall_ignored() {
        val sim = session().ev(CallMode(T0, true)).request()
        assertTrue(sim.cmds.isEmpty()); assertEquals(Phase.LISTENING, sim.ses.phase)
    }

    @Test fun r18a_requestPlay56s() {
        val sim = waiting().request()
        assertEquals(sim.t + 56_000, sim.step<StartPlayback>().single().endAt)
        assertEquals(Phase.WAIT, sim.ses.phase)
    }

    @Test fun r18b_requestPlayRemainingOnly() {
        val sim = waiting().epPlayed(82_000).request()
        assertEquals(sim.t + 30_000, sim.step<StartPlayback>().single().endAt)
        val c = waiting()
        c.s = c.s.copy(session = c.ses.copy(playedMs = P.sessionPlayCapMs - 20_000))
        assertEquals(c.t + 20_000, c.request().step<StartPlayback>().single().endAt)
    }

    @Test fun r18c_noBudget_saysS18() {
        val sim = waiting().epPlayed(112_000).request()
        assertTrue(sim.step<StartPlayback>().isEmpty())
        assertTrue(Sid.S18 in sim.sids())
    }

    @Test fun r18d_requestFocusDenied_saysS18() {
        val sim = waiting().request()
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.FOCUS_DENIED))
        assertNull(sim.ses.play); assertEquals(Phase.WAIT, sim.ses.phase)
        assertTrue(Sid.S18 in sim.sids())
    }

    @Test fun r18n_requestStartError_staysWaitNoSpeech() {
        val sim = waiting().request()
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.ERROR))
        assertNull(sim.ses.play); assertEquals(Phase.WAIT, sim.ses.phase)
        assertTrue(Sid.S18 !in sim.sids())
        sim.clear().tick(5 * MIN)
        assertEquals(Phase.LISTENING, sim.ses.phase)                  // 5분 타이머는 계속
        assertTrue(sim.all<StartPlayback>().none { it.kind == PlayKind.AUTO })
    }

    @Test fun r18e_requestStopsInPlace_timerContinues() {
        val stops: List<(Sim) -> Event> = listOf(
            { DriverStop(it.t, Via.VOICE) }, { CallMode(it.t, true) }, { FocusChanged(it.t, it.play().id, FocusChange.LOSS) },
            { GearChanged(it.t, Gear.P) }, { Suspend(it.t) },
        )
        for (stop in stops) {
            val sim = waiting().tick(MIN).request().started().tick(5_000)
            val q = sim.ses.quietMs
            assertEquals(MIN + 5_000, q)
            sim.ev(stop(sim))
            assertEquals(Phase.WAIT, sim.ses.phase); assertNull(sim.ses.play)
            assertEquals(1, sim.step<StopPlayback>().size)
            assertEquals(q, sim.ses.quietMs)                            // 0으로 되돌리지 않는다
        }
        val sus = waiting().request().started().ev(Suspend(T0 + MIN))
        assertEquals(listOf("StopPlayback", "SavePlay", "SaveEpisode", "SaveSession", "CancelSpeech", "ReleaseAudio", "SaveSnapshot"),
            sus.cmds.map { it::class.simpleName })
    }

    @Test fun r18f_capDuringRequest_fadeStayWait() {
        val sim = waiting()
        sim.s = sim.s.copy(session = sim.ses.copy(playedMs = P.sessionPlayCapMs - 20_000))
        sim.request().started().end()
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertTrue(sim.ses.capReached(P))
        assertTrue(sim.step<StopPlayback>().isEmpty() && sim.step<SendToPhone>().isEmpty())
        assertNull(sim.ep.handoff)
    }

    @Test fun r18g_noListeningDuringRequest() {
        val sim = waiting().request().started().quiet(6 * MIN).tick(1_000)
        assertEquals(Phase.WAIT, sim.ses.phase); assertNotNull(sim.ses.play)
    }

    @Test fun r18h_requestEndsAfterFiveQuiet_listening() {
        val sim = waiting().tick(4 * MIN + 10_000).request().started().end()   // 4분 10초 + 56초
        assertEquals(Phase.LISTENING, sim.ses.phase)
        assertNull(sim.ses.episode)
        assertNotNull(sim.step<SaveEpisode>().last().record.endedAt)
        val cap = waiting().tick(4 * MIN + 10_000)
        cap.s = cap.s.copy(session = cap.ses.copy(playedMs = P.sessionPlayCapMs - 56_000))
        cap.request().started().end()
        assertEquals(Phase.LISTENING, cap.ses.phase); assertTrue(cap.ses.capReached(P))
    }

    @Test fun r18i_switchWhilePlaying_restarts() {
        val sim = waiting().request().started().tick(20_000)
        val old = sim.play().id
        sim.request(WN)
        assertEquals(StopPlayback(old, false), sim.step<StopPlayback>().single())
        assertEquals(PlayEndReason.SWITCH_SOUND, sim.step<SavePlay>().single().record.endReason)
        assertEquals(30_000, sim.ep.playedMs)
        assertEquals(sim.t + 56_000, sim.step<StartPlayback>().single().endAt)
        assertTrue(sim.cmds.indexOfFirst { it is SavePlay } < sim.cmds.indexOfFirst { it is StartPlayback })
    }

    @Test fun r18j_requestTransientFocus_sameAsAttempt() {
        val sim = waiting().request().started()
        val id = sim.play().id
        sim.ev(FocusChanged(sim.t, id, FocusChange.LOSS_TRANSIENT)).ev(FocusChanged(sim.t, id, FocusChange.LOSS_TRANSIENT_CAN_DUCK))
            .ev(FocusChanged(sim.t + 1_000, id, FocusChange.GAIN))
        assertEquals(listOf(PlayMode.PAUSE, PlayMode.DUCK, PlayMode.NORMAL), sim.all<AdjustPlayback>().map { it.mode })
        assertEquals(Phase.WAIT, sim.ses.phase)
    }

    @Test fun r18k_requestNormalEnd_accumulatesOnce() {
        val sim = waiting().request().started()
        val id = sim.play().id
        sim.end()
        assertEquals(66_000, sim.ses.playedMs)
        assertEquals(Phase.WAIT, sim.ses.phase)
        sim.ev(PlaybackEnded(sim.t + 100, id, EndReason.DEADLINE))
        assertEquals(66_000, sim.ses.playedMs)
        assertEquals(1, sim.all<SavePlay>().count { it.record.id == id })
    }

    @Test fun r18l_repeatedRequests_neverExceedCaps() {
        val sim = waiting()
        repeat(5) { sim.request(); if (sim.s.session!!.play != null) sim.started().end() }
        assertEquals(112_000, sim.ep.playedMs)
        assertTrue(sim.ses.playedMs <= P.sessionPlayCapMs)
        assertTrue(Sid.S18 in sim.sids())
        assertEquals(listOf(56_000L, 46_000L), sim.all<SavePlay>().filter { it.record.purpose == PlayPurpose.REQUEST }.map { it.record.playedMs })
    }

    @Test fun r18m_requestPlaybackError_staysWait() {
        val sim = waiting().request().started()
        sim.ev(PlaybackEnded(sim.t + 4_000, sim.play().id, EndReason.ERROR))
        assertEquals(Phase.WAIT, sim.ses.phase); assertNull(sim.ses.play)
        assertEquals(PlayEndReason.ERROR, sim.step<SavePlay>().single().record.endReason)
        assertTrue(sim.step<Speak>().isEmpty() && sim.step<StartPlayback>().isEmpty())
    }

    @Test fun r19_stopWithoutPlayback_noop() {
        for (sim in listOf(session(), session().handoff(), waiting())) {
            val before = sim.s
            sim.ev(DriverStop(sim.t, Via.VOICE))
            assertTrue(sim.cmds.isEmpty())
            assertEquals(before.session!!.phase, sim.ses.phase)
        }
    }

    @Test fun r20_switchInHandoff_saysS18() {
        val sim = session().handoff().clear().request()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertTrue(sim.step<StartPlayback>().isEmpty())
        assertTrue(Sid.S18 in sim.sids())
    }

    @Test fun r11a_fiveQuietMinutes_backToListening() {
        val sim = session().handoff().tick(4 * MIN)
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        sim.tick(MIN)
        assertEquals(Phase.LISTENING, sim.ses.phase)
        assertNotNull(sim.step<SaveEpisode>().single().record.endedAt)
        val w = waiting().tick(5 * MIN)
        assertEquals(Phase.LISTENING, w.ses.phase)
    }

    @Test fun r11b_alarmWhileWaiting_resetsTimerNoPlay() {
        val sim = waiting().tick(4 * MIN).alarm()
        assertEquals(0, sim.ses.quietMs)
        assertEquals(AlarmOnly.WAITING, sim.step<LogAlarm>().single().reason)
        assertTrue(sim.step<StartPlayback>().isEmpty())
        sim.tick(4 * MIN)
        assertEquals(Phase.WAIT, sim.ses.phase)
    }

    @Test fun r18h_pausedAtRequestEnd_staysWait() {
        // 조용 4분 10초 → 56초 요청 재생 → 조용 5분 1초 → 판정 멈춤 → 재생 끝
        val sim = waiting().tick(4 * MIN + 10_000).request().started().tick(51_000)
        assertEquals(5 * MIN + 1_000, sim.ses.quietMs)
        sim.ev(InputLost(sim.t, PauseReason.SILENCED)).end()
        assertEquals(Phase.WAIT, sim.ses.phase)                       // 멈춤 표시가 있으면 LISTENING으로 가지 않는다
        assertNotNull(sim.ses.episode); assertEquals(1, sim.ses.attemptNo)
        assertTrue(sim.step<SaveEpisode>().none { it.record.endedAt != null })
        sim.ev(InputRestored(sim.t + 1_000, PauseReason.SILENCED)).tick(1_000)
        assertEquals(Phase.LISTENING, sim.ses.phase)                  // 입력이 돌아오면 11a
    }
}
