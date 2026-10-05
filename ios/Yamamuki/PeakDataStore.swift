import Foundation
import YamamukiCore

/// 取り込み済みの全国の山データの版を、端末内(UserDefaults)に覚えておく。
struct PeakDataStore {
    private let defaults = UserDefaults.standard
    private let key = "peakData"

    func load() -> InstalledPeakData? {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(InstalledPeakData.self, from: $0) }
    }

    func save(_ data: InstalledPeakData) {
        if let encoded = try? JSONEncoder().encode(data) { defaults.set(encoded, forKey: key) }
    }

    func clear() {
        defaults.removeObject(forKey: key)
    }
}
