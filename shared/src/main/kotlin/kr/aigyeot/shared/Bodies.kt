package kr.aigyeot.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 메시지 body. 시각은 모두 RFC 3339 문자열이다.
// 기본값이 없는 null 가능 필드는 "또는 null"(null을 명시), 기본값 `= null`은 "선택"(빠짐)이다.

/** `{}`인 body·요청 본문(`invite_created`, `state_request`, `POST /v1/pair-codes`). */
@Serializable
class Empty

/** `paired`·`unpaired` body의 폰 역할(owner | guardian). */
@Serializable
enum class PhoneRole {
    @SerialName("owner")
    OWNER,

    @SerialName("guardian")
    GUARDIAN
}

/** `paired` body의 폰 플랫폼(android | ios). */
@Serializable
enum class PhonePlatform {
    @SerialName("android")
    ANDROID,

    @SerialName("ios")
    IOS
}

@Serializable
data class PairedBody(val deviceId: String, val role: PhoneRole, val platform: PhonePlatform, val pairedAt: String, val handoffAlerts: Boolean)

@Serializable
enum class UnpairedBy {
    @SerialName("car")
    CAR,

    @SerialName("self")
    SELF,

    @SerialName("relay")
    RELAY
}

@Serializable
data class UnpairedBody(val deviceId: String, val role: PhoneRole, val by: UnpairedBy)

@Serializable
data class FeedingStartedBody(val at: String)

@Serializable
data class SongAddedBody(val songId: String, val name: String, val durationS: Double)

/** `song_removed`와 `song_selected`가 함께 쓴다. */
@Serializable
data class SongRefBody(val songId: String)

@Serializable
enum class SongStatusKind {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("rejected")
    REJECTED,

    @SerialName("removed")
    REMOVED
}

@Serializable
enum class SongRejectReason {
    @SerialName("peak_limit")
    PEAK_LIMIT,

    @SerialName("silent")
    SILENT,

    @SerialName("bad_format")
    BAD_FORMAT,

    @SerialName("too_long")
    TOO_LONG,

    @SerialName("too_large")
    TOO_LARGE,

    @SerialName("limit_reached")
    LIMIT_REACHED,

    @SerialName("download_failed")
    DOWNLOAD_FAILED
}

@Serializable
data class SongStatusBody(
    val songId: String,
    val name: String?,
    val status: SongStatusKind,
    val reason: SongRejectReason?,
    val durationS: Double?,
    val candidate: Boolean
)

@Serializable
data class FeedingStateBody(val lastFeedingStart: String?, val windowStartExpected: String?)

// ---- report_summary ----

@Serializable
enum class OutputRoute {
    @SerialName("rear_zone")
    REAR_ZONE,

    @SerialName("default_zone_fallback")
    DEFAULT_ZONE_FALLBACK,

    @SerialName("no_playback")
    NO_PLAYBACK
}

@Serializable(with = SoundMethodSerializer::class)
enum class SoundMethod(val wire: String) {
    WHITE_NOISE("white_noise"),
    WOMB("womb"),
    LULLABY("lullaby"),
    PARENT_SONG("parent_song"),

    /** 받기 전용: 모르는 글자(새 방식)를 읽을 때만 쓴다. 차는 보내지 않는다. */
    UNKNOWN("unknown")
}

/** 모르는 method 글자 하나가 리포트 전체를 버리지 않게 UNKNOWN으로 읽는다(report_summary의 method). */
object SoundMethodSerializer : KSerializer<SoundMethod> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("SoundMethod", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder) =
        decoder.decodeString().let { w -> SoundMethod.entries.firstOrNull { it.wire == w } ?: SoundMethod.UNKNOWN }
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: SoundMethod) = encoder.encodeString(value.wire)
}

@Serializable
enum class EpisodeTrigger {
    @SerialName("cry_alarm")
    CRY_ALARM,

    @SerialName("switch_sound")
    SWITCH_SOUND
}

@Serializable
enum class EpisodeResult {
    @SerialName("success")
    SUCCESS,

    @SerialName("failure")
    FAILURE,

    @SerialName("undetermined")
    UNDETERMINED
}

