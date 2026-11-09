import Foundation
import CryptoKit

// 기록 가져오기(phone-care-plan U64~U69). 아이곁·Huckleberry·Nara CSV를 돌봄 기록으로 바꾼다. 순수 함수이고 저장은 RecordCare.importRows가 한다.
// 두 앱이 같은 파일(shared/src/test/resources/care/import/)에서 같은 결과(id·건너뛴 수까지)를 내야 한다.

enum ImportFormat: String, Equatable { case aigyeot = "AIGYEOT", huckleberry = "HUCKLEBERRY", nara = "NARA", unknown = "UNKNOWN" }

/// 건너뛴 줄 수(U67). 한 줄은 처음 걸린 이유 하나로 센다.
struct ImportSkipped: Equatable {
    var unsupported = 0, unreadable = 0, future = 0, old = 0, duplicate = 0
}

/// `parse` 결과(U69 기대 JSON 꼴). `profiles`는 Nara 파일에 처음 나온 순서의 아이 이름(고르는 화면에만 쓰고 저장하지 않는다).
struct ImportDraft: Equatable {
    var format: ImportFormat
    var profiles: [String] = []
    var rows: [CareEvent] = []
    var skipped = ImportSkipped()
}

/// 파일 바이트를 글자로(U65). 화면 층이 파일을 고른 바로 뒤 한 번 부른다.
enum CsvRead: Equatable { case text(String), tooLarge, unreadable }

enum CareCsv {
    static let maxBytes = 5 * 1024 * 1024     // U65 설계값 5MB
    static let maxLines = 20_000              // U65 설계값 2만 줄(헤더 포함 실제 줄 수)
    static let maxFields = 256                // U65 11-09 보충 설계값: 한 레코드의 칸 수
    static let oldMs: Int64 = 14 * 86_400_000 // U67 ④ 14일
    static let duplicateMs: Int64 = 60_000    // U67 ⑤ 60초
    static let mlPerOz = 29.57
    static let zone = "Asia/Seoul"
    private static let seoulOffsetS: Int64 = 9 * 3600   // 한국 전용 범위(AGENTS): 서머타임 없음

    /// 5MB·2만 줄을 넘으면 `tooLarge`, UTF-8이 아니면 `unreadable`. 앞 BOM은 버린다.
    static func read(_ data: Data) -> CsvRead {
        guard data.count <= maxBytes else { return .tooLarge }
        guard var text = String(data: data, encoding: .utf8) else { return .unreadable }
        if text.unicodeScalars.first == "\u{FEFF}" { text.unicodeScalars.removeFirst() }
        let newlines = text.utf8.reduce(0) { $1 == 0x0A ? $0 + 1 : $0 }
        let lines = newlines + (text.utf8.last == 0x0A || text.isEmpty ? 0 : 1)
        return lines > maxLines ? .tooLarge : .text(text)
    }

    /// U65~U67. `existing`는 지금 `care.json`의 기록(지운 것 포함), `now`는 epoch ms. `profile`은 고른 Nara 아이(U69).
    static func parse(_ text: String, existing: [CareEvent], now: Int64, profile: String? = nil) -> ImportDraft {
        var records = Records(text)
        guard let first = records.next() else { return ImportDraft(format: .unknown) }
        let header = first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
        let format = detect(header)
        guard format != .unknown else { return ImportDraft(format: .unknown) }   // 모르는 헤더면 나머지 줄은 읽지 않는다
        let col = Dictionary(header.enumerated().map { ($1, $0) }, uniquingKeysWith: { first, _ in first })
        var lines: [Line] = []
        while let r = records.next() { lines.append(Line(cells: r, col: col)) }
        // 따옴표가 닫히지 않은 채 끝났거나(뒤 기록이 한 칸에 삼켜짐) 칸이 256개를 넘는 줄이 있으면 파일째 읽지 않는다(import.unreadable, U65 11-09 보충)
        if records.broken { return ImportDraft(format: .unknown) }

        var draft = ImportDraft(format: format)
        var picked = lines
        if format == .nara {
            for name in lines.compactMap({ $0["profile name"] }) where !draft.profiles.contains(name) { draft.profiles.append(name) }
            if draft.profiles.count > 1 {
                guard let profile else { return draft }   // 아이 고르기 전
                picked = lines.filter { $0["profile name"] == profile }
            }
        }

        var seen = Seen(existing)
        var kept: [(index: Int, event: CareEvent)] = []
        for (i, line) in picked.enumerated() {
            let r: Parsed
            switch format {
            case .aigyeot: r = aigyeot(line)
            case .huckleberry: r = huckleberry(line)
            case .nara: r = nara(line)
            case .unknown: continue
            }
            guard case .row(let p) = r else {
                if case .unsupported = r { draft.skipped.unsupported += 1 } else { draft.skipped.unreadable += 1 }
                continue
            }
            if p.startAt > now + CareRules.futureSlackMs { draft.skipped.future += 1; continue }
            if p.startAt < now - oldMs { draft.skipped.old += 1; continue }
            let e = event(p, now: now)
            if seen.duplicate(e) { draft.skipped.duplicate += 1; continue }
            seen.add(e)
            kept.append((i, e))
        }
        draft.rows = kept.sorted { ($0.event.startAt, $0.index) < ($1.event.startAt, $1.index) }.map(\.event)
        return draft
    }

