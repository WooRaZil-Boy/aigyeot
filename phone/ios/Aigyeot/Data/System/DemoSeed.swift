#if DEBUG
import Foundation

/// debug 빌드만: 시연 시드. 안드로이드 `DebugSeed.kt`와 같은 목록이다. release에는 들어가지 않는다.
/// 넣기: `xcrun simctl launch --terminate-running-process <기기> kr.aigyeot.phone -demoSeed`
enum DemoSeed {
    private static let minute: Int64 = 60_000
    /// 지난 6일은 날마다 같은 목록(현지 "시:분", 길이 분). 잠은 그날 안에서 끝나 오늘 칸에 섞이지 않는다.
    private static let pastFeeds = ["06:50", "09:50", "12:50", "15:50", "18:50", "21:30", "23:40"]   // 20분씩, 왼쪽·오른쪽 번갈아
    /// 끝도 현지 벽시계다. 서머타임이 바뀐 날에도 다음 잠과 겹치지 않는다(안드로이드와 같다).
    private static let pastSleeps = [("00:10", "06:40"), ("07:20", "09:40"), ("10:20", "12:40"), ("16:20", "18:40")]   // 합 13시간 30분
    private static let pastDiapers: [(String, DiaperKind)] = [
        ("06:45", .wet), ("09:45", .dirty), ("12:45", .wet), ("15:45", .wet), ("18:45", .both), ("23:35", .wet)
    ]

    /// 기준은 넣는 순간(분 내림). 오늘 값은 차 시연 장면의 오늘 값과 같다. 별명은 그대로 두고 잠·기저귀는 켠다.
    static func make(nowMs: Int64, zone: TimeZone, keep: CareFile = CareFile()) -> CareFile {
        let t = nowMs / minute * minute
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let today = cal.startOfDay(for: CareTime.date(t))
        var out: [CareEvent] = []
        func add(_ kind: CareKind, _ start: Int64, end: Int64? = nil, side: FeedSide? = nil, diaper: DiaperKind? = nil) {
            out.append(CareEvent(id: ULID.generate(date: CareTime.date(start)), kind: kind, startAt: start, endAt: end, zone: zone.identifier,
                                 feedSide: side, diaper: diaper, createdAt: end ?? start, updatedAt: end ?? start))
        }
        func hm(_ day: Date, _ s: String) -> Int64 {
            let p = s.split(separator: ":").map { Int($0)! }
            var c = cal.dateComponents([.year, .month, .day], from: day)
            c.hour = p[0]
            c.minute = p[1]
            // 서머타임으로 없는 시각은 건너뛴 길이만큼 뒤로, 되풀이되는 시각은 앞쪽 시차로(안드로이드 onDate의 java.time과 같다).
            // bySettingHour는 없는 00:10을 01:00으로 옮겨 안드로이드(01:10)와 갈린다.
            return CareTime.ms(cal.date(from: c)!)
        }
        for d in (1...6).reversed() {
            let day = cal.date(byAdding: .day, value: -d, to: today)!
            for (i, s) in pastFeeds.enumerated() { add(.feed, hm(day, s), end: hm(day, s) + 20 * minute, side: i % 2 == 0 ? .left : .right) }
            // 서머타임이 바뀐 날은 밤잠(00:10~06:40)이 한 시간 짧거나 길다. 합을 13~14시간에 두려고
            // 짧으면 낮잠 셋의 시작을 10분씩 당기고(수유 끝과 닿을 뿐 겹치지 않는다), 길면 밤잠 시작을 00:40으로 늦춘다.
            // 길면 밤잠을 끝에서 7시간 전(실제 시각)에 시작해 합 14시간. 두 시간이 바뀌는 지역도 같다.
            // 짧으면 낮잠 시작을 당기는데, 앞 수유 끝(10분 전)까지만 당길 수 있어 한 시간 짧은 날까지 13시간을 맞춘다.
            let night = hm(day, pastSleeps[0].1) - hm(day, pastSleeps[0].0)
            let pull = min(10 * minute, max(0, 390 * minute - night) / 3)
            for (i, (s, e)) in pastSleeps.enumerated() {
                if i == 0 {
                    add(.sleep, night > 420 * minute ? hm(day, e) - 420 * minute : hm(day, s), end: hm(day, e))
                } else {
                    add(.sleep, hm(day, s) - pull, end: hm(day, e))
                }
            }
            for (s, k) in pastDiapers { add(.diaper, hm(day, s), diaper: k) }
        }
        // 시드 시각이 이르면(오전 6시 40분 전) 오늘 장면이 어제로 넘어간다. 겹치지 않게 그 구간의 어제 기록을 뺀다.
        out.removeAll { ($0.endAt ?? $0.startAt) > t - 400 * minute }   // 시작만 보면 끝이 오늘 장면의 수유와 겹치는 잠이 남는다
        let night = hm(today, "01:00")
        if night + 60 * minute <= t - 360 * minute { add(.sleep, night, end: night + 60 * minute) }   // 오늘 첫 수유(t-360) 전에 끝날 때만(겹치지 않게, 이른 시드에서는 뺀다)
        add(.feed, t - 360 * minute, end: t - 340 * minute, side: .left)
        add(.diaper, t - 330 * minute, diaper: .dirty)
        add(.sleep, t - 210 * minute, end: t - 150 * minute)
        add(.feed, t - 120 * minute, end: t - 100 * minute, side: .both)
        add(.diaper, t - 60 * minute, diaper: .wet)
        var file = keep
        file.events = out.sorted { $0.startAt < $1.startAt }
        file.sleepDiaperEnabled = true
        file.migratedFeedings = true
        return file
    }
}
#endif
