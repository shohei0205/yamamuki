// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Foundation
import SQLite3
import YamamukiCore

/// 取り込み済みの地表データの版を、種類ごとに端末内(UserDefaults)に覚えておく。
/// データ本体(SQLite)と取り込んだ manifest は Application Support の surface-data/<種類>/ に置く(`files`)。
/// 取り直せるので、山データと同じくバックアップには入れない。
struct SurfaceDataStore {
    let dataset: SurfaceDataset
    let files: SurfaceDataFiles
    private let defaults = UserDefaults.standard
    private let key: String

    init(dataset: SurfaceDataset) {
        self.dataset = dataset
        key = "surfaceData.\(dataset.id)"
        var directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("surface-data", isDirectory: true)
            .appendingPathComponent(dataset.id, isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? directory.setResourceValues(values)
        files = SurfaceDataFiles(directory: directory, dataset: dataset)
    }

    /// 取り込み済みの記録。記録があってもデータ本体が無ければ(消去の途中でアプリが終わったときなど)、nil。
    func load() -> InstalledSurfaceData? {
        guard FileManager.default.fileExists(atPath: files.database.path) else { return nil }
        return defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(InstalledSurfaceData.self, from: $0) }
    }

    func save(_ data: InstalledSurfaceData) {
        if let encoded = try? JSONEncoder().encode(data) { defaults.set(encoded, forKey: key) }
    }

    /// 記録を先に消してから、データ本体と manifest を消す。途中でアプリが終わっても、記録だけが残らないように。
    func clear() {
        defaults.removeObject(forKey: key)
        files.delete()
    }
}

/// ダウンロードした地表データの SQLite を、置き換える前に開いて確かめる。
enum SurfaceDatabaseValidator {
    /// `file` が山むきの SQLite コンテナで、`manifest` に書かれた地表のデータセットとタイルを収録しているかを確かめる。
    /// 違えば `SurfaceDataError` を投げる。
    static func validate(_ file: URL, manifest: SurfaceManifest) throws {
        try SurfaceData.requireContainer(read(file, manifest: manifest), manifest: manifest)
    }

    /// コンテナの目印と収録内容。SQLite として読めなければ nil。
    private static func read(_ file: URL, manifest: SurfaceManifest) -> SurfaceContainerInfo? {
        var db: OpaquePointer?
        defer { sqlite3_close(db) }
        guard sqlite3_open_v2(file.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK,
              let applicationId = integer(db, "PRAGMA application_id"),
              let containerVersion = integer(db, "PRAGMA user_version")
        else { return nil }
        // 山むきの SQLite でなければ、地表の表があるとは限らないので数えない。
        guard applicationId == SurfaceData.containerApplicationId else {
            return SurfaceContainerInfo(applicationId: applicationId, containerVersion: containerVersion, levelCount: 0)
        }
        let arguments = [manifest.datasetId, SurfaceData.datasetKind, String(SurfaceData.surfaceFormatVersion)]
        guard let levels = integer(db, SurfaceData.levelCountSql, arguments) else { return nil }
        return SurfaceContainerInfo(applicationId: applicationId, containerVersion: containerVersion, levelCount: levels)
    }

    /// `sql` の 1 行目の 1 列目の整数。読めなければ nil。
    private static func integer(_ db: OpaquePointer?, _ sql: String, _ arguments: [String] = []) -> Int? {
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return nil }
        // SQLite が文字列を写して持つようにする(呼び出しのあとで文字列が消えてもよいように)。
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (index, argument) in arguments.enumerated() {
            sqlite3_bind_text(statement, Int32(index + 1), argument, -1, transient)
        }
        switch sqlite3_step(statement) {
        case SQLITE_ROW: return Int(sqlite3_column_int64(statement, 0))
        case SQLITE_DONE: return 0
        default: return nil
        }
    }
}
