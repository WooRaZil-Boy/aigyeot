import Foundation
import Observation

/// 나중에 FCM을 붙일 자리. 지금은 원격 푸시를 쓰지 않으므로 토큰이 없다.
/// 붙일 때 `messaging(_:didReceiveRegistrationToken:)`에서 `token`을 채우면 앱을 열 때 `PATCH /v1/devices/me`로 올린다(5.4).
protocol PushTokenSource {
    var token: String? { get }
}

enum ConnectFailure: Equatable {
    case codeInvalid, rateLimited(TimeInterval?), offline, badCode, other, busy

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
    var pendingCode: PairCode?
    /// 이미 연결된 폰에 새 링크가 왔을 때(G16).
    var replaceCandidate: PairCode?
    var linkError: String?
    /// 연결 429 잠금이 끝나는 시각. 직접 입력·확인 화면이 같이 본다. 화면을 옮기거나 코드를 고쳐도 풀리지 않는다(계약 9장).
    private(set) var pairLockedUntil: Date?
    /// 앱 안 배너로 띄울 인계 알림 메시지 id(`show(msg_id)`, 5.6).
    var bannerAlertId: String?
    /// 알림을 눌러 열었을 때 상세로 갈 인계 알림 id.
    var openAlertId: String?
    /// 알림을 눌러 열려는 id. 본문이 저장되면 `openAlertId`로 옮긴다. 연결 세대가 바뀌면 지운다.
    @ObservationIgnored var pendingOpenAlertId: String?
    /// 홈 위쪽 띠에 보이는 알림(보내지 못한 것 등).
    var notice: String?
    /// 탭(연결 안 됨) 위에 연결 흐름을 띄웠는가(U7). 저장하지 않는다.
    var connectShown = false
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
    @ObservationIgnored private var socket: RelaySocket?
    @ObservationIgnored private var flushing = false
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private var devicePollTask: Task<Void, Never>?
    @ObservationIgnored private var staleRetryTask: Task<Void, Never>?
    /// 연결(토큰)이 바뀔 때마다 올린다. await 뒤에 응답·오류의 세대를 확인해 이전 차의 결과를 버린다.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var appActive = false
    @ObservationIgnored private var refreshing = false
    @ObservationIgnored private var retryAfterRefresh = false   // 갱신 중에 저장이 실패했으면 갱신이 끝난 뒤 재시도를 예약한다
    @ObservationIgnored private var requestingState = false
    /// 연결 요청이 진행 중이면 새 요청을 받지 않는다(안드로이드 startPair의 pairJob?.isActive와 같다). 늦게 온 옛 응답이 새 연결을 덮지 않게 한다.
    @ObservationIgnored private var pairing = false
    /// '이 휴대폰의 기록 지우기'를 한 횟수. 상태 요청 응답을 기다리는 사이 지웠는지 알아내는 데 쓴다.
    @ObservationIgnored private var clearCount = 0
    /// 처음 받은 함을 처리하는 동안 온 소켓 프레임은 모아 둔다(밀린 것과 실시간을 구분).
    @ObservationIgnored private var backlogDone = true
    @ObservationIgnored private var bufferedLive: [Envelope] = []
    /// 모델이 띄운 백그라운드 작업. 시험이 `settle()`로 끝나기를 기다린다.
    @ObservationIgnored private var pending: [UUID: Task<Void, Never>] = [:]

