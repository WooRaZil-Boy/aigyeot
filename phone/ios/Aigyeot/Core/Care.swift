import Foundation

// 돌봄 기록(계획 phone-care-plan 6장). 폰 안에만 있고 차로 가는 것은 수유 시작 시각뿐이다(R5).
// JSON 키·값은 안드로이드와 글자까지 같다(13장). 시각은 epoch ms(UTC)다.

enum CareTime {
    static func ms(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
    static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(ms) / 1000) }
}

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
        zone = try c.decode(String.self, forKey: .zone)
        feedSide = try c.decodeIfPresent(FeedSide.self, forKey: .feedSide)
        amountMl = try c.decodeIfPresent(Int.self, forKey: .amountMl)
        diaper = try c.decodeIfPresent(DiaperKind.self, forKey: .diaper)
        note = try c.decodeIfPresent(String.self, forKey: .note)
        source = try c.decode(CareSource.self, forKey: .source)
        createdAt = try c.decode(Int64.self, forKey: .createdAt)
        updatedAt = try c.decode(Int64.self, forKey: .updatedAt)
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

// MARK: 저장(state.json과 같은 방식: 앱 전용 폴더, 임시 파일 → 바꿔치기)

protocol CareStore {
    /// 깨진 원본을 보존하지 못해 이 실행에서 저장을 거부하는가.
    var writesRefused: Bool { get }
    func load() -> CareFile
    /// 저장하지 못하면 던진다. 부르는 쪽은 성공했을 때만 메모리 값을 바꾼다.
    func save(_ c: CareFile) throws
}

final class MemoryCareStore: CareStore {
    var value: CareFile
    var failing = false
    var writesRefused = false
    private(set) var saves = 0
    init(_ value: CareFile = CareFile()) { self.value = value }
    func load() -> CareFile { value }
    func save(_ c: CareFile) throws {
        if failing { throw StoreError() }
        value = c
        saves += 1
    }
}

final class FileCareStore: CareStore {
    private let url: URL
    private var refuseWrites = false
    var writesRefused: Bool { refuseWrites }
    private let clock: () -> Date

    init(directory: URL = FileStateStore.supportDirectory, clock: @escaping () -> Date = Date.init) {
        self.clock = clock
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        url = directory.appendingPathComponent("care.json")
    }

    /// 파일이 없으면 빈 기록이다. 읽지 못하면 원본을 `care.json.broken-<시각>`으로 옆에 남기고 빈 기록으로 시작한다.
    /// 남기지 못하면 이 실행에서는 쓰지 않는다(원본을 빈 기록으로 덮지 않게).
    func load() -> CareFile {
        guard FileManager.default.fileExists(atPath: url.path) else { return CareFile() }
        let data = try? Data(contentsOf: url)
        if let data, let file = try? CareCoding.decode(data) { return file }
        let copy = url.deletingLastPathComponent().appendingPathComponent("care.json.broken-\(Int(clock().timeIntervalSince1970))")
        if data == nil || (try? data!.write(to: copy)) == nil { refuseWrites = true }
        return CareFile()
    }

    func save(_ c: CareFile) throws {
        if refuseWrites { throw StoreError() }
        try CareCoding.encode(c).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// 깨진 사본을 지운다. '이 휴대폰의 기록 지우기'에서만 부른다(연결 끊기는 돌봄 기록을 남긴다, R9).
    static func removeBrokenCopies(in dir: URL = FileStateStore.supportDirectory) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        for n in names where n.hasPrefix("care.json.broken-") {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(n))
        }
    }
}

// MARK: 규칙(6장 R1~R17)

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

enum CareRules {
    static let futureSlackMs: Int64 = 60_000
    static let feedMaxMs: Int64 = 3 * 3_600_000
    static let sleepMaxMs: Int64 = 16 * 3_600_000
    static let sendWindowMs: Int64 = 24 * 3_600_000
    static let purgeAfterMs: Int64 = 30 * 86_400_000
    static let gapMaxMs: Int64 = 10 * 3_600_000
    static let minGaps = 5
    static let noteMax = 200
    static let amountRange = 0...400

