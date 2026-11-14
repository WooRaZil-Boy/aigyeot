package kr.aigyeot.phone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.CredentialRepository
import kr.aigyeot.phone.domain.repository.Logger
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import java.io.File

/**
 * `phone_state.json`과 지금 자격(연결 세대)의 원본. 만들 때 자격을 읽고, 상태가 그 차 것이 아니면 그 차의 빈 상태로 복구한다.
 * 토큰은 썼는데 상태 파일을 바꾸기 전에 앱이 죽었으면 다른 차의 상태다. 자격이 있는데 읽지 못했으면(예외) 이번 실행은
 * 연결 없이 두되 지난 상태는 그대로 쓴다: 빈 상태로 보면 녹음 정리가 남은 녹음을 지우고, 기록 지우기가 차 정보 없는 상태 파일을 써
 * 다음 실행의 연결이 받는 것을 모두 버린다.
 */
class FilePhoneStateRepository(
    file: File,
    credentials: CredentialRepository,
    private val log: Logger,
    clock: Clock,
    /** 수유 옮기기를 아직 마치지 못했다: 연결이 바뀌어도 옮길 원본 수유 기록은 남긴다. */
    migrating: () -> Boolean,
    /** commit·commitLocal의 저장 실패(U·W·R 범주): 띠로 알린다. */
    private val onSaveFailed: () -> Unit,
    /** 앞 프로세스가 강제 끊김을 정리한 뒤 GONE 화면에서 고르기 전에 죽었으면 그 기기 id(재시작 복구 기록). */
    goneCleanupPending: String?,
) : PhoneStateRepository {
    private val store = StateStore(file)

    /** 상태와 연결을 함께 바꾸는 잠금. 다른 잠금(전환·파일·돌봄) 안에서 잡을 수 있고, 이 안에서는 다른 잠금을 잡지 않는다. */
    private val lock = Mutex()

    private val credsRead = runCatching { credentials.loadBlocking() }.onFailure { log.e("연결 정보를 읽지 못함", it) }
    override val credsUnreadableAtStart = credsRead.isFailure
    private val _connection = MutableStateFlow(
        credsRead.getOrNull()?.let { Connection.Paired(it) }
            ?: goneCleanupPending?.takeIf { credsRead.isSuccess }?.let { Connection.Gone(it) }
            ?: Connection.None,
    )
    override val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val loaded = store.load()
    private val recovered = creds?.let { c -> PhoneState.recoverIfNeeded(loaded, c.carId, c.deviceId, c.role, clock.now()) }
    private val startState = PhoneState.atStart(loaded, recovered, connected = creds != null || credsRead.isFailure, migrating = migrating())
    private val _state = MutableStateFlow(startState)
    override val state: StateFlow<PhoneState> = _state.asStateFlow()

    override suspend fun commit(c: Credentials, afterSave: (() -> Unit)?, f: (PhoneState) -> PhoneState): Boolean =
        lock.withLock { (isCurrent(c) && write(f)).also { if (it) afterSave?.invoke() } }

    override suspend fun commitLocal(f: (PhoneState) -> PhoneState): Boolean = lock.withLock { write(f) }

    /** [lock] 안에서만 부른다. */
    private suspend fun write(f: (PhoneState) -> PhoneState): Boolean {
        val next = f(_state.value)
        if (next == _state.value) return true
        if (!store.saveLogged(next, log, "상태 저장 실패", onSaveFailed)) return false
        _state.value = next
        return true
    }

    override suspend fun switchTo(next: Credentials?, make: (PhoneState) -> PhoneState, onlyIf: Credentials?): Boolean = lock.withLock {
        if (onlyIf != null && !isCurrent(onlyIf)) return@withLock false
        val s = make(_state.value) // 잠금 안의 최신 상태에서 만든다(그사이 저장된 지울 id를 잃지 않게)
        val ok = store.saveLogged(s, log, "상태 저장 실패")
        // 끊을 때는 저장이 실패해도 자격은 잊는다. 연결할 때는 저장이 돼야 바꾼다.
        if (!ok && next != null) return@withLock false
        _state.value = s
        _connection.value = next?.let { Connection.Paired(it) } ?: Connection.None
        true
    }

    override suspend fun adopt(c: Credentials, recover: (PhoneState) -> PhoneState?): Boolean = lock.withLock {
        if (creds != null) return@withLock true
        val rec = recover(_state.value)
        if (rec != null) {
            if (!store.saveLogged(rec, log, "상태 저장 실패")) return@withLock false
            _state.value = rec
        }
        _connection.value = Connection.Paired(c)
        true
    }

    // GONE ↔ 연결 없음은 상태 파일을 바꾸지 않으니 잠금 없이 값 하나를 원자적으로 바꾼다(자격이 바뀌는 곳은 위 잠금 안뿐이다).
    override fun showGone(deviceId: String) {
        // 그사이 새로 연결했으면(자격이 있음) 옛 끊김을 켜지 않는다: 확인 화면이 그 결과를 맡는다.
        _connection.update { if (it is Connection.Paired) it else Connection.Gone(deviceId) }
    }

    override fun hideGone(onlyDevice: String?) {
        _connection.update { if (it is Connection.Gone && (onlyDevice == null || it.deviceId == onlyDevice)) Connection.None else it }
    }

    override suspend fun saveRecoveredStart() {
        // 백업하지 못했으면 저장은 거부된다(다음 실행이 다시 복구한다).
        if (recovered != null) lock.withLock { if (_state.value === startState) store.saveLogged(startState, log, "상태 저장 실패") }
    }

    override fun recoveryPending(): Boolean = store.backup.exists() || store.unsafe

    override suspend fun deleteRecoveryCopy(): Boolean = withContext(Dispatchers.IO) { store.backup.let { !it.exists() || it.delete() } }

    override suspend fun discardRecovery() = withContext(Dispatchers.IO) {
        // 지우다 만 '기록 지우기'가 있으면 그 표시를 담은 새 상태 파일은 남긴다. 옮기기를 마치지 못한 원본 수유 기록도 같은 이유로 남긴다.
        // 판단과 삭제를 잠금 안에서 한다(그사이 저장된 지울 id를 지우지 않게).
        lock.withLock { store.discardRecovery(keepFile = _state.value.let { it.clearingCareIds != null || it.migrationFeedings.isNotEmpty() }) }
    }
}
