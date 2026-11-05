import SwiftUI
import CoreImage.CIFilterBuiltins
import UniformTypeIdentifiers

/// 공유 시트에 넘길 내보내기 파일. 공유할 때 임시 폴더에 `aigyeot-care-{YYYYMMDD}.csv`로 쓴다.
struct CareExportFile: Transferable {
    let events: [CareEvent]
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .commaSeparatedText) { item in
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(CareText.exportFileName(Date()))
            try CareExport.csv(item.events).write(to: url, options: .atomic)
            return SentTransferredFile(url)
        }
    }
}

/// 보호자(설정 안의 한 줄로 연다): owner는 초대·목록, guardian은 인계 알림 받기.
struct GuardiansView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            if model.role == .owner { OwnerGuardians() } else { GuardianToggle() }
        }
        .navigationTitle(CareText.guardians)
        .safeAreaInset(edge: .top) { StatusBands().padding(.horizontal, 16) }
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

/// 설정(5장 순서): 보호자 · 아이 별명 · 잠·기저귀도 기록하기 · (내 기록 내보내기, C7) · 이 휴대폰의 기록 지우기 · 연결 끊기 · 정보·라이선스.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmDisconnect = false
    @State private var confirmClear = false
    @State private var busy = false
    @State private var error: String?
    @State private var nickname = ""

    var body: some View {
        NavigationStack {
            List {
                if model.isPaired {
                    Section { NavigationLink(CareText.guardians) { GuardiansView() } }
                }
                Section {
                    LabeledContent(CareText.nickname) {
                        TextField(CareText.nicknamePlaceholder, text: $nickname)
                            .multilineTextAlignment(.trailing)
                            .accessibilityLabel(CareText.nickname)
                            .onSubmit { model.setChildNickname(nickname) }
                    }
                    Toggle(CareText.sleepDiaperToggle, isOn: Binding(get: { model.care.sleepDiaperEnabled },
                                                                    set: { model.setSleepDiaperEnabled($0) }))
                } footer: {
                    Text(CareText.sleepDiaperToggleNote)
                }
                Section {
                    // C7·U29: 누를 때 CSV를 만들어 공유 시트로 넘긴다.
                    ShareLink(item: CareExportFile(events: model.care.events),
                              preview: SharePreview("")) { Text(CareText.export) }   // U33: 공유 시트 제목은 비운다
                }
                Section {
                    Button(CareText.clear, role: .destructive) { confirmClear = true }
                }
                if model.isPaired {
                    Section("연결") {
                        LabeledContent("역할", value: model.role == .owner ? "함께 타는 보호자" : "다른 보호자")
                        if let c = model.credentials { LabeledContent("차", value: String(c.carId.prefix(8))) }
                        Button(CareText.disconnect, role: .destructive) { confirmDisconnect = true }.disabled(busy)
                        if busy { HStack { ProgressView(); Text("끊고 있어요") } }
                        if let error { Text(error).font(.footnote).foregroundStyle(.red) }
                    }
                }
                Section {
                    NavigationLink(CareText.about) {
                        // U28: 판, 오픈소스 라이선스(iOS 앱은 외부 라이브러리가 없다).
                        List {
                            Text(CareText.version(RelayClient.appVersion))
                            Section(CareText.oss) { Text(CareText.ossNone).foregroundStyle(.secondary) }
                        }
                        .navigationTitle(CareText.about)
                    }
                }
            }
            .navigationTitle("설정")
            .safeAreaInset(edge: .top) { StatusBands().padding(.horizontal, 16) }
            .onAppear { nickname = model.care.childNickname ?? "" }
            .onDisappear { model.setChildNickname(nickname) }   // 칸을 벗어나도 남긴다(글자마다 저장하지 않는다)
            .confirmationDialog("차와의 연결을 끊을까요?", isPresented: $confirmDisconnect, titleVisibility: .visible) {
                Button("연결 끊기", role: .destructive) {
                    busy = true; error = nil
                    Task { error = await model.disconnect(); busy = false }
                }
            } message: {
                Text(CareText.disconnectKeepsCare)   // R9
            }
            .confirmationDialog(CareText.clearConfirm, isPresented: $confirmClear, titleVisibility: .visible) {
                Button("기록 지우기", role: .destructive) { model.clearLocalRecords() }
            }
        }
    }
}
