package kr.aigyeot.phone.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kr.aigyeot.shared.Envelope

// 보낼 함 항목: 종류별 내용([OutboxPayload])과 한 줄([OutboxItem]), 저장 형식. 보내기 결과 처리는 Outbox.kt.

/** 보낼 함 항목의 종류(종류마다 필요한 칸만 둔다. iOS `OutboxPayload`와 같은 모양). */
sealed interface OutboxPayload {
    /**
     * 한 번에 보내는 메시지(feeding_started·song_removed·song_selected).
     * `reflected`는 수유(feeding_started)만 쓴다: 이 수유가 차에 반영됐다(`feeding_state`가 같은 시각). 기록 지우기·50개 제한으로
     * 기록 줄이 사라져도 버림 안내를 거르게 항목에 남긴다(iOS에는 없는 칸: iOS는 수유 기록 줄의 상태만 본다).
     */
    data class Message(val type: String, val body: JsonObject, val reflected: Boolean = false) : OutboxPayload

    /**
     * 노래 올리기(두 단계): 파일 `PUT /v1/songs/{song_id}`가 끝나면 `uploaded`가 참이 되고 그다음 `song_added`를 보낸다.
     * `file`은 앱 폴더의 녹음 파일 이름이다(iOS에는 없는 칸: iOS는 곡 id로 파일 이름을 정한다. 안드로이드는 이름을 저장하고,
     * 다른 폰이 그 곡을 지워 곡 줄이 지움이 돼도 올리기가 남아 있는 동안 파일을 지키는 데 쓴다).
     */
    data class Song(val songId: String, val body: JsonObject, val uploaded: Boolean, val file: String) : OutboxPayload

    /** 돌봄 요약. `tried`: 보내기 직전에 저장하는 '보내 봄'. 보내 본 요약은 바꾸지 않는다. */
    data class CareSummary(val body: JsonObject, val tried: Boolean) : OutboxPayload
}

/**
 * 보낼 함 한 줄(iOS `OutboxItem`과 같은 칸). 봉투는 보낼 때 [envelope]로 만든다.
 * 저장 파일(`phone_state.json`)에는 옛 평평한 키 그대로 쓴다([OutboxItemSerializer], iOS도 저장 키가 같다).
 */
@Serializable(with = OutboxItemSerializer::class)
data class OutboxItem(
    /** 메시지 봉투 id(다시 보내도 같다). */
    val id: String,
    val payload: OutboxPayload,
    val ts: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextTryAt: Long = 0,
    /** 지금 기다림이 망 실패(응답 없음·닿지 않음)에서 왔다. 망 복구·'다시 시도'·앞으로 오기 때 당긴다(iOS와 같은 칸). 중계가 준 기다림은 거짓. */
    val networkWait: Boolean = false,
) {
    /** 메시지 type·body(보낼 때 봉투에 싣는다). */
    val type: String
        get() = when (payload) {
            is OutboxPayload.Message -> payload.type
            is OutboxPayload.Song -> "song_added"
            is OutboxPayload.CareSummary -> "care_summary"
        }
    val body: JsonObject
        get() = when (payload) {
            is OutboxPayload.Message -> payload.body
            is OutboxPayload.Song -> payload.body
            is OutboxPayload.CareSummary -> payload.body
        }
    val envelope: Envelope get() = Envelope(v = 1, id = id, type = type, ts = ts, body = body)

    /** 노래면 파일을 올렸다. */
    val uploaded: Boolean get() = (payload as? OutboxPayload.Song)?.uploaded == true

    /** 요약이면 '보내 봄'. */
    val sentOnce: Boolean get() = (payload as? OutboxPayload.CareSummary)?.tried == true

    /** 수유면 차에 반영됨. */
    val reflected: Boolean get() = (payload as? OutboxPayload.Message)?.reflected == true

    /** 노래 파일을 올렸다(또는 다시 올려야 한다). 노래가 아니면 그대로. */
    fun markUploaded(v: Boolean) = (payload as? OutboxPayload.Song)?.let { copy(payload = it.copy(uploaded = v)) } ?: this

    /** 요약의 '보내 봄'. 요약이 아니면 그대로. */
    fun markTried(v: Boolean) = (payload as? OutboxPayload.CareSummary)?.let { copy(payload = it.copy(tried = v)) } ?: this

    /** 수유가 차에 반영됐다. 메시지가 아니면 그대로. */
    fun markReflected() = (payload as? OutboxPayload.Message)?.let { copy(payload = it.copy(reflected = true)) } ?: this
}

/** 새 보낼 함 항목(봉투 시각은 넣는 때). */
fun outboxItem(payload: OutboxPayload, now: Long, id: String = ulid(now)) = OutboxItem(id, payload, rfc3339(now), now)

/** 수유 시작 메시지인가(수유만 뜻이 있는 규칙의 분기 한 곳). */
val OutboxItem.isFeeding: Boolean get() = payload is OutboxPayload.Message && payload.type == "feeding_started"

/** 보낼 함 수유 항목의 시작 시각(`feeding_started` body의 `at`). */
internal val OutboxItem.feedingAt: Long? get() = (body["at"] as? JsonPrimitive)?.contentOrNull?.let(::instantOf)

/** 저장 파일의 보낼 함 한 줄: 이전 버전과 같은 평평한 키에 `network_wait`(없으면 거짓)만 더했다. 종류는 [OutboxItemSerializer]가 정한다. */
@Serializable
private class OutboxItemFile(
    val createdAt: Long,
    val envelope: Envelope,
    val songFile: String? = null,
    val uploaded: Boolean = false,
    val attempts: Int = 0,
    val nextTryAt: Long = 0,
    val sentOnce: Boolean = false,
    val reflected: Boolean = false,
    val networkWait: Boolean = false,
)

/** 저장 키(평평함) ↔ 종류([OutboxPayload]). 노래 파일이 있으면 노래, type이 돌봄 요약이면 요약, 나머지는 메시지(iOS `OutboxItem.init`과 같은 판단). */
object OutboxItemSerializer : KSerializer<OutboxItem> {
    override val descriptor: SerialDescriptor = SerialDescriptor("kr.aigyeot.phone.OutboxItem", OutboxItemFile.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: OutboxItem) {
        val p = value.payload
        encoder.encodeSerializableValue(
            OutboxItemFile.serializer(),
            OutboxItemFile(
                createdAt = value.createdAt,
                envelope = value.envelope,
                songFile = (p as? OutboxPayload.Song)?.file,
                uploaded = value.uploaded,
                attempts = value.attempts,
                nextTryAt = value.nextTryAt,
                sentOnce = value.sentOnce,
                reflected = value.reflected,
                networkWait = value.networkWait,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): OutboxItem {
        val f = decoder.decodeSerializableValue(OutboxItemFile.serializer())
        val env = f.envelope
        val payload = when {
            f.songFile != null -> OutboxPayload.Song((env.body["song_id"] as? JsonPrimitive)?.contentOrNull.orEmpty(), env.body, f.uploaded, f.songFile)
            env.type == "care_summary" -> OutboxPayload.CareSummary(env.body, f.sentOnce)
            else -> OutboxPayload.Message(env.type, env.body, f.reflected)
        }
        return OutboxItem(env.id, payload, env.ts, f.createdAt, f.attempts, f.nextTryAt, f.networkWait)
    }
}
