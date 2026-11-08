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
    val care: CareView? = null,           // 폰 돌봄 기록 요약(C119). ④ 상태값·오프라인 문장만 읽고 판단에는 쓰지 않는다. 스냅숏 호환 기본값 null
    val careGen: Int = 0,                 // '모든 기록 지우기' 세대. ClearRecords마다 +1. 사건의 gen이 이것과 다르면 그 요약은 지우기 전에 받은 것이라 버린다(C128). 스냅숏 호환 기본값 0
    val bootAt: Long? = null,             // 복원 시각. 복원으로 경로를 비운 뒤 60초는 장시간 제안(L1)이 경로를 기다린다(C128). 스냅숏 호환 기본값 null
    val careRoom: String? = null,         // 지금 `care`를 받은 중계 방. 방이 다르면 seq를 견주지 않는다(C130). 스냅숏 호환 기본값 null
    val careSeq: Long? = null,            // 지금 `care`를 실어 온 중계 seq(장면 주입이면 null). 같은 asOf일 때 견준다(C126 ①). 스냅숏 호환 기본값 null
) {
    companion object
}

@Serializable
data class SteerState(
    val filtered: Double?,
    val lastSampleAt: Long?,
    val driveBusyAt: Long? = null,        // 마지막으로 '바쁨'이던 표본 시각(C107). 스냅숏 호환 기본값 null
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
    val outPauses: Set<PauseReason> = emptySet(),   // 세션 밖에서 받은 입력 끊김 사유. 다음 세션이 이어받는다(21a·21h, C79)
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
    val comfortPending: ComfortPending? = null, // 42a에서 낸 `LowerHvac`의 결과를 기다리는 중(C102). 결과(42g·42h)가 오면 null. 스냅숏 호환을 위해 기본값 null
    val nursingStop: NursingStop?,
    val addedRestIds: Set<String> = emptySet(),
    val waypointPending: WaypointPending?,
    val recordsCleared: Boolean,
    val pendingAlarm: PendingAlarm?,
    val restLog: List<RestLogEntry> = emptyList(),   // 휴식 기록(6.1 session.rest_log): 제안 시각·문장·쉼터·답·경유지
    val playStarted: Boolean = false,    // 이 세션에 PlaybackStarted가 한 번이라도 있었음(output_route null 판단, C85)
    val departChecked: Boolean = false,  // 출발 수유실 제안(F5)을 이번 세션에 이미 봤음. 제안하지 않았어도 참(C117)
    val pendingHandoffAlert: PendingHandoffAlert? = null, // 인계 알림이 그 줄을 낼 때까지 기다리는 중(H10). 스냅숏 호환 기본값 null
    val micPath: MicPath? = null,        // 세션을 열 때의 마이크 경로. 세션 중 바뀌어도 SaveSession 행은 이 값을 유지(C88)
)

@Serializable
data class Play(
    val id: String, val kind: PlayKind,
    val purpose: PlayPurpose,
    val method: Method,
    val startedAt: Long?,                 // null = PlaybackStarted를 기다림
    val endAt: Long,
    val mode: PlayMode,
    val action: SecondAction? = null,     // 2차 시도: 시작할 때 정한 행동(C81). PlaybackStarted는 이 값만 따른다
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
    val waypointAdded: Boolean = false,   // H5: 이 에피소드에 경유지를 넣음(인계와 따로, 6.1 episode.waypoint_added)
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
    val toldDriver: Boolean? = null,      // H11(C130): 인계 문장(S07·S08·S10·S15)을 운전자에게 실제로 냈는가. H1은 null
    val restName: String? = null,         // H11: 실제로 물은 쉼터 이름
)