    static func live(_ events: [CareEvent]) -> [CareEvent] { events.filter { $0.deletedAt == nil } }

    static func inProgress(_ kind: CareKind, in events: [CareEvent]) -> CareEvent? {
        events.first { $0.kind == kind && $0.inProgress }
    }

    static func latestFeed(_ events: [CareEvent]) -> CareEvent? {
        live(events).filter { $0.kind == .feed }.max { $0.startAt < $1.startAt }
    }

    static func maxMs(_ kind: CareKind) -> Int64? {
        switch kind { case .feed: feedMaxMs; case .sleep: sleepMaxMs; default: nil }
    }

    /// R3 시각 범위와 입력 범위. 문제가 없으면 nil.
    static func problem(_ e: CareEvent, now: Int64) -> CareProblem? {
        if e.startAt > now + futureSlackMs { return .future }
        if let end = e.endAt {
            if end > now + futureSlackMs { return .future }
            if end < e.startAt { return .endBeforeStart }
            if let max = maxMs(e.kind), end - e.startAt > max { return .tooLong }
        }
        if let ml = e.amountMl, !amountRange.contains(ml) { return .amount }
        if let n = e.note, n.unicodeScalars.count > noteMax { return .noteTooLong }
        return nil
    }

    /// 진행 중 기록이 최대 길이를 넘었다(타이머가 끝 시각을 묻는다, 7장).
    static func isOverLimit(_ e: CareEvent, now: Int64) -> Bool {
        guard e.inProgress, let max = maxMs(e.kind) else { return false }
        return now - e.startAt > max
    }

    /// R4·U16: 수유·잠만 본다. 진행 중은 [시작, 지금), 끝 모름 기록은 시작 한 점이다.
    /// 구간끼리는 a.s < b.e && b.s < a.e, 점 p와 구간 [s, e)는 s ≤ p < e, 두 점은 같은 시각이면 겹친다.
    static func overlaps(_ e: CareEvent, in events: [CareEvent], now: Int64) -> Bool {
        guard e.hasDuration else { return false }
        /// nil이면 점(끝 모름).
        func end(_ x: CareEvent) -> Int64? { x.endAt ?? (x.inProgress ? now : nil) }
        return live(events).contains { o in
            guard o.id != e.id, o.kind == e.kind else { return false }
            switch (end(e), end(o)) {
            case let (ae?, be?): return e.startAt < be && o.startAt < ae
            case let (nil, be?): return o.startAt <= e.startAt && e.startAt < be
            case let (ae?, nil): return e.startAt <= o.startAt && o.startAt < ae
            case (nil, nil): return e.startAt == o.startAt
            }
        }
    }

    /// R8: 지운 지 30일이 지난 기록을 실제로 지운다.
    static func purged(_ events: [CareEvent], now: Int64) -> [CareEvent] {
        events.filter { e in e.deletedAt.map { $0 >= now - purgeAfterMs } ?? true }
    }

    /// R11: 지금 수유 기록을 FEED로 옮긴다. 같은 id는 건너뛴다.
    static func migrated(_ file: CareFile, from log: [FeedingRecord], zone: String, now: Int64) -> CareFile {
        var f = file
        let have = Set(f.events.map(\.id))
        for r in log where !have.contains(r.id) {
            let at = CareTime.ms(r.at)
            f.events.append(CareEvent(id: r.id, kind: .feed, startAt: at, endUnknown: true, zone: zone, createdAt: now, updatedAt: now))
        }
        f.migratedFeedings = true
        return f
    }

    /// 메모 입력칸: 코드 포인트 200개에서 더 받지 않는다(U17·U24).
    static func noteLimited(_ s: String) -> String {
        s.unicodeScalars.count <= noteMax ? s : String(String.UnicodeScalarView(s.unicodeScalars.prefix(noteMax)))
    }

    /// 빠른 버튼(R16, 7장 순서).
    static func quickKinds(sleepDiaper: Bool) -> [CareKind] { sleepDiaper ? [.feed, .note, .sleep, .diaper] : [.feed, .note] }

