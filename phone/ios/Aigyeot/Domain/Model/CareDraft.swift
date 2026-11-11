import Foundation

/// 기록 시트(빠른 기록·고치기)의 입력. 화면은 이 값을 바꾸기만 하고, 칩·날짜 옮김·끝 시각 맞춤과 저장할 기록 만들기는 여기서 한다
/// (안드로이드 `CareDraft`와 같은 이름·규칙, arch-phone-ios 2장).
struct CareDraft: Equatable {
    /// 시트 하나의 id(안드로이드 `sheetId`). 새 기록이면 그 기록의 id다: 시트를 열 때 한 번 정해, 저장을 빠르게 두 번 눌러도 같은 id라 기록이 하나만 생긴다.
    /// 고치기면 고치는 기록의 id.
    let sheetId: String
    /// 기록 종류. '지난 기록 적기' 시트만 종류 칸으로 바꾼다(U86, `setKind`).
    private(set) var kind: CareKind
    /// 고치는 기록. 없으면 새 기록.
    let original: CareEvent?
    /// 기록 탭에서 고른 다른 날(새 기록). 있으면 몇 분 전 칩 없이 그날 낮 12시에서 고른다.
    var day: String? = nil
    /// 몇 분 전 칩(0 = 지금, nil = 직접 고름).
    var chip: Int?
    private(set) var start: Date
    private(set) var end: Date
    var endUnknown: Bool
    var side: FeedSide?
    var amount: Int?
    var diaper: DiaperKind?
    var note: String

    var hasDuration: Bool { kind == .feed || kind == .sleep }
    /// 몇 분 전 칩은 오늘 새 기록에만 있다(고치기·다른 날 기록에는 없다).
    var showsAgoChips: Bool { original == nil && day == nil }
    /// R13: 고치는 기록은 그 기록의 시간대로 보인다. 새 기록은 기기 시간대.
    var zone: TimeZone { original?.timeZone ?? .current }
    /// 끝 칸이 자리 값이다(새 기록이거나 끝 모름 기록을 고칠 때). 끝을 모를 때 고르기에 보일 값(`endShown`)을 정할 때만 쓴다.
    var endIsPlaceholder: Bool { original?.endAt == nil }

    /// 새 기록: 지금(칩 '지금'). 기록 탭에서 다른 날을 골랐으면 그날 낮 12시에서 직접 고른다. 새 기저귀는 소변으로 시작한다(종류 없이 저장하지 않게).
    static func new(kind: CareKind, day: String?, now: Date) -> CareDraft {
        var d = CareDraft(sheetId: ULID.generate(date: now), kind: kind, original: nil, day: day, chip: 0, start: now, end: now, endUnknown: true,
                          side: nil, amount: nil, diaper: kind == .diaper ? .wet : nil, note: "")
        if let day, let noon = CareRules.noon(day, .current) {
            d.chip = nil
            d.start = CareTime.date(noon)
            d.end = d.start
        }
        return d
    }

    /// 고치기: 그 기록의 값에서 시작한다.
    static func editing(_ e: CareEvent) -> CareDraft {
        let start = CareTime.date(e.startAt)
        return CareDraft(sheetId: e.id, kind: e.kind, original: e, chip: nil, start: start, end: e.endAt.map(CareTime.date) ?? start,
                         endUnknown: e.endAt == nil, side: e.feedSide, amount: e.amountMl, diaper: e.diaper, note: e.note ?? "")
    }

    /// U86: 종류를 바꿔도 시각 입력은 그대로 둔다(안드로이드 `setKind`). 종류에 맞지 않는 칸은 저장할 때 `normalized`가 비운다.
    mutating func setKind(_ k: CareKind) {
        kind = k
        if k == .diaper, diaper == nil { diaper = .wet }   // 새 기저귀는 소변으로 시작(종류 없이 저장하지 않게)
    }

    /// 몇 분 전 칩: 누른 때 기준으로 시작을 정한다. '직접'은 지금 값에서 고른다.
    /// 시작 칩·시각은 고른 끝을 바꾸지 않는다: 시작이 끝을 넘으면 말없이 끝을 당기지 않고 저장 검사(R3 '끝 시각이 시작보다 빨라요')가 알린다(C384, 안드로이드와 같다).
    mutating func pick(chip minutes: Int?, now: Date) {
        chip = minutes
        guard let minutes else { return }
        start = now.addingTimeInterval(-Double(minutes * 60))
    }

