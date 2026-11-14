import XCTest
@testable import Aigyeot

/// Presentation 시험: 가짜 저장소·가짜 중계로 합성 루트를 만들고 화면 뷰모델의 상태를 본다.
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
        XCTAssertEqual(app.appViewModel.ui.route, .main)        // 연결 안 됨도 탭
        app.appViewModel.showConnect()
        XCTAssertEqual(app.appViewModel.ui.route, .connect)
        app.appViewModel.cancelConnect()
        XCTAssertEqual(app.appViewModel.ui.route, .main)
        make()
        app.lose.lost(app.state.creds)
        XCTAssertEqual(app.appViewModel.ui.route, .gone)          // 401·4001(GONE)
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
        XCTAssertEqual(connect.ui.run, .failed(code: .pair("123456"), .rateLimited))
        XCTAssertTrue(connect.ui.isLocked)
        XCTAssertTrue(app.appViewModel.ui.isPairLocked)
    }

    /// 차 타일 '차 꺼짐 · 마지막 연락 {경과}'는 경과 형식이다('0분 전'·'75분 전'이 아니다).
    func test_차_타일_마지막_연락은_경과_형식으로_쓴다() {
        let last = Date(timeIntervalSince1970: 1_800_000_000)
        let car = DeviceInfo(deviceId: "car", role: .car, platform: .pleos, pairedAt: "2027-01-15T08:00:00Z",
                             lastSeenAt: Rfc3339.string(last), online: false, handoffAlerts: nil)
        let tile = CarTile(car: car, offline: false)
        XCTAssertEqual([30, 300, 3600, 4500].map { tile.text(now: last.addingTimeInterval(TimeInterval($0))) },
                       ["차 꺼짐 · 마지막 연락 방금", "차 꺼짐 · 마지막 연락 5분 전", "차 꺼짐 · 마지막 연락 1시간 전", "차 꺼짐 · 마지막 연락 1시간 15분 전"])
    }

    /// 초대는 그 차의 것이다(끊으면 중계가 폐기): 만든 연결에서만 보이고, 기다리는 사이 연결이 바뀌면 옛 차의 결과를 버린다.
    func test_초대는_만든_연결에서만_보이고_기다리는_사이_연결이_바뀌면_버린다() async {
        let made = InviteResponse(code: "ABCDE23456", display: "ABCDE-23456", expiresAt: "2027-01-16T08:00:00Z", link: "https://example.invalid/i/ABCDE23456")
        make()
        relay.inviteResult = made
        let g = app.guardians()
        await g.makeInvite()
        XCTAssertEqual(g.ui.invite, made)
        app.lose.lost(app.state.creds)
        XCTAssertNil(g.ui.invite)

        make()
        relay.inviteResult = made
        let app = self.app!
        relay.inviteHook = { @MainActor in app.lose.lost(app.state.creds) }
        let g2 = app.guardians()
        await g2.makeInvite()
        XCTAssertNil(g2.ui.invite)
        XCTAssertNil(g2.ui.error)
        XCTAssertFalse(g2.ui.busy)

        // 인계 알림 받기도 같다: 기다리는 사이 연결이 바뀌면 옛 연결의 실패를 보이지 않는다
        make()
        let app3 = self.app!
        relay.patchError = .network
        relay.patchHook = { @MainActor in app3.lose.lost(app3.state.creds) }
        let g3 = app3.guardians()
        let changed = await g3.setHandoff(false)
        XCTAssertFalse(changed)
        XCTAssertNil(g3.ui.error)
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
        app.careSheet(.edit(id)).delete()
        XCTAssertEqual(app.careTabs.undo, .delete(id))
        app.careTabs.undo(.delete(id))
        XCTAssertNil(app.careTabs.undo)
        XCTAssertNotNil(CareRules.inProgress(.feed, in: today.ui.care.events))
        // 5초(띠 수명은 RecordCare 한 곳)가 지나면 그 띠만 걷힌다. 기록은 지운 그대로다.
        app.recordCare.undoMs = 0
        app.careSheet(.edit(id)).delete()
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
        XCTAssertEqual(s.feedingRecord(for: feed)?.status, .sending)   // owner: 보낼 함에 들어갔다
    }

    /// 수명 표 함수 하나(AppLifecycle.handle)가 사건마다 각 원본의 대기 중인 것을 버린다.
    /// 열려던 알림은 화면 상태(AppViewModel)이고 같은 사건을 onEvent로 받는다.
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

    func test_타이머는_그_기록과_guardian_여부를_본다() {
        let e = CareRules.newEvent(.feed, at: 1_800_000_000_000)
        var care = CareFile()
        care.events = [e]
        let paired = timerUi(id: e.id, care: care, connection: .paired(guardian))
        XCTAssertEqual(paired.event?.id, e.id)
        XCTAssertTrue(paired.guardian)
        let gone = timerUi(id: "other", care: care, connection: .none)
        XCTAssertNil(gone.event)   // 끝났거나 지운 기록: 화면이 닫힌다
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

    /// 인계 알림 받기가 아직 없으면 iOS는 켜짐으로 보인다(guardian 기본값, 뒷부분이 iOS 결과).
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

    /// 완료 화면은 iOS에 없다. 그래서 GONE·소개·연결·탭 순이다(뒷부분이 iOS 결과).
    func test_시작_화면은_GONE·소개·연결·탭_순으로_정한다() {
        XCTAssertEqual(appRoute(connection: .gone(deviceId: "d_1"), introSeen: false, connectShown: true), .gone)
        XCTAssertEqual(appRoute(connection: .none, introSeen: false, connectShown: true), .intro)
        XCTAssertEqual(appRoute(connection: .none, introSeen: true, connectShown: true), .connect)
        XCTAssertEqual(appRoute(connection: .paired(owner), introSeen: true, connectShown: true), .main)   // 연결되면 연결 흐름을 띄우지 않는다
        XCTAssertEqual(appRoute(connection: .none, introSeen: true, connectShown: false), .main)   // 연결 안 됨도 탭
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
        XCTAssertEqual(vm.pendingCode, .pair("042917"))   // 확인 화면을 거친다
        XCTAssertNil(vm.linkError)
    }

    func test_띠는_안내를_보이고_닫으면_걷는다() {
        let kit = Kit()
        kit.app.state.commit(Kit.owner) { $0.unseenDrops = [MessageType.songSelected] }
        kit.app.notices.addLine("안내")
        kit.app.notices.showCare(CareText.previousFeedEnded)
        XCTAssertEqual(kit.app.status.ui.notice, "안내\n" + CareText.previousFeedEnded)   // 한 띠
        kit.app.status.dismissNotice()
        XCTAssertNil(kit.app.status.ui.notice)
        XCTAssertTrue(kit.app.state.state.unseenDrops.isEmpty)   // 버린 메시지 안내도 이때 지운다
    }

    func test_시트_저장은_저장·지워짐·실패를_나눈다() {
        let kit = Kit()
        let sheet = kit.app.careSheet(.new(.note, day: nil))
        sheet.edit { $0.note = "  메모  " }
        sheet.save()
        XCTAssertEqual(sheet.phase, .closed(saved: true))   // 보통 저장
        let saved = try! XCTUnwrap(kit.app.care.care.events.first { $0.kind == .note })
        XCTAssertEqual(saved.note, "메모")   // 앞뒤 공백을 걷는다(CareRules.normalized)

        let editing = kit.app.careSheet(.edit(saved.id))
        XCTAssertEqual(editing.draft?.original?.id, saved.id)
        kit.app.recordCare.delete(saved.id)
        editing.edit { $0.note = "고침" }
        editing.save()
        XCTAssertEqual(editing.phase, .closed(saved: false))   // 그사이 지워졌으면 되살리지 않고 닫는다. 저장하지 않았으니 확인 표시 없음
        XCTAssertNotNil(kit.app.care.care.events.first { $0.id == saved.id }?.deletedAt)
        XCTAssertNil(kit.app.careSheet(.edit(saved.id)).draft)   // 지운 기록은 시트를 그리지 않는다

        kit.careStore.failing = true
        let other = kit.app.careSheet(.new(.note, day: nil))
        other.edit { $0.note = "다른" }
        other.save()
        XCTAssertEqual(other.phase, .failed(NoticeRules.saveFailed, attempt: 1))
    }

    /// 같은 오류가 다시 나도 실패마다 새 값을 낸다(시도 번호). 화면은 그 값이 바뀔 때 펼침·흔들림·오류 햅틱을 낸다.
    func test_같은_저장_오류가_다시_나면_시도_번호가_다른_실패를_낸다() {
        let kit = Kit()
        let now = kit.clock
        let sheet = kit.app.careSheet(.new(.feed, day: nil))
        sheet.edit { $0.setStart(now.addingTimeInterval(-3600)); $0.setEnd(picked: now.addingTimeInterval(3600)) }   // 같은 미래 끝 시각
        sheet.save()
        let first = sheet.phase
        sheet.save()
        let second = sheet.phase
        XCTAssertEqual(first, .failed("앞으로의 시각은 적을 수 없어요", problem: .futureEnd, attempt: 1))
        XCTAssertEqual(second, .failed("앞으로의 시각은 적을 수 없어요", problem: .futureEnd, attempt: 2))
        XCTAssertNotEqual(first, second)
        kit.careStore.failing = true   // 저장 실패(저장 공간)도 같은 규칙
        sheet.edit { $0.setEnd(picked: now.addingTimeInterval(-1800)) }
        sheet.save()
        XCTAssertEqual(sheet.phase, .failed(NoticeRules.saveFailed, attempt: 3))
        sheet.save()
        XCTAssertEqual(sheet.phase, .failed(NoticeRules.saveFailed, attempt: 4))
    }

    /// 값을 고치면 앞 검사 오류를 걷는다(끝 시각 오류 뒤 '끝 시각 모름'을 골라도 빨간 테·글이 남지 않게, 안드로이드 edit과 같음).
    func test_검사_오류_뒤_값을_고치면_오류를_걷는다() {
        let kit = Kit()
        let now = kit.clock
        let sheet = kit.app.careSheet(.new(.feed, day: nil))
        sheet.edit { $0.setStart(now.addingTimeInterval(-3600)); $0.setEnd(picked: now.addingTimeInterval(3600)) }
        sheet.save()
        XCTAssertEqual(sheet.phase, .failed("앞으로의 시각은 적을 수 없어요", problem: .futureEnd, attempt: 1))
        sheet.edit { $0.endUnknown = true }   // 시트의 '끝 시각 모름' 알약과 같은 입력
        XCTAssertEqual(sheet.phase, .editing)
    }

    /// 지우기로 닫히는 시트는 저장한 닫힘이 아니다(확인 표시·성공 햅틱 없음).
    func test_시트를_지우면_저장하지_않은_닫힘이다() throws {
        let kit = Kit()
        let sheet = kit.app.careSheet(.new(.diaper, day: nil))
        sheet.save()
        let id = try XCTUnwrap(kit.app.care.care.events.first?.id)
        let editing = kit.app.careSheet(.edit(id))
        editing.delete()
        XCTAssertEqual(editing.phase, .closed(saved: false))
        XCTAssertNotNil(kit.app.care.care.events.first { $0.id == id }?.deletedAt)
    }

    /// 저장 확인 표시가 머무는 동안(closed(saved: true)) 지우기는 받지 않는다.
    func test_저장_확인_표시_중에는_지우기를_받지_않는다() throws {
        let kit = Kit()
        kit.app.careSheet(.new(.diaper, day: nil)).save()
        let id = try XCTUnwrap(kit.app.care.care.events.first?.id)
        let editing = kit.app.careSheet(.edit(id))
        XCTAssertTrue(editing.canEdit)
        editing.save()
        XCTAssertEqual(editing.phase, .closed(saved: true))
        XCTAssertFalse(editing.canEdit)
        editing.delete()
        XCTAssertEqual(editing.phase, .closed(saved: true))
        XCTAssertNil(kit.app.care.care.events.first { $0.id == id }?.deletedAt)
    }

    /// 닫힐 때 보일 것은 저장 여부와 종류로만 정한다. 메모 저장은 햅틱만, 수유 저장은 확인 표시, 저장 안 됨은 바로 닫힘, 동작 줄이기는 햅틱만.
    func test_시트가_닫힐_때_확인_표시는_실제로_저장했을_때만이다() {
        XCTAssertEqual(SheetCloseFeedback.on(saved: true, kind: .note, reduceMotion: false), .hapticOnly)
        XCTAssertEqual(SheetCloseFeedback.on(saved: true, kind: .feed, reduceMotion: false), .confirm)
        XCTAssertEqual(SheetCloseFeedback.on(saved: true, kind: .diaper, reduceMotion: false), .confirm)
        XCTAssertEqual(SheetCloseFeedback.on(saved: false, kind: .feed, reduceMotion: false), .none)
        XCTAssertEqual(SheetCloseFeedback.on(saved: false, kind: .note, reduceMotion: false), .none)
        XCTAssertEqual(SheetCloseFeedback.on(saved: true, kind: .feed, reduceMotion: true), .hapticOnly)
        XCTAssertEqual(SheetCloseFeedback.on(saved: false, kind: .feed, reduceMotion: true), .none)
    }

    /// 겹침은 묻고 '고치기'면 고치는 중으로 돌아가며, '그래도 저장'이면 저장한다.
    func test_시트는_겹치면_묻고_답에_따라_저장하거나_돌아간다() {
        let kit = Kit()
        // 끝 모름 잠 둘이 같은 시각에 시작하면 겹친다(두 점)
        let first = kit.app.careSheet(.new(.sleep, day: nil))
        first.save()
        XCTAssertEqual(first.phase, .closed(saved: true))
        let second = kit.app.careSheet(.new(.sleep, day: nil))
        second.save()
        XCTAssertEqual(second.phase, .confirmOverlap)
        second.keepEditing()
        XCTAssertEqual(second.phase, .editing)
        second.save(allowOverlap: true)   // '그래도 저장'은 겹침을 묻는 중에만 받는다
        XCTAssertEqual(second.phase, .editing)
        second.save()
        XCTAssertEqual(second.phase, .confirmOverlap)
        second.save()   // 묻는 중의 보통 저장은 받지 않는다
        XCTAssertEqual(second.phase, .confirmOverlap)
        second.save(allowOverlap: true)
        second.save(allowOverlap: true)   // 닫히는 사이 또 눌렀다
        XCTAssertEqual(second.phase, .closed(saved: true))   // 겹침 확인 뒤 저장도 저장한 닫힘이다(확인 표시·햅틱)
        XCTAssertEqual(kit.app.care.care.events.filter { $0.kind == .sleep && $0.deletedAt == nil }.count, 2)
    }

    /// 사용자가 고른 끝은 시작을 옮겨도 말없이 바뀌지 않는다. 시작이 끝을 넘으면 저장하지 않고 시각 검사 글을 보인다(안드로이드 같은 이름 시험).
    func test_고른_끝은_시작을_옮겨도_그대로이고_시작이_끝을_넘으면_저장하지_않는다() throws {
        let kit = Kit()
        let sheet = kit.app.careSheet(.new(.feed, day: nil))
        let now = kit.clock
        sheet.edit { $0.setStart(now.addingTimeInterval(-3600)) }
        sheet.edit { $0.setEnd(picked: now.addingTimeInterval(-1800)) }
        let end = try XCTUnwrap(sheet.draft?.end)
        sheet.edit { $0.setStart(now.addingTimeInterval(-900)) }   // 시작을 끝 뒤로
        XCTAssertEqual(sheet.draft?.end, end)
        sheet.pick(chip: 0)                                          // 몇 분 전 칩 '지금'도 끝을 당기지 않는다
        XCTAssertEqual(sheet.draft?.end, end)
        sheet.save()
        XCTAssertEqual(sheet.phase, .failed("끝 시각이 시작보다 빨라요", problem: .endBeforeStart, attempt: 1))
        XCTAssertTrue(kit.app.care.care.events.filter { $0.kind == .feed }.isEmpty)
    }

    /// 저장 검사에 걸리면 그 검사가 단계에 남고, 탓이 된 줄을 펼친다(시각 → 시작, 끝 → 끝, 양 → 양 칸).
    func test_저장_검사에_걸리면_탓이_된_줄을_펼친다() throws {
        let kit = Kit()
        let now = kit.clock
        let feed = kit.app.careSheet(.new(.feed, day: nil))
        feed.edit { $0.setStart(now.addingTimeInterval(3600)) }   // 앞으로의 시각
        feed.save()
        guard case .failed(let m, let p?, _) = feed.phase else { return XCTFail("검사에 걸리지 않았다") }
        XCTAssertEqual(m, CareProblem.futureStart.message(for: .feed))
        XCTAssertEqual(CareDraft.field(for: p), .start)
        var folds = SheetFolds()
        folds.toggle(.end)
        folds.reveal(try XCTUnwrap(CareDraft.field(for: p)))
        XCTAssertEqual(folds.open, .start)
        let end = kit.app.careSheet(.new(.feed, day: nil))
        end.edit { $0.setStart(now.addingTimeInterval(-3600)); $0.setEnd(picked: now.addingTimeInterval(-1800)); $0.setStart(now.addingTimeInterval(-900)) }
        end.save()
        guard case .failed(_, let ep?, _) = end.phase else { return XCTFail("끝내기 단계가 실패가 아니다") }
        XCTAssertEqual(CareDraft.field(for: ep), .end)
        let bottle = kit.app.careSheet(.new(.feed, day: nil))
        bottle.edit { $0.side = .bottle; $0.amount = 500 }
        bottle.save()
        guard case .failed(_, let bp?, _) = bottle.phase else { return XCTFail("젖병 단계가 실패가 아니다") }
        XCTAssertEqual(CareDraft.field(for: bp), .amount)
        // 저장 실패(저장 공간)는 줄이 아니라 단추 위
        kit.careStore.failing = true
        let diaper = kit.app.careSheet(.new(.diaper, day: nil))
        diaper.save()
        XCTAssertEqual(diaper.phase, .failed(NoticeRules.saveFailed, problem: nil, attempt: 1))
    }

    /// 앞으로의 시각은 미래인 쪽 줄을 탓한다(시작을 고쳐도 풀리지 않는 끝 미래를 시작 줄에 붙이지 않는다).
    private func futureField(start: TimeInterval, end: TimeInterval?) -> SheetField? {
        let kit = Kit()
        let now = kit.clock
        let sheet = kit.app.careSheet(.new(.feed, day: nil))
        sheet.edit { d in d.setStart(now.addingTimeInterval(start)); if let end { d.setEnd(picked: now.addingTimeInterval(end)) } }
        sheet.save()
        guard case .failed(let m, let p?, _) = sheet.phase else { XCTFail("검사에 걸리지 않았다"); return nil }
        XCTAssertEqual(m, "앞으로의 시각은 적을 수 없어요")
        return CareDraft.field(for: p)
    }

    func test_끝만_미래면_끝_줄을_탓한다() {
        XCTAssertEqual(futureField(start: -3600, end: 3600), .end)   // 지금 오후 2시에 시작 1시·끝 3시
    }

    func test_시작만_미래면_시작_줄을_탓한다() {
        XCTAssertEqual(futureField(start: 1800, end: nil), .start)
    }

    func test_시작과_끝이_둘_다_미래면_시작_줄을_탓한다() {
        XCTAssertEqual(futureField(start: 1800, end: 3600), .start)
    }

    /// 지우지 않은 손 기록이 없을 때만 첫 기록 도움말(지운 기록은 세지 않는다).
    func test_손_기록이_없을_때만_첫_기록_도움말을_보인다() throws {
        let kit = Kit()
        let first = kit.app.careSheet(.new(.diaper, day: nil))
        XCTAssertTrue(first.firstRecord)
        first.save()
        XCTAssertFalse(kit.app.careSheet(.new(.diaper, day: nil)).firstRecord)
        let id = try XCTUnwrap(kit.app.care.care.events.first?.id)
        kit.app.recordCare.delete(id)
        XCTAssertTrue(kit.app.careSheet(.new(.feed, day: nil)).firstRecord)
    }

    /// '지난 기록 적기'는 종류 칸(빠른 기록 순서)이 있고 처음은 수유다. 종류를 바꿔도 시각은 그대로.
    func test_지난_기록_적기는_종류를_고를_수_있고_처음은_수유다() throws {
        let kit = Kit()
        let past = kit.app.careSheet(.new(.feed, day: "2026-11-08", chooseKind: true))
        XCTAssertEqual(past.kinds, [.feed, .note])   // 잠·기저귀를 켜지 않았다
        XCTAssertEqual(past.draft?.kind, .feed)
        let start = past.draft?.start
        past.setKind(.note)
        XCTAssertEqual(past.draft?.kind, .note)
        XCTAssertEqual(past.draft?.start, start)
        past.setKind(.sleep)                         // 칸에 없는 종류는 받지 않는다
        XCTAssertEqual(past.draft?.kind, .note)
        kit.app.recordCare.setSleepDiaper(true)
        XCTAssertEqual(kit.app.careSheet(.new(.feed, day: nil, chooseKind: true)).kinds, [.feed, .note, .sleep, .diaper])
        XCTAssertEqual(kit.app.careSheet(.new(.feed, day: nil)).kinds, [])   // 오늘 빠른 기록은 종류 칸이 없다
        let diaper = kit.app.careSheet(.new(.feed, day: nil, chooseKind: true))
        diaper.setKind(.diaper)
        XCTAssertEqual(diaper.draft?.diaper, .wet)   // 새 기저귀는 소변으로 시작
    }

    /// 새 기록 id는 시트를 열 때 정한다(안드로이드 sheetId). 저장해 닫히는 사이 또 누른 저장은 아무것도 하지 않고, 실패 뒤 다시 저장해도 같은 기록이다.
    func test_시트_저장을_빠르게_두_번_눌러도_기록은_하나다() throws {
        let kit = Kit()
        let sheet = kit.app.careSheet(.new(.diaper, day: nil))
        let id = try XCTUnwrap(sheet.draft?.sheetId)
        sheet.save()
        sheet.save()
        XCTAssertEqual(sheet.phase, .closed(saved: true))
        XCTAssertEqual(kit.app.care.care.events.filter { $0.kind == .diaper }.map(\.id), [id])

        kit.careStore.failing = true
        let note = kit.app.careSheet(.new(.note, day: nil))
        note.edit { $0.note = "메모" }
        note.save()
        XCTAssertEqual(note.phase, .failed(NoticeRules.saveFailed, attempt: 1))
        kit.careStore.failing = false
        note.save()   // 실패 뒤 다시 저장
        note.save()
        XCTAssertEqual(note.phase, .closed(saved: true))   // 실패 뒤 다시 저장
        XCTAssertEqual(kit.app.care.care.events.filter { $0.kind == .note }.map(\.id), [note.draft?.sheetId])
    }

    /// 타이머의 쪽·양은 뷰모델이 고친다: 젖병이 아니면 양을 비우고(normalized), 시작 시각이 겹치면 묻는다.
    func test_타이머는_쪽을_바꾸면_젖병이_아닐_때_양을_비우고_시각_겹침을_묻는다() {
        let kit = Kit()
        let today = kit.app.today()
        today.start(.feed)
        let id = try! XCTUnwrap(CareRules.inProgress(.feed, in: today.ui.care.events)).id
        let timer = kit.app.timer(id: id)
        timer.setSide(.bottle)
        timer.setAmount(120)
        XCTAssertEqual(timer.ui.event?.amountMl, 120)
        timer.setSide(.left)
        XCTAssertEqual(timer.ui.event?.feedSide, .left)
        XCTAssertNil(timer.ui.event?.amountMl)
        XCTAssertNil(timer.error)
        // 시작을 앞으로 옮기면 시각 검사 글이 시트 안에 보인다
        XCTAssertFalse(timer.setTime(.start, to: kit.clock.addingTimeInterval(3600)))
        XCTAssertEqual(timer.error, CareProblem.futureStart.message(for: .feed))
    }

    /// 시트를 닫거나 다시 녹음하면 녹음 장치가 그 파일을 지운다(파일 지우기는 SongRecorder 한 곳).
    func test_녹음을_버리면_녹음_장치가_그_파일을_지운다() async throws {
        let recorder = FakeSongRecorder()
        let rec = RecorderViewModel(recorder: recorder)
        await rec.start()
        rec.stop()
        let url = try XCTUnwrap(rec.fileURL)
        rec.discard()
        XCTAssertEqual(recorder.discarded, [url])
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertEqual(rec.phase, .idle)
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
        rec.sent()
        // 닫히는 사이: 녹음 뒤 화면 그대로(길이 유지), 파일은 지웠고 다시 보낼 녹음이 없다(RecordSheet.send는 fileURL이 없으면 멈춘다)
        XCTAssertEqual(rec.phase, .recorded)
        XCTAssertEqual(rec.elapsed, 12)
        XCTAssertNil(rec.fileURL)
        XCTAssertEqual(recorder.discarded, [url])
        rec.discard()   // 시트가 닫힐 때
        XCTAssertEqual(rec.phase, .idle)
        XCTAssertFalse(recorder.isActive)
        XCTAssertEqual(songs.ui.songs.count, 1)
    }
}
