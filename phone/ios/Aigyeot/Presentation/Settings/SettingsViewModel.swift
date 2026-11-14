import Foundation
import Observation

// MARK: 설정

/// 설정 탭. 보호자 화면은 GuardiansViewModel.
struct SettingsUiState {
    var care: CareFile
    var connection: Connection
    /// 설정 끊기의 결과: 도는 중이면 버튼을 막고, 실패면 문구.
    var disconnect: DisconnectRun?
    var online: Bool
    /// 정보·라이선스의 판 글자.
    var version: String
    /// 연결된 휴대폰 수(owner의 기기 목록, 설정 '보호자' 줄 "{n}명"). guardian은 목록을 받지 않아 0.
    var phones: Int = 0

    var connected: Bool { connection.creds != nil }
    var offline: Bool { !online }
}

/// 기록 가져오기의 단계. 저장은 한 번의 동기 저장이라 '저장 중'은 화면에 남지 않는다.
enum ImportStep: Equatable {
    case idle
    case picking
    /// 고른 파일을 읽거나 Nara 아이의 줄을 다시 읽는 중(메인 밖). 닫으면 그 작업을 버린다.
    case reading
    /// Nara 파일에 아이가 둘 이상. 읽은 글자는 메모리에만 둔다.
    case pickChild(text: String, profiles: [String])
    case preview(ImportDraft)
    /// import.unreadable·tooLarge·failed
    case failed(String)
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
    @ObservationIgnored private let fileReader: PickedFileReader

    init(state: PhoneStateRepository, care: CareRepository, connection: KeepConnected, recordCare: RecordCare, clearRecords: ClearRecords,
         disconnect: Disconnect, now: @escaping () -> Date, version: String, fileReader: PickedFileReader) {
        self.fileReader = fileReader
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
                        version: version,
                        phones: stateRepo.state.devices.filter { $0.role == .owner || $0.role == .guardian }.count)
    }

    func setNickname(_ name: String) { recordCare.setNickname(name) }
    func setSleepDiaper(_ on: Bool) { recordCare.setSleepDiaper(on) }

    /// 내 기록 내보내기: 임시 폴더에 `aigyeot-care-{YYYYMMDD}.csv`를 쓰고 그 파일을 돌려준다. 실패하면 nil.
    func export() -> URL? { careRepo.export(fileName: CareText.exportFileName(now())) { CareExport.csv($0.events) } }

    // MARK: 기록 가져오기

    /// 가져오기 한 번의 단계. 화면은 이것만 그린다.
    private(set) var importStep: ImportStep = .idle
    /// 읽기 작업은 하나다: 다시 고르기·아이 고르기·닫기가 앞 작업을 취소하고, 결과는 그 작업이 취소되지 않았을 때만 적용한다.
    /// 화면이 아니라 이 뷰모델이 쥐므로 화면이 잠깐 사라져도 이어진다. 시험은 `importJob?.value`를 기다린다.
    @ObservationIgnored private(set) var importJob: Task<Void, Never>?

    func startImport() { importStep = .picking }

    /// 파일 고르기 결과(취소는 시스템이 고르기 창을 닫아 `closeImport`로 온다). 파일은 메모리로만 읽고 앱 폴더에 저장하지 않는다.
    /// 읽기·형식 판별은 메인 밖에서 한다(iCloud Drive 같은 파일 제공자는 내려받는 동안 멈출 수 있다).
    func picked(_ result: Result<URL, Error>) {
        let url = try? result.get()
        let reader = fileReader
        runImport { [weak self] in
            var read: CsvRead?
            // 상한 + 1바이트까지만 읽는다(넘었는지 알게). 열거나 읽지 못하면 nil(import.failed)
            if let url { read = await Task.detached(priority: .userInitiated) { reader.read(url, upTo: CareCsv.maxBytes + 1).map(CareCsv.read) }.value }
            return await self?.loaded(read) ?? .idle
        }
    }

    private func runImport(_ work: @escaping @MainActor () async -> ImportStep) {
        importJob?.cancel()
        importStep = .reading
        importJob = Task { [weak self] in
            let next = await work()
            guard !Task.isCancelled, let self else { return }
            self.importStep = next
        }
    }

    /// 읽은 결과 → 다음 단계. nil은 파일을 열거나 읽지 못한 IO 실패(import.failed), UTF-8이 아니면 import.unreadable.
    func loaded(_ read: CsvRead?) async -> ImportStep {
        switch read {
        case nil: return .failed(CareText.importFailed)
        case .tooLarge: return .failed(CareText.importTooLarge)
        case .unreadable: return .failed(CareText.importUnreadable)
        case .text(let text):
            recordCare.finishClear()   // 지우다 만 기록을 먼저 빼야 그 id가 '이미 있는 기록'으로 세지지 않는다(못 마치면 가져오기가 import.failed)
            return await preview(text, profile: nil)
        }
    }

    /// 지금 돌봄 기록을 기준으로 메인 밖에서 판별한다. 아이를 골라야 하면 아이 고르기로.
    private func preview(_ text: String, profile: String?) async -> ImportStep {
        let existing = careRepo.care.events, at = CareTime.ms(now())
        let draft = await Task.detached(priority: .userInitiated) { CareCsv.parse(text, existing: existing, now: at, profile: profile) }.value
        if draft.format == .unknown { return .failed(CareText.importUnreadable) }
        if profile == nil, draft.format == .nara, draft.profiles.count > 1 { return .pickChild(text: text, profiles: draft.profiles) }
        return .preview(draft)
    }

    func pickChild(_ name: String) {
        guard case .pickChild(let text, _) = importStep else { return }
        runImport { [weak self] in await self?.preview(text, profile: name) ?? .idle }
    }

    /// '가져오기'. 가져올 것이 없으면 닫기만 한다. 저장하면 시트를 닫는다: 띠 import.done과 되돌리기·차 보내기는 RecordCare가 한다(띠 수명 한 곳).
    func confirmImport() {
        guard case .preview(let draft) = importStep else { return }
        guard !draft.rows.isEmpty else { importStep = .idle; return }
        importStep = recordCare.importRows(draft.rows) == nil ? .failed(CareText.importFailed) : .idle
    }

    /// 닫기·취소. 읽던 작업은 버린다.
    func closeImport() {
        importJob?.cancel()
        importStep = .idle
    }

    func clearRecords() { clearing() }
    /// 설정의 '연결 끊기': 앱 범위에서 끝까지 하고 결과는 `ui.disconnect`. 끊기면 탭이 오늘로 간다(CareTabsViewModel).
    func startDisconnect() { disconnect.start() }
    /// 설정을 떠나면 지난 끊기 실패 글을 걷는다.
    func dismissDisconnectError() { disconnect.dismissError() }
}

