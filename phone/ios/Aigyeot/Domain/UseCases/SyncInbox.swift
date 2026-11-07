import Foundation

/// 받기: 받은 함(GET /v1/inbox)·WebSocket 실시간·ack(계약 2장·8.3). 소켓의 수명도 여기다(앞에 있을 때만 붙는다).
/// 받은 함 → 기기 목록 → 상태 요청 → 푸시 토큰 → 보낼 함 순서로 갱신한다.
@MainActor
final class SyncInbox {
    private let state: PhoneStateRepository
    private let secrets: CredentialRepository
    private let relay: RelayClient
    private let link: LinkObserver
    private let gate: RelayGate
    private let visibility: AppVisibility
    private let tasks: TaskBag
    private let songFiles: SongFileStore
    private let outbox: SendOutbox
    private let alerts: ShowAlert
    private let pushToken: SyncPushToken
    private let makeSocket: RelaySocketMaker?
    private let now: () -> Date
    /// 기기 목록 성공 뒤 상태 요청(RequestState.afterDevices). RequestState가 이 클래스의 차 관찰을 읽어 합성 루트가 잇는다.
    private let afterDevices: (_ epoch: Int) async -> Void
    /// 같은 연결의 401·4001(Lifecycle.handleUnauthorized).
    private let lose: () -> Void

    private(set) var refreshing = false
    private var refreshRetries = 0
    /// 마지막으로 예약한 받은 함 다시 하기 간격(초, 시험용).
    private(set) var scheduledRefreshDelay: UInt64?
    private var retryTask: Task<Void, Never>?
    /// 갱신 중에 저장이 실패했으면 갱신이 끝난 뒤 재시도를 예약한다. 그 최소 기다림(받은 함 429의 Retry-After).
    private var retryAfterRefresh = false
    private var retryAfterRefreshAtLeast: TimeInterval?
    /// 갱신이 도는 사이 소켓이 다시 붙었다: 끝난 뒤 그 접속의 받은 함을 다시 본다.
    private var reconnectedDuringRefresh = false
    /// 상태 파일 복구 표시를 저장하지 못했다. 저장될 때까지 메시지 적용을 미룬다(7.4).
    private var pendingRecovery = false
    /// 받은 함 429의 Retry-After 기한. 앞으로 오기·새로고침·다시 붙기 모두 이때까지 받은 함을 다시 부르지 않는다(1.1).
    private var inboxHoldUntil: Date?
    /// 실시간 메시지 저장이 실패하면 올린다. 그 전에 띄운 실시간 처리는 버린다(뒤 메시지를 먼저 적용하지 않게).
    private var liveEpoch = 0
    /// 처음 받은 함을 처리하는 동안 온 소켓 프레임은 모아 둔다(밀린 것과 실시간을 구분).
    private var backlogDone = true
    private var bufferedLive: [Envelope] = []
    private(set) var socket: RelaySocket?
    /// 소켓을 열 때마다 올린다. 닫은 소켓의 늦은 이벤트를 버린다.
    private var socketEpoch = 0
    private var devicePollTask: Task<Void, Never>?
    /// 차의 실시간 봉투를 적용한 횟수와 그 시각. 늦게 온 기기 목록이 이 관찰을 덮지 않게 한다(RefreshDevices가 본다).
    private(set) var carLiveSeen = 0
    private(set) var lastCarLiveAt: Date?

    init(state: PhoneStateRepository, secrets: CredentialRepository, relay: RelayClient, link: LinkObserver, gate: RelayGate,
         visibility: AppVisibility, tasks: TaskBag, songFiles: SongFileStore, outbox: SendOutbox, alerts: ShowAlert,
         pushToken: SyncPushToken, makeSocket: RelaySocketMaker?, now: @escaping () -> Date,
         afterDevices: @escaping (_ epoch: Int) async -> Void, lose: @escaping () -> Void) {
        self.state = state
        self.secrets = secrets
        self.relay = relay
        self.link = link
        self.gate = gate
        self.visibility = visibility
        self.tasks = tasks
        self.songFiles = songFiles
        self.outbox = outbox
        self.alerts = alerts
        self.pushToken = pushToken
        self.makeSocket = makeSocket
        self.now = now
        self.afterDevices = afterDevices
        self.lose = lose
    }

