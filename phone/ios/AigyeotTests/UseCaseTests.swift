import XCTest
@testable import Aigyeot

/// 유스케이스 시험(안드로이드 UseCaseTest와 같은 이름).
@MainActor
final class UseCaseTests: XCTestCase {
    func test_받은_함을_저장한_뒤에만_ack한다() async {
        let kit = Kit()
        let e = kit.carEnvelope(MessageType.feedingState, seq: 1,
                                body: FeedingStateBody(lastFeedingStart: "2027-01-15T08:00:00+09:00", windowStartExpected: nil))
        kit.relay.inboxPages = [InboxResponse(messages: [e], more: false)]
        var savedBeforeAck = false
        kit.relay.ackHook = { [unowned kit] in   // ack를 보내는 순간 디스크(저장소)에 본문과 처리한 id가 이미 있어야 한다
            savedBeforeAck = kit.store.value.feeding.seq == 1 && kit.store.value.processed[e.id] != nil
        }
        await kit.app.keepConnected.refresh()
        XCTAssertTrue(savedBeforeAck)
        XCTAssertEqual(kit.app.state.state.feeding.seq, 1)
        XCTAssertEqual(kit.relay.acked, [e.id])
    }

    func test_받은_것을_저장하지_못하면_ack하지_않고_저장_실패를_알린다() async {
        let kit = Kit()
        let e = kit.carEnvelope(MessageType.feedingState, seq: 1,
                                body: FeedingStateBody(lastFeedingStart: "2027-01-15T08:00:00+09:00", windowStartExpected: nil))
        kit.relay.inboxPages = [InboxResponse(messages: [e], more: false)]
        kit.store.failing = true
        await kit.app.keepConnected.refresh()
        XCTAssertTrue(kit.relay.acked.isEmpty)   // 중계에 남아 다시 받는다
        XCTAssertEqual(kit.app.status.ui.notice, NoticeRules.saveFailed)
    }

    func test_보낼_때_401이면_강제_끊김으로_GONE에_간다() async {
        let kit = Kit(songs: ["s1"])
        kit.relay.sendError = .http(status: 401, code: "unauthorized", retryAfter: nil)
        kit.app.selectSong("s1")
        await kit.app.sendOutbox.run()
        XCTAssertEqual(kit.app.appViewModel.ui.route, .gone)
        XCTAssertNil(kit.creds.value)
        XCTAssertTrue(kit.app.state.state.outbox.isEmpty)
    }

    func test_옛_연결의_늦은_401은_새_연결을_지우지_않는다() async {
        let kit = Kit()
        let old = Kit.owner
        _ = await kit.app.disconnect.now()
        kit.relay.pairResult = .success(Kit.newOwner)
        let failure = await kit.app.pairAndWait(.pair("123456"))
        XCTAssertNil(failure)
        // 옛 연결로 시작한 요청의 401이 늦게 왔다(잡은 자격으로 처리)
        kit.app.gate.unauthorized(.http(status: 401, code: "unauthorized", retryAfter: nil), c: old)
        XCTAssertEqual(kit.app.state.creds?.deviceId, Kit.newOwner.deviceId)
        XCTAssertNotEqual(kit.app.appViewModel.ui.route, .gone)
        XCTAssertNotNil(kit.creds.value)
    }

    func test_설정_끊기는_중계가_지우면_끝나고_기록만_쓰기로_탭에_간다() async {
        let kit = Kit()
        let settings = kit.app.settings()
        settings.startDisconnect()
        XCTAssertEqual(settings.ui.disconnect, .running)
        await kit.app.tasks.settle()
        XCTAssertEqual(settings.ui.disconnect, .done)
        XCTAssertTrue(kit.app.careTabs.ui.disconnectDone)   // 탭이 오늘로 간다(MainView)
        XCTAssertTrue(kit.relay.deleted)
        XCTAssertFalse(kit.app.state.isPaired)
        XCTAssertEqual(kit.app.appViewModel.ui.route, .main)   // GONE이 아니라 기록만 쓰는 탭
        XCTAssertEqual(kit.app.status.ui.notice, CareText.disconnected)
        kit.app.careTabs.disconnectShown()
        XCTAssertNil(settings.ui.disconnect)
    }

    func test_끊기가_오프라인이면_실패_문구를_남기고_연결은_그대로다() async {
        let kit = Kit()
        kit.relay.deleteError = .network
        let settings = kit.app.settings()
        settings.startDisconnect()
        await kit.app.tasks.settle()
        XCTAssertEqual(settings.ui.disconnect, .failed("인터넷이 필요해요"))
        XCTAssertTrue(kit.app.state.isPaired)
        XCTAssertFalse(kit.app.careTabs.ui.disconnectDone)
        settings.dismissDisconnectError()   // 설정을 떠나면 걷는다
        XCTAssertNil(settings.ui.disconnect)
    }

