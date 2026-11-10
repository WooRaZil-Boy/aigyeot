import SwiftUI

private typealias DS = DesignSystem

/// 무엇을 시트로 띄울지. 새 기록은 종류, 고치기·타이머는 기록 id.
enum CareSheet: Identifiable {
    case quick(CareKind, day: String? = nil)   // day: 기록 탭에서 고른 오늘 아닌 날
    case edit(String)
    case timer(String)
    var id: String {
        switch self { case .quick(let k, let d): "q-\(k.rawValue)-\(d ?? "")"; case .edit(let i): "e-\(i)"; case .timer(let i): "t-\(i)" }
    }
}

/// 오늘(C1, 5장, 시안 S.today). 큰 제목 → (guardian) 인계 알림 → 진행 중 카드 → 마지막 수유 → 빠른 기록 → 오늘 하루 → 차 카드 → 최근 리포트.
/// guardian은 빠른 기록도 쓴다(U26). 홈에 떠 있는 버튼은 없다(v5: 지난 기록은 빠른 기록 길게 누르기·기록 탭 끝 행).
struct TodayView: View {
    @Environment(TodayViewModel.self) private var vm
    /// 탭 옮기기(오늘 하루 '기록', 차 카드, 최근 리포트 '전체').
    let goTab: (String) -> Void
    @State private var sheet: CareSheet?
    @Namespace private var zoom
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    StatusBands(gap: 12)   // 띠가 없으면 높이 0이고 간격도 없다
                    VStack(alignment: .leading, spacing: 12) {
                        if vm.ui.guardian { GuardianAlertCards().enterRise(0) }
                        // 경과는 분 단위라 1분마다 다시 그린다(R15: 저장하지 않고 계산). 진행 중 카드의 초는 그 카드만 1초마다.
                        TimelineView(.everyMinute) { ctx in
                            let now = CareTime.ms(ctx.date)
                            VStack(alignment: .leading, spacing: 12) {
                                ForEach([CareKind.feed, .sleep], id: \.self) { k in
                                    if let e = CareRules.inProgress(k, in: vm.ui.care.events) {
                                        RunningCard(event: e) { sheet = .timer(e.id) }
                                            .modifier(ZoomSource(id: e.id, ns: zoom))
                                            .transition(.cardEnter)
                                            .enterRise(0)
                                    }
                                }
                                LastFeedCard(now: now).enterRise(1)
                                QuickBlock(sheet: $sheet).enterRise(2)
                                VStack(alignment: .leading, spacing: 0) {
                                    BlockHeader(title: CareText.todayDay, link: CareText.tabLog) { goTab(CareText.tabLog) }
                                    let today = CareRules.dayKey(now, .current)
                                    DayBandChart(data: ChartData.day(care: vm.ui.care, reports: vm.ui.phone.reports, day: today, now: now, zone: .current, showCar: vm.ui.isPaired))
                                }
                                .block()
                                .enterRise(3)
                            }
                            // 진행 중 판이 생기거나 사라질 때 아래 블록이 함께 움직인다(시안 v4-7 6 기록 순간, spring.smooth)
                            .animation(reduceMotion ? nil : DS.Motion.smooth, value: CareRules.inProgress(.feed, in: vm.ui.care.events)?.id)
                            .animation(reduceMotion ? nil : DS.Motion.smooth, value: CareRules.inProgress(.sleep, in: vm.ui.care.events)?.id)
                        }
                        // U35: guardian은 기기 목록을 받을 수 없어(계약 1.3) 차 카드를 그리지 않는다.
                        if !vm.ui.guardian {
                            Group {
                                if vm.ui.isPaired { CarTile(car: vm.ui.phone.car, offline: !vm.ui.online) { goTab(CareText.tabCar) } }
                                else { ConnectCarTile() }
                            }
                            .enterRise(4)
                        }
                        RecentReports(owner: !vm.ui.guardian) { goTab(CareText.tabCar) }.enterRise(5)
                    }
                }
                .screenPadding()
            }
            .screenBackground()
            .navigationTitle(CareText.todayTitle(nickname: vm.ui.care.childNickname))   // U34
            .refreshable { await vm.retryNow() }
            .sheet(item: $sheet) { s in CareSheetView(sheet: s).modifier(ZoomDestination(id: s.timerId, ns: zoom)) }
        }
    }
}

extension CareSheet {
    var timerId: String? { if case .timer(let i) = self { i } else { nil } }
}

