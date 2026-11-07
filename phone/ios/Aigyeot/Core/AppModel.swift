import Foundation
import Observation

/// FCM 등록 토큰(5.4). 앱의 `PushToken`이 `messaging(_:didReceiveRegistrationToken:)`에서 채우고, 연결 요청·앱 열기·토큰 바뀜 때 `PATCH /v1/devices/me`로 올린다.
/// `GoogleService-Info.plist`가 없으면(공개 저장소·시험) 늘 nil이다.
protocol PushTokenSource {
    var token: String? { get }
}

enum ConnectFailure: Equatable {
    case codeInvalid, rateLimited(TimeInterval?), offline, badCode, other, busy
    /// 새 차로 바꾸려고 지금 연결을 끊다가 실패했다. 끊기의 이유 문구 그대로다.
    case disconnectFailed(String)
    /// 중계는 연결했는데 이 폰에 저장하지 못했다. `removed`: 받은 토큰으로 중계의 그 연결을 지웠다(안드로이드 discardPaired와 같은 글).
    case notSaved(removed: Bool)

    var message: String {
        switch self {
        case .codeInvalid: return "코드가 맞지 않거나 시간이 지났어요. 차 화면의 코드를 다시 확인해 주세요."
        case .rateLimited(let wait):
            if let w = wait, w >= 1 { return "잠시 뒤에 다시 해 주세요. (\(Int(w.rounded(.up)))초 뒤)" }
            return "잠시 뒤에 다시 해 주세요."
        case .offline: return "인터넷에 연결한 뒤 다시 해 주세요."
        case .badCode: return "6자리 숫자 또는 초대 코드를 넣어 주세요"
        case .other: return "연결하지 못했어요. 잠시 뒤에 다시 해 주세요."
        case .busy: return "이미 연결하고 있어요. 잠시 뒤에 다시 해 주세요."
        case .disconnectFailed(let m): return m
        case .notSaved(let removed): return removed ? "연결 정보를 저장하지 못했어요" : "연결 정보를 저장하지 못했어요. 차 화면에서 이 휴대폰 연결을 끊어 주세요."
        }
    }
}

enum SongError: Error, Equatable {
    case badName, tooLong, tooLarge, limitReached, saveFailed

    var message: String {
        switch self {
        case .badName: return "이름은 1~30자로 써 주세요."
        case .tooLong: return "3분이 넘어요. 3분 안으로 다시 녹음해 주세요."
        case .tooLarge: return "파일이 너무 커요. 다시 녹음해 주세요."
        case .limitReached: return "노래는 3곡까지예요. 한 곡을 지운 뒤 보내 주세요."
        case .saveFailed: return "저장하지 못했어요. 저장 공간을 확인해 주세요."
        }
    }
}

@MainActor
@Observable
final class AppModel {
    // MARK: 화면이 보는 값
    private(set) var state: PhoneState
    /// 돌봄 기록(`care.json`). 연결과 따로 저장한다(phone-care-plan U3·U6).
    private(set) var care: CareFile
    private(set) var credentials: Credentials?
    /// 기기가 인터넷에 닿는지(NWPathMonitor) 또는 마지막 호출이 네트워크 오류였는지.
    var offline = false
    var socketConnected = false
    private(set) var clockSkew: TimeInterval?
    /// 401·4001로 끊긴 뒤 연결 화면에 보이는 안내.
    private(set) var gone = false
    /// 확인 화면을 기다리는 코드(QR·링크만, G17. 직접 친 코드는 `connect(typed:)`로 바로 연결한다).
    /// 이미 연결된 폰이면 확인 화면이 지금 차를 끊을지도 함께 묻는다(G16).
    var pendingCode: PairCode? { didSet { if pendingCode == nil, oldValue != nil { linkError = nil } } }   // 확인 화면이 닫히면 그 화면의 안내도 지운다(다음에 연 화면에 남지 않게)
    var linkError: String?
    /// 연결 429 잠금이 끝나는 시각. 직접 입력·확인 화면이 같이 본다. 화면을 옮기거나 코드를 고쳐도 풀리지 않는다(계약 9장).
    private(set) var pairLockedUntil: Date?
    /// 앱 안 배너로 띄울 인계 알림 메시지 id(`show(msg_id)`, 5.6).
    var bannerAlertId: String?
    /// 알림을 눌러 열었을 때 상세로 갈 인계 알림 id.
    var openAlertId: String?
    /// 알림을 눌러 열려는 id. 본문이 저장되면 `openAlertId`로 옮긴다. 연결 세대가 바뀌거나 앱이 뒤로 가면 지운다(누른 그 앞 화면 동안만 기다린다).
    @ObservationIgnored var pendingOpenAlertId: String?
    /// 홈 위쪽 띠에 보이는 알림(보내지 못한 것 등).
    var notice: String?
    /// 탭(연결 안 됨) 위에 연결 흐름을 띄웠는가(U7). 저장하지 않는다.
    var connectShown = false { didSet { if !connectShown, oldValue { linkError = nil } } }   // 연결 화면이 닫히면 그 화면의 안내도 지운다
    /// 방금 지운 돌봄 기록 id. 화면이 "지웠어요" + "되돌리기"(5초)를 보인다(U38). 저장하지 않는다.
    var recentlyDeleted: String?

    // MARK: 의존
    @ObservationIgnored private let relay: RelayAPI
    @ObservationIgnored private let store: StateStore
    @ObservationIgnored private let careStore: CareStore
    @ObservationIgnored private let credStore: CredentialStore
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let baseURL: URL?
    @ObservationIgnored var pushToken: PushTokenSource?
    /// 돌봄 기록이 저장된 뒤 부른다. 앱은 여기서 잠금 화면 표시(Live Activity)를 맞춘다(C6). 시험에서는 비워 둔다.
    @ObservationIgnored var onCareChange: (@MainActor (CareFile) -> Void)?
    /// 시험에서 끈다. 켜 두면 보낼 함에 넣을 때마다 바로 보낸다.
    @ObservationIgnored var autoFlush = true
    /// 시험에서 끈다. 수유·노래·상태 요청 시험이 보낼 함의 순서를 돌봄 요약 없이 보게 한다(R19 시험은 켠다).
    @ObservationIgnored var sendsCareSummary: Bool
    /// init이 끝났는가. init 안의 돌봄 저장(옮기기·정리)은 요약을 보낼 함에 넣기만 하고 보내기는 앞으로 올 때의 갱신이 한다(수유와 같다).
    /// 지우다 만 기록 지우기를 마치는 길(finishCareClear → reconcileCareSummary)은 이 표시를 보지 않아 init 중에도 보낸다(앱 시작도 R19 계기다).
    @ObservationIgnored private var started = false
    @ObservationIgnored private(set) var socket: RelaySocket?
    @ObservationIgnored private var flushing = false
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private var devicePollTask: Task<Void, Never>?
    @ObservationIgnored private var staleRetryTask: Task<Void, Never>?
    /// 연결(토큰)이 바뀔 때마다 올린다. await 뒤에 응답·오류의 세대를 확인해 이전 차의 결과를 버린다.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var appActive = false
    @ObservationIgnored private var refreshing = false
    /// 소켓이 중계에 닿았는지(붙음·닿지 않음)를 본 횟수. 이 값이 바뀐 뒤에 끝난 HTTP 요청은 더 오래된 관찰이라 오프라인 띠를 바꾸지 않는다.
    @ObservationIgnored private var linkSeen = 0
    @ObservationIgnored private var carLiveSeen = 0   // 차의 실시간 봉투를 적용한 횟수
    @ObservationIgnored private var lastCarLiveAt: Date?
    /// HTTP 요청에 시작 순서대로 매기는 번호. 늦게 끝난 앞 요청의 결과가 나중에 시작한 요청의 결과를 덮지 않게 한다.
    @ObservationIgnored private var httpTicket = 0
    @ObservationIgnored private var appliedTicket = 0
    @ObservationIgnored private var refreshRetries = 0
    /// 지금 보내는 보낼 함 항목 id. 만료 정리가 결과를 기다리게 한다(보내는 사이 7일을 넘겨도 성공을 실패로 적지 않게, 안드로이드 `sending`).
    @ObservationIgnored private var sendingId: String?
    /// 갱신이 도는 사이 소켓이 다시 붙었다: 끝난 뒤 그 접속의 받은 함을 다시 본다.
    @ObservationIgnored private var reconnectedDuringRefresh = false
    @ObservationIgnored private var appliedDevicesTicket = 0
    /// 실시간 메시지 저장이 실패하면 올린다. 그 전에 띄운 실시간 처리는 버린다(뒤 메시지를 먼저 적용하지 않게).
    @ObservationIgnored private var liveEpoch = 0
    /// 뒤로 갈 때 올린다. 진행 중 갱신이 뒤에서 다음 요청을 시작하지 않게 한다.
    @ObservationIgnored private var foregroundEpoch = 0
    /// 갱신이 끝난 뒤로 미룬 재시도의 최소 기다림(받은 함 429의 Retry-After).
    @ObservationIgnored private var retryAfterRefreshAtLeast: TimeInterval?
    /// 마지막으로 예약한 받은 함 다시 하기 간격(초, 시험용).
    @ObservationIgnored private(set) var scheduledRefreshDelay: UInt64?
    /// 상태 파일 복구 표시를 저장하지 못했다. 저장될 때까지 메시지 적용을 미룬다(7.4).
    @ObservationIgnored private var pendingRecovery = false
    /// 보낼 함 429의 기다림을 저장하지 못했을 때 메모리로 지킨다.
    @ObservationIgnored private var outboxHoldUntil: Date?
    /// 받은 함 429의 Retry-After 기한. 앞으로 오기·새로고침·다시 붙기 모두 이때까지 받은 함을 다시 부르지 않는다(1.1).
    @ObservationIgnored private var inboxHoldUntil: Date?
    /// 요약의 '오늘'·'같은 날'을 세는 기기 시간대(시험에서 바꾼다).
    @ObservationIgnored var timeZone: () -> TimeZone = { .current }
    /// 소켓을 열 때마다 올린다. 닫은 소켓의 늦은 이벤트를 버린다.
    @ObservationIgnored private var socketEpoch = 0
    @ObservationIgnored private var retryAfterRefresh = false   // 갱신 중에 저장이 실패했으면 갱신이 끝난 뒤 재시도를 예약한다
    @ObservationIgnored private var requestingState = false
    /// 연결 요청이 진행 중이면 새 요청을 받지 않는다(안드로이드 startPair의 pairJob?.isActive와 같다). 늦게 온 옛 응답이 새 연결을 덮지 않게 한다.
    @ObservationIgnored private var pairing = false
    /// 확인 화면의 '연결하기'·'끊고 연결하기'가 도는 동안(끊기 요청 포함). 그사이 온 새 링크는 받지 않는다(U52).
    @ObservationIgnored private var confirming = false
    /// '이 휴대폰의 기록 지우기'를 한 횟수. 상태 요청 응답을 기다리는 사이 지웠는지 알아내는 데 쓴다.
    @ObservationIgnored private var clearCount = 0
    /// 처음 받은 함을 처리하는 동안 온 소켓 프레임은 모아 둔다(밀린 것과 실시간을 구분).
    @ObservationIgnored private var backlogDone = true
    @ObservationIgnored private var bufferedLive: [Envelope] = []
    /// 모델이 띄운 백그라운드 작업. 시험이 `settle()`로 끝나기를 기다린다.
    @ObservationIgnored private var pending: [UUID: Task<Void, Never>] = [:]

    init(relay: RelayAPI, store: StateStore, credentials: CredentialStore, careStore: CareStore = MemoryCareStore(),
         baseURL: URL? = nil, now: @escaping () -> Date = Date.init, sendsCareSummary: Bool = true) {
        self.sendsCareSummary = sendsCareSummary
        self.relay = relay
        self.store = store
        self.careStore = careStore
        self.care = careStore.load()
        self.credStore = credentials
        self.baseURL = baseURL
        self.now = now
        self.state = store.load()
        self.credentials = credentials.load()
        // 토큰이 없는데 상태만 남았으면(Keychain만 지워진 경우) 상태도 비운다.
        if self.credentials == nil, self.state.carId != nil { self.state = Self.fresh(introSeen: true, keeping: self.state) }
        // 띠를 보기 전에 앱이 끝났으면 버린 메시지 안내를 다시 보인다(U54, 사용자가 닫을 때까지)
        for line in self.state.unseenDrops.compactMap(Self.dropNotice) { addNotice(line) }
        // 저장 파일이 깨져 빈 상태로 복구했는데 자격은 남아 있으면, 이미 ack한 수유·곡 상태가 받은 함에 없다.
        // 요청 필요 표시를 켜 두면 기기 목록 조회가 성공한 뒤 state_request로 다시 받는다(계약 7.4, G39).
        // 저장이 거부된 실행은 건드리지 않는다. 원본이 그대로라 다음 정상 실행이 그것을 읽는다.
        // 이 표시의 저장이 실패하면 기억해 두고, 저장될 때까지 메시지 적용을 미룬다(markStaleIfNeeded가 다시 한다).
        // 자격만 남고 상태에 차가 없는 경우(앱 재설치 뒤 Keychain만 남음, 자격 저장 직후 상태 저장 전에 종료)도 같다.
        if store.restoreFailed || self.state.carId == nil, !store.writesRefused, let c = self.credentials, c.role == .owner {
            // 기준 시각도 채워, 첫 요청이 중계에서 만료돼도 24시간 재요청이 걸리게 한다(안드로이드 paired와 같다).
            pendingRecovery = !update { $0.carId = c.carId; $0.needsStateRequest = true; $0.lastReceivedAt = $0.lastReceivedAt ?? now() }
        }
        purgeExpired()
        if !store.restoreFailed { cleanSongFiles() }   // 복원에 실패한 실행에서는 녹음 파일을 지우지 않는다
        RecorderModel.removeTempRecordings()   // 앞 실행이 녹음 시트에서 죽어 남긴 원본(시작할 때는 녹음 중일 수 없다)
        migrateFeedingsIfNeeded()
        finishCareClear()
        purgeCare()
        enqueueLatestFeeding()   // 앱 시작(R6). 보내기는 앞으로 올 때의 갱신이 한다
        enqueueCareSummary()     // 같은 계기(R19)
        started = true
    }

    // MARK: 파생 값

    var role: PhoneRole? { credentials?.role }
    /// '누른 것은 연결되면 차로 가요' 띠는 차에 연결된 폰에만 보인다(U7: 연결 전에는 차로 보내지 않는다, 안드로이드와 같다).
    var showsOfflineBand: Bool { offline && isPaired }
    var isPaired: Bool { credentials != nil }

