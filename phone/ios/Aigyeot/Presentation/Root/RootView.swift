import SwiftUI

/// 첫 실행 → 안내 → (연결) → 탭(계약 7.1, phone-care-plan 5장·U7·U25).
struct RootView: View {
    @Environment(RootViewModel.self) private var vm

    var body: some View {
        @Bindable var vm = vm
        Group {
            switch vm.state.route {
            case .intro: IntroView()
            case .gone: GoneView()
            case .connect: ConnectView()
            case .main: MainView()   // 연결 안 됨도 탭을 쓴다(U7)
            }
        }
        .sheet(item: $vm.pendingCode) { code in ConfirmView(code: code) }
    }
}

/// 안내 한 화면: 무엇이 오가는지(G31). 글과 순서는 7.1 `intro.*` 그대로다(U43).
struct IntroView: View {
    @Environment(RootViewModel.self) private var vm

    var body: some View {
        VStack(alignment: .leading, spacing: DesignSystem.s3) {
            ScrollView {   // 큰 글자에서도 버튼이 화면 밖으로 밀리지 않게 글만 굴린다
                VStack(alignment: .leading, spacing: DesignSystem.s5) {
                    Text(CareText.introTitle).font(DesignSystem.display)
                    Text(CareText.introLead).font(DesignSystem.headline)
                    VStack(alignment: .leading, spacing: DesignSystem.s3) {
                        Label(CareText.introSends, systemImage: "arrow.left.arrow.right.circle")
                        Label(CareText.introNotSent, systemImage: "lock.circle")
                        Label(CareText.introRelay, systemImage: "server.rack")
                        Label(CareText.introAI, systemImage: "sparkles")
                    }
                    .font(DesignSystem.body)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, DesignSystem.s6)
            }
            Button { vm.connectFromIntro() } label: { Text(CareText.connectCar).frame(maxWidth: .infinity) }   // 안내에서 받은 링크가 있으면 그 확인 화면(U62)
                .accentButton().controlSize(.large)
            Button { vm.recordsOnlyFromIntro() } label: { Text(CareText.recordOnly).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered).controlSize(.large)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity).background(DesignSystem.bg)
    }
}

/// 차나 중계 쪽에서 끊겼을 때(401·4001, U25 a). 돌봄 기록은 남아 있어 기록만 계속 쓸 수 있다.
struct GoneView: View {
    @Environment(RootViewModel.self) private var vm

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            // 가장 큰 글자에서는 두 버튼이 높이를 차지해 문장이 말줄임됐다. 문장 칸을 굴린다(8장 동적 글자 크기).
            ScrollView {
                Label("연결이 끊겼어요. 다시 연결해 주세요.", systemImage: "exclamationmark.triangle")
                    .font(.title3.bold())
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, DesignSystem.s6)
            }
            .scrollBounceBehavior(.basedOnSize)
            Button { vm.reconnectAfterGone() } label: { Text(CareText.reconnect).frame(maxWidth: .infinity) }
                .accentButton().controlSize(.large)
            Button { vm.continueWithoutCar() } label: { Text(CareText.keepRecording).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered).controlSize(.large)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity).background(DesignSystem.bg)
    }
}

/// 연결: QR 찍기 또는 코드 넣기(7.1). QR·링크는 확인 화면으로, 직접 친 코드는 확인 없이 바로 연결한다(G17).
struct ConnectView: View {
    @Environment(RootViewModel.self) private var vm
    @State private var code = ""
    @State private var inputError: String?
    @State private var scanning = false
    @State private var connecting = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("차 화면의 QR을 찍거나 코드를 넣어 주세요").font(.title2.bold())
                    Button { scanning = true } label: {
                        Label("QR 찍기", systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity)
                    }
                    .accentButton().controlSize(.large).disabled(connecting)

