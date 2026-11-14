import SwiftUI

/// 연결: QR 찍기 또는 코드 넣기. QR·링크는 확인 화면으로, 직접 친 코드는 확인 없이 바로 연결한다.
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
                            ConnectFailureText(failure: .rateLimited, lockedUntil: vm.ui.lockedUntil)
                                .typeStyle(.caption)
                                .foregroundStyle(DesignSystem.textTertiary)
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
                // 연결하지 않고 탭(연결 안 됨)으로 돌아간다.
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
        // 429 잠금 중에는 요청하지 않고 입력도 지우지 않는다. 시각은 PairDevice가 들고 있다.
        guard !connecting else { return }
        guard !vm.ui.isLocked else { inputFailure = .rateLimited; return }   // 잠금이 풀리면 visible(locked:)이 걷는다
        code = ""
        app.clearLinkError()
        vm.start(c)   // 작업이 돌기 전 틈에 온 링크도 막는다. 연결되면 연결 흐름이 닫힌다
    }
}
