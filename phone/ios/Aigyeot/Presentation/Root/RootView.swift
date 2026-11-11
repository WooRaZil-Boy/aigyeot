import SwiftUI

/// 첫 실행 → 안내 → (연결) → 탭(계약 7.1, phone-care-plan 5장·U7·U25).
struct RootView: View {
    @Environment(AppViewModel.self) private var vm

    var body: some View {
        @Bindable var vm = vm
        Group {
            switch vm.ui.route {
            case .intro: IntroView()
            case .gone: GoneView()
            case .connect: Scoped({ $0.connect() }) { _ in ConnectView() }
            // 연결 안 됨도 탭을 쓴다(U7). 연결이 바뀌어도 탭 화면을 새로 만들지 않는다: 보내기 전 녹음(수명 표 L4 '끊기·새 연결에 그대로')이
            // 시트와 함께 버려진다. 옛 연결의 화면 값은 뷰모델이 잡은 자격(P5)으로 가린다(초대·인계 알림 받기)
            case .main: MainView()
            }
        }
        .sheet(item: $vm.pendingCode) { code in Scoped({ $0.connect() }) { _ in ConfirmView(code: code) } }
        // 받을 푸시가 있는 guardian만 연결을 마친 뒤 알림 권한을 묻는다(G13).
        // 묻기 직전에 지금 연결을 다시 본다: 마친 직후 401로 끊겼으면(GONE) 묻지 않는다(나누기 전과 같다)
        .onChange(of: vm.ui.pairRun) { _, r in
            if case .done(_, .guardian) = r { Task { if vm.ui.role == .guardian { await NotificationAccess.request() } } }
        }
    }
}

/// 안내 한 화면: 무엇이 오가는지(G31·U43 안 B, 시안 onboard.html·README v8b-10). 무대(차 정지 그림) → 머리 이름 → 두 줄 제목 → 짧은 줄 넷
/// → '자세히 보기'(원문 넷 시트) → 아래 고정 단추 둘. 글은 CareText(7.1 `intro.*`) 한 곳.
struct IntroView: View {
    @Environment(AppViewModel.self) private var vm
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 글 칸(머리 이름 위 ~ '자세히 보기' 아래)과 단추 띠 높이(무대 높이 규칙 ⑦이 쓴다).
    @State private var contentH: CGFloat = 0
    @State private var barH: CGFloat = 0
    @State private var sheet = false
    /// 무대가 그려지는가(⑦ 높이 규칙 결과). 등장 안무 계획이 쓴다.
    @State private var heroShown = true
    /// 등장 안무(v8b-10 ⑤). pending은 아직 정하지 않음(처음 그리는 첫 장은 숨긴 채), run은 이번에 할 계획, none은 하지 않음(처음부터 다 보임).
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
            // 접근성 크기(AX1~5): 아이콘을 글 위로 쌓고 단추는 고정하지 않고 굴림 끝에, 무대는 숨긴다(v8b-10 ⑥)
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

