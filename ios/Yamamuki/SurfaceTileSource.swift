// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import CoreGraphics
import Foundation
import ImageIO
import SQLite3
import YamamukiCore

/// 端末に置いた地表データの SQLite から、タイルを読んで 1 枚の色の画像にする。
/// 森林・陰影・水域の画像(WebP か PNG)を復号し、core の `SurfaceCompositor` で合成する。
/// SQLite は読み取り専用で開く。呼び出しは 1 つのスレッドから順に行う(`SurfaceLayerRenderer` の中だけで使う)。
final class SurfaceTileSource {
    private var db: OpaquePointer?
    private var datasetKey = -1
    private var layerKeys: [SurfaceLayerKind: Int] = [:]
    private var shade: SurfaceShade?

    /// 収録されたズームの段。森林・陰影・水域のどのレイヤーにもある段だけ。
    private(set) var levels: [SurfaceLevel] = []

    /// - Parameter datasetId: SQLite の中の、地表のデータセットの ID(manifest の datasets の id)。
    /// SQLite として開けなければ nil。
    init?(file: URL, datasetId: String) {
        guard sqlite3_open_v2(file.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else {
            sqlite3_close(db)
            return nil
        }
        query("SELECT dataset_key FROM datasets WHERE id = ? AND kind = 'surface'", [datasetId]) { statement in
            datasetKey = Int(sqlite3_column_int64(statement, 0))
        }
        query("SELECT layer_key, id, role, settings_json FROM surface_layers WHERE dataset_key = ?", [String(datasetKey)]) { statement in
            guard let kind = SurfaceLayerKind.of(id: text(statement, 1), role: text(statement, 2)) else { return }
            layerKeys[kind] = Int(sqlite3_column_int64(statement, 0))
            if kind == .hillshade { shade = SurfaceShade.parse(text(statement, 3) ?? "") }
        }
        var common: Set<SurfaceLevel>?
        for layerKey in layerKeys.values {
            var found = Set<SurfaceLevel>()
            query("SELECT z, tile_pixels FROM surface_levels WHERE dataset_key = ? AND layer_key = ?", [String(datasetKey), String(layerKey)]) { statement in
                found.insert(SurfaceLevel(z: Int(sqlite3_column_int64(statement, 0)), tilePixels: Int(sqlite3_column_int64(statement, 1))))
            }
            common = common.map { $0.intersection(found) } ?? found
        }
        levels = (common ?? []).sorted { $0.z < $1.z }
    }

    deinit {
        sqlite3_close(db)
    }

    /// `tile` の森林・陰影・水域を `palette` の色で合成した画像(一辺は `level` の tilePixels、1 画素は 0xAARRGGBB)。
    /// どのレイヤーにもそのタイルが無ければ nil。
    func tile(_ tile: SurfaceTile, level: SurfaceLevel, palette: SurfacePalette) -> [UInt32]? {
        let forest = mask(.forest, tile, level)
        let hillshade = mask(.hillshade, tile, level)
        let water = mask(.water, tile, level)
        if forest == nil && water == nil { return nil }
        return try? SurfaceCompositor.compose(
            tilePixels: level.tilePixels, forest: forest, hillshade: hillshade, water: water, palette: palette, shade: shade
        )
    }

    /// `kind` のレイヤーの `tile` を復号した、1 画素 1 バイトの濃さ。タイルが無い、復号できない、大きさが合わないときは nil。
    private func mask(_ kind: SurfaceLayerKind, _ tile: SurfaceTile, _ level: SurfaceLevel) -> [UInt8]? {
        guard let layerKey = layerKeys[kind] else { return nil }
        var payload: Data?
        query(
            "SELECT payload FROM surface_tiles WHERE dataset_key = ? AND layer_key = ? AND z = ? AND x = ? AND y = ?",
            [String(datasetKey), String(layerKey), String(tile.z), String(tile.x), String(tile.y)]
        ) { statement in
            if let bytes = sqlite3_column_blob(statement, 0) {
                payload = Data(bytes: bytes, count: Int(sqlite3_column_bytes(statement, 0)))
            }
        }
        guard let payload,
              let source = CGImageSourceCreateWithData(payload as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else { return nil }
        let size = level.tilePixels
        guard image.width == size, image.height == size else { return nil }
        // 濃さは灰色の画像で入っている。色を変換しないように端末の RGB のまま描き、緑の成分を読む。
        var pixels = [UInt8](repeating: 0, count: size * size * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            ) else { return false }
            context.interpolationQuality = .none
            context.draw(image, in: CGRect(x: 0, y: 0, width: size, height: size))
            return true
        }
        guard drawn else { return nil }
        return (0..<(size * size)).map { pixels[$0 * 4 + 1] }
    }

    private func text(_ statement: OpaquePointer?, _ column: Int32) -> String? {
        sqlite3_column_text(statement, column).map { String(cString: $0) }
    }

    /// `sql` を実行し、行ごとに `row` を呼ぶ。
    private func query(_ sql: String, _ arguments: [String], row: (OpaquePointer?) -> Void) {
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return }
        // SQLite が文字列を写して持つようにする(呼び出しのあとで文字列が消えてもよいように)。
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (index, argument) in arguments.enumerated() {
            sqlite3_bind_text(statement, Int32(index + 1), argument, -1, transient)
        }
        while sqlite3_step(statement) == SQLITE_ROW { row(statement) }
    }
}