    /// 시작 시각 칩(끝은 그대로, 위 pick과 같은 규칙).
    mutating func setStart(_ s: Date) { start = s }

    /// 시작 날짜 칩: 날을 옮기면 끝도 같은 만큼 옮겨 길이를 지킨다. 끝 모름이어도 옮겨 둔다(다시 시각을 고를 때 옛 날이 남지 않게).
    mutating func moveStartDay(to moved: Date) {
        end = end.addingTimeInterval(moved.timeIntervalSince(start))
        setStart(moved)
    }

    /// 끝 시각 칩: 사용자가 시각을 고르면(바퀴를 돌리면) 그때 끝을 정한다 — 고른 시·분을 시작 뒤 처음 오는 그 시각으로 두고 '끝 시각 모름'을 푼다
    /// (안드로이드 setEnd와 같다: 고르기를 열었다 닫기만 하면 끝 모름 그대로). 지금보다 늦은 끝은 저장 검사(R3)가 잡는다.
    mutating func setEnd(picked: Date) {
        endUnknown = false
        end = firstAfterStart(picked)
    }

    /// 끝 시각 고르기에 보일 시각. 끝을 모르면 상태를 바꾸지 않고 계산한다: 자리 값은 시작 뒤로, 저장된 끝은 시작 뒤 처음 오는 그 시각
    /// (그사이 시작 날을 옮겼어도 옛 날이 보이지 않게).
    var endShown: Date {
        guard endUnknown else { return end }
        return endIsPlaceholder ? max(end, start) : firstAfterStart(end)
    }

    /// `picked`의 시·분이 시작 뒤 처음 오는 때. 시작과 같은 분이면 시작 그대로(초 때문에 다음 날로 넘기지 않는다), 이른 시각이면 다음 날
    /// (자정을 넘긴 잠, 안드로이드 firstAtOrAfter).
    func firstAfterStart(_ picked: Date) -> Date {
        let cal = CareRules.calendar(zone)
        let hm = cal.dateComponents([.hour, .minute], from: picked)
        guard let same = cal.date(bySettingHour: hm.hour ?? 0, minute: hm.minute ?? 0, second: 0, of: start),
              let startMinute = cal.dateInterval(of: .minute, for: start)?.start else { return picked }
        return same < startMinute ? (cal.date(byAdding: .day, value: 1, to: same) ?? same) : max(same, start)
    }

    /// 저장할 기록. 새 기록이면 id(`sheetId`)·만든 때·기기 시간대를 붙이고, 종류에 맞지 않는 칸은 `CareRules.normalized`가 비운다.
    func event(now: Date) -> CareEvent {
        let t = CareTime.ms(now)
        var e = original ?? CareEvent(id: sheetId, kind: kind, startAt: 0, zone: TimeZone.current.identifier,
                                      createdAt: t, updatedAt: t)
        e.startAt = CareTime.ms(start)
        if hasDuration {
            e.endAt = endUnknown ? nil : CareTime.ms(end)
            e.endUnknown = endUnknown
        }
        e.feedSide = side
        e.amountMl = amount
        e.diaper = diaper
        e.note = note
        return CareRules.normalized(e)
    }
}

// MARK: 기록 시트 글(v8 2판 안 1, U82·U83) — 시트가 보이는 값은 모두 여기서 만든다(안드로이드 같은 이름)

/// 시트의 접힌 줄(U82). 시작·시각은 같은 줄(종류마다 이름만 다르다).
enum SheetField: Equatable { case start, end, amount, memo }

extension CareDraft {
    /// 종류마다 접힌 줄(U82): 수유 시작·끝·메모, 기저귀 시각·메모, 잠 끝·메모(시작은 큰 칸), 메모 시각.
    var foldRows: [SheetField] {
        switch kind {
        case .feed: [.start, .end, .memo]
        case .diaper: [.start, .memo]
        case .sleep: [.end, .memo]
        case .note: [.start]
        }
    }

