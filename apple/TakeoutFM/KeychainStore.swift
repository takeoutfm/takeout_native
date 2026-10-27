import Foundation
import Security
import Shared

/**
 * The shared core's encrypted store on the Mac: each value is a generic password in the
 * login Keychain, under [service], with the key as the account (as EncryptedSharedPreferences
 * on Android).
 */
final class KeychainStore: NSObject, KeyValueStore {
    private let service: String

    init(service: String) {
        self.service = service
    }

    private func query(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }

    func getString(key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func putString(key: String, value: String) {
        let data = Data(value.utf8)
        let status = SecItemUpdate(query(key) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = query(key)
            add[kSecValueData as String] = data
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    func putStrings(values: [String: String]) {
        values.forEach { putString(key: $0.key, value: $0.value) }
    }

    func remove(keys: KotlinArray<NSString>) {
        for i in 0..<keys.size {
            if let key = keys.get(index: i) as String? {
                SecItemDelete(query(key) as CFDictionary)
            }
        }
    }
}
