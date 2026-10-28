import Foundation
import Shared

#if DEBUG
/**
 * Debug builds' stand-in for [KeychainStore]: the tokens in a JSON file (owner-only) in the
 * app's sandboxed Application Support folder. Each ad-hoc-signed rebuild counts as a new app
 * to the Keychain, which then asks for the login password on every launch; release builds
 * keep the Keychain.
 */
final class DebugTokenStore: NSObject, KeyValueStore {
    private let url: URL
    private var values: [String: String]

    init(directory: URL) {
        url = directory.appendingPathComponent("debug-tokens.json")
        values = (try? Data(contentsOf: url))
            .flatMap { try? JSONDecoder().decode([String: String].self, from: $0) } ?? [:]
    }

    func getString(key: String) -> String? { values[key] }

    func putString(key: String, value: String) {
        putStrings(values: [key: value])
    }

    func putStrings(values: [String: String]) {
        self.values.merge(values) { _, new in new }
        save()
    }

    func remove(keys: KotlinArray<NSString>) {
        for i in 0..<keys.size {
            if let key = keys.get(index: i) as String? { values.removeValue(forKey: key) }
        }
        save()
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(values) else { return }
        try? data.write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}
#endif
