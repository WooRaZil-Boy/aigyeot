import XCTest
import Network
@testable import Aigyeot

final class PairCodeTests: XCTestCase {
    func test_연결_코드는_6자리_숫자() {
        XCTAssertEqual(PairCode.normalize("042917"), .pair("042917"))
        XCTAssertEqual(PairCode.normalize(" 042 917 "), .pair("042917"))
        XCTAssertNil(PairCode.normalize("04291"))
        XCTAssertNil(PairCode.normalize("0429170"))
    }

    func test_초대_코드는_10자_Crockford() {
        XCTAssertEqual(PairCode.normalize("7k3qm-2xw9d"), .invite("7K3QM2XW9D"))
        XCTAssertEqual(PairCode.normalize("7K3QM-2XW9D")?.display, "7K3QM-2XW9D")
        // O→0, I·L→1
        XCTAssertEqual(PairCode.normalize("OILOILOILO"), .invite("0110110110"))
        XCTAssertNil(PairCode.normalize("7K3QM2XW9U"))   // U는 Crockford에 없다
    }

    func test_링크는_scheme_host_code를_검사한다() {
        XCTAssertEqual(PairCode.parse(link: URL(string: "aigyeot://pair?code=042917")!), .pair("042917"))
        XCTAssertEqual(PairCode.parse(link: URL(string: "aigyeot://pair?code=7K3QM2XW9D")!), .invite("7K3QM2XW9D"))
        XCTAssertNil(PairCode.parse(link: URL(string: "aigyeot://pair?code=12")!))
        XCTAssertNil(PairCode.parse(link: URL(string: "aigyeot://pair")!))
        XCTAssertNil(PairCode.parse(link: URL(string: "aigyeot://other?code=042917")!))
        XCTAssertNil(PairCode.parse(link: URL(string: "https://x.example/pair?code=042917")!))
    }

    func test_QR_글자는_링크나_코드() {
        XCTAssertEqual(PairCode.parse(scanned: "aigyeot://pair?code=042917"), .pair("042917"))
        XCTAssertEqual(PairCode.parse(scanned: "042917"), .pair("042917"))
        XCTAssertNil(PairCode.parse(scanned: "https://example.com"))
    }
}

final class WaveformTests: XCTestCase {
    /// U13 ①: 막대 60개 × (4pt + 간격 3pt)가 화면 폭을 넘었다. 폭에 들어가는 만큼만 최근 막대를 그린다.
    func test_녹음_파형은_화면_폭을_넘지_않는다() {
        for width in [0.0, 3.0, 4.0, 280.0, 327.0, 345.0, 417.0, 600.0] as [CGFloat] {
            let n = Waveform.barCount(fitting: width)
            XCTAssertLessThanOrEqual(CGFloat(max(0, n * 7 - 3)), width, "\(width)")
            XCTAssertGreaterThan(CGFloat((n + 1) * 7 - 3), width, "\(width)")   // 더 넣으면 넘는다(빈자리 없이 채운다)
        }
        XCTAssertEqual(Waveform.barCount(fitting: 327), 47)
        XCTAssertEqual(Waveform.barCount(fitting: 417), 60)
    }
}

/// 다시 붙자마자 밀린 봉투를 먼저 보내고 pong은 늦게(여기서는 아예) 주는 중계 흉내.
final class EnvelopeFirstServer {
    let listener: NWListener
    private(set) var port: UInt16 = 0
    private var conns: [NWConnection] = []

    /// `closeCode`: 첫 글 뒤 그 코드로 닫기 프레임을 보낸다(중계의 4001·4002·4008처럼).
    init(frame: String, pongThenDrop: Bool = false, closeCode: UInt16? = nil) throws {
        let params = NWParameters.tcp
        params.defaultProtocolStack.applicationProtocols.insert(NWProtocolWebSocket.Options(), at: 0)
        listener = try NWListener(using: params, on: .any)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { if case .ready = $0 { ready.signal() } }
        listener.newConnectionHandler = { [weak self] c in
            self?.conns.append(c)
            c.start(queue: .global())
            let meta = NWProtocolWebSocket.Metadata(opcode: .text)
            c.send(content: Data(frame.utf8), contentContext: NWConnection.ContentContext(identifier: "env", metadata: [meta]),
                   isComplete: true, completion: .idempotent)
            if let closeCode {
                let close = NWProtocolWebSocket.Metadata(opcode: .close)
                close.closeCode = .privateCode(closeCode)
                c.send(content: nil, contentContext: NWConnection.ContentContext(identifier: "close", metadata: [close]),
                       isComplete: true, completion: .idempotent)
                return
            }
            func keep() {
                c.receiveMessage { data, _, _, e in
                    guard e == nil else { return }
                    if pongThenDrop, let data, String(decoding: data, as: UTF8.self) == "ping" {
                        c.send(content: Data("pong".utf8), contentContext: NWConnection.ContentContext(identifier: "pong", metadata: [meta]),
                               isComplete: true, completion: .contentProcessed { _ in DispatchQueue.global().asyncAfter(deadline: .now() + 0.5) { c.forceCancel() } })
                        return
                    }
                    keep()
                }
            }
            keep()   // 기본: ping은 받기만 하고 pong은 주지 않는다
        }
        listener.start(queue: .global())
        _ = ready.wait(timeout: .now() + 5)
        port = listener.port?.rawValue ?? 0
    }

    func stop() { conns.forEach { $0.cancel() }; listener.cancel() }
}

@MainActor
final class RelaySocketOrderTests: XCTestCase {
    func test_열렸는데_pong이_없으면_닿지_않음을_알린다() async throws {
        let server = try EnvelopeFirstServer(frame: "x")   // 봉투가 아닌 글 하나만 주고 pong은 주지 않는다
        defer { server.stop() }
        let got = expectation(description: "unreachable")
        got.assertForOverFulfill = false
        let s = URLSessionRelaySocket(baseURL: URL(string: "http://127.0.0.1:\(server.port)")!, token: { "t" }) { e in
            if case .unreachable = e { got.fulfill() }
        }
        s.start()
        await fulfillment(of: [got], timeout: 25)   // 10초 안에 pong이 없으면 끊고, 중계 불통으로 띠를 켠다(2장·9장). 전체 시험 중에는 늦어질 수 있어 넉넉히
        s.stop()
    }

    func test_중계가_4001로_닫으면_연결이_지워졌다고_알린다() async throws {
        let server = try EnvelopeFirstServer(frame: "x", closeCode: 4001)   // 기기를 지운 중계가 소켓을 닫는다(2장)
        defer { server.stop() }
        let got = expectation(description: "unauthorized")
        got.assertForOverFulfill = false
        var unreachable = false
        let s = URLSessionRelaySocket(baseURL: URL(string: "http://127.0.0.1:\(server.port)")!, token: { "t" }) { e in
            if case .unauthorized = e { got.fulfill() }
            if case .unreachable = e { unreachable = true }
        }
        s.start()
        await fulfillment(of: [got], timeout: 15)
        XCTAssertFalse(unreachable)   // 띠가 아니라 끊김 화면(7.1 GONE)
        s.stop()
    }

    func test_열린_소켓이_끊기면_닿지_않음을_알린다() async throws {
        let server = try EnvelopeFirstServer(frame: "x", pongThenDrop: true)   // pong 한 번 뒤 닫기 없이 끊는다(지하철 등)
        defer { server.stop() }
        var events: [String] = []
        let got = expectation(description: "unreachable")
        got.assertForOverFulfill = false
        let s = URLSessionRelaySocket(baseURL: URL(string: "http://127.0.0.1:\(server.port)")!, token: { "t" }) { e in
            switch e {
            case .connected(true): events.append("connected")
            case .unreachable: events.append("unreachable"); got.fulfill()
            default: break
            }
        }
        s.start()
        await fulfillment(of: [got], timeout: 8)
        s.stop()
        XCTAssertEqual(events.first, "connected")
    }

    func test_소켓은_첫_봉투보다_먼저_붙음을_알린다() async throws {
        let frame = #"{"v":1,"id":"01JD9Z0Q4M8T1V6B5R3H2CD7CA","seq":1,"type":"feeding_state","car":"c_1","from":{"role":"car","device":"d_9"},"ts":"2026-11-05T08:00:00Z","body":{}}"#
        let server = try EnvelopeFirstServer(frame: frame)
        defer { server.stop() }
        XCTAssertGreaterThan(server.port, 0)
        var events: [String] = []
        let got = expectation(description: "envelope")
        let s = URLSessionRelaySocket(baseURL: URL(string: "http://127.0.0.1:\(server.port)")!, token: { "t" }) { e in
            switch e {
            case .connected(let up): events.append("connected(\(up))")
            case .envelope: events.append("envelope"); got.fulfill()
            default: break
            }
        }
        s.start()
        await fulfillment(of: [got], timeout: 5)
        s.stop()
        // 모델은 '붙음'에서 버퍼링을 켠다. 봉투가 먼저 오면 밀린 것을 실시간으로 처리한다(8.3).
        XCTAssertEqual(Array(events.prefix(2)), ["connected(true)", "envelope"])
    }
}

final class RelayClientInboxTests: XCTestCase {
    func test_읽지_못한_봉투는_id와_seq만_남겨_ack하고_넘어가게_한다() throws {
        let page = #"{"messages":[{"v":1,"id":"A","seq":1,"type":"feeding_state","car":"c_1","from":{"role":"car","device":"d"},"ts":"2026-11-05T08:00:00Z","body":{}},{"v":1,"id":"B","seq":2,"type":"feeding_state","car":"c_1","body":{}}],"more":false}"#
        let r = try HTTPRelayClient.lenientInbox(Data(page.utf8), car: "c_1")
        XCTAssertEqual(r.messages.map(\.id), ["A", "B"])
        XCTAssertEqual(r.messages[0].v, 1)
        XCTAssertEqual(r.messages[1].v, 0)          // ts가 없어 읽지 못했다: 판 0(모델은 ack만 한다, 8.3 4번)
        XCTAssertEqual(r.messages[1].seq, 2)        // 다음 after_seq가 이것을 넘는다
        XCTAssertEqual(r.messages[1].car, "c_1")    // 다른 차 메시지로 걸러지지 않는다
    }

    func test_Retry_After는_유한한_값만_받고_하루로_자른다() {
        func wait(_ v: String) -> TimeInterval? {
            HTTPRelayClient.retryAfter(HTTPURLResponse(url: URL(string: "http://x")!, statusCode: 429, httpVersion: nil, headerFields: ["Retry-After": v])!)
        }
        XCTAssertEqual(wait("30"), 30)
        XCTAssertEqual(wait("3600"), 3600)          // 노래 PUT의 한 시간은 그대로
        XCTAssertEqual(wait("1e20"), 86_400)
        XCTAssertNil(wait("inf"))
        XCTAssertNil(wait("nan"))
        XCTAssertNil(wait("-5"))
    }
}

@MainActor
final class RecorderModelTests: XCTestCase {
    func test_권한_창을_기다리는_사이_버리면_늦게_허용해도_녹음을_시작하지_않는다() async {
        let rec = RecorderViewModel(recorder: AudioSongRecorder())
        rec.requestPermission = { [weak rec] in rec?.discard(); return true }   // 기다리는 사이 시트가 닫혔다(onDisappear → discard)
        await rec.start()
        XCTAssertEqual(rec.phase, .idle)   // 녹음기·반복 타이머·오디오 세션을 만들지 않았다
        XCTAssertNil(rec.fileURL)
        XCTAssertNil(rec.error)
    }

    func test_권한_창을_기다리는_사이_또_누르면_한_번만_시작한다() async {
        let rec = RecorderViewModel(recorder: AudioSongRecorder())
        var asked = 0
        rec.requestPermission = { asked += 1; try? await Task.sleep(nanoseconds: 50_000_000); return false }
        async let a: Void = rec.start()
        async let b: Void = rec.start()
        _ = await (a, b)
        XCTAssertEqual(asked, 1)
    }
}

final class OutboxPolicyTests: XCTestCase {
    private func http(_ s: Int, _ c: String? = nil, retry: TimeInterval? = nil) -> RelayError { .http(status: s, code: c, retryAfter: retry) }

    func test_응답별_처리() {
        XCTAssertEqual(OutboxPolicy.classify(.network, attempts: 0), .retry(1))
        XCTAssertEqual(OutboxPolicy.classify(.network, attempts: 3), .retry(8))
        XCTAssertEqual(OutboxPolicy.classify(.network, attempts: 20), .retry(60))
        XCTAssertEqual(OutboxPolicy.classify(http(401), attempts: 0), .unauthorized)
        XCTAssertEqual(OutboxPolicy.classify(http(429, retry: 30), attempts: 0), .retry(30))
        XCTAssertEqual(OutboxPolicy.classify(http(503), attempts: 1), .retry(2))
        XCTAssertEqual(OutboxPolicy.classify(http(409, "song_quota"), attempts: 0), .retry(60))
        XCTAssertEqual(OutboxPolicy.classify(http(422, "song_blob_missing"), attempts: 0), .reupload)
        for s in [400, 403, 413, 415] { XCTAssertEqual(OutboxPolicy.classify(http(s), attempts: 0), .drop, "\(s)") }
        XCTAssertEqual(OutboxPolicy.classify(http(409, "id_conflict"), attempts: 0), .drop)
    }
}

// MARK: 앱(합성 루트 AppContainer를 그대로 쓴다. FakeRelay는 TestKit)

@MainActor
final class AppModelTests: XCTestCase {
    var relay: FakeRelay!
    var store: MemoryStateStore!
    var creds: MemoryCredentialStore!
    var clock: Date!
    var app: AppContainer!

    func make(role: PhoneRole = .owner, paired: Bool = true) {
        relay = FakeRelay()
        store = MemoryStateStore(PhoneState(introSeen: true, carId: paired ? "c_1" : nil))   // 첫 안내를 지난 보통 상태(연결됐으면 차 id가 있다). 첫 실행은 U62 시험이 따로 만든다
        creds = MemoryCredentialStore(paired ? Credentials(carId: "c_1", deviceId: "d_1", token: "at1.c_1.d_1.x", role: role) : nil)
        clock = Date(timeIntervalSince1970: 1_800_000_000)
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
    }

    override func setUp() async throws { await MainActor.run { make() } }

    private func env(_ type: String, seq: Int64, body: some Encodable, id: String = ULID.generate(), from: Role = .car) -> Envelope {
        Envelope(id: id, seq: seq, type: type, car: "c_1", from: Sender(role: from, device: "d_9"),
                 ts: "2026-11-05T08:00:00Z", body: try! JSONValue(encoding: body))
    }

    private func feedingState(_ seq: Int64, last: String?) -> Envelope {
        env(MessageType.feedingState, seq: seq, body: FeedingStateBody(lastFeedingStart: last, windowStartExpected: nil))
    }

    // MARK: 연결

