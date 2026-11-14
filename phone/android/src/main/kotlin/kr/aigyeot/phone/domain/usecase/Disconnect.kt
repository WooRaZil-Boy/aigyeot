package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.DisconnectRun
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.SAVE_FAILED
import kr.aigyeot.phone.domain.model.keepingClear
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.CredentialRepository
import kr.aigyeot.phone.domain.repository.Logger
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.RelayClient
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.phone.domain.repository.SongFileStore
import kr.aigyeot.phone.domain.state.GoneCleanupEvent
import kr.aigyeot.phone.domain.state.LinkSwitch
import kr.aigyeot.phone.domain.state.Notices
import kr.aigyeot.shared.Role

// 연결 끊기(사용자 끊기, 강제 끊김, 둘의 공통 정리). 연결 세대가 바뀌면 연결 반복은 KeepConnected가 자격 구독으로 멈춘다.

/** 연결 캐시 지우기(끊기·401 공통). 돌봄 기록과 지우다 만 '기록 지우기'는 남긴다. */
class WipeLink(
    private val state: PhoneStateRepository,
    private val care: CareRepository,
    private val credentials: CredentialRepository,
    private val songs: SongFileStore,
    private val notices: Notices,
    private val link: LinkSwitch,
    private val scope: CoroutineScope,
    private val log: Logger
) {
    /** `c`가 아직 지금 자격일 때만 지운다. 부르는 쪽이 취소돼도(화면이 뒤로 가 연결 반복이 끝남) 앱 수명에서 끝까지 한다. */
    suspend operator fun invoke(c: Credentials): Boolean = scope.async { wipe(c) }.await()

    private suspend fun wipe(c: Credentials): Boolean = link.transitionLock.withLock {
        // 연결 비우기부터 비밀값·녹음 정리까지 한 덩어리다. 새 연결은 이것이 끝난 뒤에 시작된다.
        if (!state.switchTo(null, { PhoneState().keepingClear(it, !care.care.value.migratedFeedings) }, onlyIf = c)) return false
        // 옛 연결의 띠(버림 안내 등)도 걷는다: 상태가 비어 닫아도 지울 것이 없다. 메시지 429 기다림은 그 자격에만 걸린다(SendOutbox).
        notices.clearForWipe()
        if (!credentials.save(null)) {
            log.e("연결 비밀값을 지우지 못했어요")
            link.secretsLeft = true // 다음 실행이 옛 자격을 다시 읽는다: 복구 표시를 남겨 그때 마치게 한다
        }
        songs.purge()
        true
    }
}

/** 어느 요청이든 401, 소켓 닫기 4001("어디서든 → GONE"). 사용자 끊기 중이면 그 끊기에 맡긴다. */
class LoseConnection(private val state: PhoneStateRepository, private val link: LinkSwitch, private val wipe: WipeLink, private val scope: CoroutineScope) {
    /** HTTP 층이 401을 바로 넘긴다(부른 쪽이 취소돼 결과를 받지 못해도 잃지 않게). 그 토큰이 지금 자격일 때만 본다. */
    fun unauthorized(token: String) {
        scope.launch { state.creds?.takeIf { it.token == token }?.let { lost(it) } }
    }

    /** 끊는 중인 자격의 401·4001이면 그 끊기에 맡긴다. 끊기 표시가 이미 적혔으면 '확인됨'으로 바꿔 정리 도중 죽어도 다음 실행이 마치게 한다. */
    private fun absorbed(c: Credentials): Boolean {
        if (!link.leave.absorb(c)) return false
        if (link.leavingId() == c.deviceId) link.leftConfirmed(c)
        return true
    }

