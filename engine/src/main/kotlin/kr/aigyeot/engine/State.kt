package kr.aigyeot.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 상태 클래스와 열거값. 상태를 읽는 규칙 함수는 Rules.kt.

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
    val session: SessionState?, // null = IDLE
    val speech: SpeechQueue,
    val steer: SteerState,
    val care: CareView? = null, // 폰 돌봄 기록 요약. 질문 답의 상태값·오프라인 문장만 읽고 판단에는 쓰지 않는다. 스냅숏 호환 기본값 null
    val bootAt: Long? = null, // 복원 시각. 복원으로 경로를 비운 뒤 60초는 장시간 제안이 경로를 기다린다. 스냅숏 호환 기본값 null
    val careRoom: String? = null, // 지금 `care`를 받은 중계 방. 방이 다르면 seq를 견주지 않는다. 스냅숏 호환 기본값 null
    val handoffs: List<HandoffLedger> = emptyList(), // 인계 알림 장부 하나. 열린 세션의 것(세션마다 하나)과 닫힐 때 말하던 줄의 것을 세션 id로 가른다. 만든 순서
    // 지금 `care`를 실어 온 중계 seq(장면 주입이면 null). 같은 asOf일 때 견준다. 스냅숏 호환 기본값 null
    val careSeq: Long? = null
) {
    companion object
}

@Serializable
data class SteerState(
    val filtered: Double?,
    val lastSampleAt: Long?,
    val driveBusyAt: Long? = null, // 마지막으로 '바쁨'이던 표본 시각. 스냅숏 호환 기본값 null
    val extremumDeg: Double?,
    val dir: Int, // −1 · 0 · +1
    val window: SteerWindowAcc?
)

@Serializable
data class SteerWindowAcc(
    val startAt: Long,
    val speedSumKmh: Double,
    val speedCount: Int,
    val reversals: Int,
    val excluded: Boolean,
    val cryPresent: Boolean,
    val playing: Boolean,
    // 창 동안 sees/hears가 거짓인 때가 있었으면 false
    val observed: Boolean = true
)

@Serializable
data class CarView(
    val gear: Gear?,
    val gearSince: Long?,
    val rearDoorOpen: Set<Side>,
    val inCall: Boolean,
    val micRunning: Boolean,
    val micPath: MicPath?,
    val cabinC: Double?,
    val route: RouteView?,
    val seatHeat: Map<Area, Int>,
    // 입력 끊김 사유(판정 멈춤) 하나. 세션 안팎이 같은 칸이라 다음 세션이 그대로 이어받는다
    val pauses: Set<PauseReason> = emptySet()
)

@Serializable
data class Ignition(
    val s16Said: Boolean,
    val doorOpenedSinceP: Boolean,
    val reserved: Boolean,
    val windowLockPending: List<WindowLockPending>,
    val suspendedAt: Long?,
    val driverUserId: Int,
    val boardNotice: Boolean = false, // 세션 밖 '아기가 탔나요?' 알림이 서 있음. 마이크가 켜지거나 세션이 열리거나 새 시동이면 꺼진다. 주인 Life
    val nursingStop: NursingStop? = null, // 수유실 들름은 시동 단위. 세션이 닫혀도 수유 확인이 뜬다
    // 앰비언트 조명. 시동 단위 한 칸. null = 손대지 않음. 주인 Control, 쓰기는 ControlReducer.kt의 private ambient(next) 하나
    val ambient: Ambient? = null
)

