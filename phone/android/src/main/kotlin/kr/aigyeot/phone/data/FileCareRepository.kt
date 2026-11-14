package kr.aigyeot.phone.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.Logger

/** 돌봄 기록(`care.json`). 연결과 상관없이 쓴다. 끊어도 지우지 않는다. */
class FileCareRepository(
    file: File,
    /** 내 기록 내보내기 캐시. 기록 지우기가 함께 지운다. */
    private val exportDir: File,
    private val log: Logger,
    private val onSaveFailed: () -> Unit
) : CareRepository {
    private val store = CareStore(file)
    private val lock = Mutex()
    private val _care = MutableStateFlow(store.load())
    override val care: StateFlow<CareFile> = _care.asStateFlow()

    override suspend fun update(f: (CareFile) -> CareFile?): Boolean = lock.withLock {
        val next = f(_care.value) ?: return@withLock false
        if (next == _care.value) return@withLock true
        if (!store.saveLogged(next, log, "돌봄 기록 저장 실패", onSaveFailed)) return@withLock false
        _care.value = next
        true
    }

    override suspend fun <T> locked(block: suspend () -> T): T = lock.withLock { block() }

    override suspend fun deleteCopies(): Boolean = withContext(Dispatchers.IO) {
        val backup = store.backup.let { !it.exists() || it.delete() }
        // 내보낸 CSV와, 이름을 바꾸기 전에 죽어 남은 돌봄 임시 파일(저장과 같은 잠금)
        val rest = lock.withLock { exportDir.let { !it.exists() || it.deleteRecursively() } && store.discardTemp() }
        backup && rest
    }

    override suspend fun export(fileName: String, render: (CareFile) -> String): File? = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                exportDir.mkdirs()
                exportDir.listFiles()?.forEach { it.delete() } // 지난 내보내기 파일은 남기지 않는다
                File(exportDir, fileName).apply { writeText(render(_care.value), Charsets.UTF_8) }
            }.onFailure { log.e("내보내기 실패", it) }.getOrNull()
        }
    }
}
