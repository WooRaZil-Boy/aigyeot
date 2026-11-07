import SwiftUI

/// 토큰 값은 `Shared/DesignTokens.swift`(잠금 화면과 함께 쓴다). 여기는 앱에만 있는 종류별 색·아이콘이다.
extension DesignSystem {
    static func color(_ kind: CareKind) -> Color {
        switch kind { case .feed: feed; case .sleep: sleep; case .diaper: diaper; case .note: note }
    }

    static func icon(_ kind: CareKind) -> String {
        switch kind { case .feed: "drop.fill"; case .sleep: "moon.fill"; case .diaper: "circle.dotted"; case .note: "note.text" }
    }
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
                        .buttonStyle(.bordered)
                        .tint(selection == o ? DesignSystem.accent : DesignSystem.textSecondary)
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
