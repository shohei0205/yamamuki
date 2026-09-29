import SwiftUI
import YamamukiCore

let dialBeige = Color(hex: 0xEFE4B0)
private let ringGray = Color(hex: 0xC3C3C3)
private let peakGreen = Color(hex: 0x22B14C)
private let peakYellow = Color(hex: 0xB5E61D)
private let hillGreen = Color(hex: 0x9BD65A)
private let peakBrown = Color(hex: 0x8C5A2B)
private let peakBrownDark = Color(hex: 0x5E3A17)
private let northRed = Color(hex: 0xED1C24)
private let binocularBody = Color(hex: 0x333333)
private let binocularHinge = Color(hex: 0x777777)
private let lensBlue = Color(hex: 0x5B8DB8)
private let summitRock = Color(hex: 0x5D6D7E)
private let summitRockLight = Color(hex: 0x8A99A8)
private let flagPole = Color(hex: 0x333333)

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// 画面上部の方位目盛りに収める角度の幅。
private let tapeSpanDeg = 60.0

/// 山アイコンの縁取りの太さ。3 種類とも同じ太さにそろえる。
private let outlineWidth: CGFloat = 1.5

/// 方位盤。現在地(画面下部の双眼鏡)から向いている方向を上にとり、山をアイコンと山名で描く。
/// アイコンの色と形は標高の区分([ElevationClass])で変える。
/// [mountains] は表示の優先順(標高の高い順)に並んでいること。重なる山は優先度の低いほうを省く。
/// 描いた山(アイコンか山名)をタップすると [onMountainTap] を呼ぶ。
/// 双眼鏡(現在地)をタップすると [onObserverTap] を呼ぶ。
/// 現在地がほぼ山頂([summit] が非 nil)のときは、双眼鏡の代わりに山頂アイコンと山名を描き、そのタップも [onMountainTap] に渡す。
struct DialCanvasView: View {
    let headingDeg: Double
    let mountains: [NearbyMountain]
    let rangeKm: Double
    /// 現在地がほぼ山頂のとき、その山。
    let summit: NearbyMountain?
    /// 現在地の標高(海抜)。方位の表示の後ろに添える。nil なら出さない。
    let altitudeM: Double?
    /// 一度に表示する山の上限。
    let maxPeaks: Int
    /// 文字の大きさ(標準 = 1.0 に対する倍率)。
    let textScale: Double
    let onMountainTap: (NearbyMountain) -> Void
    let onObserverTap: () -> Void

    @State private var hitTargets = HitTargets()

    var body: some View {
        Canvas { context, size in
            draw(context, size: size)
        }
        .contentShape(Rectangle())
        .onTapLocation { location in
            if hitTargets.hitsObserver(location, slop: 8) {
                onObserverTap()
            } else if let m = hitTargets.find(location, slop: 8) {
                onMountainTap(m)
            }
        }
    }

    private func draw(_ ctx: GraphicsContext, size: CGSize) {
        let styles = TextStyles(scale: textScale)
        let tapeHeight: CGFloat = 44
        let chartTop = tapeHeight + 32
        // 双眼鏡が右下の「© OpenStreetMap contributors」と重ならない高さ。
        let observer = CGPoint(x: size.width / 2, y: size.height - 52)
        let pxPerKm = (observer.y - chartTop) / CGFloat(rangeKm)
        if pxPerKm > 0 {
            drawRings(ctx, size: size, observer: observer, pxPerKm: pxPerKm, chartTop: chartTop, styles: styles)
            hitTargets.peaks = drawPeaks(ctx, size: size, observer: observer, pxPerKm: pxPerKm, chartTop: chartTop, styles: styles)
        } else {
            hitTargets.peaks = []
        }
        if let summit {
            hitTargets.summit = drawSummit(ctx, center: observer, summit: summit, styles: styles)
            hitTargets.observer = nil
        } else {
            hitTargets.observer = drawBinoculars(ctx, center: observer)
            hitTargets.summit = nil
        }
        drawTape(ctx, size: size, tapeHeight: tapeHeight)
        drawReadout(ctx, size: size, tapeHeight: tapeHeight, styles: styles)
    }