/// 진행 중 카드 → 타이머(공유 요소, iOS 18+ 확대 전환). 17은 보통 시트.
private struct ZoomSource: ViewModifier {
    let id: String
    let ns: Namespace.ID
    func body(content: Content) -> some View {
        if #available(iOS 18, *) { content.matchedTransitionSource(id: id, in: ns) } else { content }
    }
}

private struct ZoomDestination: ViewModifier {
    let id: String?
    let ns: Namespace.ID
    func body(content: Content) -> some View {
        if #available(iOS 18, *), let id { content.navigationTransition(.zoom(sourceID: id, in: ns)) } else { content }
    }
}

/// 마지막 수유(시안 .hero-c): 이름 → 큰 경과 → 시각 · 쪽 → 간격 막대 → (owner) 다음 수유 시간대.
/// 없으면 "아직 수유 기록이 없어요" + '수유 시작'.
private struct LastFeedCard: View {
    @Environment(TodayViewModel.self) private var vm
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let now: Int64
    /// 새 수유가 들어오면 판 둘레로 파란 빛이 한 번 퍼진다(시안 .flash 1.2초 motion.base 곡선). 늘면 한 번 돈다.
    @State private var flashes = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(CareText.lastFeed).font(DS.font(14, 500)).foregroundStyle(DS.textTertiary)
            if let last = vm.ui.lastFeedingStart {
                // 화면 값이 내 기록이면 쪽·보내기 상태를 함께 보인다. 차 값이 더 늦으면 시각만.
                let mine = CareRules.latestFeed(vm.ui.care.events).flatMap { vm.ui.shownStart($0) == last ? $0 : nil }
                let ago = CareText.ago(now - CareTime.ms(last))
                Text(ago)
                    .font(DS.hero).monospacedDigit().foregroundStyle(DS.textPrimary)
                    .lineLimit(1).minimumScaleFactor(0.6).padding(.top, 6)
                    .contentTransition(.numericText())
                    .animation(reduceMotion ? nil : DS.Motion.roll, value: ago)   // 바뀐 자리만 굴러간다(roll)
                Text(CareText.feedDetail(at: last, side: mine?.feedSide, zone: mine?.timeZone ?? .current))
                    .font(DS.font(15, 400)).monospacedDigit().foregroundStyle(DS.textTertiary).padding(.top, 4)
                if let m = vm.ui.feedMeter(last: CareTime.ms(last), now: now) { FeedMeter(value: m.value, tick: m.tick).padding(.top, 18).padding(.bottom, 4) }
                if vm.ui.owner, let e = mine, let r = vm.ui.feedingRecord(for: e) {
                    Text(feedStatusText(r)).font(DS.caption)
                        .foregroundStyle(r.status == .failed || r.status == .expired ? DS.danger : DS.textTertiary).padding(.top, 8)
                }
                if !vm.ui.guardian, let w = vm.ui.phone.feeding.windowStartExpected {   // U35: guardian은 차 값을 받지 않는다
                    VStack(alignment: .leading, spacing: 2) {
                        Text(CareText.nextWindow(w)).font(DS.font(15, 600)).foregroundStyle(DS.textPrimary)
                        Text(CareText.nextWindowNote).font(DS.font(13, 400)).foregroundStyle(DS.textTertiary)
                    }
                    .padding(.top, 14)
                }
            } else {
                Text(CareText.noFeedYet).typeStyle(.body).foregroundStyle(DS.textSecondary).padding(.top, 8)
                Button { vm.start(.feed) } label: { Text(CareText.startFeed) }
                    .buttonStyle(.pill).padding(.top, 14)
            }
        }
        .block(padding: EdgeInsets(top: 22, leading: 22, bottom: 20, trailing: 22))
        .keyframeAnimator(initialValue: 1.0, trigger: flashes) { card, p in
            // p 0 → 1: 빛 두께 0 → 14, 진하기 60% → 0(box-shadow 퍼짐과 같은 꼴). 쉬는 동안은 1이라 보이지 않는다
            card.overlay {
                if !reduceMotion {   // 도는 중에 동작 줄이기를 켜면 바로 걷는다
                    RoundedRectangle(cornerRadius: DS.rL + 14 * p, style: .continuous)
                        .strokeBorder(DS.accent.opacity(0.6 * (1 - p)), lineWidth: 14 * p)
                        .padding(-14 * p)
                        .allowsHitTesting(false)
                }
            }
        } keyframes: { _ in
            MoveKeyframe(0)
            LinearKeyframe(1, duration: 1.2, timingCurve: .bezier(startControlPoint: UnitPoint(x: 0.4, y: 0), endControlPoint: UnitPoint(x: 0.2, y: 1)))
        }
        .onChange(of: vm.ui.lastFeedingStart) { old, new in
            if new != nil, new != old, !reduceMotion { flashes += 1 }
        }
        .accessibilityElement(children: .contain)
    }
}

