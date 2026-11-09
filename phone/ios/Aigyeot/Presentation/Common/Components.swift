import SwiftUI

private typealias DS = DesignSystem

// 디자인 v6 부품(시안 css/phone.css v5 '한국 앱 결'): 흰 블록·블록 머리·64 목록 행·설정 묶음·세그먼트·버튼·배지·숫자 줄·글래스.
// 화면은 이 부품으로 상태를 그리기만 한다.

// MARK: 블록

extension View {
    /// 흰 블록: 회색 바탕 위 모서리 20, 안쪽 여백 22·20(시안 `.pcard`).
    func block(padding: EdgeInsets = EdgeInsets(top: 22, leading: 20, bottom: 22, trailing: 20)) -> some View {
        self.padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(DS.surface, in: RoundedRectangle(cornerRadius: DS.rL, style: .continuous))
    }

    /// 화면 안쪽 여백 12, 블록 사이 12(시안 `.pad`).
    func screenPadding() -> some View { padding(.horizontal, 12).padding(.bottom, 24) }
}

/// 블록 머리: 제목 20/700 + 오른쪽 작은 링크(14/500 흐림 + ›).
struct BlockHeader: View {
    let title: String
    var link: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        HStack(alignment: .center) {
            Text(title).typeStyle(.title).foregroundStyle(DS.textPrimary).accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            if let link, let action {
                Button(action: action) {
                    HStack(spacing: 2) {
                        Text(link).font(DS.font(14, 500))
                        IconImage(.chevronRight, size: 16)
                    }
                    .foregroundStyle(DS.textTertiary)
                    .frame(minHeight: 44).contentShape(Rectangle())   // 터치 영역 44pt(8장)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.bottom, link == nil ? 14 : 4)
    }
}

/// 블록 머리 아래 작은 부제("오늘 **2시간 50분** · 평소 3시간"). 굵은 조각은 먹색 700.
struct BlockSub: View {
    let text: Text
    var body: some View {
        text.font(DS.font(14, 400)).foregroundStyle(DS.textTertiary).padding(.top, -8).padding(.bottom, 14)
    }
}

/// 블록 아래 덧붙임 글(13/400 흐림).
struct BlockNote: View {
    let text: String
    var body: some View { Text(text).font(DS.font(13, 400)).lineSpacing(6).foregroundStyle(DS.textTertiary).padding(.top, 14) }
}

/// 블록 밖 작은 글(12.5/500 흐림, 시안 `.foot-note`).
struct FootNote: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(DS.font(12.5, 500)).foregroundStyle(DS.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 8).padding(.vertical, 4)
    }
}

// MARK: 아이콘

/// 선 아이콘 하나. 크기는 동적 글자 크기를 따른다.
struct IconImage: View {
    let ico: Ico
    @ScaledMetric private var size: CGFloat

    init(_ ico: Ico, size: CGFloat = 20, relativeTo: Font.TextStyle = .body) {
        self.ico = ico
        _size = ScaledMetric(wrappedValue: size, relativeTo: relativeTo)
    }

    var body: some View {
        Image(ico: ico).resizable().scaledToFit().frame(width: size, height: size).accessibilityHidden(true)
    }
}

/// 오른쪽 › (흐림).
struct Chevron: View {
    var body: some View { IconImage(.chevronRight, size: 18).foregroundStyle(DS.textTertiary) }
}

// MARK: 목록 행

/// 64 목록 행: 회색 원(40) 아이콘 + 제목 16/600 + 부제 14/400 흐림 + 꼬리(시안 `.lrow-p`).
/// `add`면 원이 옅은 파랑, 제목이 파랑 글자(시안 `.is-add`).
struct ListRow<Tail: View>: View {
    let icon: Ico
    let title: String
    var sub: String? = nil
    var add = false
    var titleColor: Color = DS.textPrimary
    @ViewBuilder var tail: () -> Tail

