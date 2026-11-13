import Foundation

// 차트에 넣는 값(디자인 v6 시안 js/charts.js의 DATA 자리). 저장하지 않고 그릴 때 기록·리포트에서 계산한다(P4).
// 순수 함수라 XCTest로 본다(ChartDataTests). 실제 데이터에 붙이는 규칙은 안드로이드 ChartData.kt와 같다(진행 기록 '결정' 1~8).

enum ChartData {
    static let minute: Int64 = 60_000

    /// 차트 안 시각만 24시간제 'HH:MM'(결정 11-09, 시안 v6-9). 나머지 화면은 7.1 '오전/오후 h:mm'.
    static func hhmm(_ minuteOfDay: Int) -> String { String(format: "%02d:%02d", (minuteOfDay / 60) % 24, minuteOfDay % 60) }

    // MARK: 오늘 하루 띠(Charts.day)

    struct Span: Equatable { let from: Int, to: Int, tip: String }
    struct Mark: Equatable { let at: Int, tip: String }
    enum LaneKind: Equatable { case sleep, feed, diaper, car }

    /// 줄 하나: 머리('이름 · 부가 · 값')와 트랙 위 표시.
    struct Lane: Equatable {
        let kind: LaneKind, name: String, sub: String?, value: String
        var spans: [Span] = [], marks: [Mark] = []
    }

    struct Day: Equatable {
        let lanes: [Lane]
        /// 오늘이면 지금 분, 아니면 nil.
        let nowMin: Int?
        /// 화면 읽기용 한 줄("오늘 하루: 잠 8시간 5분, 수유 4번, …").
        var summary: String { "오늘 하루: " + lanes.map { "\($0.name) \($0.value)" }.joined(separator: ", ") }
    }

    /// U88 오늘 하루 띠의 새 표시: 막 저장한 기록이 그날 띠의 어느 줄·어느 분에 있는지. 띠에 줄이 없는 종류(메모)·다른 날이면 nil.
    struct Fresh: Hashable { let id: String, lane: LaneKind, at: Int }

    static func fresh(_ e: CareEvent, day: String, zone: TimeZone) -> Fresh? {
        guard let (from, to) = CareRules.dayRange(day, zone), e.startAt >= from, e.startAt < to else { return nil }
        let lane: LaneKind
        switch e.kind { case .feed: lane = .feed; case .sleep: lane = .sleep; case .diaper: lane = .diaper; case .note: return nil }
        return Fresh(id: e.id, lane: lane, at: minuteIn(e.startAt, from: from))
    }

    private static func minuteIn(_ ms: Int64, from: Int64) -> Int { Int(min(1440, max(0, (ms - from) / minute))) }

