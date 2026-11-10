import SwiftUI

private typealias DS = DesignSystem

/// 기록(C4·C5·C14, 시안 S.log·S.pattern): 큰 제목 → [하루] [패턴] → (하루) 날짜 띠 + 타임라인 + '지난 기록 적기' / (패턴) 차트 블록.
struct RecordsView: View {
    @State private var pattern = false
    @State private var sheet: CareSheet?
    /// 타임라인의 차 기록에서 연 리포트. 목록 안 NavigationLink는 카드 밖에 시스템 › 를 붙여 버튼으로 열고 여기서 넘긴다.
    @State private var report: String?
    /// 사용자가 고른 날. 없으면 늘 오늘이다(화면을 연 채 자정을 넘기면 오늘을 따라간다. 옛 오늘에 머물면 '+'가 어제 낮 12시로 적었다)
    @State private var picked: String?
    /// 자정을 넘기면 날짜 띠·제목·'지난 7일'이 바뀐다. 화면을 연 채 두어도 매분 같은 기준 시각으로 다시 그린다(안드로이드 rememberNow).
    /// TimelineView로 감싸지 않는다: 목록·스크롤이 내비게이션의 바로 아래여야 큰 제목이 접힌다.
    @State private var now = Date()
    /// 하루·패턴을 바꾼 때: 새로 나온 블록도 탭을 바꿀 때처럼 한 줄씩 올라온다(`enterRise`).
    @State private var segmentAt = Date.distantPast
    @Environment(\.tabShownAt) private var tabShownAt

    var body: some View {
        let today = CareRules.dayKey(CareTime.ms(now), .current)
        let segment = Segmented(title: CareText.tabLog, options: [false, true], label: { $0 ? CareText.segmentPattern : CareText.segmentDay },
                                selection: Binding(get: { pattern }, set: { pattern = $0 ?? false; segmentAt = Date() }))
        NavigationStack {
            // 하루·패턴은 한 List 안에서 내용만 바꾼다. 스크롤 뷰를 통째로 바꾸면 큰 제목이 새 스크롤을 따라 접히지 않고 내용 위에 남았다.
            List {
                Group {
                    VStack(spacing: 0) {
                        StatusBands(gap: 12)   // 지우기 실패 등 안내 띠(다른 탭과 같다)
                        segment
                    }
                    .padding(.horizontal, 16).padding(.top, 4)
                    .enterRise(0)
                    if pattern {
                        PatternView(now: now)
                    } else {
                        DayView(sheet: $sheet, report: $report, day: Binding(get: { picked ?? today }, set: { picked = $0 == today ? nil : $0 }), now: now) {
                            sheet = .quick(.feed, day: picked == nil || picked == today ? nil : picked)   // 고른 날에 적는다(고르지 않았으면 지금)
                        }
                    }
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.tabShownAt, max(tabShownAt, segmentAt))
            .screenBackground()
            .navigationTitle(CareText.tabLog)
            .sheet(item: $sheet) { CareSheetView(sheet: $0) }
            .navigationDestination(item: $report) { ReportDetailView(sessionId: $0) }
            .task {
                now = Date()   // 다시 들어오면(자정을 넘긴 뒤 등) 바로 지금으로. 그다음은 분이 바뀔 때마다
                while !Task.isCancelled {
                    let next = 60 - Calendar.current.component(.second, from: Date())   // 분이 바뀔 때
                    try? await Task.sleep(for: .seconds(next))
                    now = Date()
                }
            }
        }
    }
}

/// 날짜 띠 + 하루 타임라인(새 것이 위, 3시간마다 시각 머리). 손 기록은 누르면 고치기, 밀면 지우기(5초 되돌리기). 차 기록은 누르면 리포트 상세.
private struct DayView: View {
    @Environment(RecordsViewModel.self) private var vm
    @Binding var sheet: CareSheet?
    @Binding var report: String?
    @Binding var day: String
    let now: Date
    let add: () -> Void

    private enum Item: Identifiable {
        case care(CareEvent)
        case car(CarRow)
        var id: String { switch self { case .care(let e): e.id; case .car(let r): r.id } }
        var at: Int64 { switch self { case .care(let e): e.startAt; case .car(let r): CareTime.ms(r.at) } }
        /// 그 기록의 시간대(손 기록은 자기 시간대, 차 기록은 기기 시간대).
        var zone: TimeZone { switch self { case .care(let e): e.timeZone; case .car: .current } }
    }

