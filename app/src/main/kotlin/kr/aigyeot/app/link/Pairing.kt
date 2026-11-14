package kr.aigyeot.app.link

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kr.aigyeot.app.common.instantOf
import kr.aigyeot.app.store.bestEffortSuspend
import kr.aigyeot.engine.Params

private const val TAG = "Link"
private const val DISCONNECT_FAILED = "연결을 끊지 못했어요. 잠시 뒤에 다시 해 주세요."

/**
 * 연결 화면: 연결 코드 받기·갱신·지우기, 폰 연결 끊기, 폰 목록 새로고침.
 * 화면이 열려 있는지([isOpen])는 여기서만 쓴다. 소켓 깨우기·확인은 생성자 람다로 [Connection]에 맡긴다.
 */
internal class Pairing(
    private val room: LinkRoom,
    private val relay: Relay,
    private val clock: () -> Long,
    private val status: MutableStateFlow<LinkStatus>,
    private val rx: Mutex,
    private val creds: Creds,
    private val inbox: Inbox,
    private val scope: CoroutineScope,
    private val kick: () -> Unit, // 연결 반복을 바로 깨운다
    // 닫은 뒤 마지막 폰 확인
    private val recheck: () -> Unit
) {
    @Volatile var isOpen = false
        private set
    private val pairKick = Channel<Unit>(Channel.CONFLATED) // 무효 뒤·만료 대기 중 연결 코드 반복을 깨운다

    // 연결 코드 받기·지우기를 차례로: DELETE는 차의 코드를 모두 지워 늦으면 새 코드까지 지운다
    private val pairLock = Mutex()

    @Volatile private var pairJob: Job? = null

    fun stop() {
        pairJob?.cancel()
    }

    /** 차 토큰 무효 때(Creds.invalidate): 화면이 열려 있으면 코드를 비우고 코드 반복을 깨운다. */
    fun onInvalidated() {
        if (isOpen) {
            status.update { it.copy(pairCode = null, pairUri = null, pairExpiresAt = null) }
            pairKick.trySend(Unit)
        }
    }

    /** 연결 코드가 쓰였다: 화면이 열려 있으면 새 코드. */
    fun onNewOwner() {
        if (isOpen) open()
    }

    fun open() { // 연결 화면 열림
        isOpen = true
        pairJob?.cancel()
        pairJob = scope.launch { pairLoop() }
        kick()
    }

    /** 코드를 받아 띄우고, 만료되면 화면이 열린 동안 새로 받는다. 처음 한 번 기기 목록을 대조한다. */
    private suspend fun pairLoop() {
        var fails = 0
        var first = true
        while (isOpen) {
            try {
                // 요청 앞에서 비운다: 그 전의 신호(자기 401 등)는 이번 요청이 이미 반영한다. 요청 뒤의 신호(대조의 401 등)는 남겨 바로 새 코드를 받는다
                pairKick.tryReceive()
                val c = creds.credential()
                when (val r = if (c == null) Res.Offline else pairLock.withLock { relay.pairCode(c.token) }) {
                    is Res.Ok -> {
                        if (creds.cred !== c) continue // 그사이 차 토큰 무효: 옛 방의 코드는 띄우지 않고 새 방에서 다시 받는다
                        fails = 0
                        creds.resetRegisterTries()
                        val exp = instantOf(r.value.expiresAt) ?: (clock() + Params().pairCodeTtlMs)
                        status.update { it.copy(pairCode = r.value.code, pairUri = r.value.link, pairExpiresAt = exp, error = null) }
                        if (first && c != null) {
                            first = false
                            inbox.reconcileDevices(c)
                        }
                        val wait = exp - clock()
                        withTimeoutOrNull(if (wait > 0) wait else 30_000) { pairKick.receive() } // 만료가 과거여도 1초마다 새 코드를 받지 않는다
                    }
                    is Res.Err -> if (r.status == 401) {
                        creds.invalidate(c)
                        delay(backoffMs(fails++))
                    } else {
                        delay(r.retryAfterS?.times(1000)?.coerceAtLeast(1000) ?: backoffMs(fails++))
                    }
                    Res.Offline -> delay(backoffMs(fails++))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "연결 코드 오류", e)
                delay(backoffMs(fails++))
            }
        }
    }

    fun close() { // 연결 화면 닫기
        isOpen = false
        pairJob?.cancel()
        pairJob = null
        status.update { it.copy(pairCode = null, pairUri = null, pairExpiresAt = null) }
        scope.launch {
            // 살아 있는 시간을 줄인다. 그사이 화면이 다시 열렸으면 새 코드를 지우지 않는다. 다시 연 화면의 코드 받기는 이 DELETE 뒤에 한다
            pairLock.withLock { if (!isOpen) creds.cred?.let { relay.deletePairCode(it.token) } }
            recheck()
        }
    }

    fun disconnect(deviceId: String) { // 폰 연결 끊기
        scope.launch {
            // Room 읽기·쓰기가 다 실패하면 로그 뒤 실패 안내(조용히 안 먹지 않게). 다시 할 때 중계 삭제는 404로 같은 결과다
            if (!bestEffortSuspend("연결 끊기") {
                    val c = creds.credential() ?: return@bestEffortSuspend status.update { it.copy(error = DISCONNECT_FAILED) }
                    when (val r = relay.deleteDevice(c.token, deviceId)) {
                        is Res.Ok -> unpairHere(deviceId)
                        is Res.Err -> when (r.status) {
                            404 -> unpairHere(deviceId) // 이미 없다: 같은 결과
                            401 -> creds.invalidate(c)
                            else -> status.update { it.copy(error = DISCONNECT_FAILED) }
                        }
                        Res.Offline -> status.update { it.copy(error = DISCONNECT_FAILED) }
                    }
                }
            ) {
                status.update { it.copy(error = DISCONNECT_FAILED) }
            }
        }
    }

    private suspend fun unpairHere(deviceId: String) {
        val out = Applied()
        rx.withLock {
            room.tx { room.unpair(deviceId, 0, clock(), out) }
            inbox.after(out)
        }
        status.update { it.copy(error = null) }
    }

    fun refreshPhones() { // 폰 목록 새로고침
        scope.launch { bestEffortSuspend("폰 목록 새로고침") { creds.credential()?.let { inbox.reconcileDevices(it) } } }
    }
}
