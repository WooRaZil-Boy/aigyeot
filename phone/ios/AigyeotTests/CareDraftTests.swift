@testable import Aigyeot
import XCTest

/// 기록 시트 입력(CareDraft)과 종류별 칸 정리(CareRules.normalized). 화면에서 옮긴 규칙이라 화면 없이 본다.
final class CareDraftTests: XCTestCase {
    private let now = Rfc3339.parse("2026-11-10T09:00:00+09:00")!

    func test_새_기록은_지금에서_시작하고_다른_날이면_그날_낮_12시에서_직접_고른다() {
        let d = CareDraft.new(kind: .diaper, day: nil, now: now)
        XCTAssertEqual(d.chip, 0)
        XCTAssertEqual(d.start, now)
        XCTAssertEqual(d.diaper, .wet)          // 종류 없이 저장하지 않게 소변으로 시작
        XCTAssertTrue(d.showsAgoChips)
        let past = CareDraft.new(kind: .feed, day: "2026-11-08", now: now)
        XCTAssertNil(past.chip)
        XCTAssertFalse(past.showsAgoChips)
        XCTAssertEqual(past.start, CareRules.noon("2026-11-08", .current).map(CareTime.date))   // 기기 시간대의 그날 낮 12시
    }

    func test_몇_분_전_칩은_누른_때_기준이고_끝_모름이면_보일_끝이_시작보다_이르지_않다() {
        var d = CareDraft.new(kind: .sleep, day: nil, now: now)
        d.pick(chip: 30, now: now)
        XCTAssertEqual(d.start, now.addingTimeInterval(-1800))
        XCTAssertGreaterThanOrEqual(d.endShown, d.start)
        d.pick(chip: nil, now: now)
        XCTAssertNil(d.chip)
        XCTAssertEqual(d.start, now.addingTimeInterval(-1800))   // '직접'은 지금 값에서 고른다
    }

    func test_끝_시각은_시작_뒤_처음_오는_그_시각이고_같은_분이면_시작이다() {
        var e = CareEvent(id: "x", kind: .sleep, startAt: CareTime.ms(Rfc3339.parse("2026-11-09T23:00:30+09:00")!), zone: "Asia/Seoul", createdAt: 0, updatedAt: 0)
        e.endAt = CareTime.ms(Rfc3339.parse("2026-11-10T06:00:00+09:00")!)
        var d = CareDraft.editing(e)
        let at = { (s: String) in Rfc3339.parse(s)! }
        d.setEnd(picked: at("2026-11-10T07:30:00+09:00"))
        XCTAssertEqual(d.end, at("2026-11-10T07:30:00+09:00"))        // 이른 시각 → 다음 날(자정 넘김)
        d.setEnd(picked: at("2026-11-10T23:30:00+09:00"))
        XCTAssertEqual(d.end, at("2026-11-09T23:30:00+09:00"))        // 시작과 같은 날의 늦은 시각
        d.setEnd(picked: at("2026-11-10T23:00:00+09:00"))
        XCTAssertEqual(d.end, at("2026-11-09T23:00:30+09:00"))        // 같은 분 → 시작 그대로
    }

    func test_시작_날을_옮기면_끝도_같이_옮기고_끝_모름이면_보일_시각을_시작_뒤로_맞춘다() {
        var e = CareEvent(id: "x", kind: .feed, startAt: CareTime.ms(Rfc3339.parse("2026-11-09T12:00:00+09:00")!), zone: "Asia/Seoul", createdAt: 0, updatedAt: 0)
        e.endAt = CareTime.ms(Rfc3339.parse("2026-11-09T13:00:00+09:00")!)
        var d = CareDraft.editing(e)
        d.endUnknown = true
        d.moveStartDay(to: Rfc3339.parse("2026-11-08T12:00:00+09:00")!)
        XCTAssertEqual(d.endShown, Rfc3339.parse("2026-11-08T13:00:00+09:00"))   // 옛 날(9일)이 보이지 않는다
        XCTAssertTrue(d.endUnknown)   // 보이기만 하고 끝 모름은 그대로
    }

