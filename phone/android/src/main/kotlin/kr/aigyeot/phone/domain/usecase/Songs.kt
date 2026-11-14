package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.OutboxItem
import kr.aigyeot.phone.domain.model.OutboxPayload
import kr.aigyeot.phone.domain.model.SONGS_FULL
import kr.aigyeot.phone.domain.model.SendResult
import kr.aigyeot.phone.domain.model.SongSend
import kr.aigyeot.phone.domain.model.removeSong
import kr.aigyeot.phone.domain.model.replaceable
import kr.aigyeot.phone.domain.model.replacedSong
import kr.aigyeot.phone.domain.model.selectSong
import kr.aigyeot.phone.domain.model.songId
import kr.aigyeot.phone.domain.model.songsFull
import kr.aigyeot.phone.domain.model.ulid
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.RelayClient
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.phone.domain.repository.SongFileStore
import kr.aigyeot.phone.domain.state.Notices
import java.io.File

// 우리 노래(owner, '노래 거절').

/**
 * 녹음을 목록·보낼 함에 넣는다. 원본(`cache/recording-*`)은 복사본이 커밋된 뒤에만 지운다. 실패하면 원본을 그대로 두고 문구를 낸다.
 * 보내기는 앱 범위에서 끝나고 결과는 녹음 파일 경로별 [sends]에 남는다: 화면을 다시 만들어도 새 시트가 같은 보내기를 이어 본다.
 */
class RecordSong(
    private val state: PhoneStateRepository,
    private val songs: SongFileStore,
    private val clearRecords: ClearRecords,
    private val outbox: SendOutbox,
    private val notices: Notices,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val _sends = MutableStateFlow<Map<String, SongSend>>(emptyMap())
    val sends: StateFlow<Map<String, SongSend>> = _sends.asStateFlow()

    /** 보내는 중인 녹음 원본과, 그사이 시트가 닫혀 보내기가 끝나면 지울 원본. */
    private val sendingRecordings = mutableSetOf<File>()
    private val orphanRecordings = mutableSetOf<File>()

    /** 보내는 중에 시트가 닫힌 녹음(경로). 메인 흐름에서만 읽고 쓴다. */
    private val abandoned = mutableSetOf<String>()

    fun newRecording(): File = songs.newRecording()

    /** 앞 프로세스가 남겨 시작 때 지울 임시 녹음인가(복원된 녹음 시트는 이 이름을 다시 쓰지 않는다). */
    fun isLeftover(recording: File) = songs.isLeftover(recording)

    /** 녹음을 차로 보낸다. 같은 녹음을 보내는 중이면 다시 하지 않는다. */
    fun send(recording: File, name: String, durationS: Double, replacing: String? = null) {
        val key = recording.path
        var started = false
        _sends.update { if (it[key] == SongSend.Sending) it else it + (key to SongSend.Sending).also { started = true } }
        if (!started) return
        scope.launch {
            val err = add(recording, name, durationS, replacing)
            // 그사이 시트가 닫혔으면 결과를 남기지 않는다(받을 시트가 없어 쌓이기만 한다). 실패였으면 원본도 지웠으니 띠로 한 번 알린다.
            val dropped = abandoned.remove(key)
            _sends.update { if (dropped) it - key else it + (key to (err?.let { e -> SongSend.Failed(e) } ?: SongSend.Sent)) }
            if (dropped && err != null) notices.addLine("노래를 보내지 못했어요. 다시 녹음해 주세요.")
        }
    }

    /** 화면을 다시 만들며 복원된 녹음 시트가 그 녹음을 다시 맡는다: 앞 화면이 닫히며 버린 보내기 결과·원본 지우기를 되돌린다. */
    fun adopt(recording: File) {
        abandoned -= recording.path
        synchronized(sendingRecordings) { orphanRecordings.remove(recording) }
    }

    /** 시트가 보내기 결과를 받았다. */
    fun consume(recording: File) = _sends.update { it - recording.path }

    /**
     * 녹음 시트가 닫혔다: 원본은 보내기가 끝나면(성공이면 이미 지웠다), 보내는 중이 아니면 바로 지운다.
     * 시트의 코루틴은 닫힐 때 취소되므로 실패한 보내기의 원본을 시트가 지울 수 없다('녹음 파일').
     */
    fun release(recording: File) {
        // 그 녹음의 보내기 결과를 받을 곳이 없다. 끝난 결과는 지우고, 도는 보내기는 끝나도 남기지 않게 적는다.
        if (_sends.value[recording.path] == SongSend.Sending) abandoned += recording.path
        _sends.update { m -> if (m[recording.path] == SongSend.Sending) m else m - recording.path }
        val now = synchronized(sendingRecordings) { (recording !in sendingRecordings).also { if (!it) orphanRecordings += recording } }
        if (now) scope.launch { songs.delete(recording) }
    }

    private suspend fun add(recording: File, name: String, durationS: Double, replacing: String?): String? = scope.async {
        synchronized(sendingRecordings) { sendingRecordings += recording }
        try {
            copyAndCommit(recording, name, durationS, replacing)
        } finally {
            val orphan = synchronized(sendingRecordings) { sendingRecordings -= recording; orphanRecordings.remove(recording) }
            if (orphan) withContext(NonCancellable) { songs.delete(recording) }
        }
    }.await()

    private suspend fun copyAndCommit(recording: File, name: String, durationS: Double, replacing: String?): String? {
        val c = state.creds ?: return "연결이 끊겼어요. 다시 연결해 주세요."
        val gen = clearRecords.generation()
        var added = false
        var full = false
        val songId = ulid(clock.now())
        val fileName = "$songId.m4a"
        val err = songs.locked {
            val copied = songs.copyIn(recording, fileName)
            when {
                !copied -> "녹음을 저장하지 못했어요. 다시 보내 주세요."
                // 그사이 기록을 지웠으면 노래를 되살리지 않는다. 넣은 뒤에 기록을 지웠으면 파일은 남긴다: 상태에서 노래가 빠진 뒤 정리가 지운다.
                // '다시 녹음'이면 그 곡의 지움과 새 곡을 한 저장에 넣는다(지움이 먼저). 3곡이 찼으면(그사이 다른 폰이 더함) 넣지 않는다(iOS와 같음).
                state.commit(c) { s ->
                    when {
                        clearRecords.generation() != gen -> s
                        s.songsFull(s.replaceable(replacing)) -> s.also { full = true } // 바꿀 곡은 저장하는 지금 다시 본다
                        else -> s.replacedSong(replacing, songId, name, durationS, fileName, clock.now()).also { added = true }
                    }
                } && added -> null
                full -> {
                    songs.delete(songs.file(fileName))
                    SONGS_FULL
                }
                else -> {
                    songs.delete(songs.file(fileName))
                    "녹음을 저장하지 못했어요. 녹음은 그대로 있어요. 다시 보내 주세요."
                }
            }
        }
        if (err == null) {
            songs.delete(recording)
            if (replacing != null) songs.clean() // 바꾼 옛 곡의 파일은 저장이 끝난 뒤 지운다
            outbox.pump()
        }
        return err
    }
}

