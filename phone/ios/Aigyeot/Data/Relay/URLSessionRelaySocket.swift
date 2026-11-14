import Foundation

/// RelaySocket 구현: 그 주소로 URLSessionRelaySocket을 열어 붙기 시작한다. 토큰은 연 자격의 것을 다시 붙을 때도 쓴다.
struct URLSessionRelaySockets: RelaySocket {
    let baseURL: URL
    func open(token: String, onEvent: @escaping @MainActor (RelaySocketEvent) -> Void) -> SocketHandle {
        let s = URLSessionRelaySocket(baseURL: baseURL, token: { token }, onEvent: onEvent)
        s.start()
        return s
    }
}

/// 중계 WebSocket. 폰은 앱이 앞에 있을 때만 붙는다.
/// - 인증은 업그레이드 요청의 `Authorization: Bearer`.
/// - 25초마다 글자 `ping`, 10초 안에 `pong`이 없으면 끊고 다시 붙는다(`sendPing`은 쓰지 않는다).
/// - 다시 붙기 1·2·4·8·16·32·60초. 열리면 1초로 되돌린다. 4001은 토큰 지워짐, 4002는 새 연결로 바뀜, 4008은 60초 뒤.
final class URLSessionRelaySocket: SocketHandle {
    typealias Event = RelaySocketEvent

    private let baseURL: URL
    private let token: () -> String?
    private let onEvent: @MainActor (Event) -> Void
    private var loop: Task<Void, Never>?

    init(baseURL: URL, token: @escaping () -> String?, onEvent: @escaping @MainActor (Event) -> Void) {
        self.baseURL = baseURL
        self.token = token
        self.onEvent = onEvent
    }

    /// `http`를 `ws`로, `https`를 `wss`로 바꾸고 `/v1/ws`를 붙인다.
    static func socketURL(from base: URL) -> URL? {
        var c = URLComponents(url: base.appendingPathComponent("v1/ws"), resolvingAgainstBaseURL: false)
        c?.scheme = base.scheme == "https" ? "wss" : "ws"
        return c?.url
    }

    func start() {
        guard loop == nil else { return }
        loop = Task { [weak self] in await self?.run() }
    }

    func stop() {
        loop?.cancel()
        loop = nil
        Task { @MainActor [onEvent] in onEvent(.connected(false)) }
    }

    private func run() async {
        var delay = RetryPolicy.socketFirst
        while !Task.isCancelled {
            let outcome = await connectOnce(opened: { delay = RetryPolicy.socketFirst })
            if Task.isCancelled {
                if case .unauthorized = outcome { await onEvent(.unauthorized) }   // 모델은 연결 세대를 보고 받는다
                return
            }
            await onEvent(.connected(false))
            switch outcome {
            case .unauthorized:
                await onEvent(.unauthorized)
                return
            case .replaced:
                return
            case .tooOften:
                delay = RetryPolicy.socketTooOften
            case .rateLimited(let wait):
                delay = UInt64(max(1, wait.rounded(.up)))   // 429의 Retry-After
            case .unreachable:
                // 그사이 앱이 뒤로 가 멈췄으면 알리지 않는다. stop()은 메인에서 부르므로 메인에서 취소를 본다.
                await MainActor.run { [onEvent] in if !Task.isCancelled { onEvent(.unreachable) } }
            case .dropped:
                break
            }
            try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
            delay = min(delay * 2, RetryPolicy.socketMax)
        }
    }

    private enum Outcome { case unauthorized, replaced, tooOften, rateLimited(TimeInterval), unreachable, dropped }