    /// 끝 모름 기록의 끝 시각 고르기를 열었다 닫기만 하면 끝 모름 그대로 저장되고, 시각을 고르면 그 시각으로 저장된다(안드로이드 setEnd와 같다).
    func test_끝_모름_기록은_끝_시각을_고를_때만_끝이_정해진다() {
        let start = Rfc3339.parse("2026-11-09T21:00:00+09:00")!
        var e = CareEvent(id: "x", kind: .sleep, startAt: CareTime.ms(start), zone: "Asia/Seoul", createdAt: 0, updatedAt: 0)
        e.endUnknown = true
        var d = CareDraft.editing(e)
        XCTAssertEqual(d.endShown, start)   // 고르기에 보일 값(자리 값 = 시작)
        d.note = "메모만"
        let kept = d.event(now: start.addingTimeInterval(3600))
        XCTAssertNil(kept.endAt)
        XCTAssertTrue(kept.endUnknown)
        d.setEnd(picked: Rfc3339.parse("2026-11-10T06:30:00+09:00")!)
        let picked = d.event(now: start.addingTimeInterval(12 * 3600))
        XCTAssertFalse(picked.endUnknown)
        XCTAssertEqual(picked.endAt, CareTime.ms(Rfc3339.parse("2026-11-10T06:30:00+09:00")!))
    }

    /// 시작을 옮겨도(몇 분 전 칩·시작 시각) 고른 끝은 그대로 두고, 끝이 시작보다 빠르면 저장 검사가 알린다. 날짜 옮기기는 길이를 지킨다(안드로이드 같은 이름 시험).
    func test_시작을_끝_뒤로_옮겨도_고른_끝은_그대로_두고_저장_검사가_알린다() {
        let at = { (s: String) in Rfc3339.parse("2026-11-10T\(s):00+09:00")! }
        let ten = at("10:00")
        func check(_ d: CareDraft) -> CareProblem? { CareRules.problem(d.event(now: ten), now: CareTime.ms(ten)) }
        var d = CareDraft.new(kind: .feed, day: nil, now: ten)
        d.pick(chip: 30, now: ten)               // 9:30
        d.setEnd(picked: at("09:50"))
        d.pick(chip: 0, now: ten)                // 지금 10:00
        XCTAssertEqual(d.end, at("09:50"))
        XCTAssertEqual(check(d), .endBeforeStart)
        d.setStart(at("09:55"))                  // 직접 시작
        XCTAssertEqual(d.end, at("09:50"))
        XCTAssertEqual(check(d), .endBeforeStart)
        // 끝 모름은 끝 없음(보일 값만 시작 뒤로 계산한다)
        var unknown = CareDraft.new(kind: .sleep, day: nil, now: ten)
        unknown.setStart(ten.addingTimeInterval(30))
        XCTAssertNil(unknown.event(now: ten).endAt)
        XCTAssertEqual(unknown.endShown, ten.addingTimeInterval(30))
        // 저장된 끝(9:00~9:20)을 고쳐 시작을 9:40으로
        var e = CareEvent(id: "x", kind: .feed, startAt: CareTime.ms(at("09:00")), zone: "Asia/Seoul", createdAt: 0, updatedAt: 0)
        e.endAt = CareTime.ms(at("09:20"))
        var edit = CareDraft.editing(e)
        edit.setStart(at("09:40"))
        XCTAssertEqual(edit.end, at("09:20"))
        XCTAssertEqual(check(edit), .endBeforeStart)
    }

    // MARK: 기록 시트(두 앱 같은 이름의 시험)

    private func seoulEvent(_ kind: CareKind, _ start: String, _ end: String? = nil) -> CareEvent {
        var e = CareEvent(id: "x", kind: kind, startAt: CareTime.ms(Rfc3339.parse("2026-11-10T\(start):00+09:00")!), zone: "Asia/Seoul", createdAt: 0, updatedAt: 0)
        e.endAt = end.map { CareTime.ms(Rfc3339.parse("2026-11-10T\($0):00+09:00")!) }
        e.endUnknown = end == nil && (kind == .feed || kind == .sleep)
        return e
    }

    func test_시트_질문은_종류마다_정해진_글이다() {
        XCTAssertEqual([CareKind.feed, .sleep, .diaper, .note].map(CareText.sheetQuestion), ["어떻게 먹였어요?", "언제 잤어요?", "어떤 기저귀였어요?", "무엇을 적을까요?"])
    }

