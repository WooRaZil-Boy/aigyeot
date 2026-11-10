import SwiftUI

private typealias DS = DesignSystem

// 빠른 기록·고치기 시트(C3·C4)와 타이머(C2). 시안 S.timer·S.sheet: iOS는 떠 있는 글래스 시트, 머리 왼쪽 닫기·오른쪽 저장(파랑) 원 버튼.

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

/// 시트 머리 버튼: iOS 26은 시스템 글래스 원(아이콘), 그 아래는 글자 버튼.
private struct SheetToolbar: ToolbarContent {
    let cancel: () -> Void
    var confirm: (() -> Void)? = nil
    var confirmDisabled = false

    var body: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button(action: cancel) {
                if #available(iOS 26, *) { IconImage(.x, size: 20).foregroundStyle(DS.textPrimary) } else { Text(CareText.cancel) }   // 시안 .gbtn: 먹색 X
            }
            .accessibilityLabel(CareText.cancel)
        }
        if let confirm {
            ToolbarItem(placement: .confirmationAction) {
                ConfirmButton(action: confirm).disabled(confirmDisabled)
            }
        }
    }
}

/// 저장: iOS 26은 파랑 글래스 원(시안 .gbtn.glass.tint), 그 아래는 굵은 글자 버튼.
private struct ConfirmButton: View {
    let action: () -> Void
    var body: some View {
        if #available(iOS 26, *) {
            Button(action: action) { IconImage(.check, size: 20) }
                .buttonStyle(.glassProminent).tint(DS.accent)
                .accessibilityLabel(CareText.save)
        } else {
            Button(action: action) { Text(CareText.save).bold() }
        }
    }
}

/// 젖병 양 행: "양" + "{n}ml". 값이 없으면 + 로 10ml부터 넣는다.
private struct AmountRow: View {
    @Binding var amount: Int?

    var body: some View {
        HStack {
            Text(CareText.rowAmount).font(DS.font(15.5, 500)).foregroundStyle(DS.textPrimary)
            Spacer()
            Stepper {
                Text(amount.map(CareText.ml) ?? "").font(DS.font(15.5, 600)).monospacedDigit().foregroundStyle(DS.textPrimary)   // 옅은 판 위 파랑 글자는 4.5:1 미만
            } onIncrement: {
                amount = min(CareRules.amountRange.upperBound, (amount ?? 0) + 10)
            } onDecrement: {
                amount = amount.map { max(CareRules.amountRange.lowerBound, $0 - 10) }
            }
            .fixedSize()
        }
        .frame(minHeight: 50)
        .modifier(RowLine())
        .accessibilityElement(children: .combine)
    }
}

/// 시트 안 행 묶음(시안 .sheet .rows): 옅은 판(먹색 4%) 모서리 20, 행 50 이상, 사이 선(행마다 위 선, 첫 선은 가린다).
private struct SheetRows<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(spacing: 0) { content() }
            .mask { Rectangle().padding(.top, 1) }
            .padding(.horizontal, 14).padding(.vertical, 2)
            .background(DS.textPrimary.opacity(0.04), in: RoundedRectangle(cornerRadius: DS.rL, style: .continuous))
    }
}

/// 행 위 선(SheetRows 안).
private struct RowLine: ViewModifier {
    func body(content: Content) -> some View { content.overlay(alignment: .top) { Rectangle().fill(DS.line).frame(height: 1) } }
}

/// 이름 · 값 행(값은 파랑 글자 칸, 시각은 시스템 시각 고르기).
private struct SheetRow<Value: View>: View {
    let name: String
    @ViewBuilder var value: () -> Value
    var body: some View {
        HStack {
            Text(name).font(DS.font(15.5, 500)).foregroundStyle(DS.textPrimary)
            Spacer(minLength: 8)
            value()
        }
        .frame(minHeight: 50)
        .modifier(RowLine())
    }
}

