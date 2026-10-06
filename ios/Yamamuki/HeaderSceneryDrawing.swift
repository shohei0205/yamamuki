import SwiftUI
import YamamukiCore

/// 空の上端の色。ステータスバーの裏もこの色で塗り、空がつながって見えるようにする。
let headerSkyTop = Color(hex: 0x4E9BD6)
private let headerSkyMiddle = Color(hex: 0x8EC4EA)
private let headerSkyBottom = Color(hex: 0xD9EEF8)
private let cloudWhite = Color.white.opacity(0.85)
private let ridgeFar = Color(hex: 0xA9C6DE)
private let peakBlue = Color(hex: 0x8FB0CC)
private let ridgeMiddle = Color(hex: 0x6F9AA8)
private let ridgeNear = Color(hex: 0x5E8F6A)

/// 方位盤の上部のヘッダーと、その下の方位目盛りの裏に、青空と遠くの山並みを描く。
/// 形は `HeaderScenery` で決め、Android と同じに描く。手前の裾野は `ground` (方位盤の地面の色)に溶かし、目盛りとの境目を作らない。
/// 広告を出すときは、ヘッダーの中央に重ねる。
func drawHeaderScenery(_ ctx: GraphicsContext, width: CGFloat, ground: Color) {
    let height = CGFloat(HeaderScenery.height)

    ctx.fill(
        Path(CGRect(x: 0, y: 0, width: width, height: height)),
        with: .linearGradient(
            Gradient(stops: [
                .init(color: headerSkyTop, location: 0),
                .init(color: headerSkyMiddle, location: HeaderScenery.skyMiddleFraction),
                .init(color: headerSkyBottom, location: 1),
            ]),
            startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: 0, y: height)
        )
    )
    for cloud in HeaderScenery.clouds {
        let cx = CGFloat(cloud.xFraction) * width
        let cy = CGFloat(cloud.y)
        let s = CGFloat(cloud.scale)
        ctx.fill(Path(ellipseIn: CGRect(x: cx - 22 * s, y: cy - 6 * s, width: 44 * s, height: 12 * s)), with: .color(cloudWhite))
        ctx.fill(Path(ellipseIn: CGRect(x: cx, y: cy - 10 * s, width: 24 * s, height: 12 * s)), with: .color(cloudWhite))
    }

    func ridgePath(_ layer: HeaderScenery.Layer) -> Path {
        let ridge = HeaderScenery.ridge(layer, width: Double(width))
        var path = Path()
        path.move(to: CGPoint(x: 0, y: height))
        path.addLine(to: CGPoint(x: 0, y: ridge.startY))
        for segment in ridge.segments {
            path.addQuadCurve(to: CGPoint(x: segment.endX, y: segment.endY),
                              control: CGPoint(x: segment.controlX, y: segment.controlY))
        }
        path.addLine(to: CGPoint(x: ridge.segments.last?.endX ?? Double(width), y: Double(height)))
        path.closeSubpath()
        return path
    }

    ctx.fill(ridgePath(.far), with: .color(ridgeFar))

    // 雪をかぶった独立峰。
    let peakX = CGFloat(HeaderScenery.peakCenterX(width: Double(width)))
    let peakTop = CGFloat(HeaderScenery.peakTopY)
    let foot = CGFloat(HeaderScenery.footY)
    let crest = CGFloat(HeaderScenery.peakCrestHalf)
    let rise = CGFloat(HeaderScenery.crestRise)
    let half = CGFloat(HeaderScenery.peakHalfWidth)
    var peak = Path()
    peak.move(to: CGPoint(x: peakX - half, y: foot))
    peak.addLine(to: CGPoint(x: peakX - crest, y: peakTop))
    peak.addQuadCurve(to: CGPoint(x: peakX + crest, y: peakTop), control: CGPoint(x: peakX, y: peakTop - rise))
    peak.addLine(to: CGPoint(x: peakX + half, y: foot))
    peak.closeSubpath()
    ctx.fill(peak, with: .color(peakBlue))
    let cap = HeaderScenery.snowCap
    var snow = Path()
    snow.move(to: CGPoint(x: peakX + CGFloat(cap[0].x), y: peakTop + CGFloat(cap[0].y)))
    snow.addQuadCurve(to: CGPoint(x: peakX + CGFloat(cap[1].x), y: peakTop + CGFloat(cap[1].y)),
                      control: CGPoint(x: peakX, y: peakTop - rise))
    for point in cap.dropFirst(2) {
        snow.addLine(to: CGPoint(x: peakX + CGFloat(point.x), y: peakTop + CGFloat(point.y)))
    }
    snow.closeSubpath()
    ctx.fill(snow, with: .color(.white))

    ctx.fill(ridgePath(.middle), with: .color(ridgeMiddle))
    ctx.fill(ridgePath(.near), with: .color(ridgeNear))

    // 手前の裾野を方位盤の地面の色に溶かす。
    let fadeTop = CGFloat(HeaderScenery.fadeTop)
    ctx.fill(
        Path(CGRect(x: 0, y: fadeTop, width: width, height: height - fadeTop)),
        with: .linearGradient(
            Gradient(stops: [
                .init(color: ground.opacity(0), location: 0),
                .init(color: ground.opacity(HeaderScenery.fadeMiddleAlpha), location: HeaderScenery.fadeMiddleFraction),
                .init(color: ground, location: 1),
            ]),
            startPoint: CGPoint(x: 0, y: fadeTop), endPoint: CGPoint(x: 0, y: height)
        )
    )
}
