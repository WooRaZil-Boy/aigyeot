package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 5.3 시간 전이와 판정(3-6~3-10, 3-21).
class S53JudgeTest {
    /** 1차를 미판정으로 만든다(잠깐 무음 처리). */
    private fun Sim.unjudgedFirst() = ev(InputLost(t, PauseReason.SILENCED)).ev(InputRestored(t, PauseReason.SILENCED))

    @Test fun r06a_attempt56s_entersGapWithFade() {
        val sim = session().attempt1()
        val end = sim.play().endAt
        sim.toGap()
        assertEquals(Phase.GAP, sim.ses.phase)
        assertEquals(end, sim.ses.judgeFrom); assertEquals(end + 8_000, sim.ses.windowEnd)
        assertTrue(sim.step<StopPlayback>().isEmpty())   // 실행기가 이미 페이드로 멈췄다
        val rec = sim.step<SavePlay>().single().record
        assertEquals(56_000, rec.playedMs); assertEquals(PlayEndReason.DEADLINE, rec.endReason)
    }

    @Test fun r06b_attemptEndsWhileInputLost_gapWaits() {
        val sim = session().attempt1().ev(InputLost(T0, PauseReason.DETECTOR_TIMEOUT)).toGap()
        assertEquals(Phase.GAP, sim.ses.phase)
        assertNull(sim.ses.judgeFrom); assertNull(sim.ses.windowEnd)
    }

    @Test fun r06c_lateTick_windowAnchoredToEndAt() {
        val sim = session().attempt1()
        val end = sim.play().endAt
        sim.ev(Tick(end + 3_000))
        assertEquals(end, sim.ses.judgeFrom); assertEquals(end + 8_000, sim.ses.windowEnd)
        sim.seg(end + 1_000)                               // 늦은 Tick 전 3초 안에 시작한 구간도 센다
        assertEquals(2, sim.ses.attemptNo)
        // 구간이 Tick보다 먼저 와도 창은 endAt에 붙는다
        val b = session().attempt1()
        val e2 = b.play().endAt
        b.seg(e2 + 500)
        assertEquals(2, b.ses.attemptNo)
    }

    @Test fun r07a_gapQuiet8s_entersConfirm() {
        val sim = session().attempt1().toGap()
        val w = sim.ses.windowEnd!!
        sim.closeQuiet()
        assertEquals(Phase.CONFIRM, sim.ses.phase)
        assertEquals(w + 30_000, sim.ses.windowEnd)
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun r07b_segmentStartedBeforeStop_ignored() {
        val sim = session().attempt1().toGap()
        sim.seg(sim.ses.judgeFrom!! - 1_000)
        assertEquals(Phase.GAP, sim.ses.phase)
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun r07c_segmentClosingGap_reevaluatedInConfirm() {
        val sim = session().attempt1().toGap()
        sim.seg(sim.ses.windowEnd!! - 1_000, 0.9)          // GAP 창을 닫는 울음 구간
        assertEquals(2, sim.ses.attemptNo)
        assertEquals(TryResult.FAIL, sim.ep.first!!.result)
        assertTrue(sim.ep.recried)
        assertEquals(1, sim.step<StartPlayback>().size)
    }

    @Test fun r08a_cryInGap1_startsAttempt2() {
        val sim = session().attempt1().toGap().recry()
        assertEquals(Phase.ATTEMPT, sim.ses.phase); assertEquals(2, sim.ses.attemptNo)
        assertTrue(sim.ep.recried); assertEquals(TryResult.FAIL, sim.ep.first!!.result)
        assertTrue(sim.step<SaveEpisode>().single().record.recried)
        assertEquals(LL, sim.step<StartPlayback>().single().method)   // 1차와 다른 음원
    }

    @Test fun r08b_cryInGap2_handsOff() {
        val sim = session().handoff()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertEquals(TryResult.FAIL, sim.ep.second!!.result)
        assertEquals(HandoffCause.TWO_ATTEMPTS, sim.ep.handoff!!.cause)
    }

    @Test fun r08c_recried_keptWhenSecondRejected() {
        val sim = session().attempt1().unjudgedFirst().toGap().recry()
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.FOCUS_DENIED))
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertTrue(sim.ep.recried)
        assertEquals(TryResult.UNJUDGED, sim.ep.first!!.result)
        assertTrue(sim.step<SaveEpisode>().single().record.recried)
    }