    /// U67 ⑤ 판정 하나: 같은 id(지운 기록 포함), 또는 지우지 않은 기록·이미 가져오기로 정한 줄과 같은 종류·시작 60초 이하.
    /// 미리 보기(`parse`)와 저장하는 순간(`fresh`, RecordCare.importRows)이 같이 쓴다(U68 보충 ④).
    /// 60초 칸으로 나눠 앞뒤 칸만 본다(2만 줄이어도 줄마다 몇 개만 견준다).
    struct Seen {
        private struct Key: Hashable { let kind: CareKind, slot: Int64 }
        private var ids: Set<String>
        private var slots: [Key: [Int64]] = [:]
        init(_ existing: [CareEvent]) {
            ids = Set(existing.map(\.id))
            for e in existing where e.deletedAt == nil { addStart(e) }
        }
        func duplicate(_ e: CareEvent) -> Bool {
            let s = e.startAt / CareCsv.duplicateMs
            return ids.contains(e.id)
                || (s - 1...s + 1).contains { slots[Key(kind: e.kind, slot: $0)]?.contains { abs($0 - e.startAt) <= CareCsv.duplicateMs } == true }
        }
        mutating func add(_ e: CareEvent) { ids.insert(e.id); addStart(e) }
        private mutating func addStart(_ e: CareEvent) { slots[Key(kind: e.kind, slot: e.startAt / CareCsv.duplicateMs), default: []].append(e.startAt) }
    }

    /// 저장하는 순간 다시 거른다(U68 보충 ④): 미리 보기 뒤 손으로 넣은 기록과 겹치는 줄을 뺀다. 판정은 `parse`와 같은 `Seen`이다.
    static func fresh(_ rows: [CareEvent], existing: [CareEvent]) -> [CareEvent] {
        var seen = Seen(existing)
        return rows.filter { e in
            guard !seen.duplicate(e) else { return false }
            seen.add(e)
            return true
        }
    }

    // MARK: 형식 판별(U65)

    static func detect(_ header: [String]) -> ImportFormat {
        let h = Set(header)
        if header == CareText.csvHeader.split(separator: ",").map(String.init) { return .aigyeot }
        if h.isSuperset(of: ["type", "start date/time"]) { return .nara }
        if h.isSuperset(of: ["type", "start", "end", "duration"]) { return .huckleberry }
        return .unknown
    }

    // MARK: 줄 → 기록(U66)

    /// 한 줄. 칸은 헤더 이름(소문자)으로 찾고, 비었거나 공백뿐이면 nil(메모는 `note`가 따로 읽는다).
    struct Line {
        let cells: [String]
        let col: [String: Int]
        func raw(_ name: String) -> String? { col[name].flatMap { $0 < cells.count ? cells[$0] : nil } }
        subscript(_ name: String) -> String? {
            let v = raw(name)?.trimmingCharacters(in: .whitespacesAndNewlines)
            return v?.isEmpty == false ? v : nil
        }
        /// U24: 200자(코드 포인트)에서 자른다. 비었거나 공백뿐이면 nil.
        func note(_ name: String) -> String? {
            guard let v = raw(name), !v.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            return String(String.UnicodeScalarView(v.unicodeScalars.prefix(CareRules.noteMax)))
        }
    }

    /// 줄에서 읽은 값. 끝 처리·id·시각 검사는 `parse`와 `event`가 형식과 상관없이 한다.
    struct Picked {
        var kind: CareKind
        var startAt: Int64
        var endAt: Int64? = nil
        var feedSide: FeedSide? = nil
        var amountMl: Int? = nil
        var diaper: DiaperKind? = nil
        var note: String? = nil
    }

    enum Parsed { case row(Picked), unsupported, unreadable }

