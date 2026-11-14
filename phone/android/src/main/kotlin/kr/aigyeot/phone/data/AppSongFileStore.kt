package kr.aigyeot.phone.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.aigyeot.phone.domain.model.songFiles
import kr.aigyeot.phone.domain.model.ulid
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.SongFileStore

/**
 * 녹음 파일(`files/songs/`)과 임시 녹음(`cache/recording-*`). 파일 잠금은 새 녹음의 이동·상태 커밋과 파일 정리를 줄 세워,
 * 정리가 읽은 목록보다 늦게 저장된 새 녹음을 지우지 않게 한다. 잡는 순서는 파일 잠금 → 상태 잠금뿐이다.
 */
class AppSongFileStore(private val songsDir: File, private val cacheDir: File, private val state: PhoneStateRepository) : SongFileStore {
    private val lock = Mutex()

    init {
        songsDir.mkdirs()
    }

    /** 앞 프로세스가 남긴 임시 녹음. 녹음 시트는 프로세스를 넘어 살지 않으니, 화면이 뜨기 전(만들 때) 목록을 정해 두고 지운다. */
    private val leftovers: List<File> = cacheDir.listFiles { f -> f.name.startsWith(PREFIX) }?.toList().orEmpty()

    override fun file(name: String) = File(songsDir, name)

    override suspend fun <T> locked(block: suspend () -> T): T = lock.withLock { block() }

    override suspend fun copyIn(recording: File, name: String): Boolean =
        withContext(Dispatchers.IO) { runCatching { recording.copyTo(File(songsDir, name), overwrite = true) }.isSuccess }

    override suspend fun delete(file: File): Boolean = withContext(Dispatchers.IO) { file.delete() }

    override suspend fun clean(): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            // 읽지 못한 상태 파일을 옮겨 둔 것이 있으면 지우지 않는다. 빈 상태로 시작해 그 파일이 아는 녹음을 모르기 때문이다.
            // '이 휴대폰의 기록 지우기'가 옮겨 둔 파일을 지운 뒤에야 다시 정리한다.
            if (state.recoveryPending()) return@withContext false
            val keep = state.state.value.songFiles
            // 폴더를 읽지 못하면(장치 오류) 지우지 못한 것이다. 폴더가 없으면 지울 것도 없다.
            val files = songsDir.listFiles() ?: return@withContext !songsDir.exists()
            files.filter { it.name !in keep }.map { it.delete() }.all { it }
        }
    }

    override suspend fun purge() = lock.withLock {
        // 지킬 기록이 없으므로 백업·녹음을 모두 지운다. 정리 건너뛰기 조건을 타지 않는다.
        state.discardRecovery()
        withContext(Dispatchers.IO) { songsDir.listFiles()?.forEach { it.delete() } }
        Unit
    }

    override fun newRecording() = File(cacheDir, "$PREFIX${ulid()}.m4a")

    override fun isLeftover(recording: File) = recording in leftovers

    override suspend fun deleteLeftovers() = withContext(Dispatchers.IO) { leftovers.forEach { it.delete() } }

    override suspend fun deleteRecordingsUpTo(upTo: Long?): Boolean = withContext(Dispatchers.IO) {
        if (upTo == null) return@withContext true
        val files = cacheDir.listFiles { f -> f.name.startsWith(PREFIX) } ?: return@withContext false
        files.filter { it.lastModified() <= upTo }.map { it.delete() }.all { it }
    }

    private companion object {
        const val PREFIX = "recording-"
    }
}
