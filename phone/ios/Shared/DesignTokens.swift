import SwiftUI
import UIKit

/// 디자인 토큰(phone-care-plan 13장, 11-06 사용자 결정: 청록 + Pleos 회색). 안드로이드 `ui/Theme`과 같은 이름·같은 값이다.
/// 앱과 잠금 화면(Live Activity)이 함께 쓴다. 어둠 값은 시스템 어둠 모드에서 쓴다.
enum DesignSystem {
    // 색. 종류 색(feed·sleep·diaper·note)과 car는 늘 아이콘과 함께 쓴다. accent 위 글자는 onAccent다.
    static let bg = Color(light: 0xF7F8FA, dark: 0x131417)
    static let surface = Color(light: 0xFFFFFF, dark: 0x1D1E21)
    static let surfaceRaised = Color(light: 0xEDEEF2, dark: 0x2A2B30)
    static let textPrimary = Color(light: 0x131417, dark: 0xF7F8FA)
    static let textSecondary = Color(light: 0x4F525A, dark: 0xB9BCC3)
    static let accent = Color(light: 0x0E6D77, dark: 0x5CC6D0)
    static let onAccent = Color(light: 0xFFFFFF, dark: 0x06282C)
    static let feed = Color(light: 0xB83C0A, dark: 0xFF9A6B)
    static let sleep = Color(light: 0x5146B5, dark: 0xADA6FF)
    static let diaper = Color(light: 0x3F6E0D, dark: 0xA6D66A)
    static let note = Color(light: 0x5F636B, dark: 0xA4A8AE)
    static let car = Color(light: 0x0057DE, dark: 0x5AA8FF)
    static let warning = Color(light: 0x9A5B00, dark: 0xFFC24D)
    static let danger = Color(light: 0xC8211A, dark: 0xFF6D64)

    // 글자. 13장 크기는 기본 글자 크기에서의 값이고 동적 글자 크기를 따른다(줄 높이는 시스템 값).
    /// 32 Bold. 같은 크기의 글자 스타일이 없어 largeTitle 비율로 키운다.
    static var display: Font { Font(UIFontMetrics(forTextStyle: .largeTitle).scaledFont(for: .systemFont(ofSize: 32, weight: .bold)) as CTFont) }
    static let title = Font.title2.bold()   // 22 Bold
    static let headline = Font.headline     // 17 SemiBold
    static let body = Font.callout          // 16 Regular
    static let caption = Font.footnote      // 13 Regular
    /// 64 SemiBold 고정폭 숫자. display처럼 큰 글자 설정을 따른다(13장, 8장).
    static var timer: Font { Font(UIFontMetrics(forTextStyle: .largeTitle).scaledFont(for: .monospacedDigitSystemFont(ofSize: 64, weight: .semibold)) as CTFont) }

    // 간격(4의 배수)
    static let s1: CGFloat = 4, s2: CGFloat = 8, s3: CGFloat = 12, s4: CGFloat = 16, s5: CGFloat = 24, s6: CGFloat = 32

    // 모서리
    static let rSmall: CGFloat = 8, rMedium: CGFloat = 14, rLarge: CGFloat = 22
}

extension Color {
    /// 밝음·어둠 값(0xRRGGBB).
    init(light: UInt32, dark: UInt32) {
        func ui(_ v: UInt32) -> UIColor {
            UIColor(red: CGFloat(v >> 16 & 0xFF) / 255, green: CGFloat(v >> 8 & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
        }
        self.init(uiColor: UIColor { $0.userInterfaceStyle == .dark ? ui(dark) : ui(light) })
    }
}

extension View {
    /// 화면 바탕을 `bg`로 깐다(목록·폼의 시스템 바탕을 걷는다).
    func screenBackground() -> some View { scrollContentBackground(.hidden).background(DesignSystem.bg) }

    /// 주요 버튼: accent 바탕에 onAccent 글자(어둠에서 흰 글자는 대비 미달, 13장).
    func accentButton() -> some View { buttonStyle(.borderedProminent).foregroundStyle(DesignSystem.onAccent) }
}
