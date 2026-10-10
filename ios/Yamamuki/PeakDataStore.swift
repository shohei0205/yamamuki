import Foundation
import YamamukiCore

/// 取り込み済みの全国の山データの版を、端末内(UserDefaults)に覚えておく。
/// 取り込んだデータ本体(gz)も 1 つだけ残す(`archive`)。取り直せるので、バックアップには入れない。
struct PeakDataStore {
    private let defaults = UserDefaults.standard
    private let key = "peakData"
    private let archiveUrl: URL = {
        var directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("peak-data", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? directory.setResourceValues(values)
        return directory.appendingPathComponent("osm-peaks.json.gz")
    }()

    var archive: PeakDataArchive { FilePeakDataArchive(url: archiveUrl) }

    func load() -> InstalledPeakData? {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(InstalledPeakData.self, from: $0) }
    }

    func save(_ data: InstalledPeakData) {
        if let encoded = try? JSONEncoder().encode(data) { defaults.set(encoded, forKey: key) }
    }

    /// 記録と、残した gz を消す。
    func clear() {
        defaults.removeObject(forKey: key)
        try? FileManager.default.removeItem(at: archiveUrl)
    }
}
