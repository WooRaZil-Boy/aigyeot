import SwiftUI

/// 차나 중계 쪽에서 끊겼을 때(401·4001). 돌봄 기록은 남아 있어 기록만 계속 쓸 수 있다.
struct GoneView: View {
    @Environment(AppViewModel.self) private var vm

    /// 결과 화면(안드로이드 연결됐어요와 같은 결): 가운데 원 64 + 끊김 아이콘, 큰 제목, 부제, 아래 고정 단추 둘.
    /// 가장 큰 글자에서는 문장 칸이 굴러간다(동적 글자 크기).
    var body: some View {
        GeometryReader { geo in ScrollView {
            VStack(spacing: 16) {
                IconImage(.unlink, size: 28).foregroundStyle(DesignSystem.child)
                    .frame(width: 64, height: 64).background(DesignSystem.childSoft, in: Circle())
                Text("연결이 끊겼어요").typeStyle(.display).foregroundStyle(DesignSystem.textPrimary)
                    .multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
                Text(CareText.disconnectKeepsCare).typeStyle(.body).foregroundStyle(DesignSystem.textSecondary)   // 할 일은 단추가 말한다
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 20)
            .frame(maxWidth: .infinity, minHeight: geo.size.height * 0.8)   // 가운데보다 조금 위, 넘치면 굴린다
        }
        .scrollBounceBehavior(.basedOnSize) }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 4) {
                Button { vm.reconnectAfterGone() } label: { Text(CareText.reconnect) }.buttonStyle(.pill)
                Button { vm.continueWithoutCar() } label: { Text(CareText.keepRecording) }.buttonStyle(.pillText)   // 사용자 목소리 1인칭
            }
            .padding(.horizontal, 20).padding(.top, 8)
            .background(DesignSystem.bg)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity).background(DesignSystem.bg)
    }
}
