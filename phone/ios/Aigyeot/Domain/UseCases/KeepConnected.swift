import Foundation
import Observation

/// 연결 유지(안드로이드 KeepConnected와 같은 자리): 앞에 있는 동안의 소켓·ping, 갱신 순서(받은 함 → 기기 목록 → 상태 요청 → 푸시 토큰 → 보낼 함),
/// 받은 함 다시 하기(실패 범주 R), 60초 기기 목록, 상태 요청, 연결 표시(`status`: 온라인·소켓·시계 차이, 9장)의 원본이다.
/// 자격이 바뀌면 AppLifecycle.connectionChanged가 이 클래스의 `forget`·`stopSocket`을 부르는 한 곳에서 멈춘다(P6). 받은 것의 저장은 SyncInbox.
@MainActor
@Observable
final class KeepConnected {
    @ObservationIgnored private let state: PhoneStateRepository
    @ObservationIgnored private let relay: RelayClient
    @ObservationIgnored private let gate: RelayGate
    @ObservationIgnored private let inbox: SyncInbox
    @ObservationIgnored private let visibility: AppVisibility
    @ObservationIgnored private let tasks: TaskBag
    @ObservationIgnored private let outbox: SendOutbox
    @ObservationIgnored private let pushToken: RegisterPush
    /// 소켓을 여는 쪽(시험에서는 없음).
    @ObservationIgnored private let sockets: RelaySocket?
    /// 인터넷 경로(시험에서는 없음).
    @ObservationIgnored private let network: NetworkWatcher?
    @ObservationIgnored private let now: () -> Date
    /// 같은 연결의 401·4001.
    @ObservationIgnored private let lose: LoseConnection

    /// 연결 표시(M4). 화면(띠·차 타일·보호자)이 본다. 자격이 바뀌면 처음 값으로 돌아간다(L7, 안드로이드와 같다).
    private(set) var status = LinkStatus()
    /// 중계 시계 − 폰 시계(응답의 `Date` 헤더, 계약 9장). 보내기 판단(요약 시각)이 읽는다. 화면은 `status.clockWarning`을 본다.
    @ObservationIgnored private(set) var clockSkew: TimeInterval?
    /// 소켓이 중계에 닿았는지(붙음·닿지 않음)·인터넷 경로를 본 횟수. 이 값이 바뀐 뒤에 끝난 HTTP 요청은 더 오래된 관찰이라 띠를 바꾸지 않는다.
    @ObservationIgnored private var linkSeen = 0
    /// HTTP 요청에 시작 순서대로 매기는 번호. 늦게 끝난 앞 요청의 결과가 나중에 시작한 요청의 결과를 덮지 않게 한다.
    @ObservationIgnored private var httpTicket = 0
    @ObservationIgnored private var appliedTicket = 0
    @ObservationIgnored private var lastPathSatisfied: Bool?

    @ObservationIgnored private(set) var refreshing = false
    @ObservationIgnored private var refreshRetries = 0
    /// 마지막으로 예약한 받은 함 다시 하기 간격(초, 시험용).
    @ObservationIgnored private(set) var scheduledRefreshDelay: UInt64?
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    /// 갱신 중에 저장이 실패했으면 갱신이 끝난 뒤 재시도를 예약한다. 그 최소 기다림(받은 함 429의 Retry-After).
    @ObservationIgnored private var retryAfterRefresh = false
    @ObservationIgnored private var retryAfterRefreshAtLeast: TimeInterval?
    /// 갱신이 도는 사이 소켓이 다시 붙었다: 끝난 뒤 그 접속의 받은 함을 다시 본다.
    @ObservationIgnored private var reconnectedDuringRefresh = false
    /// 받은 함 429의 Retry-After 기한. 앞으로 오기·새로고침·다시 붙기 모두 이때까지 받은 함을 다시 부르지 않는다(1.1).
    @ObservationIgnored private var inboxHoldUntil: Date?
    /// 실시간 메시지 저장이 실패하면 올린다. 그 전에 띄운 실시간 처리는 버린다(뒤 메시지를 먼저 적용하지 않게).
    @ObservationIgnored private var liveEpoch = 0
    /// 처음 받은 함을 처리하는 동안 온 소켓 프레임은 모아 둔다(밀린 것과 실시간을 구분).
    @ObservationIgnored private var backlogDone = true
    @ObservationIgnored private var bufferedLive: [Envelope] = []
    @ObservationIgnored private(set) var socket: SocketHandle?
    /// 소켓을 열 때마다 올린다. 닫은 소켓의 늦은 이벤트를 버린다.
    @ObservationIgnored private var socketEpoch = 0
    @ObservationIgnored private var devicePollTask: Task<Void, Never>?
    /// 마지막으로 적용한 기기 목록 요청의 순번. 같은 snapshot_seq면 나중에 시작한 목록만 적용한다.
    @ObservationIgnored private var appliedDevicesTicket = 0
    /// 상태 요청을 보내는 중(동시에 불러도 한 번만).
    @ObservationIgnored private var requestingState = false
    /// '이 휴대폰의 기록 지우기'를 한 횟수. 상태 요청 응답을 기다리는 사이 지웠는지 알아내는 데 쓴다.
    @ObservationIgnored private var clearCount = 0
    /// 뒤로 갈 때 올린다(U21). 진행 중 갱신이 뒤에서 다음 요청을 시작하지 않게 한다.
    @ObservationIgnored private(set) var foregroundEpoch = 0

