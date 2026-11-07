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
    let link = LinkObserver()
    let notices: NoticeBoard
    let visibility = AppVisibility()
    let tasks = TaskBag()
    let now: () -> Date
    private let makeSocket: RelaySocketMaker?
    private let sendsCareSummary: Bool

    // MARK: 유스케이스
    // 서로 가리키는 lazy는 타입을 적어야 추론이 돌지 않는다.
    private(set) lazy var gate: RelayGate = RelayGate(link: link, state: state) { [weak self] in self?.lifecycle.handleUnauthorized() }
    private(set) lazy var uploadSong: UploadSong = UploadSong(state: state, relay: relay, link: link, songFiles: songFiles)
    private(set) lazy var sendOutbox: SendOutbox = SendOutbox(
        state: state, relay: relay, link: link, notices: notices, tasks: tasks, visibility: visibility, upload: uploadSong, now: now,
        queueSummary: { [weak self] in self?.reconcileCareSummary.queue() ?? .unchanged },
        lose: { [weak self] in self?.lifecycle.handleUnauthorized() })
    private(set) lazy var showAlert: ShowAlert = ShowAlert(state: state, now: now) { [weak self] in await self?.syncInbox.refresh() }
    private(set) lazy var syncPushToken: SyncPushToken = SyncPushToken(state: state, relay: relay, gate: gate, tasks: tasks)
    private(set) lazy var syncInbox: SyncInbox = SyncInbox(
        state: state, secrets: secrets, relay: relay, link: link, gate: gate, visibility: visibility, tasks: tasks,
        songFiles: songFiles, outbox: sendOutbox, alerts: showAlert, pushToken: syncPushToken, makeSocket: makeSocket, now: now,
        afterDevices: { [weak self] epoch in await self?.requestState.afterDevices(epoch: epoch) },
        lose: { [weak self] in self?.lifecycle.handleUnauthorized() })
    private(set) lazy var refreshDevices: RefreshDevices = RefreshDevices(state: state, relay: relay, link: link, gate: gate, inbox: syncInbox)
    private(set) lazy var requestState: RequestState = RequestState(state: state, relay: relay, gate: gate, visibility: visibility,
                                                                    devices: refreshDevices, outbox: sendOutbox, now: now)
    private(set) lazy var reconcileCareSummary: ReconcileCareSummary = ReconcileCareSummary(
        state: state, care: care, outbox: sendOutbox, now: now, sendsCareSummary: sendsCareSummary)
    private(set) lazy var reconcileFeeding: ReconcileFeeding = ReconcileFeeding(state: state, care: care, outbox: sendOutbox, now: now) { [weak self] in
        self?.recordCare.migrateFeedingsIfNeeded()
    }
    private(set) lazy var recordCare: RecordCare = RecordCare(
        care: care, state: state, notices: notices, visibility: visibility, feeding: reconcileFeeding,
        summary: reconcileCareSummary, outbox: sendOutbox, now: now) { [weak self] in self?.clearLocalRecords.end() }
    private(set) lazy var clearLocalRecords: ClearLocalRecords = ClearLocalRecords(
        state: state, care: care, songFiles: songFiles, tasks: tasks, visibility: visibility, outbox: sendOutbox,
        recordCare: recordCare, summary: reconcileCareSummary, requestState: requestState) { [weak self] in
        self?.lifecycle.handle(.recordsCleared)
    }
    private(set) lazy var disconnect: Disconnect = Disconnect(
        state: state, relay: relay, gate: gate, notices: notices,
        leave: { [weak self] in self?.lifecycle.leaveConnection($0) },
        lose: { [weak self] in self?.lifecycle.handleUnauthorized() })
    private(set) lazy var lifecycle: Lifecycle = Lifecycle(
        state: state, secrets: secrets, songFiles: songFiles, notices: notices, visibility: visibility, alerts: showAlert,
        outbox: sendOutbox, inbox: syncInbox, requestState: requestState, recordCare: recordCare, clearRecords: clearLocalRecords,
        feeding: reconcileFeeding, summary: reconcileCareSummary, disconnect: disconnect, now: now)
    private(set) lazy var pair: Pair = Pair(state: state, relay: relay, link: link, notices: notices, visibility: visibility,
                                            lifecycle: lifecycle, feeding: reconcileFeeding, summary: reconcileCareSummary,
                                            pushToken: syncPushToken, now: now)
    private(set) lazy var createInvite: CreateInvite = CreateInvite(state: state, relay: relay, gate: gate)
    private(set) lazy var setHandoffAlerts: SetHandoffAlerts = SetHandoffAlerts(state: state, relay: relay, gate: gate)
    private(set) lazy var recordSong: RecordSong = RecordSong(state: state, songFiles: songFiles, outbox: sendOutbox, now: now)
    private(set) lazy var removeSong: RemoveSong = RemoveSong(state: state, songFiles: songFiles, outbox: sendOutbox, now: now)
    private(set) lazy var selectSong: SelectSong = SelectSong(state: state, outbox: sendOutbox, now: now)
    private(set) lazy var loadReport: LoadReport = LoadReport(state: state)

    // MARK: 화면별 뷰모델
    private(set) lazy var root: RootViewModel = RootViewModel(state: state, pair: pair, disconnect: disconnect, notices: notices, lifecycle: lifecycle)
    private(set) lazy var home: HomeViewModel = HomeViewModel(state: state, link: link, notices: notices, alerts: showAlert, inbox: syncInbox)
    private(set) lazy var today: TodayViewModel = TodayViewModel(state: state, care: care, recordCare: recordCare, alerts: showAlert, inbox: syncInbox)
    private(set) lazy var undo: UndoViewModel = UndoViewModel(care: care, recordCare: recordCare)
    private(set) lazy var careSheet: CareSheetViewModel = CareSheetViewModel(state: state, care: care, recordCare: recordCare, undo: undo)
    private(set) lazy var record: RecordViewModel = RecordViewModel(care: care, reports: loadReport, undo: undo)
    private(set) lazy var songs: SongsViewModel = SongsViewModel(state: state, link: link, recordSong: recordSong, removeSong: removeSong,
                                                                 selectSong: selectSong, reports: loadReport, inbox: syncInbox)
    private(set) lazy var report: ReportViewModel = ReportViewModel(state: state, reports: loadReport, inbox: syncInbox)
    private(set) lazy var settings: SettingsViewModel = SettingsViewModel(
        state: state, care: care, link: link, recordCare: recordCare, clearRecords: clearLocalRecords, disconnect: disconnect,
        invite: createInvite, handoff: setHandoffAlerts, devices: refreshDevices)

    /// `baseURL`이 있으면 그 주소로 WebSocket을 붙인다(없으면 소켓 없음: 시험).
    init(relay: RelayClient, store stateStore: StateStore, credentials: CredentialStore, careStore: CareStore = MemoryCareStore(),
         baseURL: URL? = nil, now: @escaping () -> Date = Date.init, sendsCareSummary: Bool = true) {
        self.relay = relay
        self.now = now
        self.sendsCareSummary = sendsCareSummary
        secrets = CredentialRepository(store: credentials)
        state = PhoneStateRepository(store: stateStore, credentials: credentials.load())
        care = CareRepository(store: careStore)
        songFiles = FileSongStore()
        notices = NoticeBoard(state: state)
        // 소켓 어댑터는 사건을 메인에서 알린다(도메인과 같은 실행기).
        makeSocket = baseURL.map { base in { token, onEvent in URLSessionRelaySocket(baseURL: base, token: token, onEvent: onEvent) } }
        tasks.owner = self   // 띄운 작업이 도는 동안 합성 루트를 붙든다(유스케이스끼리 잇는 클로저가 약하게 본다)
        // 저장 실패 공통 처리(StoreFailure): 띠에 한 번. 범주마다 다른 뒷일은 부른 쪽(받기·보내기)이 한다
        let saveFailed: () -> Void = { [weak notices] in notices?.add(NoticeRules.saveFailed) }
        state.onWriteFailed = saveFailed
        care.onWriteFailed = saveFailed
        lifecycle.launch()
    }

    /// 앱 하나가 쓰는 컨테이너. 중계 주소는 Info의 `RelayBaseURL`(debug는 `http://localhost:8787`)이다.
    static let shared: AppContainer = {
        let raw = Bundle.main.object(forInfoDictionaryKey: "RelayBaseURL") as? String ?? "http://localhost:8787"
        let base = URL(string: raw) ?? URL(string: "http://localhost:8787")!
        let creds = KeychainCredentialStore()
        let client = HTTPRelayClient(baseURL: base, credentials: { creds.load() })
        let app = AppContainer(relay: client, store: FileStateStore(), credentials: creds, careStore: FileCareStore(), baseURL: base)
        app.syncPushToken.source = PushToken.shared   // FCM 토큰(5.4). 연결 요청과 앱 열기의 PATCH가 이 값을 보낸다
        // HTTPRelayClient는 도메인과 같은 메인 액터에서 돈다. 응답을 돌려주기 전에 시계 차이를 반영해, 같은 응답의 처리(요약 보낸 값 판단)가 새 값을 본다.
        let link = app.link, now = app.now
        client.onServerDate = { d in link.noteServerDate(d, now: now()) }
        // 잠금 화면 '끝내기'로 앱이 뒤에서 떠도 이 고리가 걸린다
        app.recordCare.onCareChange = { CareLiveActivities.sync($0) }
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
        app.syncLiveActivities()   // 고리를 걸기 전 시작 정리(지우다 만 기록 지우기 등)가 바꾼 것도 맞춘다
        return app
    }()

    /// 잠금 화면 표시를 지금 돌봄 기록에 맞춘다(앱을 열 때, 앞으로 올 때).
    func syncLiveActivities() { CareLiveActivities.sync(care.value) }
}
