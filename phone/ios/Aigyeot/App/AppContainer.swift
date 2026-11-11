import UIKit
import Foundation

/// 합성 루트(수동 의존성 컨테이너, 생성자 주입). 원본·유스케이스·화면별 뷰모델을 만들어 넘긴다. 외부 DI 라이브러리를 쓰지 않는다.
/// 유스케이스마다 필요한 것만 생성자로 받는다(안드로이드 di/AppContainer와 같다). 서로 부르는 길이 도는 곳
/// (수명 ↔ 받기·보내기·끊기, 보내기 ↔ 요약, 기록 ↔ 수유 보내기·기록 지우기, 받기 ↔ 알림·상태 요청)만 여기서 약한 클로저로 잇는다.
/// 유스케이스는 처음 쓸 때 만든다(lazy): 클로저가 아직 만들지 않은 쪽을 가리켜도 된다.
@MainActor
final class AppContainer {
    // MARK: 원본(저장소·관찰)
    let state: PhoneStateRepository
    let care: CareRepository
    let secrets: CredentialRepository
    let songFiles: SongFileStore
    let relay: RelayClient
    let notices = Notices()
    let visibility = AppVisibility()
    let tasks = TaskBag()
    let linkSwitch: LinkSwitch
    let now: () -> Date
    private let sockets: RelaySocket?
    private let network: NetworkWatcher?
    private let ongoing: OngoingNotice?
    private let sendsCareSummary: Bool

    // MARK: 유스케이스
    // 서로 가리키는 lazy는 타입을 적어야 추론이 돌지 않는다.
    private(set) lazy var wipe: WipeLink = WipeLink(state: state, secrets: secrets, songFiles: songFiles, notices: notices)
    private(set) lazy var lose: LoseConnection = LoseConnection(state: state, linkSwitch: linkSwitch, wipe: wipe)
    /// 중계 HTTP 결과를 연결 표시(KeepConnected)에 알리고 401을 LoseConnection에 넘기는 길(안드로이드 중계 클라이언트 콜백 자리).
    private(set) lazy var gate: RelayGate = RelayGate(
        state: state, lose: lose,
        mark: { [weak self] in self?.keepConnected.mark() ?? LinkMark(link: 0, ticket: 0) },
        observe: { [weak self] e, m in self?.keepConnected.observed(e, since: m) },
        clockSkew: { [weak self] in self?.keepConnected.clockSkew })
    private(set) lazy var uploadSong: UploadSong = UploadSong(state: state, relay: relay, gate: gate, songFiles: songFiles)
    private(set) lazy var sendOutbox: SendOutbox = SendOutbox(
        state: state, relay: relay, gate: gate, notices: notices, tasks: tasks, visibility: visibility, upload: uploadSong, now: now,
        queueSummary: { [weak self] in self?.reconcileCareSummary.queue() ?? .unchanged }, lose: lose)
    /// 이 기기 PATCH(인계 알림 받기·푸시 토큰)의 차례 하나.
    private(set) lazy var devicePatches: DevicePatches = DevicePatches(state: state, relay: relay, gate: gate)
    private(set) lazy var registerPush: RegisterPush = RegisterPush(state: state, patches: devicePatches, tasks: tasks)
    private(set) lazy var syncInbox: SyncInbox = SyncInbox(
        state: state, relay: relay, gate: gate, songFiles: songFiles, outbox: sendOutbox, notices: notices, now: now)
    private(set) lazy var keepConnected: KeepConnected = KeepConnected(
        state: state, relay: relay, gate: gate, inbox: syncInbox, visibility: visibility, tasks: tasks, outbox: sendOutbox,
        pushToken: registerPush, sockets: sockets, network: network, lose: lose, now: now)
    private(set) lazy var reconcileCareSummary: ReconcileCareSummary = ReconcileCareSummary(
        state: state, care: care, notices: notices, outbox: sendOutbox, now: now, sendsCareSummary: sendsCareSummary)
    private(set) lazy var recordCare: RecordCare = {
        let r = RecordCare(care: care, state: state, notices: notices, summary: reconcileCareSummary, outbox: sendOutbox, songFiles: songFiles,
                           ongoing: ongoing, now: now)
        r.screenReader = { UIAccessibility.isVoiceOverRunning || UIAccessibility.isSwitchControlRunning }   // U87 화면 읽기 켜짐
        return r
    }()
    private(set) lazy var clearRecords: ClearRecords = ClearRecords(
        state: state, care: care, songFiles: songFiles, tasks: tasks, outbox: sendOutbox, recordCare: recordCare,
        connection: keepConnected) { [weak self] in self?.lifecycle.handle(.recordsCleared) }
    private(set) lazy var disconnect: Disconnect = Disconnect(
        state: state, relay: relay, gate: gate, linkSwitch: linkSwitch, wipe: wipe, lose: lose, notices: notices, tasks: tasks)
    private(set) lazy var lifecycle: AppLifecycle = AppLifecycle(
        state: state, songFiles: songFiles, notices: notices, visibility: visibility,
        outbox: sendOutbox, inbox: syncInbox, connection: keepConnected, recordCare: recordCare, lose: lose, now: now)
    private(set) lazy var pair: Pair = Pair(state: state, secrets: secrets, songFiles: songFiles, relay: relay, gate: gate,
                                            linkSwitch: linkSwitch, disconnect: disconnect, notices: notices, visibility: visibility,
                                            lifecycle: lifecycle, recordCare: recordCare, pushToken: registerPush, tasks: tasks, now: now)
    private(set) lazy var invite: Invite = Invite(state: state, relay: relay, gate: gate)
    private(set) lazy var setHandoffAlerts: SetHandoffAlerts = SetHandoffAlerts(state: state, patches: devicePatches)
    private(set) lazy var recordSong: RecordSong = RecordSong(state: state, songFiles: songFiles, outbox: sendOutbox, now: now)
    private(set) lazy var removeSong: RemoveSong = RemoveSong(state: state, songFiles: songFiles, outbox: sendOutbox, now: now)
    private(set) lazy var selectSong: SelectSong = SelectSong(state: state, outbox: sendOutbox, now: now)

