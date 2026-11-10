import Foundation
import Observation

// 돌봄 기록 화면(phone-care-plan 5·7장)의 뷰모델(안드로이드 CareViewModels.kt). 원본은 care.json(CareRepository)·state.json(PhoneStateRepository)이고,
// 화면 상태는 그것에서 계산한다(P4).

// MARK: 탭 묶음

/// 탭 모양(guardian은 차 탭 없음)과 설정 끊기 끝(오늘로).
struct CareTabsUiState: Equatable {
    var guardian: Bool
    var disconnectDone: Bool
}

func careTabsUi(_ c: Connection, _ run: DisconnectRun?) -> CareTabsUiState {
    CareTabsUiState(guardian: c.creds?.role == .guardian, disconnectDone: run == .done)
}

/// 탭 묶음과 "지웠어요" + "되돌리기"(5초, U38) 띠. 기록 탭 밀기, 고치기 시트, 타이머 어디서 지워도 같다.
@MainActor
@Observable
final class CareTabsViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let notices: Notices
    @ObservationIgnored private let recordCare: RecordCare
    @ObservationIgnored private let disconnect: Disconnect

    init(state: PhoneStateRepository, notices: Notices, recordCare: RecordCare, disconnect: Disconnect) {
        stateRepo = state
        self.notices = notices
        self.recordCare = recordCare
        self.disconnect = disconnect
    }

    var ui: CareTabsUiState { careTabsUi(stateRepo.connection, disconnect.run) }

    /// 떠 있는 되돌리기 띠(Notices.undo, S9·U64). 5초와 다른 띠로 바뀜은 RecordCare가 정하고 화면은 그리기만 한다.
    var undo: CareUndo? { notices.undo }
    func undo(_ u: CareUndo) { recordCare.undo(u) }
    /// 끊은 뒤 오늘·띠를 한 번 그렸다(U25 b).
    func disconnectShown() { disconnect.shown() }
}

// MARK: 오늘

/// 오늘 탭(C1, 5장, R12·R15·R16).
struct CareUiState {
    var care: CareFile
    var phone: PhoneState
    var connection: Connection
    var online: Bool

    var role: PhoneRole? { connection.creds?.role }
    var isPaired: Bool { connection.creds != nil }
    var guardian: Bool { role == .guardian }
    var owner: Bool { role == .owner }
    /// 마지막 수유 시작: 기록과 차 값 중 늦은 것(R12).
    var lastFeedingStart: Date? { FeedRules.lastFeedingStart(phone, care) }
    func shownStart(_ e: CareEvent) -> Date { FeedRules.shownStart(e, in: phone) }
    func feedingRecord(for e: CareEvent) -> FeedingRecord? { FeedRules.record(for: e, in: phone) }

    /// 마지막 수유 막대(오늘 카드): 차에 정해 둔 간격, 없으면 앞 6일 '평소'(CareStats). 둘 다 없으면 nil(안드로이드 feedMeter와 같다).
    /// guardian은 차 값을 받지 않는다(U35).
    func feedMeter(last: Int64, now: Int64) -> (value: Double, tick: Double?)? {
        let f = guardian ? nil : phone.feeding
        return ChartData.feedMeter(last: last, now: now, carLast: f?.lastStart.map(CareTime.ms), windowStart: f?.windowStartExpected.map(CareTime.ms),
                                   usualMs: CareStats.usualFeedGapMs(care.events, now: now, zone: .current))
    }
}

/// 오늘 탭(C1). guardian은 인계 알림·최근 리포트도 본다.
@MainActor
@Observable
final class TodayViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let connection: KeepConnected
    @ObservationIgnored private let recordCare: RecordCare

    init(state: PhoneStateRepository, care: CareRepository, connection: KeepConnected, recordCare: RecordCare) {
        stateRepo = state
        careRepo = care
        self.connection = connection
        self.recordCare = recordCare
    }

    var ui: CareUiState {
        CareUiState(care: careRepo.care, phone: stateRepo.state, connection: stateRepo.connection, online: connection.status.online)
    }

    /// 빠른 버튼·카드의 수유·잠 시작.
    func start(_ kind: CareKind) { recordCare.start(kind) }
    /// 당겨서 새로고침(7.2).
    func retryNow() async { await connection.retryNow() }
}

// MARK: 타이머

/// 타이머(C2). 끝났거나 지운 기록이면 `event`가 없고 화면이 닫힌다.
struct TimerUiState {
    var event: CareEvent?
    var guardian: Bool
}

func timerUi(id: String, care: CareFile, connection: Connection) -> TimerUiState {
    TimerUiState(event: care.events.first { $0.id == id }, guardian: connection.creds?.role == .guardian)
}

@MainActor
@Observable
final class TimerViewModel {
    @ObservationIgnored private let id: String
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let recordCare: RecordCare

    init(id: String, state: PhoneStateRepository, care: CareRepository, recordCare: RecordCare) {
        self.id = id
        stateRepo = state
        careRepo = care
        self.recordCare = recordCare
    }

    var ui: TimerUiState { timerUi(id: id, care: careRepo.care, connection: stateRepo.connection) }

    /// 시트 안에 보일 저장 실패·규칙 글(띠의 다른 안내까지 옮기지 않는다).
    private(set) var error: String?
    /// 겹침을 물을 시각 고침(R4). '그래도 저장'이면 `confirmTime()`.
    private(set) var pendingTime: CareEvent?

