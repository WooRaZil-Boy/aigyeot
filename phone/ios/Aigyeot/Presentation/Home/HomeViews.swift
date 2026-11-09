import SwiftUI

private typealias DS = DesignSystem

/// 탭(phone-care-plan 5장, 시안 nav): owner·연결 안 됨: 오늘·기록·차·설정, guardian: 오늘·기록·설정.
/// iOS 26은 시스템 글래스 탭 바(아래로 내리면 작아짐), 그 아래 판은 시스템 탭 바. 아이콘은 Lucide.
struct MainView: View {
    @Environment(AppViewModel.self) private var app
    @Environment(CareTabsViewModel.self) private var tabs
    @State private var tab = CareText.tabToday

    var body: some View {
        TabView(selection: $tab) {
            Scoped({ $0.today() }) { _ in TodayView { tab = $0 } }.undoBar().tabItem { tabLabel(CareText.tabToday, .house) }.tag(CareText.tabToday)
            Scoped({ $0.records() }) { _ in RecordsView() }.undoBar().tabItem { tabLabel(CareText.tabLog, .list) }.tag(CareText.tabLog)
            if !tabs.ui.guardian {
                Scoped({ $0.car() }) { _ in CarView() }.undoBar().tabItem { tabLabel(CareText.tabCar, .carFront) }.tag(CareText.tabCar)
            }
            Scoped({ $0.settings() }) { _ in SettingsView() }.undoBar().tabItem { tabLabel(CareText.tabSettings, .settings) }.tag(CareText.tabSettings)
        }
        .tint(DS.textPrimary)   // 탭 막대 고른 칸은 먹색(시안 .tabbar button.on). 탭 안 내용은 다시 파랑 글자(accentInk)
        .modifier(MinimizeTabBar())
        // U25: 끊기면 연결 안 됨의 오늘로. 5장: 연결 흐름이 끝나도 오늘로(링크는 확인 시트만 띄워 이 화면과 탭이 그대로 남는다. 안드로이드는 nav.reset)
        .onChange(of: app.ui.isPaired) { _, _ in tab = CareText.tabToday }
        // M3: 설정 끊기가 끝나면 오늘·띠를 한 번 그렸다고 알린다(끝남 표시를 걷는다)
        .onChange(of: tabs.ui.disconnectDone, initial: true) { _, done in
            if done { tab = CareText.tabToday; tabs.disconnectShown() }
        }
        .overlay(alignment: .top) { AlertBanner().padding(.horizontal, 8) }
        .sheet(item: Binding(get: { app.ui.openAlert.map(IdBox.init) }, set: { _ in app.closeAlert() })) { box in
            NavigationStack { AlertDetailView(id: box.id) }
        }
    }

    private func tabLabel(_ title: String, _ icon: Ico) -> some View {
        Label { Text(title) } icon: { Image(ico: icon) }
    }
}

/// iOS 26: 아래로 내리면 탭 바가 작아진다(tabBarMinimizeBehavior, 시안 '탭 바 작아짐').
private struct MinimizeTabBar: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26, *) { content.tabBarMinimizeBehavior(.onScrollDown) } else { content }
    }
}

struct IdBox: Identifiable { let id: String }

private extension View {
    /// 되돌리기 띠는 모든 탭에 같은 것 하나다(안드로이드 CareTabs의 SnackbarHost): 다른 탭으로 옮겨도 남은 시간 동안 누를 수 있다.
    func undoBar() -> some View { overlay(alignment: .bottom) { UndoBar() }.tint(DesignSystem.accentInk) }
}

/// 앱 아이콘 그림(흰 바탕 + 파랑 초승달 + 주황 점) 칸: 흰 면 위에서도 경계가 보이게 얇은 테(검정 10%).
struct AppMark: View {
    var size: CGFloat = 38
    var body: some View {
        Image("brand-mark").resizable().scaledToFit()
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: size * 0.27, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: size * 0.27, style: .continuous).strokeBorder(.black.opacity(0.1), lineWidth: 1))
            .accessibilityHidden(true)
    }
}

