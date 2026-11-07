import SwiftUI
import CoreImage.CIFilterBuiltins
import UniformTypeIdentifiers

/// 정보·라이선스의 한 줄(U28·U63): 앱에 링크되는 오픈소스와 그 라이선스. 누르면 `Licenses/`의 전문을 보인다.
struct OssEntry: Identifiable {
    let name: String, artifact: String, licenses: [(name: String, file: String)]
    var id: String { name }

    /// shortcut: 손으로 적은 목록이다(FirebaseMessaging과 그 의존, 빌드 산출물의 링크된 모듈로 확인). project.yml의 패키지를 바꾸면 Package.resolved와 함께 고친다.
    static let all: [OssEntry] = [
        OssEntry(name: "Firebase iOS SDK", artifact: "firebase-ios-sdk 12.19.2 (FirebaseCore, FirebaseCoreInternal, FirebaseInstallations, FirebaseMessaging)",
                 licenses: [("Apache License 2.0", "Apache-2.0")]),
        OssEntry(name: "GoogleUtilities", artifact: "GoogleUtilities 8.1.5",
                 licenses: [("Apache License 2.0", "Apache-2.0"), ("MIT License (IsAppEncrypted)", "MIT-IsAppEncrypted")]),
        OssEntry(name: "GoogleDataTransport", artifact: "GoogleDataTransport 10.1.1", licenses: [("Apache License 2.0", "Apache-2.0")]),
        OssEntry(name: "Promises", artifact: "promises 2.4.1 (FBLPromises)", licenses: [("Apache License 2.0", "Apache-2.0")]),
        OssEntry(name: "nanopb", artifact: "nanopb 2.30910.1", licenses: [("zlib License", "Zlib-nanopb")]),
    ]

    /// 전문. 앱 묶음에 없으면 nil(시험이 모든 파일이 있는지 본다).
    static func text(_ file: String) -> String? {
        Bundle.main.url(forResource: file, withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
    }
}

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
    @Environment(SettingsViewModel.self) private var vm

    var body: some View {
        Group {
            if vm.state.role == .owner { OwnerGuardians() } else { GuardianToggle() }
        }
        .screenBackground()
        .navigationTitle(CareText.guardians)
        .safeAreaInset(edge: .top) { StatusBands().padding(.horizontal, 16) }
    }
}

private struct OwnerGuardians: View {
    @Environment(SettingsViewModel.self) private var vm
    @State private var invite: InviteResponse?
    @State private var busy = false
    @State private var error: String?

    private var others: [DeviceInfo] { vm.state.devices.filter { $0.role == .owner || $0.role == .guardian } }

    var body: some View {
        List {
            Section {
                Text("다른 보호자를 초대하면 리포트와 인계 알림을 함께 받아요").font(.callout)
                Text("초대 코드는 24시간 동안, 한 번만 쓸 수 있어요.").font(.footnote).foregroundStyle(DesignSystem.textSecondary)
                if let invite { InviteCard(invite: invite) }
                if busy { HStack { ProgressView(); Text("초대 코드를 만들고 있어요") } }
                if let error { Text(error).font(.footnote).foregroundStyle(DesignSystem.danger) }
                Button { create() } label: { Label("초대 만들기", systemImage: "person.badge.plus") }.disabled(busy || vm.state.offline)
                if vm.state.offline { Text("인터넷이 필요해요").font(.footnote).foregroundStyle(DesignSystem.textSecondary) }
            }
            Section {
                if others.isEmpty { Text("아직 없어요").foregroundStyle(DesignSystem.textSecondary) }
                ForEach(others, id: \.deviceId) { d in
                    HStack {
                        Text(d.role == .owner ? "함께 타는 보호자" : "다른 보호자")
                        if d.deviceId == vm.state.credentials?.deviceId { Text("(이 휴대폰)").foregroundStyle(DesignSystem.textSecondary) }
                        Spacer()
                        Text(d.platform == .ios ? "iPhone" : "Android").font(.footnote).foregroundStyle(DesignSystem.textSecondary)
                    }
                }
            } header: { SectionHeader("연결된 휴대폰") }
        }
        .task { await vm.refreshDevices() }
    }

