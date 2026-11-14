@testable import Aigyeot
import XCTest

/// 오프라인 띠·소켓·앞뒤 전환.
@MainActor
final class ConnectionBandTests: AppTestCase {
    func test_인터넷이_돌아오면_망_실패_기다림_없이_바로_보낸다() async {
        store.value.outbox = [OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: clock, nextTryAt: clock)]
        restart()
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
        XCTAssertEqual(relay.sent.map(\.id), ["F1"])   // 기다리지 않고 바로 보낸다
    }

    func test_받은_함_429_기다림_중에도_다시_시도는_보낼_함을_보낸다() async {
        relay.inboxError = .http(status: 429, code: "rate_limited", retryAfter: 600)
        await app.keepConnected.refresh()   // 받은 함 기다림이 생긴다
        relay.inboxError = nil
        app.recordCare.start(.feed)    // 그 사이 수유를 적는다(autoFlush 끔이라 아직 안 보냄)
        await app.status.retryNow()  // 띠의 '다시 시도': 받은 함은 기다려도 보낼 함은 보낸다
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

    func test_취소된_요청은_띠도_보낼_함도_바꾸지_않는다() async {
        relay.devicesError = .cancelled   // 보호자 화면을 떠나 목록 조회가 취소됐다
        await app.keepConnected.refreshDevices()
        XCTAssertFalse(app.status.ui.offline)
        app.keepConnected.socketEvent(.unreachable)
        await app.keepConnected.refreshDevices()
        XCTAssertTrue(app.status.ui.offline)      // 취소는 '닿음'도 아니다
        app.keepConnected.socketEvent(.alive)
        store.value.outbox = [OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: clock, nextTryAt: clock)]
        restart()
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

    func test_늦게_끝난_앞_요청의_실패는_나중에_시작한_요청의_성공을_덮지_않는다() async {
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        restart()
        app.sendOutbox.autoFlush = false
        app.keepConnected.socketEvent(.unreachable)
        relay.devicesError = .network
        relay.devicesHook = { [unowned self] in await app.sendOutbox.run() }   // 목록(A)을 기다리는 사이 보내기(B)를 시작해 성공했다
        await app.keepConnected.refreshDevices()
        XCTAssertEqual(relay.sent.count, 1)
        XCTAssertFalse(app.status.ui.offline)   // A의 늦은 네트워크 실패가 B의 성공을 덮지 않는다
    }

    func test_갱신_중에_소켓이_다시_붙으면_끝난_뒤_받은_함을_먼저_본다() async {
        let car = DeviceInfo(deviceId: "d_car", role: .car, platform: .android, pairedAt: "2026-11-05T08:00:00Z",
                             lastSeenAt: "2026-11-04T08:00:00Z", online: false, handoffAlerts: nil)
        store.value.devices = [car]
        restart()
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

    func test_다른_HTTP도_띠에_반영하고_결과_저장_실패와_따로_본다() async {
        store.value.needsStateRequest = true
        restart()
        app.sendOutbox.autoFlush = false
        relay.sendError = .http(status: 503, code: nil, retryAfter: nil)
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertTrue(app.status.ui.offline)   // 상태 요청이 5xx
        // 보내기는 성공했지만 결과 저장만 실패해도 통신은 됐다
        store.value = app.state.state
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        restart()
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
        restart()
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
        XCTAssertEqual(app.keepConnected.scheduledRefreshDelay, 600)   // 갱신이 끝난 뒤로 미룬 예약도 Retry-After를 지킨다
        app.lifecycle.onBackground()
    }

    func test_받은_함을_기다리는_사이_뒤로_가도_401은_처리한다() async {
        relay.inboxHook = { [unowned self] in app.lifecycle.onBackground() }
        relay.inboxError = .http(status: 401, code: "unauthorized", retryAfter: nil)
        await app.keepConnected.refresh()
        XCTAssertTrue((app.appViewModel.ui.route == .gone))   // 어디서든 401 → 연결이 끊겼어요
    }

    func test_앞으로_와서_갱신이_돌기_전에_뒤로_가면_갱신하지_않는다() async {
        app.lifecycle.onForeground()
        app.lifecycle.onBackground()   // 스폰한 갱신이 돌기 전
        await app.tasks.settle()
        XCTAssertEqual(relay.inboxCalls, 0)
    }

    func test_연결_안_된_폰에는_차로_보낸다는_오프라인_띠를_보이지_않는다() async {
        make(paired: false)
        await app.keepConnected.networkPathChanged(satisfied: false)
        XCTAssertTrue(app.status.ui.offline)
        XCTAssertFalse(app.status.ui.showsOfflineBand)   // 연결 전에는 차로 보내지 않는다
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

    func test_받은_함_429_기다림은_다시_앞으로_와도_지킨다() async {
        app.lifecycle.onForeground(); await app.tasks.settle()
        relay.inboxError = .http(status: 429, code: "rate_limited", retryAfter: 600)
        await app.keepConnected.refresh()
        relay.inboxError = nil
        let calls = relay.inboxCalls
        app.lifecycle.onBackground()
        app.lifecycle.onForeground(); await app.tasks.settle()
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.inboxCalls, calls)   // 600초 안에는 앞으로 오기·새로고침도 받은 함을 부르지 않는다
        app.lifecycle.onBackground()
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

    func test_소켓_pong은_앞_HTTP_실패로_켠_띠를_끈다() {
        app.keepConnected.socketEvent(.connected(true))
        app.keepConnected.socketEvent(.unreachable)   // 예: ack가 한 번 네트워크 실패
        XCTAssertTrue(app.status.ui.offline)
        app.keepConnected.socketEvent(.alive)         // 같은 소켓으로 pong이 온다
        XCTAssertFalse(app.status.ui.offline)
    }

    func test_뒤에서는_받은_함_재시도와_경로_갱신을_하지_않는다() async {
        store.value.lastReceivedAt = clock.addingTimeInterval(-25 * 3600)
        restart()
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
        restart()
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
}
