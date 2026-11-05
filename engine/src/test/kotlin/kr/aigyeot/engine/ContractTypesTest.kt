package kr.aigyeot.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

// 계약 타입이 JSON으로 썼다 다시 읽어도 같은지만 본다(전이 시험은 갈래 ②).
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
        steer = SteerState(null, null, null, null, 0, null),
    )

    @Test fun stateRoundTrip() {
        assertEquals(state, json.decodeFromString<EngineState>(json.encodeToString(state)))
    }

    @Test fun sessionStateRoundTrip() {
        val s = state.copy(session = SessionState(
            "s-1", 1L, OpenTrigger.DOOR_THEN_D, state.methodsNext, Phase.ATTEMPT, 0,
            Play("s-1-1", PlayKind.AUTO, PlayPurpose.AUTO_FIRST, Method(MethodKind.WHITE_NOISE), null, 56_000, PlayMode.NORMAL),
            null, null,
            EpisodeState("e-1", EpisodeTrigger.ALARM, 1L, 1L, 0L, 0L, null, null, false,
                HandoffRecord(HandoffCause.TWO_ATTEMPTS, HandoffKind.PARKED, false, false), emptyList(), 0L, false),
            setOf(PauseReason.SILENCED), 0L, 0L, false, null, false, null, 0L, false, false,
            mapOf(Sid.S10 to 3L), null, mapOf(Area.ROW_2_LEFT to true), false, false, null, null,   // comfortDone, comfortPending, nursingStop
            waypointPending = null, recordsCleared = false, pendingAlarm = null,
        ))
        assertEquals(s, json.decodeFromString<EngineState>(json.encodeToString(s)))
    }

    @Test fun comfortPendingRoundTripAndOldSnapshotDefault() {
        val sim = session().ev(CabinTemp(T0, 27.6)).attempt2()
        val withPending = sim.s
        assertEquals(withPending, json.decodeFromString<EngineState>(json.encodeToString(withPending)))
        // 필드가 없던 옛 스냅숏은 comfortPending = null로 읽힌다
        val old = json.encodeToString(withPending).replace(Regex(",?\"comfortPending\":\\{[^}]*\\}"), "")
        assertEquals(null, json.decodeFromString<EngineState>(old).session!!.comfortPending)
    }

    @Test fun eventsRoundTrip() {
        val events = listOf<Event>(
            Tick(1), Boot(2, Gear.P, 10),
            CrySegment(3, 0, 3, 0.9, true), PlaybackEnded(4, "p", EndReason.STOPPED),
            DriverStop(5, Via.VOICE), SwitchSound(6, Method(MethodKind.SONG, "s1"), "l1"),
            VehicleSample(7, 30.0, false, 1.5, TurnSignal.NONE),
        )
        for (e in events) assertEquals(e, json.decodeFromString<Event>(json.encodeToString(e)))
    }

    @Test fun commandsRoundTrip() {
        val commands = listOf<Command>(
            StartPlayback("p", PlayKind.AUTO, Method(MethodKind.LULLABY), 56_000, 2_000),
            ReleaseAudio, DeleteRecords,
            SetSeatHeat("c", listOf(Area.ROW_2_LEFT)),
            SendToPhone("handoff_alert", JsonObject(mapOf("kind" to JsonPrimitive("parked")))),
            SaveSteerWindow(SteerWindowRecord("s", 0, 20, false, false, true, 3)),
            SavePlay(PlayRecord("p", "s", null, PlayPurpose.REQUEST, MethodKind.SONG, "s1", null, 9, 0, PlayEndReason.ERROR)),
            SaveSnapshot(state),
        )
        for (c in commands) assertEquals(c, json.decodeFromString<Command>(json.encodeToString(c)))
    }
}