    init(state: PhoneStateRepository, relay: RelayClient, gate: RelayGate, inbox: SyncInbox, visibility: AppVisibility,
         tasks: TaskBag, outbox: SendOutbox, pushToken: RegisterPush, sockets: RelaySocket?, network: NetworkWatcher?,
         lose: LoseConnection, now: @escaping () -> Date) {
        self.state = state
        self.relay = relay
        self.gate = gate
        self.inbox = inbox
        self.visibility = visibility
        self.tasks = tasks
        self.outbox = outbox
        self.pushToken = pushToken
        self.sockets = sockets
        self.network = network
        self.lose = lose
        self.now = now
    }

    // MARK: 연결 표시(M4, 관찰 번호 규칙은 옛 LinkObserver 그대로)

    /// HTTP 요청을 시작할 때 잡는다: 그때의 소켓·경로 관찰 번호와 요청 순번.
    func mark() -> LinkMark {
        httpTicket += 1
        return LinkMark(link: linkSeen, ticket: httpTicket)
    }

    /// HTTP 결과를 띠에 반영하는 한 곳(9장). 응답을 받았으면(성공·4xx) 중계에 닿았고, 닿지 않거나 5xx면 불통이다(안드로이드 `<500`은 닿음).
    /// 응답이 아닌 실패(형식 오류 등)는 띠를 그대로 둔다.
    func observed(_ e: RelayError?, since mark: LinkMark) {
        guard let e else { setOnline(true, since: mark); return }
        if e.isRelayDown { setOnline(false, since: mark) } else if e.status != nil { setOnline(true, since: mark) }
    }

    /// HTTP 요청의 결과로 띠를 바꾼다. 요청을 시작한 뒤(`mark`) 소켓·인터넷 경로가 새로 본 것이 있으면 그쪽이 더 새 관찰이다.
    private func setOnline(_ value: Bool, since mark: LinkMark) {
        guard mark.link == linkSeen, mark.ticket > appliedTicket else { return }
        setStatus { $0.online = value }
        appliedTicket = mark.ticket
    }

    /// 소켓이 붙었거나 pong을 받았다: 중계에 닿고 있다(보호자 폰은 HTTP가 적어 이것이 띠를 끈다).
    private func reached() { linkSeen += 1; setStatus { $0.online = true } }
    /// 앞에 있는 동안 소켓이 중계에 닿지 않는다(계약 9장 '중계 불통').
    private func unreachable() { linkSeen += 1; setStatus { $0.online = false } }

    /// 연결 표시를 바꾼다. 같은 값이면 쓰지 않는다: 구조체 칸을 바로 바꾸면 값이 같아도 표시를 읽는 화면(띠·차 탭·설정)을 깨운다(25초마다 오는 pong 등).
    private func setStatus(_ f: (inout LinkStatus) -> Void) {
        var s = status
        f(&s)
        if s != status { status = s }
    }

