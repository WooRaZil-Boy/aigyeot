import SwiftUI

/// 첫 실행 → 안내 → (연결) → 탭(계약 7.1, phone-care-plan 5장·U7·U25).
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Group {
            if !model.state.introSeen { IntroView() }
            else if model.gone { GoneView() }
            else if !model.isPaired && model.connectShown { ConnectView() }
            else { MainView() }   // 연결 안 됨도 탭을 쓴다(U7)
        }
        .sheet(item: $model.pendingCode) { code in ConfirmView(code: code) }
        .alert("지금 연결된 차를 끊고 새 차에 연결할까요?", isPresented: Binding(
            get: { model.replaceCandidate != nil }, set: { if !$0 { model.replaceCandidate = nil } })) {
            Button("끊고 연결", role: .destructive) {
                // 알림창이 닫히면 바인딩이 replaceCandidate를 지운다. 동기 클로저에서 코드를 먼저 복사해 넘긴다.
                if let code = model.replaceCandidate { Task { await model.confirmReplace(code: code) } }
            }
            Button("취소", role: .cancel) {}
        }
    }
}

/// 안내 한 화면: 무엇이 오가는지(G31).
struct IntroView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Spacer()
            Text("아이곁").font(.largeTitle.bold())
            Text("차에서 아기가 울면 차가 한 일을 휴대폰에서 확인해요.").font(.title3)
            VStack(alignment: .leading, spacing: 12) {
                Label("차로 가는 것: 수유 시작 시각, 직접 녹음한 노래", systemImage: "arrow.up.circle")
                Label("차에서 오는 것: 주행 리포트, 인계 알림", systemImage: "arrow.down.circle")
                Label("위치 좌표와 울음 소리는 오가지 않아요", systemImage: "lock.circle")
            }
            .font(.callout)
            Spacer()
            Button {
                model.markIntroSeen()
                model.connectShown = true
            } label: { Text(CareText.connectCar).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large)
            Button { model.markIntroSeen() } label: { Text(CareText.recordOnly).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered).controlSize(.large)
        }
        .padding(24)
    }
}

/// 차나 중계 쪽에서 끊겼을 때(401·4001, U25 a). 돌봄 기록은 남아 있어 기록만 계속 쓸 수 있다.
struct GoneView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Spacer()
            Label("연결이 끊겼어요. 다시 연결해 주세요.", systemImage: "exclamationmark.triangle")
                .font(.title3.bold())
            Spacer()
            Button { model.reconnectAfterGone() } label: { Text(CareText.reconnect).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large)
            Button { model.continueWithoutCar() } label: { Text(CareText.keepRecording).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered).controlSize(.large)
        }
        .padding(24)
    }
}

/// 연결: QR 찍기 또는 코드 넣기(7.1). QR·링크는 확인 화면으로, 직접 친 코드는 확인 없이 바로 연결한다(G17).
struct ConnectView: View {
    @Environment(AppModel.self) private var model
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
                    .buttonStyle(.borderedProminent).controlSize(.large).disabled(connecting)

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
                        if let e = inputError ?? model.linkError { Text(e).font(.footnote).foregroundStyle(.red) }
                        if connecting { HStack { ProgressView(); Text("확인하고 있어요") } }
                        Button("확인") { submit() }.buttonStyle(.bordered).disabled(code.isEmpty || connecting || model.isPairLocked)
                    }
                    Text("링크 aigyeot://pair?code=… 를 열어도 같은 화면으로 와요.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding(24)
            }
            .navigationTitle("연결")
            .toolbar {
                // 연결하지 않고 탭(연결 안 됨)으로 돌아간다(U7).
                ToolbarItem(placement: .cancellationAction) {
                    Button(CareText.cancel) { model.connectShown = false }.disabled(connecting)
                }
            }
            .fullScreenCover(isPresented: $scanning) { ScanView(isPresented: $scanning) }
        }
    }

    private func submit() {
        guard let c = PairCode.normalize(code) else { inputError = ConnectFailure.badCode.message; return }
        // 429 잠금 중에는 요청하지 않고 입력도 지우지 않는다. 시각은 모델이 들고 있다(9장).
        guard !connecting else { return }
        guard !model.isPairLocked else { inputError = ConnectFailure.rateLimited(nil).message; return }
        code = ""
        connecting = true
        Task {
            let f = await model.connect(typed: c)
            connecting = false
            if let f { inputError = f.message }
            else if model.role == .guardian { await NotificationAccess.request() }   // 받을 푸시가 있는 guardian만 묻는다(G13)
        }
    }
}

/// 어두운 전체 카메라 화면, 한 줄 안내, 아래에 '코드로 입력'(구현 계획 8장).
struct ScanView: View {
    @Environment(AppModel.self) private var model
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
                        model.propose(c)
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
                Button("코드로 입력") { isPresented = false }
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
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let code: PairCode
    @State private var busy = false
    @State private var failure: ConnectFailure?

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Spacer()
            Text(code.confirmTitle).font(.title2.bold())
            Text(code.display).font(.system(size: 44, weight: .bold, design: .monospaced))
            if case .invite = code { Text("다른 보호자가 보낸 초대 코드예요. 리포트와 인계 알림을 받게 돼요.").font(.callout) }
            if let failure { Text(failure.message).foregroundStyle(.red) }
            Spacer()
            if busy { HStack { ProgressView(); Text("확인하고 있어요") } }
            Button { connect() } label: { Text("연결하기").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large).disabled(busy || model.isPairLocked)
            Button("아니에요") { dismiss() }.frame(maxWidth: .infinity).disabled(busy)
        }
        .padding(24)
        .interactiveDismissDisabled(busy)
    }

    private func connect() {
        busy = true
        failure = nil
        Task {
            let f = await model.pair(code)
            busy = false
            if let f { failure = f }   // 429 잠금은 모델이 들고 있다
            else if model.role == .guardian { await NotificationAccess.request() }   // 받을 푸시가 있는 guardian만 묻는다(G13)
        }
    }
}
