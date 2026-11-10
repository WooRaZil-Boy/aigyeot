import SwiftUI
import UIKit

/// 디자인 토큰 v6(11-09 확정 시안 `tokens.json` 폰 값). 안드로이드 `ui/Theme`과 같은 이름·같은 값이다.
/// 앱과 잠금 화면(Live Activity)이 함께 쓴다. 값의 한 출처는 아래 표이고, 시험이 `shared/src/test/resources/design/tokens.json`과 대조한다.
enum DesignSystem {
    // MARK: 색 — tokens.json `color` 그대로([라이트, 다크], #RRGGBB 또는 #RRGGBBAA)

    static let palette: [String: [String]] = [
        "bg": ["#F2F4F6", "#0F1013"],
        "stage": ["#E9ECEF", "#141519"],
        "surface": ["#FFFFFF", "#1B1C21"],
        "surfaceRaised": ["#F2F4F6", "#25262C"],
        "line": ["#191F2814", "#FFFFFF14"],
        "textPrimary": ["#191F28", "#F2F3F5"],
        "textSecondary": ["#4E5968", "#B0B8C1"],
        "textTertiary": ["#5D6673", "#8B95A1"],
        "accent": ["#2867E6", "#2867E6"],
        "accentInk": ["#2867E6", "#7AA5FF"],
        "onAccent": ["#FFFFFF", "#FFFFFF"],
        "accentSoft": ["#EAF1FF", "#18233A"],
        "onAccentSoft": ["#1D4FC4", "#A9C4FF"],
        "glow": ["#7AA5FF", "#7AA5FF"],
        "child": ["#C2410C", "#FF8F55"],
        "childSoft": ["#FFF1E8", "#33211A"],
        "childGlow": ["#FF9A5C", "#FF9A5C"],
        "warning": ["#B45309", "#F5C451"],
        "danger": ["#D12F34", "#FF6B6B"],
        "active": ["#12A150", "#34C77B"],
        "fill": ["#2B2E33", "#E0E1E6"],
        "onFill": ["#FFFFFF", "#131417"],
        "cFeed": ["#1BAF7A", "#199E70"],
        "cSleep": ["#9B87F5", "#9580EE"],
        "cDiaper": ["#8B95A1", "#6B7684"],
        "cNote": ["#8B95A1", "#6B7684"],
        "cCar": ["#EB6834", "#D95926"],
    ]

    /// 바탕은 회색, 블록은 흰 면(한국 앱 결). 숫자·진행은 먹색(textPrimary), 브랜드 파랑은 행동(버튼·선택·스위치)에만.
    static let bg = token("bg")
    static let stage = token("stage")
    static let surface = token("surface")
    static let surfaceRaised = token("surfaceRaised")
    /// 바탕(bg) 위에도 놓이는 옅은 판: 톤 버튼·타이머 고리 바탕·시트 칩·안내 아이콘 판. surfaceRaised는 라이트에서 bg와 같은 색이라
    /// 바탕 위에서 보이지 않아, 라이트만 먹색 8%(세그먼트 판과 같은 결), 다크는 surfaceRaised. 안드로이드와 같은 규칙.
    static let tonal = Color(lightHex: palette["textPrimary"]![0] + "14", darkHex: palette["surfaceRaised"]![1])
    static let line = token("line")
    static let textPrimary = token("textPrimary")
    static let textSecondary = token("textSecondary")
    static let textTertiary = token("textTertiary")
    /// 곁의 응답(브랜드 파랑): 채움(위 글자는 onAccent), 글자·링크·표시는 accentInk.
    static let accent = token("accent")
    static let accentInk = token("accentInk")
    static let onAccent = token("onAccent")
    static let accentSoft = token("accentSoft")
    static let onAccentSoft = token("onAccentSoft")
    static let glow = token("glow")
    /// 아이의 신호(주황): 울음·경보·인계 알림.
    static let child = token("child")
    static let childSoft = token("childSoft")
    static let childGlow = token("childGlow")
    static let warning = token("warning")
    static let danger = token("danger")
    static let active = token("active")
    static let fill = token("fill")
    static let onFill = token("onFill")
    /// 데이터 색(점·막대·면만, 글자로 쓰지 않는다). 라이트 잠·수유는 바탕 대비 3:1 미만이라 늘 이름 라벨과 함께 쓴다.
    static let cFeed = token("cFeed")
    static let cSleep = token("cSleep")
    static let cDiaper = token("cDiaper")
    static let cNote = token("cNote")
    static let cCar = token("cCar")

