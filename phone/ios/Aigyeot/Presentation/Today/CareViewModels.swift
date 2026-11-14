import Foundation
import Observation

// 돌봄 기록 화면의 뷰모델(안드로이드 CareViewModels.kt). 원본은 care.json(CareRepository)·state.json(PhoneStateRepository)이고,
// 화면 상태는 그것에서 계산한다.

// MARK: 탭 묶음

/// 탭 모양(guardian은 차 탭 없음)과 설정 끊기 끝(오늘로).
struct CareTabsUiState: Equatable {
    var guardian: Bool
    var disconnectDone: Bool
}

func careTabsUi(_ c: Connection, _ run: DisconnectRun?) -> CareTabsUiState {
    CareTabsUiState(guardian: c.creds?.role == .guardian, disconnectDone: run == .done)
}

/// 탭 묶음과 "지웠어요" + "되돌리기"(5초) 띠. 기록 탭 밀기, 고치기 시트, 타이머 어디서 지워도 같다.
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

    /// 떠 있는 되돌리기 띠(Notices.undo). 5초와 다른 띠로 바뀜은 RecordCare가 정하고 화면은 그리기만 한다.
    var undo: CareUndo? { notices.undo }
    func undo(_ u: CareUndo) { recordCare.undo(u) }
    /// 끊은 뒤 오늘·띠를 한 번 그렸다.
    func disconnectShown() { disconnect.shown() }
}

// MARK: 오늘

/// 오늘 탭.
struct CareUiState {
    var care: CareFile
    var phone: PhoneState
    var connection: Connection
    var online: Bool

    var role: PhoneRole? { connection.creds?.role }
    var isPaired: Bool { connection.creds != nil }
    var guardian: Bool { role == .guardian }
    var owner: Bool { role == .owner }
    /// 마지막 수유 시작: 기록과 차 값 중 늦은 것.
    var lastFeedingStart: Date? { FeedRules.lastFeedingStart(phone, care) }
    func shownStart(_ e: CareEvent) -> Date { FeedRules.shownStart(e, in: phone) }
    func feedingRecord(for e: CareEvent) -> FeedingRecord? { FeedRules.record(for: e, in: phone) }

    /// 마지막 수유 막대(오늘 카드): 차에 정해 둔 간격, 없으면 앞 6일 '평소'(CareStats). 둘 다 없으면 nil(안드로이드 feedMeter와 같다).
    /// guardian은 차 값을 받지 않는다.
    func feedMeter(last: Int64, now: Int64) -> (value: Double, tick: Double?)? {
        let f = guardian ? nil : phone.feeding
        return ChartData.feedMeter(last: last, now: now, carLast: f?.lastStart.map(CareTime.ms), windowStart: f?.windowStartExpected.map(CareTime.ms),
                                   usualMs: CareStats.usualFeedGapMs(care.events, now: now, zone: .current))
    }
}

/// 오늘 탭. guardian은 인계 알림·최근 리포트도 본다.
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
    /// 시트로 막 저장한 기록(오늘 하루 띠 새 표시). 띠가 한 번 보이고 걷는다. 지운 기록이면 없다.
    var fresh: CareEvent? { recordCare.fresh.flatMap { id in careRepo.care.events.first { $0.id == id && $0.deletedAt == nil } } }
    func clearFresh(_ id: String) { recordCare.clearFresh(id) }
    /// 당겨서 새로고침.
    func retryNow() async { await connection.retryNow() }
}

// MARK: 타이머

/// 타이머. 끝났거나 지운 기록이면 `event`가 없고 화면이 닫힌다.
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
    /// 겹침을 물을 시각 고침. '그래도 저장'이면 `confirmTime()`.
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
    /// `chooseKind`: 기록 탭 '지난 기록 적기'로 연 시트(종류 칸).
    case new(CareKind, day: String?, chooseKind: Bool = false)
    case edit(String)
}

/// 빠른 기록·고치기 시트. 입력은 `draft`(CareDraft)이고 시트는 그 값을 바꾸기만 한다. 저장 단계는 `phase`다
/// (안드로이드 CareSheetViewModel과 같은 이름: 고치는 중 → 겹침 묻기 → 실패, 저장했거나 그사이 지워졌으면 닫힘).
@MainActor
@Observable
final class CareSheetViewModel {
    enum Phase: Equatable {
        case editing
        /// 같은 종류와 시간이 겹친다. '그래도 저장'이면 `save(allowOverlap: true)`.
        case confirmOverlap
        /// 저장하지 않았다. 시트 안에 보일 문구(규칙 위반, 또는 저장 실패 — 띠의 다른 안내까지 옮기지 않는다). 문구가 없는 규칙도 있다.
        /// `problem`은 저장 검사에 걸린 것이면 그 검사(탓이 된 줄을 펼치고 그 아래에 글). 저장 실패면 nil(단추 위).
        /// `attempt`는 이 시트에서 몇 번째 실패인지다: 같은 오류가 다시 나도 새 값이라 화면이 펼침·흔들림·오류 햅틱을 매번 낸다.
        case failed(String?, problem: CareProblem? = nil, attempt: Int)
        /// 시트를 닫는다. `saved`는 실제로 저장했는가: 저장이면 참, 고치던 기록이 그사이 지워짐(되살리지 않는다)·지우기면 거짓.
        /// 확인 표시·성공 햅틱은 이 값으로만 정한다(화면이 단계 순서로 짐작하지 않는다).
        case closed(saved: Bool)
    }

    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let recordCare: RecordCare
    @ObservationIgnored private let now: () -> Date
    /// 시트 입력. 고칠 기록을 찾지 못했으면 없다(시트를 그리지 않는다).
    private(set) var draft: CareDraft?
    private(set) var phase: Phase = .editing
    /// 이 시트에서 낸 실패 수(`Phase.failed`의 `attempt`).
    @ObservationIgnored private var failures = 0
    /// 종류 칸(기록 탭 '지난 기록 적기'만). 순서는 빠른 기록 순서(`quickKinds`), 처음은 수유.
    let kinds: [CareKind]

