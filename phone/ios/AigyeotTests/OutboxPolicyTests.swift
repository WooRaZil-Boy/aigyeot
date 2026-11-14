import XCTest
@testable import Aigyeot

final class OutboxPolicyTests: XCTestCase {
    private func http(_ s: Int, _ c: String? = nil, retry: TimeInterval? = nil) -> RelayError { .http(status: s, code: c, retryAfter: retry) }

    func test_응답별_처리() {
        XCTAssertEqual(OutboxPolicy.classify(.network, attempts: 0), .retry(1))
        XCTAssertEqual(OutboxPolicy.classify(.network, attempts: 3), .retry(8))
        XCTAssertEqual(OutboxPolicy.classify(.network, attempts: 20), .retry(60))
        XCTAssertEqual(OutboxPolicy.classify(http(401), attempts: 0), .unauthorized)
        XCTAssertEqual(OutboxPolicy.classify(http(429, retry: 30), attempts: 0), .retry(30))
        XCTAssertEqual(OutboxPolicy.classify(http(503), attempts: 1), .retry(2))
        XCTAssertEqual(OutboxPolicy.classify(http(409, "song_quota"), attempts: 0), .retry(60))
        XCTAssertEqual(OutboxPolicy.classify(http(422, "song_blob_missing"), attempts: 0), .reupload)
        for s in [400, 403, 413, 415] { XCTAssertEqual(OutboxPolicy.classify(http(s), attempts: 0), .drop, "\(s)") }
        XCTAssertEqual(OutboxPolicy.classify(http(409, "id_conflict"), attempts: 0), .drop)
    }
}