/** 보낼 함 노래 항목의 파일 PUT. 파일을 먼저 올린 뒤 봉투(song_added)를 보낸다. */
class UploadSong(private val relay: RelayClient, private val songs: SongFileStore) {
    /** 아직 올리지 않은 노래 항목이면 올린 결과, 올릴 것이 없으면 null(이어서 봉투를 보낸다). */
    suspend operator fun invoke(c: Credentials, item: OutboxItem): SendResult? {
        val song = (item.payload as? OutboxPayload.Song)?.takeIf { !it.uploaded } ?: return null
        val file = songs.file(song.file)
        if (!file.exists()) return SendResult.Failed(415, "file_missing")
        return when (val r = relay.putSong(c.token, song.songId, file)) {
            is RelayResult.Ok -> SendResult.Uploaded
            is RelayResult.Err -> if (r.code == "already_exists") SendResult.Uploaded else SendResult.Failed(r.status, r.code, r.retryAfterS)
            RelayResult.Offline, RelayResult.Unreached -> SendResult.Offline
        }
    }
}

/** 실험에 쓸 곡 고르기. 지금 후보는 차가 보내는 `song_status.candidate`가 원본이다. */
class SelectSong(
    private val state: PhoneStateRepository,
    private val outbox: SendOutbox,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    operator fun invoke(songId: String) {
        scope.launch {
            val c = state.creds ?: return@launch
            state.commit(c) { it.selectSong(songId, clock.now()) }
            outbox.pump()
        }
    }
}

/** 곡 지우기: 지움 상태와 song_removed를 먼저 함께 커밋하고, 그 뒤에 녹음 파일을 지운다. */
class RemoveSong(
    private val state: PhoneStateRepository,
    private val songs: SongFileStore,
    private val outbox: SendOutbox,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    operator fun invoke(songId: String) {
        scope.launch {
            val c = state.creds ?: return@launch
            if (state.commit(c) { it.removeSong(songId, clock.now()) }) songs.clean()
            outbox.pump()
        }
    }
}