    private func drawRings(_ ctx: GraphicsContext, size: CGSize, observer: CGPoint, pxPerKm: CGFloat, chartTop: CGFloat, styles: TextStyles) {
        let step = CGFloat(DialGeometry.ringStepKm(rangeKm))
        let farthest = hypot(size.width / 2, observer.y)
        var i = 1
        while step * CGFloat(i) * pxPerKm <= farthest {
            let km = step * CGFloat(i)
            let radius = km * pxPerKm
            let circle = Path(ellipseIn: CGRect(x: observer.x - radius, y: observer.y - radius, width: radius * 2, height: radius * 2))
            ctx.stroke(circle, with: .color(ringGray), lineWidth: 3)
            let label = measuredText(ctx, DialGeometry.ringLabel(Double(km)), size: styles.ringLabel, color: ringGray)
            let y = observer.y - radius - label.size.height - 2
            if y >= chartTop {
                ctx.draw(label.text, at: CGPoint(x: observer.x - label.size.width / 2, y: y), anchor: .topLeading)
            }
            i += 1
        }
    }

    private func drawPeaks(_ ctx: GraphicsContext, size: CGSize, observer: CGPoint, pxPerKm: CGFloat, chartTop: CGFloat, styles: TextStyles) -> [PlacedPeak] {
        let gap: CGFloat = 2
        // 優先順に置いていき、先に置いた山と重なるものは省く(core の declutter と同じ考え方)。
        // 上限に達したら残りの山名は測らない。
        var placed: [PlacedPeak] = []
        for m in mountains {
            if placed.count >= maxPeaks { break }
            let o = DialGeometry.project(distanceKm: m.distanceKm, bearingDeg: m.bearingDeg, headingDeg: headingDeg)
            let p = CGPoint(x: observer.x + CGFloat(o.x) * pxPerKm, y: observer.y - CGFloat(o.y) * pxPerKm)
            guard p.x >= 0, p.x <= size.width, p.y - PeakIcon.maxHeight >= chartTop, p.y < observer.y else { continue }
            let icon = PeakIcon.of(m.mountain.elevationClass)
            let label = measuredText(ctx, m.mountain.name, size: styles.label, color: .black)
            let labelHalf = label.size.width / 2
            let box = ScreenBox(
                left: Double(min(p.x - icon.halfWidth, p.x - labelHalf)),
                top: Double(p.y - icon.height),
                right: Double(max(p.x + icon.halfWidth, p.x + labelHalf)),
                bottom: Double(p.y + gap + label.size.height)
            )
            guard !placed.contains(where: { $0.box.intersects(box) }) else { continue }
            placed.append(PlacedPeak(mountain: m, position: p, box: box))
            drawPeakIcon(ctx, at: p, icon: icon)
            ctx.draw(label.text, at: CGPoint(x: p.x - labelHalf, y: p.y + gap), anchor: .topLeading)
        }
        return placed
    }

    private func drawPeakIcon(_ ctx: GraphicsContext, at p: CGPoint, icon: PeakIcon) {
        let halfWidth = icon.halfWidth
        let height = icon.height
        switch icon {
        case .hill:
            // 底辺を直径とする半楕円。縁取りで背景のベージュから浮かせる。
            var unit = Path()
            unit.addArc(center: .zero, radius: 1, startAngle: .degrees(180), endAngle: .degrees(360), clockwise: false)
            unit.closeSubpath()
            let hill = unit.applying(CGAffineTransform(translationX: p.x, y: p.y).scaledBy(x: halfWidth, y: height))
            ctx.fill(hill, with: .color(hillGreen))
            ctx.stroke(hill, with: .color(peakGreen), lineWidth: outlineWidth)
        case .peak:
            let tri = triangle(bottomCenter: p, halfWidth: halfWidth, height: height)
            ctx.fill(tri, with: .color(peakYellow))
            ctx.stroke(tri, with: .color(peakGreen), lineWidth: outlineWidth)
        case .alpine:
            let tri = triangle(bottomCenter: p, halfWidth: halfWidth, height: height)
            ctx.fill(tri, with: .color(peakBrown))
            // 頂上から高さの 35% を白く塗って雪を表す。相似な三角形なので幅も同じ比率。
            let snow: CGFloat = 0.35
            ctx.fill(
                triangle(bottomCenter: CGPoint(x: p.x, y: p.y - height * (1 - snow)), halfWidth: halfWidth * snow, height: height * snow),
                with: .color(.white)
            )
            ctx.stroke(tri, with: .color(peakBrownDark), lineWidth: outlineWidth)
        }
    }

