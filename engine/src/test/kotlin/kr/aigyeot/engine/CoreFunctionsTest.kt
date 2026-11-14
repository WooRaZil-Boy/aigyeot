package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 같은 규칙을 한 곳(판단 코어 순수 함수)에서 계산한다. 함수마다 단위 시험을 둔다.
class CoreFunctionsTest {
    // ---- 이어진 경보 문턱, '이 구간을 봤나' ----

    @Test fun alarmContinues_twoHops() {
        assertTrue(alarmContinues(T0, T0 + 2 * P.yamnetHopMs))
        assertFalse(alarmContinues(T0, T0 + 2 * P.yamnetHopMs + 1))
    }

    @Test fun sees_attemptPausedAndBeforeJudgeFrom() {
        val sim = session().attempt1()
        val seg = CrySegment(sim.t, sim.t - 3_000, sim.t, 0.9, true)
        assertFalse(sim.s.hears()); assertFalse(sim.s.sees(seg), "자동 시도 재생 중")
        sim.toGap()
        val from = sim.ses.judgeFrom!!
        assertFalse(sim.s.sees(CrySegment(from + 975, from - 1, from + 2_999, 0.9, true)), "판정 창 시작 앞에 시작")
        assertTrue(sim.s.sees(CrySegment(from + 3_000, from, from + 3_000, 0.9, true)))
        val paused = sim.s.copy(car = sim.s.car.copy(pauses = setOf(PauseReason.SILENCED)))
        assertFalse(paused.sees(CrySegment(from + 3_000, from, from + 3_000, 0.9, true)), "판정 멈춤")
    }

    /** 보지 않는 구간은 울음 시간·조향 창 cry_present에도 넣지 않는다. */
    @Test fun unseenSegmentNotInCryMs() {
        val sim = session().attempt1()
        sim.ev(VehicleSample(sim.t, 60.0, false, 0.0, TurnSignal.NONE))   // 조향 창을 연다
        assertEquals(Phase.ATTEMPT, sim.ses.phase)
        val base = sim.ep.cryMs   // 에피소드를 연 때의 값(경보를 이룬 구간)
        sim.ev(CrySegment(sim.t + 3_000, sim.t, sim.t + 3_000, 0.9, true))   // 재생 중 울음(노래 오인일 수 있다)
        assertEquals(base, sim.ep.cryMs, "자동 시도 재생 중 구간은 울음 시간에 넣지 않는다")
        assertFalse(sim.s.steer.window!!.cryPresent, "조향 창도 울음 창으로 보지 않는다")
        sim.toGap()
        val from = sim.ses.judgeFrom!!
        sim.ev(CrySegment(from + 975, from - 2_025, from + 975, 0.1, false))   // 재생 소리가 섞인 구간(조용)
        sim.ev(CrySegment(from + 1_950, from - 1_050, from + 1_950, 0.9, false))
        assertEquals(base, sim.ep.cryMs, "판정 창 시작 앞에 시작한 구간도 넣지 않는다")
        assertEquals(Phase.GAP, sim.ses.phase, "판정도 보지 않는다(같은 기준)")
        sim.ev(CrySegment(from + 3_000, from, from + 3_000, 0.9, false))
        assertEquals(base + P.yamnetHopMs, sim.ep.cryMs, "본 구간은 넣는다")
        assertTrue(sim.s.steer.window!!.cryPresent)
    }

    /** 판정이 '다시 울었다'고 본 한 구간은 이어진 울음이 없으면 하나 더 센다. */
    @Test fun recryCountsOnset() {
        val sim = session().attempt1().toGap()
        assertEquals(1, sim.ses.cryRuns)
        sim.recry()                                                         // 경보 아닌 한 구간(앞 구간 조용)
        assertEquals(Phase.ATTEMPT, sim.ses.phase, "판정은 다시 울음")
        assertEquals(2, sim.ses.cryRuns, "횟수도 하나 더(이어짐 시작)")
        assertEquals(AlarmRun(sim.t, sim.t), sim.ses.alarmRun)
        // 같은 구간이 경보이기도 하면 두 번 세지 않는다
        val two = session().attempt1().toGap()
        val from = two.ses.judgeFrom!!
        two.ev(CrySegment(from + 3_000, from, from + 3_000, 0.9, true))
        assertEquals(2, two.ses.cryRuns)
        // CONFIRM도 같다
        val conf = session().attempt1().toConfirm()
        assertEquals(Phase.CONFIRM, conf.ses.phase)
        conf.recry()
        assertEquals(2, conf.ses.cryRuns)
    }

    // ---- 질문 답의 지금 울음 ----

