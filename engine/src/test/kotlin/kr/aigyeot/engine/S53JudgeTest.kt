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
        assertEquals(listOf<Command>(ShowNotice(Notice.MIC_STOPPED)), sim.cmds)
        sim.ev(MicService(sim.t, false, null))
        assertFalse(sim.s.car.micRunning)
        assertEquals(setOf(PauseReason.MIC_ERROR), sim.s.car.pauses)
        assertEquals(Unjudged.MIC_ERROR, sim.ep.first!!.unjudged)
    }

    @Test fun r21h_micDiedWhileIdle_noticeOnly() {
        val sim = idle().ev(InputLost(T0, PauseReason.MIC_ERROR)).ev(MicService(T0, false, null))
        assertNull(sim.s.session)
        assertFalse(sim.s.car.micRunning)
        assertEquals(listOf<Command>(ShowNotice(Notice.MIC_STOPPED)), sim.log)
    }
}
