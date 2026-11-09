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

/// 선 아이콘 한 벌(시안 v6 ⑤): Lucide(ISC, `Licenses/ISC-Lucide.txt`) + 직접 그린 젖병·기저귀·부모 노래·카시트.
/// `Resources/Assets.xcassets`의 `ic-{이름}` SVG(벡터 보존, 템플릿)라 글자색을 따른다. 다시 만들기는 `scripts/gen_icons.py`.
enum Ico: String, CaseIterable {
    case house, list, carFront = "car-front", settings, chevronRight = "chevron-right", chevronLeft = "chevron-left", x, check, plus
    case bottle, moon, diaper, notebookPen = "notebook-pen", square, clock, link, sparkles, qrCode = "qr-code", server
    case users, baby, download, fileUp = "file-up", unlink, trash = "trash-2", info, smartphone, song, play, pause
    case circleCheck = "circle-check", route, mic, moonStar = "moon-star", waves, heartPulse = "heart-pulse"
    case wifiOff = "wifi-off", bell, bellOff = "bell-off", bellRing = "bell-ring", share, scanLine = "scan-line", camera
    case mapPin = "map-pin", triangleAlert = "triangle-alert", rotate3d = "rotate-3d", carseat, clockAlert = "clock-alert"
    case userPlus = "user-plus", refresh = "refresh-cw", lock, circleHelp = "circle-help", rotateCcw = "rotate-ccw"

    var assetName: String { "ic-" + rawValue }
}

extension View {
    /// 중립 버튼: 회색 판 + 먹색 글자(옅은 파랑 판 위 파랑 글자는 라이트에서 4.5:1 미만).
    func neutralButton() -> some View { buttonStyle(.bordered).tint(DesignSystem.textPrimary) }

    /// 고르는 칸: 고른 것은 먹색 채움(k-style 칩), 나머지는 중립 버튼.
    @ViewBuilder func choiceButton(selected: Bool) -> some View {
        if selected { buttonStyle(.borderedProminent).tint(DesignSystem.fill).foregroundStyle(DesignSystem.onFill) }
        else { neutralButton() }
    }
}

extension Image {
    /// 장식 그림(화면 읽기가 건너뛴다). 뜻은 옆 글자나 버튼 이름이 말한다.
    init(ico: Ico) { self.init(decorative: ico.assetName) }
}

/// 목록 섹션 머리·꼬리 글. 시스템 회색은 밝은 바탕에서 3.4:1이라 토큰 색을 쓴다(8장 4.5:1).
struct SectionHeader: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).foregroundStyle(DesignSystem.textSecondary) }
}

/// 이름 · 값 줄. 값의 시스템 보조색은 밝은 바탕에서 3.4:1이라 토큰 색을 쓴다(8장 4.5:1). 읽어 주기는 한 덩어리로.
struct TokenLabeledContentStyle: LabeledContentStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: .firstTextBaseline) {
            configuration.label
            Spacer(minLength: 8)
            configuration.content.foregroundStyle(DesignSystem.textSecondary).multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .combine)
    }
}

/// 몇 개 중 하나 고르기. 나눈 선택(segmented)은 큰 글자를 따르지 않아(8장), 접근성 글자 크기에서는 글자 버튼을 세로로 놓는다(안드로이드 FlowRow).
struct AdaptiveChoice<T: Hashable>: View {
    let title: String
    let options: [T]
    let label: (T) -> String
    @Binding var selection: T?
    @Environment(\.dynamicTypeSize) private var size

    var body: some View {
        if size.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(options, id: \.self) { o in
                    Button { selection = o } label: { Text(label(o)).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading) }
                        .choiceButton(selected: selection == o)
                        .accessibilityAddTraits(selection == o ? .isSelected : [])
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(title)
        } else {
            Picker(title, selection: $selection) {
                ForEach(options, id: \.self) { Text(label($0)).tag(Optional($0)) }
            }
            .pickerStyle(.segmented).labelsHidden()
            .controlSize(.large)   // 기본 높이 32pt는 누르는 영역 44pt(8장)에 못 미친다
        }
    }
}
