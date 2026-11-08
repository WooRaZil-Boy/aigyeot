import SwiftUI

/// 탭(phone-care-plan 5장). owner·연결 안 됨: 오늘·기록·차·설정, guardian: 오늘·기록·설정.
struct MainView: View {
    @Environment(AppViewModel.self) private var app
    @Environment(CareTabsViewModel.self) private var tabs
    @State private var tab = CareText.tabToday

    var body: some View {
        TabView(selection: $tab) {
            Scoped({ $0.today() }) { _ in TodayView() }.tabItem { Label(CareText.tabToday, systemImage: "sun.max") }.tag(CareText.tabToday)
            Scoped({ $0.records() }) { _ in RecordsView() }.tabItem { Label(CareText.tabLog, systemImage: "list.bullet.rectangle") }.tag(CareText.tabLog)
            if !tabs.ui.guardian {
                Scoped({ $0.car() }) { _ in CarView() }.tabItem { Label(CareText.tabCar, systemImage: "car") }.tag(CareText.tabCar)
            }
            Scoped({ $0.settings() }) { _ in SettingsView() }.tabItem { Label(CareText.tabSettings, systemImage: "gearshape") }.tag(CareText.tabSettings)
        }
        // U25: 끊기면 연결 안 됨의 오늘로. 5장: 연결 흐름이 끝나도 오늘로(링크는 확인 시트만 띄워 이 화면과 탭이 그대로 남는다. 안드로이드는 nav.reset)
        .onChange(of: app.ui.isPaired) { _, _ in tab = CareText.tabToday }
        // M3: 설정 끊기가 끝나면 오늘·띠를 한 번 그렸다고 알린다(끝남 표시를 걷는다)
        .onChange(of: tabs.ui.disconnectDone, initial: true) { _, done in
            if done { tab = CareText.tabToday; tabs.disconnectShown() }
        }
        .overlay(alignment: .top) { AlertBanner().padding(.horizontal, 12) }
        .sheet(item: Binding(get: { app.ui.openAlert.map(IdBox.init) }, set: { _ in app.closeAlert() })) { box in
            NavigationStack { AlertDetailView(id: box.id) }
        }
    }
}

struct IdBox: Identifiable { let id: String }

/// 앞에 있을 때 푸시(`SyncInbox.onPush`)·실시간 수신이 띄운 인계 알림 배너(5.6). 시스템 알림은 띄우지 않는다.
struct AlertBanner: View {
    @Environment(AppViewModel.self) private var vm

