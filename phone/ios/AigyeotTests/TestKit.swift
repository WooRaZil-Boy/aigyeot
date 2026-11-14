import Foundation
@testable import Aigyeot

// 시험 도구(안드로이드 Kit.kt와 같은 자리). 배선은 제품과 같은 AppContainer 하나다:
// 시험은 `AppContainer(relay: FakeRelay(), store: MemoryStateStore(…), credentials: MemoryCredentialStore(…), …)`로 만들고
// 원본·유스케이스·화면 뷰모델(공장)을 그대로 부른다. 저장소는 메모리(Memory*Store), 중계는 FakeRelay, 소켓·네트워크·Keychain·FCM은 없거나 가짜다.
// 제품 코드에 시험용 넘김 함수를 두지 않는다. 아래 도움 함수는 화면이 하는 일(연결 시작 → 끝 기다리기)을 시험에서 기다릴 수 있게 할 뿐이다.

/// 잠금 화면 표시 대신 받는 시험용 OngoingNotice.
struct ClosureNotice: OngoingNotice {
    let f: (CareFile) -> Void
    func show(_ care: CareFile) { f(care) }
}

/// 시험 한 벌(안드로이드 kitTest): 가짜 중계·메모리 저장소·메모리 표시로 AppContainer를 만든다. 보낼 함은 스스로 보내지 않는다.
/// `restart()`는 같은 저장소·표시로 앱을 새로 켠다(프로세스 재시작).
@MainActor
final class Kit {
    nonisolated static let owner = Credentials(carId: "c_1", deviceId: "d_1", token: "at1.c_1.d_1.x", role: .owner)
    nonisolated static let newOwner = PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "at1.c_2.d_2.x", role: .owner)
    nonisolated static let guardian = PairResponse(carId: "c_2", deviceId: "d_3", deviceToken: "at1.c_2.d_3.x", role: .guardian)

    let relay = FakeRelay()
    let store: MemoryStateStore
    let creds: MemoryCredentialStore
    let careStore = MemoryCareStore()
    let marks: MemoryLinkMarks
    let baseURL: URL?
    /// 앱이 읽는 시계. 시험이 끝난 뒤에도 남은 작업이 읽을 수 있게 앱이 상자를 쥔다(시험 한 벌이 먼저 사라져도 된다).
    private final class Time { var now = Date(timeIntervalSince1970: 1_800_000_000) }
    private let time = Time()
    var clock: Date {
        get { time.now }
        set { time.now = newValue }
    }
    private(set) var app: AppContainer!

    /// `songs`: 받아들인(실험에 쓸 수 있는) 곡 id.
    init(creds: Credentials? = Kit.owner, songs: [String] = [], marks: [Mark: String] = [:], baseURL: URL? = nil, introSeen: Bool = true) {
        var s = PhoneState(introSeen: introSeen, carId: creds?.carId)
        s.songs = songs.map { SongRow(id: $0, name: "곡", durationS: 10, state: .accepted, addedAt: Date(timeIntervalSince1970: 1_799_000_000)) }
        store = MemoryStateStore(s)
        self.creds = MemoryCredentialStore(creds)
        self.marks = MemoryLinkMarks(marks)
        self.baseURL = baseURL
        app = make()
    }

    @discardableResult
    func restart() -> AppContainer {
        app = make()
        return app
    }

    private func make() -> AppContainer {
        let a = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, baseURL: baseURL, marks: marks,
                             now: { [time] in time.now }, sendsCareSummary: false)
        a.sendOutbox.autoFlush = false
        return a
    }

    /// 차가 보낸 봉투(지금 연결의 차).
    func carEnvelope(_ type: String, seq: Int64, body: some Encodable, id: String = ULID.generate()) -> Envelope {
        Envelope(id: id, seq: seq, type: type, car: app.state.creds?.carId ?? "c_1", from: Sender(role: .car, device: "d_9"),
                 ts: Rfc3339.string(clock), body: try! JSONValue(encoding: body))
    }
}

