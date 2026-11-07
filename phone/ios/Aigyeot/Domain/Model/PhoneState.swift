import Foundation

// MARK: 폰 상태(`state.json`, 계약 7.4)

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

/// 보낼 함 항목의 종류(P2: 종류마다 필요한 칸만 둔다). 노래 올리기만 파일 PUT 표시를, 돌봄 요약만 '보내 봄'을 갖는다.
enum OutboxPayload: Equatable {
    /// 한 번에 보내는 메시지(feeding_started·song_removed·song_selected 등).
    case message(type: String, body: JSONValue)
    /// 노래 올리기(7.5 두 단계): 파일 `PUT`이 끝나면 `uploaded`가 참이 되고 그다음 `song_added`를 보낸다.
    case song(songId: String, body: JSONValue, uploaded: Bool)
    /// 돌봄 요약. `tried`: 보내기 직전에 저장하는 '보내 봄'(U57). 보내 본 요약은 바꾸지 않는다.
    case careSummary(body: JSONValue, tried: Bool)
}

struct OutboxItem: Equatable, Identifiable {
    var id: String          // 메시지 봉투 id(다시 보내도 같다)
    var payload: OutboxPayload
    var ts: String
    var createdAt: Date
    var attempts = 0
    var nextTryAt: Date
    /// 지금 기다림이 망 실패(응답 없음)에서 왔다. 인터넷 복구·'다시 시도'·앞으로 오기 때 당긴다(7.5). 중계가 준 기다림은 거짓
    var networkWait = false

    /// 계약의 메시지 type·body(보낼 때 봉투에 싣는다).
    var type: String {
        switch payload {
        case .message(let t, _): t
        case .song: MessageType.songAdded
        case .careSummary: MessageType.careSummary
        }
    }
    var body: JSONValue {
        switch payload { case .message(_, let b), .song(_, let b, _), .careSummary(let b, _): b }
    }
    /// 노래면 올릴 곡 id.
    var songId: String? { if case .song(let id, _, _) = payload { id } else { nil } }
    var uploaded: Bool { if case .song(_, _, let u) = payload { u } else { false } }
    var sentOnce: Bool { if case .careSummary(_, let t) = payload { t } else { false } }

    /// 노래 파일을 올렸다(또는 다시 올려야 한다). 노래가 아니면 바뀌지 않는다.
    mutating func markUploaded(_ v: Bool) {
        if case .song(let id, let b, _) = payload { payload = .song(songId: id, body: b, uploaded: v) }
    }
    /// 요약의 '보내 봄'(U57). 요약이 아니면 바뀌지 않는다.
    mutating func markTried(_ v: Bool) {
        if case .careSummary(let b, _) = payload { payload = .careSummary(body: b, tried: v) }
    }

