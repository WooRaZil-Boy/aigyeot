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
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let notices: Notices
    @ObservationIgnored private let recordCare: RecordCare
    @ObservationIgnored private let disconnect: Disconnect

    init(state: PhoneStateRepository, care: CareRepository, notices: Notices, recordCare: RecordCare, disconnect: Disconnect) {
        stateRepo = state
        careRepo = care
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
    var careNotice: String?

    var role: PhoneRole? { connection.creds?.role }
    var isPaired: Bool { connection.creds != nil }
    var guardian: Bool { role == .guardian }
    var owner: Bool { role == .owner }
    /// 마지막 수유 시작: 기록과 차 값 중 늦은 것(R12).
    var lastFeedingStart: Date? { FeedRules.lastFeedingStart(phone, care) }
    func shownStart(_ e: CareEvent) -> Date { FeedRules.shownStart(e, in: phone) }
    func feedingRecord(for e: CareEvent) -> FeedingRecord? { FeedRules.record(for: e, in: phone) }
}

/// 오늘 탭(C1). guardian은 인계 알림·최근 리포트도 본다.
@MainActor
@Observable
final class TodayViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let notices: Notices
    @ObservationIgnored private let connection: KeepConnected
    @ObservationIgnored private let recordCare: RecordCare

    init(state: PhoneStateRepository, care: CareRepository, notices: Notices, connection: KeepConnected, recordCare: RecordCare) {
        stateRepo = state
        careRepo = care
        self.notices = notices
        self.connection = connection
        self.recordCare = recordCare
    }

    var ui: CareUiState {
        CareUiState(care: careRepo.care, phone: stateRepo.state, connection: stateRepo.connection, online: connection.status.online,
                    careNotice: notices.careNotice)
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
    var care: CareFile
    var connected: Bool
    var guardian: Bool
}

func timerUi(id: String, care: CareFile, connection: Connection) -> TimerUiState {
    TimerUiState(event: care.events.first { $0.id == id }, care: care, connected: connection.creds != nil,
                 guardian: connection.creds?.role == .guardian)
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

    /// 진행 중인 그 기록의 시작 시각·쪽·양을 고친다. 겹침·규칙은 결과로 돌려준다.
    func save(_ e: CareEvent, allowOverlap: Bool) -> CareSaveResult { recordCare.saveEvent(e, allowOverlap: allowOverlap) }
    func end() { recordCare.end(id) }
    func endUnknown() { recordCare.endUnknown(id) }
    func delete() { recordCare.delete(id) }
}

// MARK: 빠른 기록·고치기 시트

/// 빠른 기록·고치기 시트(C3·C4)의 저장 결과.
enum SheetSave: Equatable {
    case saved
    /// 고치던 기록이 그사이 지워졌다: 저장하지 않고 닫는다(되살리지 않는다, R10).
    case gone
    /// 같은 종류와 시간이 겹친다(R4). '그래도 저장'이면 `allowOverlap`으로 다시 부른다.
    case overlap
    /// 저장하지 않았다. 시트 안에 보일 문구(규칙 위반, 또는 저장 실패 — 띠의 다른 안내까지 옮기지 않는다).
    case failed(String?)
}

@MainActor
@Observable
final class CareSheetViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let recordCare: RecordCare

    init(state: PhoneStateRepository, care: CareRepository, recordCare: RecordCare) {
        stateRepo = state
        careRepo = care
        self.recordCare = recordCare
    }

    var care: CareFile { careRepo.care }
    var guardian: Bool { stateRepo.role == .guardian }

    func save(_ e: CareEvent, allowOverlap: Bool, editing: Bool) -> SheetSave {
        if editing, !careRepo.care.events.contains(where: { $0.id == e.id && $0.deletedAt == nil }) { return .gone }
        let r = recordCare.saveEvent(e, allowOverlap: allowOverlap)
        switch r {
        case .saved: return .saved
        case .overlap: return .overlap
        case .notSaved: return .failed(NoticeRules.saveFailed)
        case .problem: return .failed(r.message(for: e.kind))
        }
    }

    func delete(_ id: String) { recordCare.delete(id) }
}
