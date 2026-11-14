package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 판단 코어 타입이 JSON으로 썼다 다시 읽어도 같은지만 본다(전이 시험은 따로 있다).
class ContractTypesTest {
    private val json = Json

    private val state = EngineState(
        now = 1L, rng = 7L, seq = 0L,
        profile = Profile("2026-03", CarSeat.LEFT, 180, false),
        methodsNext = listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.SONG, "s1")),
        history = listOf(FirstTry(5L, Method(MethodKind.LULLABY), true)),
        feedingStart = null, otherGuardians = 0,
        car = CarView(Gear.P, 0L, setOf(Side.LEFT), false, false, null, 19.1, null, mapOf(Area.ROW_2_LEFT to 0)),
        ign = Ignition(false, false, false, emptyList(), null, 10),
        session = null,
        speech = SpeechQueue(null, null, emptyList()),
        steer = SteerState(null, null, null, null, 0, null)
    )

    @Test fun stateRoundTrip() {
        assertEquals(state, json.decodeFromString<EngineState>(json.encodeToString(state)))
    }

    @Test fun sessionStateRoundTrip() {
        val s = state.copy(
            session = SessionState(
                "s-1", 1L, OpenTrigger.DOOR_THEN_D, state.methodsNext,
                stage = Stage.Attempt(1, Play("s-1-1", PlayPurpose.AUTO_FIRST, Method(MethodKind.WHITE_NOISE), null, 56_000, PlayMode.NORMAL)),
                episode = EpisodeState(
                    "e-1", EpisodeTrigger.ALARM, 1L, 1L, 0L, 0L, null, null, false,
                    HandoffRecord(HandoffCause.TWO_ATTEMPTS, HandoffKind.PARKED), emptyList(), 0L, false
                ),
                playedMs = 0L, offers = OfferState(spokenAt = mapOf(Sid.S10 to 3L), leftParkAt = 2L),
                controls = ControlState(windowLockBefore = mapOf(Area.ROW_2_LEFT to true)),
                s17Said = false, micNoticeArmed = false, recordsCleared = false
            ),
            car = state.car.copy(pauses = setOf(PauseReason.SILENCED))
        )
        assertEquals(s, json.decodeFromString<EngineState>(json.encodeToString(s)))
    }

    /** 단계(sealed Stage)가 종류마다 JSON으로 썼다 다시 읽어도 같다. 읽기 투영(phase 등)은 직렬화되지 않는다. */
    @Test fun stageRoundTrip() {
        val pl = Play("s-1-1", PlayPurpose.AUTO_FIRST, Method(MethodKind.WHITE_NOISE), null, 56_000, PlayMode.NORMAL)
        val stages = listOf(
            Stage.Listening,
            Stage.Attempt(1, pl, PendingAlarm(5L, 1L)),
            Stage.Gap(1, JudgeWindow(2L, 8_002L)),
            Stage.Gap(1, null),
            Stage.Confirm(2, JudgeWindow(2L, 30_002L)),
            Stage.Wait(2, 3L, pl.copy(purpose = PlayPurpose.REQUEST)),
            Stage.Handoff(2, 4L)
        )
        stages.forEach { st -> assertEquals(st, json.decodeFromString<Stage>(json.encodeToString(st))) }
        // 재생 종류는 목적에서 계산한다(저장하지 않음): 요청 목적이면 REQUEST, 자동 1·2차면 AUTO. 저장된 스냅숏에 kind 칸이 있어도 읽을 때 버린다(ignoreUnknownKeys)
        assertEquals(PlayKind.AUTO, pl.kind)
        assertEquals(PlayKind.REQUEST, pl.copy(purpose = PlayPurpose.REQUEST).kind)
        // 시도 결과는 한 칸: 미판정 이유는 미판정에만. JSON으로 썼다 읽어도 같다
        val tries =
            listOf(
                TryRecord(pl.method, null),
                TryRecord(pl.method, null, TryOutcome.Success),
                TryRecord(pl.method, null, TryOutcome.NotJudged(Unjudged.FOCUS_LOSS))
            )
        tries.forEach { assertEquals(it, json.decodeFromString<TryRecord>(json.encodeToString(it))) }
        assertEquals(listOf(null, TryResult.SUCCESS, TryResult.UNJUDGED), tries.map { it.result })
        assertEquals(listOf(null, null, Unjudged.FOCUS_LOSS), tries.map { it.unjudged })
        assertEquals(TryOutcome.NotJudged(Unjudged.FOCUS_LOSS), TryRecord.outcomeOf(TryResult.UNJUDGED, Unjudged.FOCUS_LOSS))
        assertEquals(TryOutcome.Fail, TryRecord.outcomeOf(TryResult.FAIL, null))
        assertEquals(false, json.encodeToString(pl).contains("\"kind\":\"AUTO\""), "재생 종류 칸은 쓰지 않는다(음원 method.kind는 따로 있다)")
        assertEquals("""{"type":"GAP","n":1,"window":null}""", json.encodeToString<Stage>(Stage.Gap(1, null)))
    }

    @Test fun comfortPendingRoundTripAndOldSnapshotDefault() {
        val sim = session().ev(CabinTemp(T0, 27.6)).attempt2()
        val withPending = sim.s
        assertEquals(withPending, json.decodeFromString<EngineState>(json.encodeToString(withPending)))
        // 필드가 없는 스냅숏은 comfortPending = null로 읽힌다
        val old = json.encodeToString(withPending).replace(Regex(",?\"comfortPending\":\\{[^}]*\\}"), "")
        assertEquals(null, json.decodeFromString<EngineState>(old).session!!.comfortPending)
    }

    @Test fun eventsRoundTrip() {
        val events = listOf<Event>(
            Tick(1),
            Boot(2, Gear.P, 10),
            CrySegment(3, 0, 3, 0.9, true),
            PlaybackEnded(4, "p", EndReason.STOPPED),
            DriverStop(5, Via.VOICE),
            SwitchSound(6, Method(MethodKind.SONG, "s1"), "l1"),
            VehicleSample(7, 30.0, false, 1.5, TurnSignal.NONE)
        )
        for (e in events) assertEquals(e, json.decodeFromString<Event>(json.encodeToString(e)))
    }

    @Test fun commandsRoundTrip() {
        val commands = listOf<Command>(
            StartPlayback("p", PlayKind.AUTO, Method(MethodKind.LULLABY), 56_000, 2_000),
            ReleaseAudio,
            DeleteRecords,
            SetSeatHeat("c", listOf(Area.ROW_2_LEFT)),
            SendToPhone("handoff_alert", JsonObject(mapOf("kind" to JsonPrimitive("parked")))),
            SaveSteerWindow(SteerWindowRecord("s", 0, 20, false, false, true, 3)),
            SavePlay(PlayRecord("p", "s", null, PlayPurpose.REQUEST, MethodKind.SONG, "s1", null, 9, 0, PlayEndReason.ERROR)),
            SaveSnapshot(state)
        )
        for (c in commands) assertEquals(c, json.decodeFromString<Command>(json.encodeToString(c)))
    }
}
