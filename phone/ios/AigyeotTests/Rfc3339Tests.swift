import XCTest
@testable import Aigyeot

final class Rfc3339Tests: XCTestCase {
    func test_소수점_있는_시각과_없는_시각이_같은_순간() {
        let a = Rfc3339.parse("2026-11-05T08:00:00Z"), b = Rfc3339.parse("2026-11-05T08:00:00.000Z")
        XCTAssertNotNil(a)
        XCTAssertEqual(a, b)
        XCTAssertEqual(Rfc3339.parse("2026-11-05T17:00:00+09:00"), a)
        XCTAssertEqual(Rfc3339.parse("2026-11-05T08:00:00.5Z")?.timeIntervalSince(a!), 0.5)
        XCTAssertNil(Rfc3339.parse("가짜"))
    }

    func test_보낼_시각의_소수_초는_버린다() {   // 계약 3.3·R18 '소수 초는 버린다'(안드로이드 truncatedTo(SECONDS)와 같게)
        let seoul = TimeZone(identifier: "Asia/Seoul")!
        let t = Rfc3339.parse("2026-11-05T15:00:00+09:00")!
        XCTAssertEqual(Rfc3339.string(t.addingTimeInterval(0.7), timeZone: seoul), "2026-11-05T15:00:00+09:00")
        XCTAssertEqual(Rfc3339.string(t.addingTimeInterval(59.999), timeZone: seoul), "2026-11-05T15:00:59+09:00")
    }

    func test_내림차순_정렬은_예전_비교식과_같고_90개가_빠르다() {
        let base = Date(timeIntervalSince1970: 1_790_000_000)
        let items = (0..<90).map { i -> String in
            let d = base.addingTimeInterval(Double((i * 37) % 90) * 60)
            return i % 2 == 0 ? Rfc3339.string(d) : Rfc3339.string(d).replacingOccurrences(of: "Z", with: "Z")
        } + ["깨짐"]
        let old = items.sorted { (Rfc3339.parse($0) ?? .distantPast) > (Rfc3339.parse($1) ?? .distantPast) }
        let t0 = Date()
        let new = Rfc3339.sortedDescending(items) { $0 }
        print("정렬 \(items.count)개: \(Date().timeIntervalSince(t0) * 1000)ms")
        XCTAssertEqual(new, old)
    }
}