    func test_시작_줄_값은_칩이면_시각과_칩_직접이면_시각_다른_날은_날짜와_시각이다() {
        let clock = { (d: Date) in CareFormat.clock(d, .current) }
        var d = CareDraft.new(kind: .feed, day: nil, now: now)
        XCTAssertEqual(d.startValue(now: now), clock(now) + " · 지금")
        d.pick(chip: 15, now: now)
        XCTAssertEqual(d.startValue(now: now), clock(now.addingTimeInterval(-900)) + " · 15분 전")
        d.pick(chip: nil, now: now)   // 직접 고른 오늘
        XCTAssertEqual(d.startValue(now: now), clock(now.addingTimeInterval(-900)))
        let past = CareDraft.new(kind: .feed, day: "2026-11-08", now: now)   // 다른 날
        XCTAssertEqual(past.startValue(now: now), "11월 8일(일) " + clock(past.start))
        let edit = CareDraft.editing(seoulEvent(.diaper, "08:10"))   // 고치기는 날짜와 시각
        XCTAssertEqual(edit.startValue(now: Rfc3339.parse("2026-11-10T09:00:00+09:00")!), "오늘 오전 8:10")
        XCTAssertEqual(edit.startValue(now: Rfc3339.parse("2026-11-11T09:00:00+09:00")!), "어제 오전 8:10")
    }

    func test_끝_줄_값은_모르면_모름_알면_시각과_길이다() {
        var d = CareDraft.editing(seoulEvent(.sleep, "21:00"))
        XCTAssertEqual(d.endValue, "모름")
        d.setEnd(picked: Rfc3339.parse("2026-11-11T06:30:00+09:00")!)
        XCTAssertEqual(d.endValue, "오전 6:30 · 9시간 30분")
        var f = CareDraft.editing(seoulEvent(.feed, "12:00", "13:00"))
        XCTAssertEqual(f.endValue, "오후 1:00 · 1시간")
        f.setStart(Rfc3339.parse("2026-11-10T13:30:00+09:00")!)   // 끝이 시작보다 이르면 시각만
        XCTAssertEqual(f.endValue, "오후 1:00")
    }

    func test_빈_메모_줄은_메모_더하기다() {
        var d = CareDraft.new(kind: .feed, day: nil, now: now)
        XCTAssertEqual(d.foldRows, [.start, .end, .memo])
        XCTAssertEqual(d.rowName(.memo), "메모 더하기")
        d.note = "트림 잘 함"
        XCTAssertEqual(d.rowName(.memo), "메모")
        XCTAssertEqual(CareDraft.new(kind: .diaper, day: nil, now: now).foldRows, [.start, .memo])
        XCTAssertEqual(CareDraft.new(kind: .sleep, day: nil, now: now).foldRows, [.end, .memo])   // 잠의 시작은 큰 칸
        XCTAssertEqual(CareDraft.new(kind: .note, day: nil, now: now).foldRows, [.start])
        XCTAssertEqual([d.rowName(.start), CareDraft.new(kind: .diaper, day: nil, now: now).rowName(.start)], ["시작", "시각"])
    }

    func test_시트를_열면_모든_줄이_접혀_있고_한_줄만_펼친다() {
        var f = SheetFolds()
        XCTAssertNil(f.open)
        f.toggle(.start)
        XCTAssertEqual(f.open, .start)
        f.toggleWheel(.start)
        f.toggle(.end)                   // 다른 줄을 누르면 앞 줄은 접힌다(바퀴도 닫힌다)
        XCTAssertEqual(f.open, .end)
        XCTAssertNil(f.wheel)
        f.toggle(.end)
        XCTAssertNil(f.open)
    }

    func test_몇_분_전을_고른_동안_날짜와_시각_알약은_숨긴다() {
        var d = CareDraft.new(kind: .feed, day: nil, now: now)
        XCTAssertFalse(d.showsPills)     // '지금'
        d.pick(chip: 30, now: now)
        XCTAssertFalse(d.showsPills)
        d.pick(chip: nil, now: now)      // '직접'
        XCTAssertTrue(d.showsPills)
        XCTAssertTrue(CareDraft.new(kind: .feed, day: "2026-11-08", now: now).showsPills)   // 칩이 없는 시트
        XCTAssertTrue(CareDraft.editing(seoulEvent(.feed, "08:00")).showsPills)
    }

