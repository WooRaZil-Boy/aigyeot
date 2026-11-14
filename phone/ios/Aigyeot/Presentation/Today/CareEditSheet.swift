import SwiftUI

private typealias DS = DesignSystem

// 기록 시트(빠른 기록·고치기·지난 기록 적기).
// 위 → 아래: 머리(X · 고치기면 휴지통) → (지난 기록만) 종류 칸 → (고치기) '고치는 중' → 질문 → (첫 기록) 도움말 → 고를 것 하나를 크게
// → 접힌 줄(한 번에 하나만 펼침) → 아래 고정: 보호자 안내·저장 실패 + 저장될 것을 말하는 단추 하나.
// 화면은 `CareDraft`를 그리고 바꾸기만 한다. 글·줄 값·단추 글은 CareDraft(두 앱 같은 이름), 저장 단계는 CareSheetViewModel.

/// 펼친 줄과 그 안의 시각 바퀴(화면 상태, 뷰모델에 두지 않는다 — 안드로이드 rememberSaveable와 같다). 한 번에 한 줄만 펼친다.
struct SheetFolds: Equatable {
    enum Wheel: Equatable { case start, end }
    private(set) var open: SheetField?
    private(set) var wheel: Wheel?

    /// 줄을 누른다: 접혀 있으면 펼치고(앞 줄은 접힌다), 펼쳐 있으면 접는다.
    mutating func toggle(_ f: SheetField) {
        open = open == f ? nil : f
        wheel = nil
    }

    /// 저장 검사에 걸린 줄을 펼친다.
    mutating func reveal(_ f: SheetField) {
        if open != f { wheel = nil }
        open = f
    }

    mutating func toggleWheel(_ w: Wheel) { wheel = wheel == w ? nil : w }
    mutating func closeWheel() { wheel = nil }
}

struct CareEditSheet: View {
    @Environment(CareSheetViewModel.self) private var vm
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize
    let draft: CareDraft

    @State private var folds = SheetFolds()
    @State private var heroWheel = false
    /// 단추의 확인 표시(실제로 저장되어 닫히는 중)와 성공 햅틱 계기. 둘 다 VM 닫힘 단계 `closed(saved:)`로만 정한다.
    @State private var saved = false
    @State private var successTick = 0
    /// 흔들림 계기(탓이 된 칸, 동작 줄이기면 올리지 않음)와 오류 햅틱 계기(동작 줄이기와 상관없이 실패마다)는 따로다.
    @State private var shakes = 0
    @State private var errorTick = 0
    /// 저장 검사에 걸린 줄로 굴리기. 실패 한 번(attempt)마다 새 값이라 같은 오류가 다시 나도 굴린다.
    @State private var scrollRequest: ScrollRequest?
    private struct ScrollRequest: Equatable { let field: SheetField; let attempt: Int }
    @State private var bodyHeight: CGFloat = 0
    @State private var footHeight: CGFloat = 0
    @ScaledMetric private var rowMin: CGFloat = 54

