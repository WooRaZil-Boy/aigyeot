import Foundation
import Observation

// 앱 범위 화면 상태(안드로이드 AppViewModels.kt): 루트 이동·배너·열 알림·연결 링크, 연결·확인, 위쪽 띠.

/// 루트 경로(계약 7.1 화면 이동 지도). 연결 화면은 연결이 없을 때만 탭 위에 뜬다(U7).
enum AppRoute: Equatable { case intro, gone, connect, main }

/// 경로는 원본에서 바로 나온다(P4): GONE → 첫 안내 → 연결 흐름 → 탭(안드로이드 startRoute와 같은 순서).
/// GONE은 연결한 적이 있어야 생기고 연결하면 안내를 넘긴 것이라, 안내 앞에 두어도 지금 iOS와 결과가 같다.
func appRoute(connection: Connection, introSeen: Bool, connectShown: Bool) -> AppRoute {
    if case .gone = connection { return .gone }
    if !introSeen { return .intro }
    return connection.creds == nil && connectShown ? .connect : .main
}

struct AppUiState: Equatable {
    var route: AppRoute
    var connection: Connection
    var pairRun: PairRun?
    var isPairLocked: Bool
    /// 앞에 있을 때 띄운 인계 알림 배너의 알림 id(D4 a). 글은 `bannerText`.
    var banner: String?
    /// 배너 글: 본문이 있으면 차가 만든 문장, 아직 없으면 고정 문구(그릴 때 계산, P4).
    var bannerText: String?
    /// 열린 인계 알림 상세의 id. 열려는 알림의 본문이 저장돼 있을 때만 있다(본문이 오면 저절로 열린다).
    var openAlert: String?

    var isPaired: Bool { connection.creds != nil }
    var role: PhoneRole? { connection.creds?.role }
}

