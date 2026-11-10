import Foundation

// MARK: 규칙(6장 R1~R17)

enum CareRules {
    static let futureSlackMs: Int64 = 60_000
    /// 시각 고르기의 끝(지금 + R3 여유 1분). 화면의 고르기 범위도 이 값이다.
    static func latestPick(now: Date) -> Date { now.addingTimeInterval(Double(futureSlackMs) / 1000) }
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

    /// R8: 지운 지 30일이 된 기록을 실제로 지운다(안드로이드 `now - deletedAt < 30일`만 남김과 같은 경계).
    static func purged(_ events: [CareEvent], now: Int64) -> [CareEvent] {
        events.filter { e in e.deletedAt.map { now - $0 < purgeAfterMs } ?? true }
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
    /// 종류에 맞지 않는 칸을 비운다: 쪽은 수유만, 양은 젖병 수유만, 기저귀 종류는 기저귀만, 메모는 앞뒤 공백을 걷고 비면 없음.
    /// 기록 시트(`CareDraft.event`)와 타이머(쪽·양 고침)가 같은 규칙을 쓴다.
    static func normalized(_ e: CareEvent) -> CareEvent {
        var x = e
        if x.kind != .feed { x.feedSide = nil }
        if x.kind != .feed || x.feedSide != .bottle { x.amountMl = nil }
        if x.kind != .diaper { x.diaper = nil }
        let n = (x.note ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        x.note = n.isEmpty ? nil : n
        return x
    }

    static func noteLimited(_ s: String) -> String {
        s.unicodeScalars.count <= noteMax ? s : String(String.UnicodeScalarView(s.unicodeScalars.prefix(noteMax)))
    }

    /// 빠른 버튼(R16, 7장 순서).
    static func quickKinds(sleepDiaper: Bool) -> [CareKind] { sleepDiaper ? [.feed, .note, .sleep, .diaper] : [.feed, .note] }

    /// R15: 경과는 시작 시각으로 계산한다.
    static func elapsedMs(_ e: CareEvent, now: Int64) -> Int64 { max(0, now - e.startAt) }

    // MARK: 날짜(R13)

    static func calendar(_ zone: TimeZone) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = zone
        return c
    }

    static func dayKey(_ ms: Int64, _ zone: TimeZone) -> String {
        let d = calendar(zone).dateComponents([.year, .month, .day], from: CareTime.date(ms))
        return String(format: "%04d-%02d-%02d", d.year!, d.month!, d.day!)
    }

    /// 그날 0시부터 다음 날 0시 전까지(ms). 0시가 없는 날(서머타임)은 그날 첫 시각부터다(안드로이드 `atStartOfDay`와 같다).
    static func dayRange(_ day: String, _ zone: TimeZone) -> (Int64, Int64)? {
        let p = day.split(separator: "-").compactMap { Int($0) }
        let cal = calendar(zone)
        // 끝은 다음 날의 0시다. 시작(01:00일 수 있음)에 하루를 더하면 다음 날 01:00이 돼 한 시간을 겹쳐 센다.
        guard p.count == 3, let start = cal.date(from: DateComponents(year: p[0], month: p[1], day: p[2])),
              let next = cal.date(byAdding: .day, value: 1, to: start).map(cal.startOfDay) else { return nil }
        return (CareTime.ms(start), CareTime.ms(next))
    }

    /// 날짜 키의 요일(일~토). 기기 달력·시간대와 상관없이 키(그레고리력)에서 구한다.
    static func weekdayName(_ key: String) -> String {
        let p = key.split(separator: "-").compactMap { Int($0) }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        guard p.count == 3, let d = cal.date(from: DateComponents(year: p[0], month: p[1], day: p[2], hour: 12)) else { return "" }
        return ["일", "월", "화", "수", "목", "금", "토"][cal.component(.weekday, from: d) - 1]
    }

    /// 날짜 띠의 키: 기기 시간대의 날들에 기록이 있는 날을 더한다(기록은 자기 시간대의 날이다, R13).
    /// 기기 시간대에 없는 날(날짜 변경선을 옮긴 사모아의 2011-12-30 등)의 기록도 열 수 있게.
    static func stripKeys(_ days: [String], records: Set<String>) -> [String] { Array(Set(days).union(records)).sorted() }

    /// 그날 벽시계 낮 12시(ms). 하루 시작 + 12시간이 아니다: 0시가 없는 날·서머타임이 바뀌는 날엔 11시나 13시가 된다(안드로이드 `onDate(day, 12, 0)`).
    static func noon(_ day: String, _ zone: TimeZone) -> Int64? {
        guard let r = dayRange(day, zone),
              let d = calendar(zone).date(bySettingHour: 12, minute: 0, second: 0, of: CareTime.date(r.0)) else { return nil }
        return CareTime.ms(d)
    }

    /// 날짜 띠의 날들(첫날~마지막 날, 둘 다 포함). 칸 수는 날짜 성분으로 센다. 시각 차이로 세면 첫날이 01:00에 시작하는 날(0시가 없는 날)에
    /// 하루가 모자라 '오늘' 칸이 빠진다(안드로이드 `LocalDate.minusDays`와 같게).
    static func days(from first: Date, through last: Date, zone: TimeZone) -> [(key: String, date: Date)] {
        let cal = calendar(zone)
        // 정오끼리 센다(정오는 늘 있다). 날짜 성분끼리 빼도 안에서 0시로 바꿔 세어 0시가 없는 날엔 하루가 모자랐다.
        func noon(_ d: Date) -> Date { cal.date(bySettingHour: 12, minute: 0, second: 0, of: d)! }
        let n = cal.dateComponents([.day], from: noon(first), to: noon(last)).day ?? 0
        return (0...max(0, n)).map { i in
            let d = cal.date(byAdding: .day, value: i, to: first)!
            return (dayKey(CareTime.ms(d), zone), d)
        }
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
        let days = CareStats.lastDays(7, now: now, zone: zone)
        let windowStart = dayRange(days[0], zone)!.0   // 첫날의 0시(안드로이드 `days.first().atStartOfDay`)
        let alive = live(events)
        let feeds = CareStats.feedStarts(alive, from: windowStart)   // 횟수는 R3의 1분 여유 수유도 센다(기저귀·안드로이드 countPerDay와 같다)
        let episodes = CareStats.cryAlarms(reports).map { CareTime.ms($0.at) }

        let stats = days.map { day in
            let sleep = sleepDiaper ? sleepMs(onDay: day, in: alive, zone: zone) : nil
            return CareDayStat(day: day,
                        feeds: feeds.filter { dayKey($0, zone) == day }.count,
                        sleepMs: sleep,
                        sleepRecorded: (sleep ?? 0) > 0,   // U31: 끝난 잠이 그날에 걸친 날만
                        diapers: sleepDiaper ? alive.filter { $0.kind == .diaper && dayKey($0.startAt, zone) == day }.count : nil,
                        carEpisodes: episodes.filter { dayKey($0, zone) == day }.count)
        }
        let past = feeds.filter { $0 <= now }   // 간격은 지난 수유만(안드로이드 intervals와 같다)
        return CarePattern(days: stats, feedIntervalMedianMs: CareStats.usualGap(CareStats.feedGaps(past).map(\.ms)))
    }

    // MARK: 차 기록 줄(R17)

    static func carRows(_ reports: [StoredReport], zone: TimeZone) -> [CarRow] {
        reports.flatMap { r -> [CarRow] in
            let b = r.body
            var rows: [CarRow] = []
            if let start = Rfc3339.parse(b.startedAt) {
                let end = Rfc3339.parse(b.endedAt)
                rows.append(CarRow(id: "drive-\(b.sessionId)", sessionId: b.sessionId, at: start,
                                   text: "탑승 · " + CareFormat.clockRange(start, end, zone)))   // 세션 구간은 중간 정차가 들어 있어 '주행'이라 하지 않는다(7장, 차·두 폰 통일)
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
