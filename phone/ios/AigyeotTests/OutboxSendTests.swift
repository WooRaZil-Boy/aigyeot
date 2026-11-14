import XCTest
@testable import Aigyeot

/// 수유 보내기·보낼 함.
@MainActor
final class OutboxSendTests: AppTestCase {
    func test_차에_반영된_수유는_7일이_지나도_실패로_알리지_않는다() async {
        let old = clock.addingTimeInterval(-8 * 86400)
        store.value.feedingLog = [FeedingRecord(id: "F1", at: old, status: .reflected)]
        store.value.outbox = [OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: old, nextTryAt: old)]
        restart()
        app.sendOutbox.autoFlush = false
        await app.sendOutbox.run()
        XCTAssertTrue(app.state.state.outbox.isEmpty)            // 7일 지난 항목은 정리한다
        XCTAssertFalse(app.state.state.unseenDrops.contains(MessageType.feedingStarted))   // 차에 반영됐으니 실패 띠는 없다
        XCTAssertNil(app.status.ui.notice)
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .reflected)
    }

    func test_다시_시도는_망_실패_기다림을_당기고_중계가_준_기다림은_지킨다() async throws {
        store.value.outbox = [OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: clock, nextTryAt: clock)]
        restart()
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
        for r in app.state.state.songs { app.songFiles.delete(r.id) }
    }

    func test_보내는_사이_토큰이_바뀌면_지금_토큰을_다시_보낸다() async {
        let source = ChangingToken("fcm-old")
        app.registerPush.source = source
        relay.patchHook = { if source.token == "fcm-old" { source.token = "fcm-new" } }   // 옛 PATCH가 가는 사이 토큰이 바뀌었다
        app.registerPush.onToken(app.registerPush.token)
        await app.tasks.settle()
        XCTAssertEqual(relay.patches.map(\.pushToken), [.set("fcm-old"), .set("fcm-new")])   // 마지막에 닿는 값이 지금 토큰이다
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
        XCTAssertEqual(app.care.care.events.first?.startAt, CareTime.ms(clock))   // 기록의 누른 시각은 고치지 않는다
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

    func test_7일_만료된_전송은_진행_중_표시를_만료로_바꾼다() throws {
        let old = clock.addingTimeInterval(-8 * 86400)
        store.value.feedingLog = [FeedingRecord(id: "F1", at: old)]
        store.value.songs = [SongRow(id: "S1", name: "나비야", durationS: 5, state: .uploading, addedAt: old)]
        store.value.outbox = [
            OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: old, nextTryAt: old),
            OutboxItem(id: "M1", type: MessageType.songAdded, body: .object([:]), ts: "t", songId: "S1", createdAt: old, nextTryAt: old)]
        restart()
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

    func test_버려진_수유_전송은_실패_표시와_지우기를_함께_저장한다() async {
        app.recordCare.start(.feed)
        relay.sendError = .http(status: 409, code: "id_conflict", retryAfter: nil)
        await app.sendOutbox.run()
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .failed)
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(store.value.feedingLog.first?.status, .failed)   // 저장된 값에도 반영
        XCTAssertNotNil(app.status.ui.notice)
    }
}
