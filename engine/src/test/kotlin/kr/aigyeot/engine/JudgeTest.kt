package kr.aigyeot.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 시간 전이와 판정.
class JudgeTest {
    /** 1차를 미판정으로 만든다(잠깐 무음 처리). */
    private fun Sim.unjudgedFirst() = ev(InputLost(t, PauseReason.SILENCED)).ev(InputRestored(t, PauseReason.SILENCED))

    @Test fun attempt56s_entersGapWithFade() {
        val sim = session().attempt1()
        val end = sim.play().endAt
        sim.toGap()
        assertEquals(Phase.GAP, sim.ses.phase)
        assertEquals(end, sim.ses.judgeFrom); assertEquals(end + 8_000, sim.ses.windowEnd)
        assertTrue(sim.step<StopPlayback>().isEmpty())   // 실행기가 이미 페이드로 멈췄다
        val rec = sim.step<SavePlay>().single().record
        assertEquals(56_000, rec.playedMs); assertEquals(PlayEndReason.DEADLINE, rec.endReason)
    }

    @Test fun attemptEndsWhileInputLost_gapWaits() {
        val sim = session().attempt1().ev(InputLost(T0, PauseReason.DETECTOR_TIMEOUT)).toGap()
        assertEquals(Phase.GAP, sim.ses.phase)
        assertNull(sim.ses.judgeFrom); assertNull(sim.ses.windowEnd)
    }

