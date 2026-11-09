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

/// 오늘(C1, 5장). owner·연결 안 됨: 상태 카드 → 빠른 버튼 → 다음 수유 시간대 → 차 타일.
/// guardian: 인계 알림 띠 → 상태 카드 → 빠른 버튼 → 다음 수유 시간대 → 최근 리포트(U26).
struct TodayView: View {
    @Environment(TodayViewModel.self) private var vm
    @State private var sheet: CareSheet?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: DS.s4) {
                    StatusBands(inScroll: true)
                    if vm.ui.guardian { GuardianAlertBands() }
                    // 경과는 분 단위라 1분마다 다시 그린다(R15: 저장하지 않고 계산).
                    TimelineView(.everyMinute) { ctx in
                        let now = CareTime.ms(ctx.date)
                        VStack(alignment: .leading, spacing: DS.s4) {
                            LastFeedCard(now: now)
                            if let f = CareRules.inProgress(.feed, in: vm.ui.care.events) {
                                RunningCard(text: CareText.feedingNow(f, now: now), kind: .feed) { sheet = .timer(f.id) }
                            }
                            if let s = CareRules.inProgress(.sleep, in: vm.ui.care.events) {
                                RunningCard(text: CareText.sleepingNow(s, now: now), kind: .sleep) { sheet = .timer(s.id) }
                            }
                        }
                    }
                    QuickButtons(sheet: $sheet)
                    if !vm.ui.guardian, let w = vm.ui.phone.feeding.windowStartExpected {   // U35: guardian은 차 값을 받지 않는다
                        VStack(alignment: .leading, spacing: DS.s1) {
                            Text(CareText.nextWindow(w)).font(DS.headline)
                            Text(CareText.nextWindowNote).font(DS.caption).foregroundStyle(DS.textSecondary)
                        }
                        .padding(DS.s4).frame(maxWidth: .infinity, alignment: .leading)
                        .background(DS.surface, in: RoundedRectangle(cornerRadius: DS.rM))
                    }
                    // U35: guardian은 기기 목록을 받을 수 없어(계약 1.3) 차 타일을 그리지 않는다.
                    if vm.ui.guardian { GuardianReports() }
                    else if vm.ui.isPaired { CarTile(car: vm.ui.phone.car, offline: !vm.ui.online) }
                    else { ConnectCarTile() }
                }
                .padding(DS.s4)
            }
            .screenBackground()
            .navigationTitle(CareText.todayTitle(nickname: vm.ui.care.childNickname))   // U34
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { sheet = .quick(.feed) } label: { Image(systemName: "plus") }.accessibilityLabel(CareText.quickPast)
                }
            }
            .refreshable { await vm.retryNow() }
            .sheet(item: $sheet) { CareSheetView(sheet: $0) }
        }
    }
}

/// 마지막 수유: 없으면 "아직 수유 기록이 없어요" + '수유 시작', 있으면 "2시간 10분 전"과 "오후 12:52 · 왼쪽"(R12).
private struct LastFeedCard: View {
    @Environment(TodayViewModel.self) private var vm
    let now: Int64

    var body: some View {
        VStack(alignment: .leading, spacing: DS.s2) {
            if let last = vm.ui.lastFeedingStart {
                // 화면 값이 내 기록이면 쪽·시간대·보내기 상태를 함께 보인다. 차 값이 더 늦으면 시각만.
                let mine = CareRules.latestFeed(vm.ui.care.events).flatMap { vm.ui.shownStart($0) == last ? $0 : nil }
                Text(CareText.lastFeed).font(DS.caption).foregroundStyle(DS.textSecondary)
                Text(CareText.ago(now - CareTime.ms(last))).font(DS.display).monospacedDigit().lineLimit(1).minimumScaleFactor(0.6)   // 경과는 고정폭 숫자(8장)
                Text(CareText.feedDetail(at: last, side: mine?.feedSide, zone: mine?.timeZone ?? .current))
                    .font(DS.body).foregroundStyle(DS.textSecondary)
                if vm.ui.owner, let e = mine, let r = vm.ui.feedingRecord(for: e) {
                    Text(feedStatusText(r)).font(DS.caption)
                        .foregroundStyle(r.status == .failed || r.status == .expired ? DS.danger : DS.textSecondary)
                }
            } else {
                Text(CareText.noFeedYet).font(DS.body).foregroundStyle(DS.textSecondary)
                Button { vm.start(.feed) } label: {
                    Text(CareText.startFeed).font(DS.title).frame(maxWidth: .infinity, minHeight: 56)
                }
                .accentButton()
            }
        }
        .padding(DS.s4).frame(maxWidth: .infinity, alignment: .leading)
        .background(DS.surface, in: RoundedRectangle(cornerRadius: DS.rM))
    }
}