    /// 수명 표(Lifecycle)의 이 원본 칸.
    func forget(_ e: LifecycleEvent) {
        switch e {
        case .connectionChanged:
            retryTask?.cancel()
            refreshing = false; retryAfterRefresh = false; reconnectedDuringRefresh = false
            retryAfterRefreshAtLeast = nil; inboxHoldUntil = nil; pendingRecovery = false
            backlogDone = true; bufferedLive = []
        case .background:
            retryTask?.cancel(); retryTask = nil   // 뒤에서는 받은 함을 다시 시도하지 않는다. 남은 것은 앞으로 올 때 한다
            reconnectedDuringRefresh = false   // 소켓도 닫았다. 앞으로 오면 처음부터 받는다
            bufferedLive = []                  // ack 전이라 중계에 남는다. 뒤로 갈 때마다 쌓이지 않게
        case .connectionLeft, .recordsCleared: break
        }
    }

    /// 저장 파일이 깨져 빈 상태로 복구했는데 자격은 남아 있으면, 이미 ack한 수유·곡 상태가 받은 함에 없다.
    /// 요청 필요 표시를 켜 두면 기기 목록 조회가 성공한 뒤 state_request로 다시 받는다(계약 7.4, G39).
    /// 저장이 거부된 실행은 건드리지 않는다. 원본이 그대로라 다음 정상 실행이 그것을 읽는다.
    /// 이 표시의 저장이 실패하면 기억해 두고, 저장될 때까지 메시지 적용을 미룬다(markStaleIfNeeded가 다시 한다).
    /// 자격만 남고 상태에 차가 없는 경우(앱 재설치 뒤 Keychain만 남음, 자격 저장 직후 상태 저장 전에 종료)도 같다.
    func markRecoveryAtLaunch() {
        guard state.restoreFailed || state.value.carId == nil, !state.writesRefused, let c = state.credentials, c.role == .owner else { return }
        // 기준 시각도 채워, 첫 요청이 중계에서 만료돼도 24시간 재요청이 걸리게 한다(안드로이드 paired와 같다).
        let t = now()
        pendingRecovery = !state.update { $0.carId = c.carId; $0.needsStateRequest = true; $0.lastReceivedAt = $0.lastReceivedAt ?? t }
    }

    /// 앞으로 왔다(Lifecycle): 새 갱신을 실제로 시작할 때만 버퍼링을 켠다. 24시간 판단은 소켓·받은 함보다 먼저(7.4).
    /// 재요청 표시를 저장하지 못했으면 소켓도 열지 않는다(받은 것을 적용하지 못하고 쌓이기만 한다). 잠시 뒤 갱신이 연다.
    func foregrounded() {
        let began = beginRefresh()
        if began || refreshing { startSocket() }
        let epoch = visibility.foregroundEpoch   // 스폰한 갱신이 돌기 전에 뒤로 가도 멈추게 지금 잡는다
        if began { tasks.spawn { [self] in await performRefresh(epoch: epoch) } }
        else if !refreshing { outbox.kick() }   // 갱신을 못 열었다(받은 함 429 기다림 등): 보낼 함은 따로 보낸다(7.5 '앱이 앞으로 올 때')
    }

    /// 마지막으로 차의 메시지를 받은 지 24시간이 넘었으면 '상태 요청 필요'를 켠다. 표시는 201로만 끈다.
    /// 표시가 필요 없거나 저장에 성공하면 true. 저장에 실패하면 false(호출한 쪽은 메시지 적용을 미룬다).
    private func markStaleIfNeeded() -> Bool {
        if pendingRecovery, let c = state.credentials {
            let t = now()
            guard state.update({ $0.carId = c.carId; $0.needsStateRequest = true; $0.lastReceivedAt = $0.lastReceivedAt ?? t }) else { return false }
            pendingRecovery = false
        }
        guard InboxRules.needsStaleMark(state.value, now: now()) else { return true }
        return state.update { $0.needsStateRequest = true }
    }

