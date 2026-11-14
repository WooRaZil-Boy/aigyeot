@testable import Aigyeot
import UIKit
import XCTest

/// 디자인 토큰: Swift 표가 공용 원본 `shared/src/test/resources/design/tokens.json`의 폰 값과 같은지 본다
/// (안드로이드·차 시험도 같은 파일을 읽는다). 빠진 이름·남는 이름·다른 값 모두 실패다.
final class DesignTokenTests: XCTestCase {
    /// 이 파일 기준 저장소 루트의 design 폴더(`phone/ios/AigyeotTests/` → 위로 셋, ProtocolFixturesTests와 같은 방식).
    static let file = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("shared/src/test/resources/design/tokens.json")

    private func json() throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: Self.file)) as? [String: Any])
    }

    func test_색은_tokens_json과_이름·라이트·다크가_같다() throws {
        let colors = try XCTUnwrap(json()["color"] as? [String: [String]])
        XCTAssertEqual(DesignSystem.palette, colors)
    }

    func test_폰_글자_단계는_크기·줄_높이·굵기가_같다() throws {
        let type = try XCTUnwrap((json()["type"] as? [String: Any])?["phone"] as? [String: Any])
        let want = type.filter { !$0.key.hasPrefix("$") }.mapValues { ($0 as! [NSNumber]).map(\.doubleValue) }
        let have = DesignSystem.typeScale.mapValues { [Double($0.size), Double($0.line), Double($0.weight)] }
        XCTAssertEqual(have, want)
    }

    func test_폰_모서리는_같다() throws {
        let r = try XCTUnwrap((json()["radius"] as? [String: Any])?["phone"] as? [String: NSNumber])
        XCTAssertEqual(DesignSystem.radius.mapValues(Double.init), r.mapValues(\.doubleValue))
    }

    func test_모션_시간·곡선과_스프링이_같다() throws {
        let motion = try XCTUnwrap(json()["motion"] as? [String: Any]).filter { !$0.key.hasPrefix("$") }
        XCTAssertEqual(Set(motion.keys), Set(DesignSystem.motion.keys))
        for (k, v) in motion {
            let a = v as! [Any]
            XCTAssertEqual(DesignSystem.motion[k]?.ms, (a[0] as! NSNumber).intValue, k)
            XCTAssertEqual(DesignSystem.motion[k]?.curve, a[1] as? String, k)
        }
        let spring = try XCTUnwrap(json()["spring"] as? [String: Any]).filter { !$0.key.hasPrefix("$") }
        XCTAssertEqual(Set(spring.keys), Set(DesignSystem.springs.keys))
        for (k, v) in spring {
            let d = v as! [String: Any]
            XCTAssertEqual(DesignSystem.springs[k]?.damping, (d["damping"] as! NSNumber).doubleValue, k)
            XCTAssertEqual(DesignSystem.springs[k]?.stiffness, (d["stiffness"] as! NSNumber).doubleValue, k)
            XCTAssertEqual(DesignSystem.springs[k]?.swiftui, d["swiftui"] as? String, k)
        }
    }

    func test_곡선·스프링_글자에서_애니메이션_값을_읽는다() {
        XCTAssertEqual(DesignSystem.Motion.numbers("cubic-bezier(0.65, 0, 0.35, 1)"), [0.65, 0, 0.35, 1])
        XCTAssertEqual(DesignSystem.Motion.numbers(".smooth(duration: 0.35, extraBounce: 0.12)"), [0.35, 0.12])
        XCTAssertEqual(DesignSystem.Motion.numbers("linear"), [])
        XCTAssertEqual(DesignSystem.Motion.animation("camera"), .timingCurve(0.65, 0, 0.35, 1, duration: 1.1))
        XCTAssertEqual(DesignSystem.Motion.animation("press"), .linear(duration: 0.09))
        XCTAssertEqual(DesignSystem.Motion.bouncy, .smooth(duration: 0.35, extraBounce: 0.12))
    }

    func test_반투명_선_색은_알파까지_읽는다() {
        var a: CGFloat = 0
        UIColor(hex: "#191F2814").getRed(nil, green: nil, blue: nil, alpha: &a)
        XCTAssertEqual(a, 0x14 / 255, accuracy: 0.001)
    }

    func test_Pretendard가_앱에_올라와_있고_굵기_축을_쓴다() throws {
        XCTAssertNotNil(UIFont(name: DesignSystem.pretendard, size: 16), "UIAppFonts의 PretendardVariable.ttf")
        let f = DesignSystem.uiFont(16, 650)
        XCTAssertTrue(f.fontName.hasPrefix("PretendardVariable"), f.fontName)
        let axes = CTFontCopyVariation(f as CTFont) as? [NSNumber: NSNumber]
        XCTAssertEqual(axes?[NSNumber(value: 0x77676874)]?.doubleValue, 650)
    }

    func test_선_아이콘은_모두_앱에_있다() {
        for i in Ico.allCases { XCTAssertNotNil(UIImage(named: i.assetName), i.assetName) }
    }

    func test_서체·아이콘_라이선스_전문이_앱에_있다() {
        XCTAssertNotNil(OssEntry.text("OFL-Pretendard"))
        XCTAssertNotNil(OssEntry.text("ISC-Lucide"))
        XCTAssertTrue(OssEntry.all.contains { $0.licenses.contains { $0.file == "OFL-Pretendard" } })
        XCTAssertTrue(OssEntry.all.contains { $0.licenses.contains { $0.file == "ISC-Lucide" } })
    }

    /// 부품 움직임은 표의 곡선 이름을 읽는다(같은 이름의 움직임이 다른 곡선을 갖지 않게).
    func test_부품_움직임은_표의_곡선을_쓴다() {
        XCTAssertEqual(DesignSystem.Motion.seconds("camera"), 1.1)
        let camera = DesignSystem.Motion.curve("camera")
        XCTAssertEqual(camera.value(at: 0.5), 0.5, accuracy: 0.001)          // cubic-bezier(.65,0,.35,1)는 가운데에서 반
        XCTAssertLessThan(camera.value(at: 0.25), 0.25)                       // 처음은 느리게
        XCTAssertEqual(DesignSystem.Motion.curve("press"), .linear)
        XCTAssertEqual(DesignSystem.Motion.liveSeconds * DesignSystem.Motion.liveSpreadShare, 1.08, accuracy: 0.0001)
    }

    /// 글자 역할은 값 하나에 이름 하나다(같은 크기·굵기를 두 이름이 갖지 않고, tokens 7단계와도 겹치지 않는다).
    func test_글자_역할은_값_하나에_이름_하나다() {
        let roles = DesignSystem.TextRole.allCases.map { "\($0.style.size)/\($0.style.weight)" }
        XCTAssertEqual(Set(roles).count, roles.count)
        let steps = Set(DesignSystem.typeScale.values.map { "\($0.size)/\($0.weight)" })
        XCTAssertTrue(steps.isDisjoint(with: roles))
    }
}