/// 진행 중 수유·잠 카드. 누르면 타이머.
private struct RunningCard: View {
    let text: String
    let kind: CareKind
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: DS.s3) {
                Image(ico: DS.icon(kind)).foregroundStyle(DS.color(kind))
                Text(text).font(DS.headline).foregroundStyle(DS.textPrimary).monospacedDigit()
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(DS.textSecondary)
            }
            .padding(DS.s4).frame(minHeight: 56)
            .background(DS.color(kind).opacity(0.08), in: RoundedRectangle(cornerRadius: DS.rM))   // 15%에서는 종류 색 아이콘이 4.3:1(13장 4.5:1). 8%면 모든 종류가 넘는다
        }
        .buttonStyle(.plain)
    }
}

/// 빠른 버튼(R16): 수유·메모, 켜면 잠·기저귀. 수유·잠은 누르면 시작하고, 진행 중이면 "수유 중"·"자는 중"으로 바뀌어 타이머를 연다.
/// 길게 누르면 지난 시각으로 적는 시트.
private struct QuickButtons: View {
    @Environment(TodayViewModel.self) private var vm
    @Binding var sheet: CareSheet?
    @State private var started = 0
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let kinds = CareRules.quickKinds(sleepDiaper: vm.ui.care.sleepDiaperEnabled)
        // 큰 글자에서는 두 줄로 나눠 글자가 쪼개지지 않게 한다.
        let columns = Array(repeating: GridItem(.flexible(), spacing: DS.s3), count: typeSize.isAccessibilitySize ? 2 : kinds.count)
        LazyVGrid(columns: columns, spacing: DS.s3) {
            ForEach(kinds, id: \.self) { kind in
                let running = CareRules.inProgress(kind, in: vm.ui.care.events)
                let title = running == nil ? CareText.quickLabel(kind) : kind == .sleep ? CareText.sleepRunning : CareText.feedRunning
                // U51: 평소 surfaceRaised·아이콘 종류 색·글자 textPrimary, 진행 중 accent·onAccent. 종류 색을 바탕에 깔지 않는다.
                VStack(spacing: DS.s1) {
                    Image(ico: DS.icon(kind)).font(.title2).foregroundStyle(running == nil ? DS.color(kind) : DS.onAccent)
                    Text(title).font(DS.headline).foregroundStyle(running == nil ? DS.textPrimary : DS.onAccent)
                }
                .frame(maxWidth: .infinity, minHeight: 72)
                .background(running == nil ? DS.surfaceRaised : DS.accent, in: RoundedRectangle(cornerRadius: DS.rL))
                .contentShape(RoundedRectangle(cornerRadius: DS.rL))
                .onTapGesture { tap(kind, running: running) }
                .onLongPressGesture(minimumDuration: 0.5) { sheet = .quick(kind) }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(title)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { tap(kind, running: running) }
                .accessibilityAction(named: CareText.quickPast) { sheet = .quick(kind) }
            }
        }
        .sensoryFeedback(.success, trigger: started)
    }

    private func tap(_ kind: CareKind, running: CareEvent?) {
        if let running { sheet = .timer(running.id); return }
        switch kind {
        case .feed, .sleep: vm.start(kind); started += 1
        case .note, .diaper: sheet = .quick(kind)
        }
    }
}

/// 연결 안 됨의 차 타일(7장).
struct ConnectCarTile: View {
    @Environment(AppViewModel.self) private var root

    var body: some View {
        VStack(alignment: .leading, spacing: DS.s3) {
            Label(CareText.carTileUnpaired, systemImage: "car.fill").font(DS.body)
            Button(CareText.connectCar) { root.showConnect() }.accentButton().controlSize(.large)   // 옅은 바탕 위 .bordered는 대비 3.7:1(13장). large는 44pt 넘는다(8장)
        }
        .padding(DS.s4).frame(maxWidth: .infinity, alignment: .leading)
        .background(DS.cCar.opacity(0.12), in: RoundedRectangle(cornerRadius: DS.rM))
    }
}

