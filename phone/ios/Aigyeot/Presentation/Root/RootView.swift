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
            case .main: MainView()   // 연결 안 됨도 탭을 쓴다(U7)
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

/// 안내 한 화면: 무엇이 오가는지(G31, 시안 S.intro). 3D 차 무대 + 앱 표지 → 제목·첫 줄 → 오가는 것 목록 → 버튼 둘.
/// 글과 순서는 7.1 `intro.*` 그대로다(U43, 거치는 곳 `intro.relay`도 빼지 않는다).
struct IntroView: View {
    @Environment(AppViewModel.self) private var vm

    var body: some View {
        // 시안 .intro: 무대(250, 화면 맨 위부터 상태 막대 뒤까지) → 제목·첫 줄 → 목록 → 버튼 둘이 한 흐름(사이 12, 옆 12). 큰 글자면 통째로 굴린다.
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topLeading) {
                    RadialGradient(colors: [DesignSystem.stageGlow, DesignSystem.bg], center: UnitPoint(x: 0.5, y: 0.7), startRadius: 0, endRadius: 260)
                    CarStageCard(preset: .phone, hint: false).padding(.top, 40)
                    AppMark(size: 52).padding(.leading, 20).padding(.top, 62)
                }
                .frame(height: 250)
                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(CareText.introTitle).typeStyle(.display).foregroundStyle(DesignSystem.textPrimary)
                        Text(CareText.introLead).font(DesignSystem.font(16, 500)).foregroundStyle(DesignSystem.textSecondary)
                    }
                    VStack(alignment: .leading, spacing: 10) {
                        row(.link, CareText.introSends)
                        row(.x, CareText.introNotSent, tone: DesignSystem.child)
                        row(.server, CareText.introRelay)
                        row(.sparkles, CareText.introAI)
                    }
                    Button { vm.connectFromIntro() } label: { Label { Text(CareText.connectCar) } icon: { IconImage(.qrCode, size: 20) } }   // 안내에서 받은 링크가 있으면 그 확인 화면(U62)
                        .buttonStyle(.pillBig)
                    Button { vm.recordsOnlyFromIntro() } label: { Text(CareText.recordOnly) }
                        .buttonStyle(.pillTonal)
                }
                .padding(.horizontal, 12).padding(.bottom, 24)
            }
        }
        .ignoresSafeArea(edges: .top)
        .scrollBounceBehavior(.basedOnSize)
        .frame(maxWidth: .infinity, maxHeight: .infinity).background(DesignSystem.bg)
    }

    /// 한 줄: 회색 둥근 네모(34) 아이콘 + 13.5/500 흐린 글.
    private func row(_ icon: Ico, _ text: String, tone: Color = DesignSystem.textSecondary) -> some View {
        HStack(alignment: .top, spacing: 12) {
            IconImage(icon, size: 18).foregroundStyle(tone)
                .frame(width: 34, height: 34)
                .background(DesignSystem.tonal, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            Text(text).font(DesignSystem.font(13.5, 500)).lineSpacing(4).foregroundStyle(DesignSystem.textSecondary)   // 줄 높이 20
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// 차나 중계 쪽에서 끊겼을 때(401·4001, U25 a). 돌봄 기록은 남아 있어 기록만 계속 쓸 수 있다.
struct GoneView: View {
    @Environment(AppViewModel.self) private var vm

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // 가장 큰 글자에서는 두 버튼이 높이를 차지해 문장이 말줄임됐다. 문장 칸을 굴린다(8장 동적 글자 크기).
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    IconImage(.unlink, size: 24).foregroundStyle(DesignSystem.warning)
                        .frame(width: 52, height: 52).background(DesignSystem.tonal, in: Circle())
                    Text("연결이 끊겼어요. 다시 연결해 주세요.").typeStyle(.display).foregroundStyle(DesignSystem.textPrimary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, DesignSystem.s6)
            }
            .scrollBounceBehavior(.basedOnSize)
            Button { vm.reconnectAfterGone() } label: { Text(CareText.reconnect) }.buttonStyle(.pillBig)
            Button { vm.continueWithoutCar() } label: { Text(CareText.keepRecording) }.buttonStyle(.pillTonal)
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity).background(DesignSystem.bg)
    }
}

/// 연결: QR 찍기 또는 코드 넣기(7.1). QR·링크는 확인 화면으로, 직접 친 코드는 확인 없이 바로 연결한다(G17).
struct ConnectView: View {
    @Environment(AppViewModel.self) private var app
    @Environment(ConnectViewModel.self) private var vm
    @State private var code = ""
    @State private var inputError: String?
    @State private var scanning = false

