package kr.aigyeot.phone.domain.usecase

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.FeedTrigger
import kr.aigyeot.phone.domain.model.PairRun
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.SAVE_FAILED
import kr.aigyeot.phone.domain.model.canOpenConfirm
import kr.aigyeot.phone.domain.model.isPairLocked
import kr.aigyeot.phone.domain.model.keepingClear
import kr.aigyeot.phone.domain.model.pairDoneValid
import kr.aigyeot.phone.domain.model.pairLockUntil
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.CredentialRepository
import kr.aigyeot.phone.domain.repository.LinkMarks
import kr.aigyeot.phone.domain.repository.Logger
import kr.aigyeot.phone.domain.repository.Mark
import kr.aigyeot.phone.domain.repository.MarkEdit
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.PushTokens
import kr.aigyeot.phone.domain.repository.RelayClient
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.phone.domain.repository.SongFileStore
import kr.aigyeot.phone.domain.state.GoneCleanupEvent
import kr.aigyeot.phone.domain.state.LinkSwitch
import kr.aigyeot.phone.domain.state.Notices
import kr.aigyeot.shared.Role

/**
 * 코드로 연결하기. 이미 연결된 차가 있으면 먼저 끊고, 둘 다 성공해야 역할이 실린다.
 * 작업은 앱 수명에서 끝나고 화면은 [run]만 본다. 토큰 저장부터 자격 전환·되돌림까지 끊기지 않게 앱 범위에서만 한다.
 */