/// guardian 오늘 맨 위 인계 알림 띠(있을 때). 글자는 차가 만든 문장과 지금 화면의 '몇 분 전'이다.
private struct GuardianAlertBands: View {
    @Environment(TodayViewModel.self) private var vm
    @Environment(AppViewModel.self) private var app

    var body: some View {
        // '몇 분 전'은 화면을 연 채 두어도 매분 바뀐다(9장 '{n}분 전').
        TimelineView(.everyMinute) { ctx in bands(now: ctx.date) }
    }

    private func bands(now: Date) -> some View {
        ForEach(vm.ui.phone.alerts.prefix(5)) { a in
            NavigationLink { AlertDetailView(id: a.id).onAppear { app.userOpenedAlert(a.id) } } label: {   // VoiceOver·키보드로 열어도 대기하던 알림 열기를 버린다
                HStack(alignment: .top, spacing: DS.s3) {
                    Image(systemName: "bell.badge.fill").foregroundStyle(DS.warning)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(a.body.text).foregroundStyle(DS.textPrimary).multilineTextAlignment(.leading)
                        Text(ago(a.body.occurredAt, now: now)).font(DS.caption).monospacedDigit().foregroundStyle(DS.textSecondary)
                    }
                    Spacer(minLength: 0)
                }
                .padding(DS.s3).frame(maxWidth: .infinity, alignment: .leading)
                .background(DS.warning.opacity(0.08), in: RoundedRectangle(cornerRadius: DS.rS))   // 경고 아이콘 대비 4.5:1(15%에서는 4.2:1)
            }
            .buttonStyle(.plain)
            .simultaneousGesture(TapGesture().onEnded { app.userOpenedAlert(a.id) })   // 직접 고른 알림: 대기하던 다른 알림 열기는 버린다
        }
    }
}

/// guardian 오늘의 최근 리포트 3개(5장). 머리를 누르면 리포트 목록(안드로이드와 같다).
private struct GuardianReports: View {
    @Environment(TodayViewModel.self) private var vm

    var body: some View {
        if vm.ui.phone.alerts.isEmpty && vm.ui.phone.reports.isEmpty {
            Text("아직 받은 리포트·인계 알림이 없어요").foregroundStyle(DS.textSecondary)   // 계약 7.2
        }
        if !vm.ui.phone.reports.isEmpty {
            NavigationLink { ReportListView() } label: {
                HStack(spacing: DS.s1) { Text(CareText.recentReports); Image(systemName: "chevron.right").font(DS.caption).accessibilityHidden(true) }.font(DS.headline)
                    .frame(minHeight: 44).contentShape(Rectangle())   // 터치 영역 44pt(8장)
            }
            .padding(.top, DS.s2)
            ForEach(vm.ui.phone.reports.prefix(3)) { r in   // 받을 때 시작 시각 늦은 순으로 둔다(InboxRules)
                NavigationLink { ReportDetailView(sessionId: r.body.sessionId) } label: { ReportRow(report: r) }
                Divider()
            }
        }
    }
}

// MARK: 시트

struct CareSheetView: View {
    let sheet: CareSheet

    var body: some View {
        switch sheet {
        case .quick(let kind, let day): Scoped({ $0.careSheet() }) { _ in CareEditSheet(original: nil, kind: kind, day: day) }
        case .edit(let id):
            Scoped({ $0.careSheet() }) { vm in
                if let e = vm.care.events.first(where: { $0.id == id }) { CareEditSheet(original: e, kind: e.kind) }
            }
        case .timer(let id): Scoped({ $0.timer(id: id) }) { _ in TimerView() }
        }
    }
}

/// 젖병 양 행: "양" + "{n}ml". 값이 없으면 + 로 10ml부터 넣는다.
private struct AmountRow: View {
    @Binding var amount: Int?

    var body: some View {
        Stepper {
            LabeledContent(CareText.rowAmount, value: amount.map(CareText.ml) ?? "").labeledContentStyle(TokenLabeledContentStyle())
        } onIncrement: {
            amount = min(CareRules.amountRange.upperBound, (amount ?? 0) + 10)
        } onDecrement: {
            amount = amount.map { max(CareRules.amountRange.lowerBound, $0 - 10) }
        }
    }
}

