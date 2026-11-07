import Foundation
import Observation

/// 차 탭(5장)의 상태: 연결, 우리 노래(owner), 리포트 목록.
struct SongsState {
    var isPaired: Bool
    var role: PhoneRole?
    var offline: Bool
    var visibleSongs: [SongRow]
    var songSlotsUsed: Int
    var reports: [StoredReport]
}

@MainActor
@Observable
final class SongsViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let link: LinkObserver
    @ObservationIgnored private let adding: RecordSong
    @ObservationIgnored private let removing: RemoveSong
    @ObservationIgnored private let selecting: SelectSong
    @ObservationIgnored private let reports: LoadReport
    @ObservationIgnored private let inbox: SyncInbox

    init(state: PhoneStateRepository, link: LinkObserver, recordSong: RecordSong, removeSong: RemoveSong, selectSong: SelectSong,
         reports: LoadReport, inbox: SyncInbox) {
        stateRepo = state
        self.link = link
        adding = recordSong
        removing = removeSong
        selecting = selectSong
        self.reports = reports
        self.inbox = inbox
    }

    var state: SongsState {
        SongsState(isPaired: stateRepo.isPaired, role: stateRepo.role, offline: link.offline, visibleSongs: stateRepo.value.visibleSongs,
                   songSlotsUsed: stateRepo.value.songSlotsUsed, reports: reports.reports)
    }

    func removeSong(_ id: String) { removing.run(id) }
    func selectSong(_ id: String) { selecting.run(id) }
    /// '다시 녹음'이면 그 곡과 바꾼다(9장). 문구는 `SongError.message`.
    func addSong(name: String, recording: URL, durationS: Double, replacing: String?) throws {
        try adding.run(name: name, recording: recording, durationS: durationS, replacing: replacing)
    }
    func retryNow() async { await inbox.retryNow() }
}

/// 리포트 목록·상세(11장)의 상태.
struct ReportState {
    var reports: [StoredReport]
    var songNames: [String: String]
    /// `song_id`로 내 곡 목록에서 이름을 찾는다. 없으면 nil("부모 노래").
    func songName(_ id: String?) -> String? { id.flatMap { songNames[$0] } }
}

@MainActor
@Observable
final class ReportViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let reports: LoadReport
    @ObservationIgnored private let inbox: SyncInbox

    init(state: PhoneStateRepository, reports: LoadReport, inbox: SyncInbox) {
        stateRepo = state
        self.reports = reports
        self.inbox = inbox
    }

    var state: ReportState {
        ReportState(reports: reports.reports,
                    songNames: Dictionary(stateRepo.value.songs.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a }))
    }

    func retryNow() async { await inbox.retryNow() }
}
