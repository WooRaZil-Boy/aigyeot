import XCTest
@testable import Aigyeot

/// Presentation 시험(1차 정리 때 쓴 것, arch-phone-ios 9장): 가짜 저장소·가짜 중계로 합성 루트를 만들고 화면 뷰모델의 상태를 본다.
/// 안드로이드 ViewModelTest와 같은 이름의 시험은 아래 `ViewModelTests`다.
@MainActor
final class PresentationTests: XCTestCase {
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
        XCTAssertEqual(app.appViewModel.ui.route, .intro)
        app.appViewModel.recordsOnlyFromIntro()
        XCTAssertEqual(app.appViewModel.ui.route, .main)        // 연결 안 됨도 탭(U7)
        app.appViewModel.showConnect()
        XCTAssertEqual(app.appViewModel.ui.route, .connect)
        app.appViewModel.cancelConnect()
        XCTAssertEqual(app.appViewModel.ui.route, .main)
        make()
        app.lose.lost(app.state.creds)
        XCTAssertEqual(app.appViewModel.ui.route, .gone)          // 401·4001(7.1 GONE)
        app.appViewModel.continueWithoutCar()
        XCTAssertEqual(app.appViewModel.ui.route, .main)
        XCTAssertFalse(app.appViewModel.ui.isPaired)
    }

    func test_연결_429는_확인_화면과_직접_입력이_같이_보는_잠금이다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        let connect = app.connect()
        connect.start(.pair("123456"))
        await app.pair.job?.value
        XCTAssertEqual(connect.ui.run, .failed(code: .pair("123456"), .rateLimited(30)))
        XCTAssertTrue(connect.ui.isLocked)
        XCTAssertTrue(app.appViewModel.ui.isPairLocked)
    }

    /// 차 타일 '차 꺼짐 · 마지막 연락 {경과}'는 U20 경과 형식이다(L100: '0분 전'·'75분 전'이 아니다).
    func test_차_타일_마지막_연락은_U20_경과로_쓴다() {
        let last = Date(timeIntervalSince1970: 1_800_000_000)
        let car = DeviceInfo(deviceId: "car", role: .car, platform: .pleos, pairedAt: "2027-01-15T08:00:00Z",
                             lastSeenAt: Rfc3339.string(last), online: false, handoffAlerts: nil)
        let tile = CarTile(car: car, offline: false)
        XCTAssertEqual([30, 300, 3600, 4500].map { tile.text(now: last.addingTimeInterval(TimeInterval($0))) },
                       ["차 꺼짐 · 마지막 연락 방금", "차 꺼짐 · 마지막 연락 5분 전", "차 꺼짐 · 마지막 연락 1시간 전", "차 꺼짐 · 마지막 연락 1시간 15분 전"])
    }

    func test_배너는_그_배너가_떠_있을_때만_닫히고_상세는_뷰모델로_닫는다() async {
        make()
        app.notices.showBanner("M2")
        app.appViewModel.dismissBanner("M1")              // 8초 타이머가 앞 배너의 것이었다
        XCTAssertEqual(app.appViewModel.ui.banner, "M2")
        app.appViewModel.dismissBanner()
        XCTAssertNil(app.appViewModel.ui.banner)
        await storeAlert("M2")
        app.appViewModel.open("M2")
        XCTAssertEqual(app.appViewModel.ui.openAlert, "M2")
        app.appViewModel.closeAlert()
        XCTAssertNil(app.appViewModel.ui.openAlert)
    }

    func test_되돌리기는_지운_기록만_5초_동안_보인다() async {
        make()
        let today = app.today()
        today.start(.feed)
        let id = try! XCTUnwrap(CareRules.inProgress(.feed, in: today.ui.care.events)).id
        app.careSheet().delete(id)
        XCTAssertEqual(app.careTabs.undo, .delete(id))
        app.careTabs.undo(.delete(id))
        XCTAssertNil(app.careTabs.undo)
        XCTAssertNotNil(CareRules.inProgress(.feed, in: today.ui.care.events))
        // 5초(띠 수명은 RecordCare 한 곳)가 지나면 그 띠만 걷힌다. 기록은 지운 그대로다.
        app.recordCare.undoMs = 0
        app.careSheet().delete(id)
        XCTAssertEqual(app.careTabs.undo, .delete(id))
        let timer = app.recordCare.undoTask
        await timer?.value
        XCTAssertNil(app.careTabs.undo)
        XCTAssertNil(CareRules.inProgress(.feed, in: today.ui.care.events))
    }

    func test_오늘은_마지막_수유와_보내기_상태를_원본에서_계산한다() {
        make()
        let today = app.today()
        XCTAssertNil(today.ui.lastFeedingStart)
        today.start(.feed)
        let s = today.ui
        let feed = try! XCTUnwrap(CareRules.latestFeed(s.care.events))
        XCTAssertEqual(s.lastFeedingStart, CareTime.date(feed.startAt))
        XCTAssertEqual(s.feedingRecord(for: feed)?.status, .sending)   // owner: 보낼 함에 들어갔다(R5)
    }

    /// P6: 수명 표 함수 하나(AppLifecycle.handle)가 사건마다 각 원본의 대기 중인 것을 버린다(arch-phone-ios 6장).
    /// 열려던 알림은 화면 상태(AppViewModel)이고 같은 사건을 onEvent로 받는다(L10).
    func test_수명_표_함수_하나가_사건마다_대기_중인_것을_버린다() async {
        make()
        await storeAlert("D")
        app.notices.showBanner("B"); app.appViewModel.open("D")
        app.lifecycle.handle(.background)   // 본문을 기다리는 열 알림만 버린다
        XCTAssertEqual(app.appViewModel.ui.banner, "B")
        XCTAssertEqual(app.appViewModel.ui.openAlert, "D")
        app.appViewModel.open("P")
        XCTAssertEqual(app.appViewModel.pendingOpenAlert, "P")
        app.lifecycle.handle(.background)
        XCTAssertNil(app.appViewModel.pendingOpenAlert)
        app.appViewModel.open("P"); app.lifecycle.handle(.connectionChanged)   // 열린 상세·열려던 알림
        XCTAssertNil(app.appViewModel.ui.openAlert)
        XCTAssertNil(app.appViewModel.pendingOpenAlert)
        XCTAssertEqual(app.appViewModel.ui.banner, "B")
        app.notices.addLine("안내"); app.wipe(try! XCTUnwrap(app.state.creds))   // 끊기 정리: 배너·띠
        XCTAssertNil(app.appViewModel.ui.banner)
        XCTAssertNil(app.status.ui.notice)
        app.notices.showBanner("B2"); app.appViewModel.open("D2"); app.lifecycle.handle(.recordsCleared)
        XCTAssertNil(app.appViewModel.ui.banner)
        XCTAssertNil(app.appViewModel.ui.openAlert)
        XCTAssertNil(app.appViewModel.pendingOpenAlert)
    }

    func test_노래와_설정은_도메인_오류와_저장값을_상태로_보인다() {
        make()
        let songs = app.songs()
        XCTAssertThrowsError(try songs.add(name: " ", recording: URL(fileURLWithPath: "/nope"), durationS: 3, replacing: nil)) {
            XCTAssertEqual($0 as? SongError, .badName)
        }
        XCTAssertEqual(songs.ui.slotsUsed, 0)
        let settings = app.settings()
        settings.setNickname("  콩이 ")
        XCTAssertEqual(settings.ui.care.childNickname, "콩이")
        XCTAssertEqual(app.reportDetail(sessionId: "x").ui.songName(nil), nil)
    }

    /// 인계 알림 본문 하나를 받은 함 길로 저장한다(guardian 연결로 바꾼 뒤).
    private func storeAlert(_ id: String) async {
        let c = try! XCTUnwrap(app.state.creds)
        let body = HandoffAlertBody(text: "t", kind: .parked, restName: nil, minutesToArrival: nil,
                                    occurredAt: Rfc3339.string(Date(timeIntervalSince1970: 1_800_000_000)), sessionId: "S", episodeId: "E")
        let env = Envelope(id: id, seq: 1, type: MessageType.handoffAlert, car: c.carId, from: Sender(role: .car, device: "d_9"),
                           ts: Rfc3339.string(Date(timeIntervalSince1970: 1_800_000_000)), body: try! JSONValue(encoding: body))
        await app.syncInbox.store(c, [env], live: false)
        XCTAssertNotNil(app.state.state.alert(id))
    }
}