    @Test fun r09a_confirmQuiet30s_successAndListening() {
        val sim = session().attempt1().toConfirm().closeQuiet()
        assertEquals(Phase.LISTENING, sim.ses.phase)
        assertNull(sim.ses.episode)
        assertEquals(listOf(true), sim.s.history.map { it.success })
        val rec = sim.step<SaveEpisode>().single().record
        assertEquals(TryResult.SUCCESS, rec.firstResult); assertEquals(sim.t, rec.endedAt)
    }

    @Test fun r09b_unjudgedFirstStaysUnjudged() {
        val sim = session().attempt1().unjudgedFirst().toConfirm().closeQuiet()
        assertEquals(Phase.LISTENING, sim.ses.phase)
        assertEquals(TryResult.UNJUDGED, sim.step<SaveEpisode>().single().record.firstResult)
        assertTrue(sim.s.history.isEmpty())
    }

    @Test fun r09c_noRecry_recriedFalse() {
        val sim = session().attempt1().toConfirm().closeQuiet()
        assertFalse(sim.step<SaveEpisode>().single().record.recried)
    }

    @Test fun r09c_recriedFromGapCry_keptTrueOnSuccess() {
        val sim = session().attempt2().toConfirm().closeQuiet()
        val rec = sim.step<SaveEpisode>().single().record
        assertEquals(TryResult.SUCCESS, rec.secondResult)
        assertTrue(rec.recried)
    }

    @Test fun r10a_cryInConfirm1_failAndAttempt2() {
        val sim = session().attempt1().toConfirm().recry()
        assertEquals(2, sim.ses.attemptNo); assertEquals(Phase.ATTEMPT, sim.ses.phase)
        assertEquals(TryResult.FAIL, sim.ep.first!!.result)
        assertTrue(sim.ep.recried)
    }

    @Test fun r10b_cryInConfirm2_failAndHandoff() {
        val sim = session().attempt2().toConfirm().recry()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertEquals(TryResult.FAIL, sim.ep.second!!.result)
    }

    @Test fun r21a_inputLost_flagsPauseKeepsState() {
        val l = session().ev(InputLost(T0, PauseReason.SILENCED))
        assertEquals(Phase.LISTENING, l.ses.phase); assertEquals(setOf(PauseReason.SILENCED), l.s.car.pauses)
        l.alarm()
        assertTrue(l.step<StartPlayback>().isEmpty())     // 경보 판정은 보류
        val a = session().attempt1().ev(InputLost(T0, PauseReason.MIC_ERROR))
        assertEquals(Phase.ATTEMPT, a.ses.phase)
        assertEquals(Unjudged.MIC_ERROR, a.ep.first!!.unjudged)
    }

    @Test fun r21b_timeAndEventTransitionsContinue() {
        val sim = session().attempt1().ev(InputLost(T0, PauseReason.SILENCED)).toGap()
        assertEquals(Phase.GAP, sim.ses.phase)
        sim.gear(Gear.P, 1_000)
        assertEquals(Phase.WAIT, sim.ses.phase)
    }

    @Test fun r21c_inputLost_freezesQuietTimer() {
        val sim = session().handoff().tick(MIN).ev(InputLost(T0, PauseReason.SILENCED))
        val q = sim.ses.quietMs
        sim.tick(10 * MIN)
        assertEquals(Phase.HANDOFF, sim.ses.phase); assertEquals(q, sim.ses.quietMs)
    }

    @Test fun r21d_restore_restartsQuietWindow() {
        val g = session().attempt1().toGap().ev(InputLost(T0, PauseReason.SILENCED))
        assertNull(g.ses.windowEnd)
        g.ev(InputRestored(g.t + 20_000, PauseReason.SILENCED))
        assertEquals(g.t, g.ses.judgeFrom); assertEquals(g.t + 8_000, g.ses.windowEnd)
        val c = session().attempt1().toConfirm().ev(InputLost(T0, PauseReason.MIC_ERROR))
        c.ev(InputRestored(c.t + 5_000, PauseReason.MIC_ERROR))
        assertEquals(Phase.CONFIRM, c.ses.phase); assertEquals(c.t + 30_000, c.ses.windowEnd)
    }

    @Test fun r21e_countersKeptAcrossPause() {
        val sim = session().attempt2().toGap()
        val before = sim.ses
        sim.ev(InputLost(sim.t, PauseReason.DETECTOR_TIMEOUT)).ev(InputRestored(sim.t + 4_000, PauseReason.DETECTOR_TIMEOUT))
        assertEquals(Phase.GAP, sim.ses.phase)
        assertEquals(before.attemptNo, sim.ses.attemptNo)
        assertEquals(before.playedMs, sim.ses.playedMs)
        assertEquals(before.episode!!.playedMs, sim.ep.playedMs)
    }