    private func connectOnce(opened: @escaping () -> Void) async -> Outcome {
        guard let url = Self.socketURL(from: baseURL), let token = token() else { return .unauthorized }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let task = URLSession.shared.webSocketTask(with: req)
        task.resume()
        defer { task.cancel(with: .goingAway, reason: nil) }

        let pong = PongFlag()
        let onEvent = self.onEvent
        // 받기와 ping을 한 그룹의 직접 자식으로 돌린다. 먼저 끝난 쪽이 있으면 소켓을 닫아 나머지도 끝낸다.
        var response: URLResponse?
        var closeCode = URLSessionWebSocketTask.CloseCode.invalid
        // receive()·send()는 Task 취소를 소켓에 넘기지 않으므로, 협상 중 취소도 소켓을 바로 닫게 한다.
        await withTaskCancellationHandler {
        await withTaskGroup(of: Void.self) { g in
            g.addTask {
                while !Task.isCancelled {
                    guard let msg = try? await task.receive() else {
                        if !Task.isCancelled { await pong.ioFail() }   // 닫기 프레임 없이 끊겼다(망 끊김). 닫기 코드가 없으면 닿지 않음으로 본다
                        await pong.close(); return
                    }
                    guard case .string(let text) = msg else { continue }
                    if text == "pong" {
                        await pong.mark()
                        // '붙음'은 받기 작업 하나만 알린다: 첫 pong이든 첫 봉투든 그 뒤의 봉투보다 먼저 전달된다
                        if await pong.claimAnnounce() { opened(); await onEvent(.connected(true)) } else { await onEvent(.alive) }
                        continue
                    }
                    // 모르는 프레임은 무시한다. 봉투만 올린다.
                    if let env = try? ProtocolCoding.decoder.decode(Envelope.self, from: Data(text.utf8)) {
                        // 다시 붙자마자 중계는 밀린 것을 첫 pong보다 먼저 보낸다. 봉투가 왔으면 열린 것이니 '붙음'을 먼저 알려
                        // 모델이 버퍼링을 켠 뒤 봉투를 받게 한다(받은 함을 먼저 처리).
                        if await pong.claimAnnounce() { opened(); await onEvent(.connected(true)) }
                        await onEvent(.envelope(env))
                    }
                }
            }
            g.addTask {
                while !Task.isCancelled {
                    await pong.reset()
                    do { try await task.send(.string("ping")) } catch { await pong.ioFail(); return }   // 받기보다 먼저 끝나도 '닿지 않음'(띠)
                    // 10초 안에 pong이 없으면 끊는다.
                    var ok = false
                    for _ in 0..<100 where !Task.isCancelled {
                        if await pong.seen { ok = true; break }
                        if await pong.closed { break }
                        try? await Task.sleep(nanoseconds: 100_000_000)
                    }
                    let closed = await pong.closed
                    if !ok && !Task.isCancelled && !closed { await pong.timeOut() }   // 열렸는데 pong이 없다: 중계에 닿지 않는다
                    if !ok || Task.isCancelled { return }
                    try? await Task.sleep(nanoseconds: 25_000_000_000)
                    if Task.isCancelled { return }
                    if await pong.closed { return }
                }
            }
            await g.next()
            response = task.response
            closeCode = task.closeCode
            g.cancelAll()
            task.cancel(with: .goingAway, reason: nil)   // receive()가 취소에 반응하지 않을 수 있어 소켓을 닫는다
        }
        } onCancel: {
            task.cancel(with: .goingAway, reason: nil)
        }
        // 앱이 뒤로 가 멈추는 순간 이미 받은 401·4001은 잃지 않는다(GONE). 그 밖에는 멈춤이다.
        if Task.isCancelled { return (response as? HTTPURLResponse)?.statusCode == 401 || closeCode.rawValue == 4001 ? .unauthorized : .dropped }
        // 중계가 보낸 닫기 코드는 확인된 사실이라 먼저 본다(응답 객체나 pong 시간 초과와 겹쳐도 4001이면 GONE, 닫기 코드)
        switch closeCode.rawValue {
        case 4001: return .unauthorized
        case 4002: return .replaced
        case 4008: return .tooOften
        default: break
        }
        if await pong.timedOut { return .unreachable }
        // 열렸다가 망이 끊겼다(띠)   // 101 뒤 10초 안에 pong이 없었다('끊고 다시 붙는다', 띠는 켠다)
        if await pong.ioFailed, closeCode == .invalid, (response as? HTTPURLResponse)?.statusCode == 101 { return .unreachable }
        if let http = response as? HTTPURLResponse {
            if http.statusCode == 401 { return .unauthorized }
            if http.statusCode == 429 { return .rateLimited(HTTPRelayClient.retryAfter(http) ?? RetryPolicy.rateLimitDefault) }
            if http.statusCode >= 500 { return .unreachable }
        } else {
            return .unreachable   // 연결조차 안 됨(거절·인터넷 없음). 열렸다 끊긴 것은 101 응답이 있어 여기 오지 않는다
        }
        return .dropped
    }
}

private actor PongFlag {
    private(set) var seen = false
    private(set) var closed = false
    func close() { closed = true }
    func mark() { seen = true }
    func reset() { seen = false }
    private(set) var timedOut = false
    private(set) var ioFailed = false
    func ioFail() { ioFailed = true }
    func timeOut() { timedOut = true }
    /// '붙음'은 한 접속에 한 번만 알린다(첫 pong이든 첫 봉투든 먼저 온 쪽).
    private var announced = false
    func claimAnnounce() -> Bool {
        if announced { return false }
        announced = true
        return true
    }
}
