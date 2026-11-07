import Foundation
import Observation

/// 루트 화면의 상태(계약 7.1 화면 이동 지도). 도메인 원본에서 계산한다.
struct RootState: Equatable {
    enum Route: Equatable { case intro, gone, connect, main }
    var route: Route
    var isPaired: Bool
    var isPairLocked: Bool
    var role: PhoneRole?
}

/// 첫 실행 → 안내 → (연결) → 탭(계약 7.1·7.6, G16·G17·U52·U62).
/// 확인 시트의 코드·연결 화면을 띄웠는지·링크 오류·안내에서 받은 코드는 화면 상태라 여기에 둔다. 연결 요청은 Pair, 끊기는 Disconnect가 한다.
@MainActor
@Observable
final class RootViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let pairing: Pair
    @ObservationIgnored private let disconnect: Disconnect
    @ObservationIgnored private let notices: NoticeBoard
    @ObservationIgnored private let lifecycle: Lifecycle

    /// 확인 화면을 기다리는 코드(QR·링크만, G17. 직접 친 코드는 `connect(typed:)`로 바로 연결한다).
    /// 이미 연결된 폰이면 확인 화면이 지금 차를 끊을지도 함께 묻는다(G16).
    var pendingCode: PairCode? { didSet { if pendingCode == nil, oldValue != nil { linkError = nil } } }   // 확인 화면이 닫히면 그 화면의 안내도 지운다(다음에 연 화면에 남지 않게)
    private(set) var linkError: String?
    /// 탭(연결 안 됨) 위에 연결 흐름을 띄웠는가(U7). 저장하지 않는다.
    var connectShown = false { didSet { if !connectShown, oldValue { linkError = nil } } }   // 연결 화면이 닫히면 그 화면의 안내도 지운다
    /// U62: 첫 실행 안내 화면에서 받은 연결 링크의 코드(메모리에만, 안드로이드 introCode와 같다).
    @ObservationIgnored private(set) var introCode: PairCode?
    /// 확인 화면의 '연결하기'·'끊고 연결하기'가 도는 동안(끊기 요청 포함). 그사이 온 새 링크는 받지 않는다(U52).
    @ObservationIgnored private var confirming = false
    @ObservationIgnored private var busyTask: Task<Void, Never>?

    init(state: PhoneStateRepository, pair: Pair, disconnect: Disconnect, notices: NoticeBoard, lifecycle: Lifecycle) {
        stateRepo = state
        pairing = pair
        self.disconnect = disconnect
        self.notices = notices
        self.lifecycle = lifecycle
    }

    var state: RootState {
        let gone = stateRepo.connection == .gone
        let route: RootState.Route = !stateRepo.value.introSeen ? .intro : gone ? .gone : (!stateRepo.isPaired && connectShown) ? .connect : .main
        return RootState(route: route, isPaired: stateRepo.isPaired, isPairLocked: pairing.isLocked, role: stateRepo.role)
    }

    /// 링크·QR이 이 길로 온다. 확인 화면을 거치고 바로 연결하지 않는다(G17).
    func receive(link url: URL) {
        guard let code = PairCode.parse(link: url) else {
            let text = NoticeRules.badLink
            // 연결·확인 화면이 떠 있으면 그 화면이, 탭 화면이면 띠가 보인다(안드로이드는 토스트). 나중에 연 연결 화면에 옛 문구로 남지 않게
            if connectShown || pendingCode != nil { linkError = text } else { notices.add(text) }
            return
        }
        // U62: 첫 실행 안내 화면에서 받은 링크는 안내를 그대로 두고 코드만 쥔다('차와 연결하기'에서 확인 화면을 연다). 무엇이 오가는지 읽기 전에 연결되지 않게
        if !stateRepo.value.introSeen && !stateRepo.isPaired { introCode = code; return }   // '연결도 기록만도 고른 적 없는 첫 실행'(U62)
        propose(code)
    }

    /// 안내 화면의 '차와 연결하기'(U62): 안내에서 받은 코드가 있으면 그 코드로 확인 화면을 연다(안내는 그대로 두고, 연결되면 넘어간다).
    /// 한 번만 쓴다: 확인 화면을 닫고 다시 누르면 연결 방법 화면이다.
    func connectFromIntro() {
        if let c = introCode { introCode = nil; propose(c); return }
        pairing.markIntroSeen()
        connectShown = true
    }

    /// 안내 화면의 '먼저 기록만 쓸게요': 안내에서 받은 코드는 버린다(U62).
    func recordsOnlyFromIntro() {
        introCode = nil
        pairing.markIntroSeen()
    }

    /// QR·링크의 코드로 확인 화면을 연다.
    func propose(_ code: PairCode) {
        // 직접 친 코드의 요청이 진행 중이면 확인 화면으로 옮기지 않는다(끝난 결과를 받을 화면이 닫힌다).
        // U52: 끊고 연결의 끊기 중에도 막는다. 화면 코드만 바뀌고 연결은 앞 코드로 가는 어긋남을 막는다.
        guard !pairing.pairing, !confirming else { showBusy(); return }
        linkError = nil
        pendingCode = code
    }

    /// `pair.busy`를 짧게 보인다. 그사이 다른 문구로 바뀌었으면 건드리지 않는다.
    private func showBusy() {
        linkError = CareText.pairBusy
        busyTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(RetryPolicy.pairBusyNotice))
            if self?.linkError == CareText.pairBusy { self?.linkError = nil }
        }
    }

    /// 코드 넣기 화면에서 직접 친 코드는 확인 없이 바로 연결한다(G17, L57). 오류는 호출한 화면이 보인다.
    /// 코드 넣기 화면은 연결되지 않았을 때만 보이므로 바꾸기 확인(G16)은 거치지 않는다.
    func connect(typed code: PairCode) async -> ConnectFailure? {
        linkError = nil
        return await pair(code)
    }

    /// 확인 화면의 연결 버튼이 Task를 띄우기 전에 부른다: 그사이 온 새 링크가 화면 코드를 바꾸지 않게(U52).
    func beginConfirm() { confirming = true }
    func endConfirm() { confirming = false }

    /// 확인 화면의 연결 버튼. 이미 연결돼 있으면 이 버튼 하나로 지금 연결을 끊고 새 차에 연결한다(G16, 안드로이드 `startPair`와 같다).
    /// 확인하기 전에는 지금 연결을 지우지 않는다. 그사이 끊겼으면(401) 끊기를 거치지 않는다(U13 ②).
    func confirmPair(_ code: PairCode) async -> ConnectFailure? {
        confirming = true
        defer { confirming = false }
        if stateRepo.isPaired, let message = await disconnect.run() { return .disconnectFailed(message) }
        return await pair(code)
    }

    /// 연결 요청(Pair). 연결되면 연결 흐름을 닫고 오늘 탭으로 간다(5장).
    func pair(_ code: PairCode) async -> ConnectFailure? {
        let failure = await pairing.run(code)
        if failure == nil {
            pendingCode = nil
            connectShown = false
        }
        return failure
    }

    /// '연결이 끊겼어요' 화면의 '기록만 계속 쓸게요'(U25 a).
    func continueWithoutCar() { lifecycle.leaveGone() }

    /// '연결이 끊겼어요' 화면의 '다시 연결하기'(U25 a): 연결 흐름으로 간다.
    func reconnectAfterGone() {
        lifecycle.leaveGone()
        connectShown = true
    }

    /// 연결 안 됨의 '차와 연결하기'(7장 차 타일).
    func showConnect() { connectShown = true }
    /// 연결 화면의 '취소'(U7): 연결하지 않고 탭으로 돌아간다.
    func cancelConnect() { connectShown = false }
}