    private var editing: Bool { draft.original != nil }
    private var zone: TimeZone { draft.zone }
    private var problemField: SheetField? {
        if case .failed(_, let p?, _) = vm.phase { CareDraft.field(for: p) } else { nil }
    }
    private var problemText: String? {
        if case .failed(let m, _?, _) = vm.phase { m } else { nil }
    }
    /// 저장 실패(저장 공간)는 줄이 아니라 단추 바로 위.
    private var footError: String? {
        if case .failed(let m?, nil, _) = vm.phase { m } else { nil }
    }
    private var big: Bool { typeSize.isAccessibilitySize }
    private var smooth: Animation? { reduceMotion ? nil : DS.Motion.smooth }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollViewReader { proxy in
                ScrollView {
                    content
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { bodyHeight = $0 }
                }
                .scrollBounceBehavior(.basedOnSize)
                .scrollDismissesKeyboard(.interactively)
                // 탓이 된 줄(과 그 아래 오류 한 줄)이 본문 밖이면 그 자리가 보이게 굴린다. 펼침 다음 갱신이라 펼친 뒤의 배치로 굴린다. 동작 줄이기면 바로.
                // 줄 아래 끝(오류 한 줄)을 본문 아래 끝에 맞춘다: 큰 글자에서는 펼친 줄이 본문보다 커 가운데 맞춤이면 오류 글이 잘렸다
                .onChange(of: scrollRequest) { _, r in
                    guard let r else { return }
                    if reduceMotion {
                        proxy.scrollTo(r.field, anchor: .bottom)
                    } else {
                        withAnimation(DS.Motion.smooth) {
                            proxy.scrollTo(r.field, anchor: .bottom)
                        }
                    }
                }
            }
            footer
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { footHeight = $0 }
        }
        .background(DS.surface)
        // 시트 높이는 내용을 따라간다(줄을 펼치면 커진다, 높이는 spring.smooth). 넘치면 화면 높이에서 멈추고 본문이 굴러간다.
        .presentationDetents(bodyHeight > 0 ? [.height(headerHeight + bodyHeight + footHeight)] : [.medium])
        .presentationDragIndicator(.hidden)
        .animation(smooth, value: bodyHeight)
        .overlapConfirm(draft.kind, isPresented: Binding(get: { vm.phase == .confirmOverlap }, set: { if !$0 { vm.keepEditing() } }),
                        save: { vm.save(allowOverlap: true) }, close: { vm.keepEditing() })
        .onChange(of: vm.phase) { _, p in closed(p) }
        .sensoryFeedback(.error, trigger: errorTick)
        .sensoryFeedback(.success, trigger: successTick)
    }

    private let headerHeight: CGFloat = 56

    // MARK: 머리 — X(읽기 '취소') · 고치기면 휴지통(읽기 '지우기')

    private var header: some View {
        HStack {
            SheetIconButton(icon: .x, label: CareText.cancel) { dismiss() }
            Spacer()
            if editing { SheetIconButton(icon: .trash, label: CareText.delete) { vm.delete() }.disabled(!vm.canEdit) }
        }
        .padding(.horizontal, 12).padding(.top, 12)
        .frame(height: headerHeight, alignment: .bottom)
    }

    // MARK: 본문

    private var content: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !vm.kinds.isEmpty {
                OptionTiles(options: vm.kinds, selected: draft.kind, small: true, groupLabel: CareText.rowKind,
                            label: CareText.quickLabel, icon: kindIcon, tint: kindTint) { vm.setKind($0) }
            }
            question
            hero
            if !draft.foldRows.isEmpty { rows }
        }
        .padding(.horizontal, 14).padding(.top, 6).padding(.bottom, 4)
        .animation(smooth, value: draft.kind)
    }

    private var question: some View {
        VStack(alignment: .leading, spacing: 4) {
            if editing { Text(CareText.sheetEditing).typeStyle(.eyebrow).foregroundStyle(DS.textTertiary) }
            Text(CareText.sheetQuestion(draft.kind)).typeStyle(.sheetQuestion).foregroundStyle(DS.textPrimary)
                .accessibilityAddTraits(.isHeader)
                .contentTransition(.opacity)
            if vm.firstRecord, !editing {
                Text(CareText.sheetHintFirst).typeStyle(.rowSub).foregroundStyle(DS.textTertiary).padding(.top, 2)
            }
        }
        .padding(.horizontal, 4).padding(.top, 4)
    }

    // MARK: 고를 것 하나를 크게

    @ViewBuilder private var hero: some View {
        switch draft.kind {
        case .feed:
            OptionTiles(options: FeedSide.allCases, selected: draft.side, small: false, groupLabel: CareText.rowSide,
                        label: { $0.label }, sub: { $0 == vm.lastSide ? CareText.sheetLast : nil }, icon: sideIcon, tint: { _ in DS.cFeed }) { s in
                vm.edit { $0.side = $0.side == s ? nil : s }   // 다시 누르면 고르지 않은 것으로(안드로이드 toggleSide)
            }
            if draft.side == .bottle {
                VStack(alignment: .leading, spacing: 12) {
                    AmountBlock(amount: draft.amount, bad: problemField == .amount) { a in vm.edit { $0.amount = a } }
                        .modifier(Shake(trigger: shakes, active: problemField == .amount))
                    if problemField == .amount { errorLine }
                }
                .id(SheetField.amount)   // 오류면 굴려 갈 자리
            }
        case .diaper:
            OptionTiles(options: DiaperKind.allCases, selected: draft.diaper, small: false, groupLabel: CareText.rowKind,
                        label: CareText.diaperLabel, icon: diaperIcon, tint: { _ in DS.textPrimary }) { d in vm.edit { $0.diaper = d } }
        case .sleep:
            VStack(alignment: .leading, spacing: 12) {
                sleepStart
                if problemField == .start { errorLine }
            }
            .id(SheetField.start)   // 잠의 시작은 주인공 칸(줄이 아니다)
        case .note:
            MemoField(text: draft.note, big: true) { v in vm.edit { $0.note = CareRules.noteLimited(v) } }
                .padding(.horizontal, 14).padding(.vertical, 12)
                .background(DS.textPrimary.opacity(0.04), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
    }

    /// 잠: 큰 시작 시각 칸 + 몇 분 전 줄. 날짜·시각 알약은 '직접'이거나 칩이 없는 시트에서만.
    private var sleepStart: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                Text(CareText.rowStart).typeStyle(.caption).foregroundStyle(DS.textTertiary)
                BigClock(date: draft.start, zone: zone)
                if draft.showsPills { startPills(wheelOpen: heroWheel) { heroWheel.toggle() } }
                if heroWheel, draft.showsPills { startWheel.transition(.blurReplace) }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(DS.danger, lineWidth: problemField == .start ? 1.5 : 0))
            .padding(4)
            .modifier(Shake(trigger: shakes, active: problemField == .start))
            if draft.showsAgoChips {
                Rectangle().fill(DS.line).frame(height: 1).padding(.horizontal, 4)
                AgoStrip(selected: draft.chip) { vm.pick(chip: $0) }.padding(.horizontal, 4).padding(.bottom, 2)
            }
        }
        .background(DS.textPrimary.opacity(0.04), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .animation(smooth, value: heroWheel)
        .animation(smooth, value: draft.showsPills)
    }

    // MARK: 접힌 줄

    private var rows: some View {
        VStack(spacing: 0) {
            // 줄마다 한 덩어리(위 구분선 + 줄). 굴림 자리(id)는 ForEach의 줄 값이라, 구분선과 줄이 따로 있으면 위 구분선으로 굴러
            // 펼친 줄이 본문 밖에 남았다(큰 글자 실측)
            ForEach(Array(draft.foldRows.enumerated()), id: \.element) { i, f in
                VStack(spacing: 0) {
                    if i > 0 { Rectangle().fill(DS.line).frame(height: 1).padding(.horizontal, 12) }
                    foldRow(f)
                }
            }
        }
        .padding(.horizontal, 4)
        .background(DS.textPrimary.opacity(0.04), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .animation(smooth, value: folds)
    }

    @ViewBuilder private func foldRow(_ f: SheetField) -> some View {
        let open = folds.open == f
        let bad = problemField == f
        VStack(alignment: .leading, spacing: 0) {
            Button { withAnimation(smooth) { folds.toggle(f) } } label: { rowHead(f, open: open, bad: bad) }
                .buttonStyle(.plain)
                .accessibilityLabel(rowName(f))
                .accessibilityValue(rowA11yValue(f, open: open))
            if open {
                panel(f)
                    .padding(.horizontal, 8).padding(.bottom, 12)
                    .transition(reduceMotion ? .identity : AnyTransition(.blurReplace).combined(with: .offset(y: 8)))
            }
            if bad { errorLine.padding(.horizontal, 8).padding(.bottom, 10) }
        }
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(DS.danger, lineWidth: bad ? 1.5 : 0))
        .modifier(Shake(trigger: shakes, active: bad))
    }

    private func rowName(_ f: SheetField) -> String { draft.rowName(f) }

    private func rowValue(_ f: SheetField) -> String? {
        switch f {
        case .start: draft.startValue(now: Date())
        case .end: draft.endValue
        case .memo: draft.note.isEmpty ? nil : draft.note
        case .amount: nil
        }
    }

    /// 읽기 값: 줄 값 + 펼침 상태.
    private func rowA11yValue(_ f: SheetField, open: Bool) -> String {
        ([rowValue(f)].compactMap { $0 } + [open ? "펼침" : "접힘"]).joined(separator: ", ")
    }

    private func rowHead(_ f: SheetField, open: Bool, bad: Bool) -> some View {
        let add = f == .memo && draft.note.isEmpty
        let value = rowValue(f)
        let dim = f == .end && draft.endUnknown
        return Group {
            if big {   // 큰 글자: 값을 이름 아래로
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(rowName(f)).typeStyle(add ? .chip : .bodyMedium).foregroundStyle(add ? DS.accentInk : DS.textSecondary)
                        if let value { valueText(value, dim: dim, bad: bad).multilineTextAlignment(.leading) }
                    }
                    Spacer(minLength: 0)
                    rowIcon(add: add, open: open)
                }
            } else {
                HStack(spacing: 12) {
                    Text(rowName(f)).typeStyle(add ? .chip : .bodyMedium).foregroundStyle(add ? DS.accentInk : DS.textSecondary)
                    Spacer(minLength: 8)
                    if let value { valueText(value, dim: dim, bad: bad).lineLimit(1).multilineTextAlignment(.trailing) }
                    rowIcon(add: add, open: open)
                }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .frame(minHeight: rowMin)
        .contentShape(Rectangle())
    }

    private func valueText(_ s: String, dim: Bool, bad: Bool) -> some View {
        Text(s).typeStyle(dim ? .bodyMedium : .chip).monospacedDigit()
            .foregroundStyle(bad ? DS.danger : dim ? DS.textTertiary : DS.textPrimary)
            .contentTransition(.numericText())
            .animation(reduceMotion ? nil : DS.Motion.snappy, value: s)
    }

    private func rowIcon(add: Bool, open: Bool) -> some View {
        IconImage(add ? .plus : .chevronDown, size: 18)
            .foregroundStyle(add ? DS.accentInk : DS.textTertiary)
            .rotationEffect(.degrees(open && !add ? 180 : 0))
            .animation(reduceMotion ? nil : DS.Motion.fast, value: open)
            .accessibilityHidden(true)
    }

    @ViewBuilder private func panel(_ f: SheetField) -> some View {
        switch f {
        case .start:
            VStack(alignment: .leading, spacing: 8) {
                if draft.showsAgoChips { AgoStrip(selected: draft.chip) { vm.pick(chip: $0); folds.closeWheel() } }
                if draft.showsPills { startPills(wheelOpen: folds.wheel == .start) { withAnimation(smooth) { folds.toggleWheel(.start) } } }
                if folds.wheel == .start, draft.showsPills { startWheel.transition(.blurReplace) }
            }
            .animation(smooth, value: draft.showsPills)
        case .end:
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    Pill(text: CareText.endUnknown, on: draft.endUnknown) { vm.edit { $0.endUnknown = true }; folds.closeWheel() }
                    Pill(text: draft.endUnknown ? CareText.chipCustom : CareFormat.clock(draft.end, zone), on: !draft.endUnknown) {
                        withAnimation(smooth) { folds.toggleWheel(.end) }
                    }
                    .accessibilityLabel(CareText.sheetA11yEndTime)
                }
                if folds.wheel == .end {
                    // 끝은 고를 때만 정한다(열기만 하면 끝 모름 그대로). 날은 시작 뒤 처음 오는 그 시각(자정을 넘긴 잠)
                    Wheel(date: Binding(get: { draft.endShown }, set: { d in vm.edit { $0.setEnd(picked: d) } }), limit: .distantFuture, zone: zone)
                        .transition(.blurReplace)
                }
            }
        case .memo:
            MemoField(text: draft.note, big: false) { v in vm.edit { $0.note = CareRules.noteLimited(v) } }
                .padding(.horizontal, 10).padding(.top, 8).padding(.bottom, 4)
                .background(DS.segmentThumb, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        case .amount:
            EmptyView()
        }
    }

    /// 시작(또는 잠 큰 칸)의 날짜·시각 알약. 날짜는 달력 작은 창, 시각은 그 자리 바퀴.
    private func startPills(wheelOpen: Bool, toggleWheel: @escaping () -> Void) -> some View {
        let dayText = CareText.dayTitle(CareRules.dayKey(CareTime.ms(draft.start), zone), now: Date(), zone: zone)
        let time = CareFormat.clock(draft.start, zone)
        return HStack(spacing: 8) {
            DatePill(text: dayText, a11y: CareText.sheetA11yDate(dayText), zone: zone,
                     date: Binding(get: { draft.start }, set: { d in vm.edit { $0.moveStartDay(to: d) } }))
            Pill(text: time, on: wheelOpen, action: toggleWheel).accessibilityLabel(CareText.sheetA11yTime(time))
        }
    }

    private var startWheel: some View {
        Wheel(date: Binding(get: { draft.start }, set: { d in vm.edit { $0.setStart(d) } }), limit: CareRules.latestPick(now: Date()), zone: zone)
    }

    private var errorLine: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(problemText ?? "").typeStyle(.eyebrow).foregroundStyle(DS.danger)
        }
        .padding(.horizontal, 6)
        .accessibilityAddTraits(.updatesFrequently)
    }

    // MARK: 아래 고정

    private var footer: some View {
        VStack(spacing: 10) {
            if vm.guardian { Text(CareText.guardianNote).typeStyle(.caption).foregroundStyle(DS.textTertiary).frame(maxWidth: .infinity, alignment: .leading) }
            if let footError { Text(footError).typeStyle(.eyebrow).foregroundStyle(DS.danger).frame(maxWidth: .infinity, alignment: .leading) }
            SaveButton(label: draft.saveLabel(fit: !big), enabled: draft.savable, saved: saved) { vm.save() }
        }
        .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 14)
    }

    // MARK: 단계 바뀜

    private func closed(_ p: CareSheetViewModel.Phase) {
        switch p {
        case .closed(let wasSaved):
            // 겹침 확인 뒤 저장도 같은 길이다(저장 단추를 눌렀는지로 짐작하지 않는다)
            switch SheetCloseFeedback.on(saved: wasSaved, kind: draft.kind, reduceMotion: reduceMotion) {
            case .confirm:
                successTick += 1
                withAnimation(DS.Motion.bouncy) { saved = true }
                Task { try? await Task.sleep(for: .milliseconds(320)); dismiss() }
            case .hapticOnly:
                successTick += 1
                dismiss()
            case .none:
                dismiss()
            }
        case .failed(let m, let problem?, let attempt):
            // 실패 한 번에 한 묶음: 펼침 → 펼친 뒤 그 자리로 굴림 → 흔들림·오류 햅틱·읽기 알림.
            // 굴림은 펼침(spring.smooth 약 0.36초)이 끝난 배치로 한다: 펼치는 중에 굴리면 접힌 줄 높이로 맞춰 펼친 아래(오류 글)가 본문 밖에 남았다
            let f = CareDraft.field(for: problem)
            if let f {
                if f == .start, draft.kind == .sleep { heroWheel = false } else if draft.foldRows.contains(f) { withAnimation(smooth) { folds.reveal(f) } }
            }
            Task { @MainActor in
                if let f {
                    try? await Task.sleep(for: .milliseconds(reduceMotion ? 50 : 360))
                    guard vm.phase == p else { return }   // 그사이 다시 저장해 새 실패가 났으면 그 묶음이 맡는다
                    scrollRequest = ScrollRequest(field: f, attempt: attempt)
                }
                if !reduceMotion { shakes += 1 }
                errorTick += 1
                if let m { AccessibilityNotification.Announcement(m).post() }
            }
        case .failed(let m?, nil, _):
            errorTick += 1
            AccessibilityNotification.Announcement(m).post()
        default:
            break
        }
    }

    // MARK: 그림

    private func sideIcon(_ s: FeedSide) -> Ico {
        switch s {
        case .left: .sideLeft
        case .right: .sideRight
        case .both: .sideBoth
        case .bottle: .bottle
        }
    }

    private func diaperIcon(_ d: DiaperKind) -> Ico {
        switch d {
        case .wet: .droplet
        case .dirty: .poo
        case .both: .peePoo
        case .dry: .clean
        }
    }

    private func kindIcon(_ k: CareKind) -> Ico {
        switch k {
        case .feed: .bottle
        case .sleep: .moon
        case .diaper: .diaper
        case .note: .notebookPen
        }
    }

    private func kindTint(_ k: CareKind) -> Color {
        switch k {
        case .feed: DS.cFeed
        case .sleep: DS.cSleep
        case .diaper, .note: DS.textPrimary
        }
    }
}

