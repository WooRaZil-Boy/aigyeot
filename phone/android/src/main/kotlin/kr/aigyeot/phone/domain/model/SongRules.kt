package kr.aigyeot.phone.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kr.aigyeot.shared.SongRejectReason

// 우리 노래: 목록의 곡([SongEntry])과 곡 올리기·지우기·고르기 규칙.

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
    val error: String? = null
)

fun songRef(songId: String): JsonObject = buildJsonObject { put("song_id", songId) }

/** 이 상태가 쓰는 녹음 파일 이름(목록에 있는 곡, 아직 올리지 않은 곡). 나머지 파일은 지워도 된다. */
val PhoneState.songFiles: Set<String>
    get() = (songs.filter { it.phase != SongPhase.REMOVED }.mapNotNull { it.file } + outbox.mapNotNull { (it.payload as? OutboxPayload.Song)?.file }).toSet()

fun PhoneState.addSong(songId: String, name: String, durationS: Double, file: String, now: Long, id: String = ulid(now)): PhoneState {
    val body = buildJsonObject {
        put("song_id", songId)
        put("name", name)
        put("duration_s", durationS)
    }
    return copy(
        songs = songs + SongEntry(songId, name, durationS, file, SongPhase.UPLOADING),
        outbox = outbox + outboxItem(OutboxPayload.Song(songId, body, uploaded = false, file = file), now, id)
    )
}

/** 곡 지우기. 아직 보내지 않은 올리기는 빼고, song_removed는 늘 보낸다(차가 모르는 곡이어도 '지움'으로 남아 해가 없다). */
fun PhoneState.removeSong(songId: String, now: Long, id: String = ulid(now)) = copy(
    songs = songs.map { if (it.songId == songId) it.copy(phase = SongPhase.REMOVED, removedByMe = true, candidate = false) else it },
    outbox = outbox.filterNot { it.payload is OutboxPayload.Song && it.songId == songId } +
        outboxItem(OutboxPayload.Message("song_removed", songRef(songId)), now, id)
)

/**
 * '다시 녹음'('노래 거절'): 거절·실패한 곡 `old`를 지우고 새 곡을 더한다. 한 저장에 넣고 지움 메시지가 먼저 선다(iOS `addSong(replacing:)`와 같음).
 * `old`가 이미 지워졌으면 더하기만 한다.
 */
fun PhoneState.replacedSong(old: String?, songId: String, name: String, durationS: Double, file: String, now: Long): PhoneState {
    val base = replaceable(old)?.let { removeSong(it, now) } ?: this
    return base.addSong(songId, name, durationS, file, now)
}

/** '다시 녹음'으로 바꿀 수 있는 곡인가(거절·실패, '노래 거절'). 화면 버튼과 저장이 같은 규칙을 쓴다. */
val SongEntry.replaceable: Boolean get() = phase == SongPhase.REJECTED || (phase != SongPhase.REMOVED && error != null)

/**
 * 저장하는 지금도 바꿀 수 있는 곡이면 그 id. 시트를 연 사이 차가 받아들였거나 지웠으면 null: 바꾸지 않고 새 곡으로 넣는다
 * (3곡 한도는 그 곡까지 센다, iOS `addSong(replacing:)`과 같음).
 */
fun PhoneState.replaceable(songId: String?): String? = songId?.takeIf { id -> songs.any { it.songId == id && it.replaceable } }

/** 우리 노래 한도. */
const val MAX_SONGS = 3

/**
 * 우리 노래 3곡이 찼다(바꿀 곡 `replacing`은 빼고 센다). 거절된 곡은 줄은 보이지만 자동 지움(song_removed)을 이미 보낼 함에 넣어
 * 새 곡보다 먼저 처리되므로 자리를 차지하지 않는다(iOS `songSlots`와 같음). 화면의 '녹음하기'·한도 안내와 저장이 같은 규칙을 쓴다.
 */
fun PhoneState.songsFull(replacing: String? = null) = visibleSongs.songsFull(replacing)

/** 보이는 곡 목록에서 같은 규칙(화면 상태가 목록만 가질 때). */
fun List<SongEntry>.songsFull(replacing: String? = null) = count { it.songId != replacing && it.phase != SongPhase.REJECTED } >= MAX_SONGS

/** 노래면 올릴 곡 id, 노래 지움·고르기면 body의 곡 id. */
val OutboxItem.songId: String? get() = (payload as? OutboxPayload.Song)?.songId ?: (body["song_id"] as? JsonPrimitive)?.contentOrNull

/**
 * 실험에 쓸 곡 고르기. 지금 후보는 차가 보내는 `song_status.candidate`가 원본이다(마지막 선택이 이긴다).
 * 이 폰이 고른 것을 따로 기억하지 않는다. 다른 폰·차에서 바꾼 선택이 그대로 보이게 한다.
 */
fun PhoneState.selectSong(songId: String, now: Long, id: String = ulid(now)) = copy(
    outbox = outbox + outboxItem(OutboxPayload.Message("song_selected", songRef(songId)), now, id)
)

/** 이 곡의 song_selected가 아직 보낼 함에 있다(차가 후보로 반영하기 전). */
fun PhoneState.selecting(songId: String) = outbox.any { it.payload is OutboxPayload.Message && it.type == "song_selected" && it.songId == songId }

/** 화면에 보일 곡(지운 곡은 뺀다). */
val PhoneState.visibleSongs get() = songs.filter { it.phase != SongPhase.REMOVED }
