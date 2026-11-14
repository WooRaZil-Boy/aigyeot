import Foundation

// MARK: 저장(state.json과 같은 방식: 앱 전용 폴더, 임시 파일 → 바꿔치기)

final class FileCareStore: CareStore {
    private let url: URL
    private var refuseWrites = false
    var writesRefused: Bool { refuseWrites }
    private let clock: () -> Date

    init(directory: URL = FileStateStore.supportDirectory, clock: @escaping () -> Date = Date.init) {
        self.clock = clock
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        url = directory.appendingPathComponent("care.json")
    }

    /// 파일이 없으면 빈 기록이다. 읽지 못하면 원본을 `care.json.broken-<시각>`으로 옆에 남기고 빈 기록으로 시작한다.
    /// 남기지 못하면 이 실행에서는 쓰지 않는다(원본을 빈 기록으로 덮지 않게).
    func load() -> CareFile {
        guard FileManager.default.fileExists(atPath: url.path) else { return CareFile() }
        let data = try? Data(contentsOf: url)
        if let data, let file = try? CareCoding.decode(data) { return file }
        let copy = url.deletingLastPathComponent().appendingPathComponent("care.json.broken-\(Int(clock().timeIntervalSince1970))")
        if data == nil || (try? data!.write(to: copy)) == nil { refuseWrites = true }
        return CareFile()
    }

    func save(_ c: CareFile) throws {
        if refuseWrites { throw StoreError() }
        try CareCoding.encode(c).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func removeCopies() { Self.removeBrokenCopies(); CareExport.removeCopies() }

    func writeExport(name: String, data: Data) -> URL? {
        CareExport.removeCopies()
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        return (try? data.write(to: url, options: .atomic)) == nil ? nil : url
    }

    /// 깨진 사본을 지운다. '이 휴대폰의 기록 지우기'에서만 부른다(연결 끊기는 돌봄 기록을 남긴다).
    static func removeBrokenCopies(in dir: URL = FileStateStore.supportDirectory) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        for n in names where n.hasPrefix("care.json.broken-") {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(n))
        }
    }
}

extension CareExport {
    /// 공유하려고 임시 폴더에 쓴 사본(`aigyeot-care-*.csv`)을 지운다. '이 휴대폰의 기록 지우기'가 부른다.
    static func removeCopies(in dir: URL = FileManager.default.temporaryDirectory) {
        let fm = FileManager.default
        for name in (try? fm.contentsOfDirectory(atPath: dir.path)) ?? [] where name.hasPrefix("aigyeot-care-") && name.hasSuffix(".csv") {
            try? fm.removeItem(at: dir.appendingPathComponent(name))
        }
    }
}

// MARK: 고른 파일 읽기(기록 가져오기)

/// 파일 고르기 바로 뒤 한 곳: 보안 범위 접근 시작·끝, 파일 제공자와 맞춘 읽기(NSFileCoordinator), `limit` 바이트까지만.
/// 빈 파일은 빈 데이터(읽기 실패와 구별), 열거나 읽지 못하면 nil.
struct CoordinatedFileReader: PickedFileReader {
    func read(_ url: URL, upTo limit: Int) -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var data: Data?
        var error: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &error) { u in
            guard let h = try? FileHandle(forReadingFrom: u) else { return }
            defer { try? h.close() }
            do { data = try h.read(upToCount: limit) ?? Data() } catch { data = nil }
        }
        return data
    }
}
