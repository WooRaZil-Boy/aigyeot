import SwiftUI

/// 탭(phone-care-plan 5장). owner·연결 안 됨: 오늘·기록·차·설정, guardian: 오늘·기록·설정.
struct MainView: View {
    @Environment(AppModel.self) private var model
    @State private var tab = CareText.tabToday

    var body: some View {
        @Bindable var model = model
        TabView(selection: $tab) {
            TodayView().tabItem { Label(CareText.tabToday, systemImage: "sun.max") }.tag(CareText.tabToday)
            RecordsView().tabItem { Label(CareText.tabLog, systemImage: "list.bullet.rectangle") }.tag(CareText.tabLog)
            if model.role != .guardian {
                CarView().tabItem { Label(CareText.tabCar, systemImage: "car") }.tag(CareText.tabCar)
            }
            SettingsView().tabItem { Label(CareText.tabSettings, systemImage: "gearshape") }.tag(CareText.tabSettings)
        }
        .onChange(of: model.isPaired) { _, paired in if !paired { tab = CareText.tabToday } }   // U25: 끊기면 연결 안 됨의 오늘로
        .overlay(alignment: .top) { AlertBanner().padding(.horizontal, 12) }
        .sheet(item: Binding(get: { model.openAlertId.map(IdBox.init) }, set: { _ in model.openAlertId = nil })) { box in
            NavigationStack { AlertDetailView(id: box.id) }
        }
    }
}

struct IdBox: Identifiable { let id: String }

/// 앞에 있을 때 `show(msg_id)`가 띄운 인계 알림 배너(5.6). 시스템 알림은 띄우지 않는다.
struct AlertBanner: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let id = model.bannerAlertId {
            Button {
                model.userOpenedAlert(id)   // 사용자가 직접 고른 알림: 대기하던 다른 알림 열기는 버린다
                model.bannerAlertId = nil
                model.openAlertId = id
            } label: {
                HStack {
                    Image(systemName: "bell.badge.fill")
                    Text(model.alert(id)?.body.text ?? "인계 알림이 왔어요").multilineTextAlignment(.leading)
                    Spacer()
                    Button { model.bannerAlertId = nil } label: { Image(systemName: "xmark") }
                }
                .padding().foregroundStyle(.white)
                .background(DesignSystem.accent, in: RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)
            .task(id: id) {
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                if model.bannerAlertId == id { model.bannerAlertId = nil }
            }
        }
    }
}

/// 위쪽 띠: 오프라인, 시계 차이, 알림 꺼짐, 안내(7.2).
struct StatusBands: View {
    @Environment(AppModel.self) private var model
    @State private var notificationsDenied = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(spacing: 8) {
            if model.offline { band("오프라인이에요. 누른 것은 연결되면 차로 가요.", "wifi.slash", .orange) }
            if let w = model.clockWarning { band(w, "clock.badge.exclamationmark", .orange) }
            if model.role == .guardian, notificationsDenied {
                HStack {
                    Image(systemName: "bell.slash")
                    Text("알림이 꺼져 있어 인계 알림을 못 받아요")
                    Spacer()
                    Button("설정 열기") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                }
                .font(.footnote).padding(10)
                .background(.orange.opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
            }
            if let n = model.notice {
                HStack { Text(n); Spacer(); Button { model.notice = nil } label: { Image(systemName: "xmark") } }
                    .font(.footnote).padding(10)
                    .background(DesignSystem.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
            }
        }
        .task { notificationsDenied = await NotificationAccess.isDenied() }
        // 설정에서 돌아오거나 권한 요청 창이 닫히면 다시 확인
        .onChange(of: scenePhase) { _, p in
            if p == .active { Task { notificationsDenied = await NotificationAccess.isDenied() } }
        }
    }

    private func band(_ text: String, _ icon: String, _ color: Color) -> some View {
        Label(text, systemImage: icon).font(.footnote)
            .padding(10).frame(maxWidth: .infinity, alignment: .leading)
            .background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
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
    @Environment(AppModel.self) private var model

    private var text: String {
        if model.offline { return "알 수 없음" }
        guard let car = model.car else { return "차를 기다리고 있어요" }
        if car.online { return "연결됨" }
        guard let last = car.lastSeenAt.flatMap(Rfc3339.parse) else { return "차 꺼짐" }
        return "차 꺼짐 · 마지막 연락 \(max(0, Int(Date().timeIntervalSince(last) / 60)))분 전"
    }

    var body: some View {
        HStack {
            Image(systemName: "car.fill")
            Text(text).font(.headline)
            Spacer()
        }
        .padding(14)
        .background(DesignSystem.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
    }
}

func ago(_ rfc: String) -> String {
    guard let d = Rfc3339.parse(rfc) else { return "" }
    let m = max(0, Int(Date().timeIntervalSince(d) / 60))
    return m < 1 ? "방금" : m < 60 ? "\(m)분 전" : "\(m / 60)시간 전"
}

/// 인계 알림 상세(guardian). 알림 문장은 차가 만든 그대로 보인다.
struct AlertDetailView: View {
    @Environment(AppModel.self) private var model
    let id: String

    var body: some View {
        if let a = model.alert(id) {
            List {
                Section { Text(a.body.text).font(.title3) }
                Section("자세히") {
                    LabeledContent("시각", value: Rfc3339.parse(a.body.occurredAt)?.formatted(date: .abbreviated, time: .shortened) ?? a.body.occurredAt)
                    if let rest = a.body.restName { LabeledContent("쉼터", value: rest) }
                    if let m = a.body.minutesToArrival { LabeledContent("도착까지", value: "\(m)분") }
                }
            }
            .navigationTitle("인계 알림")
        } else {
            ContentUnavailableView("알림을 불러오고 있어요", systemImage: "bell")
        }
    }
}