    /// 받은 함을 다 받지 못했을 때(조회·저장 실패, StoreFailure.receive) 다시 한다. 5·10·20·40·60초로 늘리고 받은 함을 다 받으면 처음으로 돌린다.
    /// 필요 표시를 저장하지 못한 채 받은 메시지를 적용하면 `lastReceivedAt`이 갱신돼 재요청 조건이 사라진다. 그래서 저장이 성공할 때까지 갱신을 시작하지 않는다.
    func scheduleRetry(atLeast minimum: TimeInterval? = nil) {
        guard !state.writesRefused, visibility.active else { return }   // 저장을 영구 거부하는 실행에서 헛돌지 않는다. 뒤에서는 하지 않는다
        if refreshing {   // 진행 중인 갱신이 끝나면 예약한다(그 전에 시간이 지나면 beginRefresh가 거절한다). Retry-After도 넘긴다
            retryAfterRefresh = true
            if let m = minimum { retryAfterRefreshAtLeast = max(retryAfterRefreshAtLeast ?? 0, m) }
            return
        }
        retryTask?.cancel()
        let delay = max(RetryPolicy.inboxRetry(retries: refreshRetries), UInt64((minimum ?? 0).rounded(.up)))   // 429면 Retry-After 뒤(1.1)
        refreshRetries += 1
        scheduledRefreshDelay = delay
        retryTask = Task { [weak self] in   // 기다리는 동안 붙들지 않는다
            try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
            guard !Task.isCancelled, let self else { return }
            await self.retryFired()
        }
    }

    /// 타이머가 울릴 때 다른 갱신이 진행 중이면 refresh()가 거절된다. 그 갱신이 끝난 뒤로 넘긴다.
    private func retryFired() async {
        if refreshing { retryAfterRefresh = true } else { await refresh() }
    }

    // MARK: 소켓

    /// 소켓이 붙을 때 상태 요청 표시가 남아 있으면 다시 판단한다(앱을 열 때 조회가 실패했던 경우). 표시가 없으면 목록을 또 받지 않는다.
    func socketDidConnect(_ up: Bool) {
        link.socketChanged(up)
        guard up else { return }
        link.reached()   // 중계에 다시 닿았다. 앞에 있는 동안 돌아와도 띠를 끈다
        // 다시 붙을 때마다 중계가 밀린 것을 소켓으로 먼저 보낸다. 받은 함으로 먼저 처리하고 실시간으로 넘어간다
        // (밀린 리포트·인계 알림을 실시간으로 다뤄 꺼진 차를 '연결됨'으로 보이거나 배너를 띄우지 않게. 안드로이드 OPENED와 같다).
        let epoch = visibility.foregroundEpoch
        if beginRefresh() { tasks.spawn { [self] in await performRefresh(inboxOnly: true, epoch: epoch) } }
        else if refreshing { backlogDone = false; reconnectedDuringRefresh = true }   // 도는 갱신의 받은 함은 이 접속 전 것이다
        if state.role == .owner, state.value.needsStateRequest { tasks.spawn { [self] in await afterDevices(epoch) } }
    }