    func test_첫_안내를_넘기지_않고_링크로_연결해도_홈으로_간다() async {
        make(paired: false)
        store = MemoryStateStore(PhoneState())   // 설치 직후: 첫 안내를 아직 넘기지 않았다
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertFalse(app.state.state.introSeen)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .guardian))
        let f = await app.pairAndWait(.invite("7K3QM2XW9D"))
        XCTAssertNil(f)
        XCTAssertTrue(app.state.state.introSeen)    // 안내 화면이 남지 않는다(7.1 DONE → HOME)
    }

    func test_첫_실행_안내에서_받은_링크는_안내를_두고_차와_연결하기에서_확인_화면을_연다() {
        make(paired: false)
        store = MemoryStateStore(PhoneState())   // 연결도 '먼저 기록만'도 고르지 않은 첫 실행
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertNil(app.appViewModel.pendingCode)                      // 안내를 덮지 않는다: 무엇이 오가는지 읽기 전에 연결되지 않게(U62)
        XCTAssertFalse(app.state.state.introSeen)
        app.appViewModel.connectFromIntro()
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))   // '차와 연결하기'에서 그 코드로 확인 화면
        XCTAssertFalse(app.state.state.introSeen)                // 안내는 그대로: 확인 화면을 닫으면 안내로 돌아간다
        app.appViewModel.pendingCode = nil
        app.appViewModel.connectFromIntro()                             // 한 번만 쓴다: 다시 누르면 연결 방법 화면
        XCTAssertTrue(app.appViewModel.connectShown)
        XCTAssertTrue(app.state.state.introSeen)
    }

    func test_첫_실행_안내에서_기록만_고르면_받은_링크_코드는_버린다() {
        make(paired: false)
        store = MemoryStateStore(PhoneState())
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        app.appViewModel.recordsOnlyFromIntro()
        XCTAssertTrue(app.state.state.introSeen)
        XCTAssertNil(app.appViewModel.introCode)
        XCTAssertNil(app.appViewModel.pendingCode)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=555555")!)   // 안내를 지난 뒤의 링크는 전처럼 확인 화면
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("555555"))
    }

    func test_연결이_없으면_늦은_푸시로_배너를_띄우지_않고_이_연결에_없는_알림은_빈_상세를_열지_않는다() async {
        make(role: .guardian)
        await app.appViewModel.openBanner("M-OLD")                      // 이전 차의 늦은 푸시: 받은 함에서 본문을 받을 수 없다
        XCTAssertNil(app.appViewModel.ui.openAlert)                      // '불러오고 있어요'에 머무는 상세를 열지 않는다
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body, id: "M9")], live: false)
        await app.appViewModel.openBanner("M9")                         // 본문이 있으면 바로 연다
        XCTAssertEqual(app.appViewModel.ui.openAlert, "M9")
        make(role: .guardian, paired: false)
        app.syncInbox.onPush("M-LATE")                                 // 끊은 뒤 늦게 온 푸시
        XCTAssertNil(app.appViewModel.ui.banner)
    }

    func test_차에_반영된_수유는_7일이_지나도_실패로_알리지_않는다() async {
        let old = clock.addingTimeInterval(-8 * 86400)
        store.value.feedingLog = [FeedingRecord(id: "F1", at: old, status: .reflected)]
        store.value.outbox = [OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: old, nextTryAt: old)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        await app.sendOutbox.run()
        XCTAssertTrue(app.state.state.outbox.isEmpty)            // 7일 지난 항목은 정리한다
        XCTAssertFalse(app.state.state.unseenDrops.contains(MessageType.feedingStarted))   // 차에 반영됐으니 실패 띠는 없다(7.5)
        XCTAssertNil(app.status.ui.notice)
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .reflected)
    }

    func test_인터넷이_돌아오면_망_실패_기다림_없이_바로_보낸다() async {
        store.value.outbox = [OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.sendError = .network
        for _ in 0..<5 { await app.sendOutbox.run(); clock = app.state.state.outbox[0].nextTryAt }   // 오프라인이 길어진다
        await app.sendOutbox.run()   // 또 실패해 기다림이 60초가 된다
        XCTAssertGreaterThan(app.state.state.outbox[0].nextTryAt, clock.addingTimeInterval(30))
        XCTAssertEqual(app.state.state.outbox.first?.networkWait, true)
        relay.sendError = nil
        await app.sendOutbox.run()
        XCTAssertTrue(relay.sent.isEmpty)   // 기다리는 중
        await app.keepConnected.networkPathChanged(satisfied: true)   // 인터넷이 돌아왔다
        await app.sendOutbox.run()
        XCTAssertEqual(relay.sent.map(\.id), ["F1"])   // 기다리지 않고 바로 보낸다(7.5)
    }

    func test_다시_시도는_망_실패_기다림을_당기고_중계가_준_기다림은_지킨다() async throws {
        store.value.outbox = [OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.sendError = .network
        await app.sendOutbox.run(); await app.sendOutbox.run()
        relay.sendError = nil
        await app.status.retryNow()   // 띠의 '다시 시도'
        XCTAssertEqual(relay.sent.map(\.id), ["F1"])
        // 노래 PUT 429(시간당 한도)의 한 시간 기다림은 당기지 않는다
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        relay.putError = .http(status: 429, code: "rate_limited", retryAfter: 3600)
        await app.sendOutbox.run()
        relay.putError = nil
        XCTAssertEqual(app.state.state.outbox.first?.networkWait, false)
        await app.status.retryNow()
        await app.keepConnected.networkPathChanged(satisfied: true)
        await app.sendOutbox.run()
        XCTAssertTrue(relay.uploaded.isEmpty)   // Retry-After를 지킨다
        for r in app.state.state.songs { SongFiles.remove(r.id) }
    }

    func test_받은_함_429_기다림_중에도_다시_시도는_보낼_함을_보낸다() async {
        relay.inboxError = .http(status: 429, code: "rate_limited", retryAfter: 600)
        await app.keepConnected.refresh()   // 받은 함 기다림이 생긴다
        relay.inboxError = nil
        app.recordCare.start(.feed)    // 그 사이 수유를 적는다(autoFlush 끔이라 아직 안 보냄)
        await app.status.retryNow()  // 띠의 '다시 시도': 받은 함은 기다려도 보낼 함은 보낸다(7.5)
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.feedingStarted])
    }

    func test_인터넷이_돌아오면_기다리던_소켓을_바로_다시_띄운다() async {
        // 닿지 않는 주소: 소켓은 붙지 못하고 백오프로 기다린다
        app = AppContainer(relay: relay, store: store, credentials: creds, baseURL: URL(string: "http://127.0.0.1:9")!,
                         now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        app.lifecycle.onForeground(); await app.tasks.settle()
        let connecting = app.keepConnected.socket.map(ObjectIdentifier.init)
        XCTAssertNotNil(connecting)
        await app.keepConnected.networkPathChanged(satisfied: true)   // 앱을 열 때의 경로 알림: 붙는 중인 소켓은 그대로
        XCTAssertEqual(app.keepConnected.socket.map(ObjectIdentifier.init), connecting)
        for _ in 0..<50 where !app.status.ui.offline { try? await Task.sleep(for: .milliseconds(100)) }   // 붙지 못해 '닿지 않음'(백오프 대기)
        XCTAssertTrue(app.status.ui.offline)
        XCTAssertFalse(app.keepConnected.status.socket)
        await app.keepConnected.networkPathChanged(satisfied: true)   // 같은 상태의 경로 갱신(인터페이스 변화 등): 아무것도 하지 않는다
        XCTAssertEqual(app.keepConnected.socket.map(ObjectIdentifier.init), connecting)
        await app.keepConnected.networkPathChanged(satisfied: false)
        await app.keepConnected.networkPathChanged(satisfied: true)   // 끊겼다 돌아왔다
        XCTAssertNotEqual(app.keepConnected.socket.map(ObjectIdentifier.init), connecting)   // 기다림을 건너뛰고 새로 붙는다
        app.lifecycle.onBackground()
    }

    struct StubToken: PushTokens { var token: String? }
    final class ChangingToken: PushTokens { var token: String?; init(_ t: String) { token = t } }

    func test_보내는_사이_토큰이_바뀌면_지금_토큰을_다시_보낸다() async {
        let source = ChangingToken("fcm-old")
        app.registerPush.source = source
        relay.patchHook = { if source.token == "fcm-old" { source.token = "fcm-new" } }   // 옛 PATCH가 가는 사이 토큰이 바뀌었다
        app.registerPush.onToken(app.registerPush.token)
        await app.tasks.settle()
        XCTAssertEqual(relay.patches.map(\.pushToken), [.set("fcm-old"), .set("fcm-new")])   // 마지막에 닿는 값이 지금 토큰이다(5.4)
    }

    func test_푸시_토큰이_생기면_연결된_폰은_바로_중계에_보낸다() async {
        app.registerPush.source = StubToken(token: "fcm-1")
        app.registerPush.onToken(app.registerPush.token)
        await app.tasks.settle()
        XCTAssertEqual(relay.patches.last?.pushToken, .set("fcm-1"))   // 5.4
        make(paired: false)
        app.registerPush.source = StubToken(token: "fcm-2")
        app.registerPush.onToken(app.registerPush.token)   // 연결 전에는 보내지 않는다(연결 요청에 실어 보낸다)
        await app.tasks.settle()
        XCTAssertTrue(relay.patches.isEmpty)
    }

    func test_잘못된_연결_링크는_탭_화면에서도_보이고_연결_화면에_남지_않는다() {
        make(paired: false)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=12")!)   // 탭 화면에서 연 깨진 링크
        XCTAssertEqual(app.status.ui.notice, "올바른 연결 링크가 아니에요")
        XCTAssertNil(app.appViewModel.linkError)   // 나중에 연 연결 화면에 옛 문구가 남지 않는다
        app.appViewModel.connectShown = true
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=12")!)
        XCTAssertEqual(app.appViewModel.linkError, "올바른 연결 링크가 아니에요")
        app.appViewModel.connectShown = false   // 연결 화면을 닫았다 다시 연다
        app.appViewModel.connectShown = true
        XCTAssertNil(app.appViewModel.linkError)
    }

    func test_전송_취소는_망_실패와_가른다() {
        XCTAssertEqual(HTTPRelayClient.transportError(URLError(.cancelled)), .cancelled)
        XCTAssertEqual(HTTPRelayClient.transportError(CancellationError()), .cancelled)
        XCTAssertEqual(HTTPRelayClient.transportError(URLError(.notConnectedToInternet)), .network)
    }

    func test_취소된_요청은_띠도_보낼_함도_바꾸지_않는다() async {
        relay.devicesError = .cancelled   // 보호자 화면을 떠나 목록 조회가 취소됐다
        await app.keepConnected.refreshDevices()
        XCTAssertFalse(app.status.ui.offline)
        app.keepConnected.socketEvent(.unreachable)
        await app.keepConnected.refreshDevices()
        XCTAssertTrue(app.status.ui.offline)      // 취소는 '닿음'도 아니다
        app.keepConnected.socketEvent(.alive)
        store.value.outbox = [OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.sendError = .cancelled      // 새로고침을 띄운 화면이 사라져 그 안의 보내기가 취소됐다
        app.lifecycle.onForeground(); await app.tasks.settle()
        XCTAssertFalse(app.status.ui.offline)
        XCTAssertEqual(app.state.state.outbox.first?.attempts, 0)   // 시도로 세지 않는다
        XCTAssertEqual(app.state.state.outbox.first?.nextTryAt, clock)
        relay.sendError = nil
        try? await Task.sleep(for: .seconds(2))   // 보낼 함이 멈추지 않고 곧 같은 id로 다시 보낸다
        XCTAssertEqual(relay.sent.filter { $0.type == MessageType.feedingStarted }.map(\.id), ["F1"])
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        app.lifecycle.onBackground()
    }

    func test_연결_성공은_토큰을_저장하고_역할을_정한다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .guardian))
        let f = await app.pairAndWait(.invite("7K3QM2XW9D"))
        XCTAssertNil(f)
        XCTAssertEqual(creds.value, Credentials(carId: "c_9", deviceId: "d_9", token: "at1.c_9.d_9.s", role: .guardian))
        XCTAssertEqual(app.state.state.handoffAlerts, true)   // guardian 기본값 참(G12)
    }

    func test_연결_실패_문구_분류() async {
        make(paired: false)
        for (err, want) in [(RelayError.http(status: 404, code: "code_invalid", retryAfter: nil), ConnectFailure.codeInvalid),
                            (.http(status: 429, code: "rate_limited", retryAfter: 10), .rateLimited(10)),
                            (.http(status: 400, code: "bad_code_format", retryAfter: nil), .badCode),
                            (.network, .offline)] {
            relay.pairResult = .failure(err)
            let got = await app.pairAndWait(.pair("042917"))
            XCTAssertEqual(got, want)
            XCTAssertNil(creds.value)
            clock = clock.addingTimeInterval(11)   // 429의 잠금(10초)을 지나 다음 경우로
        }
    }

    func test_링크는_바로_연결하지_않고_확인을_거친다() {
        make(paired: false)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))
        app.appViewModel.pendingCode = nil
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=zz")!)
        XCTAssertNil(app.appViewModel.pendingCode)
        XCTAssertEqual(app.status.ui.notice, "올바른 연결 링크가 아니에요")   // 탭 화면이라 띠로 보인다
    }

    func test_직접_친_코드는_확인_없이_연결_요청() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        let f = await app.pairAndWait(typed: .pair("042917"))
        XCTAssertNil(f)
        XCTAssertNil(app.appViewModel.pendingCode)       // 확인 화면을 거치지 않았다
        XCTAssertTrue(app.state.isPaired)
    }

    func test_직접_친_코드의_오류는_호출한_화면에_돌려준다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 404, code: "code_invalid", retryAfter: nil))
        let f = await app.pairAndWait(typed: .pair("042917"))
        XCTAssertEqual(f, .codeInvalid)
        XCTAssertNil(app.appViewModel.pendingCode)
        XCTAssertFalse(app.state.isPaired)
    }

    func test_링크로_온_연결_코드는_확인_화면_제목이_차_화면() {
        make(paired: false)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertEqual(app.appViewModel.pendingCode?.confirmTitle, "차 화면에 이 코드가 보이나요?")
    }

    func test_링크로_온_초대_코드는_확인_화면_제목이_받은_초대_코드() {
        make(paired: false)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=7k3qm-2xw9d")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .invite("7K3QM2XW9D"))
        XCTAssertEqual(app.appViewModel.pendingCode?.confirmTitle, "받은 초대 코드가 이것과 같나요?")
    }

    func test_이미_연결된_폰에_새_링크가_오면_확인_화면에서_바꿀지_묻는다() {
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))   // 확인 화면이 "지금 연결된 차를 끊고…"를 함께 묻는다(G16)
        XCTAssertTrue(app.state.isPaired)
        XCTAssertFalse(relay.deleted)
    }

    func test_끊고_연결은_확인해야_기존_연결을_지운다() async {
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        app.appViewModel.pendingCode = nil                 // '아니에요': 창만 닫는다
        XCTAssertTrue(app.state.isPaired)
        XCTAssertFalse(relay.deleted)
        XCTAssertNotNil(creds.value)

        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        let f = await app.pairAndWait(.pair("042917"))   // 확인 화면의 버튼 하나로 끊고 연결한다
        XCTAssertNil(f)
        XCTAssertTrue(relay.deleted)
        XCTAssertEqual(creds.value?.carId, "c_9")
        XCTAssertNil(app.appViewModel.pendingCode)
    }

    func test_연결하는_동안_온_새_링크는_화면_코드를_바꾸지_않는다() async {
        // U52: 끊고 연결의 끊기 요청 중과 연결 요청 중 모두 본다.
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        var stage = ""
        var gate: CheckedContinuation<Void, Never>?
        relay.deleteHook = { stage = "delete"; await withCheckedContinuation { gate = $0 } }
        relay.pairHook = { stage = "pair"; await withCheckedContinuation { gate = $0 } }
        let run = Task { await self.app.pairAndWait(.pair("042917")) }
        while stage != "delete" { await Task.yield() }
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=555555")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))
        XCTAssertEqual(app.appViewModel.linkError, CareText.pairBusy)
        gate?.resume()
        while stage != "pair" { await Task.yield() }
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=7k3qm-2xw9d")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))
        gate?.resume()
        let f = await run.value
        XCTAssertNil(f)
        XCTAssertEqual(relay.pairCalls, 1)
        XCTAssertEqual(creds.value?.carId, "c_9")
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=555555")!)   // 끝나면 평소처럼 받는다
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("555555"))
        XCTAssertNil(app.appViewModel.linkError)
    }

    func test_끊기에_실패하면_새_차에_연결하지_않고_지금_연결을_둔다() async {
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        relay.deleteError = .network
        let f = await app.pairAndWait(.pair("042917"))
        XCTAssertEqual(f, .disconnectFailed("인터넷이 필요해요"))
        XCTAssertEqual(relay.pairCalls, 0)
        XCTAssertTrue(app.state.isPaired)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))   // 확인 화면은 남아 이유를 보인다
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=555555")!)   // 실패로 끝났으니 새 링크를 받는다(U52)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("555555"))
    }

    func test_확인_화면이_떠_있는_동안_끊기면_바꿀지_묻지_않고_연결한다() async {
        // U13 ②: 차 쪽에서 끊긴(401) 뒤에는 이전 연결이 없으니 끊기를 거치지 않는다.
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        app.lose.lost(app.state.creds)
        XCTAssertTrue((app.appViewModel.ui.route == .gone))
        XCTAssertFalse(app.state.isPaired)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))   // 확인 화면이 남고, 이제 바꿀지 묻지 않는다
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        let f = await app.pairAndWait(.pair("042917"))
        XCTAssertNil(f)
        XCTAssertFalse(relay.deleted)            // 끊기 요청을 보내지 않았다
        XCTAssertEqual(relay.pairCalls, 1)
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
    }

    func test_연결_끊기는_중계가_성공해야_지운다() async {
        let msg = await app.disconnect.now()
        XCTAssertNil(msg)
        XCTAssertTrue(relay.deleted)
        XCTAssertNil(creds.value)
        XCTAssertFalse(app.state.isPaired)
    }

    func test_직접_끊는_사이_온_4001은_끊김_화면이_아니라_끊기의_결과다() async {
        // 실제 중계는 DELETE /v1/devices/me의 204보다 먼저 소켓을 4001로 닫는다(removeDevice → closeAll). U25 b가 나와야 한다.
        relay.deleteHook = { await MainActor.run { self.app.lose.lost(self.app.state.creds) } }
        let msg = await app.disconnect.now()
        XCTAssertNil(msg)
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
        XCTAssertEqual(app.status.ui.notice, "연결을 끊었어요. 돌봄 기록은 이 휴대폰에 남아 있어요")
        XCTAssertFalse(app.state.isPaired)
        XCTAssertNil(creds.value)
        // 끊기가 끝난 뒤의 401은 다시 강제 해제로 다룬다(이미 끊겼으니 바뀌는 것은 없다).
        app.lose.lost(app.state.creds)
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
    }

    func test_끊기가_실패해도_그사이_온_4001을_잊지_않는다() async {
        // 중계가 4001로 닫은 뒤 DELETE 응답을 잃었다: 토큰은 이미 지워졌으니 다음 요청을 기다리지 않고 GONE(7.1).
        relay.deleteError = .network
        relay.deleteHook = { await MainActor.run { self.app.lose.lost(self.app.state.creds) } }
        let msg = await app.disconnect.now()
        XCTAssertEqual(msg, "인터넷이 필요해요")
        XCTAssertTrue((app.appViewModel.ui.route == .gone))
        XCTAssertFalse(app.state.isPaired)
        XCTAssertNil(creds.value)
        XCTAssertNil(app.state.state.carId)
    }

    func test_끊기가_겹쳐도_그사이_온_4001을_잊지_않는다() async {
        // 설정의 끊기가 도는 사이 링크로 '끊고 연결하기'를 누르면 뒤의 끊기는 앞의 결과를 기다린다(끊기 요청은 한 번).
        var deletes = 0
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        relay.deleteHook = {
            deletes += 1
            if deletes == 1 { entered = true; await withCheckedContinuation { gate = $0 } }
        }
        relay.deleteError = .network
        let first = Task { await self.app.disconnect.now() }
        while !entered { await Task.yield() }
        app.lose.lost(app.state.creds)   // 중계가 4001로 닫았다
        let second = Task { await self.app.pairAndWait(.pair("042917")) }
        for _ in 0..<20 { await Task.yield() }
        gate?.resume()
        let msg = await first.value
        let f = await second.value
        XCTAssertEqual(msg, "인터넷이 필요해요")
        XCTAssertEqual(f, .disconnectFailed("인터넷이 필요해요"))
        XCTAssertEqual(deletes, 1)
        XCTAssertEqual(relay.pairCalls, 0)
        XCTAssertTrue((app.appViewModel.ui.route == .gone))
        XCTAssertNil(creds.value)
    }

    func test_끊기가_실패하고_그사이_401이_없었으면_연결을_둔다() async {
        relay.deleteError = .network
        let msg = await app.disconnect.now()
        XCTAssertEqual(msg, "인터넷이 필요해요")
        XCTAssertTrue(app.state.isPaired)
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
    }

    func test_401이면_토큰과_기록을_지우고_끊김_안내() async {
        relay.inboxError = .http(status: 401, code: "unauthorized", retryAfter: nil)
        await app.keepConnected.refresh()
        XCTAssertNil(creds.value)
        XCTAssertTrue((app.appViewModel.ui.route == .gone))
    }

    func test_저장이_거부된_실행의_401_초기화는_실패하고_녹음_파일을_지우지_않는다() async throws {
        let file = SongFiles.url(for: "refused-test")
        try Data("m4a".utf8).write(to: file)
        defer { SongFiles.remove("refused-test") }
        store.restoreFailed = true   // 복원에 실패해 원본을 보존하지 못한 실행
        store.writesRefused = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path), "init 직후")
        relay.inboxError = .http(status: 401, code: "unauthorized", retryAfter: nil)
        await app.keepConnected.refresh()
        XCTAssertTrue((app.appViewModel.ui.route == .gone))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    }

    // MARK: 수유

    func test_수유_시작은_누른_시각_그대로_보내고_반영되면_표시한다() async throws {
        app.recordCare.start(.feed)
        let record = try XCTUnwrap(app.state.state.feedingLog.first)
        XCTAssertEqual(record.status, .sending)
        XCTAssertEqual(record.at, clock)
        await app.sendOutbox.run()
        XCTAssertEqual(relay.sent.map(\.type), ["feeding_started"])
        XCTAssertEqual(relay.sent.first?.id, record.id)   // 같은 id로 다시 보내므로 두 번 가도 한 번 처리된다
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .sent)

        await app.syncInbox.store(app.state.creds, [feedingState(5, last: Rfc3339.string(clock))], live: true)
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .reflected)
    }

    func test_중계가_고친_수유_시각을_보여_준다() async throws {
        app.recordCare.start(.feed)
        let fixed = clock.addingTimeInterval(-30)
        relay.sendBody = .object(["at": .string(Rfc3339.string(fixed))])
        await app.sendOutbox.run()
        XCTAssertEqual(app.state.state.feedingLog.first?.fixedAt, fixed)
        XCTAssertEqual(app.today().ui.lastFeedingStart, fixed)   // 화면의 '마지막 수유'도 고친 시각이다
        XCTAssertEqual(app.care.care.events.first?.startAt, CareTime.ms(clock))   // 기록의 누른 시각은 고치지 않는다(G33)
    }

    func test_오프라인에서_누른_것은_보낼_함에_남고_기다림_뒤_다시_보낸다() async {
        relay.sendError = .network
        app.recordCare.start(.feed)
        await app.sendOutbox.run()
        XCTAssertEqual(app.state.state.outbox.count, 1)
        XCTAssertEqual(app.state.state.outbox[0].attempts, 1)
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .sending)
        XCTAssertTrue(app.status.ui.offline)
        // 1초 뒤 연결이 돌아오면 같은 id로 보낸다.
        relay.sendError = nil
        clock = clock.addingTimeInterval(2)
        await app.sendOutbox.run()
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(relay.sent.count, 1)
    }

    func test_400은_버리고_알린다() async {
        relay.sendError = .http(status: 400, code: "bad_request", retryAfter: nil)
        app.recordCare.start(.feed)
        await app.sendOutbox.run()
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertNotNil(app.status.ui.notice)
    }

    func test_feeding_state는_큰_seq가_이긴다() async {
        await app.syncInbox.store(app.state.creds, [feedingState(10, last: "2026-11-05T08:00:00Z")], live: true)
        await app.syncInbox.store(app.state.creds, [feedingState(5, last: "2026-11-05T06:00:00Z")], live: true)
        XCTAssertEqual(app.state.state.feeding.lastStart, Rfc3339.parse("2026-11-05T08:00:00Z"))
        XCTAssertEqual(app.state.state.feeding.seq, 10)
    }

    // MARK: 받기

    func test_같은_메시지는_한_번만_처리하고_ack만_한다() async {
        let e = feedingState(7, last: "2026-11-05T08:00:00Z")
        await app.syncInbox.store(app.state.creds, [e], live: true)
        await app.syncInbox.store(app.state.creds, [feedingState(7, last: "2026-11-05T03:00:00Z").with(id: e.id)], live: true)
        XCTAssertEqual(app.state.state.feeding.lastStart, Rfc3339.parse("2026-11-05T08:00:00Z"))
        XCTAssertEqual(relay.acked, [e.id, e.id])
    }

    func test_모르는_type과_판도_ack해서_받은_함이_막히지_않게_한다() async {
        var unknown = env("future_type", seq: 3, body: Empty())
        var v2 = env(MessageType.feedingState, seq: 4, body: FeedingStateBody(lastFeedingStart: nil, windowStartExpected: nil))
        v2.v = 2
        unknown.body = .object([:])
        await app.syncInbox.store(app.state.creds, [v2, unknown], live: false)
        XCTAssertEqual(relay.acked, [unknown.id, v2.id])
        XCTAssertEqual(app.state.state.feeding.seq, 0)
    }

    func test_받은_함은_seq_순서로_처리한다() async {
        let late = feedingState(9, last: "2026-11-05T09:00:00Z")
        let early = feedingState(2, last: "2026-11-05T02:00:00Z")
        await app.syncInbox.store(app.state.creds, [late, early], live: false)
        XCTAssertEqual(app.state.state.feeding.seq, 9)
        XCTAssertEqual(relay.acked, [early.id, late.id])
    }

    func test_차가_보낸_메시지를_받은_시각을_저장한다() async {
        await app.syncInbox.store(app.state.creds, [feedingState(1, last: nil)], live: false)
        XCTAssertEqual(app.state.state.lastReceivedAt, clock)
    }

    func test_차가_보낸_메시지를_받으면_차_타일이_바로_연결됨이_된다() async {
        store.value.devices = [DeviceInfo(deviceId: "d_car", role: .car, platform: .android, pairedAt: "2026-11-05T08:00:00Z",
                                          lastSeenAt: "2026-11-04T08:00:00Z", online: false, handoffAlerts: nil)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        await app.syncInbox.store(app.state.creds, [feedingState(1, last: nil)], live: false)   // 받은 함에서 꺼낸 것은 바꾸지 않는다
        XCTAssertEqual(app.car().ui.phone.car?.online, false)
        await app.syncInbox.store(app.state.creds, [feedingState(2, last: nil)], live: true)
        XCTAssertEqual(app.car().ui.phone.car?.online, true)
        XCTAssertEqual(app.car().ui.phone.car?.lastSeenAt.flatMap(Rfc3339.parse), clock)
    }

    func test_기록_지우기는_복구_사본도_지우고_저장이_거부되면_남긴다() throws {
        let dir = FileStateStore.supportDirectory
        let fm = FileManager.default
        let stateCopy = dir.appendingPathComponent("state.json.broken-1")
        let songsCopy = dir.appendingPathComponent("songs.broken-1", isDirectory: true)
        func plant() throws {
            try fm.createDirectory(at: songsCopy, withIntermediateDirectories: true)
            try Data("x".utf8).write(to: songsCopy.appendingPathComponent("x.m4a"))
            try Data("{}".utf8).write(to: stateCopy)
        }
        defer { try? fm.removeItem(at: stateCopy); try? fm.removeItem(at: songsCopy) }
        // 저장이 거부된 실행: 아무것도 지우지 않는다.
        try plant()
        store.writesRefused = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.clearRecords()
        XCTAssertTrue(fm.fileExists(atPath: stateCopy.path))
        XCTAssertTrue(fm.fileExists(atPath: songsCopy.path))
        // 정상 실행: 둘 다 지운다.
        store.writesRefused = false
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.clearRecords()
        XCTAssertFalse(fm.fileExists(atPath: stateCopy.path))
        XCTAssertFalse(fm.fileExists(atPath: songsCopy.path))
    }

    // MARK: 노래

    func test_거절된_곡은_같은_id로_song_removed를_자동으로_보낸다() async throws {
        let id = "01SONG"
        let rejected = SongStatusBody(songId: id, name: "나비야", status: .rejected, reason: .peakLimit, durationS: nil, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: rejected)], live: true)
        guard case .rejected(.peakLimit) = app.state.state.songs.first?.state else { return XCTFail("거절 표시") }
        XCTAssertEqual(app.state.state.outbox.map(\.type), ["song_removed"])
        XCTAssertEqual(try app.state.state.outbox[0].body.decode(SongRefBody.self).songId, id)
        // 같은 거절이 다른 id로 다시 와도 두 번 만들지 않는다.
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 6, body: rejected)], live: true)
        XCTAssertEqual(app.state.state.outbox.count, 1)
    }

    func test_지운_곡은_늦게_온_accepted로_되살리지_않는다() async throws {
        let id = "01SONG"
        let acc = SongStatusBody(songId: id, name: "나비야", status: .accepted, reason: nil, durationS: 30, candidate: true)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: acc)], live: true)
        XCTAssertEqual(app.songs().ui.songs.count, 1)
        app.removeSong(id)
        XCTAssertTrue(app.songs().ui.songs.isEmpty)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 6, body: acc)], live: true)
        XCTAssertTrue(app.songs().ui.songs.isEmpty)
    }

    func test_곡_상태는_곡마다_큰_seq가_이긴다() async {
        let id = "01SONG"
        let acc = SongStatusBody(songId: id, name: "나비야", status: .accepted, reason: nil, durationS: 30, candidate: true)
        let rej = SongStatusBody(songId: id, name: "나비야", status: .rejected, reason: .silent, durationS: nil, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 8, body: acc)], live: true)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 7, body: rej)], live: true)
        XCTAssertEqual(app.state.state.songs.first?.state, .accepted)
        XCTAssertEqual(app.state.state.songs.first?.candidate, true)
    }

    func test_거절된_곡은_줄은_남아도_3곡_자리를_차지하지_않는다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 0, count: 100).write(to: file)
        for i in 0..<2 { try app.recordSong(name: "곡\(i)", recording: file, durationS: 10) }
        let rejected = SongStatusBody(songId: "01REJ", name: "나비야", status: .rejected, reason: .peakLimit, durationS: nil, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: rejected)], live: true)
        XCTAssertEqual(app.songs().ui.songs.count, 3)     // 거절 줄은 거절 문구·'다시 녹음'과 함께 보인다(9장)
        XCTAssertEqual(app.songs().ui.slotsUsed, 2)          // 자동 지움을 보내므로 자리는 빈다(7.4)
        XCTAssertNoThrow(try app.recordSong(name: "셋", recording: file, durationS: 10))
        XCTAssertThrowsError(try app.recordSong(name: "넷", recording: file, durationS: 10)) { XCTAssertEqual($0 as? SongError, .limitReached) }
        app.songs().ui.songs.forEach { app.removeSong($0.id) }
    }

    func test_노래를_올리는_사이_다른_메시지가_429를_받으면_song_added도_기다린다() async throws {
        store.value.needsStateRequest = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 0, count: 100).write(to: file)
        try app.recordSong(name: "시연", recording: file, durationS: 1)
        relay.putHook = { [unowned self] in
            relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 60)
            await app.keepConnected.requestStateIfNeeded()   // 올리는 사이 상태 요청이 메시지 429를 받았다
            relay.sendError = nil
        }
        await app.sendOutbox.run()
        XCTAssertFalse(relay.sent.contains { $0.type == MessageType.songAdded })   // 기기 기다림 중에는 보내지 않는다(1.4)
        XCTAssertEqual(app.state.state.outbox.first?.uploaded, true)   // 파일은 올렸으니 기다림 뒤 POST만 한다
        XCTAssertEqual(app.state.state.outbox.first?.attempts, 0)      // 실패로 세지 않는다
        app.songs().ui.songs.forEach { app.removeSong($0.id) }
    }

    func test_노래는_3곡까지이고_이름은_1에서_30자() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 0, count: 100).write(to: file)
        XCTAssertThrowsError(try app.recordSong(name: "  ", recording: file, durationS: 10)) { XCTAssertEqual($0 as? SongError, .badName) }
        XCTAssertThrowsError(try app.recordSong(name: "가", recording: file, durationS: 181)) { XCTAssertEqual($0 as? SongError, .tooLong) }
        for i in 0..<3 { try app.recordSong(name: "곡\(i)", recording: file, durationS: 10) }
        XCTAssertThrowsError(try app.recordSong(name: "넷", recording: file, durationS: 10)) { XCTAssertEqual($0 as? SongError, .limitReached) }
        app.songs().ui.songs.forEach { app.removeSong($0.id) }
    }

    func test_노래는_파일을_먼저_올리고_song_added를_보낸다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(app.state.state.songs.first?.id)
        XCTAssertEqual(app.state.state.songs.first?.state, .uploading)
        await app.sendOutbox.run()
        XCTAssertEqual(relay.uploaded, [id])
        XCTAssertEqual(relay.sent.map(\.type), ["song_added"])
        XCTAssertEqual(app.state.state.songs.first?.state, .waiting)
        app.removeSong(id)
    }

    // MARK: 리포트·인계 알림

    private func report(_ session: String, started: String = "2026-11-05T08:00:00+09:00") -> ReportSummaryBody {
        ReportSummaryBody(sessionId: session, startedAt: started, endedAt: "2026-11-05T08:40:00+09:00", simulated: false,
                          outputRoute: .rearZone, truncated: nil, timeline: [], preCryContext: [],
                          steering: Steering(available: false, baselinePerMin: nil, duringCryPerMin: nil, afterResponsePerMin: nil,
                                             duringCryZ: nil, unavailableReason: .noComparableWindow),
                          carActions: [], controls: [], methodStats: nil)
    }

    func test_리포트는_session_id마다_큰_seq가_이긴다() async {
        var r2 = report("S1"); r2.simulated = true
        await app.syncInbox.store(app.state.creds, [env(MessageType.reportSummary, seq: 10, body: report("S1"))], live: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.reportSummary, seq: 8, body: r2)], live: false)
        XCTAssertEqual(app.state.state.reports.count, 1)
        XCTAssertFalse(app.state.state.reports[0].body.simulated)
        await app.syncInbox.store(app.state.creds, [env(MessageType.reportSummary, seq: 11, body: r2)], live: false)
        XCTAssertEqual(app.state.state.reports.count, 1)
        XCTAssertTrue(app.state.state.reports[0].body.simulated)
    }

    /// 화면은 다시 정렬하지 않고 저장 순서를 쓴다: 받을 때(바꿔 넣을 때도) 시작 시각 늦은 순으로 둔다.
    func test_리포트는_받은_순서와_상관없이_시작_시각이_늦은_것이_위다() async {
        let c = app.state.creds
        await app.syncInbox.store(c, [env(MessageType.reportSummary, seq: 1, body: report("S2", started: "2026-11-05T10:00:00+09:00"))], live: false)
        await app.syncInbox.store(c, [env(MessageType.reportSummary, seq: 2, body: report("S1", started: "2026-11-05T08:00:00+09:00"))], live: false)
        await app.syncInbox.store(c, [env(MessageType.reportSummary, seq: 3, body: report("S3", started: "2026-11-05T09:00:00+09:00"))], live: false)
        // 가장 늦은 S2를 바꿔 넣는다(정렬하지 않으면 맨 뒤로 간다)
        await app.syncInbox.store(c, [env(MessageType.reportSummary, seq: 4, body: report("S2", started: "2026-11-05T10:00:00+09:00"))], live: false)
        XCTAssertEqual(app.reports().reports.map(\.body.sessionId), ["S2", "S3", "S1"])
        XCTAssertEqual(app.car().ui.reports.map(\.body.sessionId), ["S2", "S3", "S1"])
    }

    func test_알림_열기는_본문이_나중에_저장돼도_이어서_연다() async {
        make(role: .guardian)
        await app.appViewModel.onAlert("M9")                  // 본문이 아직 없다
        XCTAssertNil(app.appViewModel.ui.openAlert)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body, id: "M9")], live: true)
        XCTAssertEqual(app.appViewModel.ui.openAlert, "M9")
        XCTAssertNil(app.appViewModel.pendingOpenAlert)
    }

    func test_연결이_지워지거나_기록을_지우면_열린_알림_상세도_닫는다() async {
        make(role: .guardian)
        app.appViewModel.open("M9")
        app.lose.lost(app.state.creds)                   // 401·4001 → 끊김. 다시 연결해도 지운 알림의 상세가 남지 않는다
        XCTAssertNil(app.appViewModel.ui.openAlert)
        make(role: .guardian)
        app.appViewModel.open("M9")
        await app.appViewModel.onAlert("M8")                  // 본문이 없어 열기를 기다린다
        app.clearRecords()
        XCTAssertNil(app.appViewModel.ui.openAlert)
        XCTAssertNil(app.appViewModel.pendingOpenAlert)
    }

    func test_본문을_못_받은_채_뒤로_가면_알림_열기는_버린다() async {
        make(role: .guardian)
        await app.appViewModel.onAlert("M9")                  // 본문이 아직 없다
        app.lifecycle.onBackground()
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body, id: "M9")], live: false)
        XCTAssertNil(app.appViewModel.ui.openAlert)              // 나중에 받아도 다른 화면 위에 저절로 뜨지 않는다
        XCTAssertEqual(app.state.state.alerts.map(\.id), ["M9"])   // 알림은 목록에 남는다
    }

    func test_다른_알림을_직접_열면_대기하던_알림_열기는_버린다() async {
        make(role: .guardian)
        await app.appViewModel.onAlert("M9")
        app.appViewModel.userOpenedAlert("M8")                  // 사용자가 목록에서 다른 알림을 열었다
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body, id: "M9")], live: true)
        XCTAssertNil(app.appViewModel.ui.openAlert)
    }

    func test_인계_알림은_본문을_저장하고_배너는_한_번만() async {
        make(role: .guardian)
        let body = HandoffAlertBody(text: "아기가 계속 울어요. 7분 뒤 도착해요.", kind: .arrivalNotice, restName: nil,
                                    minutesToArrival: 7, occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        let live = env(MessageType.handoffAlert, seq: 4, body: body)
        await app.syncInbox.store(app.state.creds, [live], live: true)
        XCTAssertEqual(app.appViewModel.ui.banner, live.id)
        XCTAssertEqual(app.state.state.alert(live.id)?.body.text, body.text)
        app.appViewModel.dismissBanner()
        app.syncInbox.onPush(live.id)            // 푸시 willPresent가 같은 id로 불러도
        XCTAssertNil(app.appViewModel.ui.banner)   // 다시 띄우지 않는다

        // 앱을 열며 받은 함에서 꺼낸 것은 배너 없이 목록에만 넣는다.
        let backlog = env(MessageType.handoffAlert, seq: 5, body: body)
        await app.syncInbox.store(app.state.creds, [backlog], live: false)
        XCTAssertNil(app.appViewModel.ui.banner)
        XCTAssertNotNil(app.state.state.alert(backlog.id))
    }

    func test_푸시가_먼저_와서_띄운_뒤에도_본문은_저장한다() async {
        make(role: .guardian)
        app.syncInbox.onPush("M1")                       // 푸시가 먼저: 표시만
        XCTAssertNil(app.state.state.processed["M1"])   // 본문 저장 기록에는 넣지 않는다(5.6)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        app.appViewModel.dismissBanner()
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body, id: "M1")], live: true)
        XCTAssertNil(app.appViewModel.ui.banner)
        XCTAssertNotNil(app.state.state.alert("M1"))
        XCTAssertNotNil(app.state.state.processed["M1"])
    }

    // MARK: 상태 요청(G39)

    func test_24시간_넘게_못_받았으면_상태_요청을_한_번_보낸다() async {
        store.value.lastReceivedAt = clock.addingTimeInterval(-25 * 3600)
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
        XCTAssertFalse(app.state.state.needsStateRequest)
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.sent.count, 1)
    }

    func test_연결_뒤_차_메시지를_못_받아도_24시간이_지나면_상태_요청을_보낸다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "t", role: .owner))
        _ = await app.pairAndWait(.pair("042917"))
        await app.tasks.settle()
        relay.sent = []
        clock = clock.addingTimeInterval(25 * 3600)
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
    }

    func test_상태_요청은_10분_안에_다시_보내지_않고_201로만_표시를_끈다() async {
        app.clearRecords()   // 표시를 켜고 바로 한 번 보낸다(7.4)
        await app.tasks.settle()
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
        XCTAssertFalse(app.state.state.needsStateRequest)
        app.clearRecords()   // 1분도 안 지났으니 표시만 켜고 보내지 않는다
        await app.tasks.settle()
        XCTAssertEqual(relay.sent.count, 1)
        XCTAssertTrue(app.state.state.needsStateRequest)
        clock = clock.addingTimeInterval(11 * 60)
        relay.sendError = .network          // 오프라인이면 표시를 그대로 둔다
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertTrue(app.state.state.needsStateRequest)
        XCTAssertNotNil(app.state.state.lastStateRequestAt)   // 실패해도 시각은 보내기 전에 남는다
        relay.sendError = nil
        clock = clock.addingTimeInterval(11 * 60)
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertFalse(app.state.state.needsStateRequest)
        XCTAssertEqual(relay.sent.count, 2)
    }

    func test_상태_요청이_네트워크_오류로_실패해도_10분_안에는_다시_보내지_않는다() async {
        app.clearRecords()
        relay.sendError = .network
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertNotNil(app.state.state.lastStateRequestAt)
        XCTAssertTrue(app.state.state.needsStateRequest)
        relay.sendError = nil
        clock = clock.addingTimeInterval(5 * 60)
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertTrue(relay.sent.isEmpty)
    }

    func test_연결이_진행_중이면_다른_연결_요청은_busy이고_자격은_처음_것_하나다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_A", deviceId: "d_A", deviceToken: "at1.c_A.d_A.x", role: .owner))
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        relay.pairHook = { [unowned self] in if self.relay.pairCalls == 1 { entered = true; await withCheckedContinuation { gate = $0 } } }
        let a = Task { await self.app.pairAndWait(.pair("042917")) }
        while !entered { await Task.yield() }
        XCTAssertEqual(relay.pairCalls, 1)
        let b = await app.pairAndWait(.pair("111111"))
        XCTAssertEqual(b, .busy)
        XCTAssertEqual(relay.pairCalls, 1)
        gate?.resume()
        let ra = await a.value
        XCTAssertNil(ra)
        XCTAssertEqual(app.state.state.carId, "c_A")
        XCTAssertEqual(creds.value?.carId, "c_A")
    }

    func test_guardian은_상태_요청을_보내지_않는다() async {
        make(role: .guardian)
        app.clearRecords()
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertTrue(relay.sent.isEmpty)
    }

    // MARK: 검토 반영

    func test_저장에_실패하면_ack하지_않고_상태도_바꾸지_않는다() async {
        store.failing = true
        let e = feedingState(5, last: "2026-11-05T08:00:00Z")
        await app.syncInbox.store(app.state.creds, [e], live: true)
        XCTAssertTrue(relay.acked.isEmpty)
        XCTAssertNil(app.state.state.processed[e.id])
        XCTAssertEqual(app.state.state.feeding.seq, 0)
        store.failing = false
        await app.syncInbox.store(app.state.creds, [e], live: true)   // 중계가 다시 주면 그때 처리하고 ack한다
        XCTAssertEqual(relay.acked, [e.id])
        XCTAssertEqual(app.state.state.feeding.seq, 5)
    }

    func test_수유_시작은_기록과_보낼_함을_한_번에_저장한다() {
        store.failing = true
        app.recordCare.start(.feed)
        XCTAssertTrue(app.state.state.feedingLog.isEmpty)
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        store.failing = false
        app.recordCare.start(.feed)
        XCTAssertEqual(store.value.feedingLog.count, 1)
        XCTAssertEqual(store.value.outbox.map(\.id), store.value.feedingLog.map(\.id))
    }

    func test_곡_삭제는_저장이_성공한_뒤에만_파일을_지운다() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(app.state.state.songs.first?.id)
        XCTAssertEqual(store.value.outbox.count, 1)   // 곡과 song_added가 같이 저장됐다
        store.failing = true
        app.removeSong(id)
        XCTAssertTrue(FileManager.default.fileExists(atPath: SongFiles.url(for: id).path))
        XCTAssertEqual(app.state.state.songs.first?.state, .uploading)
        store.failing = false
        app.removeSong(id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: SongFiles.url(for: id).path))
        XCTAssertEqual(store.value.outbox.map(\.type), ["song_removed"])   // 아직 못 올린 올리기는 빼고 지움은 늘 보낸다(안드로이드 removeSong과 같다)
        XCTAssertNotNil(store.value.songs.first?.removedAt)
    }

    func test_삭제_표시는_삭제한_시각부터_7일_뒤에_지운다() {
        let old = clock.addingTimeInterval(-30 * 86400)
        store.value.songs = [SongRow(id: "a", name: "a", durationS: 1, state: .removed, addedAt: old, removedAt: clock.addingTimeInterval(-86400)),
                             SongRow(id: "b", name: "b", durationS: 1, state: .removed, addedAt: old, removedAt: old)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertEqual(app.state.state.songs.map(\.id), ["a"])   // 추가는 오래전이어도 어제 지웠으면 남는다
    }

    func test_연결_직후_앱이_앞에_있으면_받기를_시작한다() async {
        make(paired: false)
        app.lifecycle.onForeground()   // 연결 전에는 아무것도 하지 않는다
        await app.tasks.settle()
        XCTAssertEqual(relay.inboxCalls, 0)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "t", role: .owner))
        _ = await app.pairAndWait(.pair("042917"))
        await app.tasks.settle()
        XCTAssertEqual(relay.inboxCalls, 1)
    }

    func test_다른_차의_메시지는_적용도_ack도_하지_않는다() async {
        var e = feedingState(5, last: "2026-11-05T08:00:00Z")
        e.car = "c_OTHER"
        await app.syncInbox.store(app.state.creds, [e], live: true)
        XCTAssertEqual(app.state.state.feeding.seq, 0)
        XCTAssertTrue(relay.acked.isEmpty)
    }

    func test_연결이_바뀐_뒤_도착한_이전_응답은_버린다() async {
        let old = feedingState(9, last: "2026-11-05T08:00:00Z")
        relay.inboxPages = [InboxResponse(messages: [old], more: false)]
        relay.inboxHook = { [unowned self] in
            // 응답을 기다리는 사이 401로 끊기고 다른 차에 새로 연결된다.
            self.app.lose.lost(self.app.state.creds)
            self.relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
            _ = await self.app.pairAndWait(.pair("042917"))
        }
        await app.keepConnected.refresh()
        XCTAssertEqual(app.state.state.carId, "c_2")
        XCTAssertEqual(app.state.state.feeding.seq, 0)
        XCTAssertTrue(relay.acked.isEmpty)
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
    }

    func test_필수_nullable_키가_빠진_body는_읽지_못한다() async {
        XCTAssertThrowsError(try JSONValue.object([:]).decode(FeedingStateBody.self))
        XCTAssertNoThrow(try JSONValue.object(["last_feeding_start": .null, "window_start_expected": .null]).decode(FeedingStateBody.self))
        await app.syncInbox.store(app.state.creds, [feedingState(3, last: "2026-11-05T08:00:00Z")], live: true)
        var empty = feedingState(4, last: nil)
        empty.body = .object([:])
        await app.syncInbox.store(app.state.creds, [empty], live: true)
        XCTAssertEqual(app.state.state.feeding.seq, 3)   // 빈 body가 수유 값을 지우지 못한다
        XCTAssertNotNil(app.state.state.feeding.lastStart)
    }

    func test_오래된_인계_알림은_실시간으로_와도_배너를_띄우지_않는다() async {
        make(role: .guardian)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock.addingTimeInterval(-3 * 3600)), sessionId: "S", episodeId: "E")
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body)], live: true)
        XCTAssertNil(app.appViewModel.ui.banner)
        XCTAssertEqual(app.state.state.alerts.count, 1)
    }

    func test_상태_요청을_동시에_불러도_한_번만_보낸다() async {
        store.value.needsStateRequest = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        // 첫 요청의 응답 시점을 continuation으로 정한다.
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        relay.sendHook = { entered = true; await withCheckedContinuation { gate = $0 } }
        let first = Task { await self.app.keepConnected.requestStateIfNeeded() }
        while !entered { await Task.yield() }                 // 첫 요청이 응답을 기다리는 중
        await app.keepConnected.requestStateIfNeeded()                 // 두 번째는 '요청 중'이라 보내지 않는다
        XCTAssertTrue(relay.sent.isEmpty)
        gate?.resume()
        await first.value
        XCTAssertEqual(relay.sent.filter { $0.type == "state_request" }.count, 1)
    }

    func test_이미_갱신_중이면_다시_활성화해도_버퍼링을_건드리지_않는다() async {
        // 진행 중인 갱신의 응답을 continuation으로 붙잡아 두고 다시 활성화한다.
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        relay.inboxHook = { entered = true; await withCheckedContinuation { gate = $0 } }
        app.lifecycle.onForeground()
        while !entered { await Task.yield() }
        app.lifecycle.onForeground()                 // 이미 갱신 중: 새 갱신을 시작하지 않는다
        gate?.resume()
        relay.inboxHook = nil
        await app.tasks.settle()
        XCTAssertEqual(relay.inboxCalls, 1)
    }

    func test_연결_429는_Retry_After를_넘긴다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        let f = await app.pairAndWait(.pair("042917"))
        XCTAssertEqual(f, .rateLimited(30))
        XCTAssertTrue(f!.message.contains("30초"))
    }

    func test_연결_429_뒤_잠금_중에는_직접_친_코드도_요청하지_않는다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        _ = await app.pairAndWait(typed: .pair("042917"))
        XCTAssertTrue(app.connect().ui.isLocked)
        XCTAssertEqual(relay.pairCalls, 1)
        let f = await app.pairAndWait(typed: .pair("111111"))     // 코드를 고쳐도(자동 제출) 잠금은 풀리지 않는다
        XCTAssertEqual(f, .rateLimited(30))
        XCTAssertEqual(relay.pairCalls, 1)                      // 두 번째는 중계까지 가지 않았다
        let g = await app.pairAndWait(.pair("042917"))               // 확인 화면 길도 같은 잠금을 쓴다
        XCTAssertEqual(g, .rateLimited(30))
        XCTAssertEqual(relay.pairCalls, 1)
    }

    func test_잠금은_RetryAfter가_지나면_풀린다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        _ = await app.pairAndWait(typed: .pair("042917"))
        clock = clock.addingTimeInterval(31)
        XCTAssertFalse(app.connect().ui.isLocked)
        relay.pairResult = .failure(.http(status: 404, code: "code_invalid", retryAfter: nil))
        let f = await app.pairAndWait(typed: .pair("042917"))
        XCTAssertEqual(f, .codeInvalid)
        XCTAssertEqual(relay.pairCalls, 2)
    }

    func test_잠금_길이는_RetryAfter_없으면_10초_1초_미만은_1초() {
        XCTAssertEqual(RetryPolicy.pairLock(retryAfter:nil), 10)
        XCTAssertEqual(RetryPolicy.pairLock(retryAfter:0), 1)
        XCTAssertEqual(RetryPolicy.pairLock(retryAfter:30), 30)
    }

    func test_직접_친_코드의_요청_중에_QR_링크가_와도_확인_화면으로_옮기지_않는다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        relay.pairHook = { [unowned self] in self.app.appViewModel.propose(.pair("111111")) }   // 응답이 늦는 사이 QR을 읽었다
        let f = await app.pairAndWait(typed: .pair("042917"))
        XCTAssertNil(f)
        XCTAssertNil(app.appViewModel.pendingCode)
        XCTAssertNotNil(app.appViewModel.linkError)
        XCTAssertTrue(app.state.isPaired)
    }

    // MARK: 2회차 검토 반영

    func test_초기화_저장이_실패하면_새_자격_증명을_켜지_않는다() async {
        make(paired: false)
        store.value.outbox = [OutboxItem(id: "old", type: "feeding_started", body: .object([:]), ts: "t", createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
        store.failing = true
        let f = await app.pairAndWait(.pair("042917"))
        XCTAssertEqual(f, .notSaved(removed: true))
        XCTAssertEqual(relay.deletedTokens, ["t2"])   // 중계의 그 연결은 받은 토큰으로 지운다
        XCTAssertNil(creds.value)
        XCTAssertFalse(app.state.isPaired)
        store.failing = false
        let retry = await app.pairAndWait(.pair("042917"))
        XCTAssertNil(retry)
        XCTAssertTrue(app.state.state.outbox.isEmpty)   // 이전 차의 보낼 함은 새 차로 가지 않는다
    }

    func test_실시간으로_거절된_곡의_자동_삭제는_바로_전송된다() async throws {
        app.sendOutbox.autoFlush = true
        let rejected = SongStatusBody(songId: "01SONG", name: "나비야", status: .rejected, reason: .peakLimit, durationS: nil, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: rejected)], live: true)
        await app.tasks.settle()
        XCTAssertEqual(relay.sent.map(\.type), ["song_removed"])
        XCTAssertTrue(app.state.state.outbox.isEmpty)
    }

    func test_보낼_함_갱신_저장이_실패하면_같은_요청을_되풀이하지_않는다() async {
        app.recordCare.start(.feed)
        relay.sendHook = { [unowned self] in self.store.failing = true }   // 전송 뒤 결과 저장만 실패한다
        await app.sendOutbox.run()
        relay.sendHook = nil
        XCTAssertEqual(relay.sent.count, 1)          // 한 번 보내고 멈춘다
        XCTAssertEqual(app.state.state.outbox.count, 1)
        store.failing = false
        clock = clock.addingTimeInterval(6)
        await app.sendOutbox.run()                    // 저장을 다시 시도해 이어 간다(같은 id라 한 번만 처리된다)
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(Set(relay.sent.map(\.id)).count, 1)
    }

    func test_앞_항목을_보내는_사이_만료된_항목은_보내지_않고_만료_처리한다() async {
        app.recordCare.start(.feed)
        clock = clock.addingTimeInterval(10)
        app.recordCare.start(.feed)
        let second = app.state.state.outbox[1]
        XCTAssertEqual(app.state.state.outbox.count, 2)   // 둘째가 아직 보낼 함에 있다
        relay.sendHook = { [unowned self] in self.clock = second.createdAt.addingTimeInterval(OutboxPolicy.maxAge + 1) }
        await app.sendOutbox.run()
        relay.sendHook = nil
        XCTAssertEqual(relay.sent.count, 1)           // 첫 항목만 보냈다
        XCTAssertFalse(relay.sent.contains { $0.id == second.id })
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(app.state.state.feedingLog.first { $0.id == second.id }?.status, .expired)
    }

    func test_시작할_때_지움_상태이거나_목록에_없는_곡_파일을_정리한다() throws {
        let keep = ULID.generate(), gone = ULID.generate(), orphan = ULID.generate()
        for id in [keep, gone, orphan] { try Data(repeating: 1, count: 10).write(to: SongFiles.url(for: id)) }
        store.value.songs = [SongRow(id: keep, name: "k", durationS: 1, state: .accepted, addedAt: clock),
                             SongRow(id: gone, name: "g", durationS: 1, state: .removed, addedAt: clock, removedAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertTrue(FileManager.default.fileExists(atPath: SongFiles.url(for: keep).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: SongFiles.url(for: gone).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: SongFiles.url(for: orphan).path))
        SongFiles.remove(keep)
    }

    // MARK: 3회차 검토 반영

    func test_결과_저장에_실패하면_표시도_보낼_함도_그대로_두었다가_함께_바꾼다() async {
        app.recordCare.start(.feed)
        relay.sendHook = { [unowned self] in self.store.failing = true }   // 전송 뒤 결과 저장만 실패한다
        await app.sendOutbox.run()
        relay.sendHook = nil
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .sending)   // 결과 표시와 지우기는 함께 저장된다
        XCTAssertEqual(app.state.state.outbox.count, 1)
        store.failing = false
        clock = clock.addingTimeInterval(6)
        await app.sendOutbox.run()
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .sent)
        XCTAssertTrue(app.state.state.outbox.isEmpty)
    }

    func test_노래를_올리는_사이_기록을_지우면_노래_추가를_보내지_않는다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        XCTAssertEqual(app.state.state.outbox.count, 1)
        relay.putHook = { [unowned self] in self.app.clearRecords() }   // 업로드를 기다리는 사이 기록을 지운다
        await app.sendOutbox.run()
        relay.putHook = nil
        XCTAssertFalse(relay.sent.contains { $0.type == "song_added" })
        XCTAssertTrue(app.state.state.outbox.filter { $0.songId != nil }.isEmpty)
        XCTAssertTrue(app.state.state.songs.isEmpty)
    }

    func test_올리는_사이_지운_노래의_늦은_429는_남은_보낼_함을_막지_않는다() async throws {
        store.value.maxSentCareAsOf = CareTime.ms(clock) - 86_400_000   // U61 전제: 이 연결에서 요약을 넣은 적이 있어 지운 뒤 빈 요약이 들어온다
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        app.reconcileCareSummary.sendsCareSummary = true
        relay.putError = .http(status: 429, code: "rate_limited", retryAfter: 3600)   // 시간당 업로드 한도
        relay.putHook = { [unowned self] in self.app.clearRecords() }   // 업로드를 기다리는 사이 기록을 지운다(빈 요약이 들어온다)
        await app.sendOutbox.run()
        relay.putHook = nil
        XCTAssertTrue(relay.sent.contains { $0.type == MessageType.careSummary })   // 한 시간 기다리지 않고 지우기 결과를 보낸다
        XCTAssertTrue(app.state.state.outbox.isEmpty)
    }

    func test_PUT_429를_기다리는_노래를_지우면_뒤_메시지를_막지_않는다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(app.state.state.songs.first?.id)
        relay.putError = .http(status: 429, code: "rate_limited", retryAfter: 3600)
        await app.sendOutbox.run()   // 한 시간 기다림이 그 곡에 걸린다
        relay.putError = nil
        app.removeSong(id)
        await app.sendOutbox.run()
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.songRemoved])   // 지운 곡의 기다림이 지움 메시지를 막지 않는다
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertTrue(relay.uploaded.isEmpty)   // 지운 곡은 올리지 않는다
    }

    func test_노래_PUT_429의_기다림을_저장하지_못해도_기기_전체를_멈추지_않는다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(app.state.state.songs.first?.id)
        relay.putError = .http(status: 429, code: "rate_limited", retryAfter: 3600)
        relay.putHook = { [unowned self] in self.store.failing = true }   // 기다림 저장만 실패한다
        await app.sendOutbox.run()
        relay.putHook = nil
        store.failing = false
        relay.putError = nil
        app.removeSong(id)
        await app.sendOutbox.run()
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.songRemoved])   // 메시지는 노래 한도와 따로다(1.4)
    }

    func test_인계_알림_설정은_요청이_끝날_때까지_다음_요청을_받지_않는다() async {
        // 요청 중은 보호자 화면 뷰모델이 든다(S17): 같은 화면(다시 그려진 화면 포함)에서 다시 누른 것은 받지 않는다
        let guardians = app.guardians()
        var second: Bool?
        relay.patchHook = { [unowned self] in
            self.relay.patchHook = nil
            XCTAssertTrue(guardians.ui.busy)
            second = await guardians.setHandoff(true)
        }
        let first = await guardians.setHandoff(false)
        XCTAssertTrue(first)
        XCTAssertNil(guardians.ui.error)
        XCTAssertEqual(second, false)
        XCTAssertEqual(relay.patchCalls, 1)   // 앞 요청이 끝나기 전의 요청은 보내지 않는다
        XCTAssertEqual(app.state.state.handoffAlerts, false)
        XCTAssertFalse(guardians.ui.busy)
    }

    func test_파일이_없어_보내기_전에_버린_노래는_띠를_바꾸지_않는다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(app.state.state.songs.first?.id)
        SongFiles.remove(id)              // 올릴 파일이 사라졌다
        app.keepConnected.socketEvent(.unreachable)   // 띠가 켜져 있다
        await app.sendOutbox.run()
        XCTAssertTrue(app.status.ui.offline)      // HTTP가 없었으니 '닿음'으로 치지 않는다
        XCTAssertTrue(relay.uploaded.isEmpty)
    }

    func test_버려지는_전송도_실패_표시와_지우기를_함께_저장한다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(app.state.state.songs.first?.id)
        relay.sendError = .http(status: 415, code: "unsupported_media", retryAfter: nil)
        relay.sendHook = { [unowned self] in self.store.failing = true }   // 전송 뒤 결과 저장만 실패한다
        await app.sendOutbox.run()
        relay.sendHook = nil
        XCTAssertEqual(app.state.state.songs.first?.state, .uploading)
        XCTAssertEqual(app.state.state.outbox.count, 1)
        store.failing = false
        clock = clock.addingTimeInterval(6)
        await app.sendOutbox.run()
        XCTAssertEqual(app.state.state.songs.first?.state, .failed("녹음 파일을 읽지 못했어요. 다시 녹음해 주세요."))
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        app.removeSong(id)
    }

    func test_상태_요청을_기다리는_사이_기록을_지우면_요청_표시를_남긴다() async {
        app.clearRecords()   // 표시를 켜고 한 번 보낸다
        await app.tasks.settle()
        app.clearRecords()   // 10분 안이라 표시만 켠다
        await app.tasks.settle()
        clock = clock.addingTimeInterval(11 * 60)
        XCTAssertTrue(app.state.state.needsStateRequest)
        relay.sendHook = { [unowned self] in self.app.clearRecords() }   // 응답을 기다리는 사이 기록을 지운다
        await app.keepConnected.requestStateIfNeeded()
        relay.sendHook = nil
        await app.tasks.settle()
        XCTAssertTrue(app.state.state.needsStateRequest)          // 뒤늦은 201이 새 요청 표시를 끄지 못한다
        clock = clock.addingTimeInterval(11 * 60)             // 10분이 지나면 다시 요청한다
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertFalse(app.state.state.needsStateRequest)
    }

    func test_연결_요청의_결과도_오프라인_띠에_반영한다() async {
        make(paired: false)
        relay.pairResult = .failure(.network)
        _ = await app.pairAndWait(.pair("042917"))
        XCTAssertTrue(app.status.ui.offline)
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
        let f = await app.pairAndWait(.pair("042917"))
        XCTAssertNil(f)
        XCTAssertFalse(app.status.ui.offline)                // 끊긴 동안 켜진 띠가 다시 연결한 뒤 남지 않는다
    }

    func test_Keychain_저장에_실패하면_연결로_치지_않는다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
        creds.failing = true
        let f = await app.pairAndWait(.pair("042917"))
        XCTAssertEqual(f, .notSaved(removed: true))
        XCTAssertEqual(relay.deletedTokens, ["t2"])   // 중계에 토큰 없는 폰이 남지 않게 받은 토큰으로 지운다
        XCTAssertFalse(app.state.isPaired)
        XCTAssertNil(app.state.state.carId)
        XCTAssertNil(app.status.ui.notice)   // "연결됐어요"를 띄우지 않는다
        creds.failing = false
        let retry = await app.pairAndWait(.pair("042917"))
        XCTAssertNil(retry)
        XCTAssertTrue(app.state.isPaired)
    }

    func test_저장하지_못한_연결을_지우지도_못하면_차에서_끊게_알린다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
        creds.failing = true
        relay.deleteTokenError = .network            // 되돌리기도 못 했다
        let g = await app.pairAndWait(.pair("042917"))
        XCTAssertEqual(g, .notSaved(removed: false))
        XCTAssertTrue(g?.message.contains("차 화면에서 이 휴대폰 연결을 끊어 주세요") == true)
    }

    // MARK: 4회차 검토 반영

    func test_7일_만료된_전송은_진행_중_표시를_만료로_바꾼다() throws {
        let old = clock.addingTimeInterval(-8 * 86400)
        store.value.feedingLog = [FeedingRecord(id: "F1", at: old)]
        store.value.songs = [SongRow(id: "S1", name: "나비야", durationS: 5, state: .uploading, addedAt: old)]
        store.value.outbox = [
            OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: old, nextTryAt: old),
            OutboxItem(id: "M1", type: MessageType.songAdded, body: .object([:]), ts: "t", songId: "S1", createdAt: old, nextTryAt: old)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .expired)
        guard case .failed = app.state.state.songs.first?.state else { return XCTFail("곡은 만료 표시") }
        XCTAssertEqual(store.value.feedingLog.first?.status, .expired)   // 같은 저장에 들어갔다
    }

    func test_feeding_state가_응답보다_먼저_와도_고친_시각으로_반영_표시한다() async throws {
        app.recordCare.start(.feed)
        let fixed = clock.addingTimeInterval(-30)   // 중계가 미래 시각을 고쳤다고 하자
        // 차의 feeding_state가 HTTP 응답보다 먼저 도착한다.
        await app.syncInbox.store(app.state.creds, [feedingState(7, last: Rfc3339.string(fixed))], live: true)
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .sending)
        relay.sendBody = .object(["at": .string(Rfc3339.string(fixed))])
        await app.sendOutbox.run()
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .reflected)
    }

    // MARK: 5회차 검토 반영

    func test_만료_정리_저장이_실패하면_만료된_항목을_보내지_않는다() async {
        let old = clock.addingTimeInterval(-8 * 86400)
        store.value.outbox = [OutboxItem(id: "F3", type: MessageType.feedingStarted, body: .object(["at": .string("x")]), ts: "t",
                                         createdAt: old, nextTryAt: old)]
        store.failing = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)   // 시작 정리가 저장에 실패해 항목이 남는다
        XCTAssertEqual(app.state.state.outbox.count, 1)
        await app.sendOutbox.run()
        XCTAssertTrue(relay.sent.isEmpty)            // 만료된 항목은 보내지 않는다
        XCTAssertEqual(app.state.state.outbox.count, 1)
        store.failing = false
        clock = clock.addingTimeInterval(6)
        await app.sendOutbox.run()
        XCTAssertTrue(relay.sent.isEmpty)
        XCTAssertTrue(app.state.state.outbox.isEmpty)    // 저장이 되면 지운다
    }

    // MARK: 6회차 검토 반영

    func test_버려진_수유_전송은_실패_표시와_지우기를_함께_저장한다() async {
        app.recordCare.start(.feed)
        relay.sendError = .http(status: 409, code: "id_conflict", retryAfter: nil)
        await app.sendOutbox.run()
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .failed)
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(store.value.feedingLog.first?.status, .failed)   // 저장된 값에도 반영
        XCTAssertNotNil(app.status.ui.notice)
    }

    // MARK: 7회차 검토 반영

    func test_노래_삭제와_선택이_버려지면_실패를_알린다() async {
        let acc = SongStatusBody(songId: "01SONG", name: "나비야", status: .accepted, reason: nil, durationS: 30, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: acc)], live: true)
        relay.sendError = .http(status: 403, code: "forbidden_role", retryAfter: nil)
        app.selectSong("01SONG")
        await app.sendOutbox.run()
        XCTAssertEqual(app.status.ui.notice, "실험에 쓸 노래 선택을 차로 보내지 못했어요.")
        app.notices.dismiss()
        app.removeSong("01SONG")
        await app.sendOutbox.run()
        XCTAssertTrue(app.status.ui.notice?.contains("노래 삭제") == true)
        XCTAssertTrue(app.state.state.outbox.isEmpty)
    }


    // MARK: 11회차 검토 반영

    private func device(_ id: String, _ role: Role = .guardian) -> DeviceInfo {
        DeviceInfo(deviceId: id, role: role, platform: .ios, pairedAt: "2026-11-05T08:00:00Z", lastSeenAt: nil, online: false, handoffAlerts: true)
    }

    func test_오래된_기기_목록이_그_뒤에_받은_끊김을_덮지_못한다() async {
        let paired = PairedBody(deviceId: "d_8", role: .guardian, platform: .ios, pairedAt: "2026-11-05T08:00:00Z", handoffAlerts: true)
        await app.syncInbox.store(app.state.creds, [env(MessageType.paired, seq: 90, body: paired, from: .relay)], live: true)
        XCTAssertEqual(app.state.state.devices.map(\.deviceId), ["d_8"])
        relay.devicesResult = DevicesResponse(snapshotSeq: 100, devices: [device("d_8")])   // 끊기기 전에 읽은 목록
        relay.devicesHook = { [unowned self] in
            // 응답을 기다리는 사이 소켓으로 unpaired(101)를 저장·ack한다.
            await self.app.syncInbox.store(self.app.state.creds, [self.env(MessageType.unpaired, seq: 101, body: UnpairedBody(deviceId: "d_8", role: .guardian, by: .car), from: .relay)], live: true)
        }
        await app.keepConnected.refreshDevices()
        XCTAssertTrue(app.state.state.devices.isEmpty)
    }

    func test_스냅숏_뒤에_붙은_기기는_목록에_없어도_남는다() async {
        relay.devicesResult = DevicesResponse(snapshotSeq: 100, devices: [])
        relay.devicesHook = { [unowned self] in
            let p = PairedBody(deviceId: "d_new", role: .owner, platform: .android, pairedAt: "2026-11-05T09:00:00Z", handoffAlerts: false)
            await self.app.syncInbox.store(self.app.state.creds, [self.env(MessageType.paired, seq: 101, body: p, from: .relay)], live: true)
        }
        await app.keepConnected.refreshDevices()
        XCTAssertEqual(app.state.state.devices.map(\.deviceId), ["d_new"])
        // 스냅숏이 더 새로우면 목록이 이긴다.
        relay.devicesHook = nil
        relay.devicesResult = DevicesResponse(snapshotSeq: 200, devices: [])
        await app.keepConnected.refreshDevices()
        XCTAssertTrue(app.state.state.devices.isEmpty)
    }

    func test_끊긴_뒤_늦게_온_paired는_무시한다() async {
        let p = PairedBody(deviceId: "d_8", role: .guardian, platform: .ios, pairedAt: "2026-11-05T08:00:00Z", handoffAlerts: true)
        await app.syncInbox.store(app.state.creds, [env(MessageType.unpaired, seq: 101, body: UnpairedBody(deviceId: "d_8", role: .guardian, by: .car), from: .relay)], live: true)
        await app.syncInbox.store(app.state.creds, [env(MessageType.paired, seq: 90, body: p, from: .relay)], live: true)
        XCTAssertTrue(app.state.state.devices.isEmpty)
    }


    // MARK: 12회차 검토 반영

    func test_더_새로운_목록을_적용한_뒤_오래된_목록_응답은_버린다() async {
        let paired = PairedBody(deviceId: "d_8", role: .guardian, platform: .ios, pairedAt: "2026-11-05T08:00:00Z", handoffAlerts: true)
        await app.syncInbox.store(app.state.creds, [env(MessageType.paired, seq: 90, body: paired, from: .relay)], live: true)
        relay.devicesResult = DevicesResponse(snapshotSeq: 200, devices: [])          // 끊긴 뒤의 목록
        await app.keepConnected.refreshDevices()
        XCTAssertTrue(app.state.state.devices.isEmpty)
        XCTAssertEqual(store.value.devicesSnapshotSeq, 200)                           // 목록과 함께 저장됐다
        relay.devicesResult = DevicesResponse(snapshotSeq: 100, devices: [device("d_8")])   // 겹친 갱신의 낡은 응답
        await app.keepConnected.refreshDevices()
        XCTAssertTrue(app.state.state.devices.isEmpty)
        XCTAssertEqual(app.state.state.devicesSnapshotSeq, 200)
    }

    func test_스냅숏_이하_seq의_연결_이벤트는_무시한다() async {
        relay.devicesResult = DevicesResponse(snapshotSeq: 200, devices: [])
        await app.keepConnected.refreshDevices()
        let p = PairedBody(deviceId: "d_x", role: .guardian, platform: .ios, pairedAt: "2026-11-05T08:00:00Z", handoffAlerts: true)
        await app.syncInbox.store(app.state.creds, [env(MessageType.paired, seq: 150, body: p, from: .relay)], live: true)
        XCTAssertTrue(app.state.state.devices.isEmpty)
        await app.syncInbox.store(app.state.creds, [env(MessageType.paired, seq: 201, body: p, from: .relay)], live: true)   // 스냅숏보다 새로운 것은 적용한다
        XCTAssertEqual(app.state.state.devices.map(\.deviceId), ["d_x"])
    }


    // MARK: 13회차 검토 반영

    private func tempDir() -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("state-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    func test_보낸_돌봄_요약_표시는_state_json에_저장하고_다시_읽는다() throws {
        var s = PhoneState(introSeen: true)
        s.lastSentCareSummary = .object(["zone": .string("Asia/Seoul"), "today_feed_count": .number(2), "last_diaper_at": .null])
        let dir = tempDir()
        try FileStateStore(directory: dir).save(s)
        XCTAssertEqual(FileStateStore(directory: dir).load().lastSentCareSummary, s.lastSentCareSummary)
    }

    func test_새_필드가_없는_옛_state_json도_기록_그대로_읽는다() throws {
        var s = PhoneState(introSeen: true)
        s.carId = "c_1"
        s.processed = ["m1": clock]
        s.songs = [SongRow(id: "S1", name: "나비야", durationS: 5, state: .accepted, addedAt: clock)]
        s.feedingLog = [FeedingRecord(id: "F1", at: clock)]
        s.outbox = [OutboxItem(id: "F1", type: "feeding_started", body: .object(["at": .string("x")]), ts: "t", createdAt: clock, nextTryAt: clock)]
        let dir = tempDir()
        let file = dir.appendingPathComponent("state.json")
        try FileStateStore(directory: dir).save(s)
        // 옛 형식으로 만든다: 나중에 더한 필드를 모두 뺀다.
        var obj = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        for k in ["devicesSnapshotSeq", "deviceMarks", "needsStateRequest", "devices", "feeding", "shown"] { obj[k] = nil }
        var songs = obj["songs"] as! [[String: Any]]
        for i in songs.indices { for k in ["candidate", "lastSeq", "removedAt", "hasFile"] { songs[i][k] = nil } }
        obj["songs"] = songs
        var log = obj["feedingLog"] as! [[String: Any]]
        for i in log.indices { for k in ["status", "fixedAt", "sentAt"] { log[i][k] = nil } }
        obj["feedingLog"] = log
        var outbox = obj["outbox"] as! [[String: Any]]
        for i in outbox.indices { for k in ["uploaded", "attempts", "songId"] { outbox[i][k] = nil } }
        obj["outbox"] = outbox
        try JSONSerialization.data(withJSONObject: obj).write(to: file)

        let store = FileStateStore(directory: dir)
        XCTAssertEqual(store.load(), s)         // 기록이 그대로다
        XCTAssertFalse(store.restoreFailed)
    }

    func test_깨진_state_json은_원본을_보존하고_녹음_파일을_지우지_않는다() throws {
        let dir = tempDir()
        let broken = Data("{ not json".utf8)
        try broken.write(to: dir.appendingPathComponent("state.json"))
        let songId = ULID.generate()
        try Data(repeating: 1, count: 10).write(to: SongFiles.url(for: songId))
        let store = FileStateStore(directory: dir)
        let m = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertTrue(store.restoreFailed)
        XCTAssertTrue(FileManager.default.fileExists(atPath: SongFiles.url(for: songId).path))   // 정리하지 않았다
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path)
        let side = try XCTUnwrap(names.first { $0.hasPrefix("state.json.broken-") })
        XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent(side)), broken)           // 원본이 옆에 남았다
        XCTAssertNotNil(m)
        SongFiles.remove(songId)
    }

    func test_재요청_표시를_저장하지_못하면_받은_메시지를_적용하지_않는다() async {
        store.value.lastReceivedAt = clock.addingTimeInterval(-25 * 3600)
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        store.failing = true
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.inboxCalls, 0)         // 받은 함을 시작하지 않는다
        let e = feedingState(5, last: "2026-11-05T08:00:00Z")
        await app.syncInbox.store(app.state.creds, [e], live: true)         // 소켓 프레임도 적용·ack하지 않는다
        XCTAssertTrue(relay.acked.isEmpty)
        XCTAssertEqual(app.state.state.feeding.seq, 0)
        XCTAssertEqual(app.state.state.lastReceivedAt, clock.addingTimeInterval(-25 * 3600))   // 재요청 조건이 그대로다
        store.failing = false
        await app.keepConnected.refresh()                       // 저장이 되면 이어 간다
        XCTAssertEqual(relay.inboxCalls, 1)
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
    }

    // MARK: 14회차 검토 반영

    func test_곡_폴더를_보존하지_못하면_저장을_거부한다() throws {
        let dir = tempDir()
        try Data("{ not json".utf8).write(to: dir.appendingPathComponent("state.json"))
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("songs"), withIntermediateDirectories: true)
        let fixed = Date(timeIntervalSince1970: 1_800_000_000)
        // 이동할 자리가 이미 있어 moveItem이 실패한다.
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("songs.broken-1800000000"), withIntermediateDirectories: true)
        let store = FileStateStore(directory: dir, clock: { fixed })
        _ = store.load()
        XCTAssertTrue(store.restoreFailed)
        XCTAssertTrue(store.writesRefused)
        XCTAssertThrowsError(try store.save(PhoneState()))   // 빈 상태 저장을 거부한다
        let raw = try Data(contentsOf: dir.appendingPathComponent("state.json"))
        XCTAssertEqual(raw, Data("{ not json".utf8))          // 원본은 그대로다
    }

    func test_빈_상태로_복구하면_요청_필요_표시를_켜고_기기_목록_뒤에_상태를_요청한다() async {
        store.restoreFailed = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        XCTAssertTrue(app.state.state.needsStateRequest)
        XCTAssertEqual(app.state.state.carId, "c_1")
        relay.devicesError = .network                      // 기기 목록 조회가 실패하면 요청하지 않는다
        await app.keepConnected.refresh()
        XCTAssertTrue(relay.sent.isEmpty)
        relay.devicesError = nil
        await app.keepConnected.refresh()                              // 성공한 뒤에 요청한다
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
        XCTAssertFalse(app.state.state.needsStateRequest)
    }

    func test_소켓이_다시_붙으면_남은_요청_표시로_기기_목록_뒤에_상태를_요청한다() async {
        store.restoreFailed = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.devicesError = .network
        await app.keepConnected.refresh()                              // 조회가 실패해 표시가 남는다
        XCTAssertTrue(app.state.state.needsStateRequest)
        XCTAssertTrue(relay.sent.isEmpty)
        relay.devicesError = nil
        app.keepConnected.socketDidConnect(true)
        for _ in 0..<200 where relay.sent.isEmpty { try? await Task.sleep(nanoseconds: 5_000_000) }
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
    }

    func test_앞에_있는_동안_중계가_끊기면_오프라인_띠를_켜고_다시_붙으면_끈다() {
        app.keepConnected.socketEvent(.unreachable)
        XCTAssertTrue(app.status.ui.offline)
        app.keepConnected.socketEvent(.connected(false))
        XCTAssertTrue(app.status.ui.offline)   // 끊김만으로는 끄지 않는다
        app.keepConnected.socketEvent(.connected(true))
        XCTAssertFalse(app.status.ui.offline)
    }

    func test_받은_함이_5xx로_실패하면_소켓이_켠_오프라인_띠를_그대로_둔다() async {
        app.keepConnected.socketEvent(.unreachable)
        relay.inboxError = .http(status: 503, code: nil, retryAfter: nil)
        relay.devicesError = relay.inboxError   // 기기 목록도 5xx(성공하면 그것이 띠를 끈다)
        await app.keepConnected.refresh()
        XCTAssertGreaterThan(relay.inboxCalls, 0)
        XCTAssertTrue(app.status.ui.offline)
    }

    func test_기기_목록_결과도_오프라인_띠를_끄고_켠다() async {
        app.keepConnected.socketEvent(.unreachable)
        XCTAssertTrue(app.status.ui.offline)
        await app.keepConnected.refreshDevices()
        XCTAssertFalse(app.status.ui.offline)   // 200: 중계에 닿았다
        relay.devicesError = .network
        await app.keepConnected.refreshDevices()
        XCTAssertTrue(app.status.ui.offline)
        app.keepConnected.socketEvent(.connected(true)); await app.tasks.settle()
        XCTAssertFalse(app.status.ui.offline)
        relay.devicesError = .http(status: 503, code: nil, retryAfter: nil)
        await app.keepConnected.refreshDevices()
        XCTAssertTrue(app.status.ui.offline)   // 5xx도 중계 장애다(계약 9장 '중계 불통(네트워크·중계 장애)')
        app.keepConnected.socketEvent(.connected(true)); await app.tasks.settle()
        relay.devicesError = .http(status: 404, code: nil, retryAfter: nil)
        await app.keepConnected.refreshDevices()
        XCTAssertFalse(app.status.ui.offline)   // 4xx는 띠를 바꾸지 않는다
    }

    func test_늦게_끝난_앞_요청의_실패는_나중에_시작한_요청의_성공을_덮지_않는다() async {
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        app.keepConnected.socketEvent(.unreachable)
        relay.devicesError = .network
        relay.devicesHook = { [unowned self] in await app.sendOutbox.run() }   // 목록(A)을 기다리는 사이 보내기(B)를 시작해 성공했다
        await app.keepConnected.refreshDevices()
        XCTAssertEqual(relay.sent.count, 1)
        XCTAssertFalse(app.status.ui.offline)   // A의 늦은 네트워크 실패가 B의 성공을 덮지 않는다
    }

    func test_받은_함을_다_받지_못하면_모아_둔_프레임을_실시간으로_처리하지_않는다() async {
        make(role: .guardian)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        let missed = env(MessageType.handoffAlert, seq: 3, body: body)
        relay.inboxError = .http(status: 503, code: nil, retryAfter: nil)
        app.keepConnected.socketEvent(.connected(true))
        app.keepConnected.socketEvent(.envelope(missed))   // 다시 붙자마자 온 밀린 것
        await app.tasks.settle()
        XCTAssertNil(app.appViewModel.ui.banner)          // 지난 것을 실시간 배너로 띄우지 않는다
        XCTAssertNil(app.state.state.alert(missed.id))       // ack하지 않고 중계에 남겨 다음 받은 함에서 받는다
        XCTAssertFalse(relay.acked.contains(missed.id))
    }

    func test_버린_메시지_안내는_앱을_다시_열어도_닫을_때까지_남는다() async {
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songRemoved,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.sendError = .http(status: 400, code: nil, retryAfter: nil)
        await app.sendOutbox.run()
        let line = "노래 삭제를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요."
        XCTAssertEqual(app.status.ui.notice, line)
        // 띠를 보기 전에 앱이 끝났다
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertEqual(app.status.ui.notice, line)
        app.status.dismissNotice()
        XCTAssertTrue(store.value.unseenDrops.isEmpty)
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertNil(app.status.ui.notice)
    }

    func test_첫_기기_목록을_기다리는_사이_받은_차_봉투로_차를_연결됨으로_둔다() async {
        let car = DeviceInfo(deviceId: "d_car", role: .car, platform: .android, pairedAt: "2026-11-05T08:00:00Z",
                             lastSeenAt: "2026-11-04T08:00:00Z", online: false, handoffAlerts: nil)
        XCTAssertTrue(app.state.state.devices.isEmpty)   // 새 연결이라 캐시에 차 행이 없다
        relay.devicesResult = DevicesResponse(snapshotSeq: 2, devices: [car])
        relay.devicesHook = { [unowned self] in await app.syncInbox.store(app.state.creds, [feedingState(1, last: nil)], live: true) }
        await app.keepConnected.refreshDevices()
        XCTAssertEqual(app.car().ui.phone.car?.online, true)
        XCTAssertEqual(app.car().ui.phone.car?.lastSeenAt.flatMap(Rfc3339.parse), clock)
    }

    func test_갱신_중에_소켓이_다시_붙으면_끝난_뒤_받은_함을_먼저_본다() async {
        let car = DeviceInfo(deviceId: "d_car", role: .car, platform: .android, pairedAt: "2026-11-05T08:00:00Z",
                             lastSeenAt: "2026-11-04T08:00:00Z", online: false, handoffAlerts: nil)
        store.value.devices = [car]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.devicesResult = DevicesResponse(snapshotSeq: 1, devices: [car])
        let missed = feedingState(5, last: nil)
        var hooked = false
        relay.devicesHook = { [unowned self] in   // 받은 함을 마친 갱신이 기기 목록을 기다리는 사이 소켓이 다시 붙어 밀린 것을 보낸다
            guard !hooked else { return }
            hooked = true
            relay.inboxPages = [InboxResponse(messages: [missed], more: false)]
            app.keepConnected.socketEvent(.connected(true))
            app.keepConnected.socketEvent(.envelope(missed))
        }
        await app.keepConnected.refresh()
        await app.tasks.settle()
        XCTAssertNotNil(app.state.state.processed[missed.id])   // 받은 함으로 처리했다
        XCTAssertEqual(app.car().ui.phone.car?.online, false)             // 지난 차 봉투로 '연결됨'이 되지 않는다
    }

    func test_같은_snapshot_seq의_늦은_목록이_나중_목록을_덮지_않는다() async {
        let off = DeviceInfo(deviceId: "d_car", role: .car, platform: .android, pairedAt: "2026-11-05T08:00:00Z",
                             lastSeenAt: "2026-11-04T08:00:00Z", online: false, handoffAlerts: nil)
        var on = off; on.online = true
        var calls = 0
        relay.devicesResult = DevicesResponse(snapshotSeq: 5, devices: [off])
        relay.devicesHook = { [unowned self] in
            calls += 1
            guard calls == 1 else { return }
            relay.devicesResult = DevicesResponse(snapshotSeq: 5, devices: [on])
            await app.keepConnected.refreshDevices()   // 나중에 시작한 목록(차가 다시 붙음)이 먼저 끝난다
            relay.devicesResult = DevicesResponse(snapshotSeq: 5, devices: [off])   // 앞 목록은 그 전에 만들어졌다
        }
        await app.keepConnected.refreshDevices()
        XCTAssertEqual(app.car().ui.phone.car?.online, true)
    }

    func test_실시간_메시지_저장이_실패하면_뒤_메시지를_먼저_적용하지_않는다() async {
        make(role: .guardian)
        let body = { (t: String) in HandoffAlertBody(text: t, kind: .parked, restName: nil, minutesToArrival: nil,
                                                   occurredAt: Rfc3339.string(self.clock), sessionId: "S", episodeId: "E") }
        let a = env(MessageType.handoffAlert, seq: 1, body: body("a")), b = env(MessageType.handoffAlert, seq: 2, body: body("b"))
        store.failNext = 1   // a만 저장 실패
        app.keepConnected.socketEvent(.envelope(a))
        app.keepConnected.socketEvent(.envelope(b))
        await app.tasks.settle()
        XCTAssertNil(app.state.state.alert(a.id))
        XCTAssertNil(app.state.state.alert(b.id))   // a보다 뒤라 받은 함에서 순서대로 다시 받는다(8.3 4번)
        XCTAssertTrue(relay.acked.isEmpty)
    }

    func test_다른_HTTP도_띠에_반영하고_결과_저장_실패와_따로_본다() async {
        store.value.needsStateRequest = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.sendError = .http(status: 503, code: nil, retryAfter: nil)
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertTrue(app.status.ui.offline)   // 상태 요청이 5xx
        // 보내기는 성공했지만 결과 저장만 실패해도 통신은 됐다
        store.value = app.state.state
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        app.keepConnected.socketEvent(.unreachable)
        relay.sendError = nil
        store.failing = true
        await app.sendOutbox.run()
        XCTAssertFalse(app.status.ui.offline)
    }

    func test_뒤로_가면_진행_중_갱신이_다음_요청을_시작하지_않는다() async {
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        var devicesCalls = 0
        relay.devicesHook = { devicesCalls += 1 }
        relay.inboxHook = { [unowned self] in app.lifecycle.onBackground() }   // 받은 함을 기다리는 사이 홈으로 갔다
        await app.keepConnected.refresh()
        XCTAssertEqual(devicesCalls, 0)
        XCTAssertTrue(relay.sent.isEmpty)
    }

    func test_받은_함_429는_Retry_After_뒤에_다시_한다() async {
        app.lifecycle.onForeground(); await app.tasks.settle()
        relay.inboxError = .http(status: 429, code: "rate_limited", retryAfter: 600)
        await app.keepConnected.refresh()
        XCTAssertEqual(app.keepConnected.scheduledRefreshDelay, 600)   // 갱신이 끝난 뒤로 미룬 예약도 Retry-After를 지킨다(1.1)
        app.lifecycle.onBackground()
    }

    func test_받은_함을_기다리는_사이_뒤로_가도_401은_처리한다() async {
        relay.inboxHook = { [unowned self] in app.lifecycle.onBackground() }
        relay.inboxError = .http(status: 401, code: "unauthorized", retryAfter: nil)
        await app.keepConnected.refresh()
        XCTAssertTrue((app.appViewModel.ui.route == .gone))   // 7.1: 어디서든 401 → 연결이 끊겼어요
    }

    func test_기기_목록을_기다리는_사이_뒤로_가면_상태_요청을_시작하지_않는다() async {
        store.value.needsStateRequest = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.devicesHook = { [unowned self] in app.lifecycle.onBackground() }
        await app.keepConnected.refresh()
        XCTAssertTrue(relay.sent.isEmpty)
    }

    func test_앞으로_와서_갱신이_돌기_전에_뒤로_가면_갱신하지_않는다() async {
        app.lifecycle.onForeground()
        app.lifecycle.onBackground()   // 스폰한 갱신이 돌기 전
        await app.tasks.settle()
        XCTAssertEqual(relay.inboxCalls, 0)
    }

    func test_복구_표시를_처음에_저장하지_못해도_다음_갱신에서_상태를_다시_요청한다() async {
        store = MemoryStateStore(PhoneState())   // 상태 파일이 비었다(자격은 owner)
        store.failNext = 1                        // 시작할 때 복구 표시 저장만 실패
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        XCTAssertFalse(app.state.state.needsStateRequest)
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.stateRequest])   // 7.4: 표시를 저장한 뒤 다시 받는다
    }

    func test_보낼_함_429의_기다림을_저장하지_못해도_지킨다() async {
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        var tries = 0
        relay.sendHook = { tries += 1 }
        relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 600)
        store.failNext = 1   // 기다림 저장만 실패
        await app.sendOutbox.run()
        clock = clock.addingTimeInterval(10)
        relay.sendError = nil
        await app.sendOutbox.run()
        XCTAssertEqual(tries, 1)   // 600초 안에는 다시 보내지 않는다
    }

    func test_모은_실시간_메시지_저장이_실패하면_ack를_기다리는_사이_온_것을_적용하지_않는다() async {
        make(role: .guardian)
        let body = { (t: String) in HandoffAlertBody(text: t, kind: .parked, restName: nil, minutesToArrival: nil,
                                                   occurredAt: Rfc3339.string(self.clock), sessionId: "S", episodeId: "E") }
        let a = env(MessageType.handoffAlert, seq: 1, body: body("a")), b = env(MessageType.handoffAlert, seq: 2, body: body("b"))
        let c = env(MessageType.handoffAlert, seq: 3, body: body("c"))
        relay.inboxHook = { [unowned self] in app.keepConnected.socketEvent(.envelope(a)); app.keepConnected.socketEvent(.envelope(b)) }   // 받은 함을 기다리는 사이 모인다
        relay.ackHook = { [unowned self] in app.keepConnected.socketEvent(.envelope(c)) }   // a의 ack를 기다리는 사이 새것이 온다
        store.saveCount = 0
        store.failAtSave = 2   // a는 저장, b는 실패
        app.keepConnected.socketEvent(.connected(true))
        await app.tasks.settle()
        XCTAssertNotNil(app.state.state.alert(a.id))
        XCTAssertNil(app.state.state.alert(b.id))
        XCTAssertNil(app.state.state.alert(c.id))   // b보다 뒤라 받은 함에서 순서대로 다시 받는다(8.3 4번)
    }

    func test_노래_올리기와_연결_끊기도_띠에_반영한다() async {
        let id = "S_put"
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songAdded,
                                         body: try! JSONValue(encoding: SongAddedBody(songId: id, name: "곡", durationS: 10)),
                                         ts: Rfc3339.string(clock), songId: id, createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        try? Data("x".utf8).write(to: SongFiles.url(for: id))
        defer { SongFiles.remove(id) }
        app.keepConnected.socketEvent(.unreachable)
        relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 60)   // 올리기는 성공, 보내기는 429
        await app.sendOutbox.run()
        XCTAssertEqual(relay.uploaded, [id])
        XCTAssertFalse(app.status.ui.offline)   // 올리기 성공이 중계에 닿았음을 보였다
        // 끊기 DELETE가 네트워크 실패면 띠를 켠다
        relay.deleteError = .network
        _ = await app.disconnect.now()
        XCTAssertTrue(app.status.ui.offline)
    }

    func test_연결_안_된_폰에는_차로_보낸다는_오프라인_띠를_보이지_않는다() async {
        make(paired: false)
        await app.keepConnected.networkPathChanged(satisfied: false)
        XCTAssertTrue(app.status.ui.offline)
        XCTAssertFalse(app.status.ui.showsOfflineBand)   // U7: 연결 전에는 차로 보내지 않는다
        make()
        await app.keepConnected.networkPathChanged(satisfied: false)
        XCTAssertTrue(app.status.ui.showsOfflineBand)
    }

    func test_스폰한_갱신이_돌기_전에_뒤로_가도_다음_앞으로_올_때_다시_갱신한다() async {
        app.lifecycle.onForeground()
        app.lifecycle.onBackground()
        await app.tasks.settle()
        XCTAssertEqual(relay.inboxCalls, 0)
        app.lifecycle.onForeground()     // '갱신 중'이 풀려 있어야 다시 한다
        await app.tasks.settle()
        XCTAssertGreaterThan(relay.inboxCalls, 0)
    }

    func test_더_큰_snapshot_seq는_늦게_끝났어도_적용한다() async {
        let off = DeviceInfo(deviceId: "d_car", role: .car, platform: .android, pairedAt: "2026-11-05T08:00:00Z",
                             lastSeenAt: "2026-11-04T08:00:00Z", online: false, handoffAlerts: nil)
        var on = off; on.online = true
        var calls = 0
        relay.devicesHook = { [unowned self] in
            calls += 1
            guard calls == 1 else { return }
            relay.devicesResult = DevicesResponse(snapshotSeq: 5, devices: [off])
            await app.keepConnected.refreshDevices()   // 나중에 시작한 목록(seq 5)이 먼저 끝난다
            relay.devicesResult = DevicesResponse(snapshotSeq: 6, devices: [on])   // 먼저 시작했지만 중계에서 늦게 처리된 목록(seq 6)
        }
        await app.keepConnected.refreshDevices()
        XCTAssertEqual(app.car().ui.phone.car?.online, true)
        XCTAssertEqual(app.state.state.devicesSnapshotSeq, 6)
    }

    func test_나중_요청의_4xx_응답은_먼저_시작한_요청의_늦은_실패보다_새_관찰이다() async {
        relay.devicesError = .network
        relay.devicesHook = { [unowned self] in
            relay.devicesHook = nil
            relay.inviteError = .http(status: 409, code: "invite_limit", retryAfter: nil)
            _ = await app.invite()   // 나중에 시작해 먼저 끝난 요청: 거절이어도 중계에 닿았다
        }
        await app.keepConnected.refreshDevices()   // 먼저 시작한 목록이 늦게 네트워크 실패로 끝난다
        XCTAssertFalse(app.status.ui.offline)
        relay.devicesError = .http(status: 404, code: nil, retryAfter: nil)
        app.keepConnected.socketEvent(.unreachable)   // 띠를 켠 뒤
        await app.keepConnected.refreshDevices()      // 4xx도 닿음이라 끈다
        XCTAssertFalse(app.status.ui.offline)
    }

    func test_버린_작은_목록은_같은_snapshot의_늦은_나중_목록을_막지_않는다() async {
        let off = DeviceInfo(deviceId: "d_car", role: .car, platform: .android, pairedAt: "2026-11-05T08:00:00Z",
                             lastSeenAt: "2026-11-04T08:00:00Z", online: false, handoffAlerts: nil)
        var on = off; on.online = true
        // 1번(seq 11 꺼짐)이 먼저 끝나 적용 → 3번(seq 10)은 버림 → 2번(seq 11 연결됨, 1번보다 나중에 시작)은 적용해야 한다
        let lists = [1: DevicesResponse(snapshotSeq: 11, devices: [off]), 2: DevicesResponse(snapshotSeq: 11, devices: [on]),
                     3: DevicesResponse(snapshotSeq: 10, devices: [off])]
        var gates: [Int: CheckedContinuation<Void, Never>] = [:]
        let base = relay.devicesCalls
        relay.devicesResponder = { n in
            await withCheckedContinuation { gates[n - base] = $0 }
            return lists[n - base]!
        }
        // 따로 만든 Task가 시작하는 순서는 보장되지 않는다: 하나씩 시작해 문에 닿은 뒤 다음을 시작한다(1번 → 2번 → 3번 순서로 시작, 요청 순번도 그 순서)
        let a = Task { await self.app.keepConnected.refreshDevices() }
        while gates.count < 1 { await Task.yield() }
        let b = Task { await self.app.keepConnected.refreshDevices() }
        while gates.count < 2 { await Task.yield() }
        let c = Task { await self.app.keepConnected.refreshDevices() }
        while gates.count < 3 { await Task.yield() }
        gates[1]!.resume(); _ = await a.value
        XCTAssertEqual(app.car().ui.phone.car?.online, false)
        gates[3]!.resume(); _ = await c.value
        gates[2]!.resume(); _ = await b.value
        XCTAssertEqual(app.car().ui.phone.car?.online, true)
        XCTAssertEqual(app.state.state.devicesSnapshotSeq, 11)
    }

    func test_받은_함_429_기다림은_다시_앞으로_와도_지킨다() async {
        app.lifecycle.onForeground(); await app.tasks.settle()
        relay.inboxError = .http(status: 429, code: "rate_limited", retryAfter: 600)
        await app.keepConnected.refresh()
        relay.inboxError = nil
        let calls = relay.inboxCalls
        app.lifecycle.onBackground()
        app.lifecycle.onForeground(); await app.tasks.settle()
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.inboxCalls, calls)   // 600초 안에는 앞으로 오기·새로고침도 받은 함을 부르지 않는다(1.1)
        app.lifecycle.onBackground()
    }

    func test_메시지_429_기다림은_상태_요청도_지킨다() async {
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        store.value.needsStateRequest = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 60)
        await app.sendOutbox.run()
        relay.sendError = nil
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertTrue(relay.sent.isEmpty)   // 기기당 메시지 한도라 상태 요청도 기다린다(1.4)
        XCTAssertTrue(app.state.state.needsStateRequest)
    }

    func test_확인을_누른_즉시_새_링크를_받지_않는다() {
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        app.connect().start(app.appViewModel.pendingCode!)   // 확인 화면의 연결 버튼: 작업이 돌기 전에 '도는 중'이 된다
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=555555")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))   // U52
    }

    func test_뒤로_간_뒤_늦게_돈_실시간_처리는_버린다() async {
        make(role: .guardian)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        let e = env(MessageType.handoffAlert, seq: 1, body: body)
        app.keepConnected.socketEvent(.envelope(e))
        app.lifecycle.onBackground()   // 처리 작업이 돌기 전
        await app.tasks.settle()
        XCTAssertNil(app.state.state.alert(e.id))   // ack하지 않아 중계에 남는다(앞으로 오면 받는다)
        XCTAssertTrue(relay.acked.isEmpty)
    }

    func test_노래_올리기_429는_같은_모델에서도_메시지_기다림을_만들지_않는다() async {
        let id = "S_429b"
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songAdded,
                                         body: try! JSONValue(encoding: SongAddedBody(songId: id, name: "곡", durationS: 10)),
                                         ts: Rfc3339.string(clock), songId: id, createdAt: clock, nextTryAt: clock)]
        store.value.needsStateRequest = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        try? Data("x".utf8).write(to: SongFiles.url(for: id))
        defer { SongFiles.remove(id) }
        relay.putError = .http(status: 429, code: "rate_limited", retryAfter: 3600)
        var puts = 0
        relay.putHook = { puts += 1 }
        await app.sendOutbox.run()
        clock = clock.addingTimeInterval(1)
        await app.sendOutbox.run()   // 새 기록 저장 등으로 다시 깨워도
        XCTAssertEqual(puts, 1)     // 올리기 Retry-After(한 시간) 안에는 다시 올리지 않는다
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.stateRequest])   // 업로드 한도는 상태 요청을 막지 않는다
    }

    func test_상태_요청의_429도_보낼_함을_기다리게_한다() async {
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        store.value.needsStateRequest = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 60)
        await app.keepConnected.requestStateIfNeeded()
        relay.sendError = nil
        await app.sendOutbox.run()
        XCTAssertTrue(relay.sent.isEmpty)   // 같은 메시지 한도(1.4)
    }

    func test_받은_함_429는_뒤로_간_사이에_와도_기한을_남긴다() async {
        app.lifecycle.onForeground(); await app.tasks.settle()
        relay.inboxHook = { [unowned self] in app.lifecycle.onBackground() }
        relay.inboxError = .http(status: 429, code: "rate_limited", retryAfter: 600)
        await app.keepConnected.refresh()
        relay.inboxHook = nil
        relay.inboxError = nil
        let calls = relay.inboxCalls
        app.lifecycle.onForeground(); await app.tasks.settle()
        XCTAssertEqual(relay.inboxCalls, calls)
        app.lifecycle.onBackground()
    }

    func test_갱신이_끝나기_전에_뒤로_갔다_돌아오면_끝난_뒤_다시_갱신한다() async {
        var calls = 0
        relay.inboxHook = { [unowned self] in   // 첫 받은 함을 기다리는 사이 뒤로 갔다가 곧 돌아온다
            calls += 1
            guard calls == 1 else { return }
            app.lifecycle.onBackground()
            app.lifecycle.onForeground()   // 아직 '갱신 중'이라 이 갱신은 거절된다
        }
        app.lifecycle.onForeground()
        await app.tasks.settle()
        XCTAssertGreaterThanOrEqual(calls, 2)   // 첫 갱신은 결과를 버렸지만 끝난 뒤 다시 받았다
        app.lifecycle.onBackground()
    }

    func test_다시_녹음은_새_곡이_저장될_때만_기존_곡과_바꾼다() throws {
        let rec = FileManager.default.temporaryDirectory.appendingPathComponent("re-\(ULID.generate()).m4a")
        try Data("x".utf8).write(to: rec)
        defer { try? FileManager.default.removeItem(at: rec) }
        for n in ["a", "b", "c"] { try app.recordSong(name: n, recording: rec, durationS: 10) }
        var st = app.state.state; st.songs[2].state = .failed("x"); store.value = st   // 셋째 곡이 올리기에 실패했다
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        let bad = app.songs().ui.songs[2].id
        XCTAssertThrowsError(try app.recordSong(name: "d", recording: rec, durationS: 10))   // 3곡 한도
        // 거절·실패가 아닌 곡(시트를 연 사이 바뀐 곡 등)은 바꾸지 않는다: 3곡 한도에 걸리고 그 곡은 남는다
        let fine = app.songs().ui.songs[0].id
        XCTAssertThrowsError(try app.recordSong(name: "d", recording: rec, durationS: 10, replacing: fine))
        XCTAssertTrue(app.songs().ui.songs.contains { $0.id == fine })
        // 이름이 비면 실패하고 기존 곡은 그대로다
        XCTAssertThrowsError(try app.recordSong(name: " ", recording: rec, durationS: 10, replacing: bad))
        XCTAssertTrue(app.songs().ui.songs.contains { $0.id == bad })
        XCTAssertTrue(FileManager.default.fileExists(atPath: SongFiles.url(for: bad).path))
        // 정상이면 한 번에 바꾼다(지움이 새 올리기보다 먼저 선다)
        try app.recordSong(name: "d", recording: rec, durationS: 10, replacing: bad)
        XCTAssertFalse(app.songs().ui.songs.contains { $0.id == bad })
        XCTAssertEqual(app.songs().ui.songs.count, 3)
        let types = app.state.state.outbox.map(\.type)
        XCTAssertEqual(types.filter { $0 == MessageType.songRemoved }.count, 1)
        XCTAssertLessThan(types.firstIndex(of: MessageType.songRemoved)!, types.lastIndex(of: MessageType.songAdded)!)
        XCTAssertFalse(FileManager.default.fileExists(atPath: SongFiles.url(for: bad).path))
        for r in app.state.state.songs { SongFiles.remove(r.id) }
    }

    func test_소켓_pong은_앞_HTTP_실패로_켠_띠를_끈다() {
        app.keepConnected.socketEvent(.connected(true))
        app.keepConnected.socketEvent(.unreachable)   // 예: ack가 한 번 네트워크 실패
        XCTAssertTrue(app.status.ui.offline)
        app.keepConnected.socketEvent(.alive)         // 같은 소켓으로 pong이 온다
        XCTAssertFalse(app.status.ui.offline)
    }

    func test_주기_목록이_성공하면_남은_상태_요청을_보낸다() async {
        store.value.needsStateRequest = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        await app.keepConnected.afterDevices(epoch: app.keepConnected.foregroundEpoch)   // 기록 지우기 직후 목록 조회가 실패해 표시가 남아 있었다
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.stateRequest])
    }

    func test_주기_목록을_기다리는_사이_뒤로_가면_상태_요청을_시작하지_않는다() async {
        store.value.needsStateRequest = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.devicesHook = { [unowned self] in app.lifecycle.onBackground() }
        await app.keepConnected.afterDevices(epoch: app.keepConnected.foregroundEpoch)
        XCTAssertTrue(relay.sent.isEmpty)
        XCTAssertNil(app.state.state.lastStateRequestAt)   // 보내지 못한 요청으로 10분 제한을 걸지 않는다
    }

    func test_다시_붙은_직후_띄운_상태_요청은_돌기_전에_뒤로_가면_시작하지_않는다() async {
        store.value.needsStateRequest = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        let calls = relay.devicesCalls
        app.keepConnected.socketDidConnect(true)   // 상태 요청을 띄운다
        app.lifecycle.onBackground()     // 띄운 일이 돌기 전에 뒤로 간다
        await app.tasks.settle()
        XCTAssertEqual(relay.devicesCalls, calls)   // 목록도 받지 않는다
        XCTAssertFalse(relay.sent.map(\.type).contains(MessageType.stateRequest))
        XCTAssertNil(app.state.state.lastStateRequestAt)
        XCTAssertTrue(app.state.state.needsStateRequest)   // 표시는 남아 앞으로 오면 보낸다
    }

    func test_기록_지우기_직후_뒤로_가면_상태_요청을_시작하지_않는다() async {
        let calls = relay.devicesCalls
        app.clearRecords()      // 표시를 켜고 상태 요청을 띄운다
        app.lifecycle.onBackground()
        await app.tasks.settle()
        XCTAssertEqual(relay.devicesCalls, calls)
        XCTAssertFalse(relay.sent.map(\.type).contains(MessageType.stateRequest))
        XCTAssertNil(app.state.state.lastStateRequestAt)
        XCTAssertTrue(app.state.state.needsStateRequest)
    }

    func test_기록_지우기와_앱_시작은_남은_임시_녹음을_지운다() throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("recording-test-\(ULID.generate()).m4a")
        try Data("x".utf8).write(to: tmp)
        app.clearRecords()
        XCTAssertFalse(FileManager.default.fileExists(atPath: tmp.path))
        try Data("x".utf8).write(to: tmp)
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertFalse(FileManager.default.fileExists(atPath: tmp.path))
    }

    func test_뒤에서는_받은_함_재시도와_경로_갱신을_하지_않는다() async {
        store.value.lastReceivedAt = clock.addingTimeInterval(-25 * 3600)
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        store.failing = true
        app.lifecycle.onForeground()      // 재요청 표시를 저장하지 못해 5초 뒤 다시 하기를 예약한다
        await app.tasks.settle()
        app.lifecycle.onBackground()
        store.failing = false
        try? await Task.sleep(nanoseconds: 5_500_000_000)
        XCTAssertEqual(relay.inboxCalls, 0)   // 뒤로 가며 취소했다
        app.keepConnected.socketEvent(.unreachable)
        await app.keepConnected.networkPathChanged(satisfied: true)
        XCTAssertEqual(relay.inboxCalls, 0)   // 뒤에서는 경로가 돌아와도 갱신하지 않는다
    }

    func test_받은_함_다음_쪽은_그_요청을_시작할_때의_관찰로_띠를_정한다() async {
        relay.inboxPages = [InboxResponse(messages: [feedingState(1, last: nil)], more: true),
                            InboxResponse(messages: [feedingState(2, last: nil)], more: false)]
        relay.devicesError = .http(status: 404, code: nil, retryAfter: nil)   // 기기 목록은 띠를 바꾸지 않게(4xx)
        var calls = 0
        relay.inboxHook = { [unowned self] in calls += 1; if calls == 1 { app.keepConnected.socketEvent(.unreachable) } }   // 첫 쪽을 기다리는 사이
        await app.keepConnected.refresh()
        XCTAssertEqual(calls, 2)
        XCTAssertFalse(app.status.ui.offline)   // 그 관찰 뒤에 시작한 둘째 쪽이 성공했다
    }

    func test_노래_보내기는_올리기_뒤_보내기를_시작할_때의_관찰로_띠를_정한다() async {
        let id = "S_mark"
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songAdded,
                                         body: try! JSONValue(encoding: SongAddedBody(songId: id, name: "곡", durationS: 10)),
                                         ts: Rfc3339.string(clock), songId: id, createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        try? Data("x".utf8).write(to: SongFiles.url(for: id))   // 시작 정리(목록에 없는 파일 지우기) 뒤에 둔다
        defer { SongFiles.remove(id) }
        relay.putHook = { [unowned self] in app.keepConnected.socketEvent(.unreachable) }   // 올리는 사이 소켓이 닿지 않음을 봤다
        await app.sendOutbox.run()
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.songAdded])
        XCTAssertFalse(app.status.ui.offline)   // 그 뒤에 시작한 보내기가 성공했다
    }

    func test_인터넷_경로가_생겨도_중계에_닿기_전에는_띠를_끄지_않는다() async {
        app.lifecycle.onForeground(); await app.tasks.settle()   // 앞에 있을 때만 다시 확인한다
        let before = relay.inboxCalls
        app.keepConnected.socketEvent(.unreachable)
        relay.inboxError = .http(status: 503, code: nil, retryAfter: nil)
        relay.devicesError = relay.inboxError
        await app.keepConnected.networkPathChanged(satisfied: true)
        XCTAssertGreaterThan(relay.inboxCalls, before)   // 다시 확인은 한다
        XCTAssertTrue(app.status.ui.offline)                 // 경로는 있지만 중계가 고장
        relay.inboxError = nil
        relay.devicesError = nil
        await app.keepConnected.networkPathChanged(satisfied: false)   // 한 번 끊겼다 다시 생긴다(같은 상태 갱신은 무시한다)
        await app.keepConnected.networkPathChanged(satisfied: true)
        XCTAssertFalse(app.status.ui.offline)                // 받은 함이 성공해 끈다
    }

    func test_인터넷_경로가_끊긴_뒤_늦게_온_HTTP_성공은_띠를_끄지_않는다() async {
        relay.devicesError = .http(status: 503, code: nil, retryAfter: nil)
        relay.inboxHook = { [unowned self] in await app.keepConnected.networkPathChanged(satisfied: false) }
        await app.keepConnected.refresh()
        XCTAssertTrue(app.status.ui.offline)
    }

    func test_연결_안_된_폰은_인터넷_경로만으로_띠를_켜고_끈다() async {
        make(paired: false)
        await app.keepConnected.networkPathChanged(satisfied: false)
        XCTAssertTrue(app.status.ui.offline)
        await app.keepConnected.networkPathChanged(satisfied: true)
        XCTAssertFalse(app.status.ui.offline)
    }

    func test_기기_목록을_기다리는_사이_받은_차_봉투의_연결됨을_늦은_목록이_되돌리지_않는다() async {
        let car = DeviceInfo(deviceId: "d_car", role: .car, platform: .android, pairedAt: "2026-11-05T08:00:00Z",
                             lastSeenAt: "2026-11-04T08:00:00Z", online: false, handoffAlerts: nil)
        store.value.devices = [car]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.devicesResult = DevicesResponse(snapshotSeq: 2, devices: [car])   // 차가 꺼져 있을 때 만든 목록
        relay.devicesHook = { [unowned self] in await app.syncInbox.store(app.state.creds, [feedingState(1, last: nil)], live: true) }   // 그사이 차가 켜졌다
        await app.keepConnected.refreshDevices()
        XCTAssertEqual(app.car().ui.phone.car?.online, true)
        XCTAssertEqual(app.car().ui.phone.car?.lastSeenAt.flatMap(Rfc3339.parse), clock)
        // 실시간 봉투 뒤에 요청한 목록은 그대로 적용한다
        relay.devicesHook = nil
        relay.devicesResult = DevicesResponse(snapshotSeq: 3, devices: [car])
        await app.keepConnected.refreshDevices()
        XCTAssertEqual(app.car().ui.phone.car?.online, false)
    }

    func test_소켓이_다시_붙으면_밀린_것은_받은_함으로_먼저_처리해_실시간으로_다루지_않는다() async {
        make(role: .guardian)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        let missed = env(MessageType.handoffAlert, seq: 3, body: body)
        relay.inboxPages = [InboxResponse(messages: [missed], more: false)]
        app.keepConnected.socketEvent(.connected(true))
        app.keepConnected.socketEvent(.envelope(missed))   // 다시 붙자마자 중계가 밀린 것을 소켓으로 먼저 보낸다
        await app.tasks.settle()
        XCTAssertNotNil(app.state.state.alert(missed.id))   // 저장했다
        XCTAssertNil(app.appViewModel.ui.banner)          // 끊긴 사이 온 것이라 배너를 띄우지 않는다
        XCTAssertEqual(relay.inboxCalls, 1)
    }

    func test_뒤로_가면_보낼_함_다시_시도를_멈추고_뒤에서는_예약하지_않는다() async {
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.sendError = .network
        var tries = 0
        relay.sendHook = { tries += 1 }
        app.lifecycle.onForeground()   // 앞에서 보내다 실패하면 1초 뒤 다시 시도를 예약한다
        await app.tasks.settle()
        XCTAssertEqual(tries, 1)
        app.lifecycle.onBackground()
        clock = clock.addingTimeInterval(5)
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        XCTAssertEqual(tries, 1)   // 예약했던 다시 시도가 돌지 않았다
        await app.sendOutbox.run()   // 뒤에서도 한 번 보내기는 한다(잠금 화면 끝내기)
        XCTAssertEqual(tries, 2)
        clock = clock.addingTimeInterval(5)
        try? await Task.sleep(nanoseconds: 2_500_000_000)
        XCTAssertEqual(tries, 2)   // 실패해도 뒤에서는 다시 시도를 예약하지 않는다
    }

    func test_재요청_표시를_저장하지_못하면_소켓을_열지_않고_저장되면_연다() async {
        store.value.lastReceivedAt = clock.addingTimeInterval(-25 * 3600)
        app = AppContainer(relay: relay, store: store, credentials: creds, baseURL: URL(string: "http://127.0.0.1:1"),
                         now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        defer { app.lifecycle.onBackground() }
        store.failing = true
        app.lifecycle.onForeground()
        XCTAssertNil(app.keepConnected.socket)   // 받은 것을 적용하지 못하고 모으기만 할 소켓은 열지 않는다(중계에 남겨 둔다)
        store.failing = false
        await app.keepConnected.refresh()
        XCTAssertNotNil(app.keepConnected.socket)
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.stateRequest])   // 저장된 표시로 상태를 다시 요청했다
    }

    func test_버린_안내와_저장_실패_안내는_서로_덮지_않는다() async {
        store.value.introSeen = false   // 아래 markIntroSeen이 실제로 저장하게(저장 실패를 일으킬 아무 저장)
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        relay.sendError = .http(status: 400, code: nil, retryAfter: nil)
        await app.sendOutbox.run()
        let dropped = "실험에 쓸 노래 선택을 차로 보내지 못했어요."
        XCTAssertEqual(app.status.ui.notice, dropped)
        store.failing = true
        app.pair.markIntroSeen()   // 아무 저장 실패
        XCTAssertEqual(app.status.ui.notice, dropped + "\n저장하지 못했어요. 저장 공간을 확인해 주세요.")
        app.pair.markIntroSeen()
        XCTAssertEqual(app.status.ui.notice?.components(separatedBy: "\n").count, 2)   // 같은 글은 한 번
    }

    func test_7일_지나_버린_메시지도_안내한다() async {
        func item(_ type: String, at t: Date) -> OutboxItem {
            OutboxItem(id: ULID.generate(), type: type, body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                       ts: Rfc3339.string(t), songId: nil, createdAt: t, nextTryAt: t)
        }
        // 앱을 열 때의 정리(purgeExpired)
        store.value.outbox = [item(MessageType.songRemoved, at: clock.addingTimeInterval(-8 * 86400))]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(app.status.ui.notice, "노래 삭제를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요.")
        app.status.dismissNotice()
        // 보내기 직전의 정리(flushOutbox): 열 때는 아직 7일이 지나지 않았다
        store.value.outbox = [item(MessageType.songSelected, at: clock.addingTimeInterval(-6 * 86400))]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        XCTAssertEqual(app.state.state.outbox.count, 1)
        XCTAssertNil(app.status.ui.notice)
        clock = clock.addingTimeInterval(2 * 86400)
        await app.sendOutbox.run()
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertTrue(relay.sent.isEmpty)
        XCTAssertEqual(app.status.ui.notice, "실험에 쓸 노래 선택을 차로 보내지 못했어요.")
    }

    func test_다시_붙기_전에_시작한_요청의_늦은_실패는_띠를_다시_켜지_않는다() async {
        // 받은 함 요청 중에 소켓이 다시 붙고, 그 요청은 끊겼던 동안의 실패로 늦게 끝난다.
        app.keepConnected.socketEvent(.unreachable)
        relay.inboxHook = { [unowned self] in self.app.keepConnected.socketEvent(.connected(true)) }
        relay.inboxError = .network
        await app.keepConnected.refresh()
        XCTAssertGreaterThan(relay.inboxCalls, 0)
        XCTAssertFalse(app.status.ui.offline)
        // 보낼 함도 같다.
        relay.inboxHook = nil
        relay.inboxError = nil   // 다시 붙을 때 도는 받은 함 갱신은 성공한다
        app.keepConnected.socketEvent(.unreachable)
        app.recordCare.start(.feed)
        relay.sendHook = { [unowned self] in self.app.keepConnected.socketEvent(.connected(true)) }
        relay.sendError = .network
        await app.sendOutbox.run()
        XCTAssertEqual(app.state.state.outbox.first?.attempts, 1)   // 실패해 다시 보낼 차례로 갔다
        XCTAssertFalse(app.status.ui.offline)
    }

    func test_멈춘_소켓은_닿지_않음을_늦게_알리지_않는다() async {
        // 연결 실패를 알리는 사이 앱이 뒤로 가 멈춘다(stop). 그 뒤 '닿지 않음'이 오면 띠가 켜진 채 남는다.
        let closed = expectation(description: "connected(false)")
        closed.assertForOverFulfill = false
        let late = expectation(description: "unreachable")
        late.isInverted = true
        var s: SocketHandle?
        s = URLSessionRelaySocket(baseURL: URL(string: "http://127.0.0.1:1")!, token: { "t" }) { e in
            switch e {
            case .connected(false):
                // 처음 한 번만 멈춘다(stop도 connected(false)를 낸다). s를 놓아 순환 참조도 끊는다.
                if let socket = s { s = nil; socket.stop() }
                closed.fulfill()
            case .unreachable: late.fulfill()
            default: break
            }
        }
        s?.start()
        await fulfillment(of: [closed, late], timeout: 2)
    }

    func test_소켓이_중계에_닿지_못하면_닿지_않음을_알린다() async {
        // 127.0.0.1:1에는 듣는 곳이 없어 연결이 바로 거절된다(응답 없음).
        let got = expectation(description: "unreachable")
        got.assertForOverFulfill = false
        let s = URLSessionRelaySocket(baseURL: URL(string: "http://127.0.0.1:1")!, token: { "t" }) { e in if case .unreachable = e { got.fulfill() } }
        s.start()
        await fulfillment(of: [got], timeout: 5)
        s.stop()
    }

    func test_소켓이_붙어도_요청_표시가_없으면_기기_목록을_받지_않는다() async {
        var devicesCalls = 0
        relay.devicesHook = { devicesCalls += 1 }
        app.keepConnected.socketDidConnect(true)
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(devicesCalls, 0)
        XCTAssertTrue(relay.sent.isEmpty)
    }

    func test_받은_함_저장이_실패하면_다음_쪽을_요청하지_않는다() async {
        relay.inboxPages = [InboxResponse(messages: [feedingState(5, last: "2026-11-05T08:00:00Z")], more: true),
                            InboxResponse(messages: [feedingState(6, last: "2026-11-05T09:00:00Z")], more: false)]
        store.failing = true
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.inboxCalls, 1)                // 실패한 메시지보다 뒤의 쪽으로 넘어가지 않는다
        XCTAssertTrue(relay.acked.isEmpty)
        XCTAssertEqual(app.state.state.feeding.seq, 0)
        store.failing = false
        await app.keepConnected.refresh()                              // 저장이 되면 이어서 받는다
        XCTAssertEqual(relay.inboxCalls, 2)
    }

    func test_자격만_있고_상태가_비었으면_요청_표시를_켠다() {
        store = MemoryStateStore()   // restoreFailed는 꺼져 있다(파일이 없었다)
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertTrue(app.state.state.needsStateRequest)
        XCTAssertEqual(app.state.state.carId, "c_1")
    }

    func test_복구_뒤_첫_요청이_만료돼도_24시간이_지나면_상태_요청을_다시_보낸다() async {
        store = MemoryStateStore()
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertFalse(app.state.state.needsStateRequest)
        relay.sent = []
        clock = clock.addingTimeInterval(8 * 86400)
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
    }

    func test_저장이_거부된_실행은_요청_표시를_만들지_않는다() {
        store.restoreFailed = true
        store.writesRefused = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertFalse(app.state.state.needsStateRequest)       // 원본이 그대로라 다음 정상 실행이 읽는다
    }

    // MARK: 15회차 검토 반영

    func test_기록_지우기도_기기_목록_조회가_성공한_뒤에_상태를_요청한다() async {
        relay.devicesError = .network
        app.clearRecords()
        await app.tasks.settle()
        XCTAssertTrue(relay.sent.isEmpty)                  // 목록 조회가 실패하면 요청하지 않는다
        XCTAssertTrue(app.state.state.needsStateRequest)
        relay.devicesError = nil
        app.clearRecords()
        await app.tasks.settle()
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
        XCTAssertFalse(app.state.state.needsStateRequest)
    }
    func test_기록_지우기는_노래_올리기만_빼고_삭제와_선택_요청은_남긴다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)   // 올리는 중(song_added)
        let acc = SongStatusBody(songId: "01SONG", name: "자장가", status: .accepted, reason: nil, durationS: 30, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: acc)], live: true)
        app.selectSong("01SONG")
        app.removeSong("01SONG")   // 오프라인에서 지운 노래
        XCTAssertEqual(store.value.outbox.map(\.type), ["song_added", "song_selected", "song_removed"])
        app.clearRecords()
        XCTAssertEqual(store.value.outbox.map(\.type), ["song_selected", "song_removed"])
    }

    // MARK: 보관

    func test_보관_기간이_지난_것은_지운다() {
        let old = clock.addingTimeInterval(-8 * 86400)
        let older = clock.addingTimeInterval(-91 * 86400)
        store.value.processed = ["a": old, "b": clock]
        store.value.shown = ["a": old]
        store.value.reports = [StoredReport(messageId: "m", seq: 1, receivedAt: older, body: report("old")),
                               StoredReport(messageId: "n", seq: 2, receivedAt: old, body: report("keep"))]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertEqual(Set(app.state.state.processed.keys), ["b"])
        XCTAssertTrue(app.state.state.shown.isEmpty)
        XCTAssertEqual(app.state.state.reports.map(\.body.sessionId), ["keep"])
    }

    func test_폰_시계가_2분_넘게_다르면_경고() {
        app.keepConnected.noteServerDate(clock.addingTimeInterval(60))
        XCTAssertNil(app.status.ui.clockWarning)
        app.keepConnected.noteServerDate(clock.addingTimeInterval(-5 * 60))
        XCTAssertEqual(app.status.ui.clockWarning, "휴대폰 시계가 5분 달라요. 수유 시각이 어긋날 수 있어요.")
    }
}

