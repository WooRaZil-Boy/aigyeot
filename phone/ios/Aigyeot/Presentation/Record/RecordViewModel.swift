import Foundation
import Observation

/// 기록 탭(C4·C5·C14)의 상태: 손 기록, 리포트에서 만든 차 기록 줄, 잠·기저귀를 켰는지.
struct RecordState {
    var care: CareFile
    var reports: [StoredReport]
    func report(sessionId: String) -> StoredReport? { reports.first { $0.body.sessionId == sessionId } }
}

@MainActor
@Observable
final class RecordViewModel {
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let reports: LoadReport
    @ObservationIgnored private let undo: UndoViewModel

    init(care: CareRepository, reports: LoadReport, undo: UndoViewModel) {
        careRepo = care
        self.reports = reports
        self.undo = undo
    }

    var state: RecordState { RecordState(care: careRepo.value, reports: reports.reports) }

    /// 밀어 지우기(5초 되돌리기).
    func delete(_ id: String) { undo.delete(id) }
}