    @Test fun lateTick_windowAnchoredToEndAt() {
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

    @Test fun gapQuiet8s_entersConfirm() {
        val sim = session().attempt1().toGap()
        val w = sim.ses.windowEnd!!
        sim.closeQuiet()
        assertEquals(Phase.CONFIRM, sim.ses.phase)
        assertEquals(w + 30_000, sim.ses.windowEnd)
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun segmentStartedBeforeStop_ignored() {
        val sim = session().attempt1().toGap()
        sim.seg(sim.ses.judgeFrom!! - 1_000)
        assertEquals(Phase.GAP, sim.ses.phase)
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun segmentClosingGap_reevaluatedInConfirm() {
        val sim = session().attempt1().toGap()
        sim.seg(sim.ses.windowEnd!! - 1_000, 0.9)          // GAP 창을 닫는 울음 구간
        assertEquals(2, sim.ses.attemptNo)
        assertEquals(TryResult.FAIL, sim.ep.first!!.result)
        assertTrue(sim.ep.recried)
        assertEquals(1, sim.step<StartPlayback>().size)
    }

    @Test fun cryInGap1_startsAttempt2() {
        val sim = session().attempt1().toGap().recry()
        assertEquals(Phase.ATTEMPT, sim.ses.phase); assertEquals(2, sim.ses.attemptNo)
        assertTrue(sim.ep.recried); assertEquals(TryResult.FAIL, sim.ep.first!!.result)
        assertTrue(sim.step<SaveEpisode>().single().record.recried)
        assertEquals(LL, sim.step<StartPlayback>().single().method)   // 1차와 다른 음원
    }

    @Test fun cryInGap2_handsOff() {
        val sim = session().handoff()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertEquals(TryResult.FAIL, sim.ep.second!!.result)
        assertEquals(HandoffCause.TWO_ATTEMPTS, sim.ep.handoff!!.cause)
    }

    @Test fun recried_keptWhenSecondRejected() {
        val sim = session().attempt1().unjudgedFirst().toGap().recry()
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.FOCUS_DENIED))
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertTrue(sim.ep.recried)
        assertEquals(TryResult.UNJUDGED, sim.ep.first!!.result)
        assertTrue(sim.step<SaveEpisode>().single().record.recried)
    }

    @Test fun confirmQuiet30s_successAndListening() {
        val sim = session().attempt1().toConfirm().closeQuiet()
        assertEquals(Phase.LISTENING, sim.ses.phase)
        assertNull(sim.ses.episode)
        assertEquals(listOf(true), sim.s.history.map { it.success })
        val rec = sim.step<SaveEpisode>().single().record
        assertEquals(TryResult.SUCCESS, rec.firstResult); assertEquals(sim.t, rec.endedAt)
    }

    @Test fun unjudgedFirstStaysUnjudged() {
        val sim = session().attempt1().unjudgedFirst().toConfirm().closeQuiet()
        assertEquals(Phase.LISTENING, sim.ses.phase)
        assertEquals(TryResult.UNJUDGED, sim.step<SaveEpisode>().single().record.firstResult)
        assertTrue(sim.s.history.isEmpty())
    }

    @Test fun noRecry_recriedFalse() {
        val sim = session().attempt1().toConfirm().closeQuiet()
        assertFalse(sim.step<SaveEpisode>().single().record.recried)
    }

    @Test fun recriedFromGapCry_keptTrueOnSuccess() {
        val sim = session().attempt2().toConfirm().closeQuiet()
        val rec = sim.step<SaveEpisode>().single().record
        assertEquals(TryResult.SUCCESS, rec.secondResult)
        assertTrue(rec.recried)
    }

    @Test fun cryInConfirm1_failAndAttempt2() {
        val sim = session().attempt1().toConfirm().recry()
        assertEquals(2, sim.ses.attemptNo); assertEquals(Phase.ATTEMPT, sim.ses.phase)
        assertEquals(TryResult.FAIL, sim.ep.first!!.result)
        assertTrue(sim.ep.recried)
    }

    @Test fun cryInConfirm2_failAndHandoff() {
        val sim = session().attempt2().toConfirm().recry()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertEquals(TryResult.FAIL, sim.ep.second!!.result)
    }

    @Test fun inputLost_flagsPauseKeepsState() {
        val l = session().ev(InputLost(T0, PauseReason.SILENCED))
        assertEquals(Phase.LISTENING, l.ses.phase); assertEquals(setOf(PauseReason.SILENCED), l.s.car.pauses)
        l.alarm()
        assertTrue(l.step<StartPlayback>().isEmpty())     // 경보 판정은 보류
        val a = session().attempt1().ev(InputLost(T0, PauseReason.MIC_ERROR))
        assertEquals(Phase.ATTEMPT, a.ses.phase)
        assertEquals(Unjudged.MIC_ERROR, a.ep.first!!.unjudged)
    }

    @Test fun timeAndEventTransitionsContinue() {
        val sim = session().attempt1().ev(InputLost(T0, PauseReason.SILENCED)).toGap()
        assertEquals(Phase.GAP, sim.ses.phase)
        sim.gear(Gear.P, 1_000)
        assertEquals(Phase.WAIT, sim.ses.phase)
    }

    @Test fun inputLost_freezesQuietTimer() {
        val sim = session().handoff().tick(MIN).ev(InputLost(T0, PauseReason.SILENCED))
        val q = sim.ses.quietMs
        sim.tick(10 * MIN)
        assertEquals(Phase.HANDOFF, sim.ses.phase); assertEquals(q, sim.ses.quietMs)
    }

    @Test fun restore_restartsQuietWindow() {
        val g = session().attempt1().toGap().ev(InputLost(T0, PauseReason.SILENCED))
        assertNull(g.ses.windowEnd)
        g.ev(InputRestored(g.t + 20_000, PauseReason.SILENCED))
        assertEquals(g.t, g.ses.judgeFrom); assertEquals(g.t + 8_000, g.ses.windowEnd)
        val c = session().attempt1().toConfirm().ev(InputLost(T0, PauseReason.MIC_ERROR))
        c.ev(InputRestored(c.t + 5_000, PauseReason.MIC_ERROR))
        assertEquals(Phase.CONFIRM, c.ses.phase); assertEquals(c.t + 30_000, c.ses.windowEnd)
    }

    @Test fun countersKeptAcrossPause() {
        val sim = session().attempt2().toGap()
        val before = sim.ses
        sim.ev(InputLost(sim.t, PauseReason.DETECTOR_TIMEOUT)).ev(InputRestored(sim.t + 4_000, PauseReason.DETECTOR_TIMEOUT))
        assertEquals(Phase.GAP, sim.ses.phase)
        assertEquals(before.attemptNo, sim.ses.attemptNo)
        assertEquals(before.playedMs, sim.ses.playedMs)
        assertEquals(before.episode!!.playedMs, sim.ep.playedMs)
    }

    @Test fun silencedNeverCountsAsQuiet() {
        val sim = session().attempt1().toGap().ev(InputLost(T0, PauseReason.SILENCED))
        repeat(40) { sim.seg(sim.t, 0.0) }
        assertEquals(Phase.GAP, sim.ses.phase)
        assertTrue(sim.s.history.isEmpty())
    }

    @Test fun micServiceDiedUnexpectedly_showsMicStoppedNotice() {
        val sim = session().attempt1().toGap()
        sim.ev(InputLost(sim.t, PauseReason.MIC_ERROR))
        assertTrue(sim.cmds.none { it is ShowNotice }, "주행 중(D)에는 띄우지 않는다")
        sim.ev(MicService(sim.t, false, null))
        assertFalse(sim.s.car.micRunning)
        assertEquals(setOf(PauseReason.MIC_ERROR), sim.s.car.pauses)
        assertEquals(Unjudged.MIC_ERROR, sim.ep.first!!.unjudged)
    }

    @Test fun micDiedWhileIdle_noticeOnly() {
        val sim = idle().ev(InputLost(T0, PauseReason.MIC_ERROR)).ev(MicService(T0, false, null))
        assertNull(sim.s.session)
        assertFalse(sim.s.car.micRunning)
        // idle()의 마이크 켜짐(앱 화면이 떠서 둘 다 내림) 뒤 P에서 받아 띄운다. Boot가 장부 되돌림을 낸다
        assertEquals(listOf<Command>(HideNotice(Notice.BABY_ABOARD), HideNotice(Notice.MIC_STOPPED), ShowNotice(Notice.MIC_STOPPED)), sim.log.filter { it !is RestoreAmbientLedger })
    }

    // D에서 끊기면 안 띄우고 다음 P마다 띄운다(세션 안·밖)
    @Test fun micErrorReturnPark_reshowsMicStopped() {
        for (inSession in listOf(true, false)) {
            val sim = if (inSession) session() else idle(gear = Gear.D)
            sim.ev(InputLost(sim.t, PauseReason.MIC_ERROR)).ev(MicService(sim.t, false, null))
            assertTrue(sim.all<ShowNotice>().isEmpty(), "D에서는 안 띄운다 inSession=$inSession")
            assertEquals(listOf(Notice.MIC_STOPPED), sim.gear(Gear.P, MIN).step<ShowNotice>().map { it.kind }.filter { it == Notice.MIC_STOPPED }, "P에서 뜬다 inSession=$inSession")
            sim.gear(Gear.D, MIN).gear(Gear.P, MIN)
            assertEquals(listOf(Notice.MIC_STOPPED), sim.step<ShowNotice>().map { it.kind }.filter { it == Notice.MIC_STOPPED }, "다시 P에서도 뜬다 inSession=$inSession")
        }
    }

    @Test fun micStarted_hidesMicStopped() {
        val sim = idle(gear = Gear.D).ev(InputLost(T0, PauseReason.MIC_ERROR)).ev(MicService(T0, false, null)).gear(Gear.P, MIN)
        assertEquals(listOf(HideNotice(Notice.BABY_ABOARD), HideNotice(Notice.MIC_STOPPED)), sim.ev(MicService(sim.t, true, MicPath.NOTICE)).step<HideNotice>())
    }

    // 장면 끝 Boot는 지금 기어 P를 읽어 뒤이은 P가 무시되므로 Boot에서 상태대로 다시 띄운다. 프로세스 시작 Boot(기어 null)는 띄우지 않는다
    @Test fun bootAtPark_reshowsMicStoppedOnly() {
        val err = idle(gear = Gear.D).ev(InputLost(T0, PauseReason.MIC_ERROR)).ev(MicService(T0, false, null)).gear(Gear.P, MIN)
        assertEquals(listOf(Notice.MIC_STOPPED), err.ev(Boot(err.t + MIN, Gear.P, 10)).step<ShowNotice>().map { it.kind })
        assertTrue(err.ev(GearChanged(err.t + 1, Gear.P)).step<ShowNotice>().isEmpty())   // 뒤이은 reemit의 같은 P는 그대로 무시
        assertTrue(err.ev(Boot(err.t + MIN, null, 10)).step<ShowNotice>().isEmpty())   // 프로세스 시작은 첫 GearChanged(P)가 맡는다
        // 마이크가 켜진 세션에서 P에 선 채 장면을 반복하면 실행기가 마이크를 끈다(의도한 정지라 오류 없음). 회차 끝 Boot(P)는 '아기가 탔나요?'를 띄우지 않는다
        val ses = session().gear(Gear.P, MIN).ev(MicService(T0, false, null))
        assertTrue(ses.ev(Boot(ses.t + MIN, Gear.P, 10)).step<ShowNotice>().isEmpty())
        assertTrue(ses.s.session!!.micNoticeArmed)   // 새 시동이 건 칸은 남아 다음 P가 띄운다
        assertEquals(listOf(HideNotice(Notice.BABY_ABOARD), HideNotice(Notice.MIC_STOPPED)), ses.ev(MicService(ses.t, true, MicPath.HOME_BUTTON)).step<HideNotice>())   // 반복이 끝나 다시 켜면 칸을 끈다
        assertFalse(ses.s.session!!.micNoticeArmed)
        val off = session(mic = false).gear(Gear.P, MIN).also { it.ev(Boot(it.t + MIN, Gear.P, 10)) }.gear(Gear.D, MIN)
        assertEquals(listOf(Notice.BABY_ABOARD), off.gear(Gear.P, MIN).step<ShowNotice>().map { it.kind })   // 마이크가 꺼진 채 다음 P에서는 띄운다
        val on = session().gear(Gear.P, MIN)
        assertTrue(on.ev(Boot(on.t + MIN, Gear.P, 10)).step<ShowNotice>().isEmpty())   // 마이크가 켜져 있으면 띄우지 않는다
    }

    // 정차 중 다시 켜기가 같은 오류로 실패하면(감지기는 이어받은 MIC_ERROR를 다시 내지 않음) 마이크 꺼짐에서 '듣기가 멈췄어요'를 되살린다
    @Test fun micRetryFailedAtPark_reshowsMicStopped() {
        for (inSession in listOf(false, true)) {
            val sim = (if (inSession) session() else idle(gear = Gear.D)).ev(InputLost(T0, PauseReason.MIC_ERROR)).ev(MicService(T0, false, null)).gear(Gear.P, MIN)
            sim.ev(MicService(sim.t, true, MicPath.APP_LAUNCH))   // 앱 화면 onResume: 내린다
            assertEquals(listOf(Notice.MIC_STOPPED), sim.ev(MicService(sim.t + 1_000, false, null)).step<ShowNotice>().map { it.kind }, "inSession=$inSession")   // InputLost 없이 꺼짐
            assertTrue(PauseReason.MIC_ERROR in sim.s.car.pauses)
        }
        val died = idle()   // P, 마이크 켜짐: 오류로 죽으면 띄우고, 뒤이은 꺼짐은 겹쳐 띄우지 않는다
        assertEquals(listOf(Notice.MIC_STOPPED), died.ev(InputLost(died.t, PauseReason.MIC_ERROR)).step<ShowNotice>().map { it.kind })
        assertTrue(died.ev(MicService(died.t, false, null)).step<ShowNotice>().isEmpty())
        val quiet = idle()   // 의도한 정지(오류 없음)는 띄우지 않는다
        assertTrue(quiet.ev(MicService(quiet.t, false, null)).step<ShowNotice>().isEmpty())
        val drive = idle(gear = Gear.D).ev(InputLost(T0, PauseReason.MIC_ERROR)).ev(MicService(T0, false, null)).gear(Gear.P, MIN).gear(Gear.D, MIN)
        drive.ev(MicService(drive.t, true, MicPath.APP_LAUNCH))
        assertTrue(drive.ev(MicService(drive.t, false, null)).step<ShowNotice>().isEmpty())   // 주행 중이면 띄우지 않고 다음 P
    }

    @Test fun micErrorAtPark_showsNoticeAtOnce() {
        val sim = idle().ev(InputLost(T0, PauseReason.MIC_ERROR))
        assertEquals(listOf(Notice.MIC_STOPPED), sim.step<ShowNotice>().map { it.kind })
    }

    @Test fun bootDrive_hidesMicStopped() {
        assertTrue(HideNotice(Notice.MIC_STOPPED) in idle(gear = Gear.D).also { it.clear() }.ev(Boot(T0 + MIN, Gear.D, 10)).step<HideNotice>())
    }
}

// 판정 뒤에 오는 구간과 재생 사용 시간.
// 판정 창 시작(judgeFrom)과 '재생 소리가 섞인 구간 거르기'를 칸 둘로 나눴다. 자동 시도 재생이 멈춘 시각(mixedUntil)보다 앞에 시작한 구간은
// 다음 단계가 무엇이든(GAP·WAIT·HANDOFF) sees() 하나가 거른다. 재현: ATTEMPT 중 '그만'·소리 바꾸기·통화·P·포커스 상실·오류·Suspend·상한 인계로
// WAIT·HANDOFF가 되면 창이 없어 꼬리 구간을 울음으로 봤다(울음 수 +1, 질문 답의 '지금 n초째', 5분 조용함 다시, alarm_log WAITING).
// 재생 사용 시간은 실제로 멈춘 시각까지(endAt을 넘지 않는다). 복원 ③은 스냅숏의 마지막 시각을 멈춘 시각으로 넘긴다(호스트 몫).
class MixedTailTest {
    /** 자동 시도 1차를 20초 틀다가 [end]로 끝낸다. 재생이 멈춘 시각을 돌려준다. */
    private fun Sim.stopAttempt(end: Sim.(at: Long) -> Unit): Long {
        attempt1()
        val at = t + 20_000
        end(at)
        return at
    }