    func startSocket() {
        guard socket == nil, let make = makeSocket else { return }
        let gen = state.generation
        socketEpoch += 1
        let mine = socketEpoch
        let secrets = secrets
        // 토큰은 붙을 때마다 Keychain에서 읽는다(소켓은 자기 작업에서 부른다. 끊긴 뒤에는 없다)
        let socket = make({ secrets.load()?.token }) { [weak self] event in
            // 이전 연결이나 이미 닫은 소켓의 늦은 이벤트는 버린다(닫은 뒤 모으기를 다시 채우지 않게). 401·4001은 연결 세대만 본다
            guard let self, gen == self.state.generation else { return }
            if case .unauthorized = event {} else { guard mine == self.socketEpoch else { return } }
            self.socketEvent(event)
        }
        self.socket = socket
        socket.start()
        // 앞에 있고 소켓이 붙어 있는 동안 60초마다 기기 목록만 다시 받아 차 타일을 맞춘다(설계값). 소켓과 같이 끝난다.
        // 상태 요청 표시가 남아 있으면(기록 지우기 직후 목록 조회가 실패했던 경우) 이번 목록 성공 뒤 요청한다(7.4).
        if state.role == .owner {
            devicePollTask?.cancel()
            devicePollTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: RetryPolicy.devicePollNanos)
                    guard !Task.isCancelled, let self, gen == self.state.generation else { return }
                    if self.link.socketConnected { await self.afterDevices(self.visibility.foregroundEpoch) }
                }
            }
        }
    }

    func stopSocket() {
        socketEpoch += 1
        devicePollTask?.cancel()
        devicePollTask = nil
        socket?.stop()
        socket = nil
        link.socketChanged(false)
    }

    func socketEvent(_ event: RelaySocketEvent) {
        switch event {
        case .connected(let up): socketDidConnect(up)
        case .unreachable: link.unreachable()   // 앞에 있는 동안 중계가 끊겨도 띠를 켠다(계약 9장 '중계 불통')
        case .alive: link.reached()             // 소켓 pong: 중계에 닿고 있다(보호자 폰은 HTTP가 적어 이것이 띠를 끈다)
        case .unauthorized: lose()
        case .envelope(let env):
            if backlogDone {
                let epoch = liveEpoch, fe = visibility.foregroundEpoch
                tasks.spawn { [self] in
                    guard epoch == liveEpoch else { return }   // 앞 실시간 메시지 저장이 실패했다: 이것은 받은 함에서 순서대로 다시 받는다
                    guard fe == visibility.foregroundEpoch else { return }   // 그사이 뒤로 갔다(중계에 남아 앞으로 오면 받는다)
                    if !(await ingest([env], live: true)) { liveFailed() }
                }
            } else { bufferedLive.append(env) }
        }
    }

    /// 실시간 메시지를 저장하지 못했다. 뒤 메시지를 먼저 적용하지 않게 실시간 처리를 멈추고 소켓을 닫고 받은 함부터 다시 받는다(8.3 4번).
    private func liveFailed() {
        liveEpoch += 1
        backlogDone = false; bufferedLive = []
        stopSocket()
        scheduleRetry()
    }

    /// 붙지 못하고 백오프로 기다리는 소켓을 깨운다(인터넷 복구·'다시 시도', 안드로이드 onAvailable의 kick과 같다). 붙어 있으면 그대로 둔다
    private func wakeSocket() {
        // 닿지 않음으로 띠가 켜졌을 때만(백오프로 기다리는 중). 앱을 열 때 경로 알림이 붙는 중인 소켓을 버리지 않게
        guard visibility.active, state.isPaired, link.offline, socket != nil, !link.socketConnected else { return }
        stopSocket()
        startSocket()
    }

    /// 인터넷 경로가 바뀌었다(NWPathMonitor). 경로가 없으면 띠를 켜고, 돌아오면 기다림 없이 다시 보낸다(7.5, 계약 9장).
    func networkPathChanged(satisfied: Bool) async {
        // 같은 상태의 경로 갱신(인터페이스·비용 변화 등)은 무시한다. 끊김↔연결이 바뀔 때만 움직인다(중계가 내려간 동안 백오프를 건너뛰며 되풀이하지 않게)
        guard link.pathChanged(satisfied), satisfied else { return }
        guard state.isPaired else { link.noRelayToCheck(); return }   // 연결 안 됨: 확인할 중계가 없다
        outbox.hurryNetworkWaits()   // 인터넷이 돌아왔다: 망 실패 기다림 없이 바로 보낸다(7.5)
        guard visibility.active else { return }   // 뒤에서는 갱신하지 않는다(앞으로 오면 한다)
        wakeSocket()          // 소켓도 백오프를 기다리지 않고 다시 붙는다
        if link.offline { await refresh() } else { outbox.kick() }
    }

    // MARK: 갱신

    /// 사용자가 다시 해 달라고 했다(띠의 '다시 시도', 당겨서 새로고침): 망 실패의 기다림을 당기고 갱신한다(7.5, 사용자 결정 11-07).
    func retryNow() async {
        outbox.hurryNetworkWaits()
        wakeSocket()
        await refresh()
    }

    /// 받은 함 → 기기 목록 → 상태 요청 → 보낼 함 순서. 앱을 열 때와 네트워크가 돌아올 때 부른다.
    func refresh() async {
        guard beginRefresh() else {
            if !refreshing { await outbox.run() }   // 갱신을 못 열었다(받은 함 429 기다림 등): 보낼 함은 따로 보낸다. 도는 갱신은 끝에 보낸다
            return
        }
        if visibility.active { startSocket() }   // 재요청 표시 저장에 실패해 닫았던 소켓을 다시 연다(이미 열려 있으면 그대로)
        await performRefresh()
    }

    /// 이미 갱신 중이면 false(진행 중인 갱신의 버퍼링 상태를 건드리지 않는다). 시작하면 24시간 판단을 하고 버퍼링을 켠다.
    /// 재요청 표시를 저장하지 못하면 소켓을 닫는다: 받은 것을 적용할 수 없어 모으기만 하면 메모리에 쌓인다.
    /// ack하지 않은 것은 중계에 남아 저장이 될 때 다시 받는다(8.3, 안드로이드는 소켓 루프를 끝낸다).
    private func beginRefresh() -> Bool {
        guard state.isPaired, !refreshing else { return false }
        if let hold = inboxHoldUntil, hold > now() {   // 받은 함 429 기다림 중: 소켓도 열지 않고 기한 뒤에 다시 한다
            scheduleRetry(atLeast: hold.timeIntervalSince(now()))
            return false
        }
        guard markStaleIfNeeded() else {
            stopSocket(); bufferedLive = []
            scheduleRetry()
            return false
        }
        backlogDone = false      // 처음 받은 함을 처리하는 동안 온 소켓 프레임은 모아 둔다
        refreshing = true
        return true
    }

    /// `inboxOnly`: 소켓이 다시 붙었을 때. 받은 함·모아 둔 프레임·보낼 함만 본다(기기 목록·상태 요청·푸시 토큰은 앱을 열 때의 갱신이 한다).
    private func performRefresh(inboxOnly: Bool = false, epoch: Int? = nil) async {
        guard let c = state.credentials else { return }
        let gen = state.generation, epoch = epoch ?? visibility.foregroundEpoch
        let current = { gen == self.state.generation && epoch == self.visibility.foregroundEpoch }   // 연결이 바뀌거나 뒤로 갔으면 멈춘다
        defer {
            if gen == state.generation {
                refreshing = false
                // 뒤로 갔다가 이 갱신이 끝나기 전에 돌아왔다: 그때 앞으로 오기의 갱신은 '갱신 중'이라 거절됐다. 지금 다시 한다
                if epoch != visibility.foregroundEpoch, visibility.active {
                    let e = visibility.foregroundEpoch
                    if beginRefresh() { tasks.spawn { [self] in await performRefresh(epoch: e) } }
                }
                if reconnectedDuringRefresh {
                    reconnectedDuringRefresh = false
                    let e = visibility.foregroundEpoch   // 스폰한 뒤 돌기 전에 뒤로 가면 멈추게 지금 잡는다
                    if beginRefresh() { tasks.spawn { [self] in await performRefresh(inboxOnly: true, epoch: e) } }
                }
                if retryAfterRefresh {
                    retryAfterRefresh = false
                    let m = retryAfterRefreshAtLeast; retryAfterRefreshAtLeast = nil
                    scheduleRetry(atLeast: m)
                }
            }
        }
        guard current() else { return }   // 스폰한 뒤 돌기 전에 뒤로 갔다. 위 defer가 '갱신 중'을 풀어 다음 앞으로 올 때 다시 한다

        var inboxError: RelayError?
        var saved = true
        var mark = link.mark()
        do {
            var after: Int64?
            var more = true
            while more {
                mark = link.mark()   // 요청마다 잡는다: 앞 쪽을 처리하는 사이의 관찰보다 뒤에 시작한 요청의 결과는 새것이다
                let page = try await relay.inbox(afterSeq: after)
                guard current() else { return }
                saved = await ingest(page.messages, live: false)
                guard current() else { return }
                if !saved { break }   // 실패한 메시지보다 뒤의 쪽을 먼저 적용하지 않는다
                after = page.messages.last?.seq
                more = page.more && after != nil
            }
        } catch let e as RelayError {
            inboxError = e
        } catch {}
        guard gen == state.generation else { return }
        if inboxError?.status == 429 { inboxHoldUntil = now().addingTimeInterval(inboxError?.retryAfter ?? RetryPolicy.rateLimitDefault) }   // 뒤로 갔어도 기한은 남긴다
        if inboxError?.isUnauthorized == true { lose(); return }   // 뒤로 갔어도 같은 연결의 401은 처리한다(7.1)
        guard current() else { return }
        link.observe(inboxError, since: mark)
        guard inboxError == nil, saved else {
            // 밀린 것을 다 받지 못했다. 모아 둔 프레임을 실시간으로 처리하면 지난 것이 실시간으로 보이고(배너·차 타일)
            // 저장하지 못한 것보다 뒤의 것을 먼저 적용한다. ack하지 않은 것은 중계에 남으니 버리고, 소켓을 닫고
            // 받은 함부터 다시 한다(8.3, 안드로이드는 syncInbox가 실패하면 소켓 루프를 끝낸다). 보내기는 따로 한다.
            bufferedLive = []; reconnectedDuringRefresh = false
            stopSocket()
            scheduleRetry(atLeast: inboxError?.status == 429 ? inboxError?.retryAfter ?? RetryPolicy.rateLimitDefault : nil)
            await outbox.run()
            return
        }
        refreshRetries = 0
        // 밀린 것을 다 처리했다. 그동안 모아 둔 소켓 프레임은 이제 실시간으로 처리한다.
        // 그사이 소켓이 다시 붙었으면 모은 것은 그 접속의 밀린 것일 수 있다: 끝난 뒤 받은 함을 다시 보고 넘긴다(defer).
        if !reconnectedDuringRefresh {
            backlogDone = true
            let buffered = bufferedLive
            bufferedLive = []
            if !buffered.isEmpty, !(await ingest(buffered, live: true)) { liveFailed() }
        }
        guard current() else { return }
        if inboxOnly { await outbox.run(); return }
        if c.role == .owner { await afterDevices(epoch) }
        guard current() else { return }
        await pushToken.run()
        guard current() else { return }
        await outbox.run()
    }

    // MARK: 받기

    /// 받은 메시지를 `seq` 오름차순으로 하나씩 처리한다. 본문과 '처리한 id'를 한 번에 저장하고, 저장이 성공한 것만 ack한다.
    /// 저장하지 못하면 그 메시지부터 ack하지 않는다(중계가 다시 준다).
    /// `live`는 처음 받은 함을 처리한 뒤 WebSocket으로 받은 것이다. 받은 함에서 꺼낸 것은 배너 없이 목록에만 넣는다.
    /// 모두 저장했거나 저장할 것이 없으면 true, 저장에 실패해 멈췄으면 false(호출한 쪽이 다시 받게 한다).
    @discardableResult
    func ingest(_ envelopes: [Envelope], live: Bool) async -> Bool {
        guard let creds = state.credentials else { return true }
        defer { alerts.resolvePendingOpen() }   // 저장을 마친 뒤 열려던 알림이 있으면 연다
        guard markStaleIfNeeded() else { return false }   // 재요청 표시를 저장하기 전에는 적용도 ack도 하지 않는다(중계가 다시 준다)
        let gen = state.generation
        var ackIds: [String] = []
        var allSaved = true
        for env in envelopes.sorted(by: { ($0.seq ?? 0) < ($1.seq ?? 0) }) {
            // 다른 차(이전 연결)의 메시지는 적용도 ack도 하지 않는다. seq는 같은 차 안에서만 비교한다(3.1).
            guard env.car == creds.carId else { continue }
            if state.value.processed[env.id] != nil || env.v != 1 {
                ackIds.append(env.id)   // 이미 처리한 id, 모르는 판은 ack만 해서 받은 함이 막히지 않게 한다
                continue
            }
            var effects = InboxRules.Effects()
            let inbound = env.inbound   // DTO → 도메인(Data/Protocol/EnvelopeMapping)
            let t = now()
            let saved = state.update { effects = InboxRules.receive(inbound, live: live, myDevice: creds.deviceId, now: t, in: &$0) }
            guard saved else { allSaved = false; break }
            if live, env.from?.role == .car { carLiveSeen += 1; lastCarLiveAt = now() }   // 늦게 온 기기 목록이 이 관찰을 덮지 않게
            ackIds.append(env.id)
            if let id = effects.banner { alerts.raise(id) }
            effects.removeFiles.forEach(songFiles.remove)
            if effects.outboxAdded { outbox.kick() }   // 거절된 곡의 자동 삭제 같은, 받은 결과로 생긴 전송을 바로 시작한다
        }
        // 실시간 메시지 저장이 실패했다: 앞 것의 ack를 기다리는 사이 새 실시간 메시지가 먼저 적용되지 않게 지금 막는다(8.3 4번)
        if !allSaved, live { liveEpoch += 1; backlogDone = false; bufferedLive = [] }
        guard !ackIds.isEmpty else { return allSaved }
        do { try await gate.observed { try await relay.ack(ids: ackIds) } }
        catch let e as RelayError { gate.unauthorized(e, gen: gen) }   // 못 보내도 다시 받으면 중복으로 ack만 한다
        catch {}
        return allSaved || gen != state.generation   // ack를 기다리는 동안 연결 세대가 바뀌었으면 이 세대가 다시 받을 것이 없다
    }
}