    /// 무대: 차 정지 그림(11-11 사용자 결정, 시안 assets/intro, 테마마다 한 장) + 바닥 전에 사라지는 타원 빛. 꾸밈이라 읽지 않는다.
    private func stage(height: CGFloat, top: CGFloat) -> some View {
        GeometryReader { g in
            ZStack {
                // 시안 radial-gradient(ellipse 72% 40% at 50% 58%): 반지름이 폭 72%·높이 40%인 타원
                EllipticalGradient(colors: [DesignSystem.stageGlow, DesignSystem.stageGlow.opacity(0)], center: .center, startRadiusFraction: 0, endRadiusFraction: 0.5)
                    .frame(width: g.size.width * 1.44, height: g.size.height * 0.8)
                    .position(x: g.size.width / 2, y: g.size.height * 0.58)
                    // 빛은 차와 따로 900ms `light`로 번진다(한 층으로 묶으면 차가 900ms 내내 흐리다)
                    .opacity(hidden ? 0 : 1)
                    .animation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.9), value: go)
                // (나) 도착(11-11 사용자 확정): 오른쪽 48pt에서 spring.smooth로 들어오고, 투명도는 220ms `base`. 모디파이어마다 따로 단다
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

    /// 주 '차와 연결하기'(안내에서 받은 링크가 있으면 그 확인 화면, U62) + 글자 단추 '먼저 기록만 쓸게요'.
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

/// 첫 안내 등장 한 칸(v8b-10 ⑤): 아래 `dy` → 0, 블러 → 0, 투명 → 보임, 360ms `enter`(cb .2,.8,.2,1)를 `delayMs` 뒤에.
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

/// 첫 안내 등장 안무 계획(v8b-10 ⑤, 안드로이드 `IntroPlan`과 같은 값): 차 안무를 하는지, 글·단추 띠 시작(ms).
struct IntroPlan: Equatable {
    let car: Bool
    let textDelayMs: Int
    let barDelayMs: Int
}

/// 첫 안내 등장 안무는 앱 프로세스에서 이 화면을 처음 그릴 때 한 번만 한다(화면 상태가 아니라 프로세스 표시 하나).
/// 되돌아오기·시트 닫기·다크 전환은 다시 하지 않고, 동작 줄이기로 건너뛴 것도 한 것으로 친다.
enum IntroMotion {
    static var played = false

    /// 무대가 있으면 빛·차가 0ms, 글이 240ms부터. 무대가 숨으면(⑦) 차 안무 없이 글이 120ms부터. 단추 띠는 글 일곱 칸(머리 이름·제목·줄 넷·자세히) 뒤.
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

/// 첫 안내 무대 높이(v8b-10 ⑦): 창 높이 − 글 칸(무대 아래 여백 6 + 머리 이름 위 ~ '자세히 보기' 아래) − 단추 띠 − 32(흐림 24 + 여유 8), 최대 232.
/// 120보다 작으면 nil(무대를 그리지 않는다). 무엇이 오가는지가 결정 단추보다 먼저 보여야 해서(U62) 꾸밈인 차를 먼저 줄인다.
enum IntroLayout {
    static let heroMax: CGFloat = 232, heroMin: CGFloat = 120
    /// 무대와 머리 이름 사이(v8b-10 ②). 무대가 보일 때만 붙지만 계산에는 늘 넣는다: 잰 글 높이에 이 여백이 들어 있으면
    /// 무대를 보일 때 글이 6 늘어 숨김 조건이 되고, 숨기면 다시 보임 조건이 되어 남는 높이 120~125pt 창에서 배치가 오갔다(검토 2회차).
    static let stageGap: CGFloat = 6

    /// `text`는 무대 여부와 무관한 글 높이(머리 이름 위 ~ '자세히 보기' 아래, 무대 여백 없이). 같은 입력이면 결과가 한 번에 정해진다.
    /// 안드로이드 `introStageHeight`의 `text`는 이 값 + 무대 여백(6, 늘 붙는 위 여백)이라 결과가 같다.
    static func heroHeight(window: CGFloat, text: CGFloat, bar: CGFloat, max top: CGFloat = heroMax) -> CGFloat? {
        let h = min(top, window - stageGap - text - bar - 32)
        return h < heroMin ? nil : h
    }
}

/// '자세히 보기' 시트(U43): 제목 '오가는 정보' + 라벨·원문 넷(7.1 `intro.*` 글자 그대로) + '닫기'. 열면 읽기 초점이 제목으로 간다.
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

/// 차나 중계 쪽에서 끊겼을 때(401·4001, U25 a). 돌봄 기록은 남아 있어 기록만 계속 쓸 수 있다.
struct GoneView: View {
    @Environment(AppViewModel.self) private var vm

    /// 결과 화면(v8b-10 ⑧, 안드로이드 연결됐어요와 같은 결): 가운데 원 64 + 끊김 아이콘, 큰 제목, 부제(R9), 아래 고정 단추 둘.
    /// 가장 큰 글자에서는 문장 칸이 굴러간다(8장 동적 글자 크기).
    var body: some View {
        GeometryReader { geo in ScrollView {
            VStack(spacing: 16) {
                IconImage(.unlink, size: 28).foregroundStyle(DesignSystem.child)
                    .frame(width: 64, height: 64).background(DesignSystem.childSoft, in: Circle())
                Text("연결이 끊겼어요").typeStyle(.display).foregroundStyle(DesignSystem.textPrimary)
                    .multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
                Text(CareText.disconnectKeepsCare).typeStyle(.body).foregroundStyle(DesignSystem.textSecondary)   // R9(할 일은 단추가 말한다)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 20)
            .frame(maxWidth: .infinity, minHeight: geo.size.height * 0.8)   // 가운데보다 조금 위, 넘치면 굴린다
        }
        .scrollBounceBehavior(.basedOnSize) }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 4) {
                Button { vm.reconnectAfterGone() } label: { Text(CareText.reconnect) }.buttonStyle(.pill)
                Button { vm.continueWithoutCar() } label: { Text(CareText.keepRecording) }.buttonStyle(.pillText)   // 사용자 목소리 1인칭(B11)
            }
            .padding(.horizontal, 20).padding(.top, 8)
            .background(DesignSystem.bg)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity).background(DesignSystem.bg)
    }
}

/// 연결: QR 찍기 또는 코드 넣기(7.1). QR·링크는 확인 화면으로, 직접 친 코드는 확인 없이 바로 연결한다(G17).
struct ConnectView: View {
    @Environment(AppViewModel.self) private var app
    @Environment(ConnectViewModel.self) private var vm
    @State private var code = ""
    /// 입력 칸이 막은 까닭(코드 모양·잠금 중 누름). 보일 때도 `visible(locked:)`을 거쳐 잠금이 풀리면 429 글이 걷힌다.
    @State private var inputFailure: ConnectFailure?
    @State private var scanning = false

    private var connecting: Bool { if case .running = vm.ui.run { true } else { false } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("차 화면의 QR이나 코드로 연결해요").typeStyle(.display).foregroundStyle(DesignSystem.textPrimary)
                    Button { scanning = true } label: { Label { Text("QR 찍기") } icon: { IconImage(.scanLine, size: 20) } }
                        .buttonStyle(.pillBig).disabled(connecting)

                    VStack(alignment: .leading, spacing: 10) {
                        Text("코드로 연결").typeStyle(.headline).foregroundStyle(DesignSystem.textPrimary)
                        TextField("6자리 숫자 또는 초대 코드", text: $code)
                            .typeStyle(.title, monospacedDigits: true)
                            .padding(.horizontal, 16).frame(minHeight: 56)
                            .background(DesignSystem.surface, in: RoundedRectangle(cornerRadius: DesignSystem.rM, style: .continuous))
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .onChange(of: code) { _, v in
                                inputFailure = nil
                                vm.clear()   // 고치면 지난 연결 실패 글을 걷는다
                                // 6자리를 다 넣으면 바로 연결한다.
                                if v.filter(\.isNumber).count == 6, v.allSatisfy({ $0.isNumber || $0 == " " || $0 == "-" }) { submit() }   // 잠금 중이면 submit이 막는다
                            }
                        // 칸 아래 한 줄: 가장 최근 오류. 429가 아닌 실패 → QR·링크 오류 → 429 차례(429 뒤에 QR이 실패해도 그 글이 보이게).
                        let failure = inputFailure?.visible(locked: vm.ui.isLocked) ?? vm.ui.failure()
                        let other = failure == .rateLimited ? nil : failure
                        let waitOnTop = other == nil && app.linkError == nil && failure == .rateLimited
                        if let f = other {
                            ConnectFailureText(failure: f, lockedUntil: vm.ui.lockedUntil).typeStyle(.caption).foregroundStyle(DesignSystem.danger)
                        } else if let e = app.linkError {
                            Text(e).typeStyle(.caption).foregroundStyle(DesignSystem.danger)
                        } else if waitOnTop {
                            ConnectFailureText(failure: .rateLimited, lockedUntil: vm.ui.lockedUntil).typeStyle(.caption).foregroundStyle(DesignSystem.danger)
                        }
                        // 잠긴 동안 위 줄이 남은 시간이 아니면(다른 오류가 덮었거나, 고쳐서 걷혔거나, QR로 시작한 연결) 남은 시간을 한 줄 더 보인다.
                        // '확인'이 왜 꺼졌는지 늘 보이게(안드로이드와 같음, 같은 말 두 번 금지).
                        if vm.ui.isLocked, !waitOnTop {
                            ConnectFailureText(failure: .rateLimited, lockedUntil: vm.ui.lockedUntil).typeStyle(.caption).foregroundStyle(DesignSystem.textTertiary)
                        }
                        if connecting { HStack { ProgressView(); Text("확인하고 있어요").typeStyle(.body) } }
                        Button { submit() } label: { Text("확인") }.buttonStyle(.pillTonal).disabled(code.isEmpty || connecting || vm.ui.isLocked)
                    }
                }
                .padding(20)
            }
            .screenBackground()
            .navigationTitle("차와 연결")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // 연결하지 않고 탭(연결 안 됨)으로 돌아간다(U7).
                ToolbarItem(placement: .cancellationAction) {
                    Button(CareText.cancel) { app.cancelConnect() }.disabled(connecting)
                }
            }
            .fullScreenCover(isPresented: $scanning) { ScanView(isPresented: $scanning) }
        }
        .onDisappear { vm.clear() }
    }

    private func submit() {
        guard let c = PairCode.normalize(code) else { inputFailure = .badCode; return }
        // 429 잠금 중에는 요청하지 않고 입력도 지우지 않는다. 시각은 Pair가 들고 있다(9장).
        guard !connecting else { return }
        guard !vm.ui.isLocked else { inputFailure = .rateLimited; return }   // 잠금이 풀리면 visible(locked:)이 걷는다
        code = ""
        app.clearLinkError()
        vm.start(c)   // 작업이 돌기 전 틈에 온 링크도 막는다(U52). 연결되면 연결 흐름이 닫힌다
    }
}

