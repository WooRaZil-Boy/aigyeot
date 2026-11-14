import SwiftUI

/// 어두운 전체 카메라 화면, 한 줄 안내, 아래에 '코드 넣기'.
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
                // 44pt
                Button { isPresented = false } label: { Text("코드 넣기").typeStyle(.strong).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }
                    .foregroundStyle(.white).padding(.bottom, 40)
            }
        }
        .task {
            // 카메라 권한은 QR 화면을 처음 열 때 묻는다.
            allowed = await CameraAccess.request()
        }
    }
}
