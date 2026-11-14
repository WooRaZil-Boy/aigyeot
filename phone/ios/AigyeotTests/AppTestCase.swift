import XCTest
@testable import Aigyeot

/// 합성 루트 `AppContainer`를 그대로 쓰는 앱 단위 시험의 바탕. `FakeRelay`·메모리 저장소는 TestKit에 있다.
@MainActor
class AppTestCase: XCTestCase {
    var relay: FakeRelay!
    var store: MemoryStateStore!
    var creds: MemoryCredentialStore!
    var clock: Date!
    var app: AppContainer!

    func make(role: PhoneRole = .owner, paired: Bool = true) {
        relay = FakeRelay()
        store = MemoryStateStore(PhoneState(introSeen: true, carId: paired ? "c_1" : nil))   // 첫 안내를 지난 보통 상태(연결됐으면 차 id가 있다). 첫 실행은 그 시험이 따로 만든다
        creds = MemoryCredentialStore(paired ? Credentials(carId: "c_1", deviceId: "d_1", token: "at1.c_1.d_1.x", role: role) : nil)
        clock = Date(timeIntervalSince1970: 1_800_000_000)
        restart()
        app.sendOutbox.autoFlush = false
    }

    /// 같은 저장소·자격으로 앱을 다시 만든다(프로세스를 다시 켠 것).
    func restart() {
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false)
    }

    override func setUp() async throws { await MainActor.run { make() } }

    struct StubToken: PushTokens { var token: String? }
    final class ChangingToken: PushTokens { var token: String?; init(_ t: String) { token = t } }

    func env(_ type: String, seq: Int64, body: some Encodable, id: String = ULID.generate(), from: Role = .car) -> Envelope {
        Envelope(id: id, seq: seq, type: type, car: "c_1", from: Sender(role: from, device: "d_9"),
                 ts: "2026-11-05T08:00:00Z", body: try! JSONValue(encoding: body))
    }

    func feedingState(_ seq: Int64, last: String?) -> Envelope {
        env(MessageType.feedingState, seq: seq, body: FeedingStateBody(lastFeedingStart: last, windowStartExpected: nil))
    }

    func report(_ session: String, started: String = "2026-11-05T08:00:00+09:00") -> ReportSummaryBody {
        ReportSummaryBody(sessionId: session, startedAt: started, endedAt: "2026-11-05T08:40:00+09:00", simulated: false,
                          outputRoute: .rearZone, truncated: nil, timeline: [], preCryContext: [],
                          steering: Steering(available: false, baselinePerMin: nil, duringCryPerMin: nil, afterResponsePerMin: nil,
                                             duringCryZ: nil, unavailableReason: .noComparableWindow),
                          carActions: [], controls: [], methodStats: nil)
    }

    func device(_ id: String, _ role: Role = .guardian) -> DeviceInfo {
        DeviceInfo(deviceId: id, role: role, platform: .ios, pairedAt: "2026-11-05T08:00:00Z", lastSeenAt: nil, online: false, handoffAlerts: true)
    }

    func tempDir() -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("state-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }
}

extension Envelope {
    func with(id: String) -> Envelope { var e = self; e.id = id; return e }
}
