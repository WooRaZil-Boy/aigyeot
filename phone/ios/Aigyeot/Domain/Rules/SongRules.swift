import Foundation

/// 우리 노래 규칙(9장, 7.4·L48). `songs`는 이 파일과 받기(InboxRules)·보낼 함 규칙(OutboxRules)으로만 고친다(P3).
enum SongRules {
    static let maxSongs = 3
    static let maxDurationS: Double = 180
    static let maxBytes = 2_000_000
    static let maxNameLength = 30

    /// 이름은 앞뒤 공백을 빼고 1~30자(3.3).
    static func name(_ raw: String) throws -> String {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...maxNameLength).contains(name.count) else { throw SongError.badName }
        return name
    }

    /// 입력 칸: 30자에서 더 받지 않는다(안드로이드 take(30)과 같다).
    static func nameInput(_ v: String) -> String { v.count > maxNameLength ? String(v.prefix(maxNameLength)) : v }

    /// '다시 녹음'으로 바꿀 곡: 거절·실패한 곡만 바꾼다. 시트를 연 사이 차가 받아들였으면 바꾸지 않고 새 곡으로 넣는다(3곡 한도는 그대로 본다).
    static func replaceable(_ old: String?, in s: PhoneState) -> SongRow? {
        old.flatMap { o in s.songs.first { r in
            guard r.id == o else { return false }
            if case .rejected = r.state { return true }
            if case .failed = r.state { return true }
            return false
        } }
    }

    /// 새 곡과 (바꿀 곡의 지움)을 한 번에 넣는다. 지움 메시지가 먼저 선다.
    static func add(_ row: SongRow, upload: OutboxItem, replacing: (id: String, removal: OutboxItem)?, now: Date, in s: inout PhoneState) {
        if let r = replacing { OutboxRules.markSongRemoved(r.id, removal: r.removal, now: now, in: &s) }
        s.songs.append(row)
        s.outbox.append(upload)
    }

    /// 실험에 쓸 곡 고르기는 차로 보내는 요청만 남긴다(보이는 표시는 차가 알려 주는 `SongRow.candidate`).
    static func select(_ item: OutboxItem, in s: inout PhoneState) {
        s.outbox.append(item)
    }
}

extension PhoneState {
    /// 지운 곡(순번 비교용 표시)은 숨긴다.
    var visibleSongs: [SongRow] { songs.filter { $0.state != .removed }.sorted { $0.addedAt < $1.addedAt } }

    /// 3곡 한도에 드는 곡 수. 거절된 곡은 줄은 보이지만(9장 거절 문구·'다시 녹음') 자동 지움을 보내므로 자리는 차지하지 않는다(7.4, G40).
    func songSlots(excluding id: String?) -> Int {
        visibleSongs.filter { s in
            if s.id == id { return false }
            if case .rejected = s.state { return false }
            return true
        }.count
    }

    var songSlotsUsed: Int { songSlots(excluding: nil) }

    var car: DeviceInfo? { devices.first { $0.role == .car } }

    func alert(_ id: String) -> StoredAlert? { alerts.first { $0.id == id } }
}
