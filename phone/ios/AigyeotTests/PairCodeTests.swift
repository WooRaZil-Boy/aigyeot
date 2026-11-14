import XCTest
@testable import Aigyeot

final class PairCodeTests: XCTestCase {
    func test_연결_코드는_6자리_숫자() {
        XCTAssertEqual(PairCode.normalize("042917"), .pair("042917"))
        XCTAssertEqual(PairCode.normalize(" 042 917 "), .pair("042917"))
        XCTAssertNil(PairCode.normalize("04291"))
        XCTAssertNil(PairCode.normalize("0429170"))
    }

    func test_초대_코드는_10자_Crockford() {
        XCTAssertEqual(PairCode.normalize("7k3qm-2xw9d"), .invite("7K3QM2XW9D"))
        XCTAssertEqual(PairCode.normalize("7K3QM-2XW9D")?.display, "7K3QM-2XW9D")
        // O→0, I·L→1
        XCTAssertEqual(PairCode.normalize("OILOILOILO"), .invite("0110110110"))
        XCTAssertNil(PairCode.normalize("7K3QM2XW9U"))   // U는 Crockford에 없다
    }

    func test_링크는_scheme_host_code를_검사한다() {
        XCTAssertEqual(PairCode.parse(link: URL(string: "aigyeot://pair?code=042917")!), .pair("042917"))
        XCTAssertEqual(PairCode.parse(link: URL(string: "aigyeot://pair?code=7K3QM2XW9D")!), .invite("7K3QM2XW9D"))
        XCTAssertNil(PairCode.parse(link: URL(string: "aigyeot://pair?code=12")!))
        XCTAssertNil(PairCode.parse(link: URL(string: "aigyeot://pair")!))
        XCTAssertNil(PairCode.parse(link: URL(string: "aigyeot://other?code=042917")!))
        XCTAssertNil(PairCode.parse(link: URL(string: "https://x.example/pair?code=042917")!))
    }

    func test_QR_글자는_링크나_코드() {
        XCTAssertEqual(PairCode.parse(scanned: "aigyeot://pair?code=042917"), .pair("042917"))
        XCTAssertEqual(PairCode.parse(scanned: "042917"), .pair("042917"))
        XCTAssertNil(PairCode.parse(scanned: "https://example.com"))
    }
}