    /// R15: 경과는 시작 시각으로 계산한다.
    static func elapsedMs(_ e: CareEvent, now: Int64) -> Int64 { max(0, now - e.startAt) }

    // MARK: 날짜(R13)

    private static func calendar(_ zone: TimeZone) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = zone
        return c
    }

    static func dayKey(_ ms: Int64, _ zone: TimeZone) -> String {
        let d = calendar(zone).dateComponents([.year, .month, .day], from: CareTime.date(ms))
        return String(format: "%04d-%02d-%02d", d.year!, d.month!, d.day!)
    }

    /// 그날 0시부터 다음 날 0시 전까지(ms).
    static func dayRange(_ day: String, _ zone: TimeZone) -> (Int64, Int64)? {
        let p = day.split(separator: "-").compactMap { Int($0) }
        let cal = calendar(zone)
        guard p.count == 3, let start = cal.date(from: DateComponents(year: p[0], month: p[1], day: p[2])),
              let end = cal.date(byAdding: .day, value: 1, to: start) else { return nil }
        return (CareTime.ms(start), CareTime.ms(end))
    }

    /// 그날 목록: 그 기록의 시간대로 시작한 날이 이 날인 것(자정을 넘어도 시작한 날).
    static func events(onDay day: String, in events: [CareEvent]) -> [CareEvent] {
        live(events).filter { dayKey($0.startAt, $0.timeZone) == day }.sorted { $0.startAt < $1.startAt }
    }

    /// 그날 잠 합계: 끝난 잠만, 날짜 경계로 나눠 센다.
    static func sleepMs(onDay day: String, in events: [CareEvent], zone: TimeZone) -> Int64 {
        guard let (s, e) = dayRange(day, zone) else { return 0 }
        return live(events).reduce(0) { sum, x in
            guard x.kind == .sleep, !x.endUnknown, let end = x.endAt else { return sum }
            return sum + max(0, min(end, e) - max(x.startAt, s))
        }
    }

    // MARK: 패턴(R14)

    static func pattern(_ events: [CareEvent], reports: [StoredReport], now: Int64, zone: TimeZone, sleepDiaper: Bool) -> CarePattern {
        let cal = calendar(zone)
        let today = cal.startOfDay(for: CareTime.date(now))
        let days = (0..<7).reversed().map { dayKey(CareTime.ms(cal.date(byAdding: .day, value: -$0, to: today)!), zone) }
        let windowStart = CareTime.ms(cal.date(byAdding: .day, value: -6, to: today)!)
        let alive = live(events)
        let feeds = alive.filter { $0.kind == .feed && $0.startAt >= windowStart && $0.startAt <= now }.map(\.startAt).sorted()
        let episodes = reports.flatMap(\.body.timeline).filter { $0.trigger == .cryAlarm }.compactMap { Rfc3339.parse($0.startedAt).map(CareTime.ms) }

        let stats = days.map { day in
            let sleep = sleepDiaper ? sleepMs(onDay: day, in: alive, zone: zone) : nil
            return CareDayStat(day: day,
                        feeds: feeds.filter { dayKey($0, zone) == day }.count,
                        sleepMs: sleep,
                        sleepRecorded: (sleep ?? 0) > 0,   // U31: 끝난 잠이 그날에 걸친 날만
                        diapers: sleepDiaper ? alive.filter { $0.kind == .diaper && dayKey($0.startAt, zone) == day }.count : nil,
                        carEpisodes: episodes.filter { dayKey($0, zone) == day }.count)
        }
        let gaps = zip(feeds, feeds.dropFirst()).map { $1 - $0 }.filter { $0 <= gapMaxMs }.sorted()
        let median: Int64? = gaps.count < minGaps ? nil
            : gaps.count % 2 == 1 ? gaps[gaps.count / 2] : (gaps[gaps.count / 2 - 1] + gaps[gaps.count / 2]) / 2
        return CarePattern(days: stats, feedIntervalMedianMs: median)
    }

    // MARK: 차 기록 줄(R17)

    static func carRows(_ reports: [StoredReport], zone: TimeZone) -> [CarRow] {
        reports.flatMap { r -> [CarRow] in
            let b = r.body
            var rows: [CarRow] = []
            if let start = Rfc3339.parse(b.startedAt) {
                let end = Rfc3339.parse(b.endedAt)
                rows.append(CarRow(id: "drive-\(b.sessionId)", sessionId: b.sessionId, at: start,
                                   text: "주행 · " + CareFormat.clockRange(start, end, zone)))
            }
            for e in b.timeline where e.trigger == .cryAlarm {   // 소리 요청 에피소드는 줄을 만들지 않는다(U19)
                guard let at = Rfc3339.parse(e.startedAt) else { continue }
                // U37: "차에서 울음 · {시각} · {소리} · {결과}", 빈 값은 앞의 " · "와 함께 뺀다(U15).
                let parts = ["차에서 울음", CareFormat.clock(at, zone), e.sound.map { CareText.method($0.method) }, e.result.map(CareText.carResult)].compactMap { $0 }
                rows.append(CarRow(id: e.episodeId, sessionId: b.sessionId, at: at, text: parts.joined(separator: " · ")))
            }
            return rows
        }
    }
}

