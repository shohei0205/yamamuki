import Foundation

/// 設定画面に出すキャッシュの状況。
public struct CacheInfo: Sendable {
    public let mountainCount: Int
    public let tileCount: Int
    public let sizeBytes: Int64
}

/// 山データをタイルごとの JSON ファイルに保存するキャッシュ。
/// Android 版は Room(SQLite) を使うが、1 タイルが数百件程度なので、ファイル単位で読み書きしても十分速い。
/// 山が 0 件のタイルもファイルを作り、オフライン時に再取得しない。
public actor FileMountainCache: MountainCache {
    private struct TileFile: Codable {
        var fetchedAt: Date
        var mountains: [Mountain]
    }

    private let directory: URL
    /// 読み込んだタイルの内容。ファイルの無いタイルは nil を入れて、何度も探さないようにする。
    private var loaded: [Tile: TileFile?] = [:]

    public init(directory: URL) {
        self.directory = directory
    }

    public func fetchedAt(_ tiles: [Tile]) async throws -> [Tile: Date] {
        var result: [Tile: Date] = [:]
        for tile in tiles {
            if let file = load(tile) { result[tile] = file.fetchedAt }
        }
        return result
    }

    public func mountains(in box: BoundingBox) async throws -> [Mountain] {
        Tile.covering(box).flatMap { tile in
            (load(tile)?.mountains ?? []).filter { box.contains($0.latitude, $0.longitude) }
        }
    }

    public func replaceTiles(_ tiles: [Tile], mountains: [Mountain], fetchedAt: Date) async throws {
        let byTile = Dictionary(grouping: mountains) { Tile.of($0.latitude, $0.longitude) }
        try createDirectory()
        let encoder = JSONEncoder()
        for tile in tiles {
            let file = TileFile(fetchedAt: fetchedAt, mountains: byTile[tile] ?? [])
            try encoder.encode(file).write(to: url(of: tile), options: .atomic)
            loaded[tile] = file
        }
    }

    public func info() -> CacheInfo {
        var mountains = 0
        var tiles = 0
        var bytes: Int64 = 0
        for url in tileFiles() {
            guard let tile = tileIndex(of: url), let file = load(tile) else { continue }
            tiles += 1
            mountains += file.mountains.count
            bytes += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return CacheInfo(mountainCount: mountains, tileCount: tiles, sizeBytes: bytes)
    }

    /// 山と取得済みタイルを消す(keeping のタイルは残す)。
    public func clear(keeping: Set<Tile> = []) {
        for url in tileFiles() {
            if let tile = tileIndex(of: url), keeping.contains(tile) { continue }
            try? FileManager.default.removeItem(at: url)
        }
        loaded = [:]
    }

    /// 指定したタイルだけを消す(事前ダウンロードした地域の削除)。
    public func remove(_ tiles: [Tile]) {
        for tile in tiles {
            try? FileManager.default.removeItem(at: url(of: tile))
            loaded[tile] = .some(nil)
        }
    }

    /// 保存先を作る。山データは取り直せるので iCloud バックアップから外す
    /// (Caches に置くと OS に消されてオフラインで使えなくなるため、置き場所はそのままにする)。
    private func createDirectory() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = directory
        try url.setResourceValues(values)
    }

    private func load(_ tile: Tile) -> TileFile? {
        if let cached = loaded[tile] { return cached }
        let file = (try? Data(contentsOf: url(of: tile))).flatMap { try? JSONDecoder().decode(TileFile.self, from: $0) }
        loaded[tile] = .some(file)
        return file
    }

    private func url(of tile: Tile) -> URL {
        directory.appendingPathComponent("tile_\(tile.latIndex)_\(tile.lonIndex).json")
    }

    private func tileIndex(of url: URL) -> Tile? {
        let parts = url.deletingPathExtension().lastPathComponent.split(separator: "_")
        guard parts.count == 3, parts[0] == "tile", let la = Int(parts[1]), let lo = Int(parts[2]) else { return nil }
        return Tile(latIndex: la, lonIndex: lo)
    }

    private func tileFiles() -> [URL] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return urls.filter { $0.pathExtension == "json" && $0.lastPathComponent.hasPrefix("tile_") }
    }
}
