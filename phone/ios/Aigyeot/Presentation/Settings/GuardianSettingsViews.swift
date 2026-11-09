import SwiftUI
import CoreImage.CIFilterBuiltins
import UniformTypeIdentifiers

/// 정보·라이선스의 한 줄(U28·U63): 앱에 링크되는 오픈소스와 그 라이선스. 누르면 `Licenses/`의 전문을 보인다.
struct OssEntry: Identifiable {
    let name: String, artifact: String, licenses: [(name: String, file: String)]
    var id: String { name }

    /// shortcut: 손으로 적은 목록이다(FirebaseMessaging과 그 의존, 빌드 산출물의 링크된 모듈로 확인, 앱에 넣은 서체·아이콘). project.yml의 패키지를 바꾸면 Package.resolved와 함께 고친다.
    static let all: [OssEntry] = [
        OssEntry(name: "Firebase iOS SDK", artifact: "firebase-ios-sdk 12.19.2 (FirebaseCore, FirebaseCoreInternal, FirebaseInstallations, FirebaseMessaging)",
                 licenses: [("Apache License 2.0", "Apache-2.0")]),
        OssEntry(name: "GoogleUtilities", artifact: "GoogleUtilities 8.1.5",
                 licenses: [("Apache License 2.0", "Apache-2.0"), ("MIT License (IsAppEncrypted)", "MIT-IsAppEncrypted")]),
        OssEntry(name: "GoogleDataTransport", artifact: "GoogleDataTransport 10.1.1", licenses: [("Apache License 2.0", "Apache-2.0")]),
        OssEntry(name: "Promises", artifact: "promises 2.4.1 (FBLPromises)", licenses: [("Apache License 2.0", "Apache-2.0")]),
        OssEntry(name: "nanopb", artifact: "nanopb 2.30910.1", licenses: [("zlib License", "Zlib-nanopb")]),
        OssEntry(name: "Pretendard", artifact: "Pretendard Variable 1.3.9 (서체)", licenses: [("SIL Open Font License 1.1", "OFL-Pretendard")]),
        OssEntry(name: "Lucide", artifact: "lucide-static 1.70.0 (선 아이콘 SVG)", licenses: [("ISC License", "ISC-Lucide")]),
    ]

    /// 전문. 앱 묶음에 없으면 nil(시험이 모든 파일이 있는지 본다).
    static func text(_ file: String) -> String? {
        Bundle.main.url(forResource: file, withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
    }
}

/// 공유 시트에 넘길 내보내기 파일. 공유할 때 임시 폴더에 `aigyeot-care-{YYYYMMDD}.csv`로 쓴다(SettingsViewModel.export).
struct CareExportFile: Transferable {
    let export: @MainActor () -> URL?
    struct NotWritten: Error {}
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .commaSeparatedText) { item in
            guard let url = await item.export() else { throw NotWritten() }
            return SentTransferredFile(url)
        }
    }
}

/// 보호자(설정 안의 한 줄로 연다): owner는 초대·목록, guardian은 인계 알림 받기.
struct GuardiansView: View {
    var body: some View {
        Scoped({ $0.guardians() }) { vm in
            ScrollView {
                VStack(spacing: 0) {
                    StatusBands(gap: 12)
                    if vm.ui.role == .owner { OwnerGuardians() } else { GuardianToggle() }
                }
                .screenPadding()
            }
            .screenBackground()
            .navigationTitle(CareText.guardians)
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct OwnerGuardians: View {
    @Environment(GuardiansViewModel.self) private var vm

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 12) {
                Text("다른 보호자를 초대하면 리포트와 인계 알림을 함께 받아요").typeStyle(.body).foregroundStyle(DesignSystem.textPrimary)
                Text("초대 코드는 24시간 동안, 한 번만 쓸 수 있어요.").font(DesignSystem.caption).foregroundStyle(DesignSystem.textTertiary)
                if let invite = vm.ui.invite { InviteCard(invite: invite) }
                if vm.ui.busy { HStack { ProgressView(); Text("초대 코드를 만들고 있어요").font(DesignSystem.body) } }
                if let error = vm.ui.error { Text(error).font(DesignSystem.caption).foregroundStyle(DesignSystem.danger) }
                Button { Task { await vm.makeInvite() } } label: { Label { Text("초대 만들기") } icon: { IconImage(.userPlus, size: 20) } }
                    .buttonStyle(.pill).disabled(vm.ui.busy || vm.ui.offline)
                if vm.ui.offline { Text("인터넷이 필요해요").font(DesignSystem.caption).foregroundStyle(DesignSystem.textTertiary) }
            }
            .block()
            VStack(alignment: .leading, spacing: 0) {
                BlockHeader(title: "연결된 휴대폰")
                if vm.ui.phones.isEmpty { Text("아직 없어요").typeStyle(.body).foregroundStyle(DesignSystem.textTertiary).padding(.bottom, 12) }
                ForEach(vm.ui.phones, id: \.deviceId) { d in
                    ListRow(icon: .smartphone, title: d.role == .owner ? "함께 타는 보호자" : "다른 보호자",
                            sub: [d.platform == .ios ? "iPhone" : "Android", d.deviceId == vm.ui.myDeviceId ? "이 휴대폰" : nil].compactMap { $0 }.joined(separator: " · ")) { EmptyView() }
                }
            }
            .block(padding: EdgeInsets(top: 22, leading: 20, bottom: 8, trailing: 20))
        }
        .task { await vm.refreshDevices() }
    }
}