/// 값 칩 줄: 한 줄에 안 들어가면(큰 글자) 오른쪽 정렬로 쌓는다.
private struct ChipLine<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 6) { content() }
            VStack(alignment: .trailing, spacing: 0) { content() }
        }
        .padding(.vertical, 2)
    }
}

/// 값 칩(시안 .sheet .row em): 고르면 브랜드 10% 바탕 + onAccentSoft 글자(행 판 위에서 accentInk는 4.08:1이라 5.7:1인 onAccentSoft,
/// 안드로이드와 같음), 안 고르면 먹색 8% + textSecondary. 15.5·600, 모서리 10, 높이 36 이상.
private struct ValueChip: View {
    let text: String
    var selected = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(text).font(DS.font(15.5, 600)).monospacedDigit()
                .foregroundStyle(selected ? DS.onAccentSoft : DS.textSecondary)
                .padding(.horizontal, 10).padding(.vertical, 7).frame(minHeight: 36)
                .background(selected ? DS.accent.opacity(0.1) : DS.textPrimary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .padding(.vertical, 4).contentShape(Rectangle())   // 보이는 칩은 36, 누르는 자리는 44(8장)
        }
        .buttonStyle(.press)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// 날짜·시각 칩: 누르면 그 칩에 붙은 작은 창에 시스템 고르기가 열린다(날짜는 달력, 시각은 바퀴). 지금 뒤는 고를 수 없다.
private struct PickerChip: View {
    let text: String
    var selected = true
    let a11y: String
    @Binding var date: Date
    let components: DatePickerComponents
    let zone: TimeZone
    /// 지금 뒤를 막는다(시작·시각). 끝 시각은 날을 맞추기 전 값이라 막지 않는다.
    var limitToNow = true
    var beforeOpen: () -> Void = {}
    @State private var open = false