@Serializable data class PendingAlarm(val alarmAt: Long, val delayFromAt: Long)   // C36
@Serializable data class ComfortPending(val cmdId: String, val tempC: Double, val playId: String)  // S04는 이 cmdId의 ControlResult가 ok로 온 뒤 말한다(C102)
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
    val remainingKm: Double? = null,   // 목적지까지 남은 거리(km). 출발 수유실 제안(F5)용. 스냅숏 호환 기본값 null
)
@Serializable data class CareView(
    val asOf: Long,                       // 폰이 요약을 계산한 시각(epoch ms). 더 늦은 것만 받는다
    val sleepDiaperEnabled: Boolean,      // 거짓이면 잠·기저귀 칸은 모두 null
    val lastSleepStartAt: Long?, val lastSleepEndAt: Long?,
    val sleepingSince: Long?,
    val todaySleepMin: Int?, val nightWakeups: Int?,
    val lastDiaperAt: Long?, val todayDiaperCount: Int?,
    val todayFeedCount: Int,
    val zone: String? = null,             // 요약의 IANA 시간대. ④ '같은 날'을 이 시간대로 본다(C126 ③). 스냅숏 호환 기본값 null = 차 시간대
)
@Serializable data class RestCandidate(
    val id: String, val name: String, val kind: RestKind,
    val distanceKm: Double, val hasNursingRoom: Boolean,
)
@Serializable data class PendingAsk(
    val askId: String, val sid: Sid, val restId: String, val episodeId: String?,
    val hasNursingRoom: Boolean,
)
@Serializable data class PendingHandoffAlert(
    val kind: HandoffKind, val sid: Sid, val askRestId: String?, val queuedAt: Long?,   // null = 옛 줄(스냅숏 호환). null도 같은 줄을 가리키는 값이다
    val minutes: Int?, val sessionId: String, val episodeId: String?, val occurredAt: Long,
    val alert: Boolean = true,                 // false: 폰 알림은 따로 보냈거나 보내지 않고 줄을 냈는지만 따른다(H2·H8, H11)
    val closedRecord: EpisodeRecord? = null,   // 에피소드가 먼저 닫히면 마지막으로 저장한 행(H11: 그 행을 갱신한다)
)
@Serializable data class WaypointPending(
    val cmdId: String, val restId: String, val sessionId: String, val episodeId: String?,
    val fromQa: Boolean = false,
    val restLogIndex: Int? = null,        // 이 요청을 낸 휴식 기록 줄(SessionState.restLog의 위치). 결과는 그 줄에만 붙인다
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
    val songHeard: Boolean = false,       // Q5d: 이 듣기 창이 열린 동안 자장가·부모 노래가 있었다(재생이 먼저 끝나도 가사를 질문으로 받지 않는다)
)
@Serializable data class Line(
    val sid: Sid, val vars: Map<String, String>, val askRestId: String?,
    val qaAnswer: Boolean = false,
    val longDrive: Boolean = false,       // 2시간 제안(S11, 쉼터 없으면 S10). 말하기가 끝나야 longDriveOffered가 선다(L1, C84)
    val playId: String? = null,           // S02~S04: 이 알림을 낸 재생. 그 재생이 계속될 때만 말한다(C15)
    val queuedAt: Long? = null,           // 큐에 넣은 시각. 미룰 수 있는 문장이 holdMaxMs를 넘으면 버린다(C107). 기본값 null = 버리지 않음(스냅숏 호환)
    val sessionId: String? = null,        // 줄을 넣은 세션 id. 다른 세션의 줄이 끝나도 지금 세션 상태는 바꾸지 않는다(C141). null(옛 스냅숏)이면 지금 세션으로 본다
)
/** 휴식 기록 한 줄. sid는 제안 문장(S07·S07_DEPART·S08·S10·S11) 또는 ④ 경유지 요청이면 QA. */
@Serializable data class RestLogEntry(
    val at: Long, val sid: Sid, val restId: String?,
    val answer: YesNo? = null,            // '응/아니' 답. 없으면 아직 답이 없음
    val waypointOk: Boolean? = null,      // 경유지 결과(WaypointResult.ok). 없으면 요청하지 않음·결과 전
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
@Serializable enum class MethodKind { WHITE_NOISE, WOMB, LULLABY, SONG }
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

/** CS1(C126 ①): 새 요약이 지금 값을 바꾸는가. asOf가 늦거나, 같은 초에 둘 다 중계 seq가 있고 새것이 크다. */
fun careReplaces(old: CareView?, oldSeq: Long?, new: CareView, newSeq: Long?, oldRoom: String? = null, newRoom: String? = null): Boolean =
    old == null || new.asOf > old.asOf ||
        (new.asOf == old.asOf && ((oldRoom != null && newRoom != null && oldRoom != newRoom) || (oldSeq != null && newSeq != null && newSeq > oldSeq)))   // 둘 다 방을 알고 다르면 seq 비교가 뜻이 없어 나중에 받은 것(C130). 모르면(옛 스냅숏·장면) 같은 방으로 본다