    @Test fun r21f_silencedNeverCountsAsQuiet() {
        val sim = session().attempt1().toGap().ev(InputLost(T0, PauseReason.SILENCED))
        repeat(40) { sim.seg(sim.t, 0.0) }
        assertEquals(Phase.GAP, sim.ses.phase)
        assertTrue(sim.s.history.isEmpty())
    }

    @Test fun r21g_micServiceDiedUnexpectedly_showsMicStoppedNotice() {
        val sim = session().attempt1().toGap()
        sim.ev(InputLost(sim.t, PauseReason.MIC_ERROR))
        assertTrue(sim.cmds.none { it is ShowNotice }, "C393: 주행 중(D)에는 띄우지 않는다")
        sim.ev(MicService(sim.t, false, null))
        assertFalse(sim.s.car.micRunning)
        assertEquals(setOf(PauseReason.MIC_ERROR), sim.s.car.pauses)
        assertEquals(Unjudged.MIC_ERROR, sim.ep.first!!.unjudged)
    }

    @Test fun r21h_micDiedWhileIdle_noticeOnly() {
        val sim = idle().ev(InputLost(T0, PauseReason.MIC_ERROR)).ev(MicService(T0, false, null))
        assertNull(sim.s.session)
        assertFalse(sim.s.car.micRunning)
        // idle()의 마이크 켜짐(1j가 둘 다 내림, C394) 뒤 P에서 받아 띄운다(C393). Boot가 장부 되돌림(C364)을 낸다
        assertEquals(listOf<Command>(HideNotice(Notice.BABY_ABOARD), HideNotice(Notice.MIC_STOPPED), ShowNotice(Notice.MIC_STOPPED)), sim.log.filter { it !is RestoreAmbientLedger })
    }

    // 1i·C393: D에서 끊기면 안 띄우고 다음 P마다 띄운다(세션 안·밖)
    @Test fun r01i_micErrorReturnPark_reshowsMicStopped() {
        for (inSession in listOf(true, false)) {
            val sim = if (inSession) session() else idle(gear = Gear.D)
            sim.ev(InputLost(sim.t, PauseReason.MIC_ERROR)).ev(MicService(sim.t, false, null))
            assertTrue(sim.all<ShowNotice>().isEmpty(), "D에서는 안 띄운다 inSession=$inSession")
            assertEquals(listOf(Notice.MIC_STOPPED), sim.gear(Gear.P, MIN).step<ShowNotice>().map { it.kind }.filter { it == Notice.MIC_STOPPED }, "P에서 뜬다 inSession=$inSession")
            sim.gear(Gear.D, MIN).gear(Gear.P, MIN)
            assertEquals(listOf(Notice.MIC_STOPPED), sim.step<ShowNotice>().map { it.kind }.filter { it == Notice.MIC_STOPPED }, "다시 P에서도 뜬다 inSession=$inSession")
        }
    }

    @Test fun r01j_micStarted_hidesMicStopped() {
        val sim = idle(gear = Gear.D).ev(InputLost(T0, PauseReason.MIC_ERROR)).ev(MicService(T0, false, null)).gear(Gear.P, MIN)
        assertEquals(listOf(HideNotice(Notice.BABY_ABOARD), HideNotice(Notice.MIC_STOPPED)), sim.ev(MicService(sim.t, true, MicPath.NOTICE)).step<HideNotice>())
    }

