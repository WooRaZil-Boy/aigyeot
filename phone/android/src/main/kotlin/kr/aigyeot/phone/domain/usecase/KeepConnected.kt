package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.DEVICE_POLL_MS
import kr.aigyeot.phone.domain.model.LinkStatus
import kr.aigyeot.phone.domain.model.PING_FIRST_MS
import kr.aigyeot.phone.domain.model.PING_REST_MS
import kr.aigyeot.phone.domain.model.PONG_WAIT_MS
import kr.aigyeot.phone.domain.model.RATE_LIMITED_WAIT_MS
import kr.aigyeot.phone.domain.model.SAVE_RETRY_MS
import kr.aigyeot.phone.domain.model.STATE_REQUEST_GAP_MS
import kr.aigyeot.phone.domain.model.backoffMs
import kr.aigyeot.phone.domain.model.creds
import kr.aigyeot.phone.domain.model.envelope
import kr.aigyeot.phone.domain.model.mergeDevices
import kr.aigyeot.phone.domain.model.onAppOpen
import kr.aigyeot.phone.domain.model.rateLimitWaitMs
import kr.aigyeot.phone.domain.model.shouldRequestState
import kr.aigyeot.phone.domain.model.stateRequestDone
import kr.aigyeot.phone.domain.model.withMessageHold
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.NetworkWatcher
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.RelayClient
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.phone.domain.repository.RelaySocket
import kr.aigyeot.phone.domain.repository.SocketHandle
import kr.aigyeot.phone.domain.repository.SocketListener
import kr.aigyeot.phone.domain.state.AppVisibility
import kr.aigyeot.shared.Role
import java.util.concurrent.atomic.AtomicLong

/**
 * 연결 유지(계약 2장·7.2·7.4). 폰은 앱이 화면 앞에 있을 때만 WebSocket으로 붙는다. 연결 반복은 한 자격(연결 세대)으로만 돈다.
 * 수명(P6): 자격이 바뀌면(끊기·401·새 연결) 이 클래스가 자격 구독 한 곳에서 반복을 멈추고 표시를 처음으로 돌린 뒤, 앞에 있으면 새 자격으로 다시 연다.
 */
