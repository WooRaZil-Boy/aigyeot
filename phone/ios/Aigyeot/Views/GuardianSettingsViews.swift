import SwiftUI
import CoreImage.CIFilterBuiltins

/// 보호자: owner는 초대·목록, guardian은 인계 알림 받기.
struct GuardiansView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationStack {
            Group {
                if model.role == .owner { OwnerGuardians() } else { GuardianToggle() }
            }
            .navigationTitle("보호자")
            .safeAreaInset(edge: .top) { StatusBands().padding(.horizontal, 16) }
        }
    }
}

private struct OwnerGuardians: View {
    @Environment(AppModel.self) private var model
    @State private var invite: InviteResponse?
    @State private var busy = false
    @State private var error: String?

    private var others: [DeviceInfo] { model.state.devices.filter { $0.role == .owner || $0.role == .guardian } }

    var body: some View {
        List {
            Section {
                Text("다른 보호자를 초대하면 리포트와 인계 알림을 함께 받아요").font(.callout)
                Text("초대 코드는 24시간 동안, 한 번만 쓸 수 있어요.").font(.footnote).foregroundStyle(.secondary)
                if let invite { InviteCard(invite: invite) }
                if busy { HStack { ProgressView(); Text("초대 코드를 만들고 있어요") } }
                if let error { Text(error).font(.footnote).foregroundStyle(.red) }
                Button { create() } label: { Label("초대 만들기", systemImage: "person.badge.plus") }.disabled(busy || model.offline)
                if model.offline { Text("인터넷이 필요해요").font(.footnote).foregroundStyle(.secondary) }
            }
            Section("연결된 휴대폰") {
                if others.isEmpty { Text("아직 없어요").foregroundStyle(.secondary) }
                ForEach(others, id: \.deviceId) { d in
                    HStack {
                        Text(d.role == .owner ? "함께 타는 보호자" : "다른 보호자")
                        if d.deviceId == model.credentials?.deviceId { Text("(이 휴대폰)").foregroundStyle(.secondary) }
                        Spacer()
                        Text(d.platform == .ios ? "iPhone" : "Android").font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .task { await model.refreshDevices() }
    }

    private func create() {
        busy = true; error = nil
        Task {
            let (r, message) = await model.createInvite()
            if let r { invite = r }
            error = message
            busy = false
        }
    }
}

/// 초대 코드, 링크로 보내기, QR(받는 사람은 연결 화면에서 QR을 찍어도 된다).
private struct InviteCard: View {
    let invite: InviteResponse

    var body: some View {
        VStack(spacing: 10) {
            Text(invite.display).font(.system(size: 30, weight: .bold, design: .monospaced))
            if let img = qr(invite.link) { Image(uiImage: img).interpolation(.none).resizable().frame(width: 160, height: 160) }
            if let url = URL(string: invite.link) {
                ShareLink(item: url, message: Text("아이곁 초대 코드: \(invite.display)")) { Label("링크로 보내기", systemImage: "square.and.arrow.up") }
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func qr(_ s: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8)
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

private struct GuardianToggle: View {
    @Environment(AppModel.self) private var model
    @State private var error: String?

    var body: some View {
        List {
            Section {
                Toggle("인계 알림 받기", isOn: Binding(
                    get: { model.state.handoffAlerts ?? true },
                    set: { on in
                        Task {
                            error = await model.setHandoffAlerts(on)
                            if on, error == nil { await NotificationAccess.request() }   // 켤 때 다시 묻는다(7.3)
                        }
                    }))
                if let error { Text(error).font(.footnote).foregroundStyle(.red) }
            } footer: {
                Text("아기가 계속 울어 차가 운전자에게 쉼터를 제안하거나 세우도록 안내하면 알려 드려요. 리포트는 알림 설정과 상관없이 받아요.")
            }
        }
    }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmDisconnect = false
    @State private var confirmClear = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                Section("연결") {
                    LabeledContent("역할", value: model.role == .owner ? "함께 타는 보호자" : "다른 보호자")
                    if let c = model.credentials { LabeledContent("차", value: String(c.carId.prefix(8))) }
                    Button("연결 끊기", role: .destructive) { confirmDisconnect = true }.disabled(busy)
                    if busy { HStack { ProgressView(); Text("끊고 있어요") } }
                    if let error { Text(error).font(.footnote).foregroundStyle(.red) }
                }
                Section {
                    Button("이 휴대폰의 기록 지우기", role: .destructive) { confirmClear = true }
                } footer: {
                    Text("리포트·인계 알림·녹음 파일을 이 휴대폰에서 지워요. 차와의 연결은 그대로예요. 차와 중계에 남은 기록은 차 화면에서 지워요.")
                }
                Section { LabeledContent("버전", value: RelayClient.appVersion) }
            }
            .navigationTitle("설정")
            .safeAreaInset(edge: .top) { StatusBands().padding(.horizontal, 16) }
            .confirmationDialog("차와의 연결을 끊을까요?", isPresented: $confirmDisconnect, titleVisibility: .visible) {
                Button("연결 끊기", role: .destructive) {
                    busy = true; error = nil
                    Task { error = await model.disconnect(); busy = false }
                }
            }
            .confirmationDialog("이 휴대폰의 기록을 지울까요?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("기록 지우기", role: .destructive) { model.clearLocalRecords() }
            }
        }
    }
}
