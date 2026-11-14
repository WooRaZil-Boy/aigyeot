import Foundation
import Observation

/// 기록 탭: 하루 타임라인과 패턴. 손 기록, 리포트에서 만든 차 기록 줄, 잠·기저귀를 켰는지.
struct RecordsUiState {
    var care: CareFile
    var phone: PhoneState

    var reports: [StoredReport] { phone.reports }

    /// 고른 날의 줄(손 기록 + 차 기록 줄, 새 것이 위). 비면 화면은 가운데 빈 상태 판 하나만 그리고 목록 끝 '지난 기록 적기' 줄은 숨긴다.
    func dayItems(_ day: String, car: [CarRow]) -> [DayItem] {
        let hand = CareRules.events(onDay: day, in: care.events).map(DayItem.care)
        let carItems = car.filter { CareRules.dayKey(CareTime.ms($0.at), .current) == day }.map(DayItem.car)
        return (hand + carItems).sorted { $0.at > $1.at }   // 새 것이 위
    }
    func report(sessionId: String) -> StoredReport? { phone.reports.first { $0.body.sessionId == sessionId } }

    /// 패턴 화면 값: 그릴 때 계산한다. 집계 규칙은 CareStats·CareRules.pattern 한 곳이고 막대·칸으로 바꾸는 것은 ChartData다.
    func pattern(now: Int64) -> PatternUi {
        let on = care.sleepDiaperEnabled
        return PatternUi(numbers: CareRules.pattern(care.events, reports: reports, now: now, zone: .current, sleepDiaper: on),
                         interval: ChartData.intervalWeek(care: care, now: now, zone: .current),
                         carCries: ChartData.carCryWeek(reports: reports, now: now, zone: .current),
                         sleep: on ? ChartData.sleepGrid(care: care, now: now, zone: .current) : nil,
                         feedTrend: ChartData.feedTrend(care: care, now: now, zone: .current))
    }
}

/// 하루 목록 한 줄: 손 기록 또는 리포트에서 만든 차 기록 줄.
enum DayItem: Identifiable {
    case care(CareEvent)
    case car(CarRow)
    var id: String {
        switch self {
        case .care(let e): e.id
        case .car(let r): r.id
        }
    }
    var at: Int64 {
        switch self {
        case .care(let e): e.startAt
        case .car(let r): CareTime.ms(r.at)
        }
    }
    /// 그 기록의 시간대(손 기록은 자기 시간대, 차 기록은 기기 시간대).
    var zone: TimeZone {
        switch self {
        case .care(let e): e.timeZone
        case .car: .current
        }
    }
}

/// 패턴 화면 한 번 그릴 값: 지난 7일 숫자 칸, 수유 간격 막대, 차에서 울음 막대, 잠 패턴(잠·기저귀를 끄면 없음), 지난주 대비 글.
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

    var ui: RecordsUiState { RecordsUiState(care: careRepo.care, phone: stateRepo.state) }

    /// 시트로 막 저장한 기록(새 줄 강조)과 강조를 마친 뒤 걷기.
    var fresh: String? { recordCare.fresh }
    func clearFresh(_ id: String) { recordCare.clearFresh(id) }

    /// 밀어 지우기(5초 되돌리기).
    func delete(_ id: String) { recordCare.delete(id) }
}
