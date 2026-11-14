import XCTest
import Observation
@testable import Aigyeot

/// 늦게 온 응답과 그사이 바뀐 연결: 옛 요청의 결과가 지금 상태를 덮지 않는지 본다.
@MainActor
final class LateResponseTests: XCTestCase {
    private let guardian = Credentials(carId: "c_1", deviceId: "d_3", token: "at1.c_1.d_3.x", role: .guardian)
    private struct Token: PushTokens { var token: String? }

    /// 강제 끊김 정리 표시를 걷지 못하면 GONE을 둔다(다시 켜면 또 GONE이라 고른 것과 어긋나지 않게).
    func test_GONE_표시를_걷지_못하면_GONE_화면을_둔다() {
        let kit = Kit()
        kit.app.lose.lost(Kit.owner)
        XCTAssertEqual(kit.app.appViewModel.ui.route, .gone)
        kit.marks.failPut = true
        kit.app.appViewModel.reconnectAfterGone()
        XCTAssertEqual(kit.app.appViewModel.ui.route, .gone)
        XCTAssertFalse(kit.app.appViewModel.connectShown)
        kit.app.appViewModel.continueWithoutCar()
        XCTAssertEqual(kit.app.appViewModel.ui.route, .gone)
        kit.marks.failPut = false
        kit.app.appViewModel.continueWithoutCar()
        XCTAssertEqual(kit.app.appViewModel.ui.route, .main)
        XCTAssertNil(kit.marks.get(.goneCleanupPending))
    }

    /// 보호자 화면을 닫았다 다시 열어(새 뷰모델) 겹쳐 눌러도 앞 요청이 끝난 뒤에 보내, 늦게 온 앞 응답이 나중 설정을 덮지 않는다.
    func test_보호자_화면을_다시_열어_겹쳐_눌러도_앞_응답이_나중_설정을_덮지_않는다() async {
        let kit = Kit(creds: guardian)
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        kit.relay.patchHook = { [unowned kit] in
            if kit.relay.patchCalls == 1 { entered = true; await withCheckedContinuation { gate = $0 } }
        }
        let before = kit.app.guardians()
        let first = Task { await before.setHandoff(false) }
        while !entered { await Task.yield() }
        let reopened = kit.app.guardians()   // 화면을 닫고 다시 열었다
        XCTAssertFalse(reopened.ui.busy)
        let second = Task { await reopened.setHandoff(true) }
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(kit.relay.patchCalls, 1)   // 앞 요청이 끝나기 전에는 보내지 않는다
        gate?.resume()
        _ = await first.value
        _ = await second.value
        XCTAssertEqual(kit.relay.patches.map(\.handoffAlerts), [false, true])
        XCTAssertEqual(kit.app.state.state.handoffAlerts, true)   // 마지막에 누른 값
    }

    /// 차례를 기다리던 인계 알림 설정은 누른 때의 연결로만 보낸다. 그사이 다른 차에 연결했으면 새 연결의 설정을 바꾸지 않는다.
    func test_차례를_기다리던_인계_알림_설정은_그사이_연결이_바뀌면_보내지_않는다() async {
        let kit = Kit(creds: guardian)
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        kit.relay.patchHook = { [unowned kit] in
            if kit.relay.patchCalls == 1 { entered = true; await withCheckedContinuation { gate = $0 } }
        }
        let before = kit.app.guardians()
        let first = Task { await before.setHandoff(false) }
        while !entered { await Task.yield() }
        let reopened = kit.app.guardians()   // 화면을 닫고 다시 열어 켜기를 눌렀다(앞 요청 뒤에 줄 선다)
        let second = Task { await reopened.setHandoff(true) }
        for _ in 0..<20 { await Task.yield() }
        // 그사이 다른 차에 보호자로 연결했다
        kit.app.lose.lost(guardian)
        kit.app.appViewModel.continueWithoutCar()
        kit.relay.pairResult = .success(Kit.guardian)
        let failure = await kit.app.pairAndWait(.pair("123456"))
        XCTAssertNil(failure)
        gate?.resume()
        let firstApplied = await first.value
        let secondApplied = await second.value
        XCTAssertEqual(kit.relay.patches.map(\.handoffAlerts), [false])   // 줄 서 있던 켜기는 새 연결로 보내지 않았다
        XCTAssertFalse(firstApplied)    // 옛 연결의 응답은 버렸다
        XCTAssertFalse(secondApplied)   // 보내지 않은 켜기로 알림 권한을 묻지 않는다
        XCTAssertEqual(kit.app.state.creds?.deviceId, Kit.guardian.deviceId)
        XCTAssertEqual(kit.app.state.state.handoffAlerts, true)   // 옛 연결의 늦은 응답(끔)이 새 연결의 기본값을 덮지 않았다
    }