    init(target: CareSheetTarget, state: PhoneStateRepository, care: CareRepository, recordCare: RecordCare, now: @escaping () -> Date) {
        stateRepo = state
        careRepo = care
        self.recordCare = recordCare
        self.now = now
        var choose = false
        switch target {
        case .new(let kind, let day, let chooseKind): draft = .new(kind: kind, day: day, now: now()); choose = chooseKind
        case .edit(let id): draft = care.care.events.first { $0.id == id && $0.deletedAt == nil }.map(CareDraft.editing)
        }
        kinds = choose ? CareRules.quickKinds(sleepDiaper: care.care.sleepDiaperEnabled) : []
    }

    var guardian: Bool { stateRepo.role == .guardian }

    /// 지우지 않은 손 기록이 하나도 없을 때만 첫 기록 도움말(차 기록 줄은 `events`에 없다). 첫 기록을 저장하면 저절로 사라진다.
    var firstRecord: Bool { careRepo.care.events.allSatisfy { $0.deletedAt != nil } }

    /// 새 수유의 쪽 칸에서 지난 수유의 쪽 아래 '지난번'(표시만, 미리 고르지 않는다). 고치기에는 보이지 않는다.
    var lastSide: FeedSide? { draft?.original == nil ? CareRules.latestFeed(careRepo.care.events)?.feedSide : nil }

    /// 종류를 바꾼다(시각 입력은 그대로).
    func setKind(_ k: CareKind) {
        guard kinds.contains(k) else { return }
        edit { $0.setKind(k) }
    }

    /// 입력 칸을 고친다(시트가 바꾸는 것은 이 값뿐). 고치면 앞 실패 글은 걷는다(고친 값에 앞 오류가 붙지 않게, 안드로이드와 같음).
    /// 입력을 받는 단계(`canEdit`)에서만 바꾼다.
    func edit(_ change: (inout CareDraft) -> Void) {
        guard canEdit, var d = draft else { return }
        change(&d)
        draft = d
        if case .failed = phase { phase = .editing }
    }

    /// 몇 분 전 칩. 누른 때 기준이다.
    func pick(chip minutes: Int?) { edit { $0.pick(chip: minutes, now: now()) } }

    /// 저장(안드로이드와 같은 단계 규칙). 고치는 중·실패 뒤에만 저장하고, `allowOverlap`('그래도 저장')은 겹침을 묻는 중에만 받는다.
    /// 저장해 닫히는 사이 또 누른 저장은 아무것도 하지 않는다. 새 기록 id는 시트를 열 때 정한 `draft.sheetId`라 다시 저장해도 같은 기록이다.
    func save(allowOverlap: Bool = false) {
        guard let d = draft, d.savable else { return }   // 메모가 비면 단추가 꺼진다
        switch (phase, allowOverlap) {
        case (.editing, false), (.failed, false), (.confirmOverlap, true): break
        default: return
        }
        let e = d.event(now: now())
        if d.original != nil, !careRepo.care.events.contains(where: { $0.id == e.id && $0.deletedAt == nil }) { phase = .closed(saved: false); return }
        let r = recordCare.saveEvent(e, allowOverlap: allowOverlap)
        switch r {
        case .saved: recordCare.markFresh(e.id); phase = .closed(saved: true)
        case .overlap: phase = .confirmOverlap
        case .notSaved: fail(NoticeRules.saveFailed, nil)
        case .problem(let p): fail(r.message(for: e.kind), p)
        }
    }

    /// 실패마다 새 값(시도 번호)을 낸다: 저장을 누를 때마다 화면이 반응한다.
    private func fail(_ message: String?, _ problem: CareProblem?) {
        failures += 1
        phase = .failed(message, problem: problem, attempt: failures)
    }

    /// 겹침을 묻는 창에서 '고치기'.
    func keepEditing() { if phase == .confirmOverlap { phase = .editing } }

    /// 입력·지우기를 받는 단계: 저장 확인 표시가 머무는 동안(`closed`)과 겹침을 묻는 중에는 받지 않는다. 휴지통도 이것으로 켠다.
    var canEdit: Bool {
        switch phase {
        case .editing, .failed: true
        default: false
        }
    }

    /// 고치던 기록을 지운다(되돌리기 띠는 RecordCare가 띄운다). `canEdit`일 때만.
    func delete() {
        guard canEdit, let id = draft?.original?.id else { return }
        recordCare.delete(id)
        phase = .closed(saved: false)
    }
}

/// 기록 시트가 닫힐 때 보일 것: 확인 표시·성공 햅틱은 실제로 저장되어 닫힐 때만. 메모 저장은 햅틱만,
/// 동작 줄이기면 햅틱만 내고 바로 닫는다. 저장하지 않고 닫히면(지우기·그사이 지워짐) 아무것도 없이 바로 닫는다.
enum SheetCloseFeedback: Equatable {
    /// 단추에 확인 표시(spring.bouncy) + 성공 햅틱, 320ms 뒤 닫힘
    case confirm
    /// 성공 햅틱만, 바로 닫힘
    case hapticOnly
    /// 바로 닫힘
    case none

    static func on(saved: Bool, kind: CareKind, reduceMotion: Bool) -> SheetCloseFeedback {
        guard saved else { return .none }
        return kind == .note || reduceMotion ? .hapticOnly : .confirm
    }
}
