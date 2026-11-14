package kr.aigyeot.phone.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kr.aigyeot.phone.domain.model.BANNER_MS
import kr.aigyeot.phone.domain.model.Banner
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.DisconnectRun
import kr.aigyeot.phone.domain.model.LinkStatus
import kr.aigyeot.phone.domain.model.PAIR_BUSY
import kr.aigyeot.phone.domain.model.PairRun
import kr.aigyeot.phone.domain.model.codeFromLink
import kr.aigyeot.phone.domain.model.isUlid
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.state.LinkSwitch
import kr.aigyeot.phone.domain.state.Notices
import kr.aigyeot.phone.domain.usecase.KeepConnected
import kr.aigyeot.phone.domain.usecase.LoseConnection
import kr.aigyeot.phone.domain.usecase.PairDevice
import kr.aigyeot.phone.domain.usecase.SendOutbox
import kr.aigyeot.shared.Role

/** ViewModel 범위. 화면은 메인 흐름(`viewModelScope`와 같은 디스패처), 시험은 가짜 범위를 넘긴다. ViewModel이 지워지면 취소된다. */
fun vmScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** 원본 StateFlow들에서 화면 상태를 계산한다(저장하지 않고 쓸 때 계산). 처음 값도 같은 함수로 바로 계산한다. */
internal fun <T> ViewModel.derive(vararg sources: StateFlow<*>, compute: () -> T): StateFlow<T> =
    combine(sources.toList()) { compute() }.stateIn(viewModelScope, SharingStarted.Eagerly, compute())

// ---- Root(이동·배너·GONE·완료 화면·연결 링크) ----

data class AppUiState(
    val connection: Connection,
    val pairRun: PairRun?,
    val disconnectRun: DisconnectRun?,
    val banner: Banner?,
    /** 링크·QR로 받은 코드. 확인 화면을 거친 뒤에만 쓴다. 확인 화면 경로에 실으면 비운다. */
    val pendingCode: String?,
    /** 시스템 알림을 눌러 연 인계 알림 id. */
    val openAlert: String?
)

class AppViewModel(
    private val state: PhoneStateRepository,
    private val notices: Notices,
    private val link: LinkSwitch,
    private val pair: PairDevice,
    private val lose: LoseConnection,
    scope: CoroutineScope = vmScope(),
    private val bannerMs: Long = BANNER_MS
) : ViewModel(scope) {
    private val pendingCode = MutableStateFlow<String?>(null)
    private val openAlert = MutableStateFlow<String?>(null)

    val ui: StateFlow<AppUiState> = derive(state.connection, pair.run, link.disconnectRun, notices.banner, pendingCode, openAlert) {
        AppUiState(state.connection.value, pair.run.value, link.disconnectRun.value, notices.banner.value, pendingCode.value, openAlert.value)
    }

    init {
        // 앱 안 배너: 뜬 지 [bannerMs] 뒤 그 배너만 닫는다. 새 배너가 뜨거나 닫히면 앞 기다림은 버리고 새로 센다(소비 코루틴 하나).
        viewModelScope.launch {
            notices.banner.collectLatest { b ->
                if (b == null) return@collectLatest
                delay(bannerMs)
                notices.clearBanner(b.alertId)
            }
        }
    }

    /**
     * 새로 연 화면(저장된 화면 상태 없음)에서만: 지난 연결 결과를 받을 확인 화면이 없다. 실패·지난 결과는 새 링크를 막지 않게 먼저 비우고,
     * 지금 연결의 성공은 남겨 완료 화면으로 보낸다. 화면을 다시 만든 때(회전·시스템이 화면만 없앤 뒤)는 부르지 않는다.
     */
    fun clearStalePairRun() = pair.clearStale()

    /**
     * 시작 화면은 처음 한 번만 정한다. 앞 실행의 강제 끊김을 고르기 전에 죽었으면 끊김 화면부터, 완료 화면을 넘기기 전에 죽었으면 그 화면부터,
     * 연결이 없어도 '먼저 기록만 쓸게요'를 골랐으면 바로 탭으로.
     */
    fun startRoute(): Route = when {
        state.connection.value is Connection.Gone -> Route.Gone
        else -> pair.pendingDoneRole()?.let { Route.Done(it.name) } ?: afterReset()
    }

    /** 이력을 비우고 갈 기본 화면. */
    fun afterReset(): Route = if (state.creds == null && !link.recordsOnly) Route.Intro else Route.Home

    /** 연결 링크. 받은 순간에 연결이 돌고 있으면 버린다. 버렸으면 짧게 보일 글. */
    fun onLink(raw: String): String? {
        val code = codeFromLink(raw) ?: return "올바른 연결 링크가 아니에요"
        if (!pair.canTakeLink()) return PAIR_BUSY
        pendingCode.value = code
        return null
    }

    fun consumePendingCode() {
        pendingCode.value = null
    }

    /** 인계 알림 누름. 밖에서 온 값이라 ULID가 아니면 버린다(경로에 그대로 들어간다). */
    fun onAlert(id: String) {
        if (isUlid(id)) openAlert.value = id
    }

    fun consumeOpenAlert() {
        openAlert.value = null
    }

    fun canTakeLink() = pair.canTakeLink()
    fun takePairDone(r: PairRun) = pair.takeDone(r)
    fun clearPairRun() = pair.clear()
    fun resetAndPair(code: String) = pair.resetAndStart(code)
    fun pendingDoneRole(): Role? = pair.pendingDoneRole()
    fun takeRestoredDone(): Role? = pair.takeRestoredDone()
    fun pairDoneShown() = pair.doneShown()
    fun acknowledgeGone(recordsOnly: Boolean = false) = lose.acknowledgeGone(recordsOnly)
    fun chooseRecordsOnly() = link.chooseRecordsOnly()
    fun dismissBanner() = notices.clearBanner()
}

// ---- 연결·확인 ----

data class ConnectUiState(val run: PairRun?, val lockedUntil: Long?, val connected: Boolean)

class ConnectViewModel(state: PhoneStateRepository, private val pair: PairDevice, scope: CoroutineScope = vmScope()) : ViewModel(scope) {
    val ui: StateFlow<ConnectUiState> = derive(pair.run, pair.lockedUntil, state.connection) {
        ConnectUiState(pair.run.value, pair.lockedUntil.value, state.creds != null)
    }

    fun start(code: String) = pair.start(code)
    fun clear() = pair.clear()
    fun takeDone(r: PairRun) = pair.takeDone(r)
    fun canTakeLink() = pair.canTakeLink()
}

// ---- 위쪽 띠(오프라인·안내·시계) ----

data class StatusUiState(val status: LinkStatus, val notice: String?)

class StatusViewModel(private val connection: KeepConnected, notices: Notices, private val outbox: SendOutbox, scope: CoroutineScope = vmScope()) :
    ViewModel(scope) {
    val ui: StateFlow<StatusUiState> = derive(connection.status, notices.notice) { StatusUiState(connection.status.value, notices.notice.value) }

    fun retryNow() = connection.retryNow()
    fun dismissNotice() = outbox.dismissNotice()
}
