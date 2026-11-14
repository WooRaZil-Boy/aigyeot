package kr.aigyeot.app.ui

import kr.aigyeot.app.exec.HeardText
import kr.aigyeot.app.exec.Said
import kr.aigyeot.app.link.LinkConn
import kr.aigyeot.app.link.LinkStatus
import kr.aigyeot.app.link.LinkedPhone
import kr.aigyeot.app.store.db.OkControl
import kr.aigyeot.app.ui.car3d.Callout3d
import kr.aigyeot.app.ui.car3d.Light3d
import kr.aigyeot.app.ui.car3d.Seat3d
import kr.aigyeot.app.ui.car3d.View3d
import kr.aigyeot.engine.Ambient
import kr.aigyeot.engine.AmbientBack
import kr.aigyeot.engine.AmbientValue
import kr.aigyeot.engine.Area
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.ControlState
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.EpisodeState
import kr.aigyeot.engine.EpisodeTrigger
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.JudgeWindow
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.OfferState
import kr.aigyeot.engine.OpenTrigger
import kr.aigyeot.engine.OutputRoute
import kr.aigyeot.engine.PauseReason
import kr.aigyeot.engine.PendingAsk
import kr.aigyeot.engine.Play
import kr.aigyeot.engine.PlayMode
import kr.aigyeot.engine.PlayPurpose
import kr.aigyeot.engine.QaStep
import kr.aigyeot.engine.QaWindow
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.RouteView
import kr.aigyeot.engine.SessionState
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Stage
import kr.aigyeot.engine.TryOutcome
import kr.aigyeot.engine.TryRecord
import kr.aigyeot.engine.Unjudged
import kr.aigyeot.engine.WaypointPending
import kr.aigyeot.engine.fresh
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val T0 = 1_800_000_000_000L   // 2027-01-15 08:00 UTC = 17:00 서울