// MARK: 부품

/// 머리 원 단추(44pt, 먹색 6%).
private struct SheetIconButton: View {
    let icon: Ico
    let label: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            IconImage(icon, size: 20).foregroundStyle(DS.textPrimary)
                .frame(width: 44, height: 44)
                .background(DS.textPrimary.opacity(0.06), in: Circle())
        }
        .buttonStyle(.press)
        .accessibilityLabel(label)
    }
}

/// 아이콘 칸(.oseg): 회색 판 + 흰 엄지 하나, 아이콘 위·이름 아래. 큰 칸(쪽·기저귀 종류, 높이 92) / 작은 칸(종류, 44).
/// 화면 읽기: 묶음 이름 + 항목마다 고름 상태(라디오처럼). '지난번'은 이름 뒤에 붙여 읽는다.
private struct OptionTiles<T: Hashable>: View {
    let options: [T]
    let selected: T?
    let small: Bool
    let groupLabel: String
    let label: (T) -> String
    var sub: (T) -> String? = { _ in nil }
    let icon: (T) -> Ico
    let tint: (T) -> Color
    let pick: (T) -> Void
    @Namespace private var ns
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize
    @ScaledMetric private var bigMin: CGFloat = 92

    var body: some View {
        let cols = typeSize.isAccessibilitySize ? 2 : options.count   // 큰 글자: 2×2
        // 칸은 넷뿐이라 게으른 격자를 쓰지 않는다(화면 밖 칸도 만들어 두어야 화면 읽기·굴림이 모든 칸을 찾는다)
        let rows = stride(from: 0, to: options.count, by: cols).map { Array(options[$0..<min($0 + cols, options.count)]) }
        VStack(spacing: 0) {
            ForEach(rows, id: \.self) { r in HStack(spacing: 0) { ForEach(r, id: \.self) { o in tile(o) } } }
        }
        .padding(small ? 3 : 4)
        .background(DS.textPrimary.opacity(0.04), in: RoundedRectangle(cornerRadius: small ? 16 : 20, style: .continuous))
        .animation(reduceMotion ? nil : DS.Motion.snappy, value: selected)
        .sensoryFeedback(.selection, trigger: selected)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(groupLabel)
    }