/// 첫 실행 → 안내 → (연결) → 탭(계약 7.1·7.6, G16·G17·U52·U62), 인계 알림 배너와 열 알림(5.3·5.6).
/// 확인 시트의 코드·연결 화면을 띄웠는지·링크 오류·안내에서 받은 코드·열려는 알림은 화면 상태라 여기에 둔다.
/// 연결은 Pair, 끊기는 Disconnect·LoseConnection이 한다.
@MainActor
@Observable
final class AppViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let pair: Pair
    @ObservationIgnored private let notices: Notices
    @ObservationIgnored private let lose: LoseConnection
    @ObservationIgnored private let connection: KeepConnected

    /// 확인 화면을 기다리는 코드(QR·링크만, G17. 직접 친 코드는 확인 없이 바로 연결한다).
    /// 이미 연결된 폰이면 확인 화면이 지금 차를 끊을지도 함께 묻는다(G16).
    var pendingCode: PairCode? { didSet { if pendingCode == nil, oldValue != nil { linkError = nil } } }   // 확인 화면이 닫히면 그 화면의 안내도 지운다(다음에 연 화면에 남지 않게)
    private(set) var linkError: String?
    /// 탭(연결 안 됨) 위에 연결 흐름을 띄웠는가(U7). 저장하지 않는다.
    var connectShown = false { didSet { if !connectShown, oldValue { linkError = nil } } }   // 연결 화면이 닫히면 그 화면의 안내도 지운다
    /// U62: 첫 실행 안내 화면에서 받은 연결 링크의 코드(메모리에만, 안드로이드 introCode와 같다).
    @ObservationIgnored private(set) var introCode: PairCode?
    /// 열려는 인계 알림 id(S19). 본문이 저장돼 있으면 상세가 열리고, 없으면 받을 때까지 기다린다(열지는 `ui`가 계산한다).
    /// 수명은 `forget`(L10): 연결이 바뀌거나 기록을 지우면 버리고, 본문을 못 받은 채 뒤로 가면 버린다.
    private var openAlertRequest: String?

    init(state: PhoneStateRepository, pair: Pair, notices: Notices, lose: LoseConnection, connection: KeepConnected) {
        stateRepo = state
        self.pair = pair
        self.notices = notices
        self.lose = lose
        self.connection = connection
    }

    var ui: AppUiState {
        let s = stateRepo.state
        return AppUiState(route: appRoute(connection: stateRepo.connection, introSeen: s.introSeen, connectShown: connectShown),
                          connection: stateRepo.connection, pairRun: pair.run, isPairLocked: pair.isLocked,
                          banner: notices.banner, bannerText: notices.banner.map { s.alert($0)?.body.text ?? "인계 알림이 왔어요" },
                          openAlert: openAlertRequest.flatMap { s.alert($0) != nil ? $0 : nil })
    }

    /// 본문을 기다리는 열 알림(시험이 본다).
    var pendingOpenAlert: String? { openAlertRequest.flatMap { stateRepo.state.alert($0) == nil ? $0 : nil } }

    // MARK: 연결 흐름

    /// 링크·QR이 이 길로 온다. 확인 화면을 거치고 바로 연결하지 않는다(G17).
    func receive(link url: URL) {
        guard let code = PairCode.parse(link: url) else {
            let text = NoticeRules.badLink
            // 연결·확인 화면이 떠 있으면 그 화면이, 탭 화면이면 띠가 보인다(안드로이드는 토스트). 나중에 연 연결 화면에 옛 문구로 남지 않게
            if connectShown || pendingCode != nil { linkError = text } else { notices.addLine(text) }
            return
        }
        // U52: 연결 작업이 도는 동안 온 링크는 버리고 알린다. 첫 실행 안내에서 연 확인 시트의 연결 중에도 같다(안드로이드 onLink와 같은 순서)
        guard pair.canTakeLink() else { showBusy(); return }
        // U62: 첫 실행 안내 화면에서 받은 링크는 안내를 그대로 두고 코드만 쥔다('차와 연결하기'에서 확인 화면을 연다). 무엇이 오가는지 읽기 전에 연결되지 않게
        if !stateRepo.state.introSeen && !stateRepo.isPaired { introCode = code; return }   // '연결도 기록만도 고른 적 없는 첫 실행'(U62)
        propose(code)
    }

    /// 안내 화면의 '차와 연결하기'(U62): 안내에서 받은 코드가 있으면 그 코드로 확인 화면을 연다(안내는 그대로 두고, 연결되면 넘어간다).
    /// 한 번만 쓴다: 확인 화면을 닫고 다시 누르면 연결 방법 화면이다.
    func connectFromIntro() {
        if let c = introCode { introCode = nil; propose(c); return }
        pair.markIntroSeen()
        connectShown = true
    }

    /// 안내 화면의 '먼저 기록만 쓸게요': 안내에서 받은 코드는 버린다(U62).
    func recordsOnlyFromIntro() {
        introCode = nil
        pair.markIntroSeen()
    }

    /// QR·링크의 코드로 확인 화면을 연다. U52: 연결 작업(끊고 연결의 끊기 포함)이 도는 동안은 옮기지 않는다(끝난 결과를 받을 화면이 닫힌다).
    func propose(_ code: PairCode) {
        guard pair.canTakeLink() else { showBusy(); return }
        linkError = nil
        pendingCode = code
    }

    /// `pair.busy`를 짧게 보인다. 그사이 다른 문구로 바뀌었으면 건드리지 않는다.
    private func showBusy() {
        linkError = CareText.pairBusy
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(RetryPolicy.pairBusyNotice))
            if self?.linkError == CareText.pairBusy { self?.linkError = nil }
        }
    }

    /// 코드 넣기 화면이 직접 친 코드로 연결을 시작한다: 지난 링크 안내를 걷는다.
    func clearLinkError() { linkError = nil }

    /// 연결 작업이 끝났다(ConnectViewModel이 넘긴 Pair.start의 `then`). 연결되면 연결 흐름을 닫고 오늘 탭으로 간다(5장).
    func pairFinished(_ r: PairRun) {
        guard case .done = r else { return }
        pendingCode = nil
        connectShown = false
    }

    /// '연결이 끊겼어요' 화면의 '기록만 계속 쓸게요'(U25 a).
    func continueWithoutCar() { lose.acknowledgeGone() }

    /// '연결이 끊겼어요' 화면의 '다시 연결하기'(U25 a): 연결 흐름으로 간다.
    func reconnectAfterGone() {
        if lose.acknowledgeGone() { connectShown = true }
    }

    /// 연결 안 됨의 '차와 연결하기'(7장 차 타일).
    func showConnect() { connectShown = true }
    /// 연결 화면의 '취소'(U7): 연결하지 않고 탭으로 돌아간다.
    func cancelConnect() { connectShown = false }

    // MARK: 인계 알림(5.3·5.6)

    /// 알림 상세를 연다: 본문이 저장돼 있으면 바로, 아니면 본문이 저장될 때.
    func open(_ id: String) { openAlertRequest = id }

    /// 알림을 눌러 앱이 열렸을 때: 받은 함에서 본문을 받아 저장되면 상세가 열린다(5.3).
    func onAlert(_ id: String) async {
        open(id)
        await connection.refresh()
    }

    /// 배너를 눌렀다(5.6): 본문이 있으면 상세를 열고, 없으면 받은 뒤 연다. 이 연결에 없는 알림(이전 차의 늦은 푸시)이면 상세를 열지 않는다(빈 상세에 머물지 않게).
    func openBanner(_ id: String) async {
        notices.clearBanner()
        if stateRepo.state.alert(id) != nil { open(id) } else { await onAlert(id) }
    }

    /// 배너 닫기. `id`를 주면 그 배너가 아직 떠 있을 때만(8초).
    func dismissBanner(_ id: String? = nil) { notices.clearBanner(id) }
    /// 알림 상세 시트를 닫았다.
    func closeAlert() { openAlertRequest = nil }
    /// 사용자가 알림 상세를 직접 열었다. 다른 알림을 열려고 기다리던 요청은 버린다(고른 화면 위에 뜨지 않게).
    func userOpenedAlert(_ id: String) { if openAlertRequest != id { openAlertRequest = nil } }

    /// 수명 표의 화면 칸(L10, AppLifecycle.onEvent).
    func forget(_ e: LifecycleEvent) {
        switch e {
        case .connectionChanged, .recordsCleared: openAlertRequest = nil   // 지우는 알림을 가리키는 상세가 다시 연결한 뒤 남지 않게
        case .background: if pendingOpenAlert != nil { openAlertRequest = nil }   // 본문을 못 받은 채 떠났으면 며칠 뒤 다른 화면 위에 상세가 저절로 뜨지 않게
        }
    }
}