    /// 인터넷 경로가 바뀌었다. 같은 상태의 갱신(인터페이스·비용 변화 등)이면 false.
    /// 경로가 없으면 띠를 켠다. 경로가 생겼다고 중계에 닿는 것은 아니라 띠는 그대로 둔다: 끄는 것은 소켓이 붙거나 HTTP가 성공할 때다.
    private func pathChanged(_ satisfied: Bool) -> Bool {
        guard lastPathSatisfied != satisfied else { return false }
        lastPathSatisfied = satisfied
        linkSeen += 1   // 이 관찰보다 먼저 시작한 HTTP의 늦은 결과가 덮지 않게
        if !satisfied { setStatus { $0.online = false } }
        return true
    }

    /// 응답의 `Date` 헤더(계약 9장).
    func noteServerDate(_ date: Date) {
        let skew = date.timeIntervalSince(now())
        clockSkew = skew
        setStatus { $0.clockWarning = NoticeRules.clockWarning(skew: skew) }   // 띠 글이 바뀔 때만 화면을 깨운다
    }

    /// 수명 표(AppLifecycle.handle)의 이 원본 칸.
    func forget(_ e: LifecycleEvent) {
        switch e {
        case .connectionChanged:
            status = LinkStatus()   // 표시는 처음으로(M4·L7). 옛 연결 때 시작한 요청의 늦은 결과는 아래 관찰 번호로 버린다
            clockSkew = nil         // 시계 차이도 표시와 함께 처음으로(나누기 전과 같다)
            linkSeen += 1
            retryTask?.cancel()
            refreshing = false; retryAfterRefresh = false; reconnectedDuringRefresh = false
            retryAfterRefreshAtLeast = nil; inboxHoldUntil = nil
            backlogDone = true; bufferedLive = []
            requestingState = false
        case .background:
            foregroundEpoch += 1
            retryTask?.cancel(); retryTask = nil   // 뒤에서는 받은 함을 다시 시도하지 않는다. 남은 것은 앞으로 올 때 한다
            reconnectedDuringRefresh = false   // 소켓도 닫았다. 앞으로 오면 처음부터 받는다
            bufferedLive = []                  // ack 전이라 중계에 남는다. 뒤로 갈 때마다 쌓이지 않게
        case .recordsCleared: clearCount += 1
        }
    }

    /// 앞으로 왔다(AppLifecycle.onForeground): 새 갱신을 실제로 시작할 때만 버퍼링을 켠다. 24시간 판단은 소켓·받은 함보다 먼저(7.4).
    /// 재요청 표시를 저장하지 못했으면 소켓도 열지 않는다(받은 것을 적용하지 못하고 쌓이기만 한다). 잠시 뒤 갱신이 연다.
    func foregrounded() {
        let began = beginRefresh()
        if began || refreshing { startSocket() }
        let epoch = foregroundEpoch   // 스폰한 갱신이 돌기 전에 뒤로 가도 멈추게 지금 잡는다
        if began { tasks.spawn { [self] in await performRefresh(epoch: epoch) } }
        else if !refreshing { outbox.kick() }   // 갱신을 못 열었다(받은 함 429 기다림 등): 보낼 함은 따로 보낸다(7.5 '앱이 앞으로 올 때')
    }