// MARK: 형식(7장: 기기 언어와 관계없이 한국어)

enum CareFormat {
    private static let ko = Locale(identifier: "ko_KR")

    private static func formatter(_ pattern: String, _ zone: TimeZone) -> DateFormatter {
        let f = DateFormatter()
        f.locale = ko
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = zone
        f.dateFormat = pattern
        return f
    }

    /// "오후 12:52"
    static func clock(_ d: Date, _ zone: TimeZone = .current) -> String { formatter("a h:mm", zone).string(from: d) }

    /// "오후 1:10–1:52". 끝이 같은 오전·오후면 끝의 오전·오후를 뺀다(7장 예). 같은 분(epoch 분)에 끝났으면 시각 하나(U41).
    static func clockRange(_ start: Date, _ end: Date?, _ zone: TimeZone) -> String {
        guard let end, floor(end.timeIntervalSince1970 / 60) != floor(start.timeIntervalSince1970 / 60) else { return clock(start, zone) }
        let samePeriod = formatter("a", zone).string(from: start) == formatter("a", zone).string(from: end)
        return clock(start, zone) + "–" + (samePeriod ? formatter("h:mm", zone).string(from: end) : clock(end, zone))
    }

    /// "11월 5일(수)"
    static func day(_ d: Date, _ zone: TimeZone = .current) -> String { formatter("M월 d일(E)", zone).string(from: d) }

    /// U27: 길이·평균. 0 "0분", 1분 미만 "1분 미만", 그 위는 U20.
    static func length(_ ms: Int64) -> String {
        if ms <= 0 { return "0분" }
        return ms < 60_000 ? "1분 미만" : duration(ms)
    }

    /// U20: '~전'과 진행 중 경과. 1분 미만 "방금", 1~59분 "12분", 60분 이상 "2시간"·"2시간 10분".
    static func duration(_ ms: Int64) -> String {
        let m = max(0, ms) / 60_000
        if m < 1 { return "방금" }
        if m < 60 { return "\(m)분" }
        return m % 60 == 0 ? "\(m / 60)시간" : "\(m / 60)시간 \(m % 60)분"
    }

    /// 타이머·잠금 화면: "12:34", 1시간이 넘으면 "1:02:34".
    static func timer(_ ms: Int64) -> String {
        let s = max(0, ms) / 1000
        let h = s / 3600, m = s % 3600 / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }
}

