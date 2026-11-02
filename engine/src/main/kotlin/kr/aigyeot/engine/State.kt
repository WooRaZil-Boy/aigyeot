package kr.aigyeot.engine

import kotlinx.serialization.Serializable

// 계약 2.2. 상태 클래스와 열거값.

@Serializable
data class EngineState(
    val now: Long,
    val rng: Long,
    val seq: Long,
    val profile: Profile?,
    val methodsNext: List<Method>,
    val history: List<FirstTry>,
    val feedingStart: Long?,
    val otherGuardians: Int,
    val car: CarView,
    val ign: Ignition,
    val session: SessionState?,           // null = IDLE
    val speech: SpeechQueue,
    val steer: SteerState,
) {
    companion object
}

@Serializable
data class SteerState(
    val filtered: Double?,
    val lastSampleAt: Long?,
    val extremumDeg: Double?,
    val dir: Int,                         // −1 · 0 · +1
    val window: SteerWindowAcc?,
)

@Serializable
data class SteerWindowAcc(
    val startAt: Long,
    val speedSumKmh: Double, val speedCount: Int,
    val reversals: Int,
    val excluded: Boolean,
    val cryPresent: Boolean, val playing: Boolean,
)

@Serializable
data class CarView(
    val gear: Gear?, val gearSince: Long?,
    val rearDoorOpen: Set<Side>,
    val inCall: Boolean,
    val micRunning: Boolean,
    val micPath: MicPath?,
    val cabinC: Double?,
    val route: RouteView?,
    val seatHeat: Map<Area, Int>,
)

@Serializable
data class Ignition(
    val s16Said: Boolean,
    val doorOpenedSinceP: Boolean,
    val reserved: Boolean,
    val windowLockPending: List<WindowLockPending>,
    val suspendedAt: Long?,
    val driverUserId: Int,
)

@Serializable
data class SessionState(
    val id: String, val openedAt: Long, val trigger: OpenTrigger,
    val methods: List<Method>,
    val phase: Phase,
    val attemptNo: Int,
    val play: Play?,
    val judgeFrom: Long?,
    val windowEnd: Long?,
    val episode: EpisodeState?,
    val pauses: Set<PauseReason>,
    val quietMs: Long,
    val playedMs: Long,
    val capReached: Boolean,
    val driveFrom: Long?,
    val longDriveOffered: Boolean,
    val lastPAt: Long?,
    val carseatFrom: Long,
    val s17Said: Boolean,
    val micNoticeArmed: Boolean,
    val lastOffer: Map<Sid, Long>,
    val pendingAsk: PendingAsk?,
    val windowLockBefore: Map<Area, Boolean>?,
    val frontAudible: Boolean,
    val comfortDone: Boolean,
    val nursingStop: NursingStop?,
    val addedRestIds: Set<String> = emptySet(),
    val waypointPending: WaypointPending?,
    val recordsCleared: Boolean,
    val pendingAlarm: PendingAlarm?,
)

@Serializable
data class Play(
    val id: String, val kind: PlayKind,
    val purpose: PlayPurpose,
    val method: Method,
    val startedAt: Long?,                 // null = PlaybackStarted를 기다림
    val endAt: Long,
    val mode: PlayMode,
)

@Serializable
data class EpisodeState(
    val id: String, val trigger: EpisodeTrigger,
    val startedAt: Long, val alarmAt: Long?, val alarmDelayMs: Long?,   // G36
    val playedMs: Long,
    val first: TryRecord?, val second: TryRecord?,
    val s06Said: Boolean,
    val handoff: HandoffRecord?,
    val laterReasons: List<Unjudged>,
    val cryMs: Long,                      // G37
    val recried: Boolean,                 // C55: 에피소드를 만들 때만 false로 초기화
)

@Serializable
data class TryRecord(
    val method: Method,
    val action: SecondAction?,            // 2차만
    val result: TryResult?,               // null = 아직 정해지지 않음
    val unjudged: Unjudged?,
)

// 필드는 6.1 episode의 handoff_cause·handoff_kind·waypoint_added·guardian_notified에서 가져왔다.
@Serializable
data class HandoffRecord(
    val cause: HandoffCause,
    val kind: HandoffKind?,
    val waypointAdded: Boolean,
    val guardianNotified: Boolean,
)

