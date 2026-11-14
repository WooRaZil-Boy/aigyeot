import XCTest
@testable import Aigyeot

/// 리포트·인계 알림.
@MainActor
final class ReportAlertTests: AppTestCase {
    func test_연결이_없으면_늦은_푸시로_배너를_띄우지_않고_이_연결에_없는_알림은_빈_상세를_열지_않는다() async {
        make(role: .guardian)
        await app.appViewModel.openBanner("M-OLD")                      // 이전 차의 늦은 푸시: 받은 함에서 본문을 받을 수 없다
        XCTAssertNil(app.appViewModel.ui.openAlert)                      // '불러오고 있어요'에 머무는 상세를 열지 않는다
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body, id: "M9")], live: false)
        await app.appViewModel.openBanner("M9")                         // 본문이 있으면 바로 연다
        XCTAssertEqual(app.appViewModel.ui.openAlert, "M9")
        make(role: .guardian, paired: false)
        app.syncInbox.onPush("M-LATE")                                 // 끊은 뒤 늦게 온 푸시
        XCTAssertNil(app.appViewModel.ui.banner)
    }

    func test_리포트는_session_id마다_큰_seq가_이긴다() async {
        var r2 = report("S1"); r2.simulated = true
        await app.syncInbox.store(app.state.creds, [env(MessageType.reportSummary, seq: 10, body: report("S1"))], live: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.reportSummary, seq: 8, body: r2)], live: false)
        XCTAssertEqual(app.state.state.reports.count, 1)
        XCTAssertFalse(app.state.state.reports[0].body.simulated)
        await app.syncInbox.store(app.state.creds, [env(MessageType.reportSummary, seq: 11, body: r2)], live: false)
        XCTAssertEqual(app.state.state.reports.count, 1)
        XCTAssertTrue(app.state.state.reports[0].body.simulated)
    }

    /// 화면은 다시 정렬하지 않고 저장 순서를 쓴다: 받을 때(바꿔 넣을 때도) 시작 시각 늦은 순으로 둔다.
    func test_리포트는_받은_순서와_상관없이_시작_시각이_늦은_것이_위다() async {
        let c = app.state.creds
        await app.syncInbox.store(c, [env(MessageType.reportSummary, seq: 1, body: report("S2", started: "2026-11-05T10:00:00+09:00"))], live: false)
        await app.syncInbox.store(c, [env(MessageType.reportSummary, seq: 2, body: report("S1", started: "2026-11-05T08:00:00+09:00"))], live: false)
        await app.syncInbox.store(c, [env(MessageType.reportSummary, seq: 3, body: report("S3", started: "2026-11-05T09:00:00+09:00"))], live: false)
        // 가장 늦은 S2를 바꿔 넣는다(정렬하지 않으면 맨 뒤로 간다)
        await app.syncInbox.store(c, [env(MessageType.reportSummary, seq: 4, body: report("S2", started: "2026-11-05T10:00:00+09:00"))], live: false)
        XCTAssertEqual(app.reports().reports.map(\.body.sessionId), ["S2", "S3", "S1"])
        XCTAssertEqual(app.car().ui.reports.map(\.body.sessionId), ["S2", "S3", "S1"])
    }

    func test_알림_열기는_본문이_나중에_저장돼도_이어서_연다() async {
        make(role: .guardian)
        await app.appViewModel.onAlert("M9")                  // 본문이 아직 없다
        XCTAssertNil(app.appViewModel.ui.openAlert)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body, id: "M9")], live: true)
        XCTAssertEqual(app.appViewModel.ui.openAlert, "M9")
        XCTAssertNil(app.appViewModel.pendingOpenAlert)
    }

    func test_연결이_지워지거나_기록을_지우면_열린_알림_상세도_닫는다() async {
        make(role: .guardian)
        app.appViewModel.open("M9")
        app.lose.lost(app.state.creds)                   // 401·4001 → 끊김. 다시 연결해도 지운 알림의 상세가 남지 않는다
        XCTAssertNil(app.appViewModel.ui.openAlert)
        make(role: .guardian)
        app.appViewModel.open("M9")
        await app.appViewModel.onAlert("M8")                  // 본문이 없어 열기를 기다린다
        app.clearRecords()
        XCTAssertNil(app.appViewModel.ui.openAlert)
        XCTAssertNil(app.appViewModel.pendingOpenAlert)
    }

    func test_본문을_못_받은_채_뒤로_가면_알림_열기는_버린다() async {
        make(role: .guardian)
        await app.appViewModel.onAlert("M9")                  // 본문이 아직 없다
        app.lifecycle.onBackground()
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body, id: "M9")], live: false)
        XCTAssertNil(app.appViewModel.ui.openAlert)              // 나중에 받아도 다른 화면 위에 저절로 뜨지 않는다
        XCTAssertEqual(app.state.state.alerts.map(\.id), ["M9"])   // 알림은 목록에 남는다
    }

    func test_다른_알림을_직접_열면_대기하던_알림_열기는_버린다() async {
        make(role: .guardian)
        await app.appViewModel.onAlert("M9")
        app.appViewModel.userOpenedAlert("M8")                  // 사용자가 목록에서 다른 알림을 열었다
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body, id: "M9")], live: true)
        XCTAssertNil(app.appViewModel.ui.openAlert)
    }

    func test_인계_알림은_본문을_저장하고_배너는_한_번만() async {
        make(role: .guardian)
        let body = HandoffAlertBody(text: "아기가 계속 울어요. 7분 뒤 도착해요.", kind: .arrivalNotice, restName: nil,
                                    minutesToArrival: 7, occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        let live = env(MessageType.handoffAlert, seq: 4, body: body)
        await app.syncInbox.store(app.state.creds, [live], live: true)
        XCTAssertEqual(app.appViewModel.ui.banner, live.id)
        XCTAssertEqual(app.state.state.alert(live.id)?.body.text, body.text)
        app.appViewModel.dismissBanner()
        app.syncInbox.onPush(live.id)            // 푸시 willPresent가 같은 id로 불러도
        XCTAssertNil(app.appViewModel.ui.banner)   // 다시 띄우지 않는다

        // 앱을 열며 받은 함에서 꺼낸 것은 배너 없이 목록에만 넣는다.
        let backlog = env(MessageType.handoffAlert, seq: 5, body: body)
        await app.syncInbox.store(app.state.creds, [backlog], live: false)
        XCTAssertNil(app.appViewModel.ui.banner)
        XCTAssertNotNil(app.state.state.alert(backlog.id))
    }

    func test_푸시가_먼저_와서_띄운_뒤에도_본문은_저장한다() async {
        make(role: .guardian)
        app.syncInbox.onPush("M1")                       // 푸시가 먼저: 표시만
        XCTAssertNil(app.state.state.processed["M1"])   // 본문 저장 기록에는 넣지 않는다
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock), sessionId: "S", episodeId: "E")
        app.appViewModel.dismissBanner()
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body, id: "M1")], live: true)
        XCTAssertNil(app.appViewModel.ui.banner)
        XCTAssertNotNil(app.state.state.alert("M1"))
        XCTAssertNotNil(app.state.state.processed["M1"])
    }

    func test_오래된_인계_알림은_실시간으로_와도_배너를_띄우지_않는다() async {
        make(role: .guardian)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(clock.addingTimeInterval(-3 * 3600)), sessionId: "S", episodeId: "E")
        await app.syncInbox.store(app.state.creds, [env(MessageType.handoffAlert, seq: 1, body: body)], live: true)
        XCTAssertNil(app.appViewModel.ui.banner)
        XCTAssertEqual(app.state.state.alerts.count, 1)
    }

    func test_인계_알림_설정은_요청이_끝날_때까지_다음_요청을_받지_않는다() async {
        // 요청 중은 보호자 화면 뷰모델이 든다: 같은 화면(다시 그려진 화면 포함)에서 다시 누른 것은 받지 않는다
        let guardians = app.guardians()
        var second: Bool?
        relay.patchHook = { [unowned self] in
            self.relay.patchHook = nil
            XCTAssertTrue(guardians.ui.busy)
            second = await guardians.setHandoff(true)
        }
        let first = await guardians.setHandoff(false)
        XCTAssertTrue(first)
        XCTAssertNil(guardians.ui.error)
        XCTAssertEqual(second, false)
        XCTAssertEqual(relay.patchCalls, 1)   // 앞 요청이 끝나기 전의 요청은 보내지 않는다
        XCTAssertEqual(app.state.state.handoffAlerts, false)
        XCTAssertFalse(guardians.ui.busy)
    }
}