    private func tile(_ o: T) -> some View {
        let on = o == selected
        let s = sub(o)
        return Button { pick(o) } label: {
            Group {
                if small {
                    HStack(spacing: 6) {
                        IconImage(icon(o), size: 18).foregroundStyle(on ? tint(o) : DS.textSecondary)
                        Text(label(o)).typeStyle(.quickLabel)
                    }
                    .frame(maxWidth: .infinity, minHeight: 44)
                } else {
                    VStack(spacing: 6) {
                        IconImage(icon(o), size: 28).foregroundStyle(on ? tint(o) : DS.textSecondary)
                        Text(label(o)).typeStyle(.chip)
                        if let s { Text(s).typeStyle(.hourMark).foregroundStyle(DS.textTertiary).padding(.top, -2) }
                    }
                    .padding(.vertical, 9)
                    .frame(maxWidth: .infinity, minHeight: bigMin)
                }
            }
            .foregroundStyle(on ? DS.textPrimary : DS.textSecondary)
            .background {
                if on {
                    RoundedRectangle(cornerRadius: small ? 13 : 16, style: .continuous).fill(DS.segmentThumb)
                        .shadow(color: .black.opacity(0.08), radius: 6, y: 2)
                        .matchedGeometryEffect(id: "thumb", in: ns)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.press)
        .accessibilityLabel([label(o), s].compactMap { $0 }.joined(separator: ", "))
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// 몇 분 전 한 줄(.agos): 글자 + 먹색 엄지 하나, 넘치면 가로로 굴린다. 누르는 자리 44.
private struct AgoStrip: View {
    let selected: Int?
    let pick: (Int?) -> Void
    @Namespace private var ns
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let items: [(Int?, String)] = [(0, CareText.chipNow)] + CareText.chipMinutes.map { ($0, CareText.chip(minutes: $0)) } + [(nil, CareText.chipCustom)]

    var body: some View {
        ViewThatFits(in: .horizontal) {
            strip
            ScrollView(.horizontal, showsIndicators: false) { strip }
        }
        .animation(reduceMotion ? nil : DS.Motion.snappy, value: selected)
        .sensoryFeedback(.selection, trigger: selected)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(CareText.sheetA11yAgo)
    }

    private var strip: some View {
        HStack(spacing: 2) {
            ForEach(items, id: \.1) { m, text in
                let on = m == selected
                Button { pick(m) } label: {
                    Text(text).typeStyle(.quickLabel).lineLimit(1).fixedSize()   // 줄이지 않는다: 한 줄에 안 들면 ViewThatFits가 가로 굴림으로 바꾼다
                        .foregroundStyle(on ? DS.onFill : DS.textSecondary)
                        .padding(.horizontal, 10).padding(.vertical, 8)
                        .background {
                            if on { Capsule().fill(DS.fill).matchedGeometryEffect(id: "ago", in: ns) }
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.press)
                .accessibilityAddTraits(on ? .isSelected : [])
                .frame(maxWidth: .infinity)
            }
        }
    }
}

/// 알약(.pill8): 보이는 36, 누르는 자리 44. 고르면 먹색 채움.
private struct Pill: View {
    let text: String
    var on = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(text).typeStyle(.quickLabel).monospacedDigit()
                .foregroundStyle(on ? DS.onFill : DS.textSecondary)
                .padding(.horizontal, 14).frame(minHeight: 36)
                .background(on ? DS.fill : DS.textPrimary.opacity(0.07), in: Capsule())
                .padding(.vertical, 4).contentShape(Rectangle())
                .contentTransition(.numericText())
        }
        .buttonStyle(.press)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// 날짜 알약: 누르면 그 알약에 붙은 작은 창에 달력(지금 뒤는 고를 수 없다).
private struct DatePill: View {
    let text: String
    let a11y: String
    let zone: TimeZone
    @Binding var date: Date
    @State private var open = false
    var body: some View {
        Pill(text: text) { open = true }
            .accessibilityLabel(a11y)
            .popover(isPresented: $open) {
                DatePicker(a11y, selection: $date, in: ...CareRules.latestPick(now: Date()), displayedComponents: .date)
                    .datePickerStyle(.graphical).labelsHidden()
                    .environment(\.timeZone, zone)
                    .padding(12)
                    .presentationCompactAdaptation(.popover)
            }
    }
}

/// 그 자리 시각 바퀴(UIDatePicker .wheels).
private struct Wheel: View {
    @Binding var date: Date
    let limit: Date
    let zone: TimeZone
    var body: some View {
        DatePicker("", selection: $date, in: ...limit, displayedComponents: .hourAndMinute)
            .datePickerStyle(.wheel).labelsHidden()
            .environment(\.timeZone, zone)
            .frame(maxWidth: .infinity)
    }
}

/// 잠의 큰 시작 시각: 작은 '오후' + 큰 '1:13'.
private struct BigClock: View {
    let date: Date
    let zone: TimeZone
    var body: some View {
        let full = CareFormat.clock(date, zone)
        let parts = full.split(separator: " ", maxSplits: 1).map(String.init)
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            if parts.count == 2 { Text(parts[0]).typeStyle(.chip).foregroundStyle(DS.textSecondary) }
            Text(parts.last ?? full).typeStyle(.hero).monospacedDigit().foregroundStyle(DS.textPrimary)
                .contentTransition(.numericText())
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(CareText.rowStart)
        .accessibilityValue(full)
    }
}

/// 젖병 양: 작은 '양' + 큰 숫자 + 'ml' + 둥근 −/＋(44pt, ±10ml). 화면 읽기는 한 요소로 묶어 위아래 쓸기로 조절한다.
private struct AmountBlock: View {
    let amount: Int?
    let bad: Bool
    let set: (Int?) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private func up() { set(min(CareRules.amountRange.upperBound, (amount ?? 0) + 10)) }
    private func down() { set(amount.map { max(CareRules.amountRange.lowerBound, $0 - 10) }) }

    var body: some View {
        HStack {
            stepButton(.minus, enabled: (amount ?? 0) > CareRules.amountRange.lowerBound, action: down)
            VStack(spacing: 2) {
                Text(CareText.rowAmount).typeStyle(.caption).foregroundStyle(DS.textTertiary)
                HStack(alignment: .firstTextBaseline, spacing: 3) {
                    Text(amount.map(String.init) ?? "—").typeStyle(.amountNumber).monospacedDigit()
                        .foregroundStyle(amount == nil ? DS.textTertiary : bad ? DS.danger : DS.textPrimary)
                        .contentTransition(.numericText(countsDown: false))
                        .animation(reduceMotion ? nil : DS.Motion.roll, value: amount)
                    Text("ml").typeStyle(.headline).foregroundStyle(DS.textTertiary)
                }
            }
            .frame(maxWidth: .infinity)
            stepButton(.plus, enabled: (amount ?? 0) < CareRules.amountRange.upperBound, action: up)
        }
        .padding(12)
        .background(DS.textPrimary.opacity(0.04), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(DS.danger, lineWidth: bad ? 1.5 : 0))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(CareText.rowAmount)
        .accessibilityValue(amount.map(CareText.ml) ?? "")
        .accessibilityAdjustableAction { d in
            switch d {
            case .increment: up()
            case .decrement: down()
            @unknown default: break
            }
        }
    }

    private func stepButton(_ icon: Ico, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            IconImage(icon, size: 22).foregroundStyle(enabled ? DS.textPrimary : DS.textTertiary)
                .frame(width: 44, height: 44)
                .background(enabled ? DS.segmentThumb : .clear, in: Circle())
                .shadow(color: .black.opacity(enabled ? 0.08 : 0), radius: 1, y: 1)
        }
        .buttonStyle(.press)
        .disabled(!enabled)
    }
}

/// 메모 글칸: 자리 글 '예: 트림 잘 함', 150자부터 '{n}/200'. 메모 종류는 큰 칸.
private struct MemoField: View {
    let text: String
    let big: Bool
    let set: (String) -> Void
    @FocusState private var focused: Bool
    @ScaledMetric private var bigMin: CGFloat = 128

    var body: some View {
        let n = text.unicodeScalars.count
        ZStack(alignment: .bottomTrailing) {
            TextField(CareText.notePlaceholder, text: Binding(get: { text }, set: set), axis: .vertical)
                .typeStyle(big ? .dateNumber : .bodyMedium)
                .foregroundStyle(DS.textPrimary)
                .focused($focused)
                .frame(maxWidth: .infinity, minHeight: big ? bigMin : 44, alignment: .topLeading)
                .padding(.bottom, 20)
                .accessibilityLabel(CareText.rowNote)
            if focused || n >= 150 {
                Text("\(n)/\(CareRules.noteMax)").typeStyle(.micro).monospacedDigit().foregroundStyle(DS.textTertiary)
            }
        }
    }
}

/// 저장 단추 하나(.cta: 56·모서리 16·700/17). 글은 저장될 것, 저장되면 확인 표시.
private struct SaveButton: View {
    let label: String
    let enabled: Bool
    let saved: Bool
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            ZStack {
                if saved {
                    IconImage(.check, size: 26).transition(.scale(scale: 0.5).combined(with: .opacity))
                } else {
                    Text(label).typeStyle(.strong).multilineTextAlignment(.center).lineLimit(2)
                        .contentTransition(.numericText())
                        .animation(reduceMotion ? nil : DS.Motion.snappy, value: label)
                }
            }
            .foregroundStyle(enabled ? DS.onAccent : DS.textTertiary)
            .padding(.horizontal, 16).padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(enabled ? DS.accent : DS.textPrimary.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.press)
        .disabled(!enabled || saved)
        .accessibilityLabel(label)
    }
}

/// 오류 흔들림(360ms, 0 → −6 → 6 → −4 → 4 → −2 → 0). trigger가 바뀔 때 한 번.
private struct Shake: ViewModifier {
    let trigger: Int
    /// 탓이 된 칸만 흔든다.
    let active: Bool
    func body(content: Content) -> some View {
        content.keyframeAnimator(initialValue: CGFloat(0), trigger: trigger) { v, x in v.offset(x: active ? x : 0) } keyframes: { _ in
            for x in [-6, 6, -4, 4, -2, 0] as [CGFloat] { CubicKeyframe(x, duration: 0.06) }
        }
    }
}