    /// 오늘 하루 띠. 잠·수유는 그날에 걸친 구간(진행 중은 지금까지, 끝 모름은 시작 점), 기저귀는 시각 점,
    /// '차에서'는 그날 리포트의 탑승 구간(세션 시작~끝, 중간 정차 포함이라 '탑승')과 울음 경보 에피소드 시각. 잠·기저귀를 끄면 그 줄을 뺀다(R16).
    /// 수유·기저귀 횟수는 그날 시작한 기록, 잠은 그날에 걸친 기록 수와 끝난 잠의 길이 합(U31).
    static func day(care: CareFile, reports: [StoredReport], day: String, now: Int64, zone: TimeZone, showCar: Bool) -> Day {
        guard let (from, to) = CareRules.dayRange(day, zone) else { return Day(lanes: [], nowMin: nil) }
        let live = CareRules.live(care.events)
        func end(_ e: CareEvent) -> Int64 { e.endAt ?? (e.inProgress ? now : e.startAt) }
        // 그날과 실제로 겹치는 구간만(자정에 끝난 어제 잠은 빼고), 길이 없는 점(끝 모름)은 그날 시작한 것만(안드로이드 inDay와 같다).
        func inDay(_ start: Int64, _ stop: Int64) -> Bool { start < to && (stop > from || (stop == start && start >= from)) }
        func spans(_ kind: CareKind, _ name: String) -> [Span] {
            live.filter { $0.kind == kind && inDay($0.startAt, end($0)) }.sorted { $0.startAt < $1.startAt }.map { e in
                let a = minuteIn(max(e.startAt, from), from: from), b = minuteIn(min(end(e), to), from: from)
                // 끝 모름은 시작 점 하나(끝 시각을 만들어 보이지 않는다)
                if e.endUnknown { return Span(from: a, to: a, tip: "\(name) \(hhmm(a)) · \(CareText.endUnknown)") }
                // 길이 글은 그날에 든 기록 시각의 차이(분으로 내린 그림 좌표의 차이가 아니다: 기록 목록·잠 합계와 같은 값, 안드로이드와 같다).
                // 붙일지도 그 차이로 본다(같은 분 안에 끝난 40초 잠도 '1분 미만'이 붙는다, 기록 목록과 같은 글)
                let ms = min(end(e), to) - max(e.startAt, from)
                return Span(from: a, to: b, tip: "\(name) \(hhmm(a))–\(hhmm(b))" + (ms > 0 ? " · " + CareFormat.length(ms) : ""))
            }
        }
        func startedToday(_ kind: CareKind) -> [CareEvent] { live.filter { $0.kind == kind && $0.startAt >= from && $0.startAt < to } }
        var lanes: [Lane] = []
        if care.sleepDiaperEnabled {
            let s = spans(.sleep, "잠")
            let total = CareRules.sleepMs(onDay: day, in: live, zone: zone)
            lanes.append(Lane(kind: .sleep, name: "잠", sub: s.isEmpty ? nil : "\(s.count)번", value: s.isEmpty ? "0번" : CareFormat.length(total), spans: s))
        }
        lanes.append(Lane(kind: .feed, name: "수유", sub: nil, value: "\(startedToday(.feed).count)번", spans: spans(.feed, "수유")))
        if care.sleepDiaperEnabled {
            let d = startedToday(.diaper).sorted { $0.startAt < $1.startAt }
            lanes.append(Lane(kind: .diaper, name: "기저귀", sub: nil, value: "\(d.count)번",
                              marks: d.map { e in let m = minuteIn(e.startAt, from: from); return Mark(at: m, tip: "기저귀 \(hhmm(m))") }))
        }
        if showCar {
            var drives: [Span] = [], cries: [Mark] = []
            var driveMs: Int64 = 0   // 탑승 합계도 그날에 든 세션 시각의 차이를 더한다(그림 좌표는 분으로 내림)
            for r in reports {
                let b = r.body
                if let s = Rfc3339.parse(b.startedAt).map(CareTime.ms) {
                    let e = Rfc3339.parse(b.endedAt).map(CareTime.ms) ?? s
                    if inDay(s, e) {
                        let inS = max(s, from), inE = min(e, to)
                        let a = minuteIn(inS, from: from), z = minuteIn(inE, from: from)
                        drives.append(Span(from: a, to: z, tip: "탑승 \(hhmm(a))–\(hhmm(z)) · " + CareFormat.length(inE - inS)))
                        driveMs += inE - inS
                    }
                }
                for ep in b.timeline where ep.trigger == .cryAlarm {
                    guard let at = Rfc3339.parse(ep.startedAt).map(CareTime.ms), at >= from, at < to else { continue }
                    let m = minuteIn(at, from: from)
                    cries.append(Mark(at: m, tip: "차에서 울음 \(hhmm(m))"))
                }
            }
            drives.sort { $0.from < $1.from }
            cries.sort { $0.at < $1.at }
            lanes.append(Lane(kind: .car, name: "차에서", sub: driveMs > 0 ? "탑승 " + CareFormat.length(driveMs) : nil,
                              value: "울음 \(cries.count)번", spans: drives, marks: cries))
        }
        return Day(lanes: lanes, nowMin: now >= from && now < to ? minuteIn(now, from: from) : nil)
    }

    // MARK: 7일 막대·히트맵(Charts.week·heat)

    /// 막대 하나. 값이 없으면 nil(막대 없음).
    struct Bar: Equatable { let day: String, label: String, value: Int64?, tip: String? }

    /// 지난 7일(오늘 포함, 오래된 것부터).
    static func lastWeek(now: Int64, zone: TimeZone) -> [String] { CareStats.lastDays(7, now: now, zone: zone) }

    /// 막대 밑 이름: 요일 한 글자, 오늘은 "오늘".
    static func weekLabel(_ day: String, today: String) -> String { day == today ? "오늘" : CareRules.weekdayName(day) }

    /// 툴팁 날짜 "11월 9일".
    static func monthDay(_ day: String) -> String {
        let p = day.split(separator: "-").compactMap { Int($0) }
        return p.count == 3 ? "\(p[1])월 \(p[2])일" : day
    }