/// 녹음 장치 흉내(SongRecorder): 시작하면 임시 파일을 만들고, 멈추면 마지막으로 잰 길이를 돌려준다.
@MainActor
final class FakeSongRecorder: SongRecorder {
    var permissionUndetermined = false
    var permissionDenied = false
    var granted = true
    private(set) var file: URL?
    func requestPermission() async -> Bool { granted }
    var isActive: Bool { file != nil }
    func start() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("fake-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: url)
        file = url
        return url
    }
    func sample() -> (elapsed: TimeInterval, level: Float)? { file.map { _ in (1, 0.5) } }
    func stop(lastKnown: TimeInterval) -> TimeInterval { lastKnown }
    func durationS(_ url: URL, lastKnown: TimeInterval) -> TimeInterval { file = nil; return max(lastKnown, 12) }
    func play(_ url: URL, onEnded: @escaping () -> Void) -> Bool { true }
    func stopPlaying() {}
    /// 지운 녹음 파일(시트 닫힘·다시 녹음).
    private(set) var discarded: [URL] = []
    func discard(_ url: URL?) {
        file = nil
        if let url { discarded.append(url); try? FileManager.default.removeItem(at: url) }
    }
}

extension AppContainer {
    /// 연결 화면(ConnectViewModel)이 연결을 시작하고 PairDevice.run이 끝나기를 기다린다. 도는 연결이 있으면 받지 않는다(.busy).
    /// 연결되면 ConnectViewModel이 넘긴 `then`이 연결 흐름(확인 시트·연결 화면)을 닫는다.
    func pairAndWait(_ code: PairCode) async -> ConnectFailure? {
        let connect = connect()
        guard connect.canTakeLink() else { return .busy }
        connect.start(code)
        await pair.job?.value
        if case .failed(_, let f) = connect.ui.run { return f }
        return nil
    }

    /// 코드 넣기 화면의 직접 친 코드: 지난 링크 안내를 걷고 연결한다.
    func pairAndWait(typed code: PairCode) async -> ConnectFailure? {
        appViewModel.clearLinkError()
        return await pairAndWait(code)
    }
}

/// 가짜 중계. 요청마다 결과·실패·끼어들 일(hook)을 정한다.
/// 요청은 여러 작업에서 동시에 온다(nonisolated async). 상태는 시험이 쓰는 이름 그대로(`relay.sent`) 읽고 쓰되 모두 잠금 하나를 거친다 —
/// 잠금 없이 두 작업이 `sent`에 함께 붙이다 배열이 깨져 시험 앱이 죽었다. hook은 잠금 밖에서 부른다.
@dynamicMemberLookup
final class FakeRelay: RelayClient {
    struct State {
        var sent: [Envelope] = []
        var acked: [String] = []
        var uploaded: [String] = []
        var inboxPages: [InboxResponse] = []
        var sendError: RelayError?
        var inboxError: RelayError?
        var pairResult: Result<PairResponse, RelayError> = .failure(.network)
        var sendBody: JSONValue?
        var deleted = false
        var deleteError: RelayError?
        var inboxCalls = 0
        var sendHook: (() async -> Void)?
        var pairHook: (() async -> Void)?
        var pairCalls = 0
        var putHook: (() async -> Void)?
        /// inbox 응답이 오기 전에 일어나는 일(연결이 바뀌는 상황 흉내).
        var inboxHook: (() async -> Void)?
        var devicesResult = DevicesResponse(snapshotSeq: 1, devices: [])
        var devicesError: RelayError?
        var devicesHook: (() async -> Void)?
        var devicesCalls = 0
        /// 요청 번호(1부터)마다 응답을 정한다. 요청마다 따로 멈췄다 풀어 끝나는 순서를 만들 때 쓴다.
        var devicesResponder: ((Int) async -> DevicesResponse)?
        var patchCalls = 0
        var patchHook: (() async -> Void)?
        var patches: [PatchDeviceRequest] = []
        /// 중계에 저장된 인계 알림 받기(요청에 값이 없을 때 응답에 싣는다).
        var storedHandoffAlerts = true
        /// 있으면 응답 대신 이 실패(hook 뒤).
        var patchError: RelayError?
        /// 끊기 응답이 오기 전에 일어나는 일(중계는 204보다 먼저 그 폰의 소켓을 4001로 닫는다).
        var deleteHook: (() async -> Void)?
        var deletedTokens: [String] = []
        var deleteTokenError: RelayError?
        var inviteError: RelayError = .network
        /// 있으면 초대를 만들어 준다(없으면 `inviteError`). `inviteHook`은 응답 전에 돈다(그사이 연결 바뀜 흉내).
        var inviteResult: InviteResponse?
        var inviteHook: (() async -> Void)?
        var ackHook: (() async -> Void)?
        var putError: RelayError?
    }
    private let lock = NSLock()
    private var state = State()
    subscript<T>(dynamicMember key: WritableKeyPath<State, T>) -> T {
        get { lock.withLock { state[keyPath: key] } }
        set { lock.withLock { state[keyPath: key] = newValue } }
    }
    private func locked<R>(_ f: (inout State) throws -> R) rethrows -> R { try lock.withLock { try f(&state) } }