@Serializable
data class SessionState(
    val id: String,
    val openedAt: Long,
    val trigger: OpenTrigger,
    val methods: List<Method>,
    val stage: Stage, // 단계. 시도 번호·재생·판정 창·조용함·경보 대기는 단계 안에만 있다. 쓰는 곳은 Tx.enter 하나
    val episode: EpisodeState?, // LISTENING이면 null(enter가 보장)
    val playedMs: Long, // 세션 재생 누적(줄지 않는다). 쌓은 값이라 저장한다. 상한 도달은 capReached(p)로 계산
    val offers: OfferState = OfferState(), // 주인 Offer. 쓰기는 OfferReducer.kt의 private offers {} 하나
    val controls: ControlState = ControlState(), // 주인 Control. 쓰기는 ControlReducer.kt의 private controls {} 하나
    val s17Said: Boolean, // 읽는 곳은 시험뿐. 지우면 그 단언의 기대값 표현이 바뀌어 남긴다
    val micNoticeArmed: Boolean,
    val recordsCleared: Boolean,
    val restLog: List<RestLogEntry> = emptyList(), // 휴식 기록(session.rest_log): 제안 시각·문장·쉼터·답·경유지
    val output: OutputRoute? = null, // 이 세션 재생의 출력(session.output_route). 시작한 재생이 없으면 null, 앞좌석 폴백이 한 번이라도 있었으면 DEFAULT_FALLBACK
    val departChecked: Boolean = false, // 출발 수유실 제안을 이번 세션에 이미 봤음. 제안하지 않았어도 참
    val micPath: MicPath? = null, // 세션을 열 때의 마이크 경로. 세션 중 바뀌어도 SaveSession 행은 이 값을 유지
    val cryRuns: Int = 0, // 이번 세션에서 감지한 이어진 울음 수(질문 '몇 번 울었어'. alarmRun이 새로 열릴 때마다 하나). 기록 지우기 때 0. 스냅숏 호환 기본값 0
    val alarmRun: AlarmRun? = null, // 지금 세션의 이어진 경보. 질문 답의 울음 상태값이 에피소드 종류가 아니라 이것으로 본다. 스냅숏 호환 기본값 null
    // 소리가 난 마지막 자동 시도 재생이 멈춘 시각(재생 종료 기록의 stopAt). 이보다 앞에 시작한 구간은 재생 소리가 섞여 sees()가 거른다.
    // 판정 창 시작(judgeFrom)과 다른 칸. 쓰는 곳은 playEnd 하나
    val mixedUntil: Long? = null
) {
    // 읽기 투영: 직렬화되지 않는 멤버 getter. 앱·장면 expect.state·시험이 그대로 읽는다
    val phase: Phase get() = stage.phase
    val attemptNo: Int get() = stage.n
    val play: Play? get() = stage.play
    val judgeFrom: Long? get() = stage.window?.from
    val windowEnd: Long? get() = stage.window?.end
    val quietMs: Long get() = stage.quietMs
    val pendingAlarm: PendingAlarm? get() = (stage as? Stage.Attempt)?.alarm
    val carseatFrom: Long get() = openedAt // 카시트 시작은 늘 세션 시작(Qa.kt)
    val frontAudible: Boolean get() = output == OutputRoute.DEFAULT_FALLBACK // 장면 측정(SceneRun)

    // 읽기 투영: 묶음 칸(offers·controls)을 평평한 이름으로 읽는다. 쓰기는 주인 파일의 private 쓰기 함수뿐
    val spokenAt: Map<Sid, Long> get() = offers.spokenAt
    val pendingAsk: PendingAsk? get() = offers.pendingAsk
    val waypointPending: WaypointPending? get() = offers.waypointPending
    val addedRestIds: Set<String> get() = offers.addedRestIds
    val driveFrom: Long? get() = offers.driveFrom
    val longDriveOffered: Boolean get() = offers.longDriveOffered
    val leftParkAt: Long? get() = offers.leftParkAt
    val windowLockBefore: Map<Area, Boolean>? get() = controls.windowLockBefore
    val comfortDone: Boolean get() = controls.comfortDone
    val comfortPending: ComfortPending? get() = controls.comfortPending
}