    /**
     * 재생이 섞인 꼬리 구간(멈추기 1초 전에 시작해 멈춘 뒤 도착한 경보)은 보지 않는다:
     * 울음 수·이어진 울음(질문 답의 지금 울음)·울음 시간·조향 창·alarm_log·5분 조용함이 그대로다. 멈춘 뒤에 시작한 구간은 본다(지나치게 거르지 않는다).
     */
    private fun Sim.assertTailIgnored(stopAt: Long, what: String) {
        ev(VehicleSample(t, 60.0, false, 0.0, TurnSignal.NONE))   // 멈춘 뒤 조향 창을 연다(판단 코어가 듣는 중)
        val runs = ses.cryRuns; val run = ses.alarmRun; val cry = ep.cryMs; val phase = ses.phase
        val q0 = ses.quietMs; val counts = s.ign.suspendedAt == null; val from = s.now
        clear()
        ev(CrySegment(stopAt + 2_000, stopAt - 1_000, stopAt + 2_000, 0.9, true))
        assertEquals(phase, ses.phase, what)
        assertEquals(runs, ses.cryRuns, "$what: 울음 수가 늘지 않는다")
        assertEquals(run, ses.alarmRun, "$what: 질문 답의 지금 울음을 새로 열지 않는다")
        assertEquals(cry, ep.cryMs, "$what: 울음 시간")
        assertFalse(s.steer.window!!.cryPresent, "$what: 조향 창 cry_present")
        assertTrue(step<LogAlarm>().isEmpty(), "$what: alarm_log WAITING을 남기지 않는다")
        assertEquals(if (counts) q0 + (stopAt + 2_000 - from) else q0, ses.quietMs, "$what: 5분 조용함을 다시 시작하지 않는다")
        ev(CrySegment(stopAt + 3_000, stopAt, stopAt + 3_000, 0.9, true))   // 멈춘 뒤에 시작한 구간
        assertEquals(runs + 1, ses.cryRuns, "$what: 본 경보는 센다")
        assertEquals(AlarmOnly.WAITING, step<LogAlarm>().single().reason, what)
        assertEquals(0, ses.quietMs, what)
    }