    // MARK: 앱 범위 뷰모델(나머지는 아래 공장이 화면마다 만든다, 1.7)
    private(set) lazy var appViewModel: AppViewModel = AppViewModel(state: state, pair: pair, notices: notices, lose: lose,
                                                                    connection: keepConnected)
    private(set) lazy var status: StatusViewModel = StatusViewModel(state: state, connection: keepConnected, notices: notices,
                                                                    outbox: sendOutbox)
    private(set) lazy var careTabs: CareTabsViewModel = CareTabsViewModel(state: state, notices: notices, recordCare: recordCare,
                                                                          disconnect: disconnect)
    /// 정보·라이선스의 판 글자(앱 묶음에서 읽는다).
    private let version: String
    /// 기록 가져오기에서 고른 파일 읽기(시험은 가짜로 바꿀 수 있다).
    private let fileReader: PickedFileReader

    /// `baseURL`이 있으면 그 주소로 WebSocket을 붙인다(없으면 소켓 없음: 시험).
    init(relay: RelayClient, store stateStore: StateStore, credentials: CredentialStore, careStore: CareStore = MemoryCareStore(),
         baseURL: URL? = nil, network: NetworkWatcher? = nil, ongoing: OngoingNotice? = nil, marks: LinkMarks? = nil,
         now: @escaping () -> Date = Date.init, sendsCareSummary: Bool = true, version: String = AppInfo.version,
         fileReader: PickedFileReader = CoordinatedFileReader()) {
        self.relay = relay
        self.fileReader = fileReader
        self.now = now
        self.sendsCareSummary = sendsCareSummary
        self.network = network
        self.ongoing = ongoing
        self.version = version
        let marks = marks ?? MemoryLinkMarks()   // 시험·미리보기는 메모리 표시
        linkSwitch = LinkSwitch(marks: marks)
        secrets = CredentialRepository(store: credentials)
        // 강제 끊김 정리가 남은 기기면(자격 없음) GONE 화면으로 시작한다(D1)
        state = PhoneStateRepository(store: stateStore, creds: credentials.load(), goneDevice: marks.get(.goneCleanupPending))
        care = CareRepository(store: careStore)
        songFiles = FileSongStore()
        // 소켓 어댑터는 사건을 메인에서 알린다(도메인과 같은 실행기).
        sockets = baseURL.map { URLSessionRelaySockets(baseURL: $0) }
        tasks.owner = self   // 띄운 작업이 도는 동안 합성 루트를 붙든다(유스케이스끼리 잇는 클로저가 약하게 본다)
        // 저장 실패 공통 처리(arch-phone-ios 실패 범주): 띠에 한 번. 범주마다 다른 뒷일은 부른 쪽(받기·보내기)이 한다
        let saveFailed: () -> Void = { [weak notices] in notices?.saveFailed() }
        state.onWriteFailed = saveFailed
        care.onWriteFailed = saveFailed
        // 자격이 바뀌면 옛 연결의 소켓·메모리 칸을 버리는 한 곳(P6)
        state.onConnectionChange = { [weak self] in self?.lifecycle.connectionChanged() }
        // 화면 상태(열려던 알림)도 수명 표의 같은 사건을 받는다(L10)
        lifecycle.onEvent = { [weak self] e in self?.appViewModel.forget(e) }
        lifecycle.start()
    }

