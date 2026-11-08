package kr.aigyeot.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// C318(F-C, 11-08) V4·V10.
// V4(P2): 판정 창 시작(judgeFrom)과 '재생 소리가 섞인 구간 거르기'를 칸 둘로 나눴다. 자동 시도 재생이 멈춘 시각(mixedUntil)보다 앞에 시작한 구간은
// 다음 단계가 무엇이든(GAP·WAIT·HANDOFF) sees() 하나가 거른다. 옛 재현: ATTEMPT 중 '그만'·소리 바꾸기·통화·P·포커스 상실·오류·Suspend·상한 인계로
// WAIT·HANDOFF가 되면 창이 없어 꼬리 구간을 울음으로 봤다(울음 수 +1, ④ '지금 n초째', 5분 조용함 다시, alarm_log WAITING).
// V10(P5): 재생 사용 시간은 실제로 멈춘 시각까지(endAt을 넘지 않는다). 복원 ③은 스냅숏의 마지막 시각을 멈춘 시각으로 넘긴다(호스트 몫, H1).
class C318MixedTailTest {
    /** 자동 시도 1차를 20초 틀다가 [end]로 끝낸다. 재생이 멈춘 시각을 돌려준다. */
    private fun Sim.stopAttempt(end: Sim.(at: Long) -> Unit): Long {
        attempt1()
        val at = t + 20_000
        end(at)
        return at
    }

