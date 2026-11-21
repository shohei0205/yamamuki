// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import Foundation

/// このアプリが読める地表データの種類(カタログの datasets のキー)。yamamuki-data は同じ地表を収録ズームの違う
/// ファイルに分けて配るので、配布ファイルごとに種類を分ける。並びは選択画面の並び。
public enum SurfaceDataset: String, CaseIterable, Codable, Sendable {
    /// ESA WorldCover 2021 の森林・水域と、国土地理院の標高タイルから作った陰影。ズーム 5〜9(約 17 MB)。
    case esaWorldcoverZ9 = "esa-worldcover-z9"

    /// `esaWorldcoverZ9` と同じ地表で、ズーム 5〜10(約 42 MB)まで収める。拡大したときに細かく見える。
    case esaWorldcoverZ10 = "esa-worldcover-z10"

    /// 同じ地表で、ズーム 5〜11(約 127 MB)まで収める。
    case esaWorldcoverZ11 = "esa-worldcover-z11"

    /// 同じ地表で、ズーム 5〜12(約 390 MB)まで収める。
    case esaWorldcoverZ12 = "esa-worldcover-z12"

    /// カタログでの名前(datasets のキー)。
    public var id: String { rawValue }

    /// データ本体のファイル名(manifest の fileName)。
    public var fileName: String { "\(id).sqlite3" }
}

/// yamamuki-data が配る地表データの目印(manifest)。形式は yamamuki-data の formats/sqlite.md の「配布manifest」にある。
public struct SurfaceManifest: Equatable, Sendable {
    /// データの版。生成時の UTC 日時と Actions の実行 ID をつないだ文字列。
    public let version: String
    /// データ本体(SQLite)の URL。
    public let downloadUrl: URL
    /// データ本体の SHA-256(小文字の16進数)。
    public let sha256: String
    /// データ本体のバイト数。
    public let sizeBytes: Int
    /// SQLite の中の、地表のデータセットの ID(datasets の id)。
    public let datasetId: String
    /// 試験版のデータ(experimental が true)。
    public let experimental: Bool

    public init(version: String, downloadUrl: URL, sha256: String, sizeBytes: Int, datasetId: String, experimental: Bool = false) {
        self.version = version
        self.downloadUrl = downloadUrl
        self.sha256 = sha256
        self.sizeBytes = sizeBytes
        self.datasetId = datasetId
        self.experimental = experimental
    }

    /// データを作った日(UTC)。「2026/10/07」の形。`version` から読めなければ nil。
    public var releaseDate: String? { PeakData.releaseDate(version) }
}

/// 受け取った SQLite から読んだ、コンテナの目印と収録内容。
public struct SurfaceContainerInfo: Equatable, Sendable {
    /// PRAGMA application_id。
    public let applicationId: Int
    /// PRAGMA user_version。
    public let containerVersion: Int
    /// manifest に書かれた地表のデータセットの、ズームの段の数(`SurfaceData.levelCountSql`)。
    public let levelCount: Int

    public init(applicationId: Int, containerVersion: Int, levelCount: Int) {
        self.applicationId = applicationId
        self.containerVersion = containerVersion
        self.levelCount = levelCount
    }
}

/// 地表データが壊れている、または読めない形式だった。
public struct SurfaceDataError: Error, CustomStringConvertible, Equatable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

public enum SurfaceData {
    /// 読める manifest の形式の版。
    public static let manifestSchemaVersion = 6

    /// 読めるデータ本体の形式の名前(manifest の dataSchemaFormat)。山むきの SQLite コンテナ。
    public static let dataSchemaFormat = "yamamuki-sqlite"

    /// 読める SQLite コンテナの版(manifest の dataSchemaVersion。SQLite の PRAGMA user_version と同じ値)。
    public static let containerVersion = 1

    /// 山むきの SQLite コンテナの目印(PRAGMA application_id。ASCII で「YMDB」)。
    public static let containerApplicationId = 0x594D4442

    /// 地表のデータセットの種類(datasets の kind)。
    public static let datasetKind = "surface"

    /// 読める地表の形式の版(datasets の formatVersion)。
    public static let surfaceFormatVersion = 1

    /// これより大きいデータは受け取らない。ズーム 9 までの版は約 17 MB、ズーム 10 までの版は約 42 MB。
    public static let maxSizeBytes = 500_000_000

    /// `requireContainer` に渡す、ズームの段の数を数える SQL。引数は順に、データセットの ID・種類(`datasetKind`)・
    /// 地表の形式の版(`surfaceFormatVersion`)。
    public static let levelCountSql =
        "SELECT COUNT(*) FROM surface_levels AS l JOIN datasets AS d USING(dataset_key) "
        + "WHERE d.id = ? AND d.kind = ? AND d.format_version = ?"

