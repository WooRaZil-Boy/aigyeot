@testable import Aigyeot
import XCTest

/// 돌봄 기록 규칙(표준 예제·겹침·시간 한도).
@MainActor
final class CareRulesTests: CareTestCase {
    // MARK: 표준 예제

    func test_표준_예제를_읽고_다시_쓰면_같다() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "care-sample", withExtension: "json"))
        let original = try Data(contentsOf: url)
        let file = try CareCoding.decode(original)
        XCTAssertEqual(file.childNickname, "콩이")
        XCTAssertEqual(file.events.count, 6)
        XCTAssertEqual(file.events[1].endUnknown, true)
        XCTAssertEqual(file.events[2].amountMl, 120)
        XCTAssertEqual(file.events[4].diaper, .wet)
        XCTAssertEqual(file.events[5].deletedAt, 1793860000000)

        let written = try CareCoding.encode(file)
        XCTAssertEqual(Self.withoutNulls(try JSONSerialization.jsonObject(with: written)) as? NSDictionary,
                       Self.withoutNulls(try JSONSerialization.jsonObject(with: original)) as? NSDictionary)
        // 쓸 때는 null인 키도 쓴다: 파일 키 5개, 기록마다 키 14개.
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: written) as? [String: Any])
        XCTAssertEqual(Set(obj.keys), ["version", "events", "childNickname", "sleepDiaperEnabled", "migratedFeedings"])
        for e in try XCTUnwrap(obj["events"] as? [[String: Any]]) {
            XCTAssertEqual(Set(e.keys), ["id", "kind", "startAt", "endAt", "endUnknown", "zone", "feedSide", "amountMl",
                                         "diaper", "note", "source", "createdAt", "updatedAt", "deletedAt"])
        }
        // 없는 키는 기본값, 모르는 키는 무시한다.
        // swiftlint:disable:next line_length
        let sparse = try CareCoding.decode(Data(#"{"version":1,"events":[{"id":"A","kind":"NOTE","startAt":1,"zone":"Asia/Seoul","source":"PHONE","createdAt":1,"updatedAt":1,"later":true}],"x":1}"#.utf8))
        XCTAssertEqual(sparse.sleepDiaperEnabled, false)
        XCTAssertEqual(sparse.migratedFeedings, false)
        XCTAssertNil(sparse.childNickname)
        XCTAssertEqual(sparse.events.first?.endUnknown, false)
        XCTAssertNil(sparse.events.first?.endAt)
    }

    // MARK: 기록 규칙

    func test_진행_중_수유는_하나만() {
        app.recordCare.start(.feed, side: .left)
        clock.addTimeInterval(600)
        app.recordCare.start(.feed, side: .right)
        let running = app.care.care.events.filter { $0.kind == .feed && $0.inProgress }
        XCTAssertEqual(running.count, 1)
        XCTAssertEqual(running.first?.feedSide, .right)
        // 수유 중에 잠을 시작할 수 있다.
        app.recordCare.start(.sleep)
        XCTAssertNotNil(CareRules.inProgress(.sleep, in: app.care.care.events))
        XCTAssertNotNil(CareRules.inProgress(.feed, in: app.care.care.events))
        // 고치기·빠른 기록으로도 진행 중 수유를 하나 더 만들지 않는다.
        let extra = CareEvent(id: "X", kind: .feed, startAt: nowMs - 60_000, zone: "Asia/Seoul", createdAt: nowMs, updatedAt: nowMs)
        XCTAssertEqual(app.recordCare.saveEvent(extra), .problem(.inProgressExists))
        XCTAssertEqual(app.care.care.events.filter { $0.kind == .feed && $0.inProgress }.count, 1)
    }

    func test_새_수유가_진행_중_수유를_끝낸다() throws {
        app.recordCare.start(.feed, side: .left)
        let first = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        clock.addTimeInterval(20 * 60)
        app.recordCare.start(.feed, side: .right)
        let ended = try XCTUnwrap(app.care.care.events.first { $0.id == first.id })
        XCTAssertEqual(ended.endAt, nowMs)
        XCTAssertFalse(ended.endUnknown)
        XCTAssertEqual(app.status.ui.notice, "앞 수유를 끝냈어요")
        XCTAssertEqual(careStore.value, app.care.care)   // 끝내기와 새 시작이 한 번에 저장됐다

        // 앞 수유가 이미 3시간을 넘었으면 끝 시각 모름으로 닫는다(3시간 한도를 어기는 끝 시각을 만들지 않는다).
        let second = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        clock.addTimeInterval(3 * 3600 + 1)
        app.notices.dismiss()
        app.recordCare.start(.feed, side: .left)
        let closed = try XCTUnwrap(app.care.care.events.first { $0.id == second.id })
        XCTAssertNil(closed.endAt)
        XCTAssertTrue(closed.endUnknown)
        XCTAssertFalse(closed.inProgress)
        XCTAssertEqual(app.status.ui.notice, "앞 수유를 끝냈어요 · 끝 시각 모름")
        // 3시간 정각은 넘은 것이 아니다.
        let third = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        clock.addTimeInterval(3 * 3600)
        app.recordCare.start(.feed, side: .right)
        XCTAssertEqual(app.care.care.events.first { $0.id == third.id }?.endAt, nowMs)

        // 잠금 화면 '끝내기'도 같은 규칙이고 띠는 없다.
        let fourth = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        app.notices.dismiss()
        clock.addTimeInterval(600)
        app.recordCare.end(fourth.id)
        XCTAssertEqual(app.care.care.events.first { $0.id == fourth.id }?.endAt, nowMs)
        XCTAssertNil(app.status.ui.notice)
        app.recordCare.start(.sleep)
        let sleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: app.care.care.events))
        clock.addTimeInterval(16 * 3600 + 1)
        app.recordCare.end(sleep.id)
        XCTAssertEqual(app.care.care.events.first { $0.id == sleep.id }?.endUnknown, true)
        XCTAssertNil(app.care.care.events.first { $0.id == sleep.id }?.endAt)
        XCTAssertNil(app.status.ui.notice)

        // 잠을 또 시작해도 같다. 띠는 "앞 잠을 끝냈어요"(끝 모름이면 " · 끝 시각 모름").
        app.recordCare.start(.sleep)
        let firstSleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: app.care.care.events))
        clock.addTimeInterval(600)
        app.recordCare.start(.sleep)
        XCTAssertEqual(app.care.care.events.first { $0.id == firstSleep.id }?.endAt, nowMs)
        XCTAssertEqual(app.status.ui.notice, "앞 잠을 끝냈어요")
        let secondSleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: app.care.care.events))
        clock.addTimeInterval(16 * 3600 + 1)
        app.recordCare.start(.sleep)
        XCTAssertEqual(app.care.care.events.first { $0.id == secondSleep.id }?.endUnknown, true)
        XCTAssertEqual(app.status.ui.notice, "앞 잠을 끝냈어요 · 끝 시각 모름")
        app.recordCare.end(CareRules.inProgress(.sleep, in: app.care.care.events)!.id)
    }

    func test_미래_시각과_최대_길이를_넘는_기록은_저장하지_않는다() {
        let now = nowMs
        func feed(_ start: Int64, _ end: Int64?) -> CareEvent {
            CareEvent(id: ULID.generate(), kind: .feed, startAt: start, endAt: end, zone: "Asia/Seoul", createdAt: now, updatedAt: now)
        }
        // 미래: 지금 + 1분까지 허용
        XCTAssertNil(CareRules.problem(feed(now + 60_000, nil), now: now))
        XCTAssertEqual(CareRules.problem(feed(now + 60_001, nil), now: now), .futureStart)
        XCTAssertEqual(CareRules.problem(feed(now - 60_000, now + 60_001), now: now), .futureEnd)
        XCTAssertEqual(CareProblem.futureStart.message(for: .feed), "앞으로의 시각은 적을 수 없어요")
        XCTAssertEqual(CareProblem.futureEnd.message(for: .feed), "앞으로의 시각은 적을 수 없어요")
        XCTAssertEqual(CareProblem.endBeforeStart.message(for: .feed), "끝 시각이 시작보다 빨라요")
        XCTAssertEqual(CareProblem.tooLong.message(for: .feed), "수유는 3시간까지 적을 수 있어요")
        XCTAssertEqual(CareProblem.tooLong.message(for: .sleep), "잠은 16시간까지 적을 수 있어요")
        XCTAssertEqual(CareProblem.amount.message(for: .feed), "0~400ml로 적어 주세요")
        // 끝 ≥ 시작
        XCTAssertNil(CareRules.problem(feed(now - 1000, now - 1000), now: now))
        XCTAssertEqual(CareRules.problem(feed(now - 1000, now - 1001), now: now), .endBeforeStart)
        // 수유 3시간, 잠 16시간
        let h: Int64 = 3_600_000
        XCTAssertNil(CareRules.problem(feed(now - 3 * h, now), now: now))
        XCTAssertEqual(CareRules.problem(feed(now - 3 * h - 1, now), now: now), .tooLong)
        var sleep = feed(now - 16 * h, now); sleep.kind = .sleep
        XCTAssertNil(CareRules.problem(sleep, now: now))
        sleep.startAt -= 1
        XCTAssertEqual(CareRules.problem(sleep, now: now), .tooLong)
        // 젖병 0~400ml, 메모 200자
        var bottle = feed(now - 600_000, now); bottle.feedSide = .bottle
        bottle.amountMl = 400; XCTAssertNil(CareRules.problem(bottle, now: now))
        bottle.amountMl = 401; XCTAssertEqual(CareRules.problem(bottle, now: now), .amount)
        bottle.amountMl = -1; XCTAssertEqual(CareRules.problem(bottle, now: now), .amount)
        var note = CareEvent(id: "N", kind: .note, startAt: now, zone: "Asia/Seoul", note: String(repeating: "가", count: 200), createdAt: now, updatedAt: now)
        XCTAssertNil(CareRules.problem(note, now: now))
        note.note = String(repeating: "가", count: 201)
        XCTAssertEqual(CareRules.problem(note, now: now), .noteTooLong)
        // 코드 포인트로 센다. 가족 이모지는 글자 하나로 보이지만 코드 포인트 5개다.
        note.note = String(repeating: "가", count: 199) + "👨‍👩‍👧"
        XCTAssertEqual(note.note!.count, 200)
        XCTAssertEqual(CareRules.problem(note, now: now), .noteTooLong)
        XCTAssertEqual(CareRules.noteLimited(String(repeating: "가", count: 199) + "👨‍👩‍👧").unicodeScalars.count, 200)   // 입력칸은 200에서 더 받지 않는다
        // 저장하지 않는다
        XCTAssertEqual(app.recordCare.saveEvent(feed(now + 120_000, nil)), .problem(.futureStart))
        XCTAssertTrue(app.care.care.events.isEmpty)
        // 진행 중 수유가 3시간을 넘으면 타이머가 끝 시각을 묻는다(기록은 그대로 있다).
        app.recordCare.start(.feed, side: .left)
        clock.addTimeInterval(3 * 3600 + 1)
        let running = CareRules.inProgress(.feed, in: app.care.care.events)!
        XCTAssertTrue(CareRules.isOverLimit(running, now: nowMs))
        XCTAssertEqual(CareText.overLimitFeed, "수유가 3시간째예요. 끝내는 걸 잊으셨나요?")
        XCTAssertEqual(CareText.overLimitSleep, "잠이 16시간째예요. 끝내는 걸 잊으셨나요?")
        XCTAssertEqual([CareText.pickEnd, CareText.closeEndUnknown], ["끝 시각 고르기", "끝 시각 모름으로 닫기"])
        // '끝 시각 고르기'로 3시간 넘는 끝을 넣으면 저장하지 않는다.
        var picked = running; picked.endAt = nowMs
        XCTAssertEqual(app.recordCare.saveEvent(picked), .problem(.tooLong))
        XCTAssertTrue(app.care.care.events.first { $0.id == running.id }!.inProgress)
        // '끝 시각 모름으로 닫기'
        app.recordCare.endUnknown(running.id)
        XCTAssertEqual(app.care.care.events.first { $0.id == running.id }?.endUnknown, true)
        XCTAssertNil(app.care.care.events.first { $0.id == running.id }?.endAt)
    }

    func test_겹치는_수유는_경고하고_저장할_수_있다() {
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T10:00:00+09:00", end: "2026-11-05T10:30:00+09:00")), .saved)
        let overlapping = event(.feed, "2026-11-05T10:20:00+09:00", end: "2026-11-05T10:40:00+09:00")
        XCTAssertEqual(app.recordCare.saveEvent(overlapping), .overlap)
        XCTAssertEqual(app.care.care.events.count, 1)   // 묻기 전에는 저장하지 않는다
        XCTAssertEqual(CareSaveResult.overlap.message(for: .feed), "다른 수유와 시간이 겹쳐요")
        XCTAssertEqual(app.recordCare.saveEvent(overlapping, allowOverlap: true), .saved)   // '그래도 저장'
        XCTAssertEqual(app.care.care.events.count, 2)
        // 맞닿기만 하면 겹침이 아니다. 다른 종류와는 겹쳐도 묻지 않는다.
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T10:40:00+09:00", end: "2026-11-05T10:50:00+09:00")), .saved)
        XCTAssertEqual(app.recordCare.saveEvent(event(.sleep, "2026-11-05T10:10:00+09:00", end: "2026-11-05T10:45:00+09:00")), .saved)
        XCTAssertEqual(CareSaveResult.overlap.message(for: .sleep), "다른 잠과 시간이 겹쳐요")

        // 경계: 끝 모름 기록은 시작 한 점, 점 p와 [s, e)는 s ≤ p < e, 두 점은 같은 시각이면 겹친다.
        let now = nowMs
        let base = [event(.feed, "2026-11-05T08:00:00+09:00", end: "2026-11-05T08:30:00+09:00"),
                    event(.feed, "2026-11-05T09:00:00+09:00", endUnknown: true)]
        func overlaps(_ e: CareEvent, _ list: [CareEvent] = base) -> Bool { CareRules.overlaps(e, in: list, now: now) }
        XCTAssertTrue(overlaps(event(.feed, "2026-11-05T08:00:00+09:00", endUnknown: true)))    // p = s
        XCTAssertTrue(overlaps(event(.feed, "2026-11-05T08:29:00+09:00", endUnknown: true)))
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T08:30:00+09:00", endUnknown: true)))   // p = e
        XCTAssertTrue(overlaps(event(.feed, "2026-11-05T09:00:00+09:00", endUnknown: true)))    // 두 점이 같은 시각
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T09:00:01+09:00", endUnknown: true)))
        XCTAssertTrue(overlaps(event(.feed, "2026-11-05T08:50:00+09:00", end: "2026-11-05T09:10:00+09:00")))   // 구간이 점을 품음
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T09:00:01+09:00", end: "2026-11-05T09:10:00+09:00")))
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T08:40:00+09:00", end: "2026-11-05T09:00:00+09:00")))  // 끝이 점에 닿기만 함
        // 진행 중 기록은 [시작, 지금): 지금부터 시작하는 기록과는 겹치지 않는다.
        var running = event(.feed, "2026-11-05T11:00:00+09:00")
        XCTAssertTrue(running.inProgress)
        XCTAssertTrue(overlaps(event(.feed, "2026-11-05T11:59:00+09:00", endUnknown: true), [running]))
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T12:00:00+09:00", endUnknown: true), [running]))
        running.kind = .sleep
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true), [running]))   // 다른 종류
        XCTAssertFalse(CareRules.overlaps(event(.note, "2026-11-05T08:10:00+09:00"),
                                          in: [event(.note, "2026-11-05T08:10:00+09:00")], now: now))      // 메모·기저귀는 보지 않는다
    }
}
