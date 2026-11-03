import Foundation
import Security

// 로컬 저장(계약 7.4). 기기 토큰은 Keychain, 나머지는 앱 전용 폴더의 JSON 한 파일이다.

protocol CredentialStore {
    func load() -> Credentials?
    /// 저장하지 못하면 던진다. 호출한 쪽은 성공한 뒤에만 연결을 켠다.
    func save(_ c: Credentials) throws
    func clear()
}

struct KeychainError: Error { let status: OSStatus }

/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. 백업·다른 기기 복원에 실리지 않는다.
struct KeychainCredentialStore: CredentialStore {
    var service = "kr.aigyeot.phone.credentials"
    private let account = "device"

    private var base: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    func load() -> Credentials? {
        var q = base
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(Credentials.self, from: data)
    }

    func save(_ c: Credentials) throws {
        clear()
        let data = try JSONEncoder().encode(c)
        var q = base
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    func clear() { SecItemDelete(base as CFDictionary) }
}

final class MemoryCredentialStore: CredentialStore {
    var value: Credentials?
    /// 시험에서 Keychain 저장 실패를 흉내 낸다.
    var failing = false
    init(_ value: Credentials? = nil) { self.value = value }
    func load() -> Credentials? { value }
    func save(_ c: Credentials) throws {
        if failing { throw KeychainError(status: errSecIO) }
        value = c
    }
    func clear() { value = nil }
}

// MARK: 폰 상태

enum SongState: Codable, Equatable {
    case uploading                 // 올리는 중
    case waiting                   // 차가 받기를 기다려요
    case accepted                  // 다음 주행부터 써요
    case rejected(SongRejectReason)
    case failed(String)            // 올리기 실패(413·415 등), 화면 문구
    case removed                   // 지움(순번 비교용으로 남긴다)
}

struct SongRow: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var durationS: Double?
    var state: SongState
    var candidate = false
    /// 이 곡에 적용한 마지막 `song_status`의 `seq`(큰 `seq`가 이긴다, G19).
    var lastSeq: Int64 = 0
    var addedAt: Date
    /// 지운 시각. 지움 표시를 7일 뒤에 지우는 기준이다(추가 시각이 아니다).
    var removedAt: Date?
    /// 내가 녹음한 파일(Application Support/songs)이 있으면 true. 다른 폰이 올린 곡은 없다.
    var hasFile = false
}

struct FeedingRecord: Codable, Equatable, Identifiable {
    enum Status: String, Codable { case sending, sent, reflected, expired, failed }
    var id: String          // 메시지 id
    var at: Date            // 누른 시각(고치지 않는다, G33)
    var fixedAt: Date?      // 중계가 고쳐 저장한 값
    var sentAt: Date?
    var status: Status = .sending
}

struct FeedingState: Codable, Equatable {
    var lastStart: Date?
    var windowStartExpected: Date?
    var seq: Int64 = 0
}

struct StoredReport: Codable, Equatable, Identifiable {
    var id: String { body.sessionId }
    var messageId: String
    var seq: Int64
    var receivedAt: Date
    var body: ReportSummaryBody
}

struct StoredAlert: Codable, Equatable, Identifiable {
    var id: String          // 메시지 id
    var seq: Int64
    var receivedAt: Date
    var body: HandoffAlertBody
}

struct OutboxItem: Codable, Equatable, Identifiable {
    var id: String          // 메시지 봉투 id(다시 보내도 같다)
    var type: String
    var body: JSONValue
    var ts: String
    /// 노래면 올릴 곡 id. 파일 `PUT`이 끝나면 `uploaded`가 참이 된다(7.5 두 단계).
    var songId: String?
    var uploaded = false
    var createdAt: Date
    var attempts = 0
    var nextTryAt: Date
}

/// 기기별로 마지막에 적용한 연결 변경(`paired`·`unpaired`)의 seq와 삭제 표시(계약 8.4의 `phone_link_mark`와 같은 뜻).
/// `GET /v1/devices` 목록을 합칠 때 `snapshot_seq`보다 새로운 변경을 지키는 데 쓴다.
struct DeviceMark: Codable, Equatable {
    var seq: Int64
    var removed: Bool
    var updatedAt: Date
}

/// 폰이 저장하는 것 전부(Keychain의 토큰은 뺀다).
struct PhoneState: Codable, Equatable {
    var introSeen = false
    var carId: String?
    /// 본문을 저장한 메시지 id(처리 여부, 7일). 5.6의 두 기록 중 하나.
    var processed: [String: Date] = [:]
    /// 띄운 메시지 id(표시 여부, 7일).
    var shown: [String: Date] = [:]
    var outbox: [OutboxItem] = []
    var reports: [StoredReport] = []
    var alerts: [StoredAlert] = []
    var songs: [SongRow] = []
    var selectedSongId: String?
    var feeding = FeedingState()
    var feedingLog: [FeedingRecord] = []
    /// `GET /v1/devices` 마지막 결과(오프라인에서 보이는 값).
    var devices: [DeviceInfo] = []
    var deviceMarks: [String: DeviceMark] = [:]
    /// 마지막으로 적용한 `GET /v1/devices`의 `snapshot_seq`. 목록과 함께 저장한다. 이보다 작은 응답과 이 이하 seq의 연결 이벤트는 낡은 것이다.
    var devicesSnapshotSeq: Int64 = 0
    var handoffAlerts: Bool?
    /// G39
    var lastReceivedAt: Date?
    var needsStateRequest = false
    var lastStateRequestAt: Date?
}