    /// 現在地がほぼ山頂のときに双眼鏡の代わりに描く、赤い旗を立てた灰色の岩山と山名。
    /// 方位盤の山アイコンと見分けられる形にし、双眼鏡と同じ視野の扇形を前方へ描く。
    /// 山名は右側に白い下地付きで置く(下は画面の端、上は方位盤のため)。タップの当たり判定用の範囲を返す。
    private func drawSummit(_ ctx: GraphicsContext, center: CGPoint, summit: NearbyMountain, styles: TextStyles) -> PlacedPeak {
        func at(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: center.x + x, y: center.y + y) }

        let rock = polygon([at(-16, 10), at(-8, -2), at(-4, 1), at(2, -8), at(16, 10)])
        // 日の当たる面。右の尾根を明るくして立体に見せる。
        let lit = polygon([at(2, -8), at(16, 10), at(7, 10)])
        let flag = polygon([at(2, -24), at(13, -20.5), at(2, -17)])
        let pole = line(at(2, -24), at(2, -8))

        drawViewCone(ctx, apex: at(0, -6))

        // 白い縁取り → 本体の順に描く。
        let halo = StrokeStyle(lineWidth: 4, lineJoin: .round)
        ctx.stroke(rock, with: .color(.white), style: halo)
        ctx.stroke(flag, with: .color(.white), style: halo)
        ctx.stroke(pole, with: .color(.white), style: StrokeStyle(lineWidth: 5, lineCap: .round))
        ctx.fill(rock, with: .color(summitRock))
        ctx.fill(lit, with: .color(summitRockLight))
        ctx.stroke(pole, with: .color(flagPole), style: StrokeStyle(lineWidth: 2, lineCap: .round))
        ctx.fill(flag, with: .color(northRed))

        let label = measuredText(ctx, summit.mountain.name, size: styles.label, color: .black)
        let padX: CGFloat = 5
        let padY: CGFloat = 2
        let labelLeft = center.x + 22
        let labelTop = center.y - 4 - label.size.height / 2
        let background = CGRect(x: labelLeft - padX, y: labelTop - padY, width: label.size.width + padX * 2, height: label.size.height + padY * 2)
        ctx.fill(Path(roundedRect: background, cornerRadius: 4), with: .color(.white.opacity(0.85)))
        ctx.draw(label.text, at: CGPoint(x: labelLeft, y: labelTop), anchor: .topLeading)

