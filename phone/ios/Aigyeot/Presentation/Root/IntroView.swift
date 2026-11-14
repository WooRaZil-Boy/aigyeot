import SwiftUI

/// 안내 한 화면: 무엇이 오가는지. 무대(차 정지 그림) → 머리 이름 → 두 줄 제목 → 짧은 줄 넷
/// → '자세히 보기'(원문 넷 시트) → 아래 고정 단추 둘. 글은 CareText(`intro.*`) 한 곳.
struct IntroView: View {
    @Environment(AppViewModel.self) private var vm
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 글 칸(머리 이름 위 ~ '자세히 보기' 아래)과 단추 띠 높이(무대 높이 계산이 쓴다).
    @State private var contentH: CGFloat = 0
    @State private var barH: CGFloat = 0
    @State private var sheet = false
    /// 무대가 그려지는가(무대 높이 계산 결과). 등장 안무 계획이 쓴다.
    @State private var heroShown = true
    /// 등장 안무. pending은 아직 정하지 않음(처음 그리는 첫 장은 숨긴 채), run은 이번에 할 계획, none은 하지 않음(처음부터 다 보임).
    @State private var motion: Motion = IntroMotion.played ? .none : .pending
    /// 안무 시작(거짓 → 참으로 바뀔 때 모디파이어마다 단 애니메이션이 돈다).
    @State private var go = false
    @AccessibilityFocusState private var moreFocused: Bool

    private enum Motion: Equatable { case pending, run(IntroPlan), none }

    /// 지금 숨긴 상태로 그리는가: 처음 그리는데 아직 정하지 않았거나(동작 줄이기면 아님), 계획이 있는데 아직 시작 전.
    private var hidden: Bool {
        switch motion {
        case .pending: !reduceMotion
        case .run: !go
        case .none: false
        }
    }
    private var plan: IntroPlan? { if case .run(let p) = motion { p } else { nil } }

    /// 줄 넷의 아이콘(intro.short.* 순서). 둘째(보내지 않아요)만 아이 색.
    private static let icons: [Ico] = [.link, .x, .shieldCheck, .sparkles]