/** 제안 상태(주인 Offer). 쉼터 제안·질문·경유지·장시간 주행. */
@Serializable data class OfferState(
    val spokenAt: Map<Sid, Long> = emptyMap(), // 제안 문장을 실제로 말한 시각만(offerSpoken 한 곳). 10분 금지는 이 칸과 큐로 offerBanned가 계산한다
    val pendingAsk: PendingAsk? = null, // 쉼터 질문의 답 기다림
    val waypointPending: WaypointPending? = null, // 경유지 결과 기다림
    val addedRestIds: Set<String> = emptySet(), // 이 세션에 넣은 경유지
    val driveFrom: Long? = null, // 장시간 주행을 세는 시작. P에 오래 서면 비운다
    val longDriveOffered: Boolean = false, // 2시간 제안을 실제로 말했다
    // P에서 나온 시각(이번 주행의 시작)
    val leftParkAt: Long? = null
)

/** 차량 제어 상태(주인 Control). */
@Serializable data class ControlState(
    val windowLockBefore: Map<Area, Boolean>? = null, // 잠그기 전 뒷창 잠금 값. 세션이 닫힐 때 되돌린다
    val comfortDone: Boolean = false, // 이 세션에 쾌적 조정을 했다
    // 2차 시도에서 낸 `LowerHvac`의 결과를 기다리는 중. 결과가 오면 null
    val comfortPending: ComfortPending? = null
)

/** 앰비언트 조명 칸. sessionId = 낮춘 세션: 되돌림 cmdId·control_log·리포트 기다림이 이 세션 몫이다 */
@Serializable sealed interface Ambient {
    val sessionId: String

    /** `DimAmbient`를 냈고 결과 둘(밝기·색)을 기다린다. got = 이미 온 결과 중 바꾸고 확인된 값 */
    @Serializable
    @SerialName("DIMMING")
    data class Dimming(override val sessionId: String, val cmdId: String, val got: AmbientBack = AmbientBack(), val pending: Set<ControlKind>) : Ambient

    /** 낮춤이 확인됐다. back이 비어 있으면 바꾼 것이 없다 */
    @Serializable
    @SerialName("DIMMED")
    data class Dimmed(override val sessionId: String, val back: AmbientBack) : Ambient

    /** `RestoreAmbient`를 냈고 back에 있는 종류의 결과를 기다린다(이 동안 새 낮춤을 내지 않는다) */
    @Serializable
    @SerialName("RESTORING")
    data class Restoring(override val sessionId: String, val cmdId: String, val back: AmbientBack, val pending: Set<ControlKind>) : Ambient
}

@Serializable data class AmbientBack(val brightness: AmbientValue? = null, val color: AmbientValue? = null) {
    val any: Boolean get() = brightness != null || color != null

    /** 두 종류 모두 쓴 값을 안다. 아니면 확인하지 못했거나 쓰지 않은 종류가 있어, 쓰기 직전 남기는 실행기 장부가 되돌릴 값을 알 수 있다(RestoreAmbientLedger). */
    val complete: Boolean get() = brightness != null && color != null
}

@Serializable data class AmbientValue(val before: Int, val set: Int) // 읽어 둔 원래 값·넣어 반영된 값. 색은 24비트 RGB(`and 0xFFFFFF`, 알파 뺌)

/** 화면 '조명 낮춤' 배지. 읽기 투영(저장 안 함): 낮춤이 확인된 동안만 참 */
val EngineState.ambientDimmed: Boolean get() = (ign.ambient as? Ambient.Dimmed)?.back?.any == true

/** 세션 재생 상한에 닿았다. playedMs는 줄지 않아 저장하지 않고 계산한다. */
fun SessionState.capReached(p: Params): Boolean = playedMs >= p.sessionPlayCapMs

/** 이어진 경보의 처음·마지막 경보 시각. 경보가 문턱(두 걸음, [alarmContinues])보다 오래 끊기면 다음 경보가 새로 연다. */
@Serializable data class AlarmRun(val from: Long, val last: Long)

