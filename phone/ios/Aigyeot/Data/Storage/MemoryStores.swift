import Foundation
import Security

// 메모리 저장소(시험·미리보기). 실패를 흉내 내는 손잡이가 있다.

final class MemoryCredentialStore: CredentialStore {
    var value: Credentials?
    /// 시험에서 Keychain 저장 실패를 흉내 낸다.
    var failing = false
    init(_ value: Credentials? = nil) { self.value = value }
    func load() -> Credentials? { value }
    func save(_ c: Credentials) throws {
        if failing { throw KeychainError(status: errSecIO) }
        value = c
    }
    func clear() { value = nil }
}

final class MemoryStateStore: StateStore {
    var value: PhoneState
    /// 시험에서 저장 실패를 흉내 낸다.
    var failing = false
    var restoreFailed = false
    var writesRefused = false
    init(_ value: PhoneState = PhoneState()) { self.value = value }
    func load() -> PhoneState { value }
    /// 다음 n번만 실패(시험).
    var failNext = 0
    /// 저장 횟수와, 그 횟수째에 한 번 실패(시험).
    var saveCount = 0
    var failAtSave: Int?
    /// 이 횟수째부터 모두 실패(그사이 앱이 죽은 것과 같다, 시험).
    var failFromSave: Int?
    func save(_ s: PhoneState) throws {
        saveCount += 1
        if failing { throw StoreError() }
        if failNext > 0 { failNext -= 1; throw StoreError() }
        if failAtSave == saveCount { throw StoreError() }
        if let f = failFromSave, saveCount >= f { throw StoreError() }
        value = s
    }
}

final class MemoryCareStore: CareStore {
    var value: CareFile
    var failing = false
    var writesRefused = false
    private(set) var saves = 0
    init(_ value: CareFile = CareFile()) { self.value = value }
    func load() -> CareFile { value }
    /// 다음 n번만 실패(시험).
    var failNext = 0
    func save(_ c: CareFile) throws {
        if failing { throw StoreError() }
        if failNext > 0 { failNext -= 1; throw StoreError() }
        value = c
        saves += 1
    }
    /// 메모리 저장도 앱처럼 실제 사본 파일을 지운다(나누기 전 시험이 보던 동작 그대로).
    func removeCopies() { FileCareStore.removeBrokenCopies(); CareExport.removeCopies() }
}
