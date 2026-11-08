import SwiftUI
import Charts

private typealias DS = DesignSystem

/// 기록(C4·C5·C14): 위쪽 [하루] [패턴] 전환.
struct RecordsView: View {
    @State private var pattern = false
    @State private var sheet: CareSheet?
    /// 사용자가 고른 날. 없으면 늘 오늘이다(화면을 연 채 자정을 넘기면 오늘을 따라간다. 옛 오늘에 머물면 '+'가 어제 낮 12시로 적었다)
    @State private var picked: String?
    @Environment(\.dynamicTypeSize) private var size

    var body: some View {
        // 가장 큰 글자에서는 세로 글자 버튼(나눈 선택은 커지지 않는다, 8장)
        let choice = AdaptiveChoice(title: CareText.tabLog, options: [false, true], label: { $0 ? CareText.segmentPattern : CareText.segmentDay },
                                    selection: Binding(get: { pattern }, set: { pattern = $0 ?? false }))
            .padding(.horizontal, DS.s4).padding(.vertical, DS.s2)
        // 가장 큰 글자에서는 전환·날짜 띠·날짜 제목도 목록 첫 줄로 함께 굴린다(고정하면 안내 띠까지 있을 때 목록 자리가 화면의 6%뿐이었다)
        let top = size.isAccessibilitySize ? AnyView(choice) : nil
        NavigationStack {
            VStack(spacing: 0) {
                if top == nil { choice }
                // 자정을 넘기면 날짜 띠·제목·'지난 7일'이 바뀐다. 화면을 연 채 두어도 같은 기준 시각으로 매분 다시 그린다(안드로이드 rememberNow).
                TimelineView(.everyMinute) { ctx in
                    let today = CareRules.dayKey(CareTime.ms(ctx.date), .current)
                    if pattern { PatternView(now: ctx.date, top: top) } else {
                        DayView(sheet: $sheet, day: Binding(get: { picked ?? today }, set: { picked = $0 == today ? nil : $0 }), now: ctx.date, top: top)
                    }
                }
            }
            .screenBackground()
            .navigationTitle(CareText.tabLog)
            .safeAreaInset(edge: .top) { StatusBands().padding(.horizontal, 16) }   // 지우기 실패 등 안내 띠(다른 탭과 같다)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        let today = CareRules.dayKey(CareTime.ms(Date()), .current)
                        sheet = .quick(.feed, day: pattern || picked == today ? nil : picked)   // 고른 날에 적는다(고르지 않았으면 지금)
                    } label: { Image(systemName: "plus") }.accessibilityLabel(CareText.quickPast)
                }
            }
            .sheet(item: $sheet) { CareSheetView(sheet: $0) }
        }
    }
}

/// 날짜 띠 + 하루 타임라인. 손 기록은 누르면 고치기, 밀면 지우기(5초 되돌리기). 차 기록은 누르면 리포트 상세.
private struct DayView: View {
    @Environment(RecordsViewModel.self) private var vm
    @Binding var sheet: CareSheet?
    @Binding var day: String
    let now: Date
    /// 있으면(가장 큰 글자) 전환과 날짜 띠·제목을 목록 첫 줄로 굴린다.
    let top: AnyView?

    private enum Item: Identifiable {
        case care(CareEvent)
        case car(CarRow)
        var id: String { switch self { case .care(let e): e.id; case .car(let r): r.id } }
        var at: Int64 { switch self { case .care(let e): e.startAt; case .car(let r): CareTime.ms(r.at) } }
    }

    /// 기록이 있는 날(목록과 같게 그 기록의 시간대로). 띠가 이 날들을 모두 담아야 열 수 있다.
    private func recordKeys(_ car: [CarRow]) -> Set<String> {
        Set(vm.ui.care.events.filter { $0.deletedAt == nil }.map { CareRules.dayKey($0.startAt, $0.timeZone) }
            + car.map { CareRules.dayKey(CareTime.ms($0.at), .current) })
    }

    private func items(_ day: String, _ car: [CarRow]) -> [Item] {
        let hand = CareRules.events(onDay: day, in: vm.ui.care.events).map(Item.care)
        let carItems = car.filter { CareRules.dayKey(CareTime.ms($0.at), .current) == day }.map(Item.car)
        return (hand + carItems).sorted { $0.at < $1.at }
    }

    var body: some View {
        // 차 기록 줄은 그릴 때마다 한 번만 만든다(날짜 띠·목록이 함께 쓴다. 리포트마다 시각을 포맷해 비싸다)
        let car = CareRules.carRows(vm.ui.reports, zone: .current)
        let keys = recordKeys(car)
        VStack(spacing: 0) {
            if top == nil { header(keys) }
            let list = items(day, car)
            List {
                if let top {
                    VStack(spacing: 0) { top; header(keys) }
                        .listRowInsets(EdgeInsets()).listRowBackground(DS.bg).listRowSeparator(.hidden)
                }
                if list.isEmpty { Text(CareText.emptyDay).foregroundStyle(DS.textSecondary).listRowBackground(DS.bg) }
                ForEach(list) { item in
                    switch item {
                    case .care(let e): careRow(e)
                    case .car(let r): carRow(r)
                    }
                }
                .listRowBackground(DS.bg)   // 어둠에서 plain 목록 행이 순검정이었다(8장: 아주 어두운 회색)
            }
            .listStyle(.plain)
        }
        .overlay(alignment: .bottom) { UndoBar() }
    }