    /// 쪽 고침(젖병이 아니면 양을 비운다, `CareRules.normalized`). 쪽·양은 시각을 바꾸지 않아 겹침을 묻지 않는다.
    func setSide(_ side: FeedSide?) { change { $0.feedSide = side } }
    func setAmount(_ ml: Int?) { change { $0.amountMl = ml } }

    enum TimeField { case start, end }

    /// 시작·끝 시각 고침. 겹치면 `pendingTime`에 두고 묻는다. 저장했으면 true.
    @discardableResult
    func setTime(_ field: TimeField, to picked: Date) -> Bool {
        guard var e = ui.event else { return false }
        switch field {
        case .start: e.startAt = CareTime.ms(picked)
        case .end: e.endAt = CareTime.ms(picked)
        }
        return commit(e, allowOverlap: false)
    }

    /// 겹침을 묻는 창에서 '그래도 저장'. 저장했으면 true.
    @discardableResult
    func confirmTime() -> Bool {
        guard let e = pendingTime else { return false }
        pendingTime = nil
        return commit(e, allowOverlap: true)
    }

    func keepEditing() { pendingTime = nil }

    private func change(_ edit: (inout CareEvent) -> Void) {
        guard var e = ui.event else { return }
        edit(&e)
        commit(CareRules.normalized(e), allowOverlap: true)
    }

    @discardableResult
    private func commit(_ e: CareEvent, allowOverlap: Bool) -> Bool {
        let r = recordCare.saveEvent(e, allowOverlap: allowOverlap)
        if r == .overlap { pendingTime = e; return false }
        error = r == .notSaved ? NoticeRules.saveFailed : r.message(for: e.kind)
        return r == .saved
    }
    func end() { recordCare.end(id) }
    func endUnknown() { recordCare.endUnknown(id) }
    func delete() { recordCare.delete(id) }
}

// MARK: 빠른 기록·고치기 시트

/// 기록 시트가 고치는 것: 새 기록(종류, 기록 탭에서 고른 날) 또는 고칠 기록 id.
enum CareSheetTarget: Equatable {
    case new(CareKind, day: String?)
    case edit(String)
}

/// 빠른 기록·고치기 시트(C3·C4). 입력은 `draft`(CareDraft)이고 시트는 그 값을 바꾸기만 한다. 저장 단계는 `phase`다
/// (안드로이드 CareSheetViewModel과 같은 이름: 고치는 중 → 겹침 묻기 → 실패, 저장했거나 그사이 지워졌으면 닫힘).
@MainActor
@Observable
final class CareSheetViewModel {
    enum Phase: Equatable {
        case editing
        /// 같은 종류와 시간이 겹친다(R4). '그래도 저장'이면 `save(allowOverlap: true)`.
        case confirmOverlap
        /// 저장하지 않았다. 시트 안에 보일 문구(규칙 위반, 또는 저장 실패 — 띠의 다른 안내까지 옮기지 않는다). 문구가 없는 규칙도 있다.
        case failed(String?)
        /// 저장했거나, 고치던 기록이 그사이 지워졌다(되살리지 않는다, R10). 시트를 닫는다.
        case closed
    }

    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let recordCare: RecordCare
    @ObservationIgnored private let now: () -> Date
    /// 시트 입력. 고칠 기록을 찾지 못했으면 없다(시트를 그리지 않는다).
    private(set) var draft: CareDraft?
    private(set) var phase: Phase = .editing

    init(target: CareSheetTarget, state: PhoneStateRepository, care: CareRepository, recordCare: RecordCare, now: @escaping () -> Date) {
        stateRepo = state
        careRepo = care
        self.recordCare = recordCare
        self.now = now
        switch target {
        case .new(let kind, let day): draft = .new(kind: kind, day: day, now: now())
        case .edit(let id): draft = care.care.events.first { $0.id == id && $0.deletedAt == nil }.map(CareDraft.editing)
        }
    }

    var guardian: Bool { stateRepo.role == .guardian }

    /// 입력 칸을 고친다(시트가 바꾸는 것은 이 값뿐). 실패 글은 다음 저장까지 그대로 둔다.
    func edit(_ change: (inout CareDraft) -> Void) {
        guard var d = draft else { return }
        change(&d)
        draft = d
    }

    /// 몇 분 전 칩. 누른 때 기준이다.
    func pick(chip minutes: Int?) { edit { $0.pick(chip: minutes, now: now()) } }

    func save(allowOverlap: Bool = false) {
        guard let d = draft else { return }
        let e = d.event(now: now())
        if d.original != nil, !careRepo.care.events.contains(where: { $0.id == e.id && $0.deletedAt == nil }) { phase = .closed; return }
        let r = recordCare.saveEvent(e, allowOverlap: allowOverlap)
        switch r {
        case .saved: phase = .closed
        case .overlap: phase = .confirmOverlap
        case .notSaved: phase = .failed(NoticeRules.saveFailed)
        case .problem: phase = .failed(r.message(for: e.kind))
        }
    }

    /// 겹침을 묻는 창에서 '고치기'.
    func keepEditing() { if phase == .confirmOverlap { phase = .editing } }

    /// 고치던 기록을 지운다(되돌리기 띠는 RecordCare가 띄운다).
    func delete() {
        guard let id = draft?.original?.id else { return }
        recordCare.delete(id)
        phase = .closed
    }
}
