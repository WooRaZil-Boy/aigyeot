import Foundation
import Observation

// MARK: 설정

/// 설정 탭(5장 순서). 보호자 화면은 GuardiansViewModel.
struct SettingsUiState {
    var care: CareFile
    var connection: Connection
    /// 설정 끊기의 결과(M3): 도는 중이면 버튼을 막고, 실패면 문구.
    var disconnect: DisconnectRun?
    var online: Bool
    /// 정보·라이선스의 판 글자.
    var version: String

    var connected: Bool { connection.creds != nil }
    var role: PhoneRole? { connection.creds?.role }
    var carId: String? { connection.creds?.carId }
    var offline: Bool { !online }
}

@MainActor
@Observable
final class SettingsViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let connection: KeepConnected
    @ObservationIgnored private let recordCare: RecordCare
    @ObservationIgnored private let clearing: ClearRecords
    @ObservationIgnored private let disconnect: Disconnect
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let version: String

    init(state: PhoneStateRepository, care: CareRepository, connection: KeepConnected, recordCare: RecordCare, clearRecords: ClearRecords,
         disconnect: Disconnect, now: @escaping () -> Date, version: String) {
        stateRepo = state
        careRepo = care
        self.connection = connection
        self.recordCare = recordCare
        clearing = clearRecords
        self.disconnect = disconnect
        self.now = now
        self.version = version
    }

    var ui: SettingsUiState {
        SettingsUiState(care: careRepo.care, connection: stateRepo.connection, disconnect: disconnect.run, online: connection.status.online,
                        version: version)
    }

    func setNickname(_ name: String) { recordCare.setNickname(name) }
    func setSleepDiaper(_ on: Bool) { recordCare.setSleepDiaper(on) }

    /// 내 기록 내보내기(C7, U29): 임시 폴더에 `aigyeot-care-{YYYYMMDD}.csv`를 쓰고 그 파일을 돌려준다. 실패하면 nil.
    func export() -> URL? { careRepo.export(fileName: CareText.exportFileName(now())) { CareExport.csv($0.events) } }

    func clearRecords() { clearing() }
    /// 설정의 '연결 끊기': 앱 범위에서 끝까지 하고 결과는 `ui.disconnect`(M3). 끊기면 탭이 오늘로 간다(CareTabsViewModel).
    func startDisconnect() { disconnect.start() }
    /// 설정을 떠나면 지난 끊기 실패 글을 걷는다.
    func dismissDisconnectError() { disconnect.dismissError() }
}

// MARK: 보호자

/// 보호자: owner는 초대·목록, guardian은 인계 알림 받기(7.1).
struct GuardiansUiState: Equatable {
    var role: PhoneRole?
    var handoffAlerts: Bool
    /// 차를 뺀 연결된 폰.
    var phones: [DeviceInfo]
    var myDeviceId: String?
    var online: Bool
    var busy: Bool
    var error: String?
    var invite: InviteResponse?

    var offline: Bool { !online }
}

/// 차를 뺀 연결된 폰. 인계 알림 받기는 중계 값(아직 없으면 켜짐으로 보인다, G12 guardian 기본값).
func guardiansUi(_ s: PhoneState, creds: Credentials?, online: Bool, busy: Bool, error: String?, invite: InviteResponse?) -> GuardiansUiState {
    GuardiansUiState(role: creds?.role, handoffAlerts: s.handoffAlerts ?? true,
                     phones: s.devices.filter { $0.role == .owner || $0.role == .guardian }, myDeviceId: creds?.deviceId,
                     online: online, busy: busy, error: error, invite: invite)
}

/// 보호자 화면. 요청 중·오류·만든 초대 코드는 이 뷰모델이 들고(S17), 요청도 이 뷰모델의 작업으로 끝까지 반영한다
/// (화면이 다시 그려지거나 잠깐 가려져도 만든 초대 코드와 '인계 알림 받기' 결과를 버리지 않게).
@MainActor
@Observable
final class GuardiansViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let connection: KeepConnected
    @ObservationIgnored private let invite: Invite
    @ObservationIgnored private let setHandoffAlerts: SetHandoffAlerts
    /// 요청 중. 요청이 겹치면 늦게 온 앞 응답이 나중 설정을 덮으니 한 번에 하나만 보낸다.
    private var busy = false
    private var error: String?
    private var made: InviteResponse?

    init(state: PhoneStateRepository, connection: KeepConnected, invite: Invite, setHandoffAlerts: SetHandoffAlerts) {
        stateRepo = state
        self.connection = connection
        self.invite = invite
        self.setHandoffAlerts = setHandoffAlerts
    }

    var ui: GuardiansUiState {
        guardiansUi(stateRepo.state, creds: stateRepo.creds, online: connection.status.online, busy: busy, error: error, invite: made)
    }

    func makeInvite() async {
        guard !busy else { return }
        busy = true; error = nil
        switch await invite() {
        case .success(let r): made = r
        case .failure(let f): error = f.message
        }
        busy = false
    }

    /// '인계 알림 받기'를 바꾼다. 바꿨으면 true(켤 때 알림 권한을 다시 묻는다, 7.3).
    /// 차례를 기다리거나 응답을 받는 사이 연결이 바뀌었으면(끊기·다른 차) 보내지 않았거나 버렸으니 false(묻지 않는다, P5).
    func setHandoff(_ on: Bool) async -> Bool {
        guard !busy else { return false }
        busy = true
        let c = stateRepo.creds
        error = await setHandoffAlerts(on)
        busy = false
        return error == nil && stateRepo.isCurrent(c)
    }

    /// 연결된 폰 목록(owner, GET /v1/devices).
    func refreshDevices() async { await connection.refreshDevices() }
}