// MARK: 보호자

/// 보호자: owner는 초대·목록, guardian은 인계 알림 받기.
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

/// 차를 뺀 연결된 폰. 인계 알림 받기는 중계 값(아직 없으면 켜짐으로 보인다, guardian 기본값).
func guardiansUi(_ s: PhoneState, creds: Credentials?, online: Bool, busy: Bool, error: String?, invite: InviteResponse?) -> GuardiansUiState {
    GuardiansUiState(role: creds?.role, handoffAlerts: s.handoffAlerts ?? true,
                     phones: s.devices.filter { $0.role == .owner || $0.role == .guardian }, myDeviceId: creds?.deviceId,
                     online: online, busy: busy, error: error, invite: invite)
}

/// 보호자 화면. 요청 중·오류·만든 초대 코드는 이 뷰모델이 들고, 요청도 이 뷰모델의 작업으로 끝까지 반영한다
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
    /// 만든 초대와 그때의 연결. 초대는 그 차의 것이라(끊으면 중계가 폐기) 연결이 바뀌면 보이지 않는다.
    private var made: (invite: InviteResponse, creds: Credentials?)?

    init(state: PhoneStateRepository, connection: KeepConnected, invite: Invite, setHandoffAlerts: SetHandoffAlerts) {
        stateRepo = state
        self.connection = connection
        self.invite = invite
        self.setHandoffAlerts = setHandoffAlerts
    }

    var ui: GuardiansUiState {
        guardiansUi(stateRepo.state, creds: stateRepo.creds, online: connection.status.online, busy: busy, error: error,
                    invite: made.flatMap { stateRepo.isCurrent($0.creds) ? $0.invite : nil })
    }

    func makeInvite() async {
        guard !busy else { return }
        busy = true; error = nil
        let c = stateRepo.creds
        let result = await invite()
        busy = false
        guard stateRepo.isCurrent(c) else { return }   // 기다리는 사이 연결이 바뀌었으면 옛 차의 초대·오류를 버린다
        switch result {
        case .success(let r): made = (r, c)
        case .failure(let f): error = f.message
        }
    }

    /// '인계 알림 받기'를 바꾼다. 바꿨으면 true(켤 때 알림 권한을 다시 묻는다).
    /// 차례를 기다리거나 응답을 받는 사이 연결이 바뀌었으면(끊기·다른 차) 보내지 않았거나 버렸으니 false(묻지 않는다).
    func setHandoff(_ on: Bool) async -> Bool {
        guard !busy else { return false }
        busy = true
        let c = stateRepo.creds
        let failed = await setHandoffAlerts(on)
        busy = false
        guard stateRepo.isCurrent(c) else { return false }   // 옛 연결의 오류는 보이지 않는다(초대와 같다)
        error = failed
        return failed == nil
    }

    /// 연결된 폰 목록(owner, GET /v1/devices).
    func refreshDevices() async { await connection.refreshDevices() }
}
