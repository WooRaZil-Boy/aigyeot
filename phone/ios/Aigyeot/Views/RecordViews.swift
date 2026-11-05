import SwiftUI
import Charts

private typealias DS = DesignSystem

/// 기록(C4·C5·C14): 위쪽 [하루] [패턴] 전환.
struct RecordsView: View {
    @Environment(AppModel.self) private var model
    @State private var pattern = false
    @State private var sheet: CareSheet?
    @State private var day = CareRules.dayKey(CareTime.ms(Date()), .current)

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker(CareText.tabLog, selection: $pattern) {
                    Text(CareText.segmentDay).tag(false)
                    Text(CareText.segmentPattern).tag(true)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, DS.s4).padding(.vertical, DS.s2)
                if pattern { PatternView() } else { DayView(sheet: $sheet, day: $day) }
            }
            .navigationTitle(CareText.tabLog)
            .safeAreaInset(edge: .top) { StatusBands().padding(.horizontal, 16) }   // 지우기 실패 등 안내 띠(다른 탭과 같다)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        let today = CareRules.dayKey(CareTime.ms(Date()), .current)
                        sheet = .quick(.feed, day: pattern || day == today ? nil : day)   // 고른 날에 적는다
                    } label: { Image(systemName: "plus") }.accessibilityLabel(CareText.quickPast)
                }
            }
            .sheet(item: $sheet) { CareSheetView(sheet: $0) }
        }
    }
}

/// 날짜 띠 + 하루 타임라인. 손 기록은 누르면 고치기, 밀면 지우기(5초 되돌리기). 차 기록은 누르면 리포트 상세.
private struct DayView: View {
    @Environment(AppModel.self) private var model
    @Binding var sheet: CareSheet?
    @Binding var day: String

    private enum Item: Identifiable {
        case care(CareEvent)
        case car(CarRow)
        var id: String { switch self { case .care(let e): e.id; case .car(let r): r.id } }
        var at: Int64 { switch self { case .care(let e): e.startAt; case .car(let r): CareTime.ms(r.at) } }
    }

    /// 기록이 있는 가장 이른 날과 늦은 날(목록과 같게 그 기록의 시간대로). 띠가 이 날들을 모두 담아야 열 수 있다.
    private var recordDays: (first: String, last: String)? {
        let keys = model.care.events.filter { $0.deletedAt == nil }.map { CareRules.dayKey($0.startAt, $0.timeZone) }
            + CareRules.carRows(model.state.reports, zone: .current).map { CareRules.dayKey(CareTime.ms($0.at), .current) }
        guard let first = keys.min(), let last = keys.max() else { return nil }
        return (first, last)
    }

    private func items(_ day: String) -> [Item] {
        let hand = CareRules.events(onDay: day, in: model.care.events).map(Item.care)
        let car = CareRules.carRows(model.state.reports, zone: .current)
            .filter { CareRules.dayKey(CareTime.ms($0.at), .current) == day }.map(Item.car)
        return (hand + car).sorted { $0.at < $1.at }
    }

