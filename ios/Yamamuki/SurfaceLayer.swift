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

/// 地表データの SQLite から、方位盤に貼る画像を作る。SQLite は最初に画像を作るときに開く。
/// 作るのは重い(タイルの復号と並べ直し)ので、画面を描くスレッドとは別のところで、1 つずつ順に行う。
private actor SurfaceLayerRenderer {
    private let file: URL
    private let datasetId: String
    private let hillshade: Bool
    private var source: SurfaceTileSource?

    init(file: URL, datasetId: String, hillshade: Bool) {
        self.file = file
        self.datasetId = datasetId
        self.hillshade = hillshade
    }

    func render(_ frame: SurfaceFrame) -> CGImage? {
        if source == nil { source = SurfaceTileSource(file: file, datasetId: datasetId) }
        guard let source, let level = SurfaceRaster.chooseLevel(source.levels, frame: frame) else { return nil }
        let size = SurfaceRaster.size
        guard var pixels = try? SurfaceRaster.render(frame, level: level, palette: .dial, shade: hillshade ? source.shade : nil, size: size, tile: { tile in
            source.tile(tile, level: level, hillshade: hillshade)
        }) else { return nil }
        // CGImage は色に透明度を掛けた形で持つ。半透明なのはデータの無い場所との境目だけ。
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
}

/// 方位盤の背景に貼る地表の画像を持つ。画面の基準の位置と表示範囲に合わせて作り、大きく動かしたり拡縮したりしたときだけ作り直す。
@MainActor
final class SurfaceLayerModel: ObservableObject {
    /// 貼る画像。地表を表示しないあいだと、最初の画像ができるまでは nil。
    @Published private(set) var image: SurfaceLayerImage?
    /// 地表を表示している(設定がオンで、その地表データを取得済み)。距離の帯の色分けを消し、出典を添える。
    @Published private(set) var shown = false

    /// 画像を作るのに要る、今の画面の状態。
    struct Request: Equatable {
        /// 表示する地表データのファイルと、SQLite の中のデータセットの ID、版。表示しないときは nil。
        var file: URL?
        var datasetId: String?
        var version: String?
        var centerLat: Double?
        var centerLon: Double?
        var rangeKm: Double
        /// 陰影も重ねる。
        var hillshade = false
    }

    private var renderer: SurfaceLayerRenderer?
    private var rendererKey: [String?] = []
    private let logger = Logger(subsystem: "io.github.shohei0205.yamamuki", category: "SurfaceLayer")

    /// 拡縮や移動の途中では作らず、落ち着いてから作る。
    private static let renderDelay: UInt64 = 200_000_000

    /// 画面の状態が変わるたびに呼ぶ。作り直す必要があれば、少し待ってから作る。呼び出し元のタスクが取り消されたらやめる。
    func update(_ request: Request) async {
        let key = [request.file?.path, request.datasetId, request.version, request.hillshade ? "hillshade" : nil]
        if key != rendererKey {
            // 地表データを取り直したら、新しいファイルを開き直す。地表を表示しなくなったら、画像も捨てる。
            rendererKey = key
            image = nil
            if let file = request.file, let datasetId = request.datasetId {
                renderer = SurfaceLayerRenderer(file: file, datasetId: datasetId, hillshade: request.hillshade)
            } else {
                renderer = nil
            }
        }
        shown = renderer != nil
        guard let renderer, let lat = request.centerLat, let lon = request.centerLon,
              SurfaceRaster.needsRender(image?.frame, centerLat: lat, centerLon: lon, rangeKm: request.rangeKm)
        else { return }
        try? await Task.sleep(nanoseconds: Self.renderDelay)
        if Task.isCancelled { return }
        let frame = SurfaceFrame(centerLat: lat, centerLon: lon, radiusKm: SurfaceRaster.radiusKm(request.rangeKm))
        guard let rendered = await renderer.render(frame) else {
            logger.warning("地表の画像を作れませんでした")
            return
        }
        // 作っている間に地表データが変わっていたら、古い画像は使わない。
        if key == rendererKey {
            image = SurfaceLayerImage(image: Image(decorative: rendered, scale: 1), frame: frame)
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
            .task(id: request) { await layer.update(request) }
    }

    private var request: SurfaceLayerModel.Request {
        let dataset = model.settings.surfaceDataset
        // 設定で地表を表示にしていて、その地表データを取得済みなら、背景に貼る画像を作る。
        let installed = model.settings.showSurface ? surface.entries.first { $0.dataset == dataset }?.installed : nil
        return SurfaceLayerModel.Request(
            file: installed.map { _ in surface.databaseFile(dataset) },
            datasetId: installed?.datasetId,
            version: installed?.version,
            centerLat: model.location?.latitude,
            centerLon: model.location?.longitude,
            rangeKm: model.rangeKm,
            hillshade: model.settings.showHillshade
        )
    }
}
