// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

import CoreGraphics
import os
import SwiftUI
import YamamukiCore

/// 方位盤の背景に貼る地表の画像と、その範囲。
struct SurfaceLayerImage {
    let image: Image
    let frame: SurfaceFrame
}

/// 方位盤に貼る地表の画像を作ったもの。`complete` が false なら、通信に失敗して取れなかったタイルがあり、その場所は透明。
private struct RenderedSurface {
    let image: CGImage
    let complete: Bool
}

/// 方位盤に貼る地表の画像を作るもの。地表データ(`FileSurfaceRenderer`)と、通信して取る地図(`OnlineSurfaceRenderer`)がある。
private protocol SurfaceRenderer: Actor {
    /// `frame` の画像を作る。作れなければ nil。
    func render(_ frame: SurfaceFrame) async -> RenderedSurface?
}

/// 0xAARRGGBB(透明度を掛けていない色)の画素を、一辺 `size` の CGImage にする。
private func makeImage(_ pixels: [UInt32], size: Int) -> CGImage? {
    var pixels = pixels
    // CGImage は色に透明度を掛けた形で持つ。
    for index in pixels.indices {
        let pixel = pixels[index]
        let alpha = pixel >> 24
        if alpha == 255 { continue }
        let r = (pixel >> 16 & 0xFF) * alpha / 255
        let g = (pixel >> 8 & 0xFF) * alpha / 255
        let b = (pixel & 0xFF) * alpha / 255
        pixels[index] = alpha << 24 | r << 16 | g << 8 | b
    }
    let data = pixels.withUnsafeBufferPointer { Data(buffer: $0) }
    guard let provider = CGDataProvider(data: data as CFData) else { return nil }
    // 0xAARRGGBB を下位のバイトから並べると、青・緑・赤・透明度の順になる。
    let bitmapInfo = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
    return CGImage(
        width: size, height: size, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: size * 4,
        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: bitmapInfo),
        provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
    )
}

/// 地表データの SQLite から、方位盤に貼る画像を作る。SQLite は最初に画像を作るときに開く。
/// 作るのは重い(タイルの復号と並べ直し)ので、画面を描くスレッドとは別のところで、1 つずつ順に行う。
private actor FileSurfaceRenderer: SurfaceRenderer {
    private let file: URL
    private let datasetId: String
    private let hillshade: Bool
    private var source: SurfaceTileSource?

    init(file: URL, datasetId: String, hillshade: Bool) {
        self.file = file
        self.datasetId = datasetId
        self.hillshade = hillshade
    }

    func render(_ frame: SurfaceFrame) async -> RenderedSurface? {
        if source == nil { source = SurfaceTileSource(file: file, datasetId: datasetId) }
        guard let source, let level = SurfaceRaster.chooseLevel(source.levels, frame: frame) else { return nil }
        let size = SurfaceRaster.size
        guard let pixels = try? SurfaceRaster.render(frame, level: level, palette: .dial, shade: hillshade ? source.shade : nil, size: size, tile: { tile in
            source.tile(tile, level: level, hillshade: hillshade)
        }), let image = makeImage(pixels, size: size) else { return nil }
        return RenderedSurface(image: image, complete: true)
    }
}

/// 通信して取る地図(地理院タイル)から、方位盤に貼る画像を作る。画像の範囲にかかるタイルをまとめて取り、並べ直す。
/// 取ったタイルは `client` がメモリにだけ覚え、ファイルには保存しない。通信できなければ画像を作らない。
private actor OnlineSurfaceRenderer: SurfaceRenderer {
    private let client: OnlineSurfaceClient
    private let surface: OnlineSurface

    init(client: OnlineSurfaceClient, surface: OnlineSurface) {
        self.client = client
        self.surface = surface
    }

    func render(_ frame: SurfaceFrame) async -> RenderedSurface? {
        let size = SurfaceRaster.size
        guard let level = SurfaceRaster.chooseLevel(surface.levels, frame: frame, size: size),
              let fetched = try? await client.fetch(surface, tiles: SurfaceRaster.imageTiles(frame, level: level, size: size))
        else { return nil }
        // 1 枚も取れなかった(圏外など)ときは、前の画像を残す。
        if fetched.tiles.isEmpty && fetched.failed > 0 { return nil }
        guard let pixels = try? SurfaceRaster.renderImage(frame, level: level, size: size, tile: { tile in
            fetched.tiles[tile].flatMap(Self.decode)
        }), let image = makeImage(pixels, size: size) else { return nil }
        return RenderedSurface(image: image, complete: fetched.failed == 0)
    }

    /// タイルの画像(PNG か JPEG)を、透明度を掛けていない 0xAARRGGBB の画素にする。読めない、大きさが違うときは nil。
    private static func decode(_ data: Data) -> [UInt32]? {
        let tilePixels = OnlineSurface.tilePixels
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
              image.width == tilePixels, image.height == tilePixels
        else { return nil }
        var pixels = [UInt32](repeating: 0, count: tilePixels * tilePixels)
        let bitmapInfo = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: tilePixels, height: tilePixels, bitsPerComponent: 8, bytesPerRow: tilePixels * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: bitmapInfo
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: tilePixels, height: tilePixels))
            return true
        }
        guard drawn else { return nil }
        // CGContext は色に透明度を掛けた形で描くので、掛けない形に戻す。
        for index in pixels.indices {
            let pixel = pixels[index]
            let alpha = pixel >> 24
            if alpha == 255 { continue }
            if alpha == 0 {
                pixels[index] = 0
                continue
            }
            let r = min((pixel >> 16 & 0xFF) * 255 / alpha, 255)
            let g = min((pixel >> 8 & 0xFF) * 255 / alpha, 255)
            let b = min((pixel & 0xFF) * 255 / alpha, 255)
            pixels[index] = alpha << 24 | r << 16 | g << 8 | b
        }
        return pixels
    }
}