/// 타이머(C2): 큰 경과 · 끝내기 · 시작 시각 수정 · 수유 쪽 바꾸기(젖병이면 양) · 이 기록 지우기. 최대 길이를 넘으면 U14의 두 버튼.
struct TimerView: View {
    @Environment(TimerViewModel.self) private var vm
    @Environment(\.dismiss) private var dismiss
    @State private var editingStart = false
    @State private var pickingEnd = false
    @State private var picked = Date()
    @State private var error: String?
    @State private var overlapping: CareEvent?
    @State private var askOverlap = false

    private var event: CareEvent? { vm.ui.event }

    var body: some View {
        NavigationStack {
            if let e = event, e.inProgress {
                ScrollView {
                    VStack(spacing: DS.s5) {
                        TimelineView(.periodic(from: .now, by: 1)) { ctx in
                            let now = CareTime.ms(ctx.date)
                            VStack(spacing: DS.s4) {
                                Text(CareFormat.timer(CareRules.elapsedMs(e, now: now))).font(DS.timer).lineLimit(1).minimumScaleFactor(0.5)
                                    .accessibilityLabel(CareFormat.duration(CareRules.elapsedMs(e, now: now)))
                                if CareRules.isOverLimit(e, now: now) { overLimit(e) }
                                else {
                                    Button { vm.end(); dismiss() } label: {
                                        Text(CareText.end).font(DS.title).frame(maxWidth: .infinity, minHeight: 56)
                                    }
                                    .accentButton()
                                }
                            }
                        }
                        if e.kind == .feed { feedControls(e) }
                        Text(CareFormat.clock(CareTime.date(e.startAt), e.timeZone)).font(DS.body).foregroundStyle(DS.textSecondary)
                        Button { picked = CareTime.date(e.startAt); editingStart = true; pickingEnd = false } label: { Text(CareText.editStart).frame(minHeight: 44).contentShape(Rectangle()) }   // 44pt
                        if editingStart || pickingEnd { timePicker(e) }
                        if let error { Text(error).font(DS.caption).foregroundStyle(DS.danger) }
                        if vm.ui.guardian { Text(CareText.guardianNote).font(DS.caption).foregroundStyle(DS.textSecondary) }
                        Button(role: .destructive) { vm.delete(); dismiss() } label: { Text(CareText.deleteRecord).frame(minHeight: 44).contentShape(Rectangle()) }
                            .foregroundStyle(DS.danger)   // 시스템 빨강은 밝은 바탕에서 3.4:1(13장 danger). 44pt
                    }
                    .padding(DS.s5)
                }
                .screenBackground()
                .navigationTitle(CareText.quickLabel(e.kind))
                .navigationBarTitleDisplayMode(.inline)
                .alert(CareSaveResult.overlap.message(for: e.kind) ?? "", isPresented: $askOverlap, presenting: overlapping) { x in
                    Button(CareText.saveAnyway) { commitTime(x, allowOverlap: true) }
                    Button(CareText.fix, role: .cancel) {}
                }
            } else {
                Color.clear.onAppear { dismiss() }   // 끝났거나 지워졌다
            }
        }
        .presentationDetents([.large])
    }

    /// 최대 길이를 넘음: 안내 문장(U36) + U14 버튼 둘.
    @ViewBuilder private func overLimit(_ e: CareEvent) -> some View {
        Text(e.kind == .sleep ? CareText.overLimitSleep : CareText.overLimitFeed).font(DS.headline).multilineTextAlignment(.center)
        Button(CareText.pickEnd) { picked = Date(); pickingEnd = true; editingStart = false }
            .accentButton().controlSize(.large)
        Button(CareText.closeEndUnknown) { vm.endUnknown(); dismiss() }
            .neutralButton().controlSize(.large)
    }

    @ViewBuilder private func feedControls(_ e: CareEvent) -> some View {
        AdaptiveChoice(title: CareText.rowSide, options: FeedSide.allCases, label: { $0.label }, selection: Binding(get: { e.feedSide }, set: { side in
            var x = e
            x.feedSide = side
            if side != .bottle { x.amountMl = nil }
            show(vm.save(x, allowOverlap: true), kind: e.kind)
        }))
        if e.feedSide == .bottle {
            AmountRow(amount: Binding(get: { e.amountMl }, set: { n in
                var x = e
                x.amountMl = n
                show(vm.save(x, allowOverlap: true), kind: e.kind)
            }))
        }
    }

