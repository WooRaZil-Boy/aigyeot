import XCTest
@testable import Aigyeot

/// 차트 값(디자인 v6, 진행 기록 '결정' 1~8): 기록·리포트에서 그릴 때 계산한 값이 규칙대로다. 안드로이드 ChartDataTest와 같은 경우.
final class ChartDataTests: XCTestCase {
    private let zone = TimeZone(identifier: "Asia/Seoul")!
    private let min: Int64 = 60_000
    private var n = 0

    private func at(_ h: Int, _ m: Int = 0, daysAgo: Int = 0) -> Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let day = cal.date(byAdding: .day, value: -daysAgo, to: cal.date(from: DateComponents(year: 2026, month: 11, day: 9))!)!
        return CareTime.ms(cal.date(bySettingHour: h, minute: m, second: 0, of: day)!)
    }
    private var now: Int64 { at(14, 13) }
    private let today = "2026-11-09"

    private func ev(_ kind: CareKind, _ start: Int64, _ end: Int64? = nil, deleted: Bool = false, endUnknown: Bool = false) -> CareEvent {
        n += 1
        return CareEvent(id: "e\(n)", kind: kind, startAt: start, endAt: end, endUnknown: endUnknown, zone: zone.identifier,
                         createdAt: start, updatedAt: start, deletedAt: deleted ? start : nil)
    }

    private func report() throws -> ReportSummaryBody {
        let env = try ProtocolCoding.decoder.decode(Envelope.self, from: Data(contentsOf: ProtocolFixturesTests.dir.appendingPathComponent("report_summary.json")))
        return try env.decodeBody(ReportSummaryBody.self)
    }

    private func iso(_ ms: Int64) -> String { Rfc3339.string(CareTime.date(ms)) }

    private func stored(_ b: ReportSummaryBody) -> StoredReport { StoredReport(messageId: "m", seq: 1, receivedAt: Date(), body: b) }

    /// 하루 띠 누르기: 닿는 후보 중 점(울음 마름모·기저귀 점)이 있으면 점, 없으면 가장 가까운 칸(같으면 작은 면적). 안드로이드와 같은 규칙.
    func test_차트_누름은_닿는_점이_칸보다_먼저이고_닿는_것이_없으면_없다() {
        let drive = ChartHit(rect: CGRect(x: 100, y: 10, width: 40, height: 14), tip: "탑승")
        let cry = ChartHit(rect: CGRect(x: 110, y: 10, width: 14, height: 14), tip: "울음", point: true)
        XCTAssertEqual(ChartHit.pick([drive, cry], at: CGPoint(x: 117, y: 17)), cry)     // 탑승 칸 안의 마름모
        XCTAssertEqual(ChartHit.pick([drive, cry], at: CGPoint(x: 136, y: 17)), drive)   // 칸 안이지만 마름모에서 6pt 넘게 떨어짐
        XCTAssertNil(ChartHit.pick([drive, cry], at: CGPoint(x: 300, y: 100)))
        // 마름모 자리보다 좁은 탑승 칸(짧은 탑승): 둘 다 담아도 마름모(면적이 작은 칸이 이기지 않는다)
        let narrow = ChartHit(rect: CGRect(x: 114, y: 10, width: 6, height: 14), tip: "짧은 탑승")
        XCTAssertEqual(ChartHit.pick([narrow, cry], at: CGPoint(x: 117, y: 17)), cry)
        // 점이 없으면 예전과 같다: 담은 칸 중 작은 것, 담은 것이 없으면 6pt 안에서 가까운 것
        let wide = ChartHit(rect: CGRect(x: 0, y: 0, width: 200, height: 40), tip: "넓은 칸")
        XCTAssertEqual(ChartHit.pick([wide, drive], at: CGPoint(x: 120, y: 17)), drive)
        XCTAssertEqual(ChartHit.pick([drive], at: CGPoint(x: 144, y: 17)), drive)
    }

    func test_차트_안_시각은_24시간제_HH_MM이다() {
        XCTAssertEqual(ChartData.hhmm(0), "00:00")
        XCTAssertEqual(ChartData.hhmm(14 * 60 + 13), "14:13")
    }

    func test_오늘_하루_띠는_그날에_걸친_구간을_자르고_횟수는_규칙대로_센다() {
        var care = CareFile()
        care.sleepDiaperEnabled = true
        care.events = [
            ev(.sleep, at(23, 0, daysAgo: 1), at(1, 0)),   // 어제 밤 → 오늘 0~60분
            ev(.sleep, at(10, 20), at(11, 40)),
            ev(.feed, at(3, 15), at(3, 35)),
            ev(.feed, at(14, 0)),                            // 진행 중: 지금까지
            ev(.feed, at(9, 0), at(9, 20), deleted: true),   // 지운 것은 뺀다
            ev(.diaper, at(12, 58)),
        ]
        let d = ChartData.day(care: care, reports: [], day: today, now: now, zone: zone, showCar: false)
        XCTAssertEqual(d.lanes.map(\.kind), [.sleep, .feed, .diaper])
        XCTAssertEqual(d.lanes[0].spans.map { [$0.from, $0.to] }, [[0, 60], [620, 700]])
        XCTAssertEqual(d.lanes[0].sub, "2번")
        XCTAssertEqual(d.lanes[0].value, "2시간 20분")   // 끝난 잠이 오늘에 걸친 길이(60 + 80분)
        XCTAssertEqual(d.lanes[1].value, "2번")
        XCTAssertEqual(d.lanes[1].spans.last.map { [$0.from, $0.to] }, [840, 853])
        XCTAssertEqual(d.lanes[1].spans.first?.tip, "수유 03:15–03:35 · 20분")
        XCTAssertEqual(d.lanes[2].marks.map(\.at), [778])
        XCTAssertEqual(d.nowMin, 853)
        XCTAssertTrue(d.summary.hasPrefix("오늘 하루: 잠 2시간 20분"))
        // 자정에 딱 끝난 어제 잠은 오늘 줄에 없다. 오늘 시작한 끝 모름 수유는 점으로 남는다.
        var edge = CareFile()
        edge.sleepDiaperEnabled = true
        edge.events = [ev(.sleep, at(23, 0, daysAgo: 1), at(0, 0)), ev(.feed, at(5, 0), endUnknown: true)]
        let e2 = ChartData.day(care: edge, reports: [], day: today, now: now, zone: zone, showCar: false)
        XCTAssertTrue(e2.lanes[0].spans.isEmpty)
        XCTAssertEqual(e2.lanes[1].spans.map { [$0.from, $0.to] }, [[300, 300]])
        XCTAssertEqual(e2.lanes[1].spans.first?.tip, "수유 05:00 · 끝 시각 모름")   // 모르는 끝을 시작 시각으로 만들지 않는다
    }

    func test_방법별_성적은_판정할_수_있는_것_먼저_그친_비율_높은_순이다() {
        let stats = [MethodStat(method: .whiteNoise, label: "백색소음", successes: 2, attempts: 6), MethodStat(method: .parentSong, label: "부모 노래", successes: 0, attempts: 2),
                     MethodStat(method: .lullaby, label: "자장가", successes: 6, attempts: 7), MethodStat(method: .womb, label: "뱃속 소리", successes: 3, attempts: 4)]
        XCTAssertEqual(methodDotRows(stats).map(\.name), ["자장가", "뱃속 소리", "백색소음", "부모 노래"])
        // 차가 보낸 값이 음수·뒤집혀도 점 범위가 깨지지 않는다
        let odd = MethodDots.Row(id: 0, name: "x", ok: 5, n: -1)
        XCTAssertEqual([odd.n, odd.ok], [0, 0])
    }

    func test_잠·기저귀를_끄면_그_줄을_빼고_차에서_줄은_리포트의_탑승_구간과_울음_경보를_그린다() throws {
        var b = try report()
        b.startedAt = iso(at(13, 10))
        b.endedAt = iso(at(13, 52))
        b.timeline = b.timeline.map { var e = $0; e.startedAt = iso(at(13, 24)); return e }
        let d = ChartData.day(care: CareFile(), reports: [stored(b)], day: today, now: now, zone: zone, showCar: true)
        XCTAssertEqual(d.lanes.map(\.kind), [.feed, .car])
        XCTAssertEqual(d.lanes[1].value, "울음 1번")
        XCTAssertEqual(d.lanes[1].sub, "탑승 42분")
        XCTAssertEqual(d.lanes[1].spans.map { [$0.from, $0.to] }, [[790, 832]])
        XCTAssertEqual(d.lanes[1].marks.map(\.at), [804])
        // 다른 날이면 지금 선이 없다.
        XCTAssertNil(ChartData.day(care: CareFile(), reports: [], day: "2026-11-08", now: now, zone: zone, showCar: false).nowMin)
    }

    func test_하루_띠의_길이_글과_탑승_합계는_분으로_내린_칸이_아니라_기록_시각의_차이다() throws {
        // 13:10:50~13:52:10은 기록 목록처럼 41분(칸 차이로 세면 42분). 그림 칸은 분으로 내린 그대로다.
        var b = try report()
        b.startedAt = iso(at(13, 10) + 50_000)
        b.endedAt = iso(at(13, 52) + 10_000)
        var care = CareFile()
        care.sleepDiaperEnabled = true
        care.events = [ev(.feed, at(3, 15) + 50_000, at(3, 35) + 10_000), ev(.sleep, at(23, 0, daysAgo: 1) + 30_000, at(1, 0) + 20_000)]
        let d = ChartData.day(care: care, reports: [stored(b)], day: today, now: now, zone: zone, showCar: true)
        let car = try XCTUnwrap(d.lanes.first { $0.kind == .car })
        XCTAssertEqual(car.spans.map { [$0.from, $0.to] }, [[790, 832]])
        XCTAssertEqual(car.spans.first?.tip, "탑승 13:10–13:52 · 41분")
        XCTAssertEqual(car.sub, "탑승 41분")
        let feed = try XCTUnwrap(d.lanes.first { $0.kind == .feed })
        XCTAssertEqual(feed.spans.map { [$0.from, $0.to] }, [[195, 215]])
        XCTAssertEqual(feed.spans.first?.tip, "수유 03:15–03:35 · 19분")
        // 어제 밤 잠은 오늘에 든 부분(00:00~01:00:20)의 길이
        let sleep = try XCTUnwrap(d.lanes.first { $0.kind == .sleep })
        XCTAssertEqual(sleep.spans.first?.tip, "잠 00:00–01:00 · 1시간")
    }

    func test_하루_띠에서_같은_분_안에_끝난_기록도_길이가_붙는다() {
        var care = CareFile()
        care.sleepDiaperEnabled = true
        care.events = [ev(.sleep, at(12, 0) + 10_000, at(12, 0) + 50_000)]   // 12:00:10~12:00:50
        let d = ChartData.day(care: care, reports: [], day: today, now: now, zone: zone, showCar: false)
        XCTAssertEqual(d.lanes[0].spans.map { [$0.from, $0.to] }, [[720, 720]])
        XCTAssertEqual(d.lanes[0].spans.first?.tip, "잠 12:00–12:00 · 1분 미만")   // 기록 목록과 같은 글(CareFormat.length)
    }

    func test_수유_간격_막대는_날마다_중앙값이고_평소는_앞_6일_간격이_5개_이상일_때만이다() {
        let feeds = [at(0, 0, daysAgo: 1), at(3, 0, daysAgo: 1), at(6, 0, daysAgo: 1), at(9, 0, daysAgo: 1), at(2, 0), at(4, 30), at(20, 0)]
        var care = CareFile()
        care.events = feeds.map { ev(.feed, $0, $0 + 20 * min) }
        let w = ChartData.intervalWeek(care: care, now: now, zone: zone)
        XCTAssertEqual(w.bars.count, 7)
        XCTAssertEqual(w.bars.last?.label, "오늘")
        XCTAssertEqual(w.bars[5].value, 180 * min)   // 어제: 3시간 셋
        // 오늘: 어제 9:00 → 오늘 2:00(17시간, 10시간 넘어 뺀다), 2:00 → 4:30. 20:00은 지금 뒤라 뺀다.
        XCTAssertEqual(w.todayMs, 150 * min)
        XCTAssertNil(w.usualMs)   // 앞 6일 간격 3개 < 5
        var more = care
        more.events += [at(12, 0, daysAgo: 1), at(15, 0, daysAgo: 1)].map { ev(.feed, $0) }
        XCTAssertEqual(ChartData.intervalWeek(care: more, now: now, zone: zone).usualMs, 180 * min)
        XCTAssertEqual(w.bars[5].tip, "11월 8일 · 3시간")
    }

    func test_차에서_울음_막대는_날마다_울음_경보_에피소드_수와_평균_감지_지연이다() throws {
        var b = try report()
        b.timeline = b.timeline.map { var e = $0; e.startedAt = iso(at(13, 24)); return e }
        let w = ChartData.carCryWeek(reports: [stored(b)], now: now, zone: zone)
        XCTAssertEqual(w.total, 1)
        XCTAssertEqual(w.bars.map(\.value), [0, 0, 0, 0, 0, 0, 1])
        XCTAssertEqual(w.avgLatencyS ?? 0, 2.4, accuracy: 1e-9)
        XCTAssertEqual(ChartData.seconds(w.avgLatencyS!), "2.4초")
    }

    func test_잠_패턴은_30분_칸이_잠과_겹치면_참이고_평균은_잔_날로_나눈다() {
        var care = CareFile()
        care.sleepDiaperEnabled = true
        care.events = [ev(.sleep, at(0, 0, daysAgo: 1), at(3, 10, daysAgo: 1)), ev(.sleep, at(0, 0), at(1, 0))]
        let g = ChartData.sleepGrid(care: care, now: now, zone: zone)
        XCTAssertEqual(g.rows[5].indices.filter { g.rows[5][$0] }, Array(0...6))   // 0:00~3:30 칸 일곱
        XCTAssertEqual(g.rows[6].indices.filter { g.rows[6][$0] }, [0, 1])
        XCTAssertEqual(g.sleepDays, 2)   // 오늘도 끝난 잠이 있으면 센다(패턴 '하루 잠'과 같은 분모)
        XCTAssertEqual(g.avgMs, (190 + 60) / 2 * min)
        XCTAssertTrue(g.summary.contains("일 3시간 10분"))   // 화면 읽기는 칸 수(3시간 30분)가 아니라 실제 길이(11월 8일은 일요일)
    }

    /// 재생 사이 칸은 그 사이 판정을 멈췄을 수도 있어(통화 등, 리포트에 그 칸이 없다) '듣기 틈'이라 단정하지 않고 시간만 쓴다(차 리포트와 같다).
    func test_에피소드_띠의_재생_사이_칸은_이름_없이_초만_보인다() throws {
        var e = try report().timeline[0]
        let t0 = at(13, 24)
        e.startedAt = iso(t0)
        e.plays = [
            PlayEntry(purpose: .autoFirst, method: .lullaby, songId: nil, startedAt: iso(t0 + 2000), endedAt: iso(t0 + 58_000), playS: 56),
            PlayEntry(purpose: .request, method: .whiteNoise, songId: nil, startedAt: iso(t0 + 90_000), endedAt: iso(t0 + 100_000), playS: 10),
        ]
        let b = ChartData.episode(e, playName: { $0.method == .lullaby ? "자장가" : "백색소음" })
        XCTAssertEqual(b.gaps.map(\.tip), ["32초"])
        XCTAssertEqual(b.sounds.map(\.tip), ["자장가 56초", "백색소음 10초"])
        XCTAssertFalse((b.gaps + b.sounds).contains { $0.tip.contains("듣기") })
    }

    func test_에피소드_띠는_재생_구간과_재생_사이와_표시를_리포트에서_만든다() throws {
        var e = try report().timeline[0]
        let t0 = at(13, 24)
        e.startedAt = iso(t0)
        e.recried = true
        e.result = .success
        e.plays = [
            PlayEntry(purpose: .autoFirst, method: .lullaby, songId: nil, startedAt: iso(t0 + 2000), endedAt: iso(t0 + 58_000), playS: 56),
            PlayEntry(purpose: .autoSecond, method: .whiteNoise, songId: nil, startedAt: iso(t0 + 66_000), endedAt: iso(t0 + 122_000), playS: 56),
        ]
        let name: (PlayEntry) -> String = { $0.method == .lullaby ? "자장가" : "백색소음" }
        let b = ChartData.episode(e, playName: name)
        XCTAssertEqual(b.sounds, [.init(from: 2, to: 58, name: "자장가", playS: 56), .init(from: 66, to: 122, name: "백색소음", playS: 56)])
        // 같은 초에 시작·끝난 재생: 칸은 1초 폭, 보이는 길이는 play_s(0초)
        var zero = e
        zero.plays = [PlayEntry(purpose: .autoFirst, method: .lullaby, songId: nil, startedAt: iso(t0 + 2000), endedAt: iso(t0 + 2000), playS: 0)]
        XCTAssertEqual(ChartData.episode(zero, playName: name).sounds.first?.seconds, 0)
        XCTAssertEqual(b.gaps, [.init(from: 58, to: 66, name: nil)])
        XCTAssertEqual(b.totalS, 122)
        // 시간축에는 시각을 아는 것만(시작·자동 2차 시작), 결과는 축 밖.
        XCTAssertEqual(b.marks, [.init(at: 0, label: "감지 2.4초"), .init(at: 66, label: "다시 울음")])
        XCTAssertEqual(b.outcomes, ["그쳤어요"])
        // 다시 울음은 자동 2차 재생의 시작이다(운전자 요청 재생이면 표시하지 않는다).
        var req = e
        req.plays[1].purpose = .request
        XCTAssertFalse(ChartData.episode(req, playName: name).marks.contains { $0.label == "다시 울음" })
        // 쉼터 제안은 운전자에게 실제로 물었을 때만, 소리 바꾸기 요청은 '감지'가 아니다.
        var stop = StopInfo(handoff: true, reason: .attemptLimit, mode: .restOffer, restName: nil, waypointAdded: false, guardianNotified: false, toldDriver: nil)
        var withStop = e
        withStop.stop = stop
        XCTAssertTrue(ChartData.episode(withStop, playName: name).outcomes.contains("쉼터 제안"))
        stop.toldDriver = false
        withStop.stop = stop
        XCTAssertFalse(ChartData.episode(withStop, playName: name).outcomes.contains("쉼터 제안"))
        var sw = e
        sw.trigger = .switchSound
        XCTAssertEqual(ChartData.episode(sw, playName: name).marks.first?.label, "소리 바꾸기 요청")
    }

    func test_리포트_숫자_줄은_감지_평균·울음_합·한_일_종류_수다() throws {
        var r = try report()
        r.carActions += [CarAction(action: .rearPlayback, count: 1, totalS: 10), CarAction(action: .waypointAdded, count: 0, totalS: nil)]
        let k = ChartData.kpis(r)
        XCTAssertEqual(k.detectS ?? 0, 2.4, accuracy: 1e-9)
        XCTAssertEqual(k.cryS, 61)
        XCTAssertEqual(k.actionKinds, 3)   // 같은 종류는 한 번, 0번은 세지 않는다
        XCTAssertEqual(ChartData.reportMinutes(r), 42)
    }

    func test_마지막_수유_막대는_차_간격이_있으면_그것으로_없으면_평소로_그린다() {
        let last = at(12, 52)
        // 차: 마지막 수유 12:52, 다음 시간대 시작 = 12:52 + 3시간 × 0.8 → 간격 3시간, 꽉 참 = 3시간 45분
        let m = ChartData.feedMeter(last: last, now: now, carLast: last, windowStart: last + 144 * min, usualMs: nil)!
        XCTAssertEqual(m.value, 81.0 / 225.0, accuracy: 1e-4)
        XCTAssertEqual(m.tick ?? 0, 0.64, accuracy: 1e-4)
        let m2 = ChartData.feedMeter(last: last, now: now, carLast: nil, windowStart: nil, usualMs: 180 * min)!
        XCTAssertEqual(m2.value, 81.0 / 225.0, accuracy: 1e-4)
        XCTAssertNil(m2.tick)
        XCTAssertNil(ChartData.feedMeter(last: last, now: now, carLast: nil, windowStart: nil, usualMs: nil))
    }

    func test_하루_줄은_앞_두_조각을_제목으로_나머지를_둘째_줄로_나눈다() {
        XCTAssertTrue(CareText.splitRow("수유 · 오후 12:52–1:10 · 왼쪽") == ("수유 · 오후 12:52–1:10", "왼쪽"))
        XCTAssertTrue(CareText.splitRow("수유 중 · 오후 2:01부터") == ("수유 중 · 오후 2:01부터", nil))
        XCTAssertTrue(CareText.splitRow("수유 · 오후 12:52 · 왼쪽 · 끝 시각 모름") == ("수유 · 오후 12:52", "왼쪽 · 끝 시각 모름"))
        XCTAssertTrue(CareText.splitRow("메모 · 오전 9:42 · 트림 · 잘 함") == ("메모 · 오전 9:42", "트림 · 잘 함"))
    }

    func test_지난주보다는_두_주의_하루_평균_수유_차이이고_앞_주_기록이_없거나_같으면_없다() {
        func feeds(_ daysAgo: Int, _ count: Int) -> [CareEvent] { (0..<count).map { ev(.feed, at(1 + $0 * 2, 0, daysAgo: daysAgo)) } }
        var care = CareFile()
        care.events = feeds(0, 8) + feeds(1, 7)   // 평균 7.5
        XCTAssertNil(ChartData.feedTrend(care: care, now: now, zone: zone))   // 앞 주 기록 없음
        care.events += feeds(7, 7) + feeds(8, 7)   // 앞 주 평균 7.0
        XCTAssertEqual(ChartData.feedTrend(care: care, now: now, zone: zone), "지난주보다 0.5회 많아요")
        care.events += feeds(9, 9)   // 앞 주 평균 (7+7+9)/3 = 7.67
        XCTAssertEqual(ChartData.feedTrend(care: care, now: now, zone: zone), "지난주보다 0.2회 적어요")
        // 정확히 반인 차이(7.25 − 7)는 안드로이드 %.1f(HALF_UP)처럼 올린다
        var quarter = CareFile()
        quarter.events = feeds(0, 8) + feeds(1, 7) + feeds(2, 7) + feeds(3, 7) + feeds(7, 7)   // 평균 29/4 = 7.25, 앞 주 7
        XCTAssertEqual(ChartData.feedTrend(care: quarter, now: now, zone: zone), "지난주보다 0.3회 많아요")
        XCTAssertEqual(ChartData.seconds(2.25), "2.3초")
        XCTAssertEqual(ChartData.seconds(2.05), "2.1초")   // 자바는 가장 짧은 십진 표기 2.05를 올린다(printf는 2.0)
        XCTAssertEqual(CareText.oneDecimal(7.0), "7.0")
    }
}
