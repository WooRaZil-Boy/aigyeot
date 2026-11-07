import Foundation
import Security

struct KeychainError: Error { let status: OSStatus }

/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. 백업·다른 기기 복원에 실리지 않는다.
struct KeychainCredentialStore: CredentialStore {
    var service = "kr.aigyeot.phone.credentials"
    private let account = "device"

    private var base: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    func load() -> Credentials? {
        var q = base
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(Credentials.self, from: data)
    }

    func save(_ c: Credentials) throws {
        clear()
        let data = try JSONEncoder().encode(c)
        var q = base
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    func clear() { SecItemDelete(base as CFDictionary) }
}