/**
 * 복원 ②(Room 대조)에 호스트가 넘기는 Room 사실. 판단 코어는 Room을 모른다.
 * [episodes]는 상태가 가리키는 에피소드 id(열린 에피소드·인계 장부)의 Room 행(없으면 빠짐). [told]는 그중 인계가 Room에서 이미 확정된 행의
 * handoff_told_driver(null이 아닌 것만). [history]는 Room에서 다시 센 28일 1차 시도.
 */
data class RoomFacts(
    val sessionEnded: Boolean,
    val sessionPlayedMs: Long,
    val alarmEpisodes: Int,
    val episodePlayedMs: Long,
    val episodes: Map<String, EpisodeRecord>,
    val history: List<FirstTry>,
    val told: Map<String, Boolean> = emptyMap()
)

@Serializable
data class Play(
    val id: String,
    val purpose: PlayPurpose,
    val method: Method,
    val startedAt: Long?, // null = PlaybackStarted를 기다림
    val endAt: Long,
    val mode: PlayMode,
    val action: SecondAction? = null, // 2차 시도: 시작할 때 정한 행동. PlaybackStarted는 이 값만 따른다
    // 질문 답 도구로 연 요청 재생. 거절은 지금 질문 창이 아니라 이 값을 본다
    val fromQa: Boolean = false
) {
    /** 자동 시도·요청. 목적에서 계산한다(저장하지 않는다). */
    val kind: PlayKind get() = if (purpose == PlayPurpose.REQUEST) PlayKind.REQUEST else PlayKind.AUTO
}

@Serializable
data class EpisodeState(
    val id: String,
    val trigger: EpisodeTrigger,
    val startedAt: Long,
    val alarmAt: Long?,
    val alarmDelayMs: Long?,
    val playedMs: Long,
    val first: TryRecord?,
    val second: TryRecord?,
    val s06Said: Boolean,
    val handoff: HandoffRecord?,
    val laterReasons: List<Unjudged>,
    val cryMs: Long,
    val recried: Boolean, // 에피소드를 만들 때만 false로 초기화
    // 이 에피소드에 경유지를 넣음(인계와 따로, episode.waypoint_added)
    val waypointAdded: Boolean = false
)

@Serializable
data class TryRecord(
    val method: Method,
    val action: SecondAction?, // 2차만
    // null = 아직 정해지지 않음. 결과와 미판정 이유가 한 칸이라 '그침인데 미판정 이유' 같은 조합이 생기지 않는다
    val outcome: TryOutcome? = null
) {
    /** 읽기 투영(기록·화면이 읽는 이름 그대로). */
    val result: TryResult? get() = when (outcome) {
        TryOutcome.Success -> TryResult.SUCCESS
        TryOutcome.Fail -> TryResult.FAIL
        is TryOutcome.NotJudged -> TryResult.UNJUDGED
        null -> null
    }
    val unjudged: Unjudged? get() = (outcome as? TryOutcome.NotJudged)?.reason

    companion object {
        /** Room 행(결과·이유 두 칸) → 결과 하나. 복원 대조(Room 사실)에서만 쓴다. */
        fun outcomeOf(result: TryResult?, unjudged: Unjudged?): TryOutcome? = when (result) {
            TryResult.SUCCESS -> TryOutcome.Success
            TryResult.FAIL -> TryOutcome.Fail
            TryResult.UNJUDGED -> TryOutcome.NotJudged(unjudged)
            null -> null
        }
    }
}

/** 시도 결과: 그침 · 실패 · 미판정(이유). 이유는 미판정에만 있다. */
@Serializable sealed interface TryOutcome {
    @Serializable
    @kotlinx.serialization.SerialName("SUCCESS")
    data object Success : TryOutcome

    @Serializable
    @kotlinx.serialization.SerialName("FAIL")
    data object Fail : TryOutcome

    /** [reason]은 판단 코어가 정할 때 늘 있다. Room 행에서 되살릴 때만 비어 있을 수 있다. */
    @Serializable
    @kotlinx.serialization.SerialName("UNJUDGED")
    data class NotJudged(val reason: Unjudged?) : TryOutcome
}