    private func timePicker(_ e: CareEvent) -> some View {
        VStack(spacing: DS.s3) {
            DatePicker(editingStart ? CareText.rowStart : CareText.rowEnd, selection: $picked, in: ...Date().addingTimeInterval(60))
                .environment(\.timeZone, e.timeZone)   // R13: 목록처럼 그 기록의 시간대로 보인다
            Button {
                var x = e
                if editingStart { x.startAt = CareTime.ms(picked) } else { x.endAt = CareTime.ms(picked) }
                commitTime(x, allowOverlap: false)
            } label: { Text(CareText.save).frame(minHeight: 44) }   // 44pt
            .neutralButton()
        }
    }

    /// 시각을 바꾸면 겹침을 먼저 묻는다(R4). 쪽·양은 시각을 바꾸지 않아 묻지 않는다.
    private func commitTime(_ x: CareEvent, allowOverlap: Bool) {
        let r = vm.save(x, allowOverlap: allowOverlap)
        if r == .overlap { overlapping = x; askOverlap = true; return }
        if show(r, kind: x.kind) {
            editingStart = false
            if pickingEnd { dismiss() }
        }
    }

    @discardableResult
    private func show(_ r: CareSaveResult, kind: CareKind) -> Bool {
        error = r == .notSaved ? NoticeRules.saveFailed : r.message(for: kind)   // 저장 실패는 시트 안에서도 보인다(띠의 다른 안내까지 옮기지 않게)
        return r == .saved
    }
}

/// 빠른 기록(C3)·고치기(C4) 시트. 새 수유·잠은 시작만 적고 끝은 '끝 시각 모름'이 기본이다.
struct CareEditSheet: View {
    @Environment(CareSheetViewModel.self) private var vm
    @Environment(\.dismiss) private var dismiss
    let original: CareEvent?
    let kind: CareKind
    var day: String? = nil

    @State private var chip: Int? = 0          // 몇 분 전(0 = 지금, nil = 직접)
    @State private var start = Date()
    @State private var end = Date()
    @State private var endUnknown = true
    @State private var side: FeedSide?
    @State private var amount: Int?
    @State private var diaper: DiaperKind?
    @State private var note = ""
    @State private var error: String?
    @State private var askOverlap = false
    @State private var loaded = false