/// 화면 뷰모델·순수 매핑 시험(안드로이드 ViewModelTest와 같은 이름 9개). 안드로이드 전용 셋(SavedStateHandle·다시 만든 화면)은 해당 없다.
@MainActor
final class ViewModelTests: XCTestCase {
    private let owner = Kit.owner
    private let guardian = Credentials(carId: "c_1", deviceId: "d_3", token: "t3", role: .guardian)

    func test_탭_모양과_끊기_끝() {
        XCTAssertEqual(careTabsUi(.paired(guardian), nil), CareTabsUiState(guardian: true, disconnectDone: false))
        XCTAssertEqual(careTabsUi(.paired(owner), .running), CareTabsUiState(guardian: false, disconnectDone: false))
        XCTAssertEqual(careTabsUi(.none, .done), CareTabsUiState(guardian: false, disconnectDone: true))
        XCTAssertEqual(careTabsUi(.gone(deviceId: "d_1"), .failed("x")), CareTabsUiState(guardian: false, disconnectDone: false))
    }

    func test_타이머는_그_기록과_연결_여부를_본다() {
        let e = CareRules.newEvent(.feed, at: 1_800_000_000_000)
        var care = CareFile()
        care.events = [e]
        let paired = timerUi(id: e.id, care: care, connection: .paired(guardian))
        XCTAssertEqual(paired.event?.id, e.id)
        XCTAssertTrue(paired.connected)
        XCTAssertTrue(paired.guardian)
        let gone = timerUi(id: "other", care: care, connection: .none)
        XCTAssertNil(gone.event)   // 끝났거나 지운 기록: 화면이 닫힌다
        XCTAssertFalse(gone.connected)
        XCTAssertFalse(gone.guardian)
    }