    /**
     * 재생이 섞인 꼬리 구간(멈추기 1초 전에 시작해 멈춘 뒤 도착한 경보)은 보지 않는다:
     * 울음 수·이어진 울음(④ 지금 울음)·울음 시간·조향 창·alarm_log·5분 조용함이 그대로다. 멈춘 뒤에 시작한 구간은 본다(지나치게 거르지 않는다).
     */
    private fun Sim.assertTailIgnored(stopAt: Long, what: String) {
        ev(VehicleSample(t, 60.0, false, 0.0, TurnSignal.NONE))   // 멈춘 뒤 조향 창을 연다(판단 코어가 듣는 중)
        val runs = ses.cryRuns; val run = ses.alarmRun; val cry = ep.cryMs; val phase = ses.phase
        val q0 = ses.quietMs; val counts = s.ign.suspendedAt == null; val from = s.now
        clear()
        ev(CrySegment(stopAt + 2_000, stopAt - 1_000, stopAt + 2_000, 0.9, true))
        assertEquals(phase, ses.phase, what)
        assertEquals(runs, ses.cryRuns, "$what: 울음 수가 늘지 않는다")
        assertEquals(run, ses.alarmRun, "$what: ④ 지금 울음을 새로 열지 않는다")
        assertEquals(cry, ep.cryMs, "$what: 울음 시간(G37)")
        assertFalse(s.steer.window!!.cryPresent, "$what: 조향 창 cry_present(C312)")
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

    // ---- V4: 자동 시도 재생을 끝내는 길마다 ----

    @Test fun c318_v4_driverStopTail_notSeen() = tail("그만(14a)", Phase.WAIT) { ev(DriverStop(it, Via.VOICE)) }

    @Test fun c318_v4_switchSoundTail_notSeen() = tail("소리 바꾸기(14d)", Phase.WAIT) { ev(SwitchSound(it, LL)) }

    @Test fun c318_v4_callTail_notSeen() = tail("통화(15a)", Phase.WAIT) { ev(CallMode(it, true)) }

    @Test fun c318_v4_parkTail_notSeen() = tail("P(14b)", Phase.WAIT) { ev(GearChanged(it, Gear.P)) }

    @Test fun c318_v4_focusLossTail_notSeen() = tail("포커스 상실(14c)", Phase.WAIT) { ev(FocusChanged(it, play().id, FocusChange.LOSS)) }

    @Test fun c318_v4_playbackErrorTail_notSeen() = tail("재생 오류(14o)", Phase.WAIT) { ev(PlaybackEnded(it, play().id, EndReason.ERROR)) }

    @Test fun c318_v4_suspendTail_notSeen() = tail("Suspend(14e)", Phase.WAIT) { ev(Suspend(it)) }

    @Test fun c318_v4_capHandoffTail_notSeen() {
        val sim = session()
        sim.s = sim.s.copy(session = sim.ses.copy(playedMs = P.sessionPlayCapMs - 20_000))   // 마지막 20초
        sim.attempt1()
        val end = sim.play().endAt
        sim.ev(Tick(end))                                                                       // 12a
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        sim.assertTailIgnored(end, "상한 인계(12a)")
    }

    /** 옛 코드는 ATTEMPT → GAP만 창 시작으로 걸렀다(같은 규칙의 두 구현). GAP에서 곧 WAIT로 가도 꼬리 구간은 그대로 걸러야 한다. */
    @Test fun c318_v4_gapThenWaitTail_notSeen() {
        val sim = session().attempt1().toGap()
        val end = sim.ses.judgeFrom!!
        sim.ev(DriverStop(end + 500, Via.VOICE))
        assertEquals(Phase.WAIT, sim.ses.phase)
        sim.assertTailIgnored(end, "GAP 뒤 그만")
    }

    /** 복원 뒤: 스냅숏(JSON)으로 다시 읽은 상태도 같은 구간을 거른다(멈춘 시각이 상태에 남는다). */
    @Test fun c318_v4_restoredSnapshotTail_notSeen() {
        val sim = session()
        val at = sim.stopAttempt { ev(DriverStop(it, Via.VOICE)) }
        sim.s = Json.decodeFromString<EngineState>(Json.encodeToString(sim.s))
        sim.assertTailIgnored(at, "스냅숏 복원 뒤")
    }

    /** 소리가 나지 않은 자동 시도(시작 전에 멈춤·시작과 같은 순간 멈춤)에는 섞인 구간이 없다: 그 앞에 시작한 울음도 보고 이어진 울음이 끊기지 않는다. */
    @Test fun c318_v4_silentAttemptTail_seen() {
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

    /** 직접 튼 소리(요청 재생)는 본다(C303 ①): 요청 재생이 멈춰도 거르지 않는다. */
    @Test fun c318_v4_requestPlayTail_seen() {
        val sim = session().ev(SwitchSound(T0, LL)).started()
        val stop = sim.t + 20_000
        sim.ev(DriverStop(stop, Via.VOICE))
        assertEquals(Phase.WAIT, sim.ses.phase)
        sim.clear().ev(CrySegment(stop + 2_000, stop - 1_000, stop + 2_000, 0.9, true))
        assertEquals(AlarmOnly.WAITING, sim.step<LogAlarm>().single().reason)
        assertEquals(1, sim.ses.cryRuns)
    }

    /** judgeFrom은 판정 창 시작만이다: 입력 복귀(21d) 앞에 시작한 구간은 판정에 넣지 않는다(7b). 재생이 섞이지 않아 울음으로는 본다. */
    @Test fun c318_v4_windowStartOnlyJudges() {
        val sim = session().attempt1().toGap()
        sim.ev(InputLost(sim.t + 1_000, PauseReason.SILENCED)).ev(InputRestored(sim.t + 2_000, PauseReason.SILENCED))
        val from = sim.ses.judgeFrom!!
        assertEquals(sim.t, from)
        val cry = sim.ep.cryMs
        sim.ev(CrySegment(from + 2_000, from - 1_000, from + 2_000, 0.9, false))
        assertEquals(Phase.GAP, sim.ses.phase, "창 시작 앞에 시작한 구간은 판정하지 않는다")
        assertEquals(cry + P.yamnetHopMs, sim.ep.cryMs, "재생이 섞이지 않은 울음이라 울음 시간에는 넣는다")
        sim.ev(CrySegment(from + 3_000, from, from + 3_000, 0.9, false))
        assertEquals(Phase.ATTEMPT, sim.ses.phase, "창 안의 울음은 판정한다(8a)")
    }

    // ---- V10: 재생 사용 시간 = 실제로 멈춘 시각까지 ----

    /** endAt 뒤에 온 재생 끝(오류·복원)은 endAt까지만 센다. 실행기는 endAt에 멈췄다. */
    @Test fun c318_v10_lateEndCountsUntilEndAt() {
        val sim = session().attempt1()
        val pl = sim.play()
        sim.ev(PlaybackEnded(pl.endAt + 10 * MIN, pl.id, EndReason.ERROR))
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertEquals(P.attemptMs, sim.ses.playedMs); assertEquals(P.attemptMs, sim.ep.playedMs)
        val rec = sim.step<SavePlay>().single().record
        assertEquals(pl.endAt, rec.endedAt); assertEquals(P.attemptMs, rec.playedMs)
    }

    /**
     * 복원 ③(r14r): 호스트는 끊긴 재생을 스냅숏의 마지막 시각(②가 돌려준 상태의 now)에 끝낸 사실 사건으로 넣는다(H1, 호스트 몫).
     * 마지막 시도(남은 20초) 5초 뒤에 죽고 10분 뒤 복원해도 상한에 닿지 않는다. 옛 길(복원 시각)은 10분을 더해 capReached였다.
     */
    @Test fun c318_v10_restoreStopsAtSnapshotTime() {
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
    @Test fun c318_v10_sceneEndRestoreStopsAtSceneStart() {
        val sim = session().attempt1()
        val id = sim.play().id
        val eng = Engine()
        val sceneAt = sim.t + 10_000
        val real = eng.sceneStart(sim.s, sim.s, sceneAt).realStep.state   // 호스트와 같다(C319 V9: 장면 시작이 재생을 끊어 보관한다)
        val endAt = sceneAt + 1_230_000
        val st = eng.restoreFromRoom(real, facts(), endAt)   // 장면 끝은 보관한 실제 상태 그대로 복원한다(C322 V12)
        val step = eng.step(st, PlaybackEnded(st.now, id, EndReason.ERROR))
        assertTrue(step.state.session!!.playedMs <= 10_000, "장면 시작 뒤 시간을 세지 않는다: ${step.state.session!!.playedMs}")
    }
}