    /// 끝 처리(U66: 비었거나 시작보다 이르거나 최대 길이를 넘거나 지금 + 1분보다 늦으면 끝 모름. FEED·SLEEP이 아니면 끝 없음)와 id.
    static func event(_ p: Picked, now: Int64) -> CareEvent {
        var endAt: Int64? = nil, endUnknown = false
        if p.kind == .feed || p.kind == .sleep {
            let limit = p.kind == .feed ? CareRules.feedMaxMs : CareRules.sleepMaxMs
            if let end = p.endAt, end >= p.startAt, end - p.startAt <= limit, end <= now + CareRules.futureSlackMs { endAt = end } else { endUnknown = true }
        }
        let key = [p.kind.rawValue, String(p.startAt), endAt.map(String.init) ?? "", p.feedSide?.rawValue ?? "", p.amountMl.map(String.init) ?? ""]
            .joined(separator: "|")
        let id = "imp-" + SHA256.hash(data: Data(key.utf8)).prefix(8).map { String(format: "%02x", $0) }.joined()   // 16진수 앞 16글자 = 앞 8바이트
        return CareEvent(id: id, kind: p.kind, startAt: p.startAt, endAt: endAt, endUnknown: endUnknown, zone: zone,
                         feedSide: p.feedSide, amountMl: p.amountMl, diaper: p.diaper, note: p.note, source: .imported,
                         createdAt: now, updatedAt: now)
    }

    /// 아이곁 내보내기(U29의 역): enum 글자, 오프셋 있는 ISO 8601, NOTE 줄도 가져온다.
    static func aigyeot(_ l: Line) -> Parsed {
        guard let kind = l["kind"].flatMap(CareKind.init(rawValue:)) else { return .unsupported }
        guard let start = l["start"].flatMap(time) else { return .unreadable }
        // amount_ml은 종류와 상관없이 본다: 비었으면 null, 정수·0~400이 아니면 그 줄은 읽을 수 없는 줄(U66). 값은 FEED에만 둔다
        var amount: Int?
        if let a = l["amount_ml"] {
            guard let n = Int(a), CareRules.amountRange.contains(n) else { return .unreadable }
            amount = n
        }
        var p = Picked(kind: kind, startAt: start, endAt: l["end"].flatMap(time), note: l.note("note"))
        switch kind {
        case .feed:
            p.feedSide = l["side"].flatMap(FeedSide.init(rawValue:))
            p.amountMl = amount
        case .diaper: p.diaper = l["diaper"].flatMap(DiaperKind.init(rawValue:))
        case .sleep, .note: break
        }
        return .row(p)
    }

    static func huckleberry(_ l: Line) -> Parsed {
        let kind: CareKind
        switch l["type"]?.lowercased() {
        case "sleep": kind = .sleep
        case "feed": kind = .feed
        case "diaper": kind = .diaper
        default: return .unsupported
        }
        guard let start = l["start"].flatMap(time) else { return .unreadable }
        var p = Picked(kind: kind, startAt: start, endAt: l["end"].flatMap(time), note: l.note("notes"))
        switch kind {
        case .feed:
            let d = l["duration"] ?? ""
            if let v = firstMatch(volumeRe, in: d) {   // 조각은 공백으로 나눈다: 숫자에 붙었거나 띄운 ml·oz
                guard let ml = volume(v[1], unit: v[2]) else { return .unreadable }
                p.feedSide = .bottle; p.amountMl = ml   // 젖병이 우선
            } else {
                let sides = Set(sideRe.matches(in: d, range: NSRange(d.startIndex..., in: d))
                    .compactMap { Range($0.range(at: 1), in: d).map { String(d[$0]) } })
                p.feedSide = sides.count == 2 ? .both : sides.first == "R" ? .right : sides.first == "L" ? .left : nil
            }
        case .diaper:
            // 기저귀는 Start Condition을 읽지 않는다
            p.diaper = diaper([l["end condition"], l["duration"]].compactMap { $0 }.joined(separator: " "))
        case .sleep, .note: break
        }
        return .row(p)
    }