/// 앞에 있을 때 푸시(`SyncInbox.onPush`)·실시간 수신이 띄운 인계 알림 배너(5.6, 시안 .banner .ncard). 시스템 알림은 띄우지 않는다.
struct AlertBanner: View {
    @Environment(AppViewModel.self) private var vm
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if let id = vm.ui.banner {
                HStack(alignment: .top, spacing: 0) {
                    Button {
                        Task { await vm.openBanner(id) }   // 본문이 없으면 받은 뒤 연다(이 연결에 없는 알림이면 빈 상세에 머물지 않는다)
                    } label: {
                        HStack(alignment: .top, spacing: 12) {
                            AppMark(size: 38)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(CareText.introTitle).font(DS.font(14.5, 700))
                                Text(vm.ui.bannerText ?? "").font(DS.font(14.5, 500)).multilineTextAlignment(.leading).lineLimit(3)   // 큰 글자에서 화면을 덮지 않게. 전체는 눌러서 본다
                            }
                            Spacer(minLength: 0)
                        }
                        .padding([.vertical, .leading], 14).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("handoffBanner")   // 목록의 같은 문장과 구별(투어)
                    // 닫기는 열기 버튼 밖의 형제다(안에 두면 닫기 누름이 바깥 열기와 겹칠 수 있다)
                    Button { vm.dismissBanner() } label: { IconImage(.x, size: 18).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }   // 터치 영역 44pt(8장)
                        .buttonStyle(.plain)
                        .accessibilityLabel(CareText.bannerClose)
                        .padding(.trailing, 4).padding(.top, 4)
                }
                .foregroundStyle(DS.textPrimary)
                .glass(in: RoundedRectangle(cornerRadius: 26, style: .continuous))
                .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                .task(id: id) {
                    try? await Task.sleep(nanoseconds: RetryPolicy.bannerNanos)
                    vm.dismissBanner(id)
                }
            }
        }
        .animation(reduceMotion ? nil : DS.Motion.bouncy, value: vm.ui.banner)
    }
}

/// 위쪽 띠: 오프라인, 시계 차이, 알림 꺼짐, 안내(7.2). 옅은 띠 위 버튼 글자는 먹색 굵게(4.5:1).
struct StatusBands: View {
    @Environment(StatusViewModel.self) private var vm
    @State private var notificationsDenied = false
    @Environment(\.scenePhase) private var scenePhase
    /// 오늘 탭은 화면 스크롤 안에 둔다. 나머지 탭은 고정 영역(safeAreaInset)이다.
    var inScroll = false

    var body: some View {
        Group {
            // 고정 영역: 띠 높이만큼만 차지하고(없으면 0), 큰 글자로 200pt를 넘으면 묶음째 그 안에서 굴린다(8장 동적 글자 크기).
            // frame(maxHeight:)만 두면 제안 높이가 커서 띠가 없어도 늘 200pt 빈칸이 생겼다.
            if inScroll { bands } else {
                ScrollView { bands }
                    .scrollBounceBehavior(.basedOnSize)
                    .frame(maxHeight: 200)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("statusBands")
            }
        }
        .task { notificationsDenied = await NotificationAccess.isDenied() }
        // 설정에서 돌아오면 다시 확인
        .onChange(of: scenePhase) { _, p in
            if p == .active { Task { notificationsDenied = await NotificationAccess.isDenied() } }
        }
        // 권한 창에 답하면 다시 확인(창이 닫혀도 scenePhase는 바뀌지 않았다)
        .onReceive(NotificationCenter.default.publisher(for: NotificationAccess.answered)) { _ in
            Task { notificationsDenied = await NotificationAccess.isDenied() }
        }
    }

