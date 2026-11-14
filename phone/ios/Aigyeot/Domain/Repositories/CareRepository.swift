import Foundation
import Observation

/// 돌봄 기록(`care.json`)의 원본(두 앱 같은 이름). 쓰기 입구는 `update` 하나다.
/// 그 위의 규칙(지우다 만 '기록 지우기'의 id 빼기)은 RecordCare.save 하나가 한다.
@MainActor
@Observable
final class CareRepository {
    @ObservationIgnored private let store: CareStore
    private(set) var care: CareFile
    /// 저장 실패 공통 처리(띠 글). 합성 루트가 `Notices.saveFailed`에 잇는다.
    @ObservationIgnored var onWriteFailed: () -> Void = {}

    init(store: CareStore) {
        self.store = store
        care = store.load()
    }

    enum WriteResult: Equatable {
        case unchanged, saved, failed
        var ok: Bool { self != .failed }
    }

    /// 돌봄 기록을 바꾸고 저장한다. 저장하지 못하면 메모리 값도 바꾸지 않는다.
    @discardableResult
    func update(_ f: (inout CareFile) -> Void) -> WriteResult {
        if store.writesRefused { onWriteFailed(); return .failed }
        var copy = care
        f(&copy)
        if copy == care { return .unchanged }
        do { try store.save(copy) } catch { onWriteFailed(); return .failed }
        care = copy
        return .saved
    }

    /// 지운 기록의 사본(깨진 원본·내보낸 CSV)을 지운다. 기록 지우기를 마칠 때만 부른다.
    func deleteCopies() { store.removeCopies() }

    /// 공유할 내보내기 파일을 쓴다. 지난 내보내기는 지우고 새로 쓴다. 쓰지 못하면 nil.
    func export(fileName: String, render: (CareFile) -> Data) -> URL? {
        store.writeExport(name: fileName, data: render(care))
    }
}

/// 디스크에 남기는 비밀값(기기 토큰)만 갖는다. 메모리의 지금 자격·세대·GONE은 PhoneStateRepository가 갖는다.
/// 칸이 없는 Keychain 통로라 액터에 묶지 않는다: 소켓이 붙을 때 자기 작업에서 토큰을 읽는다(나누기 전과 같다).
final class CredentialRepository: @unchecked Sendable {
    private let store: CredentialStore
    init(store: CredentialStore) { self.store = store }
    func load() -> Credentials? { store.load() }
    /// 저장하지 못하면 던진다. 부른 쪽은 성공한 뒤에만 연결을 켠다.
    func save(_ c: Credentials) throws { try store.save(c) }
    func clear() { store.clear() }
}
