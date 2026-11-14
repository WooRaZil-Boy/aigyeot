import SwiftUI

/// 첫 실행 → 안내 → (연결) → 탭.
struct RootView: View {
    @Environment(AppViewModel.self) private var vm

    var body: some View {
        @Bindable var vm = vm
        Group {
            switch vm.ui.route {
            case .intro: IntroView()
            case .gone: GoneView()
            case .connect: Scoped({ $0.connect() }) { _ in ConnectView() }
            // 연결 안 됨도 탭을 쓴다. 연결이 바뀌어도 탭 화면을 새로 만들지 않는다: 보내기 전 녹음이
            // 시트와 함께 버려진다. 옛 연결의 화면 값은 뷰모델이 잡은 자격으로 가린다(초대·인계 알림 받기)
            case .main: MainView()
            }
        }
        .sheet(item: $vm.pendingCode) { code in Scoped({ $0.connect() }) { _ in ConfirmView(code: code) } }
        // 받을 푸시가 있는 guardian만 연결을 마친 뒤 알림 권한을 묻는다.
        // 묻기 직전에 지금 연결을 다시 본다: 마친 직후 401로 끊겼으면(GONE) 묻지 않는다(나누기 전과 같다)
        .onChange(of: vm.ui.pairRun) { _, r in
            if case .done(_, .guardian) = r { Task { if vm.ui.role == .guardian { await NotificationAccess.request() } } }
        }
    }
}
