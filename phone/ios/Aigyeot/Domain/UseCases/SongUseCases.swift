import Foundation

/// 녹음한 노래를 보낼 함에 넣는다(9장). 파일 올리기는 UploadSong, 보내기는 SendOutbox.
@MainActor
final class RecordSong {
    private let state: PhoneStateRepository
    private let songFiles: SongFileStore
    private let outbox: SendOutbox
    private let now: () -> Date

    init(state: PhoneStateRepository, songFiles: SongFileStore, outbox: SendOutbox, now: @escaping () -> Date) {
        self.state = state
        self.songFiles = songFiles
        self.outbox = outbox
        self.now = now
    }

    /// `replacing`: '다시 녹음'으로 바꿀 거절·실패 곡(9장). 새 곡 검증·파일 준비가 끝난 뒤, 그 곡의 지움과 새 곡 추가를
    /// 한 번에 저장한다(지움 메시지가 먼저 선다). 실패하면 기존 곡은 그대로 남는다. 기존 파일은 저장이 성공한 뒤 지운다.
    func run(name: String, recording: URL, durationS: Double, replacing old: String? = nil) throws {
        let name = try SongRules.name(name)
        guard durationS <= SongRules.maxDurationS else { throw SongError.tooLong }
        let replaced = SongRules.replaceable(old, in: state.value)
        guard state.value.songSlots(excluding: replaced?.id) < SongRules.maxSongs else { throw SongError.limitReached }
        guard songFiles.size(of: recording) <= SongRules.maxBytes else { throw SongError.tooLarge }
        let t = now()
        let id = ULID.generate(date: t)
        try songFiles.copyIn(recording, as: id)   // 올릴 파일을 먼저 두고, 목록과 보낼 함은 한 번에 저장한다
        let item = OutboxRules.item(type: MessageType.songAdded, body: SongAddedBody(songId: id, name: name, durationS: durationS), songId: id, now: t)
        let removal = replaced.map { OutboxRules.item(type: MessageType.songRemoved, body: SongRefBody(songId: $0.id), now: t) }
        let row = SongRow(id: id, name: name, durationS: durationS, state: .uploading, addedAt: t, hasFile: true)
        let swap = replaced.flatMap { r in removal.map { (id: r.id, removal: $0) } }
        guard state.update({ SongRules.add(row, upload: item, replacing: swap, now: t, in: &$0) }) else {
            songFiles.remove(id)
            throw SongError.saveFailed
        }
        if let r = replaced { songFiles.remove(r.id) }   // 저장이 성공한 뒤에 지운다
        outbox.kick()
    }
}

/// 곡 지우기(9장): 표시하고 song_removed를 넣는다. 파일은 저장이 성공한 뒤에 지운다.
@MainActor
final class RemoveSong {
    private let state: PhoneStateRepository
    private let songFiles: SongFileStore
    private let outbox: SendOutbox
    private let now: () -> Date

    init(state: PhoneStateRepository, songFiles: SongFileStore, outbox: SendOutbox, now: @escaping () -> Date) {
        self.state = state
        self.songFiles = songFiles
        self.outbox = outbox
        self.now = now
    }

    func run(_ id: String) {
        guard let row = state.value.songs.first(where: { $0.id == id }), row.state != .removed else { return }
        let t = now()
        let item = OutboxRules.item(type: MessageType.songRemoved, body: SongRefBody(songId: id), now: t)
        guard state.update({ OutboxRules.markSongRemoved(id, removal: item, now: t, in: &$0) }) else { return }
        songFiles.remove(id)
        outbox.kick()
    }
}

/// 실험에 쓸 곡 고르기(받아들인 곡만, song_selected).
@MainActor
final class SelectSong {
    private let state: PhoneStateRepository
    private let outbox: SendOutbox
    private let now: () -> Date

    init(state: PhoneStateRepository, outbox: SendOutbox, now: @escaping () -> Date) {
        self.state = state
        self.outbox = outbox
        self.now = now
    }

    func run(_ id: String) {
        guard let row = state.value.songs.first(where: { $0.id == id }), row.state == .accepted else { return }
        let item = OutboxRules.item(type: MessageType.songSelected, body: SongRefBody(songId: id), now: now())
        if state.update({ SongRules.select(id, item: item, in: &$0) }) { outbox.kick() }
    }
}

/// 받은 리포트 읽기(11장). 저장하지 않고 원본(PhoneStateRepository)에서 계산한다(P4).
@MainActor
final class LoadReport {
    private let state: PhoneStateRepository
    init(state: PhoneStateRepository) { self.state = state }

    /// 시작 시각 늦은 순(받을 때 정렬해 둔다).
    var reports: [StoredReport] { state.value.reports }
    func report(sessionId: String) -> StoredReport? { state.value.reports.first { $0.body.sessionId == sessionId } }
    /// 리포트 상세의 곡 이름(이 폰이 아는 곡만).
    func songName(_ id: String?) -> String? { id.flatMap { id in state.value.songs.first { $0.id == id }?.name } }
}