    func test_저장_단추_글은_시각과_고른_것과_종류다() {
        var f = CareDraft.editing(seoulEvent(.feed, "13:58"))
        XCTAssertEqual(f.saveLabel(), "오후 1:58 수유 저장하기")
        f.side = .left
        XCTAssertEqual(f.saveLabel(), "오후 1:58 왼쪽 수유 저장하기")
        var d = CareDraft.editing(seoulEvent(.diaper, "14:13")); d.diaper = .wet
        XCTAssertEqual(d.saveLabel(), "오후 2:13 소변 기저귀 저장하기")
        XCTAssertEqual(CareDraft.editing(seoulEvent(.sleep, "10:20", "11:40")).saveLabel(), "오전 10:20–11:40 잠 저장하기")
        XCTAssertEqual(CareDraft.editing(seoulEvent(.sleep, "10:20")).saveLabel(), "오전 10:20 잠 저장하기")   // 끝 모름이면 시작만
        XCTAssertEqual(CareDraft.editing(seoulEvent(.note, "10:20")).saveLabel(), "메모 저장하기")
    }

    func test_젖병_양이_있으면_수유_대신_양을_쓴다() {
        var f = CareDraft.editing(seoulEvent(.feed, "13:43")); f.side = .bottle
        XCTAssertEqual(f.saveLabel(), "오후 1:43 젖병 수유 저장하기")
        f.amount = 120
        XCTAssertEqual(f.saveLabel(), "오후 1:43 젖병 120ml 저장하기")
    }

    func test_22자를_넘으면_잠_구간과_고른_것과_시각_순으로_줄인다() {
        var s = CareDraft.editing(seoulEvent(.sleep, "12:50"))
        s.setEnd(picked: Rfc3339.parse("2026-11-11T02:05:00+09:00")!)
        XCTAssertEqual(s.saveLabel(fit: false), "오후 12:50–오전 2:05 잠 저장하기")   // 23자: 큰 글자에서는 줄이지 않는다
        XCTAssertEqual(s.saveLabel(), "오후 12:50 잠 저장하기")                     // ① 잠 구간 → 시작 시각
        // 가장 긴 고른 것도 22자 안에 든다(한 줄로 확인한 예)
        var d = CareDraft.editing(seoulEvent(.diaper, "12:58")); d.diaper = .dry
        XCTAssertEqual(d.saveLabel(), "오후 12:58 깨끗함 기저귀 저장하기")
        var b = CareDraft.editing(seoulEvent(.feed, "12:52")); b.side = .bottle; b.amount = 400
        XCTAssertEqual(b.saveLabel(), "오후 12:52 젖병 400ml 저장하기")
        for k in [DiaperKind.wet, .dirty, .both, .dry] { d.diaper = k; XCTAssertLessThanOrEqual(d.saveLabel().unicodeScalars.count, 22) }
    }

    func test_메모가_비면_저장_단추가_꺼진다() {
        var n = CareDraft.new(kind: .note, day: nil, now: now)
        XCTAssertFalse(n.savable)
        n.note = "   "
        XCTAssertFalse(n.savable)
        n.note = "트림"
        XCTAssertTrue(n.savable)
        XCTAssertTrue(CareDraft.new(kind: .diaper, day: nil, now: now).savable)
    }

    func test_저장할_기록은_종류에_맞지_않는_칸을_비우고_메모_공백을_걷는다() {
        var d = CareDraft.new(kind: .feed, day: nil, now: now)
        d.side = .left
        d.amount = 100                 // 젖병이 아니면 양을 버린다
        d.note = "  트림  "
        let e = d.event(now: now)
        XCTAssertEqual(e.feedSide, .left)
        XCTAssertNil(e.amountMl)
        XCTAssertEqual(e.note, "트림")
        XCTAssertTrue(e.endUnknown)    // 끝을 고르지 않은 새 수유
        var sleep = CareDraft.new(kind: .sleep, day: nil, now: now)
        sleep.side = .bottle
        sleep.note = "   "
        let s = sleep.event(now: now)
        XCTAssertNil(s.feedSide)
        XCTAssertNil(s.note)
        let original = CareEvent(id: "keep", kind: .diaper, startAt: 0, zone: "Asia/Seoul", diaper: .dirty, createdAt: 1, updatedAt: 1)
        XCTAssertEqual(CareDraft.editing(original).event(now: now).id, "keep")
    }
}
