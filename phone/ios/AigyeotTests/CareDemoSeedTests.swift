@testable import Aigyeot
import XCTest

/// 시연 시드(안드로이드 DebugSeedTest와 같은 두 시험).
@MainActor
final class CareDemoSeedTests: CareTestCase {
    // MARK: 시연 시드

    func test_시연_시드는_오늘_숫자를_낸다() {
        let t = Self.ms("2026-11-06T15:00:30+09:00")
        let m = t / 60_000 * 60_000   // 분 내림
        let f = DemoSeed.make(nowMs: t, zone: seoul, keep: CareFile(childNickname: "콩이"))
        assertSeedValues(f, t)
        XCTAssertEqual(f.events.first { $0.startAt == m - 120 * 60_000 }?.feedSide, .both)
        XCTAssertEqual(f.childNickname, "콩이")
        // 지난 6일: 날마다 수유 7번(간격 중앙값 3시간), 잠 13시간 30분, 기저귀 6번.
        let p = CareRules.pattern(f.events, reports: [], now: t, zone: seoul, sleepDiaper: true)
        XCTAssertEqual(p.days.map(\.feeds), [7, 7, 7, 7, 7, 7, 2])
        XCTAssertEqual(p.days.prefix(6).map(\.sleepMs), Array(repeating: 810 * 60_000, count: 6))
        XCTAssertEqual(p.days.map(\.diapers), [6, 6, 6, 6, 6, 6, 2])
        XCTAssertEqual(p.feedIntervalMedianMs, 180 * 60_000)
    }

    func test_시연_시드는_언제_넣어도_규칙에_맞는다() {
        // 0시부터 30분마다: 미래 기록·같은 종류 겹침·진행 중 기록이 없다(이른 시각에는 오늘 장면이 어제로 넘어간다).
        let day0 = Self.ms("2026-11-06T00:00:00+09:00")
        for half in 0..<48 {
            let t = day0 + Int64(half) * 30 * 60_000
            let f = DemoSeed.make(nowMs: t, zone: seoul)
            for e in f.events {
                XCTAssertNil(CareRules.problem(e, now: t), "\(half) \(e.kind)")
                XCTAssertFalse(CareRules.overlaps(e, in: f.events, now: t), "\(half) \(e.kind) \(e.startAt)")
                XCTAssertFalse(e.inProgress, "\(half) \(e.kind)")
            }
            XCTAssertEqual(CareRules.latestFeed(f.events)?.startAt, t - 120 * 60_000)
            XCTAssertEqual(f.events.filter { $0.kind == .diaper }.map(\.startAt).max(), t - 60 * 60_000)
            // 오늘 숫자는 08:30부터 모두 맞는다. 그 전에는 6시간 전 수유가 0시 앞이고, 마지막 잠 끝(T−150)이 오늘 6시 전이라 밤중 깸에 들어간다.
            if half >= 17 { assertSeedValues(f, t) }
        }
    }