    private var hasDuration: Bool { kind == .feed || kind == .sleep }
    /// R13: 고치는 기록은 그 기록의 시간대로 보인다(안드로이드 `zoneOrDevice`와 같다). 새 기록은 기기 시간대.
    private var zone: TimeZone { original?.timeZone ?? .current }
    /// 끝 칸이 자리 값이다: 새 기록이거나 끝 모름 기록을 고칠 때. 이때만 끝을 시작 뒤로 맞춘다(R3). 저장된 끝은 그대로 둔다
    private var endIsPlaceholder: Bool { original?.endAt == nil }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent(hasDuration ? CareText.rowStart : CareText.rowTime) {
                        if original == nil && day == nil { chips }   // 몇 분 전 칩은 오늘 기준이다
                    }
                    if original != nil || chip == nil {
                        DatePicker(hasDuration ? CareText.rowStart : CareText.rowTime, selection: $start, in: ...Date().addingTimeInterval(60))
                            .labelsHidden().environment(\.timeZone, zone)
                            .onChange(of: start) { _, s in if endIsPlaceholder { end = max(end, s) } }
                    }
                    if hasDuration {
                        Toggle(CareText.endUnknown, isOn: $endUnknown)
                            .onChange(of: endUnknown) { _, unknown in if !unknown, endIsPlaceholder { end = max(end, start) } }
                        if !endUnknown {
                            DatePicker(CareText.rowEnd, selection: $end, in: ...Date().addingTimeInterval(60))
                                .environment(\.timeZone, zone)
                        }
                    }
                }
                if kind == .feed {
                    Section {
                        LabeledContent(CareText.rowSide) {
                            AdaptiveChoice(title: CareText.rowSide, options: FeedSide.allCases, label: { $0.label }, selection: $side)
                        }
                        if side == .bottle { AmountRow(amount: $amount) }
                    }
                }
                if kind == .diaper {
                    Section {
                        LabeledContent(CareText.rowKind) {
                            AdaptiveChoice(title: CareText.rowKind, options: DiaperKind.allCases, label: { CareText.diaperLabel($0) }, selection: $diaper)
                        }
                    }
                }
                Section {
                    TextField(CareText.notePlaceholder, text: $note, axis: .vertical)
                        .accessibilityLabel(CareText.rowNote)
                        .onChange(of: note) { _, v in let l = CareRules.noteLimited(v); if l != v { note = l } }   // 200에서 더 받지 않는다(U17)
                } header: {
                    SectionHeader(CareText.rowNote)
                } footer: {
                    VStack(alignment: .leading, spacing: DS.s1) {
                        if let error { Text(error).foregroundStyle(DS.danger) }
                        if vm.guardian { SectionHeader(CareText.guardianNote) }
                    }
                }
                if let e = original {
                    Section {
                        Button(CareText.delete, role: .destructive) { vm.delete(e.id); dismiss() }.foregroundStyle(DS.danger)
                    }
                }
            }
            .screenBackground()
            .navigationTitle(CareText.sheetTitle(kind, editing: original != nil))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(CareText.cancel) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(CareText.save) { save(allowOverlap: false) }
                        .disabled(kind == .note && note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .alert(CareSaveResult.overlap.message(for: kind) ?? "", isPresented: $askOverlap) {
                Button(CareText.saveAnyway) { save(allowOverlap: true) }
                Button(CareText.fix, role: .cancel) {}
            }
            .onAppear(perform: load)
        }
        .presentationDetents([.medium, .large])
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: DS.s2) {
                chipButton(CareText.chipNow, 0)
                ForEach(CareText.chipMinutes, id: \.self) { chipButton(CareText.chip(minutes: $0), $0) }
                chipButton(CareText.chipCustom, nil)
            }
        }
    }

    private func chipButton(_ title: String, _ minutes: Int?) -> some View {
        Button {
            chip = minutes
            // 누른 때 기준으로 시작을 정한다(처음 '지금'은 시트를 연 때). 끝 기본값보다 늦어지지 않는다. '직접'은 지금 값에서 고른다.
            if let minutes { start = Date().addingTimeInterval(-Double(minutes * 60)); end = max(end, start) }   // 시트를 오래 열어 두어도 끝 ≥ 시작(R3)
        } label: { Text(title).frame(minHeight: 44) }   // 44pt(8장)
        .choiceButton(selected: chip == minutes)
        .accessibilityAddTraits(chip == minutes ? .isSelected : [])
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let e = original else {
            if kind == .diaper { diaper = .wet }   // 새 기저귀는 소변으로 시작한다(안드로이드 CareScreens와 같다. 종류 없이 저장하지 않게)
            // 기록 탭에서 다른 날을 골랐으면 그날 낮 12시에서 고른다(안드로이드와 같다).
            if let day, let noon = CareRules.noon(day, .current) { chip = nil; start = CareTime.date(noon); end = start }
            return
        }
        start = CareTime.date(e.startAt)
        if let x = e.endAt { end = CareTime.date(x); endUnknown = false } else { end = start }
        side = e.feedSide
        amount = e.amountMl
        diaper = e.diaper
        note = e.note ?? ""
    }

    private func save(allowOverlap: Bool) {
        let now = Date()
        let t = CareTime.ms(now)
        var e = original ?? CareEvent(id: ULID.generate(date: now), kind: kind, startAt: 0, zone: TimeZone.current.identifier,
                                      createdAt: t, updatedAt: t)
        e.startAt = CareTime.ms(start)
        if hasDuration {
            e.endAt = endUnknown ? nil : CareTime.ms(end)
            e.endUnknown = endUnknown
        }
        e.feedSide = kind == .feed ? side : nil
        e.amountMl = kind == .feed && side == .bottle ? amount : nil
        e.diaper = kind == .diaper ? diaper : nil
        let n = note.trimmingCharacters(in: .whitespacesAndNewlines)
        e.note = n.isEmpty ? nil : n
        switch vm.save(e, allowOverlap: allowOverlap, editing: original != nil) {
        case .saved, .gone: dismiss()   // 그사이 지워졌으면 되살리지 않고 닫는다(R10)
        case .overlap: askOverlap = true
        case .failed(let m): error = m   // 저장 실패는 시트 안에서도 보인다(띠의 다른 안내까지 옮기지 않게)
        }
    }
}