    /// 줄 이름(`sheet.row.*`): 시작은 길이가 있는 기록이면 '시작', 나머지는 '시각'. 빈 메모 줄은 '메모 더하기'(누르면 하는 줄, U82).
    func rowName(_ f: SheetField) -> String {
        switch f {
        case .start: hasDuration ? CareText.rowStart : CareText.rowTime
        case .end: CareText.rowEnd
        case .memo: note.isEmpty ? CareText.sheetMemoAdd : CareText.rowNote
        case .amount: CareText.rowAmount
        }
    }

    /// 고른 몇 분 전 칩 글('지금'·'15분 전'), '직접'이면 nil.
    var chipText: String? { chip.map { $0 == 0 ? CareText.chipNow : CareText.chip(minutes: $0) } }

    /// 몇 분 전을 고른 동안은 날짜·시각 알약을 숨긴다(U82). 칩이 없는 시트(고치기·다른 날)는 늘 보인다.
    var showsPills: Bool { !showsAgoChips || chip == nil }

    /// U82 시작·시각 줄 값: 칩이면 '{시각} · {칩}', 직접 고른 오늘이면 '{시각}', 다른 날·고치기면 '{날짜} {시각}'.
    func startValue(now: Date) -> String {
        let t = CareFormat.clock(start, zone)
        if showsAgoChips, let c = chipText { return t + " · " + c }
        let day = CareRules.dayKey(CareTime.ms(start), zone)
        if showsAgoChips, day == CareRules.dayKey(CareTime.ms(now), zone) { return t }
        return CareText.dayTitle(day, now: now, zone: zone) + " " + t
    }

    /// U82 끝 줄 값: 모르면 '모름', 알면 '{시각} · {길이}'(끝이 시작보다 이르면 시각만).
    var endValue: String {
        if endUnknown { return CareText.sheetEndUnknownValue }
        let t = CareFormat.clock(end, zone)
        return end < start ? t : t + " · " + CareFormat.length(CareTime.ms(end) - CareTime.ms(start))
    }

    /// 저장할 수 있는가(메모는 글이 있어야 한다). 비면 저장 단추가 꺼진다(U83).
    var savable: Bool { kind != .note || !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// U83 저장 단추 글 '{시각} {고른 것} {종류} 저장하기'. `fit`이면 22자(공백 포함 코드 포인트)를 넘을 때
    /// ① 잠 구간 → 시작 시각 ② 고른 것 빼기 ③ 시각 빼기 순으로 처음 드는 글을 쓴다. 큰 글자면 `fit` 없이 두 줄까지 쓴다.
    func saveLabel(fit: Bool = true) -> String {
        let save = CareText.saveSuffix
        if kind == .note { return CareText.quickLabel(.note) + " " + save }
        let word = CareText.quickLabel(kind)
        let time = CareFormat.clock(start, zone)
        let span = kind == .sleep && !endUnknown ? CareFormat.clockRange(start, end, zone) : time
        var picked: String?, pickedWord: String? = word
        switch kind {
        case .feed where side == .bottle:
            if let a = amount { picked = FeedSide.bottle.label + " " + CareText.ml(a); pickedWord = nil } else { picked = FeedSide.bottle.label }
        case .feed: picked = side?.label
        case .diaper: picked = diaper.map(CareText.diaperLabel)
        default: break
        }
        func make(_ parts: [String?]) -> String { (parts.compactMap { $0 } + [save]).joined(separator: " ") }
        let tries = [make([span, picked, pickedWord]), make([time, picked, pickedWord]), make([time, word]), make([word])]
        guard fit else { return tries[0] }
        return tries.first { $0.unicodeScalars.count <= CareText.saveLabelMax } ?? tries[3]
    }

    /// U82 끝: 저장 검사에 걸리면 탓이 된 줄(시작·시각은 잠이면 큰 칸). 글이 없는 검사는 줄도 없다.
    static func field(for p: CareProblem) -> SheetField? {
        switch p {
        case .futureStart: .start
        case .futureEnd: .end
        case .endBeforeStart, .tooLong: .end
        case .amount: .amount
        case .noteTooLong: .memo
        case .inProgressExists: nil
        }
    }
}
