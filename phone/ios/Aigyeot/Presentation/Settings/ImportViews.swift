import SwiftUI
import UniformTypeIdentifiers

private typealias DS = DesignSystem

/// 가져오기 시트 글(U68). 미리 보기: 가져올 수·종류별 수·건너뛴 이유(0은 감춤)·가장 늦은 수유·zoneNote, 메모 글은 보이지 않는다.
/// 가져올 것이 0이면 import.nothing과 '닫기'만(U68, 안드로이드와 같음). 끝내는 안내(unreadable·tooLarge·failed)도 같은 꼴: 글 + '닫기'(11-09 보충).
struct ImportPreview: Equatable {
    var title: String
    var kinds: String? = nil
    var skips: [String] = []
    var latestFeed: String? = nil
    var zoneNote: String? = nil
    var buttons = [CareText.bannerClose]

    init(_ d: ImportDraft, now: Date) {
        guard !d.rows.isEmpty else { title = CareText.importNothing; return }
        title = CareText.importTitle(d.rows.count)
        skips = CareText.importSkips(d.skipped)
        zoneNote = CareText.importZoneNote
        kinds = CareText.importKinds(d.rows)
        latestFeed = d.rows.filter { $0.kind == .feed }.map(\.startAt).max()
            .map { CareText.importLatestFeed(CareText.dayClock($0, now: now, zone: TimeZone(identifier: CareCsv.zone)!)) }
        buttons = [CareText.importConfirm, CareText.importCancel]
    }

    init(failure: String) { title = failure }
}

/// 설정의 가져오기 흐름: 파일 고르기 → (Nara 아이 둘 이상) 아이 고르기 → 미리 보기 → 저장. 단계는 `SettingsViewModel.importStep` 하나다.
/// 저장 뒤 띠(import.done + 되돌리기)는 모든 탭에 붙은 같은 되돌리기 띠(UndoBar, MainView)다.
struct ImportFlow: ViewModifier {
    @Environment(SettingsViewModel.self) private var vm


    func body(content: Content) -> some View {
        content
            .fileImporter(isPresented: shown { if case .picking = $0 { true } else { false } }, allowedContentTypes: [.commaSeparatedText, .plainText]) {
                vm.picked($0)
            }
            // 아이 고르기·미리 보기·끝내는 안내는 같은 시트 하나다(단계가 바뀌면 글만 바뀐다)
            .sheet(isPresented: shown { switch $0 { case .reading, .pickChild, .preview, .failed: true; default: false } }) { ImportSheet() }
    }

    /// 단계가 맞으면 보이고, 시스템이 닫으면(밀어 내리기·바깥 누르기) 그 단계만 닫는다.
    private func shown(_ match: @escaping (ImportStep) -> Bool) -> Binding<Bool> {
        Binding(get: { match(vm.importStep) }, set: { if !$0, match(vm.importStep) { vm.closeImport() } })
    }
}

/// 가져오기 시트: 글(과 아이 이름)은 스크롤하고(큰 글자·중간 높이에서도 잘리지 않게) 끝내는 버튼은 아래에 붙인다.
private struct ImportSheet: View {
    @Environment(SettingsViewModel.self) private var vm

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DS.s4) {
                switch vm.importStep {
                case .reading: ProgressView().frame(maxWidth: .infinity)   // 읽는 중(새 글자 없이 진행 표시만)
                case .pickChild(_, let profiles):
                    // 아이 이름은 글과 같이 스크롤한다(이름이 많거나 큰 글자여도 모두 누를 수 있게)
                    Text(CareText.importPickChild).font(DS.title)
                    ForEach(profiles, id: \.self) { name in button(name, prominent: false) { vm.pickChild(name) } }
                case .preview(let draft): PreviewText(preview: ImportPreview(draft, now: Date()))
                case .failed(let message): PreviewText(preview: ImportPreview(failure: message))
                default: EmptyView()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(DS.s5)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: DS.s3) {
                switch vm.importStep {
                case .pickChild: button(CareText.importCancel, prominent: false) { vm.closeImport() }
                case .preview(let draft):
                    ForEach(ImportPreview(draft, now: Date()).buttons, id: \.self) { label in
                        label == CareText.importConfirm ? button(label, prominent: true) { vm.confirmImport() } : button(label, prominent: false) { vm.closeImport() }
                    }
                case .failed: button(CareText.bannerClose, prominent: false) { vm.closeImport() }
                case .reading: button(CareText.importCancel, prominent: false) { vm.closeImport() }
                default: EmptyView()
                }
            }
            .padding(.horizontal, DS.s5)
            .padding(.vertical, DS.s4)
            .background(DS.bg)
        }
        .tint(DS.accent)
        .background(DS.bg)
        .presentationDetents([.medium, .large])
    }

    @ViewBuilder
    private func button(_ label: String, prominent: Bool, action: @escaping () -> Void) -> some View {
        let b = Button(action: action) { Text(label).frame(maxWidth: .infinity, minHeight: 44) }
        if prominent { b.buttonStyle(.borderedProminent) } else { b.buttonStyle(.bordered) }
    }
}

private struct PreviewText: View {
    let preview: ImportPreview

    var body: some View {
        Text(preview.title).font(DS.title)
        if let kinds = preview.kinds { Text(kinds).font(DS.body) }
        if let latest = preview.latestFeed { Text(latest).font(DS.body) }
        if !preview.skips.isEmpty {
            VStack(alignment: .leading, spacing: DS.s1) {
                Text(CareText.importSkipTitle).font(DS.headline)
                ForEach(preview.skips, id: \.self) { Text($0).font(DS.body).foregroundStyle(DS.textSecondary) }
            }
        }
        if let note = preview.zoneNote { Text(note).font(DS.caption).foregroundStyle(DS.textSecondary) }
    }
}