    private var bands: some View {
        VStack(spacing: 8) {
            if vm.ui.showsOfflineBand {
                band(.wifiOff, "오프라인이에요. 누른 것은 연결되면 차로 가요.", tone: DS.warning) {
                    // 7.2 '그 밖: 띠에 다시 시도'. 받은 함 429 기다림 중이면 갱신이 알아서 미룬다
                    bandButton("다시 시도") { Task { await vm.retryNow() } }
                }
            }
            if let w = vm.ui.clockWarning { band(.clockAlert, w, tone: DS.warning) { EmptyView() } }
            if vm.ui.guardian, notificationsDenied {
                band(.bellOff, "알림이 꺼져 있어 인계 알림을 못 받아요", tone: DS.warning) {
                    bandButton("설정 열기") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                }
            }
            if let n = vm.ui.notice {
                // U48: 닫기는 글자 버튼(두 앱 같은 글자)
                band(.info, n, tone: DS.accentInk) { bandButton(CareText.bannerClose) { vm.dismissNotice() } }
            }
        }
    }

    /// 띠 한 줄: 흰 면 + 왼쪽 뜻 아이콘(뜻 색) + 글(먹색) + 버튼.
    private func band<B: View>(_ icon: Ico, _ text: String, tone: Color, @ViewBuilder button: () -> B) -> some View {
        HStack(spacing: 10) {
            IconImage(icon, size: 18).foregroundStyle(tone)
            Text(text).font(DS.font(14, 500)).foregroundStyle(DS.textPrimary).padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
            button()
        }
        .padding(.horizontal, 14)
        .background(DS.surface, in: RoundedRectangle(cornerRadius: DS.rM, style: .continuous))
    }

    /// 터치 영역 44pt(8장): 글자 버튼 틀을 키우고 띠의 위아래 여백은 글에만 준다.
    private func bandButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(title).font(DS.font(14, 700)).foregroundStyle(DS.textPrimary).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }
            .buttonStyle(.plain)
    }
}

/// 차로 보내기 상태: 보내는 중 → 보냄 08:12 → 차에 반영됨(계약 7.5, 7장 '지금 문구 그대로').
func feedStatusText(_ r: FeedingRecord) -> String {
    switch r.status {
    case .sending: return "보내는 중"
    case .sent: return "보냄 \(r.sentAt.map { CareFormat.clock($0) } ?? "")"
    case .reflected: return "차에 반영됨"
    case .expired: return "7일이 지나 보내지 못했어요"
    case .failed: return "차로 보내지 못했어요"
    }
}

/// 인계 알림의 '~전'. 오늘 카드와 같은 U20 형식이다("2시간 10분 전").
func ago(_ rfc: String, now: Date = Date()) -> String {
    guard let d = Rfc3339.parse(rfc) else { return "" }
    return CareText.ago(CareTime.ms(now) - CareTime.ms(d))
}

/// 인계 알림 상세(guardian). 알림 문장은 차가 만든 그대로 보인다.
struct AlertDetailView: View {
    let id: String

    var body: some View {
        Scoped({ [id] in $0.alert(id: id) }) { vm in AlertDetailContent(vm: vm) }
    }
}

private struct AlertDetailContent: View {
    let vm: AlertViewModel

    var body: some View {
        if let a = vm.alert {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 10) {
                        Badge(text: CareText.fromCar, icon: .carFront, child: true)
                        Text(a.body.text).font(DS.font(19, 600)).lineSpacing(8).foregroundStyle(DS.textPrimary)
                    }
                    .block()
                    VStack(alignment: .leading, spacing: 0) {
                        detailRow("시각", Rfc3339.parse(a.body.occurredAt).map { CareFormat.dateTime($0) } ?? a.body.occurredAt)
                        if let rest = a.body.restName { detailRow("쉼터", rest) }
                        if let m = a.body.minutesToArrival { detailRow("도착까지", "\(m)분") }
                    }
                    .block()
                }
                .screenPadding()
            }
            .screenBackground()
            .navigationTitle("인계 알림")
            .navigationBarTitleDisplayMode(.inline)
        } else {
            ContentUnavailableView("알림을 불러오고 있어요", image: Ico.bell.assetName)
        }
    }

    private func detailRow(_ k: String, _ v: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(k).font(DS.font(15, 400)).foregroundStyle(DS.textTertiary)
            Spacer(minLength: 12)
            Text(v).font(DS.font(15, 600)).foregroundStyle(DS.textPrimary).multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }
}