final class SoundLabelTests: XCTestCase {
    func test_모르는_소리_방법의_음원_이름은_기타() throws {
        let ref = try JSONDecoder().decode(SoundRef.self, from: Data(#"{"method":"future_sound","label":"미래 소리"}"#.utf8))
        XCTAssertEqual(ref.method, .unknown)
        XCTAssertEqual(soundLabel(ref.method, ref.label), "기타")
        XCTAssertEqual(soundLabel(.lullaby, "자장가 1"), "자장가 1")
    }
}

private extension Envelope {
    func with(id: String) -> Envelope { var e = self; e.id = id; return e }
}

final class StopTextTests: XCTestCase {
    private func stop(_ mode: HandoffKind, told: Bool?) throws -> StopInfo {
        let t = told.map { ",\"told_driver\":\($0)" } ?? ""
        let json = "{\"handoff\":true,\"reason\":\"attempt_limit\",\"mode\":\"\(mode.rawValue)\",\"rest_name\":\"휴게소\",\"waypoint_added\":false,\"guardian_notified\":false\(t)}"
        return try ProtocolCoding.decoder.decode(StopInfo.self, from: Data(json.utf8))
    }

    func test_운전자에게_말하지_못한_인계는_제안했다고_보이지_않는다() throws {
        XCTAssertEqual(stopHandoffText(try stop(.restOffer, told: false)), "쉼터는 묻지 못했어요")
        XCTAssertEqual(stopHandoffText(try stop(.arrivalNotice, told: false)), "도착 안내는 하지 못했어요")
        XCTAssertEqual(stopHandoffText(try stop(.noRest, told: false)), "안전한 곳에 세우도록 안내하지 못했어요")
        XCTAssertEqual(stopHandoffText(try stop(.restOffer, told: true)), "휴게소에 들르자고 제안했어요")
        XCTAssertEqual(stopHandoffText(try stop(.restOffer, told: nil)), "휴게소에 들르자고 제안했어요")
        XCTAssertEqual(stopHandoffText(try stop(.arrivalNotice, told: true)), "도착 안내를 했어요")
        XCTAssertEqual(stopHandoffText(try stop(.noRest, told: nil)), "경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요")
        XCTAssertEqual(stopHandoffText(try stop(.parked, told: nil)), "차가 선 채로 달래기를 멈췄어요")
        let out = String(data: try ProtocolCoding.encoder.encode(try stop(.restOffer, told: nil)), encoding: .utf8)!
        XCTAssertFalse(out.contains("told_driver"))
    }

    func test_뒷좌석_출력이_없어_정차한_인계는_소리를_틀지_못했다고_쓴다() throws {   // U59
        let json = "{\"handoff\":true,\"reason\":\"no_sound\",\"mode\":\"parked\",\"rest_name\":null,\"waypoint_added\":false,\"guardian_notified\":false}"
        let s = try ProtocolCoding.decoder.decode(StopInfo.self, from: Data(json.utf8))
        XCTAssertEqual(stopText(s), "뒷좌석 출력 없음 · 차가 선 채로 소리를 틀지 못했어요")
    }

    func test_정차_인계는_알렸다고_보이지_않는다() throws {
        func parked(_ g: Bool) throws -> StopInfo {
            let json = "{\"handoff\":true,\"reason\":\"attempt_limit\",\"mode\":\"parked\",\"rest_name\":null,\"waypoint_added\":false,\"guardian_notified\":\(g)}"
            return try ProtocolCoding.decoder.decode(StopInfo.self, from: Data(json.utf8))
        }
        XCTAssertEqual(stopText(try parked(false)), "두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요")
        XCTAssertEqual(stopText(try parked(true)), "두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요 · 다른 보호자에게 알림")
    }

    func test_정차_문구는_확인된_사실만_쓴다() throws {
        func parked(_ g: Bool) throws -> StopInfo {
            let json = "{\"handoff\":true,\"reason\":\"attempt_limit\",\"mode\":\"parked\",\"rest_name\":null,\"waypoint_added\":false,\"guardian_notified\":\(g)}"
            return try ProtocolCoding.decoder.decode(StopInfo.self, from: Data(json.utf8))
        }
        XCTAssertEqual(stopText(try parked(true)), "두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요 · 다른 보호자에게 알림")
        XCTAssertEqual(stopText(try parked(false)), "두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요")
        XCTAssertEqual(stopText(try stop(.noRest, told: true)), "두 번 시도 뒤 · 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요")
        XCTAssertEqual(stopText(try stop(.noRest, told: false)), "두 번 시도 뒤 · 안전한 곳에 세우도록 안내하지 못했어요")
        XCTAssertEqual(stopText(try stop(.noRest, told: nil)), "두 번 시도 뒤 · 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요")
    }

    func test_뒤붙임까지_전체_줄이_안드로이드와_같다() throws {
        let json = "{\"handoff\":true,\"reason\":\"session_cap\",\"mode\":\"rest_offer\",\"rest_name\":\"휴게소\",\"waypoint_added\":true,\"guardian_notified\":true,\"told_driver\":true}"
        let s = try ProtocolCoding.decoder.decode(StopInfo.self, from: Data(json.utf8))
        XCTAssertEqual(stopText(s), "세션 20분 상한 · 휴게소에 들르자고 제안했어요 · 경유지 추가 · 다른 보호자에게 알림")
    }
}

final class MethodStatRowsTests: XCTestCase {
    func test_모르는_소리_둘은_두_행() {
        let rows = methodStatRows([
            MethodStat(method: .unknown, label: "future_a", successes: 1, attempts: 3),
            MethodStat(method: .unknown, label: "future_b", successes: 2, attempts: 2),
        ])
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(Set(rows.map(\.id)).count, 2)
        XCTAssertEqual(rows.map(\.value), ["3번 중 1번 그침", "아직 모름"])
    }
}
