import Foundation

// 돌봄 기록(계획 phone-care-plan 6장). 폰 안에만 있고 차로 가는 것은 수유 시작 시각뿐이다(R5).
// JSON 키·값은 안드로이드와 글자까지 같다(13장). 시각은 epoch ms(UTC)다.

enum CareKind: String, Codable { case feed = "FEED", sleep = "SLEEP", diaper = "DIAPER", note = "NOTE" }

enum FeedSide: String, Codable, CaseIterable {
    case left = "LEFT", right = "RIGHT", both = "BOTH", bottle = "BOTTLE"
    var label: String {
        switch self { case .left: "왼쪽"; case .right: "오른쪽"; case .both: "양쪽"; case .bottle: "젖병" }
    }
}

enum DiaperKind: String, Codable, CaseIterable { case wet = "WET", dirty = "DIRTY", both = "BOTH", dry = "DRY" }

enum CareSource: String, Codable { case phone = "PHONE", imported = "IMPORT" }

struct CareEvent: Equatable, Identifiable {
    var id: String
    var kind: CareKind
    var startAt: Int64
    var endAt: Int64? = nil
    /// 끝 시각을 모름(옮긴 기록, 시작만 적은 기록). 진행 중이 아니다.
    var endUnknown = false
    /// 기록한 때의 시간대 ID. 화면은 이 시간대로 보인다(R13).
    var zone: String
    var feedSide: FeedSide? = nil
    var amountMl: Int? = nil
    var diaper: DiaperKind? = nil
    var note: String? = nil
    var source: CareSource = .phone
    var createdAt: Int64
    var updatedAt: Int64
    /// 지운 시각. 표시만 하고 30일 뒤 실제로 지운다(R8).
    var deletedAt: Int64? = nil

    var hasDuration: Bool { kind == .feed || kind == .sleep }
    var inProgress: Bool { hasDuration && endAt == nil && !endUnknown && deletedAt == nil }
    var timeZone: TimeZone { TimeZone(identifier: zone) ?? .current }
}

struct CareFile: Equatable {
    var version = 1
    var events: [CareEvent] = []
    var childNickname: String? = nil
    var sleepDiaperEnabled = false
    /// 지금 수유 기록(`feedingLog`)을 옮겼는지(R11).
    var migratedFeedings = false
}

// MARK: JSON(13장: 쓸 때 null 키도 쓴다. 없는 키는 기본값, 모르는 키는 무시)

extension CareEvent: Codable {
    enum CodingKeys: String, CodingKey {
        case id, kind, startAt, endAt, endUnknown, zone, feedSide, amountMl, diaper, note, source, createdAt, updatedAt, deletedAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        kind = try c.decode(CareKind.self, forKey: .kind)
        startAt = try c.decode(Int64.self, forKey: .startAt)
        endAt = try c.decodeIfPresent(Int64.self, forKey: .endAt)
        endUnknown = try c.decodeIfPresent(Bool.self, forKey: .endUnknown) ?? false
        // 없는 키는 기본값으로 읽는다(6장). 하나가 빠졌다고 파일 전체를 깨진 것으로 보지 않게. 안드로이드는 source만 기본값이 있고 zone·createdAt·updatedAt은 필수다(보고함)
        zone = try c.decodeIfPresent(String.self, forKey: .zone) ?? TimeZone.current.identifier
        feedSide = try c.decodeIfPresent(FeedSide.self, forKey: .feedSide)
        amountMl = try c.decodeIfPresent(Int.self, forKey: .amountMl)
        diaper = try c.decodeIfPresent(DiaperKind.self, forKey: .diaper)
        note = try c.decodeIfPresent(String.self, forKey: .note)
        source = try c.decodeIfPresent(CareSource.self, forKey: .source) ?? .phone
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? startAt
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? createdAt
        deletedAt = try c.decodeIfPresent(Int64.self, forKey: .deletedAt)
    }

    /// Optional을 `encode`로 넘기면 nil이 `null`로 쓰인다(`encodeIfPresent`는 키를 뺀다).
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(kind, forKey: .kind)
        try c.encode(startAt, forKey: .startAt)
        try c.encode(endAt, forKey: .endAt)
        try c.encode(endUnknown, forKey: .endUnknown)
        try c.encode(zone, forKey: .zone)
        try c.encode(feedSide, forKey: .feedSide)
        try c.encode(amountMl, forKey: .amountMl)
        try c.encode(diaper, forKey: .diaper)
        try c.encode(note, forKey: .note)
        try c.encode(source, forKey: .source)
        try c.encode(createdAt, forKey: .createdAt)
        try c.encode(updatedAt, forKey: .updatedAt)
        try c.encode(deletedAt, forKey: .deletedAt)
    }
}