// 필드는 episode 행의 handoff_cause·handoff_kind. 확정 칸(말함·쉼터 이름)은 SettleHandoff, guardian_notified는 link, 경유지는 EpisodeState.waypointAdded
@Serializable
data class HandoffRecord(val cause: HandoffCause, val kind: HandoffKind?)

@Serializable data class PendingAlarm(val alarmAt: Long, val delayFromAt: Long)

// S04는 이 cmdId의 ControlResult가 ok로 온 뒤 말한다
@Serializable data class ComfortPending(val cmdId: String, val tempC: Double, val playId: String)

@Suppress("ktlint:standard:class-signature")
@Serializable
data class WindowLockPending(
    val sessionId: String,
    val locking: Map<String, Set<Area>>,
    val queued: Set<Area>,
    val restoring: Map<String, Set<Area>>
)

@Serializable data class Method(val kind: MethodKind, val songId: String? = null)

@Serializable data class FirstTry(val at: Long, val method: Method, val success: Boolean)

@Suppress("ktlint:standard:class-signature")
@Serializable
data class Profile(
    val birthYm: String,
    val carseat: CarSeat,
    val feedingIntervalMin: Int?,
    val hasSibling: Boolean,
    val frontFallbackAllowed: Boolean = false
)

@Serializable data class RouteView(
    val at: Long,
    val hasDestination: Boolean,
    val remainingSec: Int?,
    val rests: List<RestCandidate>,
    val remainingKm: Double? = null, // 목적지까지 남은 거리(km). 출발 수유실 제안용. 스냅숏 호환 기본값 null
    // RouteState.restsPending이 처음 참이 된 시각(참인 동안 유지). null = 기다리지 않음. 스냅숏 호환 기본값 null
    val restsPendingSince: Long? = null
)

@Serializable data class CareView(
    val asOf: Long, // 폰이 요약을 계산한 시각(epoch ms). 더 늦은 것만 받는다
    val sleepDiaperEnabled: Boolean, // 거짓이면 잠·기저귀 칸은 모두 null
    val lastSleepStartAt: Long?,
    val lastSleepEndAt: Long?,
    val sleepingSince: Long?,
    val todaySleepMin: Int?,
    val nightWakeups: Int?,
    val lastDiaperAt: Long?,
    val todayDiaperCount: Int?,
    val todayFeedCount: Int,
    // 요약의 IANA 시간대. 질문 답이 '같은 날'을 이 시간대로 본다. 스냅숏 호환 기본값 null = 차 시간대
    val zone: String? = null
) {
    /** 24시간 안의 요약만 질문 답이 쓴다. 받기(LinkRoom)와 상태값(Qa)이 같이 쓴다. */
    fun freshAt(now: Long): Boolean = now - asOf <= DAY_MS
}

@Serializable data class RestCandidate(
    val id: String,
    val name: String,
    val kind: RestKind,
    val distanceKm: Double,
    val hasNursingRoom: Boolean,
    /** 이름만으로 경유지 줄이 하나로 정해져 넣을 수 있는가(RestAreas.placeable). 기본 true(스냅숏·장면 JSON에 없을 때). 넣을 수 없는 쉼터는 제안하지 않는다. */
    val addable: Boolean = true
)

@Serializable data class PendingAsk(
    val askId: String,
    val sid: Sid,
    val restId: String,
    val episodeId: String?,
    val hasNursingRoom: Boolean,
    // 질문을 낼 때 적은 휴식 기록 줄(SessionState.restLog의 위치). 답은 그 줄에만 붙인다
    val restLogIndex: Int? = null
)

/**
 * 인계 알림 장부. 묶인 줄은 `Line.handoff` 표시와 [sessionId]로 찾는다. kind·minutes·restName은 묶인 줄을 낼 때(또는 바꿀 때) 그 줄에서 채운다.
 * 세션이 닫혀도 그 줄의 SpeechDone까지 같은 칸에 남는다. 확정은 [settle] → SettleHandoff 하나다(Room 행 사본을 들지 않는다).
 */
