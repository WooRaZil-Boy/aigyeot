package kr.aigyeot.phone.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kr.aigyeot.shared.DeviceInfo
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.FeedingStateBody
import kr.aigyeot.shared.HandoffAlertBody
import kr.aigyeot.shared.PairedBody
import kr.aigyeot.shared.PhonePlatform
import kr.aigyeot.shared.PhoneRole
import kr.aigyeot.shared.Platform
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.Role
import kr.aigyeot.shared.SongRejectReason
import kr.aigyeot.shared.SongStatusBody
import kr.aigyeot.shared.SongStatusKind
import kr.aigyeot.shared.UnpairedBody
import kr.aigyeot.phone.domain.usecase.BuildCareSummary
import java.time.ZoneId

// 폰의 지금 상태와 받은 메시지 처리(계약 3.2 '받는 쪽 규칙', 5.6, 7.4·7.5). 안드로이드 없이 JVM에서 시험한다.
// 단순화: 계약 7.4는 Room이지만 양이 작아(리포트 90일, 곡 3개) 한 JSON 파일로 둔다. 커지면 Room으로 옮긴다.

const val KEEP_IDS_MS = 7 * DAY
const val KEEP_RECORDS_MS = 90 * DAY // G34
const val STATE_STALE_MS = 24 * HOUR // G39

@Serializable
data class Versioned<T>(val seq: Long, val value: T)

/** 이 폰이 누른 수유 시작. `at`은 중계가 고친 값이 오면 그 값으로 바꾼다. */
@Serializable
data class FeedingRecord(
    val msgId: String,
    val at: String,
    val sentAt: Long? = null,
    val reflected: Boolean = false,
    val failed: String? = null,
    /** 보낸 돌봄 기록의 시작(ms). 중계가 `at`을 고쳐도 남아, 오늘 카드의 수유와 전송 상태를 맞춘다(계약 7.5). 옛 기록은 null. */
    val startAt: Long? = null,
)

enum class SongPhase { UPLOADING, WAITING, ACCEPTED, REJECTED, REMOVED }

@Serializable
data class SongEntry(
    val songId: String,
    val name: String?,
    val durationS: Double? = null,
    /** 앱 전용 폴더의 녹음 파일 이름. 다른 owner 폰이 올린 곡은 null. */
    val file: String? = null,
    val phase: SongPhase,
    val reason: SongRejectReason? = null,
    val candidate: Boolean = false,
    val seq: Long = 0,
    /** 이 폰에서 지웠다. 늦게 온 받음·거절로 되살리지 않는다. */
    val removedByMe: Boolean = false,
    val error: String? = null,
)

/** 기기마다 마지막으로 적용한 연결 변경 seq와 끊김 표시(3.2 `paired`·`unpaired`, 1.4 `snapshot_seq`). 기기 id는 다시 쓰지 않는다. */
@Serializable
data class DeviceMark(val seq: Long, val removed: Boolean = false)

@Serializable
data class StoredReport(val seq: Long, val receivedAt: Long, val body: ReportSummaryBody)

@Serializable
data class StoredAlert(val id: String, val seq: Long, val receivedAt: Long, val body: HandoffAlertBody)

/** 보낼 함 항목의 종류(P2: 종류마다 필요한 칸만 둔다. iOS `OutboxPayload`와 같은 모양). */
sealed interface OutboxPayload {
    /**
     * 한 번에 보내는 메시지(feeding_started·song_removed·song_selected).
     * `reflected`는 수유(feeding_started)만 쓴다: 이 수유가 차에 반영됐다(`feeding_state`가 같은 시각, 7.5). 기록 지우기·50개 제한으로
     * 기록 줄이 사라져도 버림 안내를 거르게 항목에 남긴다(iOS에는 없는 칸: iOS는 수유 기록 줄의 상태만 본다).
     */
    data class Message(val type: String, val body: JsonObject, val reflected: Boolean = false) : OutboxPayload

    /**
     * 노래 올리기(7.5 두 단계): 파일 `PUT /v1/songs/{song_id}`가 끝나면 `uploaded`가 참이 되고 그다음 `song_added`를 보낸다.
     * `file`은 앱 폴더의 녹음 파일 이름이다(iOS에는 없는 칸: iOS는 곡 id로 파일 이름을 정한다. 안드로이드는 이름을 저장하고,
     * 다른 폰이 그 곡을 지워 곡 줄이 지움이 돼도 올리기가 남아 있는 동안 파일을 지키는 데 쓴다).
     */
    data class Song(val songId: String, val body: JsonObject, val uploaded: Boolean, val file: String) : OutboxPayload

    /** 돌봄 요약. `tried`: 보내기 직전에 저장하는 '보내 봄'(U57). 보내 본 요약은 바꾸지 않는다. */
    data class CareSummary(val body: JsonObject, val tried: Boolean) : OutboxPayload
}