/// 기기 목록(GET /v1/devices, 7.2). owner만 받는다.
@MainActor
final class RefreshDevices {
    private let state: PhoneStateRepository
    private let relay: RelayClient
    private let link: LinkObserver
    private let gate: RelayGate
    /// 차의 실시간 관찰(SyncInbox.carLiveSeen·lastCarLiveAt).
    private let inbox: SyncInbox
    /// 마지막으로 적용한 목록 요청의 순번. 같은 snapshot_seq면 나중에 시작한 목록만 적용한다.
    private var appliedTicket = 0

    init(state: PhoneStateRepository, relay: RelayClient, link: LinkObserver, gate: RelayGate, inbox: SyncInbox) {
        self.state = state
        self.relay = relay
        self.link = link
        self.gate = gate
        self.inbox = inbox
    }

    @discardableResult
    func run() async -> Bool {
        guard state.role == .owner else { return false }
        let gen = state.generation
        let mark = link.mark(), carMark = inbox.carLiveSeen
        do {
            let r = try await relay.devices()
            guard gen == state.generation else { return false }
            link.observe(nil, since: mark)   // 이 HTTP도 중계에 닿았다(띠를 끄는 HTTP 성공, 안드로이드 fetchDevices와 같다)
            // 목록을 기다리는 사이 차의 실시간 봉투를 받았으면 차 행의 연결 상태는 그쪽이 새것이다(7.2: 그 자리에서 바꾼다).
            let keepCarLink = inbox.carLiveSeen != carMark
            // 더 큰 snapshot_seq는 늦게 끝났어도 새것이다. 같으면 나중에 시작한 목록만(차 online은 seq 없이 바뀐다). 작으면 버린다.
            // 적용 판단은 여기 한 곳이다. 버리거나 저장하지 못한 목록은 순번을 올리지 않는다(같은 seq의 나중 목록을 막지 않게)
            let s0 = state.value.devicesSnapshotSeq
            guard r.snapshotSeq > s0 || (r.snapshotSeq == s0 && mark.ticket > appliedTicket) else { return true }
            // 목록과 snapshot_seq는 한 번에 저장한다.
            let seenAt = keepCarLink ? inbox.lastCarLiveAt : nil
            let saved = state.update { InboxRules.applyDevices(r, carSeenAt: seenAt, in: &$0) }
            if saved { appliedTicket = max(appliedTicket, mark.ticket) }
            return true
        } catch let e as RelayError {
            gate.unauthorized(e, gen: gen)
            if gen == state.generation { link.observe(e, since: mark) }
        } catch {}
        return false
    }
}