    var clockWarning: String? {
        guard let s = clockSkew, abs(s) > 120 else { return nil }
        return "휴대폰 시계가 \(Int((abs(s) / 60).rounded()))분 달라요. 수유 시각이 어긋날 수 있어요."
    }

    var car: DeviceInfo? { state.devices.first { $0.role == .car } }
    var visibleSongs: [SongRow] { state.songs.filter { $0.state != .removed }.sorted { $0.addedAt < $1.addedAt } }
    /// 3곡 한도에 드는 곡 수. 거절된 곡은 줄은 보이지만(9장 거절 문구·'다시 녹음') 자동 지움을 보내므로 자리는 차지하지 않는다(7.4, G40).
    var songSlotsUsed: Int { songSlots(excluding: nil) }
    private func songSlots(excluding id: String?) -> Int {
        visibleSongs.filter { s in
            if s.id == id { return false }
            if case .rejected = s.state { return false }
            return true
        }.count
    }

    /// 마지막 수유 시작: 지우지 않은 수유 기록 중 가장 늦은 것과 차의 `feeding_state` 값 중 늦은 것(R12).
    var lastFeedingStart: Date? {
        [state.feeding.lastStart, CareRules.latestFeed(care.events).map(shownStart)].compactMap { $0 }.max()
    }

    /// 화면에 보일 수유 시작. 중계가 미래 시각을 고쳐 저장했으면 그 시각이다(계약 3.3). 기록의 누른 시각은 고치지 않는다(G33).
    func shownStart(_ e: CareEvent) -> Date { feedingRecord(for: e)?.fixedAt ?? CareTime.date(e.startAt) }

    func alert(_ id: String) -> StoredAlert? { state.alerts.first { $0.id == id } }

    /// 이 수유를 보낸 기록(보내는 중·보냄·차에 반영됨). `state.json`은 초까지만 저장해(.iso8601) 1초 안이면 같은 수유로 본다(아래 반영 판정과 같다).
    func feedingRecord(for e: CareEvent) -> FeedingRecord? {
        state.feedingLog.first { abs(CareTime.ms($0.at) - e.startAt) < 1000 }
    }

    // MARK: 저장

    /// 상태를 바꾸고 저장한다. 저장하지 못하면 메모리 상태도 바꾸지 않고(롤백) false를 돌려준다.
    /// 상태 변경과 보낼 함 넣기는 한 번의 `update`로 묶어 한 번에 저장한다(계약 8.3의 '한 트랜잭션').
    @discardableResult
    private func update(_ f: (inout PhoneState) -> Void) -> Bool {
        // 원본을 보존하지 못한 실행은 모든 저장을 거부한다. 같음 비교보다 먼저 막아야
        // 빈 상태로의 초기화가 '성공'으로 보여 녹음 파일이 지워지는 일이 없다.
        if store.writesRefused {
            addNotice(Self.saveFailed)
            return false
        }
        var copy = state
        f(&copy)
        if copy == state { return true }   // 바뀐 게 없으면 저장하지 않는다
        do { try store.save(copy) }
        catch {
            addNotice(Self.saveFailed)
            return false
        }
        state = copy
        return true
    }

    static let saveFailed = "저장하지 못했어요. 저장 공간을 확인해 주세요."   // 띠와 시트가 같은 글을 쓴다

    /// 연결을 비운 새 상태. 지우다 만 '기록 지우기'(돌봄 기록은 연결과 따로다, R9·R10)는 남긴다.
    private static func fresh(introSeen: Bool, keeping old: PhoneState) -> PhoneState {
        var s = PhoneState(introSeen: introSeen)
        s.clearingCareIds = old.clearingCareIds
        return s
    }

    /// 띠의 '닫기'. 저장해 둔 버린 메시지 안내도 이때 지운다(U54).
    func dismissNotice() {
        notice = nil
        if !state.unseenDrops.isEmpty { update { $0.unseenDrops = [] } }
    }

    /// U54: 띠 글에 한 줄을 더한다. 띠가 하나라 떠 있는 안내(다른 버림·저장 실패)를 덮지 않고 줄을 바꿔 함께 보인다.
    /// 같은 글은 한 번. 사용자가 띠를 닫으면 다음 안내부터 새로 모은다(안드로이드 withNoticeLine).
    func addNotice(_ line: String) {
        guard let n = notice else { notice = line; return }
        if !n.components(separatedBy: "\n").contains(line) { notice = n + "\n" + line }
    }

    /// '앞 기록을 끝냈어요' 안내. 앞의 같은 종류 안내는 바꾸고, 버림·저장 실패 안내는 남긴다(U54, 안드로이드는 돌봄 안내를 따로 둔다).
    private func setCareNotice(_ line: String) {
        let care: Set = [CareText.previousFeedEnded, CareText.previousFeedEndedUnknown, CareText.previousSleepEnded, CareText.previousSleepEndedUnknown]
        let kept = (notice ?? "").components(separatedBy: "\n").filter { !$0.isEmpty && !care.contains($0) }
        notice = kept.isEmpty ? nil : kept.joined(separator: "\n")
        addNotice(line)
    }

    /// 7.1 outbox.dropped(U54): 버린 메시지의 띠 글. 노래 추가는 노래 줄에만 보인다.
    static func dropNotice(_ type: String) -> String? {
        switch type {
        case MessageType.feedingStarted: "수유 기록을 차로 보내지 못했어요."
        case MessageType.songRemoved: "노래 삭제를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요."
        case MessageType.songSelected: "실험에 쓸 노래 선택을 차로 보내지 못했어요."
        case MessageType.careSummary: CareText.careSummaryDropped
        default: nil
        }
    }

    /// 7일 지나 버린 메시지도 알린다(U54). 정리를 저장한 뒤에 부른다.
    private func noteExpired(_ types: [String]) { types.compactMap(Self.dropNotice).forEach(addNotice) }

    /// 7일 지난 id·보낼 함, 90일 지난 기록, 지운 지 7일 지난 곡 표시를 지운다(7.4, G34).
    func purgeExpired() {
        let t = now()
        let week = t.addingTimeInterval(-7 * 86400), quarter = t.addingTimeInterval(-90 * 86400)
        var expired: [String] = []
        let ok = update { s in
            s.processed = s.processed.filter { $0.value > week }
            s.shown = s.shown.filter { $0.value > week }
            expired = Self.expireOutbox(before: week, except: sendingId, in: &s)
            s.reports.removeAll { $0.receivedAt < quarter }
            s.alerts.removeAll { $0.receivedAt < quarter }
            s.songs.removeAll { $0.state == .removed && ($0.removedAt ?? $0.addedAt) < week }
            s.deviceMarks = s.deviceMarks.filter { $0.value.updatedAt > week }
        }
        if ok { noteExpired(expired) }
    }

    /// 앱을 시작할 때 지움 상태이거나 목록에 없는 곡의 녹음 파일을 지운다(저장 직후 죽어 파일이 남은 경우).
    func cleanSongFiles() {
        let keep = Set(state.songs.filter { $0.state != .removed }.map(\.id))
        let files = (try? FileManager.default.contentsOfDirectory(at: SongFiles.directory, includingPropertiesForKeys: nil)) ?? []
        for f in files where f.pathExtension == "m4a" && !keep.contains(f.deletingPathExtension().lastPathComponent) {
            try? FileManager.default.removeItem(at: f)
        }
    }

    /// 7일 지난 보낼 함 항목을 지우고, 진행 중으로 보이던 수유·곡에는 만료 표시를 남긴다(같은 저장 안에서, 계약 7.5).
    /// 버린 항목의 type을 돌려준다(저장한 뒤 U54 안내).
    @discardableResult
    private static func expireOutbox(before cutoff: Date, except sending: String?, in s: inout PhoneState) -> [String] {
        let old = { (i: OutboxItem) in i.createdAt < cutoff && i.id != sending }   // 보내는 중인 것은 결과를 먼저 적용한다
        // 차에 이미 반영된 수유(feeding_state로 확인)는 실패로 알리지 않는다(7.5 '차에 반영됨', 안드로이드 feedingDone과 같다)
        let reflected = Set(s.feedingLog.filter { $0.status == .reflected }.map(\.id))
        let expired = s.outbox.filter { old($0) && !($0.type == MessageType.feedingStarted && reflected.contains($0.id)) }.map(\.type)
        for t in expired where dropNotice(t) != nil && !s.unseenDrops.contains(t) { s.unseenDrops.append(t) }   // 앱이 끝나도 안내를 잃지 않게
        for item in s.outbox where old(item) {
            switch item.type {
            case MessageType.feedingStarted:
                if let i = s.feedingLog.firstIndex(where: { $0.id == item.id }), s.feedingLog[i].status == .sending {
                    s.feedingLog[i].status = .expired
                }
            case MessageType.songAdded:
                if let id = item.songId, let i = s.songs.firstIndex(where: { $0.id == id }),
                   s.songs[i].state == .uploading || s.songs[i].state == .waiting {
                    s.songs[i].state = .failed("7일이 지나 보내지 못했어요. 다시 녹음해 주세요.")
                }
            default: break
            }
        }
        s.outbox.removeAll(where: old)
        if expired.contains(MessageType.careSummary) { forgetSentSummary(in: &s) }   // 차에 닿지 않았다
        return expired
    }

    func markIntroSeen() { update { $0.introSeen = true } }

    // MARK: 연결

    /// 링크·QR이 이 길로 온다. 확인 화면을 거치고 바로 연결하지 않는다(G17).
    func receive(link url: URL) {
        guard let code = PairCode.parse(link: url) else {
            let text = "올바른 연결 링크가 아니에요"   // 7.6 검사
            // 연결·확인 화면이 떠 있으면 그 화면이, 탭 화면이면 띠가 보인다(안드로이드는 토스트). 나중에 연 연결 화면에 옛 문구로 남지 않게
            if connectShown || pendingCode != nil { linkError = text } else { addNotice(text) }
            return
        }
        // U62: 첫 실행 안내 화면에서 받은 링크는 안내를 그대로 두고 코드만 쥔다('차와 연결하기'에서 확인 화면을 연다). 무엇이 오가는지 읽기 전에 연결되지 않게
        if !state.introSeen, !isPaired { introCode = code; return }   // '연결도 기록만도 고른 적 없는 첫 실행'(U62)
        propose(code)
    }

    /// U62: 첫 실행 안내 화면에서 받은 연결 링크의 코드(메모리에만, 안드로이드 introCode와 같다).
    @ObservationIgnored private(set) var introCode: PairCode?

    /// 안내 화면의 '차와 연결하기'(U62): 안내에서 받은 코드가 있으면 그 코드로 확인 화면을 연다(안내는 그대로 두고, 연결되면 넘어간다).
    /// 한 번만 쓴다: 확인 화면을 닫고 다시 누르면 연결 방법 화면이다.
    func connectFromIntro() {
        if let c = introCode { introCode = nil; propose(c); return }
        markIntroSeen()
        connectShown = true
    }

    /// 안내 화면의 '먼저 기록만 쓸게요': 안내에서 받은 코드는 버린다(U62).
    func recordsOnlyFromIntro() {
        introCode = nil
        markIntroSeen()
    }

    /// 429 잠금이 남았는가. 시각은 `now` 시계로 본다.
    var isPairLocked: Bool { pairLockedUntil.map { $0 > now() } ?? false }

    /// 429 뒤 잠금 길이(초). Retry-After가 없거나 1초 미만이면 10초·1초(안드로이드 pairLockUntil과 같다).
    static func pairLockSeconds(_ retryAfter: TimeInterval?) -> TimeInterval { max(retryAfter ?? 10, 1) }

    func propose(_ code: PairCode) {
        // 직접 친 코드의 요청이 진행 중이면 확인 화면으로 옮기지 않는다(끝난 결과를 받을 화면이 닫힌다).
        // U52: 끊고 연결의 끊기 중에도 막는다. 화면 코드만 바뀌고 연결은 앞 코드로 가는 어긋남을 막는다.
        guard !pairing, !confirming else { showBusy(); return }
        linkError = nil
        pendingCode = code
    }

