package kr.aigyeot.phone.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.SongEntry
import kr.aigyeot.phone.domain.model.SongSend
import kr.aigyeot.phone.domain.model.selecting
import kr.aigyeot.phone.domain.model.visibleSongs
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.SongFileStore
import kr.aigyeot.phone.domain.repository.SongRecorder
import kr.aigyeot.phone.domain.usecase.KeepConnected
import kr.aigyeot.phone.domain.usecase.RecordSong
import kr.aigyeot.phone.domain.usecase.RemoveSong
import kr.aigyeot.phone.domain.usecase.SelectSong
import java.io.File

/** 녹음 시트의 녹음 상태. 녹음 중이면서 끝난 녹음이 있는 조합은 없다. */
sealed interface Take {
    /** `ms`: 멈췄지만 쓸 수 없는 녹음(너무 짧음)의 길이, 처음이면 0. */
    data class Idle(val ms: Long = 0) : Take
    data class Recording(val startedAt: Long) : Take

    /** 보낼 수 있는 녹음. */
    data class Recorded(val ms: Long) : Take
}

data class SongsUiState(val songs: List<SongEntry>, val selecting: Set<String>, val online: Boolean)

/** 보이는 곡(지운 곡 뺌)과, 고른 뒤 아직 차로 보내지 못한 곡. */
internal fun songsUi(s: PhoneState, online: Boolean) =
    SongsUiState(s.visibleSongs, s.visibleSongs.filter { s.selecting(it.songId) }.mapTo(HashSet()) { it.songId }, online)

data class SheetUiState(
    /** 시트마다 고유한 임시 파일. 앞 시트의 보내기(복사·원본 지우기)가 늦어도 새 녹음과 섞이지 않는다. 시트가 없으면 null. */
    val file: File?,
    val take: Take,
    val send: SongSend?,
    val startError: String?,
    val sendError: String?,
) {
    /** 보내는 동안 그 원본을 바꾸는 녹음·다시 보내기는 막는다. */
    val sending get() = send == SongSend.Sending
}

/**
 * 우리 노래(owner): 1~3곡 녹음·다시 듣기·지우기·고르기, 차로 보낸 상태. 노래 화면과 차 탭 '우리 노래' 섹션(U39)이 함께 쓴다.
 * 녹음기는 이 ViewModel이 들고, 화면이 떠나면 놓는다. 녹음은 앱 화면에서만 한다(명세서 12장).
 */