@Serializable data class HandoffLedger(
    val sessionId: String,
    val episodeId: String?,
    val occurredAt: Long,
    val cause: HandoffCause,
    val kind: HandoffKind,
    val minutes: Int?,
    val alert: Boolean = true, // 이 장부가 폰 알림을 보낼 차례. false: 바로 보냈거나 받는 보호자가 없었다
    val utteranceId: String? = null, // 묶은 줄을 낸 발화 id. 그 SpeechDone(spoken = true)에서 알림·확정
    // 그 줄을 낼 때의 쉼터 이름
    val restName: String? = null
)

@Serializable data class WaypointPending(
    val cmdId: String,
    val restId: String,
    val sessionId: String,
    val episodeId: String?,
    val fromQa: Boolean = false,
    val restLogIndex: Int? = null, // 이 요청을 낸 휴식 기록 줄(SessionState.restLog의 위치). 결과는 그 줄에만 붙인다
    // 요청할 때의 쉼터 이름(S09). 결과가 올 때 쉼터 목록이 비어(다시 받는 중) 있어도 id를 읽지 않게. 스냅숏 호환 기본값 null
    val restName: String? = null
)

/**
 * 수유실 들름 단계: 요청 → 경유지 들어감 → 그 뒤 첫 P(수유 확인은 이 단계에서만). 앞 단계의 칸 없이 뒤 단계 칸이 생기지 않는다.
 * 읽는 쪽은 addedAt·firstPAt·confirmShown 이름 그대로 읽는다(단계에 없으면 null·false).
 */
@Serializable sealed interface NursingStop {
    val restId: String
    val addedAt: Long? get() = null
    val firstPAt: Long? get() = null
    val confirmShown: Boolean get() = false

    @Serializable
    @kotlinx.serialization.SerialName("REQUESTED")
    data class Requested(override val restId: String) : NursingStop

    @Serializable
    @kotlinx.serialization.SerialName("ADDED")
    data class Added(override val restId: String, override val addedAt: Long) : NursingStop

    @Serializable
    @kotlinx.serialization.SerialName("PARKED")
    data class Parked(override val restId: String, override val addedAt: Long, override val firstPAt: Long, override val confirmShown: Boolean = false) :
        NursingStop
}

@Serializable data class SpeechQueue(val speaking: String?, val lastDoneAt: Long?, val queue: List<Line>, val qa: QaWindow? = null)

@Serializable data class QaWindow(
    val listenId: String,
    val step: QaStep,
    val until: Long,
    val ruleSaid: Boolean,
    val songHeard: Boolean = false, // 이 듣기 창이 열린 동안 자장가·부모 노래가 있었다(재생이 먼저 끝나도 가사를 질문으로 받지 않는다)
    // 질문 중 돌봄 요약이 바뀌었다. 이 질문의 QaReply 글은 말하지 않는다(도구는 처리)
    val careStale: Boolean = false
)

@Serializable data class Line(
    val sid: Sid,
    val vars: Map<String, String>,
    val askRestId: String?,
    val qaAnswer: Boolean = false,
    val careUsed: Boolean = true, // 질문 답(QA) 줄이 돌봄 값을 썼는가. false면 돌봄 요약이 바뀌어도 빼지 않는다
    val longDrive: Boolean = false, // 2시간 제안(S11, 쉼터 없으면 S10). 말하기가 끝나야 longDriveOffered가 선다
    val playId: String? = null, // S02~S04: 이 알림을 낸 재생. 그 재생이 계속될 때만 말한다
    val queuedAt: Long? = null, // 큐에 넣은 시각. 미룰 수 있는 문장이 holdMaxMs를 넘으면 버린다. 기본값 null = 버리지 않음(스냅숏 호환)
    val sessionId: String? = null, // 줄을 넣은 세션 id. 다른 세션의 줄이 끝나도 지금 세션 상태는 바꾸지 않는다. null이면 지금 세션으로 본다
    // 인계 안내 줄(S07·S08·S10·S15). 그 세션의 인계 묶음은 이 줄을 따라간다. 스냅숏 호환 기본값 false
    val handoff: Boolean = false
)

