import XCTest
@testable import Aigyeot

final class SoundLabelTests: XCTestCase {
    func test_모르는_소리_방법의_음원_이름은_기타() throws {
        let ref = try JSONDecoder().decode(SoundRef.self, from: Data(#"{"method":"future_sound","label":"미래 소리"}"#.utf8))
        XCTAssertEqual(ref.method, .unknown)
        XCTAssertEqual(soundLabel(ref.method, ref.label), "기타")
        XCTAssertEqual(soundLabel(.lullaby, "자장가 1"), "자장가 1")
    }
}

final class StopTextTests: XCTestCase {
    private func stop(_ mode: HandoffKind, told: Bool?) throws -> StopInfo {
        let t = told.map { ",\"told_driver\":\($0)" } ?? ""
        let json = "{\"handoff\":true,\"reason\":\"attempt_limit\",\"mode\":\"\(mode.rawValue)\",\"rest_name\":\"휴게소\",\"waypoint_added\":false,\"guardian_notified\":false\(t)}"
        return try ProtocolCoding.decoder.decode(StopInfo.self, from: Data(json.utf8))
    }

    func test_운전자에게_말하지_못한_인계는_제안했다고_보이지_않는다() throws {
        XCTAssertEqual(stopHandoffText(try stop(.restOffer, told: false)), "쉼터는 묻지 못했어요")
        XCTAssertEqual(stopHandoffText(try stop(.arrivalNotice, told: false)), "도착 안내는 하지 못했어요")
        XCTAssertEqual(stopHandoffText(try stop(.noRest, told: false)), "안전한 곳에 세우도록 안내하지 못했어요")
        XCTAssertEqual(stopHandoffText(try stop(.restOffer, told: true)), "휴게소에 들르자고 제안했어요")
        XCTAssertEqual(stopHandoffText(try stop(.restOffer, told: nil)), "휴게소에 들르자고 제안했어요")
        XCTAssertEqual(stopHandoffText(try stop(.arrivalNotice, told: true)), "도착 안내를 했어요")
        XCTAssertEqual(stopHandoffText(try stop(.noRest, told: nil)), "경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요")
        XCTAssertEqual(stopHandoffText(try stop(.parked, told: nil)), "차가 선 채로 달래기를 멈췄어요")
        let out = String(data: try ProtocolCoding.encoder.encode(try stop(.restOffer, told: nil)), encoding: .utf8)!
        XCTAssertFalse(out.contains("told_driver"))
    }

    func test_뒷좌석_출력이_없어_정차한_인계는_소리를_틀지_못했다고_쓴다() throws {
        let json = "{\"handoff\":true,\"reason\":\"no_sound\",\"mode\":\"parked\",\"rest_name\":null,\"waypoint_added\":false,\"guardian_notified\":false}"
        let s = try ProtocolCoding.decoder.decode(StopInfo.self, from: Data(json.utf8))
        XCTAssertEqual(stopText(s), "뒷좌석 출력 없음 · 차가 선 채로 소리를 틀지 못했어요")
    }

    func test_정차_인계는_알렸다고_보이지_않는다() throws {
        func parked(_ g: Bool) throws -> StopInfo {
            let json = "{\"handoff\":true,\"reason\":\"attempt_limit\",\"mode\":\"parked\",\"rest_name\":null,\"waypoint_added\":false,\"guardian_notified\":\(g)}"
            return try ProtocolCoding.decoder.decode(StopInfo.self, from: Data(json.utf8))
        }
        XCTAssertEqual(stopText(try parked(false)), "두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요")
        XCTAssertEqual(stopText(try parked(true)), "두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요 · 다른 보호자에게 알림")
    }

    func test_정차_문구는_확인된_사실만_쓴다() throws {
        func parked(_ g: Bool) throws -> StopInfo {
            let json = "{\"handoff\":true,\"reason\":\"attempt_limit\",\"mode\":\"parked\",\"rest_name\":null,\"waypoint_added\":false,\"guardian_notified\":\(g)}"
            return try ProtocolCoding.decoder.decode(StopInfo.self, from: Data(json.utf8))
        }
        XCTAssertEqual(stopText(try parked(true)), "두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요 · 다른 보호자에게 알림")
        XCTAssertEqual(stopText(try parked(false)), "두 번 시도 뒤 · 차가 선 채로 달래기를 멈췄어요")
        XCTAssertEqual(stopText(try stop(.noRest, told: true)), "두 번 시도 뒤 · 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요")
        XCTAssertEqual(stopText(try stop(.noRest, told: false)), "두 번 시도 뒤 · 안전한 곳에 세우도록 안내하지 못했어요")
        XCTAssertEqual(stopText(try stop(.noRest, told: nil)), "두 번 시도 뒤 · 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요")
    }

    func test_뒤붙임까지_전체_줄이_안드로이드와_같다() throws {
        let json = "{\"handoff\":true,\"reason\":\"session_cap\",\"mode\":\"rest_offer\",\"rest_name\":\"휴게소\",\"waypoint_added\":true,\"guardian_notified\":true,\"told_driver\":true}"
        let s = try ProtocolCoding.decoder.decode(StopInfo.self, from: Data(json.utf8))
        XCTAssertEqual(stopText(s), "달래기 20분 끝남 · 휴게소에 들르자고 제안했어요 · 경유지 추가 · 다른 보호자에게 알림")
    }
}

/// 3D 카드 '처음 자리로'의 짧은 쪽 각도(음수·여러 바퀴에도 −180 이상 180 미만).
final class CarPresetTests: XCTestCase {
    func test_처음_자리로는_짧은_쪽으로_돈다() {
        XCTAssertEqual(CarPreset.shortestTurn(from: 594, to: 0), 126)
        XCTAssertEqual(CarPreset.shortestTurn(from: -594, to: 0), -126)
        XCTAssertEqual(CarPreset.shortestTurn(from: 179, to: 0), -179)
        XCTAssertEqual(CarPreset.shortestTurn(from: 181, to: 0), 179)
        XCTAssertEqual(CarPreset.shortestTurn(from: 594, to: 34), 160)   // 카드 처음 자리(34°)
    }
}

/// 화면 읽기가 켜져 있으면 되돌리기 띠를 늘린다(WCAG 2.2.1 기본값의 10배). 띠를 띄울 때 한 번 정한다.
final class UndoBandTests: XCTestCase {
    func test_화면_읽기가_켜져_있으면_되돌리기_띠는_50초다() {
        XCTAssertEqual(RetryPolicy.undoBandMs(screenReader: false), 5000)
        XCTAssertEqual(RetryPolicy.undoBandMs(screenReader: true), 50_000)
    }

    func test_시스템_권장_시간이_더_길면_그_값이다() {
        XCTAssertEqual(RetryPolicy.undoBandMs(screenReader: false, systemRecommendedMs: 12_000), 12_000)
        XCTAssertEqual(RetryPolicy.undoBandMs(screenReader: true, systemRecommendedMs: 60_000), 60_000)
        XCTAssertEqual(RetryPolicy.undoBandMs(screenReader: true, systemRecommendedMs: 6_000), 50_000)
    }
}

/// 첫 안내 무대 높이 = 창 − (무대 여백 6 + 글 칸) − 단추 띠 − 32, 최대 232(안드로이드 222), 120 미만이면 숨김.
/// 안드로이드의 글 칸 446은 무대 여백 6을 넣은 값이라 iOS 글 높이(여백 없이)로는 440이다.
final class IntroLayoutTests: XCTestCase {
    func test_첫_안내_무대는_남는_높이를_쓰고_120보다_작으면_숨긴다() {
        XCTAssertEqual(IntroLayout.heroHeight(window: 874, text: 440, bar: 150), 232)
        XCTAssertEqual(IntroLayout.heroHeight(window: 915, text: 440, bar: 134, max: 222), 222)
        XCTAssertEqual(IntroLayout.heroHeight(window: 760, text: 440, bar: 134), 148)
        XCTAssertNil(IntroLayout.heroHeight(window: 667, text: 464, bar: 150))
        // 경계: 남는 높이(창 − 6 − 글 − 띠 − 32) 119는 숨김, 120·121·125·126은 그 높이
        let base: CGFloat = 6 + 440 + 150 + 32
        XCTAssertNil(IntroLayout.heroHeight(window: base + 119, text: 440, bar: 150))
        for r: CGFloat in [120, 121, 125, 126] { XCTAssertEqual(IntroLayout.heroHeight(window: base + r, text: 440, bar: 150), r) }
    }

    /// 잰 글 높이가 무대 여부에 따라 바뀌면(무대 여백 6) 남는 높이 120~125pt 창에서 보임·숨김이 오갔다.
    /// 글 높이는 무대 여부와 무관하게 재고 여백은 함수가 더하므로, 그린 뒤 다시 재어도 결과가 같다(한 번에 정해짐).
    func test_첫_안내_무대_높이는_같은_입력이면_한_번에_정해진다() {
        for window in stride(from: CGFloat(700), through: 800, by: 1) {
            let first = IntroLayout.heroHeight(window: window, text: 440, bar: 150)
            let measuredAgain: CGFloat = 440   // 무대를 보였든 숨겼든 같은 글 높이(여백 전)
            XCTAssertEqual(IntroLayout.heroHeight(window: window, text: measuredAgain, bar: 150), first, "창 \(window)")
            if let h = first { XCTAssertLessThanOrEqual(h + IntroLayout.stageGap + 440 + 150 + 32, window) }   // 보이면 '자세히 보기'가 흐림 위에 든다
        }
        XCTAssertEqual(IntroLayout.heroHeight(window: 748, text: 440, bar: 150), 120)   // 지적 예(창 748)는 120으로 정해진다
    }
}

/// 3D 준비 표시와 첫 안내 등장 안무(안드로이드 StageTest와 같은 이름·값). 시각은 무대를 붙인 때부터 ms.
final class StageTests: XCTestCase {
    private func marks(firstFrame: Int64?, failed: Int64? = nil, reduced: Bool = false, until: Int64 = 5_000) -> [Int64: StageMark] {
        Dictionary(uniqueKeysWithValues: stride(from: Int64(0), through: until, by: 10).map { ($0, StageMark.at($0, firstFrame: firstFrame, failed: failed, reduced: reduced)) })
    }
    private func all(_ m: [Int64: StageMark], _ from: Int64, _ to: Int64, _ want: StageMark) -> Bool {
        stride(from: from, to: to, by: 10).allSatisfy { m[$0] == want }
    }

    func test_고리는_초당_15장_24도씩_돈다() {
        XCTAssertEqual(StageMark.ringFps, 15)
        XCTAssertEqual(StageMark.ringSteps, stride(from: 0.0, to: 360.0, by: 24.0).map { $0 })   // 0, 24, …, 336
        XCTAssertEqual([0, 66, 67, 133, 134, 999, 1_000, 1_067].map { StageMark.ringAngle(atMs: $0) }, [0, 0, 24, 24, 48, 336, 0, 24])   // 1000/15ms마다 끊어 건너뜀
    }

    func test_준비가_800ms_안에_끝나면_고리를_보이지_않는다() {
        XCTAssertTrue(marks(firstFrame: 670).values.allSatisfy { $0 == .hidden })
        XCTAssertTrue(marks(firstFrame: 800).values.allSatisfy { $0 == .hidden })
    }

    func test_한_번_보인_고리는_400ms는_둔다() {
        let m = marks(firstFrame: 900)
        XCTAssertEqual(m[790], .hidden)
        XCTAssertTrue(all(m, 800, 1_200, .ring))   // 첫 그림(900)이 와도 800 + 400까지
        XCTAssertEqual(m[1_200], .hidden)
    }

    func test_첫_그림이_나오면_고리를_감춘다() {
        let m = marks(firstFrame: 4_300)
        XCTAssertTrue(all(m, 800, 4_300, .ring))
        XCTAssertTrue(all(m, 4_300, 5_010, .hidden))
        XCTAssertEqual(StageMark.at(60_000, firstFrame: nil, failed: nil, reduced: false), .ring)   // 기다리는 시간 상한은 두지 않는다
    }

    func test_동작_줄이기면_정지_표시다() {
        let m = marks(firstFrame: 900, reduced: true)
        XCTAssertEqual(m[790], .hidden)
        XCTAssertTrue(all(m, 800, 1_200, .still))   // 같은 때(800·400)에 고리 대신 정지 표시
        XCTAssertEqual(m[1_200], .hidden)
    }

    func test_준비_실패면_정지_표시만_남고_읽지_않는다() {
        let m = marks(firstFrame: nil, failed: 1_000)
        XCTAssertEqual(m[990], .ring)
        XCTAssertTrue(all(m, 1_000, 5_010, .failed))   // failed는 읽기 이름이 없는 정지 표시(StageMarkView가 accessibilityHidden)
        XCTAssertEqual(StageMark.at(300, firstFrame: nil, failed: 200, reduced: false), .failed)   // 800 전 실패도 바로
    }

    func test_첫_안내는_3D_보기를_만들지_않는다() throws {
        // 첫 안내 무대는 정지 그림이라 3D 무대·준비 표시가 없다(차 탭만 3D)
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let src = try String(contentsOf: root.appendingPathComponent("Aigyeot/Presentation/Root/IntroView.swift"), encoding: .utf8)
        XCTAssertFalse(src.contains("CarStageCard") || src.contains("CarStageView"))
        XCTAssertTrue(src.contains("Image(\"IntroCar\")"))
    }

    func test_첫_안내_안무는_처음_한_번만_한다() {
        IntroMotion.played = false
        XCTAssertNotNil(IntroMotion.take(reduced: false, stage: true))
        XCTAssertNil(IntroMotion.take(reduced: false, stage: true))   // 뒤로 와 다시 그림·시트 닫기·다크 전환
        IntroMotion.played = false
    }

    func test_동작_줄이기면_첫_안내_안무가_없다() {
        IntroMotion.played = false
        XCTAssertNil(IntroMotion.take(reduced: true, stage: true))
        XCTAssertNil(IntroMotion.take(reduced: false, stage: true))   // 줄이기로 건너뛴 것도 했음
        IntroMotion.played = false
    }

    func test_무대가_숨으면_차_안무_없이_글이_120ms부터다() {
        XCTAssertEqual(IntroMotion.plan(played: false, reduced: false, stage: true), IntroPlan(car: true, textDelayMs: 240, barDelayMs: 590))
        XCTAssertEqual(IntroMotion.plan(played: false, reduced: false, stage: false), IntroPlan(car: false, textDelayMs: 120, barDelayMs: 470))
    }
}

final class PairWaitTextTests: XCTestCase {
    func test_남은_시간_글_경계() {
        let t = RetryPolicy.pairWaitText
        XCTAssertEqual(t(600), "10분 뒤에 다시 해 주세요")
        XCTAssertEqual(t(540.001), "10분 뒤에 다시 해 주세요")
        XCTAssertEqual(t(540), "9분 뒤에 다시 해 주세요")
        XCTAssertEqual(t(60.001), "2분 뒤에 다시 해 주세요")
        XCTAssertEqual(t(60), "60초 뒤에 다시 해 주세요")
        XCTAssertEqual(t(0.001), "1초 뒤에 다시 해 주세요")
        XCTAssertNil(t(0))
        XCTAssertEqual(t(nil), "잠시 뒤에 다시 해 주세요")
    }
}

/// '코드 시도 초과': 429 글은 잠금 동안만 보이고, Retry-After가 지나 단추가 다시 켜지면 걷는다(직접 입력·확인 화면 같은 규칙).
final class ConnectFailureVisibleTests: XCTestCase {
    func test_429_글은_잠금_동안만_보이고_풀리면_걷힌다() {
        XCTAssertEqual(ConnectFailure.rateLimited.visible(locked: true), .rateLimited)
        XCTAssertNil(ConnectFailure.rateLimited.visible(locked: false))
        XCTAssertNil(ConnectFailure.rateLimited.visible(locked: false))   // 잠금 중 누름 글도 풀리면 걷힌다
        XCTAssertEqual(ConnectFailure.codeInvalid.visible(locked: false), .codeInvalid)   // 다른 실패는 그대로
        let code = PairCode.normalize("123456")!
        var ui = ConnectUiState(run: .failed(code: code, .rateLimited), isLocked: true, connected: false)
        XCTAssertEqual(ui.failure(), .rateLimited)
        XCTAssertEqual(ui.failure(for: code), .rateLimited)
        ui.isLocked = false
        XCTAssertNil(ui.failure())
        XCTAssertNil(ui.failure(for: code))
        ui.run = .failed(code: code, .codeInvalid)
        XCTAssertEqual(ui.failure(for: code), .codeInvalid)
        XCTAssertNil(ui.failure(for: PairCode.normalize("654321")!))   // 확인 화면은 그 코드의 실패만
    }
}
