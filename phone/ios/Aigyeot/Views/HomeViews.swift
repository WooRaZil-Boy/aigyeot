import SwiftUI

/// 연결된 뒤의 탭. 우리 노래는 owner만 보인다(7.1).
struct MainView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView {
            HomeView().tabItem { Label("홈", systemImage: "house") }
            if model.role == .owner {
                SongsView().tabItem { Label("우리 노래", systemImage: "music.note") }
            }
            ReportsView().tabItem { Label("리포트", systemImage: "list.bullet.rectangle") }
            GuardiansView().tabItem { Label("보호자", systemImage: "person.2") }
            SettingsView().tabItem { Label("설정", systemImage: "gearshape") }
        }
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
                .background(Theme.accent, in: RoundedRectangle(cornerRadius: 14))
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
                    .background(Theme.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
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

struct HomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    StatusBands()
                    if model.role == .owner { OwnerHome() } else { GuardianHome() }
                }
                .padding(16)
            }
            .navigationTitle("아이곁")
            .refreshable { await model.refresh() }
        }
    }
}

private func timeText(_ d: Date) -> String { d.formatted(date: .omitted, time: .shortened) }

struct OwnerHome: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        CarTile()
        HStack(alignment: .top, spacing: 12) {
            BigValue(title: "마지막 수유 시작", value: model.lastFeedingStart.map(timeText) ?? "-")
            BigValue(title: "다음 수유 시간대 예상", value: model.state.feeding.windowStartExpected.map(timeText) ?? "-")
        }
        if model.lastFeedingStart == nil {
            Text("아직 수유 기록이 없어요").foregroundStyle(.secondary)
        }
        Button { model.startFeeding() } label: {
            Text("수유 시작").font(.title2.bold()).frame(maxWidth: .infinity, minHeight: 64)
        }
        .buttonStyle(.borderedProminent)

        if !model.state.feedingLog.isEmpty {
            Text("최근 기록").font(.headline).padding(.top, 8)
            ForEach(model.state.feedingLog.prefix(8)) { r in
                HStack {
                    Text(timeText(r.fixedAt ?? r.at))   // 중계가 고쳐 저장한 시각을 보인다(3.3)
                    Spacer()
                    Text(status(r)).font(.footnote).foregroundStyle(.secondary)
                }
                Divider()
            }
        }
    }

    /// 보내는 중 → 보냄 08:12 → 차에 반영됨(7.5).
    private func status(_ r: FeedingRecord) -> String {
        switch r.status {
        case .sending: return "보내는 중"
        case .sent: return "보냄 \(r.sentAt.map(timeText) ?? "")"
        case .reflected: return "차에 반영됨"
        case .expired: return "7일이 지나 보내지 못했어요"
        case .failed: return "차로 보내지 못했어요"
        }
    }
}

struct BigValue: View {
    let title: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.footnote).foregroundStyle(.secondary)
            Text(value).font(.system(size: 34, weight: .bold, design: .rounded))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 14))
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
        .background(Theme.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
    }
}

struct GuardianHome: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.state.alerts.isEmpty && model.state.reports.isEmpty {
            Text("아직 받은 리포트·인계 알림이 없어요").foregroundStyle(.secondary)
        }
        if !model.state.alerts.isEmpty {
            Text("인계 알림").font(.headline)
            ForEach(model.state.alerts.prefix(5)) { a in
                NavigationLink { AlertDetailView(id: a.id) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(a.body.text).foregroundStyle(.primary).multilineTextAlignment(.leading)
                        Text(ago(a.body.occurredAt)).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .simultaneousGesture(TapGesture().onEnded { model.userOpenedAlert(a.id) })   // 직접 고른 알림: 대기하던 다른 알림 열기는 버린다
                Divider()
            }
        }
        if !model.state.reports.isEmpty {
            Text("최근 리포트").font(.headline).padding(.top, 8)
            ForEach(model.state.reports.prefix(3)) { r in
                NavigationLink { ReportDetailView(report: r) } label: { ReportRow(report: r) }
                Divider()
            }
        }
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