/// 상태 요청(state_request, G39). owner만, 10분 안에 다시 보내지 않는다. 오프라인이면 보낼 함에 넣지 않고 표시만 둔다(7.5).
@MainActor
final class RequestState {
    private let state: PhoneStateRepository
    private let relay: RelayClient
    private let gate: RelayGate
    private let visibility: AppVisibility
    private let devices: RefreshDevices
    private let outbox: SendOutbox
    private let now: () -> Date
    private var requesting = false
    /// '이 휴대폰의 기록 지우기'를 한 횟수. 상태 요청 응답을 기다리는 사이 지웠는지 알아내는 데 쓴다.
    private var clearCount = 0

    init(state: PhoneStateRepository, relay: RelayClient, gate: RelayGate, visibility: AppVisibility, devices: RefreshDevices,
         outbox: SendOutbox, now: @escaping () -> Date) {
        self.state = state
        self.relay = relay
        self.gate = gate
        self.visibility = visibility
        self.devices = devices
        self.outbox = outbox
        self.now = now
    }

    func forget(_ e: LifecycleEvent) {
        switch e {
        case .connectionChanged: requesting = false
        case .recordsCleared: clearCount += 1
        case .connectionLeft, .background: break
        }
    }

    /// 기기 목록 조회가 성공한 뒤에 상태를 요청한다(계약 7.4). 갱신·기록 지우기·다시 붙기·60초 주기가 함께 쓰는 길이다.
    /// `epoch`는 일을 띄운 때의 화면 세대다. 띄운 뒤 돌기 전이나 목록을 기다리는 사이 뒤로 갔으면 시작하지 않는다(2장 '뒤로 가면 멈춘다').
    func afterDevices(epoch: Int) async {
        guard epoch == visibility.foregroundEpoch, !Task.isCancelled else { return }
        if await devices.run(), epoch == visibility.foregroundEpoch, !Task.isCancelled { await sendIfNeeded() }
    }