    /// 수유 간격 7일 막대 + '평소'(앞 6일 모든 간격의 중앙값, R14와 같이 10시간 넘는 간격은 빼고 5개 이상일 때만).
    struct IntervalWeek: Equatable { let bars: [Bar], usualMs: Int64?, todayMs: Int64? }

    static func intervalWeek(care: CareFile, now: Int64, zone: TimeZone) -> IntervalWeek {
        let days = lastWeek(now: now, zone: zone)
        let today = days.last!
        // 간격은 뒤 수유가 시작한 날에 넣는다.
        let gaps = CareStats.feedGaps(CareStats.feedStarts(care.events, until: now)).map { (day: CareRules.dayKey($0.end, zone), ms: $0.ms) }
        let bars = days.map { d -> Bar in
            let v = CareStats.median(gaps.filter { $0.day == d }.map(\.ms))
            return Bar(day: d, label: weekLabel(d, today: today), value: v, tip: v.map { "\(monthDay(d)) · " + CareFormat.length($0) })
        }
        return IntervalWeek(bars: bars, usualMs: CareStats.usualFeedGapMs(care.events, now: now, zone: zone), todayMs: bars.last?.value)
    }

    /// 차에서 울음 7일 막대(리포트 울음 경보 에피소드 수) + 평균 감지 지연(초, 없으면 nil).
    struct CarCryWeek: Equatable { let bars: [Bar], total: Int, avgLatencyS: Double? }

    static func carCryWeek(reports: [StoredReport], now: Int64, zone: TimeZone) -> CarCryWeek {
        let days = lastWeek(now: now, zone: zone)
        let today = days.last!
        let eps = CareStats.cryAlarms(reports).map { (CareRules.dayKey(CareTime.ms($0.at), zone), $0.episode) }.filter { days.contains($0.0) }
        let bars = days.map { d -> Bar in
            let n = Int64(eps.filter { $0.0 == d }.count)
            return Bar(day: d, label: weekLabel(d, today: today), value: n, tip: "\(monthDay(d)) · \(n)번")
        }
        let lat = eps.compactMap(\.1.alarmLatencyS)
        return CarCryWeek(bars: bars, total: eps.count, avgLatencyS: lat.isEmpty ? nil : lat.reduce(0, +) / Double(lat.count))
    }

    /// 잠 패턴 히트맵: 7일 × 48칸(30분). 잠 기록(끝난 것, 진행 중은 지금까지)과 겹친 칸이 참.
    struct SleepGrid: Equatable {
        let days: [String], labels: [String], rows: [[Bool]], avgMs: Int64?, sleepDays: Int
        /// 날마다 끝난 잠이 그날에 걸친 길이(화면 읽기용).
        var perDayMs: [Int64] = []
        /// 화면 읽기는 칸 수가 아니라 실제 길이("지난 7일 잠 패턴: 월 3시간 10분, …").
        var summary: String { "지난 7일 잠 패턴: " + labels.indices.map { "\(labels[$0]) " + CareFormat.length(perDayMs.indices.contains($0) ? perDayMs[$0] : 0) }.joined(separator: ", ") }
    }

    static func sleepGrid(care: CareFile, now: Int64, zone: TimeZone) -> SleepGrid {
        let days = lastWeek(now: now, zone: zone)
        let today = days.last!
        let sleeps = CareRules.live(care.events).filter { $0.kind == .sleep && ($0.endAt != nil || $0.inProgress) }
        let rows = days.map { d -> [Bool] in
            guard let (from, _) = CareRules.dayRange(d, zone) else { return Array(repeating: false, count: 48) }
            return (0..<48).map { c in
                let a = from + Int64(c) * 30 * minute, b = a + 30 * minute
                return sleeps.contains { $0.startAt < b && ($0.endAt ?? now) > a }
            }
        }
        // U31·패턴 '하루 잠'과 같은 값: 지난 7일(오늘 포함) 중 끝난 잠이 그날에 걸친 시간이 0보다 큰 날의 평균(같은 화면에서 두 숫자가 어긋나지 않게).
        let all = days.map { CareRules.sleepMs(onDay: $0, in: care.events, zone: zone) }
        return SleepGrid(days: days, labels: days.map { weekLabel($0, today: today) }, rows: rows,
                         avgMs: CareStats.perRecordedDay(all), sleepDays: all.filter { $0 > 0 }.count, perDayMs: all)
    }

