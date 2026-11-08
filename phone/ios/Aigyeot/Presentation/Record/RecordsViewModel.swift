import Foundation
import Observation

/// 기록 탭(C4·C5·C14): 하루 타임라인과 패턴. 손 기록, 리포트에서 만든 차 기록 줄, 잠·기저귀를 켰는지.
struct RecordsUiState {
    var care: CareFile
    var phone: PhoneState
    var connection: Connection

    var guardian: Bool { connection.creds?.role == .guardian }
    var reports: [StoredReport] { phone.reports }
    func report(sessionId: String) -> StoredReport? { phone.reports.first { $0.body.sessionId == sessionId } }
}

@MainActor
@Observable
final class RecordsViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let recordCare: RecordCare

    init(state: PhoneStateRepository, care: CareRepository, recordCare: RecordCare) {
        stateRepo = state
        careRepo = care
        self.recordCare = recordCare
    }

    var ui: RecordsUiState { RecordsUiState(care: careRepo.care, phone: stateRepo.state, connection: stateRepo.connection) }

    /// 밀어 지우기(5초 되돌리기).
    func delete(_ id: String) { recordCare.delete(id) }
}