class PairDevice(
    private val state: PhoneStateRepository,
    private val care: CareRepository,
    private val credentials: CredentialRepository,
    private val relay: RelayClient,
    private val link: LinkSwitch,
    private val disconnect: Disconnect,
    private val lose: LoseConnection,
    private val marks: LinkMarks,
    private val push: PushTokens,
    private val songs: SongFileStore,
    private val recordCare: RecordCare,
    private val notices: Notices,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val log: Logger
) {
    private val _run = MutableStateFlow<PairRun?>(null)
    val run: StateFlow<PairRun?> = _run.asStateFlow()

    private val _lockedUntil = MutableStateFlow<Long?>(null)

    /** 429 잠금이 끝나는 시각(epoch ms). 화면·입력·확인 화면이 같이 본다. 화면을 옮기거나 코드를 고쳐도 풀리지 않는다. */
    val lockedUntil: StateFlow<Long?> = _lockedUntil.asStateFlow()

    /** 마지막 연결이 429였으면 Retry-After 초(헤더 없으면 0), 아니면 -1. */
    private val retryAfterS = AtomicLong(-1L)

    /** 시작할 때 자격을 읽지 못했고 아직 다시 읽어 확정하지 못했다. */
    @Volatile
    private var credsUnresolved = state.credsUnreadableAtStart

    /** 이 실행에서 연결 전 다시 읽기가 연달아 실패한 횟수. */
    private var rereadFailures = 0

    private var doneRestoreChecked = false

    private fun migrating() = !care.care.value.migratedFeedings

    /** 확인 화면의 '연결할게요'·직접 친 코드. */
    fun start(code: String) {
        if (link.pairActive() || isPairLocked(_lockedUntil.value, clock.now())) return
        _run.value = PairRun.Running(code)
        link.pairGen++
        if (link.disconnectActive()) link.pairDuringLeave = true
        link.pairReplacing = state.creds != null
        link.pairJob = scope.launch {
            // 시작할 때 자격을 읽지 못했으면 연결 전에 다시 읽는다(끊기 → 연결).
            when (reread()) {
                Reread.FOUND -> {
                    // 옛 연결이 있었다: 확인 화면이 "끊고 연결하기"로 바뀌어 동의를 다시 받게 멈춘다(수신은 자격 구독이 다시 켠다).
                    // 시작 때 자격을 못 읽어 건너뛴 수유·요약 판단도 한다(앱 시작 계기).
                    recordCare.syncAtStart()
                    _run.value = PairRun.Failed(code, "지금 연결된 차가 있어요. 끊고 연결할지 다시 눌러 주세요.")
                    return@launch
                }
                Reread.FAILED -> {
                    // 옛 연결을 끊지 못한 채 덮어쓰지 않는다(중계에 옛 기기가 남는다). 거듭 실패하면(보안 저장소 고장) 그 정보를 지우고
                    // 새로 연결할지 묻는다: 앱 데이터를 지우지 않고도(돌봄 기록을 잃지 않고) 다시 연결할 길.
                    rereadFailures++
                    _run.value = PairRun.Failed(code, "지금 연결 정보를 읽지 못했어요. 앱을 다시 켠 뒤 해 주세요.", resetOffer = rereadFailures >= REREAD_LIMIT)
                    return@launch
                }
                Reread.NONE -> Unit
            }
            // 설정 끊기가 아직 도는 중이면(메모리 자격은 먼저 비지만 비밀값 정리는 뒤에 끝난다) 그 끝을 기다린 뒤 끊기 표시를 지운다.
            var err = if (state.creds != null || link.leavingRun?.isActive == true) disconnect.now() else null
            link.lostLock.withLock { } // 시작 때 이어 하는 끊김 정리도 비밀값까지 지울 때까지 기다린다
            // 끊고 연결하기: 이 뒤는 연결 결과가 화면을 맡는다. 옛 비밀값을 못 지웠으면 다음 실행이 마치게 남긴다(새 자격을 저장하면 그때 덮인다).
            if (!link.secretsLeft) link.leavingCleared()
            // 끊기는 실패했지만 그사이 받은 401·4001로 GONE으로 갔다. GONE 화면이 알리므로 옛 결과를 남기지 않는다.
            if (err != null && state.creds == null) {
                _run.value = null
                return@launch
            }
            if (err == null) err = pair(code)
            val wait = retryAfterS.getAndSet(-1L).takeIf { it >= 0 } // 429면 잠금 길이(그 글은 화면이 잠금 끝 시각에서 만든다)
            wait?.let { _lockedUntil.value = pairLockUntil(clock.now(), it) }
            _run.value = when {
                wait != null -> PairRun.Limited(code)
                err != null -> PairRun.Failed(code, err)
                else -> PairRun.Done(code, state.creds?.role)
            }
        }
    }

    private enum class Reread { NONE, FOUND, FAILED }

    /**
     * 시작할 때 자격을 읽지 못했으면 연결 전에 다시 읽는다: 읽히면 옛 연결을 먼저 끊는다. 계속 못 읽으면 중계의 옛 기기 행을 지울 수 없다
     * (차 화면에서 지운다).
     */
    private suspend fun reread(): Reread {
        if (state.creds != null || !credsUnresolved) return Reread.NONE
        val c = credentials.load().getOrElse { return Reread.FAILED }
        if (c == null) return Reread.NONE.also { credsUnresolved = false }
        // 시작할 때와 같은 복구를 저장한 뒤에 자격을 공개한다: 상태에 차 id가 없으면 받은 것을 저장하지 않고 ack해 잃는다.
        val ok = state.adopt(c) { s -> PhoneState.recoverIfNeeded(s, c.carId, c.deviceId, c.role, clock.now())?.keepingClear(s, migrating()) }
        if (!ok) return Reread.FAILED
        credsUnresolved = false
        // 시작 때 못 읽어 건너뛴 표시를 본다: 이 자격이 이미 강제 끊김·사용자 끊기를 받았으면 옛 연결로 되살리지 않고 정리를 마친다.
        // 새 연결이 도는 중이라 GONE 화면은 켜지 않는다. 끊기 표시만 있으면 확인 화면의 '끊고 연결하기'가 맡는다.
        if (link.goneCleanupPending() == c.deviceId || (link.leavingId() == c.deviceId && link.leavingDone())) {
            lose.lost(c)
            if (state.creds == null) return Reread.NONE
        }
        return Reread.FOUND
    }

    /**
     * 읽지 못하는 연결 정보를 지우고 그 코드로 새로 연결한다(사용자가 대화에서 동의). 돌봄 기록·이 폰의 상태는 그대로이고,
     * 옛 연결은 중계에 남으니 화면이 차에서 지우라고 알린다. 지우지 못하면 연결하지 않는다.
     */
    fun resetAndStart(code: String) {
        if (link.pairActive()) return
        _run.value = PairRun.Running(code)
        scope.launch {
            if (!credentials.reset()) {
                _run.value = PairRun.Failed(code, SAVE_FAILED)
                return@launch
            }
            credsUnresolved = false
            rereadFailures = 0
            _run.value = null
            start(code)
        }
    }

    /** 지금 새 연결 링크·QR을 받을 수 있는가. 받은 순간과 화면이 처리할 때 모두 이것을 본다. */
    fun canTakeLink(): Boolean = _run.value.let { canOpenConfirm(pairBusy = it is PairRun.Running, doneUnshown = it is PairRun.Done && it.role != null) }

    /** 지금 연결의 성공인가(완료 화면으로 가도 되는가). */
    private fun doneValid(r: PairRun): Boolean = r is PairRun.Done && pairDoneValid(r.role, state.connection.value is Connection.Gone, state.creds?.role)

    /** 화면이 끝난 연결 결과로 완료 화면에 가기 직전에 부른다. 지금 연결의 성공일 때만 결과를 비우고 참. 그사이 GONE이면 거짓. */
    fun takeDone(r: PairRun): Boolean {
        if (_run.value != r || !doneValid(r)) return false
        _run.value = null
        return true
    }

    fun clear() {
        if (!link.pairActive()) _run.value = null
    }

    /** 새로 연 화면에서: 끝난 결과 중 지금 연결의 성공은 남겨 완료 화면으로 보내게 하고, 실패·지난 결과만 비워 새 링크를 막지 않는다. */
    fun clearStale() {
        val r = _run.value ?: return
        if (link.pairActive()) return
        if (!doneValid(r)) _run.value = null
    }

    /** 지금 연결의 완료 화면을 아직 넘기지 않았으면 그 역할(앞 프로세스가 완료 화면 전·중에 죽음). 시작 화면을 정할 때 본다. */
    fun pendingDoneRole(): Role? = (state.connection.value as? Connection.Paired)?.creds?.takeIf { marks.get(Mark.PAIR_DONE) == it.deviceId }?.role

    /**
     * 새 프로세스의 첫 화면에서만: 저장된 화면 이력이 복원돼 시작 화면을 쓰지 않았어도 완료 화면으로 보낼 역할.
     * 같은 프로세스에서 화면만 다시 만들면(회전) null이다: 그사이 받은 새 링크의 확인 화면을 지우지 않게.
     */
    fun takeRestoredDone(): Role? = if (doneRestoreChecked) null else pendingDoneRole().also { doneRestoreChecked = true }

    /** 완료 화면을 넘겼다(알림 권한 답 뒤). 바로 쓴다: 넘긴 뒤 곧 죽어도 완료 화면이 다시 뜨지 않게. 저장하지 못했으면 거짓. */
    fun doneShown(): Boolean {
        val id = marks.get(Mark.PAIR_DONE)
        if (marks.commit(MarkEdit.Remove(Mark.PAIR_DONE))) return true
        // 실패한 commit도 메모리 값은 지웠다: 되돌려 회전해도 완료 화면에 남게 한다(디스크에는 아직 있다).
        marks.apply(MarkEdit.Put(Mark.PAIR_DONE, id))
        return false
    }

    /**
     * 중계에 새 기기를 만들었지만 이 폰에 저장하지 못했다: 받은 토큰으로 그 기기를 바로 지운다(1.4. 남으면 차의 기기 목록에 쓸모없는
     * 연결이 남고 guardian이면 인계 알림 푸시도 간다). 지우지 못하면 차 화면에서 지울 수 있다. 화면 문구는 저장 실패 그대로다.
     */
    private suspend fun discardPaired(c: Credentials): String {
        marks.apply(MarkEdit.Remove(Mark.PAIR_DONE))
        val removed = relay.deleteMe(c.token).let { it is RelayResult.Ok || (it is RelayResult.Err && it.status == 401) }
        return if (removed) "연결 정보를 저장하지 못했어요" else "연결 정보를 저장하지 못했어요. 차 화면에서 이 휴대폰 연결을 끊어 주세요."
    }

    /** 코드 쓰기. 실패하면 화면 문구. */
    private suspend fun pair(code: String): String? {
        val token = push.token()
        return when (val r = relay.pair(code, token, marks.installId)) {
            is RelayResult.Ok -> link.transitionLock.withLock {
                // 진행 중인 옛 연결 정리(지우기)가 끝난 뒤에만 새 토큰·상태·파일을 바꾼다. 옛 연결 반복은 자격이 바뀌면 멈춘다.
                val c = Credentials(r.value.carId, r.value.deviceId, r.value.deviceToken, r.value.role)
                val prev = state.creds
                // 완료 화면(guardian 알림 권한)을 보이기 전에 죽어도 다음 실행이 그 화면부터 보이게 먼저 적는다.
                // 토큰을 디스크에 쓴 것이 확인된 뒤에 상태를 커밋하고 자격을 공개한다.
                if (!(marks.commitInBackground(MarkEdit.Put(Mark.PAIR_DONE, c.deviceId)) && credentials.save(c))) return discardPaired(c)
                link.secretsLeft = false // 옛 비밀값을 새 자격이 덮었다
                if (!state.switchTo(c, { PhoneState.paired(c.carId, c.deviceId, c.role, clock.now()).keepingClear(it, migrating()) })) {
                    credentials.save(prev)
                    return discardPaired(c)
                }
                // 화면 띠·GONE 초기화는 자격을 공개한 바로 뒤, 멈추는 곳(파일 정리) 전에 한다: 그 사이 새 자격의 401·4001이 적은
                // 표시를 뒤늦게 지우지 않게(iOS `pair`와 같은 순서). GONE은 자격을 바꾼 저장에서 이미 걷혔다.
                notices.clearForPair()
                link.clearDisconnectRun() // 지난 끊기 결과가 새 연결의 설정 화면을 닫지 않게
                link.goneCleanup(GoneCleanupEvent.Paired)
                songs.clean()
                recordCare.syncToCar(FeedTrigger.PAIRED) // 새 차에는 24시간 안의 최신 수유가 다시 간다
                null
            }
            is RelayResult.Err -> when {
                r.code == "code_invalid" -> "코드가 맞지 않거나 시간이 지났어요. 차 화면의 코드를 다시 확인해 주세요."
                r.code == "bad_code_format" -> "6자리 숫자 또는 초대 코드를 넣어 주세요"
                r.status == 429 -> {
                    retryAfterS.set(r.retryAfterS ?: 0L)
                    "rate_limited" // 화면 글이 아니다: 결과는 PairRun.Limited가 되고 글은 화면이 잠금 끝 시각에서 만든다
                }
                // 화면 문구는 앱이 정한다: 중계의 코드는 로그에만 남긴다(iOS와 같은 글).
                else -> "연결하지 못했어요. 잠시 뒤에 다시 해 주세요.".also { log.w("연결 실패 ${r.status} ${r.code}") }
            }
            RelayResult.Offline, RelayResult.Unreached -> "인터넷에 연결한 뒤 다시 해 주세요"
        }
    }

    private companion object {
        const val REREAD_LIMIT = 3 // 연결 정보 읽기가 이만큼 연달아 실패하면 지우고 새로 연결할지 묻는다(설계값)
    }
}