    var body: some View {
        if let id = vm.ui.banner {
            HStack(spacing: 0) {
                Button {
                    Task { await vm.openBanner(id) }   // 본문이 없으면 받은 뒤 연다(이 연결에 없는 알림이면 빈 상세에 머물지 않는다)
                } label: {
                    HStack {
                        Image(systemName: "bell.badge.fill")
                        Text(vm.ui.bannerText ?? "").multilineTextAlignment(.leading).lineLimit(3)   // 큰 글자에서 화면을 덮지 않게. 전체는 눌러서 본다
                        Spacer(minLength: 0)
                    }
                    .padding([.vertical, .leading]).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("handoffBanner")   // 목록의 같은 문장과 구별(투어)
                // 닫기는 열기 버튼 밖의 형제다(안에 두면 닫기 누름이 바깥 열기와 겹칠 수 있다)
                Button { vm.dismissBanner() } label: { Image(systemName: "xmark").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }   // 터치 영역 44pt(8장)
                    .buttonStyle(.plain)
                    .accessibilityLabel(CareText.bannerClose)
                    .padding(.trailing, DesignSystem.s2)
            }
            .foregroundStyle(DesignSystem.onAccent)   // 어둠에서 흰 글자는 accent 위 대비 미달(13장)
            .background(DesignSystem.accent, in: RoundedRectangle(cornerRadius: DesignSystem.rMedium))
            .task(id: id) {
                try? await Task.sleep(nanoseconds: RetryPolicy.bannerNanos)
                vm.dismissBanner(id)
            }
        }
    }
}

/// 위쪽 띠: 오프라인, 시계 차이, 알림 꺼짐, 안내(7.2).
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
                HStack {
                    Label("오프라인이에요. 누른 것은 연결되면 차로 가요.", systemImage: "wifi.slash").padding(.vertical, 10)
                    Spacer()
                    // 7.2 '그 밖: 띠에 다시 시도'. 받은 함 429 기다림 중이면 갱신이 알아서 미룬다
                    Button { Task { await vm.retryNow() } } label: { Text("다시 시도").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }
                }
                .font(DesignSystem.caption).padding(.horizontal, 10)
                .background(DesignSystem.warning.opacity(0.15), in: RoundedRectangle(cornerRadius: DesignSystem.rSmall))
            }
            if let w = vm.ui.clockWarning { band(w, "clock.badge.exclamationmark", DesignSystem.warning) }
            if vm.ui.guardian, notificationsDenied {
                HStack {
                    Image(systemName: "bell.slash")
                    Text("알림이 꺼져 있어 인계 알림을 못 받아요").padding(.vertical, 10)
                    Spacer()
                    Button { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) } label: {
                        Text("설정 열기").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())   // 터치 영역 44pt(8장). 위아래 여백은 글에만
                    }
                }
                .font(DesignSystem.caption).padding(.horizontal, 10)
                .background(DesignSystem.warning.opacity(0.15), in: RoundedRectangle(cornerRadius: DesignSystem.rSmall))
            }
            if let n = vm.ui.notice {
                // U48: 닫기는 글자 버튼(두 앱 같은 글자)
                // 터치 영역 44pt(8장): 글자 버튼 틀을 키우고 띠의 위아래 여백은 글에만 준다
                HStack {
                    Text(n).padding(.vertical, 10)
                    Spacer()
                    Button { vm.dismissNotice() } label: { Text(CareText.bannerClose).bold().frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }
                }
                    .font(DesignSystem.caption).padding(.horizontal, 10)
                    .background(DesignSystem.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: DesignSystem.rSmall))
            }
        }
    }

    private func band(_ text: String, _ icon: String, _ color: Color) -> some View {
        Label(text, systemImage: icon).font(DesignSystem.caption)
            .padding(10).frame(maxWidth: .infinity, alignment: .leading)
            .background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: DesignSystem.rSmall))
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

struct CarTile: View {
    /// 기기 목록의 차(owner만 받는다).
    let car: DeviceInfo?
    let offline: Bool

    private func text(now: Date) -> String {
        if offline { return "알 수 없음" }
        guard let car else { return "차를 기다리고 있어요" }
        if car.online { return "연결됨" }
        guard let last = car.lastSeenAt.flatMap(Rfc3339.parse) else { return "차 꺼짐" }
        return "차 꺼짐 · 마지막 연락 \(max(0, Int(now.timeIntervalSince(last) / 60)))분 전"
    }

    var body: some View {
        HStack {
            Image(systemName: "car.fill")
            TimelineView(.everyMinute) { ctx in Text(text(now: ctx.date)).font(.headline).monospacedDigit() }   // '{n}분 전'은 화면을 연 채 두어도 바뀐다(7.2)
            Spacer()
        }
        .padding(14)
        .background(DesignSystem.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: DesignSystem.rMedium))
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
            List {
                Section { Text(a.body.text).font(.title3) }
                Section {
                    LabeledContent("시각", value: Rfc3339.parse(a.body.occurredAt).map { CareFormat.dateTime($0) } ?? a.body.occurredAt)
                    if let rest = a.body.restName { LabeledContent("쉼터", value: rest) }
                    if let m = a.body.minutesToArrival { LabeledContent("도착까지", value: "\(m)분") }
                } header: { SectionHeader("자세히") }
            }
            .screenBackground()
            .labeledContentStyle(TokenLabeledContentStyle())   // 값 글자 대비 4.5:1(8장)
            .navigationTitle("인계 알림")
        } else {
            ContentUnavailableView("알림을 불러오고 있어요", systemImage: "bell")
        }
    }
}