class SongsViewModel(
    state: PhoneStateRepository,
    connection: KeepConnected,
    private val recordSong: RecordSong,
    private val selectSong: SelectSong,
    private val removeSong: RemoveSong,
    private val songs: SongFileStore,
    private val recorder: SongRecorder,
    private val clock: Clock,
    /** 시트 상태를 화면 저장 상태에 둔다(옛 rememberSaveable과 같다): 시스템이 화면만 없앤 뒤 돌아와도 끝낸 녹음·보내기를 이어받는다. */
    private val saved: SavedStateHandle,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    val ui: StateFlow<SongsUiState> = derive(state.state, connection.status) { songsUi(state.state.value, connection.status.value.online) }

    private data class Sheet(val file: File?, val take: Take = Take.Idle(), val startError: String? = null, val sendError: String? = null)

    private val sheet = MutableStateFlow(restore())

    init {
        edit { it } // 되살리며 바꾼 것(새 이름·처음으로)을 바로 저장한다
    }

    /** 시트 상태를 바꾸는 곳은 여기 하나다(P3): 메모리와 화면 저장 상태를 함께 쓴다. 하던 녹음(녹음 중)은 저장하지 않는다(다시 만들면 녹음기는 멈췄다). */
    private fun edit(f: (Sheet) -> Sheet) {
        val s = sheet.updateAndGet(f)
        saved[K_FILE] = s.file?.path
        saved[K_DONE] = s.take is Take.Recorded
        saved[K_MS] = when (val t = s.take) {
            is Take.Recorded -> t.ms
            is Take.Idle -> t.ms
            is Take.Recording -> 0L
        }
        saved[K_START_ERROR] = s.startError
        saved[K_SEND_ERROR] = s.sendError
    }

    /**
     * 저장된 시트를 되살린다. 프로세스가 죽은 뒤 복원됐으면 앞 프로세스의 임시 녹음은 시작 때 지운다: 그 녹음은 없는 것으로 보고 새 이름으로 다시 녹음하게 한다
     * (같은 이름을 다시 쓰면 그 뒤의 새 녹음도 '지울 목록'으로 보인다). 끝낸 녹음의 파일이 없어도 처음으로 돌린다.
     */
    private fun restore(): Sheet {
        val path = saved.get<String>(K_FILE) ?: return Sheet(null)
        val file = File(path)
        val done = saved.get<Boolean>(K_DONE) == true
        val ms = saved.get<Long>(K_MS) ?: 0L
        val errors = Sheet(file, startError = saved.get<String>(K_START_ERROR), sendError = saved.get<String>(K_SEND_ERROR))
        return when {
            recordSong.isLeftover(file) -> errors.copy(file = recordSong.newRecording())
            done && !file.exists() -> errors
            else -> errors.copy(take = if (done) Take.Recorded(ms) else Take.Idle(ms))
        }
    }

    val sheetUi: StateFlow<SheetUiState> = derive(sheet, recordSong.sends) {
        val s = sheet.value
        SheetUiState(s.file, s.take, s.file?.let { recordSong.sends.value[it.path] }, s.startError, s.sendError)
    }

    fun select(songId: String) = selectSong(songId)
    fun remove(songId: String) = removeSong(songId)

    /** 듣기. 녹음 파일을 읽지 못하면 거짓. 녹음하는 동안은 틀지 않는다(스피커 소리가 새 녹음에 섞인다, 녹음 화면 아래 목록). */
    fun play(e: SongEntry): Boolean {
        if (sheet.value.take is Take.Recording) return true
        return e.file?.let { recorder.play(songs.file(it)) {} } ?: false
    }

    fun stopPlaying() = recorder.stopPlaying()

    /** 노래 화면(섹션)이 사라졌다: 녹음·재생을 놓는다. */
    fun stopAll() {
        recorder.stop()
        recorder.stopPlaying()
    }

    override fun onCleared() = stopAll()

    // ---- 녹음 시트 ----

    /**
     * 시트가 보였다. 처음이면 새 임시 파일을 정하고, 화면만 다시 만든 것이면(회전·시스템이 화면만 없앤 뒤) 그 녹음을 이어받는다:
     * 앞 화면이 닫히며 버린 보내기 결과·원본 지우기를 되돌린다(처음 연 시트면 아무 일도 없다).
     */
    fun openSheet() {
        val f = sheet.value.file ?: recordSong.newRecording().also { f -> edit { Sheet(f) } }
        recordSong.adopt(f)
    }

    /**
     * 시트를 닫았다(회전·시스템이 화면만 없앤 때는 부르지 않는다): 녹음을 멈추고, 임시 파일은 보내기가 끝나면(보내는 중이면 끝난 뒤) 지운다.
     * 실패한 보내기의 원본도 남기지 않는다(7.4 '녹음 파일').
     */
    fun closeSheet() {
        recorder.stop()
        val f = sheet.value.file ?: return
        edit { Sheet(null) }
        recordSong.release(f)
    }

    /** 큰 녹음·멈춤 버튼. */
    fun toggleRecording() {
        val s = sheet.value
        val f = s.file ?: return
        when (val t = s.take) {
            is Take.Recording -> stop(t)
            else -> {
                recorder.stopPlaying()
                val at = clock.now()
                // 앞 녹음의 보내기 오류를 새 녹음에 남기지 않는다.
                val ok = recorder.start(f) { edit { cur -> if (cur.take is Take.Recording) cur.copy(take = Take.Recorded(clock.now() - at)) else cur } }
                edit {
                    it.copy(
                        take = if (ok) Take.Recording(at) else Take.Idle(),
                        sendError = null,
                        startError = if (ok) null else "녹음을 시작하지 못했어요. 다른 앱이 마이크를 쓰고 있으면 닫고 다시 눌러 주세요.",
                    )
                }
            }
        }
    }

    private fun stop(t: Take.Recording) {
        val ms = clock.now() - t.startedAt
        val done = recorder.stop()
        edit { it.copy(take = if (done) Take.Recorded(ms) else Take.Idle(ms)) }
    }

    /** 앱이 뒤로 갔다: 녹음은 멈추고 그때까지 녹음한 것을 남긴다. 재생도 멈춘다. */
    fun onBackground() {
        (sheet.value.take as? Take.Recording)?.let { stop(it) }
        recorder.stopPlaying()
    }

    /** 다시 듣기. */
    fun playTake() {
        val f = sheet.value.file ?: return
        if (!recorder.play(f) {}) edit { it.copy(sendError = "녹음 파일을 읽지 못했어요. 다시 녹음해 주세요.") }
    }

    /** 다시 녹음(끝낸 녹음을 버리고 처음으로). */
    fun discardTake() = edit { it.copy(take = Take.Idle(), sendError = null) }

    /** 차로 보내기. 목록·보낼 함에 커밋된 뒤에만 시트를 닫는다. 실패하면 녹음은 그대로 두고 다시 보낼 수 있다. */
    fun send(name: String, replacing: String?) {
        val f = sheet.value.file ?: return
        val d = recorder.durationS(f)
        if (d == null) {
            edit { it.copy(sendError = "녹음 파일을 읽지 못했어요. 다시 녹음해 주세요.") }
            return
        }
        edit { it.copy(sendError = null) }
        recordSong.send(f, name, d, replacing)
    }

    /** 끝난 보내기 결과를 받는다. 보냈으면 참(시트를 닫는다). */
    fun takeSendResult(): Boolean {
        val f = sheet.value.file ?: return false
        val r = recordSong.sends.value[f.path] ?: return false
        if (r == SongSend.Sending) return false
        recordSong.consume(f)
        if (r is SongSend.Failed) edit { it.copy(sendError = r.error) }
        return r == SongSend.Sent
    }

    private companion object {
        const val K_FILE = "song.sheet.file"
        const val K_DONE = "song.sheet.done"
        const val K_MS = "song.sheet.ms"
        const val K_START_ERROR = "song.sheet.startError"
        const val K_SEND_ERROR = "song.sheet.sendError"
    }
}