    private var connecting: Bool { if case .running = vm.ui.run { true } else { false } }
    private var failure: String? { if case .failed(_, let f) = vm.ui.run { f.message } else { nil } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("차 화면의 QR을 찍거나 코드를 넣어 주세요").typeStyle(.display).foregroundStyle(DesignSystem.textPrimary)
                    Button { scanning = true } label: { Label { Text("QR 찍기") } icon: { IconImage(.scanLine, size: 20) } }
                        .buttonStyle(.pillBig).disabled(connecting)

                    VStack(alignment: .leading, spacing: 10) {
                        Text("코드로 입력").typeStyle(.headline).foregroundStyle(DesignSystem.textPrimary)
                        TextField("6자리 숫자 또는 초대 코드", text: $code)
                            .font(DesignSystem.font(20, 600, relativeTo: .title3, monospacedDigits: true))
                            .padding(.horizontal, 16).frame(minHeight: 56)
                            .background(DesignSystem.surface, in: RoundedRectangle(cornerRadius: DesignSystem.rM, style: .continuous))
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .onChange(of: code) { _, v in
                                inputError = nil
                                vm.clear()   // 고치면 지난 연결 실패 글을 걷는다
                                // 6자리를 다 넣으면 바로 연결한다.
                                if v.filter(\.isNumber).count == 6, v.allSatisfy({ $0.isNumber || $0 == " " || $0 == "-" }) { submit() }   // 잠금 중이면 submit이 막는다
                            }
                        if let e = inputError ?? failure ?? app.linkError { Text(e).font(DesignSystem.caption).foregroundStyle(DesignSystem.danger) }
                        if connecting { HStack { ProgressView(); Text("확인하고 있어요").font(DesignSystem.body) } }
                        Button { submit() } label: { Text("확인") }.buttonStyle(.pillTonal).disabled(code.isEmpty || connecting || vm.ui.isLocked)
                    }
                }
                .padding(20)
            }
            .screenBackground()
            .navigationTitle("연결")
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
        guard let c = PairCode.normalize(code) else { inputError = ConnectFailure.badCode.message; return }
        // 429 잠금 중에는 요청하지 않고 입력도 지우지 않는다. 시각은 Pair가 들고 있다(9장).
        guard !connecting else { return }
        guard !vm.ui.isLocked else { inputError = ConnectFailure.rateLimited(nil).message; return }
        code = ""
        app.clearLinkError()
        vm.start(c)   // 작업이 돌기 전 틈에 온 링크도 막는다(U52). 연결되면 연결 흐름이 닫힌다
    }
}

/// 어두운 전체 카메라 화면, 한 줄 안내, 아래에 '코드로 입력'(구현 계획 8장).
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
                    Text(allowed == false ? "카메라를 쓸 수 없어요. 설정에서 카메라를 켜거나 코드로 입력해 주세요."
                                          : "이 기기에서는 QR을 찍을 수 없어요. 코드로 입력해 주세요.")
                        .multilineTextAlignment(.center)
                }
                .foregroundStyle(.white).padding(32)
            }
            VStack {
                Text(invalid ? "올바른 연결 QR이 아니에요" : "차 화면의 QR을 비춰 주세요")
                    .font(.headline).foregroundStyle(.white).padding(.top, 60)
                Spacer()
                Button { isPresented = false } label: { Text("코드로 입력").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }   // 44pt(8장)
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
    /// 이 코드의 연결 실패(429 잠금은 Pair가 들고 있다).
    private var failure: ConnectFailure? { if case .failed(let c, let f) = vm.ui.run, c == code { f } else { nil } }
    /// 이미 연결된 폰. 확인하기 전에는 지금 연결을 지우지 않는다.
    private var replacing: Bool { switching ?? vm.ui.connected }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            ScrollView {   // 큰 글자에서 안내·오류가 함께 있어도 버튼이 밀리지 않게 글만 굴린다(IntroView와 같다)
                VStack(alignment: .leading, spacing: 20) {
                    Text(code.confirmTitle).typeStyle(.display).foregroundStyle(DesignSystem.textPrimary)
                    Text(code.display).font(.system(size: codeSize, weight: .bold, design: .monospaced)).minimumScaleFactor(0.5).lineLimit(1)
                    if case .invite = code { Text("다른 보호자가 보낸 초대 코드예요. 리포트와 인계 알림을 받게 돼요.").typeStyle(.body).foregroundStyle(DesignSystem.textSecondary) }
                    if replacing { Text("지금 연결된 차를 끊고 새 차에 연결할까요?").typeStyle(.body).foregroundStyle(DesignSystem.textSecondary) }   // G16
                    if let failure { Text(failure.message).font(DesignSystem.body).foregroundStyle(DesignSystem.danger) }
                    if let e = app.linkError { Text(e).font(DesignSystem.caption).foregroundStyle(DesignSystem.textSecondary) }   // U52 pair.busy
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, DesignSystem.s6)
            }
            if busy { HStack { ProgressView(); Text("확인하고 있어요").font(DesignSystem.body) } }
            Button { connect() } label: { Text(replacing ? CareText.pairConfirmSwitch : CareText.pairConfirm) }   // U49
                .buttonStyle(.pillBig).disabled(busy || vm.ui.isLocked)
            Button { dismiss() } label: { Text("아니에요") }.buttonStyle(.pillTonal).disabled(busy)
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