    private fun tail(what: String, phase: Phase, end: Sim.(at: Long) -> Unit) {
        val sim = session()
        val at = sim.stopAttempt(end)
        assertEquals(phase, sim.ses.phase, what)
        sim.assertTailIgnored(at, what)
    }

    // ---- 자동 시도 재생을 끝내는 길마다 ----

    @Test fun driverStopTail_notSeen() = tail("그만", Phase.WAIT) { ev(DriverStop(it, Via.VOICE)) }

    @Test fun switchSoundTail_notSeen() = tail("소리 바꾸기", Phase.WAIT) { ev(SwitchSound(it, LL)) }

    @Test fun callTail_notSeen() = tail("통화", Phase.WAIT) { ev(CallMode(it, true)) }

    @Test fun parkTail_notSeen() = tail("P", Phase.WAIT) { ev(GearChanged(it, Gear.P)) }

    @Test fun focusLossTail_notSeen() = tail("포커스 상실", Phase.WAIT) { ev(FocusChanged(it, play().id, FocusChange.LOSS)) }

    @Test fun playbackErrorTail_notSeen() = tail("재생 오류", Phase.WAIT) { ev(PlaybackEnded(it, play().id, EndReason.ERROR)) }

    @Test fun suspendTail_notSeen() = tail("Suspend", Phase.WAIT) { ev(Suspend(it)) }