/** 휴식 기록 한 줄. sid는 제안 문장(S07·S07_DEPART·S08·S10·S11) 또는 질문 답의 경유지 요청이면 QA. */
@Serializable data class RestLogEntry(
    val at: Long,
    val sid: Sid,
    val restId: String?,
    val answer: YesNo? = null, // '응/아니' 답. 없으면 아직 답이 없음
    // 경유지 결과(WaypointResult.ok). 없으면 요청하지 않음·결과 전
    val waypointOk: Boolean? = null
)

// ---- 열거 ----
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
    DRIVER_STOP,
    CALL,
    GEAR_P,
    PARKED_ALARM,
    SWITCH_SOUND,
    SESSION_CAP,
    FEEDING_WINDOW,
    MIC_ERROR,
    DETECTOR_TIMEOUT,
    SILENCED,
    FOCUS_LOSS,
    SUSPEND,
    SESSION_END
}

// ---- 표 밖에서 이름·값이 나온 열거 ----
// session.trigger
@Serializable enum class OpenTrigger {
    DOOR_THEN_D,
    BUTTON_THEN_D
}

// episode.trigger
@Serializable enum class EpisodeTrigger {
    ALARM,
    SWITCH_SOUND
}

// Play.kind
@Serializable enum class PlayKind {
    AUTO,
    REQUEST
}

// Play.mode
@Serializable enum class PlayMode {
    NORMAL,
    PAUSE,
    DUCK
}

@Serializable enum class MethodKind { WHITE_NOISE, WOMB, LULLABY, SONG }

@Serializable enum class CarSeat { LEFT, RIGHT, CENTER }

@Serializable enum class RestKind { REST_AREA, DROWSY_SHELTER }

// TryRecord.action
@Serializable enum class SecondAction {
    COMFORT,
    FEEDING,
    OTHER
}

@Serializable enum class HandoffCause { TWO_ATTEMPTS, SESSION_CAP, NO_SOUND }

@Serializable enum class HandoffKind { WAYPOINT_OFFER, ARRIVAL_NOTICE, NO_REST, PARKED }

// session.output_route
@Serializable enum class OutputRoute {
    REAR_ZONE,
    DEFAULT_FALLBACK
}

@Serializable enum class PlayEndReason {
    // play_log.end_reason
    DEADLINE,
    DRIVER_STOP,
    CALL,
    GEAR_P,
    FOCUS_LOSS,
    SUSPEND,
    SWITCH_SOUND,
    ERROR,
    SESSION_END
}

// ---- 사건·할 일 안의 열거 ----
@Serializable enum class Side { LEFT, RIGHT }

@Serializable enum class MicPath { HOME_BUTTON, NOTICE, APP_LAUNCH }

@Serializable enum class FocusChange { LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK, GAIN }

@Serializable enum class RejectReason { FOCUS_DENIED, ERROR, NO_REAR_OUTPUT }

@Serializable enum class EndReason { DEADLINE, STOPPED, ERROR }

@Serializable enum class YesNo { YES, NO, NONE }

@Serializable enum class ControlKind { SEAT_HEAT, WINDOW_LOCK, READING_LIGHT, HVAC_TEMP, AMBIENT_BRIGHTNESS, AMBIENT_COLOR }

@Serializable enum class Via { TOOL, VOICE, SCENARIO }

@Serializable enum class BabyAboardFrom { HOME, NOTICE }

@Serializable enum class FeedingFrom { CAR_SCREEN, PHONE }

// 판단 코어 안에서만 쓰는 값
@Serializable enum class TurnSignal {
    NONE,
    LEFT,
    RIGHT
}

@Serializable enum class Notice { BABY_ABOARD, FEEDING_CONFIRM, SAFETY_CONTROL_FAILED, MIC_STOPPED }
