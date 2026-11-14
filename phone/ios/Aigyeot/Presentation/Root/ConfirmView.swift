import SwiftUI

/// 확인(QR·링크만): 남이 보낸 링크로 남의 차에 붙지 않도록 코드가 맞는지 묻는다.
/// 연결 코드는 차 화면, 초대 코드는 받은 초대 코드와 견준다.
struct ConfirmView: View {
    @ScaledMetric(relativeTo: .largeTitle) private var codeSize: CGFloat = 44   // 대조할 코드도 동적 글자 크기를 따른다
    @Environment(AppViewModel.self) private var app
    @Environment(ConnectViewModel.self) private var vm
    @Environment(\.dismiss) private var dismiss
    let code: PairCode
    /// 누른 순간의 교체 여부(안드로이드 `switching`과 같다). 끊고 연결하는 동안 글자가 '연결하기'로 바뀌지 않게 끝날 때까지 둔다.
    @State private var switching: Bool?
    /// 끊고 연결의 끊기와 연결 요청이 도는 동안.
    private var busy: Bool { if case .running = vm.ui.run { true } else { false } }
    /// 이 코드의 연결 실패(429 잠금은 PairDevice가 들고 있고, 그 글은 잠금 동안만).
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
                    if replacing { Text("지금 연결된 차를 끊고 새 차에 연결할까요?").typeStyle(.body).foregroundStyle(DesignSystem.textSecondary) }
                    if let failure {
                        ConnectFailureText(failure: failure, lockedUntil: vm.ui.lockedUntil).typeStyle(.body).foregroundStyle(DesignSystem.danger)
                    }
                    // 다른 코드(직접 입력)로 받은 429로 잠긴 동안에도 남은 시간을 보인다: '연결하기'가 왜 꺼졌는지(연결 화면과 같은 규칙, 같은 글 두 번 없이)
                    if vm.ui.isLocked, failure != .rateLimited {
                        ConnectFailureText(failure: .rateLimited, lockedUntil: vm.ui.lockedUntil).typeStyle(.caption).foregroundStyle(DesignSystem.textTertiary)
                    }
                    if let e = app.linkError { Text(e).typeStyle(.caption).foregroundStyle(DesignSystem.textSecondary) }   // pair.busy
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, DesignSystem.s6)
            }
            if busy { HStack { ProgressView(); Text("확인하고 있어요").typeStyle(.body) } }
            Button { connect() } label: { Text(replacing ? CareText.pairConfirmSwitch : CareText.pairConfirm) }
                .buttonStyle(.pillBig).disabled(busy || vm.ui.isLocked)
            // 창만 닫는 보조 단추는 '닫기'(iOS만, 안드로이드는 '코드 다시 넣기'로 연결 화면에 감)
            Button { dismiss() } label: { Text(CareText.closeDialog) }.buttonStyle(.pillTonal).disabled(busy)
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity).background(DesignSystem.bg)
        .interactiveDismissDisabled(busy)
        .onChange(of: busy) { _, b in if !b { switching = nil } }
        .onDisappear { vm.clear() }
    }

    private func connect() {
        switching = vm.ui.connected
        vm.start(code)   // 작업이 돌기 전 틈에 온 새 링크도 막는다. 연결되면 시트가 닫힌다
    }
}
