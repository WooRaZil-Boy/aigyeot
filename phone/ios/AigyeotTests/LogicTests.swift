import XCTest
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

// MARK: AppModel

final class FakeRelay: RelayAPI {
    var sent: [Envelope] = []
    var acked: [String] = []
    var uploaded: [String] = []
    var inboxPages: [InboxResponse] = []
    var sendError: RelayError?
    var inboxError: RelayError?
    var pairResult: Result<PairResponse, RelayError> = .failure(.network)
    var sendBody: JSONValue?
    var deleted = false
    var deleteError: RelayError?
    var inboxCalls = 0
    var sendHook: (() async -> Void)?
    var pairHook: (() async -> Void)?
    var pairCalls = 0
    var putHook: (() async -> Void)?
    /// inbox 응답이 오기 전에 일어나는 일(연결이 바뀌는 상황 흉내).
    var inboxHook: (() async -> Void)?

    func pair(code: String, pushToken: String?) async throws -> PairResponse {
        pairCalls += 1
        await pairHook?()
        return try pairResult.get()
    }
    var devicesResult = DevicesResponse(snapshotSeq: 1, devices: [])
    var devicesError: RelayError?
    var devicesHook: (() async -> Void)?
    func devices() async throws -> DevicesResponse {
        await devicesHook?()
        if let devicesError { throw devicesError }
        return devicesResult
    }
    func patchMe(_ r: PatchDeviceRequest) async throws -> PatchDeviceResponse {
        PatchDeviceResponse(deviceId: "d", role: .guardian, platform: .ios, handoffAlerts: r.handoffAlerts ?? true, pushTokenSet: false)
    }
    func deleteMe() async throws {
        if let deleteError { throw deleteError }
        deleted = true
    }
    func createInvite() async throws -> InviteResponse { throw RelayError.network }
    func send(_ e: Envelope) async throws -> SendMessageResponse {
        await sendHook?()
        if let sendError { throw sendError }
        sent.append(e)
        return SendMessageResponse(id: e.id, seq: Int64(sent.count), recipients: 1, body: sendBody ?? e.body)
    }
    func inbox(afterSeq: Int64?) async throws -> InboxResponse {
        inboxCalls += 1
        await inboxHook?()
        if let inboxError { throw inboxError }
        return inboxPages.isEmpty ? InboxResponse(messages: [], more: false) : inboxPages.removeFirst()
    }
    func ack(ids: [String]) async throws { acked += ids }
    func putSong(id: String, file: URL) async throws { await putHook?(); uploaded.append(id) }
}

@MainActor
final class AppModelTests: XCTestCase {
    var relay: FakeRelay!
    var store: MemoryStateStore!
    var creds: MemoryCredentialStore!
    var clock: Date!
    var model: AppModel!