    /// 앱 하나가 쓰는 컨테이너. 중계 주소는 Info의 `RelayBaseURL`(debug는 `http://localhost:8787`)이다.
    static let shared: AppContainer = {
        let raw = Bundle.main.object(forInfoDictionaryKey: "RelayBaseURL") as? String ?? "http://localhost:8787"
        let base = URL(string: raw) ?? URL(string: "http://localhost:8787")!
        let creds = KeychainCredentialStore()
        let client = HTTPRelayClient(baseURL: base)
        // 잠금 화면 '끝내기'로 앱이 뒤에서 떠도 진행 중 표시가 돌봄 저장마다 다시 그려진다(R9 OngoingNotice)
        let app = AppContainer(relay: client, store: FileStateStore(), credentials: creds, careStore: FileCareStore(), baseURL: base,
                               network: PathNetworkWatcher(), ongoing: LiveActivityNotice(), marks: UserDefaultsLinkMarks())
        app.registerPush.source = PushToken.shared   // FCM 토큰(5.4). 연결 요청과 앱 열기의 PATCH가 이 값을 보낸다
        // HTTPRelayClient는 도메인과 같은 메인 액터에서 돈다. 응답을 돌려주기 전에 시계 차이를 반영해, 같은 응답의 처리(요약 보낸 값 판단)가 새 값을 본다.
        client.onServerDate = { [weak app] d in app?.keepConnected.noteServerDate(d) }
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-demoSeed") {   // U50 시연 시드
            app.recordCare.replaceForDebug { DemoSeed.make(nowMs: CareTime.ms(Date()), zone: .current, keep: $0) }
        }
        if ProcessInfo.processInfo.arguments.contains("-longFeed") {   // 투어: 2시간 전에 시작한 진행 중 수유(긴 경과 표시를 시각과 상관없이 본다)
            let t = CareTime.ms(Date()) - 2 * 3_600_000
            app.recordCare.replaceForDebug { f in
                var f = f
                f.events.append(CareEvent(id: ULID.generate(date: CareTime.date(t)), kind: .feed, startAt: t, zone: TimeZone.current.identifier, createdAt: t, updatedAt: t))
                return f
            }
        }
        #endif
        app.syncLiveActivities()   // 시작 정리(지우다 만 기록 지우기 등)가 바꾼 것도 맞춘다
        return app
    }()

    /// 잠금 화면 표시를 지금 돌봄 기록에 맞춘다(앱을 열 때, 앞으로 올 때).
    func syncLiveActivities() { ongoing?.show(care.care) }
}

/// 화면 범위 뷰모델 공장(1.7). 화면이 나타날 때 부르고 그 화면의 `@State`가 든다.
extension AppContainer: ViewModelFactory {
    func connect() -> ConnectViewModel {
        ConnectViewModel(state: state, pair: pair) { [weak self] r in self?.appViewModel.pairFinished(r) }
    }
    func today() -> TodayViewModel {
        TodayViewModel(state: state, care: care, connection: keepConnected, recordCare: recordCare)
    }
    func timer(id: String) -> TimerViewModel { TimerViewModel(id: id, state: state, care: care, recordCare: recordCare) }
    func careSheet(_ target: CareSheetTarget) -> CareSheetViewModel {
        CareSheetViewModel(target: target, state: state, care: care, recordCare: recordCare, now: now)
    }
    func records() -> RecordsViewModel { RecordsViewModel(state: state, care: care, recordCare: recordCare) }
    func car() -> CarViewModel { CarViewModel(state: state, connection: keepConnected) }
    func songs() -> SongsViewModel {
        SongsViewModel(state: state, connection: keepConnected, recordSong: recordSong, removeSong: removeSong, selectSong: selectSong)
    }
    /// 녹음 시트마다 녹음기 하나(R10).
    func recorder() -> RecorderViewModel { RecorderViewModel(recorder: AudioSongRecorder()) }
    func songPlayer() -> SongPlayer { SongPlayer(files: songFiles, recorder: AudioSongRecorder()) }
    func reports() -> ReportsViewModel { ReportsViewModel(state: state, connection: keepConnected) }
    func reportDetail(sessionId: String) -> ReportDetailViewModel { ReportDetailViewModel(sessionId: sessionId, state: state) }
    func alert(id: String) -> AlertViewModel { AlertViewModel(id: id, state: state) }
    func settings() -> SettingsViewModel {
        SettingsViewModel(state: state, care: care, connection: keepConnected, recordCare: recordCare, clearRecords: clearRecords,
                          disconnect: disconnect, now: now, version: version, fileReader: fileReader)
    }
    func guardians() -> GuardiansViewModel {
        GuardiansViewModel(state: state, connection: keepConnected, invite: invite, setHandoffAlerts: setHandoffAlerts)
    }
}
