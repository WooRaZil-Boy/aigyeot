import XCTest
@testable import Aigyeot

@MainActor
/// 돌봄 기록 시험의 바탕: 가짜 중계·저장소로 `AppContainer`를 만들고 기록을 쉽게 만드는 도우미.
class CareTestCase: XCTestCase {
    var relay: FakeRelay!

    var store: MemoryStateStore!

    var creds: MemoryCredentialStore!

    var careStore: MemoryCareStore!

    var clock: Date!

    var app: AppContainer!

    let seoul = TimeZone(identifier: "Asia/Seoul")!

    static func t(_ s: String) -> Date { Rfc3339.parse(s)! }

    static func ms(_ s: String) -> Int64 { CareTime.ms(t(s)) }

    var nowMs: Int64 { CareTime.ms(clock) }

    /// role이 nil이면 차와 연결하지 않은 폰이다.
    func make(role: PhoneRole? = .owner, care: CareFile = CareFile(migratedFeedings: true), state: PhoneState? = nil) {
        relay = FakeRelay()
        store = MemoryStateStore(state ?? PhoneState(introSeen: true, carId: role == nil ? nil : "c_1"))
        creds = MemoryCredentialStore(role.map { Credentials(carId: "c_1", deviceId: "d_1", token: "at1.c_1.d_1.x", role: $0) })
        careStore = MemoryCareStore(care)
        restart()
        app.sendOutbox.autoFlush = false
    }

    /// 같은 저장소·자격으로 앱을 다시 만든다(프로세스를 다시 켠 것).
    func restart() {
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore,
                           now: { [unowned self] in self.clock }, sendsCareSummary: false)
    }

    /// 전제: 이 연결에서 요약을 넣은 적이 있는 폰(기록에서 온 값이 없는 빈 요약도 보낸다). 다시 보내기·날 바뀜·시계 같은 요약 규칙 시험은 이 폰에서 본다.
    /// '넣은 적'은 maxSentCareAsOf다. 시험들이 쓰는 어떤 시각보다 이르게 둔다(시계 비교에 끼어들지 않게).
    func makeSummarySentBefore() {
        var s = PhoneState(introSeen: true, carId: "c_1")
        s.maxSentCareAsOf = Self.ms("2026-10-01T00:00:00+09:00")
        make(state: s)
    }

    override func setUp() async throws {
        await MainActor.run {
            clock = Self.t("2026-11-05T12:00:00+09:00")
            make()
        }
    }

    func event(_ kind: CareKind, _ start: String, end: String? = nil, endUnknown: Bool = false,
               zone: String = "Asia/Seoul", deletedAt: Int64? = nil, id: String = ULID.generate()) -> CareEvent {
        CareEvent(id: id, kind: kind, startAt: Self.ms(start), endAt: end.map(Self.ms), endUnknown: endUnknown, zone: zone,
                  createdAt: Self.ms(start), updatedAt: Self.ms(start), deletedAt: deletedAt)
    }

    /// 보낼 함의 feeding_started `at` 값들.
    var queuedFeedings: [String] {
        app.state.state.outbox.filter { $0.type == MessageType.feedingStarted }.compactMap {
            if case .string(let at)? = $0.body.objectValue?["at"] { return at } else { return nil }
        }
    }

    static func withoutNulls(_ v: Any) -> Any {
        if let d = v as? [String: Any] { return d.filter { !($0.value is NSNull) }.mapValues(withoutNulls) }
        if let a = v as? [Any] { return a.map(withoutNulls) }
        return v
    }

    var careSummaries: [OutboxItem] { app.state.state.outbox.filter { $0.type == MessageType.careSummary } }

    var sentSummaries: Int { relay.sent.filter { $0.type == MessageType.careSummary }.count }

    /// 지금 시각 기준 n분 전에 시작한 끝 모름 수유(기기 시간대).
    func feedAgo(_ minutes: Double, id: String = ULID.generate()) -> CareEvent {
        let start = CareTime.ms(clock) - Int64(minutes * 60_000)
        return CareEvent(id: id, kind: .feed, startAt: start, endAt: nil, endUnknown: true, zone: TimeZone.current.identifier,
                         createdAt: start, updatedAt: start)
    }

    var nextMidnight: Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        return cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: clock))!
    }

    func assertSeedValues(_ f: CareFile, _ t: Int64, zone: TimeZone? = nil, line: UInt = #line) {
        let zone = zone ?? seoul
        let m = t / 60_000 * 60_000
        let s = BuildCareSummary()(f, asOf: t, zone: zone)
        func at(_ ms: Int64) -> String { Rfc3339.string(CareTime.date(ms), timeZone: zone) }
        let w = at(t)
        XCTAssertEqual(s.lastSleepStartAt, at(m - 210 * 60_000), w, line: line)
        XCTAssertEqual(s.lastSleepEndAt, at(m - 150 * 60_000), w, line: line)
        XCTAssertNil(s.sleepingSince, w, line: line)
        XCTAssertEqual(s.todaySleepMin, 120, w, line: line)
        XCTAssertEqual(s.nightWakeups, 1, w, line: line)
        XCTAssertEqual(s.lastDiaperAt, at(m - 60 * 60_000), w, line: line)
        XCTAssertEqual(s.todayDiaperCount, 2, w, line: line)
        XCTAssertEqual(s.todayFeedCount, 2, w, line: line)
    }
}
