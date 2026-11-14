import XCTest
@testable import Aigyeot

final class WaveformTests: XCTestCase {
    /// 막대 60개 × (4pt + 간격 3pt)가 화면 폭을 넘었다. 폭에 들어가는 만큼만 최근 막대를 그린다.
    func test_녹음_파형은_화면_폭을_넘지_않는다() {
        for width in [0.0, 3.0, 4.0, 280.0, 327.0, 345.0, 417.0, 600.0] as [CGFloat] {
            let n = Waveform.barCount(fitting: width)
            XCTAssertLessThanOrEqual(CGFloat(max(0, n * 7 - 3)), width, "\(width)")
            XCTAssertGreaterThan(CGFloat((n + 1) * 7 - 3), width, "\(width)")   // 더 넣으면 넘는다(빈자리 없이 채운다)
        }
        XCTAssertEqual(Waveform.barCount(fitting: 327), 47)
        XCTAssertEqual(Waveform.barCount(fitting: 417), 60)
    }
}

@MainActor
final class RecorderModelTests: XCTestCase {
    func test_권한_창을_기다리는_사이_버리면_늦게_허용해도_녹음을_시작하지_않는다() async {
        let rec = RecorderViewModel(recorder: AudioSongRecorder())
        rec.requestPermission = { [weak rec] in rec?.discard(); return true }   // 기다리는 사이 시트가 닫혔다(onDisappear → discard)
        await rec.start()
        XCTAssertEqual(rec.phase, .idle)   // 녹음기·반복 타이머·오디오 세션을 만들지 않았다
        XCTAssertNil(rec.fileURL)
        XCTAssertNil(rec.error)
    }

    func test_권한_창을_기다리는_사이_또_누르면_한_번만_시작한다() async {
        let rec = RecorderViewModel(recorder: AudioSongRecorder())
        var asked = 0
        rec.requestPermission = { asked += 1; try? await Task.sleep(nanoseconds: 50_000_000); return false }
        async let a: Void = rec.start()
        async let b: Void = rec.start()
        _ = await (a, b)
        XCTAssertEqual(asked, 1)
    }
}
