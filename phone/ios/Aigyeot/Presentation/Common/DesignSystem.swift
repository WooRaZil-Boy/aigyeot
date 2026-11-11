import SwiftUI

/// 토큰 값은 `Shared/DesignTokens.swift`(잠금 화면과 함께 쓴다). 여기는 앱에만 있는 종류별 색·아이콘이다.
extension DesignSystem {
    /// 종류의 데이터 색(점·막대만, 시안 data.js `CARE`).
    static func color(_ kind: CareKind) -> Color {
        switch kind { case .feed: cFeed; case .sleep: cSleep; case .diaper: cDiaper; case .note: cNote }
    }

    static func icon(_ kind: CareKind) -> Ico {
        switch kind { case .feed: .bottle; case .sleep: .moon; case .diaper: .diaper; case .note: .notebookPen }
    }
}

/// 선 아이콘 한 벌(시안 v6 ⑤): Lucide(ISC, `Licenses/ISC-Lucide.txt`) + 직접 그린 젖병·기저귀·우리 노래.
/// `Resources/Assets.xcassets`의 `ic-{이름}` SVG(벡터 보존, 템플릿)라 글자색을 따른다. 다시 만들기는 `scripts/gen_icons.py`.
enum Ico: String, CaseIterable {
    case house, list, carFront = "car-front", settings, chevronRight = "chevron-right", x, check, plus
    case bottle, moon, diaper, notebookPen = "notebook-pen", square, clock, link, sparkles, qrCode = "qr-code"
    case users, baby, download, fileUp = "file-up", unlink, trash = "trash-2", info, smartphone, song, play
    case circleCheck = "circle-check", route
    case wifiOff = "wifi-off", bell, bellOff = "bell-off", share, scanLine = "scan-line", camera
    case rotate3d = "rotate-3d", clockAlert = "clock-alert"
    case userPlus = "user-plus", refresh = "refresh-cw", lock, locateFixed = "locate-fixed", shieldCheck = "shield-check"
    // 기록 시트 v8(시안 js/sheet.js ICONS: Lucide에 없는 쪽·기저귀 종류 그림, 선 1.75) + Lucide chevron-down·minus·calendar
    case sideLeft = "side-left", sideRight = "side-right", sideBoth = "side-both", droplet, poo, peePoo = "pee-poo", clean
    case chevronDown = "chevron-down", minus, calendar

    var assetName: String { "ic-" + rawValue }
}

extension Image {
    /// 장식 그림(화면 읽기가 건너뛴다). 뜻은 옆 글자나 버튼 이름이 말한다.
    init(ico: Ico) { self.init(decorative: ico.assetName) }
}
