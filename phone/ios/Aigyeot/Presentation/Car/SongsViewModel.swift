import Foundation
import Observation

// MARK: 차 탭

/// 차 탭: 연결 상태 → 우리 노래(owner) → 리포트.
struct CarUiState {
    var phone: PhoneState
    var connection: Connection
    var online: Bool

    var isPaired: Bool { connection.creds != nil }
    var owner: Bool { connection.creds?.role == .owner }
    var offline: Bool { !online }
    /// 받을 때 시작 시각 늦은 순으로 저장한다(InboxRules).
    var reports: [StoredReport] { phone.reports }
}

@MainActor
@Observable
final class CarViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let connection: KeepConnected

    init(state: PhoneStateRepository, connection: KeepConnected) {
        stateRepo = state
        self.connection = connection
    }

    var ui: CarUiState { CarUiState(phone: stateRepo.state, connection: stateRepo.connection, online: connection.status.online) }

    /// 당겨서 새로고침.
    func retryNow() async { await connection.retryNow() }
}

// MARK: 우리 노래

/// 우리 노래(owner): 보이는 곡, 쓰는 자리 수(3곡 한도), 온라인.
struct SongsUiState: Equatable {
    var songs: [SongRow]
    var slotsUsed: Int
    var online: Bool

    var offline: Bool { !online }
}

func songsUi(_ s: PhoneState, online: Bool) -> SongsUiState {
    SongsUiState(songs: s.visibleSongs, slotsUsed: s.songSlotsUsed, online: online)
}

/// 우리 노래 목록과 녹음 시트의 보내기. 녹음기는 시트의 RecorderViewModel이 든다(관용 차이).
@MainActor
@Observable
final class SongsViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let connection: KeepConnected
    @ObservationIgnored private let recordSong: RecordSong
    @ObservationIgnored private let removeSong: RemoveSong
    @ObservationIgnored private let selectSong: SelectSong

    init(state: PhoneStateRepository, connection: KeepConnected, recordSong: RecordSong, removeSong: RemoveSong, selectSong: SelectSong) {
        stateRepo = state
        self.connection = connection
        self.recordSong = recordSong
        self.removeSong = removeSong
        self.selectSong = selectSong
    }

    var ui: SongsUiState { songsUi(stateRepo.state, online: connection.status.online) }

    func remove(_ id: String) { removeSong(id) }
    func select(_ id: String) { selectSong(id) }
    /// '다시 녹음'이면 그 곡과 바꾼다. 문구는 `SongError.message`.
    func add(name: String, recording: URL, durationS: Double, replacing: String?) throws {
        try recordSong(name: name, recording: recording, durationS: durationS, replacing: replacing)
    }
}