/// 方位盤の背景に貼る地表の画像を持つ。画面の基準の位置と表示範囲に合わせて作り、大きく動かしたり拡縮したりしたときだけ作り直す。
/// 通信に失敗して作れなかった、または欠けた画像は、しばらくしてから作り直す。
@MainActor
final class SurfaceLayerModel: ObservableObject {
    /// 貼る画像。地表を表示しないあいだと、最初の画像ができるまでは nil。
    @Published private(set) var image: SurfaceLayerImage?
    /// 地表を表示している(設定がオンで、通信して取る地図を選んでいるか、その地表データを取得済み)。出典を添える。
    @Published private(set) var shown = false
    /// 通信して取る地図を表示している。出典を地理院タイルにする。
    @Published private(set) var online = false

    /// 画像を作るのに要る、今の画面の状態。
    struct Request: Equatable {
        /// 表示する地表データのファイルと、SQLite の中のデータセットの ID、版。表示しないときは nil。
        var file: URL?
        var datasetId: String?
        var version: String?
        /// 地表データの代わりに表示する、通信して取る地図。
        var online: OnlineSurface?
        var centerLat: Double?
        var centerLon: Double?
        var rangeKm: Double
        /// 陰影も重ねる。
        var hillshade = false
    }

    private var renderer: (any SurfaceRenderer)?
    private var rendererKey: [String?] = []
    /// 通信に失敗して作れなかった、または欠けた画像になった。しばらくしてから作り直す。
    private var retryNeeded = false
    private var lastAttempt: Date?
    private let logger = Logger(subsystem: "io.github.shohei0205.yamamuki", category: "SurfaceLayer")

    /// 拡縮や移動の途中では作らず、落ち着いてから作る。
    private static let renderDelay = 0.2
    /// 通信に失敗したときに、作り直すまで待つ時間(秒)。
    private static let retryDelay = 10.0

    /// 画面の状態が変わるたびに呼ぶ。作り直す必要があれば、少し待ってから作る。呼び出し元のタスクが取り消されたらやめる。
    /// - Parameter onlineClient: 通信して取る地図のタイルを取るもの。
    func update(_ request: Request, onlineClient: OnlineSurfaceClient) async {
        let key = [request.online?.id, request.file?.path, request.datasetId, request.version, request.hillshade ? "hillshade" : nil]
        if key != rendererKey {
            // 地表の種類や地表データが変わったら、作り直す。地表を表示しなくなったら、画像も捨てる。
            rendererKey = key
            image = nil
            retryNeeded = false
            lastAttempt = nil
            if let online = request.online {
                renderer = OnlineSurfaceRenderer(client: onlineClient, surface: online)
            } else if let file = request.file, let datasetId = request.datasetId {
                renderer = FileSurfaceRenderer(file: file, datasetId: datasetId, hillshade: request.hillshade)
            } else {
                renderer = nil
            }
        }
        shown = renderer != nil
        online = request.online != nil && renderer != nil
        guard let renderer, let lat = request.centerLat, let lon = request.centerLon else { return }
        while !Task.isCancelled {
            guard retryNeeded || SurfaceRaster.needsRender(image?.frame, centerLat: lat, centerLon: lon, rangeKm: request.rangeKm)
            else { return }
            var wait = Self.renderDelay
            if retryNeeded, let lastAttempt { wait = max(wait, Self.retryDelay + lastAttempt.timeIntervalSinceNow) }
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            if Task.isCancelled { return }
            lastAttempt = Date()
            let frame = SurfaceFrame(centerLat: lat, centerLon: lon, radiusKm: SurfaceRaster.radiusKm(request.rangeKm))
            let rendered = await renderer.render(frame)
            // 作っている間に地表の種類が変わっていたら、古い画像は使わない。
            guard key == rendererKey else { return }
            if let rendered {
                image = SurfaceLayerImage(image: Image(decorative: rendered.image, scale: 1), frame: frame)
                retryNeeded = !rendered.complete
            } else {
                logger.warning("地表の画像を作れませんでした")
                retryNeeded = true
            }
        }
    }
}

/// 方位盤の状態(設定・地表データ・表示位置・表示範囲)を見て、地表の画像を作り直させる。画面には何も描かない。
struct SurfaceLayerDriver: View {
    @ObservedObject var model: DialModel
    @ObservedObject var surface: SurfaceDataModel
    let layer: SurfaceLayerModel

    var body: some View {
        Color.clear
            .allowsHitTesting(false)
            .task(id: request) { await layer.update(request, onlineClient: model.onlineSurfaceClient) }
    }

    private var request: SurfaceLayerModel.Request {
        let dataset = model.settings.surfaceDataset
        // 設定で地表を表示にしていて、通信して取る地図を選んでいるか、その地表データを取得済みなら、背景に貼る画像を作る。
        let online = model.settings.showSurface ? model.settings.onlineSurface : nil
        let installed = model.settings.showSurface && online == nil ? surface.entries.first { $0.dataset == dataset }?.installed : nil
        return SurfaceLayerModel.Request(
            file: installed.map { _ in surface.databaseFile(dataset) },
            datasetId: installed?.datasetId,
            version: installed?.version,
            online: online,
            centerLat: model.location?.latitude,
            centerLon: model.location?.longitude,
            rangeKm: model.rangeKm,
            hillshade: model.settings.showHillshade
        )
    }
}
