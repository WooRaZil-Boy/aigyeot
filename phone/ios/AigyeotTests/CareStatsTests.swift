import XCTest
@testable import Aigyeot

/// 집계 공통 규칙(CareStats). 패턴 숫자 칸·7일 막대·오늘 막대가 같은 함수를 쓴다.
final class CareStatsTests: XCTestCase {
    private let seoul = TimeZone(identifier: "Asia/Seoul")!
    private func ms(_ s: String) -> Int64 { CareTime.ms(Rfc3339.parse(s)!) }
    private func feed(_ s: String, deleted: Bool = false) -> CareEvent {
        CareEvent(id: s, kind: .feed, startAt: ms(s), zone: "Asia/Seoul", createdAt: 0, updatedAt: 0, deletedAt: deleted ? 1 : nil)
    }

    func test_지난_며칠은_오늘을_포함해_오래된_날부터다() {
        XCTAssertEqual(CareStats.lastDays(3, now: ms("2026-11-10T09:00:00+09:00"), zone: seoul), ["2026-11-08", "2026-11-09", "2026-11-10"])
    }

    func test_수유_간격은_지운_기록을_빼고_10시간_넘는_간격을_뺀다() {
        let events = [feed("2026-11-10T01:00:00+09:00"), feed("2026-11-10T03:00:00+09:00"), feed("2026-11-10T04:00:00+09:00", deleted: true),
                      feed("2026-11-10T15:00:00+09:00"), feed("2026-11-10T18:00:00+09:00")]
        let starts = CareStats.feedStarts(events, until: ms("2026-11-10T20:00:00+09:00"))
        XCTAssertEqual(starts.count, 4)
        XCTAssertEqual(CareStats.feedGaps(starts).map(\.ms), [2 * 3_600_000, 3 * 3_600_000])   // 03시 → 15시(12시간)는 뺀다
        XCTAssertEqual(CareStats.median([5, 1, 3]), 3)
        XCTAssertEqual(CareStats.median([4, 1, 3, 2]), 2)                     // 짝수: 가운데 둘의 평균(내림)
        XCTAssertNil(CareStats.usualGap([1, 2, 3, 4]))                        // 5개 미만이면 '평소' 없음
        XCTAssertEqual(CareStats.usualGap([1, 2, 3, 4, 5]), 3)
    }

    func test_하루_평균은_기록한_날만_나눠_센다() {
        XCTAssertEqual(CareStats.perRecordedDay([0, 4, 0, 6]), 5)
        XCTAssertNil(CareStats.perRecordedDay([0, 0]))
        XCTAssertEqual(CareStats.perRecordedDay([Int64(0), 3_000, 4_001]), 3_500)
    }

    func test_방법별_성적은_판정할_수_있는_것_먼저_그친_비율_높은_순이다() {
        let s = [MethodStat(method: .lullaby, label: "자장가", successes: 1, attempts: 2),
                 MethodStat(method: .womb, label: "뱃속 소리", successes: 3, attempts: 4),
                 MethodStat(method: .whiteNoise, label: "백색소음", successes: 6, attempts: 7),
                 MethodStat(method: .unknown, label: "?", successes: -2, attempts: 5)]
        XCTAssertEqual(CareStats.methodOrder(s).map(\.index), [2, 1, 3, 0])
    }
}