    /// iOS는 고른 곡을 바로 '실험에 쓰는 곡'으로 그리지 않고 차가 받아들인 표시(candidate)를 그린다(뒷부분이 iOS 결과).
    func test_노래_목록은_지운_곡을_빼고_실험에_쓰는_곡을_표시한다() {
        let t = Date(timeIntervalSince1970: 1_800_000_000)
        var s = PhoneState()
        s.songs = [SongRow(id: "a", name: "가", durationS: 10, state: .accepted, candidate: true, addedAt: t),
                   SongRow(id: "b", name: "나", durationS: 10, state: .removed, addedAt: t.addingTimeInterval(1)),
                   SongRow(id: "c", name: "다", durationS: 10, state: .uploading, addedAt: t.addingTimeInterval(2))]
        let ui = songsUi(s, online: false)
        XCTAssertEqual(ui.songs.map(\.id), ["a", "c"])
        XCTAssertEqual(ui.songs.filter(\.candidate).map(\.id), ["a"])
        XCTAssertEqual(ui.slotsUsed, 2)
        XCTAssertTrue(ui.offline)
    }

    /// 인계 알림 받기가 아직 없으면 iOS는 켜짐으로 보인다(G12 guardian 기본값, 뒷부분이 iOS 결과).
    func test_보호자_목록은_차를_빼고_인계_알림_받기는_없으면_켜짐이다() {
        var s = PhoneState()
        s.devices = [DeviceInfo(deviceId: "car", role: .car, platform: .pleos, pairedAt: "2027-01-15T08:00:00Z", lastSeenAt: nil, online: true, handoffAlerts: nil),
                     DeviceInfo(deviceId: "d_1", role: .owner, platform: .ios, pairedAt: "2027-01-15T08:00:00Z", lastSeenAt: nil, online: false, handoffAlerts: nil),
                     DeviceInfo(deviceId: "d_3", role: .guardian, platform: .android, pairedAt: "2027-01-15T08:00:00Z", lastSeenAt: nil, online: false, handoffAlerts: true)]
        let ui = guardiansUi(s, creds: owner, online: true, busy: false, error: nil, invite: nil)
        XCTAssertEqual(ui.phones.map(\.deviceId), ["d_1", "d_3"])
        XCTAssertEqual(ui.myDeviceId, "d_1")
        XCTAssertTrue(ui.handoffAlerts)
        s.handoffAlerts = false
        XCTAssertFalse(guardiansUi(s, creds: guardian, online: true, busy: false, error: nil, invite: nil).handoffAlerts)
    }