    func test_서머타임이_바뀐_날이_지난_6일에_있어도_시드_기록이_겹치지_않는다() {
        // 뉴욕 봄(3/8 2시→3시)·가을(11/1 2시→1시), 산티아고 0시 건너뜀(9/6 24시→1시). 안드로이드 시험과 같은 경우.
        for (id, date) in [("America/New_York", "2026-03-10"), ("America/New_York", "2026-11-03"), ("America/Santiago", "2026-09-08")] {
            let zone = TimeZone(identifier: id)!
            var cal = Calendar(identifier: .gregorian)
            cal.timeZone = zone
            let p = date.split(separator: "-").map { Int($0)! }
            let t = CareTime.ms(cal.date(from: DateComponents(year: p[0], month: p[1], day: p[2], hour: 15))!)
            let f = DemoSeed.make(nowMs: t, zone: zone)
            for e in f.events {
                XCTAssertNil(CareRules.problem(e, now: t), "\(id) \(e.kind)")
                XCTAssertFalse(CareRules.overlaps(e, in: f.events, now: t), "\(id) \(e.kind) \(e.startAt)")
            }
            assertSeedValues(f, t, zone: zone)
            // 지난 6일 모두 잠 합이 13~14시간이다. 전환일도(봄 12시간 30분, 가을 14시간 30분이 되지 않게 보정)
            for d in 1...6 {
                let key = CareRules.dayKey(CareTime.ms(cal.date(byAdding: .day, value: -d, to: CareTime.date(t))!), zone)
                let total = CareRules.sleepMs(onDay: key, in: f.events, zone: zone)
                XCTAssert((780...840).contains(total / 60_000), "\(id) \(key) 잠 합 \(total / 60_000)분")
            }
        }
        // 산티아고 9/6은 0시가 없다: 00:10 잠은 안드로이드처럼 01:10에 시작한다. 낮잠은 10분씩 당겨 합이 780분이다.
        let santiago = TimeZone(identifier: "America/Santiago")!
        let f = DemoSeed.make(nowMs: Self.ms("2026-09-08T15:00:00-03:00"), zone: santiago)
        XCTAssertTrue(f.events.contains { $0.kind == .sleep && $0.startAt == Self.ms("2026-09-06T01:10:00-03:00") })
        XCTAssertEqual(CareRules.sleepMs(onDay: "2026-09-06", in: f.events, zone: santiago), 780 * 60_000)
        // 뉴욕 가을 11/1은 한 시간이 되풀이된다: 밤잠을 00:40에 시작해 합이 840분이다.
        let ny = TimeZone(identifier: "America/New_York")!
        let g = DemoSeed.make(nowMs: Self.ms("2026-11-03T15:00:00-05:00"), zone: ny)
        XCTAssertEqual(CareRules.sleepMs(onDay: "2026-11-01", in: g.events, zone: ny), 840 * 60_000)
        // 두 시간이 되풀이되는 날(남극 Troll 10/25)도 밤잠을 끝에서 7시간 전에 시작해 840분이다.
        // 두 시간이 빠지는 날(3/29)은 수유와 겹치지 않고는 13시간을 맞출 수 없어 보정 범위 밖이다.
        let troll = TimeZone(identifier: "Antarctica/Troll")!
        let h = DemoSeed.make(nowMs: Self.ms("2026-10-27T15:00:00+00:00"), zone: troll)
        XCTAssertEqual(CareRules.sleepMs(onDay: "2026-10-25", in: h.events, zone: troll), 840 * 60_000)
    }

    func test_고른_날의_낮_12시는_서머타임이_바뀌는_날에도_12시다() {
        // 0시가 없는 날(산티아고)·한 시간이 되풀이되는 날(뉴욕)에 '하루 시작 + 12시간'은 13시·11시가 된다(안드로이드 onDate(day, 12, 0)).
        for (zone, day) in [("America/Santiago", "2026-09-06"), ("America/New_York", "2026-11-01"), ("Asia/Seoul", "2026-11-05")] {
            let tz = TimeZone(identifier: zone)!
            var cal = Calendar(identifier: .gregorian)
            cal.timeZone = tz
            let noon = CareTime.date(try! XCTUnwrap(CareRules.noon(day, tz)))
            XCTAssertEqual(cal.dateComponents([.hour, .minute], from: noon), DateComponents(hour: 12, minute: 0), zone)
            XCTAssertEqual(CareRules.dayKey(CareTime.ms(noon), tz), day, zone)
        }
    }

    func test_날짜_띠는_0시가_없는_날부터_시작해도_오늘까지_모두_있다() {
        // 산티아고 9/6은 01:00에 시작한다. 시각 차이로 세면 10/5가 빠졌다.
        let santiago = TimeZone(identifier: "America/Santiago")!
        let first = CareTime.date(CareRules.dayRange("2026-09-06", santiago)!.0)
        let last = CareTime.date(CareRules.dayRange("2026-10-05", santiago)!.0)
        let keys = CareRules.days(from: first, through: last, zone: santiago).map(\.key)
        XCTAssertEqual(keys.count, 30)
        XCTAssertEqual(keys.first, "2026-09-06")
        XCTAssertEqual(keys.last, "2026-10-05")
        XCTAssertEqual(Set(keys).count, 30)
    }

