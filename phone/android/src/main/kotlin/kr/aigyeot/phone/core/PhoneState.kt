package kr.aigyeot.phone.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
import kr.aigyeot.shared.bodySerializers

// 폰의 지금 상태와 받은 메시지 처리(계약 3.2 '받는 쪽 규칙', 5.6, 7.4·7.5). 안드로이드 없이 JVM에서 시험한다.
// 단순화: 계약 7.4는 Room이지만 양이 작아(리포트 90일, 곡 3개) 한 JSON 파일로 둔다. 커지면 Room으로 옮긴다.

const val KEEP_IDS_MS = 7 * DAY
const val KEEP_RECORDS_MS = 90 * DAY // G34
const val STATE_STALE_MS = 24 * HOUR // G39
const val STATE_REQUEST_GAP_MS = 10 * MINUTE

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
)

/** 마지막 수유 시작: 차가 보낸 값과 이 폰이 기록한 시각(`at`은 중계가 고친 값) 중 최신. 없으면 null. */
fun PhoneState.lastFeedingStart(): Long? =
    (listOf(instantOf(feeding?.value?.lastFeedingStart)) + feedings.map { instantOf(it.at) }).filterNotNull().maxOrNull()

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

/** 보낼 함 한 줄(7.5). `songFile`이 있으면 먼저 `PUT /v1/songs/{song_id}` 뒤 봉투(song_added)를 보낸다. */
@Serializable
data class OutboxItem(
    val createdAt: Long,
    val envelope: Envelope,
    val songFile: String? = null,
    val uploaded: Boolean = false,
    val attempts: Int = 0,
    val nextTryAt: Long = 0,
)

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
            if (loaded.carId == carId) null else paired(carId, deviceId, role, now).needState()
    }
}

/** 받은 메시지 처리 결과. ack는 저장한 뒤에 보낸다. `banner`는 앱 안 배너로 띄울 인계 알림. */
data class Received(val state: PhoneState, val banner: StoredAlert? = null)

@Suppress("UNCHECKED_CAST")
private fun <T> decodeBody(env: Envelope): T? {
    val s = bodySerializers[env.type] as KSerializer<T>? ?: return null
    return runCatching { ProtocolJson.decodeFromJsonElement(s, env.body) }.getOrNull()
}

/**
 * 받은 봉투 하나를 적용한다(계약 3.2). 같은 id는 한 번만 처리하고, 늦게 온 것은 seq로 거른다(G19).
 * `live`는 WebSocket으로 바로 받은 것(배너를 띄울 수 있음), 거짓이면 앱을 열며 받은 함에서 꺼낸 것(목록에만, 5.6).
 */
/** 인계 알림 배너는 방금 생긴 알림에만 띄운다(푸시 유효 시간 30분, G28). */
private const val BANNER_WINDOW_MS = 30 * 60 * 1000L