        let box = ScreenBox(
            left: Double(center.x - 18),
            top: Double(min(center.y - 26, labelTop - padY)),
            right: Double(labelLeft + label.size.width + padX),
            bottom: Double(max(center.y + 12, labelTop + label.size.height + padY))
        )
        return PlacedPeak(mountain: summit, position: center, box: box)
    }

    /// 向いている方位(画面の上)を示す視野。[apex] から前方へ扇形に広がり、遠くほど薄くなる。
    /// 双眼鏡と山頂アイコンで共通に使う。
    private func drawViewCone(_ ctx: GraphicsContext, apex: CGPoint) {
        let reach: CGFloat = 70
        let halfAngle = 22.0
        var cone = Path()
        cone.move(to: apex)
        cone.addArc(center: apex, radius: reach, startAngle: .degrees(-90 - halfAngle), endAngle: .degrees(-90 + halfAngle), clockwise: false)
        cone.closeSubpath()
        ctx.fill(
            cone,
            with: .radialGradient(
                Gradient(colors: [lensBlue.opacity(0.35), lensBlue.opacity(0)]),
                center: apex,
                startRadius: 0,
                endRadius: reach
            )
        )
    }

    /// 現在地を表す双眼鏡。対物レンズを上(向いている方位)に向け、前方へ広がる視野を薄く描いて
    /// 「前を覗いている」ように見せる。同心円や山と重なっても埋もれないよう、白い縁取りを付ける。
    /// タップの当たり判定用に、白い縁取りまで含めた範囲を返す。
    private func drawBinoculars(_ ctx: GraphicsContext, center: CGPoint) -> CGRect {
        /// 中心からのずれで角丸の矩形を描く。[grow] だけ四方に広げる。
        func part(x: CGFloat, top: CGFloat, width: CGFloat, bottom: CGFloat, corner: CGFloat, color: Color, grow: CGFloat) {
            let rect = CGRect(
                x: center.x + x - width / 2 - grow,
                y: center.y + top - grow,
                width: width + grow * 2,
                height: bottom - top + grow * 2
            )
            ctx.fill(Path(roundedRect: rect, cornerRadius: corner + grow), with: .color(color))
        }

        func body(_ color: Color, grow: CGFloat) {
            for side: CGFloat in [-1, 1] {
                let x = side * 10
                part(x: x, top: -13, width: 15, bottom: 3, corner: 5, color: color, grow: grow) // 対物部
                part(x: x, top: 1, width: 9, bottom: 12, corner: 3, color: color, grow: grow) // 接眼部
            }
            part(x: 0, top: -1, width: 8, bottom: 6, corner: 2, color: color, grow: grow) // ブリッジ
        }

        drawViewCone(ctx, apex: CGPoint(x: center.x, y: center.y - 10))
        body(.white, grow: 2)
        body(binocularBody, grow: 0)
        ctx.fill(Path(ellipseIn: CGRect(x: center.x - 3, y: center.y + 2.5 - 3, width: 6, height: 6)), with: .color(binocularHinge))
        for side: CGFloat in [-1, 1] {
            // 前を向いたレンズ面を斜め後ろから見た楕円。
            let lens = CGPoint(x: center.x + side * 10, y: center.y - 10.5)
            ctx.fill(Path(ellipseIn: CGRect(x: lens.x - 5.5, y: lens.y - 2.5, width: 11, height: 5)), with: .color(lensBlue))
            ctx.fill(Path(ellipseIn: CGRect(x: lens.x - 3.5, y: lens.y - 1.5, width: 3, height: 1.4)), with: .color(.white.opacity(0.8)))
        }
        return CGRect(x: center.x - 20, y: center.y - 15, width: 40, height: 29)
    }

    /// 画面上部の方位目盛り。向いている方位が中央に来る。上端は高さが決まっているので文字の倍率を掛けない。
    private func drawTape(_ ctx: GraphicsContext, size: CGSize, tapeHeight: CGFloat) {
        let center = size.width / 2
        for tick in DialGeometry.tapeTicks(headingDeg: headingDeg, spanDeg: tapeSpanDeg) {
            let x = center + CGFloat(tick.offsetDeg / tapeSpanDeg) * size.width
            if let cardinal = DialGeometry.cardinalLabel(tick.angleDeg) {
                let label = measuredText(
                    ctx,
                    cardinal,
                    size: cardinal.count == 1 ? 22 : 15,
                    color: cardinal == "N" ? northRed : .black
                )
                ctx.draw(label.text, at: CGPoint(x: x - label.size.width / 2, y: (tapeHeight - label.size.height) / 2), anchor: .topLeading)
            } else {
                let length = tick.angleDeg % 10 == 0 ? tapeHeight * 0.75 : tapeHeight * 0.45
                ctx.stroke(line(CGPoint(x: x, y: 0), CGPoint(x: x, y: length)), with: .color(.black), lineWidth: 3)
            }
        }
    }

    /// 目盛りの下に、中央を指す赤い印と「北東 45°　標高 312m」の表示(標高は分かるときだけ)。
    private func drawReadout(_ ctx: GraphicsContext, size: CGSize, tapeHeight: CGFloat, styles: TextStyles) {
        let center = size.width / 2
        let caret: CGFloat = 6
        ctx.fill(
            polygon([
                CGPoint(x: center, y: tapeHeight),
                CGPoint(x: center + caret, y: tapeHeight + caret),
                CGPoint(x: center - caret, y: tapeHeight + caret),
            ]),
            with: .color(northRed)
        )
        let deg = Int(headingDeg.rounded()) % 360
        let altitude = altitudeM.map { "　標高 \(groupedInteger(Int($0.rounded())))m" } ?? ""
        let label = measuredText(ctx, "\(Heading.directionName(headingDeg)) \(deg)°\(altitude)", size: styles.readout, color: .black)
        ctx.draw(label.text, at: CGPoint(x: center - label.size.width / 2, y: tapeHeight + caret + 2), anchor: .topLeading)
    }
}

/// 方位盤の文字の大きさ。設定の文字サイズ([scale])を山名・距離の目盛り・方位の表示に掛ける。
private struct TextStyles {
    let label: CGFloat
    let ringLabel: CGFloat
    let readout: CGFloat