    /// 受け取った SQLite から読んだ値で、`manifest` の地表データを収録した山むきの SQLite コンテナかを確かめる。
    /// SQLite を開いて値を読むのはアプリ側で、SQLite として読めなかったときは `container` に nil を渡す。
    /// 違えば `SurfaceDataError` を投げる。
    public static func requireContainer(_ container: SurfaceContainerInfo?, manifest: SurfaceManifest) throws {
        guard let container else { throw SurfaceDataError("地表データを SQLite として読めません") }
        guard container.applicationId == containerApplicationId else { throw SurfaceDataError("山むきの SQLite ではありません") }
        guard container.containerVersion == containerVersion else {
            throw SurfaceDataError("このアプリが読めない形式の地表データです(データの版 \(container.containerVersion))。アプリを更新してください。")
        }
        guard container.levelCount > 0 else { throw SurfaceDataError("地表のデータセット \(manifest.datasetId) がありません") }
    }

    /// `dataset` の manifest を読む。山データなど、ほかの種類の manifest は読まない。
    public static func parseManifest(_ body: Data, dataset: SurfaceDataset) throws -> SurfaceManifest {
        guard let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else {
            throw SurfaceDataError("manifest を読めません")
        }
        func string(_ key: String) throws -> String {
            guard let value = root[key] as? String else { throw SurfaceDataError("manifest に \(key) がありません") }
            return value
        }
        func int(_ key: String) throws -> Int {
            guard let value = PeakData.jsonInt(root[key]) else { throw SurfaceDataError("manifest の \(key) が整数ではありません") }
            return value
        }

        let schemaVersion = try int("schemaVersion")
        guard schemaVersion == manifestSchemaVersion else {
            throw SurfaceDataError("このアプリが読めない形式の地表データです(形式の版 \(schemaVersion))。アプリを更新してください。")
        }
        guard root["schemaFormat"] as? String == PeakData.manifestSchemaFormat else {
            throw SurfaceDataError("manifest の schemaFormat が \(PeakData.manifestSchemaFormat) ではありません")
        }
        guard root["dataSchemaFormat"] as? String == dataSchemaFormat else {
            throw SurfaceDataError("manifest の dataSchemaFormat が \(dataSchemaFormat) ではありません")
        }
        let version = PeakData.jsonInt(root["dataSchemaVersion"])
        guard version == containerVersion else {
            throw SurfaceDataError("このアプリが読めない形式の地表データです(データの版 \(version.map(String.init) ?? "不明"))。アプリを更新してください。")
        }
        // 収録ズームの違うファイルを取り違えないように、データ本体のファイル名で種類を確かめる。
        guard root["fileName"] as? String == dataset.fileName else {
            throw SurfaceDataError("manifest の fileName が \(dataset.fileName) ではありません(\(root["fileName"].map { "\($0)" } ?? "なし"))")
        }
        // 地表のデータセットを 1 つだけ収録したファイルを読む。形式の版が違うものは、アプリの更新を促す。
        let surfaces = (root["datasets"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }.filter { $0["kind"] as? String == datasetKind }
        guard surfaces.count == 1, let surface = surfaces.first else {
            throw SurfaceDataError("manifest の地表のデータセットが 1 つではありません(\(surfaces.count) 件)")
        }
        let formatVersion = PeakData.jsonInt(surface["formatVersion"])
        guard formatVersion == surfaceFormatVersion else {
            throw SurfaceDataError("このアプリが読めない形式の地表データです(地表の形式の版 \(formatVersion.map(String.init) ?? "不明"))。アプリを更新してください。")
        }
        guard let datasetId = surface["id"] as? String, !datasetId.isEmpty else {
            throw SurfaceDataError("manifest の地表のデータセットに id がありません")
        }

        guard let downloadUrl = URL(string: try string("downloadUrl")), downloadUrl.scheme == "https" else {
            throw SurfaceDataError("manifest の downloadUrl が https ではありません")
        }
        let manifest = SurfaceManifest(
            version: try string("version"),
            downloadUrl: downloadUrl,
            sha256: try string("sha256").lowercased(),
            sizeBytes: try int("sizeBytes"),
            datasetId: datasetId,
            experimental: root["experimental"] as? Bool == true
        )
        guard manifest.sha256.count == 64, manifest.sha256.allSatisfy({ $0.isASCII && ($0.isNumber || ("a"..."f").contains($0)) }) else {
            throw SurfaceDataError("manifest の sha256 が SHA-256 の形ではありません")
        }
        guard (1...maxSizeBytes).contains(manifest.sizeBytes) else {
            throw SurfaceDataError("manifest のサイズ \(manifest.sizeBytes) バイトは受け取れません")
        }
        return manifest
    }
}