    /// `pair.busy`를 짧게 보인다. 그사이 다른 문구로 바뀌었으면 건드리지 않는다.
    private func showBusy() {
        linkError = CareText.pairBusy
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            if self?.linkError == CareText.pairBusy { self?.linkError = nil }
        }
    }

    /// 코드 넣기 화면에서 직접 친 코드는 확인 없이 바로 연결한다(G17, L57). 오류는 호출한 화면이 보인다.
    /// 코드 넣기 화면은 연결되지 않았을 때만 보이므로 바꾸기 확인(G16)은 거치지 않는다.
    func connect(typed code: PairCode) async -> ConnectFailure? {
        linkError = nil
        return await pair(code)
    }

    /// 확인 화면의 연결 버튼. 이미 연결돼 있으면 이 버튼 하나로 지금 연결을 끊고 새 차에 연결한다(G16, 안드로이드 `startPair`와 같다).
    /// 확인하기 전에는 지금 연결을 지우지 않는다. 그사이 끊겼으면(401) 끊기를 거치지 않는다(U13 ②).
    /// 확인 화면의 연결 버튼이 Task를 띄우기 전에 부른다: 그사이 온 새 링크가 화면 코드를 바꾸지 않게(U52).
    func beginConfirm() { confirming = true }
    func endConfirm() { confirming = false }

    func confirmPair(_ code: PairCode) async -> ConnectFailure? {
        confirming = true
        defer { confirming = false }
        if isPaired, let message = await disconnect() { return .disconnectFailed(message) }
        return await pair(code)
    }

    /// 중계는 연결했는데 이 폰에 저장하지 못했다: 차에 토큰 없는 폰이 남지 않게 방금 받은 토큰으로 그 연결을 지운다(안드로이드 discardPaired와 같다).
    /// 못 지우면 토큰을 남길 곳도 없으니 차 화면에서 끊게 알린다. 401은 이미 지워진 것이다.
    private func discardPaired(_ c: Credentials) async -> ConnectFailure {
        do { try await relay.deleteMe(token: c.token); return .notSaved(removed: true) }
        catch let e as RelayError { return .notSaved(removed: e.isUnauthorized) }
        catch { return .notSaved(removed: false) }
    }

    func pair(_ code: PairCode) async -> ConnectFailure? {
        guard !pairing else { return .busy }
        if isPairLocked, let until = pairLockedUntil { return .rateLimited(until.timeIntervalSince(now())) }
        pairing = true
        defer { pairing = false }
        // 연결 요청의 결과도 띠에 반영한다(9장, 끊긴 동안 켜진 띠가 다시 연결한 뒤 남지 않게). 성공하면 세대가 바뀌어 observed 대신 직접 본다
        let mark = httpMark()
        do {
            let r = try await relay.pair(code: code.value, pushToken: pushToken?.token)
            observe(nil, since: mark)
            guard let role = PhoneRole(rawValue: r.role.rawValue) else { return .other }
            // 연결 세대가 바뀐다. 이전 연결의 응답은 이제 버려진다.
            // 이전 상태·보낼 함을 지우는 저장이 성공해야만 새 자격 증명을 켠다(이전 차의 보낼 함이 새 차로 가지 않게).
            let c = Credentials(carId: r.carId, deviceId: r.deviceId, token: r.deviceToken, role: role)
            guard wipeLocal() else { return await discardPaired(c) }
            // Keychain에 저장하지 못하면 연결로 치지 않는다(다음 실행에서 토큰이 없어 끊긴 것처럼 되지 않게).
            do { try credStore.save(c) } catch { return await discardPaired(c) }
            credentials = c
            update { s in
                s.introSeen = true   // 첫 안내를 넘기지 않고 링크로 연결해도 홈으로 간다(7.1 DONE → HOME, 안드로이드와 같다)
                s.carId = r.carId
                s.lastReceivedAt = now()   // 연결 시각을 기준으로 둬 차 메시지를 한 번도 못 받아도 24시간 재요청이 걸린다(안드로이드 paired와 같다)
                s.handoffAlerts = role == .guardian ? true : nil   // 기본값은 guardian 참, owner 거짓(G12)
            }
            pendingCode = nil
            gone = false
            connectShown = false   // 연결이 끝나면 오늘 탭으로 간다(5장)
            notice = "연결됐어요"
            reconcileFeeding()   // 연결 직후(R6): 연결 전에 적은 24시간 안의 수유를 보낸다
            reconcileCareSummary()   // 같은 계기(R19)
            if appActive { didBecomeActive() }   // 연결 직후 받기·동기화를 바로 시작한다
            return nil
        } catch let e as RelayError {
            observe(e, since: mark)
            switch (e.status, e.code) {
            case (404, _): return .codeInvalid
            case (429, _):
                lockPair(e.retryAfter)
                return .rateLimited(e.retryAfter)
            case (400, "bad_code_format"): return .badCode
            default: return e.isOffline ? .offline : .other
            }
        } catch {
            return .other
        }
    }

    private func lockPair(_ retryAfter: TimeInterval?) {
        let secs = Self.pairLockSeconds(retryAfter)
        let until = now().addingTimeInterval(secs)
        pairLockedUntil = until
        // 끝나는 때 화면이 다시 그려지게 값을 비운다. 그 사이 새 429가 와서 시각이 바뀌었으면 건드리지 않는다.
        // 허용 오차를 0으로 준다. 기본값으로는 기다린 시간의 약 6.5%만큼 늦게 깨어 589초 잠금이 39초 늦게 풀렸다(시뮬레이터 실측).
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(secs), tolerance: .zero, clock: .continuous)
            if self?.pairLockedUntil == until { self?.pairLockedUntil = nil }
        }
    }

    /// 사용자가 요청한 끊기 중(안드로이드 `leaving`과 같다). 그사이 온 4001·401은 강제 해제가 아니라 이 끊기의 결과다.
    private var leaving = false
    /// 끊기 중에 온 401·4001의 연결 세대.
    @ObservationIgnored private var lostWhileLeaving: Int?
    @ObservationIgnored private var leavingTask: Task<String?, Never>?

    /// 설정의 '연결 끊기'. 중계의 토큰을 지워야 해서 인터넷이 필요하다. 실패하면 문구를 돌려준다.
    func disconnect() async -> String? {
        // 설정의 끊기와 '끊고 연결하기'의 끊기가 겹치면 뒤에 온 쪽은 앞의 결과를 기다린다(끊기 중 받은 401·4001 표시를 지우지 않게).
        if let running = leavingTask { return await running.value }
        let task = Task { await performDisconnect() }
        leavingTask = task
        let result = await task.value
        leavingTask = nil
        return result
    }

    private func performDisconnect() async -> String? {
        let gen = generation
        leaving = true
        lostWhileLeaving = nil
        defer {
            leaving = false
            // 끊기가 실패로 끝났는데 그사이 이 연결의 401·4001이 왔다: 토큰은 이미 지워졌으니 그대로 GONE으로 간다(7.1).
            let lost = lostWhileLeaving == generation
            lostWhileLeaving = nil
            if lost { handleUnauthorized() }
        }
        do { try await observed { try await relay.deleteMe() } }   // 중계는 204보다 먼저 이 폰의 소켓을 4001로 닫는다(removeDevice)
        catch let e as RelayError {
            if e.isOffline { return "인터넷이 필요해요" }
            if !e.isUnauthorized { return "끊지 못했어요. 잠시 뒤에 다시 해 주세요." }
        } catch { return "끊지 못했어요. 잠시 뒤에 다시 해 주세요." }
        if gen == generation {
            wipeAll()
            notice = CareText.disconnected   // U25 b: 탭(연결 안 됨)의 오늘로 가고 띠를 보인다
        }
        return nil
    }

    /// '연결이 끊겼어요' 화면의 '기록만 계속 쓸게요'(U25 a).
    func continueWithoutCar() { gone = false }

    /// '연결이 끊겼어요' 화면의 '다시 연결하기'(U25 a): 연결 흐름으로 간다.
    func reconnectAfterGone() {
        gone = false
        connectShown = true
    }

    /// 어디서든 401 또는 닫기 코드 4001(계약 7.1 GONE). 호출한 쪽이 현재 연결의 응답인지 먼저 확인한다.
    func handleUnauthorized() {
        guard isPaired else { return }
        if leaving { lostWhileLeaving = generation; return }   // 끊기의 결과일 수 있다. 끊기가 끝날 때 본다
        wipeAll()
        gone = true
    }

    /// 로컬 기록을 비우고 연결 세대를 올린다. 이 뒤에 도착하는 이전 연결의 응답·오류는 모두 버려진다.
    /// 초기화 저장이 성공했는지 돌려준다. 파일은 저장이 성공한 뒤에 지운다.
    @discardableResult
    private func wipeLocal() -> Bool {
        generation += 1
        pendingOpenAlertId = nil; openAlertId = nil   // 지우는 알림을 가리키는 상세가 다시 연결한 뒤 '불러오고 있어요'로 남지 않게
        retryTask?.cancel()
        staleRetryTask?.cancel()
        flushing = false; refreshing = false; requestingState = false; retryAfterRefresh = false; reconnectedDuringRefresh = false
        retryAfterRefreshAtLeast = nil; outboxHoldUntil = nil; inboxHoldUntil = nil; pendingRecovery = false
        backlogDone = true; bufferedLive = []; sendingId = nil   // 옛 연결의 보내는 중 표시가 새 보낼 함의 '보내 봄' 판정(U57)에 남지 않게
        let fresh = Self.fresh(introSeen: state.introSeen, keeping: state)
        guard update({ $0 = fresh }) else { return false }
        SongFiles.removeAll()
        SongFiles.removeBrokenCopies()
        return true
    }

    private func wipeAll() {
        stopSocket()
        wipeLocal()
        credStore.clear()
        credentials = nil
        bannerAlertId = nil   // 확인 화면(pendingCode)은 남긴다: 끊긴 뒤에도 그 코드로 연결할 수 있다(U13 ②)
        notice = nil
    }

    /// '이 휴대폰의 기록 지우기'(7.4): 캐시·녹음 파일·기록을 지우고 토큰은 남긴다. 상태 요청 표시를 켠다.
    func clearLocalRecords() {
        let ok = update { s in
            s.reports = []; s.alerts = []; s.songs = []; s.selectedSongId = nil
            s.feeding = FeedingState(); s.feedingLog = []; s.shown = [:]
            s.outbox.removeAll { $0.songId != nil }   // 녹음 파일이 필요한 노래 올리기만 뺀다. 삭제·선택 요청은 파일이 필요 없어 남긴다
            // 지울 기록의 안 보낸 요약은 보내지 않는다. 다 지운 뒤 빈 요약을 새로 넣는다. 보내 본 요약은 결과를 확정할 때까지 둔다(U57, 빈 요약이 뒤에 가서 이긴다)
            s.outbox.removeAll { $0.type == MessageType.careSummary && !tried($0) }
            s.lastSentCareSummary = nil
            s.needsStateRequest = true
            s.clearingCareIds = care.events.map(\.id)   // 아래 돌봄 기록 지우기 전에 죽거나 저장에 실패해도 다음 저장·시작에 이어서 지운다
        }
        guard ok else { return }
        pendingOpenAlertId = nil; openAlertId = nil   // 지운 알림을 가리키지 않게(wipeLocal과 같다)
        clearCount += 1
        SongFiles.removeAll()   // 파일은 저장이 성공한 뒤에 지운다
        SongFiles.removeBrokenCopies()
        RecorderModel.removeTempRecordings()   // 앱이 죽어 남은 녹음 원본도(7.4 녹음 파일)
        finishCareClear()
        bannerAlertId = nil
        let epoch = foregroundEpoch
        spawn { [self] in await requestStateAfterDevices(epoch: epoch) }
    }

    /// R10·U18: 돌봄 기록도 지운다. 설정은 남기고, 지운 수유를 다시 옮겨 오지 않게 옮기기 표시를 켠다.
    /// 지울 id가 남아 있으면(지우는 사이 죽었거나 care.json 저장에 실패) 시작·앞으로 올 때 다시 한다. 다음 돌봄 저장도 먼저 뺀다(`updateCare`).
    /// 남아 있는 동안에는 옛 기록으로 요약을 보내지 않는다.
    private func finishCareClear() {
        guard state.clearingCareIds != nil else { return }
        guard updateCare({ _ in }) else { return }   // updateCare가 지울 기록을 빼고 저장한다
        if state.clearingCareIds != nil { endCareClear() }   // 이미 빠져 있어 저장할 것이 없었다
        reconcileCareSummary()
    }

    /// 지운 기록의 사본(돌봄·내보내기·상태·노래 복구 사본)을 먼저 지우고, 마지막에 표시를 지운다. 그사이 죽어도 다음에 다시 한다.
    private func endCareClear() {
        FileCareStore.removeBrokenCopies(); CareExport.removeCopies(); SongFiles.removeBrokenCopies()
        update { $0.clearingCareIds = nil }
    }

    // MARK: 앞에 있을 때 연결

    func didBecomeActive() {
        appActive = true
        finishCareClear()
        purgeCare()
        guard isPaired else { return }
        purgeExpired()
        hurryNetworkWaits()      // 앞으로 올 때 망 실패 기다림 없이 보낸다(7.5)
        enqueueLatestFeeding()   // 앞으로 올 때(R6). 아래 갱신이 보낸다
        enqueueCareSummary()     // 같은 계기(R19). 저장 없이 날이 바뀌어 '오늘' 숫자가 달라진 것도 여기서 잡는다
        // 새 갱신을 실제로 시작할 때만 버퍼링을 켠다. 24시간 판단은 소켓·받은 함보다 먼저(7.4).
        // 재요청 표시를 저장하지 못했으면 소켓도 열지 않는다(받은 것을 적용하지 못하고 쌓이기만 한다). 5초 뒤 갱신이 연다.
        let began = beginRefresh()
        if began || refreshing { startSocket() }
        let epoch = foregroundEpoch   // 스폰한 갱신이 돌기 전에 뒤로 가도 멈추게 지금 잡는다
        if began { spawn { [self] in await performRefresh(epoch: epoch) } }
        else if !refreshing { kick() }   // 갱신을 못 열었다(받은 함 429 기다림 등): 보낼 함은 따로 보낸다(7.5 '앱이 앞으로 올 때')
    }

    func didEnterBackground() {
        appActive = false
        stopSocket()
        retryTask?.cancel()   // 뒤에서는 보낼 함·받은 함을 다시 시도하지 않는다. 남은 것은 앞으로 올 때 한다(2장 '뒤로 가면 멈춘다')
        retryTask = nil
        staleRetryTask?.cancel()
        staleRetryTask = nil
        foregroundEpoch += 1
        reconnectedDuringRefresh = false   // 소켓도 닫았다. 앞으로 오면 처음부터 받는다
        bufferedLive = []                  // ack 전이라 중계에 남는다. 뒤로 갈 때마다 쌓이지 않게
        pendingOpenAlertId = nil           // 본문을 못 받은 채 떠났으면 며칠 뒤 다른 화면 위에 상세가 저절로 뜨지 않게. 알림은 목록에 남는다
    }

    /// 마지막으로 차의 메시지를 받은 지 24시간이 넘었으면 '상태 요청 필요'를 켠다. 표시는 201로만 끈다.
    /// 표시가 필요 없거나 저장에 성공하면 true. 저장에 실패하면 false(호출한 쪽은 메시지 적용을 미룬다).
    private func markStaleIfNeeded() -> Bool {
        if pendingRecovery, let c = credentials {
            guard update({ $0.carId = c.carId; $0.needsStateRequest = true; $0.lastReceivedAt = $0.lastReceivedAt ?? now() }) else { return false }
            pendingRecovery = false
        }
        guard let last = state.lastReceivedAt, now().timeIntervalSince(last) > 24 * 3600, !state.needsStateRequest else { return true }
        return update { $0.needsStateRequest = true }
    }

    /// 필요 표시를 저장하지 못한 채 받은 메시지를 적용하면 `lastReceivedAt`이 갱신돼 재요청 조건이 사라진다.
    /// 그래서 저장이 성공할 때까지 갱신을 시작하지 않고 5초 뒤 다시 시도한다.
    /// 받은 함을 다 받지 못했을 때(조회 실패)도 이 길로 다시 한다. 5·10·20·40·60초로 늘리고 받은 함을 다 받으면 처음으로 돌린다.
    private func scheduleStaleRetry(atLeast minimum: TimeInterval? = nil) {
        guard !store.writesRefused, appActive else { return }   // 저장을 영구 거부하는 실행에서 헛돌지 않는다. 뒤에서는 하지 않는다
        if refreshing {   // 진행 중인 갱신이 끝나면 예약한다(그 전에 시간이 지나면 beginRefresh가 거절한다). Retry-After도 넘긴다
            retryAfterRefresh = true
            if let m = minimum { retryAfterRefreshAtLeast = max(retryAfterRefreshAtLeast ?? 0, m) }
            return
        }
        staleRetryTask?.cancel()
        let delay = max(UInt64(min(60, 5 << min(refreshRetries, 4))), UInt64((minimum ?? 0).rounded(.up)))   // 429면 Retry-After 뒤(1.1)
        refreshRetries += 1
        scheduledRefreshDelay = delay
        staleRetryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
            guard !Task.isCancelled, let self else { return }
            // 타이머가 울릴 때 다른 갱신이 진행 중이면 refresh()가 거절된다. 그 갱신이 끝난 뒤로 넘긴다.
            if self.refreshing { self.retryAfterRefresh = true } else { await self.refresh() }
        }
    }

    /// 소켓이 붙을 때 상태 요청 표시가 남아 있으면 다시 판단한다(앱을 열 때 조회가 실패했던 경우). 표시가 없으면 목록을 또 받지 않는다.
    func socketDidConnect(_ up: Bool) {
        socketConnected = up
        guard up else { return }
        linkSeen += 1; offline = false   // 중계에 다시 닿았다. 앞에 있는 동안 돌아와도 띠를 끈다
        // 다시 붙을 때마다 중계가 밀린 것을 소켓으로 먼저 보낸다. 받은 함으로 먼저 처리하고 실시간으로 넘어간다
        // (밀린 리포트·인계 알림을 실시간으로 다뤄 꺼진 차를 '연결됨'으로 보이거나 배너를 띄우지 않게. 안드로이드 OPENED와 같다).
        let epoch = foregroundEpoch
        if beginRefresh() { spawn { [self] in await performRefresh(inboxOnly: true, epoch: epoch) } }
        else if refreshing { backlogDone = false; reconnectedDuringRefresh = true }   // 도는 갱신의 받은 함은 이 접속 전 것이다
        if role == .owner, state.needsStateRequest { spawn { [self] in await requestStateAfterDevices(epoch: epoch) } }
    }

    private func startSocket() {
        guard socket == nil, let baseURL else { return }
        let gen = generation
        socketEpoch += 1
        let mine = socketEpoch
        let s = RelaySocket(baseURL: baseURL, token: { [weak self] in self?.credStore.load()?.token }) { [weak self] event in
            // 이전 연결이나 이미 닫은 소켓의 늦은 이벤트는 버린다(닫은 뒤 모으기를 다시 채우지 않게). 401·4001은 연결 세대만 본다
            guard let self, gen == self.generation else { return }
            if case .unauthorized = event {} else { guard mine == self.socketEpoch else { return } }
            self.socketEvent(event)
        }
        socket = s
        s.start()
        // 앞에 있고 소켓이 붙어 있는 동안 60초마다 기기 목록만 다시 받아 차 타일을 맞춘다(설계값). 소켓과 같이 끝난다.
        if role == .owner {
            devicePollTask?.cancel()
            devicePollTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: Self.devicePollInterval)
                    guard !Task.isCancelled, let self, gen == self.generation else { return }
                    if self.socketConnected { await self.pollDevices() }
                }
            }
        }
    }

    private static let devicePollInterval: UInt64 = 60_000_000_000

    /// 60초 주기 목록(7.2). 상태 요청 표시가 남아 있으면(기록 지우기 직후 목록 조회가 실패했던 경우) 이번 목록 성공 뒤 요청한다(7.4).
    func pollDevices() async { await requestStateAfterDevices(epoch: foregroundEpoch) }

    /// HTTP 결과를 띠에 반영하는 한 곳(9장). 응답을 받았으면(성공·4xx) 중계에 닿았고, 닿지 않거나 5xx면 불통이다(안드로이드 `<500`은 닿음).
    /// 응답이 아닌 실패(형식 오류 등)는 띠를 그대로 둔다.
    private func observe(_ e: RelayError?, since mark: LinkMark) {
        guard let e else { setOffline(false, since: mark); return }
        if e.isRelayDown { setOffline(true, since: mark) } else if e.status != nil { setOffline(false, since: mark) }
    }

    /// HTTP 요청의 결과로 띠를 바꾼다. 요청을 시작한 뒤(`mark`) 소켓·인터넷 경로가 새로 본 것이 있으면 그쪽이 더 새 관찰이다.
    private func setOffline(_ value: Bool, since mark: LinkMark) {
        guard mark.link == linkSeen, mark.ticket > appliedTicket else { return }
        offline = value
        appliedTicket = mark.ticket
    }

    /// HTTP 요청을 시작할 때 잡는다: 그때의 소켓·경로 관찰 번호와 요청 순번.
    private struct LinkMark { let link: Int; let ticket: Int }
    private func httpMark() -> LinkMark {
        httpTicket += 1
        return LinkMark(link: linkSeen, ticket: httpTicket)
    }

    /// 인터넷 경로가 바뀌었다(NWPathMonitor). 경로가 없으면 띠를 켠다. 경로가 생겼다고 중계에 닿는 것은 아니라
    /// 띠는 그대로 두고 다시 확인한다: 끄는 것은 소켓이 붙거나 HTTP가 성공할 때다(계약 9장, 안드로이드 onAvailable과 같다).
    @ObservationIgnored private var lastPathSatisfied: Bool?

    func networkPathChanged(satisfied: Bool) async {
        // 같은 상태의 경로 갱신(인터페이스·비용 변화 등)은 무시한다. 끊김↔연결이 바뀔 때만 움직인다(중계가 내려간 동안 백오프를 건너뛰며 되풀이하지 않게)
        guard lastPathSatisfied != satisfied else { return }
        lastPathSatisfied = satisfied
        linkSeen += 1   // 이 관찰보다 먼저 시작한 HTTP의 늦은 결과가 덮지 않게
        if !satisfied { offline = true; return }
        guard isPaired else { offline = false; return }   // 연결 안 됨: 확인할 중계가 없다
        hurryNetworkWaits()   // 인터넷이 돌아왔다: 망 실패 기다림 없이 바로 보낸다(7.5)
        guard appActive else { return }   // 뒤에서는 갱신하지 않는다(앞으로 오면 한다)
        wakeSocket()          // 소켓도 백오프를 기다리지 않고 다시 붙는다
        if offline { await refresh() } else { kick() }
    }

    func socketEvent(_ event: RelaySocket.Event) {
        switch event {
        case .connected(let up): socketDidConnect(up)
        case .unreachable: linkSeen += 1; offline = true   // 앞에 있는 동안 중계가 끊겨도 띠를 켠다(계약 9장 '중계 불통')
        case .alive: linkSeen += 1; offline = false        // 소켓 pong: 중계에 닿고 있다(보호자 폰은 HTTP가 적어 이것이 띠를 끈다)
        case .unauthorized: handleUnauthorized()
        case .envelope(let env):
            if backlogDone {
                let epoch = liveEpoch, fe = foregroundEpoch
                spawn { [self] in
                    guard epoch == liveEpoch else { return }   // 앞 실시간 메시지 저장이 실패했다: 이것은 받은 함에서 순서대로 다시 받는다
                    guard fe == foregroundEpoch else { return }   // 그사이 뒤로 갔다(중계에 남아 앞으로 오면 받는다)
                    if !(await ingest([env], live: true)) { liveFailed() }
                }
            } else { bufferedLive.append(env) }
        }
    }

    /// 실시간 메시지를 저장하지 못했다. 뒤 메시지를 먼저 적용하지 않게 실시간 처리를 멈추고 소켓을 닫고 받은 함부터 다시 받는다(8.3 4번).
    private func liveFailed() {
        liveEpoch += 1
        backlogDone = false; bufferedLive = []
        stopSocket()
        scheduleStaleRetry()
    }

    private func stopSocket() {
        socketEpoch += 1
        devicePollTask?.cancel()
        devicePollTask = nil
        socket?.stop()
        socket = nil
        socketConnected = false
    }

    private func spawn(_ work: @escaping @MainActor () async -> Void) {
        let id = UUID()
        pending[id] = Task { await work(); self.pending[id] = nil }
    }

    /// 띄워 둔 작업이 모두 끝날 때까지 기다린다(시험용).
    func settle() async {
        while let t = pending.values.first { await t.value }
    }

    func noteServerDate(_ date: Date) { clockSkew = date.timeIntervalSince(now()) }

    /// 받은 함 → 기기 목록 → 상태 요청 → 보낼 함 순서. 앱을 열 때와 네트워크가 돌아올 때 부른다.
    /// 사용자가 다시 해 달라고 했다(띠의 '다시 시도', 당겨서 새로고침): 망 실패의 기다림을 당기고 갱신한다(7.5, 사용자 결정 11-07).
    func retryNow() async {
        hurryNetworkWaits()
        wakeSocket()
        await refresh()
    }

    /// 붙지 못하고 백오프로 기다리는 소켓을 깨운다(인터넷 복구·'다시 시도', 안드로이드 onAvailable의 kick과 같다). 붙어 있으면 그대로 둔다
    private func wakeSocket() {
        // 닿지 않음으로 띠가 켜졌을 때만(백오프로 기다리는 중). 앱을 열 때 경로 알림이 붙는 중인 소켓을 버리지 않게
        guard appActive, isPaired, offline, socket != nil, !socketConnected else { return }
        stopSocket()
        startSocket()
    }

    /// 망 실패(응답 없음)로 생긴 보낼 함 기다림을 지금으로 당긴다(계약 7.5 '네트워크가 돌아올 때, 앱이 앞으로 올 때').
    /// 중계가 준 기다림(429 Retry-After·song_quota)과 5xx 백오프는 둔다. 메시지 429 기기 기다림은 보내기가 따로 지킨다.
    private func hurryNetworkWaits() {
        let t = now()
        guard state.outbox.contains(where: { $0.networkWait && $0.nextTryAt > t }) else { return }
        update { s in for i in s.outbox.indices where s.outbox[i].networkWait && s.outbox[i].nextTryAt > t { s.outbox[i].nextTryAt = t } }
    }

    func refresh() async {
        guard beginRefresh() else {
            if !refreshing { await flushOutbox() }   // 갱신을 못 열었다(받은 함 429 기다림 등): 보낼 함은 따로 보낸다. 도는 갱신은 끝에 보낸다
            return
        }
        if appActive { startSocket() }   // 재요청 표시 저장에 실패해 닫았던 소켓을 다시 연다(이미 열려 있으면 그대로)
        await performRefresh()
    }

    /// 이미 갱신 중이면 false(진행 중인 갱신의 버퍼링 상태를 건드리지 않는다). 시작하면 24시간 판단을 하고 버퍼링을 켠다.
    /// 재요청 표시를 저장하지 못하면 소켓을 닫는다: 받은 것을 적용할 수 없어 모으기만 하면 메모리에 쌓인다.
    /// ack하지 않은 것은 중계에 남아 저장이 될 때 다시 받는다(8.3, 안드로이드는 소켓 루프를 끝낸다).
    private func beginRefresh() -> Bool {
        guard credentials != nil, !refreshing else { return false }
        if let hold = inboxHoldUntil, hold > now() {   // 받은 함 429 기다림 중: 소켓도 열지 않고 기한 뒤에 다시 한다
            scheduleStaleRetry(atLeast: hold.timeIntervalSince(now()))
            return false
        }
        guard markStaleIfNeeded() else {
            stopSocket(); bufferedLive = []
            scheduleStaleRetry()
            return false
        }
        backlogDone = false      // 처음 받은 함을 처리하는 동안 온 소켓 프레임은 모아 둔다
        refreshing = true
        return true
    }

    /// `inboxOnly`: 소켓이 다시 붙었을 때. 받은 함·모아 둔 프레임·보낼 함만 본다(기기 목록·상태 요청·푸시 토큰은 앱을 열 때의 갱신이 한다).
    private func performRefresh(inboxOnly: Bool = false, epoch: Int? = nil) async {
        guard let c = credentials else { return }
        let gen = generation, epoch = epoch ?? foregroundEpoch
        let current = { gen == self.generation && epoch == self.foregroundEpoch }   // 연결이 바뀌거나 뒤로 갔으면 멈춘다
        defer {
            if gen == generation {
                refreshing = false
                // 뒤로 갔다가 이 갱신이 끝나기 전에 돌아왔다: 그때 앞으로 오기의 갱신은 '갱신 중'이라 거절됐다. 지금 다시 한다
                if epoch != foregroundEpoch, appActive {
                    let e = foregroundEpoch
                    if beginRefresh() { spawn { [self] in await performRefresh(epoch: e) } }
                }
                if reconnectedDuringRefresh {
                    reconnectedDuringRefresh = false
                    let e = foregroundEpoch   // 스폰한 뒤 돌기 전에 뒤로 가면 멈추게 지금 잡는다
                    if beginRefresh() { spawn { [self] in await performRefresh(inboxOnly: true, epoch: e) } }
                }
                if retryAfterRefresh {
                    retryAfterRefresh = false
                    let m = retryAfterRefreshAtLeast; retryAfterRefreshAtLeast = nil
                    scheduleStaleRetry(atLeast: m)
                }
            }
        }
        guard current() else { return }   // 스폰한 뒤 돌기 전에 뒤로 갔다. 위 defer가 '갱신 중'을 풀어 다음 앞으로 올 때 다시 한다

        var inboxError: RelayError?
        var saved = true
        var mark = httpMark()
        do {
            var after: Int64?
            var more = true
            while more {
                mark = httpMark()   // 요청마다 잡는다: 앞 쪽을 처리하는 사이의 관찰보다 뒤에 시작한 요청의 결과는 새것이다
                let page = try await relay.inbox(afterSeq: after)
                guard current() else { return }
                saved = await ingest(page.messages, live: false)
                guard current() else { return }
                if !saved { break }   // 실패한 메시지보다 뒤의 쪽을 먼저 적용하지 않는다
                after = page.messages.last?.seq
                more = page.more && after != nil
            }
        } catch let e as RelayError {
            inboxError = e
        } catch {}
        guard gen == generation else { return }
        if inboxError?.status == 429 { inboxHoldUntil = now().addingTimeInterval(inboxError?.retryAfter ?? 60) }   // 뒤로 갔어도 기한은 남긴다
        if inboxError?.isUnauthorized == true { handleUnauthorized(); return }   // 뒤로 갔어도 같은 연결의 401은 처리한다(7.1)
        guard current() else { return }
        observe(inboxError, since: mark)
        guard inboxError == nil, saved else {
            // 밀린 것을 다 받지 못했다. 모아 둔 프레임을 실시간으로 처리하면 지난 것이 실시간으로 보이고(배너·차 타일)
            // 저장하지 못한 것보다 뒤의 것을 먼저 적용한다. ack하지 않은 것은 중계에 남으니 버리고, 소켓을 닫고
            // 받은 함부터 다시 한다(8.3, 안드로이드는 syncInbox가 실패하면 소켓 루프를 끝낸다). 보내기는 따로 한다.
            bufferedLive = []; reconnectedDuringRefresh = false
            stopSocket()
            scheduleStaleRetry(atLeast: inboxError?.status == 429 ? inboxError?.retryAfter ?? 60 : nil)
            await flushOutbox()
            return
        }
        refreshRetries = 0
        // 밀린 것을 다 처리했다. 그동안 모아 둔 소켓 프레임은 이제 실시간으로 처리한다.
        // 그사이 소켓이 다시 붙었으면 모은 것은 그 접속의 밀린 것일 수 있다: 끝난 뒤 받은 함을 다시 보고 넘긴다(defer).
        if !reconnectedDuringRefresh {
            backlogDone = true
            let buffered = bufferedLive
            bufferedLive = []
            if !buffered.isEmpty, !(await ingest(buffered, live: true)) { liveFailed() }
        }
        guard current() else { return }
        if inboxOnly { await flushOutbox(); return }
        if c.role == .owner { await requestStateAfterDevices(epoch: epoch) }
        guard current() else { return }
        await syncPushToken()
        guard current() else { return }
        await flushOutbox()
    }

    /// 띠 판단에 들어가는 HTTP: 시작할 때 표시를 잡고, 성공이면 끄고 중계 장애면 켠다(9장). 연결이 바뀐 뒤의 결과는 띠를 바꾸지 않는다.
    private func observed<T>(_ op: () async throws -> T) async throws -> T {
        let mark = httpMark(), gen = generation
        do {
            let v = try await op()
            if gen == generation { observe(nil, since: mark) }
            return v
        } catch let e as RelayError {
            if gen == generation { observe(e, since: mark) }
            throw e
        }
    }

    /// 기기 목록 조회가 성공한 뒤에 상태를 요청한다(계약 7.4). 갱신·기록 지우기·다시 붙기·60초 주기가 함께 쓰는 길이다.
    /// `epoch`는 일을 띄운 때의 화면 세대다. 띄운 뒤 돌기 전이나 목록을 기다리는 사이 뒤로 갔으면 시작하지 않는다(2장 '뒤로 가면 멈춘다').
    private func requestStateAfterDevices(epoch: Int) async {
        guard epoch == foregroundEpoch, !Task.isCancelled else { return }
        if await refreshDevices(), epoch == foregroundEpoch, !Task.isCancelled { await sendStateRequestIfNeeded() }
    }

    @discardableResult
    func refreshDevices() async -> Bool {
        guard role == .owner else { return false }
        let gen = generation
        let mark = httpMark(), carMark = carLiveSeen
        do {
            let r = try await relay.devices()
            guard gen == generation else { return false }
            observe(nil, since: mark)   // 이 HTTP도 중계에 닿았다(띠를 끄는 HTTP 성공, 안드로이드 fetchDevices와 같다)
            // 목록을 기다리는 사이 차의 실시간 봉투를 받았으면 차 행의 연결 상태는 그쪽이 새것이다(7.2: 그 자리에서 바꾼다).
            let keepCarLink = carLiveSeen != carMark
            // 더 큰 snapshot_seq는 늦게 끝났어도 새것이다. 같으면 나중에 시작한 목록만(차 online은 seq 없이 바뀐다). 작으면 버린다.
            // 적용 판단은 여기 한 곳이다. 버리거나 저장하지 못한 목록은 순번을 올리지 않는다(같은 seq의 나중 목록을 막지 않게)
            let s0 = state.devicesSnapshotSeq
            guard r.snapshotSeq > s0 || (r.snapshotSeq == s0 && mark.ticket > appliedDevicesTicket) else { return true }
            // 목록과 snapshot_seq는 한 번에 저장한다.
            let saved = update { s in
                var merged = Self.mergeDevices(local: s.devices, snapshot: r, marks: s.deviceMarks)
                if keepCarLink, let seen = lastCarLiveAt {
                    // 캐시에 차 행이 없던 첫 목록에도 적용한다(그때는 바꿀 행이 없어 실시간 관찰이 남지 않았다)
                    for i in merged.indices where merged[i].role == .car { merged[i].online = true; merged[i].lastSeenAt = Rfc3339.string(seen) }
                }
                s.devices = merged
                s.devicesSnapshotSeq = r.snapshotSeq
            }
            if saved { appliedDevicesTicket = max(appliedDevicesTicket, mark.ticket) }
            return true
        } catch let e as RelayError {
            if gen == generation, e.isUnauthorized { handleUnauthorized() }
            if gen == generation { observe(e, since: mark) }
        } catch {}
        return false
    }

    /// 기기 목록을 합친다. 목록을 읽은 뒤(`snapshot_seq`보다 새로) 소켓으로 받은 연결 변경은 목록이 덮지 않는다(계약 3.3·8.4).
    static func mergeDevices(local: [DeviceInfo], snapshot r: DevicesResponse, marks: [String: DeviceMark]) -> [DeviceInfo] {
        var result: [DeviceInfo] = []
        for d in r.devices {
            if let m = marks[d.deviceId], m.seq > r.snapshotSeq {
                if m.removed { continue }                                    // 스냅숏 뒤에 끊겼다
                if let mine = local.first(where: { $0.deviceId == d.deviceId }) { result.append(mine); continue }
            }
            result.append(d)
        }
        for l in local where !result.contains(where: { $0.deviceId == l.deviceId }) {
            if let m = marks[l.deviceId], m.seq > r.snapshotSeq, !m.removed { result.append(l) }   // 스냅숏 뒤에 붙었다
        }
        return result
    }

    /// FCM 토큰이 생기거나 바뀌었다(5.4 MessagingDelegate). 연결돼 있으면 바로 중계에 알린다(앱을 열 때마다도 다시 보낸다).
    func pushTokenDidChange() {
        guard isPaired else { return }
        spawn { [self] in await syncPushToken() }
    }

    private func syncPushToken() async {
        guard let t = pushToken?.token else { return }
        let gen = generation
        do {
            _ = try await observed { try await relay.patchMe(PatchDeviceRequest(pushToken: .set(t))) }
            // 보내는 사이 토큰이 바뀌었으면(갱신 콜백의 PATCH와 겹침) 중계에 늦게 닿은 옛 값이 남지 않게 지금 값을 한 번 더 보낸다
            if gen == generation, pushToken?.token != t { await syncPushToken() }
        }
        catch let e as RelayError { if gen == generation, e.isUnauthorized { handleUnauthorized() } }   // 실패는 다음 앱 열기 때 다시(5.4)
        catch {}
    }

    /// 상태 요청(G39). owner만, 10분 안에 다시 보내지 않는다. 오프라인이면 보낼 함에 넣지 않고 표시만 둔다.
    /// 동시에 불러도 한 번만 보내도록 첫 await 전에 '요청 중' 표시를 세운다.
    func sendStateRequestIfNeeded() async {
        guard role == .owner, state.needsStateRequest, !requestingState else { return }
        if let hold = outboxHoldUntil, hold > now() { return }   // 메시지 429 기다림 중(표시는 남아 다음 계기에 보낸다)
        if let last = state.lastStateRequestAt, now().timeIntervalSince(last) < 600 { return }
        // 보내기 전에 시각을 먼저 저장한다(안드로이드 requestState와 같다). 응답이 유실돼도 10분 제한이 남는다.
        guard update({ $0.lastStateRequestAt = now() }) else { return }
        requestingState = true
        let gen = generation
        let clears = clearCount
        defer { requestingState = false }
        let env = Envelope(id: ULID.generate(date: now()), type: MessageType.stateRequest,
                           ts: Rfc3339.string(now()), body: .object([:]))
        do {
            _ = try await observed { try await relay.send(env) }
            guard gen == generation else { return }
            // 기다리는 사이 기록을 지웠다면 그 뒤에 필요해진 요청 표시는 남긴다(10분이 지나면 다시 요청한다).
            let cleared = clears != clearCount
            update { $0.needsStateRequest = cleared }
        } catch let e as RelayError {
            if gen == generation, e.isUnauthorized { handleUnauthorized() }
            // 같은 메시지 한도다(1.4): 뒤따르는 보낼 함도 기다린다. 표시는 남아 다음 계기에 다시 요청한다
            if gen == generation, e.status == 429 { outboxHoldUntil = max(outboxHoldUntil ?? .distantPast, now().addingTimeInterval(e.retryAfter ?? 60)) }
        } catch {}
    }

    // MARK: 받기

    /// 받은 메시지를 `seq` 오름차순으로 하나씩 처리한다. 본문과 '처리한 id'를 한 번에 저장하고, 저장이 성공한 것만 ack한다.
    /// 저장하지 못하면 그 메시지부터 ack하지 않는다(중계가 다시 준다).
    /// `live`는 처음 받은 함을 처리한 뒤 WebSocket으로 받은 것이다. 받은 함에서 꺼낸 것은 배너 없이 목록에만 넣는다.
    /// 모두 저장했거나 저장할 것이 없으면 true, 저장에 실패해 멈췄으면 false(호출한 쪽이 다시 받게 한다).
    @discardableResult
    func ingest(_ envelopes: [Envelope], live: Bool) async -> Bool {
        guard let creds = credentials else { return true }
        defer { resolvePendingOpen() }   // 저장을 마친 뒤 열려던 알림이 있으면 연다
        guard markStaleIfNeeded() else { return false }   // 재요청 표시를 저장하기 전에는 적용도 ack도 하지 않는다(중계가 다시 준다)
        let gen = generation
        var ackIds: [String] = []
        var allSaved = true
        for env in envelopes.sorted(by: { ($0.seq ?? 0) < ($1.seq ?? 0) }) {
            // 다른 차(이전 연결)의 메시지는 적용도 ack도 하지 않는다. seq는 같은 차 안에서만 비교한다(3.1).
            guard env.car == creds.carId else { continue }
            if state.processed[env.id] != nil || env.v != 1 {
                ackIds.append(env.id)   // 이미 처리한 id, 모르는 판은 ack만 해서 받은 함이 막히지 않게 한다
                continue
            }
            var effects = Effects()
            let saved = update { s in
                let before = s.outbox.count
                effects = applyMessage(env, to: &s, live: live, myDevice: creds.deviceId)
                effects.outboxAdded = s.outbox.count > before
                s.processed[env.id] = now()
                if env.from?.role == .car { s.lastReceivedAt = now(); if live { Self.markCarSeen(&s, at: now()) } }   // 받은 함에서 꺼낸 지난 봉투는 차 타일을 바꾸지 않는다
            }
            guard saved else { allSaved = false; break }
            if live, env.from?.role == .car { carLiveSeen += 1; lastCarLiveAt = now() }   // 늦게 온 기기 목록이 이 관찰을 덮지 않게
            ackIds.append(env.id)
            if let id = effects.banner { bannerAlertId = id }
            effects.removeFiles.forEach(SongFiles.remove)
            if effects.outboxAdded { kick() }   // 거절된 곡의 자동 삭제 같은, 받은 결과로 생긴 전송을 바로 시작한다
        }
        // 실시간 메시지 저장이 실패했다: 앞 것의 ack를 기다리는 사이 새 실시간 메시지가 먼저 적용되지 않게 지금 막는다(8.3 4번)
        if !allSaved, live { liveEpoch += 1; backlogDone = false; bufferedLive = [] }
        guard !ackIds.isEmpty else { return allSaved }
        do { try await observed { try await relay.ack(ids: ackIds) } }
        catch let e as RelayError { if gen == generation, e.isUnauthorized { handleUnauthorized() } }   // 못 보내도 다시 받으면 중복으로 ack만 한다
        catch {}
        return allSaved || gen != generation   // ack를 기다리는 동안 연결 세대가 바뀌었으면 이 세대가 다시 받을 것이 없다
    }

    /// 차가 보낸 봉투를 받았으면 차는 지금 켜져 있다. 기기 목록(60초 간격)을 기다리지 않고 차 타일을 바로 맞춘다.
    static func markCarSeen(_ s: inout PhoneState, at t: Date) {
        for i in s.devices.indices where s.devices[i].role == .car {
            s.devices[i].online = true
            s.devices[i].lastSeenAt = Rfc3339.string(t)
        }
    }

    private struct Effects {
        var banner: String?
        var outboxAdded = false
        var removeFiles: [String] = []
    }

    /// 인계 알림 배너는 방금 생긴 알림에만 띄운다(푸시 유효 시간 30분, G28).
    private static let bannerWindow: TimeInterval = 30 * 60

    private func applyMessage(_ env: Envelope, to s: inout PhoneState, live: Bool, myDevice: String) -> Effects {
        var fx = Effects()
        let seq = env.seq ?? 0
        switch env.type {
        case MessageType.paired:
            guard let b = try? env.decodeBody(PairedBody.self) else { break }
            // 그 기기의 마지막 적용 seq보다 큰 것만 적용한다. 끊김 표시가 더 새로우면 늦게 온 paired는 무시된다.
            guard seq > max(s.deviceMarks[b.deviceId]?.seq ?? 0, s.devicesSnapshotSeq) else { break }   // 스냅숏 이하 seq는 이미 목록에 반영돼 있다
            s.deviceMarks[b.deviceId] = DeviceMark(seq: seq, removed: false, updatedAt: now())
            let info = DeviceInfo(deviceId: b.deviceId, role: Role(rawValue: b.role.rawValue) ?? .owner,
                                  platform: Platform(rawValue: b.platform.rawValue) ?? .android,
                                  pairedAt: b.pairedAt, lastSeenAt: nil, online: false, handoffAlerts: b.handoffAlerts)
            if let i = s.devices.firstIndex(where: { $0.deviceId == b.deviceId }) {
                var merged = info; merged.lastSeenAt = s.devices[i].lastSeenAt; merged.online = s.devices[i].online
                s.devices[i] = merged
            } else { s.devices.append(info) }
            if b.deviceId == myDevice { s.handoffAlerts = b.handoffAlerts }
        case MessageType.unpaired:
            guard let b = try? env.decodeBody(UnpairedBody.self) else { break }
            guard seq > max(s.deviceMarks[b.deviceId]?.seq ?? 0, s.devicesSnapshotSeq) else { break }
            s.deviceMarks[b.deviceId] = DeviceMark(seq: seq, removed: true, updatedAt: now())
            s.devices.removeAll { $0.deviceId == b.deviceId }
        case MessageType.feedingState:
            // 필수 필드가 빠진 body는 decode가 실패해 무시된다(수유 값을 지우지 않는다). 큰 seq가 이긴다(G19).
            guard let b = try? env.decodeBody(FeedingStateBody.self), seq > s.feeding.seq else { break }
            let last = b.lastFeedingStart.flatMap(Rfc3339.parse)
            s.feeding = FeedingState(lastStart: last, windowStartExpected: b.windowStartExpected.flatMap(Rfc3339.parse), seq: seq)
            if let last {
                for i in s.feedingLog.indices where s.feedingLog[i].status != .reflected {
                    let sent = s.feedingLog[i].fixedAt ?? s.feedingLog[i].at
                    if abs(sent.timeIntervalSince(last)) < 1 { s.feedingLog[i].status = .reflected }   // "차에 반영됨"
                }
            }
        case MessageType.songStatus:
            if let b = try? env.decodeBody(SongStatusBody.self) { fx.removeFiles += applySongStatus(b, seq: seq, to: &s) }
        case MessageType.reportSummary:
            guard let b = try? env.decodeBody(ReportSummaryBody.self) else { break }
            if let i = s.reports.firstIndex(where: { $0.body.sessionId == b.sessionId }) {
                guard seq > s.reports[i].seq else { break }   // session_id마다 큰 seq가 이긴다
                s.reports.remove(at: i)
            }
            s.reports.append(StoredReport(messageId: env.id, seq: seq, receivedAt: now(), body: b))
            s.reports = Rfc3339.sortedDescending(s.reports) { $0.body.startedAt }
        case MessageType.handoffAlert:
            guard let b = try? env.decodeBody(HandoffAlertBody.self) else { break }
            // 본문은 늘 저장한다. 배너는 한 번만, 그리고 방금 생긴 알림에만 띄운다(5.6).
            if !s.alerts.contains(where: { $0.id == env.id }) {
                s.alerts.append(StoredAlert(id: env.id, seq: seq, receivedAt: now(), body: b))
                s.alerts = Rfc3339.sortedDescending(s.alerts) { $0.body.occurredAt }
            }
            if s.shown[env.id] == nil {
                s.shown[env.id] = now()
                let age = Rfc3339.parse(b.occurredAt).map { now().timeIntervalSince($0) } ?? 0
                if live, age <= Self.bannerWindow { fx.banner = env.id }
            }
        default:
            break   // 모르는 type, feeding_started 같은 차 쪽 메시지는 무시
        }
        return fx
    }

    /// 인계 알림을 앱 안에 한 번만 띄우는 공통 함수(5.6). 푸시(`willPresent`)가 부른다.
    /// 배너를 눌렀다(5.6): 본문이 있으면 상세를 열고, 없으면 받은 뒤 연다. 이 연결에 없는 알림(이전 차의 늦은 푸시)이면 열지 않는다(빈 상세에 머물지 않게).
    func openBanner(_ id: String) async {
        bannerAlertId = nil
        if alert(id) != nil { userOpenedAlert(id); openAlertId = id } else { await openAlert(id) }
    }

    func show(_ messageId: String) {
        guard isPaired, state.shown[messageId] == nil else { return }   // 끊은 뒤 늦게 온 이전 연결의 푸시는 띄우지 않는다
        if update({ $0.shown[messageId] = now() }) { bannerAlertId = messageId }
    }

    /// 지워야 할 파일의 곡 id를 돌려준다.
    private func applySongStatus(_ b: SongStatusBody, seq: Int64, to s: inout PhoneState) -> [String] {
        var files: [String] = []
        if let i = s.songs.firstIndex(where: { $0.id == b.songId }) {
            if s.songs[i].state == .removed && b.status != .removed { return [] }   // 지운 곡은 되살리지 않는다
            guard seq > s.songs[i].lastSeq else { return [] }
            s.songs[i].lastSeq = seq
            s.songs[i].candidate = b.candidate
            if let n = b.name, s.songs[i].name.isEmpty { s.songs[i].name = n }
            switch b.status {
            case .accepted:
                s.songs[i].state = .accepted
                s.songs[i].durationS = b.durationS ?? s.songs[i].durationS
            case .rejected:
                s.songs[i].state = .rejected(b.reason ?? .badFormat)
                s.songs[i].candidate = false
            case .removed:
                if s.songs[i].state != .removed { s.songs[i].removedAt = now() }
                s.songs[i].state = .removed
                s.songs[i].candidate = false
                files.append(b.songId)
            }
        } else {
            // 다른 owner 폰이 올린 곡도 목록에 보인다(3.3 `song_status.name`). 지움이면 순번 비교용 표시만 남긴다.
            var row = SongRow(id: b.songId, name: b.name ?? "지운 노래", durationS: b.durationS,
                              state: .removed, candidate: b.candidate, lastSeq: seq, addedAt: now(), removedAt: now())
            switch b.status {
            case .accepted: row.state = .accepted; row.removedAt = nil
            case .rejected: row.state = .rejected(b.reason ?? .badFormat); row.candidate = false; row.removedAt = nil
            case .removed: row.candidate = false
            }
            s.songs.append(row)
        }
        // 거절된 곡은 목록에 잠깐 보이고 같은 id로 song_removed를 자동으로 보낸다(7.4, L48). 같은 저장 안에서 보낼 함에 넣는다.
        if b.status == .rejected, let row = s.songs.first(where: { $0.id == b.songId }), case .rejected = row.state {
            let item = makeItem(type: MessageType.songRemoved, body: SongRefBody(songId: b.songId))
            if !s.outbox.contains(where: { $0.type == MessageType.songRemoved && $0.body == item.body }) { s.outbox.append(item) }
        }
        return files
    }

    // MARK: 보내기

    private func makeItem<B: Encodable>(type: String, body: B, id: String? = nil, songId: String? = nil) -> OutboxItem {
        let t = now()
        return OutboxItem(id: id ?? ULID.generate(date: t), type: type, body: (try? JSONValue(encoding: body)) ?? .object([:]),
                          ts: Rfc3339.string(t), songId: songId, createdAt: t, nextTryAt: t)
    }

    private func kick() {
        if autoFlush { spawn { [self] in await flushOutbox() } }
    }

    // MARK: 돌봄 기록(phone-care-plan 6장)

    /// 돌봄 기록을 바꾸고 `care.json`에 저장한다. 저장하지 못하면 메모리 값도 바꾸지 않는다(`update`와 같다).
    @discardableResult
    private func updateCare(_ f: (inout CareFile) -> Void) -> Bool {
        if careStore.writesRefused {
            addNotice(Self.saveFailed)
            return false
        }
        var copy = care
        // 지우다 만 '기록 지우기'를 이 저장에서 먼저 마친다. 그때의 id만 빼므로 그 뒤 적은 기록은 남는다(R10).
        let wiping = state.clearingCareIds.map(Set.init)
        if let wiping { copy.events.removeAll { wiping.contains($0.id) }; copy.migratedFeedings = true }
        f(&copy)
        if let wiping { copy.events.removeAll { wiping.contains($0.id) } }   // 바꾸는 쪽이 옛 배열로 계산해 다시 넣어도 빼낸다(만료 정리 등)
        if copy == care { return true }
        do { try careStore.save(copy) }
        catch {
            addNotice(Self.saveFailed)
            return false
        }
        care = copy
        if wiping != nil { endCareClear() }   // 실패해도 다음에 같은 id를 다시 빼므로 새 기록은 지우지 않는다
        onCareChange?(copy)
        // care.json 저장 뒤(R19): 저장·고치기·지우기·되돌리기·잠·기저귀 켜고 끄기 모두 여기를 지난다
        if enqueueCareSummary(), started { kick() }
        return true
    }

    #if DEBUG
    /// U50 시연 시드. 사용자 저장과 같은 길(`updateCare`)이라 화면이 바로 바뀌고, owner면 저장 뒤 계기로 요약이 간다.
    func replaceCareForDebug(_ f: (CareFile) -> CareFile) { updateCare { $0 = f($0) } }
    #endif

    private var nowMs: Int64 { CareTime.ms(now()) }

    /// 새 기록의 틀. 시간대는 지금 기기 시간대다(R13).
    private func newEvent(_ kind: CareKind, at: Int64) -> CareEvent {
        CareEvent(id: ULID.generate(date: CareTime.date(at)), kind: kind, startAt: at, zone: TimeZone.current.identifier,
                  createdAt: at, updatedAt: at)
    }

    /// 빠른 길로 닫기(U14): 지금 시각으로 끝낸다. 이미 최대 길이를 넘었으면 끝 시각 모름으로 닫는다.
    /// 끝 시각 모름으로 닫았으면 true.
    @discardableResult
    private static func closeQuickly(_ e: inout CareEvent, now t: Int64) -> Bool {
        let unknown = CareRules.isOverLimit(e, now: t)
        // 시작은 지금+1분까지 고칠 수 있어(R3) 끝이 시작보다 앞서지 않게 한다.
        if unknown { e.endUnknown = true } else { e.endAt = max(t, e.startAt) }
        e.updatedAt = t
        return unknown
    }

    /// 수유·잠 시작. 같은 종류가 진행 중이면 그것을 빠른 길로 닫고 새로 시작한다(R1·R2·U14). 한 번에 저장한다.
    @discardableResult
    private func start(_ kind: CareKind, side: FeedSide? = nil) -> Bool {
        let t = nowMs
        var e = newEvent(kind, at: t)
        e.feedSide = side
        let previous = CareRules.inProgress(kind, in: care.events)
        var closedUnknown = false
        let ok = updateCare { c in
            if let p = previous, let i = c.events.firstIndex(where: { $0.id == p.id }) {
                closedUnknown = Self.closeQuickly(&c.events[i], now: t)
            }
            c.events.append(e)
        }
        guard ok else { return false }
        if previous != nil {
            switch kind {
            case .feed: setCareNotice(closedUnknown ? CareText.previousFeedEndedUnknown : CareText.previousFeedEnded)
            case .sleep: setCareNotice(closedUnknown ? CareText.previousSleepEndedUnknown : CareText.previousSleepEnded)   // U21
            default: break
            }
        }
        reconcileFeeding()
        return true
    }

    /// 타이머·잠금 화면의 '끝내기'(U14 빠른 길, 띠 없음).
    func endNow(_ id: String) {
        let t = nowMs
        let ok = updateCare { c in
            if let i = c.events.firstIndex(where: { $0.id == id && $0.inProgress }) { Self.closeQuickly(&c.events[i], now: t) }
        }
        if ok { reconcileFeeding() }
    }

    /// 타이머가 최대 길이를 넘었을 때 '끝 시각 모름으로 닫기'(U14).
    func closeEndUnknown(_ id: String) {
        let t = nowMs
        let ok = updateCare { c in
            if let i = c.events.firstIndex(where: { $0.id == id && $0.inProgress }) { c.events[i].endUnknown = true; c.events[i].updatedAt = t }
        }
        if ok { reconcileFeeding() }
    }

    /// '수유 시작'. 기록을 만든 뒤 차로 보내기는 `reconcileFeeding()`이 한다(U4). guardian 기록은 이 폰에만 남는다(U23).
    func startFeeding(side: FeedSide? = nil) { start(.feed, side: side) }

    func startSleep() { start(.sleep) }

    /// 빠른 기록·고치기 저장(R1·R3·R4). 겹치면 묻기 위해 저장하지 않고 `.overlap`을 돌려준다.
    func saveEvent(_ event: CareEvent, allowOverlap: Bool = false) -> CareSaveResult {
        let t = nowMs
        if let p = CareRules.problem(event, now: t) { return .problem(p) }
        if event.inProgress, let other = CareRules.inProgress(event.kind, in: care.events), other.id != event.id {
            return .problem(.inProgressExists)
        }
        if !allowOverlap, CareRules.overlaps(event, in: care.events, now: t) { return .overlap }
        var e = event
        e.updatedAt = t
        let ok = updateCare { c in
            if let i = c.events.firstIndex(where: { $0.id == e.id }) {
                e.createdAt = c.events[i].createdAt
                c.events[i] = e
            } else {
                c.events.append(e)
            }
        }
        guard ok else { return .notSaved }   // 문구는 위쪽 띠("저장하지 못했어요")가 보인다
        reconcileFeeding()
        return .saved
    }

    /// 지우기는 표시만 한다(R8). 차에는 알리지 않는다. 어느 길로 지워도 되돌리기를 보인다(U38).
    func deleteEvent(_ id: String) {
        let t = nowMs
        let ok = updateCare { c in
            if let i = c.events.firstIndex(where: { $0.id == id && $0.deletedAt == nil }) { c.events[i].deletedAt = t; c.events[i].updatedAt = t }
        }
        if ok { recentlyDeleted = id }
    }

    /// 지운 지 5초 안의 '되돌리기'.
    func undoDelete(_ id: String) {
        let t = nowMs
        let ok = updateCare { c in
            guard let i = c.events.firstIndex(where: { $0.id == id && $0.deletedAt != nil }) else { return }
            c.events[i].deletedAt = nil; c.events[i].updatedAt = t
            // U40: 지운 사이 같은 종류를 새로 시작했으면 되살린 기록은 끝 시각 모름으로 둔다(진행 중은 하나, R1).
            let e = c.events[i]
            if e.inProgress, c.events.contains(where: { $0.id != e.id && $0.kind == e.kind && $0.inProgress }) { c.events[i].endUnknown = true }
        }
        if ok {
            if recentlyDeleted == id { recentlyDeleted = nil }
            reconcileFeeding()
        }
    }

    func setSleepDiaperEnabled(_ on: Bool) { updateCare { $0.sleepDiaperEnabled = on } }

    /// 아이 별명은 폰 안에서만 쓴다(U8). 비우면 없앤다.
    func setChildNickname(_ name: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        updateCare { $0.childNickname = n.isEmpty ? nil : n }
    }

    /// R8: 지운 지 30일 지난 기록을 실제로 지운다. 앱을 열 때 부른다.
    private func purgeCare() {
        let t = nowMs
        let kept = CareRules.purged(care.events, now: t)
        if kept.count != care.events.count { updateCare { $0.events = kept } }
    }

    /// R11: 처음 한 번 지금 수유 기록을 돌봄 기록으로 옮긴다. 원래 기록은 전송 상태 표시용으로 둔다(U5).
    /// U22: 옛 경로가 이미 보낼 함에 넣은 수유를 다시 보내지 않게, `state.json`의 `lastSentFeedingAt`을 먼저 채우고 `care.json`을 쓴다.
    /// 그사이 죽으면 다음 실행이 다시 옮기고 결과는 같다.
    private func migrateFeedingsIfNeeded() {
        guard !care.migratedFeedings else { return }
        let t = nowMs
        if let latest = state.feedingLog.map({ CareTime.ms($0.at) }).max() {
            guard update({ $0.lastSentFeedingAt = max($0.lastSentFeedingAt ?? .min, latest) }) else { return }
        }
        updateCare { c in c = CareRules.migrated(c, from: state.feedingLog, zone: TimeZone.current.identifier, now: t) }
    }

    /// 차로 보내기는 이 함수로만 한다(U4·R5·R6). 보낼 함에 넣었으면 바로 보낸다.
    func reconcileFeeding() {
        if enqueueLatestFeeding() { kick() }
    }

    /// R5: owner이고 연결됐으며, 지우지 않은 수유 중 가장 늦은 시작이 `lastSentFeedingAt`보다 늦고 24시간 안이면
    /// 지금의 수유 보내기 경로(보낼 함 + 전송 상태 기록)에 넣고 같은 저장에서 `lastSentFeedingAt`을 올린다.
    /// 저장에 실패하면 아무것도 바뀌지 않는다. 다음 계기가 다시 본다.
    @discardableResult
    private func enqueueLatestFeeding() -> Bool {
        // R11 이전을 못 끝냈으면 먼저 한다. 끝나기 전에 보내면 보낸 기록(feedingLog)이 다음 이전에서 다른 id의 수유로 또 옮겨진다.
        migrateFeedingsIfNeeded()
        guard care.migratedFeedings, role == .owner, state.clearingCareIds == nil,   // 지우다 만 기록을 새 차로 보내지 않는다(R10)
              let latest = CareRules.latestFeed(care.events) else { return false }
        let t = now()
        guard latest.startAt > (state.lastSentFeedingAt ?? .min), latest.startAt >= CareTime.ms(t) - CareRules.sendWindowMs else { return false }
        let at = CareTime.date(latest.startAt)
        let id = ULID.generate(date: t)   // 메시지 id는 보낼 때마다 새로 만든다(같은 수유를 더 늦게 고쳐도 다시 보낼 수 있게)
        let item = makeItem(type: MessageType.feedingStarted, body: FeedingStartedBody(at: Rfc3339.string(at)), id: id)
        return update { s in
            s.feedingLog.insert(FeedingRecord(id: id, at: at), at: 0)
            s.feedingLog = Array(s.feedingLog.prefix(30))
            s.outbox.append(item)
            s.lastSentFeedingAt = latest.startAt
        }
    }

    /// R19(U45): 돌봄 요약 보내기는 이 함수로만 한다. 보낼 함에 넣었으면 바로 보낸다.
    func reconcileCareSummary() {
        if enqueueCareSummary() { kick() }
    }

    /// owner이고 연결됐으며, R18 요약에서 `as_of`를 뺀 것이 `lastSentCareSummary`와 다르면
    /// 안 보낸 옛 요약을 새것으로 바꾸고(없으면 넣고) 같은 저장에서 표시를 바꾼다. 저장에 실패하면 아무것도 바뀌지 않는다.
    /// 내용이 같아도 보내는 때: 날이 바뀌었을 때(안 보낸 요약도 새 as_of로 바꾼다), 시계가 뒤로 갔을 때(3.3).
    /// 저장 실패면 `.failed`(보낼 함이 보내기 직전 재계산에서 멈추는 데 쓴다).
    @discardableResult
    private func enqueueCareSummary() -> Bool { queueCareSummary() == .queued }

    private enum SummaryQueued { case queued, unchanged, failed }

    /// 보내 본 요약인가(U57): 보내기 전에 '보내 봄'을 저장했거나 지금 보내는 중이다(안드로이드 tried와 같다).
    /// 이런 요약은 바꾸지 않고 결과를 확정할 때까지 둔다(새것은 뒤에 하나). 바꿔치우면 늦게 닿은 옛 요약이 더 큰 seq로 새것을 덮는다.
    /// 시도 횟수는 보지 않는다: 429로 중계가 받지 않은 것이 확정된 요약은 '보내 봄'을 걷어(untried) 새것으로 바꿀 수 있게 한다.
    private func tried(_ i: OutboxItem) -> Bool { i.sentOnce || i.id == sendingId }

    private func queueCareSummary() -> SummaryQueued {
        guard sendsCareSummary, care.migratedFeedings, role == .owner,   // 옮기기 전 수유가 빠진 요약을 보내지 않는다(R11)
              state.clearingCareIds == nil else { return .unchanged }    // 지우다 만 기록으로 보내지 않는다(R10)
        let t = nowMs
        let asOf = t - ((t % 1000) + 1000) % 1000   // as_of는 초까지 쓴다
        let zone = timeZone()
        let body = CareRules.careSummary(care, asOf: t, zone: zone)
        // U61: 기록에서 온 값이 없는 요약이고 이 연결에서 요약을 넣은 적도 없으면 보내지 않는다(같은 차의 다른 owner 폰 요약을 빈 요약으로 덮지 않게).
        // '넣은 적'은 maxSentCareAsOf다: 기록 지우기에도 남아(빈 요약으로 차의 옛 요약을 바꾼다, R10) 연결 끊기(R9)에서만 사라진다(안드로이드와 같다)
        if state.maxSentCareAsOf == nil, body.isEmpty { return .unchanged }
        guard case .object(var key)? = try? JSONValue(encoding: body) else { return .unchanged }
        key["as_of"] = nil
        // 같은 날이어야 한다: 날이 바뀌면 차는 앞 요약의 '오늘' 값을 빼고, 24시간이 지나면 요약을 쓰지 않는다(1.4 L61).
        let sameDay = { (ms: Int64) in CareRules.dayKey(ms, zone) == CareRules.dayKey(asOf, zone) }
        if let p = state.outbox.first(where: { $0.type == MessageType.careSummary && !tried($0) }) {
            // 안 보낸 요약이 같은 날 같은 내용이면 그대로 둔다: 같은 id로 다시 보내야 응답을 잃어도 중계가 한 번만 처리한다(7.5).
            // 날이 바뀌었거나 시계가 뒤로 갔으면 새 as_of로 바꾼다.
            if case .object(var pk) = p.body, case .string(let s)? = pk["as_of"], let pd = Rfc3339.parse(s) {
                pk["as_of"] = nil
                let pAsOf = CareTime.ms(pd)
                // 앞서 보낸 더 늦은 as_of보다 이르면 차가 무시한다(3.3): 시각이 따라잡은 지금 as_of로 바꾼다
                if pk == key, pAsOf <= asOf, sameDay(pAsOf), asOf - pAsOf < 86_400_000, pAsOf >= (state.maxSentCareAsOf ?? pAsOf) { return .unchanged }   // 25시간인 날도 24시간을 넘기면 바꾼다
            }
        } else if state.lastSentCareSummary == .object(key), let last = state.lastSentCareAsOf,
                  last <= asOf, last >= (state.maxSentCareAsOf ?? last), sameDay(last), asOf - last < 86_400_000 {
            // 보낸 것과 같다. 다만 시계가 뒤로 갔으면(지금이 마지막 as_of보다 이르거나, 그 앞에 더 늦은 as_of를 보낸 적이 있으면) 차가 무시했을 수 있어 보낸다(3.3).
            return .unchanged
        }
        let item = makeItem(type: MessageType.careSummary, body: body)
        // 같은 초에 또 바뀌어도 그대로 보낸다: 차는 as_of가 같으면 중계 seq가 큰 것을 받는다(8.4, careReplaces).
        let ok = update { s in
            if let i = s.outbox.firstIndex(where: { $0.type == MessageType.careSummary && !tried($0) }) {
                // 안 보낸 요약은 하나만(새것이 옛것을 바꾼다, 7.5). 줄의 자리와 다시 보낼 기다림(429 Retry-After·백오프)은 이어받는다. 보내 본 요약은 두고 그 뒤에 둔다(U57)
                let old = s.outbox[i]
                var next = item
                // 실패해 기다리던 것(429로 되돌린 요약)만 기다림을 넘긴다. 보낸 적 없는 항목의 nextTryAt은 만든 시각이라 넘기면 시계가 앞섰을 때 만든 값이 줄을 막는다
                if old.attempts > 0 { next.attempts = old.attempts; next.nextTryAt = max(next.nextTryAt, old.nextTryAt); next.networkWait = old.networkWait }
                s.outbox[i] = next
            } else {
                s.outbox.append(item)
            }
            s.lastSentCareSummary = .object(key)
            s.lastSentCareAsOf = asOf
            s.maxSentCareAsOf = max(s.maxSentCareAsOf ?? asOf, asOf)
        }
        return ok ? .queued : .failed
    }

    /// `replacing`: '다시 녹음'으로 바꿀 거절·실패 곡(9장). 새 곡 검증·파일 준비가 끝난 뒤, 그 곡의 지움과 새 곡 추가를
    /// 한 번에 저장한다(지움 메시지가 먼저 선다). 실패하면 기존 곡은 그대로 남는다. 기존 파일은 저장이 성공한 뒤 지운다.
    func addSong(name: String, recording: URL, durationS: Double, replacing old: String? = nil) throws {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...30).contains(name.count) else { throw SongError.badName }
        guard durationS <= 180 else { throw SongError.tooLong }
        // 거절·실패한 곡만 바꾼다. 시트를 연 사이 차가 받아들였으면 바꾸지 않고 새 곡으로 넣는다(3곡 한도는 그대로 본다)
        let replaced = old.flatMap { o in state.songs.first { s in
            guard s.id == o else { return false }
            if case .rejected = s.state { return true }
            if case .failed = s.state { return true }
            return false
        } }
        guard songSlots(excluding: replaced?.id) < 3 else { throw SongError.limitReached }
        let size = (try? FileManager.default.attributesOfItem(atPath: recording.path)[.size] as? Int) ?? 0
        guard size <= 2_000_000 else { throw SongError.tooLarge }
        let id = ULID.generate(date: now())
        let dest = SongFiles.url(for: id)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.copyItem(at: recording, to: dest)   // 올릴 파일을 먼저 두고, 목록과 보낼 함은 한 번에 저장한다
        let item = makeItem(type: MessageType.songAdded, body: SongAddedBody(songId: id, name: name, durationS: durationS), songId: id)
        let removal = replaced.map { makeItem(type: MessageType.songRemoved, body: SongRefBody(songId: $0.id)) }
        let ok = update { s in
            if let r = replaced, let removal { Self.markSongRemoved(r.id, removal: removal, now: now(), in: &s) }
            s.songs.append(SongRow(id: id, name: name, durationS: durationS, state: .uploading, addedAt: now(), hasFile: true))
            s.outbox.append(item)
        }
        guard ok else {
            try? FileManager.default.removeItem(at: dest)
            throw SongError.saveFailed
        }
        if let r = replaced { SongFiles.remove(r.id) }   // 저장이 성공한 뒤에 지운다
        kick()
    }

    func removeSong(_ id: String) {
        guard let row = state.songs.first(where: { $0.id == id }), row.state != .removed else { return }
        let item = makeItem(type: MessageType.songRemoved, body: SongRefBody(songId: id))
        guard update({ s in Self.markSongRemoved(id, removal: item, now: now(), in: &s) }) else { return }
        SongFiles.remove(id)   // 파일은 저장이 성공한 뒤에 지운다
        kick()
    }

    /// 곡 지우기 한 벌(9장, 지우기·다시 녹음이 함께 쓴다): 표시·선택을 지우고, 아직 못 올린 그 곡의 올리기는 빼고
    /// (지운 곡의 PUT 기다림이 뒤 메시지를 막지 않게, 안드로이드 `removeSong`과 같다) 지움 메시지를 넣는다.
    private static func markSongRemoved(_ id: String, removal: OutboxItem, now: Date, in s: inout PhoneState) {
        if let i = s.songs.firstIndex(where: { $0.id == id }) { s.songs[i].state = .removed; s.songs[i].candidate = false; s.songs[i].removedAt = now }
        if s.selectedSongId == id { s.selectedSongId = nil }
        s.outbox.removeAll { $0.songId == id }
        s.outbox.append(removal)
    }

    func selectSong(_ id: String) {
        guard let row = state.songs.first(where: { $0.id == id }), row.state == .accepted else { return }
        let item = makeItem(type: MessageType.songSelected, body: SongRefBody(songId: id))
        if update({ s in s.selectedSongId = id; s.outbox.append(item) }) { kick() }
    }

    func flushOutbox() async {
        guard credentials != nil, !flushing else { return }
        let gen = generation
        flushing = true
        defer { if gen == generation { flushing = false } }
        // 만료 정리를 저장하지 못하면 만료된 항목이 메모리에 남아 전송될 수 있다. 보내지 않고 나중에 다시 한다.
        guard expireOutboxNow() else { retrySaveLater(); return }
        var refreshedId: String?
        while let item = state.outbox.first {
            if let hold = outboxHoldUntil, hold > now() { scheduleFlush(at: hold); return }   // 저장하지 못한 429 기다림
            if item.nextTryAt > now() { scheduleFlush(at: item.nextTryAt); return }   // 백오프·Retry-After(노래 PUT은 한 시간도 온다)
            // 앞 항목을 기다리는 사이 7일이 지났을 수 있다. 보내기 직전에 다시 보고 만료면 정리·저장 뒤 다음으로(계약 7.5).
            if item.createdAt < now().addingTimeInterval(-OutboxPolicy.maxAge) {
                guard expireOutboxNow() else { retrySaveLater(); return }
                continue
            }
            if item.type == MessageType.careSummary, refreshedId != item.id {
                // 보내기 직전에 다시 계산한다: 오래 기다린 안 보낸 요약의 as_of·'오늘' 값이 낡지 않게(복구가 자정을 넘긴 경우 등).
                // 보내 본 요약은 그대로 같은 id로 보내고, 달라졌으면 새것을 그 뒤에 둔다(U57. 차는 날짜 경계 규칙으로 앞 요약의 '오늘'을 뺀다).
                // 같은 날 같은 내용이면 그대로(같은 id로 다시 보내야 중계가 한 번만 처리한다). 바꿨으면 줄의 자리는 이어받는다(바뀌는 것은 안 보낸 요약뿐이다, U57).
                switch queueCareSummary() {
                case .queued: refreshedId = state.outbox.first?.id; continue
                case .failed: retrySaveLater(); return   // 지운 기록이 담긴 옛 요약을 보내지 않는다
                case .unchanged: refreshedId = item.id
                }
            }
            // 요약은 보내기 전에 '보내 봄'을 저장한다(U57): 응답 전에 죽거나 결과 저장이 실패해도 바꿔치우지 않는다
            if item.type == MessageType.careSummary, !item.sentOnce {
                guard updateItem(item.id, { $0.sentOnce = true }) else { retrySaveLater(); return }
            }
            sendingId = item.id
            let result = await attempt(item, gen: gen)
            if gen == generation { sendingId = nil }
            guard let (outcome, error, response, mark, posting) = result, gen == generation else {
                // 같은 연결에서 취소로 끝났다(새로고침을 띄운 화면이 사라짐 등): 항목은 그대로 두고 곧 다시 보낸다. 뒤에서는 scheduleFlush가 막고 앞으로 올 때 보낸다
                if result == nil, gen == generation { scheduleFlush(at: now().addingTimeInterval(1)) }
                return
            }
            // 통신 결과(결과 저장 실패와 따로). 4xx 거절도 중계에 닿은 것이다. 보내기 전에 버린 것(파일 없음 등)은 HTTP가 없어 보지 않는다
            if case .drop = outcome, error == nil {} else { observe(error, since: mark) }
            switch outcome {
            case .done:
                // 결과 표시와 보낼 함 지우기를 한 번에 저장한다. 저장하지 못하면 항목을 남기고 멈춘다.
                // 잠시 뒤 다시 보내도 같은 id라 한 번만 처리된다(계약 1.4).
                guard update({ s in
                    if let response { Self.applySent(item, response, now: now(), to: &s) }
                    s.outbox.removeAll { $0.id == item.id }
                    // 폰 시계가 중계보다 5분 넘게 앞서면 차는 이 요약을 미래 시각이라 버린다(3.3). 보낸 값으로 치지 않고 다음 계기에 다시 보낸다.
                    if item.type == MessageType.careSummary, let skew = clockSkew, skew < -300 { Self.forgetSentSummary(in: &s) }
                }) else { retrySaveLater(); return }
            case .drop:
                // 보내는 사이 보낼 함에서 빠진 항목(기록 지우기의 노래 등)의 거절은 알리지 않는다(안드로이드 wasQueued). 요약은 U57로 빠지지 않는다
                let wasQueued = state.outbox.contains { $0.id == item.id }
                guard update({ s in
                    Self.applyDropped(item, error, to: &s)
                    s.outbox.removeAll { $0.id == item.id }
                    if item.type == MessageType.careSummary { Self.forgetSentSummary(in: &s) }   // 차에 닿지 못했다(안드로이드 afterSend와 같다)
                    if wasQueued, Self.dropNotice(item.type) != nil, !s.unseenDrops.contains(item.type) { s.unseenDrops.append(item.type) }   // 안내를 보기 전에 끝나도 잃지 않게
                }) else { retrySaveLater(); return }
                if wasQueued, let text = Self.dropNotice(item.type) { addNotice(text) }   // 떠 있는 안내를 덮지 않는다(U54)
            case .reupload:
                guard updateItem(item.id, { $0.uploaded = false; $0.attempts += 1; $0.nextTryAt = now().addingTimeInterval(OutboxPolicy.backoff(attempts: $0.attempts)) }) else { retrySaveLater(); return }
            case .retry(let delay):
                // 429는 기기당 메시지 한도다(1.4): 이 항목이 바뀌거나 지워져도 다른 메시지·상태 요청도 기다린다
                if error?.status == 429, posting { outboxHoldUntil = max(outboxHoldUntil ?? .distantPast, now().addingTimeInterval(delay)) }
                // 보내는 중인 요약은 바뀌지 않는다(U57). 보내는 사이 지운 항목(기록 지우기의 노래 등)의 기다림은 남은 줄을 막지 않는다.
                // 메시지 429는 위 기기 기다림이 지킨다
                let id = item.id
                guard state.outbox.contains(where: { $0.id == id }) else { continue }
                // 429인 요약은 중계에 없다(같은 id를 한도 검사보다 먼저 본다): 보내 보지 않은 것으로 되돌려 새 요약으로 바꿀 수 있게 한다(U57, 안드로이드 untried)
                let untry = error?.status == 429 && item.type == MessageType.careSummary
                guard update({ s in
                    guard let i = s.outbox.firstIndex(where: { $0.id == id }) else { return }
                    s.outbox[i].attempts += 1; s.outbox[i].nextTryAt = max(s.outbox[i].nextTryAt, now().addingTimeInterval(delay)); s.outbox[i].networkWait = error == .network
                    if untry { Self.untried(id, in: &s) }
                }) else {
                    // 저장은 못 해도 메시지 Retry-After는 지킨다. 노래 PUT 한도는 메시지와 따로라(1.4) 기기 전체 기다림은 걸지 않는다(5초 뒤 다시 올려 다시 받는다).
                    // 다만 맨 앞 항목의 기다림은 넣은 순서대로 줄을 막는다(7.5, 보고서 계약 결정 항목)
                    if posting { outboxHoldUntil = max(outboxHoldUntil ?? .distantPast, now().addingTimeInterval(delay)) }
                    retrySaveLater(); return
                }
                scheduleFlush(at: now().addingTimeInterval(delay))
                return
            case .unauthorized:
                handleUnauthorized()
                return
            }
        }
    }

    private func retrySaveLater() { scheduleFlush(at: now().addingTimeInterval(5)) }

    /// '보내 봄'을 걷는다(U57, 안드로이드 untried와 같다): 429로 중계가 받지 않은 것이 확정된 요약. 그사이 이 표시 때문에 남긴 안 보낸 요약을 정리한다:
    /// 기록 지우기 중이면 모두 빼고(R10, 빈 요약은 지우기를 마칠 때 들어간다), 아니면 가장 뒤의 하나만 둔다(7.5). 메시지 429 기다림은 기기 기다림이 지킨다.
    private static func untried(_ id: String, in s: inout PhoneState) {
        guard let i = s.outbox.firstIndex(where: { $0.id == id }) else { return }
        s.outbox[i].sentOnce = false
        let unsent = s.outbox.filter { $0.type == MessageType.careSummary && !$0.sentOnce }.map(\.id)
        let keep = s.clearingCareIds != nil ? [] : Array(unsent.suffix(1))
        s.outbox.removeAll { unsent.contains($0.id) && !keep.contains($0.id) }
        if !s.outbox.contains(where: { $0.type == MessageType.careSummary }) { s.lastSentCareSummary = nil }
    }

    /// 7일 지난 보낼 함 항목을 정리해 저장하고 버린 것을 알린다(U54). 저장하지 못하면 false.
    private func expireOutboxNow() -> Bool {
        let cutoff = now().addingTimeInterval(-OutboxPolicy.maxAge)
        var expired: [String] = []
        guard update({ expired = Self.expireOutbox(before: cutoff, except: sendingId, in: &$0) }) else { return false }
        noteExpired(expired)
        return true
    }

    @discardableResult
    private func updateItem(_ id: String, _ f: (inout OutboxItem) -> Void) -> Bool {
        update { s in if let i = s.outbox.firstIndex(where: { $0.id == id }) { f(&s.outbox[i]) } }
    }

    /// 연결이 바뀌었으면(세대가 다르면) nil. 이전 차로 가던 요청의 결과는 새 연결에 적용하지 않는다.
    /// 마지막 값은 결과를 낸 HTTP 요청을 시작할 때의 관찰 번호다(노래는 올리기·보내기가 따로 잡는다, 띠 판단용).
    /// 마지막 값: 메시지 POST까지 갔는가(노래 PUT의 429는 시간당 업로드 한도라 메시지 한도와 따로다, 1.4).
    private func attempt(_ item: OutboxItem, gen: Int) async -> (SendOutcome, RelayError?, SendMessageResponse?, LinkMark, Bool)? {
        var mark = httpMark()
        var posting = false
        do {
            if let songId = item.songId, !item.uploaded {
                let file = SongFiles.url(for: songId)
                guard FileManager.default.fileExists(atPath: file.path) else { return (.drop, nil, nil, mark, posting) }
                try await relay.putSong(id: songId, file: file)
                guard gen == generation else { return nil }
                observe(nil, since: mark)   // 올리기도 중계에 닿은 HTTP다
                guard state.outbox.contains(where: { $0.id == item.id }) else { return (.done, nil, nil, mark, posting) }   // 업로드 사이 기록을 지웠으면 보내지 않는다
                updateItem(item.id) { $0.uploaded = true }
                // 올리는 사이 다른 메시지가 429를 받았으면 기기 기다림을 지킨다(1.4 '다른 메시지도 기다린다', 안드로이드와 같다).
                // 결과가 아니므로(nil) 시도 횟수를 늘리지 않고, 다음 보내기가 기다림 뒤 POST만 한다(파일은 올렸다)
                if let hold = outboxHoldUntil, hold > now() { return nil }
            }
            let env = Envelope(id: item.id, type: item.type, ts: item.ts, body: item.body)
            mark = httpMark()
            posting = true
            let response = try await relay.send(env)
            guard gen == generation else { return nil }
            return (.done, nil, response, mark, posting)
        } catch let e as RelayError {
            // 취소(뒤로 감·새로고침 화면이 사라짐)는 결과가 아니다: 항목·시도 횟수·띠를 그대로 둔다. 보내기가 곧 같은 id로 다시 보낸다
            guard gen == generation, e != .cancelled else { return nil }
            return (OutboxPolicy.classify(e, attempts: item.attempts), e, nil, mark, posting)
        } catch {
            return gen == generation ? (.drop, nil, nil, mark, posting) : nil
        }
    }

    /// 전송 결과를 상태에 반영한다. 보낼 함 지우기와 같은 `update` 안에서 부른다.
    private static func applySent(_ item: OutboxItem, _ r: SendMessageResponse, now: Date, to s: inout PhoneState) {
        switch item.type {
        case MessageType.feedingStarted:
            if let i = s.feedingLog.firstIndex(where: { $0.id == item.id }) {
                // 중계가 고쳐 저장한 값을 보여 준다(3.3).
                if case .string(let at)? = r.body.objectValue?["at"] { s.feedingLog[i].fixedAt = Rfc3339.parse(at) }
                s.feedingLog[i].sentAt = now
                if s.feedingLog[i].status == .sending { s.feedingLog[i].status = .sent }
                // feeding_state가 HTTP 응답보다 먼저 왔을 수 있다. 고친 시각을 저장한 뒤 지금 수유 상태와 다시 비교한다.
                if let last = s.feeding.lastStart, abs((s.feedingLog[i].fixedAt ?? s.feedingLog[i].at).timeIntervalSince(last)) < 1 {
                    s.feedingLog[i].status = .reflected
                }
            }
        case MessageType.songAdded:
            if let id = item.songId, let i = s.songs.firstIndex(where: { $0.id == id }), s.songs[i].state == .uploading {
                s.songs[i].state = .waiting
            }
        default: break
        }
    }

    /// 버려지는 전송의 실패 표시. 보낼 함 지우기와 같은 `update` 안에서 부른다.
    /// 차에 닿지 못한 요약(버림·만료·차가 미래로 버림): 내용이 같아도 다음 계기에 다시 보낸다. 보낼 함에 요약이 남았으면
    /// 표시는 그것을 가리키므로 둔다(U57, 안드로이드 afterSend와 같다). 항목을 뺀 뒤에 부른다.
    private static func forgetSentSummary(in s: inout PhoneState) {
        if !s.outbox.contains(where: { $0.type == MessageType.careSummary }) { s.lastSentCareSummary = nil }
    }

    private static func applyDropped(_ item: OutboxItem, _ error: RelayError?, to s: inout PhoneState) {
        // 수유 기록은 실패 표시를 남긴다(보내지 않는 기록에 '보내는 중'이 남지 않게).
        if item.type == MessageType.feedingStarted {
            if let i = s.feedingLog.firstIndex(where: { $0.id == item.id }), s.feedingLog[i].status == .sending {
                s.feedingLog[i].status = .failed
            }
            return
        }
        guard item.type == MessageType.songAdded, let id = item.songId else { return }
        let text: String
        switch error?.status {
        case 413?: text = "파일이 너무 커요. 3분 안으로 다시 녹음해 주세요."
        case 415?: text = "녹음 파일을 읽지 못했어요. 다시 녹음해 주세요."
        default: text = "노래를 보내지 못했어요. 다시 녹음해 주세요."
        }
        if let i = s.songs.firstIndex(where: { $0.id == id }), s.songs[i].state != .removed { s.songs[i].state = .failed(text) }
    }

    private func scheduleFlush(at date: Date) {
        retryTask?.cancel()
        guard appActive else { return }   // 뒤에서는 다시 시도하지 않는다(잠금 화면 끝내기의 한 번 보내기는 된다). 앞으로 오면 갱신이 보낸다
        let delay = max(0.2, date.timeIntervalSince(now()))
        retryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            if !Task.isCancelled { await self?.flushOutbox() }
        }
    }

    // MARK: 보호자

    /// 초대 만들기. 성공하면 (코드, nil), 실패하면 (nil, 화면 문구).
    func createInvite() async -> (InviteResponse?, String?) {
        let gen = generation
        do { return (try await observed { try await relay.createInvite() }, nil) }
        catch let e as RelayError {
            if gen == generation, e.isUnauthorized { handleUnauthorized() }
            if e.isOffline { return (nil, "인터넷이 필요해요") }
            if e.status == 409 { return (nil, "쓰지 않은 초대가 3개 있어요") }
            if e.status == 429 { return (nil, "잠시 뒤에 다시 해 주세요.") }
            return (nil, "초대를 만들지 못했어요. 잠시 뒤에 다시 해 주세요.")
        } catch { return (nil, "초대를 만들지 못했어요.") }
    }

    /// 인계 알림 받기(guardian만).
    /// 인계 알림 설정 요청 중(화면을 다시 열어도 유지된다). 요청이 겹치면 늦게 온 앞 응답이 나중 설정을 덮는다.
    private(set) var handoffBusy = false

    func setHandoffAlerts(_ on: Bool) async -> String? {
        guard !handoffBusy else { return nil }
        handoffBusy = true
        defer { handoffBusy = false }
        let gen = generation
        do {
            let r = try await observed { try await relay.patchMe(PatchDeviceRequest(handoffAlerts: on)) }
            guard gen == generation else { return nil }
            update { $0.handoffAlerts = r.handoffAlerts }
            return nil
        } catch let e as RelayError {
            if gen == generation, e.isUnauthorized { handleUnauthorized() }
            return e.isOffline ? "인터넷이 필요해요" : "바꾸지 못했어요. 잠시 뒤에 다시 해 주세요."
        } catch { return "바꾸지 못했어요." }
    }
}

extension AppModel {
    /// 알림을 눌러 앱이 열렸을 때: 받은 함에서 본문을 받아 저장한 뒤 상세로 간다(5.3).
    func openAlert(_ id: String) async {
        pendingOpenAlertId = id   // 진행 중인 조회가 있으면 refresh()가 바로 돌아오므로, 본문이 저장될 때 이어서 연다
        await refresh()
        resolvePendingOpen()
    }

    /// 사용자가 알림 상세를 직접 열었다. 다른 알림을 열려고 기다리던 요청은 버린다(고른 화면 위에 뜨지 않게).
    func userOpenedAlert(_ id: String) {
        if pendingOpenAlertId != id { pendingOpenAlertId = nil }
    }

    /// 열려던 알림의 본문이 저장돼 있으면 상세로 옮기고 보관값을 지운다.
    func resolvePendingOpen() {
        guard let id = pendingOpenAlertId, alert(id) != nil else { return }
        pendingOpenAlertId = nil
        openAlertId = id
    }
}