/// 화면 글자. 계획 7장 표와 7.1 전체 문구표(ID를 주석으로 단다)에 있는 것만 쓴다(U26). 두 앱이 글자까지 같다.
enum CareText {
    // 7장
    static let connectCar = "차와 연결하기"
    static let recordOnly = "먼저 기록만 쓸게요"
    static let noFeedYet = "아직 수유 기록이 없어요"
    static let startFeed = "수유 시작"
    static let carTileUnpaired = "차와 연결하면 수유 기록이 차에도 가요"
    static let nextWindowNote = "차에 정해 둔 간격 기준"
    static let overLimitFeed = "수유가 3시간째예요. 끝내는 걸 잊으셨나요?"
    static let overLimitSleep = "잠이 16시간째예요. 끝내는 걸 잊으셨나요?"   // U36
    static let previousFeedEnded = "앞 수유를 끝냈어요"
    static let previousFeedEndedUnknown = "앞 수유를 끝냈어요 · 끝 시각 모름"
    static let previousSleepEnded = "앞 잠을 끝냈어요"
    static let previousSleepEndedUnknown = "앞 잠을 끝냈어요 · 끝 시각 모름"
    static let guardianNote = "보호자 기록은 이 휴대폰에만 남아요"
    static let reconnect = "다시 연결하기", keepRecording = "기록만 계속 쓸게요"
    static let disconnected = "연결을 끊었어요. 돌봄 기록은 이 휴대폰에 남아 있어요"
    static let pickEnd = "끝 시각 고르기", closeEndUnknown = "끝 시각 모름으로 닫기"
    static let chipNow = "지금", chipCustom = "직접"
    static let chipMinutes = [5, 15, 30, 60]
    static let saveAnyway = "그래도 저장", fix = "고치기"
    static let emptyDay = "이 날 기록이 없어요"
    static let deleted = "지웠어요", undo = "되돌리기"
    static let patternFewIntervals = "기록이 더 쌓이면 보여 드려요"
    static let patternFootnote = "직접 기록한 것만 셉니다. 빠진 기록이 있으면 실제와 다를 수 있어요"
    static let sleepDiaperToggleNote = "다른 육아 앱에 이미 적고 있다면 꺼 두셔도 돼요"
    static let clearConfirm = "이 휴대폰의 리포트·알림·노래·돌봄 기록을 지울까요? 차와 다른 보호자에게 있는 기록은 그대로예요"
    static let disconnectKeepsCare = "돌봄 기록은 이 휴대폰에 남아요"

    // 7.1
    static let tabToday = "오늘", tabLog = "기록", tabCar = "차", tabSettings = "설정"   // tab.*
    static let lastFeed = "마지막 수유"                                                  // today.lastFeed.title
    static let feedRunning = CareActivityText.feeding, sleepRunning = CareActivityText.sleeping   // today.quick.*Running, notify.title.*
    static let quickPast = "지난 기록 적기"                                               // today.quick.past
    static let recentReports = "최근 리포트"                                              // today.recentReports
    static let end = CareActivityText.end, editStart = "시작 시각 수정", deleteRecord = "이 기록 지우기"  // timer.end·editStart·delete, notify.end
    static let rowStart = "시작", rowEnd = "끝", rowTime = "시각", rowSide = "쪽"          // sheet.row.*
    static let rowAmount = "양", rowKind = "종류", rowNote = "메모"                        // sheet.row.*, timer.amount
    static let endUnknown = "끝 시각 모름"                                                // sheet.end.unknown
    static let notePlaceholder = "예: 트림 잘 함"                                         // sheet.note.placeholder
    static let save = "저장", cancel = "취소", delete = "지우기"                           // sheet.save·cancel·delete
    static let segmentDay = "하루", segmentPattern = "패턴"                               // log.segment
    static let today = "오늘", yesterday = "어제"                                         // log.date.*
    static let patternHeader = "지난 7일"                                                // pattern.header
    static let patternFeeds = "하루 수유", patternInterval = "수유 간격", patternSleep = "하루 잠"   // pattern.*
    static let patternDiapers = "하루 기저귀", patternCarCries = "차에서 울음"
    static let guardians = "보호자", nickname = "아이 별명", sleepDiaperToggle = "잠·기저귀도 기록하기"   // settings.*
    static let export = "내 기록 내보내기", clear = "이 휴대폰의 기록 지우기", disconnect = "연결 끊기", about = "정보·라이선스"
    static let nicknamePlaceholder = "예: 콩이"                                           // settings.nickname.placeholder
    static let csvHeader = "kind,start,end,end_unknown,side,amount_ml,diaper,note"       // export.csvHeader

