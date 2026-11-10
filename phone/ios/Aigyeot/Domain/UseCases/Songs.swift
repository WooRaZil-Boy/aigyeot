import Foundation

// 노래(9장): 녹음해 넣기·올리기·고르기·지우기(안드로이드 usecase/Songs.kt와 같은 나눔). 보내기는 SendOutbox.

/// 녹음한 노래를 보낼 함에 넣는다. 파일 올리기는 UploadSong, 보내기는 SendOutbox.
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
    func callAsFunction(name: String, recording: URL, durationS: Double, replacing old: String? = nil) throws {
        let name = try SongRules.name(name)
        guard durationS <= SongRules.maxDurationS else { throw SongError.tooLong }
        let replaced = SongRules.replaceable(old, in: state.state)
        guard state.state.songSlots(excluding: replaced?.id) < SongRules.maxSongs else { throw SongError.limitReached }
        guard songFiles.size(of: recording) <= SongRules.maxBytes else { throw SongError.tooLarge }
        let t = now()
        let id = ULID.generate(date: t)
        try songFiles.copyIn(recording, as: id)   // 올릴 파일을 먼저 두고, 목록과 보낼 함은 한 번에 저장한다
        let item = OutboxRules.item(type: MessageType.songAdded, body: SongAddedBody(songId: id, name: name, durationS: durationS), songId: id, now: t)
        let removal = replaced.map { OutboxRules.item(type: MessageType.songRemoved, body: SongRefBody(songId: $0.id), now: t) }
        let row = SongRow(id: id, name: name, durationS: durationS, state: .uploading, addedAt: t, hasFile: true)
        let swap = replaced.flatMap { r in removal.map { (id: r.id, removal: $0) } }
        guard state.commit(state.creds, { SongRules.add(row, upload: item, replacing: swap, now: t, in: &$0) }) else {
            songFiles.delete(id)
            throw SongError.saveFailed
        }
        if let r = replaced { songFiles.delete(r.id) }   // 저장이 성공한 뒤에 지운다
        outbox.kick()
    }
}

/// 노래 보내기의 첫 단계: 파일 PUT(7.5 두 단계). 성공(201·409 `already_exists`)해야 `song_added`로 넘어간다.
@MainActor
final class UploadSong {
    private let state: PhoneStateRepository
    private let relay: RelayClient
    private let gate: RelayGate
    private let songFiles: SongFileStore

    init(state: PhoneStateRepository, relay: RelayClient, gate: RelayGate, songFiles: SongFileStore) {
        self.state = state
        self.relay = relay
        self.gate = gate
        self.songFiles = songFiles
    }

    enum Outcome: Equatable {
        /// 노래가 아니거나 이미 올렸다.
        case notNeeded
        case uploaded
        /// 보내기 전에 버린다(HTTP 없음).
        case missingFile
        /// 업로드 사이 기록을 지웠다: 보내지 않고 끝난 것으로 친다.
        case removedMeanwhile
        /// 연결이 바뀌었다.
        case stale
    }

    func callAsFunction(_ item: OutboxItem, c: Credentials, mark: LinkMark) async throws -> Outcome {
        guard let songId = item.songId, !item.uploaded else { return .notNeeded }
        guard songFiles.exists(songId) else { return .missingFile }
        try await relay.putSong(token: c.token, id: songId, file: songFiles.url(for: songId))
        guard state.isCurrent(c) else { return .stale }
        gate.observe(nil, since: mark)   // 올리기도 중계에 닿은 HTTP다
        guard state.state.outbox.contains(where: { $0.id == item.id }) else { return .removedMeanwhile }   // 업로드 사이 기록을 지웠으면 보내지 않는다
        state.commit(c) { st in if let i = st.outbox.firstIndex(where: { $0.id == item.id }) { st.outbox[i].markUploaded(true) } }
        return .uploaded
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

    func callAsFunction(_ id: String) {
        guard let row = state.state.songs.first(where: { $0.id == id }), row.state == .accepted else { return }
        let item = OutboxRules.item(type: MessageType.songSelected, body: SongRefBody(songId: id), now: now())
        if state.commit(state.creds, { SongRules.select(item, in: &$0) }) { outbox.kick() }
    }
}

/// 곡 지우기: 표시하고 song_removed를 넣는다. 파일은 저장이 성공한 뒤에 지운다.
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

    func callAsFunction(_ id: String) {
        guard let row = state.state.songs.first(where: { $0.id == id }), row.state != .removed else { return }
        let t = now()
        let item = OutboxRules.item(type: MessageType.songRemoved, body: SongRefBody(songId: id), now: t)
        guard state.commit(state.creds, { OutboxRules.markSongRemoved(id, removal: item, now: t, in: &$0) }) else { return }
        songFiles.delete(id)
        outbox.kick()
    }
}
