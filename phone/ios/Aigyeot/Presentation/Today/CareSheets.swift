import SwiftUI

private typealias DS = DesignSystem

// 타이머(C2, 시안 S.timer)와 시트 입구. 기록 시트(빠른 기록·고치기·지난 기록)는 CareEditSheet.swift(v8 2판 안 1).

struct CareSheetView: View {
    let sheet: CareSheet

    var body: some View {
        switch sheet {
        case .quick(let kind, let day): Scoped({ $0.careSheet(.new(kind, day: day)) }) { vm in if let d = vm.draft { CareEditSheet(draft: d) } }
        case .past(let day): Scoped({ $0.careSheet(.new(.feed, day: day, chooseKind: true)) }) { vm in if let d = vm.draft { CareEditSheet(draft: d) } }   // U86
        case .edit(let id): Scoped({ $0.careSheet(.edit(id)) }) { vm in if let d = vm.draft { CareEditSheet(draft: d) } }
        case .timer(let id): Scoped({ $0.timer(id: id) }) { _ in TimerView() }
        }
    }
}

/// 타이머 머리 버튼: iOS 26은 시스템 글래스 원(아이콘), 그 아래는 글자 버튼.
private struct SheetToolbar: ToolbarContent {
    let cancel: () -> Void

    var body: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button(action: cancel) {
                if #available(iOS 26, *) { IconImage(.x, size: 20).foregroundStyle(DS.textPrimary) } else { Text(CareText.cancel) }   // 시안 .gbtn: 먹색 X
            }
            .accessibilityLabel(CareText.cancel)
        }
    }
}

/// 젖병 양 행: "양" + "{n}ml". 값이 없으면 + 로 10ml부터 넣는다.
private struct AmountRow: View {
    @Binding var amount: Int?

    var body: some View {
        HStack {
            Text(CareText.rowAmount).typeStyle(.sheetRow).foregroundStyle(DS.textPrimary)
            Spacer()
            Stepper {
                Text(amount.map(CareText.ml) ?? "").typeStyle(.sheetValue).monospacedDigit().foregroundStyle(DS.textPrimary)   // 옅은 판 위 파랑 글자는 4.5:1 미만
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
            Text(name).typeStyle(.sheetRow).foregroundStyle(DS.textPrimary)
            Spacer(minLength: 8)
            value()
        }
        .frame(minHeight: 50)
        .modifier(RowLine())
    }
}

// MARK: 타이머

/// 타이머(C2, 시안 S.timer): 링(1시간에 한 바퀴, 초침 점) + 큰 경과 + "수유 중 · 왼쪽" → 쪽 세그먼트(젖병이면 양) → 끝내기 → 시작 시각 고치기 → 이 기록 지우기.
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
                        // 시안 S.timer 순서: 링 → 쪽(젖병이면 양) → 끝내기 → 시작 시각 고치기 → 이 기록 지우기
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
                        if let error = vm.error { Text(error).typeStyle(.caption).foregroundStyle(DS.danger) }
                        if vm.ui.guardian { Text(CareText.guardianNote).typeStyle(.caption).foregroundStyle(DS.textTertiary) }
                        Button(role: .destructive) { vm.delete(); dismiss() } label: { Text(CareText.deleteRecord) }
                            .buttonStyle(.pillDanger)
                    }
                    .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 24)
                }
                .screenBackground()
                .navigationTitle(CareText.quickLabel(e.kind))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { SheetToolbar(cancel: { dismiss() }) }
                .overlapConfirm(e.kind, isPresented: Binding(get: { vm.pendingTime != nil }, set: { if !$0 { vm.keepEditing() } }),
                                save: { if vm.confirmTime() { timeSaved() } }, close: { vm.keepEditing() })
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
                .animation(reduceMotion ? nil : DS.Motion.secondHand, value: seconds)
            VStack(spacing: 12) {
                Text(CareFormat.timer(ms)).typeStyle(.timer, monospacedDigits: true).foregroundStyle(DS.textPrimary).lineLimit(1).minimumScaleFactor(0.5)
                    .contentTransition(.numericText())   // 1초마다 바뀐 자리만 굴러간다(시안 v4-7 4, roll)
                    .animation(reduceMotion ? nil : DS.Motion.roll, value: seconds)
                    .accessibilityLabel(CareFormat.duration(ms))
                Text(title).typeStyle(.bodyMedium).foregroundStyle(DS.textTertiary)
            }
            .padding(.horizontal, 24)
        }
        .frame(width: d, height: d)
    }

    private var title: String {
        event.kind == .sleep ? CareText.sleepRunning : ([CareText.feedRunning] + [event.feedSide?.label].compactMap { $0 }).joined(separator: " · ")
    }
}

extension View {
    /// 겹침 확인 창(R4·U71, 7장 표): 제목 '그래도 저장할까요?' / 본문 겹침 글 / '저장'·'닫기'(닫으면 시트·타이머로 돌아가 고친다). 시트·타이머 한 곳.
    func overlapConfirm(_ kind: CareKind, isPresented: Binding<Bool>, save: @escaping () -> Void, close: @escaping () -> Void) -> some View {
        alert(CareText.overlapTitle, isPresented: isPresented) {
            Button(CareText.save, action: save)
            Button(CareText.closeDialog, role: .cancel, action: close)
        } message: {
            Text(CareSaveResult.overlap.message(for: kind) ?? "")
        }
    }
}