@Serializable
enum class ExcludedReason {
    @SerialName("driver_stop")
    DRIVER_STOP,

    @SerialName("call")
    CALL,

    @SerialName("gear_to_park")
    GEAR_TO_PARK,

    @SerialName("started_in_park")
    STARTED_IN_PARK,

    @SerialName("switch_sound")
    SWITCH_SOUND,

    @SerialName("session_cap")
    SESSION_CAP,

    @SerialName("feeding_window")
    FEEDING_WINDOW,

    @SerialName("mic_error")
    MIC_ERROR,

    @SerialName("focus_loss")
    FOCUS_LOSS,

    @SerialName("detector_timeout")
    DETECTOR_TIMEOUT,

    @SerialName("input_silenced")
    INPUT_SILENCED,

    @SerialName("suspend")
    SUSPEND,

    @SerialName("session_end")
    SESSION_END
}

@Serializable
enum class PlayPurpose {
    @SerialName("auto_first")
    AUTO_FIRST,

    @SerialName("auto_second")
    AUTO_SECOND,

    @SerialName("request")
    REQUEST
}

@Serializable
enum class SecondAction {
    @SerialName("comfort_cool")
    COMFORT_COOL,

    @SerialName("feeding_window")
    FEEDING_WINDOW,

    @SerialName("other_sound")
    OTHER_SOUND,

    @SerialName("focus_denied")
    FOCUS_DENIED
}

/** 정차 이유(`stop.reason`). */
@Serializable
enum class StopReason {
    @SerialName("attempt_limit")
    ATTEMPT_LIMIT,

    @SerialName("session_cap")
    SESSION_CAP,

    @SerialName("feeding_window")
    FEEDING_WINDOW,

    @SerialName("no_sound")
    NO_SOUND
}

/** 인계 방식. `report_summary.timeline[].stop.mode`와 `handoff_alert.kind`가 함께 쓴다. */
@Serializable
enum class HandoffKind {
    @SerialName("rest_offer")
    REST_OFFER,

    @SerialName("arrival_notice")
    ARRIVAL_NOTICE,

    @SerialName("no_rest")
    NO_REST,

    @SerialName("parked")
    PARKED
}

@Serializable
enum class Gear {
    @SerialName("P")
    P,

    @SerialName("R")
    R,

    @SerialName("N")
    N,

    @SerialName("D")
    D
}

@Serializable
enum class SteeringUnavailableReason {
    @SerialName("no_comparable_window")
    NO_COMPARABLE_WINDOW,

    @SerialName("no_cry")
    NO_CRY
}

@Serializable
enum class CarActionKind {
    @SerialName("rear_playback")
    REAR_PLAYBACK,

    @SerialName("default_zone_playback")
    DEFAULT_ZONE_PLAYBACK,

    @SerialName("seat_heater_off")
    SEAT_HEATER_OFF,

    @SerialName("rear_window_lock")
    REAR_WINDOW_LOCK,

    @SerialName("cabin_cool")
    CABIN_COOL,

    @SerialName("reading_light_off")
    READING_LIGHT_OFF,

    // 달래는 동안 실내 앰비언트 조명 낮춤. 폰 두 앱의 enum에도 있어야 한다(iOS는 모르는 값에서 디코딩이 깨진다)
    @SerialName("ambient_dim")
    AMBIENT_DIM,

    @SerialName("waypoint_added")
    WAYPOINT_ADDED,

    @SerialName("arrival_notice")
    ARRIVAL_NOTICE,

    @SerialName("guardian_alert")
    GUARDIAN_ALERT
}

/** `timeline[].sound`, `second.sound`. */
@Serializable
data class SoundRef(val method: SoundMethod, val label: String)

@Serializable
data class PlayEntry(val purpose: PlayPurpose, val method: SoundMethod, val songId: String?, val startedAt: String, val endedAt: String, val playS: Int)

@Serializable
data class SecondAttempt(val action: SecondAction, val sound: SoundRef?, val result: EpisodeResult?)

@Serializable
data class StopInfo(
    val handoff: Boolean,
    val reason: StopReason,
    val mode: HandoffKind,
    val restName: String?,
    val waypointAdded: Boolean,
    val guardianNotified: Boolean,
    // 인계 문장을 운전자에게 냈는가(없으면 참으로 읽는다)
    @SerialName("told_driver") val toldDriver: Boolean? = null
)