    @ViewBuilder private func header(_ keys: Set<String>) -> some View {
        DayStrip(selected: $day, span: keys.min().flatMap { a in keys.max().map { (a, $0) } }, records: keys, now: now)
        Text(CareText.dayTitle(day, now: now, zone: .current))
            .font(DS.headline).frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, DS.s4).padding(.vertical, DS.s2)
    }

    private func careRow(_ e: CareEvent) -> some View {
        Button { sheet = e.inProgress ? .timer(e.id) : .edit(e.id) } label: {
            line(icon: DS.icon(e.kind), color: DS.color(e.kind)) {
                Text(CareText.row(e)).foregroundStyle(DS.textPrimary)
                if e.kind != .note, let n = e.note { Text(n).font(DS.caption).foregroundStyle(DS.textSecondary) }
            }
        }
        .swipeActions {
            Button(CareText.delete, role: .destructive) { vm.delete(e.id) }
        }
    }

    @ViewBuilder private func carRow(_ r: CarRow) -> some View {
        if vm.ui.report(sessionId: r.sessionId) != nil {
            NavigationLink { ReportDetailView(sessionId: r.sessionId) } label: {
                line(icon: "car.fill", color: DS.car) { Text(r.text) }
            }
        }
    }

    private func line(icon: String, color: Color, @ViewBuilder content: () -> some View) -> some View {
        HStack(alignment: .top, spacing: DS.s3) {
            Image(systemName: icon).foregroundStyle(color).frame(width: 24)
            VStack(alignment: .leading, spacing: 2) { content() }
        }
    }

}

/// "지웠어요" + "되돌리기"(5초). 기록 탭 밀기, 고치기 시트, 타이머 어디서 지워도 같다(U38).
struct UndoBar: View {
    @Environment(CareTabsViewModel.self) private var undo

    var body: some View {
        if let id = undo.undoCareId {
            HStack {
                Text(CareText.deleted)
                Spacer()
                Button { undo.undoDelete(id) } label: { Text(CareText.undo).bold().frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }   // 터치 영역 44pt(8장)
            }
            .foregroundStyle(DS.bg)   // 글자·바탕을 뒤집어 밝음·어둠 모두 읽힌다
            .padding(DS.s4)
            .background(DS.textPrimary.opacity(0.9), in: RoundedRectangle(cornerRadius: DS.rMedium))
            .padding(DS.s4)
            .task(id: id) {
                // 5초는 지운 때부터 센다. 띠가 사라져 취소되면 비우지 않는다(다른 화면의 띠가 남은 시간을 이어 센다).
                let at = undo.deletedAt(id) ?? CareTime.ms(Date())
                let left = UInt64(max(0, at + RetryPolicy.undoMs - CareTime.ms(Date())))
                guard (try? await Task.sleep(nanoseconds: left * 1_000_000)) != nil else { return }
                undo.clearUndo(id)
            }
        }
    }
}

/// 위쪽 날짜 띠: 지난 30일. 기록이 있는 날이 그 밖이면(더 오래됐거나 다른 시간대라 내일) 그날까지 늘린다. 칸은 요일과 날짜 숫자, 읽어 주기는 "오늘"·"어제"·"11월 5일(수)".
private struct DayStrip: View {
    @Binding var selected: String
    let span: (first: String, last: String)?
    let records: Set<String>
    let now: Date

    private var days: [String] {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let today = cal.startOfDay(for: now)
        let date = { (key: String) in CareRules.dayRange(key, .current).map { CareTime.date($0.0) } }
        let first = min(cal.date(byAdding: .day, value: -29, to: today)!, span.flatMap { date($0.first) } ?? today)
        let last = max(today, span.flatMap { date($0.last) } ?? today)
        return CareRules.stripKeys(CareRules.days(from: first, through: last, zone: .current).map(\.key), records: records)
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: DS.s2) {   // 오래 쓰면 칸이 수백 개라 보이는 칸만 그린다
                    ForEach(days, id: \.self) { key in
                        let on = key == selected
                        Button { selected = key } label: {
                            VStack(spacing: 2) {
                                Text(CareRules.weekdayName(key)).font(DS.caption)
                                Text(String(Int(key.suffix(2)) ?? 0)).font(DS.headline.monospacedDigit())   // 키(그레고리력)에서: 기기 달력이 달라도 칸과 날짜가 같다
                            }
                            .frame(minWidth: 44, minHeight: 52).padding(.horizontal, DS.s1)
                            .foregroundStyle(on ? DS.onAccent : DS.textPrimary)
                            .background(on ? DS.accent : DS.surface, in: RoundedRectangle(cornerRadius: DS.rSmall))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(CareText.dayTitle(key, now: now, zone: .current))
                        .accessibilityAddTraits(on ? .isSelected : [])
                        .id(key)
                    }
                }
                .padding(.horizontal, DS.s4)
            }
            // 칸이 게을리 그려져 onAppear의 scrollTo만으로는 오늘(맨 끝)로 가지 않았다. 처음부터 끝에 붙여 연다.
            .defaultScrollAnchor(.trailing)
            .fixedSize(horizontal: false, vertical: true)   // 띠 높이를 칸에 맞춘다(위아래 빈 공간)
            .onAppear { proxy.scrollTo(selected, anchor: .trailing) }
        }
    }
}