    var body: some View {
        HStack(spacing: 12) {
            IconImage(icon, size: 19)
                .foregroundStyle(add ? DS.onAccentSoft : DS.textSecondary)
                .frame(width: 40, height: 40)
                .background(add ? DS.accentSoft : DS.surfaceRaised, in: Circle())
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(DS.font(16, 600)).lineSpacing(6).foregroundStyle(add ? DS.accentInk : titleColor)
                if let sub { Text(sub).font(DS.font(14, 400)).lineSpacing(6).foregroundStyle(DS.textTertiary) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            tail()
        }
        .padding(.vertical, 10)
        .frame(minHeight: 64)
        .contentShape(Rectangle())
    }
}

extension ListRow where Tail == Chevron {
    init(icon: Ico, title: String, sub: String? = nil, add: Bool = false, titleColor: Color = DS.textPrimary) {
        self.init(icon: icon, title: title, sub: sub, add: add, titleColor: titleColor) { Chevron() }
    }
}

/// 설정 묶음(시안 `.grp`): 흰 면 모서리 26, 안쪽 좌우 16.
struct Group26<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(spacing: 0, content: content)
            .padding(.horizontal, 16).padding(.vertical, 2)
            .background(DS.surface, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
    }
}

/// 설정 행: 32 아이콘(바탕 없음, 흐림) + 제목 16/600 + 부제 14 흐림, 높이 56 이상.
struct SettingRow<Tail: View>: View {
    let icon: Ico
    let title: String
    var sub: String? = nil
    var tone: Color = DS.textPrimary
    @ViewBuilder var tail: () -> Tail

    var body: some View {
        HStack(spacing: 12) {
            IconImage(icon, size: 19).foregroundStyle(tone == DS.textPrimary ? DS.textTertiary : tone).frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(DS.font(16, 600)).foregroundStyle(tone)
                if let sub { Text(sub).font(DS.font(14, 400)).foregroundStyle(DS.textTertiary) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            tail()
        }
        .padding(.vertical, 12)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
    }
}

extension SettingRow where Tail == Chevron {
    init(icon: Ico, title: String, sub: String? = nil, tone: Color = DS.textPrimary) {
        self.init(icon: icon, title: title, sub: sub, tone: tone) { Chevron() }
    }
}

extension SettingRow where Tail == EmptyView {
    init(icon: Ico, title: String, sub: String? = nil, tone: Color = DS.textPrimary, noTail: Void) {
        self.init(icon: icon, title: title, sub: sub, tone: tone) { EmptyView() }
    }
}

// MARK: 세그먼트

/// 회색 판 + 흰 칸 세그먼트(두 플랫폼 같음, 시안 `.segp`). 엄지는 spring.snappy로 옮긴다.
/// 가장 큰 글자에서는 칸이 좁아 글자가 잘리므로 세로 글자 버튼으로 바꾼다(8장 동적 글자 크기).
struct Segmented<T: Hashable>: View {
    let title: String
    let options: [T]
    let label: (T) -> String
    @Binding var selection: T?
    @Namespace private var ns
    @Environment(\.dynamicTypeSize) private var size
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static var thumb: Color { Color(lightHex: "#FFFFFF", darkHex: "#3A3F46") }

