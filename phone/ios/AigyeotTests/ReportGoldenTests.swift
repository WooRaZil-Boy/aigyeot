import XCTest
@testable import Aigyeot

/// 리포트 화면에 나가는 문구를 공용 예제(`shared/src/test/resources/protocol/report_summary*.json`)마다 모아
/// `Golden/report_text.txt`와 글자까지 견준다. 한 줄은 `<예제 파일>:<JSON 경로>: <글자>` 꼴이다.
/// 안드로이드는 자기 골든 파일(`src/test/resources/golden/report_text.txt`)을 따로 두고 같은 입력에 같은 글이 나오게 맞춘다. 문구를 일부러 바꿨으면 `GOLDEN_UPDATE=1`로 다시 쓴다
/// (`xcodebuild test … TEST_RUNNER_GOLDEN_UPDATE=1`).
final class ReportGoldenTests: XCTestCase {
    static let fixtures = ["report_summary", "report_summary_handoff", "report_summary_womb"]

    static let goldenURL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().appendingPathComponent("Golden/report_text.txt")

    func lines(_ name: String) throws -> [String] {
        let data = try Data(contentsOf: ProtocolFixturesTests.dir.appendingPathComponent("\(name).json"))
        let body = try ProtocolCoding.decoder.decode(Envelope.self, from: data).decodeBody(ReportSummaryBody.self)
        var out: [String] = []
        func add(_ path: String, _ text: String) { out.append("\(name):\(path): \(text)") }
        for (i, e) in body.timeline.enumerated() {
            let p = "timeline[\(i)]"
            add("\(p).trigger", CareText.episodeStart(e.trigger))
            for (label, value) in episodeSoundLines(e) { add("\(p).line.\(label)", value) }
            for (k, play) in e.plays.enumerated() {
                add("\(p).plays[\(k)].purpose", purposeText(play.purpose))
                add("\(p).plays[\(k)].method", CareText.method(play.method))
            }
            add("\(p).result", CareText.result(e.result))
            if let x = e.excludedReason { add("\(p).excluded_reason", CareText.excluded(x)) }
            if let s = e.second { add("\(p).second", secondText(s)) }
            if let s = e.stop { add("\(p).stop", stopText(s)) }
        }
        for (j, a) in body.carActions.enumerated() { add("car_actions[\(j)].action", actionText(a.action)) }
        return out
    }

    func test_리포트_문구는_골든_파일과_같다() throws {
        let text = try Self.fixtures.flatMap(lines).joined(separator: "\n") + "\n"
        if ProcessInfo.processInfo.environment["GOLDEN_UPDATE"] == "1" {
            try FileManager.default.createDirectory(at: Self.goldenURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try text.write(to: Self.goldenURL, atomically: true, encoding: .utf8)
            return
        }
        let golden = try String(contentsOf: Self.goldenURL, encoding: .utf8)
        XCTAssertEqual(text, golden, "문구가 골든 파일과 다르다. 의도한 변경이면 GOLDEN_UPDATE=1로 다시 쓴다")
    }
}