    var body: some View {
        VStack(spacing: 0) {
            DayStrip(selected: $day, span: recordDays)
            Text(CareText.dayTitle(day, now: Date(), zone: .current))
                .font(DS.headline).frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, DS.s4).padding(.vertical, DS.s2)
            let list = items(day)
            List {
                if list.isEmpty { Text(CareText.emptyDay).foregroundStyle(DS.textSecondary) }
                ForEach(list) { item in
                    switch item {
                    case .care(let e): careRow(e)
                    case .car(let r): carRow(r)
                    }
                }
            }
            .listStyle(.plain)
        }
        .overlay(alignment: .bottom) { UndoBar() }
    }

    private func careRow(_ e: CareEvent) -> some View {
        Button { sheet = e.inProgress ? .timer(e.id) : .edit(e.id) } label: {
            line(icon: DS.icon(e.kind), color: DS.color(e.kind)) {
                Text(CareText.row(e)).foregroundStyle(DS.textPrimary)
                if e.kind != .note, let n = e.note { Text(n).font(DS.caption).foregroundStyle(DS.textSecondary) }
            }
        }
        .swipeActions {
            Button(CareText.delete, role: .destructive) { model.deleteEvent(e.id) }
        }
    }

    @ViewBuilder private func carRow(_ r: CarRow) -> some View {
        if let report = model.state.reports.first(where: { $0.body.sessionId == r.sessionId }) {
            NavigationLink { ReportDetailView(report: report) } label: {
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
    @Environment(AppModel.self) private var model

    var body: some View {
        if let id = model.recentlyDeleted {
            HStack {
                Text(CareText.deleted)
                Spacer()
                Button(CareText.undo) { model.undoDelete(id) }.bold()
            }
            .foregroundStyle(DS.bg)   // 글자·바탕을 뒤집어 밝음·어둠 모두 읽힌다
            .padding(DS.s4)
            .background(DS.textPrimary.opacity(0.9), in: RoundedRectangle(cornerRadius: DS.rMedium))
            .padding(DS.s4)
            .task(id: id) {
                // 5초는 지운 때부터 센다. 띠가 사라져 취소되면 비우지 않는다(다른 화면의 띠가 남은 시간을 이어 센다).
                let at = model.care.events.first { $0.id == id }?.deletedAt ?? CareTime.ms(Date())
                let left = UInt64(max(0, at + 5000 - CareTime.ms(Date())))
                guard (try? await Task.sleep(nanoseconds: left * 1_000_000)) != nil else { return }
                if model.recentlyDeleted == id { model.recentlyDeleted = nil }
            }
        }
    }
}

/// 위쪽 날짜 띠: 지난 30일. 기록이 있는 날이 그 밖이면(더 오래됐거나 다른 시간대라 내일) 그날까지 늘린다. 칸은 요일과 날짜 숫자, 읽어 주기는 "오늘"·"어제"·"11월 5일(수)".
private struct DayStrip: View {
    @Binding var selected: String
    let span: (first: String, last: String)?

    private var days: [(key: String, date: Date)] {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let today = cal.startOfDay(for: Date())
        let date = { (key: String) in CareRules.dayRange(key, .current).map { CareTime.date($0.0) } }
        let first = min(cal.date(byAdding: .day, value: -29, to: today)!, span.flatMap { date($0.first) } ?? today)
        let last = max(today, span.flatMap { date($0.last) } ?? today)
        let n = cal.dateComponents([.day], from: first, to: last).day ?? 0
        return (0...max(0, n)).map { i in
            let d = cal.date(byAdding: .day, value: i, to: first)!
            return (CareRules.dayKey(CareTime.ms(d), .current), d)
        }
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: DS.s2) {   // 오래 쓰면 칸이 수백 개라 보이는 칸만 그린다
                    ForEach(days, id: \.key) { d in
                        let on = d.key == selected
                        Button { selected = d.key } label: {
                            VStack(spacing: 2) {
                                Text(weekday(d.date)).font(DS.caption)
                                Text("\(Calendar.current.component(.day, from: d.date))").font(DS.headline.monospacedDigit())
                            }
                            .frame(minWidth: 44, minHeight: 52).padding(.horizontal, DS.s1)
                            .foregroundStyle(on ? DS.onAccent : DS.textPrimary)
                            .background(on ? DS.accent : DS.surface, in: RoundedRectangle(cornerRadius: DS.rSmall))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(CareText.dayTitle(d.key, now: Date(), zone: .current))
                        .accessibilityAddTraits(on ? .isSelected : [])
                        .id(d.key)
                    }
                }
                .padding(.horizontal, DS.s4)
            }
            .onAppear { proxy.scrollTo(selected, anchor: .trailing) }
        }
    }

    private func weekday(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.dateFormat = "E"
        return f.string(from: d)
    }
}

/// 패턴(C5, R14·R16): 지난 7일. 칸마다 이름 · 값, 아래에 날마다 막대.
private struct PatternView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let on = model.care.sleepDiaperEnabled
        let p = CareRules.pattern(model.care.events, reports: model.state.reports, now: CareTime.ms(Date()), zone: .current, sleepDiaper: on)
        List {
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
                Text(CareText.patternHeader)
            } footer: {
                Text(CareText.patternFootnote)
            }
        }
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
        .chartYAxis { AxisMarks(values: ticks) }
        .frame(height: 100)
        .accessibilityHidden(true)   // 값은 위 칸 글자로 읽는다
    }
}
