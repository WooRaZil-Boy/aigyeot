import XCTest
@testable import Aigyeot

/// 잠금 화면 진행 표시·내보내기·보관.
@MainActor
final class CareExportTests: CareTestCase {
    // MARK: 잠금 화면

    func test_잠금_화면_표시는_진행_중_기록과_맞춘다() throws {
        app.recordCare.start(.feed, side: .left)
        app.recordCare.start(.sleep)
        let feed = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        let sleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: app.care.care.events))
        let wanted = CareActivityPlan.wanted(app.care.care)
        XCTAssertEqual(wanted.map(\.eventId), [feed.id, sleep.id])
        XCTAssertEqual(wanted[0].state, .init(startAt: CareTime.date(feed.startAt), side: "왼쪽"))
        XCTAssertEqual(wanted[1].state.side, nil)   // 잠은 쪽이 없다
        XCTAssertTrue(wanted[1].sleep)
        // 처음에는 둘 다 시작한다.
        XCTAssertEqual(CareActivityPlan.diff(wanted, existing: [:]).start, wanted)
        // 쪽을 바꾸면 고치고, 끝난 기록의 활동은 끝낸다.
        var changed = feed; changed.feedSide = .right
        XCTAssertEqual(app.recordCare.saveEvent(changed, allowOverlap: true), .saved)
        app.recordCare.end(sleep.id)
        let d = CareActivityPlan.diff(CareActivityPlan.wanted(app.care.care),
                                      existing: [feed.id: wanted[0].state, sleep.id: wanted[1].state, "OLD": wanted[1].state])
        XCTAssertEqual(d.start, [])
        XCTAssertEqual(d.update.map(\.state.side), ["오른쪽"])
        XCTAssertEqual(d.end, ["OLD", sleep.id].sorted())
        // 바뀐 것이 없으면 아무것도 하지 않는다.
        let same = CareActivityPlan.wanted(app.care.care)
        XCTAssertEqual(CareActivityPlan.diff(same, existing: [feed.id: same[0].state]), .init())
        // 잠금 화면 글자(notify.*)
        XCTAssertEqual([CareActivityText.feeding, CareActivityText.sleeping, CareActivityText.end], ["수유 중", "자는 중", "끝내기"])
        // 기록이 바뀔 때마다 알린다(앱은 이 알림으로 Live Activity를 맞춘다).
        var seen: [CareFile] = []
        app.recordCare.ongoing = ClosureNotice { seen.append($0) }
        app.recordCare.end(feed.id)
        XCTAssertEqual(seen.last, app.care.care)
    }

    // MARK: 내보내기

    func test_내_기록을_정해진_CSV로_내보낸다() throws {
        var feed = event(.feed, "2026-11-05T09:00:00+09:00", end: "2026-11-05T09:15:00+09:00", id: "F")
        feed.feedSide = .bottle; feed.amountMl = 120
        var note = event(.note, "2026-11-05T08:00:00+09:00", zone: "America/Los_Angeles", id: "N")
        note.note = "트림, \"잘\" 함\n두 번"
        var diaper = event(.diaper, "2026-11-05T10:00:00+09:00", id: "D"); diaper.diaper = .wet
        let sleep = event(.sleep, "2026-11-05T11:00:00+09:00", endUnknown: true, id: "S")
        let gone = event(.note, "2026-11-05T07:00:00+09:00", deletedAt: nowMs, id: "X")   // 지운 것은 넣지 않는다
        // 초까지(밀리초 버림), 오프셋이 0이면 Z.
        var london = event(.note, "2026-11-05T12:00:00+09:00", zone: "Europe/London", id: "L")
        london.startAt += 999
        XCTAssertEqual(CareExport.iso(london.startAt, london.timeZone), "2026-11-05T03:00:00Z")
        XCTAssertEqual(CareExport.iso(london.startAt, TimeZone(identifier: "UTC")!), "2026-11-05T03:00:00Z")
        let data = CareExport.csv([diaper, gone, sleep, feed, note])
        XCTAssertEqual(Array(data.prefix(3)), [0xEF, 0xBB, 0xBF])   // UTF-8 BOM
        let text = try XCTUnwrap(String(data: data.dropFirst(3), encoding: .utf8))
        XCTAssertEqual(text, [
            "kind,start,end,end_unknown,side,amount_ml,diaper,note",
            "NOTE,2026-11-04T15:00:00-08:00,,false,,,,\"트림, \"\"잘\"\" 함\n두 번\"",   // 그 기록의 시간대, RFC 4180 따옴표
            "FEED,2026-11-05T09:00:00+09:00,2026-11-05T09:15:00+09:00,false,BOTTLE,120,,",
            "DIAPER,2026-11-05T10:00:00+09:00,,false,,,WET,",
            "SLEEP,2026-11-05T11:00:00+09:00,,true,,,,"
        ].joined(separator: "\r\n") + "\r\n")   // 줄바꿈은 CRLF, 시작 시각 오름차순
        XCTAssertEqual(CareText.exportFileName(clock, zone: seoul), "aigyeot-care-20261105.csv")
        XCTAssertEqual(CareText.export, "내 기록 내보내기")
    }

    // MARK: 보관

    func test_지우지_않은_돌봄_기록은_오래돼도_남는다() {
        var care = CareFile(migratedFeedings: true)
        care.events.append(event(.note, "2024-11-05T09:00:00+09:00", id: "Y2"))                                       // 2년 전
        care.events.append(event(.feed, "2026-08-01T09:00:00+09:00", end: "2026-08-01T09:20:00+09:00", id: "D96"))   // 리포트 보관 90일보다 오래됨
        make(care: care)        // 앱을 열 때의 정리
        app.lifecycle.purgeExpired()    // 리포트·알림 90일 정리
        XCTAssertEqual(Set(app.care.care.events.map(\.id)), ["Y2", "D96"])
        XCTAssertEqual(careStore.value, care)
    }
}