/// 어두운 전체 카메라 화면, 한 줄 안내, 아래에 '코드 넣기'(구현 계획 8장).
struct ScanView: View {
    @Environment(AppViewModel.self) private var vm
    @Binding var isPresented: Bool
    @State private var allowed: Bool?
    @State private var invalid = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if allowed == true, QRScanner.isSupported {
                QRScanner { text in
                    if let c = PairCode.parse(scanned: text) {
                        isPresented = false
                        vm.propose(c)
                        return true
                    }
                    invalid = true
                    return false
                }
                .ignoresSafeArea()
            } else if allowed != nil {
                VStack(spacing: 12) {
                    Image(systemName: "camera.fill").font(.largeTitle)
                    Text(allowed == false ? "카메라를 쓸 수 없어요. 설정에서 카메라를 켜거나 코드를 넣어 주세요."
                                          : "이 기기에서는 QR을 찍을 수 없어요. 코드를 넣어 주세요.")
                        .typeStyle(.body).multilineTextAlignment(.center)
                }
                .foregroundStyle(.white).padding(32)
            }
            VStack {
                Text(invalid ? "올바른 연결 QR이 아니에요" : "차 화면의 QR을 비춰 주세요")
                    .typeStyle(.headline).foregroundStyle(.white).padding(.top, 60)
                Spacer()
                Button { isPresented = false } label: { Text("코드 넣기").typeStyle(.strong).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }   // 44pt(8장)
                    .foregroundStyle(.white).padding(.bottom, 40)
            }
        }
        .task {
            // 카메라 권한은 QR 화면을 처음 열 때 묻는다(7.3).
            allowed = await CameraAccess.request()
        }
    }
}