    init(scale: Double) {
        label = CGFloat(13 * scale)
        ringLabel = CGFloat(12 * scale)
        readout = CGFloat(15 * scale)
    }
}

private struct MeasuredText {
    let text: GraphicsContext.ResolvedText
    let size: CGSize
}

private func measuredText(_ ctx: GraphicsContext, _ string: String, size: CGFloat, color: Color) -> MeasuredText {
    let text = ctx.resolve(Text(string).font(.system(size: size, weight: .bold)).foregroundColor(color))
    return MeasuredText(text: text, size: text.measure(in: CGSize(width: CGFloat.greatestFiniteMagnitude, height: .greatestFiniteMagnitude)))
}

private struct PlacedPeak {
    let mountain: NearbyMountain
    let position: CGPoint
    /// アイコンと山名を合わせた範囲。重なりの判定とタップの当たり判定に使う。
    let box: ScreenBox
}

/// 直近に描いた山。描画のたびに差し替え、タップ位置から山を引く。
private final class HitTargets {
    var peaks: [PlacedPeak] = []
    /// 現在地の山頂アイコンと山名。山と重なっても優先する。
    var summit: PlacedPeak?
    /// 双眼鏡の範囲。山頂アイコンを描いているときは nil。山と重なっても優先する。
    var observer: CGRect?

    /// [tap] が双眼鏡に当たったか。枠を [slop] だけ広げて判定する。
    func hitsObserver(_ tap: CGPoint, slop: CGFloat) -> Bool {
        observer?.insetBy(dx: -slop, dy: -slop).contains(tap) ?? false
    }

    /// [tap] を含む山のうち、アイコンが最も近いもの。枠を [slop] だけ広げて判定する。
    func find(_ tap: CGPoint, slop: CGFloat) -> NearbyMountain? {
        let x = Double(tap.x)
        let y = Double(tap.y)
        let s = Double(slop)
        func hit(_ p: PlacedPeak) -> Bool {
            x >= p.box.left - s && x <= p.box.right + s && y >= p.box.top - s && y <= p.box.bottom + s
        }
        if let summit, hit(summit) { return summit.mountain }
        return peaks.filter(hit).min { a, b in
            hypot(a.position.x - tap.x, a.position.y - tap.y) < hypot(b.position.x - tap.x, b.position.y - tap.y)
        }?.mountain
    }
}

/// 標高の区分ごとの山アイコンの大きさ(pt)。底辺の中点が山の位置に来る。
private enum PeakIcon {
    /// 1000m 未満(標高不明を含む): 黄緑の低い丘。
    case hill
    /// 1000m 以上 2000m 未満: 黄色の ▲ を緑で縁取る。
    case peak
    /// 2000m 以上: 茶色の高く尖った ▲ に白い雪の冠。濃い茶色で縁取る。
    case alpine

    var halfWidth: CGFloat {
        switch self {
        case .hill: return 10
        case .peak: return 11
        case .alpine: return 12
        }
    }

    var height: CGFloat {
        switch self {
        case .hill: return 11
        case .peak: return 18
        case .alpine: return 25
        }
    }

    static let maxHeight: CGFloat = 25

    static func of(_ cls: ElevationClass) -> PeakIcon {
        switch cls {
        case .low: return .hill
        case .middle: return .peak
        case .high: return .alpine
        }
    }
}

/// 底辺の中点を [bottomCenter] とする二等辺三角形。
private func triangle(bottomCenter: CGPoint, halfWidth: CGFloat, height: CGFloat) -> Path {
    polygon([
        CGPoint(x: bottomCenter.x, y: bottomCenter.y - height),
        CGPoint(x: bottomCenter.x + halfWidth, y: bottomCenter.y),
        CGPoint(x: bottomCenter.x - halfWidth, y: bottomCenter.y),
    ])
}

/// [points] を順に結んで閉じた多角形。
private func polygon(_ points: [CGPoint]) -> Path {
    var path = Path()
    path.addLines(points)
    path.closeSubpath()
    return path
}

private func line(_ from: CGPoint, _ to: CGPoint) -> Path {
    var path = Path()
    path.move(to: from)
    path.addLine(to: to)
    return path
}

private extension View {
    /// タップした位置を渡す。
    func onTapLocation(_ action: @escaping (CGPoint) -> Void) -> some View {
        gesture(SpatialTapGesture().onEnded { action($0.location) })
    }
}