/** 홈 투영: 단계마다 눈썹·카드·3D·주행 중 기호가 판단 코어 상태에서 바로 나온다. */
class HomeUiTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun fresh() = EngineState.fresh(1, null, emptyList(), emptyList(), null, 0)
    private val lullaby = Method(MethodKind.LULLABY)
    private fun play(n: Int, started: Long?, m: Method = lullaby) = Play("p$n", if (n == 1) PlayPurpose.AUTO_FIRST else PlayPurpose.AUTO_SECOND, m, started, (started ?: T0) + 56_000, PlayMode.NORMAL)
    private fun ep(first: TryRecord? = null, second: TryRecord? = null, wp: Boolean = false) =
        EpisodeState("E1", EpisodeTrigger.ALARM, T0, T0, 2_400, 0, first, second, false, null, emptyList(), 0, false, wp)
    private fun ses(stage: Stage, episode: EpisodeState? = null, offers: OfferState = OfferState(), controls: ControlState = ControlState()) = SessionState(
        id = "S1", openedAt = T0, trigger = OpenTrigger.DOOR_THEN_D, methods = listOf(lullaby), stage = stage, episode = episode, playedMs = 0,
        offers = offers, controls = controls, s17Said = false, micNoticeArmed = false, recordsCleared = false,
    )
    private fun driving(x: SessionState) = fresh().let { it.copy(session = x, car = it.car.copy(gear = Gear.D, micRunning = true)) }
    private fun ui(s: EngineState?, said: Said? = null, heard: HeardText? = null, stepFrom: Long = 0, done: Set<OkControl> = emptySet(), car: List<CarSpan> = emptyList(), now: Long = T0, lastQa: String? = null) =
        homeUi(s, now, LinkStatus(), said, heard, stepFrom, null, done, car, seoul, lastQa)

    @Test fun beforeDrive_offAndOn() {
        val off = ui(fresh())
        assertEquals("출발 전이에요", off.status); assertEquals(Eyebrow("듣기 꺼짐", Tone.MUTED), off.eyebrow)
        assertTrue(off.cta.board); assertEquals(View3d.CABIN, off.stage.view); assertEquals(Light3d.BASE, off.stage.light)
        val on = ui(fresh().let { it.copy(car = it.car.copy(micRunning = true)) })
        assertEquals(Eyebrow("듣기 켜짐", Tone.ACCENT, live = true), on.eyebrow); assertEquals(Light3d.LISTEN, on.stage.light)
        val reserved = ui(fresh().let { it.copy(ign = it.ign.copy(reserved = true), car = it.car.copy(micRunning = true)) })
        assertEquals("출발하면 듣기 시작해요", reserved.status); assertFalse(reserved.cta.board, "예약되면 물어보기·먹이기")
    }

    private fun noSession(gear: Gear?, mic: Boolean = true, reserved: Boolean = false) =
        fresh().let { it.copy(ign = it.ign.copy(reserved = reserved), car = it.car.copy(gear = gear, micRunning = mic)) }

    @Test fun noSession_drivingD_saysMicOff() {
        val u = ui(noSession(Gear.D))
        assertEquals(MIC_OFF, u.status)
        assertEquals(Eyebrow("듣기 꺼짐", Tone.MUTED), u.eyebrow); assertFalse(u.eyebrow.live)
        assertEquals(Symbol.EAR, u.drive.symbol)
        assertEquals(Light3d.BASE, u.stage.light)
    }

    @Test fun noSession_drivingR_saysMicOff() = assertEquals(MIC_OFF, ui(noSession(Gear.R)).status)

    @Test fun noSession_unknownGear_staysBeforeDrive() = assertEquals("출발 전이에요", ui(noSession(null)).status)

    @Test fun noSession_parkedMicOn_staysBeforeDrive() {
        val u = ui(noSession(Gear.P))
        assertEquals("출발 전이에요", u.status); assertEquals(Eyebrow("듣기 켜짐", Tone.ACCENT, live = true), u.eyebrow)
        assertEquals(Light3d.LISTEN, u.stage.light)
    }

    @Test fun noSession_reservedDriving_saysStartListening() = assertEquals("출발하면 듣기 시작해요", ui(noSession(Gear.D, reserved = true)).status)

    @Test fun detected_beforePlaybackStarts_isAlertAndChild() {
        val u = ui(driving(ses(Stage.Attempt(1, play(1, null)), ep())))
        assertEquals(Eyebrow("울음 감지 · 2.4초", Tone.CHILD), u.eyebrow)
        assertEquals(View3d.REAR, u.stage.view); assertEquals(Light3d.ALERT, u.stage.light); assertFalse(u.stage.ripple)
        assertEquals(Symbol.BABY, u.drive.symbol)
        assertEquals(listOf(StepMark.NOW, StepMark.NONE, StepMark.NONE, StepMark.NONE), (u.card as HomeCard.Track).marks)
    }

    @Test fun firstAttempt_playing_trackTimeAndRipple() {
        val u = ui(driving(ses(Stage.Attempt(1, play(1, T0)), ep())))
        assertEquals(Eyebrow("1차 · 자장가", Tone.METHOD), u.eyebrow)
        assertEquals(Light3d.SOOTHE, u.stage.light); assertTrue(u.stage.ripple); assertEquals(Symbol.LULLABY, u.drive.symbol)
        val front = ui(driving(ses(Stage.Attempt(1, play(1, T0)), ep()).copy(output = OutputRoute.DEFAULT_FALLBACK)))
        assertFalse(front.stage.ripple, "앞좌석 폴백이 있었으면(누적) 뒷자리 소리 결을 그리지 않는다")
        assertEquals("소리를 틀고 있어요", front.status, "폴백이 있었던 세션은 지금 재생의 자리를 모르니 자리를 말하지 않는다")
        val paused = ui(driving(ses(Stage.Attempt(1, play(1, T0).copy(mode = PlayMode.PAUSE)), ep())))
        assertEquals("잠시 소리를 멈췄어요", paused.status, "일시 포커스 상실로 멈춘 동안은 튼다고 쓰지 않는다"); assertFalse(paused.stage.ripple)
        assertEquals(null, Tagged("S0", setOf(1)).of("S1"), "앞 세션의 Room 값은 지금 세션에 쓰지 않는다"); assertEquals(setOf(1), Tagged("S1", setOf(1)).of("S1"))
        val t = u.card as HomeCard.Track
        assertEquals(T0, t.from); assertEquals(56_000L, t.totalMs); assertEquals("자장가", t.subs[0]); assertEquals("", t.subs[2], "2차 소리는 정해진 뒤에만")
    }

    @Test fun gap_thenSecondAfterFail_thenHandoffMarks() {
        val gap = ui(driving(ses(Stage.Gap(1, JudgeWindow(T0, T0 + 8_000)), ep(TryRecord(lullaby, null)))))
        assertEquals(Eyebrow("듣기 틈 · 8초", Tone.ACCENT, live = true), gap.eyebrow)
        assertEquals(listOf(StepMark.DONE, StepMark.NOW, StepMark.NONE, StepMark.NONE), (gap.card as HomeCard.Track).marks)
        assertTrue(gap.stage.listen)
        val noise = Method(MethodKind.WHITE_NOISE)
        val second = ui(driving(ses(Stage.Attempt(2, play(2, T0, noise)), ep(TryRecord(lullaby, null, TryOutcome.Fail)))))
        assertEquals("2차 · 백색소음", second.eyebrow.text)
        assertEquals(listOf(StepMark.FAIL, StepMark.FAIL, StepMark.NOW, StepMark.NONE), (second.card as HomeCard.Track).marks)
        val hand = ui(driving(ses(Stage.Handoff(2), ep(TryRecord(lullaby, null, TryOutcome.Fail), TryRecord(noise, null, TryOutcome.Fail)))))
        assertEquals(Eyebrow("사람에게 넘김", Tone.CHILD), hand.eyebrow)
        assertEquals(listOf(StepMark.FAIL, StepMark.FAIL, StepMark.FAIL, StepMark.NONE), (hand.card as HomeCard.Track).marks, "쉼터 이름을 모르면 달래기 단계, 제안을 하지 않았으면 제안 칸은 비운다")
        assertEquals(Symbol.PIN, hand.drive.symbol)
    }

    // 1차·듣기 틈 두 칸은 1차 결과 하나로 정한다. 1차가 미판정이면 2차 시도에서도 실패로 그리지 않고, 2차 시도 → 틈 → 확인 → 인계 내내 같은 값
    @Test fun track_firstTwoSlots_followFirstResult_fromSecondAttemptToHandoff() {
        val noise = Method(MethodKind.WHITE_NOISE)
        fun marks(st: Stage, e: EpisodeState) = (ui(driving(ses(st, e))).card as HomeCard.Track).marks.take(2)
        val w = JudgeWindow(T0, T0 + 8_000)
        for ((first, want) in listOf(TryRecord(lullaby, null, TryOutcome.NotJudged(Unjudged.FEEDING_WINDOW)) to StepMark.DONE, TryRecord(lullaby, null, TryOutcome.Fail) to StepMark.FAIL)) {
            val two = ep(first, TryRecord(noise, null, TryOutcome.Fail))
            listOf(marks(Stage.Attempt(2, play(2, T0, noise)), ep(first)), marks(Stage.Gap(2, w), two), marks(Stage.Confirm(2, w), two), marks(Stage.Handoff(2), two))
                .forEach { assertEquals(listOf(want, want), it, "1차 ${first.outcome}") }
        }
    }

    // 재생 시작 전(포커스를 받기 전)에는 튼다고 쓰지 않는다. 시작 뒤·일시 정지는 그대로
    @Test fun attempt_beforePlaybackStarts_statusSaysPreparing() {
        assertEquals("소리를 준비하고 있어요", ui(driving(ses(Stage.Attempt(1, play(1, null)), ep()))).status)
        assertEquals("소리를 준비하고 있어요", ui(driving(ses(Stage.Attempt(1, play(1, null)), ep()).copy(output = OutputRoute.DEFAULT_FALLBACK))).status, "자리 없이")
        assertEquals("뒷자리에서 소리를 틀고 있어요", ui(driving(ses(Stage.Attempt(1, play(1, T0)), ep()))).status)
    }

    @Test fun handoff_restOffer_cardAndAskHint_waypointAdded() {
        val rest = RestCandidate("R1", "망향휴게소", RestKind.entries.first(), 7.0, true)
        val offers = OfferState(pendingAsk = PendingAsk("A1", Sid.S07, "R1", "E1", true))
        val s = driving(ses(Stage.Handoff(2), ep(), offers)).let { it.copy(car = it.car.copy(route = RouteView(T0, true, 600, listOf(rest)))) }
        val u = ui(s)
        assertEquals(HomeCard.Rest("망향휴게소", "수유실 · 7km", added = false), u.card)
        assertTrue(u.drive.ask, "쉼터 질문 중이면 '응 / 아니'")
        val added = ui(driving(ses(Stage.Handoff(2), ep(wp = true), OfferState(addedRestIds = setOf("R1")))).let { it.copy(car = it.car.copy(route = RouteView(T0, true, 600, listOf(rest)))) })
        assertEquals("경유지 추가", added.eyebrow.text); assertEquals(true, (added.card as HomeCard.Rest).added)
        // 앞 쉼터(R1)를 넣은 뒤 같은 에피소드에서 다른 쉼터(R2)를 요청해 결과를 기다리는 동안: R2는 아직 넣지 않았다
        val r2 = RestCandidate("R2", "칠곡휴게소", RestKind.entries.first(), 30.0, true)
        val waiting = ui(driving(ses(Stage.Handoff(2), ep(wp = true), OfferState(addedRestIds = setOf("R1"), waypointPending = WaypointPending("C2", "R2", "S1", "E1")))).let { it.copy(car = it.car.copy(route = RouteView(T0, true, 600, listOf(rest, r2)))) })
        assertEquals(HomeCard.Rest("칠곡휴게소", "수유실 · 30km", added = false), waiting.card)
    }

    @Test fun listening_drivingSub_parkedEyebrow() {
        val s = driving(ses(Stage.Listening, offers = OfferState(leftParkAt = T0 - 32 * 60_000))).let { it.copy(car = it.car.copy(cabinC = 23.6)) }
        val u = ui(s)
        assertEquals(Eyebrow("듣는 중", Tone.ACCENT, live = true), u.eyebrow); assertEquals("주행 32분째 · 실내 24℃", u.sub)
        val parked = ui(s.copy(car = s.car.copy(gear = Gear.P)))
        assertEquals(Eyebrow("정차 중", Tone.MUTED), parked.eyebrow); assertNull(parked.sub); assertTrue(parked.cta.askHero)
    }

    @Test fun callouts_onlyWhatTheCarActuallyDid() {
        val base = ses(Stage.Listening)
        assertEquals(emptySet(), ui(driving(base)).stage.callouts)
        assertEquals(emptySet(), ui(driving(ses(Stage.Listening, controls = ControlState(windowLockBefore = emptyMap())))).stage.callouts, "잠금 결과를 받은 영역이 없으면 칩이 없다")
        val locked = ses(Stage.Listening, controls = ControlState(windowLockBefore = mapOf(Area.ROW_2_LEFT to false), comfortDone = true))
        assertEquals(setOf(Callout3d.LOCK), ui(driving(locked)).stage.callouts, "쾌적 조정을 냈어도(comfortDone) 공조를 실제로 낮춘 줄이 없으면 칩이 없다")
        val dimmed = driving(locked).let { it.copy(ign = it.ign.copy(ambient = Ambient.Dimmed("S1", AmbientBack(brightness = AmbientValue(80, 20))))) }
        val hvac = OkControl(kr.aigyeot.app.store.db.ControlApi.HVAC_TEMP, "ROW_1_LEFT")
        val heatL = OkControl(kr.aigyeot.app.store.db.ControlApi.SEAT_HEAT, "ROW_2_LEFT"); val heatR = OkControl(kr.aigyeot.app.store.db.ControlApi.SEAT_HEAT, "ROW_2_RIGHT")
        val all = ui(dimmed, done = setOf(hvac, heatL))
        assertEquals(setOf(Callout3d.LOCK, Callout3d.HVAC, Callout3d.DIM, Callout3d.HEAT), all.stage.callouts)
        assertEquals(setOf(Seat3d.RL), all.stage.sides[Callout3d.HEAT], "가운데 카시트에서 왼쪽만 끄면 칩 선은 왼쪽만")
        assertEquals(listOf("Anchor_Heat_RL"), Callout3d.HEAT.anchorsIn(all.stage))
        assertEquals(listOf("Anchor_Window_RL"), Callout3d.LOCK.anchorsIn(all.stage), "왼쪽만 잠갔으면 뒷창 선도 왼쪽만")
        assertTrue(Callout3d.LOCK.shownAt(all.stage, Seat3d.RL)); assertFalse(Callout3d.LOCK.shownAt(all.stage, Seat3d.RR), "잠그지 못한 쪽 테는 켜지 않는다")
        assertEquals(setOf(Seat3d.RL, Seat3d.RR), ui(dimmed, done = setOf(heatL, heatR)).stage.sides[Callout3d.HEAT])
        val still = kr.aigyeot.app.ui.car3d.Stage3d(callouts = setOf(Callout3d.HEAT))
        assertTrue(Callout3d.HEAT.shownAt(still, Seat3d.RR) && Callout3d.HEAT.shownAt(still, Seat3d.RL), "쪽을 모르는 그림(리포트)은 둘 다")
        assertEquals(emptySet(), ui(fresh(), done = setOf(hvac, heatL)).stage.callouts, "세션이 없으면 칩이 없다")
        assertEquals("실내 온도 낮춤", calloutText(Callout3d.HVAC))
    }

    @Test fun micOff_session_neverSaysListening() {
        // 프로필은 있는데 마이크 권한을 거부한 채 뒷문 → D: 세션은 열리지만 마이크가 돌지 않는다
        val off = driving(ses(Stage.Listening)).let { it.copy(car = it.car.copy(micRunning = false)) }
        val u = ui(off)
        assertEquals(MIC_OFF, u.status); assertEquals(Eyebrow("듣기 꺼짐", Tone.MUTED), u.eyebrow)
        assertEquals(Light3d.BASE, u.stage.light, "듣는 빛을 켜지 않는다"); assertTrue(u.cta.board, "다시 켤 수 있게 '아기 탔어요'")
        val gap = off.copy(session = off.session!!.copy(stage = Stage.Gap(1, JudgeWindow(T0, T0 + 8_000))))
        assertEquals(MIC_OFF, statusText(gap)); assertFalse(stage(gap).listen); assertFalse(eyebrow(gap).live)
        val on = ui(driving(ses(Stage.Listening)))
        assertEquals("아기 소리를 듣고 있어요", on.status); assertEquals(Light3d.LISTEN, on.stage.light)
        // 입력이 끊겨 판정을 멈춘 동안(마이크는 켜짐)도 듣는다고 쓰지 않는다
        val cut = driving(ses(Stage.Listening)).let { it.copy(car = it.car.copy(pauses = setOf(PauseReason.SILENCED))) }
        val c = ui(cut)
        assertEquals(MIC_OFF, c.status); assertTrue(c.pausesWarning); assertEquals(Light3d.BASE, c.stage.light); assertFalse(c.eyebrow.live)
    }

    @Test fun askPick_dropsOldCard_whenCareRemovedOrRoomChanges() {
        val care = CareView(T0 - 60_000, true, null, null, null, null, null, null, null, 0)
        val base = fresh().copy(care = care, careRoom = "R1")
        val open = base.copy(speech = base.speech.copy(qa = QaWindow("L1", QaStep.HEARING, T0 + 8_000, false)))
        val a = AskPick()
        assertNull(a.pick(open, null, T0), "누르지 않고 열린 창(안내 뒤 자동 듣기)이 듣기만 하면 고르지 않는다")
        a.pressed(T0); assertEquals("L1", a.pick(open, null, T0 + 1_000))
        assertEquals("L1", a.pick(base, null, T0 + 9_000), "창이 닫혀도 같은 문맥이면 카드는 남는다")
        assertEquals("L1", a.pick(base.copy(care = care.copy(asOf = T0)), null, T0 + 9_500), "요약이 새로 와도 남는다")
        val old = HeardText("L1", "언제 먹였지?", T0 + 2_000)
        assertNull(a.pick(base.copy(care = null, careRoom = null), old, T0 + 10_000), "owner 해제로 요약이 지워지면 옛 질문·답을 내린다(옛 들은 글로 다시 고르지 않는다)")
        val z = AskPick(); z.pressed(T0); z.pick(open, null, T0)
        assertNull(z.pick(base, HeardText("L1", "몇 번 울었어?", T0 + 2_000), T0 + 6_000, clearedAt = T0 + 5_000), "기록을 지우면 옛 질문·답을 내린다")
        assertNull(z.pick(base, HeardText("L1", "몇 번 울었어?", T0 + 2_000), T0 + 7_000, clearedAt = T0 + 5_000), "지운 전에 들은 글로 다시 고르지 않는다")
        val b = AskPick(); b.pressed(T0); b.pick(open, null, T0)
        assertNull(b.pick(base.copy(careRoom = "R2"), null, T0 + 1_000), "방이 바뀌면 내린다")
        // 안내 뒤 자동 듣기에서 실제로 질문하면 그 창의 질문·답도 카드로
        val c = AskPick()
        val auto = base.copy(speech = base.speech.copy(qa = QaWindow("L9", QaStep.HEARING, T0 + 8_000, false)))
        assertNull(c.pick(auto, null, T0))
        assertEquals("L9", c.pick(auto, HeardText("L9", "몇 번 울었어?", T0 + 3_000), T0 + 3_000))
    }

    @Test fun said_onlyAfterStepChange_andQaGoesToAskCard() {
        val s = driving(ses(Stage.Attempt(1, play(1, T0)), ep()))
        val said = Said(Sid.S02, "뒤에서 울어요. 뒷자리에 자장가 틀었어요.", T0 + 100)
        assertEquals(said, ui(s, said = said, stepFrom = T0).said)
        assertNull(ui(s, said = said, stepFrom = T0 + 200).said, "단계가 바뀐 뒤에 말한 것만")
        val parked = fresh().let { it.copy(session = ses(Stage.Listening), car = it.car.copy(gear = Gear.P, micRunning = true)) }
        val hearingState = parked.copy(speech = parked.speech.copy(qa = QaWindow("L1", QaStep.HEARING, T0 + 8_000, false)))
        val hearing = ui(hearingState, heard = HeardText("L1", "언제 먹였지?", T0), lastQa = "L1")
        assertEquals(HomeCard.Ask(listening = true, question = "언제 먹였지?", answer = null), hearing.card)
        assertEquals(null, ui(hearingState, heard = HeardText("L1", "언제 먹였지?", T0), lastQa = null).card, "안내 뒤 자동 듣기 창(물어보기로 연 창이 아님)은 물어보기 카드가 아니다")
        val answer = Said(Sid.QA, "오후 12시 52분에 먹였어요.", T0 + 3_000)
        val answered = ui(parked, said = answer, heard = HeardText("L1", "언제 먹였지?", T0), lastQa = "L1")
        assertEquals(HomeCard.Ask(false, "언제 먹였지?", "오후 12시 52분에 먹였어요."), answered.card)
        assertNull(answered.said, "질문 답은 말한 문장 줄에 두 번 보이지 않는다")
        // 다시 물어보기: 새 창이 열리면 옛 질문·답을 붙이지 않고, 새 창이 말 없이 닫혀도 옛 답이 돌아오지 않는다
        val again = ui(parked.copy(speech = parked.speech.copy(qa = QaWindow("L2", QaStep.HEARING, T0 + 20_000, false))), said = answer, heard = HeardText("L1", "언제 먹였지?", T0), lastQa = "L2")
        assertEquals(HomeCard.Ask(listening = true, question = null, answer = null), again.card)
        assertEquals(null, ui(parked, said = answer, heard = HeardText("L1", "언제 먹였지?", T0), lastQa = "L2").card)
    }

    @Test fun pills_phoneCountOrConnText() {
        val two = LinkStatus(conn = LinkConn.CONNECTED, phones = listOf(LinkedPhone("a", "owner", null), LinkedPhone("b", "guardian", null)))
        assertEquals(Pills("휴대폰 2대", true, null), homeUi(fresh(), T0, two, null, null, 0, null).pills)
        assertEquals("휴대폰과 연결하는 중", homeUi(fresh(), T0, LinkStatus(conn = LinkConn.CONNECTING), null, null, 0, null).pills.phone)
        assertNull(homeUi(fresh(), T0, LinkStatus(), null, null, 0, null).pills.phone)
    }

    @Test fun dayChart_onlyKnownTodayValues() {
        assertNull(ui(fresh()).day, "아는 값이 없으면 카드 없음")
        val day0 = java.time.Instant.ofEpochMilli(T0).atZone(seoul).toLocalDate().atStartOfDay(seoul).toInstant().toEpochMilli()
        val care = CareView(T0 - 60_000, true, day0 + 60 * 60_000, day0 + 180 * 60_000, null, 485, 1, day0 + 600 * 60_000, 6, 4)
        val s = fresh().copy(care = care, feedingStart = day0 + 772 * 60_000)
        val d = ui(s, car = listOf(CarSpan(day0 + 790 * 60_000, day0 + 832 * 60_000, listOf(day0 + 804 * 60_000)))).day!!
        assertEquals(listOf("잠", "수유", "기저귀", "차에서"), d.lanes.map { it.name })
        assertEquals("8시간 5분", d.lanes[0].value); assertEquals(listOf(60..180), d.lanes[0].blocks.map { it.range })
        assertEquals("4번", d.lanes[1].value); assertEquals(listOf(772), d.lanes[1].dots)
        assertEquals("6번", d.lanes[2].value)
        assertEquals("탑승 42분", d.lanes[3].sub, "세션 구간(중간 정차 포함)이라 주행이 아니라 탑승"); assertEquals("울음 1번", d.lanes[3].value); assertEquals(listOf(804), d.lanes[3].diamonds)
        assertEquals(1020, d.nowMin)
        val stale = ui(fresh().copy(care = care.copy(asOf = day0 - 60_000)))
        assertNull(stale.day, "어제 계산한 요약은 쓰지 않는다")
        assertNull(ui(driving(ses(Stage.Listening)).copy(care = care)).day, "세션 중에는 오늘 하루 카드가 없다")
        // 잠든 중 띠는 요약 계산 시각(as_of)까지만(합계와 같은 기준). 지금(17:00)까지 늘이지 않는다
        val asleep = care.copy(asOf = day0 + 960 * 60_000, sleepingSince = day0 + 900 * 60_000)
        assertEquals(listOf(60..180, 900..960), ui(fresh().copy(care = asleep)).day!!.lanes[0].blocks.map { it.range })
        // 눈금 now(17:00)보다 늦게 계산한 요약(as_of 17:00:10)도 띠 끝이 시작보다 앞서지 않는다
        val late = care.copy(asOf = T0 + 10_000, sleepingSince = T0)
        assertTrue(ui(fresh().copy(care = late)).day!!.lanes[0].blocks.all { it.range.first <= it.range.last && it.ms >= 0 })
    }

    // 툴팁·합계의 길이는 그날 안으로 자른 기록 시각의 차이(두 폰과 같은 규칙). 분으로 내린 칸 차이(42분)가 아니다
    @Test fun dayChart_blockTipAndTotal_useRecordedTimes_notMinuteCells() {
        val day0 = java.time.Instant.ofEpochMilli(T0).atZone(seoul).toLocalDate().atStartOfDay(seoul).toInstant().toEpochMilli()
        fun at(h: Int, m: Int, sec: Int) = day0 + ((h * 60 + m) * 60 + sec) * 1_000L
        val car = ui(fresh(), car = listOf(CarSpan(at(13, 10, 50), at(13, 52, 10), emptyList()))).day!!.lanes.single { it.name == "차에서" }
        assertEquals("탑승 41분", car.sub)
        assertEquals("탑승 13:10–13:52 · 41분", dayBlockTip(car, car.blocks.single()))
        val care = CareView(T0 - 60_000, true, at(1, 0, 50), at(3, 10, 10), null, 129, 1, null, 0, 0)
        val sleep = ui(fresh().copy(care = care)).day!!.lanes.single { it.name == "잠" }
        assertEquals("잠 01:00–03:10 · 2시간 9분", dayBlockTip(sleep, sleep.blocks.single()))
    }

    // 잠 줄 값은 합계가 먼저(길이), 합계가 0분이면 칸이 있을 때 '1분 미만'·없을 때 '0번'("0초"가 아니다)
    @Test fun dayChart_sleepValue_matchesPhones() {
        val day0 = java.time.Instant.ofEpochMilli(T0).atZone(seoul).toLocalDate().atStartOfDay(seoul).toInstant().toEpochMilli()
        fun sleep(c: CareView) = ui(fresh().copy(care = c)).day!!.lanes.single { it.name == "잠" }
        val none = CareView(T0 - 60_000, true, null, null, null, 0, 0, null, 0, 0)
        assertEquals("0번", sleep(none).value); assertTrue(sleep(none).blocks.isEmpty())
        val short = none.copy(lastSleepStartAt = day0 + 60 * 60_000, lastSleepEndAt = day0 + 60 * 60_000 + 40_000)   // 오늘 40초 잠
        assertEquals("1분 미만", sleep(short).value); assertEquals(1, sleep(short).blocks.size)
        val dozing = none.copy(sleepingSince = T0 - 60_000 + 0)   // 막 잠듦(요약 시각에 잠든 중)
        assertEquals("1분 미만", sleep(dozing).value)
        assertEquals("1시간 30분", sleep(none.copy(lastSleepStartAt = day0 + 60 * 60_000, lastSleepEndAt = day0 + 150 * 60_000, todaySleepMin = 90)).value)
        // 차는 오늘 잠 칸을 다 모른다. 요약 시각 뒤에 끝난 잠(12:00–13:11을 13:10:20에 저장)은 마지막 잠에 없어도 합계 70분이 사실
        val unseen = none.copy(todaySleepMin = 70)
        assertTrue(sleep(unseen).blocks.isEmpty()); assertEquals("1시간 10분", sleep(unseen).value)
    }

    // 칸은 그날과 실제로 겹치는 구간만(두 폰 inDay). 자정에 딱 끝난 전날 잠·세션은 오늘 칸(길이 0)이 아니다
    @Test fun dayChart_midnightEnd_isNotTodayBlock() {
        val day0 = java.time.Instant.ofEpochMilli(T0).atZone(seoul).toLocalDate().atStartOfDay(seoul).toInstant().toEpochMilli()
        fun sleep(c: CareView) = ui(fresh().copy(care = c)).day!!.lanes.single { it.name == "잠" }
        val base = CareView(T0 - 60_000, true, day0 - 60 * 60_000, day0, null, 0, 0, null, 0, 0)   // 전날 23:00 → 오늘 00:00
        assertTrue(sleep(base).blocks.isEmpty()); assertEquals("0번", sleep(base).value)
        val over = base.copy(lastSleepEndAt = day0 + 30 * 60_000, todaySleepMin = 30)   // 전날 23:00 → 오늘 00:30
        assertEquals(listOf(0..30), sleep(over).blocks.map { it.range }); assertEquals("30분", sleep(over).value)
        val car = ui(fresh(), car = listOf(CarSpan(day0 - 60 * 60_000, day0, emptyList()))).day
        assertTrue(car?.lanes?.none { it.name == "차에서" } ?: true, "자정에 딱 끝난 세션은 '차에서' 칸이 없다")
    }

    // 칩 자리는 값으로 비교한다(같은 값의 새 배열이면 상태를 바꾸지 않는다)
    @Test fun sameAnchors_comparesByContent() {
        assertTrue(sameAnchors(listOf(floatArrayOf(1f, 2f, 3f), null), listOf(floatArrayOf(1f, 2f, 3f), null)))
        assertFalse(sameAnchors(listOf(floatArrayOf(1f, 2f, 3f)), listOf(floatArrayOf(1f, 2.5f, 3f))))
        assertFalse(sameAnchors(listOf(floatArrayOf(1f, 2f, 3f)), listOf(null)))
        assertFalse(sameAnchors(emptyList(), listOf(null)))
    }

    @Test fun episodeFlow_recryOnlyBeforeAutoSecond_andSecondSuccessCounts() {
        fun play(purpose: kr.aigyeot.shared.PlayPurpose, m: kr.aigyeot.shared.SoundMethod) = kr.aigyeot.shared.PlayEntry(purpose, m, null, "2027-01-15T17:00:00+09:00", "2027-01-15T17:00:56+09:00", 56)
        fun ep(plays: List<kr.aigyeot.shared.PlayEntry>, result: kr.aigyeot.shared.EpisodeResult?, recried: Boolean, second: kr.aigyeot.shared.SecondAttempt?) =
            kr.aigyeot.shared.TimelineEpisode("E", kr.aigyeot.shared.EpisodeTrigger.CRY_ALARM, "2027-01-15T17:00:00+09:00", 2.4, null, 112, plays, result, null, recried, second, null, 30)
        val auto = ep(listOf(play(kr.aigyeot.shared.PlayPurpose.AUTO_FIRST, kr.aigyeot.shared.SoundMethod.LULLABY), play(kr.aigyeot.shared.PlayPurpose.AUTO_SECOND, kr.aigyeot.shared.SoundMethod.WHITE_NOISE)),
            kr.aigyeot.shared.EpisodeResult.FAILURE, true, kr.aigyeot.shared.SecondAttempt(kr.aigyeot.shared.SecondAction.OTHER_SOUND, null, kr.aigyeot.shared.EpisodeResult.SUCCESS))
        assertEquals(listOf("자장가", "다시 울음", "백색소음", "그침"), episodeCard(auto)!!.flow.map { it.text }, "2차에 그쳐도 '그침'")
        val request = ep(listOf(play(kr.aigyeot.shared.PlayPurpose.REQUEST, kr.aigyeot.shared.SoundMethod.WHITE_NOISE), play(kr.aigyeot.shared.PlayPurpose.REQUEST, kr.aigyeot.shared.SoundMethod.WOMB)), null, false, null)
        assertEquals(listOf("백색소음", "뱃속 소리"), episodeCard(request)!!.flow.map { it.text }, "운전자가 소리만 바꾼 것은 울음이 아니다")
    }

    // 정차 홈 에피소드 카드 제목 머리: 울음 없이 운전자가 소리를 바꾼 에피소드(SWITCH_SOUND)는 '울음'이 아니다(리포트 카드·두 폰 triggerText와 같은 낱말)
    @Test fun episodeCard_headByTrigger_switchSoundIsNotCry() {
        fun ep(trigger: kr.aigyeot.shared.EpisodeTrigger, purpose: kr.aigyeot.shared.PlayPurpose) = kr.aigyeot.shared.TimelineEpisode(
            "E", trigger, "2027-01-15T17:00:00+09:00", null, null, 56,
            listOf(kr.aigyeot.shared.PlayEntry(purpose, kr.aigyeot.shared.SoundMethod.WHITE_NOISE, null, "2027-01-15T17:00:00+09:00", "2027-01-15T17:00:56+09:00", 56)),
            null, null, false, null, null, 0)
        val request = episodeCard(ep(kr.aigyeot.shared.EpisodeTrigger.SWITCH_SOUND, kr.aigyeot.shared.PlayPurpose.REQUEST))!!
        val cry = episodeCard(ep(kr.aigyeot.shared.EpisodeTrigger.CRY_ALARM, kr.aigyeot.shared.PlayPurpose.AUTO_FIRST))!!
        assertEquals("소리 바꾸기 요청", request.head)
        assertEquals("울음", cry.head)
        assertEquals("소리 바꾸기 요청", triggerText(kr.aigyeot.shared.EpisodeTrigger.SWITCH_SOUND), "리포트 자세히·띠 시작 표시와 같은 낱말")
    }

    @Test fun textHelpers() {
        assertEquals("방금", agoText(30_000)); assertEquals("1:21", agoText(81 * 60_000L))
        assertEquals("평소 3시간", usualIntervalText(180)); assertEquals("평소 2시간 30분", usualIntervalText(150)); assertEquals("평소 45분", usualIntervalText(45))
        assertEquals("7", formatKm(7.0)); assertEquals("2.5", formatKm(2.5)); assertEquals("12", formatKm(12.4))
        assertEquals(listOf(150f, 430f, 710f), chipXs(listOf(100f, 150f, null), 1000f))
        val four = chipXs(listOf(100f, 400f, 600f, 900f), 1022f)
        assertTrue(four.all { it >= 1022f * 0.15f - 0.01f && it <= 1022f * 0.85f + 0.01f }, "칩 넷도 무대 안: $four")
        val narrow = chipXs(listOf(100f, 400f, 600f, 900f), 840f)
        assertTrue(narrow.all { it in 125f..715f }, "분할 840에서도 무대 안: $narrow")
        assertEquals("오후 2:13", TimeText.clock(14, 13, ClockMode.TEXT)); assertEquals("14:13", TimeText.clock(14, 13, ClockMode.CHART))
        assertEquals("오전 12:05", TimeText.clock(0, 5, ClockMode.TEXT)); assertEquals("오후 12:52", TimeText.clock(12, 52, ClockMode.TEXT))
    }

    // 마지막 수유 카드 값은 ViewModel(feedCard)이 정한다: 간격 시작 눈금은 판단 코어 windowStartExpected(0.8)와 같은 자리
    @Test fun feedCard_meterUsesEngineWindowStart() {
        val f = feedCard(T0 - 90 * 60_000, 180, T0)
        assertEquals("1:30", f.ago); assertFalse(f.fresh)
        assertEquals(90f / 225f, f.fill!!, 1e-4f); assertEquals(144f / 225f, f.windowMark!!, 1e-4f)
        assertEquals("방금", feedCard(T0 - 30_000, 180, T0).ago); assertTrue(feedCard(T0 - 30_000, 180, T0).fresh)
        assertNull(feedCard(T0, null, T0).fill, "간격이 없으면 막대 없음"); assertEquals("", feedCard(null, 180, T0).ago)
        feedCard(T0 + 5_000, 180, T0).let { assertEquals("방금", it.ago); assertTrue(it.fresh); assertEquals(0f, it.fill) }   // 눈금 시각보다 늦은 수유 기록
    }
}