                    VStack(alignment: .leading, spacing: 8) {
                        Text("코드로 입력").font(.headline)
                        TextField("6자리 숫자 또는 초대 코드", text: $code)
                            .textFieldStyle(.roundedBorder)
                            .font(.title3.monospaced())
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .onChange(of: code) { _, v in
                                inputError = nil
                                // 6자리를 다 넣으면 바로 연결한다.
                                if v.filter(\.isNumber).count == 6, v.allSatisfy({ $0.isNumber || $0 == " " || $0 == "-" }) { submit() }   // 잠금 중이면 submit이 막는다
                            }
                        if let e = inputError ?? vm.linkError { Text(e).font(.footnote).foregroundStyle(DesignSystem.danger) }
                        if connecting { HStack { ProgressView(); Text("확인하고 있어요") } }
                        Button { submit() } label: { Text("확인").frame(minWidth: 44, minHeight: 44) }.buttonStyle(.bordered).disabled(code.isEmpty || connecting || vm.state.isPairLocked)   // 44pt
                    }
                }
                .padding(24)
            }
            .screenBackground()
            .navigationTitle("연결")
            .toolbar {
                // 연결하지 않고 탭(연결 안 됨)으로 돌아간다(U7).
                ToolbarItem(placement: .cancellationAction) {
                    Button(CareText.cancel) { vm.cancelConnect() }.disabled(connecting)
                }
            }
            .fullScreenCover(isPresented: $scanning) { ScanView(isPresented: $scanning) }
        }
    }

    private func submit() {
        guard let c = PairCode.normalize(code) else { inputError = ConnectFailure.badCode.message; return }
        // 429 잠금 중에는 요청하지 않고 입력도 지우지 않는다. 시각은 모델이 들고 있다(9장).
        guard !connecting else { return }
        guard !vm.state.isPairLocked else { inputError = ConnectFailure.rateLimited(nil).message; return }
        code = ""
        connecting = true
        vm.beginConfirm()   // Task가 돌기 전 틈에 온 링크가 확인 화면을 바꾸지 않게(U52)
        Task {
            let f = await vm.connect(typed: c)
            vm.endConfirm()
            connecting = false
            if let f { inputError = f.message }
            else if vm.state.role == .guardian { await NotificationAccess.request() }   // 받을 푸시가 있는 guardian만 묻는다(G13)
        }
    }
}

/// 어두운 전체 카메라 화면, 한 줄 안내, 아래에 '코드로 입력'(구현 계획 8장).
struct ScanView: View {
    @Environment(RootViewModel.self) private var vm
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
    @Environment(RootViewModel.self) private var vm
    @Environment(\.dismiss) private var dismiss
    let code: PairCode
    @State private var busy = false
    @State private var failure: ConnectFailure?
    /// 누른 순간의 교체 여부(안드로이드 `switching`과 같다). 끊고 연결하는 동안 글자가 '연결하기'로 바뀌지 않게 끝날 때까지 둔다.
    @State private var switching: Bool?
    /// 이미 연결된 폰. 확인하기 전에는 지금 연결을 지우지 않는다.
    private var replacing: Bool { switching ?? vm.state.isPaired }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            ScrollView {   // 큰 글자에서 안내·오류가 함께 있어도 버튼이 밀리지 않게 글만 굴린다(IntroView와 같다)
                VStack(alignment: .leading, spacing: 20) {
                    Text(code.confirmTitle).font(.title2.bold())
                    Text(code.display).font(.system(size: codeSize, weight: .bold, design: .monospaced)).minimumScaleFactor(0.5).lineLimit(1)
                    if case .invite = code { Text("다른 보호자가 보낸 초대 코드예요. 리포트와 인계 알림을 받게 돼요.").font(.callout) }
                    if replacing { Text("지금 연결된 차를 끊고 새 차에 연결할까요?").font(.callout) }   // G16
                    if let failure { Text(failure.message).foregroundStyle(DesignSystem.danger) }
                    if let e = vm.linkError { Text(e).font(DesignSystem.caption).foregroundStyle(DesignSystem.textSecondary) }   // U52 pair.busy
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, DesignSystem.s6)
            }
            if busy { HStack { ProgressView(); Text("확인하고 있어요") } }
            Button { connect() } label: { Text(replacing ? CareText.pairConfirmSwitch : CareText.pairConfirm).frame(maxWidth: .infinity) }   // U49
                .accentButton().controlSize(.large).disabled(busy || vm.state.isPairLocked)
            Button { dismiss() } label: { Text("아니에요").frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle()) }.disabled(busy)   // 터치 영역 44pt(8장)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity).background(DesignSystem.bg)
        .interactiveDismissDisabled(busy)
    }

    private func connect() {
        busy = true
        switching = vm.state.isPaired
        failure = nil
        vm.beginConfirm()   // Task가 돌기 전 틈에 온 새 링크도 막는다(U52)
        Task {
            let f = await vm.confirmPair(code)
            busy = false
            switching = nil
            if let f { failure = f }   // 429 잠금은 모델이 들고 있다
            else if vm.state.role == .guardian { await NotificationAccess.request() }   // 받을 푸시가 있는 guardian만 묻는다(G13)
        }
    }
}