    init(relay: RelayAPI, store: StateStore, credentials: CredentialStore, careStore: CareStore = MemoryCareStore(),
         baseURL: URL? = nil, now: @escaping () -> Date = Date.init) {
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
        if self.credentials == nil, self.state.carId != nil { self.state = PhoneState(introSeen: true) }
        // 저장 파일이 깨져 빈 상태로 복구했는데 자격은 남아 있으면, 이미 ack한 수유·곡 상태가 받은 함에 없다.
        // 요청 필요 표시를 켜 두면 기기 목록 조회가 성공한 뒤 state_request로 다시 받는다(계약 7.4, G39).
        // 저장이 거부된 실행은 건드리지 않는다. 원본이 그대로라 다음 정상 실행이 그것을 읽는다.
        // 알려진 한계: 복구 직후 이 표시의 저장까지 실패하면 재시도하지 않는다. 그러면 수유·곡 상태가 다음 차 갱신
        // (`feeding_state`·`song_status`)이 올 때까지 비어 보일 뿐이고, 원본은 `state.json.broken-*`에 남아 있다.
        // 자격만 남고 상태에 차가 없는 경우(앱 재설치 뒤 Keychain만 남음, 자격 저장 직후 상태 저장 전에 종료)도 같다.
        if store.restoreFailed || self.state.carId == nil, !store.writesRefused, let c = self.credentials, c.role == .owner {
            // 기준 시각도 채워, 첫 요청이 중계에서 만료돼도 24시간 재요청이 걸리게 한다(안드로이드 paired와 같다).
            update { $0.carId = c.carId; $0.needsStateRequest = true; $0.lastReceivedAt = $0.lastReceivedAt ?? now() }
        }
        purgeExpired()
        if !store.restoreFailed { cleanSongFiles() }   // 복원에 실패한 실행에서는 녹음 파일을 지우지 않는다
        migrateFeedingsIfNeeded()
        purgeCare()
        enqueueLatestFeeding()   // 앱 시작(R6). 보내기는 앞으로 올 때의 갱신이 한다
    }

    // MARK: 파생 값

    var role: PhoneRole? { credentials?.role }
    var isPaired: Bool { credentials != nil }

    var clockWarning: String? {
        guard let s = clockSkew, abs(s) > 120 else { return nil }
        return "휴대폰 시계가 \(Int((abs(s) / 60).rounded()))분 달라요. 수유 시각이 어긋날 수 있어요."
    }

