import Foundation
@testable import Aigyeot

typealias RecorderModel = RecorderViewModel

/// 구조를 나누기 전 시험이 쓰던 이름(`AppModel`)을 그대로 쓰게 하는 시험 전용 길(arch-phone-ios 9장).
/// 합성 루트(AppContainer)를 하나 만들어 쥐고, 호출 대상만 화면이 쓰는 같은 뷰모델·합성 루트의 유스케이스로 넘긴다.
/// 제품 코드에는 넘김 함수를 두지 않는다. 시험의 기대값은 바꾸지 않는다.
@MainActor
final class AppModel {
    let app: AppContainer

    init(relay: RelayClient, store: StateStore, credentials: CredentialStore, careStore: CareStore = MemoryCareStore(),
         baseURL: URL? = nil, now: @escaping () -> Date = Date.init, sendsCareSummary: Bool = true) {
        app = AppContainer(relay: relay, store: store, credentials: credentials, careStore: careStore, baseURL: baseURL, now: now,
                           sendsCareSummary: sendsCareSummary)
    }

    static func pairLockSeconds(_ retryAfter: TimeInterval?) -> TimeInterval { RetryPolicy.pairLock(retryAfter: retryAfter) }

    // MARK: 읽기
    var state: PhoneState { app.state.value }
    var care: CareFile { app.care.value }
    var credentials: Credentials? { app.state.credentials }
    var role: PhoneRole? { app.state.role }
    var isPaired: Bool { app.state.isPaired }
    var gone: Bool { app.root.state.route == .gone || app.state.connection == .gone }
    var offline: Bool { app.home.state.offline }
    var showsOfflineBand: Bool { app.home.state.showsOfflineBand }
    var clockWarning: String? { app.home.state.clockWarning }
    var car: DeviceInfo? { app.home.state.car }
    var visibleSongs: [SongRow] { app.songs.state.visibleSongs }
    var songSlotsUsed: Int { app.songs.state.songSlotsUsed }
    var lastFeedingStart: Date? { app.today.state.lastFeedingStart }
    func shownStart(_ e: CareEvent) -> Date { app.today.state.shownStart(e) }
    func alert(_ id: String) -> StoredAlert? { app.home.state.alert(id) }
    func feedingRecord(for e: CareEvent) -> FeedingRecord? { app.today.state.feedingRecord(for: e) }
    var isPairLocked: Bool { app.root.state.isPairLocked }
    var handoffBusy: Bool { app.settings.state.handoffBusy }
    var recentlyDeleted: String? { app.undo.recentlyDeleted }
    var linkError: String? { app.root.linkError }
    var introCode: PairCode? { app.root.introCode }
    var pendingOpenAlertId: String? { app.showAlert.pendingOpenAlertId }
    var socket: RelaySocket? { app.syncInbox.socket }
    var socketConnected: Bool { app.link.socketConnected }
    var scheduledRefreshDelay: UInt64? { app.syncInbox.scheduledRefreshDelay }

    // MARK: 시험이 바꾸는 칸
    var notice: String? {
        get { app.home.state.notice }
        set { app.notices.replace(with: newValue) }
    }
    var bannerAlertId: String? {
        get { app.home.state.bannerAlertId }
        set { if let id = newValue { app.showAlert.raise(id) } else { app.home.dismissBanner() } }
    }
    var openAlertId: String? {
        get { app.home.state.openAlertId }
        set { if let id = newValue { app.showAlert.showDetail(id) } else { app.home.closeAlert() } }
    }
    var pendingCode: PairCode? {
        get { app.root.pendingCode }
        set { app.root.pendingCode = newValue }
    }
    var connectShown: Bool {
        get { app.root.connectShown }
        set { app.root.connectShown = newValue }
    }
    var autoFlush: Bool {
        get { app.sendOutbox.autoFlush }
        set { app.sendOutbox.autoFlush = newValue }
    }
    var sendsCareSummary: Bool {
        get { app.reconcileCareSummary.sendsCareSummary }
        set { app.reconcileCareSummary.sendsCareSummary = newValue }
    }
    var timeZone: () -> TimeZone {
        get { app.reconcileCareSummary.timeZone }
        set { app.reconcileCareSummary.timeZone = newValue }
    }
    var pushToken: PushTokenSource? {
        get { app.syncPushToken.source }
        set { app.syncPushToken.source = newValue }
    }
    var onCareChange: ((CareFile) -> Void)? {
        get { app.recordCare.onCareChange }
        set { app.recordCare.onCareChange = newValue }
    }