    /// 받은 함을 다 받지 못했을 때(조회·저장 실패, 실패 범주 R) 다시 한다. 5·10·20·40·60초로 늘리고 받은 함을 다 받으면 처음으로 돌린다.
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
        setStatus { $0.socket = up }
        guard up else { return }
        reached()   // 중계에 다시 닿았다. 앞에 있는 동안 돌아와도 띠를 끈다
        // 다시 붙을 때마다 중계가 밀린 것을 소켓으로 먼저 보낸다. 받은 함으로 먼저 처리하고 실시간으로 넘어간다
        // (밀린 리포트·인계 알림을 실시간으로 다뤄 꺼진 차를 '연결됨'으로 보이거나 배너를 띄우지 않게. 안드로이드 OPENED와 같다).
        let epoch = foregroundEpoch
        if beginRefresh() { tasks.spawn { [self] in await performRefresh(inboxOnly: true, epoch: epoch) } }
        else if refreshing { backlogDone = false; reconnectedDuringRefresh = true }   // 도는 갱신의 받은 함은 이 접속 전 것이다
        if state.role == .owner, state.state.needsStateRequest { tasks.spawn { [self] in await afterDevices(epoch: epoch) } }
    }

    func startSocket() {
        guard socket == nil, let sockets, let c = state.creds else { return }   // c: 이 소켓의 연결(P5)
        socketEpoch += 1
        let mine = socketEpoch
        // 토큰은 연 자격의 것을 다시 붙을 때도 쓴다. 자격이 바뀌면 이 소켓의 사건은 아래에서 버리고, 연결 바꿈이 소켓을 닫는다
        socket = sockets.open(token: c.token) { [weak self] event in
            // 이전 연결이나 이미 닫은 소켓의 늦은 이벤트는 버린다(닫은 뒤 모으기를 다시 채우지 않게). 401·4001은 연결만 본다
            guard let self, self.state.isCurrent(c) else { return }
            if case .unauthorized = event {} else { guard mine == self.socketEpoch else { return } }
            self.socketEvent(event)
        }
        // 앞에 있고 소켓이 붙어 있는 동안 60초마다 기기 목록만 다시 받아 차 타일을 맞춘다(설계값). 소켓과 같이 끝난다.
        // 상태 요청 표시가 남아 있으면(기록 지우기 직후 목록 조회가 실패했던 경우) 이번 목록 성공 뒤 요청한다(7.4).
        if state.role == .owner {
            devicePollTask?.cancel()
            devicePollTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: RetryPolicy.devicePollNanos)
                    guard !Task.isCancelled, let self, self.state.isCurrent(c) else { return }
                    if self.status.socket { await self.afterDevices(epoch: self.foregroundEpoch) }
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
        setStatus { $0.socket = false }
    }

    func socketEvent(_ event: RelaySocketEvent) {
        switch event {
        case .connected(let up): socketDidConnect(up)
        case .unreachable: unreachable()   // 앞에 있는 동안 중계가 끊겨도 띠를 켠다(계약 9장 '중계 불통')
        case .alive: reached()             // 소켓 pong: 중계에 닿고 있다(보호자 폰은 HTTP가 적어 이것이 띠를 끈다)
        case .unauthorized: lose.lost(state.creds)   // 소켓 고리가 이 소켓의 자격이 지금 연결인지 이미 봤다
        case .envelope(let env):
            if backlogDone {
                let epoch = liveEpoch, fe = foregroundEpoch
                tasks.spawn { [self] in
                    guard epoch == liveEpoch else { return }   // 앞 실시간 메시지 저장이 실패했다: 이것은 받은 함에서 순서대로 다시 받는다
                    guard fe == foregroundEpoch else { return }   // 그사이 뒤로 갔다(중계에 남아 앞으로 오면 받는다)
                    if !(await inbox.store(state.creds, [env], live: true, onSaveFailed: stopLive)) { liveFailed() }
                }
            } else { bufferedLive.append(env) }
        }
    }

    /// 실시간 메시지를 저장하지 못했다. 뒤 메시지를 먼저 적용하지 않게 실시간 처리를 멈추고 소켓을 닫고 받은 함부터 다시 받는다(8.3 4번).
    private func liveFailed() {
        stopLive()
        stopSocket()
        scheduleRetry()
    }

    /// 실시간 메시지 저장이 실패했다: 앞 것의 ack를 기다리는 사이 새 실시간 메시지가 먼저 적용되지 않게 지금 막는다(8.3 4번).
    private func stopLive() {
        liveEpoch += 1
        backlogDone = false; bufferedLive = []
    }

    /// 붙지 못하고 백오프로 기다리는 소켓을 깨운다(인터넷 복구·'다시 시도', 안드로이드 onAvailable의 kick과 같다). 붙어 있으면 그대로 둔다
    private func wakeSocket() {
        // 닿지 않음으로 띠가 켜졌을 때만(백오프로 기다리는 중). 앱을 열 때 경로 알림이 붙는 중인 소켓을 버리지 않게
        guard visibility.active, state.isPaired, !status.online, socket != nil, !status.socket else { return }
        stopSocket()
        startSocket()
    }

    /// 인터넷 경로를 보기 시작한다(앱이 처음 화면을 보일 때 한 번, 다시 불려도 한 번만).
    func watchNetwork() {
        network?.watch { [weak self] satisfied in
            guard let self else { return }
            self.tasks.spawn { [self] in await self.networkPathChanged(satisfied: satisfied) }
        }
    }

    /// 인터넷 경로가 바뀌었다(NWPathMonitor). 경로가 없으면 띠를 켜고, 돌아오면 기다림 없이 다시 보낸다(7.5, 계약 9장).
    func networkPathChanged(satisfied: Bool) async {
        // 같은 상태의 경로 갱신(인터페이스·비용 변화 등)은 무시한다. 끊김↔연결이 바뀔 때만 움직인다(중계가 내려간 동안 백오프를 건너뛰며 되풀이하지 않게)
        guard pathChanged(satisfied), satisfied else { return }
        guard state.isPaired else { setStatus { $0.online = true }; return }   // 연결 안 됨: 확인할 중계가 없어 띠를 끈다
        outbox.hurryNetworkWaits()   // 인터넷이 돌아왔다: 망 실패 기다림 없이 바로 보낸다(7.5)
        guard visibility.active else { return }   // 뒤에서는 갱신하지 않는다(앞으로 오면 한다)
        wakeSocket()          // 소켓도 백오프를 기다리지 않고 다시 붙는다
        if !status.online { await refresh() } else { outbox.kick() }
    }

    // MARK: 갱신

    /// 사용자가 다시 해 달라고 했다(띠의 '다시 시도', 당겨서 새로고침): 망 실패의 기다림을 당기고 갱신한다(7.5, 결정 11-07).
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
        guard inbox.markStaleIfNeeded() else {
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
        guard let c = state.creds else { return }
        let epoch = epoch ?? foregroundEpoch
        let current = { self.state.isCurrent(c) && epoch == self.foregroundEpoch }   // 연결이 바뀌거나 뒤로 갔으면 멈춘다
        defer {
            if state.isCurrent(c) {
                refreshing = false
                // 뒤로 갔다가 이 갱신이 끝나기 전에 돌아왔다: 그때 앞으로 오기의 갱신은 '갱신 중'이라 거절됐다. 지금 다시 한다
                if epoch != foregroundEpoch, visibility.active {
                    let e = foregroundEpoch
                    if beginRefresh() { tasks.spawn { [self] in await performRefresh(epoch: e) } }
                }
                if reconnectedDuringRefresh {
                    reconnectedDuringRefresh = false
                    let e = foregroundEpoch   // 스폰한 뒤 돌기 전에 뒤로 가면 멈추게 지금 잡는다
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

        // 받은 함을 다 받는다(SyncInbox). 연결이 바뀌거나 뒤로 갔으면 멈춘다
        guard let sync = await inbox.sync(c, current: current) else { return }
        let inboxError = sync.error, saved = sync.saved
        guard state.isCurrent(c) else { return }
        if inboxError?.status == 429 { inboxHoldUntil = now().addingTimeInterval(inboxError?.retryAfter ?? RetryPolicy.rateLimitDefault) }   // 뒤로 갔어도 기한은 남긴다
        if inboxError?.isUnauthorized == true { lose.lost(c); return }   // 뒤로 갔어도 같은 연결의 401은 처리한다(7.1)
        guard current() else { return }
        observed(inboxError, since: sync.mark)
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
            if !buffered.isEmpty, !(await inbox.store(c, buffered, live: true, onSaveFailed: stopLive)) { liveFailed() }
        }
        guard current() else { return }
        if inboxOnly { await outbox.run(); return }
        if c.role == .owner { await afterDevices(epoch: epoch) }
        guard current() else { return }
        await pushToken.now(c)
        guard current() else { return }
        await outbox.run()
    }

    // MARK: 기기 목록(GET /v1/devices, 7.2)·상태 요청(state_request, G39)

    /// 기기 목록. owner만 받는다. 성공하면(적용하지 않고 버린 낡은 목록도) true.
    @discardableResult
    func refreshDevices() async -> Bool {
        guard let c = state.creds, c.role == .owner else { return false }   // 이 일의 연결(P5)
        let mark = mark(), carMark = inbox.carLiveSeen
        do {
            let r = try await relay.devices(token: c.token)
            guard state.isCurrent(c) else { return false }
            observed(nil, since: mark)   // 이 HTTP도 중계에 닿았다(띠를 끄는 HTTP 성공, 안드로이드 fetchDevices와 같다)
            // 목록을 기다리는 사이 차의 실시간 봉투를 받았으면 차 행의 연결 상태는 그쪽이 새것이다(7.2: 그 자리에서 바꾼다).
            let keepCarLink = inbox.carLiveSeen != carMark
            // 더 큰 snapshot_seq는 늦게 끝났어도 새것이다. 같으면 나중에 시작한 목록만(차 online은 seq 없이 바뀐다). 작으면 버린다.
            // 적용 판단은 여기 한 곳이다. 버리거나 저장하지 못한 목록은 순번을 올리지 않는다(같은 seq의 나중 목록을 막지 않게)
            let s0 = state.state.devicesSnapshotSeq
            guard r.snapshotSeq > s0 || (r.snapshotSeq == s0 && mark.ticket > appliedDevicesTicket) else { return true }
            // 목록과 snapshot_seq는 한 번에 저장한다.
            let seenAt = keepCarLink ? inbox.lastCarLiveAt : nil
            let saved = state.commit(c) { InboxRules.applyDevices(r, carSeenAt: seenAt, in: &$0) }
            if saved { appliedDevicesTicket = max(appliedDevicesTicket, mark.ticket) }
            return true
        } catch let e as RelayError {
            gate.unauthorized(e, c: c)
            if state.isCurrent(c) { observed(e, since: mark) }
        } catch {}
        return false
    }

    /// 기기 목록 조회가 성공한 뒤에 상태를 요청한다(계약 7.4). 갱신·기록 지우기·다시 붙기·60초 주기가 함께 쓰는 길이다.
    /// `epoch`는 일을 띄운 때의 화면 세대다. 띄운 뒤 돌기 전이나 목록을 기다리는 사이 뒤로 갔으면 시작하지 않는다(2장 '뒤로 가면 멈춘다').
    func afterDevices(epoch: Int) async {
        guard epoch == foregroundEpoch, !Task.isCancelled else { return }
        if await refreshDevices(), epoch == foregroundEpoch, !Task.isCancelled { await requestStateIfNeeded() }
    }

    /// 상태 요청. owner만, 10분 안에 다시 보내지 않는다. 오프라인이면 보낼 함에 넣지 않고 표시만 둔다(7.5).
    /// 동시에 불러도 한 번만 보내도록 첫 await 전에 '요청 중' 표시를 세운다.
    func requestStateIfNeeded() async {
        guard let c = state.creds, c.role == .owner, state.state.needsStateRequest, !requestingState else { return }   // c: 이 일의 연결(P5)
        if outbox.isHeld { return }   // 메시지 429 기다림 중(표시는 남아 다음 계기에 보낸다)
        if let last = state.state.lastStateRequestAt, now().timeIntervalSince(last) < Retention.stateRequestGap { return }
        // 보내기 전에 시각을 먼저 저장한다(안드로이드 requestState와 같다). 응답이 유실돼도 10분 제한이 남는다.
        let t = now()
        guard state.commit(c, { $0.lastStateRequestAt = t }) else { return }
        requestingState = true
        let clears = clearCount
        defer { requestingState = false }
        let env = Envelope(id: ULID.generate(date: t), type: MessageType.stateRequest, ts: Rfc3339.string(t), body: .object([:]))
        do {
            _ = try await gate.observed { try await relay.send(token: c.token, env) }
            guard state.isCurrent(c) else { return }
            // 기다리는 사이 기록을 지웠다면 그 뒤에 필요해진 요청 표시는 남긴다(10분이 지나면 다시 요청한다).
            let cleared = clears != clearCount
            state.commit(c) { $0.needsStateRequest = cleared }
        } catch let e as RelayError {
            gate.unauthorized(e, c: c)
            // 같은 메시지 한도다(1.4): 뒤따르는 보낼 함도 기다린다. 표시는 남아 다음 계기에 다시 요청한다
            if e.status == 429 { outbox.holdMessages(c, for: e.retryAfter ?? RetryPolicy.rateLimitDefault) }
        } catch {}
    }
}
