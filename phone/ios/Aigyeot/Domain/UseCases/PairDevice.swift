import Foundation
import Observation

/// 코드로 연결하기(POST /v1/pair). 이미 연결된 차가 있으면 먼저 끊고, 둘 다 성공해야 새 자격이 켜진다.
/// 작업은 앱 범위에서 끝나고 화면은 `run`만 본다. 연결 화면의 흐름(확인 시트·링크 오류)은 Presentation의 AppViewModel이 갖는다.
@MainActor
@Observable
final class PairDevice {
    @ObservationIgnored private let state: PhoneStateRepository
    @ObservationIgnored private let secrets: CredentialRepository
    @ObservationIgnored private let songFiles: SongFileStore
    @ObservationIgnored private let relay: RelayClient
    @ObservationIgnored private let gate: RelayGate
    @ObservationIgnored private let linkSwitch: LinkSwitch
    @ObservationIgnored private let disconnect: Disconnect
    @ObservationIgnored private let notices: Notices
    @ObservationIgnored private let visibility: AppVisibility
    @ObservationIgnored private let lifecycle: AppLifecycle
    @ObservationIgnored private let recordCare: RecordCare
    @ObservationIgnored private let pushToken: RegisterPush
    @ObservationIgnored private let tasks: TaskBag
    @ObservationIgnored private let now: () -> Date
    /// 연결 작업의 결과. 다음 연결을 시작하거나 화면이 걷을 때(`clear`)까지 남는다.
    private(set) var run: PairRun?
    /// 연결 429 잠금이 끝나는 시각. 직접 입력·확인 화면이 같이 본다. 화면을 옮기거나 코드를 고쳐도 풀리지 않는다.
    private(set) var lockedUntil: Date?
    /// 도는 연결 작업(시험이 끝을 기다린다).
    @ObservationIgnored private(set) var job: Task<Void, Never>?

    init(state: PhoneStateRepository, secrets: CredentialRepository, songFiles: SongFileStore, relay: RelayClient,
         gate: RelayGate, linkSwitch: LinkSwitch, disconnect: Disconnect, notices: Notices, visibility: AppVisibility,
         lifecycle: AppLifecycle, recordCare: RecordCare, pushToken: RegisterPush, tasks: TaskBag, now: @escaping () -> Date) {
        self.state = state
        self.secrets = secrets
        self.songFiles = songFiles
        self.relay = relay
        self.gate = gate
        self.linkSwitch = linkSwitch
        self.disconnect = disconnect
        self.notices = notices
        self.visibility = visibility
        self.lifecycle = lifecycle
        self.recordCare = recordCare
        self.pushToken = pushToken
        self.tasks = tasks
        self.now = now
    }

    /// 429 잠금이 남았는가. 시각은 `now` 시계로 본다.
    var isLocked: Bool { lockedUntil.map { $0 > now() } ?? false }

    /// 첫 안내를 넘겼다(연결하기·기록만 쓰기).
    func markIntroSeen() { state.commitLocal { $0.introSeen = true } }

    /// 지금 새 연결 링크·QR을 받을 수 있는가. 연결 작업(끊고 연결의 끊기 포함)이 도는 동안은 받지 않는다.
    func canTakeLink() -> Bool { if case .running = run { false } else { true } }

    /// 확인 화면의 '연결하기'·'끊고 연결하기'와 직접 친 코드. 도는 작업이 있으면 받지 않는다(늦게 온 옛 응답이 새 연결을 덮지 않게).
    /// 결과를 `run`에 두고 `then`을 부른다(연결 흐름 화면을 닫는 쪽).
    func start(_ code: PairCode, then: @escaping @MainActor (PairRun) -> Void = { _ in }) {
        guard canTakeLink() else { return }
        if isLocked, lockedUntil != nil { finish(.failed(code: code, .rateLimited), then); return }
        run = .running(code: code)   // 작업이 돌기 전 틈에 온 링크도 막는다
        job = tasks.spawn { [self] in
            // 확인하기 전에는 지금 연결을 지우지 않는다. 그사이 끊겼으면(401) 끊기를 거치지 않는다
            let failure: ConnectFailure?
            if state.isPaired, let message = await disconnect.now() { failure = .disconnectFailed(message) } else { failure = await request(code) }
            finish(failure.map { .failed(code: code, $0) } ?? .done(code: code, role: state.role), then)
        }
    }

    private func finish(_ r: PairRun, _ then: (PairRun) -> Void) {
        run = r
        then(r)
    }