    static func nara(_ l: Line) -> Parsed {
        let type = l["type"]?.lowercased()
        let kind: CareKind
        switch type {
        case "breastfeed", "bottle feed": kind = .feed
        case "sleep": kind = .sleep
        case "diaper": kind = .diaper
        default: return .unsupported
        }
        guard let start = l["start date/time"].flatMap(time) else { return .unreadable }
        var p = Picked(kind: kind, startAt: start, note: l.note("note"))
        switch type {
        case "breastfeed":
            let left = seconds(l["[breastfeed] left duration (seconds)"]), right = seconds(l["[breastfeed] right duration (seconds)"])
            p.feedSide = left > 0 && right > 0 ? .both : left > 0 ? .left : right > 0 ? .right : nil
            // 끝 = 시작 + 합(ms 아래 버림). 합이 최대 길이(R3)를 넘으면 정수로 바꾸기 전에 끝 모름으로 둔다(1e16·1e308초에서 Int64 변환이 죽지 않게)
            let ms = (left + right) * 1000
            if ms > 0, ms <= Double(CareRules.feedMaxMs) { p.endAt = start + Int64(ms.rounded(.down)) }
        case "bottle feed":
            p.feedSide = .bottle
            // 부피·단위가 비었어도 검사한다(U66: 단위가 없으면 읽을 수 없는 줄)
            guard let ml = volume(l["[bottle feed] volume"] ?? "", unit: l["[bottle feed] volume unit"] ?? "") else { return .unreadable }
            p.amountMl = ml
        case "sleep": p.endAt = l["[sleep] end date/time"].flatMap(time)
        default: p.diaper = diaper(l["[diaper] type"] ?? "")
        }
        return .row(p)
    }

    // MARK: 값 읽기

    /// ml면 그대로, oz면 ×29.57. 범위(0~400)는 반올림 전 값으로 보고, 반올림은 0.5에서 올린다(11-09 보충).
    /// 단위가 없거나 숫자가 아니거나 범위 밖이면 nil(그 줄은 읽을 수 없는 줄).
    static func volume(_ value: String, unit: String) -> Int? {
        guard let v = Double(value.trimmingCharacters(in: .whitespaces)), v.isFinite else { return nil }
        let ml: Double
        switch unit.trimmingCharacters(in: .whitespaces).lowercased() {
        case "ml": ml = v
        case "oz": ml = v * mlPerOz
        default: return nil
        }
        guard ml >= Double(CareRules.amountRange.lowerBound), ml <= Double(CareRules.amountRange.upperBound) else { return nil }
        return Int((ml + 0.5).rounded(.down))
    }

    /// wet·pee → WET, dirty·poo → DIRTY, mixed·both → BOTH, dry → DRY(대소문자 없이). 둘 이상 맞으면 BOTH, 없으면 nil.
    static func diaper(_ s: String) -> DiaperKind? {
        let t = s.lowercased()
        let hits = [(DiaperKind.wet, ["wet", "pee"]), (.dirty, ["dirty", "poo"]), (.both, ["mixed", "both"]), (.dry, ["dry"])]
            .filter { $0.1.contains { t.contains($0) } }.map(\.0)
        return hits.count > 1 ? .both : hits.first
    }

    /// Nara 좌우 초: 소수 허용. 숫자가 아니거나 음수면 그 쪽 없음(0).
    private static func seconds(_ s: String?) -> Double { s.flatMap { Double($0) }.map { $0.isFinite ? max(0, $0) : 0 } ?? 0 }

    /// 세 형식 공통(엄격하게): 오프셋 있는 ISO 8601(`YYYY-MM-DDTHH:MM[:SS[.소수]]` + `Z`·`±HH:MM`)은 그대로,
    /// 오프셋 없는 `YYYY-MM-DD HH:MM`·`YYYY-MM-DD HH:MM:SS`는 Asia/Seoul 벽시계 → epoch ms(소수 초는 ms 아래를 버린다). 없는 날짜·시각은 nil.
    static func time(_ s: String) -> Int64? {
        guard let m = firstMatch(isoRe, in: s) ?? firstMatch(wallRe, in: s) else { return nil }
        let n = m[1...6].map { Int64($0) ?? 0 }
        let (y, mo, d, h, mi, sec) = (n[0], n[1], n[2], n[3], n[4], n[5])
        guard (1...12).contains(mo), (1...daysIn(y, mo)).contains(d), h < 24, mi < 60, sec < 60 else { return nil }
        var offset = seoulOffsetS
        let z = m.count > 8 ? m[8] : ""
        let frac = m.count > 8 ? Int64((m[7] + "00").prefix(3)) ?? 0 : 0
        if z == "Z" { offset = 0 } else if !z.isEmpty {
            let sign: Int64 = z.hasPrefix("-") ? -1 : 1
            let hh = Int64(z.dropFirst().prefix(2)) ?? 0, mm = Int64(z.suffix(2)) ?? 0
            // Java ZoneOffset과 같은 범위: 시 0~18, 분 0~59, 합 ±18:00까지. 밖이면 읽을 수 없는 시각
            guard hh <= 18, mm <= 59, hh * 3600 + mm * 60 <= 18 * 3600 else { return nil }
            offset = sign * (hh * 3600 + mm * 60)
        }
        return ((daysFromCivil(y, mo, d) * 86_400) + h * 3600 + mi * 60 + sec - offset) * 1000 + frac
    }

