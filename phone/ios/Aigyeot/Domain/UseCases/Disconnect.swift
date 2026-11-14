import Foundation
import Observation

// 연결 끊기(사용자 끊기, 강제 끊김, 둘의 공통 정리). 안드로이드 Disconnect.kt와 같은 이름·나눔.
// 연결이 바뀌면 메모리 칸의 정리는 PhoneStateRepository가 알리는 한 곳(AppLifecycle.connectionChanged)이 한다.

/// 연결 캐시 지우기(끊기·401 공통). 돌봄 기록과 지우다 만 '기록 지우기'는 남긴다.
@MainActor
final class WipeLink {
    private let state: PhoneStateRepository
    private let secrets: CredentialRepository
    private let songFiles: SongFileStore
    private let notices: Notices

    init(state: PhoneStateRepository, secrets: CredentialRepository, songFiles: SongFileStore, notices: Notices) {
        self.state = state
        self.secrets = secrets
        self.songFiles = songFiles
        self.notices = notices
    }

    /// `c`가 아직 지금 자격일 때만 지운다. 연결 비우기 → 띠·배너 걷기 → Keychain 지우기 → 녹음 파일 지우기(상태를 비운 저장이 됐을 때만).
    @discardableResult
    func callAsFunction(_ c: Credentials) -> Bool {
        let r = state.switchTo(nil, make: { PhoneStateRepository.fresh(introSeen: $0.introSeen, keeping: $0) }, onlyIf: c)
        guard r.switched else { return false }
        notices.clearForWipe()   // 옛 연결의 띠(버림 안내 등)와 배너도 걷는다
        secrets.clear()
        if r.saved { songFiles.purge() }   // 저장이 실패하면 메모리 상태가 옛 곡을 가리키니 파일을 남긴다
        return true
    }
}

/// 어느 요청이든 401, 소켓 닫기 4001('어디서든 → GONE'). 사용자 끊기 중이면 그 끊기에 맡긴다.
@MainActor
final class LoseConnection {
    private let state: PhoneStateRepository
    private let linkSwitch: LinkSwitch
    private let wipe: WipeLink

    init(state: PhoneStateRepository, linkSwitch: LinkSwitch, wipe: WipeLink) {
        self.state = state
        self.linkSwitch = linkSwitch
        self.wipe = wipe
    }

    /// 401·4001을 받은 연결(일을 시작할 때 잡은 자격). 이전 연결의 늦은 401이면 무시한다.
    func lost(_ c: Credentials?) {
        guard let c, state.isCurrent(c) else { return }
        if linkSwitch.absorb(c) { return }   // 사용자가 끊는 중이면 그 끊기의 결과일 수 있다. 끊기가 끝날 때 본다
        // 정리한 뒤 GONE 화면을 고르기 전에 죽어도 다음 실행이 GONE을 보이게 먼저 적는다
        linkSwitch.goneCleanupStarted(c.deviceId)
        guard wipe(c) else { return }
        state.showGone(deviceId: c.deviceId)
    }

    /// '연결이 끊겼어요' 화면에서 골랐다(기록만 계속·다시 연결하기). 표시를 걷고 GONE을 걷는다.
    /// 표시를 걷지 못하면 GONE을 둔다(다시 켜면 또 GONE이 떠 고른 것과 어긋나지 않게). 걷었으면 true.
    @discardableResult
    func acknowledgeGone() -> Bool {
        guard linkSwitch.goneCleanup(.acknowledged) else { return false }
        state.hideGone()
        return true
    }

    /// 앞 실행이 강제 끊김 정리를 적고 마치기 전에 죽었다. 지금 자격의 표시면 정리를 이어 하고 GONE을 보인다.
    /// 다른 기기의 옛 표시면(그 뒤 새로 연결했다) 걷는다. 자격이 없으면 PhoneStateRepository가 GONE으로 시작했다.
    func recoverAtStart() {
        guard let id = linkSwitch.goneCleanupPending, let c = state.creds else { return }
        if id == c.deviceId { lost(c) } else { linkSwitch.goneCleanup(.stale) }
    }
}