    private static func token(_ name: String) -> Color {
        let v = palette[name]!   // 표에 없는 이름은 시험(DesignTokenTests)이 먼저 잡는다
        return Color(lightHex: v[0], darkHex: v[1])
    }

    // MARK: 글자 — tokens.json `type.phone` [크기, 줄 높이, 굵기]. Pretendard Variable, 동적 글자 크기를 따른다

    struct TypeStyle: Equatable {
        let size: CGFloat, line: CGFloat, weight: CGFloat
        let relativeTo: UIFont.TextStyle

        static var hero: TypeStyle { typeScale["hero"]! }
        static var display: TypeStyle { typeScale["display"]! }
        static var title: TypeStyle { typeScale["title"]! }
        static var headline: TypeStyle { typeScale["headline"]! }
        static var body: TypeStyle { typeScale["body"]! }
        static var caption: TypeStyle { typeScale["caption"]! }
        static var timer: TypeStyle { typeScale["timer"]! }
    }

    static let typeScale: [String: TypeStyle] = [
        "hero": TypeStyle(size: 30, line: 38, weight: 700, relativeTo: .largeTitle),
        "display": TypeStyle(size: 26, line: 34, weight: 700, relativeTo: .title1),
        "title": TypeStyle(size: 20, line: 28, weight: 700, relativeTo: .title2),
        "headline": TypeStyle(size: 17, line: 24, weight: 600, relativeTo: .headline),
        "body": TypeStyle(size: 16, line: 24, weight: 400, relativeTo: .body),
        "caption": TypeStyle(size: 13, line: 18, weight: 500, relativeTo: .footnote),
        "timer": TypeStyle(size: 64, line: 72, weight: 650, relativeTo: .largeTitle),
    ]

    static var hero: Font { font(.hero) }
    static var display: Font { font(.display) }
    static var title: Font { font(.title) }
    static var headline: Font { font(.headline) }
    static var body: Font { font(.body) }
    static var caption: Font { font(.caption) }
    /// 타이머 숫자는 고정폭 숫자(tnum).
    static var timer: Font { font(.timer, monospacedDigits: true) }

    /// 표에 없는 자리 글자(시안 CSS의 15·14·12.5 등). 굵기는 가변 축 값 그대로(650 같은 중간 값도 된다).
    static func font(_ size: CGFloat, _ weight: CGFloat = 400, relativeTo: UIFont.TextStyle = .body, monospacedDigits: Bool = false) -> Font {
        Font(uiFont(size, weight, relativeTo: relativeTo, monospacedDigits: monospacedDigits) as CTFont)
    }

    /// 잠금 화면 위젯용 글자: 이름 붙은 인스턴스(Light·Regular·Medium·SemiBold·Bold)로 고른다. 위젯은 화면을 글자 속성째 묶어 보내는데,
    /// 가변 축 사전(wght)이 든 서체를 줄여 그리면(minimumScaleFactor) 묶는 중에 확장이 멈췄다(실측 11-10, KEY_TYPE_OF_DICTIONARY 충돌).
    static func namedFont(_ size: CGFloat, _ weight: CGFloat, relativeTo: Font.TextStyle = .body) -> Font {
        let name: String
        switch weight {
        case ..<350: name = "PretendardVariable-Light"
        case ..<450: name = "PretendardVariable-Regular"
        case ..<550: name = "PretendardVariable-Medium"
        case ..<650: name = "PretendardVariable-SemiBold"
        default: name = "PretendardVariable-Bold"
        }
        return .custom(name, size: size, relativeTo: relativeTo)
    }

    /// 이미 키운 크기(@ScaledMetric)를 그대로 쓰는 글자. 차트처럼 배치와 글자가 같은 배율이어야 하는 곳(두 번 키우지 않는다).
    static func fixedFont(_ size: CGFloat, _ weight: CGFloat = 400, monospacedDigits: Bool = false) -> Font {
        Font(uiFont(size, weight, monospacedDigits: monospacedDigits, scaled: false) as CTFont)
    }

