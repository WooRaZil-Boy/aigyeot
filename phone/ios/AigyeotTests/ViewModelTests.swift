import XCTest
@testable import Aigyeot

/// Presentation 시험(arch-phone-ios 9장): 가짜 저장소·가짜 중계로 합성 루트를 만들고 화면별 뷰모델의 상태 struct를 본다.
@MainActor
final class ViewModelTests: XCTestCase {
    private var relay: FakeRelay!
    private var app: AppContainer!
    private let creds = Credentials(carId: "c_1", deviceId: "d_1", token: "t", role: .owner)

    private func make(paired: Bool = true, introSeen: Bool = true) {
        relay = FakeRelay()
        app = AppContainer(relay: relay, store: MemoryStateStore(PhoneState(introSeen: introSeen, carId: paired ? "c_1" : nil)),
                           credentials: MemoryCredentialStore(paired ? creds : nil), now: { Date(timeIntervalSince1970: 1_800_000_000) },
                           sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
    }

    func test_루트는_안내_연결_끊김_탭_경로를_원본에서_계산한다() {
        make(paired: false, introSeen: false)
        XCTAssertEqual(app.root.state.route, .intro)
        app.root.recordsOnlyFromIntro()
        XCTAssertEqual(app.root.state.route, .main)        // 연결 안 됨도 탭(U7)
        app.root.showConnect()
        XCTAssertEqual(app.root.state.route, .connect)
        app.root.cancelConnect()
        XCTAssertEqual(app.root.state.route, .main)
        make()
        app.lifecycle.handleUnauthorized()
        XCTAssertEqual(app.root.state.route, .gone)          // 401·4001(7.1 GONE)
        app.root.continueWithoutCar()
        XCTAssertEqual(app.root.state.route, .main)
        XCTAssertFalse(app.root.state.isPaired)
    }

    func test_연결_429는_확인_화면과_직접_입력이_같이_보는_잠금이다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        let f = await app.root.connect(typed: .pair("123456"))
        XCTAssertEqual(f, .rateLimited(30))
        XCTAssertTrue(app.root.state.isPairLocked)
    }

    func test_배너는_그_배너가_떠_있을_때만_닫히고_상세는_뷰모델로_닫는다() {
        make()
        app.showAlert.raise("M2")
        app.home.dismissBanner("M1")              // 8초 타이머가 앞 배너의 것이었다
        XCTAssertEqual(app.home.state.bannerAlertId, "M2")
        app.home.dismissBanner()
        XCTAssertNil(app.home.state.bannerAlertId)
        app.showAlert.showDetail("M2")
        XCTAssertEqual(app.home.state.openAlertId, "M2")
        app.home.closeAlert()
        XCTAssertNil(app.home.state.openAlertId)
    }

    func test_되돌리기는_지운_기록만_5초_동안_보인다() {
        make()
        app.today.startFeeding()
        let id = try! XCTUnwrap(CareRules.inProgress(.feed, in: app.today.state.care.events)).id
        app.careSheet.delete(id)
        XCTAssertEqual(app.undo.recentlyDeleted, id)
        app.undo.expire("other")
        XCTAssertEqual(app.undo.recentlyDeleted, id)   // 다른 기록의 타이머는 지우지 않는다
        XCTAssertNotNil(app.undo.deletedAt(id))
        app.undo.undo(id)
        XCTAssertNil(app.undo.recentlyDeleted)
        XCTAssertNotNil(CareRules.inProgress(.feed, in: app.today.state.care.events))
    }

    func test_오늘은_마지막_수유와_보내기_상태를_원본에서_계산한다() {
        make()
        XCTAssertNil(app.today.state.lastFeedingStart)
        app.today.startFeeding()
        let s = app.today.state
        let feed = try! XCTUnwrap(CareRules.latestFeed(s.care.events))
        XCTAssertEqual(s.lastFeedingStart, CareTime.date(feed.startAt))
        XCTAssertEqual(s.feedingRecord(for: feed)?.status, .sending)   // owner: 보낼 함에 들어갔다(R5)
    }

    /// P6: 수명 표 함수 하나(Lifecycle.handle)가 사건마다 각 원본의 대기 중인 것을 버린다(arch-phone-ios 6장).
    func test_수명_표_함수_하나가_사건마다_대기_중인_것을_버린다() {
        make()
        app.showAlert.raise("B"); app.showAlert.showDetail("D"); app.showAlert.pendingOpenAlertId = "P"
        app.lifecycle.handle(.background)   // 열려던 알림만 버린다
        XCTAssertEqual(app.home.state.bannerAlertId, "B")
        XCTAssertEqual(app.home.state.openAlertId, "D")
        XCTAssertNil(app.showAlert.pendingOpenAlertId)
        app.showAlert.pendingOpenAlertId = "P"; app.lifecycle.handle(.connectionChanged)   // 열린 상세·열려던 알림
        XCTAssertNil(app.home.state.openAlertId)
        XCTAssertNil(app.showAlert.pendingOpenAlertId)
        XCTAssertEqual(app.home.state.bannerAlertId, "B")
        app.notices.add("안내"); app.lifecycle.handle(.connectionLeft)   // 배너·띠
        XCTAssertNil(app.home.state.bannerAlertId)
        XCTAssertNil(app.home.state.notice)
        app.showAlert.raise("B2"); app.showAlert.showDetail("D2"); app.lifecycle.handle(.recordsCleared)
        XCTAssertNil(app.home.state.bannerAlertId)
        XCTAssertNil(app.home.state.openAlertId)
    }

    func test_노래와_설정은_도메인_오류와_저장값을_상태로_보인다() {
        make()
        XCTAssertThrowsError(try app.songs.addSong(name: " ", recording: URL(fileURLWithPath: "/nope"), durationS: 3, replacing: nil)) {
            XCTAssertEqual($0 as? SongError, .badName)
        }
        XCTAssertEqual(app.songs.state.songSlotsUsed, 0)
        app.settings.setChildNickname("  콩이 ")
        XCTAssertEqual(app.settings.state.care.childNickname, "콩이")
        XCTAssertEqual(app.report.state.songName(nil), nil)
    }
}