    var car: DeviceInfo? { state.devices.first { $0.role == .car } }
    var visibleSongs: [SongRow] { state.songs.filter { $0.state != .removed }.sorted { $0.addedAt < $1.addedAt } }

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
            notice = "저장하지 못했어요. 저장 공간을 확인해 주세요."
            return false
        }
        var copy = state
        f(&copy)
        if copy == state { return true }   // 바뀐 게 없으면 저장하지 않는다
        do { try store.save(copy) }
        catch {
            notice = "저장하지 못했어요. 저장 공간을 확인해 주세요."
            return false
        }
        state = copy
        return true
    }

    /// 7일 지난 id·보낼 함, 90일 지난 기록, 지운 지 7일 지난 곡 표시를 지운다(7.4, G34).
    func purgeExpired() {
        let t = now()
        let week = t.addingTimeInterval(-7 * 86400), quarter = t.addingTimeInterval(-90 * 86400)
        update { s in
            s.processed = s.processed.filter { $0.value > week }
            s.shown = s.shown.filter { $0.value > week }
            Self.expireOutbox(before: week, in: &s)
            s.reports.removeAll { $0.receivedAt < quarter }
            s.alerts.removeAll { $0.receivedAt < quarter }
            s.songs.removeAll { $0.state == .removed && ($0.removedAt ?? $0.addedAt) < week }
            s.deviceMarks = s.deviceMarks.filter { $0.value.updatedAt > week }
        }
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
    private static func expireOutbox(before cutoff: Date, in s: inout PhoneState) {
        for item in s.outbox where item.createdAt < cutoff {
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
        s.outbox.removeAll { $0.createdAt < cutoff }
    }

    func markIntroSeen() { update { $0.introSeen = true } }

    // MARK: 연결

    /// 링크·QR이 이 길로 온다. 확인 화면을 거치고 바로 연결하지 않는다(G17).
    func receive(link url: URL) {
        guard let code = PairCode.parse(link: url) else {
            linkError = "올바른 연결 링크가 아니에요"
            return
        }
        propose(code)
    }

    /// 429 잠금이 남았는가. 시각은 `now` 시계로 본다.
    var isPairLocked: Bool { pairLockedUntil.map { $0 > now() } ?? false }

    /// 429 뒤 잠금 길이(초). Retry-After가 없거나 1초 미만이면 10초·1초(안드로이드 pairLockUntil과 같다).
    static func pairLockSeconds(_ retryAfter: TimeInterval?) -> TimeInterval { max(retryAfter ?? 10, 1) }

    func propose(_ code: PairCode) {
        // 직접 친 코드의 요청이 진행 중이면 확인 화면으로 옮기지 않는다(끝난 결과를 받을 화면이 닫힌다).
        guard !pairing else { linkError = "연결하고 있어요. 끝난 뒤에 다시 해 주세요."; return }
        linkError = nil
        if isPaired { replaceCandidate = code } else { pendingCode = code }
    }

    /// 코드 넣기 화면에서 직접 친 코드는 확인 없이 바로 연결한다(G17, L57). 오류는 호출한 화면이 보인다.
    /// 코드 넣기 화면은 연결되지 않았을 때만 보이므로 바꾸기 확인(G16)은 거치지 않는다.
    func connect(typed code: PairCode) async -> ConnectFailure? {
        linkError = nil
        return await pair(code)
    }

    /// "지금 연결된 차를 끊고 새 차에 연결할까요?"에 예(G16).
    func confirmReplace(code: PairCode) async {
        replaceCandidate = nil
        if let message = await disconnect() {
            notice = message   // 끊기에 실패하면 이유를 보이고, 새 코드 확인으로 넘어가지 않는다
        } else {
            pendingCode = code
        }
    }

    func pair(_ code: PairCode) async -> ConnectFailure? {
        guard !pairing else { return .busy }
        if isPairLocked, let until = pairLockedUntil { return .rateLimited(until.timeIntervalSince(now())) }
        pairing = true
        defer { pairing = false }
        do {
            let r = try await relay.pair(code: code.value, pushToken: pushToken?.token)
            guard let role = PhoneRole(rawValue: r.role.rawValue) else { return .other }
            // 연결 세대가 바뀐다. 이전 연결의 응답은 이제 버려진다.
            // 이전 상태·보낼 함을 지우는 저장이 성공해야만 새 자격 증명을 켠다(이전 차의 보낼 함이 새 차로 가지 않게).
            guard wipeLocal() else { return .other }
            let c = Credentials(carId: r.carId, deviceId: r.deviceId, token: r.deviceToken, role: role)
            // Keychain에 저장하지 못하면 연결로 치지 않는다(다음 실행에서 토큰이 없어 끊긴 것처럼 되지 않게).
            do { try credStore.save(c) } catch { return .other }
            credentials = c
            update { s in
                s.carId = r.carId
                s.lastReceivedAt = now()   // 연결 시각을 기준으로 둬 차 메시지를 한 번도 못 받아도 24시간 재요청이 걸린다(안드로이드 paired와 같다)
                s.handoffAlerts = role == .guardian ? true : nil   // 기본값은 guardian 참, owner 거짓(G12)
            }
            pendingCode = nil
            gone = false
            connectShown = false   // 연결이 끝나면 오늘 탭으로 간다(5장)
            notice = "연결됐어요"
            reconcileFeeding()   // 연결 직후(R6): 연결 전에 적은 24시간 안의 수유를 보낸다
            if appActive { didBecomeActive() }   // 연결 직후 받기·동기화를 바로 시작한다
            return nil
        } catch let e as RelayError {
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
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(secs * 1_000_000_000))
            if self?.pairLockedUntil == until { self?.pairLockedUntil = nil }
        }
    }

    /// 설정의 '연결 끊기'. 중계의 토큰을 지워야 해서 인터넷이 필요하다. 실패하면 문구를 돌려준다.
    func disconnect() async -> String? {
        let gen = generation
        do { try await relay.deleteMe() }
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
        wipeAll()
        gone = true
    }

    /// 로컬 기록을 비우고 연결 세대를 올린다. 이 뒤에 도착하는 이전 연결의 응답·오류는 모두 버려진다.
    /// 초기화 저장이 성공했는지 돌려준다. 파일은 저장이 성공한 뒤에 지운다.
    @discardableResult
    private func wipeLocal() -> Bool {
        generation += 1
        pendingOpenAlertId = nil
        retryTask?.cancel()
        staleRetryTask?.cancel()
        flushing = false; refreshing = false; requestingState = false; retryAfterRefresh = false
        backlogDone = true; bufferedLive = []
        let seen = state.introSeen
        guard update({ $0 = PhoneState(introSeen: seen) }) else { return false }
        SongFiles.removeAll()
        SongFiles.removeBrokenCopies()
        return true
    }

    private func wipeAll() {
        stopSocket()
        wipeLocal()
        credStore.clear()
        credentials = nil
        pendingCode = nil
        bannerAlertId = nil
        notice = nil
    }

    /// '이 휴대폰의 기록 지우기'(7.4): 캐시·녹음 파일·기록을 지우고 토큰은 남긴다. 상태 요청 표시를 켠다.
    func clearLocalRecords() {
        let ok = update { s in
            s.reports = []; s.alerts = []; s.songs = []; s.selectedSongId = nil
            s.feeding = FeedingState(); s.feedingLog = []; s.shown = [:]
            s.outbox.removeAll { $0.songId != nil }   // 녹음 파일이 필요한 노래 올리기만 뺀다. 삭제·선택 요청은 파일이 필요 없어 남긴다
            s.needsStateRequest = true
        }
        guard ok else { return }
        clearCount += 1
        SongFiles.removeAll()   // 파일은 저장이 성공한 뒤에 지운다
        SongFiles.removeBrokenCopies()
        // R10·U18: 돌봄 기록도 지운다. 설정은 남기고, 지운 수유를 다시 옮겨 오지 않게 옮기기 표시를 켠다.
        if updateCare({ $0.events = []; $0.migratedFeedings = true }) { FileCareStore.removeBrokenCopies(); CareExport.removeCopies() }
        bannerAlertId = nil
        spawn { [self] in await requestStateAfterDevices() }
    }

    // MARK: 앞에 있을 때 연결

    func didBecomeActive() {
        appActive = true
        purgeCare()
        guard isPaired else { return }
        purgeExpired()
        enqueueLatestFeeding()   // 앞으로 올 때(R6). 아래 갱신이 보낸다
        // 새 갱신을 실제로 시작할 때만 버퍼링을 켠다. 24시간 판단은 소켓·받은 함보다 먼저(7.4).
        let began = beginRefresh()
        startSocket()
        if began { spawn { [self] in await performRefresh() } }
    }

    func didEnterBackground() {
        appActive = false
        stopSocket()
    }

    /// 마지막으로 차의 메시지를 받은 지 24시간이 넘었으면 '상태 요청 필요'를 켠다. 표시는 201로만 끈다.
    /// 표시가 필요 없거나 저장에 성공하면 true. 저장에 실패하면 false(호출한 쪽은 메시지 적용을 미룬다).
    private func markStaleIfNeeded() -> Bool {
        guard let last = state.lastReceivedAt, now().timeIntervalSince(last) > 24 * 3600, !state.needsStateRequest else { return true }
        return update { $0.needsStateRequest = true }
    }

    /// 필요 표시를 저장하지 못한 채 받은 메시지를 적용하면 `lastReceivedAt`이 갱신돼 재요청 조건이 사라진다.
    /// 그래서 저장이 성공할 때까지 갱신을 시작하지 않고 5초 뒤 다시 시도한다.
    private func scheduleStaleRetry() {
        guard !store.writesRefused else { return }   // 저장을 영구 거부하는 실행에서 5초마다 헛돌지 않는다
        if refreshing { retryAfterRefresh = true; return }   // 진행 중인 갱신이 끝나면 예약한다(그 전에 5초가 지나면 beginRefresh가 거절한다)
        staleRetryTask?.cancel()
        staleRetryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled, let self else { return }
            // 타이머가 울릴 때 다른 갱신이 진행 중이면 refresh()가 거절된다. 그 갱신이 끝난 뒤로 넘긴다.
            if self.refreshing { self.retryAfterRefresh = true } else { await self.refresh() }
        }
    }

    /// 소켓이 붙을 때 상태 요청 표시가 남아 있으면 다시 판단한다(앱을 열 때 조회가 실패했던 경우). 표시가 없으면 목록을 또 받지 않는다.
    func socketDidConnect(_ up: Bool) {
        socketConnected = up
        if up, role == .owner, state.needsStateRequest { spawn { [self] in await requestStateAfterDevices() } }
    }

    private func startSocket() {
        guard socket == nil, let baseURL else { return }
        let gen = generation
        let s = RelaySocket(baseURL: baseURL, token: { [weak self] in self?.credStore.load()?.token }) { [weak self] event in
            guard let self, gen == self.generation else { return }   // 이전 연결의 소켓 이벤트는 버린다
            switch event {
            case .connected(let up): self.socketDidConnect(up)
            case .unauthorized: self.handleUnauthorized()
            case .envelope(let env):
                if self.backlogDone { self.spawn { if !(await self.ingest([env], live: true)) { self.scheduleStaleRetry() } } }
                else { self.bufferedLive.append(env) }
            }
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
                    if self.socketConnected { await self.refreshDevices() }
                }
            }
        }
    }

    private static let devicePollInterval: UInt64 = 60_000_000_000

    private func stopSocket() {
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
    func refresh() async {
        guard beginRefresh() else { return }
        await performRefresh()
    }

    /// 이미 갱신 중이면 false(진행 중인 갱신의 버퍼링 상태를 건드리지 않는다). 시작하면 24시간 판단을 하고 버퍼링을 켠다.
    private func beginRefresh() -> Bool {
        guard credentials != nil, !refreshing else { return false }
        backlogDone = false      // 처음 받은 함을 처리하는 동안 온 소켓 프레임은 모아 둔다
        guard markStaleIfNeeded() else { scheduleStaleRetry(); return false }
        refreshing = true
        return true
    }

    private func performRefresh() async {
        guard let c = credentials else { return }
        let gen = generation
        defer {
            if gen == generation {
                refreshing = false
                if retryAfterRefresh { retryAfterRefresh = false; scheduleStaleRetry() }
            }
        }

        var inboxError: RelayError?
        do {
            var after: Int64?
            var more = true
            while more {
                let page = try await relay.inbox(afterSeq: after)
                guard gen == generation else { return }
                let saved = await ingest(page.messages, live: false)
                guard gen == generation else { return }
                if !saved { scheduleStaleRetry(); break }   // 실패한 메시지보다 뒤의 쪽을 먼저 적용하지 않는다
                after = page.messages.last?.seq
                more = page.more && after != nil
            }
        } catch let e as RelayError {
            inboxError = e
        } catch {}
        guard gen == generation else { return }
        if let e = inboxError {
            if e.isUnauthorized { handleUnauthorized(); return }
            offline = e.isOffline
        } else {
            offline = false
        }
        // 밀린 것을 다 처리했다. 그동안 모아 둔 소켓 프레임은 이제 실시간으로 처리한다.
        backlogDone = true
        let buffered = bufferedLive
        bufferedLive = []
        if !buffered.isEmpty, !(await ingest(buffered, live: true)) { scheduleStaleRetry() }
        guard gen == generation else { return }
        if inboxError?.isOffline == true { await flushOutbox(); return }
        if c.role == .owner { await requestStateAfterDevices() }
        await syncPushToken()
        await flushOutbox()
    }

    /// 기기 목록 조회가 성공한 뒤에 상태를 요청한다(계약 7.4). 갱신과 '기록 지우기'가 함께 쓰는 길이다.
    private func requestStateAfterDevices() async {
        if await refreshDevices() { await sendStateRequestIfNeeded() }
    }

    @discardableResult
    func refreshDevices() async -> Bool {
        guard role == .owner else { return false }
        let gen = generation
        do {
            let r = try await relay.devices()
            guard gen == generation else { return false }
            // 이미 적용한 것보다 오래된(작은) 목록 응답은 버린다. 목록과 snapshot_seq는 한 번에 저장한다.
            update { s in
                guard r.snapshotSeq >= s.devicesSnapshotSeq else { return }
                s.devices = Self.mergeDevices(local: s.devices, snapshot: r, marks: s.deviceMarks)
                s.devicesSnapshotSeq = r.snapshotSeq
            }
            return true
        } catch let e as RelayError {
            if gen == generation, e.isUnauthorized { handleUnauthorized() }
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

    private func syncPushToken() async {
        guard let t = pushToken?.token else { return }
        let gen = generation
        do { _ = try await relay.patchMe(PatchDeviceRequest(pushToken: .set(t))) }
        catch let e as RelayError { if gen == generation, e.isUnauthorized { handleUnauthorized() } }   // 실패는 다음 앱 열기 때 다시(5.4)
        catch {}
    }

    /// 상태 요청(G39). owner만, 10분 안에 다시 보내지 않는다. 오프라인이면 보낼 함에 넣지 않고 표시만 둔다.
    /// 동시에 불러도 한 번만 보내도록 첫 await 전에 '요청 중' 표시를 세운다.
    func sendStateRequestIfNeeded() async {
        guard role == .owner, state.needsStateRequest, !requestingState else { return }
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
            _ = try await relay.send(env)
            guard gen == generation else { return }
            // 기다리는 사이 기록을 지웠다면 그 뒤에 필요해진 요청 표시는 남긴다(10분이 지나면 다시 요청한다).
            let cleared = clears != clearCount
            update { $0.needsStateRequest = cleared }
        } catch let e as RelayError {
            if gen == generation, e.isUnauthorized { handleUnauthorized() }
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
            ackIds.append(env.id)
            if let id = effects.banner { bannerAlertId = id }
            effects.removeFiles.forEach(SongFiles.remove)
            if effects.outboxAdded { kick() }   // 거절된 곡의 자동 삭제 같은, 받은 결과로 생긴 전송을 바로 시작한다
        }
        guard !ackIds.isEmpty else { return allSaved }
        do { try await relay.ack(ids: ackIds) }
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
    func show(_ messageId: String) {
        guard state.shown[messageId] == nil else { return }
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
            notice = "저장하지 못했어요. 저장 공간을 확인해 주세요."
            return false
        }
        var copy = care
        f(&copy)
        if copy == care { return true }
        do { try careStore.save(copy) }
        catch {
            notice = "저장하지 못했어요. 저장 공간을 확인해 주세요."
            return false
        }
        care = copy
        onCareChange?(copy)
        return true
    }

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
            case .feed: notice = closedUnknown ? CareText.previousFeedEndedUnknown : CareText.previousFeedEnded
            case .sleep: notice = closedUnknown ? CareText.previousSleepEndedUnknown : CareText.previousSleepEnded   // U21
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
        guard care.migratedFeedings, role == .owner, let latest = CareRules.latestFeed(care.events) else { return false }
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

    func addSong(name: String, recording: URL, durationS: Double) throws {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...30).contains(name.count) else { throw SongError.badName }
        guard durationS <= 180 else { throw SongError.tooLong }
        guard visibleSongs.count < 3 else { throw SongError.limitReached }
        let size = (try? FileManager.default.attributesOfItem(atPath: recording.path)[.size] as? Int) ?? 0
        guard size <= 2_000_000 else { throw SongError.tooLarge }
        let id = ULID.generate(date: now())
        let dest = SongFiles.url(for: id)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.copyItem(at: recording, to: dest)   // 올릴 파일을 먼저 두고, 목록과 보낼 함은 한 번에 저장한다
        let item = makeItem(type: MessageType.songAdded, body: SongAddedBody(songId: id, name: name, durationS: durationS), songId: id)
        let ok = update { s in
            s.songs.append(SongRow(id: id, name: name, durationS: durationS, state: .uploading, addedAt: now(), hasFile: true))
            s.outbox.append(item)
        }
        guard ok else {
            try? FileManager.default.removeItem(at: dest)
            throw SongError.saveFailed
        }
        kick()
    }

    func removeSong(_ id: String) {
        guard let row = state.songs.first(where: { $0.id == id }), row.state != .removed else { return }
        let item = makeItem(type: MessageType.songRemoved, body: SongRefBody(songId: id))
        let ok = update { s in
            if let i = s.songs.firstIndex(where: { $0.id == id }) {
                s.songs[i].state = .removed; s.songs[i].candidate = false; s.songs[i].removedAt = now()
            }
            if s.selectedSongId == id { s.selectedSongId = nil }
            s.outbox.append(item)
        }
        guard ok else { return }
        SongFiles.remove(id)   // 파일은 저장이 성공한 뒤에 지운다
        kick()
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
        let cutoff = now().addingTimeInterval(-OutboxPolicy.maxAge)
        // 만료 정리를 저장하지 못하면 만료된 항목이 메모리에 남아 전송될 수 있다. 보내지 않고 나중에 다시 한다.
        guard update({ Self.expireOutbox(before: cutoff, in: &$0) }) else { retrySaveLater(); return }
        while let item = state.outbox.first {
            if item.nextTryAt > now() { scheduleFlush(at: item.nextTryAt); return }
            // 앞 항목을 기다리는 사이 7일이 지났을 수 있다. 보내기 직전에 다시 보고 만료면 정리·저장 뒤 다음으로(계약 7.5).
            if item.createdAt < now().addingTimeInterval(-OutboxPolicy.maxAge) {
                let c = now().addingTimeInterval(-OutboxPolicy.maxAge)
                guard update({ Self.expireOutbox(before: c, in: &$0) }) else { retrySaveLater(); return }
                continue
            }
            guard let (outcome, error, response) = await attempt(item, gen: gen), gen == generation else { return }
            switch outcome {
            case .done:
                // 결과 표시와 보낼 함 지우기를 한 번에 저장한다. 저장하지 못하면 항목을 남기고 멈춘다.
                // 잠시 뒤 다시 보내도 같은 id라 한 번만 처리된다(계약 1.4).
                guard update({ s in
                    if let response { Self.applySent(item, response, now: now(), to: &s) }
                    s.outbox.removeAll { $0.id == item.id }
                }) else { retrySaveLater(); return }
                offline = false
            case .drop:
                guard update({ s in
                    Self.applyDropped(item, error, to: &s)
                    s.outbox.removeAll { $0.id == item.id }
                }) else { retrySaveLater(); return }
                switch item.type {
                case MessageType.feedingStarted: notice = "수유 기록을 차로 보내지 못했어요."
                case MessageType.songRemoved: notice = "노래 삭제를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요."
                case MessageType.songSelected: notice = "실험에 쓸 노래 선택을 차로 보내지 못했어요."
                default: break
                }
            case .reupload:
                guard updateItem(item.id, { $0.uploaded = false; $0.attempts += 1; $0.nextTryAt = now().addingTimeInterval(OutboxPolicy.backoff(attempts: $0.attempts)) }) else { retrySaveLater(); return }
            case .retry(let delay):
                if error?.isOffline == true { offline = true }
                guard updateItem(item.id, { $0.attempts += 1; $0.nextTryAt = now().addingTimeInterval(delay) }) else { retrySaveLater(); return }
                scheduleFlush(at: now().addingTimeInterval(delay))
                return
            case .unauthorized:
                handleUnauthorized()
                return
            }
        }
    }

    private func retrySaveLater() { scheduleFlush(at: now().addingTimeInterval(5)) }

    @discardableResult
    private func updateItem(_ id: String, _ f: (inout OutboxItem) -> Void) -> Bool {
        update { s in if let i = s.outbox.firstIndex(where: { $0.id == id }) { f(&s.outbox[i]) } }
    }

    /// 연결이 바뀌었으면(세대가 다르면) nil. 이전 차로 가던 요청의 결과는 새 연결에 적용하지 않는다.
    private func attempt(_ item: OutboxItem, gen: Int) async -> (SendOutcome, RelayError?, SendMessageResponse?)? {
        do {
            if let songId = item.songId, !item.uploaded {
                let file = SongFiles.url(for: songId)
                guard FileManager.default.fileExists(atPath: file.path) else { return (.drop, nil, nil) }
                try await relay.putSong(id: songId, file: file)
                guard gen == generation else { return nil }
                guard state.outbox.contains(where: { $0.id == item.id }) else { return (.done, nil, nil) }   // 업로드 사이 기록을 지웠으면 보내지 않는다
                updateItem(item.id) { $0.uploaded = true }
            }
            let env = Envelope(id: item.id, type: item.type, ts: item.ts, body: item.body)
            let response = try await relay.send(env)
            guard gen == generation else { return nil }
            return (.done, nil, response)
        } catch let e as RelayError {
            guard gen == generation else { return nil }
            return (OutboxPolicy.classify(e, attempts: item.attempts), e, nil)
        } catch {
            return gen == generation ? (.drop, nil, nil) : nil
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
        do { return (try await relay.createInvite(), nil) }
        catch let e as RelayError {
            if gen == generation, e.isUnauthorized { handleUnauthorized() }
            if e.isOffline { return (nil, "인터넷이 필요해요") }
            if e.status == 409 { return (nil, "쓰지 않은 초대가 3개 있어요") }
            if e.status == 429 { return (nil, "잠시 뒤에 다시 해 주세요.") }
            return (nil, "초대를 만들지 못했어요. 잠시 뒤에 다시 해 주세요.")
        } catch { return (nil, "초대를 만들지 못했어요.") }
    }

    /// 인계 알림 받기(guardian만).
    func setHandoffAlerts(_ on: Bool) async -> String? {
        let gen = generation
        do {
            let r = try await relay.patchMe(PatchDeviceRequest(handoffAlerts: on))
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