    // 1k·C394: 장면 끝 Boot는 지금 기어 P를 읽어 뒤이은 P가 무시되므로 Boot에서 상태대로 다시 띄운다. 프로세스 시작 Boot(기어 null)는 띄우지 않는다
    @Test fun r01k_bootAtPark_reshowsMicStoppedOnly() {
        val err = idle(gear = Gear.D).ev(InputLost(T0, PauseReason.MIC_ERROR)).ev(MicService(T0, false, null)).gear(Gear.P, MIN)
        assertEquals(listOf(Notice.MIC_STOPPED), err.ev(Boot(err.t + MIN, Gear.P, 10)).step<ShowNotice>().map { it.kind })
        assertTrue(err.ev(GearChanged(err.t + 1, Gear.P)).step<ShowNotice>().isEmpty())   // 뒤이은 reemit의 같은 P는 그대로 무시
        assertTrue(err.ev(Boot(err.t + MIN, null, 10)).step<ShowNotice>().isEmpty())   // 프로세스 시작은 첫 GearChanged(P)가 맡는다
        // C395: 마이크가 켜진 세션에서 P에 선 채 장면을 반복하면 실행기가 마이크를 끈다(의도한 정지라 오류 없음). 회차 끝 Boot(P)는 '아기가 탔나요?'를 띄우지 않는다
        val ses = session().gear(Gear.P, MIN).ev(MicService(T0, false, null))
        assertTrue(ses.ev(Boot(ses.t + MIN, Gear.P, 10)).step<ShowNotice>().isEmpty())
        assertTrue(ses.s.session!!.micNoticeArmed)   // 새 시동이 건 칸은 남아 다음 P(14p)가 띄운다
        assertEquals(listOf(HideNotice(Notice.BABY_ABOARD), HideNotice(Notice.MIC_STOPPED)), ses.ev(MicService(ses.t, true, MicPath.HOME_BUTTON)).step<HideNotice>())   // 1j: 반복이 끝나 다시 켜면 칸을 끈다
        assertFalse(ses.s.session!!.micNoticeArmed)
        val off = session(mic = false).gear(Gear.P, MIN).also { it.ev(Boot(it.t + MIN, Gear.P, 10)) }.gear(Gear.D, MIN)
        assertEquals(listOf(Notice.BABY_ABOARD), off.gear(Gear.P, MIN).step<ShowNotice>().map { it.kind })   // 마이크가 꺼진 채 다음 P에서는 14p가 띄운다
        val on = session().gear(Gear.P, MIN)
        assertTrue(on.ev(Boot(on.t + MIN, Gear.P, 10)).step<ShowNotice>().isEmpty())   // 마이크가 켜져 있으면 띄우지 않는다
    }

    // 1l·C396: 정차 중 다시 켜기가 같은 오류로 실패하면(감지기는 이어받은 MIC_ERROR를 다시 내지 않음) 마이크 꺼짐에서 '듣기가 멈췄어요'를 되살린다
    @Test fun r01l_micRetryFailedAtPark_reshowsMicStopped() {
        for (inSession in listOf(false, true)) {
            val sim = (if (inSession) session() else idle(gear = Gear.D)).ev(InputLost(T0, PauseReason.MIC_ERROR)).ev(MicService(T0, false, null)).gear(Gear.P, MIN)
            sim.ev(MicService(sim.t, true, MicPath.APP_LAUNCH))   // 앱 화면 onResume: 1j가 내린다
            assertEquals(listOf(Notice.MIC_STOPPED), sim.ev(MicService(sim.t + 1_000, false, null)).step<ShowNotice>().map { it.kind }, "inSession=$inSession")   // InputLost 없이 꺼짐
            assertTrue(PauseReason.MIC_ERROR in sim.s.car.pauses)
        }
        val died = idle()   // P, 마이크 켜짐: 오류로 죽으면 21h가 띄우고, 뒤이은 꺼짐은 겹쳐 띄우지 않는다
        assertEquals(listOf(Notice.MIC_STOPPED), died.ev(InputLost(died.t, PauseReason.MIC_ERROR)).step<ShowNotice>().map { it.kind })
        assertTrue(died.ev(MicService(died.t, false, null)).step<ShowNotice>().isEmpty())
        val quiet = idle()   // 의도한 정지(오류 없음)는 띄우지 않는다
        assertTrue(quiet.ev(MicService(quiet.t, false, null)).step<ShowNotice>().isEmpty())
        val drive = idle(gear = Gear.D).ev(InputLost(T0, PauseReason.MIC_ERROR)).ev(MicService(T0, false, null)).gear(Gear.P, MIN).gear(Gear.D, MIN)
        drive.ev(MicService(drive.t, true, MicPath.APP_LAUNCH))
        assertTrue(drive.ev(MicService(drive.t, false, null)).step<ShowNotice>().isEmpty())   // 주행 중이면 띄우지 않고 다음 P(1i)
    }

    @Test fun r21g_micErrorAtPark_showsNoticeAtOnce() {
        val sim = idle().ev(InputLost(T0, PauseReason.MIC_ERROR))
        assertEquals(listOf(Notice.MIC_STOPPED), sim.step<ShowNotice>().map { it.kind })
    }

    @Test fun r01g_bootDrive_hidesMicStopped() {
        assertTrue(HideNotice(Notice.MIC_STOPPED) in idle(gear = Gear.D).also { it.clear() }.ev(Boot(T0 + MIN, Gear.D, 10)).step<HideNotice>())
    }
}