    /// 저장 파일·계약의 칸(type·body·songId·uploaded·sentOnce)에서 종류를 정한다.
    init(id: String, type: String, body: JSONValue, ts: String, songId: String? = nil, uploaded: Bool = false,
         createdAt: Date, attempts: Int = 0, nextTryAt: Date, sentOnce: Bool = false, networkWait: Bool = false) {
        self.id = id
        if let songId, type == MessageType.songAdded { payload = .song(songId: songId, body: body, uploaded: uploaded) }
        else if type == MessageType.careSummary { payload = .careSummary(body: body, tried: sentOnce) }
        else { payload = .message(type: type, body: body) }
        self.ts = ts
        self.createdAt = createdAt
        self.attempts = attempts
        self.nextTryAt = nextTryAt
        self.networkWait = networkWait
    }
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
    /// 차로 보낸 마지막 수유 시작 시각(epoch ms). 보낼 함과 같은 저장에서 올린다(phone-care-plan U3·R5).
    var lastSentFeedingAt: Int64?
    /// 마지막으로 보낸 `care_summary` body에서 `as_of`를 뺀 것. 보낼 함과 같은 저장에서 바꾼다(phone-care-plan R19).
    var lastSentCareSummary: JSONValue?
    /// 마지막으로 보낸 요약의 `as_of`와 지금까지 보낸 가장 늦은 `as_of`(epoch ms, 초 단위로 내림). 같은 저장에서 바꾼다.
    /// 시계가 뒤로 가 차가 무시했을 수 있는 요약을 다시 보내는 데 쓴다.
    var lastSentCareAsOf: Int64?
    var maxSentCareAsOf: Int64?
    /// '기록 지우기'를 누른 순간의 돌봄 기록 id. 그 돌봄 기록 지우기를 아직 마치지 못했다(R10). 마칠 때까지 요약을 보내지 않는다.
    /// id로 한정해, 그 뒤 저장한 새 기록은 지우지 않는다.
    var clearingCareIds: [String]?
    /// 버린 메시지 type 중 사용자가 아직 띠를 닫지 않은 것(U54). 보낼 함에서 뺄 때 같은 저장에 넣고 '닫기'에서 지운다.
    var unseenDrops: [String] = []
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

/// 저장 형식은 종류를 나누기 전과 같은 키다(type·body·songId·uploaded·sentOnce).
extension OutboxItem: Codable {
    enum CodingKeys: String, CodingKey { case id, type, body, ts, songId, uploaded, createdAt, attempts, nextTryAt, sentOnce, networkWait }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try c.decode(String.self, forKey: .id),
                  type: try c.decode(String.self, forKey: .type),
                  body: try c.decode(JSONValue.self, forKey: .body),
                  ts: try c.decode(String.self, forKey: .ts),
                  songId: try c.decodeIfPresent(String.self, forKey: .songId),
                  uploaded: try c.decodeIfPresent(Bool.self, forKey: .uploaded) ?? false,
                  createdAt: try c.decode(Date.self, forKey: .createdAt),
                  attempts: try c.decodeIfPresent(Int.self, forKey: .attempts) ?? 0,
                  nextTryAt: try c.decode(Date.self, forKey: .nextTryAt),
                  sentOnce: try c.decodeIfPresent(Bool.self, forKey: .sentOnce) ?? false,
                  networkWait: try c.decodeIfPresent(Bool.self, forKey: .networkWait) ?? false)
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(type, forKey: .type)
        try c.encode(body, forKey: .body)
        try c.encode(ts, forKey: .ts)
        try c.encodeIfPresent(songId, forKey: .songId)
        try c.encode(uploaded, forKey: .uploaded)
        try c.encode(createdAt, forKey: .createdAt)
        try c.encode(attempts, forKey: .attempts)
        try c.encode(nextTryAt, forKey: .nextTryAt)
        try c.encode(sentOnce, forKey: .sentOnce)
        try c.encode(networkWait, forKey: .networkWait)
    }
}

extension PhoneState {
    enum CodingKeys: String, CodingKey {
        case introSeen, carId, processed, shown, outbox, reports, alerts, songs, selectedSongId, feeding, feedingLog
        case devices, deviceMarks, devicesSnapshotSeq, handoffAlerts, lastReceivedAt, needsStateRequest, lastStateRequestAt
        case lastSentFeedingAt, lastSentCareSummary, lastSentCareAsOf, maxSentCareAsOf, clearingCareIds, unseenDrops
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
        lastSentFeedingAt = try c.decodeIfPresent(Int64.self, forKey: .lastSentFeedingAt)
        lastSentCareSummary = try c.decodeIfPresent(JSONValue.self, forKey: .lastSentCareSummary)
        lastSentCareAsOf = try c.decodeIfPresent(Int64.self, forKey: .lastSentCareAsOf)
        maxSentCareAsOf = try c.decodeIfPresent(Int64.self, forKey: .maxSentCareAsOf)
        clearingCareIds = try c.decodeIfPresent([String].self, forKey: .clearingCareIds)
        unseenDrops = try c.decodeIfPresent([String].self, forKey: .unseenDrops) ?? []
    }
}
