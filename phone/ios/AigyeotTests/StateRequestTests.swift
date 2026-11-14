@testable import Aigyeot
import XCTest

/// 상태 요청·기기 목록.
@MainActor
final class StateRequestTests: AppTestCase {
    func test_24시간_넘게_못_받았으면_상태_요청을_한_번_보낸다() async {
        store.value.lastReceivedAt = clock.addingTimeInterval(-25 * 3600)
        restart()
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
        app.clearRecords()   // 표시를 켜고 바로 한 번 보낸다
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

    func test_guardian은_상태_요청을_보내지_않는다() async {
        make(role: .guardian)
        app.clearRecords()
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertTrue(relay.sent.isEmpty)
    }

    func test_상태_요청을_동시에_불러도_한_번만_보낸다() async {
        store.value.needsStateRequest = true
        restart()
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

    func test_재요청_표시를_저장하지_못하면_받은_메시지를_적용하지_않는다() async {
        store.value.lastReceivedAt = clock.addingTimeInterval(-25 * 3600)
        restart()
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

    func test_빈_상태로_복구하면_요청_필요_표시를_켜고_기기_목록_뒤에_상태를_요청한다() async {
        store.restoreFailed = true
        restart()
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
        restart()
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
        XCTAssertTrue(app.status.ui.offline)   // 5xx도 중계 장애다
        app.keepConnected.socketEvent(.connected(true)); await app.tasks.settle()
        relay.devicesError = .http(status: 404, code: nil, retryAfter: nil)
        await app.keepConnected.refreshDevices()
        XCTAssertFalse(app.status.ui.offline)   // 4xx는 띠를 바꾸지 않는다
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

    func test_기기_목록을_기다리는_사이_뒤로_가면_상태_요청을_시작하지_않는다() async {
        store.value.needsStateRequest = true
        restart()
        app.sendOutbox.autoFlush = false
        relay.devicesHook = { [unowned self] in app.lifecycle.onBackground() }
        await app.keepConnected.refresh()
        XCTAssertTrue(relay.sent.isEmpty)
    }

    func test_복구_표시를_처음에_저장하지_못해도_다음_갱신에서_상태를_다시_요청한다() async {
        store = MemoryStateStore(PhoneState())   // 상태 파일이 비었다(자격은 owner)
        store.failNext = 1                        // 시작할 때 복구 표시 저장만 실패
        restart()
        app.sendOutbox.autoFlush = false
        XCTAssertFalse(app.state.state.needsStateRequest)
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.stateRequest])   // 표시를 저장한 뒤 다시 받는다
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

    func test_메시지_429_기다림은_상태_요청도_지킨다() async {
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        store.value.needsStateRequest = true
        restart()
        app.sendOutbox.autoFlush = false
        relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 60)
        await app.sendOutbox.run()
        relay.sendError = nil
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertTrue(relay.sent.isEmpty)   // 기기당 메시지 한도라 상태 요청도 기다린다
        XCTAssertTrue(app.state.state.needsStateRequest)
    }

    func test_상태_요청의_429도_보낼_함을_기다리게_한다() async {
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        store.value.needsStateRequest = true
        restart()
        app.sendOutbox.autoFlush = false
        relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 60)
        await app.keepConnected.requestStateIfNeeded()
        relay.sendError = nil
        await app.sendOutbox.run()
        XCTAssertTrue(relay.sent.isEmpty)   // 같은 메시지 한도
    }

    func test_주기_목록이_성공하면_남은_상태_요청을_보낸다() async {
        store.value.needsStateRequest = true
        restart()
        app.sendOutbox.autoFlush = false
        await app.keepConnected.afterDevices(epoch: app.keepConnected.foregroundEpoch)   // 기록 지우기 직후 목록 조회가 실패해 표시가 남아 있었다
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.stateRequest])
    }

    func test_주기_목록을_기다리는_사이_뒤로_가면_상태_요청을_시작하지_않는다() async {
        store.value.needsStateRequest = true
        restart()
        app.sendOutbox.autoFlush = false
        relay.devicesHook = { [unowned self] in app.lifecycle.onBackground() }
        await app.keepConnected.afterDevices(epoch: app.keepConnected.foregroundEpoch)
        XCTAssertTrue(relay.sent.isEmpty)
        XCTAssertNil(app.state.state.lastStateRequestAt)   // 보내지 못한 요청으로 10분 제한을 걸지 않는다
    }

    func test_다시_붙은_직후_띄운_상태_요청은_돌기_전에_뒤로_가면_시작하지_않는다() async {
        store.value.needsStateRequest = true
        restart()
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

    func test_기기_목록을_기다리는_사이_받은_차_봉투의_연결됨을_늦은_목록이_되돌리지_않는다() async {
        let car = DeviceInfo(deviceId: "d_car", role: .car, platform: .android, pairedAt: "2026-11-05T08:00:00Z",
                             lastSeenAt: "2026-11-04T08:00:00Z", online: false, handoffAlerts: nil)
        store.value.devices = [car]
        restart()
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

    func test_소켓이_붙어도_요청_표시가_없으면_기기_목록을_받지_않는다() async {
        var devicesCalls = 0
        relay.devicesHook = { devicesCalls += 1 }
        app.keepConnected.socketDidConnect(true)
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(devicesCalls, 0)
        XCTAssertTrue(relay.sent.isEmpty)
    }

    func test_자격만_있고_상태가_비었으면_요청_표시를_켠다() {
        store = MemoryStateStore()   // restoreFailed는 꺼져 있다(파일이 없었다)
        restart()
        XCTAssertTrue(app.state.state.needsStateRequest)
        XCTAssertEqual(app.state.state.carId, "c_1")
    }

    func test_복구_뒤_첫_요청이_만료돼도_24시간이_지나면_상태_요청을_다시_보낸다() async {
        store = MemoryStateStore()
        restart()
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
        restart()
        XCTAssertFalse(app.state.state.needsStateRequest)       // 원본이 그대로라 다음 정상 실행이 읽는다
    }

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
}