/** 필드 순서는 메시지 형식 표의 순서(인코더도 이 순서로 쓴다). */
@Serializable
data class TimelineEpisode(
    val episodeId: String,
    val trigger: EpisodeTrigger,
    val startedAt: String,
    val alarmLatencyS: Double?,
    val sound: SoundRef?,
    val playS: Int,
    val plays: List<PlayEntry>,
    val result: EpisodeResult?,
    val excludedReason: ExcludedReason?,
    val recried: Boolean,
    val second: SecondAttempt?,
    val stop: StopInfo?,
    val totalCryS: Int
)

@Serializable
data class PreCryContext(
    val episodeId: String,
    val windowS: Int,
    val speedKmhAvg: Double,
    val speedKmhMax: Double,
    val brakePresses: Int,
    val steeringReversals: Int,
    val turnSignalUsed: Boolean,
    val gear: Gear,
    val cabinTempC: Double?,
    val windowOpen: Boolean?,
    val playing: Boolean
)

@Serializable
data class Steering(
    val available: Boolean,
    val baselinePerMin: Double?,
    val duringCryPerMin: Double?,
    val afterResponsePerMin: Double?,
    val duringCryZ: Double?,
    val unavailableReason: SteeringUnavailableReason?
)

@Serializable
data class CarAction(val action: CarActionKind, val count: Int, val totalS: Int?)

@Serializable
data class ControlRecord(val api: String, val target: String, val attempts: Int, val succeeded: Int, val failed: Int, val retries: Int)

/** `report_summary.method_stats[]`와 `POST /v1/llm`의 `state.method_stats[]`가 함께 쓴다. */
@Serializable
data class MethodStat(val method: SoundMethod, val label: String, val successes: Int, val attempts: Int)

@Serializable
data class ReportSummaryBody(
    val sessionId: String,
    val startedAt: String,
    val endedAt: String,
    val simulated: Boolean,
    val outputRoute: OutputRoute,
    val truncated: Boolean? = null,
    val timeline: List<TimelineEpisode>,
    val preCryContext: List<PreCryContext>,
    val steering: Steering,
    val carActions: List<CarAction>,
    val controls: List<ControlRecord>,
    val methodStats: List<MethodStat>? = null
)

@Serializable
data class HandoffAlertBody(
    val text: String,
    val kind: HandoffKind,
    val restName: String?,
    val minutesToArrival: Int?,
    val occurredAt: String,
    val sessionId: String,
    val episodeId: String
)

/** `care_summary` body. null 가능 칸은 모두 "또는 null"이라 기본값이 없다(명시적 null). */
@Serializable
data class CareSummaryBody(
    val asOf: String,
    val zone: String,
    val sleepDiaperEnabled: Boolean,
    val lastSleepStartAt: String?,
    val lastSleepEndAt: String?,
    val sleepingSince: String?,
    val todaySleepMin: Int?,
    val nightWakeups: Int?,
    val lastDiaperAt: String?,
    val todayDiaperCount: Int?,
    val todayFeedCount: Int
)

@Serializable
data class AckBody(val ids: List<String>)

/** 메시지 type → body 직렬화기(모든 type). 모르는 type은 `null`. */
val bodySerializers: Map<String, KSerializer<*>> = mapOf(
    "paired" to PairedBody.serializer(),
    "unpaired" to UnpairedBody.serializer(),
    "feeding_started" to FeedingStartedBody.serializer(),
    "song_added" to SongAddedBody.serializer(),
    "song_removed" to SongRefBody.serializer(),
    "song_selected" to SongRefBody.serializer(),
    "invite_created" to Empty.serializer(),
    "state_request" to Empty.serializer(),
    "song_status" to SongStatusBody.serializer(),
    "feeding_state" to FeedingStateBody.serializer(),
    "report_summary" to ReportSummaryBody.serializer(),
    "handoff_alert" to HandoffAlertBody.serializer(),
    "care_summary" to CareSummaryBody.serializer(),
    "ack" to AckBody.serializer()
)