// MARK: 연결·확인

struct ConnectUiState: Equatable {
    var run: PairRun?
    /// 연결 429 잠금 중(직접 입력·확인 화면이 같이 본다, 계약 9장).
    var isLocked: Bool
    /// 잠금 끝 시각(글의 남은 시간 계산용).
    var lockedUntil: Date? = nil
    var connected: Bool

    /// 보일 연결 실패(`code`를 주면 그 코드의 실패만, 확인 화면). 429 글은 잠금 동안만(`ConnectFailure.visible`).
    func failure(for code: PairCode? = nil) -> ConnectFailure? {
        guard case .failed(let c, let f) = run, code == nil || c == code else { return nil }
        return f.visible(locked: isLocked)
    }
}

/// 연결 화면(QR·코드 넣기)과 확인 시트. 작업은 Pair가 앱 범위에서 끝까지 하고, 화면은 `run`만 그린다(M2).
@MainActor
@Observable
final class ConnectViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let pair: Pair
    /// 연결 작업이 끝나면 부른다(AppViewModel.pairFinished: 연결 흐름 닫기). 이 화면이 먼저 사라져도 불린다.
    @ObservationIgnored private let finished: @MainActor (PairRun) -> Void

    init(state: PhoneStateRepository, pair: Pair, finished: @escaping @MainActor (PairRun) -> Void) {
        stateRepo = state
        self.pair = pair
        self.finished = finished
    }

    var ui: ConnectUiState { ConnectUiState(run: pair.run, isLocked: pair.isLocked, lockedUntil: pair.lockedUntil, connected: stateRepo.isPaired) }

    /// 직접 친 코드(G17, 확인 없음)와 확인 화면의 연결 버튼(이미 연결돼 있으면 끊고 연결, G16).
    func start(_ code: PairCode) { pair.start(code, then: finished) }
    /// 화면이 닫히면 지난 결과를 걷는다.
    func clear() { pair.clear() }
    func canTakeLink() -> Bool { pair.canTakeLink() }
}

// MARK: 위쪽 띠

/// 위쪽 띠 하나에 그릴 글(D5 a): 일반 안내 다음 돌봄 안내. 둘 다 없으면 띠가 없다.
func bandText(notice: String?, careNotice: String?) -> String? {
    let lines = [notice, careNotice].compactMap { $0 }
    return lines.isEmpty ? nil : lines.joined(separator: "\n")
}

struct StatusUiState: Equatable {
    var status: LinkStatus
    /// 띠 한 칸의 글(`bandText`).
    var notice: String?
    var connection: Connection

    var offline: Bool { !status.online }
    /// '누른 것은 연결되면 차로 가요' 띠는 차에 연결된 폰에만 보인다(U7: 연결 전에는 차로 보내지 않는다, 안드로이드와 같다).
    var showsOfflineBand: Bool { offline && connection.creds != nil }
    var clockWarning: String? { status.clockWarning }
    var guardian: Bool { connection.creds?.role == .guardian }
}

/// 위쪽 띠: 오프라인, 시계 차이, 안내(7.2). 모든 탭이 같은 띠를 본다.
@MainActor
@Observable
final class StatusViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let connection: KeepConnected
    @ObservationIgnored private let notices: Notices
    @ObservationIgnored private let outbox: SendOutbox

    init(state: PhoneStateRepository, connection: KeepConnected, notices: Notices, outbox: SendOutbox) {
        stateRepo = state
        self.connection = connection
        self.notices = notices
        self.outbox = outbox
    }

    var ui: StatusUiState {
        StatusUiState(status: connection.status, notice: bandText(notice: notices.notice, careNotice: notices.careNotice),
                      connection: stateRepo.connection)
    }

    /// 띠의 '다시 시도'·당겨서 새로고침(7.2, 7.5).
    func retryNow() async { await connection.retryNow() }
    /// 띠의 '닫기'(U48·U54): 두 칸을 걷고 저장된 버린 메시지 안내도 지운다.
    func dismissNotice() { outbox.dismissNotice() }
}