    static func font(_ s: TypeStyle, monospacedDigits: Bool = false) -> Font {
        font(s.size, s.weight, relativeTo: s.relativeTo, monospacedDigits: monospacedDigits)
    }

    /// 동적 글자 크기로 키운 UIFont. 서체가 없으면(시험 번들 등) 같은 굵기의 시스템 서체.
    static func uiFont(_ size: CGFloat, _ weight: CGFloat, relativeTo: UIFont.TextStyle = .body, monospacedDigits: Bool = false, scaled: Bool = true) -> UIFont {
        var attrs: [UIFontDescriptor.AttributeName: Any] = [
            .name: pretendard,
            kCTFontVariationAttribute as UIFontDescriptor.AttributeName: [wghtAxis: weight],
        ]
        if monospacedDigits {
            attrs[.featureSettings] = [[UIFontDescriptor.FeatureKey.type: kNumberSpacingType,
                                        UIFontDescriptor.FeatureKey.selector: kMonospacedNumbersSelector]]
        }
        let base: UIFont
        if UIFont(name: pretendard, size: size) != nil {
            base = UIFont(descriptor: UIFontDescriptor(fontAttributes: attrs), size: size)
        } else {
            base = monospacedDigits ? .monospacedDigitSystemFont(ofSize: size, weight: systemWeight(weight))
                                    : .systemFont(ofSize: size, weight: systemWeight(weight))
        }
        return scaled ? UIFontMetrics(forTextStyle: relativeTo).scaledFont(for: base) : base
    }

    /// 줄 높이를 맞추는 줄 간격(줄 높이 − 서체의 기본 줄 높이, 같은 비율로 키움).
    static func lineSpacing(_ s: TypeStyle) -> CGFloat {
        let f = uiFont(s.size, s.weight, relativeTo: s.relativeTo)
        return max(0, s.line * f.pointSize / s.size - f.lineHeight)
    }

    /// Pretendard Variable 1.3.9(OFL, `Resources/Fonts`)의 이름 붙은 인스턴스. 굵기는 wght 축으로 바꾼다.
    static let pretendard = "PretendardVariable-Regular"
    static let pretendardFile = "PretendardVariable.ttf"

    /// 앱은 Info의 UIAppFonts로 서체를 올린다. 잠금 화면 위젯 확장은 같은 파일을 앱 묶음에서 이 프로세스에 올린다(6.7MB를 두 번 넣지 않는다).
    static func registerHostAppFont() {
        guard UIFont(name: pretendard, size: 12) == nil else { return }
        // 확장 위치: Aigyeot.app/PlugIns/AigyeotWidgets.appex → 앱 묶음은 두 단계 위
        let app = Bundle.main.bundleURL.deletingLastPathComponent().deletingLastPathComponent()
        CTFontManagerRegisterFontsForURL(app.appendingPathComponent(pretendardFile) as CFURL, .process, nil)
    }
    private static let wghtAxis = 0x77676874   // 'wght'

    private static func systemWeight(_ w: CGFloat) -> UIFont.Weight {
        switch w {
        case ..<350: .light
        case ..<450: .regular
        case ..<550: .medium
        case ..<650: .semibold
        default: .bold
        }
    }

    // MARK: 간격·모서리 — 모서리는 tokens.json `radius.phone`

    static let s1: CGFloat = 4, s2: CGFloat = 8, s3: CGFloat = 12, s4: CGFloat = 16, s5: CGFloat = 24, s6: CGFloat = 32
    static let radius: [String: CGFloat] = ["rS": 8, "rM": 12, "rL": 20, "rXL": 28]
    static let rS = radius["rS"]!, rM = radius["rM"]!, rL = radius["rL"]!, rXL = radius["rXL"]!

    // MARK: 모션 — tokens.json `motion`(ms·곡선)·`spring`