    var body: some View {
        GeometryReader { geo in
            // 접근성 크기(AX1~5): 아이콘을 글 위로 쌓고 단추는 고정하지 않고 굴림 끝에, 무대는 숨긴다
            let big = typeSize.isAccessibilitySize
            let window = geo.size.height + geo.safeAreaInsets.top + geo.safeAreaInsets.bottom
            let hero = big ? nil : IntroLayout.heroHeight(window: window, text: contentH, bar: barH + geo.safeAreaInsets.bottom)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let hero { stage(height: hero, top: geo.safeAreaInsets.top) } else { Color.clear.frame(height: geo.safeAreaInsets.top + 8) }
                    texts(big: big)
                        // 무대 여백을 붙이기 전에 잰다: 무대 여부와 무관한 글 높이라야 보임·숨김이 오가지 않는다(여백은 IntroLayout이 더한다)
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentH = $0 }
                        .padding(.top, hero == nil ? 0 : IntroLayout.stageGap)
                    if big { buttons.padding(.horizontal, 20).padding(.vertical, 24) }
                }
            }
            .ignoresSafeArea(edges: .top)
            .scrollBounceBehavior(.basedOnSize)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if !big { bar.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { barH = $0 } }
            }
            .onChange(of: hero != nil, initial: true) { _, v in heroShown = v }
        }
        .background(DesignSystem.bg)
        .sheet(isPresented: $sheet, onDismiss: { moreFocused = true }) { IntroSheet() }   // 닫으면 읽기 초점을 '자세히 보기'로
        .task {
            // 프로세스에서 처음 그릴 때 한 번(되돌아오기·시트 닫기·다크 전환은 다시 하지 않는다). 글 칸을 잰 뒤 무대가 보이는지로 정한다
            guard motion == .pending else { return }
            for _ in 0..<10 where contentH == 0 { try? await Task.sleep(for: .milliseconds(16)) }
            guard let p = IntroMotion.take(reduced: reduceMotion, stage: heroShown) else { motion = .none; return }
            motion = .run(p)
            await Task.yield()   // 숨긴 첫 장을 그린 다음 차례에 시작한다
            go = true
        }
    }

    /// 글 한 칸의 등장: 아래 16 → 0, 블러 6 → 0, 투명 → 보임, 360ms `enter`. `order`번째(머리 이름 0 … '자세히 보기' 6), 50ms 간격.
    private func rise(_ order: Int) -> some ViewModifier {
        IntroRise(hidden: hidden, dy: 16, blur: 6, go: go, delayMs: plan.map { $0.textDelayMs + 50 * order } ?? 0)
    }

    /// 무대: 차 정지 그림(테마마다 한 장) + 바닥 전에 사라지는 타원 빛. 꾸밈이라 읽지 않는다.
    private func stage(height: CGFloat, top: CGFloat) -> some View {
        GeometryReader { g in
            ZStack {
                // 타원 그라데이션(중심 50% 58%): 반지름이 폭 72%·높이 40%인 타원
                EllipticalGradient(colors: [DesignSystem.stageGlow, DesignSystem.stageGlow.opacity(0)], center: .center, startRadiusFraction: 0,
                    endRadiusFraction: 0.5)
                    .frame(width: g.size.width * 1.44, height: g.size.height * 0.8)
                    .position(x: g.size.width / 2, y: g.size.height * 0.58)
                    // 빛은 차와 따로 900ms `light`로 번진다(한 층으로 묶으면 차가 900ms 내내 흐리다)
                    .opacity(hidden ? 0 : 1)
                    .animation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.9), value: go)
                // 도착: 오른쪽 48pt에서 spring.smooth로 들어오고, 투명도는 220ms `base`. 모디파이어마다 따로 단다
                Image("IntroCar").resizable().scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.top, top)   // 상태 막대 아래부터 바닥까지의 칸 가운데
                    .offset(x: hidden ? 48 : 0)
                    .animation(.smooth(duration: 0.32), value: go)
                    .opacity(hidden ? 0 : 1)
                    .animation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.22), value: go)
            }
        }
        .frame(height: height)
        .clipped()
        .accessibilityHidden(true)
    }

    private func texts(big: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                AppMark(size: 24)
                Text(CareText.introTitle).typeStyle(.action).foregroundStyle(DesignSystem.textSecondary)
            }
            .modifier(rise(0))
            Text(CareText.introLead).typeStyle(.display).foregroundStyle(DesignSystem.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .padding(.top, 10)
                .modifier(rise(1))
            VStack(alignment: .leading, spacing: 20) {
                ForEach(Array(CareText.introShort.enumerated()), id: \.offset) { i, r in
                    InfoRow(icon: Self.icons[i], title: r.title, sub: r.sub, tone: i == 1 ? DesignSystem.child : DesignSystem.textSecondary)
                        .modifier(rise(2 + i))
                }
            }
            .padding(.top, 28)
            .accessibilityElement(children: .contain)
            .accessibilityLabel(CareText.introSheetTitle)   // 네 줄 묶음 이름 '오가는 정보'
            Button { sheet = true } label: {
                HStack(spacing: 2) {
                    Text(CareText.introMore).typeStyle(.quickLabel)
                    IconImage(.chevronRight, size: 16)
                }
                .foregroundStyle(DesignSystem.textSecondary)
                .frame(minHeight: 48).contentShape(Rectangle())
            }
            .buttonStyle(.press)
            .padding(.leading, big ? 0 : 40).padding(.top, 6)   // 글 시작선 60(20 + 아이콘 칸 24 + 16)
            .accessibilityLabel(CareText.introMoreA11y)
            .accessibilityFocused($moreFocused)
            .modifier(rise(6))
        }
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// 주 '차와 연결하기'(안내에서 받은 링크가 있으면 그 확인 화면) + 글자 단추 '먼저 기록만 쓸게요'.
    private var buttons: some View {
        VStack(spacing: 4) {
            Button { vm.connectFromIntro() } label: { Text(CareText.connectCar) }.buttonStyle(.pill)
            Button { vm.recordsOnlyFromIntro() } label: { Text(CareText.recordOnly) }.buttonStyle(.pillText)
        }
    }

    /// 아래 고정 단추 띠: 위 8·옆 20, 띠 위 24는 투명 → bg로 흐려진다.
    private var bar: some View {
        buttons
            .padding(.horizontal, 20).padding(.top, 8)
            .background(alignment: .top) {
                VStack(spacing: 0) {
                    LinearGradient(colors: [DesignSystem.bg.opacity(0), DesignSystem.bg], startPoint: .top, endPoint: .bottom).frame(height: 24)
                    DesignSystem.bg
                }
                .padding(.top, -24)
                .ignoresSafeArea(edges: .bottom)
                .allowsHitTesting(false)
            }
            .modifier(IntroRise(hidden: hidden, dy: 12, blur: 0, go: go, delayMs: plan?.barDelayMs ?? 0))
    }
}