    /// '지난주보다 {n}번 많아요/적어요'(문구표 design.chart, 시안 S.pattern): 지난 7일 하루 평균 수유 − 그 앞 7일 하루 평균.
    /// 평균은 패턴과 같은 규칙(그날 시작한 수유 수를 수유한 날 수로 나눔, 소수 첫째 자리). 앞 7일에 수유한 날이 없거나 차이가 0.0이면 nil.
    static func feedTrend(care: CareFile, now: Int64, zone: TimeZone) -> String? {
        let days = lastWeek(now: now, zone: zone)
        let cal = CareRules.calendar(zone)
        let feeds = CareRules.live(care.events).filter { $0.kind == .feed }.map { CareRules.dayKey($0.startAt, zone) }
        func avg(_ ds: [String]) -> Double? { CareStats.perRecordedDay(ds.map { d in feeds.filter { $0 == d }.count }) }
        let prevDays = days.compactMap { d -> String? in
            CareRules.dayRange(d, zone).flatMap { cal.date(byAdding: .day, value: -7, to: CareTime.date($0.0)) }.map { CareRules.dayKey(CareTime.ms($0), zone) }
        }
        guard let now7 = avg(days), let prev7 = avg(prevDays) else { return nil }
        let d = now7 - prev7   // 평균끼리 뺀 뒤 마지막에만 반올림한다
        let n = CareText.oneDecimal(abs(d))   // 안드로이드 %.1f(HALF_UP)와 같게
        if n == "0.0" { return nil }
        return d > 0 ? "지난주보다 \(n)번 많아요" : "지난주보다 \(n)번 적어요"
    }

    // MARK: 리포트(Charts.episode·methods·steer)

    struct Seg: Equatable {
        let from: Int, to: Int
        /// 재생이면 소리 이름. 재생 사이 칸은 nil: 그 사이 통화 등으로 판정을 멈췄을 수도 있는데 리포트에는 그 칸이 없어
        /// '듣기 틈'이라 단정하지 않는다(차 리포트 soundTip과 같다).
        let name: String?
        /// 재생이면 리포트의 `play_s`(보이는 길이는 이 값, 그림의 칸은 시각으로). 재생 사이 칸은 nil(칸 길이).
        var playS: Int? = nil
        var seconds: Int { playS ?? (to - from) }
        /// 툴팁·화면 읽기 글: "자장가 56초", 재생 사이 칸은 "8초".
        var tip: String { name.map { "\($0) \(seconds)초" } ?? "\(seconds)초" }
    }
    struct EpisodeMark: Equatable { let at: Int, label: String }
    /// 에피소드 띠. 초는 감지(에피소드 시작)부터. 리포트에 울음 구간이 없어(`totalCryS`만) 울음 줄은 그리지 않는다.
    /// 시간축 표시(`marks`)는 시각을 아는 것만(시작, 자동 2차 시작), 결과(쉼터 제안·그쳤어요)는 그 시각이 리포트에 없어 축 밖 `outcomes`로 둔다
    /// (마지막 재생 끝에 찍으면 거짓 시각이 된다). 안드로이드 episodeBand와 같다.
    struct Episode: Equatable { let totalS: Int, sounds: [Seg], gaps: [Seg], marks: [EpisodeMark], outcomes: [String] }

    /// 에피소드 띠의 읽기 이름: 머리는 에피소드를 연 사건(울음 경보 '울음', 소리 바꾸기 요청 '소리 바꾸기 요청') + 재생마다 길이·표시·결과.
    static func episodeSummary(_ e: TimelineEpisode, _ band: Episode) -> String {
        CareText.episodeHead(e.trigger) + ": " + (band.sounds.map(\.tip) + band.marks.map(\.label) + band.outcomes).joined(separator: ", ")
    }

