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

    /// 깨진 사본을 지운다. '이 휴대폰의 기록 지우기'에서만 부른다(연결 끊기는 돌봄 기록을 남긴다, R9).
    static func removeBrokenCopies(in dir: URL = FileStateStore.supportDirectory) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        for n in names where n.hasPrefix("care.json.broken-") {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(n))
        }
    }
}

extension CareExport {
    /// 공유하려고 임시 폴더에 쓴 사본(`aigyeot-care-*.csv`)을 지운다. '이 휴대폰의 기록 지우기'가 부른다(계약 7.4).
    static func removeCopies(in dir: URL = FileManager.default.temporaryDirectory) {
        let fm = FileManager.default
        for name in (try? fm.contentsOfDirectory(atPath: dir.path)) ?? [] where name.hasPrefix("aigyeot-care-") && name.hasSuffix(".csv") {
            try? fm.removeItem(at: dir.appendingPathComponent(name))
        }
    }
}
