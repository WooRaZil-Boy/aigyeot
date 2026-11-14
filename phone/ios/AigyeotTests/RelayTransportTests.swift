@testable import Aigyeot
import Network
import XCTest

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
        await fulfillment(of: [got], timeout: 25)   // 10초 안에 pong이 없으면 끊고, 중계 불통으로 띠를 켠다. 전체 시험 중에는 늦어질 수 있어 넉넉히
        s.stop()
    }

    func test_중계가_4001로_닫으면_연결이_지워졌다고_알린다() async throws {
        let server = try EnvelopeFirstServer(frame: "x", closeCode: 4001)   // 기기를 지운 중계가 소켓을 닫는다
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
        XCTAssertFalse(unreachable)   // 띠가 아니라 끊김 화면(GONE)
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
        // 모델은 '붙음'에서 버퍼링을 켠다. 봉투가 먼저 오면 밀린 것을 실시간으로 처리한다.
        XCTAssertEqual(Array(events.prefix(2)), ["connected(true)", "envelope"])
    }
}

final class RelayClientInboxTests: XCTestCase {
    func test_읽지_못한_봉투는_id와_seq만_남겨_ack하고_넘어가게_한다() throws {
        // swiftlint:disable:next line_length
        let page = #"{"messages":[{"v":1,"id":"A","seq":1,"type":"feeding_state","car":"c_1","from":{"role":"car","device":"d"},"ts":"2026-11-05T08:00:00Z","body":{}},{"v":1,"id":"B","seq":2,"type":"feeding_state","car":"c_1","body":{}}],"more":false}"#
        let r = try HTTPRelayClient.lenientInbox(Data(page.utf8), car: "c_1")
        XCTAssertEqual(r.messages.map(\.id), ["A", "B"])
        XCTAssertEqual(r.messages[0].v, 1)
        XCTAssertEqual(r.messages[1].v, 0)          // ts가 없어 읽지 못했다: 판 0(모델은 ack만 한다)
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