    /// 기록이 있는 날(목록과 같게 그 기록의 시간대로). 띠가 이 날들을 모두 담아야 열 수 있다.
    private func recordKeys(_ car: [CarRow]) -> Set<String> {
        Set(vm.ui.care.events.filter { $0.deletedAt == nil }.map { CareRules.dayKey($0.startAt, $0.timeZone) }
            + car.map { CareRules.dayKey(CareTime.ms($0.at), .current) })
    }

    private func items(_ day: String, _ car: [CarRow]) -> [Item] {
        let hand = CareRules.events(onDay: day, in: vm.ui.care.events).map(Item.care)
        let carItems = car.filter { CareRules.dayKey(CareTime.ms($0.at), .current) == day }.map(Item.car)
        return (hand + carItems).sorted { $0.at > $1.at }   // 새 것이 위(시안)
    }

    var body: some View {
        // 차 기록 줄은 그릴 때마다 한 번만 만든다(날짜 띠·목록이 함께 쓴다. 리포트마다 시각을 포맷해 비싸다)
        let car = CareRules.carRows(vm.ui.reports, zone: .current)
        let keys = recordKeys(car)
        let list = items(day, car)
        // 행만 낸다(바깥 RecordsView의 List가 담는다)
        Group {
            DayStrip(selected: $day, span: keys.min().flatMap { a in keys.max().map { (a, $0) } }, records: keys, now: now).enterRise(1)
            // 고른 날 제목(log.date.*: "오늘"·"어제"·"11월 5일(수)"). 띠는 요일·날짜 숫자뿐이라 달이 바뀌어도 어느 날인지 보이게
            Text(CareText.dayTitle(day, now: now, zone: .current)).typeStyle(.headline).foregroundStyle(DS.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20).padding(.top, 2)
                .accessibilityAddTraits(.isHeader)
                .enterRise(2)
            if list.isEmpty { FootNote(CareText.emptyDay).padding(.horizontal, 16) }
            ForEach(Array(list.enumerated()), id: \.element.id) { i, item in
                let bucket = hourBucket(item)
                if i == 0 || hourBucket(list[i - 1]) != bucket { HourHead(hour: bucket).enterRise(3 + min(i, 5)) }
                switch item {
                case .care(let e): careRow(e, order: 3 + min(i, 5))
                case .car(let r): carRow(r, order: 3 + min(i, 5))
                }
            }
            ListRow(icon: .plus, title: CareText.quickPast, add: true)
                .padding(.horizontal, 20).padding(.vertical, 8)
                .background(DS.surface, in: RoundedRectangle(cornerRadius: DS.rL, style: .continuous))
                .padding(.horizontal, 12).padding(.top, 12).padding(.bottom, 24)
                .onTapGesture(perform: add)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { add() }
        }
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }

    /// 3시간 칸의 시작 시(그 기록의 시간대).
    private func hourBucket(_ item: Item) -> Int {
        CareRules.calendar(item.zone).component(.hour, from: CareTime.date(item.at)) / 3 * 3
    }

    private func careRow(_ e: CareEvent, order: Int) -> some View {
        let (title, sub) = CareText.splitRow(CareText.row(e))
        return Button { sheet = e.inProgress ? .timer(e.id) : .edit(e.id) } label: {
            TimelineCard(icon: DS.icon(e.kind), dot: DS.color(e.kind), title: title, sub: sub, memo: e.kind != .note ? e.note : nil)
        }
        .buttonStyle(.press)
        .enterRise(order)   // 밀어 지우기보다 안쪽(줄의 밀기 동작은 바깥에 남긴다)
        .swipeActions {
            Button(CareText.delete, role: .destructive) { vm.delete(e.id) }
        }
    }

