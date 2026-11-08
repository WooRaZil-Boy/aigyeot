import Foundation
import Observation

// 리포트·인계 알림 화면(안드로이드 ReportViewModels.kt). 리포트·알림은 state.json이 원본이고 읽기만 한다(저장소를 바로 읽는다, 11-08 구조 원칙).

/// 리포트 목록(guardian 오늘 '최근 리포트'에서 연다). 시작 시각이 늦은 것이 위.
@MainActor
@Observable
final class ReportsViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let connection: KeepConnected

    init(state: PhoneStateRepository, connection: KeepConnected) {
        stateRepo = state
        self.connection = connection
    }

    /// 받을 때 시작 시각 늦은 순으로 저장한다(InboxRules). 안드로이드는 맵이라 화면에서 정렬한다.
    var reports: [StoredReport] { stateRepo.state.reports }

    /// 당겨서 새로고침(7.2).
    func retryNow() async { await connection.retryNow() }
}

/// 리포트 상세. 부모 노래 이름은 이 폰이 아는 곡에서 붙인다.
struct ReportDetailUiState {
    var report: StoredReport?
    var songs: [SongRow]

    /// `song_id`로 내 곡 목록에서 이름을 찾는다. 없으면 nil("부모 노래").
    func songName(_ id: String?) -> String? { id.flatMap { id in songs.first { $0.id == id }?.name } }
}

@MainActor
@Observable
final class ReportDetailViewModel {
    @ObservationIgnored private let sessionId: String
    @ObservationIgnored private let stateRepo: PhoneStateRepository

    init(sessionId: String, state: PhoneStateRepository) {
        self.sessionId = sessionId
        stateRepo = state
    }

    var ui: ReportDetailUiState {
        let s = stateRepo.state
        return ReportDetailUiState(report: s.reports.first { $0.body.sessionId == sessionId }, songs: s.songs)
    }
}

/// 인계 알림 상세(guardian). 본문을 아직 받지 못했으면 nil.
@MainActor
@Observable
final class AlertViewModel {
    @ObservationIgnored private let id: String
    @ObservationIgnored private let stateRepo: PhoneStateRepository

    init(id: String, state: PhoneStateRepository) {
        self.id = id
        stateRepo = state
    }

    var alert: StoredAlert? { stateRepo.state.alert(id) }
}