/// 먹색 진행 막대 + 다음 수유 시간대 눈금(아이 신호 주황, 시안 .pmeter).
private struct FeedMeter: View {
    let value: Double
    let tick: Double?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(DS.surfaceRaised).frame(height: 6)
                Capsule().fill(DS.textPrimary).frame(width: g.size.width * value, height: 6)
                if let tick {
                    RoundedRectangle(cornerRadius: 1.5).fill(DS.child).frame(width: 3, height: 18).offset(x: g.size.width * tick - 1.5)
                }
            }
            .frame(height: 6)
        }
        .frame(height: 6)
        .animation(reduceMotion ? nil : DS.Motion.smooth, value: value)
        .accessibilityHidden(true)
    }
}

/// 진행 중 수유·잠(시안 .run-c): 살아 있는 점 + "수유 중 · 왼쪽" + "오후 2:01부터" + 경과 시계. 누르면 타이머.
private struct RunningCard: View {
    let event: CareEvent
    let open: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: open) {
            HStack(spacing: 14) {
                dot.frame(width: 12)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font(DS.font(16, 600)).foregroundStyle(DS.textPrimary)
                    Text(CareFormat.clock(CareTime.date(event.startAt), event.timeZone) + "부터").font(DS.font(13, 400)).foregroundStyle(DS.textTertiary)
                }
                Spacer(minLength: 8)
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    Text(CareFormat.timer(CareRules.elapsedMs(event, now: CareTime.ms(ctx.date))))
                        .font(DS.font(28, 700)).monospacedDigit().foregroundStyle(DS.textPrimary)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 18)
            .frame(maxWidth: .infinity)
            .background(DS.surface, in: RoundedRectangle(cornerRadius: DS.rL, style: .continuous))
        }
        .buttonStyle(.press)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(a11y)
        .accessibilityAddTraits(.isButton)
    }

    /// 살아 있는 점(시안 live: 1.8초마다, 앞 60% 동안 둘레 빛이 0 → 12로 퍼지며 사라지고 나머지는 쉼, motion.base 곡선).
    /// 동작 줄이기면 빛 없이 점만(도중에 켜도 반복이 멈춘다).
    @ViewBuilder private var dot: some View {
        let c = DS.color(event.kind)
        let core = Circle().fill(c).frame(width: 10, height: 10)
        if reduceMotion { core } else {
            core.keyframeAnimator(initialValue: 0.0, repeating: true) { v, p in
                v.overlay { Circle().strokeBorder(c.opacity(0.5 * (1 - p)), lineWidth: 12 * p).padding(-12 * p) }
            } keyframes: { _ in
                MoveKeyframe(0)   // 되풀이는 앞 주기 끝값(1)에서 이어지므로 주기마다 0에서 시작한다
                LinearKeyframe(1, duration: 1.08, timingCurve: .bezier(startControlPoint: UnitPoint(x: 0.4, y: 0), endControlPoint: UnitPoint(x: 0.2, y: 1)))
                LinearKeyframe(1, duration: 0.72)
            }
        }
    }

    /// 화면 제목 "수유 중 · 왼쪽"(design.label) / "자는 중".
    private var title: String {
        event.kind == .sleep ? CareText.sleepRunning : ([CareText.feedRunning] + [event.feedSide?.label].compactMap { $0 }).joined(separator: " · ")
    }

    /// 읽어 주기는 7.1 today.running 문장 그대로.
    private var a11y: String {
        let now = CareTime.ms(Date())
        return event.kind == .sleep ? CareText.sleepingNow(event, now: now) : CareText.feedingNow(event, now: now)
    }
}

