import SwiftUI

/// 디자인 토큰(phone-care-plan 13장 이름). 안드로이드 `ui/Theme`과 같은 이름이다.
/// 값은 8장 디자인 방향이 정해지기 전이라 지금 앱 모양 그대로다. 값만 바꾸면 모든 화면이 따라간다.
enum DesignSystem {
    // 색(밝음·어둠은 시스템 색이 맞춘다)
    static let bg = Color(.systemBackground)
    static let surface = Color(.secondarySystemBackground)
    static let surfaceRaised = Color(.tertiarySystemBackground)
    static let textPrimary = Color.primary
    static let textSecondary = Color.secondary
    static let accent = Color(red: 0.09, green: 0.43, blue: 0.48)
    static let onAccent = Color.white
    static let feed = Color.orange
    static let sleep = Color.indigo
    static let diaper = Color.teal
    static let note = Color.gray
    static let car = accent
    static let warning = Color.orange
    static let danger = Color.red

    // 글자(동적 글자 크기를 따른다)
    static let display = Font.largeTitle.bold()
    static let title = Font.title2.bold()
    static let headline = Font.headline
    static let body = Font.body
    static let caption = Font.footnote
    static let timer = Font.system(size: 64, weight: .semibold, design: .rounded).monospacedDigit()

    // 간격(4의 배수)
    static let s1: CGFloat = 4, s2: CGFloat = 8, s3: CGFloat = 12, s4: CGFloat = 16, s5: CGFloat = 24, s6: CGFloat = 32

    // 모서리
    static let rSmall: CGFloat = 10, rMedium: CGFloat = 14, rLarge: CGFloat = 20

    static func color(_ kind: CareKind) -> Color {
        switch kind { case .feed: feed; case .sleep: sleep; case .diaper: diaper; case .note: note }
    }

    static func icon(_ kind: CareKind) -> String {
        switch kind { case .feed: "drop.fill"; case .sleep: "moon.fill"; case .diaper: "circle.dotted"; case .note: "note.text" }
    }
}