    /// 오늘 탭 머리(U34): "{별명}의 오늘", 별명이 없으면 "오늘".
    static func todayTitle(nickname: String?) -> String { nickname.map { "\($0)의 오늘" } ?? tabToday }

    static func quickLabel(_ k: CareKind) -> String {   // today.quick.*, timer.title.*
        switch k { case .feed: "수유"; case .note: "메모"; case .sleep: "잠"; case .diaper: "기저귀" }
    }

    /// sheet.title.* / sheet.edit.*
    static func sheetTitle(_ k: CareKind, editing: Bool) -> String { quickLabel(k) + (editing ? " 고치기" : " 적기") }

    static func diaperLabel(_ d: DiaperKind) -> String {   // diaper.kind
        switch d { case .wet: "소변"; case .dirty: "대변"; case .both: "둘 다"; case .dry: "깨끗함" }
    }

    /// timer.amount 값 "{n}ml"
    static func ml(_ n: Int) -> String { "\(n)ml" }

    /// 빠른 기록 칩 "5분 전"…"1시간 전".
    static func chip(minutes m: Int) -> String { m < 60 ? "\(m)분 전" : "\(m / 60)시간 전" }

    /// today.running.feed "수유 중 · 12분 · 왼쪽"
    static func feedingNow(_ e: CareEvent, now: Int64) -> String {
        ([feedRunning, CareFormat.duration(CareRules.elapsedMs(e, now: now))] + [e.feedSide?.label].compactMap { $0 }).joined(separator: " · ")
    }

    /// today.running.sleep "자는 중 · 40분"
    static func sleepingNow(_ e: CareEvent, now: Int64) -> String { sleepRunning + " · " + CareFormat.duration(CareRules.elapsedMs(e, now: now)) }

    /// today.lastFeed.value "2시간 10분 전"(1분 미만은 "방금").
    static func ago(_ ms: Int64) -> String { ms < 60_000 ? CareFormat.duration(ms) : CareFormat.duration(ms) + " 전" }

    /// today.lastFeed.sub "오후 12:52 · 왼쪽"(쪽 없으면 시각만)
    static func feedDetail(at: Date, side: FeedSide?, zone: TimeZone) -> String {
        ([CareFormat.clock(at, zone)] + [side?.label].compactMap { $0 }).joined(separator: " · ")
    }

    /// "다음 수유 시간대 · 오후 3:10부터"
    static func nextWindow(_ d: Date) -> String { "다음 수유 시간대 · \(CareFormat.clock(d))부터" }

    /// log.date.*: 날짜 띠·제목 "오늘" / "어제" / "11월 5일(수)"
    static func dayTitle(_ day: String, now: Date, zone: TimeZone) -> String {
        let todayKey = CareRules.dayKey(CareTime.ms(now), zone)
        if day == todayKey { return today }
        if let (start, _) = CareRules.dayRange(todayKey, zone), CareRules.dayKey(start - 1, zone) == day { return yesterday }
        return CareRules.dayRange(day, zone).map { CareFormat.day(CareTime.date($0.0), zone) } ?? day
    }

    /// log.row.*: 기록 탭 손 기록 한 줄(그 기록의 시간대로 보인다).
    static func row(_ e: CareEvent) -> String {
        let z = e.timeZone
        let start = CareTime.date(e.startAt)
        let time = CareFormat.clock(start, z)
        let span = e.endAt.map { CareFormat.clockRange(start, CareTime.date($0), z) }
        let parts: [String?]
        switch e.kind {
        case .feed:
            if e.inProgress { return "\(feedRunning) · \(time)부터" }
            let side = e.feedSide == .bottle ? e.amountMl.map { "젖병 \($0)ml" } ?? FeedSide.bottle.label : e.feedSide?.label
            parts = e.endUnknown ? [quickLabel(.feed), time, side, endUnknown] : [quickLabel(.feed), span ?? time, side]   // U37
        case .sleep:
            if e.inProgress { return "\(sleepRunning) · \(time)부터" }
            if e.endUnknown { return "\(quickLabel(.sleep)) · \(time) · \(endUnknown)" }
            parts = [quickLabel(.sleep), span ?? time, e.endAt.map { CareFormat.length($0 - e.startAt) }]
        case .diaper: parts = [quickLabel(.diaper), time, e.diaper.map(diaperLabel)]
        case .note: parts = [quickLabel(.note), time, e.note]
        }
        return parts.compactMap { $0 }.joined(separator: " · ")
    }

