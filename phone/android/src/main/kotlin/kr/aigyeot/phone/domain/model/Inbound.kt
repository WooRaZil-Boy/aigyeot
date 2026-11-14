package kr.aigyeot.phone.domain.model

import kotlinx.serialization.json.JsonObject
import kr.aigyeot.shared.DeviceInfo
import kr.aigyeot.shared.FeedingStateBody
import kr.aigyeot.shared.HandoffAlertBody
import kr.aigyeot.shared.PairedBody
import kr.aigyeot.shared.PhonePlatform
import kr.aigyeot.shared.PhoneRole
import kr.aigyeot.shared.Platform
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.Role
import kr.aigyeot.shared.SongStatusBody
import kr.aigyeot.shared.SongStatusKind
import kr.aigyeot.shared.UnpairedBody

// 받은 메시지 처리: 중계에서 온 메시지를 상태에 적용한다('받는 쪽 규칙').

/**
 * 받은 봉투를 도메인이 판단에 쓰는 꼴로 바꾼 것(iOS `InboundMessage`와 같은 모양). 데이터 층 한 곳(`data/RelayClient.kt`의 `Envelope.inbound`)이 만든다.
 * 본문은 차와 글자까지 같은 메시지 값이라 그대로 담는다(복사 타입을 만들지 않는다).
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

/** 받은 메시지 처리 결과. ack는 저장한 뒤에 보낸다. `banner`는 앱 안 배너로 띄울 인계 알림. */
data class Received(val state: PhoneState, val banner: StoredAlert? = null)

/** 인계 알림 배너는 방금 생긴 알림에만 띄운다(푸시 유효 시간 30분). */
private const val BANNER_WINDOW_MS = 30 * 60 * 1000L

/**
 * 받은 메시지 하나를 적용한다. 같은 id는 한 번만 처리하고, 늦게 온 것은 seq로 거른다.
 * `live`는 WebSocket으로 바로 받은 것(배너를 띄울 수 있음), 거짓이면 앱을 열며 받은 함에서 꺼낸 것(목록에만).
 */
fun PhoneState.receive(m: Inbound, now: Long, live: Boolean, newId: () -> String = { ulid(now) }): Received {
    if (m.id in processed) return Received(this)
    if (carId == null || m.car != carId) return Received(this) // 다른 차 방(이전 연결)의 것은 적용하지 않는다
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
                    // 그 수유를 버렸다는 띠 안내도 걷는다. 기록 줄이 없어졌어도 버릴 때 적어 둔 시각이 같으면 걷는다.
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
        // 적용한 목록의 snapshot_seq 이하인 연결 변경은 이미 목록에 들어 있다. 다시 적용하지 않는다.
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
    // '마지막으로 메시지를 받은 시각'은 차에서 온 메시지의 본문을 저장할 때 고친다.
    val fromCar = m.fromCar
    // 차가 보낸 봉투를 받았으면 차는 지금 켜져 있다. 기기 목록(60초 간격)을 기다리지 않고 차 타일을 바로 맞춘다.
    // 실시간으로 받은 것만 본다. 받은 함에서 꺼낸 지난 봉투는 꺼진 차를 켜진 것으로 바꾸지 않는다.
    if (fromCar && live) s = s.copy(devices = s.devices.map { if (it.role == Role.CAR) it.copy(online = true, lastSeenAt = rfc3339(now)) else it })
    return Received(s.copy(processed = s.processed + (m.id to now), lastMessageAt = if (fromCar) now else s.lastMessageAt), banner)
}

/** song_status: song_id마다 큰 seq가 이긴다. 거절이면 owner 폰이 곡을 지운다. */
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
    // 있던 곡은 제자리에서 바꾸고(목록 순서를 지킨다, iOS InboxRules와 같다), 처음 보는 곡만 끝에 붙인다.
    var s = copy(songs = if (cur != null) songs.map { if (it.songId == b.songId) next else it } else songs + next)
    // 새 거절이고 아직 지우지 않은 곡만(이미 지움이면 보내지 않는다).
    if (next.phase == SongPhase.REJECTED && role == Role.OWNER && !base.removedByMe) {
        // 거절된 곡은 자리를 비우려고 같은 곡 id로 song_removed를 보낸다(오프라인이면 보낼 함).
        s = s.copy(
            songs = s.songs.map { if (it.songId == b.songId) it.copy(removedByMe = true) else it },
            outbox = s.outbox + outboxItem(OutboxPayload.Message("song_removed", songRef(b.songId)), now, newId()),
        )
    }
    return s
}

/**
 * `GET /v1/devices` 목록 합치기. 목록은 `snapshot_seq`까지의 연결 변경을 담는다.
 * 응답을 기다리는 사이 소켓으로 더 새 `paired`·`unpaired`(seq > snapshot_seq)를 적용했으면 그 결과를 그대로 둔다.
 * 끊김 표시가 있는 기기는 목록에 있어도 되살리지 않는다. 차 행(online·마지막 연락)은 늘 목록 값을 쓴다.
 */
fun PhoneState.mergeDevices(snapshotSeq: Long, list: List<DeviceInfo>, carLiveAt: Long? = null): PhoneState {
    // 이미 적용한 것보다 오래된 목록 응답(늦게 온 것)은 버린다.
    if (devicesSnapshotSeq != null && snapshotSeq < devicesSnapshotSeq) return this
    val marks = deviceMarks.toMutableMap()
    val out = mutableListOf<DeviceInfo>()
    for (d in list) {
        val m = marks[d.deviceId]
        when {
            // 목록을 기다리는 사이 차의 실시간 봉투를 받았으면(`carLiveAt`) 차 행의 연결 상태는 그쪽이 새것이다(iOS `carLiveSeen`과 같음).
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
    return copy(devices = out, deviceMarks = marks, devicesSnapshotSeq = snapshotSeq)
}