    func test_연결_오류는_앱이_정한_문구이고_429는_버튼을_잠근다() async {
        let kit = Kit(creds: nil)
        let connect = kit.app.connect()
        kit.relay.pairResult = .failure(.http(status: 404, code: "code_invalid", retryAfter: nil))
        connect.start(.pair("123456"))
        await kit.app.pair.job?.value
        XCTAssertEqual(connect.ui.run, .failed(code: .pair("123456"), .codeInvalid))
        XCTAssertEqual(ConnectFailure.codeInvalid.message, "코드가 맞지 않거나 시간이 지났어요. 차 화면의 코드를 다시 확인해 주세요.")
        kit.relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        connect.start(.pair("123456"))
        await kit.app.pair.job?.value
        XCTAssertEqual(connect.ui.run, .failed(code: .pair("123456"), .rateLimited))
        XCTAssertTrue(connect.ui.isLocked)
        let calls = kit.relay.pairCalls
        connect.start(.pair("654321"))   // 잠금 중: 중계에 묻지 않는다
        XCTAssertEqual(kit.relay.pairCalls, calls)
        XCTAssertEqual(connect.ui.run, .failed(code: .pair("654321"), .rateLimited))
    }

    /// iOS는 완료 화면 대신 '연결됐어요' 띠와 탭(오늘)으로 간다. 오늘 탭으로 바꾸는 일은 MainView가 연결 여부를 보고 한다.
    func test_연결을_마치면_새_자격·연결됐어요_띠·오늘_탭으로() async {
        let kit = Kit(creds: nil)
        kit.relay.pairResult = .success(Kit.newOwner)
        kit.app.notices.addLine("옛 안내"); kit.app.notices.showBanner("B"); kit.app.notices.showUndo(.delete("U"))
        kit.app.appViewModel.connectShown = true
        let failure = await kit.app.pairAndWait(.pair("123456"))
        XCTAssertNil(failure)
        XCTAssertEqual(kit.creds.value?.deviceId, Kit.newOwner.deviceId)
        XCTAssertEqual(kit.app.state.creds?.deviceId, Kit.newOwner.deviceId)
        XCTAssertEqual(kit.app.status.ui.notice, NoticeRules.paired)   // 띠를 처음으로 하고 '연결됐어요'
        XCTAssertNil(kit.app.appViewModel.ui.banner)
        XCTAssertNil(kit.app.notices.undo)
        XCTAssertFalse(kit.app.appViewModel.connectShown)
        XCTAssertEqual(kit.app.appViewModel.ui.route, .main)
    }

    func test_연결_정보를_저장하지_못하면_중계의_새_기기를_지운다() async {
        let kit = Kit(creds: nil)
        kit.relay.pairResult = .success(Kit.newOwner)
        kit.store.failing = true
        let failure = await kit.app.pairAndWait(.pair("123456"))
        XCTAssertEqual(failure, .notSaved(removed: true))
        XCTAssertEqual(kit.relay.deletedTokens, [Kit.newOwner.deviceToken])
        XCTAssertNil(kit.creds.value)
        XCTAssertFalse(kit.app.state.isPaired)
    }

    func test_수유를_시작하면_owner는_차로_보내고_다시_시작하면_앞_기록을_닫았다고_알린다() {
        let kit = Kit()
        kit.app.recordCare.start(.feed)
        XCTAssertEqual(kit.app.state.state.outbox.map(\.type), [MessageType.feedingStarted])
        kit.clock.addTimeInterval(600)
        kit.app.recordCare.start(.feed)
        XCTAssertEqual(kit.app.notices.careNotice, CareText.previousFeedEnded)
        XCTAssertEqual(kit.app.state.state.outbox.filter { $0.type == MessageType.feedingStarted }.count, 2)
    }

    func test_기록_지우기는_돌봄_기록을_지우고_지우기_표시를_마친다() {
        let kit = Kit()
        kit.app.recordCare.start(.feed)
        kit.app.recordCare.start(.sleep)
        XCTAssertFalse(kit.app.care.care.events.isEmpty)
        kit.app.clearRecords()
        XCTAssertTrue(kit.app.care.care.events.isEmpty)
        XCTAssertNil(kit.app.state.state.clearingCareIds)
        XCTAssertTrue(kit.app.care.care.migratedFeedings)   // 지운 수유를 다시 옮겨 오지 않는다
    }
}