    /// pattern.value
    static func avgCount(_ v: Double) -> String { String(format: "평균 %.1f회", v) }
    static func median(_ ms: Int64) -> String { "중앙값 " + CareFormat.length(ms) }
    static func avgDuration(_ ms: Int64) -> String { "평균 " + CareFormat.length(ms) }
    static func times(_ n: Int) -> String { "\(n)번" }
    static let noRecords = "기록 없음"                                                   // pattern.value(U27)
    static func basedOn(days n: Int) -> String { "\(n)일 기준" }                          // pattern.days

    // about.*(U28)
    static func version(_ v: String) -> String { "판 \(v)" }
    static let oss = "오픈소스 라이선스", ossNone = "이 앱은 외부 오픈소스 라이브러리를 쓰지 않아요"

    /// export.fileName "aigyeot-care-{YYYYMMDD}.csv"
    static func exportFileName(_ d: Date, zone: TimeZone = .current) -> String {
        "aigyeot-care-" + CareRules.dayKey(CareTime.ms(d), zone).replacingOccurrences(of: "-", with: "") + ".csv"
    }

    /// U15: 차 기록 줄의 소리는 방법 이름만 쓴다(곡 이름은 리포트 상세에서 본다).
    static func method(_ m: SoundMethod) -> String {
        switch m { case .whiteNoise: "백색소음"; case .lullaby: "자장가"; case .parentSong: "부모 노래" }
    }

    static func carResult(_ r: EpisodeResult) -> String {
        switch r { case .success: "그쳤어요"; case .failure: "계속 울었어요"; case .undetermined: "알 수 없음" }
    }
}

// MARK: 내 기록 내보내기(C7, U29)

/// 손 기록 CSV. 지우지 않은 것을 시작 시각 오름차순으로, 차 기록 줄은 넣지 않는다.
/// UTF-8(BOM), CRLF, 첫 줄은 7.1 `export.csvHeader`, 시각은 그 기록 시간대의 오프셋을 붙인 ISO 8601.
enum CareExport {
    /// 공유하려고 임시 폴더에 쓴 사본(`aigyeot-care-*.csv`)을 지운다. '이 휴대폰의 기록 지우기'가 부른다(계약 7.4).
    static func removeCopies(in dir: URL = FileManager.default.temporaryDirectory) {
        let fm = FileManager.default
        for name in (try? fm.contentsOfDirectory(atPath: dir.path)) ?? [] where name.hasPrefix("aigyeot-care-") && name.hasSuffix(".csv") {
            try? fm.removeItem(at: dir.appendingPathComponent(name))
        }
    }

    static func csv(_ events: [CareEvent]) -> Data {
        var lines = [CareText.csvHeader]
        for e in CareRules.live(events).sorted(by: { $0.startAt < $1.startAt }) {
            lines.append([
                e.kind.rawValue,
                iso(e.startAt, e.timeZone),
                e.endAt.map { iso($0, e.timeZone) } ?? "",
                e.endUnknown ? "true" : "false",
                e.feedSide?.rawValue ?? "",
                e.amountMl.map(String.init) ?? "",
                e.diaper?.rawValue ?? "",
                quoted(e.note ?? ""),
            ].joined(separator: ","))
        }
        return Data([0xEF, 0xBB, 0xBF]) + Data((lines.joined(separator: "\r\n") + "\r\n").utf8)
    }

    /// "2026-11-05T09:00:00+09:00"
    static func iso(_ ms: Int64, _ zone: TimeZone) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = zone
        return f.string(from: CareTime.date(ms))
    }

    /// RFC 4180: 쉼표·따옴표·줄바꿈이 있으면 따옴표로 감싸고 안의 따옴표는 두 번 쓴다.
    static func quoted(_ s: String) -> String {
        s.unicodeScalars.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) ? "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : s
    }
}
