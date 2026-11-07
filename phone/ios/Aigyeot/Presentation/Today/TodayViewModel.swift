import Foundation
import Observation

/// 오늘 탭의 상태(C1, 5장, R12·R15·R16).
struct TodayState {
    var role: PhoneRole?
    var isPaired: Bool
    var care: CareFile
    var phone: PhoneState

    /// 마지막 수유 시작: 기록과 차 값 중 늦은 것(R12).
    var lastFeedingStart: Date? { FeedRules.lastFeedingStart(phone, care) }
    func shownStart(_ e: CareEvent) -> Date { FeedRules.shownStart(e, in: phone) }
    func feedingRecord(for e: CareEvent) -> FeedingRecord? { FeedRules.record(for: e, in: phone) }
}

@MainActor
@Observable
final class TodayViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let recordCare: RecordCare
    @ObservationIgnored private let alerts: ShowAlert
    @ObservationIgnored private let inbox: SyncInbox

    init(state: PhoneStateRepository, care: CareRepository, recordCare: RecordCare, alerts: ShowAlert, inbox: SyncInbox) {
        stateRepo = state
        careRepo = care
        self.recordCare = recordCare
        self.alerts = alerts
        self.inbox = inbox
    }

    var state: TodayState { TodayState(role: stateRepo.role, isPaired: stateRepo.isPaired, care: careRepo.value, phone: stateRepo.value) }

    func startFeeding() { recordCare.startFeeding() }
    func startSleep() { recordCare.startSleep() }
    func userOpenedAlert(_ id: String) { alerts.userOpened(id) }
    func retryNow() async { await inbox.retryNow() }
}

/// 빠른 기록·고치기 시트와 타이머(C2·C3·C4)의 상태. 오늘·기록 탭이 함께 연다.
struct CareSheetState {
    var role: PhoneRole?
    var care: CareFile
    func event(_ id: String) -> CareEvent? { care.events.first { $0.id == id } }
}

@MainActor
@Observable
final class CareSheetViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let recordCare: RecordCare
    @ObservationIgnored private let undo: UndoViewModel

    init(state: PhoneStateRepository, care: CareRepository, recordCare: RecordCare, undo: UndoViewModel) {
        stateRepo = state
        careRepo = care
        self.recordCare = recordCare
        self.undo = undo
    }

    var state: CareSheetState { CareSheetState(role: stateRepo.role, care: careRepo.value) }

    /// 저장 결과를 시트가 바로 쓴다(겹침 묻기·문구).
    func save(_ e: CareEvent, allowOverlap: Bool = false) -> CareSaveResult { recordCare.saveEvent(e, allowOverlap: allowOverlap) }
    func endNow(_ id: String) { recordCare.endNow(id) }
    func closeEndUnknown(_ id: String) { recordCare.closeEndUnknown(id) }
    /// 어느 길로 지워도 되돌리기를 보인다(U38).
    func delete(_ id: String) { undo.delete(id) }
}

/// "지웠어요" + "되돌리기"(5초, U38). 기록 탭 밀기, 고치기 시트, 타이머 어디서 지워도 같다.
@MainActor
@Observable
final class UndoViewModel {
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let recordCare: RecordCare
    /// 방금 지운 돌봄 기록 id. 저장하지 않는다.
    private(set) var recentlyDeleted: String?

    init(care: CareRepository, recordCare: RecordCare) {
        careRepo = care
        self.recordCare = recordCare
    }

    func delete(_ id: String) {
        if recordCare.deleteEvent(id) { recentlyDeleted = id }
    }

    func undo(_ id: String) {
        if recordCare.undoDelete(id), recentlyDeleted == id { recentlyDeleted = nil }
    }

    /// 5초는 지운 때부터 센다(ms, 띠가 다른 화면에서 다시 떠도 남은 시간을 이어 센다).
    func deletedAt(_ id: String) -> Int64? { careRepo.value.events.first { $0.id == id }?.deletedAt }

    /// 시간이 다 됐다. 그사이 다른 기록을 지웠으면 그것은 둔다.
    func expire(_ id: String) { if recentlyDeleted == id { recentlyDeleted = nil } }
}