/// 초대 코드, 링크로 보내기, QR(받는 사람은 연결 화면에서 QR을 찍어도 된다). QR 가운데에 앱 표지(시안: 실제 QR에 가운데 로고만 얹는다).
private struct InviteCard: View {
    @ScaledMetric(relativeTo: .title) private var codeSize: CGFloat = 30   // 대조할 코드도 동적 글자 크기를 따른다(8장)
    let invite: InviteResponse

    var body: some View {
        VStack(spacing: 12) {
            Text(invite.display).font(.system(size: codeSize, weight: .bold, design: .monospaced)).foregroundStyle(DesignSystem.textPrimary)
                .minimumScaleFactor(0.5).lineLimit(1)
            if let img = qr(invite.link) {
                Image(uiImage: img).interpolation(.none).resizable().frame(width: 168, height: 168)
                    .overlay { AppMark(size: 34).padding(3).background(.white, in: RoundedRectangle(cornerRadius: 11, style: .continuous)) }
                    .padding(10).background(.white, in: RoundedRectangle(cornerRadius: DesignSystem.rM, style: .continuous))
                    .accessibilityElement().accessibilityLabel("초대 QR")
            }
            if let url = URL(string: invite.link) {
                ShareLink(item: url, message: Text("아이곁 초대 코드: \(invite.display)")) { Label { Text("링크로 보내기") } icon: { IconImage(.share, size: 20) } }
                    .buttonStyle(.pillTonal)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }

    private static let ciContext = CIContext()   // 다시 그릴 때마다 만들지 않는다(만드는 비용이 크다)

    /// 가운데 로고가 가리는 칸을 견디게 오류 정정 H(약 30%)로 만든다.
    private func qr(_ s: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8)
        f.correctionLevel = "H"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = Self.ciContext.createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

private struct GuardianToggle: View {
    @Environment(GuardiansViewModel.self) private var vm

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Group26 {
                SettingRow(icon: .bell, title: "인계 알림 받기") {
                    Toggle("인계 알림 받기", isOn: Binding(
                        get: { vm.ui.handoffAlerts },
                        set: { on in
                            Task {
                                if await vm.setHandoff(on), on { await NotificationAccess.request() }   // 켤 때 다시 묻는다(7.3)
                            }
                        }))
                    .labelsHidden().tint(DesignSystem.accent)
                    .disabled(vm.ui.busy)   // 요청이 겹치면 응답 순서에 따라 화면과 실제 설정이 달라진다(요청 중은 뷰모델에, S17)
                }
                .accessibilityElement(children: .combine)
            }
            if let error = vm.ui.error { FootNote(error).foregroundStyle(DesignSystem.danger) }
            FootNote("아기가 계속 울어 차가 운전자에게 쉼터를 제안하거나 세우도록 안내하면 알려 드려요. 리포트는 알림 설정과 상관없이 받아요.")
        }
    }
}

/// 설정(5장 순서, 시안 S.settings 묶음): [보호자 · 아이 별명] [잠·기저귀도 기록하기] [내 기록 내보내기 · 다른 앱 기록 가져오기(U64)]
/// [연결 끊기 · 이 휴대폰의 기록 지우기] [정보·라이선스].
struct SettingsView: View {
    @Environment(SettingsViewModel.self) private var vm
    @State private var confirmDisconnect = false
    @State private var confirmClear = false
    @State private var editingNickname = false
    @State private var nickname = ""

