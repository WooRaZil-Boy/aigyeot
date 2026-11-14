import Foundation

/// 돌봄 기록·리포트 집계의 공통 규칙 한 곳(안드로이드 `CareStats`와 같은 이름). 패턴 숫자 칸(`CareRules.pattern`), 7일 막대·히트맵·지난주 대비
/// (`ChartData`), 오늘 간격 막대, 차 기록 줄이 같은 함수를 쓴다. 기간이 다른 곳(7일 숫자 칸, 앞 6일 '평소')은 기간을 인자로 넘긴다.
enum CareStats {
    /// 오늘을 포함한 지난 `count`일의 날 키(오래된 날부터).
    static func lastDays(_ count: Int, now: Int64, zone: TimeZone) -> [String] {
        let cal = CareRules.calendar(zone)
        let today = cal.startOfDay(for: CareTime.date(now))
        return (0..<count).reversed().map { CareRules.dayKey(CareTime.ms(cal.date(byAdding: .day, value: -$0, to: today)!), zone) }
    }

    /// 수유 시작 시각(지운 기록 빼고, `from` ≤ 시작 ≤ `until`, 오름차순).
    static func feedStarts(_ events: [CareEvent], from: Int64 = .min, until: Int64 = .max) -> [Int64] {
        CareRules.live(events).filter { $0.kind == .feed && $0.startAt >= from && $0.startAt <= until }.map(\.startAt).sorted()
    }

    /// 이웃 수유 사이 간격(뒤 수유의 시작, 길이). 10시간 넘는 간격은 밤잠·빠진 기록으로 보고 뺀다.
    static func feedGaps(_ starts: [Int64]) -> [(end: Int64, ms: Int64)] {
        zip(starts, starts.dropFirst()).map { (end: $1, ms: $1 - $0) }.filter { $0.ms <= CareRules.gapMaxMs }
    }

    /// 중앙값(짝수 개면 가운데 둘의 평균을 내림). 비었으면 nil.
    static func median(_ xs: [Int64]) -> Int64? {
        let s = xs.sorted()
        if s.isEmpty { return nil }
        return s.count % 2 == 1 ? s[s.count / 2] : (s[s.count / 2 - 1] + s[s.count / 2]) / 2
    }

    /// '평소' 간격: 간격이 `CareRules.minGaps`개 이상일 때만 중앙값.
    static func usualGap(_ gaps: [Int64]) -> Int64? { gaps.count < CareRules.minGaps ? nil : median(gaps) }

    /// 오늘 앞 `days`일 동안 끝난(뒤 수유가 시작한) 수유 간격의 '평소': 오늘 간격 막대·마지막 수유 막대와 견주는 값.
    static func usualFeedGapMs(_ events: [CareEvent], now: Int64, zone: TimeZone, days: Int = 6) -> Int64? {
        let prior = Set(lastDays(days + 1, now: now, zone: zone).dropLast())
        return usualGap(feedGaps(feedStarts(events, until: now)).filter { prior.contains(CareRules.dayKey($0.end, zone)) }.map(\.ms))
    }

    /// 기록한 날만 나눠 센 하루 평균: 0보다 큰 날의 합 ÷ 그 날 수. 그런 날이 없으면 nil.
    static func perRecordedDay(_ values: [Int]) -> Double? {
        let per = values.filter { $0 > 0 }
        return per.isEmpty ? nil : Double(per.reduce(0, +)) / Double(per.count)
    }

    /// 같은 규칙의 길이 평균(잠, 밀리초 내림). 그런 날이 없으면 nil.
    static func perRecordedDay(_ values: [Int64]) -> Int64? {
        let per = values.filter { $0 > 0 }
        return per.isEmpty ? nil : per.reduce(0, +) / Int64(per.count)
    }

    /// 리포트의 울음 경보 에피소드와 그 시작 시각(소리 바꾸기 요청 에피소드는 빼고, 시각을 읽지 못하면 뺀다).
    static func cryAlarms(_ reports: [StoredReport]) -> [(at: Date, episode: TimelineEpisode)] {
        reports.flatMap(\.body.timeline).filter { $0.trigger == .cryAlarm }.compactMap { e in Rfc3339.parse(e.startedAt).map { (at: $0, episode: e) } }
    }

    /// 방법별 성적에서 판정할 수 있는 최소 횟수(이보다 적으면 '아직 모름').
    static let methodKnownAttempts = 3

    /// 방법별 성적 순서: 판정할 수 있는 것 먼저, 그 안은 그친 비율이 높은 순, 같으면 받은 순. 받은 차례(index)를 함께 준다.
    static func methodOrder(_ stats: [MethodStat]) -> [(index: Int, stat: MethodStat)] {
        // 리포트 값은 차가 보낸 그대로라 음수·뒤집힌 값이 올 수 있다: 0 ≤ 그침 ≤ 횟수로 묶어 센다(점 행렬과 같은 값)
        func rate(_ s: MethodStat) -> Double {
            let n = max(0, s.attempts), ok = min(max(0, s.successes), n)
            return n > 0 ? Double(ok) / Double(n) : 0
        }
        return stats.enumerated().map { (index: $0.offset, stat: $0.element) }.sorted { a, b in
            let ka = a.stat.attempts >= methodKnownAttempts, kb = b.stat.attempts >= methodKnownAttempts
            if ka != kb { return ka }
            if rate(a.stat) != rate(b.stat) { return rate(a.stat) > rate(b.stat) }
            return a.index < b.index
        }
    }
}
