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

    /// 패턴 화면 값(C5): 그릴 때 계산한다(P4). 집계 규칙은 CareStats·CareRules.pattern 한 곳이고 막대·칸으로 바꾸는 것은 ChartData다.
    func pattern(now: Int64) -> PatternUi {
        let on = care.sleepDiaperEnabled
        return PatternUi(numbers: CareRules.pattern(care.events, reports: reports, now: now, zone: .current, sleepDiaper: on),
                         interval: ChartData.intervalWeek(care: care, now: now, zone: .current),
                         carCries: ChartData.carCryWeek(reports: reports, now: now, zone: .current),
                         sleep: on ? ChartData.sleepGrid(care: care, now: now, zone: .current) : nil,
                         feedTrend: ChartData.feedTrend(care: care, now: now, zone: .current))
    }
}

/// 패턴 화면 한 번 그릴 값: 지난 7일 숫자 칸, 수유 간격 막대, 차에서 울음 막대, 잠 패턴(잠·기저귀를 끄면 없음, R16), 지난주 대비 글.
struct PatternUi {
    let numbers: CarePattern
    let interval: ChartData.IntervalWeek
    let carCries: ChartData.CarCryWeek
    let sleep: ChartData.SleepGrid?
    let feedTrend: String?
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