    /// 완료 화면은 iOS에 없다(D3). 그래서 GONE·소개·연결·탭 순이다(뒷부분이 iOS 결과).
    func test_시작_화면은_GONE·소개·연결·탭_순으로_정한다() {
        XCTAssertEqual(appRoute(connection: .gone(deviceId: "d_1"), introSeen: false, connectShown: true), .gone)
        XCTAssertEqual(appRoute(connection: .none, introSeen: false, connectShown: true), .intro)
        XCTAssertEqual(appRoute(connection: .none, introSeen: true, connectShown: true), .connect)
        XCTAssertEqual(appRoute(connection: .paired(owner), introSeen: true, connectShown: true), .main)   // 연결되면 연결 흐름을 띄우지 않는다
        XCTAssertEqual(appRoute(connection: .none, introSeen: true, connectShown: false), .main)   // 연결 안 됨도 탭(U7)
    }

    func test_연결_링크는_형식이_틀리면_알리고_맞으면_확인_화면_코드로_둔다() {
        let kit = Kit()
        let vm = kit.app.appViewModel
        vm.receive(link: URL(string: "aigyeot://pair?code=12")!)
        XCTAssertEqual(kit.app.status.ui.notice, NoticeRules.badLink)   // 탭 화면: 띠
        XCTAssertNil(vm.pendingCode)
        vm.connectShown = true
        vm.receive(link: URL(string: "aigyeot://pair?code=12")!)
        XCTAssertEqual(vm.linkError, NoticeRules.badLink)   // 연결 화면이 떠 있으면 그 화면에
        vm.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertEqual(vm.pendingCode, .pair("042917"))   // 확인 화면을 거친다(G17)
        XCTAssertNil(vm.linkError)
    }

    func test_띠는_안내를_보이고_닫으면_걷는다() {
        let kit = Kit()
        kit.app.state.commit(Kit.owner) { $0.unseenDrops = [MessageType.songSelected] }
        kit.app.notices.addLine("안내")
        kit.app.notices.showCare(CareText.previousFeedEnded)
        XCTAssertEqual(kit.app.status.ui.notice, "안내\n" + CareText.previousFeedEnded)   // 한 띠(D5 a)
        kit.app.status.dismissNotice()
        XCTAssertNil(kit.app.status.ui.notice)
        XCTAssertTrue(kit.app.state.state.unseenDrops.isEmpty)   // 버린 메시지 안내도 이때 지운다(U54)
    }

    func test_시트_저장은_저장·지워짐·실패를_나눈다() {
        let kit = Kit()
        let sheet = kit.app.careSheet()
        let note = CareEvent(id: ULID.generate(), kind: .note, startAt: CareTime.ms(kit.clock) - 60_000, zone: "Asia/Seoul",
                             note: "메모", createdAt: 0, updatedAt: 0)
        XCTAssertEqual(sheet.save(note, allowOverlap: false, editing: false), .saved)
        kit.app.recordCare.delete(note.id)
        var edited = note
        edited.note = "고침"
        XCTAssertEqual(sheet.save(edited, allowOverlap: false, editing: true), .gone)   // 그사이 지워졌으면 되살리지 않는다
        XCTAssertNotNil(kit.app.care.care.events.first { $0.id == note.id }?.deletedAt)
        kit.careStore.failing = true
        let other = CareEvent(id: ULID.generate(), kind: .note, startAt: CareTime.ms(kit.clock) - 30_000, zone: "Asia/Seoul",
                              note: "다른", createdAt: 0, updatedAt: 0)
        XCTAssertEqual(sheet.save(other, allowOverlap: false, editing: false), .failed(NoticeRules.saveFailed))
    }

    func test_녹음_시트는_녹음·멈춤·보내기를_거쳐_커밋되면_닫힌다() async throws {
        let kit = Kit()
        let recorder = FakeSongRecorder()
        let rec = RecorderViewModel(recorder: recorder)
        let songs = kit.app.songs()
        await rec.start()
        XCTAssertEqual(rec.phase, .recording)
        rec.stop()
        XCTAssertEqual(rec.phase, .recorded)
        let url = try XCTUnwrap(rec.fileURL)
        // 보내기: 커밋(곡 목록·보낼 함 저장)이 되면 시트가 닫힌다(RecordSheet는 던지지 않으면 dismiss)
        try songs.add(name: "자장가", recording: url, durationS: rec.elapsed, replacing: nil)
        XCTAssertEqual(songs.ui.songs.map(\.name), ["자장가"])
        XCTAssertTrue(kit.app.state.state.outbox.contains { $0.type == MessageType.songAdded })
        rec.discard()   // 시트가 닫힐 때
        XCTAssertEqual(rec.phase, .idle)
        XCTAssertFalse(recorder.isActive)
    }
}