    /// 동시에 불러도 한 번만 보내도록 첫 await 전에 '요청 중' 표시를 세운다.
    func sendIfNeeded() async {
        guard state.role == .owner, state.value.needsStateRequest, !requesting else { return }
        if outbox.isHeld { return }   // 메시지 429 기다림 중(표시는 남아 다음 계기에 보낸다)
        if let last = state.value.lastStateRequestAt, now().timeIntervalSince(last) < Retention.stateRequestGap { return }
        // 보내기 전에 시각을 먼저 저장한다(안드로이드 requestState와 같다). 응답이 유실돼도 10분 제한이 남는다.
        let t = now()
        guard state.update({ $0.lastStateRequestAt = t }) else { return }
        requesting = true
        let gen = state.generation
        let clears = clearCount
        defer { requesting = false }
        let env = Envelope(id: ULID.generate(date: t), type: MessageType.stateRequest, ts: Rfc3339.string(t), body: .object([:]))
        do {
            _ = try await gate.observed { try await relay.send(env) }
            guard gen == state.generation else { return }
            // 기다리는 사이 기록을 지웠다면 그 뒤에 필요해진 요청 표시는 남긴다(10분이 지나면 다시 요청한다).
            let cleared = clears != clearCount
            state.update { $0.needsStateRequest = cleared }
        } catch let e as RelayError {
            gate.unauthorized(e, gen: gen)
            // 같은 메시지 한도다(1.4): 뒤따르는 보낼 함도 기다린다. 표시는 남아 다음 계기에 다시 요청한다
            if gen == state.generation, e.status == 429 { outbox.holdMessages(for: e.retryAfter ?? RetryPolicy.rateLimitDefault) }
        } catch {}
    }
}

