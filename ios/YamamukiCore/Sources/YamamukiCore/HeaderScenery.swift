import Foundation

/// 2 次ベジェ曲線の 1 区間。始点は前の区間の終点。座標は pt。
public struct QuadSegment: Equatable {
    public let controlX: Double
    public let controlY: Double
    public let endX: Double
    public let endY: Double
}

/// 山並みの稜線。`startY` は左端(x = 0)の高さ。`segments` を左から順につなぐと、画面の右端まで届く。
public struct Ridge: Equatable {
    public let startY: Double
    public let segments: [QuadSegment]
}

/// 空に浮かべる雲。`xFraction` は画面の幅に対する中心の位置、`y` は中心の高さ(pt)、`scale` は大きさの倍率。
public struct Cloud: Equatable {
    public let xFraction: Double
    public let y: Double
    public let scale: Double
}

/// 方位盤の上部のヘッダーに描く、青空と遠くの山並みの形。両 OS で同じ形に描くため、点の位置をここで決める。
/// 座標の原点はヘッダーの左上で、単位は pt。風景はヘッダーの下の方位目盛りの裏まで続き、手前の裾野を方位盤の地面の色に溶かす。
public enum HeaderScenery {
    /// 風景を描く高さ。ヘッダーと方位目盛りを合わせた高さで、下端は方位盤の地面の色に溶ける。
    public static let height = DialGeometry.headerHeight + DialGeometry.tapeHeight

    /// 山並みの模様の幅。画面がこれより広いときは、同じ模様を横に繰り返す。
    public static let period = 360.0

    /// 山並みの裾の高さ。ここから下を地面の色に溶かし始める。
    public static let footY = 66.0

    /// 地面の色に溶かし始める高さ。裾より少し上から始め、稜線の下端をぼかす。
    public static let fadeTop = footY - 6

    /// 空のグラデーションの中ほどの色に切り替わる位置(風景の高さに対する割合)。
    public static let skyMiddleFraction = 0.55

    /// 地面の色に溶かす途中で、ほぼ地面の色になる位置(溶かす範囲に対する割合)と、そこでの濃さ。
    public static let fadeMiddleFraction = 0.45
    public static let fadeMiddleAlpha = 0.85

    /// 奥から手前へ、3 重の山並み。
    public enum Layer: CaseIterable {
        /// いちばん奥の淡い青の山並み。
        case far
        /// 中ほどの青緑の山並み。
        case middle
        /// 手前の緑の稜線。
        case near

        var baseY: Double {
            switch self {
            case .far: return HeaderScenery.footY - 30
            case .middle: return HeaderScenery.footY - 14
            case .near: return HeaderScenery.footY - 2
            }
        }

        var points: [(Double, Double)] {
            switch self {
            case .far:
                return [(0, 22), (40, 10), (78, 26), (120, 8), (160, 20), (205, 2),
                        (240, 18), (285, 6), (322, 24), (360, 22)]
            case .middle:
                return [(0, 4), (30, 14), (64, 2), (110, 16), (150, 6), (190, 18),
                        (230, 10), (272, 20), (312, 6), (360, 4)]
            case .near:
                return [(0, 8), (50, 0), (96, 10), (140, 4), (200, 12), (250, 2),
                        (300, 10), (360, 8)]
            }
        }
    }

    /// 雪をかぶった独立峰。奥の山並みと中ほどの山並みの間に、画面の幅に対して同じ割合の位置に 1 つだけ描く。
    public static let peakXFraction = 0.64
    public static let peakTopY = footY - 46
    public static let peakHalfWidth = 70.0
    /// 山頂の平らな部分の半分の幅。
    public static let peakCrestHalf = 14.0

    public static let clouds = [Cloud(xFraction: 0.36, y: 14, scale: 1), Cloud(xFraction: 0.83, y: 10, scale: 0.8)]

    /// 幅 `width` の画面に描く稜線。模様を `period` ごとに繰り返し、右端を越えるところまで返す。
    /// 山と山の間は、隣り合う 2 点の低いほう(画面では上)より少し高く盛り上げてつなぐ。
    public static func ridge(_ layer: Layer, width: Double) -> Ridge {
        let points = layer.points
        var segments: [QuadSegment] = []
        var shift = 0.0
        while shift < width || segments.isEmpty {
            for i in 1..<points.count {
                let (x, dy) = points[i]
                let (px, pdy) = points[i - 1]
                segments.append(QuadSegment(
                    controlX: shift + (x + px) / 2,
                    controlY: layer.baseY + min(dy, pdy) - 4,
                    endX: shift + x,
                    endY: layer.baseY + dy
                ))
            }
            shift += period
        }
        return Ridge(startY: layer.baseY + points[0].1, segments: segments)
    }

    /// 独立峰の中心の x(pt)。
    public static func peakCenterX(width: Double) -> Double { width * peakXFraction }

    /// 独立峰の頂の雪の輪郭。中心を (0, 山頂の高さ) とした相対位置(pt)。上の 2 点の間は山頂と同じく少し盛り上げる。
    public static let snowCap: [(x: Double, y: Double)] = [
        (-14, 0), (14, 0), (24, 12), (15, 9), (8, 14),
        (0, 8), (-8, 14), (-16, 9), (-24, 12),
    ]

    /// 山頂の平らな部分を盛り上げる量(pt)。山頂の輪郭と雪の輪郭の上辺で使う。
    public static let crestRise = 3.0
}