    /// 소켓 pong은 이미 '닿음'이면 표시를 다시 쓰지 않는다(값이 같은데 띠·차 탭·설정을 깨우지 않는다).
    func test_이미_닿는_중에_온_pong은_연결_표시_관찰자를_깨우지_않는다() {
        let kit = Kit()
        let connection = kit.app.keepConnected
        connection.socketEvent(.alive)
        XCTAssertTrue(connection.status.online)
        let woke = Flag()
        withObservationTracking { _ = connection.status } onChange: { woke.on = true }
        connection.socketEvent(.alive)
        XCTAssertFalse(woke.on)
        connection.socketEvent(.unreachable)
        XCTAssertTrue(woke.on)   // 값이 바뀌면 깨운다
    }

    /// 응답마다 조금씩 바뀌는 시계 차이는 보내기 판단에만 쓰고, 띠 글이 같으면 연결 표시를 읽는 화면(오늘·차·설정)을 깨우지 않는다.
    func test_시계_차이가_조금_바뀌어도_띠가_같으면_연결_표시_관찰자를_깨우지_않는다() {
        let kit = Kit()
        let connection = kit.app.keepConnected
        connection.noteServerDate(kit.clock.addingTimeInterval(0.2))
        let woke = Flag()
        withObservationTracking { _ = connection.status } onChange: { woke.on = true }
        connection.noteServerDate(kit.clock.addingTimeInterval(0.4))
        XCTAssertFalse(woke.on)
        XCTAssertEqual(connection.clockSkew ?? 0, 0.4, accuracy: 0.001)   // 보내기 판단에 쓰는 원시 차이는 바뀐다
        connection.noteServerDate(kit.clock.addingTimeInterval(-5 * 60))
        XCTAssertTrue(woke.on)   // 띠 글이 바뀌면 깨운다
        XCTAssertEqual(kit.app.status.ui.clockWarning, "휴대폰 시계가 5분 달라요. 수유 시각이 어긋날 수 있어요.")
    }

    /// 설정 끊기가 실패로 끝났지만 그사이 4001로 GONE이 됐으면 설정에 옛 오류를 남기지 않는다(새 차의 설정에 보이지 않게, 안드로이드와 같다).
    func test_끊기_실패_사이_강제_끊김이면_새_연결의_설정에_옛_오류가_없다() async {
        let kit = Kit()
        kit.relay.deleteError = .network   // 중계는 4001로 닫았지만 DELETE 응답을 잃었다
        kit.relay.deleteHook = { [unowned kit] in await MainActor.run { kit.app.lose.lost(kit.app.state.creds) } }
        kit.app.disconnect.start()
        await kit.app.tasks.settle()
        XCTAssertEqual(kit.app.appViewModel.ui.route, .gone)
        XCTAssertNil(kit.app.disconnect.run)
        kit.app.appViewModel.reconnectAfterGone()
        kit.relay.pairResult = .success(Kit.newOwner)
        let failure = await kit.app.pairAndWait(.pair("123456"))
        XCTAssertNil(failure)
        XCTAssertNil(kit.app.settings().ui.disconnect)
    }

