import Foundation
import Observation

/// 연결(POST /v1/pair, 계약 7.1·9장). 연결 화면의 흐름(확인 시트·링크 오류)은 Presentation의 RootViewModel이 갖는다.
@MainActor
@Observable
final class Pair {
    @ObservationIgnored private let state: PhoneStateRepository
    @ObservationIgnored private let relay: RelayClient
    @ObservationIgnored private let link: LinkObserver
    @ObservationIgnored private let notices: NoticeBoard
    @ObservationIgnored private let visibility: AppVisibility
    @ObservationIgnored private let lifecycle: Lifecycle
    @ObservationIgnored private let feeding: ReconcileFeeding
    @ObservationIgnored private let summary: ReconcileCareSummary
    @ObservationIgnored private let pushToken: SyncPushToken
    @ObservationIgnored private let now: () -> Date
    /// 연결 429 잠금이 끝나는 시각. 직접 입력·확인 화면이 같이 본다. 화면을 옮기거나 코드를 고쳐도 풀리지 않는다(계약 9장).
    private(set) var lockedUntil: Date?
    /// 연결 요청이 진행 중이면 새 요청을 받지 않는다(안드로이드 startPair의 pairJob?.isActive와 같다). 늦게 온 옛 응답이 새 연결을 덮지 않게 한다.
    @ObservationIgnored private(set) var pairing = false

    init(state: PhoneStateRepository, relay: RelayClient, link: LinkObserver, notices: NoticeBoard, visibility: AppVisibility,
         lifecycle: Lifecycle, feeding: ReconcileFeeding, summary: ReconcileCareSummary, pushToken: SyncPushToken,
         now: @escaping () -> Date) {
        self.state = state
        self.relay = relay
        self.link = link
        self.notices = notices
        self.visibility = visibility
        self.lifecycle = lifecycle
        self.feeding = feeding
        self.summary = summary
        self.pushToken = pushToken
        self.now = now
    }

    /// 429 잠금이 남았는가. 시각은 `now` 시계로 본다.
    var isLocked: Bool { lockedUntil.map { $0 > now() } ?? false }

    /// 첫 안내를 넘겼다(연결하기·기록만 쓰기, U62).
    func markIntroSeen() { state.update { $0.introSeen = true } }

    func run(_ code: PairCode) async -> ConnectFailure? {
        guard !pairing else { return .busy }
        if isLocked, let until = lockedUntil { return .rateLimited(until.timeIntervalSince(now())) }
        pairing = true
        defer { pairing = false }
        // 연결 요청의 결과도 띠에 반영한다(9장, 끊긴 동안 켜진 띠가 다시 연결한 뒤 남지 않게). 성공하면 세대가 바뀌어 observed 대신 직접 본다
        let mark = link.mark()
        do {
            let r = try await relay.pair(code: code.value, pushToken: pushToken.token)
            link.observe(nil, since: mark)
            guard let role = PhoneRole(rawValue: r.role.rawValue) else { return .other }
            // 연결 세대가 바뀐다. 이전 연결의 응답은 이제 버려진다.
            // 이전 상태·보낼 함을 지우는 저장과 Keychain 저장이 성공해야만 새 자격을 켠다(이전 차의 보낼 함이 새 차로 가지 않게).
            let c = Credentials(carId: r.carId, deviceId: r.deviceId, token: r.deviceToken, role: role)
            guard lifecycle.switchConnection(.connect(c)) == .done else { return await discard(c) }
            let t = now()
            state.update { st in
                st.introSeen = true   // 첫 안내를 넘기지 않고 링크로 연결해도 홈으로 간다(7.1 DONE → HOME, 안드로이드와 같다)
                st.carId = r.carId
                st.lastReceivedAt = t   // 연결 시각을 기준으로 둬 차 메시지를 한 번도 못 받아도 24시간 재요청이 걸린다(안드로이드 paired와 같다)
                st.handoffAlerts = role == .guardian ? true : nil   // 기본값은 guardian 참, owner 거짓(G12)
            }
            notices.replace(with: NoticeRules.paired)
            feeding.run()   // 연결 직후(R6): 연결 전에 적은 24시간 안의 수유를 보낸다
            summary.run()   // 같은 계기(R19)
            if visibility.active { lifecycle.didBecomeActive() }   // 연결 직후 받기·동기화를 바로 시작한다
            return nil
        } catch let e as RelayError {
            link.observe(e, since: mark)
            switch (e.status, e.code) {
            case (404, _): return .codeInvalid
            case (429, _):
                lock(e.retryAfter)
                return .rateLimited(e.retryAfter)
            case (400, "bad_code_format"): return .badCode
            default: return e.isOffline ? .offline : .other
            }
        } catch {
            return .other
        }
    }