/// 패턴(C5, R14·R16): 지난 7일. 칸마다 이름 · 값, 아래에 날마다 막대.
private struct PatternView: View {
    @Environment(RecordsViewModel.self) private var vm
    let now: Date
    let top: AnyView?   // 가장 큰 글자: 전환을 목록 첫 줄로

    var body: some View {
        let on = vm.ui.care.sleepDiaperEnabled
        let p = CareRules.pattern(vm.ui.care.events, reports: vm.ui.reports, now: CareTime.ms(now), zone: .current, sleepDiaper: on)
        List {
            if let top { top.listRowInsets(EdgeInsets()).listRowBackground(DS.bg) }
            Section {
                cell(CareText.patternFeeds, p.feedsPerDay.map(CareText.avgCount), days: p.feedDays,
                     p.days.map { ($0.day, Double($0.feeds)) }, DS.feed, counts: true)
                LabeledContent(CareText.patternInterval,
                               value: p.feedIntervalMedianMs.map(CareText.median) ?? CareText.patternFewIntervals)
                if on {
                    cell(CareText.patternSleep, p.sleepMsPerDay.map(CareText.avgDuration), days: p.sleepDays,
                         p.days.map { ($0.day, Double($0.sleepMs ?? 0) / 3_600_000) }, DS.sleep, counts: false)
                    cell(CareText.patternDiapers, p.diapersPerDay.map(CareText.avgCount), days: p.diaperDays,
                         p.days.map { ($0.day, Double($0.diapers ?? 0)) }, DS.diaper, counts: true)
                }
                cell(CareText.patternCarCries, CareText.times(p.carCries), days: nil,
                     p.days.map { ($0.day, Double($0.carEpisodes)) }, DS.car, counts: true)
            } header: {
                SectionHeader(CareText.patternHeader)
            } footer: {
                SectionHeader(CareText.patternFootnote)
            }
        }
        .labeledContentStyle(TokenLabeledContentStyle())   // 평균·기준 일수 글자 대비 4.5:1(8장)
    }

    /// 이름 · 값(평균이면 아래에 "{n}일 기준", 기록한 날이 없으면 "기록 없음") · 날마다 막대.
    private func cell(_ name: String, _ value: String?, days: Int?, _ values: [(String, Double)], _ color: Color, counts: Bool) -> some View {
        VStack(alignment: .leading, spacing: DS.s2) {
            LabeledContent(name) {
                VStack(alignment: .trailing, spacing: 2) {
                    Text(value ?? CareText.noRecords)
                    if let days, days > 0 { Text(CareText.basedOn(days: days)).font(DS.caption) }   // U31: 기록한 날이 없으면 숨긴다
                }
            }
            chart(values, color, counts: counts)
        }
    }

    /// 횟수는 정수 눈금만 쓴다(0, 중간, 최댓값). 막대 아래는 날짜 숫자.
    private func chart(_ values: [(String, Double)], _ color: Color, counts: Bool) -> some View {
        let top = max(1, values.map(\.1).max() ?? 0).rounded(.up)
        let ticks = counts ? Array(Set([0, (top / 2).rounded(.up), top])).sorted() : [0, top / 2, top]
        return Chart(values, id: \.0) { v in
            BarMark(x: .value("day", String(v.0.suffix(2))), y: .value("value", v.1)).foregroundStyle(color)
        }
        .chartYScale(domain: 0...top)
        .chartYAxis { AxisMarks(values: ticks) { AxisGridLine(); AxisValueLabel().foregroundStyle(DS.textSecondary) } }   // 축 글자 대비 4.5:1(8장)
        .chartXAxis { AxisMarks { AxisValueLabel().foregroundStyle(DS.textSecondary) } }
        .frame(height: 100)
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)   // 축 글자가 그래프 높이를 넘으면 날짜가 '…'로 뭉개진다. 값은 위 글줄이 큰 글자로 보인다
        .accessibilityHidden(true)   // 값은 위 칸 글자로 읽는다
    }
}
