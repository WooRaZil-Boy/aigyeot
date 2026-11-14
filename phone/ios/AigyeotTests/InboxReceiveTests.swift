import XCTest
@testable import Aigyeot

/// 받은 메시지·안내.
@MainActor
final class InboxReceiveTests: AppTestCase {
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
        restart()
        app.sendOutbox.autoFlush = false
        await app.syncInbox.store(app.state.creds, [feedingState(1, last: nil)], live: false)   // 받은 함에서 꺼낸 것은 바꾸지 않는다
        XCTAssertEqual(app.car().ui.phone.car?.online, false)
        await app.syncInbox.store(app.state.creds, [feedingState(2, last: nil)], live: true)
        XCTAssertEqual(app.car().ui.phone.car?.online, true)
        XCTAssertEqual(app.car().ui.phone.car?.lastSeenAt.flatMap(Rfc3339.parse), clock)
    }

    func test_다른_차의_메시지는_적용도_ack도_하지_않는다() async {
        var e = feedingState(5, last: "2026-11-05T08:00:00Z")
        e.car = "c_OTHER"
        await app.syncInbox.store(app.state.creds, [e], live: true)
        XCTAssertEqual(app.state.state.feeding.seq, 0)
        XCTAssertTrue(relay.acked.isEmpty)
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
        restart()
        app.sendOutbox.autoFlush = false
        relay.sendError = .http(status: 400, code: nil, retryAfter: nil)
        await app.sendOutbox.run()
        let line = "노래 지우기를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요."
        XCTAssertEqual(app.status.ui.notice, line)
        // 띠를 보기 전에 앱이 끝났다
        restart()
        XCTAssertEqual(app.status.ui.notice, line)
        app.status.dismissNotice()
        XCTAssertTrue(store.value.unseenDrops.isEmpty)
        restart()
        XCTAssertNil(app.status.ui.notice)
    }

    func test_버린_안내와_저장_실패_안내는_서로_덮지_않는다() async {
        store.value.introSeen = false   // 아래 markIntroSeen이 실제로 저장하게(저장 실패를 일으킬 아무 저장)
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        restart()
        app.sendOutbox.autoFlush = false
        relay.sendError = .http(status: 400, code: nil, retryAfter: nil)
        await app.sendOutbox.run()
        let dropped = "실험에 쓸 노래 선택을 차로 보내지 못했어요"
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
        restart()
        app.sendOutbox.autoFlush = false
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(app.status.ui.notice, "노래 지우기를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요.")
        app.status.dismissNotice()
        // 보내기 직전의 정리(flushOutbox): 열 때는 아직 7일이 지나지 않았다
        store.value.outbox = [item(MessageType.songSelected, at: clock.addingTimeInterval(-6 * 86400))]
        restart()
        app.sendOutbox.autoFlush = false
        XCTAssertEqual(app.state.state.outbox.count, 1)
        XCTAssertNil(app.status.ui.notice)
        clock = clock.addingTimeInterval(2 * 86400)
        await app.sendOutbox.run()
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertTrue(relay.sent.isEmpty)
        XCTAssertEqual(app.status.ui.notice, "실험에 쓸 노래 선택을 차로 보내지 못했어요")
    }
}