extension CareFile: Codable {
    enum CodingKeys: String, CodingKey { case version, events, childNickname, sleepDiaperEnabled, migratedFeedings }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 1
        events = try c.decodeIfPresent([CareEvent].self, forKey: .events) ?? []
        childNickname = try c.decodeIfPresent(String.self, forKey: .childNickname)
        sleepDiaperEnabled = try c.decodeIfPresent(Bool.self, forKey: .sleepDiaperEnabled) ?? false
        migratedFeedings = try c.decodeIfPresent(Bool.self, forKey: .migratedFeedings) ?? false
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(version, forKey: .version)
        try c.encode(events, forKey: .events)
        try c.encode(childNickname, forKey: .childNickname)
        try c.encode(sleepDiaperEnabled, forKey: .sleepDiaperEnabled)
        try c.encode(migratedFeedings, forKey: .migratedFeedings)
    }
}

enum CareCoding {
    static func decode(_ data: Data) throws -> CareFile { try JSONDecoder().decode(CareFile.self, from: data) }
    static func encode(_ file: CareFile) throws -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return try e.encode(file)
    }
}

// MARK: 규칙 결과

enum CareProblem: Equatable {
    case future, endBeforeStart, tooLong, amount, noteTooLong
    /// 같은 종류의 진행 중 기록이 이미 있다(R1). 화면은 이 경우를 만들지 않는다.
    case inProgressExists

    /// 7장 문구(U17). 메모는 입력칸이 200자에서 더 받지 않아 문구가 없다.
    func message(for kind: CareKind) -> String? {
        switch self {
        case .future: return "앞으로의 시각은 적을 수 없어요"
        case .endBeforeStart: return "끝 시각이 시작보다 빨라요"
        case .tooLong: return kind == .sleep ? "잠은 16시간까지 적을 수 있어요" : "수유는 3시간까지 적을 수 있어요"
        case .amount: return "0~400ml로 적어 주세요"
        case .noteTooLong, .inProgressExists: return nil
        }
    }
}

enum CareSaveResult: Equatable {
    case saved
    /// 같은 종류와 시간이 겹친다(R4). '그래도 저장'이면 `allowOverlap`으로 다시 부른다.
    case overlap
    case problem(CareProblem)
    /// 저장하지 못했다. 문구는 위쪽 띠가 보인다.
    case notSaved

    func message(for kind: CareKind) -> String? {
        switch self {
        case .overlap: return kind == .sleep ? "다른 잠과 시간이 겹쳐요" : "다른 수유와 시간이 겹쳐요"
        case .problem(let p): return p.message(for: kind)
        case .saved, .notSaved: return nil
        }
    }
}

struct CareDayStat: Equatable {
    var day: String          // yyyy-MM-dd(기기 시간대)
    var feeds: Int
    var sleepMs: Int64?      // 잠·기저귀가 꺼져 있으면 nil(R16)
    /// 끝난 잠이 그날에 걸친 시간이 0보다 큰가(U27·U31 평균의 분모).
    var sleepRecorded = false
    var diapers: Int?
    var carEpisodes: Int
}

struct CarePattern: Equatable {
    var days: [CareDayStat]          // 오래된 날 → 오늘, 7개
    var feedIntervalMedianMs: Int64? // 간격이 5개 미만이면 nil(R14)

    // U27: 평균은 그 종류 기록이 있는 날만 나눠 센다. 그런 날이 없으면 nil("기록 없음").
    var feedDays: Int { days.filter { $0.feeds > 0 }.count }
    var sleepDays: Int { days.filter(\.sleepRecorded).count }
    var diaperDays: Int { days.filter { ($0.diapers ?? 0) > 0 }.count }
    var feedsPerDay: Double? { feedDays == 0 ? nil : Double(days.map(\.feeds).reduce(0, +)) / Double(feedDays) }
    var sleepMsPerDay: Int64? { sleepDays == 0 ? nil : days.compactMap(\.sleepMs).reduce(0, +) / Int64(sleepDays) }
    var diapersPerDay: Double? { diaperDays == 0 ? nil : Double(days.compactMap(\.diapers).reduce(0, +)) / Double(diaperDays) }
    var carCries: Int { days.map(\.carEpisodes).reduce(0, +) }
}

/// 리포트에서 만든 읽기 전용 줄(R17). 저장하지 않는다.
struct CarRow: Equatable, Identifiable {
    var id: String
    var sessionId: String
    var at: Date
    var text: String
}

/// U61: 기록에서 온 값이 하나도 없는 요약(오늘 수유 0회, 잠·기저귀 칸이 비었거나 0). 메모만·옛 기록만 있는 폰의 요약도 그렇다(안드로이드 `empty`와 같다).
extension CareSummaryBody {
    var isEmpty: Bool {
        todayFeedCount == 0 && lastSleepStartAt == nil && lastSleepEndAt == nil && sleepingSince == nil && (todaySleepMin ?? 0) == 0
            && (nightWakeups ?? 0) == 0 && lastDiaperAt == nil && (todayDiaperCount ?? 0) == 0
    }
}
