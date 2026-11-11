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
    /// 아이의 신호(주황): 울음·경보·인계 알림.
    static let child = token("child")
    static let childSoft = token("childSoft")
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

    /// 글자 역할 표(두 앱 같은 표, 진행 기록 '단위 10 결정'). tokens.json 7단계(hero·display·title·headline·body·caption·timer)와 같은 값은 그 단계를 쓰고,
    /// 시안 phone.css가 단계 사이 크기를 쓰는 자리만 역할 이름을 둔다. 값은 시안 CSS 그대로(크기·굵기·줄 높이), 동적 글자 크기는 본문 곡선.
    enum TextRole: CaseIterable {
        /// 11·600 타임라인 시간 머리(.tl .hr span) — caption 계열
        case hourMark
        /// 12·500 숫자 칸 부제·날짜 띠 요일(.datestrip span) — caption 계열
        case micro
        /// 12.5·500 줄 부제·덧붙임(.tl .ev span, .foot-note) — caption 계열
        case footnote
        /// 12.5·600 배지·말풍선(.badge, .ch-tip) — caption 계열
        case tag
        /// 13·400 흐린 안내 글(.foot-note 긴 글) — caption 계열
        case note
        /// 13.5·500 첫 안내 목록(.ilist) — caption 계열
        case introItem
        /// 14·400 목록 줄 부제·블록 부제(.lrow span) — body 계열
        case rowSub
        /// 14·500 링크·흐린 머리 글(.bh a, .hero-c small) — body 계열
        case meta
        /// 14·600 빠른 기록 이름(.qb) — body 계열
        case quickLabel
        /// 14·700 띠 단추·부제 굵은 조각 — body 계열
        case metaStrong
        /// 14.5·500 타임라인 줄 제목·알림 배너 글(.tl .ev b, .ncard p) — body 계열
        case eventTitle
        /// 14.5·700 알림 배너 앱 이름(.ncard b) — body 계열
        case bannerTitle
        /// 15·400 이름·값 줄의 이름, 빈 목록 안내 — body 계열
        case valueKey
        /// 15·500 되돌리기 띠 글·타이머 상태 — body 계열
        case bodyMedium
        /// 15·600 칩·세그먼트·값(.chip, .segp span) — body 계열
        case chip
        /// 15·700 띠의 글자 단추(되돌리기) — body 계열
        case action
        /// 15.5·500 시트 행 이름·입력(.sheet .row) — body 계열
        case sheetRow
        /// 15.5·600 시트 값 칩(.sheet .row em) — body 계열
        case sheetValue
        /// 16·500 첫 안내 첫 줄(.intro p.lead) — body 계열
        case lead
        /// 16·600 목록 줄 제목·진행 중 판 제목(.lrow b, .run-c b) — body 계열
        case rowTitle
        /// 17·500 날짜 띠 날(.datestrip b) — headline 계열
        case dateNumber
        /// 17·700 버튼 글자(.pbtn, v5 덮어쓰기 433행)·방법별 성적 숫자(.dm b) — headline 계열
        case strong
        /// 18·700 큰 버튼 글자(.pbtn.big, 434행) — headline 계열
        case strongLarge
        /// 28·700 진행 중 판 시계(.run-c .tm) — display 계열
        case runningTimer
        /// 44·600 녹음 시간(.rec .rt) — timer 계열
        case recordTimer

        var style: TypeStyle {
            switch self {
            case .hourMark: TypeStyle(size: 11, line: 14, weight: 600, relativeTo: .body)
            case .micro: TypeStyle(size: 12, line: 16, weight: 500, relativeTo: .body)
            case .footnote: TypeStyle(size: 12.5, line: 17, weight: 500, relativeTo: .body)
            case .tag: TypeStyle(size: 12.5, line: 16, weight: 600, relativeTo: .body)
            case .note: TypeStyle(size: 13, line: 18, weight: 400, relativeTo: .body)
            case .introItem: TypeStyle(size: 13.5, line: 20, weight: 500, relativeTo: .body)
            case .rowSub: TypeStyle(size: 14, line: 20, weight: 400, relativeTo: .body)
            case .meta: TypeStyle(size: 14, line: 20, weight: 500, relativeTo: .body)
            case .quickLabel: TypeStyle(size: 14, line: 18, weight: 600, relativeTo: .body)
            case .metaStrong: TypeStyle(size: 14, line: 20, weight: 700, relativeTo: .body)
            case .eventTitle: TypeStyle(size: 14.5, line: 20, weight: 500, relativeTo: .body)
            case .bannerTitle: TypeStyle(size: 14.5, line: 20, weight: 700, relativeTo: .body)
            case .valueKey: TypeStyle(size: 15, line: 22, weight: 400, relativeTo: .body)
            case .bodyMedium: TypeStyle(size: 15, line: 22, weight: 500, relativeTo: .body)
            case .chip: TypeStyle(size: 15, line: 22, weight: 600, relativeTo: .body)
            case .action: TypeStyle(size: 15, line: 20, weight: 700, relativeTo: .body)
            case .sheetRow: TypeStyle(size: 15.5, line: 21, weight: 500, relativeTo: .body)
            case .sheetValue: TypeStyle(size: 15.5, line: 21, weight: 600, relativeTo: .body)
            case .lead: TypeStyle(size: 16, line: 24, weight: 500, relativeTo: .body)
            case .rowTitle: TypeStyle(size: 16, line: 22, weight: 600, relativeTo: .body)
            case .dateNumber: TypeStyle(size: 17, line: 22, weight: 500, relativeTo: .body)
            case .strong: TypeStyle(size: 17, line: 22, weight: 700, relativeTo: .body)
            case .strongLarge: TypeStyle(size: 18, line: 24, weight: 700, relativeTo: .body)
            case .runningTimer: TypeStyle(size: 28, line: 32, weight: 700, relativeTo: .body)
            case .recordTimer: TypeStyle(size: 44, line: 48, weight: 600, relativeTo: .body)
            }
        }
    }

    static func font(_ r: TextRole, monospacedDigits: Bool = false) -> Font { font(r.style, monospacedDigits: monospacedDigits) }

    static var hero: Font { font(.hero) }
    static var headline: Font { font(.headline) }
    static var body: Font { font(.body) }
    static var caption: Font { font(.caption) }
    /// 타이머 숫자는 고정폭 숫자(tnum).
    static var timer: Font { font(.timer, monospacedDigits: true) }

    /// 숫자로 바로 부르지 않는다: 화면은 단계(TypeStyle)나 역할(TextRole)로만 고른다. 굵기는 가변 축 값 그대로(650 같은 중간 값도 된다).
    private static func font(_ size: CGFloat, _ weight: CGFloat = 400, relativeTo: UIFont.TextStyle = .body, monospacedDigits: Bool = false) -> Font {
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
    static let rM = radius["rM"]!, rL = radius["rL"]!

    // MARK: 시안 부품 색 — tokens.json에 이름이 없고 시안 CSS·JS에만 있는 값(이름 하나에 값 하나)

    /// 세그먼트 엄지(.segp .sthumb): 라이트 흰색, 다크 #3A3F46.
    static let segmentThumb = Color(lightHex: "#FFFFFF", darkHex: "#3A3F46")
    /// 첫 안내 무대 빛(.intro-hero: 흰 60% + stage, 다크 #232931).
    static let stageGlow = Color(lightHex: "#F6F7F9", darkHex: "#232931")
    /// 잠금 화면 진행 중 점(.live-act .ri).
    static let liveActivityDot = Color(lightHex: "#DDB08F", darkHex: "#DDB08F")
    /// 잠금 화면 진행 중 판(.live-act 바탕 rgba(20,22,26)). 흰 잠금 화면에서도 흰 글자 대비가 나게 92% 불투명.
    static let liveActivityPanel = Color(lightHex: "#14161AEB", darkHex: "#14161AEB")
    /// 3D 차 재질(car3d.js): 차체 도장, 실내 앰비언트 선 바탕, 전조등, 미등. 앰비언트 빛은 tokens `glow`.
    /// 차 재질 색(시안 js/car3d.js materials(), v7): 도장·크롬·선루프 가림막·앰비언트 바탕·램프.
    enum CarPaint {
        static let body = "#E4E7EA", chrome = "#E2E6EA", roofShade = "#0E1013"
        static let ambientBase = "#1D2023", headLamp = "#F2F7FF", tailLamp = "#FF3326"
        static var ambientGlow: String { palette["glow"]![0] }
    }

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
        /// 표 그대로 쓰는 것만 이름을 둔다. base·exit·camera 곡선은 시안 부품 값(아래)이 `animation(_:seconds:)`·`curve(_:)`로 읽는다.
        static let press = animation("press"), fast = animation("fast"), enter = animation("enter")
        /// spring.snappy: 세그먼트 엄지·버튼 모양.
        static let snappy = spring("snappy")
        /// spring.smooth: 시트·공유 요소·화면 교체.
        static let smooth = spring("smooth")
        /// spring.bouncy: 탭 렌즈·알림 배너만 조금.
        static let bouncy = spring("bouncy")
        /// roll(README 4.7 표, tokens.json에는 없음): 숫자 굴림 spring 감쇠 .85·강성 632 → 응답 2π/√632초.
        static let roll = Animation.spring(response: 2 * .pi / 632.0.squareRoot(), dampingFraction: 0.85)

        // 시안 부품 움직임(tokens.json에 이름이 없고 시안 JS·CSS에만 있는 값). 곡선은 위 motion 표의 이름에서 가져온다.
        /// 띠·히트맵·에피소드가 왼쪽부터 그려짐(charts.js grow: 0.9초, camera 곡선).
        static let chartReveal = animation("camera", seconds: 0.9)
        /// 막대 하나가 바닥에서 자람(charts.js: 0.6초, enter 곡선). 막대마다 `chartBarStagger`초 늦게.
        static let chartBarSeconds = 0.6, chartBarStagger = 0.04
        /// 방법별 성적 점이 튐(charts.js: spring 강성 600·감쇠 30). 점마다 `dotStagger`초 늦게.
        static let dotPop = Animation.interpolatingSpring(stiffness: 600, damping: 30)
        static let dotStagger = 0.02
        /// 차트 말풍선(.ch-tip: 120ms linear).
        static let tip = Animation.linear(duration: 0.12)
        /// 알림 배너가 나감(.banner.out dropOut: 260ms, exit 곡선).
        static let bannerOut = animation("exit", seconds: 0.26)
        /// 진행 중 점의 살아 있는 빛(live: 1.8초 주기, 앞 60% 퍼짐, base 곡선).
        static let liveSeconds = 1.8, liveSpreadShare = 0.6
        /// 새 수유가 들어온 판 둘레 빛(.flash: 1.2초, base 곡선).
        static let flashSeconds = 1.2
        /// 타이머 초침 점(.sweep: 60초 한 바퀴 linear, 초마다 1초씩).
        static let secondHand = Animation.linear(duration: 1)
        /// 화면 교체 때 한 줄씩 올라옴(core.js UI.enter: 0.06초 뒤 0.05초 간격, motion.enter).
        static let riseDelay = 0.06, riseStagger = 0.05

        /// 표의 곡선을 다른 길이로(시안이 같은 곡선을 다른 시간에 쓰는 자리).
        static func animation(_ name: String, seconds: Double) -> Animation {
            let p = numbers(motion[name]!.curve)
            return p.count == 4 ? .timingCurve(p[0], p[1], p[2], p[3], duration: seconds) : .linear(duration: seconds)
        }

        /// 표의 곡선(키프레임·UIKit 프레임 계산이 직접 값을 읽는 자리). linear면 직선.
        static func curve(_ name: String) -> UnitCurve {
            let p = numbers(motion[name]!.curve)
            return p.count == 4 ? .bezier(startControlPoint: UnitPoint(x: p[0], y: p[1]), endControlPoint: UnitPoint(x: p[2], y: p[3])) : .linear
        }

        /// 표의 길이(초).
        static func seconds(_ name: String) -> Double { Double(motion[name]!.ms) / 1000 }

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
    /// 글자 표의 한 단계(서체 + 줄 높이). 줄 간격은 여러 줄일 때만 보인다.
    func typeStyle(_ s: DesignSystem.TypeStyle, monospacedDigits: Bool = false) -> some View {
        font(DesignSystem.font(s, monospacedDigits: monospacedDigits)).lineSpacing(DesignSystem.lineSpacing(s))
    }

    /// 글자 역할 하나(서체 + 줄 높이).
    func typeStyle(_ r: DesignSystem.TextRole, monospacedDigits: Bool = false) -> some View {
        typeStyle(r.style, monospacedDigits: monospacedDigits)
    }

    /// 화면 바탕을 `bg`로 깐다(목록·폼의 시스템 바탕을 걷는다).
    func screenBackground() -> some View { scrollContentBackground(.hidden).background(DesignSystem.bg) }
}