fun PhoneState.receive(env: Envelope, now: Long, live: Boolean, newId: () -> String = { ulid(now) }): Received {
    if (env.v != 1 || env.type !in bodySerializers || env.id in processed) return Received(this)
    if (carId == null || env.car != carId) return Received(this) // 다른 차 방(이전 연결)의 것은 적용하지 않는다(3.1)
    val seq = env.seq ?: 0
    var s = this
    var banner: StoredAlert? = null
    when (env.type) {
        "feeding_state" -> decodeBody<FeedingStateBody>(env)?.let { b ->
            if (seq > (feeding?.seq ?: -1)) {
                val last = instantOf(b.lastFeedingStart)
                s = s.copy(
                    feeding = Versioned(seq, b),
                    feedings = feedings.map { if (last != null && instantOf(it.at) == last) it.copy(reflected = true, failed = null) else it }, // 반영이 확인되면 만료·전송 실패 표시는 지운다
                )
            }
        }
        "song_status" -> decodeBody<SongStatusBody>(env)?.let { b -> s = s.applySongStatus(b, seq, now, newId) }
        "report_summary" -> decodeBody<ReportSummaryBody>(env)?.let { b ->
            if (seq > (reports[b.sessionId]?.seq ?: -1)) s = s.copy(reports = reports + (b.sessionId to StoredReport(seq, now, b)))
        }
        "handoff_alert" -> decodeBody<HandoffAlertBody>(env)?.let { b ->
            val alert = StoredAlert(env.id, seq, now, b)
            s = s.copy(alerts = (alerts.filter { it.id != env.id } + alert).sortedBy { it.seq })
            // 같은 id는 한 번만 띄운다. 받은 함에서 꺼낸 것은 시스템이 이미 띄웠으므로 '띄운 id'에만 넣는다.
            if (env.id !in shown) {
                s = s.copy(shown = s.shown + (env.id to now))
                val age = instantOf(b.occurredAt)?.let { now - it } ?: 0L // 발생 시각을 못 읽으면 방금 것으로 본다(iOS와 같다)
                if (live && age <= BANNER_WINDOW_MS) banner = alert
            }
        }
        // 적용한 목록의 snapshot_seq 이하인 연결 변경은 이미 목록에 들어 있다(1.4). 다시 적용하지 않는다.
        "paired" -> if (seq > (devicesSnapshotSeq ?: -1)) decodeBody<PairedBody>(env)?.let { b ->
            val mark = deviceMarks[b.deviceId]
            // 끊긴 기기는 되살리지 않고, 이미 적용한 것보다 작은 seq는 버린다.
            if (mark == null || (!mark.removed && seq > mark.seq)) {
                val role = if (b.role == PhoneRole.OWNER) Role.OWNER else Role.GUARDIAN
                val platform = if (b.platform == PhonePlatform.IOS) Platform.IOS else Platform.ANDROID
                val d = DeviceInfo(b.deviceId, role, platform, b.pairedAt, null, false, b.handoffAlerts)
                s = s.copy(devices = devices.filter { it.deviceId != b.deviceId } + d, deviceMarks = deviceMarks + (b.deviceId to DeviceMark(seq)))
            }
        }
        "unpaired" -> if (seq > (devicesSnapshotSeq ?: -1)) decodeBody<UnpairedBody>(env)?.let { b ->
            val m = DeviceMark(maxOf(seq, deviceMarks[b.deviceId]?.seq ?: 0), removed = true)
            s = s.copy(devices = devices.filter { it.deviceId != b.deviceId }, deviceMarks = deviceMarks + (b.deviceId to m))
        }
    }
    // '마지막으로 메시지를 받은 시각'은 차에서 온 메시지의 본문을 저장할 때 고친다(7.4).
    val fromCar = env.from?.role == Role.CAR
    // 차가 보낸 봉투를 받았으면 차는 지금 켜져 있다. 기기 목록(60초 간격)을 기다리지 않고 차 타일을 바로 맞춘다.
    // 실시간으로 받은 것만 본다. 받은 함에서 꺼낸 지난 봉투는 꺼진 차를 켜진 것으로 바꾸지 않는다.
    if (fromCar && live) s = s.copy(devices = s.devices.map { if (it.role == Role.CAR) it.copy(online = true, lastSeenAt = rfc3339(now)) else it })
    return Received(s.copy(processed = s.processed + (env.id to now), lastMessageAt = if (fromCar) now else s.lastMessageAt), banner)
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
            outbox = s.outbox + OutboxItem(now, envelope("song_removed", songRef(b.songId), now, newId())),
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
fun PhoneState.mergeDevices(snapshotSeq: Long, list: List<DeviceInfo>, now: Long): PhoneState {
    // 이미 적용한 것보다 오래된 목록 응답(늦게 온 것)은 버린다.
    if (devicesSnapshotSeq != null && snapshotSeq < devicesSnapshotSeq) return this
    val marks = deviceMarks.toMutableMap()
    val out = mutableListOf<DeviceInfo>()
    for (d in list) {
        val m = marks[d.deviceId]
        when {
            d.role == Role.CAR -> out += d
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
fun PhoneState.pruned(now: Long): PhoneState {
    val (old, kept) = outbox.partition { now - it.createdAt >= KEEP_IDS_MS }
    var feedings = feedings
    var songs = songs
    for (item in old) when (item.envelope.type) {
        "feeding_started" -> feedings = feedings.map {
            if (it.msgId == item.envelope.id && !it.reflected && it.failed == null && it.sentAt == null) it.copy(failed = "7일이 지나 보내지 못했어요") else it
        }
        "song_added" -> songs = songs.map {
            if (it.songId == item.songId && (it.phase == SongPhase.UPLOADING || it.phase == SongPhase.WAITING) && it.error == null)
                it.copy(error = "7일이 지나 보내지 못했어요. 다시 녹음해 주세요.") else it
        }
    }
    return copy(
        processed = processed.filterValues { now - it < KEEP_IDS_MS },
        shown = shown.filterValues { now - it < KEEP_IDS_MS },
        outbox = kept,
        feedings = feedings,
        songs = songs,
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
fun PhoneState.clearedRecords() = PhoneState(
    carId = carId,
    myDeviceId = myDeviceId,
    role = role,
    devices = devices,
    deviceMarks = deviceMarks,
    devicesSnapshotSeq = devicesSnapshotSeq,
    handoffAlerts = handoffAlerts,
    outbox = outbox.filter { it.songFile == null },
    processed = processed,
    shown = shown,
    stateRequestNeeded = true,
    stateNeedGen = stateNeedGen + 1,
    lastMessageAt = lastMessageAt,
    lastStateRequestAt = lastStateRequestAt,
)

/** 이 상태가 쓰는 녹음 파일 이름(목록에 있는 곡, 아직 올리지 않은 곡). 나머지 파일은 지워도 된다. */
val PhoneState.songFiles: Set<String>
    get() = (songs.filter { it.phase != SongPhase.REMOVED }.mapNotNull { it.file } + outbox.mapNotNull { it.songFile }).toSet()

// ---- 사용자 동작(보낼 함에 넣는다, 7.5) ----

fun PhoneState.startFeeding(now: Long, id: String = ulid(now)): PhoneState {
    val env = envelope("feeding_started", buildJsonObject { put("at", rfc3339(now)) }, now, id)
    return copy(
        feedings = (feedings + FeedingRecord(id, rfc3339(now))).takeLast(50),
        outbox = outbox + OutboxItem(now, env),
    )
}

fun PhoneState.addSong(songId: String, name: String, durationS: Double, file: String, now: Long, id: String = ulid(now)): PhoneState {
    val body = buildJsonObject {
        put("song_id", songId)
        put("name", name)
        put("duration_s", durationS)
    }
    return copy(
        songs = songs + SongEntry(songId, name, durationS, file, SongPhase.UPLOADING),
        outbox = outbox + OutboxItem(now, envelope("song_added", body, now, id), songFile = file),
    )
}

/** 곡 지우기. 아직 보내지 않은 올리기는 빼고, song_removed는 늘 보낸다(차가 모르는 곡이어도 '지움'으로 남아 해가 없다). */
fun PhoneState.removeSong(songId: String, now: Long, id: String = ulid(now)) = copy(
    songs = songs.map { if (it.songId == songId) it.copy(phase = SongPhase.REMOVED, removedByMe = true, candidate = false) else it },
    outbox = outbox.filterNot { it.songFile != null && it.songId == songId } +
        OutboxItem(now, envelope("song_removed", songRef(songId), now, id)),
)

val OutboxItem.songId: String? get() = (envelope.body["song_id"] as? JsonPrimitive)?.contentOrNull

/**
 * 실험에 쓸 곡 고르기. 지금 후보는 차가 보내는 `song_status.candidate`가 원본이다(마지막 선택이 이긴다, 명세서 5장).
 * 이 폰이 고른 것을 따로 기억하지 않는다. 다른 폰·차에서 바꾼 선택이 그대로 보이게 한다.
 */
fun PhoneState.selectSong(songId: String, now: Long, id: String = ulid(now)) = copy(
    outbox = outbox + OutboxItem(now, envelope("song_selected", songRef(songId), now, id)),
)

/** 이 곡의 song_selected가 아직 보낼 함에 있다(차가 후보로 반영하기 전). */
fun PhoneState.selecting(songId: String) = outbox.any { it.envelope.type == "song_selected" && it.songId == songId }

/** 화면에 보일 곡(지운 곡은 뺀다). */
val PhoneState.visibleSongs get() = songs.filter { it.phase != SongPhase.REMOVED }