/// 빠른 기록(R16, 시안 .qblock): 흰 블록 안 회색 원(56) 넷, 이름 14/600. 수유·잠은 누르면 시작하고, 진행 중이면 먹색 원 + "수유 중"·"자는 중"으로
/// 바뀌어 타이머를 연다. 길게 누르면 지난 시각으로 적는 시트(today.quick.past).
private struct QuickBlock: View {
    @Environment(TodayViewModel.self) private var vm
    @Binding var sheet: CareSheet?
    @State private var started = 0
    /// 누르고 있는 칸(시안 .qb:active: 원 0.94배 spring.snappy, 바탕 조금 짙게 motion.fast).
    @State private var pressed: CareKind?
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let kinds = CareRules.quickKinds(sleepDiaper: vm.ui.care.sleepDiaperEnabled)
        // 큰 글자에서는 두 줄로 나눠 글자가 쪼개지지 않게 한다.
        let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: typeSize.isAccessibilitySize ? 2 : kinds.count)
        LazyVGrid(columns: columns, spacing: 14) {
            ForEach(kinds, id: \.self) { kind in
                let running = CareRules.inProgress(kind, in: vm.ui.care.events)
                let title = running == nil ? CareText.quickLabel(kind) : kind == .sleep ? CareText.sleepRunning : CareText.feedRunning
                // Button + 동시 길게 누르기는 손을 뗄 때 누름도 함께 돈다(지난 기록 시트와 시작이 겹친다). 누름·길게 누르기를 한 뷰에서 갈라 받는다
                VStack(spacing: 10) {
                    IconImage(DS.icon(kind), size: 24)
                        .foregroundStyle(running == nil ? DS.textPrimary : DS.surface)
                        .frame(width: 56, height: 56)
                        .background(running != nil ? DS.textPrimary : pressed == kind ? DS.tonal : DS.surfaceRaised, in: Circle())
                        .animation(reduceMotion ? nil : DS.Motion.fast, value: running == nil)   // 진행 중이면 원이 반전된다
                        .animation(reduceMotion ? nil : DS.Motion.fast, value: pressed == kind)
                        .scaleEffect(pressed == kind && !reduceMotion ? 0.94 : 1)
                        .animation(DS.Motion.snappy, value: pressed == kind)
                    Text(title).font(DS.font(14, 600)).foregroundStyle(running == nil ? DS.textSecondary : DS.textPrimary)
                        .lineLimit(1).minimumScaleFactor(0.8)
                }
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
                .onTapGesture { tap(kind, running: running) }
                .onLongPressGesture(minimumDuration: 0.5) { sheet = .quick(kind) } onPressingChanged: { pressed = $0 ? kind : nil }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(title)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { tap(kind, running: running) }
                .accessibilityAction(named: CareText.quickPast) { sheet = .quick(kind) }
            }
        }
        .sensoryFeedback(.success, trigger: started)
        .block(padding: EdgeInsets(top: 18, leading: 8, bottom: 18, trailing: 8))
    }

    private func tap(_ kind: CareKind, running: CareEvent?) {
        if let running { sheet = .timer(running.id); return }
        switch kind {
        case .feed, .sleep: vm.start(kind); started += 1
        case .note, .diaper: sheet = .quick(kind)
        }
    }
}

/// guardian 오늘 맨 위 인계 알림(시안 .alert-c): 배지 "차에서 · {경과}" + 차가 만든 문장. 누르면 상세.
private struct GuardianAlertCards: View {
    @Environment(TodayViewModel.self) private var vm
    @Environment(AppViewModel.self) private var app