    @Test fun capHandoffTail_notSeen() {
        val sim = session()
        sim.s = sim.s.copy(session = sim.ses.copy(playedMs = P.sessionPlayCapMs - 20_000))   // 마지막 20초
        sim.attempt1()
        val end = sim.play().endAt
        sim.ev(Tick(end))
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        sim.assertTailIgnored(end, "상한 인계")
    }

    /** ATTEMPT → GAP만 창 시작으로 걸렀다(같은 규칙의 두 구현). GAP에서 곧 WAIT로 가도 꼬리 구간은 그대로 걸러야 한다. */
    @Test fun gapThenWaitTail_notSeen() {
        val sim = session().attempt1().toGap()
        val end = sim.ses.judgeFrom!!
        sim.ev(DriverStop(end + 500, Via.VOICE))
        assertEquals(Phase.WAIT, sim.ses.phase)
        sim.assertTailIgnored(end, "GAP 뒤 그만")
    }

    /** 복원 뒤: 스냅숏(JSON)으로 다시 읽은 상태도 같은 구간을 거른다(멈춘 시각이 상태에 남는다). */
    @Test fun restoredSnapshotTail_notSeen() {
        val sim = session()
        val at = sim.stopAttempt { ev(DriverStop(it, Via.VOICE)) }
        sim.s = Json.decodeFromString<EngineState>(Json.encodeToString(sim.s))
        sim.assertTailIgnored(at, "스냅숏 복원 뒤")
    }