protocol StateStore {
    /// 복원하지 못했는가(파일은 있는데 읽지 못함). 그러면 그 실행에서는 녹음 파일 정리를 하지 않는다.
    var restoreFailed: Bool { get }
    /// 원본을 보존하지 못해 이 실행에서 저장을 거부하는가.
    var writesRefused: Bool { get }
    func load() -> PhoneState
    /// 저장하지 못하면 던진다. 부르는 쪽은 성공했을 때만 메모리 상태를 바꾸고 ack한다.
    func save(_ s: PhoneState) throws
}

struct StoreError: Error {}

final class MemoryStateStore: StateStore {
    var value: PhoneState
    /// 시험에서 저장 실패를 흉내 낸다.
    var failing = false
    var restoreFailed = false
    var writesRefused = false
    init(_ value: PhoneState = PhoneState()) { self.value = value }
    func load() -> PhoneState { value }
    func save(_ s: PhoneState) throws {
        if failing { throw StoreError() }
        value = s
    }
}

final class FileStateStore: StateStore {
    private let url: URL
    private(set) var restoreFailed = false
    /// 원본이나 녹음 폴더를 옆에 보존하지 못했을 때만 켠다. 그러면 이 실행에서는 아무것도 쓰지 않는다(다음 실행이 정상 복원으로 보고 녹음을 지우지 않게).
    private var refuseWrites = false
    var writesRefused: Bool { refuseWrites }
    private let clock: () -> Date

    init(directory: URL = FileStateStore.supportDirectory, clock: @escaping () -> Date = Date.init) {
        self.clock = clock
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        url = directory.appendingPathComponent("state.json")
    }

    static var supportDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    private static func coders() -> (JSONEncoder, JSONDecoder) {
        let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601
        let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601
        return (e, d)
    }

    /// 파일이 없으면 첫 실행이라 빈 상태다. 파일이 있는데 읽지 못하면 빈 상태로 덮어쓰지 않는다:
    /// 원본을 `state.json.broken-<시각>`으로 옆에 남기고, 녹음 폴더도 옆으로 옮겨 다음 실행의 정리가 지우지 못하게 한다.
    func load() -> PhoneState {
        guard FileManager.default.fileExists(atPath: url.path) else { return PhoneState() }
        let data = try? Data(contentsOf: url)
        if let data, let s = try? Self.coders().1.decode(PhoneState.self, from: data) { return s }
        restoreFailed = true
        let dir = url.deletingLastPathComponent()
        let stamp = String(Int(clock().timeIntervalSince1970))
        guard let data, (try? data.write(to: dir.appendingPathComponent("state.json.broken-\(stamp)"))) != nil else {
            refuseWrites = true   // 읽지 못했거나 보존하지 못했다. 원본을 건드리지 않는다.
            return PhoneState()
        }
        let songs = dir.appendingPathComponent("songs", isDirectory: true)
        if FileManager.default.fileExists(atPath: songs.path) {
            do { try FileManager.default.moveItem(at: songs, to: dir.appendingPathComponent("songs.broken-\(stamp)")) }
            catch { refuseWrites = true }   // 녹음 폴더를 보존하지 못하면 빈 상태를 저장하지 않는다
        }
        return PhoneState()
    }

    /// 임시 파일에 쓴 뒤 바꿔치기(`.atomic`)라 도중에 죽어도 옛 파일이나 새 파일 중 하나만 남는다.
    func save(_ s: PhoneState) throws {
        if refuseWrites { throw StoreError() }
        let data = try Self.coders().0.encode(s)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

/// 녹음 파일 폴더(Application Support/songs). 곡을 지울 때 함께 지운다(7.4).
enum SongFiles {
    static var directory: URL {
        let d = FileStateStore.supportDirectory.appendingPathComponent("songs", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }
    static func url(for songId: String) -> URL { directory.appendingPathComponent("\(songId).m4a") }
    static func remove(_ songId: String) { try? FileManager.default.removeItem(at: url(for: songId)) }
    static func removeAll() { try? FileManager.default.removeItem(at: directory) }

    /// 복원 실패 때 남긴 사본(`state.json.broken-*`, `songs.broken-*`)을 지운다. 사용자가 기록 지우기·연결 끊기를 한 때만 부른다.
    static func removeBrokenCopies(in dir: URL = FileStateStore.supportDirectory) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        for n in names where n.hasPrefix("state.json.broken-") || n.hasPrefix("songs.broken-") {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(n))
        }
    }
}

// MARK: 옛 파일 읽기
// 합성 Decodable은 선언의 기본값을 쓰지 않고, 키가 없으면 실패한다. 필드를 더해도 옛 `state.json`이 읽히도록
// 기본값이 있는 필드는 모두 decodeIfPresent로 읽는다. (필드를 더할 때 여기에도 더한다.)

extension SongRow {
    enum CodingKeys: String, CodingKey { case id, name, durationS, state, candidate, lastSeq, addedAt, removedAt, hasFile }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        durationS = try c.decodeIfPresent(Double.self, forKey: .durationS)
        state = try c.decode(SongState.self, forKey: .state)
        candidate = try c.decodeIfPresent(Bool.self, forKey: .candidate) ?? false
        lastSeq = try c.decodeIfPresent(Int64.self, forKey: .lastSeq) ?? 0
        addedAt = try c.decode(Date.self, forKey: .addedAt)
        removedAt = try c.decodeIfPresent(Date.self, forKey: .removedAt)
        hasFile = try c.decodeIfPresent(Bool.self, forKey: .hasFile) ?? false
    }
}