    @Test fun cryView_orderAndCapHandoff() {
        val sim = session()
        val t = sim.t
        fun view(x: SessionState, mic: Boolean = true, pauses: Set<PauseReason> = emptySet()) =
            sim.s.copy(session = x, car = sim.s.car.copy(micRunning = mic, pauses = pauses)).cryView(t)
        val run = AlarmRun(t - 2_000, t - 500)
        assertEquals(CryView(false, false, t - 2_000), view(sim.ses.withStage(Phase.LISTENING).copy(alarmRun = run)))
        // 상한 인계도 경보가 이어지면 울고 있다(판단 코어가 그 단계에서 울음을 본다)
        val cap = sim.ses.withStage(Phase.HANDOFF).copy(alarmRun = run, episode = EpisodeState("e", EpisodeTrigger.ALARM, t, t, 0, 0, null, null, false,
            HandoffRecord(HandoffCause.SESSION_CAP, HandoffKind.NO_REST), emptyList(), 0, false))
        assertEquals(CryView(false, false, t - 2_000), view(cap))
        assertEquals(CryView(true, false, null), view(sim.ses.withStage(Phase.ATTEMPT).copy(alarmRun = run)), "자동 시도 재생 중")
        assertEquals(CryView(true, false, null), view(sim.ses.copy(alarmRun = run), mic = false), "마이크 꺼짐")
        assertEquals(CryView(true, false, null), view(sim.ses.copy(alarmRun = run), pauses = setOf(PauseReason.MIC_ERROR)), "판정 멈춤")
        assertEquals(CryView(false, true, null), view(sim.ses.withStage(Phase.GAP).copy(alarmRun = run)))
        assertEquals(CryView(false, false, null), view(sim.ses.copy(alarmRun = AlarmRun(t - 5_000, t - 2 * P.yamnetHopMs - 1))), "끊긴 경보")
        assertTrue(sim.s.copy(session = null).cryView(t).unavailable, "세션 밖")
    }

    // ---- 판정 창 다시 열기·말하던 줄 버리기 ----

    @Test fun reopenWindow_gapAndConfirmOnly() {
        val sim = session().attempt1().toGap()
        val st = Engine().reopenWindow(sim.s, sim.t + 5_000)
        assertEquals(sim.t + 5_000, st.session!!.judgeFrom); assertEquals(sim.t + 5_000 + P.gapMs, st.session!!.windowEnd)
        assertEquals(sim.s.now, st.now, "시각은 바꾸지 않는다")
        val listening = session()
        assertEquals(listening.s, Engine().reopenWindow(listening.s, listening.t + 5_000))
    }

