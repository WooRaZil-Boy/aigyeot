import Foundation

// MARK: 형식(7장: 기기 언어와 관계없이 한국어)

enum CareFormat {
    private static let ko = Locale(identifier: "ko_KR")

    // 포매터는 만들기가 비싸 문양·시간대마다 한 번만 만든다(기록 탭은 그릴 때마다 리포트 줄을 모두 포맷한다).
    // DateFormatter는 읽기 전용으로 쓰면 스레드 안전하다. 사전만 잠근다.
    nonisolated(unsafe) private static var cache: [String: DateFormatter] = [:]
    private static let cacheLock = NSLock()

    private static func formatter(_ pattern: String, _ zone: TimeZone) -> DateFormatter {
        let key = pattern + "|" + zone.identifier
        cacheLock.lock(); defer { cacheLock.unlock() }
        if let f = cache[key] { return f }
        let f = DateFormatter()
        f.locale = ko
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = zone
        f.dateFormat = pattern
        cache[key] = f
        return f
    }

    /// "오후 12:52"
    static func clock(_ d: Date, _ zone: TimeZone = .current) -> String { formatter("a h:mm", zone).string(from: d) }

    /// "11월 5일(수) 오후 12:52"(인계 알림 상세 시각, 기기 언어와 무관)
    static func dateTime(_ d: Date, _ zone: TimeZone = .current) -> String { day(d, zone) + " " + clock(d, zone) }

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
    /// 보낼 함이 버린 요약(7.5 '버리고 화면에 알린다', 7.1 `outbox.dropped`).
    static let careSummaryDropped = "돌봄 기록 요약을 차로 보내지 못했어요."
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
    // 11-09 디자인 확정 새 라벨(7.1 design.*)
    static let todayDay = "오늘 하루", all = "전체", sleepPattern = "잠 패턴"              // design.chart.*
    static let fromCar = "차에서"                                                         // design.label "차에서 · 방금"·design.day.* 줄 이름
    static let medianWord = "중앙값", sevenDays = "7일 동안"                              // 패턴 숫자 칸 아래(안드로이드 결정 2)
    static let patternHeader = "지난 7일"                                                // pattern.header
    static let patternFeeds = "하루 수유", patternInterval = "수유 간격", patternSleep = "하루 잠"   // pattern.*
    static let patternDiapers = "하루 기저귀", patternCarCries = "차에서 울음"
    static let guardians = "보호자", nickname = "아이 별명", sleepDiaperToggle = "잠·기저귀도 기록하기"   // settings.*
    static let export = "내 기록 내보내기", clear = "이 휴대폰의 기록 지우기", disconnect = "연결 끊기", about = "정보·라이선스"
    static let nicknamePlaceholder = "예: 콩이"                                           // settings.nickname.placeholder
    static let csvHeader = "kind,start,end,end_unknown,side,amount_ml,diaper,note"       // export.csvHeader
    static let introTitle = "아이곁"                                                     // intro.*(U43, 이 순서로 보인다)
    static let introLead = "차의 아이곁과 이 휴대폰을 연결해요."
    static let introSends = "주고받는 것: 수유 시작 시각, 우리 노래 녹음, 수유·잠·기저귀 기록 요약(시각·횟수·잠 시간), 주행 리포트, 인계 알림 문장"
    static let introNotSent = "보내지 않는 것: 아기 울음 소리, 차 안 마이크 소리, 차의 위치"
    static let introRelay = "모두 아이곁 중계 서버를 거쳐 오가요. 인계 알림은 Google·Apple 알림 서버도 거쳐요."
    static let introAI = "차에서 아기 상황을 물으면, 답을 만들려고 수유·잠·기저귀 숫자를 AI 서버로 보내요."
    static let pairConfirm = "연결하기", pairConfirmSwitch = "끊고 연결하기"                // pair.confirm·confirmSwitch(U49)
    static let pairBusy = "연결하는 중이에요. 끝난 뒤 링크를 다시 열어 주세요."           // pair.busy(U52)
    static let bannerClose = "닫기"                                                      // banner.close(U48)