    private func create() {
        busy = true; error = nil
        Task {
            let (r, message) = await vm.createInvite()
            if let r { invite = r }
            error = message
            busy = false
        }
    }
}

/// 초대 코드, 링크로 보내기, QR(받는 사람은 연결 화면에서 QR을 찍어도 된다).
private struct InviteCard: View {
    @ScaledMetric(relativeTo: .title) private var codeSize: CGFloat = 30   // 대조할 코드도 동적 글자 크기를 따른다(8장)
    let invite: InviteResponse

    var body: some View {
        VStack(spacing: 10) {
            Text(invite.display).font(.system(size: codeSize, weight: .bold, design: .monospaced)).minimumScaleFactor(0.5).lineLimit(1)
            if let img = qr(invite.link) { Image(uiImage: img).interpolation(.none).resizable().frame(width: 160, height: 160).accessibilityLabel("초대 QR") }
            if let url = URL(string: invite.link) {
                ShareLink(item: url, message: Text("아이곁 초대 코드: \(invite.display)")) { Label("링크로 보내기", systemImage: "square.and.arrow.up") }
            }
        }
        .frame(maxWidth: .infinity)
    }

    private static let ciContext = CIContext()   // 다시 그릴 때마다 만들지 않는다(만드는 비용이 크다)

    private func qr(_ s: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8)
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = Self.ciContext.createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

private struct GuardianToggle: View {
    @Environment(SettingsViewModel.self) private var vm
    @State private var error: String?

    var body: some View {
        List {
            Section {
                Toggle("인계 알림 받기", isOn: Binding(
                    get: { vm.state.handoffAlerts ?? true },
                    set: { on in
                        Task {
                            error = await vm.setHandoffAlerts(on)
                            if on, error == nil { await NotificationAccess.request() }   // 켤 때 다시 묻는다(7.3)
                        }
                    }))
                .disabled(vm.state.handoffBusy)   // 요청이 겹치면 응답 순서에 따라 화면과 실제 설정이 달라진다(잠금은 모델에, 화면을 다시 열어도)
                if let error { Text(error).font(.footnote).foregroundStyle(DesignSystem.danger) }
            } footer: {
                SectionHeader("아기가 계속 울어 차가 운전자에게 쉼터를 제안하거나 세우도록 안내하면 알려 드려요. 리포트는 알림 설정과 상관없이 받아요.")
            }
        }
    }
}

/// 설정(5장 순서): 보호자 · 아이 별명 · 잠·기저귀도 기록하기 · (내 기록 내보내기, C7) · 이 휴대폰의 기록 지우기 · 연결 끊기 · 정보·라이선스.
struct SettingsView: View {
    @Environment(SettingsViewModel.self) private var vm
    @State private var confirmDisconnect = false
    @State private var confirmClear = false
    @State private var busy = false
    @State private var error: String?
    @State private var nickname = ""