    static func episode(_ e: TimelineEpisode, playName: (PlayEntry) -> String) -> Episode {
        let t0 = Rfc3339.parse(e.startedAt)
        func sec(_ s: String) -> Int? { t0.flatMap { base in Rfc3339.parse(s).map { max(0, Int($0.timeIntervalSince(base))) } } }
        let timed = e.plays.compactMap { p -> (PlayEntry, Seg)? in
            guard let a = sec(p.startedAt) else { return nil }
            let b = sec(p.endedAt) ?? (a + p.playS)
            return (p, Seg(from: a, to: max(b, a + 1), name: playName(p), playS: p.playS))   // 칸은 최소 1초 폭, 길이 글은 play_s 그대로
        }.sorted { $0.1.from < $1.1.from }
        let plays = timed.map(\.1)
        let gaps = zip(plays, plays.dropFirst()).compactMap { x, y in y.from > x.to ? Seg(from: x.to, to: y.from, name: nil) : nil }
        let end = max(plays.map(\.to).max() ?? 0, 1)
        // 시작 표시: 울음 경보면 '감지 {지연}초', 소리 바꾸기 요청이면 그 이름(울음을 감지했다고 보이지 않게).
        var marks = [EpisodeMark(at: 0, label: e.trigger != .cryAlarm ? CareText.episodeStart(e.trigger) : e.alarmLatencyS.map { "감지 " + seconds($0) } ?? "감지")]
        // 다시 울음: 그 때문에 시작한 자동 2차 재생의 시작(운전자 요청 재생은 다시 울음이 아니다). 없으면 표시하지 않는다.
        if e.recried, let second = timed.first(where: { $0.0.purpose == .autoSecond }) { marks.append(EpisodeMark(at: second.1.from, label: "다시 울음")) }
        var outcomes: [String] = []
        // 운전자에게 실제로 묻지 못한 쉼터는 제안했다고 보이지 않는다(U53, stopText와 같은 규칙).
        if let st = e.stop, st.mode == .restOffer, st.toldDriver != false { outcomes.append("쉼터 제안") }
        if e.result == .success || e.second?.result == .success { outcomes.append(CareText.result(.success)) }
        return Episode(totalS: end, sounds: plays, gaps: gaps, marks: marks, outcomes: outcomes)
    }

    /// 리포트 숫자 줄: 감지까지 = `alarmLatencyS` 평균, 울음 시간 = `totalCryS` 합(그친 시각은 리포트에 없어 '그칠 때까지'로 쓰지 않는다),
    /// 차가 한 일 = `carActions` 종류 수, 정차 안내 = 운전자에게 말한 인계 수(안드로이드와 같다).
    struct Kpis: Equatable { let detectS: Double?, cryS: Int, actionKinds: Int, stopNotices: Int }

    static func kpis(_ b: ReportSummaryBody) -> Kpis {
        let lat = b.timeline.compactMap(\.alarmLatencyS)
        return Kpis(detectS: lat.isEmpty ? nil : lat.reduce(0, +) / Double(lat.count), cryS: b.timeline.reduce(0) { $0 + $1.totalCryS },
                    actionKinds: Set(b.carActions.filter { $0.count > 0 }.map(\.action)).count,
                    stopNotices: b.timeline.compactMap(\.stop).filter { $0.toldDriver != false && $0.mode != .parked }.count)
    }

    /// 탑승 구간 길이(분).
    static func reportMinutes(_ b: ReportSummaryBody) -> Int64? {
        guard let s = Rfc3339.parse(b.startedAt), let e = Rfc3339.parse(b.endedAt) else { return nil }
        let m = Int64(e.timeIntervalSince(s) / 60)
        return m >= 0 ? m : nil
    }

    /// 소수 한 자리 초("2.4초").
    static func seconds(_ s: Double) -> String { CareText.oneDecimal(s) + "초" }   // 안드로이드 %.1f(HALF_UP)와 같게

    /// "1분당 큰 조향 보정" 값("0.8").
    static func perMin(_ v: Double) -> String { CareText.oneDecimal(v) }

    // MARK: 마지막 수유 막대

    /// 엔진 `Params.feedingWindowStart`(다음 수유 시간대 시작 = 마지막 수유 + 간격 × 0.8).
    static let feedingWindowStart = 0.8

    /// 마지막 수유 막대(시안 .pmeter): 채움 = 지난 시간 / (간격 × 1.25), 눈금 = 다음 수유 시간대 시작의 자리.
    /// 간격은 차 값이 있으면 차에 정해 둔 간격((시간대 시작 − 차의 마지막 수유) ÷ 0.8), 없으면 지난 7일 '평소'(앞 6일 중앙값).
    /// 둘 다 없으면 nil(막대를 그리지 않는다). 눈금은 차 값이 있고 그 시각이 마지막 수유 뒤일 때만.
    static func feedMeter(last: Int64, now: Int64, carLast: Int64?, windowStart: Int64?, usualMs: Int64?) -> (value: Double, tick: Double?)? {
        let interval: Int64?
        if let c = carLast, let w = windowStart, w > c { interval = Int64(Double(w - c) / feedingWindowStart) } else { interval = usualMs }
        guard let interval, interval > 0 else { return nil }
        let full = Double(interval) * 1.25
        let tick = windowStart.flatMap { $0 > last ? min(1, max(0, Double($0 - last) / full)) : nil }
        return (min(1, max(0, Double(now - last) / full)), tick)
    }
}