    /// report.row.title "11월 6일(금) 오전 2:54–3:44"(U47). 상세 제목(report.detail.title)은 날짜만, 첫 줄은 구간이다.
    static func reportTitle(start: Date, end: Date, zone: TimeZone = .current) -> String {
        CareFormat.day(start, zone) + " " + CareFormat.clockRange(start, end, zone)
    }

    /// report.row.sub "울음 3번"(0번 "울음 기록 없음"), 시뮬레이션이면 " · 시뮬레이션"(U47).
    static func reportSub(cries n: Int, simulated: Bool) -> String {
        (n == 0 ? "울음 기록 없음" : "울음 \(n)번") + (simulated ? " · 시뮬레이션" : "")
    }

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
        // 키(그레고리력)에서 쓴다: 기기 시간대에 없는 날(사모아 2011-12-30)도 그날로 보인다(안드로이드 LocalDate)
        let p = day.split(separator: "-").compactMap { Int($0) }
        guard p.count == 3 else { return day }
        return "\(p[1])월 \(p[2])일(\(CareRules.weekdayName(day)))"
    }

    /// 기록·하루 카드: 7.1 `log.row.*` 문구를 " · "로 나눠 앞 두 조각(종류·시각/구간)은 제목, 나머지는 둘째 줄(시안 ev(a, b), 안드로이드와 같다).
    static func splitRow(_ text: String) -> (title: String, sub: String?) {
        let parts = text.components(separatedBy: " · ")
        guard parts.count > 2 else { return (text, nil) }
        return (parts.prefix(2).joined(separator: " · "), parts.dropFirst(2).joined(separator: " · "))
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
    static func avgCount(_ v: Double) -> String { "평균 \(oneDecimal(v))회" }

    /// 소수 첫째 자리(안드로이드 `String.format("%.1f")`와 같게). 자바는 가장 짧은 십진 표기(2.05)를 HALF_UP으로 자르고,
    /// printf는 이진 값(2.0499…)이나 정확히 반인 값을 짝수 쪽으로 보내 2.25가 "2.2"·2.05가 "2.0"(안드로이드 "2.3"·"2.1")이었다.
    /// 그래서 가장 짧은 십진 표기를 Decimal로 읽어 반올림(반은 올림)한다.
    static func oneDecimal(_ v: Double) -> String {
        guard v.isFinite, var d = Decimal(string: "\(v)", locale: Locale(identifier: "en_US_POSIX")) else { return String(format: "%.1f", v) }
        var r = Decimal()
        NSDecimalRound(&r, &d, 1, .plain)   // 반은 올림(값은 늘 0 이상)
        let n = NSDecimalNumber(decimal: r).doubleValue
        return String(format: "%.1f", n)
    }
    static func median(_ ms: Int64) -> String { "중앙값 " + CareFormat.length(ms) }
    static func avgDuration(_ ms: Int64) -> String { "평균 " + CareFormat.length(ms) }
    static func times(_ n: Int) -> String { "\(n)번" }
    static let noRecords = "기록 없음"                                                   // pattern.value(U27)
    static func basedOn(days n: Int) -> String { "\(n)일 기준" }                          // pattern.days

    // about.*(U28)
    static func version(_ v: String) -> String { "판 \(v)" }
    static let oss = "오픈소스 라이선스"   // 목록은 OssEntry.all(U63)

    /// export.fileName "aigyeot-care-{YYYYMMDD}.csv"
    static func exportFileName(_ d: Date, zone: TimeZone = .current) -> String {
        "aigyeot-care-" + CareRules.dayKey(CareTime.ms(d), zone).replacingOccurrences(of: "-", with: "") + ".csv"
    }

    // 기록 가져오기(U64~U68, 7.1 settings.import·import.*)
    static let importEntry = "다른 앱 기록 가져오기", importEntrySub = "아이곁·Huckleberry·Nara에서 내보낸 CSV"   // settings.import(.sub)
    static let importPickChild = "어느 아이의 기록인가요?"                                  // import.pickChild
    static let importSkipTitle = "가져오지 않는 것"                                         // import.skip.title
    static let importZoneNote = "시각은 한국 시간으로 읽어요"                                // import.zoneNote
    static let importConfirm = "가져오기", importCancel = "취소"                             // import.confirm·cancel
    static let importNothing = "가져올 기록이 없어요"                                       // import.nothing
    static let importUnreadable = "이 파일은 읽을 수 없어요. 아이곁·Huckleberry·Nara에서 내보낸 CSV인지 확인해 주세요"   // import.unreadable
    static let importTooLarge = "파일이 너무 커요", importFailed = "가져오지 못했어요. 다시 해 주세요"   // import.tooLarge·failed
    static func importTitle(_ n: Int) -> String { "기록 \(n)건을 가져올게요" }                // import.title
    static func importDone(_ n: Int) -> String { "기록 \(n)건을 가져왔어요" }                 // import.done

    /// 되돌리기 띠 글: '지웠어요'(U38) / import.done
    static func undoText(_ u: CareUndo) -> String {
        switch u { case .delete: deleted; case .imported(let ids): importDone(ids.count) }
    }
    static func importLatestFeed(_ at: String) -> String { "가장 늦은 수유 \(at)" }           // import.latestFeed

    /// import.latestFeed {시각}(U68 11-09 보충): 오늘이면 "오전 11:00", 어제면 "어제 오전 11:00", 그 전이면 "11월 3일 오전 11:00". 날은 `zone` 기준.
    static func dayClock(_ ms: Int64, now: Date, zone: TimeZone) -> String {
        let clock = CareFormat.clock(CareTime.date(ms), zone)
        let day = CareRules.dayKey(ms, zone), todayKey = CareRules.dayKey(CareTime.ms(now), zone)
        if day == todayKey { return clock }
        if let (start, _) = CareRules.dayRange(todayKey, zone), CareRules.dayKey(start - 1, zone) == day { return yesterday + " " + clock }
        let p = day.split(separator: "-").compactMap { Int($0) }
        return p.count == 3 ? "\(p[1])월 \(p[2])일 \(clock)" : clock
    }

    /// import.kinds "수유 {a} · 잠 {b} · 기저귀 {c} · 메모 {d}"(0인 종류는 뺀다)
    static func importKinds(_ rows: [CareEvent]) -> String {
        [CareKind.feed, .sleep, .diaper, .note].compactMap { k in
            let n = rows.filter { $0.kind == k }.count
            return n == 0 ? nil : "\(quickLabel(k)) \(n)"
        }.joined(separator: " · ")
    }

    /// import.skip.*: 0인 이유는 감춘다(U68). 순서는 7.1 표 그대로.
    static func importSkips(_ s: ImportSkipped) -> [String] {
        [(s.duplicate, "이미 있는 기록"), (s.old, "14일보다 오래된 기록"), (s.future, "앞으로의 시각"),
         (s.unreadable, "읽을 수 없는 줄"), (s.unsupported, "가져오지 않는 종류")]
            .filter { $0.0 > 0 }.map { "\($0.1) \($0.0)건" }
    }

    /// U15: 차 기록 줄의 소리는 방법 이름만 쓴다(곡 이름은 리포트 상세에서 본다).
    static func method(_ m: SoundMethod) -> String {
        switch m { case .whiteNoise: "백색소음"; case .lullaby: "자장가"; case .parentSong: "부모 노래"; case .womb: "뱃속 소리"; case .unknown: "기타" }
    }

    static func carResult(_ r: EpisodeResult) -> String {
        switch r { case .success: "그쳤어요"; case .failure: "계속 울었어요"; case .undetermined: "알 수 없음" }
    }
}
