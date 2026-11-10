import SwiftUI

private typealias DS = DesignSystem

// 빠른 기록·고치기 시트(C3·C4)와 타이머(C2). 시안 S.timer·S.sheet: iOS는 떠 있는 글래스 시트, 머리 왼쪽 닫기·오른쪽 저장(파랑) 원 버튼.

struct CareSheetView: View {
    let sheet: CareSheet

    var body: some View {
        switch sheet {
        case .quick(let kind, let day): Scoped({ $0.careSheet(.new(kind, day: day)) }) { vm in if let d = vm.draft { CareEditSheet(draft: d) } }
        case .edit(let id): Scoped({ $0.careSheet(.edit(id)) }) { vm in if let d = vm.draft { CareEditSheet(draft: d) } }
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
                let limit = limitToNow ? CareRules.latestPick(now: Date()) : Date.distantFuture
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
                        if let error = vm.error { Text(error).font(DS.caption).foregroundStyle(DS.danger) }
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
                .alert(CareSaveResult.overlap.message(for: e.kind) ?? "",
                       isPresented: Binding(get: { vm.pendingTime != nil }, set: { if !$0 { vm.keepEditing() } })) {
                    Button(CareText.saveAnyway) { if vm.confirmTime() { timeSaved() } }
                    Button(CareText.fix, role: .cancel) { vm.keepEditing() }
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
        Segmented(title: CareText.rowSide, options: FeedSide.allCases, label: { $0.label }, selection: Binding(get: { e.feedSide }, set: { vm.setSide($0) }))
        if e.feedSide == .bottle {
            SheetRows { AmountRow(amount: Binding(get: { e.amountMl }, set: { vm.setAmount($0) })) }
        }
    }

    private func timePicker(_ e: CareEvent) -> some View {
        VStack(spacing: 12) {
            SheetRows {
                SheetRow(name: editingStart ? CareText.rowStart : CareText.rowEnd) {
                    DatePicker(editingStart ? CareText.rowStart : CareText.rowEnd, selection: $picked, in: ...CareRules.latestPick(now: Date()))
                        .labelsHidden().environment(\.timeZone, e.timeZone)   // R13: 목록처럼 그 기록의 시간대로 보인다
                }
            }
            Button {
                // 시각을 바꾸면 겹침을 먼저 묻는다(R4, 뷰모델이 pendingTime에 둔다)
                if vm.setTime(editingStart ? .start : .end, to: picked) { timeSaved() }
            } label: { Text(CareText.save) }
            .buttonStyle(.pillTonal)
        }
    }

    /// 시각을 저장했다: 시작 고침은 고르기를 닫고, 끝 고침(최대 길이를 넘은 기록)은 시트를 닫는다.
    private func timeSaved() {
        editingStart = false
        if pickingEnd { dismiss() }
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

/// 빠른 기록·고치기 시트(C3·C4, 시안 S.sheet): 몇 분 전 칩(새 기록·오늘) → 행 판(시작·끝·젖병 양·메모) → 쪽·기저귀 종류 → 지우기.
/// 화면은 `CareDraft`를 그리고 바꾸기만 한다. 칩 규칙·끝 맞춤·저장할 기록 만들기는 CareDraft, 저장 단계는 CareSheetViewModel이 한다.
struct CareEditSheet: View {
    @Environment(CareSheetViewModel.self) private var vm
    @Environment(\.dismiss) private var dismiss
    /// 지금 입력(뷰모델의 값을 그대로 받는다).
    let draft: CareDraft

    private var editing: Bool { draft.original != nil }
    private var zone: TimeZone { draft.zone }

    /// 입력 칸 바인딩: 읽기는 받은 값, 쓰기는 뷰모델로.
    private func field<T>(_ kp: WritableKeyPath<CareDraft, T>) -> Binding<T> {
        Binding(get: { draft[keyPath: kp] }, set: { v in vm.edit { $0[keyPath: kp] = v } })
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if draft.showsAgoChips { chips }
                    SheetRows {
                        if editing || draft.chip == nil {
                            // 시작(수유·잠) 또는 시각: 날짜 칩은 날을, 시각 칩은 시각을 고른다(시안 .sheet .row em, 안드로이드와 같음)
                            let name = draft.hasDuration ? CareText.rowStart : CareText.rowTime
                            SheetRow(name: name) {
                                ChipLine {
                                    PickerChip(text: CareText.dayTitle(CareRules.dayKey(CareTime.ms(draft.start), zone), now: Date(), zone: zone),
                                               a11y: name + " 날짜",
                                               date: Binding(get: { draft.start }, set: { d in vm.edit { $0.moveStartDay(to: d) } }),
                                               components: .date, zone: zone)
                                    PickerChip(text: CareFormat.clock(draft.start, zone), a11y: name + " 시각",
                                               date: Binding(get: { draft.start }, set: { d in vm.edit { $0.setStart(d) } }),
                                               components: .hourAndMinute, zone: zone)
                                }
                            }
                        }
                        if draft.hasDuration {
                            // 끝: '끝 시각 모름' 칩과 시각 칩. 시각만 고르고 날은 시작 뒤 처음 오는 그 시각이다(자정을 넘긴 잠)
                            SheetRow(name: CareText.rowEnd) {
                                ChipLine {
                                    ValueChip(text: CareText.endUnknown, selected: draft.endUnknown) { vm.edit { $0.endUnknown = true } }
                                    PickerChip(text: draft.endUnknown ? CareText.chipCustom : CareFormat.clock(draft.end, zone), selected: !draft.endUnknown,
                                               a11y: CareText.rowEnd + " 시각",
                                               date: Binding(get: { draft.end }, set: { d in vm.edit { $0.setEnd(picked: d) } }),
                                               components: .hourAndMinute, zone: zone, limitToNow: false) {
                                        vm.edit { $0.useEndTime() }
                                    }
                                }
                            }
                        }
                        if draft.kind == .feed, draft.side == .bottle { AmountRow(amount: field(\.amount)) }
                        SheetRow(name: CareText.rowNote) {
                            TextField(CareText.notePlaceholder, text: Binding(get: { draft.note }, set: { v in vm.edit { $0.note = CareRules.noteLimited(v) } }),
                                      axis: .vertical)   // 200에서 더 받지 않는다(U17)
                                .font(DS.font(15.5, 400)).multilineTextAlignment(.trailing)
                                .accessibilityLabel(CareText.rowNote)
                        }
                    }
                    if draft.kind == .feed {
                        Segmented(title: CareText.rowSide, options: FeedSide.allCases, label: { $0.label }, selection: field(\.side))
                    }
                    if draft.kind == .diaper {
                        Segmented(title: CareText.rowKind, options: DiaperKind.allCases, label: { CareText.diaperLabel($0) }, selection: field(\.diaper))
                    }
                    if case .failed(let m?) = vm.phase { Text(m).font(DS.caption).foregroundStyle(DS.danger) }
                    if vm.guardian { Text(CareText.guardianNote).font(DS.caption).foregroundStyle(DS.textTertiary) }
                    if editing {
                        Button(role: .destructive) { vm.delete() } label: { Text(CareText.delete) }
                            .buttonStyle(.pillDanger).padding(.top, 8)
                    }
                }
                .padding(.horizontal, 18).padding(.top, 8).padding(.bottom, 24)
            }
            .scrollContentBackground(.hidden).background(DS.surface)   // 시안 .sheet: 흰 면 위에 행 판(먹색 4%)
            .navigationTitle(CareText.sheetTitle(draft.kind, editing: editing))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                SheetToolbar(cancel: { dismiss() }, confirm: { vm.save() },
                             confirmDisabled: draft.kind == .note && draft.note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .alert(CareSaveResult.overlap.message(for: draft.kind) ?? "",
                   isPresented: Binding(get: { vm.phase == .confirmOverlap }, set: { if !$0 { vm.keepEditing() } })) {
                Button(CareText.saveAnyway) { vm.save(allowOverlap: true) }
                Button(CareText.fix, role: .cancel) { vm.keepEditing() }
            }
            .onChange(of: vm.phase) { _, p in if p == .closed { dismiss() } }   // 저장했거나 그사이 지워졌다(R10)
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
        let on = draft.chip == minutes
        return Button { vm.pick(chip: minutes) } label: {
            Text(title).font(DS.font(15, 600))
                .foregroundStyle(on ? DS.onFill : DS.textSecondary)
                .padding(.horizontal, 14).frame(minHeight: 44)
                .background(on ? DS.fill : DS.tonal, in: Capsule())
        }
        .buttonStyle(.press)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