    /** 소리가 나지 않은 자동 시도(시작 전에 멈춤·시작과 같은 순간 멈춤)에는 섞인 구간이 없다: 그 앞에 시작한 울음도 보고 이어진 울음이 끊기지 않는다. */
    @Test fun silentAttemptTail_seen() {
        val pending = session().alarm()                                    // StartPlayback, 시작 대기
        val a = pending.t
        pending.ev(DriverStop(a + 500, Via.VOICE))
        assertEquals(Phase.WAIT, pending.ses.phase)
        pending.clear().ev(CrySegment(a + 1_200, a - 1_800, a + 1_200, 0.9, true))
        assertEquals(AlarmOnly.WAITING, pending.step<LogAlarm>().single().reason, "시작 전에 멈춘 재생")
        assertEquals(AlarmRun(a, a + 1_200), pending.ses.alarmRun); assertEquals(1, pending.ses.cryRuns)
        val zero = session().attempt1()                                    // 시작과 같은 순간 '그만'(0ms)
        val b = zero.t
        zero.ev(DriverStop(b, Via.VOICE))
        zero.clear().ev(CrySegment(b + 1_500, b - 1_500, b + 1_500, 0.9, true))
        assertEquals(AlarmOnly.WAITING, zero.step<LogAlarm>().single().reason, "0ms 재생")
        assertEquals(1, zero.ses.cryRuns)
    }

    /** 직접 튼 소리(요청 재생)는 본다: 요청 재생이 멈춰도 거르지 않는다. */
    @Test fun requestPlayTail_seen() {
        val sim = session().ev(SwitchSound(T0, LL)).started()
        val stop = sim.t + 20_000
        sim.ev(DriverStop(stop, Via.VOICE))
        assertEquals(Phase.WAIT, sim.ses.phase)
        sim.clear().ev(CrySegment(stop + 2_000, stop - 1_000, stop + 2_000, 0.9, true))
        assertEquals(AlarmOnly.WAITING, sim.step<LogAlarm>().single().reason)
        assertEquals(1, sim.ses.cryRuns)
    }