class KeepConnected(
    private val state: PhoneStateRepository,
    private val relay: RelayClient,
    private val socket: RelaySocket,
    private val inbox: SyncInbox,
    private val outbox: SendOutbox,
    private val lose: LoseConnection,
    private val disconnect: Disconnect,
    private val push: RegisterPush,
    private val network: NetworkWatcher,
    private val visibility: AppVisibility,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(LinkStatus())

    /** 오프라인·소켓·시계 차이(9장·7.2). 쓰는 곳은 이 클래스뿐이다. */
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var open: SocketHandle? = null
    private var stateRetry: Job? = null
    private var retryCheck: Job? = null

    /** 마지막으로 반영한 중계 관찰의 요청 순번(메인 흐름). 먼저 시작한 요청의 늦은 결과가 뒤의 관찰을 덮지 않게 한다(iOS `linkSeen`과 같음). */
    private var observedTicket = 0L

    /** 기기 목록 요청을 시작한 순번과, 마지막으로 적용한 목록의 (snapshot_seq, 요청 순번). 순번은 같은 snapshot_seq 안에서만 비교한다. */
    private val devicesTicket = AtomicLong()

    @Volatile
    private var appliedDevices = -1L to 0L

    init {
        scope.launch {
            state.connection.map { it.creds }.distinctUntilChanged().collect {
                stop()
                _status.value = LinkStatus()
                start()
            }
        }
    }

    /** 앞에 있을 때만 연결 반복을 연다. 앱 범위 작업이 뒤에서 불러도 열지 않는다. */
    fun start() {
        if (!visibility.visible.value || loop?.isActive == true || state.creds == null) return
        network.watch {
            kick.trySend(Unit) // 기다림을 건너뛰고 바로 다시 붙고, 보낼 함의 망 실패 기다림도 당겨 비운다(7.5)
            outbox.hurryNetworkWaits()
        }
        loop = scope.launch { connectLoop() }
    }

    fun stop() {
        network.unwatch()
        loop?.cancel()
        open?.close(1000)
        open = null
    }

    /**
     * 오프라인 띠의 '다시 시도'(7.2): 연결 반복의 기다림을 건너뛰어 다시 붙고, 중계에 한 번 물어 닿으면 띠를 끈다(iOS `retryNow`와 같은 뜻).
     * 보낼 함은 망 실패 기다림만 당긴다(7.5, 사용자 결정 11-07). 429 Retry-After·5xx 백오프와 받은 함 429 기다림은 지킨다.
     */
    fun retryNow() {
        kick.trySend(Unit)
        start()
        outbox.hurryNetworkWaits()
        // 기기 목록은 owner만 볼 수 있다(guardian은 403): guardian은 다시 붙기(소켓이 열리면 온라인)에 맡긴다. 거듭 눌러도 한 번만 묻는다.
        val c = state.creds?.takeIf { it.role == Role.OWNER } ?: return
        if (retryCheck?.isActive == true) return
        retryCheck = scope.launch { relay.devices(c.token) } // 닿으면 관찰(`observed`)이 온라인으로 바꾼다. 401은 콜백이 맡는다
    }

    /**
     * 토큰을 실은 모든 HTTP 요청의 결과(9장 '중계 불통'): 상태 요청·푸시 등록·ack·알림 설정도 오프라인 띠에 반영한다.
     * IO 스레드에서 불리므로 메인 흐름으로 넘겨, 토큰·순번 확인과 표시 변경을 연결 전환과 끼어들 틈 없이 한다.
     */
    fun observed(token: String, ticket: Long, up: Boolean) {
        scope.launch {
            // 지금 연결의 토큰인지 먼저 본다: 옛 연결의 늦은 응답이 순번만 올려 새 연결의 관찰을 막지 않게.
            val c = state.creds?.takeIf { it.token == token } ?: return@launch
            if (ticket <= observedTicket) return@launch
            observedTicket = ticket
            setOnline(c, up)
        }
    }

    private fun setOnline(c: Credentials, online: Boolean) {
        if (state.isCurrent(c)) _status.update { it.copy(online = online) }
    }

    /** 소켓이 붙거나 협상이 5xx로 끝났다(메인 흐름). 그 전에 시작한 HTTP 요청의 늦은 결과는 이 관찰을 덮지 않는다. */
    private fun observedBySocket(c: Credentials, up: Boolean) {
        observedTicket = maxOf(observedTicket, relay.lastTicket())
        setOnline(c, up)
    }

    /** 시작 저장에 실패해 연결 반복을 열지 못했다(W 범주): 앞에 있는 동안 잠시 뒤 다시 연다(iOS `scheduleStaleRetry`와 같음). */
    private fun retryConnectLater(c: Credentials) {
        scope.launch {
            delay(SAVE_RETRY_MS)
            if (state.isCurrent(c) && loop?.isActive != true) {
                loop = null
                start()
            }
        }
    }

    /** 한 자격(연결 세대)으로만 돈다. 자격이 바뀌면 [stop]의 취소로 끝난다. */
    private suspend fun connectLoop() {
        val c = state.creds ?: return
        // WebSocket·받은 함을 처리하기 전에 24시간을 본다(7.4).
        if (!outbox.commitPruned(c) { it.onAppOpen(clock.now()) }) return retryConnectLater(c)
        push.now(c)
        var attempts = 0
        while (state.isCurrent(c)) {
            // 받은 함 429 기다림 중이면 기기 목록·소켓도 열지 않고 기한 뒤에 다시 한다(1.1, iOS `beginRefresh`와 같음). 깨워도 기한을 다시 본다.
            inbox.inboxHoldUntil(c, clock.now())?.let { until ->
                outbox.pump() // 보낼 함은 따로 보낸다(iOS와 같음)
                kick.tryReceive()
                withTimeoutOrNull(until - clock.now()) { kick.receive() }
                continue
            }
            // 붙을 때마다(통신이 돌아올 때 포함) 기기 목록을 다시 받고 상태 요청이 필요한지 다시 본다(7.4).
            if (!refreshDevices(c)) return
            val code = runSocket(c) { attempts = 0 }
            when (code) {
                4001, 401 -> return // 소켓 콜백이 이미 LoseConnection에 넘겼다
                4002 -> return // 같은 기기의 새 연결로 바뀜
                in 500..599 -> observedBySocket(c, false) // 소켓 협상 5xx: 중계 장애(9장, iOS `unreachable`과 같음)
            }
            // 붙지 못했거나 끊긴 사이 온 것은 HTTPS로 받는다.
            if (inbox.sync(c) == SyncInbox.Sync.LOST) return
            outbox.pump()
            val wait = if (code == 4008 || code == 429) RATE_LIMITED_WAIT_MS else backoffMs(attempts++)
            kick.tryReceive() // 연결 중 쌓인 신호로 간격 없이 재접속하지 않는다
            withTimeoutOrNull(wait) { kick.receive() }
        }
    }

    /** owner: 기기 목록(차 타일) → 필요하면 state_request(7.4). 401이거나 세대가 바뀌었으면 거짓. 기록 지우기 뒤에도 부른다. */
    suspend fun refreshDevices(c: Credentials): Boolean {
        val fetched = fetchDevices(c)
        if (fetched == true) requestState(c)
        return if (fetched == null) false else state.isCurrent(c)
    }

    /**
     * 기기 목록만 받아 차 타일을 맞춘다. 받아 저장했으면 참, 못 받았으면(오프라인·owner 아님·저장 실패) 거짓,
     * 401이면 연결을 끝내고 null을 돌려 부른 쪽이 멈추게 한다.
     */
    private suspend fun fetchDevices(c: Credentials): Boolean? {
        if (c.role != Role.OWNER) return false
        var ok = false
        val carMark = inbox.carLiveMark()
        val ticket = devicesTicket.incrementAndGet()
        when (val r = relay.devices(c.token)) {
            is RelayResult.Ok -> {
                // 같은 snapshot_seq에서 뒤에 시작한 목록을 이미 적용했으면 이 늦은 응답은 버린다(iOS `appliedDevicesTicket`과 같은 뜻).
                // 목록을 기다리는 사이 받은 차의 실시간 봉투는 차 행을 켜진 채로 둔다. 둘 다 잠금 안에서 본다.
                // 더 큰 snapshot_seq면 늘 적용하고(작으면 mergeDevices가 버린다), 같으면 뒤에 시작한 요청이 이긴다. 적용 순번은 저장한 뒤 같은 잠금 안에서 올린다.
                var merged = false
                val seq = r.value.snapshotSeq
                val saved = state.commit(c, afterSave = { if (merged) appliedDevices = seq to ticket }) { s ->
                    if (s.devicesSnapshotSeq != null && seq < s.devicesSnapshotSeq) return@commit s // 더 오래된 목록
                    if (seq == appliedDevices.first && ticket < appliedDevices.second) return@commit s // 같은 목록의 앞선 요청
                    merged = true
                    s.mergeDevices(seq, r.value.devices, inbox.carLiveSince(carMark))
                }
                if (!saved) return false
                ok = true
            }
            is RelayResult.Err -> if (r.status == 401) return null.also { lose.lost(c) }
            RelayResult.Offline, RelayResult.Unreached -> Unit // 온라인 표시는 HTTP 층의 관찰(`observed`)이 맞춘다
        }
        if (state.isCurrent(c)) _status.update { it.copy(clockSkewMin = relay.clockSkewMs?.let { ms -> ms / 60_000 }) }
        return ok
    }

    /** 10분 제한을 보고 요청 시각을 저장하는 것을 한 잠금 안에서 한다. 예약에 성공한 호출만 보낸다. */
    private suspend fun requestState(c: Credentials) {
        // 메시지 429 기다림 중이면 끝난 뒤 목록 확인부터 다시 한다(표시는 남아 있다).
        outbox.holdUntil(clock.now())?.let { return retryStateAt(c, it) }
        var reserved = false
        var gen = 0
        var before: Long? = null
        val at = clock.now()
        val ok = state.commit(c) { s ->
            if (!s.shouldRequestState(at)) return@commit s
            reserved = true
            gen = s.stateNeedGen
            before = s.lastStateRequestAt
            s.copy(lastStateRequestAt = at)
        }
        if (!ok) return
        if (!reserved) {
            // 10분 안에 보냈다(또는 되돌리지 못한 예약이 남았다): 표시가 남아 있으면 그때 다시 판단한다.
            val s = state.state.value
            if (s.role == Role.OWNER && s.stateRequestNeeded) s.lastStateRequestAt?.let { retryStateAt(c, it + STATE_REQUEST_GAP_MS) }
            return
        }
        // 예약을 저장하는 사이 다른 메시지가 429를 받았으면 보내지 않는다: 예약을 되돌려 10분 제한에 걸리지 않게 하고, 끝나면 다시 판단한다.
        outbox.holdUntil(clock.now())?.let { until ->
            state.commit(c) { if (it.lastStateRequestAt == at) it.copy(lastStateRequestAt = before) else it }
            return retryStateAt(c, until)
        }
        // 보내기와 결과 처리는 앱 범위에서 끝낸다: 화면이 뒤로 가 연결 반복이 취소돼도 429 기다림을 잃지 않게.
        scope.launch { sendStateRequest(c, gen) }.join()
    }

    private suspend fun sendStateRequest(c: Credentials, gen: Int) {
        when (val r = relay.send(c.token, envelope("state_request", buildJsonObject {}, clock.now()))) {
            is RelayResult.Ok -> if (r.status == 201) state.commit(c) { it.stateRequestDone(gen) }
            is RelayResult.Err -> when (r.status) {
                401 -> lose.lost(c)
                // 상태 요청도 메시지 POST다: 같은 기다림에 넣어 보낼 함도 지키고, 끝나면 다시 판단한다(1.1). 옛 연결의 늦은 429는 새 연결을 멈추지 않는다.
                429 -> if (state.isCurrent(c)) rateLimitWaitMs(r.retryAfterS).let { w ->
                    outbox.holdInMemory(c, clock.now() + w)
                    state.commit(c) { it.withMessageHold(clock.now(), w) }
                    retryStateAt(c, clock.now() + w)
                }
            }
            RelayResult.Offline, RelayResult.Unreached -> Unit // 표시를 둔 채 다음 연결 때 다시 판단한다(보낼 함에 넣지 않는다)
        }
    }

    /** 429 기다림 때문에 미룬 상태 요청: 끝나면 목록 확인부터 다시 판단한다(7.4 '목록 성공 뒤'). */
    private fun retryStateAt(c: Credentials, at: Long) {
        stateRetry?.cancel()
        stateRetry = scope.launch {
            delay(at - clock.now())
            if (state.isCurrent(c) && visibility.visible.value) refreshDevices(c)
        }
    }

    /**
     * WebSocket 하나를 열어 닫힐 때까지 돈다. 닫기 코드(실패면 HTTP 코드 또는 -1)를 돌려준다.
     * 소켓으로 들어온 프레임은 모두 실시간이다(iOS와 같음). 붙으면 받은 함을 HTTPS로 다 받고, 받은 함에서 이미 저장한 id는 receive가 거른다.
     */
    private suspend fun runSocket(c: Credentials, onOpen: () -> Unit): Int {
        val frames = Channel<String>(Channel.UNLIMITED)
        var closeCode = -1
        // 4001·업그레이드 401은 콜백에서 바로 앱 범위로 넘긴다. 받은 함 처리 중이거나 화면이 뒤로 가 이 반복이 취소돼도 잃지 않는다(7.1).
        fun lostIf(code: Int) {
            if (code == 4001 || code == 401) scope.launch { lose.lost(c) }
        }
        val ws = socket.open(
            c.token,
            object : SocketListener {
                override fun onOpen() {
                    frames.trySend(OPENED)
                }

                override fun onMessage(text: String) {
                    frames.trySend(text)
                }

                override fun onClosing(code: Int) {
                    closeCode = code
                    frames.close()
                    lostIf(code)
                }

                override fun onFailure(httpCode: Int?) {
                    closeCode = httpCode ?: -1
                    frames.close()
                    lostIf(closeCode)
                }
            },
        )
        open = ws
        var lastPong = clock.now()
        // 앞에 있고 소켓이 붙어 있는 동안 기기 목록만 다시 받아 차 타일을 맞춘다. 소켓과 같이 끝난다.
        val devicePoll = scope.launch {
            while (isActive) {
                delay(DEVICE_POLL_MS)
                // 목록을 받았으면 상태 요청 표시도 다시 판단한다(7.4 '목록 성공 뒤'): 앞 판단이 목록 실패로 끊겼어도 이어진다.
                when (fetchDevices(c)) {
                    null -> return@launch
                    true -> requestState(c)
                    false -> Unit
                }
            }
        }
        val pinger = scope.launch {
            // 글자 ping, 보낸 뒤 10초 안에 pong이 없으면 끊고 다시 붙는다(2장).
            delay(PING_FIRST_MS)
            while (isActive) {
                val sentAt = clock.now()
                ws.send("ping")
                delay(PONG_WAIT_MS)
                if (lastPong < sentAt) ws.cancel()
                delay(PING_REST_MS)
            }
        }
        try {
            for (text in frames) {
                if (!state.isCurrent(c)) break
                when (text) {
                    OPENED -> {
                        observedBySocket(c, true)
                        disconnect.stillPaired(c)
                        // 도는 받기(푸시 등)는 이 접속 전 것이라 끝난 뒤 한 번 더 받는다. 실패하면 끊고 기다림 뒤 다시 붙는다
                        if (inbox.sync(c, fresh = true) != SyncInbox.Sync.DONE) break
                        onOpen() // 받은 함까지 저장했을 때만 기다림을 처음으로(저장이 계속 실패하면 늘어난 간격으로 다시 붙는다, iOS와 같음)
                        outbox.pump()
                    }
                    "pong" -> {
                        lastPong = clock.now()
                        observedBySocket(c, true) // 소켓이 살아 있으면 중계에 닿는다: 앞서 HTTP 실패로 켠 오프라인 띠를 끈다(9장)
                    }
                    else -> {
                        observedBySocket(c, true)
                        if (!handleFrame(c, ws, text)) break // 저장 실패: 닫고 재접속·받은 함으로 다시 받는다
                    }
                }
            }
        } finally {
            pinger.cancel()
            devicePoll.cancel()
            ws.cancel()
            if (open === ws) open = null
        }
        return closeCode
    }

    /** 저장하지 못했으면 거짓: ack 없이 연결을 닫게 해, 재접속 뒤 받은 함에서 다시 받는다(5.6, 8.3). */
    private suspend fun handleFrame(c: Credentials, ws: SocketHandle, text: String): Boolean {
        val m = socket.read(text) ?: return true
        if (!inbox.store(c, m, live = true)) return !state.isCurrent(c) // 세대가 바뀐 경우는 반복이 알아서 끝난다
        ws.send(socket.ackFrame(listOf(m.id)))
        if (state.state.value.outbox.isNotEmpty()) outbox.pump()
        return true
    }

    private companion object {
        const val OPENED = "\u0000opened"
    }
}
