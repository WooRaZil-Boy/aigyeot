import Foundation

final class FileStateStore: StateStore {
    private let url: URL
    private(set) var restoreFailed = false
    /// 원본이나 녹음 폴더를 옆에 보존하지 못했을 때만 켠다. 그러면 이 실행에서는 아무것도 쓰지 않는다(다음 실행이 정상 복원으로 보고 녹음을 지우지 않게).
    private var refuseWrites = false
    var writesRefused: Bool { refuseWrites }
    private let clock: () -> Date

    init(directory: URL = FileStateStore.supportDirectory, clock: @escaping () -> Date = Date.init) {
        self.clock = clock
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        url = directory.appendingPathComponent("state.json")
    }

    static var supportDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    private static func coders() -> (JSONEncoder, JSONDecoder) {
        let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601
        let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601
        return (e, d)
    }

    /// 파일이 없으면 첫 실행이라 빈 상태다. 파일이 있는데 읽지 못하면 빈 상태로 덮어쓰지 않는다:
    /// 원본을 `state.json.broken-<시각>`으로 옆에 남기고, 녹음 폴더도 옆으로 옮겨 다음 실행의 정리가 지우지 못하게 한다.
    func load() -> PhoneState {
        guard FileManager.default.fileExists(atPath: url.path) else { return PhoneState() }
        let data = try? Data(contentsOf: url)
        if let data, let s = try? Self.coders().1.decode(PhoneState.self, from: data) { return s }
        restoreFailed = true
        let dir = url.deletingLastPathComponent()
        let stamp = String(Int(clock().timeIntervalSince1970))
        guard let data, (try? data.write(to: dir.appendingPathComponent("state.json.broken-\(stamp)"))) != nil else {
            refuseWrites = true   // 읽지 못했거나 보존하지 못했다. 원본을 건드리지 않는다.
            return PhoneState()
        }
        let songs = dir.appendingPathComponent("songs", isDirectory: true)
        if FileManager.default.fileExists(atPath: songs.path) {
            do { try FileManager.default.moveItem(at: songs, to: dir.appendingPathComponent("songs.broken-\(stamp)")) }
            catch { refuseWrites = true }   // 녹음 폴더를 보존하지 못하면 빈 상태를 저장하지 않는다
        }
        return PhoneState()
    }

    /// 임시 파일에 쓴 뒤 바꿔치기(`.atomic`)라 도중에 죽어도 옛 파일이나 새 파일 중 하나만 남는다.
    func save(_ s: PhoneState) throws {
        if refuseWrites { throw StoreError() }
        let data = try Self.coders().0.encode(s)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

/// SongFileStore 구현: 앱 전용 폴더의 녹음 파일과 임시 폴더의 녹음 원본.
struct FileSongStore: SongFileStore {
    func url(for songId: String) -> URL { SongFiles.url(for: songId) }
    func exists(_ songId: String) -> Bool { FileManager.default.fileExists(atPath: url(for: songId).path) }
    func copyIn(_ recording: URL, as songId: String) throws {
        let dest = url(for: songId)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.copyItem(at: recording, to: dest)
    }
    func size(of url: URL) -> Int { (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0 }
    func remove(_ songId: String) { SongFiles.remove(songId) }
    func removeAll() { SongFiles.removeAll() }
    func removeBrokenCopies() { SongFiles.removeBrokenCopies() }
    func removeFiles(notIn keep: Set<String>) {
        let files = (try? FileManager.default.contentsOfDirectory(at: SongFiles.directory, includingPropertiesForKeys: nil)) ?? []
        for f in files where f.pathExtension == "m4a" && !keep.contains(f.deletingPathExtension().lastPathComponent) {
            try? FileManager.default.removeItem(at: f)
        }
    }
    func removeTempRecordings() { AudioRecorder.removeTempRecordings() }
}

/// 녹음 파일 폴더(Application Support/songs). 곡을 지울 때 함께 지운다(7.4).
enum SongFiles {
    static var directory: URL {
        let d = FileStateStore.supportDirectory.appendingPathComponent("songs", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }
    static func url(for songId: String) -> URL { directory.appendingPathComponent("\(songId).m4a") }
    static func remove(_ songId: String) { try? FileManager.default.removeItem(at: url(for: songId)) }
    static func removeAll() { try? FileManager.default.removeItem(at: directory) }

    /// 복원 실패 때 남긴 사본(`state.json.broken-*`, `songs.broken-*`)을 지운다. 사용자가 기록 지우기·연결 끊기를 한 때만 부른다.
    static func removeBrokenCopies(in dir: URL = FileStateStore.supportDirectory) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        for n in names where n.hasPrefix("state.json.broken-") || n.hasPrefix("songs.broken-") {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(n))
        }
    }
}