    /** judgeFrom은 판정 창 시작만이다: 입력 복귀 앞에 시작한 구간은 판정에 넣지 않는다. 재생이 섞이지 않아 울음으로는 본다. */
    @Test fun windowStartOnlyJudges() {
        val sim = session().attempt1().toGap()
        sim.ev(InputLost(sim.t + 1_000, PauseReason.SILENCED)).ev(InputRestored(sim.t + 2_000, PauseReason.SILENCED))
        val from = sim.ses.judgeFrom!!
        assertEquals(sim.t, from)
        val cry = sim.ep.cryMs
        sim.ev(CrySegment(from + 2_000, from - 1_000, from + 2_000, 0.9, false))
        assertEquals(Phase.GAP, sim.ses.phase, "창 시작 앞에 시작한 구간은 판정하지 않는다")
        assertEquals(cry + P.yamnetHopMs, sim.ep.cryMs, "재생이 섞이지 않은 울음이라 울음 시간에는 넣는다")
        sim.ev(CrySegment(from + 3_000, from, from + 3_000, 0.9, false))
        assertEquals(Phase.ATTEMPT, sim.ses.phase, "창 안의 울음은 판정한다")
    }

    // ---- 재생 사용 시간 = 실제로 멈춘 시각까지 ----

    /** endAt 뒤에 온 재생 끝(오류·복원)은 endAt까지만 센다. 실행기는 endAt에 멈췄다. */
    @Test fun lateEndCountsUntilEndAt() {
        val sim = session().attempt1()
        val pl = sim.play()
        sim.ev(PlaybackEnded(pl.endAt + 10 * MIN, pl.id, EndReason.ERROR))
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertEquals(P.attemptMs, sim.ses.playedMs); assertEquals(P.attemptMs, sim.ep.playedMs)
        val rec = sim.step<SavePlay>().single().record
        assertEquals(pl.endAt, rec.endedAt); assertEquals(P.attemptMs, rec.playedMs)
    }

    /**
     * 복원 ③: 호스트는 끊긴 재생을 스냅숏의 마지막 시각(복원 ②가 돌려준 상태의 now)에 끝낸 사실 사건으로 넣는다(호스트 몫).
     * 마지막 시도(남은 20초) 5초 뒤에 죽고 10분 뒤 복원해도 상한에 닿지 않는다. 복원 시각까지 세면 10분이 더해져 capReached가 된다.
     */
    @Test fun restoreStopsAtSnapshotTime() {
        val sim = session()
        sim.s = sim.s.copy(session = sim.ses.copy(playedMs = P.sessionPlayCapMs - 20_000))
        sim.attempt1()
        val started = sim.t; val id = sim.play().id
        sim.ev(VehicleSample(started + 5_000, 60.0, false, 0.0, TurnSignal.NONE))   // 스냅숏의 마지막 시각
        val eng = Engine()
        val at = sim.t + 10 * MIN
        var st = eng.restoreFromRoom(sim.s, facts(played = P.sessionPlayCapMs - 20_000), at)   // ②
        val step = eng.step(st, PlaybackEnded(st.now, id, EndReason.ERROR))                    // ③ 사실 사건(stopAt = 스냅숏 now)
        st = eng.afterRestore(step.state, at, micAlive = true)
        assertEquals(P.sessionPlayCapMs - 15_000, st.session!!.playedMs)
        assertFalse(st.session!!.capReached(P))
        assertEquals(started + 5_000, step.commands.filterIsInstance<SavePlay>().single().record.endedAt)
    }

    /** 장면 끝 복원도 같다: 실제 상태의 마지막 시각은 장면 시작 시각이라 장면 길이(장면 A 1,230초)를 재생 시간에 넣지 않는다. */
    @Test fun sceneEndRestoreStopsAtSceneStart() {
        val sim = session().attempt1()
        val id = sim.play().id
        val eng = Engine()
        val sceneAt = sim.t + 10_000
        val real = eng.sceneStart(sim.s, sim.s, sceneAt).realStep.state   // 호스트와 같다(장면 시작이 재생을 끊어 보관한다)
        val endAt = sceneAt + 1_230_000
        val st = eng.restoreFromRoom(real, facts(), endAt)   // 장면 끝은 보관한 실제 상태 그대로 복원한다
        val step = eng.step(st, PlaybackEnded(st.now, id, EndReason.ERROR))
        assertTrue(step.state.session!!.playedMs <= 10_000, "장면 시작 뒤 시간을 세지 않는다: ${step.state.session!!.playedMs}")
    }
}