    func pair(code: String, pushToken: String?) async throws -> PairResponse {
        let hook = locked { s in s.pairCalls += 1; return s.pairHook }
        await hook?()
        return try locked { $0.pairResult }.get()
    }
    func devices(token: String) async throws -> DevicesResponse {
        let (n, hook) = locked { s in s.devicesCalls += 1; return (s.devicesCalls, s.devicesHook) }
        await hook?()
        let (error, responder, result) = locked { ($0.devicesError, $0.devicesResponder, $0.devicesResult) }
        if let error { throw error }
        if let responder { return await responder(n) }
        return result
    }
    func patchMe(token: String, _ r: PatchDeviceRequest) async throws -> PatchDeviceResponse {
        // 중계는 받은 때 처리하고 그때의 값을 응답에 싣는다. 응답이 늦게 닿는 일은 hook이 흉내 낸다
        let (response, hook) = locked { s in
            s.patchCalls += 1
            s.patches.append(r)
            if let on = r.handoffAlerts { s.storedHandoffAlerts = on }
            return (PatchDeviceResponse(deviceId: "d", role: .guardian, platform: .ios, handoffAlerts: s.storedHandoffAlerts, pushTokenSet: false),
                    s.patchHook)
        }
        await hook?()
        if let e = locked({ $0.patchError }) { throw e }
        return response
    }
    /// 연결 응답의 토큰이면 저장하지 못한 연결 되돌리기(`deletedTokens`), 아니면 설정 끊기(`deleted`)로 본다.
    func deleteMe(token: String) async throws {
        let rolledBack = try locked { s in
            guard case .success(let p) = s.pairResult, p.deviceToken == token else { return false }
            if let e = s.deleteTokenError { throw e }
            s.deletedTokens.append(token)
            return true
        }
        if rolledBack { return }
        await locked({ $0.deleteHook })?()
        try locked { s in
            if let e = s.deleteError { throw e }
            s.deleted = true
        }
    }
    func createInvite(token: String) async throws -> InviteResponse {
        await locked({ $0.inviteHook })?()
        let (result, error) = locked { ($0.inviteResult, $0.inviteError) }
        if let result { return result }
        throw error
    }
    func send(token: String, _ e: Envelope) async throws -> SendMessageResponse {
        await locked({ $0.sendHook })?()
        return try locked { s in
            if let error = s.sendError { throw error }
            s.sent.append(e)
            return SendMessageResponse(id: e.id, seq: Int64(s.sent.count), recipients: 1, body: s.sendBody ?? e.body)
        }
    }
    func inbox(token: String, car: String, afterSeq: Int64?) async throws -> InboxResponse {
        let hook = locked { s in s.inboxCalls += 1; return s.inboxHook }
        await hook?()
        return try locked { s in
            if let error = s.inboxError { throw error }
            return s.inboxPages.isEmpty ? InboxResponse(messages: [], more: false) : s.inboxPages.removeFirst()
        }
    }
    func ack(token: String, ids: [String]) async throws {
        await locked({ $0.ackHook })?()
        locked { $0.acked += ids }
    }
    func putSong(token: String, id: String, file: URL) async throws {
        await locked({ $0.putHook })?()
        try locked { s in
            if let error = s.putError { throw error }
            s.uploaded.append(id)
        }
    }
}
