package kr.aigyeot.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

// 중계 HTTP API 요청·응답 본문(contracts-link.md 1.4). 메시지 보내기·ack·초대 만들기의 요청은 봉투([Envelope])다.

/** 오류 본문(1.1). 화면 문구는 앱이 `code`로 정한다. */
@Serializable
data class ErrorResponse(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String)

/** `GET /v1/health` */
@Serializable
data class HealthResponse(val ok: Boolean, val version: String, val llm: Boolean, val push: Boolean)

/** `POST /v1/cars` */
@Serializable
data class RegisterCarRequest(val platform: Platform, val appVersion: String)

@Serializable
data class RegisterCarResponse(val carId: String, val deviceId: String, val carToken: String)

/** `POST /v1/pair-codes`의 요청은 [Empty]. */
@Serializable
data class PairCodeResponse(val code: String, val expiresAt: String, val link: String)

/** `POST /v1/pair` */
@Serializable
data class PairRequest(val code: String, val platform: Platform, val pushToken: String?, val appVersion: String)

@Serializable
data class PairResponse(val carId: String, val deviceId: String, val deviceToken: String, val role: Role)

/** `POST /v1/invites`의 응답. 요청은 `invite_created` 봉투. */
@Serializable
data class InviteResponse(val code: String, val display: String, val expiresAt: String, val link: String)

/** `GET /v1/devices` */
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val role: Role,
    val platform: Platform,
    val pairedAt: String,
    val lastSeenAt: String?,
    val online: Boolean,
    /** 차 행에는 없다(1.4 예시). */
    val handoffAlerts: Boolean? = null,
)

@Serializable
data class DevicesResponse(val snapshotSeq: Long, val devices: List<DeviceInfo>)

/** `PATCH /v1/devices/me`의 `push_token` 세 상태(1.4): 빠짐 = 그대로, 문자열 = 바꾸기, 명시적 `null` = 지우기. */
@Serializable(with = PushTokenPatchSerializer::class)
sealed interface PushTokenPatch {
    data object Keep : PushTokenPatch
    data class Set(val token: String) : PushTokenPatch
    data object Clear : PushTokenPatch
}

object PushTokenPatchSerializer : KSerializer<PushTokenPatch> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PushTokenPatch", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PushTokenPatch) {
        val out = encoder as JsonEncoder
        when (value) {
            // Keep은 기본값이라 쓰이지 않는다(encodeDefaults = false).
            PushTokenPatch.Keep -> throw SerializationException("Keep은 기본값이라 직접 쓸 수 없다")
            is PushTokenPatch.Set -> out.encodeJsonElement(JsonPrimitive(value.token))
            PushTokenPatch.Clear -> out.encodeJsonElement(JsonNull)
        }
    }

    override fun deserialize(decoder: Decoder): PushTokenPatch {
        val e = (decoder as JsonDecoder).decodeJsonElement()
        return when {
            e is JsonNull -> PushTokenPatch.Clear
            e is JsonPrimitive && e.isString -> PushTokenPatch.Set(e.content)
            else -> throw SerializationException("push_token은 문자열 또는 null이어야 한다")
        }
    }
}

/** `PATCH /v1/devices/me` 요청. 필드는 각각 선택이다. */
@Serializable
data class PatchDeviceRequest(val pushToken: PushTokenPatch = PushTokenPatch.Keep, val handoffAlerts: Boolean? = null)

@Serializable
data class PatchDeviceResponse(
    val deviceId: String,
    val role: Role,
    val platform: Platform,
    val handoffAlerts: Boolean,
    val pushTokenSet: Boolean,
)

/** `POST /v1/messages` 응답. `body`는 중계가 저장한 그대로(고쳐진 값 포함). */
@Serializable
data class SendMessageResponse(val id: String, val seq: Long, val recipients: Int, val body: JsonObject)

/** `GET /v1/inbox` */
@Serializable
data class InboxResponse(val messages: List<Envelope>, val more: Boolean)

/** `PUT /v1/songs/{song_id}` */
@Serializable
data class UploadSongResponse(val songId: String, val sizeBytes: Long)

// ---- POST /v1/llm ----

@Serializable
data class LlmCry(val currentMin: Int?, val countThisDrive: Int, val confirming: Boolean = false, val unavailable: Boolean = false)   // confirming: 그침 확인 중(GAP·CONFIRM, C231). unavailable: 마이크 꺼짐·입력 끊김이라 울음을 확인할 수 없음(C245). false는 보내지 않는다

@Serializable
data class LlmFeeding(val elapsedMin: Int, val intervalMin: Int)

@Serializable
data class LlmNextRest(val id: String, val name: String, val distanceKm: Double, val nursingRoom: Boolean)

@Serializable
data class LlmSleep(val sleepingSinceMin: Int?, val wokeMinAgo: Int?, val lastSleepMin: Int?, val todaySleepMin: Int?)

@Serializable
data class LlmDiaper(val minAgo: Int?, val todayCount: Int?)

@Serializable
data class LlmState(
    val cry: LlmCry,
    val feeding: LlmFeeding?,
    val carseatMin: Int?,   // 세션이 없으면 null(C234)
    val cabinTemp: Double?,
    val nextRest: LlmNextRest?,
    val methodStats: List<MethodStat>,
    val lastNightWakeups: Int?,
    // 계약 '선택'(L61): 없으면 빠진다.
    val sleep: LlmSleep? = null,
    val diaper: LlmDiaper? = null,
    val feedsToday: Int? = null,
    val careAsOfMinAgo: Int? = null,
)

@Serializable
data class LlmRequest(val requestId: String, val question: String, val state: LlmState)

/** 도구 이름은 `switch_sound`, `add_waypoint`, `stop_soothing`. `input`은 도구마다 다르다. */
@Serializable
data class LlmToolCall(val name: String, val input: JsonObject)

@Serializable
data class LlmResponse(
    val text: String,
    val toolCalls: List<LlmToolCall>,
    val stopReason: String,
    val model: String,
    val promptVersion: String,
    val latencyMs: Long,
)