    var body: some View {
        NavigationStack {
            List {
                if vm.state.isPaired {
                    Section { NavigationLink(CareText.guardians) { GuardiansView() } }
                }
                Section {
                    LabeledContent(CareText.nickname) {
                        TextField(CareText.nicknamePlaceholder, text: $nickname)
                            .multilineTextAlignment(.trailing)
                            .accessibilityLabel(CareText.nickname)
                            .onSubmit { vm.setChildNickname(nickname) }
                    }
                    Toggle(CareText.sleepDiaperToggle, isOn: Binding(get: { vm.state.care.sleepDiaperEnabled },
                                                                    set: { vm.setSleepDiaperEnabled($0) }))
                } footer: {
                    SectionHeader(CareText.sleepDiaperToggleNote)
                }
                Section {
                    // C7·U29: 누를 때 CSV를 만들어 공유 시트로 넘긴다.
                    ShareLink(item: CareExportFile(events: vm.state.care.events),
                              preview: SharePreview("")) { Text(CareText.export) }   // U33: 공유 시트 제목은 비운다
                }
                Section {
                    Button(CareText.clear, role: .destructive) { confirmClear = true }.foregroundStyle(DesignSystem.danger)   // 시스템 빨강은 대비 미달(13장 danger)
                }
                if vm.state.isPaired {
                    Section {
                        LabeledContent("역할", value: vm.state.role == .owner ? "함께 타는 보호자" : "다른 보호자").labeledContentStyle(TokenLabeledContentStyle())
                        if let c = vm.state.credentials { LabeledContent("차", value: String(c.carId.prefix(8))).labeledContentStyle(TokenLabeledContentStyle()) }
                        Button(CareText.disconnect, role: .destructive) { confirmDisconnect = true }.foregroundStyle(DesignSystem.danger).disabled(busy || vm.state.offline)
                        if vm.state.offline, !busy { Text("인터넷이 필요해요").font(.footnote).foregroundStyle(DesignSystem.textSecondary) }   // 7.2 설정 '연결 끊기 막음'(중계의 토큰을 지워야 해서). 초대 만들기와 같다
                        if busy { HStack { ProgressView(); Text("끊고 있어요") } }
                        if let error { Text(error).font(.footnote).foregroundStyle(DesignSystem.danger) }
                    } header: { SectionHeader("연결") }
                }
                Section {
                    NavigationLink(CareText.about) {
                        // U28·U63: 판, 오픈소스 라이선스(링크되는 라이브러리마다 이름·라이선스, 누르면 전문. 안드로이드와 같은 구조).
                        List {
                            Text(CareText.version(AppInfo.version))
                            Section {
                                ForEach(OssEntry.all) { e in
                                    NavigationLink { LicenseView(entry: e) } label: {
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(e.name)
                                            Text(e.licenses.map(\.name).joined(separator: " · ")).font(.footnote).foregroundStyle(DesignSystem.textSecondary)
                                        }
                                    }
                                }
                            } header: { SectionHeader(CareText.oss) }   // 제목 대비 4.5:1
                        }
                        .screenBackground()
                        .navigationTitle(CareText.about)
                    }
                }
            }
            .screenBackground()
            .navigationTitle("설정")
            .safeAreaInset(edge: .top) { StatusBands().padding(.horizontal, 16) }
            .onAppear { nickname = vm.state.care.childNickname ?? "" }
            .onDisappear { vm.setChildNickname(nickname) }   // 칸을 벗어나도 남긴다(글자마다 저장하지 않는다)
            .confirmationDialog("차와의 연결을 끊을까요?", isPresented: $confirmDisconnect, titleVisibility: .visible) {
                Button("연결 끊기", role: .destructive) {
                    busy = true; error = nil
                    Task { error = await vm.disconnect(); busy = false }
                }
            } message: {
                Text(CareText.disconnectKeepsCare)   // R9
            }
            .confirmationDialog(CareText.clearConfirm, isPresented: $confirmClear, titleVisibility: .visible) {
                Button("기록 지우기", role: .destructive) { vm.clearLocalRecords() }
            }
        }
    }
}

/// 한 라이브러리의 라이선스 전문(U28·U63, 안드로이드 LicenseScreen과 같다).
struct LicenseView: View {
    let entry: OssEntry

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(entry.artifact).font(.footnote).foregroundStyle(DesignSystem.textSecondary)
                ForEach(entry.licenses, id: \.file) { l in
                    Text(l.name).font(.headline)
                    Text(OssEntry.text(l.file) ?? "").font(.caption).textSelection(.enabled)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .screenBackground()
        .navigationTitle(entry.name)
    }
}