    @ViewBuilder private func carRow(_ r: CarRow, order: Int) -> some View {
        if vm.ui.report(sessionId: r.sessionId) != nil {
            let (title, sub) = CareText.splitRow(r.text)
            Button { report = r.sessionId } label: {
                TimelineCard(icon: .carFront, dot: DS.cCar, title: title, sub: sub, memo: nil)
            }
            .buttonStyle(.press)
            .enterRise(order)
        }
    }
}

/// 줄기(시안 .tl::before, x 45): 타임라인의 모든 줄이 같은 자리에 그려 이어 보인다.
private struct Rail: ViewModifier {
    func body(content: Content) -> some View {
        content.background(alignment: .leading) { Rectangle().fill(DS.line).frame(width: 1).padding(.leading, 45) }
    }
}

/// 타임라인 한 칸(시안 .tl .ev): 왼쪽 줄기와 종류 점, 흰 카드 안 아이콘 · 제목 · 둘째 줄(· 메모).
private struct TimelineCard: View {
    let icon: Ico
    let dot: Color
    let title: String
    let sub: String?
    let memo: String?

    var body: some View {
        HStack(spacing: 0) {
            Circle().fill(dot).frame(width: 8, height: 8)
                .background(Circle().fill(DS.bg).frame(width: 16, height: 16))
                .padding(.leading, 42).frame(width: 64, alignment: .leading)
            HStack(spacing: 12) {
                IconImage(icon, size: 18).foregroundStyle(DS.textTertiary).frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(DS.font(14.5, 500)).foregroundStyle(DS.textPrimary).multilineTextAlignment(.leading)
                    if let sub { Text(sub).font(DS.font(12.5, 500)).foregroundStyle(DS.textSecondary).multilineTextAlignment(.leading) }
                    if let memo { Text(memo).font(DS.font(12.5, 500)).foregroundStyle(DS.textTertiary).multilineTextAlignment(.leading) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 16).padding(.vertical, 13)
            .background(DS.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .padding(.vertical, 4).padding(.trailing, 16)
        }
        .frame(maxWidth: .infinity)
        .modifier(Rail())
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// 시각 머리("오후 3시", 3시간 칸의 시작). 줄기 왼쪽 흐린 11/600 + 점선.
private struct HourHead: View {
    let hour: Int
    var body: some View {
        HStack(spacing: 6) {
            Text((hour < 12 ? "오전 " : "오후 ") + "\(hour % 12 == 0 ? 12 : hour % 12)시")
                .font(DS.font(11, 600)).foregroundStyle(DS.textTertiary).lineLimit(1).fixedSize()
                .padding(.horizontal, 2).background(DS.bg)
                .frame(width: 52, alignment: .leading).padding(.leading, 14)
            Line().stroke(DS.line, style: StrokeStyle(lineWidth: 1, dash: [4, 4])).frame(height: 1)
                .padding(.trailing, 16)
        }
        .padding(.top, 6)
        .modifier(Rail())
        .accessibilityAddTraits(.isHeader)
    }

    private struct Line: Shape {
        func path(in r: CGRect) -> Path { Path { p in p.move(to: CGPoint(x: 0, y: r.midY)); p.addLine(to: CGPoint(x: r.maxX, y: r.midY)) } }
    }
}

/// 되돌리기 띠(U38·U64): "지웠어요" 또는 "기록 n건을 가져왔어요" + "되돌리기". 어느 탭에서 지우거나 가져와도 같은 띠다.
/// 띠의 수명(5초, 다른 띠로 바뀜)은 RecordCare 한 곳이 정하고 이 화면은 떠 있는 띠를 그리기만 한다(가져오기는 띠가 끝난 뒤 차로 보낸다).
struct UndoBar: View {
    @Environment(CareTabsViewModel.self) private var vm

    var body: some View {
        if let u = vm.undo {
            HStack {
                Text(CareText.undoText(u)).font(DS.font(15, 500))
                Spacer()
                Button { vm.undo(u) } label: { Text(CareText.undo).font(DS.font(15, 700)).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }   // 터치 영역 44pt(8장)
            }
            .foregroundStyle(DS.surface)   // 글자·바탕을 뒤집어 밝음·어둠 모두 읽힌다
            .padding(.horizontal, 18).padding(.vertical, 6)
            .background(DS.textPrimary.opacity(0.92), in: RoundedRectangle(cornerRadius: DS.rL, style: .continuous))
            .padding(.horizontal, 16).padding(.bottom, 8)
        }
    }
}

/// 위쪽 날짜 띠(시안 .datestrip: 46×62 알약, 요일 12/500 + 날 17, 고르면 먹색 반전): 지난 30일. 기록이 있는 날이 그 밖이면 그날까지 늘린다.
/// 읽어 주기는 "오늘"·"어제"·"11월 5일(수)".
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

    private static let end = "strip-end"

    var body: some View {
        let todayKey = CareRules.dayKey(CareTime.ms(now), .current)
        let all = days
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {   // 오래 쓰면 칸이 수백 개라 보이는 칸만 그린다
                    ForEach(all, id: \.self) { key in
                        let on = key == selected
                        Button { selected = key } label: {
                            VStack(spacing: 5) {
                                Text(key == todayKey ? CareText.today : CareRules.weekdayName(key)).font(DS.font(12, 500))
                                    .foregroundStyle(on ? DS.bg.opacity(0.75) : DS.textTertiary)
                                Text(String(Int(key.suffix(2)) ?? 0)).font(DS.font(17, 500)).monospacedDigit()   // 키(그레고리력)에서: 기기 달력이 달라도 칸과 날짜가 같다
                                    .foregroundStyle(on ? DS.bg : DS.textPrimary)
                            }
                            .frame(minWidth: 46, minHeight: 62)
                            .background(on ? DS.textPrimary : .clear, in: Capsule())
                            .contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(CareText.dayTitle(key, now: now, zone: .current))
                        .accessibilityAddTraits(on ? .isSelected : [])
                        .id(key)
                    }
                    Color.clear.frame(width: 8).id(Self.end)   // 끝 여백(칸 간격 8 + 8 = 16). 오늘로 돌아올 때 이 표시까지 굴린다
                }
                .padding(.leading, 16)
            }
            // 칸이 게을리 그려져 onAppear의 scrollTo만으로는 오늘(맨 끝)로 가지 않았다. 처음부터 끝에 붙여 연다.
            .defaultScrollAnchor(.trailing)
            .fixedSize(horizontal: false, vertical: true)   // 띠 높이를 칸에 맞춘다(위아래 빈 공간)
            // 다시 나타날 때(탭을 옮겼다 돌아옴) 고른 칸이 보이게: 맨 끝 날(보통 오늘)은 끝 여백까지, 앞의 날은 가운데로.
            // 칸 자체를 .trailing으로 굴리면 칸이 화면 끝에 붙어 여백이 사라졌다.
            .onAppear {
                if selected == all.last { proxy.scrollTo(Self.end, anchor: .trailing) } else { proxy.scrollTo(selected, anchor: .center) }
            }
        }
        .padding(.vertical, 4)
    }
}

/// 패턴(C5, R14·R16, 시안 S.pattern): 지난 7일 숫자 → 수유 간격 7일 막대 → (잠·기저귀 켬) 잠 패턴 히트맵 → 차에서 울음 7일 막대 → 덧붙임.
/// 값을 붙이는 규칙은 안드로이드 PatternView·ChartData와 같다(진행 기록 '결정' 2~5).
private struct PatternView: View {
    @Environment(RecordsViewModel.self) private var vm
    let now: Date

    var body: some View {
        let ui = vm.ui.pattern(now: CareTime.ms(now))
        let iw = ui.interval, cw = ui.carCries
        // 블록마다 한 행(바깥 RecordsView의 List가 담는다). 행 사이 12, 옆 12(시안 `.pad`).
        Group {
            VStack(alignment: .leading, spacing: 0) {
                BlockHeader(title: CareText.patternHeader)
                let kpis = kpiItems(ui)
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(0..<((kpis.count + 2) / 3), id: \.self) { r in KpiRow(items: Array(kpis[(r * 3)..<min(kpis.count, r * 3 + 3)])) }
                }
            }
            .block()
            .enterRise(1)
            VStack(alignment: .leading, spacing: 0) {
                BlockHeader(title: CareText.patternInterval)
                let sub = [iw.todayMs.map { Text("오늘 ") + Text(CareFormat.length($0)).font(DS.font(14, 700)).foregroundColor(DS.textPrimary) },
                           iw.usualMs.map { Text("평소 " + CareFormat.length($0)) }].compactMap { $0 }
                if !sub.isEmpty { BlockSub(text: sub.dropFirst().reduce(sub[0]) { $0 + Text(" · ") + $1 }) }
                WeekBarsChart(items: iw.bars.enumerated().map { i, b in
                    .init(label: b.label, value: b.value.map(Double.init), tip: b.tip, hot: i == iw.bars.count - 1,
                          valueLabel: i == iw.bars.count - 1 ? b.value.map(CareFormat.length) : nil)
                }, hot: DS.cFeed, target: iw.usualMs.map { (Double($0), "평소 " + CareFormat.length($0)) },
                              summary: "지난 7일 수유 간격 중앙값")
            }
            .block()
            .enterRise(2)
            if let g = ui.sleep {
                VStack(alignment: .leading, spacing: 0) {
                    BlockHeader(title: CareText.sleepPattern)
                    if let avg = g.avgMs {
                        BlockSub(text: Text("하루 평균 ") + Text(CareFormat.length(avg)).font(DS.font(14, 700)).foregroundColor(DS.textPrimary) + Text(" · " + CareText.basedOn(days: g.sleepDays)))
                    }
                    SleepHeatChart(grid: g)
                }
                .block()
                .enterRise(3)
            }
            VStack(alignment: .leading, spacing: 0) {
                BlockHeader(title: CareText.patternCarCries)
                BlockSub(text: [Text("7일 동안 ") + Text("\(cw.total)번").font(DS.font(14, 700)).foregroundColor(DS.textPrimary),
                                cw.avgLatencyS.map { Text("평균 감지 " + ChartData.seconds($0)) }].compactMap { $0 }
                    .reduce(Text("")) { $0 == Text("") ? $1 : $0 + Text(" · ") + $1 })
                WeekBarsChart(items: cw.bars.enumerated().map { i, b in
                    .init(label: b.label, value: b.value.map(Double.init), tip: b.tip, hot: i == cw.bars.count - 1,
                          valueLabel: i == cw.bars.count - 1 ? b.value.map { "\($0)번" } : nil)
                }, hot: DS.cCar, height: 110, summary: "지난 7일 차에서 울음 횟수")
            }
            .block()
            .enterRise(4)
            FootNote(CareText.patternFootnote).padding(.bottom, 24)
        }
        .padding(.top, 12)
        .padding(.horizontal, 12)
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }

    /// 지난 7일 숫자 칸(결정 2): 하루 수유 "평균 n회"·"{n}일 기준", 수유 간격 "{기간}"·"중앙값", 차에서 울음 "{n}번"·"7일 동안", 켜면 하루 잠·하루 기저귀.
    private func kpiItems(_ ui: PatternUi) -> [KpiRow.Item] {
        let p = ui.numbers, trend = ui.feedTrend
        func days(_ n: Int?) -> String? { n.flatMap { $0 > 0 ? CareText.basedOn(days: $0) : nil } }
        var items = [
            // pattern.days는 늘 보이고, 앞 주와 견줄 수 있으면 그 아래 '지난주보다 …'(design.chart)
            KpiRow.Item(name: CareText.patternFeeds, value: p.feedsPerDay.map(CareText.avgCount) ?? CareText.noRecords, sub: days(p.feedDays), note: trend),
            KpiRow.Item(name: CareText.patternInterval, value: p.feedIntervalMedianMs.map(CareFormat.length) ?? "—",
                        sub: p.feedIntervalMedianMs == nil ? CareText.patternFewIntervals : CareText.medianWord),
            KpiRow.Item(name: CareText.patternCarCries, value: CareText.times(p.carCries), sub: CareText.sevenDays),
        ]
        if vm.ui.care.sleepDiaperEnabled {
            items.append(KpiRow.Item(name: CareText.patternSleep, value: p.sleepMsPerDay.map(CareText.avgDuration) ?? CareText.noRecords, sub: days(p.sleepDays)))
            items.append(KpiRow.Item(name: CareText.patternDiapers, value: p.diapersPerDay.map(CareText.avgCount) ?? CareText.noRecords, sub: days(p.diaperDays)))
        }
        return items
    }
}
