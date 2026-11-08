import SwiftUI

/// 화면 범위 뷰모델을 만드는 공장(1.7). 합성 루트(AppContainer)가 채운다. 앱 범위 뷰모델(App·Status·CareTabs)은 합성 루트가 들고
/// `.environment`로 넘기고, 나머지는 화면이 나타날 때 이 공장으로 만들어 그 화면의 `@State`가 든다(안드로이드 내비게이션 항목 범위).
@MainActor
protocol ViewModelFactory: AnyObject {
    func connect() -> ConnectViewModel
    func today() -> TodayViewModel
    func timer(id: String) -> TimerViewModel
    func careSheet() -> CareSheetViewModel
    func records() -> RecordsViewModel
    func car() -> CarViewModel
    func songs() -> SongsViewModel
    func recorder() -> RecorderViewModel
    func songPlayer() -> SongPlayer
    func reports() -> ReportsViewModel
    func reportDetail(sessionId: String) -> ReportDetailViewModel
    func alert(id: String) -> AlertViewModel
    func settings() -> SettingsViewModel
    func guardians() -> GuardiansViewModel
}

extension EnvironmentValues {
    @Entry var viewModels: (any ViewModelFactory)? = nil
}

/// 화면이 처음 나타날 때 공장으로 뷰모델을 하나 만들어 들고(`@State`), 안쪽 화면에 `.environment`로 넘긴다.
/// 화면이 다시 그려져도 같은 뷰모델이고, 화면이 사라지면 함께 사라진다.
/// 첫 그림부터 안쪽 화면을 그린다(빈 틀을 한 번 그리면 시트 높이·제목이 흔들린다). 만든 뷰모델은 나타날 때 쥔다.
struct Scoped<VM: AnyObject & Observable, Content: View>: View {
    let make: @MainActor (any ViewModelFactory) -> VM
    @ViewBuilder let content: (VM) -> Content
    @Environment(\.viewModels) private var factory
    @State private var vm: VM?

    init(_ make: @escaping @MainActor (any ViewModelFactory) -> VM, @ViewBuilder content: @escaping (VM) -> Content) {
        self.make = make
        self.content = content
    }

    var body: some View {
        if let current = vm ?? factory.map(make) {
            content(current).environment(current).onAppear { if vm == nil { vm = current } }
        }
    }
}