    private var busy: Bool { vm.ui.disconnect == .running }
    private var error: String? { if case .failed(let m) = vm.ui.disconnect { m } else { nil } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    StatusBands(gap: 12).padding(.horizontal, 12)
                    VStack(spacing: 12) {
                        Group26 {
                            if vm.ui.connected {
                                NavigationLink { GuardiansView() } label: {
                                    SettingRow(icon: .users, title: CareText.guardians, sub: vm.ui.phones > 0 ? "\(vm.ui.phones)명" : nil)
                                }
                                .buttonStyle(.plain)
                            }
                            Button { nickname = vm.ui.care.childNickname ?? ""; editingNickname = true } label: {
                                SettingRow(icon: .baby, title: CareText.nickname, sub: vm.ui.care.childNickname ?? CareText.nicknamePlaceholder)
                            }
                            .buttonStyle(.plain)
                        }
                        Group26 {
                            SettingRow(icon: .moon, title: CareText.sleepDiaperToggle, sub: CareText.sleepDiaperToggleNote) {
                                Toggle(CareText.sleepDiaperToggle, isOn: Binding(get: { vm.ui.care.sleepDiaperEnabled }, set: { vm.setSleepDiaper($0) }))
                                    .labelsHidden().tint(DesignSystem.accent)
                            }
                            .accessibilityElement(children: .combine)
                        }
                        Group26 {
                            // C7·U29: 누를 때 CSV를 만들어 공유 시트로 넘긴다. U33: 공유 시트 제목은 비운다
                            ShareLink(item: CareExportFile(export: { [vm] in vm.export() }), preview: SharePreview("")) {
                                SettingRow(icon: .download, title: CareText.export)
                            }
                            .buttonStyle(.plain)
                            // U64: 내보내기 바로 아래. owner·guardian 모두.
                            Button { vm.startImport() } label: { SettingRow(icon: .fileUp, title: CareText.importEntry, sub: CareText.importEntrySub) }
                                .buttonStyle(.plain)
                        }
                        Group26 {
                            if vm.ui.connected {
                                // 7.2 설정 '연결 끊기 막음'(중계의 토큰을 지워야 해서 인터넷이 필요하다). 초대 만들기와 같다
                                Button { confirmDisconnect = true } label: {
                                    SettingRow(icon: .unlink, title: CareText.disconnect,
                                               sub: busy ? "끊고 있어요" : vm.ui.offline ? "인터넷이 필요해요" : nil, tone: DesignSystem.textPrimary)
                                }
                                .buttonStyle(.plain)
                                .disabled(busy || vm.ui.offline)
                            }
                            Button { confirmClear = true } label: {
                                SettingRow(icon: .trash, title: CareText.clear, tone: DesignSystem.danger) { EmptyView() }
                            }
                            .buttonStyle(.plain)
                        }
                        if let error { FootNote(error).foregroundStyle(DesignSystem.danger) }
                        Group26 {
                            NavigationLink { AboutView(version: vm.ui.version) } label: { SettingRow(icon: .info, title: CareText.about) }
                                .buttonStyle(.plain)
                        }
                    }
                    .screenPadding()
                }
            }
            .screenBackground()
            .navigationTitle(CareText.tabSettings)
            .modifier(ImportFlow())
            .onDisappear { vm.dismissDisconnectError() }   // 설정을 떠나면 지난 끊기 실패 글을 걷는다(안드로이드와 같다)
            .alert(CareText.nickname, isPresented: $editingNickname) {
                TextField(CareText.nicknamePlaceholder, text: $nickname)
                Button(CareText.save) { vm.setNickname(nickname) }
                Button(CareText.cancel, role: .cancel) {}
            }
            .confirmationDialog("차와의 연결을 끊을까요?", isPresented: $confirmDisconnect, titleVisibility: .visible) {
                Button("연결 끊기", role: .destructive) { vm.startDisconnect() }
            } message: {
                Text(CareText.disconnectKeepsCare)   // R9
            }
            .confirmationDialog(CareText.clearConfirm, isPresented: $confirmClear, titleVisibility: .visible) {
                Button("기록 지우기", role: .destructive) { vm.clearRecords() }
            }
        }
    }
}

/// 정보·라이선스(U28·U63): 판, 오픈소스 라이선스(링크되는 라이브러리·서체·아이콘마다 이름·라이선스, 누르면 전문. 안드로이드와 같은 구조).
private struct AboutView: View {
    let version: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(CareText.version(version)).typeStyle(.body).foregroundStyle(DesignSystem.textSecondary).padding(.horizontal, 8)
                VStack(alignment: .leading, spacing: 0) {
                    BlockHeader(title: CareText.oss)
                    ForEach(OssEntry.all) { e in
                        NavigationLink { LicenseView(entry: e) } label: {
                            ListRow(icon: .info, title: e.name, sub: e.licenses.map(\.name).joined(separator: " · "))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .block(padding: EdgeInsets(top: 22, leading: 20, bottom: 8, trailing: 20))
            }
            .screenPadding()
        }
        .screenBackground()
        .navigationTitle(CareText.about)
        .navigationBarTitleDisplayMode(.inline)
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