/// 첫 안내 등장 한 칸: 아래 `dy` → 0, 블러 → 0, 투명 → 보임, 360ms `enter`(cb .2,.8,.2,1)를 `delayMs` 뒤에.
private struct IntroRise: ViewModifier {
    let hidden: Bool
    let dy: CGFloat
    let blur: CGFloat
    let go: Bool
    let delayMs: Int
    func body(content: Content) -> some View {
        content
            .opacity(hidden ? 0 : 1)
            .offset(y: hidden ? dy : 0)
            .blur(radius: hidden ? blur : 0)
            .animation(.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.36).delay(Double(delayMs) / 1000), value: go)
    }
}

/// 첫 안내 등장 안무 계획(안드로이드 `IntroPlan`과 같은 값): 차 안무를 하는지, 글·단추 띠 시작(ms).
struct IntroPlan: Equatable {
    let car: Bool
    let textDelayMs: Int
    let barDelayMs: Int
}

/// 첫 안내 등장 안무는 앱 프로세스에서 이 화면을 처음 그릴 때 한 번만 한다(화면 상태가 아니라 프로세스 표시 하나).
/// 되돌아오기·시트 닫기·다크 전환은 다시 하지 않고, 동작 줄이기로 건너뛴 것도 한 것으로 친다.
enum IntroMotion {
    static var played = false

    /// 무대가 있으면 빛·차가 0ms, 글이 240ms부터. 무대가 숨으면 차 안무 없이 글이 120ms부터. 단추 띠는 글 일곱 칸(머리 이름·제목·줄 넷·자세히) 뒤.
    static func plan(played: Bool, reduced: Bool, stage: Bool) -> IntroPlan? {
        guard !played, !reduced else { return nil }
        let text = stage ? 240 : 120
        return IntroPlan(car: stage, textDelayMs: text, barDelayMs: text + 50 * 7)
    }

    /// 이번 그림의 계획을 받고 '했음'으로 표시한다. 이미 했거나 동작 줄이기면 nil(처음부터 다 보인다).
    static func take(reduced: Bool, stage: Bool) -> IntroPlan? {
        defer { played = true }
        return plan(played: played, reduced: reduced, stage: stage)
    }
}

/// 첫 안내 무대 높이: 창 높이 − 글 칸(무대 아래 여백 6 + 머리 이름 위 ~ '자세히 보기' 아래) − 단추 띠 − 32(흐림 24 + 여유 8), 최대 232.
/// 120보다 작으면 nil(무대를 그리지 않는다). 무엇이 오가는지가 결정 단추보다 먼저 보여야 해서 꾸밈인 차를 먼저 줄인다.
enum IntroLayout {
    static let heroMax: CGFloat = 232, heroMin: CGFloat = 120
    /// 무대와 머리 이름 사이. 무대가 보일 때만 붙지만 계산에는 늘 넣는다: 잰 글 높이에 이 여백이 들어 있으면
    /// 무대를 보일 때 글이 6 늘어 숨김 조건이 되고, 숨기면 다시 보임 조건이 되어 남는 높이 120~125pt 창에서 배치가 오갔다.
    static let stageGap: CGFloat = 6

    /// `text`는 무대 여부와 무관한 글 높이(머리 이름 위 ~ '자세히 보기' 아래, 무대 여백 없이). 같은 입력이면 결과가 한 번에 정해진다.
    /// 안드로이드 `introStageHeight`의 `text`는 이 값 + 무대 여백(6, 늘 붙는 위 여백)이라 결과가 같다.
    static func heroHeight(window: CGFloat, text: CGFloat, bar: CGFloat, max top: CGFloat = heroMax) -> CGFloat? {
        let h = min(top, window - stageGap - text - bar - 32)
        return h < heroMin ? nil : h
    }
}

/// '자세히 보기' 시트: 제목 '오가는 정보' + 라벨·원문 넷(`intro.*` 글자 그대로) + '닫기'. 열면 읽기 초점이 제목으로 간다.
private struct IntroSheet: View {
    @Environment(\.dismiss) private var dismiss
    @AccessibilityFocusState private var titleFocused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(CareText.introSheetTitle).typeStyle(.title).foregroundStyle(DesignSystem.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityFocused($titleFocused)
                    .padding(.bottom, 6)
                ForEach(Array(CareText.introSheet.enumerated()), id: \.offset) { i, s in
                    if i > 0 { Rectangle().fill(DesignSystem.line).frame(height: 1) }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(s.label).typeStyle(.chip).foregroundStyle(DesignSystem.textPrimary)
                        Text(s.text).typeStyle(.valueKey).foregroundStyle(DesignSystem.textSecondary).fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.vertical, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .combine)
                }
                Button { dismiss() } label: { Text(CareText.introSheetClose) }.buttonStyle(.pillTonal).padding(.top, 14)
            }
            .padding(.horizontal, 24).padding(.top, 28).padding(.bottom, 16)
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(DesignSystem.surface)
        .onAppear { titleFocused = true }
    }
}