@Serializable data class PendingAlarm(val alarmAt: Long, val delayFromAt: Long)   // C36
@Serializable data class WindowLockPending(
    val sessionId: String,
    val locking: Map<String, Set<Area>>,
    val queued: Set<Area>,
    val restoring: Map<String, Set<Area>>,
)
@Serializable data class Method(val kind: MethodKind, val songId: String? = null)
@Serializable data class FirstTry(val at: Long, val method: Method, val success: Boolean)
@Serializable data class Profile(
    val birthYm: String, val carseat: CarSeat,
    val feedingIntervalMin: Int?, val hasSibling: Boolean,
    val frontFallbackAllowed: Boolean = false,   // D12
)
@Serializable data class RouteView(
    val at: Long, val hasDestination: Boolean,
    val remainingSec: Int?, val rests: List<RestCandidate>,
)
@Serializable data class RestCandidate(
    val id: String, val name: String, val kind: RestKind,
    val distanceKm: Double, val hasNursingRoom: Boolean,
)
@Serializable data class PendingAsk(
    val askId: String, val sid: Sid, val restId: String, val episodeId: String?,
    val hasNursingRoom: Boolean,
)
@Serializable data class WaypointPending(
    val cmdId: String, val restId: String, val sessionId: String, val episodeId: String?,
    val fromQa: Boolean = false,
)
@Serializable data class NursingStop(
    val restId: String,
    val addedAt: Long?,
    val firstPAt: Long?,
    val confirmShown: Boolean,
)
@Serializable data class SpeechQueue(
    val speaking: String?, val lastDoneAt: Long?, val queue: List<Line>,
    val qa: QaWindow? = null,
)
@Serializable data class QaWindow(
    val listenId: String, val step: QaStep,
    val until: Long,
    val ruleSaid: Boolean,
)
@Serializable data class Line(
    val sid: Sid, val vars: Map<String, String>, val askRestId: String?,
    val qaAnswer: Boolean = false,
)

// ---- 열거(2.2 표) ----
@Serializable enum class Phase { LISTENING, ATTEMPT, GAP, CONFIRM, HANDOFF, WAIT }
@Serializable enum class QaStep { HEARING, THINKING }
@Serializable enum class PauseReason { SILENCED, MIC_ERROR, DETECTOR_TIMEOUT }
@Serializable enum class TryResult { SUCCESS, FAIL, UNJUDGED }
@Serializable enum class AlarmOnly { IN_CALL, FOCUS_DENIED, SESSION_CAP, WAITING }
@Serializable enum class Sid { S02, S03, S04, S06, S07, S07_DEPART, S08, S09, S10, S11, S12, S15, S16, S17, S18, S19, S20, QA }
@Serializable enum class Gear { P, R, N, D }
@Serializable enum class Area { ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT }
@Serializable enum class PlayPurpose { AUTO_FIRST, AUTO_SECOND, REQUEST }
@Serializable enum class Unjudged {
    DRIVER_STOP, CALL, GEAR_P, PARKED_ALARM, SWITCH_SOUND, SESSION_CAP, FEEDING_WINDOW,
    MIC_ERROR, DETECTOR_TIMEOUT, SILENCED, FOCUS_LOSS, SUSPEND, SESSION_END,
}

// ---- 표 밖에서 이름·값이 나온 열거 ----
@Serializable enum class OpenTrigger { DOOR_THEN_D, BUTTON_THEN_D }   // 6.1 session.trigger
@Serializable enum class EpisodeTrigger { ALARM, SWITCH_SOUND }       // 6.1 episode.trigger
@Serializable enum class PlayKind { AUTO, REQUEST }                   // 2.2 Play.kind 주석
@Serializable enum class PlayMode { NORMAL, PAUSE, DUCK }             // 2.2 Play.mode 주석
@Serializable enum class MethodKind { WHITE_NOISE, LULLABY, SONG }
@Serializable enum class CarSeat { LEFT, RIGHT, CENTER }
@Serializable enum class RestKind { REST_AREA, DROWSY_SHELTER }
@Serializable enum class SecondAction { COMFORT, FEEDING, OTHER }     // 2.2 TryRecord.action
@Serializable enum class HandoffCause { TWO_ATTEMPTS, SESSION_CAP, NO_SOUND }                      // 6.1
@Serializable enum class HandoffKind { WAYPOINT_OFFER, ARRIVAL_NOTICE, NO_REST, PARKED }           // 6.1
@Serializable enum class OutputRoute { REAR_ZONE, DEFAULT_FALLBACK }  // 6.1 session.output_route
@Serializable enum class PlayEndReason {                              // 6.1 play_log.end_reason
    DEADLINE, DRIVER_STOP, CALL, GEAR_P, FOCUS_LOSS, SUSPEND, SWITCH_SOUND, ERROR, SESSION_END,
}

// ---- 3장 표 안의 열거 ----
@Serializable enum class Side { LEFT, RIGHT }
@Serializable enum class MicPath { HOME_BUTTON, NOTICE, APP_LAUNCH }
@Serializable enum class FocusChange { LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK, GAIN }
@Serializable enum class RejectReason { FOCUS_DENIED, ERROR, NO_REAR_OUTPUT }
@Serializable enum class EndReason { DEADLINE, STOPPED, ERROR }
@Serializable enum class YesNo { YES, NO, NONE }
@Serializable enum class ControlKind { SEAT_HEAT, WINDOW_LOCK, READING_LIGHT, HVAC_TEMP }
@Serializable enum class Via { TOOL, VOICE, SCENARIO }
@Serializable enum class BabyAboardFrom { HOME, NOTICE }
@Serializable enum class FeedingFrom { CAR_SCREEN, PHONE }
@Serializable enum class TurnSignal { NONE, LEFT, RIGHT }             // 값은 계약에 없음
@Serializable enum class Notice { BABY_ABOARD, FEEDING_CONFIRM, SAFETY_CONTROL_FAILED, MIC_STOPPED }  // 4.3