    func make(role: PhoneRole = .owner, paired: Bool = true) {
        relay = FakeRelay()
        store = MemoryStateStore(PhoneState(carId: paired ? "c_1" : nil))   // 연결된 뒤의 보통 상태(차 id가 있다)
        creds = MemoryCredentialStore(paired ? Credentials(carId: "c_1", deviceId: "d_1", token: "at1.c_1.d_1.x", role: role) : nil)
        clock = Date(timeIntervalSince1970: 1_800_000_000)
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        model.autoFlush = false
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

    func test_연결_성공은_토큰을_저장하고_역할을_정한다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .guardian))
        let f = await model.pair(.invite("7K3QM2XW9D"))
        XCTAssertNil(f)
        XCTAssertEqual(creds.value, Credentials(carId: "c_9", deviceId: "d_9", token: "at1.c_9.d_9.s", role: .guardian))
        XCTAssertEqual(model.state.handoffAlerts, true)   // guardian 기본값 참(G12)
    }

    func test_연결_실패_문구_분류() async {
        make(paired: false)
        for (err, want) in [(RelayError.http(status: 404, code: "code_invalid", retryAfter: nil), ConnectFailure.codeInvalid),
                            (.http(status: 429, code: "rate_limited", retryAfter: 10), .rateLimited(10)),
                            (.http(status: 400, code: "bad_code_format", retryAfter: nil), .badCode),
                            (.network, .offline)] {
            relay.pairResult = .failure(err)
            let got = await model.pair(.pair("042917"))
            XCTAssertEqual(got, want)
            XCTAssertNil(creds.value)
            clock = clock.addingTimeInterval(11)   // 429의 잠금(10초)을 지나 다음 경우로
        }
    }

    func test_링크는_바로_연결하지_않고_확인을_거친다() {
        make(paired: false)
        model.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertEqual(model.pendingCode, .pair("042917"))
        model.pendingCode = nil
        model.receive(link: URL(string: "aigyeot://pair?code=zz")!)
        XCTAssertNil(model.pendingCode)
        XCTAssertNotNil(model.linkError)
    }

    func test_직접_친_코드는_확인_없이_연결_요청() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        let f = await model.connect(typed: .pair("042917"))
        XCTAssertNil(f)
        XCTAssertNil(model.pendingCode)       // 확인 화면을 거치지 않았다
        XCTAssertTrue(model.isPaired)
    }

    func test_직접_친_코드의_오류는_호출한_화면에_돌려준다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 404, code: "code_invalid", retryAfter: nil))
        let f = await model.connect(typed: .pair("042917"))
        XCTAssertEqual(f, .codeInvalid)
        XCTAssertNil(model.pendingCode)
        XCTAssertFalse(model.isPaired)
    }

    func test_링크로_온_연결_코드는_확인_화면_제목이_차_화면() {
        make(paired: false)
        model.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertEqual(model.pendingCode?.confirmTitle, "차 화면에 이 코드가 보이나요?")
    }

    func test_링크로_온_초대_코드는_확인_화면_제목이_받은_초대_코드() {
        make(paired: false)
        model.receive(link: URL(string: "aigyeot://pair?code=7k3qm-2xw9d")!)
        XCTAssertEqual(model.pendingCode, .invite("7K3QM2XW9D"))
        XCTAssertEqual(model.pendingCode?.confirmTitle, "받은 초대 코드가 이것과 같나요?")
    }

    func test_이미_연결된_폰에_새_링크가_오면_바꿀지_묻는다() {
        model.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertNil(model.pendingCode)
        XCTAssertEqual(model.replaceCandidate, .pair("042917"))
    }

    func test_연결_끊기는_중계가_성공해야_지운다() async {
        let msg = await model.disconnect()
        XCTAssertNil(msg)
        XCTAssertTrue(relay.deleted)
        XCTAssertNil(creds.value)
        XCTAssertFalse(model.isPaired)
    }

    func test_401이면_토큰과_기록을_지우고_끊김_안내() async {
        relay.inboxError = .http(status: 401, code: "unauthorized", retryAfter: nil)
        await model.refresh()
        XCTAssertNil(creds.value)
        XCTAssertTrue(model.gone)
    }

    func test_저장이_거부된_실행의_401_초기화는_실패하고_녹음_파일을_지우지_않는다() async throws {
        let file = SongFiles.url(for: "refused-test")
        try Data("m4a".utf8).write(to: file)
        defer { SongFiles.remove("refused-test") }
        store.restoreFailed = true   // 복원에 실패해 원본을 보존하지 못한 실행
        store.writesRefused = true
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path), "init 직후")
        relay.inboxError = .http(status: 401, code: "unauthorized", retryAfter: nil)
        await model.refresh()
        XCTAssertTrue(model.gone)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    }

    // MARK: 수유

    func test_수유_시작은_누른_시각_그대로_보내고_반영되면_표시한다() async throws {
        model.startFeeding()
        let record = try XCTUnwrap(model.state.feedingLog.first)
        XCTAssertEqual(record.status, .sending)
        XCTAssertEqual(record.at, clock)
        await model.flushOutbox()
        XCTAssertEqual(relay.sent.map(\.type), ["feeding_started"])
        XCTAssertEqual(relay.sent.first?.id, record.id)   // 같은 id로 다시 보내므로 두 번 가도 한 번 처리된다
        XCTAssertTrue(model.state.outbox.isEmpty)
        XCTAssertEqual(model.state.feedingLog.first?.status, .sent)

        await model.ingest([feedingState(5, last: Rfc3339.string(clock))], live: true)
        XCTAssertEqual(model.state.feedingLog.first?.status, .reflected)
    }

    func test_중계가_고친_수유_시각을_보여_준다() async throws {
        model.startFeeding()
        let fixed = clock.addingTimeInterval(-30)
        relay.sendBody = .object(["at": .string(Rfc3339.string(fixed))])
        await model.flushOutbox()
        XCTAssertEqual(model.state.feedingLog.first?.fixedAt, fixed)
        XCTAssertEqual(model.lastFeedingStart, fixed)   // 화면의 '마지막 수유'도 고친 시각이다
        XCTAssertEqual(model.care.events.first?.startAt, CareTime.ms(clock))   // 기록의 누른 시각은 고치지 않는다(G33)
    }

    func test_오프라인에서_누른_것은_보낼_함에_남고_기다림_뒤_다시_보낸다() async {
        relay.sendError = .network
        model.startFeeding()
        await model.flushOutbox()
        XCTAssertEqual(model.state.outbox.count, 1)
        XCTAssertEqual(model.state.outbox[0].attempts, 1)
        XCTAssertEqual(model.state.feedingLog.first?.status, .sending)
        XCTAssertTrue(model.offline)
        // 1초 뒤 연결이 돌아오면 같은 id로 보낸다.
        relay.sendError = nil
        clock = clock.addingTimeInterval(2)
        await model.flushOutbox()
        XCTAssertTrue(model.state.outbox.isEmpty)
        XCTAssertEqual(relay.sent.count, 1)
    }

    func test_400은_버리고_알린다() async {
        relay.sendError = .http(status: 400, code: "bad_request", retryAfter: nil)
        model.startFeeding()
        await model.flushOutbox()
        XCTAssertTrue(model.state.outbox.isEmpty)
        XCTAssertNotNil(model.notice)
    }

    func test_feeding_state는_큰_seq가_이긴다() async {
        await model.ingest([feedingState(10, last: "2026-11-05T08:00:00Z")], live: true)
        await model.ingest([feedingState(5, last: "2026-11-05T06:00:00Z")], live: true)
        XCTAssertEqual(model.state.feeding.lastStart, Rfc3339.parse("2026-11-05T08:00:00Z"))
        XCTAssertEqual(model.state.feeding.seq, 10)
    }

    // MARK: 받기

    func test_같은_메시지는_한_번만_처리하고_ack만_한다() async {
        let e = feedingState(7, last: "2026-11-05T08:00:00Z")
        await model.ingest([e], live: true)
        await model.ingest([feedingState(7, last: "2026-11-05T03:00:00Z").with(id: e.id)], live: true)
        XCTAssertEqual(model.state.feeding.lastStart, Rfc3339.parse("2026-11-05T08:00:00Z"))
        XCTAssertEqual(relay.acked, [e.id, e.id])
    }

    func test_모르는_type과_판도_ack해서_받은_함이_막히지_않게_한다() async {
        var unknown = env("future_type", seq: 3, body: Empty())
        var v2 = env(MessageType.feedingState, seq: 4, body: FeedingStateBody(lastFeedingStart: nil, windowStartExpected: nil))
        v2.v = 2
        unknown.body = .object([:])
        await model.ingest([v2, unknown], live: false)
        XCTAssertEqual(relay.acked, [unknown.id, v2.id])
        XCTAssertEqual(model.state.feeding.seq, 0)
    }

    func test_받은_함은_seq_순서로_처리한다() async {
        let late = feedingState(9, last: "2026-11-05T09:00:00Z")
        let early = feedingState(2, last: "2026-11-05T02:00:00Z")
        await model.ingest([late, early], live: false)
        XCTAssertEqual(model.state.feeding.seq, 9)
        XCTAssertEqual(relay.acked, [early.id, late.id])
    }

    func test_차가_보낸_메시지를_받은_시각을_저장한다() async {
        await model.ingest([feedingState(1, last: nil)], live: false)
        XCTAssertEqual(model.state.lastReceivedAt, clock)
    }

    func test_차가_보낸_메시지를_받으면_차_타일이_바로_연결됨이_된다() async {
        store.value.devices = [DeviceInfo(deviceId: "d_car", role: .car, platform: .android, pairedAt: "2026-11-05T08:00:00Z",
                                          lastSeenAt: "2026-11-04T08:00:00Z", online: false, handoffAlerts: nil)]
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        model.autoFlush = false
        await model.ingest([feedingState(1, last: nil)], live: false)   // 받은 함에서 꺼낸 것은 바꾸지 않는다
        XCTAssertEqual(model.car?.online, false)
        await model.ingest([feedingState(2, last: nil)], live: true)
        XCTAssertEqual(model.car?.online, true)
        XCTAssertEqual(model.car?.lastSeenAt.flatMap(Rfc3339.parse), clock)
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
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        model.clearLocalRecords()
        XCTAssertTrue(fm.fileExists(atPath: stateCopy.path))
        XCTAssertTrue(fm.fileExists(atPath: songsCopy.path))
        // 정상 실행: 둘 다 지운다.
        store.writesRefused = false
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        model.clearLocalRecords()
        XCTAssertFalse(fm.fileExists(atPath: stateCopy.path))
        XCTAssertFalse(fm.fileExists(atPath: songsCopy.path))
    }

    // MARK: 노래

    func test_거절된_곡은_같은_id로_song_removed를_자동으로_보낸다() async throws {
        let id = "01SONG"
        let rejected = SongStatusBody(songId: id, name: "나비야", status: .rejected, reason: .peakLimit, durationS: nil, candidate: false)
        await model.ingest([env(MessageType.songStatus, seq: 5, body: rejected)], live: true)
        guard case .rejected(.peakLimit) = model.state.songs.first?.state else { return XCTFail("거절 표시") }
        XCTAssertEqual(model.state.outbox.map(\.type), ["song_removed"])
        XCTAssertEqual(try model.state.outbox[0].body.decode(SongRefBody.self).songId, id)
        // 같은 거절이 다른 id로 다시 와도 두 번 만들지 않는다.
        await model.ingest([env(MessageType.songStatus, seq: 6, body: rejected)], live: true)
        XCTAssertEqual(model.state.outbox.count, 1)
    }

    func test_지운_곡은_늦게_온_accepted로_되살리지_않는다() async throws {
        let id = "01SONG"
        let acc = SongStatusBody(songId: id, name: "나비야", status: .accepted, reason: nil, durationS: 30, candidate: true)
        await model.ingest([env(MessageType.songStatus, seq: 5, body: acc)], live: true)
        XCTAssertEqual(model.visibleSongs.count, 1)
        model.removeSong(id)
        XCTAssertTrue(model.visibleSongs.isEmpty)
        await model.ingest([env(MessageType.songStatus, seq: 6, body: acc)], live: true)
        XCTAssertTrue(model.visibleSongs.isEmpty)
    }

    func test_곡_상태는_곡마다_큰_seq가_이긴다() async {
        let id = "01SONG"
        let acc = SongStatusBody(songId: id, name: "나비야", status: .accepted, reason: nil, durationS: 30, candidate: true)
        let rej = SongStatusBody(songId: id, name: "나비야", status: .rejected, reason: .silent, durationS: nil, candidate: false)
        await model.ingest([env(MessageType.songStatus, seq: 8, body: acc)], live: true)
        await model.ingest([env(MessageType.songStatus, seq: 7, body: rej)], live: true)
        XCTAssertEqual(model.state.songs.first?.state, .accepted)
        XCTAssertEqual(model.state.songs.first?.candidate, true)
    }

    func test_노래는_3곡까지이고_이름은_1에서_30자() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 0, count: 100).write(to: file)
        XCTAssertThrowsError(try model.addSong(name: "  ", recording: file, durationS: 10)) { XCTAssertEqual($0 as? SongError, .badName) }
        XCTAssertThrowsError(try model.addSong(name: "가", recording: file, durationS: 181)) { XCTAssertEqual($0 as? SongError, .tooLong) }
        for i in 0..<3 { try model.addSong(name: "곡\(i)", recording: file, durationS: 10) }
        XCTAssertThrowsError(try model.addSong(name: "넷", recording: file, durationS: 10)) { XCTAssertEqual($0 as? SongError, .limitReached) }
        model.visibleSongs.forEach { model.removeSong($0.id) }
    }

    func test_노래는_파일을_먼저_올리고_song_added를_보낸다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try model.addSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(model.state.songs.first?.id)
        XCTAssertEqual(model.state.songs.first?.state, .uploading)
        await model.flushOutbox()
        XCTAssertEqual(relay.uploaded, [id])
        XCTAssertEqual(relay.sent.map(\.type), ["song_added"])
        XCTAssertEqual(model.state.songs.first?.state, .waiting)
        model.removeSong(id)
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
        await model.ingest([env(MessageType.reportSummary, seq: 10, body: report("S1"))], live: false)
        await model.ingest([env(MessageType.reportSummary, seq: 8, body: r2)], live: false)
        XCTAssertEqual(model.state.reports.count, 1)
        XCTAssertFalse(model.state.reports[0].body.simulated)
        await model.ingest([env(MessageType.reportSummary, seq: 11, body: r2)], live: false)
        XCTAssertEqual(model.state.reports.count, 1)
        XCTAssertTrue(model.state.reports[0].body.simulated)
    }

    func test_알림_열기는_본문이_나중에_저장돼도_이어서_연다() async {
        make(role: .guardian)
        await model.openAlert("M9")                  // 본문이 아직 없다
        XCTAssertNil(model.openAlertId)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        await model.ingest([env(MessageType.handoffAlert, seq: 1, body: body, id: "M9")], live: true)
        XCTAssertEqual(model.openAlertId, "M9")
        XCTAssertNil(model.pendingOpenAlertId)
    }

    func test_다른_알림을_직접_열면_대기하던_알림_열기는_버린다() async {
        make(role: .guardian)
        await model.openAlert("M9")
        model.userOpenedAlert("M8")                  // 사용자가 목록에서 다른 알림을 열었다
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        await model.ingest([env(MessageType.handoffAlert, seq: 1, body: body, id: "M9")], live: true)
        XCTAssertNil(model.openAlertId)
    }

    func test_인계_알림은_본문을_저장하고_배너는_한_번만() async {
        make(role: .guardian)
        let body = HandoffAlertBody(text: "아기가 계속 울어요. 7분 뒤 도착해요.", kind: .arrivalNotice, restName: nil,
                                    minutesToArrival: 7, occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        let live = env(MessageType.handoffAlert, seq: 4, body: body)
        await model.ingest([live], live: true)
        XCTAssertEqual(model.bannerAlertId, live.id)
        XCTAssertEqual(model.alert(live.id)?.body.text, body.text)
        model.bannerAlertId = nil
        model.show(live.id)            // 푸시 willPresent가 같은 id로 불러도
        XCTAssertNil(model.bannerAlertId)   // 다시 띄우지 않는다

        // 앱을 열며 받은 함에서 꺼낸 것은 배너 없이 목록에만 넣는다.
        let backlog = env(MessageType.handoffAlert, seq: 5, body: body)
        await model.ingest([backlog], live: false)
        XCTAssertNil(model.bannerAlertId)
        XCTAssertNotNil(model.alert(backlog.id))
    }

    func test_푸시가_먼저_와서_띄운_뒤에도_본문은_저장한다() async {
        make(role: .guardian)
        model.show("M1")                       // 푸시가 먼저: 표시만
        XCTAssertNil(model.state.processed["M1"])   // 본문 저장 기록에는 넣지 않는다(5.6)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        model.bannerAlertId = nil
        await model.ingest([env(MessageType.handoffAlert, seq: 1, body: body, id: "M1")], live: true)
        XCTAssertNil(model.bannerAlertId)
        XCTAssertNotNil(model.alert("M1"))
        XCTAssertNotNil(model.state.processed["M1"])
    }

    // MARK: 상태 요청(G39)

    func test_24시간_넘게_못_받았으면_상태_요청을_한_번_보낸다() async {
        store.value.lastReceivedAt = clock.addingTimeInterval(-25 * 3600)
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        await model.refresh()
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
        XCTAssertFalse(model.state.needsStateRequest)
        await model.refresh()
        XCTAssertEqual(relay.sent.count, 1)
    }

    func test_연결_뒤_차_메시지를_못_받아도_24시간이_지나면_상태_요청을_보낸다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "t", role: .owner))
        _ = await model.pair(.pair("042917"))
        await model.settle()
        relay.sent = []
        clock = clock.addingTimeInterval(25 * 3600)
        await model.refresh()
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
    }

    func test_상태_요청은_10분_안에_다시_보내지_않고_201로만_표시를_끈다() async {
        model.clearLocalRecords()   // 표시를 켜고 바로 한 번 보낸다(7.4)
        await model.settle()
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
        XCTAssertFalse(model.state.needsStateRequest)
        model.clearLocalRecords()   // 1분도 안 지났으니 표시만 켜고 보내지 않는다
        await model.settle()
        XCTAssertEqual(relay.sent.count, 1)
        XCTAssertTrue(model.state.needsStateRequest)
        clock = clock.addingTimeInterval(11 * 60)
        relay.sendError = .network          // 오프라인이면 표시를 그대로 둔다
        await model.sendStateRequestIfNeeded()
        XCTAssertTrue(model.state.needsStateRequest)
        XCTAssertNotNil(model.state.lastStateRequestAt)   // 실패해도 시각은 보내기 전에 남는다
        relay.sendError = nil
        clock = clock.addingTimeInterval(11 * 60)
        await model.sendStateRequestIfNeeded()
        XCTAssertFalse(model.state.needsStateRequest)
        XCTAssertEqual(relay.sent.count, 2)
    }

    func test_상태_요청이_네트워크_오류로_실패해도_10분_안에는_다시_보내지_않는다() async {
        model.clearLocalRecords()
        relay.sendError = .network
        await model.sendStateRequestIfNeeded()
        XCTAssertNotNil(model.state.lastStateRequestAt)
        XCTAssertTrue(model.state.needsStateRequest)
        relay.sendError = nil
        clock = clock.addingTimeInterval(5 * 60)
        await model.sendStateRequestIfNeeded()
        XCTAssertTrue(relay.sent.isEmpty)
    }

    func test_연결이_진행_중이면_다른_연결_요청은_busy이고_자격은_처음_것_하나다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_A", deviceId: "d_A", deviceToken: "at1.c_A.d_A.x", role: .owner))
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        relay.pairHook = { [unowned self] in if self.relay.pairCalls == 1 { entered = true; await withCheckedContinuation { gate = $0 } } }
        let a = Task { await self.model.pair(.pair("042917")) }
        while !entered { await Task.yield() }
        XCTAssertEqual(relay.pairCalls, 1)
        let b = await model.pair(.pair("111111"))
        XCTAssertEqual(b, .busy)
        XCTAssertEqual(relay.pairCalls, 1)
        gate?.resume()
        let ra = await a.value
        XCTAssertNil(ra)
        XCTAssertEqual(model.state.carId, "c_A")
        XCTAssertEqual(creds.value?.carId, "c_A")
    }

    func test_guardian은_상태_요청을_보내지_않는다() async {
        make(role: .guardian)
        model.clearLocalRecords()
        await model.sendStateRequestIfNeeded()
        XCTAssertTrue(relay.sent.isEmpty)
    }

    // MARK: 검토 반영

    func test_저장에_실패하면_ack하지_않고_상태도_바꾸지_않는다() async {
        store.failing = true
        let e = feedingState(5, last: "2026-11-05T08:00:00Z")
        await model.ingest([e], live: true)
        XCTAssertTrue(relay.acked.isEmpty)
        XCTAssertNil(model.state.processed[e.id])
        XCTAssertEqual(model.state.feeding.seq, 0)
        store.failing = false
        await model.ingest([e], live: true)   // 중계가 다시 주면 그때 처리하고 ack한다
        XCTAssertEqual(relay.acked, [e.id])
        XCTAssertEqual(model.state.feeding.seq, 5)
    }

    func test_수유_시작은_기록과_보낼_함을_한_번에_저장한다() {
        store.failing = true
        model.startFeeding()
        XCTAssertTrue(model.state.feedingLog.isEmpty)
        XCTAssertTrue(model.state.outbox.isEmpty)
        store.failing = false
        model.startFeeding()
        XCTAssertEqual(store.value.feedingLog.count, 1)
        XCTAssertEqual(store.value.outbox.map(\.id), store.value.feedingLog.map(\.id))
    }

    func test_곡_삭제는_저장이_성공한_뒤에만_파일을_지운다() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try model.addSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(model.state.songs.first?.id)
        XCTAssertEqual(store.value.outbox.count, 1)   // 곡과 song_added가 같이 저장됐다
        store.failing = true
        model.removeSong(id)
        XCTAssertTrue(FileManager.default.fileExists(atPath: SongFiles.url(for: id).path))
        XCTAssertEqual(model.state.songs.first?.state, .uploading)
        store.failing = false
        model.removeSong(id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: SongFiles.url(for: id).path))
        XCTAssertEqual(store.value.outbox.map(\.type), ["song_added", "song_removed"])
        XCTAssertNotNil(store.value.songs.first?.removedAt)
    }

    func test_삭제_표시는_삭제한_시각부터_7일_뒤에_지운다() {
        let old = clock.addingTimeInterval(-30 * 86400)
        store.value.songs = [SongRow(id: "a", name: "a", durationS: 1, state: .removed, addedAt: old, removedAt: clock.addingTimeInterval(-86400)),
                             SongRow(id: "b", name: "b", durationS: 1, state: .removed, addedAt: old, removedAt: old)]
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        XCTAssertEqual(model.state.songs.map(\.id), ["a"])   // 추가는 오래전이어도 어제 지웠으면 남는다
    }

    func test_연결_직후_앱이_앞에_있으면_받기를_시작한다() async {
        make(paired: false)
        model.didBecomeActive()   // 연결 전에는 아무것도 하지 않는다
        await model.settle()
        XCTAssertEqual(relay.inboxCalls, 0)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "t", role: .owner))
        _ = await model.pair(.pair("042917"))
        await model.settle()
        XCTAssertEqual(relay.inboxCalls, 1)
    }

    func test_다른_차의_메시지는_적용도_ack도_하지_않는다() async {
        var e = feedingState(5, last: "2026-11-05T08:00:00Z")
        e.car = "c_OTHER"
        await model.ingest([e], live: true)
        XCTAssertEqual(model.state.feeding.seq, 0)
        XCTAssertTrue(relay.acked.isEmpty)
    }

    func test_연결이_바뀐_뒤_도착한_이전_응답은_버린다() async {
        let old = feedingState(9, last: "2026-11-05T08:00:00Z")
        relay.inboxPages = [InboxResponse(messages: [old], more: false)]
        relay.inboxHook = { [unowned self] in
            // 응답을 기다리는 사이 401로 끊기고 다른 차에 새로 연결된다.
            self.model.handleUnauthorized()
            self.relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
            _ = await self.model.pair(.pair("042917"))
        }
        await model.refresh()
        XCTAssertEqual(model.state.carId, "c_2")
        XCTAssertEqual(model.state.feeding.seq, 0)
        XCTAssertTrue(relay.acked.isEmpty)
        XCTAssertFalse(model.gone)
    }

    func test_필수_nullable_키가_빠진_body는_읽지_못한다() async {
        XCTAssertThrowsError(try JSONValue.object([:]).decode(FeedingStateBody.self))
        XCTAssertNoThrow(try JSONValue.object(["last_feeding_start": .null, "window_start_expected": .null]).decode(FeedingStateBody.self))
        await model.ingest([feedingState(3, last: "2026-11-05T08:00:00Z")], live: true)
        var empty = feedingState(4, last: nil)
        empty.body = .object([:])
        await model.ingest([empty], live: true)
        XCTAssertEqual(model.state.feeding.seq, 3)   // 빈 body가 수유 값을 지우지 못한다
        XCTAssertNotNil(model.state.feeding.lastStart)
    }

    func test_오래된_인계_알림은_실시간으로_와도_배너를_띄우지_않는다() async {
        make(role: .guardian)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock.addingTimeInterval(-3 * 3600)), sessionId: "S", episodeId: "E")
        await model.ingest([env(MessageType.handoffAlert, seq: 1, body: body)], live: true)
        XCTAssertNil(model.bannerAlertId)
        XCTAssertEqual(model.state.alerts.count, 1)
    }

    func test_상태_요청을_동시에_불러도_한_번만_보낸다() async {
        store.value.needsStateRequest = true
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        model.autoFlush = false
        // 첫 요청의 응답 시점을 continuation으로 정한다.
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        relay.sendHook = { entered = true; await withCheckedContinuation { gate = $0 } }
        let first = Task { await self.model.sendStateRequestIfNeeded() }
        while !entered { await Task.yield() }                 // 첫 요청이 응답을 기다리는 중
        await model.sendStateRequestIfNeeded()                 // 두 번째는 '요청 중'이라 보내지 않는다
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
        model.didBecomeActive()
        while !entered { await Task.yield() }
        model.didBecomeActive()                 // 이미 갱신 중: 새 갱신을 시작하지 않는다
        gate?.resume()
        relay.inboxHook = nil
        await model.settle()
        XCTAssertEqual(relay.inboxCalls, 1)
    }

    func test_끊기_실패는_안내하고_새_코드_확인으로_넘어가지_않는다() async {
        relay.deleteError = .network
        await model.confirmReplace(code: .pair("042917"))
        XCTAssertEqual(model.notice, "인터넷이 필요해요")
        XCTAssertNil(model.pendingCode)
        XCTAssertTrue(model.isPaired)
    }

    func test_연결_429는_Retry_After를_넘긴다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        let f = await model.pair(.pair("042917"))
        XCTAssertEqual(f, .rateLimited(30))
        XCTAssertTrue(f!.message.contains("30초"))
    }

    func test_연결_429_뒤_잠금_중에는_직접_친_코드도_요청하지_않는다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        _ = await model.connect(typed: .pair("042917"))
        XCTAssertTrue(model.isPairLocked)
        XCTAssertEqual(relay.pairCalls, 1)
        let f = await model.connect(typed: .pair("111111"))     // 코드를 고쳐도(자동 제출) 잠금은 풀리지 않는다
        XCTAssertEqual(f, .rateLimited(30))
        XCTAssertEqual(relay.pairCalls, 1)                      // 두 번째는 중계까지 가지 않았다
        let g = await model.pair(.pair("042917"))               // 확인 화면 길도 같은 잠금을 쓴다
        XCTAssertEqual(g, .rateLimited(30))
        XCTAssertEqual(relay.pairCalls, 1)
    }

    func test_잠금은_RetryAfter가_지나면_풀린다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        _ = await model.connect(typed: .pair("042917"))
        clock = clock.addingTimeInterval(31)
        XCTAssertFalse(model.isPairLocked)
        relay.pairResult = .failure(.http(status: 404, code: "code_invalid", retryAfter: nil))
        let f = await model.connect(typed: .pair("042917"))
        XCTAssertEqual(f, .codeInvalid)
        XCTAssertEqual(relay.pairCalls, 2)
    }

    func test_잠금_길이는_RetryAfter_없으면_10초_1초_미만은_1초() {
        XCTAssertEqual(AppModel.pairLockSeconds(nil), 10)
        XCTAssertEqual(AppModel.pairLockSeconds(0), 1)
        XCTAssertEqual(AppModel.pairLockSeconds(30), 30)
    }

    func test_직접_친_코드의_요청_중에_QR_링크가_와도_확인_화면으로_옮기지_않는다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        relay.pairHook = { [unowned self] in self.model.propose(.pair("111111")) }   // 응답이 늦는 사이 QR을 읽었다
        let f = await model.connect(typed: .pair("042917"))
        XCTAssertNil(f)
        XCTAssertNil(model.pendingCode)
        XCTAssertNotNil(model.linkError)
        XCTAssertTrue(model.isPaired)
    }

    // MARK: 2회차 검토 반영

    func test_초기화_저장이_실패하면_새_자격_증명을_켜지_않는다() async {
        make(paired: false)
        store.value.outbox = [OutboxItem(id: "old", type: "feeding_started", body: .object([:]), ts: "t", createdAt: clock, nextTryAt: clock)]
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
        store.failing = true
        let f = await model.pair(.pair("042917"))
        XCTAssertEqual(f, .other)
        XCTAssertNil(creds.value)
        XCTAssertFalse(model.isPaired)
        store.failing = false
        let retry = await model.pair(.pair("042917"))
        XCTAssertNil(retry)
        XCTAssertTrue(model.state.outbox.isEmpty)   // 이전 차의 보낼 함은 새 차로 가지 않는다
    }

    func test_실시간으로_거절된_곡의_자동_삭제는_바로_전송된다() async throws {
        model.autoFlush = true
        let rejected = SongStatusBody(songId: "01SONG", name: "나비야", status: .rejected, reason: .peakLimit, durationS: nil, candidate: false)
        await model.ingest([env(MessageType.songStatus, seq: 5, body: rejected)], live: true)
        await model.settle()
        XCTAssertEqual(relay.sent.map(\.type), ["song_removed"])
        XCTAssertTrue(model.state.outbox.isEmpty)
    }

    func test_보낼_함_갱신_저장이_실패하면_같은_요청을_되풀이하지_않는다() async {
        model.startFeeding()
        relay.sendHook = { [unowned self] in self.store.failing = true }   // 전송 뒤 결과 저장만 실패한다
        await model.flushOutbox()
        relay.sendHook = nil
        XCTAssertEqual(relay.sent.count, 1)          // 한 번 보내고 멈춘다
        XCTAssertEqual(model.state.outbox.count, 1)
        store.failing = false
        clock = clock.addingTimeInterval(6)
        await model.flushOutbox()                    // 저장을 다시 시도해 이어 간다(같은 id라 한 번만 처리된다)
        XCTAssertTrue(model.state.outbox.isEmpty)
        XCTAssertEqual(Set(relay.sent.map(\.id)).count, 1)
    }

    func test_앞_항목을_보내는_사이_만료된_항목은_보내지_않고_만료_처리한다() async {
        model.startFeeding()
        clock = clock.addingTimeInterval(10)
        model.startFeeding()
        let second = model.state.outbox[1]
        XCTAssertEqual(model.state.outbox.count, 2)   // 둘째가 아직 보낼 함에 있다
        relay.sendHook = { [unowned self] in self.clock = second.createdAt.addingTimeInterval(OutboxPolicy.maxAge + 1) }
        await model.flushOutbox()
        relay.sendHook = nil
        XCTAssertEqual(relay.sent.count, 1)           // 첫 항목만 보냈다
        XCTAssertFalse(relay.sent.contains { $0.id == second.id })
        XCTAssertTrue(model.state.outbox.isEmpty)
        XCTAssertEqual(model.state.feedingLog.first { $0.id == second.id }?.status, .expired)
    }

    func test_시작할_때_지움_상태이거나_목록에_없는_곡_파일을_정리한다() throws {
        let keep = ULID.generate(), gone = ULID.generate(), orphan = ULID.generate()
        for id in [keep, gone, orphan] { try Data(repeating: 1, count: 10).write(to: SongFiles.url(for: id)) }
        store.value.songs = [SongRow(id: keep, name: "k", durationS: 1, state: .accepted, addedAt: clock),
                             SongRow(id: gone, name: "g", durationS: 1, state: .removed, addedAt: clock, removedAt: clock)]
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        XCTAssertTrue(FileManager.default.fileExists(atPath: SongFiles.url(for: keep).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: SongFiles.url(for: gone).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: SongFiles.url(for: orphan).path))
        SongFiles.remove(keep)
    }

    // MARK: 3회차 검토 반영

    func test_결과_저장에_실패하면_표시도_보낼_함도_그대로_두었다가_함께_바꾼다() async {
        model.startFeeding()
        relay.sendHook = { [unowned self] in self.store.failing = true }   // 전송 뒤 결과 저장만 실패한다
        await model.flushOutbox()
        relay.sendHook = nil
        XCTAssertEqual(model.state.feedingLog.first?.status, .sending)   // 결과 표시와 지우기는 함께 저장된다
        XCTAssertEqual(model.state.outbox.count, 1)
        store.failing = false
        clock = clock.addingTimeInterval(6)
        await model.flushOutbox()
        XCTAssertEqual(model.state.feedingLog.first?.status, .sent)
        XCTAssertTrue(model.state.outbox.isEmpty)
    }

    func test_노래를_올리는_사이_기록을_지우면_노래_추가를_보내지_않는다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try model.addSong(name: "나비야", recording: file, durationS: 12)
        XCTAssertEqual(model.state.outbox.count, 1)
        relay.putHook = { [unowned self] in self.model.clearLocalRecords() }   // 업로드를 기다리는 사이 기록을 지운다
        await model.flushOutbox()
        relay.putHook = nil
        XCTAssertFalse(relay.sent.contains { $0.type == "song_added" })
        XCTAssertTrue(model.state.outbox.filter { $0.songId != nil }.isEmpty)
        XCTAssertTrue(model.state.songs.isEmpty)
    }

    func test_버려지는_전송도_실패_표시와_지우기를_함께_저장한다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try model.addSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(model.state.songs.first?.id)
        relay.sendError = .http(status: 415, code: "unsupported_media", retryAfter: nil)
        relay.sendHook = { [unowned self] in self.store.failing = true }   // 전송 뒤 결과 저장만 실패한다
        await model.flushOutbox()
        relay.sendHook = nil
        XCTAssertEqual(model.state.songs.first?.state, .uploading)
        XCTAssertEqual(model.state.outbox.count, 1)
        store.failing = false
        clock = clock.addingTimeInterval(6)
        await model.flushOutbox()
        XCTAssertEqual(model.state.songs.first?.state, .failed("녹음 파일을 읽지 못했어요. 다시 녹음해 주세요."))
        XCTAssertTrue(model.state.outbox.isEmpty)
        model.removeSong(id)
    }

    func test_상태_요청을_기다리는_사이_기록을_지우면_요청_표시를_남긴다() async {
        model.clearLocalRecords()   // 표시를 켜고 한 번 보낸다
        await model.settle()
        model.clearLocalRecords()   // 10분 안이라 표시만 켠다
        await model.settle()
        clock = clock.addingTimeInterval(11 * 60)
        XCTAssertTrue(model.state.needsStateRequest)
        relay.sendHook = { [unowned self] in await self.model.clearLocalRecords() }   // 응답을 기다리는 사이 기록을 지운다
        await model.sendStateRequestIfNeeded()
        relay.sendHook = nil
        await model.settle()
        XCTAssertTrue(model.state.needsStateRequest)          // 뒤늦은 201이 새 요청 표시를 끄지 못한다
        clock = clock.addingTimeInterval(11 * 60)             // 10분이 지나면 다시 요청한다
        await model.sendStateRequestIfNeeded()
        XCTAssertFalse(model.state.needsStateRequest)
    }

    func test_Keychain_저장에_실패하면_연결로_치지_않는다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
        creds.failing = true
        let f = await model.pair(.pair("042917"))
        XCTAssertEqual(f, .other)
        XCTAssertFalse(model.isPaired)
        XCTAssertNil(model.state.carId)
        XCTAssertNil(model.notice)   // "연결됐어요"를 띄우지 않는다
        creds.failing = false
        let retry = await model.pair(.pair("042917"))
        XCTAssertNil(retry)
        XCTAssertTrue(model.isPaired)
    }

    // MARK: 4회차 검토 반영

    func test_7일_만료된_전송은_진행_중_표시를_만료로_바꾼다() throws {
        let old = clock.addingTimeInterval(-8 * 86400)
        store.value.feedingLog = [FeedingRecord(id: "F1", at: old)]
        store.value.songs = [SongRow(id: "S1", name: "나비야", durationS: 5, state: .uploading, addedAt: old)]
        store.value.outbox = [
            OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: old, nextTryAt: old),
            OutboxItem(id: "M1", type: MessageType.songAdded, body: .object([:]), ts: "t", songId: "S1", createdAt: old, nextTryAt: old)]
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        XCTAssertTrue(model.state.outbox.isEmpty)
        XCTAssertEqual(model.state.feedingLog.first?.status, .expired)
        guard case .failed = model.state.songs.first?.state else { return XCTFail("곡은 만료 표시") }
        XCTAssertEqual(store.value.feedingLog.first?.status, .expired)   // 같은 저장에 들어갔다
    }

    func test_feeding_state가_응답보다_먼저_와도_고친_시각으로_반영_표시한다() async throws {
        model.startFeeding()
        let fixed = clock.addingTimeInterval(-30)   // 중계가 미래 시각을 고쳤다고 하자
        // 차의 feeding_state가 HTTP 응답보다 먼저 도착한다.
        await model.ingest([feedingState(7, last: Rfc3339.string(fixed))], live: true)
        XCTAssertEqual(model.state.feedingLog.first?.status, .sending)
        relay.sendBody = .object(["at": .string(Rfc3339.string(fixed))])
        await model.flushOutbox()
        XCTAssertEqual(model.state.feedingLog.first?.status, .reflected)
    }

    // MARK: 5회차 검토 반영

    func test_만료_정리_저장이_실패하면_만료된_항목을_보내지_않는다() async {
        let old = clock.addingTimeInterval(-8 * 86400)
        store.value.outbox = [OutboxItem(id: "F3", type: MessageType.feedingStarted, body: .object(["at": .string("x")]), ts: "t",
                                         createdAt: old, nextTryAt: old)]
        store.failing = true
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })   // 시작 정리가 저장에 실패해 항목이 남는다
        XCTAssertEqual(model.state.outbox.count, 1)
        await model.flushOutbox()
        XCTAssertTrue(relay.sent.isEmpty)            // 만료된 항목은 보내지 않는다
        XCTAssertEqual(model.state.outbox.count, 1)
        store.failing = false
        clock = clock.addingTimeInterval(6)
        await model.flushOutbox()
        XCTAssertTrue(relay.sent.isEmpty)
        XCTAssertTrue(model.state.outbox.isEmpty)    // 저장이 되면 지운다
    }

    // MARK: 6회차 검토 반영

    func test_버려진_수유_전송은_실패_표시와_지우기를_함께_저장한다() async {
        model.startFeeding()
        relay.sendError = .http(status: 409, code: "id_conflict", retryAfter: nil)
        await model.flushOutbox()
        XCTAssertEqual(model.state.feedingLog.first?.status, .failed)
        XCTAssertTrue(model.state.outbox.isEmpty)
        XCTAssertEqual(store.value.feedingLog.first?.status, .failed)   // 저장된 값에도 반영
        XCTAssertNotNil(model.notice)
    }

    // MARK: 7회차 검토 반영

    func test_바꾸기_확인은_알림창이_후보를_지운_뒤에도_새_연결로_이어진다() async {
        model.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        let code = model.replaceCandidate!      // 버튼의 동기 클로저에서 복사한다
        model.replaceCandidate = nil            // 알림창이 닫히며 바인딩이 지운다
        await model.confirmReplace(code: code)
        XCTAssertTrue(relay.deleted)
        XCTAssertEqual(model.pendingCode, .pair("042917"))
        XCTAssertFalse(model.isPaired)
    }

    func test_노래_삭제와_선택이_버려지면_실패를_알린다() async {
        let acc = SongStatusBody(songId: "01SONG", name: "나비야", status: .accepted, reason: nil, durationS: 30, candidate: false)
        await model.ingest([env(MessageType.songStatus, seq: 5, body: acc)], live: true)
        relay.sendError = .http(status: 403, code: "forbidden_role", retryAfter: nil)
        model.selectSong("01SONG")
        await model.flushOutbox()
        XCTAssertEqual(model.notice, "실험에 쓸 노래 선택을 차로 보내지 못했어요.")
        model.notice = nil
        model.removeSong("01SONG")
        await model.flushOutbox()
        XCTAssertTrue(model.notice?.contains("노래 삭제") == true)
        XCTAssertTrue(model.state.outbox.isEmpty)
    }


    // MARK: 11회차 검토 반영

    private func device(_ id: String, _ role: Role = .guardian) -> DeviceInfo {
        DeviceInfo(deviceId: id, role: role, platform: .ios, pairedAt: "2026-11-05T08:00:00Z", lastSeenAt: nil, online: false, handoffAlerts: true)
    }

    func test_오래된_기기_목록이_그_뒤에_받은_끊김을_덮지_못한다() async {
        let paired = PairedBody(deviceId: "d_8", role: .guardian, platform: .ios, pairedAt: "2026-11-05T08:00:00Z", handoffAlerts: true)
        await model.ingest([env(MessageType.paired, seq: 90, body: paired, from: .relay)], live: true)
        XCTAssertEqual(model.state.devices.map(\.deviceId), ["d_8"])
        relay.devicesResult = DevicesResponse(snapshotSeq: 100, devices: [device("d_8")])   // 끊기기 전에 읽은 목록
        relay.devicesHook = { [unowned self] in
            // 응답을 기다리는 사이 소켓으로 unpaired(101)를 저장·ack한다.
            await self.model.ingest([self.env(MessageType.unpaired, seq: 101, body: UnpairedBody(deviceId: "d_8", role: .guardian, by: .car), from: .relay)], live: true)
        }
        await model.refreshDevices()
        XCTAssertTrue(model.state.devices.isEmpty)
    }

    func test_스냅숏_뒤에_붙은_기기는_목록에_없어도_남는다() async {
        relay.devicesResult = DevicesResponse(snapshotSeq: 100, devices: [])
        relay.devicesHook = { [unowned self] in
            let p = PairedBody(deviceId: "d_new", role: .owner, platform: .android, pairedAt: "2026-11-05T09:00:00Z", handoffAlerts: false)
            await self.model.ingest([self.env(MessageType.paired, seq: 101, body: p, from: .relay)], live: true)
        }
        await model.refreshDevices()
        XCTAssertEqual(model.state.devices.map(\.deviceId), ["d_new"])
        // 스냅숏이 더 새로우면 목록이 이긴다.
        relay.devicesHook = nil
        relay.devicesResult = DevicesResponse(snapshotSeq: 200, devices: [])
        await model.refreshDevices()
        XCTAssertTrue(model.state.devices.isEmpty)
    }

    func test_끊긴_뒤_늦게_온_paired는_무시한다() async {
        let p = PairedBody(deviceId: "d_8", role: .guardian, platform: .ios, pairedAt: "2026-11-05T08:00:00Z", handoffAlerts: true)
        await model.ingest([env(MessageType.unpaired, seq: 101, body: UnpairedBody(deviceId: "d_8", role: .guardian, by: .car), from: .relay)], live: true)
        await model.ingest([env(MessageType.paired, seq: 90, body: p, from: .relay)], live: true)
        XCTAssertTrue(model.state.devices.isEmpty)
    }


    // MARK: 12회차 검토 반영

    func test_더_새로운_목록을_적용한_뒤_오래된_목록_응답은_버린다() async {
        let paired = PairedBody(deviceId: "d_8", role: .guardian, platform: .ios, pairedAt: "2026-11-05T08:00:00Z", handoffAlerts: true)
        await model.ingest([env(MessageType.paired, seq: 90, body: paired, from: .relay)], live: true)
        relay.devicesResult = DevicesResponse(snapshotSeq: 200, devices: [])          // 끊긴 뒤의 목록
        await model.refreshDevices()
        XCTAssertTrue(model.state.devices.isEmpty)
        XCTAssertEqual(store.value.devicesSnapshotSeq, 200)                           // 목록과 함께 저장됐다
        relay.devicesResult = DevicesResponse(snapshotSeq: 100, devices: [device("d_8")])   // 겹친 갱신의 낡은 응답
        await model.refreshDevices()
        XCTAssertTrue(model.state.devices.isEmpty)
        XCTAssertEqual(model.state.devicesSnapshotSeq, 200)
    }

    func test_스냅숏_이하_seq의_연결_이벤트는_무시한다() async {
        relay.devicesResult = DevicesResponse(snapshotSeq: 200, devices: [])
        await model.refreshDevices()
        let p = PairedBody(deviceId: "d_x", role: .guardian, platform: .ios, pairedAt: "2026-11-05T08:00:00Z", handoffAlerts: true)
        await model.ingest([env(MessageType.paired, seq: 150, body: p, from: .relay)], live: true)
        XCTAssertTrue(model.state.devices.isEmpty)
        await model.ingest([env(MessageType.paired, seq: 201, body: p, from: .relay)], live: true)   // 스냅숏보다 새로운 것은 적용한다
        XCTAssertEqual(model.state.devices.map(\.deviceId), ["d_x"])
    }


    // MARK: 13회차 검토 반영

    private func tempDir() -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("state-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
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
        let m = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
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
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        model.autoFlush = false
        store.failing = true
        await model.refresh()
        XCTAssertEqual(relay.inboxCalls, 0)         // 받은 함을 시작하지 않는다
        let e = feedingState(5, last: "2026-11-05T08:00:00Z")
        await model.ingest([e], live: true)         // 소켓 프레임도 적용·ack하지 않는다
        XCTAssertTrue(relay.acked.isEmpty)
        XCTAssertEqual(model.state.feeding.seq, 0)
        XCTAssertEqual(model.state.lastReceivedAt, clock.addingTimeInterval(-25 * 3600))   // 재요청 조건이 그대로다
        store.failing = false
        await model.refresh()                       // 저장이 되면 이어 간다
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
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        model.autoFlush = false
        XCTAssertTrue(model.state.needsStateRequest)
        XCTAssertEqual(model.state.carId, "c_1")
        relay.devicesError = .network                      // 기기 목록 조회가 실패하면 요청하지 않는다
        await model.refresh()
        XCTAssertTrue(relay.sent.isEmpty)
        relay.devicesError = nil
        await model.refresh()                              // 성공한 뒤에 요청한다
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
        XCTAssertFalse(model.state.needsStateRequest)
    }

    func test_소켓이_다시_붙으면_남은_요청_표시로_기기_목록_뒤에_상태를_요청한다() async {
        store.restoreFailed = true
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        model.autoFlush = false
        relay.devicesError = .network
        await model.refresh()                              // 조회가 실패해 표시가 남는다
        XCTAssertTrue(model.state.needsStateRequest)
        XCTAssertTrue(relay.sent.isEmpty)
        relay.devicesError = nil
        model.socketDidConnect(true)
        for _ in 0..<200 where relay.sent.isEmpty { try? await Task.sleep(nanoseconds: 5_000_000) }
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
    }

    func test_소켓이_붙어도_요청_표시가_없으면_기기_목록을_받지_않는다() async {
        var devicesCalls = 0
        relay.devicesHook = { devicesCalls += 1 }
        model.socketDidConnect(true)
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(devicesCalls, 0)
        XCTAssertTrue(relay.sent.isEmpty)
    }

    func test_받은_함_저장이_실패하면_다음_쪽을_요청하지_않는다() async {
        relay.inboxPages = [InboxResponse(messages: [feedingState(5, last: "2026-11-05T08:00:00Z")], more: true),
                            InboxResponse(messages: [feedingState(6, last: "2026-11-05T09:00:00Z")], more: false)]
        store.failing = true
        await model.refresh()
        XCTAssertEqual(relay.inboxCalls, 1)                // 실패한 메시지보다 뒤의 쪽으로 넘어가지 않는다
        XCTAssertTrue(relay.acked.isEmpty)
        XCTAssertEqual(model.state.feeding.seq, 0)
        store.failing = false
        await model.refresh()                              // 저장이 되면 이어서 받는다
        XCTAssertEqual(relay.inboxCalls, 2)
    }

    func test_자격만_있고_상태가_비었으면_요청_표시를_켠다() {
        store = MemoryStateStore()   // restoreFailed는 꺼져 있다(파일이 없었다)
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        XCTAssertTrue(model.state.needsStateRequest)
        XCTAssertEqual(model.state.carId, "c_1")
    }

    func test_복구_뒤_첫_요청이_만료돼도_24시간이_지나면_상태_요청을_다시_보낸다() async {
        store = MemoryStateStore()
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        await model.sendStateRequestIfNeeded()
        XCTAssertFalse(model.state.needsStateRequest)
        relay.sent = []
        clock = clock.addingTimeInterval(8 * 86400)
        await model.refresh()
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
    }

    func test_저장이_거부된_실행은_요청_표시를_만들지_않는다() {
        store.restoreFailed = true
        store.writesRefused = true
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        XCTAssertFalse(model.state.needsStateRequest)       // 원본이 그대로라 다음 정상 실행이 읽는다
    }

    // MARK: 15회차 검토 반영

    func test_기록_지우기도_기기_목록_조회가_성공한_뒤에_상태를_요청한다() async {
        relay.devicesError = .network
        model.clearLocalRecords()
        await model.settle()
        XCTAssertTrue(relay.sent.isEmpty)                  // 목록 조회가 실패하면 요청하지 않는다
        XCTAssertTrue(model.state.needsStateRequest)
        relay.devicesError = nil
        model.clearLocalRecords()
        await model.settle()
        XCTAssertEqual(relay.sent.map(\.type), ["state_request"])
        XCTAssertFalse(model.state.needsStateRequest)
    }
    func test_기록_지우기는_노래_올리기만_빼고_삭제와_선택_요청은_남긴다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try model.addSong(name: "나비야", recording: file, durationS: 12)   // 올리는 중(song_added)
        let acc = SongStatusBody(songId: "01SONG", name: "자장가", status: .accepted, reason: nil, durationS: 30, candidate: false)
        await model.ingest([env(MessageType.songStatus, seq: 5, body: acc)], live: true)
        model.selectSong("01SONG")
        model.removeSong("01SONG")   // 오프라인에서 지운 노래
        XCTAssertEqual(store.value.outbox.map(\.type), ["song_added", "song_selected", "song_removed"])
        model.clearLocalRecords()
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
        model = AppModel(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock })
        XCTAssertEqual(Set(model.state.processed.keys), ["b"])
        XCTAssertTrue(model.state.shown.isEmpty)
        XCTAssertEqual(model.state.reports.map(\.body.sessionId), ["keep"])
    }

    func test_폰_시계가_2분_넘게_다르면_경고() {
        model.noteServerDate(clock.addingTimeInterval(60))
        XCTAssertNil(model.clockWarning)
        model.noteServerDate(clock.addingTimeInterval(-5 * 60))
        XCTAssertEqual(model.clockWarning, "휴대폰 시계가 5분 달라요. 수유 시각이 어긋날 수 있어요.")
    }
}

private extension Envelope {
    func with(id: String) -> Envelope { var e = self; e.id = id; return e }
}
