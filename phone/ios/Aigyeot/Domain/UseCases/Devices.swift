import Foundation

// 이 기기의 중계 설정(초대·인계 알림 받기·푸시 토큰, 안드로이드 usecase/Devices.kt). 401은 어디서든 연결 끊김이다.

/// 초대를 만들지 못했다. 화면에 보일 문구를 싣는다.
struct InviteFailure: Error, Equatable {
    let message: String
}

/// owner의 '초대 코드 만들기'(POST /v1/invites).
@MainActor
final class Invite {
    private let state: PhoneStateRepository
    private let relay: RelayClient
    private let gate: RelayGate

    init(state: PhoneStateRepository, relay: RelayClient, gate: RelayGate) {
        self.state = state
        self.relay = relay
        self.gate = gate
    }

    func callAsFunction() async -> Result<InviteResponse, InviteFailure> {
        guard let c = state.creds else { return .failure(InviteFailure(message: CareText.inviteFailed)) }
        do { return .success(try await gate.observed { try await relay.createInvite(token: c.token) }) } catch let e as RelayError {
            gate.unauthorized(e, c: c)
            if e.isOffline { return .failure(InviteFailure(message: "인터넷이 필요해요")) }
            if e.status == 409 { return .failure(InviteFailure(message: "쓰지 않은 초대가 3개 있어요")) }
            if e.status == 429 { return .failure(InviteFailure(message: CareText.inviteFailed)) }
            return .failure(InviteFailure(message: CareText.inviteFailed))
        } catch { return .failure(InviteFailure(message: CareText.inviteFailed)) }
    }
}

/// 이 기기 PATCH(/v1/devices/me: 인계 알림 받기·푸시 토큰)를 받은 차례대로 하나씩 보낸다. 두 응답 모두 중계의 인계 알림 받기 값을 싣는다(중계가 원본):
/// 중계가 처리한 차례와 응답을 반영하는 차례를 같게 해, 늦게 온 앞 응답이 나중 설정을 덮지 않는다
/// (보호자 화면을 다시 열어 겹쳐 누름, 앱을 열 때의 푸시 토큰 등록과 겹친 설정 변경).
/// 요청은 받을 때의 자격으로 보내고, 차례를 기다리는 사이 연결이 바뀌었으면 보내지 않는다.
@MainActor
final class DevicePatches {
    private let state: PhoneStateRepository
    private let relay: RelayClient
    private let gate: RelayGate
    /// 마지막으로 받은 요청(그 앞 요청이 끝나야 보낸다).
    private var last: Task<Void, Never>?

    init(state: PhoneStateRepository, relay: RelayClient, gate: RelayGate) {
        self.state = state
        self.relay = relay
        self.gate = gate
    }

    enum Outcome {
        case done
        /// 401은 여기서 LoseConnection에 넘겼다. 응답이 아닌 실패는 오류 없음.
        case failed(RelayError?)
        /// 차례를 기다리거나 응답을 받는 사이 연결이 바뀌었다(보내지 않았거나 결과를 버렸다).
        case stale
    }

    func send(_ c: Credentials, _ request: PatchDeviceRequest) async -> Outcome {
        let previous = last
        let task = Task { [self] () -> Outcome in
            _ = await previous?.value
            guard state.isCurrent(c) else { return .stale }
            do {
                let r = try await gate.observed { try await relay.patchMe(token: c.token, request) }
                guard state.isCurrent(c) else { return .stale }
                // 앞서 로컬 저장에 실패한 값도 이때 맞춘다(안드로이드 RegisterPush·SetHandoffAlerts와 같다)
                if state.state.handoffAlerts != r.handoffAlerts { state.commit(c) { $0.handoffAlerts = r.handoffAlerts } }
                return .done
            } catch let e as RelayError {
                gate.unauthorized(e, c: c)
                return .failed(e)
            } catch { return .failed(nil) }
        }
        last = Task { _ = await task.value }
        return await task.value
    }
}

/// guardian의 '인계 알림 받기'. 중계가 원본이고 응답으로 맞춘다(DevicePatches). 실패하면 문구.
/// '요청 중'은 화면 쪽(뷰모델)이 든다. 요청은 누른 때의 연결로 보낸다.
@MainActor
final class SetHandoffAlerts {
    private let state: PhoneStateRepository
    private let patches: DevicePatches

    init(state: PhoneStateRepository, patches: DevicePatches) {
        self.state = state
        self.patches = patches
    }

    func callAsFunction(_ on: Bool) async -> String? {
        guard let c = state.creds else { return CareText.handoffSettingFailed }
        switch await patches.send(c, PatchDeviceRequest(handoffAlerts: on)) {
        case .done, .stale: return nil
        case .failed(let e?): return e.isOffline ? "인터넷이 필요해요" : CareText.handoffSettingFailed
        case .failed(nil): return CareText.handoffSettingFailed
        }
    }
}

/// 푸시 토큰 등록. 앱을 열 때마다 같은 값이어도 보낸다. 실패하면 다음 열기 때 다시.
@MainActor
final class RegisterPush {
    private let state: PhoneStateRepository
    private let patches: DevicePatches
    private let tasks: TaskBag
    /// FCM 토큰 원본(앱의 PushToken). 시험이 바꾼다.
    var source: PushTokens?

    init(state: PhoneStateRepository, patches: DevicePatches, tasks: TaskBag) {
        self.state = state
        self.patches = patches
        self.tasks = tasks
    }

    var token: String? { source?.token }

    /// 갱신 순서 안에서 부른다(KeepConnected). `c`: 이 일의 연결. 응답의 인계 알림 받기는 DevicePatches가 맞춘다.
    func now(_ c: Credentials) async {
        guard let t = token else { return }
        guard case .done = await patches.send(c, PatchDeviceRequest(pushToken: .set(t))) else { return }   // 실패는 다음 앱 열기 때 다시
        // 보내는 사이 토큰이 바뀌었으면(갱신 콜백의 PATCH와 겹침) 중계에 늦게 닿은 옛 값이 남지 않게 지금 값을 한 번 더 보낸다
        if state.isCurrent(c), token != t { await now(c) }
    }

    /// FCM 토큰이 생기거나 바뀌었다(MessagingDelegate). 원본에 적고, 연결돼 있으면 바로 중계에 알린다.
    func onToken(_ token: String?) {
        source?.token = token
        guard let c = state.creds else { return }
        tasks.spawn { [self] in await now(c) }
    }
}