    /// 중계는 연결했는데 이 폰에 저장하지 못했다: 차에 토큰 없는 폰이 남지 않게 방금 받은 토큰으로 그 연결을 지운다(안드로이드 discardPaired와 같다).
    /// 못 지우면 토큰을 남길 곳도 없으니 차 화면에서 끊게 알린다. 401은 이미 지워진 것이다.
    private func discard(_ c: Credentials) async -> ConnectFailure {
        do { try await relay.deleteMe(token: c.token); return .notSaved(removed: true) }
        catch let e as RelayError { return .notSaved(removed: e.isUnauthorized) }
        catch { return .notSaved(removed: false) }
    }

    private func lock(_ retryAfter: TimeInterval?) {
        let secs = RetryPolicy.pairLock(retryAfter: retryAfter)
        let until = now().addingTimeInterval(secs)
        lockedUntil = until
        // 끝나는 때 화면이 다시 그려지게 값을 비운다. 그 사이 새 429가 와서 시각이 바뀌었으면 건드리지 않는다.
        // 허용 오차를 0으로 준다. 기본값으로는 기다린 시간의 약 6.5%만큼 늦게 깨어 589초 잠금이 39초 늦게 풀렸다(시뮬레이터 실측).
        // 시험의 settle()이 잠금 시간만큼 기다리지 않게 작업 묶음에 넣지 않는다(잠금 시각만 비우는 타이머다)
        unlockTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(secs), tolerance: .zero, clock: .continuous)
            if self?.lockedUntil == until { self?.lockedUntil = nil }
        }
    }
    @ObservationIgnored private var unlockTask: Task<Void, Never>?
}

/// 설정의 '연결 끊기'와 '끊고 연결하기'의 끊기(U25 b, G16). 중계의 토큰을 지워야 해서 인터넷이 필요하다.
@MainActor
final class Disconnect {
    private let state: PhoneStateRepository
    private let relay: RelayClient
    private let gate: RelayGate
    private let notices: NoticeBoard
    /// 연결을 비운다(Lifecycle.leaveConnection). Lifecycle이 이 클래스의 `absorbUnauthorized`를 써서 합성 루트가 잇는다.
    private let leave: (PhoneStateRepository.Switch) -> Void
    /// 끊기 중에 온 이 연결의 401·4001을 끊기가 끝난 뒤 처리한다(Lifecycle.handleUnauthorized).
    private let lose: () -> Void
    /// 사용자가 요청한 끊기 중(안드로이드 `leaving`과 같다). 그사이 온 4001·401은 강제 해제가 아니라 이 끊기의 결과다.
    private var leaving = false
    /// 끊기 중에 온 401·4001의 연결 세대.
    private var lostWhileLeaving: Int?
    private var leavingTask: Task<String?, Never>?

    init(state: PhoneStateRepository, relay: RelayClient, gate: RelayGate, notices: NoticeBoard,
         leave: @escaping (PhoneStateRepository.Switch) -> Void, lose: @escaping () -> Void) {
        self.state = state
        self.relay = relay
        self.gate = gate
        self.notices = notices
        self.leave = leave
        self.lose = lose
    }

    /// 실패하면 문구를 돌려준다.
    func run() async -> String? {
        // 설정의 끊기와 '끊고 연결하기'의 끊기가 겹치면 뒤에 온 쪽은 앞의 결과를 기다린다(끊기 중 받은 401·4001 표시를 지우지 않게).
        if let running = leavingTask { return await running.value }
        let task = Task { await perform() }
        leavingTask = task
        let result = await task.value
        leavingTask = nil
        return result
    }

    /// 끊는 중에 온 401·4001이면 기억하고 true(끊기가 끝날 때 GONE인지 본다).
    func absorbUnauthorized() -> Bool {
        guard leaving else { return false }
        lostWhileLeaving = state.generation
        return true
    }

    private func perform() async -> String? {
        let gen = state.generation
        leaving = true
        lostWhileLeaving = nil
        defer {
            leaving = false
            // 끊기가 실패로 끝났는데 그사이 이 연결의 401·4001이 왔다: 토큰은 이미 지워졌으니 그대로 GONE으로 간다(7.1).
            let lost = lostWhileLeaving == state.generation
            lostWhileLeaving = nil
            if lost { lose() }
        }
        do { try await gate.observed { try await relay.deleteMe() } }   // 중계는 204보다 먼저 이 폰의 소켓을 4001로 닫는다(removeDevice)
        catch let e as RelayError {
            if e.isOffline { return "인터넷이 필요해요" }
            if !e.isUnauthorized { return "끊지 못했어요. 잠시 뒤에 다시 해 주세요." }
        } catch { return "끊지 못했어요. 잠시 뒤에 다시 해 주세요." }
        if gen == state.generation {
            leave(.disconnect)
            notices.replace(with: CareText.disconnected)   // U25 b: 탭(연결 안 됨)의 오늘로 가고 띠를 보인다
        }
        return nil
    }
}