    /// tokens.json `motion` 그대로: 이름 → [ms, 곡선 글자]. `Motion`이 이 표로 애니메이션을 만든다(시험이 표를 대조한다).
    static let motion: [String: (ms: Int, curve: String)] = [
        "press": (90, "linear"),
        "fast": (150, "cubic-bezier(0.4, 0, 0.2, 1)"),
        "base": (220, "cubic-bezier(0.4, 0, 0.2, 1)"),
        "enter": (360, "cubic-bezier(0.2, 0.8, 0.2, 1)"),
        "exit": (180, "cubic-bezier(0.4, 0, 1, 1)"),
        "camera": (1100, "cubic-bezier(0.65, 0, 0.35, 1)"),
        "light": (900, "cubic-bezier(0.4, 0, 0.2, 1)"),
        "ripple": (2400, "cubic-bezier(0.2, 0.6, 0.35, 1)"),
    ]

    /// tokens.json `spring` 그대로: 이름 → (감쇠, 강성, SwiftUI 글자). SwiftUI 값은 글자를 읽어 만든다.
    static let springs: [String: (damping: Double, stiffness: Double, swiftui: String)] = [
        "snappy": (0.86, 600, ".smooth(duration: 0.2)"),
        "smooth": (1.0, 385, ".smooth(duration: 0.32)"),
        "bouncy": (0.78, 420, ".smooth(duration: 0.35, extraBounce: 0.12)"),
    ]

    /// 동작 줄이기면 화면이 nil 애니메이션을 쓴다.
    enum Motion {
        static let press = animation("press"), fast = animation("fast"), base = animation("base"), enter = animation("enter")
        static let exit = animation("exit"), light = animation("light"), camera = animation("camera")
        /// spring.snappy: 세그먼트 엄지·버튼 모양.
        static let snappy = spring("snappy")
        /// spring.smooth: 시트·공유 요소·화면 교체.
        static let smooth = spring("smooth")
        /// spring.bouncy: 탭 렌즈·알림 배너만 조금.
        static let bouncy = spring("bouncy")
        /// roll(README 4.7 표, tokens.json에는 없음): 숫자 굴림 spring 감쇠 .85·강성 632 → 응답 2π/√632초.
        static let roll = Animation.spring(response: 2 * .pi / 632.0.squareRoot(), dampingFraction: 0.85)

        /// "linear" 또는 "cubic-bezier(a, b, c, d)" → Animation.
        static func animation(_ name: String) -> Animation {
            let m = motion[name]!
            let d = Double(m.ms) / 1000
            let p = numbers(m.curve)
            return p.count == 4 ? .timingCurve(p[0], p[1], p[2], p[3], duration: d) : .linear(duration: d)
        }

        /// ".smooth(duration: x[, extraBounce: y])" → Animation.
        static func spring(_ name: String) -> Animation {
            let p = numbers(springs[name]!.swiftui)
            return .smooth(duration: p[0], extraBounce: p.count > 1 ? p[1] : 0)
        }

        static func numbers(_ s: String) -> [Double] {
            s.split(whereSeparator: { !"0123456789.".contains($0) }).compactMap { Double($0) }
        }
    }
}

extension Color {
    /// 라이트·다크 값(#RRGGBB 또는 #RRGGBBAA).
    init(lightHex: String, darkHex: String) {
        let l = UIColor(hex: lightHex), d = UIColor(hex: darkHex)
        self.init(uiColor: UIColor { $0.userInterfaceStyle == .dark ? d : l })
    }
}

extension UIColor {
    convenience init(hex: String) {
        let s = hex.dropFirst()
        let v = UInt64(s, radix: 16) ?? 0
        let hasAlpha = s.count == 8
        let rgb = hasAlpha ? v >> 8 : v
        self.init(red: CGFloat(rgb >> 16 & 0xFF) / 255, green: CGFloat(rgb >> 8 & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255,
                  alpha: hasAlpha ? CGFloat(v & 0xFF) / 255 : 1)
    }
}

extension View {
    /// 글자 표의 한 단계(서체 + 줄 높이).
    func typeStyle(_ s: DesignSystem.TypeStyle) -> some View {
        font(DesignSystem.font(s)).lineSpacing(DesignSystem.lineSpacing(s))
    }

    /// 화면 바탕을 `bg`로 깐다(목록·폼의 시스템 바탕을 걷는다).
    func screenBackground() -> some View { scrollContentBackground(.hidden).background(DesignSystem.bg) }

    /// 주요 버튼: accent 바탕에 onAccent 글자.
    func accentButton() -> some View { buttonStyle(.borderedProminent).tint(DesignSystem.accent).foregroundStyle(DesignSystem.onAccent) }
}