    /** 이전 연결의 늦은 401이면 무시한다. 같은 자격의 끊김(같은 401의 콜백과 결과 분기 등)은 하나씩 한다. */
    suspend fun lost(c: Credentials) {
        if (!state.isCurrent(c) || absorbed(c)) return
        link.lostLock.withLock {
            // 잠금을 기다리는 사이 사용자 끊기가 시작됐으면 그 끊기가 결과를 맡는다(실패하면 그 끊기가 GONE으로 보낸다).
            if (!state.isCurrent(c) || absorbed(c)) return
            // 정리를 마친 뒤 GONE을 켠다. 그사이 새 연결을 시작했으면 켜지 않는다: 확인 화면이 그 결과를 맡는다.
            // 바꿔 연결(끊고 연결하기) 중이면 그 연결은 이 끊김 때문에 멈추므로 GONE을 켠다.
            val gen = link.pairGen
            if (link.leavingId() == c.deviceId) {
                // 앞 프로세스가 보낸 사용자 끊기가 중계에 닿았다: 강제 끊김이 아니라 사용자 끊기로 마친다.
                link.leftConfirmed(c) // 401·4001도 끊기 확인이다: 정리 도중 죽어도 다음 실행이 묻지 않고 마친다
                if (wipe(c)) {
                    link.goneCleanup(GoneCleanupEvent.UserLeft(c.deviceId))
                    link.afterUserDisconnect()
                    // 정리하는 사이 새 연결을 시작했으면(이미 실패로 끝났어도) 확인 화면이 결과를 맡는다: 탭으로 옮기지 않는다.
                    if (gen == link.pairGen && !link.pairActive()) {
                        link.setDisconnectRun(DisconnectRun.Done)
                    } else if (!link.secretsLeft) {
                        link.leavingCleared()
                    }
                }
                return
            }
            // 정리한 뒤 GONE 화면을 고르기 전에 죽어도 다음 실행이 GONE을 보이게 먼저 적는다.
            val marked = link.goneCleanupStarted(c.deviceId)
            // 표시를 적는 사이 사용자 끊기가 시작됐다: 그 끊기에 맡기고 표시를 지운다.
            if (absorbed(c)) {
                link.goneCleanup(GoneCleanupEvent.TakenByLeave)
                return
            }
            // 거짓이면 다른 호출이 이미 정리했거나 새 연결로 바뀌었다: 그쪽 표시를 지우지 않는다.
            if (!wipe(c)) return
            if ((gen == link.pairGen && !link.pairActive()) || link.pairReplacing) {
                // 저장 공간이 없어 못 적었으면 정리가 파일을 지운 뒤 한 번 더 적는다(그래도 못 적으면 남길 방법이 없다).
                // 그 사이 새 연결이 시작·완료됐으면 옛 끊김을 켜지 않는다.
                if (!marked) {
                    link.goneCleanupStarted(c.deviceId)
                    if (state.creds != null || ((gen != link.pairGen || link.pairActive()) && !link.pairReplacing)) {
                        link.goneCleanup(GoneCleanupEvent.HandedOver)
                        return
                    }
                }
                state.showGone(c.deviceId)
            } else {
                link.goneCleanup(GoneCleanupEvent.HandedOver) // 새 연결이 확인 화면에서 결과를 맡는다
            }
        }
    }

    /** 끊김 화면에서 골랐다. 저장하지 못했으면 거짓: 화면은 그대로 두고 저장 실패를 알린다(다시 누르면 다시 쓴다). */
    fun acknowledgeGone(recordsOnly: Boolean = false): Boolean {
        if (!link.goneCleanup(GoneCleanupEvent.Acknowledged(recordsOnly))) return false
        state.hideGone()
        return true
    }
}