    @Test fun dropSpeaking_endsFrontLine() {
        val sim = session()
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(Line(Sid.S12, emptyMap(), null), Line(Sid.S16, emptyMap(), null))))
        val st = Engine().dropSpeaking(sim.s, sim.t + 1_000)
        assertNull(st.speech.speaking); assertEquals(sim.t + 1_000, st.speech.lastDoneAt); assertEquals(listOf(Sid.S16), st.speech.queue.map { it.sid })
    }

    // ---- 복원 ② ----

    @Test fun restoreFromRoom_endedSessionClosesLikeCloseSession() {
        val sim = session()
        val offer = Line(Sid.S08, emptyMap(), "r", sessionId = sim.ses.id)
        val other = Line(Sid.S12, emptyMap(), null)
        sim.s = sim.s.copy(car = sim.s.car.copy(pauses = setOf(PauseReason.SILENCED)), speech = sim.s.speech.copy(queue = listOf(offer, other)))
        val h = listOf(FirstTry(T0, WN, true))
        val st = Engine().restoreFromRoom(sim.s, facts(ended = true, history = h), sim.t)
        assertNull(st.session)
        assertEquals(listOf(Sid.S12), st.speech.queue.map { it.sid }, "제안 문장은 뺀다")
        assertEquals(setOf(PauseReason.SILENCED), st.car.pauses, "남은 끊김은 다음 세션이 이어받는다")
        assertEquals(h, st.history)
        assertEquals(sim.s.now, st.now)
    }

    @Test fun restoreFromRoom_cryLowerBoundAndEndedEpisode() {
        val sim = session().attempt1()
        val e = sim.ep.id
        val st = Engine().restoreFromRoom(sim.s, facts(played = 5_000, alarms = 3, rows = listOf(roomRow(e, sim.ses.id, endedAt = sim.t))), sim.t)
        val x = st.session!!
        assertEquals(3, x.cryRuns, "Room 경보 에피소드 수가 하한")
        assertEquals(5_000, x.playedMs)
        assertEquals(Phase.LISTENING, x.phase); assertNull(x.episode); assertNull(x.play); assertEquals(0, x.attemptNo)
    }

    @Test fun restoreFromRoom_firstResultFromRoom() {   // Room guardian_notified를 상태로 두는 시험은 없다(판단 코어가 그 칸을 갖지 않는다)
        val sim = session().attempt1().toGap()
        val e = sim.ep.id
        val st = Engine().restoreFromRoom(sim.s, facts(rows = listOf(roomRow(e, sim.ses.id, firstResult = TryResult.FAIL))), sim.t)
        assertEquals(TryResult.FAIL, st.session!!.episode!!.first!!.result, "확정 결과 우선")
    }

    // ---- 인계 종류·확정·알림 규칙 ----

    @Test fun isHandoffOf() {
        val l = Line(Sid.S08, emptyMap(), null, sessionId = "s1", handoff = true)
        assertTrue(l.isHandoffOf("s1")); assertFalse(l.isHandoffOf("s2")); assertFalse(l.isHandoffOf(null))
        assertFalse(l.copy(handoff = false).isHandoffOf("s1"))
    }

    @Test fun settle_writesLedgerKind() {
        val a = HandoffLedger("s1", "e1", T0, HandoffCause.TWO_ATTEMPTS, HandoffKind.NO_REST, null, restName = "n 휴게소")
        assertEquals(SettleHandoff("e1", HandoffKind.NO_REST, false, null), a.settle(false), "다시 고른 장부의 종류")
        assertNull(a.copy(kind = HandoffKind.PARKED).settle(false)!!.toldDriver, "PARKED는 null")
        assertEquals("n 휴게소", a.copy(kind = HandoffKind.WAYPOINT_OFFER).settle(true)!!.restName, "말한 경유 제안만 쉼터 이름")
        assertNull(a.settle(true)!!.restName); assertNull(a.copy(kind = HandoffKind.WAYPOINT_OFFER).settle(false)!!.restName)
        assertNull(a.copy(episodeId = null).settle(true), "에피소드가 없으면 쓸 행이 없다")
    }

    @Test fun alertsAtOnceToldShown() {
        assertEquals(setOf(HandoffKind.PARKED, HandoffKind.ARRIVAL_NOTICE), HandoffKind.entries.filter { it.alertsAtOnce() }.toSet(), "PARKED·ARRIVAL_NOTICE는 알림을 바로 보낸다")
        assertTrue(toldShown(true, false)); assertFalse(toldShown(null, false)); assertTrue(toldShown(null, true)); assertFalse(toldShown(false, true))
    }

    // ---- 그 밖의 순수 함수 ----

    @Test fun feedingAndRouteHelpers() {
        assertTrue(laterFeeding(null, T0)); assertTrue(laterFeeding(T0, T0 + 1)); assertFalse(laterFeeding(T0, T0))
        assertEquals(T0 + (180 * MIN * 0.8).toLong(), windowStartExpected(T0, 180))
        assertNull(windowStartExpected(null, 180)); assertNull(windowStartExpected(T0, null))
        assertEquals(T0 + 180 * MIN, hungryAt(T0, 180))
        assertEquals(mapOf("hungryAt" to "${T0 + 1}", "distanceKm" to "12", "restName" to "r 휴게소"), departVars(rest("r", 12.4, true), T0 + 1))
        assertEquals(2, remainingMinutes(RouteView(T0, true, 61, emptyList()))); assertNull(remainingMinutes(null))
        fun banned(spoken: Map<Sid, Long>, now: Long) = offerBanned(spoken, emptyList(), "s", Sid.S07, now)
        assertTrue(banned(mapOf(Sid.S07 to T0), T0 + P.offerRepeatMs - 1)); assertFalse(banned(mapOf(Sid.S07 to T0), T0 + P.offerRepeatMs))
        assertFalse(banned(emptyMap(), T0))
    }

    @Test fun careFreshAt() {
        val c = CareView(T0, false, null, null, null, null, null, null, null, 0)
        assertTrue(c.freshAt(T0 + DAY_MS)); assertFalse(c.freshAt(T0 + DAY_MS + 1))
    }

    @Test fun scoreLabelsDefaults() {
        assertEquals(2.0 / 5.0, methodScore(1, 3))
        assertEquals(T0 - 28 * DAY_MS, P.statSince(T0))
        assertEquals("우리 노래", methodLabel(MethodKind.SONG))   // 말과 화면 이름이 같다
        assertEquals("뱃속 소리", methodLabel(MethodKind.WOMB))
        assertEquals(listOf(MethodKind.WHITE_NOISE, MethodKind.WOMB, MethodKind.LULLABY), DEFAULT_METHODS.map { it.kind })
        assertEquals(setOf(Sid.S07, Sid.S07_DEPART, Sid.S08, Sid.S10, Sid.S11), OFFER_SIDS)
    }

    @Test fun heardTextPlainWord() {
        assertNull(heardText(null)); assertNull(heardText(" 응 ")); assertEquals("그만", heardText("그만"))
        assertEquals("그만해", plainWord("그만해요!")); assertEquals("내가할게", plainWord("내가 할게"))
    }

    @Test fun canAsk() {
        val sim = idle()
        assertTrue(sim.s.canAsk())
        assertFalse(sim.s.copy(speech = sim.s.speech.copy(speaking = "u1")).canAsk(), "말하는 중")
        assertFalse(sim.s.copy(car = sim.s.car.copy(inCall = true)).canAsk(), "통화 중")
        assertFalse(sim.s.copy(car = sim.s.car.copy(gear = Gear.D)).canAsk(), "주행 중")
        assertFalse(sim.s.copy(car = sim.s.car.copy(micRunning = false)).canAsk(), "마이크 꺼짐")
    }
}