/// 설정의 '연결 끊기'와 '끊고 연결하기'의 끊기. 중계의 토큰을 지워야 해서 인터넷이 필요하다.
@MainActor
@Observable
final class Disconnect {
    @ObservationIgnored private let state: PhoneStateRepository
    @ObservationIgnored private let relay: RelayClient
    @ObservationIgnored private let gate: RelayGate
    @ObservationIgnored private let linkSwitch: LinkSwitch
    @ObservationIgnored private let wipe: WipeLink
    @ObservationIgnored private let lose: LoseConnection
    @ObservationIgnored private let notices: Notices
    @ObservationIgnored private let tasks: TaskBag
    /// 설정 끊기의 결과. 화면이 받으면 걷는다.
    private(set) var run: DisconnectRun?
    @ObservationIgnored private var leavingTask: Task<String?, Never>?

    init(state: PhoneStateRepository, relay: RelayClient, gate: RelayGate, linkSwitch: LinkSwitch, wipe: WipeLink,
         lose: LoseConnection, notices: Notices, tasks: TaskBag) {
        self.state = state
        self.relay = relay
        self.gate = gate
        self.linkSwitch = linkSwitch
        self.wipe = wipe
        self.lose = lose
        self.notices = notices
        self.tasks = tasks
    }

    /// 설정의 '연결 끊기': 앱 범위 작업으로 끊고 결과를 `run`에 둔다(화면이 사라져도 끝까지 한다).
    func start() {
        if run == .running { return }
        run = .running
        tasks.spawn { [self] in
            let error = await now()
            // 끊기는 실패했지만 그사이 받은 401·4001로 GONE으로 갔다: GONE 화면이 알리므로 설정에 옛 오류를 남기지 않는다
            // (남기면 새 차에 연결한 뒤 설정에 옛 차의 오류가 보인다, 안드로이드와 같다)
            switch error {
            case nil: run = .done
            case let e? where state.creds != nil: run = .failed(e)
            case _?: run = nil
            }
        }
    }

    /// 도는 끊기가 없으면 지난 결과를 걷는다.
    func clearRun() { if run != .running { run = nil } }
    /// 설정을 떠나면 지난 끊기 실패 글을 걷는다. 끝남 표시는 탭 이동이 쓰니 둔다.
    func dismissError() { if case .failed = run { run = nil } }
    /// 탭이 끊은 뒤 화면(오늘·띠)을 보였다.
    func shown() { clearRun() }

    /// 끊기. 성공하면 nil, 실패하면 화면 문구. 설정의 끊기와 '끊고 연결하기'의 끊기가 겹치면 뒤에 온 쪽은 앞의 결과를 기다린다
    /// (끊기 중 받은 401·4001 표시를 지우지 않게).
    func now() async -> String? {
        if let running = leavingTask { return await running.value }
        let task = Task { await leaveOnce() }
        leavingTask = task
        let result = await task.value
        leavingTask = nil
        return result
    }

    private func leaveOnce() async -> String? {
        guard let c = state.creds else { return nil }   // 끊을 연결이 없다
        linkSwitch.leaveStarted(c)
        defer {
            // 끊기가 실패로 끝났는데 그사이 이 연결의 401·4001이 왔다: 토큰은 이미 지워졌으니 그대로 GONE으로 간다.
            let lost = linkSwitch.leaveEnded()
            if state.isCurrent(lost) { lose.lost(lost) }
        }
        do { try await gate.observed { try await relay.deleteMe(token: c.token) } }   // 중계는 204보다 먼저 이 폰의 소켓을 4001로 닫는다(removeDevice)
        catch let e as RelayError {
            if e.isOffline { return "인터넷이 필요해요" }
            if !e.isUnauthorized { return "끊지 못했어요. 잠시 뒤에 다시 해 주세요." }
        } catch { return "끊지 못했어요. 잠시 뒤에 다시 해 주세요." }
        if wipe(c) {
            notices.showCare(CareText.disconnected)   // 탭(연결 안 됨)의 오늘로 가고 띠를 보인다(정리가 띠를 먼저 비웠다)
        }
        return nil
    }
}