    /// 끊기가 먼저 실패로 끝나(설정을 떠난 뒤라 걷지 못함) 오류가 남은 뒤, 차에서 해제되고 새 차에 연결해도 새 설정에 옛 오류가 없다.
    func test_끊기가_먼저_실패한_뒤_연결이_바뀌어도_새_연결의_설정에_옛_오류가_없다() async {
        let kit = Kit()
        kit.relay.deleteError = .network   // 중계에 닿지 않았다(자격은 남는다)
        kit.app.disconnect.start()
        await kit.app.tasks.settle()
        XCTAssertEqual(kit.app.disconnect.run, .failed("인터넷이 필요해요"))
        kit.app.lose.lost(Kit.owner)   // 그 뒤 차에서 이 폰을 해제했다
        kit.app.appViewModel.reconnectAfterGone()
        kit.relay.pairResult = .success(Kit.newOwner)
        let failure = await kit.app.pairAndWait(.pair("123456"))
        XCTAssertNil(failure)
        XCTAssertNil(kit.app.disconnect.run)
        XCTAssertNil(kit.app.settings().ui.disconnect)
    }

    /// 관찰 콜백(@Sendable)이 적는 표시. 콜백은 바꾸는 쪽(메인 액터)에서 곧바로 불린다.
    private final class Flag: @unchecked Sendable { var on = false }

    /// 앱을 열 때의 푸시 토큰 등록 응답이 늦게 와도, 그 뒤에 바꾼 인계 알림 받기를 옛 값으로 덮지 않는다.
    func test_늦은_푸시_토큰_응답이_그_뒤에_바꾼_인계_알림_받기를_덮지_않는다() async {
        let kit = Kit(creds: guardian)
        kit.app.state.commit(guardian) { $0.handoffAlerts = true }
        kit.relay.storedHandoffAlerts = true
        kit.app.registerPush.source = Token(token: "fcm-1")
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        kit.relay.patchHook = { [unowned kit] in
            if kit.relay.patchCalls == 1 { entered = true; await withCheckedContinuation { gate = $0 } }
        }
        let register = Task { await kit.app.registerPush.now(guardian) }   // 응답(켜짐)이 늦게 온다
        while !entered { await Task.yield() }
        let guardians = kit.app.guardians()
        let toggle = Task { await guardians.setHandoff(false) }
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(kit.relay.patchCalls, 1)   // 앞 PATCH가 끝난 뒤에 보낸다
        gate?.resume()
        await register.value
        _ = await toggle.value
        XCTAssertEqual(kit.relay.storedHandoffAlerts, false)
        XCTAssertEqual(kit.app.state.state.handoffAlerts, false)   // 화면과 중계가 같다
    }

    /// 첫 실행 안내에서 연 확인 시트가 연결하는 동안 온 링크도 버리고 알린다.
    func test_첫_실행_안내에서도_연결_중에_온_링크는_받지_않는다() async {
        let kit = Kit(creds: nil, introSeen: false)
        let vm = kit.app.appViewModel
        vm.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertEqual(vm.introCode, .pair("042917"))
        vm.connectFromIntro()
        XCTAssertEqual(vm.pendingCode, .pair("042917"))
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        kit.relay.pairHook = { entered = true; await withCheckedContinuation { gate = $0 } }
        kit.relay.pairResult = .failure(.http(status: 404, code: "code_invalid", retryAfter: nil))
        kit.app.connect().start(.pair("042917"))
        while !entered { await Task.yield() }
        vm.receive(link: URL(string: "aigyeot://pair?code=555555")!)
        XCTAssertNil(vm.introCode)   // 나중에 안내 버튼이 받지 말았어야 할 확인 화면을 열지 않는다
        XCTAssertEqual(vm.pendingCode, .pair("042917"))
        XCTAssertEqual(vm.linkError, CareText.pairBusy)
        gate?.resume()
        await kit.app.pair.job?.value
    }

    /// 인계 알림 받기는 중계가 원본이다. 로컬 저장에 실패했던 값도 앱을 열 때 푸시 토큰 응답으로 맞춘다.
    func test_앱을_열_때_푸시_토큰_응답으로_인계_알림_받기를_맞춘다() async {
        let kit = Kit(creds: guardian)
        kit.app.state.commit(guardian) { $0.handoffAlerts = true }   // 끄기는 중계에 닿았지만 로컬 저장에 실패했다
        kit.relay.storedHandoffAlerts = false
        kit.app.registerPush.source = Token(token: "fcm-1")
        await kit.app.registerPush.now(guardian)
        XCTAssertEqual(kit.app.state.state.handoffAlerts, false)
        XCTAssertEqual(kit.store.value.handoffAlerts, false)
    }
}