    /// 끝난 결과를 걷는다(연결·확인 화면이 닫힐 때). 도는 작업은 둔다.
    func clear() { if canTakeLink() { run = nil } }

    private func request(_ code: PairCode) async -> ConnectFailure? {
        // 연결 요청의 결과도 띠에 반영한다(끊긴 동안 켜진 띠가 다시 연결한 뒤 남지 않게). 성공하면 세대가 바뀌어 observed 대신 직접 본다
        let mark = gate.mark()
        do {
            let r = try await relay.pair(code: code.value, pushToken: pushToken.token)
            gate.observe(nil, since: mark)
            guard let role = PhoneRole(rawValue: r.role.rawValue) else { return .other }
            // 연결 세대가 바뀐다(자격 값이 세대다). 이전 연결의 응답은 이제 버려진다.
            // Keychain 저장과 이전 상태·보낼 함을 지운 새 상태의 저장이 성공해야만 새 자격을 켠다(이전 차의 보낼 함이 새 차로 가지 않게).
            let c = Credentials(carId: r.carId, deviceId: r.deviceId, token: r.deviceToken, role: role)
            let t = now()
            // 토큰을 Keychain에 쓴 것이 확인된 뒤에 상태를 커밋하고 자격을 공개한다(안드로이드와 같은 순서). 쓰지 못하면 중계의 새 기기를 지운다
            let prev = state.creds
            do { try secrets.save(c) } catch { return await discard(c) }
            let r2 = state.switchTo(c) { old in
                var st = PhoneStateRepository.fresh(introSeen: true, keeping: old)   // 첫 안내를 넘기지 않고 링크로 연결해도 홈으로 간다(DONE → HOME, 안드로이드와 같다)
                st.carId = r.carId
                st.lastReceivedAt = t   // 연결 시각을 기준으로 둬 차 메시지를 한 번도 못 받아도 24시간 재요청이 걸린다(안드로이드 paired와 같다)
                st.handoffAlerts = role == .guardian ? true : nil   // 기본값은 guardian 참, owner 거짓
                return st
            }
            guard r2.switched else {
                if let prev { try? secrets.save(prev) } else { secrets.clear() }   // 옛 비밀값으로 되돌린다
                return await discard(c)
            }
            songFiles.purge()   // 지난 연결의 녹음 파일(상태는 이미 비웠다)
            linkSwitch.goneCleanup(.paired)   // 남은 강제 끊김 기록을 걷는다
            notices.clearForPair()   // 새 연결: 띠·배너·되돌리기를 처음으로
            disconnect.clearRun()    // 지난 연결의 끊기 결과(실패 글·끝남)가 새 연결의 설정에 남지 않게(안드로이드 PairDevice와 같다)
            notices.addLine(NoticeRules.paired)   // iOS는 완료 화면 대신 '연결됐어요' 띠
            recordCare.syncToCar(.paired)   // 연결 직후: 연결 전에 적은 24시간 안의 수유와 요약을 보낸다
            if visibility.active { lifecycle.onForeground() }   // 연결 직후 받기·동기화를 바로 시작한다
            return nil
        } catch let e as RelayError {
            gate.observe(e, since: mark)
            switch (e.status, e.code) {
            case (404, _): return .codeInvalid
            case (429, _):
                lock(e.retryAfter)
                return .rateLimited
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
        do {
            try await relay.deleteMe(token: c.token); return .notSaved(removed: true)
        } catch let e as RelayError {
            return .notSaved(removed: e.isUnauthorized)
        } catch {
            return .notSaved(removed: false)
        }
    }

    private func lock(_ retryAfter: TimeInterval?) {
        let secs = RetryPolicy.pairLock(retryAfter: retryAfter)
        let until = now().addingTimeInterval(secs)
        lockedUntil = until
        // 끝나는 때 화면이 다시 그려지게 값을 비운다. 그 사이 새 429가 와서 시각이 바뀌었으면 건드리지 않는다.
        // 허용 오차를 0으로 준다. 기본값으로는 기다린 시간의 약 6.5%만큼 늦게 깨어 589초 잠금이 39초 늦게 풀렸다(시뮬레이터 실측).
        // 시험의 settle()이 잠금 시간만큼 기다리지 않게 작업 묶음에 넣지 않는다(잠금 시각만 비우는 타이머다)
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(secs), tolerance: .zero, clock: .continuous)
            if self?.lockedUntil == until { self?.lockedUntil = nil }
        }
    }
}