/// 확인(QR·링크만): 남이 보낸 링크로 남의 차에 붙지 않도록 코드가 맞는지 묻는다(G17).
/// 연결 코드는 차 화면, 초대 코드는 받은 초대 코드와 견준다.
struct ConfirmView: View {
    @ScaledMetric(relativeTo: .largeTitle) private var codeSize: CGFloat = 44   // 대조할 코드도 동적 글자 크기를 따른다(8장)
    @Environment(AppViewModel.self) private var app
    @Environment(ConnectViewModel.self) private var vm
    @Environment(\.dismiss) private var dismiss
    let code: PairCode
    /// 누른 순간의 교체 여부(안드로이드 `switching`과 같다). 끊고 연결하는 동안 글자가 '연결하기'로 바뀌지 않게 끝날 때까지 둔다.
    @State private var switching: Bool?
    /// 끊고 연결의 끊기와 연결 요청이 도는 동안.
    private var busy: Bool { if case .running = vm.ui.run { true } else { false } }
    /// 이 코드의 연결 실패(429 잠금은 Pair가 들고 있고, 그 글은 잠금 동안만).
    private var failure: ConnectFailure? { vm.ui.failure(for: code) }
    /// 이미 연결된 폰. 확인하기 전에는 지금 연결을 지우지 않는다.
    private var replacing: Bool { switching ?? vm.ui.connected }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            ScrollView {   // 큰 글자에서 안내·오류가 함께 있어도 버튼이 밀리지 않게 글만 굴린다(IntroView와 같다)
                VStack(alignment: .leading, spacing: 20) {
                    Text(code.confirmTitle).typeStyle(.display).foregroundStyle(DesignSystem.textPrimary)
                    Text(code.display).font(.system(size: codeSize, weight: .bold, design: .monospaced)).minimumScaleFactor(0.5).lineLimit(1)
                    if case .invite = code { Text("다른 보호자가 보낸 초대 코드예요. 리포트와 인계 알림을 받아요.").typeStyle(.body).foregroundStyle(DesignSystem.textSecondary) }
                    if replacing { Text("지금 연결된 차를 끊고 새 차에 연결할까요?").typeStyle(.body).foregroundStyle(DesignSystem.textSecondary) }   // G16
                    if let failure { ConnectFailureText(failure: failure, lockedUntil: vm.ui.lockedUntil).typeStyle(.body).foregroundStyle(DesignSystem.danger) }
                    // 다른 코드(직접 입력)로 받은 429로 잠긴 동안에도 남은 시간을 보인다: '연결하기'가 왜 꺼졌는지(연결 화면과 같은 규칙, 같은 글 두 번 없이)
                    if vm.ui.isLocked, failure != .rateLimited {
                        ConnectFailureText(failure: .rateLimited, lockedUntil: vm.ui.lockedUntil).typeStyle(.caption).foregroundStyle(DesignSystem.textTertiary)
                    }
                    if let e = app.linkError { Text(e).typeStyle(.caption).foregroundStyle(DesignSystem.textSecondary) }   // U52 pair.busy
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, DesignSystem.s6)
            }
            if busy { HStack { ProgressView(); Text("확인하고 있어요").typeStyle(.body) } }
            Button { connect() } label: { Text(replacing ? CareText.pairConfirmSwitch : CareText.pairConfirm) }   // U49
                .buttonStyle(.pillBig).disabled(busy || vm.ui.isLocked)
            Button { dismiss() } label: { Text(CareText.closeDialog) }.buttonStyle(.pillTonal).disabled(busy)   // P9·U71: 창만 닫는 보조 단추는 '닫기'(iOS만, 안드로이드는 '코드 다시 넣기'로 연결 화면에 감)
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity).background(DesignSystem.bg)
        .interactiveDismissDisabled(busy)
        .onChange(of: busy) { _, b in if !b { switching = nil } }
        .onDisappear { vm.clear() }
    }

    private func connect() {
        switching = vm.ui.connected
        vm.start(code)   // 작업이 돌기 전 틈에 온 새 링크도 막는다(U52). 연결되면 시트가 닫힌다
    }
}

/// 연결 실패 글. 429 잠금이면 남은 시간이 줄어들도록 1초마다 다시 그린다.
private struct ConnectFailureText: View {
    let failure: ConnectFailure
    let lockedUntil: Date?
    var body: some View {
        if case .rateLimited = failure {
            TimelineView(.periodic(from: .now, by: 1)) { ctx in Text(failure.text(lockedUntil: lockedUntil, now: ctx.date)) }
        } else {
            Text(failure.message)
        }
    }
}