    private static func daysIn(_ y: Int64, _ m: Int64) -> Int64 {
        switch m {
        case 2: return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// 그레고리력 날짜 → 1970-01-01부터의 날 수(Howard Hinnant).
    private static func daysFromCivil(_ y0: Int64, _ m: Int64, _ d: Int64) -> Int64 {
        let y = m <= 2 ? y0 - 1 : y0
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    // 정규식은 한 번만 만든다(2만 줄이면 줄마다 여러 번 쓴다).
    private static let isoRe = try! NSRegularExpression(
        pattern: #"^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.([0-9]{1,9}))?)?(Z|[+-][0-9]{2}:[0-9]{2})$"#)
    private static let wallRe = try! NSRegularExpression(pattern: #"^([0-9]{4})-([0-9]{2})-([0-9]{2}) ([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?$"#)
    /// Huckleberry `Duration`의 부피 조각: 부호 있는 숫자 + ml·oz(붙었거나 공백 하나 이상 띄움, 단위는 대소문자 없이). '-0.4ml'는 범위 밖이다.
    private static let volumeRe = try! NSRegularExpression(
        pattern: #"(?:^|\s)([+-]?[0-9]+(?:\.[0-9]+)?)\s*(ml|oz)(?=\s|$)"#, options: [.caseInsensitive])
    /// Huckleberry `Duration`의 쪽 조각: `H:MM` + 대문자 R·L(조각 전체).
    private static let sideRe = try! NSRegularExpression(pattern: #"(?:^|\s)[0-9]+:[0-9]{2}([RL])(?=\s|$)"#)

    /// 첫 일치의 묶음들(0은 전체). 없는 묶음은 "".
    private static func firstMatch(_ re: NSRegularExpression, in s: String) -> [String]? {
        guard let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        return (0..<m.numberOfRanges).map { Range(m.range(at: $0), in: s).map { String(s[$0]) } ?? "" }
    }

    // MARK: RFC 4180

    /// RFC 4180 레코드를 하나씩 읽는다: 따옴표 칸(`""`는 `"`), 칸 안 줄바꿈, CRLF·LF, 따옴표 밖의 CR은 버린다.
    /// 헤더만 보고 모르는 형식이면 나머지를 펼치지 않는다. 따옴표가 닫히지 않은 채 끝나거나, 한 레코드가 257칸째에 이르면
    /// 그 자리에서 멈추고 `broken`(파일째 읽지 않는다, U65 11-09 보충: 한 줄에 수백만 칸인 파일이 칸 목록을 다 만들지 않게).
    struct Records {
        private var it: String.UnicodeScalarView.Iterator
        private var pending: Unicode.Scalar?
        private(set) var broken = false
        init(_ text: String) { it = text.unicodeScalars.makeIterator() }

        private mutating func scalar() -> Unicode.Scalar? {
            if let p = pending { pending = nil; return p }
            return it.next()
        }

        /// 칸이 모두 비었거나 공백뿐인 레코드(빈 줄, 끝의 `,,,,`)는 건너뛴다. 헤더 앞의 빈 줄도 같다(안드로이드 `records`).
        mutating func next() -> [String]? {
            while let r = raw() {
                if r.contains(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) { return r }
            }
            return nil
        }

        private mutating func raw() -> [String]? {
            var record: [String] = [], cell = "", quoted = false, any = false
            while let c = scalar() {
                any = true
                if quoted {
                    if c == "\"" {
                        if let n = it.next() { if n == "\"" { cell.unicodeScalars.append("\"") } else { quoted = false; pending = n } } else { quoted = false }
                    } else { cell.unicodeScalars.append(c) }
                    continue
                }
                switch c {
                case "\"": quoted = true
                case ",":
                    record.append(cell); cell = ""
                    if record.count == CareCsv.maxFields { broken = true; return nil }   // 257번째 칸이 시작된다
                case "\r": break
                case "\n": record.append(cell); return record
                default: cell.unicodeScalars.append(c)
                }
            }
            if quoted { broken = true; return nil }
            guard any else { return nil }
            record.append(cell)
            return record
        }
    }
}
