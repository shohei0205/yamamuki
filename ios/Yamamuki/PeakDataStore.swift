import Foundation
import YamamukiCore

/// 取り込み済みの全国の山データの版を、山データの種類ごとに端末内(UserDefaults)に覚えておく。
/// 取り込んだ manifest とデータ本体(gz)も種類ごとに 1 組だけ残す(`archive`)。取り直せるので、バックアップには入れない。
/// osm-peaks は 0.7.0 までと同じキーと置き場所のまま。
struct PeakDataStore {
    let dataset: PeakDataset
    private let defaults = UserDefaults.standard
    private let key: String
    private let updateFoundKey: String
    private let archiveDirectory: URL

    /// 自動の確認の日時。種類をまたいで 1 つだけ持つ(0.7.0 までと同じキー)。
    private static let autoCheckedAtKey = "peakDataAutoCheckedAt"

    init(dataset: PeakDataset) {
        self.dataset = dataset
        let suffix = dataset == .osmPeaks ? "" : ".\(dataset.id)"
        key = "peakData\(suffix)"
        updateFoundKey = "peakDataUpdateFound\(suffix)"
        var directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("peak-data", isDirectory: true)
        if dataset != .osmPeaks { directory.appendPathComponent(dataset.id, isDirectory: true) }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? directory.setResourceValues(values)
        archiveDirectory = directory
    }

    var archive: PeakDataArchive { FilePeakDataArchive(directory: archiveDirectory, dataset: dataset) }

    func load() -> InstalledPeakData? {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(InstalledPeakData.self, from: $0) }
    }

    func save(_ data: InstalledPeakData) {
        if let encoded = try? JSONEncoder().encode(data) { defaults.set(encoded, forKey: key) }
    }

    /// 自動の確認で、最後に確かめられた日時。まだ確かめていなければ nil。
    static var autoCheckedAt: Date? { UserDefaults.standard.object(forKey: autoCheckedAtKey) as? Date }

    static func saveAutoCheckedAt(_ date: Date) {
        UserDefaults.standard.set(date, forKey: autoCheckedAtKey)
    }

    /// 自動の確認か選択画面の確認で新しい版が見つかり、まだ取り込んでいない。アプリを開き直しても方位盤のラベルを出し続ける。
    var updateFound: Bool { defaults.bool(forKey: updateFoundKey) }

    func saveUpdateFound(_ updateFound: Bool) {
        defaults.set(updateFound, forKey: updateFoundKey)
    }

    /// 記録と、残した manifest と gz を消す。自動の確認の日時は種類をまたいだものなので残す。
    func clear() {
        defaults.removeObject(forKey: key)
        defaults.removeObject(forKey: updateFoundKey)
        for name in [FilePeakDataArchive.manifestName, dataset.fileName] {
            try? FileManager.default.removeItem(at: archiveDirectory.appendingPathComponent(name))
        }
    }
}
