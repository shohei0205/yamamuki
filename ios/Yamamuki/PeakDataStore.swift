import Foundation
import YamamukiCore

/// 取り込み済みの全国の山データの版を、端末内(UserDefaults)に覚えておく。
/// 取り込んだ manifest とデータ本体(gz)も 1 組だけ残す(`archive`)。取り直せるので、バックアップには入れない。
struct PeakDataStore {
    private let defaults = UserDefaults.standard
    private let key = "peakData"
    private let archiveDirectory: URL = {
        var directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("peak-data", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? directory.setResourceValues(values)
        return directory
    }()

    var archive: PeakDataArchive { FilePeakDataArchive(directory: archiveDirectory) }

    func load() -> InstalledPeakData? {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(InstalledPeakData.self, from: $0) }
    }

    func save(_ data: InstalledPeakData) {
        if let encoded = try? JSONEncoder().encode(data) { defaults.set(encoded, forKey: key) }
    }

    /// 記録と、残した manifest と gz を消す。
    func clear() {
        defaults.removeObject(forKey: key)
        for name in [FilePeakDataArchive.manifestName, FilePeakDataArchive.dataName] {
            try? FileManager.default.removeItem(at: archiveDirectory.appendingPathComponent(name))
        }
    }
}