    var body: some View {
        // '몇 분 전'은 화면을 연 채 두어도 매분 바뀐다(9장 '{n}분 전').
        TimelineView(.everyMinute) { ctx in
            VStack(spacing: 12) {
                ForEach(vm.ui.phone.alerts.prefix(5)) { a in
                    NavigationLink { AlertDetailView(id: a.id).onAppear { app.userOpenedAlert(a.id) } } label: {   // VoiceOver·키보드로 열어도 대기하던 알림 열기를 버린다
                        VStack(alignment: .leading, spacing: 10) {
                            Badge(text: CareText.fromCar + " · " + ago(a.body.occurredAt, now: ctx.date), icon: .carFront, child: true)
                            Text(a.body.text).font(DS.font(17, 600)).lineSpacing(8).foregroundStyle(DS.textPrimary)
                                .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(18)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(DS.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                    }
                    .buttonStyle(.press)
                    .simultaneousGesture(TapGesture().onEnded { app.userOpenedAlert(a.id) })   // 직접 고른 알림: 대기하던 다른 알림 열기는 버린다
                }
            }
        }
    }
}

/// 차 카드(owner, 시안 .car-tile): 3D 차(정차 실시간, 손가락으로 돌림) + "● 연결됨" + 마지막 연락. 아래 줄을 누르면 차 탭.
struct CarTile: View {
    /// 기기 목록의 차(owner만 받는다).
    let car: DeviceInfo?
    let offline: Bool
    var phones: Int? = nil
    var stageHeight: CGFloat = 150
    var open: (() -> Void)? = nil

    /// 차 카드 글(7.2). 마지막 연락은 U20 경과(1분 미만 '방금', 그 밖 '5분 전'·'1시간 전'·'1시간 15분 전', 연결 계약 5.6·L100).
    func text(now: Date) -> String {
        if offline { return "알 수 없음" }
        guard let car else { return "차를 기다리고 있어요" }
        if car.online { return "연결됨" }
        guard let last = car.lastSeenAt.flatMap(Rfc3339.parse) else { return "차 꺼짐" }
        return "차 꺼짐 · 마지막 연락 " + CareText.ago(CareTime.ms(now) - CareTime.ms(last))
    }

    var body: some View {
        VStack(spacing: 0) {
            CarStageCard(preset: .tile).frame(height: stageHeight)
            row
        }
        .background(DS.surface, in: RoundedRectangle(cornerRadius: DS.rL, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: DS.rL, style: .continuous))
    }

    @ViewBuilder private var row: some View {
        let content = HStack(alignment: .bottom, spacing: 10) {
            TimelineView(.everyMinute) { ctx in   // 경과는 화면을 연 채 두어도 바뀐다(7.2)
                let t = text(now: ctx.date)
                HStack(spacing: 6) {
                    if car?.online == true, !offline { Circle().fill(DS.active).frame(width: 8, height: 8) }
                    Text(t).font(DS.font(16, 600)).monospacedDigit().foregroundStyle(DS.textPrimary)
                }
            }
            Spacer(minLength: 8)
            if let phones { HStack(spacing: 4) { IconImage(.smartphone, size: 16); Text("\(phones)대").font(DS.font(14, 500)) }.foregroundStyle(DS.textTertiary) }
            if open != nil { Chevron() }
        }
        .padding(.horizontal, 20).padding(.top, 4).padding(.bottom, 18)
        .frame(minHeight: 44)
        if let open {
            Button(action: open) { content.contentShape(Rectangle()) }.buttonStyle(.plain)
                .accessibilityHint(CareText.tabCar)
        } else { content }
    }
}

/// 연결 안 됨의 차 카드(7장): 안내 한 줄 + '차와 연결하기'.
struct ConnectCarTile: View {
    @Environment(AppViewModel.self) private var root

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                IconImage(.carFront, size: 20).foregroundStyle(DS.textSecondary).frame(width: 40, height: 40).background(DS.surfaceRaised, in: Circle())
                Text(CareText.carTileUnpaired).typeStyle(.body).foregroundStyle(DS.textPrimary)
            }
            Button { root.showConnect() } label: { Label { Text(CareText.connectCar) } icon: { IconImage(.qrCode, size: 20) } }
                .buttonStyle(.pill)
        }
        .block()
    }
}

/// 최근 리포트(시안 '최근 리포트' 블록). owner는 머리 '전체' → 차 탭, guardian은 머리를 누르면 리포트 목록(안드로이드와 같다).
private struct RecentReports: View {
    @Environment(TodayViewModel.self) private var vm
    let owner: Bool
    let openCarTab: () -> Void

    var body: some View {
        let reports = vm.ui.phone.reports
        if !owner && vm.ui.phone.alerts.isEmpty && reports.isEmpty {
            FootNote("아직 받은 리포트·인계 알림이 없어요")   // 계약 7.2
        }
        if !reports.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                if owner {
                    BlockHeader(title: CareText.recentReports, link: CareText.all) { openCarTab() }
                } else {
                    HStack {
                        Text(CareText.recentReports).typeStyle(.title).foregroundStyle(DS.textPrimary).accessibilityAddTraits(.isHeader)
                        Spacer(minLength: 8)
                        NavigationLink { ReportListView() } label: {
                            HStack(spacing: 2) { Text(CareText.all).font(DS.font(14, 500)); IconImage(.chevronRight, size: 16) }
                                .foregroundStyle(DS.textTertiary).frame(minHeight: 44).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.bottom, 4)
                }
                ForEach(reports.prefix(3)) { r in   // 받을 때 시작 시각 늦은 순으로 둔다(InboxRules)
                    NavigationLink { ReportDetailView(sessionId: r.body.sessionId) } label: { ReportRow(report: r) }.buttonStyle(.plain)
                }
            }
            .block(padding: EdgeInsets(top: 22, leading: 20, bottom: 8, trailing: 20))
        }
    }
}