    // MARK: 화면 동작(뷰모델)
    func dismissNotice() { app.home.dismissNotice() }
    func retryNow() async { await app.home.retryNow() }
    func openBanner(_ id: String) async { await app.home.openBanner(id) }
    func userOpenedAlert(_ id: String) { app.home.userOpenedAlert(id) }
    func receive(link url: URL) { app.root.receive(link: url) }
    func propose(_ code: PairCode) { app.root.propose(code) }
    func connectFromIntro() { app.root.connectFromIntro() }
    func recordsOnlyFromIntro() { app.root.recordsOnlyFromIntro() }
    func connect(typed code: PairCode) async -> ConnectFailure? { await app.root.connect(typed: code) }
    func beginConfirm() { app.root.beginConfirm() }
    func endConfirm() { app.root.endConfirm() }
    func confirmPair(_ code: PairCode) async -> ConnectFailure? { await app.root.confirmPair(code) }
    func pair(_ code: PairCode) async -> ConnectFailure? { await app.root.pair(code) }
    func continueWithoutCar() { app.root.continueWithoutCar() }
    func reconnectAfterGone() { app.root.reconnectAfterGone() }
    func disconnect() async -> String? { await app.settings.disconnect() }
    func clearLocalRecords() { app.settings.clearLocalRecords() }
    func createInvite() async -> (InviteResponse?, String?) { await app.settings.createInvite() }
    func setHandoffAlerts(_ on: Bool) async -> String? { await app.settings.setHandoffAlerts(on) }
    @discardableResult func refreshDevices() async -> Bool { await app.refreshDevices.run() }
    func startFeeding(side: FeedSide? = nil) { app.recordCare.startFeeding(side: side) }
    func startSleep() { app.today.startSleep() }
    func endNow(_ id: String) { app.careSheet.endNow(id) }
    func closeEndUnknown(_ id: String) { app.careSheet.closeEndUnknown(id) }
    func saveEvent(_ event: CareEvent, allowOverlap: Bool = false) -> CareSaveResult { app.careSheet.save(event, allowOverlap: allowOverlap) }
    func deleteEvent(_ id: String) { app.careSheet.delete(id) }
    func undoDelete(_ id: String) { app.undo.undo(id) }
    func setSleepDiaperEnabled(_ on: Bool) { app.settings.setSleepDiaperEnabled(on) }
    func setChildNickname(_ name: String) { app.settings.setChildNickname(name) }
    func addSong(name: String, recording: URL, durationS: Double, replacing old: String? = nil) throws {
        try app.songs.addSong(name: name, recording: recording, durationS: durationS, replacing: old)
    }
    func removeSong(_ id: String) { app.songs.removeSong(id) }
    func selectSong(_ id: String) { app.songs.selectSong(id) }

    // MARK: 앱·시스템 사건(앱 진입점·푸시가 부르는 유스케이스)
    func settle() async { await app.tasks.settle() }
    func flushOutbox() async { await app.sendOutbox.run() }
    @discardableResult func ingest(_ envelopes: [Envelope], live: Bool) async -> Bool { await app.syncInbox.ingest(envelopes, live: live) }
    func socketEvent(_ event: RelaySocketEvent) { app.syncInbox.socketEvent(event) }
    func socketDidConnect(_ up: Bool) { app.syncInbox.socketDidConnect(up) }
    func refresh() async { await app.syncInbox.refresh() }
    func networkPathChanged(satisfied: Bool) async { await app.syncInbox.networkPathChanged(satisfied: satisfied) }
    func didBecomeActive() { app.lifecycle.didBecomeActive() }
    func didEnterBackground() { app.lifecycle.didEnterBackground() }
    func handleUnauthorized() { app.lifecycle.handleUnauthorized() }
    func purgeExpired() { app.lifecycle.purgeExpired() }
    func markIntroSeen() { app.pair.markIntroSeen() }
    func reconcileCareSummary() { app.reconcileCareSummary.run() }
    func sendStateRequestIfNeeded() async { await app.requestState.sendIfNeeded() }
    func pollDevices() async { await app.requestState.afterDevices(epoch: app.visibility.foregroundEpoch) }
    func pushTokenDidChange() { app.syncPushToken.tokenDidChange() }
    func noteServerDate(_ date: Date) { app.link.noteServerDate(date, now: app.now()) }
    func show(_ messageId: String) { app.showAlert.show(messageId) }
    func openAlert(_ id: String) async { await app.showAlert.open(id) }
    func addNotice(_ line: String) { app.notices.add(line) }
}