/**
 * 보낼 함 한 줄(7.5, iOS `OutboxItem`과 같은 칸). 봉투는 보낼 때 [envelope]로 만든다.
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
    /** 지금 기다림이 망 실패(응답 없음·닿지 않음)에서 왔다. 망 복구·'다시 시도'·앞으로 오기 때 당긴다(7.5, iOS와 같은 칸). 중계가 준 기다림은 거짓. */
    val networkWait: Boolean = false,
) {
    /** 계약의 메시지 type·body(보낼 때 봉투에 싣는다). */
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

    /** 요약이면 '보내 봄'(U57). */
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

/** 저장 파일의 보낼 함 한 줄: 옛 판과 같은 평평한 키에 `network_wait`(없으면 거짓)만 더했다. 종류는 [OutboxItemSerializer]가 정한다. */
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

/**
 * 이 수유 항목은 차에 반영됐거나 보냈다: 버려도 실패로 알리지 않는다(7.5 '차에 반영됨', U54).
 * 넘겨받은 항목은 보내기 전 사본일 수 있다(보내는 사이 반영됨): 지금 보낼 함의 같은 id도 본다.
 */
internal fun PhoneState.feedingDone(item: OutboxItem): Boolean =
    item.reflected || outbox.any { it.id == item.id && it.reflected } ||
        feedings.any { it.msgId == item.id && (it.reflected || it.sentAt != null) }

/**
 * 받은 봉투를 도메인이 판단에 쓰는 꼴로 바꾼 것(계약 3.3, iOS `InboundMessage`와 같은 모양). 데이터 층 한 곳(`data/RelayClient.kt`의 `Envelope.inbound`)이 만든다.
 * 본문은 차와 글자까지 같은 계약 값이라 그대로 담는다(복사 타입을 만들지 않는다).
 */
sealed interface InboundMessage {
    data class Paired(val body: PairedBody) : InboundMessage
    data class Unpaired(val body: UnpairedBody) : InboundMessage
    data class FeedingState(val body: FeedingStateBody) : InboundMessage
    data class SongStatus(val body: SongStatusBody) : InboundMessage
    data class Report(val body: ReportSummaryBody) : InboundMessage
    data class HandoffAlert(val body: HandoffAlertBody) : InboundMessage

    /**
     * 모르는 type·판, 폰이 적용하지 않는 type(feeding_started 등), 읽지 못한 body(필수 칸이 빠짐): 본문은 적용하지 않고 처리한 것으로 친다
     * (처리한 id, 차가 보냈으면 마지막 수신 시각·차 타일. iOS `ignored`와 같다).
     */
    data object Ignored : InboundMessage
}

/**
 * 받은 메시지 하나(봉투의 id·seq·보낸 쪽과 바꾼 본문, iOS `Inbound`와 같은 칸). `car`는 안드로이드에만 있는 칸이다:
 * 상태의 차 id와 같은 저장 안에서 견주려고 둔다(iOS는 유스케이스가 자격의 차 id로 미리 거른다).
 */
data class Inbound(val id: String, val seq: Long, val fromCar: Boolean, val message: InboundMessage, val car: String?)

@Serializable
data class PhoneState(
    val carId: String? = null,
    val myDeviceId: String? = null,
    val role: Role? = null,
    val feeding: Versioned<FeedingStateBody>? = null,
    val feedings: List<FeedingRecord> = emptyList(),
    val songs: List<SongEntry> = emptyList(),
    val reports: Map<String, StoredReport> = emptyMap(),
    val alerts: List<StoredAlert> = emptyList(),
    val devices: List<DeviceInfo> = emptyList(),
    val devicesAt: Long? = null,
    val deviceMarks: Map<String, DeviceMark> = emptyMap(),
    /** 마지막으로 적용한 기기 목록의 `snapshot_seq`. 목록·표시와 한 상태로 함께 저장한다. */
    val devicesSnapshotSeq: Long? = null,
    /** guardian의 '인계 알림 받기'(중계가 원본, PATCH 응답으로 맞춘다). */
    val handoffAlerts: Boolean? = null,
    /** 본문을 저장한 메시지 id → 시각(7일). '띄운 id'와 따로 둔다(5.6, L43). */
    val processed: Map<String, Long> = emptyMap(),
    /** 띄운 인계 알림 id → 시각(7일). */
    val shown: Map<String, Long> = emptyMap(),
    val outbox: List<OutboxItem> = emptyList(),
    val lastMessageAt: Long? = null,
    val stateRequestNeeded: Boolean = false,
    /** '상태 요청 필요'를 켤 때마다 1씩 는다. 앞선 요청의 늦은 201이 새로 켠 표시를 끄지 않게 한다(7.4). */
    val stateNeedGen: Int = 0,
    val lastStateRequestAt: Long? = null,
    /** 차로 보낸 마지막 수유 시작(epoch ms). 보낼 함과 한 파일이라 한 번의 저장으로 함께 바뀐다(phone-care U3·R5). */
    val lastSentFeedingAt: Long? = null,
    /**
     * 차로 보낸 마지막 `care_summary` body에서 `as_of`를 뺀 것(R19). 보낼 함과 한 저장에서 바뀐다.
     * 끊기(R9)·기록 지우기에서 사라지고, 차에 닿지 못하고 버린 요약(만료·영구 실패)이면 지워 같은 내용도 다시 보낸다.
     */
    val lastSentCareSummary: JsonObject? = null,
    /** 마지막으로 보낸 요약의 `as_of`와 지금까지 보낸 가장 늦은 `as_of`(epoch ms, 초 내림). 시계가 뒤로 가 차가 무시했을 수 있는 요약을 다시 보내는 데 쓴다(3.3). */
    val lastSentCareAsOf: Long? = null,
    val maxSentCareAsOf: Long? = null,
    /** 메시지 POST의 429 기다림이 끝나는 시각(1.1 'Retry-After 뒤'). 그 항목을 지우거나 바꿔도, 다시 켜도 지킨다. 연결이 바뀌면(새 상태) 사라진다. */
    val messageHoldUntil: Long? = null,
    /**
     * '기록 지우기'를 누른 때의 돌봄 기록 id(R10). care.json에서 이 id를 빼고 저장하기 전에는 남는다(그사이 죽거나 저장 실패).
     * 다음 돌봄 저장·시작·앞으로 올 때 이 id만 빼므로 그 뒤 적은 기록은 남는다. 남아 있는 동안 요약을 보내지 않는다.
     * 돌봄 기록은 연결과 따로라 끊기·새 연결·연결 없는 시작에도 남긴다(iOS `clearingCareIds`와 같음).
     */
    val clearingCareIds: List<String>? = null,
    /** 그 '기록 지우기'를 누른 시각. 이때까지 쓰인 임시 녹음만 지운다(그 뒤 시작한 새 녹음은 남긴다). */
    val clearingSince: Long? = null,
    /**
     * 옮기기(R11)를 마치기 전에 연결이 바뀌어 넘어온 옛 연결의 수유 기록(U22). 옮기기만 쓴다. 지금 연결의 `feedings`와 따로 둔다:
     * 보낸 표시 채우기(U22)는 지금 연결의 기록으로만 해야 새 차에 최신 수유가 다시 간다(U6).
     */
    val migrationFeedings: List<FeedingRecord> = emptyList(),
    /** 버렸지만 사용자가 띠를 닫지 않은 메시지(id → type, U54). 버린 저장과 함께 남겨, 앱이 그사이 죽어도 다시 켜면 띠로 알린다. */
    val unseenDrops: Map<String, String> = emptyMap(),
    /**
     * 띠에 남은 수유 버림의 시작 시각(msgId → epoch ms). 기록 지우기로 기록 줄·보낼 함이 없어진 뒤 늦게 온 `feeding_state`가
     * 같은 시각이면 그 버림 안내를 걷는 데 쓴다(7.5 '차에 반영됨', U54). `unseenDrops`에 남은 것만 둔다.
     */
    val droppedFeedingAt: Map<String, Long> = emptyMap(),
) {
    companion object {
        /** 새로 연결했을 때. 다른 차의 seq 캐시는 모두 버린다(3.1). */
        fun paired(carId: String, deviceId: String, role: Role, now: Long) =
            PhoneState(carId = carId, myDeviceId = deviceId, role = role, lastMessageAt = now)

        /**
         * 앱을 시작할 때 읽은 상태가 지금 자격의 차 것이 아니면(빈 상태로 복구, 상태 파일을 바꾸기 전에 죽음)
         * 그 차의 빈 상태로 시작하고 '상태 요청 필요'를 켠다. 이미 ack한 수유·곡 상태는 받은 함에 없기 때문이다(7.4, L47).
         * 맞으면 null.
         */
        fun recoverIfNeeded(loaded: PhoneState, carId: String, deviceId: String, role: Role, now: Long): PhoneState? =
            // 같은 차라도 기기·역할이 바뀌었으면(같은 차에 다시 연결하다 죽음) 옛 상태를 쓰지 않는다. 옛 판 파일의 빈 칸은 같다고 본다.
            if (loaded.carId == carId && (loaded.myDeviceId ?: deviceId) == deviceId && (loaded.role ?: role) == role) null
            else paired(carId, deviceId, role, now).needState()

        /**
         * 앱을 시작할 때의 상태. 연결이 없으면 남은 상태 파일을 쓰지 않는다: 모두 옛 차의 것이다
         * (끊기의 빈 상태 저장이 실패했거나, 자격을 지운 뒤 파일을 지우기 전에 앱이 죽음. 7.4 '연결 끊기: 위 전부를 지운다').
         * 지우다 만 '기록 지우기'(돌봄 기록은 연결과 따로다)만 남긴다.
         */
        fun atStart(loaded: PhoneState, recovered: PhoneState?, connected: Boolean, migrating: Boolean = false): PhoneState =
            (recovered ?: if (connected) loaded else PhoneState()).let { if (it === loaded) it else it.keepingClear(loaded, migrating) }
    }
}

/** 받은 메시지 처리 결과. ack는 저장한 뒤에 보낸다. `banner`는 앱 안 배너로 띄울 인계 알림. */
data class Received(val state: PhoneState, val banner: StoredAlert? = null)

/** 인계 알림 배너는 방금 생긴 알림에만 띄운다(푸시 유효 시간 30분, G28). */
private const val BANNER_WINDOW_MS = 30 * 60 * 1000L

/**
 * 받은 메시지 하나를 적용한다(계약 3.2). 같은 id는 한 번만 처리하고, 늦게 온 것은 seq로 거른다(G19).
 * `live`는 WebSocket으로 바로 받은 것(배너를 띄울 수 있음), 거짓이면 앱을 열며 받은 함에서 꺼낸 것(목록에만, 5.6).
 */
fun PhoneState.receive(m: Inbound, now: Long, live: Boolean, newId: () -> String = { ulid(now) }): Received {
    if (m.id in processed) return Received(this)
    if (carId == null || m.car != carId) return Received(this) // 다른 차 방(이전 연결)의 것은 적용하지 않는다(3.1)
    val seq = m.seq
    var s = this
    var banner: StoredAlert? = null
    when (val msg = m.message) {
        is InboundMessage.FeedingState -> msg.body.let { b ->
            if (seq > (feeding?.seq ?: -1)) {
                val last = instantOf(b.lastFeedingStart)
                val nowReflected = feedings.filter { last != null && instantOf(it.at) == last }.map { it.msgId }
                s = s.copy(
                    feeding = Versioned(seq, b),
                    feedings = feedings.map { if (it.msgId in nowReflected) it.copy(reflected = true, failed = null) else it }, // 반영이 확인되면 만료·전송 실패 표시는 지운다
                    // 그 수유를 버렸다는 띠 안내도 걷는다(U54). 기록 줄이 없어졌어도 버릴 때 적어 둔 시각이 같으면 걷는다.
                    unseenDrops = unseenDrops - nowReflected.toSet() - droppedFeedingAt.filterValues { it == last }.keys,
                    droppedFeedingAt = droppedFeedingAt - nowReflected.toSet() - droppedFeedingAt.filterValues { it == last }.keys,
                    // 보낼 함의 같은 수유(응답을 잃어 다시 보내는 중)에도 적는다: 기록 줄이 사라져도 반영을 안다.
                    outbox = outbox.map { if (it.isFeeding && (it.id in nowReflected || (last != null && it.feedingAt == last))) it.markReflected() else it },
                )
            }
        }
        is InboundMessage.SongStatus -> s = s.applySongStatus(msg.body, seq, now, newId)
        is InboundMessage.Report -> msg.body.let { b ->
            if (seq > (reports[b.sessionId]?.seq ?: -1)) s = s.copy(reports = reports + (b.sessionId to StoredReport(seq, now, b)))
        }
        is InboundMessage.HandoffAlert -> msg.body.let { b ->
            val alert = StoredAlert(m.id, seq, now, b)
            s = s.copy(alerts = (alerts.filter { it.id != m.id } + alert).sortedBy { it.seq })
            // 같은 id는 한 번만 띄운다. 받은 함에서 꺼낸 것은 시스템이 이미 띄웠으므로 '띄운 id'에만 넣는다.
            if (m.id !in shown) {
                s = s.copy(shown = s.shown + (m.id to now))
                val age = instantOf(b.occurredAt)?.let { now - it } ?: 0L // 발생 시각을 못 읽으면 방금 것으로 본다(iOS와 같다)
                if (live && age <= BANNER_WINDOW_MS) banner = alert
            }
        }
        // 적용한 목록의 snapshot_seq 이하인 연결 변경은 이미 목록에 들어 있다(1.4). 다시 적용하지 않는다.
        is InboundMessage.Paired -> if (seq > (devicesSnapshotSeq ?: -1)) msg.body.let { b ->
            val mark = deviceMarks[b.deviceId]
            // 끊긴 기기는 되살리지 않고, 이미 적용한 것보다 작은 seq는 버린다.
            if (mark == null || (!mark.removed && seq > mark.seq)) {
                val role = if (b.role == PhoneRole.OWNER) Role.OWNER else Role.GUARDIAN
                val platform = if (b.platform == PhonePlatform.IOS) Platform.IOS else Platform.ANDROID
                val d = DeviceInfo(b.deviceId, role, platform, b.pairedAt, null, false, b.handoffAlerts)
                s = s.copy(devices = devices.filter { it.deviceId != b.deviceId } + d, deviceMarks = deviceMarks + (b.deviceId to DeviceMark(seq)))
            }
        }
        is InboundMessage.Unpaired -> if (seq > (devicesSnapshotSeq ?: -1)) msg.body.let { b ->
            val mark = DeviceMark(maxOf(seq, deviceMarks[b.deviceId]?.seq ?: 0), removed = true)
            s = s.copy(devices = devices.filter { it.deviceId != b.deviceId }, deviceMarks = deviceMarks + (b.deviceId to mark))
        }
        InboundMessage.Ignored -> Unit
    }
    // '마지막으로 메시지를 받은 시각'은 차에서 온 메시지의 본문을 저장할 때 고친다(7.4).
    val fromCar = m.fromCar
    // 차가 보낸 봉투를 받았으면 차는 지금 켜져 있다. 기기 목록(60초 간격)을 기다리지 않고 차 타일을 바로 맞춘다.
    // 실시간으로 받은 것만 본다. 받은 함에서 꺼낸 지난 봉투는 꺼진 차를 켜진 것으로 바꾸지 않는다.
    if (fromCar && live) s = s.copy(devices = s.devices.map { if (it.role == Role.CAR) it.copy(online = true, lastSeenAt = rfc3339(now)) else it })
    return Received(s.copy(processed = s.processed + (m.id to now), lastMessageAt = if (fromCar) now else s.lastMessageAt), banner)
}

/** song_status: song_id마다 큰 seq가 이긴다(G19). 거절이면 owner 폰이 곡을 지운다(L48). */
private fun PhoneState.applySongStatus(b: SongStatusBody, seq: Long, now: Long, newId: () -> String): PhoneState {
    val cur = songs.find { it.songId == b.songId }
    if (cur != null && seq <= cur.seq) return this
    val base = cur ?: SongEntry(b.songId, b.name, phase = SongPhase.WAITING)
    val next = when {
        b.status == SongStatusKind.REMOVED -> base.copy(phase = SongPhase.REMOVED, candidate = false)
        base.removedByMe -> base // 이 폰에서 지운 곡은 되살리지 않는다
        b.status == SongStatusKind.ACCEPTED -> base.copy(phase = SongPhase.ACCEPTED, durationS = b.durationS ?: base.durationS, candidate = b.candidate)
        else -> base.copy(phase = SongPhase.REJECTED, reason = b.reason, candidate = false)
    }.copy(seq = seq, name = base.name ?: b.name).let { if (base.removedByMe) it else it.copy(error = null) } // 차의 최신 상태가 오면 보내기 실패 표시는 지운다
    var s = copy(songs = songs.filter { it.songId != b.songId } + next)
    // 새 거절이고 아직 지우지 않은 곡만(이미 지움이면 보내지 않는다, 3.2).
    if (next.phase == SongPhase.REJECTED && role == Role.OWNER && !base.removedByMe) {
        // 거절된 곡은 자리를 비우려고 같은 곡 id로 song_removed를 보낸다(7.4, 오프라인이면 보낼 함).
        s = s.copy(
            songs = s.songs.map { if (it.songId == b.songId) it.copy(removedByMe = true) else it },
            outbox = s.outbox + outboxItem(OutboxPayload.Message("song_removed", songRef(b.songId)), now, newId()),
        )
    }
    return s
}

fun songRef(songId: String): JsonObject = buildJsonObject { put("song_id", songId) }

/**
 * `GET /v1/devices` 목록 합치기. 목록은 `snapshot_seq`까지의 연결 변경을 담는다(1.4).
 * 응답을 기다리는 사이 소켓으로 더 새 `paired`·`unpaired`(seq > snapshot_seq)를 적용했으면 그 결과를 그대로 둔다.
 * 끊김 표시가 있는 기기는 목록에 있어도 되살리지 않는다. 차 행(online·마지막 연락)은 늘 목록 값을 쓴다.
 */
fun PhoneState.mergeDevices(snapshotSeq: Long, list: List<DeviceInfo>, now: Long, carLiveAt: Long? = null): PhoneState {
    // 이미 적용한 것보다 오래된 목록 응답(늦게 온 것)은 버린다.
    if (devicesSnapshotSeq != null && snapshotSeq < devicesSnapshotSeq) return this
    val marks = deviceMarks.toMutableMap()
    val out = mutableListOf<DeviceInfo>()
    for (d in list) {
        val m = marks[d.deviceId]
        when {
            // 목록을 기다리는 사이 차의 실시간 봉투를 받았으면(`carLiveAt`) 차 행의 연결 상태는 그쪽이 새것이다(7.2, iOS `carLiveSeen`과 같음).
            d.role == Role.CAR -> out += carLiveAt?.let { d.copy(online = true, lastSeenAt = rfc3339(it)) } ?: d
            m?.removed == true -> Unit
            m != null && m.seq > snapshotSeq -> out += devices.find { it.deviceId == d.deviceId } ?: d
            else -> {
                out += d
                marks[d.deviceId] = DeviceMark(snapshotSeq)
            }
        }
    }
    val listed = list.map { it.deviceId }.toSet()
    for (d in devices) {
        if (d.role == Role.CAR || d.deviceId in listed) continue
        val m = marks[d.deviceId]
        if (m != null && !m.removed && m.seq > snapshotSeq) {
            out += d // 목록보다 나중에 붙은 기기
        } else {
            marks[d.deviceId] = DeviceMark(maxOf(snapshotSeq, m?.seq ?: 0), removed = true)
        }
    }
    return copy(devices = out, devicesAt = now, deviceMarks = marks, devicesSnapshotSeq = snapshotSeq)
}

/**
 * 7일 지난 id, 90일 지난 기록, 7일 지난 보낼 함을 버린다. 버린 항목 때문에 '보내는 중'·'올리는 중'으로 남을
 * 수유·곡에는 같은 저장 안에서 만료 표시를 남긴다(7.5. iOS `expireOutbox`와 같은 뜻).
 * iOS의 `expired`·`failed(…)`는 여기서 `failed`·`error` 문구로 나타낸다.
 */
fun PhoneState.pruned(now: Long, sending: String? = null): PhoneState {
    // 보내는 중인 항목은 결과를 기다린다(그사이 7일을 넘겨도 성공했으면 실패로 적지 않게). 결과 뒤 다음 정리가 본다.
    val (old, kept) = outbox.partition { now - it.createdAt >= KEEP_IDS_MS && it.id != sending }
    var feedings = feedings
    var songs = songs
    for (item in old) when {
        item.isFeeding -> feedings = feedings.map {
            if (it.msgId == item.id && !it.reflected && it.failed == null && it.sentAt == null) it.copy(failed = "7일이 지나 보내지 못했어요") else it
        }
        item.payload is OutboxPayload.Song -> songs = songs.map {
            if (it.songId == item.songId && (it.phase == SongPhase.UPLOADING || it.phase == SongPhase.WAITING) && it.error == null)
                it.copy(error = "7일이 지나 보내지 못했어요. 다시 녹음해 주세요.") else it
        }
    }
    val toNotify = old.filter { item -> !item.isFeeding || !feedingDone(item) }
    val newDrops = toNotify.fold(unseenDrops) { d, item -> d.withDrop(item.id, item.type) }
    val newFeedingDrops = toNotify.filter { it.isFeeding }.mapNotNull { i -> i.feedingAt?.let { i.id to it } }
    return copy(
        processed = processed.filterValues { now - it < KEEP_IDS_MS },
        shown = shown.filterValues { now - it < KEEP_IDS_MS },
        outbox = kept,
        feedings = feedings,
        songs = songs,
        // 차에 반영됐거나 보낸 수유는 기록 줄에 실패를 적지 않으니 띠로도 알리지 않는다(7.5 '차에 반영됨', U54).
        unseenDrops = newDrops,
        droppedFeedingAt = (droppedFeedingAt + newFeedingDrops).filterKeys { it in newDrops },
        // 차에 닿지 못한 요약을 버렸다: 내용이 같아도 다음 계기에 다시 보낸다(iOS `expireOutbox`와 같음).
        // 뒤에 남은 요약이 있으면 표시는 그것을 가리키므로 둔다.
        lastSentCareSummary = if (old.any { it.payload is OutboxPayload.CareSummary } && kept.none { it.payload is OutboxPayload.CareSummary }) null else lastSentCareSummary,
        reports = reports.filterValues { now - it.receivedAt < KEEP_RECORDS_MS },
        alerts = alerts.filter { now - it.receivedAt < KEEP_RECORDS_MS },
    )
}

/** 앱을 열 때, WebSocket·받은 함을 처리하기 전에(7.4 '현재 상태 다시 받기'). */
fun PhoneState.onAppOpen(now: Long) =
    if (lastMessageAt != null && now - lastMessageAt > STATE_STALE_MS) needState() else this

/** '상태 요청 필요'를 켜고 세대를 올린다. */
fun PhoneState.needState() = copy(stateRequestNeeded = true, stateNeedGen = stateNeedGen + 1)

/** state_request가 201을 받았을 때. 보낼 때의 세대가 그대로일 때만 표시를 끈다. */
fun PhoneState.stateRequestDone(gen: Int) = if (stateNeedGen == gen) copy(stateRequestNeeded = false) else this

/** `GET /v1/devices`가 성공한 뒤 state_request를 보낼지(owner만, 10분 안에 보냈으면 안 보냄). */
fun PhoneState.shouldRequestState(now: Long) =
    role == Role.OWNER && stateRequestNeeded && (lastStateRequestAt == null || now - lastStateRequestAt >= STATE_REQUEST_GAP_MS)

/**
 * '이 휴대폰의 기록 지우기'(7.4): 연결은 남기고 캐시·기록을 지운 뒤 상태 요청 필요를 켠다.
 * 마지막 상태 요청 시각은 남겨 10분 간격을 지킨다(G6). 보낼 함은 녹음 파일이 필요한 노래 올리기만 빼고 남긴다.
 */
/**
 * 연결과 따로인 것을 `from`에서 이어받는다(끊기·새 연결·연결 없는 시작): '기록 지우기' 진행(지울 id·누른 시각),
 * 그리고 수유 옮기기(R11)를 아직 마치지 못했으면(`migrating`) 옮길 원본 수유 기록. 마치기 전에 지우면 다시 옮길 수 없다(U22).
 */
fun PhoneState.keepingClear(from: PhoneState, migrating: Boolean = false) = copy(
    clearingCareIds = from.clearingCareIds,
    clearingSince = from.clearingSince,
    migrationFeedings = if (migrating) from.feedings + from.migrationFeedings else emptyList(),
)

/**
 * 보내 본 항목인가(U57): 중계에 닿았을 수 있다(보내기 시작을 적었고 결과가 '받지 않음 확정'이 아니다), 또는 지금 보내는 중이다.
 * 시도 횟수(`attempts`)는 다시 보낼 간격에만 쓴다: 429는 횟수를 올려도 중계에 없다.
 */
fun OutboxItem.tried(sending: String?) = sentOnce || id == sending

/** 보내기 직전에 '보내 봄'을 적는다(U57). 프로세스가 죽거나 결과 저장이 실패해도 바꿔치우지 않게 한다. */
fun PhoneState.markSentOnce(id: String) = copy(outbox = outbox.map { if (it.id == id) it.markTried(true) else it })

/**
 * '보내 봄'을 걷는다(U57 '결과를 확정할 때까지'): 적었지만 보내지 않고 멈췄거나(429 기다림), 중계가 받지 않은 것이 확정됐다
 * (429: 중계는 같은 id를 한도 검사보다 먼저 본다, 연결 전 실패). 응답을 잃은 망 실패는 닿았을 수 있어 걷지 않는다. 그사이 이 표시 때문에 남긴 것을 정리한다:
 * 기록 지우기 중이면 보내지 않은 요약을 모두 빼고(R10, 빈 요약은 지우기를 마칠 때 들어간다), 아니면 보내지 않은 요약은 가장 뒤의 하나만 둔다(7.5).
 * 다시 보낼 기다림(`attempts`·`nextTryAt`·메시지 429 기다림)은 남긴다. 429는 앞 시도와 상관없이 확정이고, 연결 전 실패는 앞서 응답을 잃은 적이 없을 때만 확정이다.
 */
fun PhoneState.untried(id: String): PhoneState {
    if (outbox.none { it.id == id }) return this
    val reverted = outbox.map { if (it.id == id) it.markTried(false) else it }
    val unsent = reverted.filter { it.payload is OutboxPayload.CareSummary && !it.tried(null) }.map { it.id }
    val keep = if (clearingCareIds != null) emptyList() else unsent.takeLast(1)
    val next = reverted.filter { it.id !in unsent || it.id in keep }
    return copy(outbox = next, lastSentCareSummary = if (next.any { it.payload is OutboxPayload.CareSummary }) lastSentCareSummary else null)
}

fun PhoneState.clearedRecords(careIds: List<String> = emptyList(), at: Long? = null, sending: String? = null) = PhoneState(
    carId = carId,
    myDeviceId = myDeviceId,
    role = role,
    devices = devices,
    deviceMarks = deviceMarks,
    devicesSnapshotSeq = devicesSnapshotSeq,
    handoffAlerts = handoffAlerts,
    // 녹음 파일이 필요한 노래 올리기와, 지울 기록으로 만든 요약은 뺀다. 돌봄 기록을 다 지운 뒤 빈 요약을 새로 넣는다(R10·R19).
    // 보내 본 요약은 결과를 확정할 때까지 둔다(빈 요약이 그 뒤에 가서 이긴다).
    outbox = outbox.filter { it.payload !is OutboxPayload.Song && !(it.payload is OutboxPayload.CareSummary && !it.tried(sending)) },
    processed = processed,
    shown = shown,
    stateRequestNeeded = true,
    stateNeedGen = stateNeedGen + 1,
    lastMessageAt = lastMessageAt,
    lastStateRequestAt = lastStateRequestAt,
    lastSentFeedingAt = lastSentFeedingAt, // 차에 이미 간 값이다. 지우면 더 이른 수유를 다시 보낸다(R7). 끊기(R9)에서만 사라진다
    lastSentCareAsOf = lastSentCareAsOf, // 시계 되돌림 판단은 남긴다(R19)
    maxSentCareAsOf = maxSentCareAsOf,
    clearingCareIds = ((clearingCareIds ?: emptyList()) + careIds).distinct(), // 이 저장 뒤 care.json을 쓰다 죽어도 이어서 지운다(R10)
    clearingSince = listOfNotNull(clearingSince, at).maxOrNull(), // 다시 지우면 그때까지의 임시 녹음도
    messageHoldUntil = messageHoldUntil, // 지운 항목이 받은 429도 지킨다(1.1)
    // 지운 수유의 시각을 남기지 않는다(7.4 '기록을 지운다'). 시각이 없으면 늦은 반영으로 그 버림 안내를 걷을 수 없으니 안내도 함께 걷는다.
    unseenDrops = unseenDrops.filterValues { it != "feeding_started" },
    droppedFeedingAt = emptyMap(),
)

/** 이 상태가 쓰는 녹음 파일 이름(목록에 있는 곡, 아직 올리지 않은 곡). 나머지 파일은 지워도 된다. */
val PhoneState.songFiles: Set<String>
    get() = (songs.filter { it.phase != SongPhase.REMOVED }.mapNotNull { it.file } + outbox.mapNotNull { (it.payload as? OutboxPayload.Song)?.file }).toSet()

// ---- 사용자 동작(보낼 함에 넣는다, 7.5) ----

/**
 * 수유 시작 시각을 보낼 함에 넣고 전송 상태 기록을 남긴다. 앱에서는 [reconciledFeeding]만 부른다(R6, 시험이 소스를 검사한다).
 * 시험이 지금 보내기 경로를 직접 보도록 internal로 둔다.
 */
internal fun PhoneState.startFeeding(now: Long, id: String = ulid(now), at: Long = now): PhoneState {
    val env = envelope("feeding_started", buildJsonObject { put("at", rfc3339(at)) }, now, id)
    return copy(
        feedings = (feedings + FeedingRecord(id, rfc3339(at), startAt = at)).takeLast(50),
        outbox = outbox + OutboxItem(env.id, OutboxPayload.Message(env.type, env.body), env.ts, now),
    )
}

/**
 * U22: 옮기기(R11)와 같은 일에서, 옛 경로가 이미 보낼 함에 넣은 수유를 다시 보내지 않게
 * [PhoneState.lastSentFeedingAt]을 지금 수유 기록 중 가장 늦은 시각으로 채운다. state.json을 care.json보다 먼저 저장한다.
 */
fun PhoneState.markedMigratedFeedings(): PhoneState {
    val latest = feedings.mapNotNull { instantOf(it.at) }.maxOrNull() ?: return this
    return if (latest <= (lastSentFeedingAt ?: Long.MIN_VALUE)) this else copy(lastSentFeedingAt = latest)
}

/**
 * R5: owner이고 연결됐을 때, 지우지 않은 수유 중 가장 늦은 시작이 이미 보낸 것보다 늦고 24시간 안이면
 * `feeding_started`(at = 그 시작)를 넣고 같은 상태에서 [PhoneState.lastSentFeedingAt]을 올린다. 아니면 그대로.
 * 차는 더 늦은 `at`만 받으므로 더 이른 수유는 보내지 않는다(R7).
 */
fun PhoneState.reconciledFeeding(events: List<CareEvent>, now: Long, id: String = ulid(now)): PhoneState {
    if (role != Role.OWNER || carId == null) return this
    val latest = events.lastFeedStart() ?: return this
    if (latest <= (lastSentFeedingAt ?: Long.MIN_VALUE) || latest < now - SEND_WINDOW_MS) return this
    return startFeeding(now, id, at = latest).copy(lastSentFeedingAt = latest)
}

/**
 * R19(U45): owner이고 연결됐을 때, R18 요약에서 `as_of`를 뺀 것이 보낸 것과 다르면 안 보낸 옛 요약을 새것으로 바꾸고(없으면 넣고)
 * 같은 상태에서 보낸 표시를 바꾼다. 옮기기(R11) 전이나 기록 지우기(R10)를 마치기 전에는 보내지 않는다.
 * 내용이 같아도 보내는 때(iOS와 같음): 안 보낸 요약이 남았을 때(새 `as_of`로 바꿔 차의 24시간 신선도에 걸리지 않게, 1.4),
 * 시계가 뒤로 갔을 때(지금이 마지막 `as_of`보다 이르거나 그 앞에 더 늦은 `as_of`를 보냈으면 차가 무시했을 수 있다, 3.3).
 */
fun PhoneState.reconciledCareSummary(care: CareFile, now: Long, zone: ZoneId, id: String = ulid(now), sending: String? = null): PhoneState {
    if (role != Role.OWNER || carId == null || !care.migratedFeedings || clearingCareIds != null) return this
    val summary = BuildCareSummary(care, now, zone)
    // 기록에서 온 값이 없는 요약이고 이 연결에서 요약을 넣은 적도 없으면 보내지 않는다: 같은 차의 다른 owner 폰 요약을 빈 요약으로 덮지 않게(U61).
    // 판단은 보낼 요약 그대로 본다(메모만·옛 기록만 있어도 빈 요약이다). 기록을 지운 뒤에는 넣은 적이 있으니 빈 요약을 보낸다(R10).
    // '넣은 적'(`maxSentCareAsOf`)은 기록 지우기에도 남고 연결 끊기(R9)에서만 사라진다.
    if (maxSentCareAsOf == null && summary.empty) return this
    val asOf = Math.floorDiv(now, 1000L) * 1000 // as_of는 초까지 쓴다
    val body = ProtocolJson.encodeToJsonElement(CareSummaryBody.serializer(), summary).jsonObject
    val key = JsonObject(body - "as_of")
    // '아직 안 보낸' 요약만 바꾼다(R19). 보내 본(보내는 중·실패해 기다리는) 요약은 같은 id로 결과를 확정할 때까지 두고 새것을 뒤에 둔다:
    // 바꿔치우면 같은 초의 옛 요약이 중계에 늦게 닿아 seq가 커져 새것을 덮을 수 있다(3.3·7.5).
    val pending = outbox.indexOfFirst { it.payload is OutboxPayload.CareSummary && !it.tried(sending) }
    val last = lastSentCareAsOf
    // 같은 날이고 24시간 안이어야 한다: 날이 바뀌면 차는 앞 요약의 '오늘' 값을 빼고, 24시간이 지나면 요약을 쓰지 않는다(1.4, iOS와 같음).
    // 25시간인 날(서머타임)은 같은 날짜여도 24시간이 지날 수 있다.
    fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    if (pending >= 0) {
        // 안 보낸 요약이 같은 날 같은 내용이면 그대로 둔다: 같은 id로 다시 보내야 응답을 잃어도 중계가 한 번만 처리한다(7.5).
        val p = outbox[pending].body
        val pAsOf = instantOf((p["as_of"] as? JsonPrimitive)?.contentOrNull)
        // 앞서 보낸 더 늦은 as_of보다 이르면(시계를 되돌린 사이 넣었다) 차가 무시하므로 지금 as_of로 바꾼다(3.3, iOS와 같음).
        if (pAsOf != null && JsonObject(p - "as_of") == key && pAsOf <= asOf && asOf - pAsOf < DAY && day(pAsOf) == day(asOf) &&
            pAsOf >= (maxSentCareAsOf ?: pAsOf)
        ) return this
    } else if (key == lastSentCareSummary && last != null && last <= asOf && last >= (maxSentCareAsOf ?: last) && asOf - last < DAY && day(last) == day(asOf)) {
        return this
    }
    // 같은 초에 또 바뀌어도 그대로 보낸다: 차는 as_of가 같으면 중계 seq가 큰 것을 받는다(iOS와 같음).
    val fresh = outboxItem(OutboxPayload.CareSummary(body, tried = false), now, id)
    // 안 보낸 요약은 하나만(7.5). 줄의 자리와 재시도 대기(429 Retry-After·백오프)는 새것이 이어받는다.
    val next = if (pending < 0) outbox + fresh else outbox.mapIndexed { i, it ->
        if (i == pending) fresh.copy(attempts = it.attempts, nextTryAt = maxOf(fresh.nextTryAt, it.nextTryAt), networkWait = it.networkWait) else it
    }
    return copy(outbox = next, lastSentCareSummary = key, lastSentCareAsOf = asOf, maxSentCareAsOf = maxOf(maxSentCareAsOf ?: asOf, asOf))
}

/**
 * R5·R19를 한 상태에서(앱이 네 계기에서 부른다): 보낼 수유와 바뀐 돌봄 요약을 넣고 보낸 표시를 함께 바꾼다.
 * 수유 옮기기(R11) 전에는 둘 다 하지 않는다. 옮기기 전에 보낸 기록은 다음 옮기기에서 다른 id의 수유로 또 옮겨진다(iOS와 같음).
 */
fun PhoneState.reconciled(care: CareFile, now: Long, zone: ZoneId, sending: String? = null): PhoneState =
    // 기록 지우기(R10)를 마치기 전에는 지울 수유도 보내지 않는다(iOS `enqueueLatestFeeding`과 같음).
    if (!care.migratedFeedings || clearingCareIds != null) this else reconciledFeeding(care.events, now).reconciledCareSummary(care, now, zone, sending = sending)

/** 요약만 다시 맞춘다(보내기 직전 재계산·지우기를 마친 뒤). 수유는 정해진 네 계기에서만 보낸다(R6). 막는 조건은 [reconciled]와 같다. */
fun PhoneState.reconciledSummary(care: CareFile, now: Long, zone: ZoneId, sending: String? = null): PhoneState =
    if (!care.migratedFeedings || clearingCareIds != null) this else reconciledCareSummary(care, now, zone, sending = sending)

/** R10: '기록 지우기'를 누른 때의 id만 뺀다. 지운 수유를 다시 옮겨 오지 않게 옮김 표시도 켠다([cleared]와 같음). */
fun CareFile.withoutCleared(ids: Collection<String>) = copy(events = events.filter { it.id !in ids }, migratedFeedings = true)

/**
 * R10: '기록 지우기'로 지울 id. 누를 때 있던 기록(`shown`)은 만든 시각과 상관없이 지운다(시계가 앞섰다 바로잡혀도 남지 않게).
 * 누를 때 저장 중이던 기록은 만든 시각이 누른 때까지인 것으로 고른다. 누른 뒤 새로 적은 기록은 남긴다.
 */
fun CareFile.idsToClear(shown: Set<String>, pressedAt: Long) = events.filter { it.id in shown || it.createdAt <= pressedAt }.map { it.id }

fun PhoneState.addSong(songId: String, name: String, durationS: Double, file: String, now: Long, id: String = ulid(now)): PhoneState {
    val body = buildJsonObject {
        put("song_id", songId)
        put("name", name)
        put("duration_s", durationS)
    }
    return copy(
        songs = songs + SongEntry(songId, name, durationS, file, SongPhase.UPLOADING),
        outbox = outbox + outboxItem(OutboxPayload.Song(songId, body, uploaded = false, file = file), now, id),
    )
}

/** 곡 지우기. 아직 보내지 않은 올리기는 빼고, song_removed는 늘 보낸다(차가 모르는 곡이어도 '지움'으로 남아 해가 없다). */
fun PhoneState.removeSong(songId: String, now: Long, id: String = ulid(now)) = copy(
    songs = songs.map { if (it.songId == songId) it.copy(phase = SongPhase.REMOVED, removedByMe = true, candidate = false) else it },
    outbox = outbox.filterNot { it.payload is OutboxPayload.Song && it.songId == songId } +
        outboxItem(OutboxPayload.Message("song_removed", songRef(songId)), now, id),
)

/**
 * '다시 녹음'(9장 '노래 거절'): 거절·실패한 곡 `old`를 지우고 새 곡을 더한다. 한 저장에 넣고 지움 메시지가 먼저 선다(iOS `addSong(replacing:)`와 같음).
 * `old`가 이미 지워졌으면 더하기만 한다.
 */
fun PhoneState.replacedSong(old: String?, songId: String, name: String, durationS: Double, file: String, now: Long): PhoneState {
    val base = replaceable(old)?.let { removeSong(it, now) } ?: this
    return base.addSong(songId, name, durationS, file, now)
}

/** '다시 녹음'으로 바꿀 수 있는 곡인가(거절·실패, 9장 '노래 거절'). 화면 버튼과 저장이 같은 규칙을 쓴다. */
val SongEntry.replaceable: Boolean get() = phase == SongPhase.REJECTED || (phase != SongPhase.REMOVED && error != null)

/**
 * 저장하는 지금도 바꿀 수 있는 곡이면 그 id. 시트를 연 사이 차가 받아들였거나 지웠으면 null: 바꾸지 않고 새 곡으로 넣는다
 * (3곡 한도는 그 곡까지 센다, iOS `addSong(replacing:)`과 같음).
 */
fun PhoneState.replaceable(songId: String?): String? = songId?.takeIf { id -> songs.any { it.songId == id && it.replaceable } }

/** 우리 노래 한도(명세서 5장·12장, G40). */
const val MAX_SONGS = 3

/**
 * 우리 노래 3곡이 찼다(바꿀 곡 `replacing`은 빼고 센다, G40). 거절된 곡은 줄은 보이지만 자동 지움(song_removed)을 이미 보낼 함에 넣어
 * 새 곡보다 먼저 처리되므로 자리를 차지하지 않는다(8.4, iOS `songSlots`와 같음). 화면의 '녹음하기'·한도 안내와 저장이 같은 규칙을 쓴다.
 */
fun PhoneState.songsFull(replacing: String? = null) = visibleSongs.count { it.songId != replacing && it.phase != SongPhase.REJECTED } >= MAX_SONGS

/** 노래면 올릴 곡 id, 노래 지움·고르기면 body의 곡 id. */
val OutboxItem.songId: String? get() = (payload as? OutboxPayload.Song)?.songId ?: (body["song_id"] as? JsonPrimitive)?.contentOrNull

/**
 * 실험에 쓸 곡 고르기. 지금 후보는 차가 보내는 `song_status.candidate`가 원본이다(마지막 선택이 이긴다, 명세서 5장).
 * 이 폰이 고른 것을 따로 기억하지 않는다. 다른 폰·차에서 바꾼 선택이 그대로 보이게 한다.
 */
fun PhoneState.selectSong(songId: String, now: Long, id: String = ulid(now)) = copy(
    outbox = outbox + outboxItem(OutboxPayload.Message("song_selected", songRef(songId)), now, id),
)

/** 이 곡의 song_selected가 아직 보낼 함에 있다(차가 후보로 반영하기 전). */
fun PhoneState.selecting(songId: String) = outbox.any { it.payload is OutboxPayload.Message && it.type == "song_selected" && it.songId == songId }

/** 화면에 보일 곡(지운 곡은 뺀다). */
val PhoneState.visibleSongs get() = songs.filter { it.phase != SongPhase.REMOVED }