    var body: some View {
        Group {
            if size.isAccessibilitySize {
                VStack(spacing: 4) { ForEach(options, id: \.self) { cell($0) } }
            } else {
                HStack(spacing: 0) { ForEach(options, id: \.self) { cell($0) } }
            }
        }
        .padding(4)
        .background(DS.textPrimary.opacity(0.06), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(title)
    }

    private func cell(_ o: T) -> some View {
        let on = selection == o
        return Button {
            withAnimation(reduceMotion ? nil : DS.Motion.snappy) { selection = o }
        } label: {
            Text(label(o)).font(DS.font(15, 600))
                .foregroundStyle(on ? DS.textPrimary : DS.textSecondary)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background {
                    if on {
                        RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Self.thumb)
                            .shadow(color: .black.opacity(0.1), radius: 4, y: 2)
                            .padding(.vertical, 2)
                            .matchedGeometryEffect(id: "thumb", in: ns)
                    }
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

// MARK: 버튼

/// 토스 결 버튼: 높이 56(큰 것 58), 모서리 16, 17/700. 누르면 0.97배(press 90ms, Pleos·quietly).
struct PillButtonStyle: ButtonStyle {
    enum Kind { case accent, tonal, danger }
    var kind: Kind = .accent
    var big = false

    func makeBody(configuration: Configuration) -> some View {
        PillBody(configuration: configuration, kind: kind, big: big)
    }

    private struct PillBody: View {
        let configuration: Configuration
        let kind: Kind
        let big: Bool
        @Environment(\.isEnabled) private var enabled
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        var body: some View {
            configuration.label
                .font(DS.font(big ? 18 : 17, 700))
                .foregroundStyle(fg)
                .frame(maxWidth: .infinity, minHeight: kind == .danger ? 44 : (big ? 58 : 56))
                .padding(.horizontal, 16)
                .background(bg, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
                .animation(DS.Motion.press, value: configuration.isPressed)
                .opacity(enabled ? 1 : 0.45)
        }

        private var fg: Color {
            switch kind { case .accent: DS.onAccent; case .tonal: DS.textPrimary; case .danger: DS.danger }
        }
        private var bg: Color {
            switch kind { case .accent: DS.accent; case .tonal: DS.tonal; case .danger: .clear }
        }
    }
}

extension ButtonStyle where Self == PillButtonStyle {
    static var pill: PillButtonStyle { PillButtonStyle() }
    static var pillBig: PillButtonStyle { PillButtonStyle(big: true) }
    static var pillTonal: PillButtonStyle { PillButtonStyle(kind: .tonal) }
    static var pillDanger: PillButtonStyle { PillButtonStyle(kind: .danger) }
}

/// 카드·행 누름: 0.97배(press 90ms).
struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { PressBody(configuration: configuration) }
    private struct PressBody: View {
        let configuration: Configuration
        @Environment(\.accessibilityReduceMotion) private var reduceMotion
        var body: some View {
            configuration.label
                .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
                .animation(DS.Motion.press, value: configuration.isPressed)
        }
    }
}

extension ButtonStyle where Self == PressStyle {
    static var press: PressStyle { PressStyle() }
}

// MARK: 배지·숫자 줄·스위치 색

/// 배지(뜻 하나에 색 하나). `child`면 아이 신호 주황.
struct Badge: View {
    let text: String
    var icon: Ico? = nil
    var child = false
    var body: some View {
        HStack(spacing: 4) {
            if let icon { IconImage(icon, size: 14, relativeTo: .caption) }
            Text(text).font(DS.font(12.5, 600))
        }
        .foregroundStyle(child ? DS.child : DS.textSecondary)
        .padding(.horizontal, 8).frame(minHeight: 24)
        .background(child ? DS.childSoft : DS.surfaceRaised, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

/// 숫자 타일 줄(시안 `.kpi`): 이름 13/500 흐림, 값 20/700, 아래 12/500 흐림.
struct KpiRow: View {
    struct Item: Hashable { let name: String; let value: String; var sub: String? = nil; var note: String? = nil }
    let items: [Item]
    @Environment(\.dynamicTypeSize) private var size

    var body: some View {
        let layout = size.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12)) : AnyLayout(HStackLayout(alignment: .top, spacing: 8))
        layout {
            ForEach(items, id: \.self) { i in
                VStack(alignment: .leading, spacing: 2) {
                    Text(i.name).font(DS.font(13, 500)).foregroundStyle(DS.textTertiary)
                    Text(i.value).font(DS.font(20, 700)).foregroundStyle(DS.textPrimary).monospacedDigit()
                    if let s = i.sub { Text(s).font(DS.font(12, 500)).foregroundStyle(DS.textTertiary) }
                    if let n = i.note { Text(n).font(DS.font(12, 500)).foregroundStyle(DS.textTertiary) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
            }
        }
    }
}

// MARK: 글래스(iOS 26 Liquid Glass, 그 아래는 얇은 머티리얼)

extension View {
    /// 기능 층(탭 바·떠 있는 버튼·시트·배너)에만 쓴다(HIG: 내용 층에는 쓰지 않는다).
    @ViewBuilder func glass<S: Shape>(in shape: S, tint: Color? = nil, interactive: Bool = false) -> some View {
        if #available(iOS 26, *) {
            let g: Glass = tint.map { Glass.regular.tint($0) } ?? .regular
            glassEffect(interactive ? g.interactive() : g, in: shape)
        } else {
            background(tint.map { AnyShapeStyle($0.opacity(0.85)) } ?? AnyShapeStyle(.ultraThinMaterial), in: shape)
        }
    }
}

/// 원형 글래스 아이콘 버튼(44): 시트의 닫기·저장, 머리의 뒤로.
struct GlassIconButton: View {
    let icon: Ico
    let label: String
    var tint: Color? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            IconImage(icon, size: 20)
                .foregroundStyle(tint == nil ? DS.textPrimary : DS.onAccent)
                .frame(width: 44, height: 44)
                .glass(in: Circle(), tint: tint, interactive: true)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

// MARK: 시안에서 섞어 만든 색(토큰 둘을 섞은 값)

extension DesignSystem {
    /// 소개 무대 빛(시안 .intro-hero: 흰 60% + stage, 다크 #232931).
    static let stageGlow = Color(lightHex: "#F6F7F9", darkHex: "#232931")
}
