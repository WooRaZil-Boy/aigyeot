import Foundation

/// 중계 WebSocket(계약 2장). 폰은 앱이 앞에 있을 때만 붙는다.
/// - 인증은 업그레이드 요청의 `Authorization: Bearer`.
/// - 25초마다 글자 `ping`, 10초 안에 `pong`이 없으면 끊고 다시 붙는다(`sendPing`은 쓰지 않는다).
/// - 다시 붙기 1·2·4·8·16·32·60초. 열리면 1초로 되돌린다. 4001은 토큰 지워짐, 4002는 새 연결로 바뀜, 4008은 60초 뒤.
final class RelaySocket {
    enum Event {
        case connected(Bool)
        case envelope(Envelope)
        case unauthorized
    }

    private let baseURL: URL
    private let token: () -> String?
    private let onEvent: @MainActor (Event) -> Void
    private var loop: Task<Void, Never>?

    init(baseURL: URL, token: @escaping () -> String?, onEvent: @escaping @MainActor (Event) -> Void) {
        self.baseURL = baseURL
        self.token = token
        self.onEvent = onEvent
    }

    /// `http`를 `ws`로, `https`를 `wss`로 바꾸고 `/v1/ws`를 붙인다(0장).
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
        var delay: UInt64 = 1
        while !Task.isCancelled {
            let outcome = await connectOnce(opened: { delay = 1 })
            if Task.isCancelled { return }
            await onEvent(.connected(false))
            switch outcome {
            case .unauthorized:
                await onEvent(.unauthorized)
                return
            case .replaced:
                return
            case .tooOften:
                delay = 60
            case .rateLimited(let wait):
                delay = UInt64(max(1, wait.rounded(.up)))   // 429의 Retry-After(1.1)
            case .dropped:
                break
            }
            try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
            delay = min(delay * 2, 60)
        }
    }

    private enum Outcome { case unauthorized, replaced, tooOften, rateLimited(TimeInterval), dropped }

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
                    guard let msg = try? await task.receive() else { await pong.close(); return }
                    guard case .string(let text) = msg else { continue }
                    if text == "pong" { await pong.mark(); continue }
                    // 모르는 프레임은 무시한다(계약 2장). 봉투만 올린다.
                    if let env = try? ProtocolCoding.decoder.decode(Envelope.self, from: Data(text.utf8)) {
                        await onEvent(.envelope(env))
                    }
                }
            }
            g.addTask {
                var announced = false
                while !Task.isCancelled {
                    await pong.reset()
                    do { try await task.send(.string("ping")) } catch { return }
                    // 10초 안에 pong이 없으면 끊는다.
                    var ok = false
                    for _ in 0..<100 where !Task.isCancelled {
                        if await pong.seen { ok = true; break }
                        if await pong.closed { break }
                        try? await Task.sleep(nanoseconds: 100_000_000)
                    }
                    if !ok || Task.isCancelled { return }
                    if !announced {
                        announced = true
                        opened()
                        await onEvent(.connected(true))
                    }
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
        if Task.isCancelled { return .dropped }
        if let http = response as? HTTPURLResponse {
            if http.statusCode == 401 { return .unauthorized }
            if http.statusCode == 429 { return .rateLimited(http.value(forHTTPHeaderField: "Retry-After").flatMap { TimeInterval($0) } ?? 60) }
        }
        switch closeCode.rawValue {
        case 4001: return .unauthorized
        case 4002: return .replaced
        case 4008: return .tooOften
        default: return .dropped
        }
    }
}

private actor PongFlag {
    private(set) var seen = false
    private(set) var closed = false
    func close() { closed = true }
    func mark() { seen = true }
    func reset() { seen = false }
}