/** 설정의 '연결 끊기'. 중계의 토큰을 지워야 해서 인터넷이 필요하다. */
class Disconnect(
    private val state: PhoneStateRepository,
    private val relay: RelayClient,
    private val link: LinkSwitch,
    private val wipe: WipeLink,
    private val lose: LoseConnection,
    private val scope: CoroutineScope,
    private val log: Logger
) {
    val run = link.disconnectRun

    fun start() {
        if (link.disconnectActive()) return
        link.pairDuringLeave = false
        link.setDisconnectRun(DisconnectRun.Running)
        link.disconnectJob = scope.launch {
            // 강제 끊김 정리(401·4001)가 돌면 끝을 기다린다. 그쪽이 자격을 지웠으면 GONE이 결과를 맡는다: 이 끊기는 할 일이 없다.
            link.lostLock.withLock { }
            if (state.creds == null) {
                // 이 끊기가 낸 '도는 중'만 걷는다: 그사이 다른 정리(끊기 재개)가 낸 끝남 결과는 화면이 받게 둔다.
                if (link.disconnectRun.value == DisconnectRun.Running) link.setDisconnectRun(null)
                return@launch
            }
            val error = now()
            // '끊고 연결하기'가 이 끊기를 이어받았다: 확인 화면과 새 연결이 결과를 맡는다(홈으로 돌아가면 확인 화면이 사라진다).
            if (link.pairDuringLeave || link.pairActive()) {
                link.setDisconnectRun(null)
                return@launch
            }
            if (error == null && state.creds == null) link.afterUserDisconnect() // 화면과 무관하게(설정 탭이 사라져도)
            // 끊기는 실패했지만 그사이 받은 401·4001로 GONE으로 갔다: GONE 화면이 알리므로 설정에 옛 오류를 남기지 않는다.
            link.setDisconnectRun(
                when {
                    error == null -> DisconnectRun.Done
                    state.creds == null -> null
                    else -> DisconnectRun.Failed(error)
                }
            )
        }
    }

    /** 설정을 떠나면 지난 끊기 실패 글을 걷는다. 끝남 표시는 탭 이동이 쓰니 둔다. */
    fun dismissError() {
        if (!link.disconnectActive() && link.disconnectRun.value is DisconnectRun.Failed) link.setDisconnectRun(null)
    }

    /** 탭이 끊은 뒤 화면(오늘·띠)을 보였다. 그때까지 끊기 표시를 남겨, 그 전에 죽으면 다음 실행이 다시 보인다. */
    fun shown() {
        link.clearDisconnectRun()
        if (state.creds == null && !link.secretsLeft) link.leavingCleared(now = true)
    }

    /** 끊기. 성공하면 null, 실패하면 화면 문구. 설정의 끊기와 '끊고 연결하기'의 끊기가 겹치면 뒤에 온 쪽은 앞의 결과를 기다린다. */
    suspend fun now(): String? {
        link.leavingRun?.takeIf { it.isActive }?.let { return it.await() }
        return scope.async { leaveOnce() }.also { link.leavingRun = it }.await()
    }

    private suspend fun leaveOnce(): String? {
        val c = state.creds ?: return null
        link.leave.start(c)
        // 메인 흐름에서 바로 적는다: `leave.start`와 표시 사이에 401·4001이 끼지 않아, 흡수한 것은 늘 표시가 있을 때 '확인됨'으로 적힌다.
        if (!link.leavingStarted(c)) {
            link.leave.end(true) // 그사이 흡수한 것은 없다(위와 같이 끼지 않음)
            return SAVE_FAILED
        }
        var failed = true
        try {
            return when (val r = relay.deleteMe(c.token)) {
                is RelayResult.Ok -> null.also { left(c) }
                is RelayResult.Err -> if (r.status == 401) null.also { left(c) } else "끊지 못했어요. 잠시 뒤에 다시 해 주세요.".also { log.w("끊기 실패 ${r.status} ${r.code}") }
                RelayResult.Offline, RelayResult.Unreached -> "인터넷이 필요해요"
            }.also { failed = it != null }
        } finally {
            // 끊기가 실패로 끝났는데 그사이 이 연결의 401·4001이 왔다: 중계 토큰은 이미 없으니 GONE으로 간다.
            if (failed) link.goneCleanup(GoneCleanupEvent.LeaveFailed(c.deviceId, link.leave.lostFor(c)))
            if (link.leave.end(failed)) lose.lost(c)
        }
    }

    /** 중계가 끊기를 확인했다(204·401). */
    private suspend fun left(c: Credentials) {
        link.leftConfirmed(c)
        wipe(c)
        // 그사이 같은 기기의 강제 끊김 처리(401·4001)가 켠 GONE을 걷는다. 그 처리가 끝난 뒤에 보도록 같은 잠금을 쓴다.
        link.lostLock.withLock {
            link.goneCleanup(GoneCleanupEvent.UserLeft(c.deviceId))
            state.hideGone(onlyDevice = c.deviceId)
        }
    }

    /**
     * 이 연결이 중계에 남아 있다고 확인됐다(토큰이 받아들여짐): 앞 프로세스의 끊기 표시를 지운다. 그 뒤의 401·4001은 강제 끊김(GONE)이다.
     * 지금 끊는 중이거나 그사이 자격이 바뀌었으면 그 표시는 다른 끊기의 것이라 둔다.
     */
    fun stillPaired(c: Credentials) {
        if (state.isCurrent(c) && link.leavingRun?.isActive != true && link.leavingId() == c.deviceId && !link.leavingDone()) link.leavingCleared()
    }

    /**
     * 앞 프로세스가 사용자 끊기 중이거나 끊은 뒤 화면을 보이기 전에, 또는 강제 끊김 정리 중에 죽었다. 시작 화면을 정하기 전에 부른다.
     * 중계에 묻거나 정리하는 일은 메인 흐름의 다음 차례로 미룬다(앱 시작 정리보다 뒤). 자격을 읽지 못한 실행은 어느 쪽인지 알 수 없으니 표시를 남긴다.
     */
    fun recoverAtStart() {
        if (state.credsUnreadableAtStart) return
        val c = state.creds
        link.leavingId()?.let { id ->
            // 같은 기기의 복구 표시가 있으면 중계가 토큰을 지운 것이 확인됐다. 결과는 사용자 끊기가 맡는다.
            val goneToo = link.goneCleanupPending() == id
            when {
                // 중계가 끊기를 확인했다: 묻지 않고 정리를 마친다(복구 표시는 정리 뒤 LoseConnection이 걷는다).
                c?.deviceId == id && (link.leavingDone() || goneToo) -> later { lose.lost(c) }
                // DELETE가 중계에 닿았는지 모른다. 다시 보내지 않고 중계에 물어, 이미 지웠으면(401) 사용자 끊기로 마치고, 남아 있으면 표시만 지운다.
                c?.deviceId == id -> later { checkLeft(c) }
                // 정리가 끝났다(자격 없음): 끊기 성공과 같은 결과를 낸다. 저장된 화면이 복원돼도 탭의 오늘로 간다.
                c == null -> {
                    if (goneToo) {
                        link.goneCleanup(GoneCleanupEvent.Stale)
                        state.hideGone()
                    }
                    link.afterUserDisconnect()
                    link.setDisconnectRun(DisconnectRun.Done)
                }
                else -> link.leavingCleared()
            }
        }
        if (c != null && link.leavingId() != c.deviceId) {
            link.goneCleanupPending()?.let { id ->
                // 앞 프로세스가 복구 표시를 적고 자격을 지우기 전에 죽었다: 그 강제 끊김 정리를 마친다. 다른 기기 것이면 그 뒤 새로 연결했다.
                if (id == c.deviceId) later { lose.lost(c) } else link.goneCleanup(GoneCleanupEvent.Stale)
            }
        }
    }

    private fun later(block: suspend () -> Unit) {
        scope.launch {
            yield()
            block()
        }
    }

    /** 앞 프로세스가 끊기를 보낸 뒤 죽었다: 이 기기가 중계에 남았는지만 본다. 오프라인이면 표시를 두고 다음 실행에 다시 본다. */
    private suspend fun checkLeft(c: Credentials) {
        if (c.role != Role.OWNER) return // guardian은 GET /v1/devices가 403: 소켓이 붙거나(stillPaired) 401·4001이 오면 확인된다
        when (val r = relay.devices(c.token)) {
            is RelayResult.Ok -> stillPaired(c)
            is RelayResult.Err -> if (r.status == 401) {
                lose.lost(c)
            } else if (r.status < 500) {
                stillPaired(c)
            }
            RelayResult.Offline, RelayResult.Unreached -> Unit // 소켓이 붙으면 다시 확인된다
        }
    }
}