/// FCM 토큰을 중계에 알린다(5.4, PATCH /v1/devices/me).
@MainActor
final class SyncPushToken {
    private let state: PhoneStateRepository
    private let relay: RelayClient
    private let gate: RelayGate
    private let tasks: TaskBag
    /// FCM 토큰(앱의 PushToken). 시험이 바꾼다.
    var source: PushTokenSource?

    init(state: PhoneStateRepository, relay: RelayClient, gate: RelayGate, tasks: TaskBag) {
        self.state = state
        self.relay = relay
        self.gate = gate
        self.tasks = tasks
    }

    var token: String? { source?.token }

    /// FCM 토큰이 생기거나 바뀌었다(MessagingDelegate). 연결돼 있으면 바로 중계에 알린다(앱을 열 때마다도 다시 보낸다).
    func tokenDidChange() {
        guard state.isPaired else { return }
        tasks.spawn { [self] in await run() }
    }

    func run() async {
        guard let t = token else { return }
        let gen = state.generation
        do {
            _ = try await gate.observed { try await relay.patchMe(PatchDeviceRequest(pushToken: .set(t))) }
            // 보내는 사이 토큰이 바뀌었으면(갱신 콜백의 PATCH와 겹침) 중계에 늦게 닿은 옛 값이 남지 않게 지금 값을 한 번 더 보낸다
            if gen == state.generation, token != t { await run() }
        }
        catch let e as RelayError { gate.unauthorized(e, gen: gen) }   // 실패는 다음 앱 열기 때 다시(5.4)
        catch {}
    }
}