    func test_차가_거절한_돌봄_요약은_버리고_화면에_알린다() async {
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
        // 400·403·409 id_conflict·413·415는 버리고 화면에 알린다.
        app.reconcileCareSummary.sendsCareSummary = true
        app.reconcileCareSummary.summaryOnly()   // 보낼 함에 요약만 둔다(수유 저장은 feeding_started도 넣는다)
        XCTAssertEqual(app.state.state.outbox.map(\.type), [MessageType.careSummary])
        relay.sendError = .http(status: 403, code: "forbidden", retryAfter: nil)
        await app.sendOutbox.run()
        XCTAssertTrue(careSummaries.isEmpty)
        XCTAssertEqual(app.status.ui.notice, CareText.careSummaryDropped)
    }

    func test_버린_메시지_안내는_서로_덮지_않는다() async {
        // 수유 시작은 요약과 수유를 함께 넣는다. 둘 다 거절되면 앞 안내가 덮이지 않게 함께 보인다.
        app.reconcileCareSummary.sendsCareSummary = true
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertEqual(app.state.state.outbox.map(\.type), [MessageType.careSummary, MessageType.feedingStarted])
        relay.sendError = .http(status: 400, code: "bad_body", retryAfter: nil)
        await app.sendOutbox.run()
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(app.status.ui.notice, CareText.careSummaryDropped + "\n수유 기록을 차로 보내지 못했어요")

        // 같은 글은 한 번만 보인다: 두 곡 삭제가 함께 거절돼도 줄은 하나다.
        var st = PhoneState(introSeen: true, carId: "c_1")
        st.songs = [SongRow(id: "a", name: "a", durationS: 1, state: .accepted, addedAt: clock),
                    SongRow(id: "b", name: "b", durationS: 1, state: .accepted, addedAt: clock)]
        make(state: st)
        app.removeSong("a")
        app.removeSong("b")
        XCTAssertEqual(app.state.state.outbox.map(\.type), [MessageType.songRemoved, MessageType.songRemoved])
        relay.sendError = .http(status: 400, code: "bad_body", retryAfter: nil)
        await app.sendOutbox.run()
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(app.status.ui.notice, "노래 지우기를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요.")
    }

    func test_0시가_없는_날의_하루_범위는_다음_날_0시에서_끝나고_패턴_창은_첫날_0시부터다() {
        // 산티아고 9/6은 0시를 건너뛴다(01:00 시작). 안드로이드 atStartOfDay와 같은 경계여야 두 앱 숫자가 같다.
        let santiago = TimeZone(identifier: "America/Santiago")!
        XCTAssertEqual(CareRules.dayRange("2026-09-06", santiago)?.0, Self.ms("2026-09-06T01:00:00-03:00"))
        XCTAssertEqual(CareRules.dayRange("2026-09-06", santiago)?.1, Self.ms("2026-09-07T00:00:00-03:00"))
        XCTAssertEqual(CareRules.dayRange("2026-09-05", santiago)?.1, Self.ms("2026-09-06T01:00:00-03:00"))
        // 오늘이 그날이면 7일 창의 첫날(8/31) 0시 반 수유도 센다.
        let feed = CareEvent(id: "F", kind: .feed, startAt: Self.ms("2026-08-31T00:30:00-04:00"), endAt: Self.ms("2026-08-31T00:50:00-04:00"),
                             zone: santiago.identifier, createdAt: 0, updatedAt: 0)
        let p = CareRules.pattern([feed], reports: [], now: Self.ms("2026-09-06T15:00:00-03:00"), zone: santiago, sleepDiaper: false)
        XCTAssertEqual(p.days.first?.day, "2026-08-31")
        XCTAssertEqual(p.days.first?.feeds, 1)
    }
}