extension FeedingRecord {
    enum CodingKeys: String, CodingKey { case id, at, fixedAt, sentAt, status }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        at = try c.decode(Date.self, forKey: .at)
        fixedAt = try c.decodeIfPresent(Date.self, forKey: .fixedAt)
        sentAt = try c.decodeIfPresent(Date.self, forKey: .sentAt)
        status = try c.decodeIfPresent(Status.self, forKey: .status) ?? .sending
    }
}

extension FeedingState {
    enum CodingKeys: String, CodingKey { case lastStart, windowStartExpected, seq }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        lastStart = try c.decodeIfPresent(Date.self, forKey: .lastStart)
        windowStartExpected = try c.decodeIfPresent(Date.self, forKey: .windowStartExpected)
        seq = try c.decodeIfPresent(Int64.self, forKey: .seq) ?? 0
    }
}

extension OutboxItem {
    enum CodingKeys: String, CodingKey { case id, type, body, ts, songId, uploaded, createdAt, attempts, nextTryAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        type = try c.decode(String.self, forKey: .type)
        body = try c.decode(JSONValue.self, forKey: .body)
        ts = try c.decode(String.self, forKey: .ts)
        songId = try c.decodeIfPresent(String.self, forKey: .songId)
        uploaded = try c.decodeIfPresent(Bool.self, forKey: .uploaded) ?? false
        createdAt = try c.decode(Date.self, forKey: .createdAt)
        attempts = try c.decodeIfPresent(Int.self, forKey: .attempts) ?? 0
        nextTryAt = try c.decode(Date.self, forKey: .nextTryAt)
    }
}

extension PhoneState {
    enum CodingKeys: String, CodingKey {
        case introSeen, carId, processed, shown, outbox, reports, alerts, songs, selectedSongId, feeding, feedingLog
        case devices, deviceMarks, devicesSnapshotSeq, handoffAlerts, lastReceivedAt, needsStateRequest, lastStateRequestAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        introSeen = try c.decodeIfPresent(Bool.self, forKey: .introSeen) ?? false
        carId = try c.decodeIfPresent(String.self, forKey: .carId)
        processed = try c.decodeIfPresent([String: Date].self, forKey: .processed) ?? [:]
        shown = try c.decodeIfPresent([String: Date].self, forKey: .shown) ?? [:]
        outbox = try c.decodeIfPresent([OutboxItem].self, forKey: .outbox) ?? []
        reports = try c.decodeIfPresent([StoredReport].self, forKey: .reports) ?? []
        alerts = try c.decodeIfPresent([StoredAlert].self, forKey: .alerts) ?? []
        songs = try c.decodeIfPresent([SongRow].self, forKey: .songs) ?? []
        selectedSongId = try c.decodeIfPresent(String.self, forKey: .selectedSongId)
        feeding = try c.decodeIfPresent(FeedingState.self, forKey: .feeding) ?? FeedingState()
        feedingLog = try c.decodeIfPresent([FeedingRecord].self, forKey: .feedingLog) ?? []
        devices = try c.decodeIfPresent([DeviceInfo].self, forKey: .devices) ?? []
        deviceMarks = try c.decodeIfPresent([String: DeviceMark].self, forKey: .deviceMarks) ?? [:]
        devicesSnapshotSeq = try c.decodeIfPresent(Int64.self, forKey: .devicesSnapshotSeq) ?? 0
        handoffAlerts = try c.decodeIfPresent(Bool.self, forKey: .handoffAlerts)
        lastReceivedAt = try c.decodeIfPresent(Date.self, forKey: .lastReceivedAt)
        needsStateRequest = try c.decodeIfPresent(Bool.self, forKey: .needsStateRequest) ?? false
        lastStateRequestAt = try c.decodeIfPresent(Date.self, forKey: .lastStateRequestAt)
    }
}