    var body: some View {
        ValueChip(text: text, selected: selected) { beforeOpen(); open = true }
            .accessibilityLabel(a11y)
            .accessibilityValue(text)
            .popover(isPresented: $open) {
                let limit = limitToNow ? Date().addingTimeInterval(60) : Date.distantFuture
                Group {
                    if components == .date {
                        DatePicker(a11y, selection: $date, in: ...limit, displayedComponents: components).datePickerStyle(.graphical)
                    } else {
                        DatePicker(a11y, selection: $date, in: ...limit, displayedComponents: components).datePickerStyle(.wheel)
                    }
                }
                .labelsHidden()
                .environment(\.timeZone, zone)
                .padding(12)
                .presentationCompactAdaptation(.popover)
            }
    }
}

// MARK: 타이머

/// 타이머(C2, 시안 S.timer): 링(1시간에 한 바퀴, 초침 점) + 큰 경과 + "수유 중 · 왼쪽" → 쪽 세그먼트(젖병이면 양) → 끝내기 → 시작 시각 수정 → 이 기록 지우기.
/// 최대 길이를 넘으면 U14의 두 버튼.
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
                    VStack(spacing: 22) {
                        // 시안 S.timer 순서: 링 → 쪽(젖병이면 양) → 끝내기 → 시작 시각 수정 → 이 기록 지우기
                        TimelineView(.periodic(from: .now, by: 1)) { ctx in TimerRing(event: e, now: CareTime.ms(ctx.date)) }
                        if e.kind == .feed { feedControls(e) }
                        // 최대 길이 판정은 초마다(넘은 뒤 '끝내기'가 남아 끝 시각 모름으로 닫지 않게)
                        TimelineView(.periodic(from: .now, by: 1)) { ctx in
                            if CareRules.isOverLimit(e, now: CareTime.ms(ctx.date)) { VStack(spacing: 12) { overLimit(e) } }
                            else {
                                Button { vm.end(); dismiss() } label: {
                                    Label { Text(CareText.end) } icon: { IconImage(.square, size: 20) }
                                }
                                .buttonStyle(.pillBig)
                            }
                        }
                        Button { picked = CareTime.date(e.startAt); editingStart = true; pickingEnd = false } label: {
                            Label { Text(CareText.editStart) } icon: { IconImage(.clock, size: 20) }
                        }
                        .buttonStyle(.pillTonal)   // 시안 .pbtn.ton
                        if editingStart || pickingEnd { timePicker(e) }
                        if let error { Text(error).font(DS.caption).foregroundStyle(DS.danger) }
                        if vm.ui.guardian { Text(CareText.guardianNote).font(DS.caption).foregroundStyle(DS.textTertiary) }
                        Button(role: .destructive) { vm.delete(); dismiss() } label: { Text(CareText.deleteRecord) }
                            .buttonStyle(.pillDanger)
                    }
                    .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 24)
                }
                .screenBackground()
                .navigationTitle(CareText.quickLabel(e.kind))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { SheetToolbar(cancel: { dismiss() }) }
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
        Text(e.kind == .sleep ? CareText.overLimitSleep : CareText.overLimitFeed).typeStyle(.headline).multilineTextAlignment(.center)
        Button(CareText.pickEnd) { picked = Date(); pickingEnd = true; editingStart = false }
            .buttonStyle(.pill)
        Button(CareText.closeEndUnknown) { vm.endUnknown(); dismiss() }
            .buttonStyle(.pillTonal)
    }

    @ViewBuilder private func feedControls(_ e: CareEvent) -> some View {
        Segmented(title: CareText.rowSide, options: FeedSide.allCases, label: { $0.label }, selection: Binding(get: { e.feedSide }, set: { side in
            var x = e
            x.feedSide = side
            if side != .bottle { x.amountMl = nil }
            show(vm.save(x, allowOverlap: true), kind: e.kind)
        }))
        if e.feedSide == .bottle {
            SheetRows {
                AmountRow(amount: Binding(get: { e.amountMl }, set: { n in
                    var x = e
                    x.amountMl = n
                    show(vm.save(x, allowOverlap: true), kind: e.kind)
                }))
            }
        }
    }

    private func timePicker(_ e: CareEvent) -> some View {
        VStack(spacing: 12) {
            SheetRows {
                SheetRow(name: editingStart ? CareText.rowStart : CareText.rowEnd) {
                    DatePicker(editingStart ? CareText.rowStart : CareText.rowEnd, selection: $picked, in: ...Date().addingTimeInterval(60))
                        .labelsHidden().environment(\.timeZone, e.timeZone)   // R13: 목록처럼 그 기록의 시간대로 보인다
                }
            }
            Button {
                var x = e
                if editingStart { x.startAt = CareTime.ms(picked) } else { x.endAt = CareTime.ms(picked) }
                commitTime(x, allowOverlap: false)
            } label: { Text(CareText.save) }
            .buttonStyle(.pillTonal)
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

/// 타이머 링(시안 .tring 300): 회색 원 + 1시간에 한 바퀴 도는 종류 색 호 + 초마다 옮기는 점. 가운데 큰 경과와 "수유 중 · 왼쪽".
private struct TimerRing: View {
    let event: CareEvent
    let now: Int64
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .largeTitle) private var size: CGFloat = 300

    var body: some View {
        let ms = CareRules.elapsedMs(event, now: now)
        let hour = Double(ms % 3_600_000) / 3_600_000
        let seconds = Double(ms / 1000)   // 초는 누적(0으로 돌아가며 거꾸로 돌지 않게)
        let color = DS.color(event.kind)
        let d = min(size, 320)
        ZStack {
            Circle().stroke(DS.tonal, lineWidth: 3)
            Circle().trim(from: 0, to: hour).stroke(color, style: StrokeStyle(lineWidth: 3, lineCap: .round)).rotationEffect(.degrees(-90))
            Circle().fill(color).frame(width: 12, height: 12)
                .offset(y: -d / 2)
                .rotationEffect(.degrees(seconds * 6))
                .animation(reduceMotion ? nil : .linear(duration: 1), value: seconds)
            VStack(spacing: 12) {
                Text(CareFormat.timer(ms)).font(DS.timer).foregroundStyle(DS.textPrimary).lineLimit(1).minimumScaleFactor(0.5)
                    .contentTransition(.numericText())   // 1초마다 바뀐 자리만 굴러간다(시안 v4-7 4, roll)
                    .animation(reduceMotion ? nil : DS.Motion.roll, value: seconds)
                    .accessibilityLabel(CareFormat.duration(ms))
                Text(title).font(DS.font(15, 500)).foregroundStyle(DS.textTertiary)
            }
            .padding(.horizontal, 24)
        }
        .frame(width: d, height: d)
    }

    private var title: String {
        event.kind == .sleep ? CareText.sleepRunning : ([CareText.feedRunning] + [event.feedSide?.label].compactMap { $0 }).joined(separator: " · ")
    }
}

// MARK: 빠른 기록·고치기 시트

/// 빠른 기록(C3)·고치기(C4) 시트(시안 S.sheet): 행 묶음(시작·끝·메모) → 쪽 세그먼트(수유) → 지우기. 새 수유·잠은 시작만 적고 끝은 '끝 시각 모름'이 기본이다.
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

    /// 시작 날짜 칩: 날을 옮기면 끝도 같은 만큼 옮겨 길이를 지킨다(안드로이드와 같음).
    private var startDay: Binding<Date> {
        Binding(get: { start }, set: { moved in
            end = end.addingTimeInterval(moved.timeIntervalSince(start))   // 끝 모름이어도 옮겨 둔다(다시 시각을 고를 때 옛 날이 남지 않게)
            start = moved
        })
    }

    /// 끝 시각 칩: 고른 시·분을 시작 뒤 처음 오는 그 시각으로 둔다(시작보다 이르면 다음 날).
    /// 바퀴는 지금 뒤를 막지 않는다: 날을 맞추기 전 값이라 어제 밤 시각이 막힌다. 지금보다 늦은 끝은 저장 검사(R3)가 잡는다.
    private var endAfterStart: Binding<Date> {
        Binding(get: { end }, set: { end = afterStart($0) })
    }

    /// `picked`의 시·분이 시작 뒤 처음 오는 때. 시작과 같은 분이면 시작 그대로(초 때문에 다음 날로 넘기지 않는다), 이른 시각이면 다음 날.
    private func afterStart(_ picked: Date) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let hm = cal.dateComponents([.hour, .minute], from: picked)
        guard let same = cal.date(bySettingHour: hm.hour ?? 0, minute: hm.minute ?? 0, second: 0, of: start),
              let startMinute = cal.dateInterval(of: .minute, for: start)?.start else { return picked }
        return same < startMinute ? (cal.date(byAdding: .day, value: 1, to: same) ?? same) : max(same, start)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if original == nil && day == nil { chips }   // 몇 분 전 칩은 오늘 기준이다
                    SheetRows {
                        if original != nil || chip == nil {
                            // 시작(수유·잠) 또는 시각: 날짜 칩은 날을, 시각 칩은 시각을 고른다(시안 .sheet .row em, 안드로이드와 같음)
                            let name = hasDuration ? CareText.rowStart : CareText.rowTime
                            SheetRow(name: name) {
                                ChipLine {
                                    PickerChip(text: CareText.dayTitle(CareRules.dayKey(CareTime.ms(start), zone), now: Date(), zone: zone),
                                               a11y: name + " 날짜", date: startDay, components: .date, zone: zone)
                                    PickerChip(text: CareFormat.clock(start, zone), a11y: name + " 시각", date: $start, components: .hourAndMinute, zone: zone)
                                }
                            }
                            .onChange(of: start) { _, s in if endIsPlaceholder { end = max(end, s) } }
                        }
                        if hasDuration {
                            // 끝: '끝 시각 모름' 칩과 시각 칩. 시각만 고르고 날은 시작 뒤 처음 오는 그 시각이다(자정을 넘긴 잠, 안드로이드 firstAtOrAfter)
                            SheetRow(name: CareText.rowEnd) {
                                ChipLine {
                                    ValueChip(text: CareText.endUnknown, selected: endUnknown) { endUnknown = true }
                                    PickerChip(text: endUnknown ? CareText.chipCustom : CareFormat.clock(end, zone), selected: !endUnknown,
                                               a11y: CareText.rowEnd + " 시각", date: endAfterStart, components: .hourAndMinute, zone: zone, limitToNow: false) {
                                        // 끝 모름에서 돌아오면 보이는 시각을 시작 뒤로 맞춘다(시작을 그사이 옮겼어도)
                                        if endUnknown { endUnknown = false; end = endIsPlaceholder ? max(end, start) : afterStart(end) }
                                    }
                                }
                            }
                        }
                        if kind == .feed, side == .bottle { AmountRow(amount: $amount) }
                        SheetRow(name: CareText.rowNote) {
                            TextField(CareText.notePlaceholder, text: $note, axis: .vertical)
                                .font(DS.font(15.5, 400)).multilineTextAlignment(.trailing)
                                .accessibilityLabel(CareText.rowNote)
                                .onChange(of: note) { _, v in let l = CareRules.noteLimited(v); if l != v { note = l } }   // 200에서 더 받지 않는다(U17)
                        }
                    }
                    if kind == .feed {
                        Segmented(title: CareText.rowSide, options: FeedSide.allCases, label: { $0.label }, selection: $side)
                    }
                    if kind == .diaper {
                        Segmented(title: CareText.rowKind, options: DiaperKind.allCases, label: { CareText.diaperLabel($0) }, selection: $diaper)
                    }
                    if let error { Text(error).font(DS.caption).foregroundStyle(DS.danger) }
                    if vm.guardian { Text(CareText.guardianNote).font(DS.caption).foregroundStyle(DS.textTertiary) }
                    if let e = original {
                        Button(role: .destructive) { vm.delete(e.id); dismiss() } label: { Text(CareText.delete) }
                            .buttonStyle(.pillDanger).padding(.top, 8)
                    }
                }
                .padding(.horizontal, 18).padding(.top, 8).padding(.bottom, 24)
            }
            .scrollContentBackground(.hidden).background(DS.surface)   // 시안 .sheet: 흰 면 위에 행 판(먹색 4%)
            .navigationTitle(CareText.sheetTitle(kind, editing: original != nil))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                SheetToolbar(cancel: { dismiss() }, confirm: { save(allowOverlap: false) },
                             confirmDisabled: kind == .note && note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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
            HStack(spacing: 8) {
                chipButton(CareText.chipNow, 0)
                ForEach(CareText.chipMinutes, id: \.self) { chipButton(CareText.chip(minutes: $0), $0) }
                chipButton(CareText.chipCustom, nil)
            }
        }
    }

    /// 칩 선택은 먹색 채움(k-style), 나머지는 회색.
    private func chipButton(_ title: String, _ minutes: Int?) -> some View {
        let on = chip == minutes
        return Button {
            chip = minutes
            // 누른 때 기준으로 시작을 정한다(처음 '지금'은 시트를 연 때). 끝 기본값보다 늦어지지 않는다. '직접'은 지금 값에서 고른다.
            if let minutes { start = Date().addingTimeInterval(-Double(minutes * 60)); end = max(end, start) }   // 시트를 오래 열어 두어도 끝 ≥ 시작(R3)
        } label: {
            Text(title).font(DS.font(15, 600))
                .foregroundStyle(on ? DS.onFill : DS.textSecondary)
                .padding(.horizontal, 14).frame(minHeight: 44)
                .background(on ? DS.fill : DS.tonal, in: Capsule())
        }
        .buttonStyle(.press)
        .accessibilityAddTraits(on ? .isSelected : [])
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
