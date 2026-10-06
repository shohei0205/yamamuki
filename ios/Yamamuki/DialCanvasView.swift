import SwiftUI
import YamamukiCore

/// 方位盤の地面のクリーム色。
let dialGround = Color(hex: 0xFFF4D8)
/// 距離の円の間の帯。現在地に近い帯から順に塗り、ここにない遠くの帯は地面の色のままにする。
private let groundBands = [Color(hex: 0xFFE7B0), Color(hex: 0xFFF0C9), Color(hex: 0xFDEBC4)]
private let ringLine = Color(hex: 0xE8C98F)
private let shadowColor = Color.black.opacity(0.2)
private let northRed = Color(hex: 0xED1C24)
private let binocularBody = Color(hex: 0x333333)
private let binocularHinge = Color(hex: 0x777777)
private let lensBlue = Color(hex: 0x5B8DB8)
private let summitRock = Color(hex: 0x5D6D7E)
private let summitRockLight = Color(hex: 0x8A99A8)
private let flagPole = Color(hex: 0x333333)
private let fanShade = Color(hex: 0xB08D57)
private let fanEdge = Color(hex: 0xF2A65A)
let tapeInk = Color(hex: 0x2E3A40)
let tapeSubtle = Color(hex: 0x6B7178)
/// 方位目盛りの 10° ごとと 5° ごとの線の長さ。
private let tapeMajorTick: CGFloat = 14
private let tapeMinorTick: CGFloat = 8

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
private let tapeSpanDeg = DialGeometry.tapeSpanDeg

/// 描画原点(双眼鏡)の画面下端からの高さ。
private let originBottom = CGFloat(DialGeometry.originBottom)

/// 山名の札の、文字の周りの余白。
private let labelPadX: CGFloat = 7
private let labelPadY: CGFloat = 2

/// 方位盤。現在地(画面下部の双眼鏡)から向いている方向を上にとり、山をアイコンと山名で描く。
/// アイコンの色と形は標高の区分([ElevationClass])で変える。
/// 表示する山は、現在地から見上げる角度(仰角)の大きい順に選び、すぐそばの山どうしは標高の高いほうを残す([PeakLayout])。
/// 画面に描くときは前回描いた山を先に置くので、向きを変えても、描いている山は実際に重なるまで消えない。
/// 重なって山名を省いた山はアイコンだけを描き、代表の山の山名の下に「ほか 3 山」と添える。
/// 描いた山(アイコンか山名)をタップすると [onMountainTap] を呼ぶ。代表の山なら、まとめた山を含む一覧を [onGroupTap] に渡す。
/// 双眼鏡(現在地)をタップすると [onObserverTap] を呼ぶ。
/// 現在地がほぼ山頂([summit] が非 nil)のときは、双眼鏡の代わりに山頂アイコンと山名を描き、そのタップも [onMountainTap] に渡す。
struct DialCanvasView: View, Animatable {
    let headingDeg: Double
    let mountains: [NearbyMountain]
    let rangeKm: Double
    /// 現在地がほぼ山頂のとき、その山。
    let summit: NearbyMountain?
    /// 現在地の標高(海抜)。方位の表示の後ろに添え、山の仰角の計算にも使う。nil なら出さない(仰角は 0m とみなす)。
    let altitudeM: Double?
    /// 一度に表示する山の上限。
    let maxPeaks: Int
    /// 文字の大きさ(標準 = 1.0 に対する倍率)。
    let textScale: Double
    let observerLocation: GeoPoint?
    let viewportLocation: GeoPoint?
    let compassHeading: Double
    let headingUp: Bool
    /// 上部の方位目盛りの引っ込み具合。0 で表示(ヘディングアップ)、1 でヘッダーの下端へ隠れる(手動位置モード)。
    var tapeHidden: Double
    /// 現在地から画面上部へ広がる視野の扇の濃さ(0〜1)。双眼鏡の短い視野は残りの (1 - 濃さ) で描く。
    var viewFanAlpha: Double
    /// 画面下端の余白(ホームインジケーターなど)の高さ。視野の扇の外側の暗さだけを、ここまで描き足す。
    var bottomBleed: CGFloat = 0
    let onPan: (Double, Double, Double) -> Void
    let onTransform: (Double, Double, PlanOffset, PlanOffset, Double) -> Void
    let onMountainTap: (NearbyMountain) -> Void
    /// 重なる山をまとめた代表の山をタップしたとき。代表の山を先頭に、まとめた山を優先順に並べて渡す。
    let onGroupTap: ([NearbyMountain]) -> Void
    let onObserverTap: () -> Void

    @State private var hitTargets = HitTargets()
    @State private var peakSelection = PeakSelection()

    /// モードを切り替えるときに、目盛りの出し入れと視野の扇の濃さを少しずつ変えて描き直す。
    var animatableData: AnimatablePair<Double, Double> {
        get { AnimatablePair(tapeHidden, viewFanAlpha) }
        set {
            tapeHidden = newValue.first
            viewFanAlpha = newValue.second
        }
    }

    var body: some View {
        // 距離の帯と視野の扇だけを画面下端の余白まで描くので、Canvas を余白の分だけ下へ広げ、それ以外は元の範囲で描く。
        Canvas { context, size in
            draw(context, size: CGSize(width: size.width, height: size.height - bottomBleed))
        }
        .padding(.bottom, -bottomBleed)
        .overlay {
            DialTouchSurface(onPan: onPan, onTransform: onTransform) { point in
                if hitTargets.hitsObserver(point, slop: 8) {
                    onObserverTap()
                } else if let peak = hitTargets.find(point, slop: 8) {
                    if peak.members.isEmpty {
                        onMountainTap(peak.mountain)
                    } else {
                        onGroupTap([peak.mountain] + peak.members)
                    }
                }
            }
        }
    }

    private func draw(_ ctx: GraphicsContext, size: CGSize) {
        let styles = TextStyles(scale: textScale)
        let headerHeight = CGFloat(DialGeometry.headerHeight)
        let tapeHeight = CGFloat(DialGeometry.tapeHeight)
        let chartTop = CGFloat(DialGeometry.chartTop)
        let origin = CGPoint(x: size.width / 2, y: size.height - originBottom)
        let pxPerKm = (origin.y - chartTop) / CGFloat(rangeKm)
        var observer = origin
        if let here = observerLocation, let viewport = viewportLocation {
            let offset = PanGeometry.observerOffset(MapCenter(here.latitude, here.longitude),
                viewport: MapCenter(viewport.latitude, viewport.longitude), heading: headingDeg)
            observer.x += CGFloat(offset.x) * pxPerKm
            observer.y -= CGFloat(offset.y) * pxPerKm
        }
        // 距離の帯と視野の扇は、画面下端の余白まで描く。
        var groundContext = ctx
        let groundTop = headerHeight + tapeHeight
        groundContext.clip(to: Path(CGRect(x: 0, y: groundTop, width: size.width, height: max(0, size.height + bottomBleed - groundTop))))
        if pxPerKm > 0 {
            drawGroundBands(groundContext, observer: observer, pxPerKm: pxPerKm)
        }
        if viewFanAlpha > 0 {
            // 扇は画面の真上に固定する。手動位置モードへ切り替えて消える間も、端末の向きにつられて回らない。
            drawViewFan(groundContext, size: size, apex: observer)
        }
        var ctx = ctx
        ctx.clip(to: Path(CGRect(origin: .zero, size: size)))
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
            hitTargets.observerCenter = observer
            hitTargets.observerRotation = Heading.delta(headingDeg, compassHeading)
            hitTargets.observer = drawBinoculars(rotatedObserver(ctx, center: observer), center: observer)
            hitTargets.summit = nil
        }
        // 上部の青空と山並みは、地図の上に重ねる。手動位置モードで地図を動かしても、双眼鏡などがヘッダーに重ならない。
        drawHeaderScenery(ctx, width: size.width, ground: dialGround)
        if tapeHidden < 1 {
            // 目盛りはヘッダーの下に置き、手動位置モードではヘッダーの下端で切って、ヘッダーに重ねずに消す。
            // 方位の表示の文字は大きくできるので、目盛りの帯より長めに動かして隠しきる。
            var tapeContext = ctx
            tapeContext.clip(to: Path(CGRect(x: 0, y: headerHeight, width: size.width, height: max(0, size.height - headerHeight))))
            tapeContext.translateBy(x: 0, y: headerHeight - CGFloat(tapeHidden) * (chartTop - headerHeight) * 1.5)
            drawTape(tapeContext, size: size, tapeHeight: tapeHeight)
            drawReadout(tapeContext, size: size, tapeHeight: tapeHeight, styles: styles)
        }
    }

    private func rotatedObserver(_ ctx: GraphicsContext, center: CGPoint) -> GraphicsContext {
        var rotated = ctx
        rotated.translateBy(x: center.x, y: center.y)
        rotated.rotate(by: .degrees(Heading.delta(headingDeg, compassHeading)))
        rotated.translateBy(x: -center.x, y: -center.y)
        return rotated
    }

    /// 距離の円の間を、現在地に近いほど濃い淡い色で塗り分ける(groundBands)。遠い帯から順に重ねる。
    private func drawGroundBands(_ ctx: GraphicsContext, observer: CGPoint, pxPerKm: CGFloat) {
        let step = CGFloat(DialGeometry.ringStepKm(rangeKm))
        for i in groundBands.indices.reversed() {
            let radius = step * CGFloat(i + 1) * pxPerKm
            ctx.fill(Path(ellipseIn: CGRect(x: observer.x - radius, y: observer.y - radius, width: radius * 2, height: radius * 2)),
                with: .color(groundBands[i]))
        }
    }

    private func drawRings(_ ctx: GraphicsContext, size: CGSize, observer: CGPoint, pxPerKm: CGFloat, chartTop: CGFloat, styles: TextStyles) {
        let step = CGFloat(DialGeometry.ringStepKm(rangeKm))
        let farthest = hypot(max(abs(observer.x), abs(size.width - observer.x)),
            max(abs(chartTop - observer.y), abs(size.height - observer.y)))
        let nearest = hypot(max(0, max(-observer.x, observer.x - size.width)),
            max(0, max(chartTop - observer.y, observer.y - size.height)))
        var ctx = ctx
        ctx.clip(to: Path(CGRect(x: 0, y: chartTop, width: size.width, height: max(0, size.height - originBottom - chartTop))))
        var i = max(1, Int(nearest / (step * pxPerKm)))
        var rings: [(CGFloat, MeasuredText)] = []
        while step * CGFloat(i) * pxPerKm <= farthest {
            let km = step * CGFloat(i)
            let radius = km * pxPerKm
            let circle = Path(ellipseIn: CGRect(x: observer.x - radius, y: observer.y - radius, width: radius * 2, height: radius * 2))
            ctx.stroke(circle, with: .color(ringLine), lineWidth: 1.5)
            let label = measuredText(ctx, DialGeometry.ringLabel(Double(km)), size: styles.ringLabel, color: tapeSubtle)
            rings.append((radius, label))
            i += 1
        }
        func placements(_ angle: Double) -> [(MeasuredText, CGRect)] {
            var labels: [(MeasuredText, CGRect)] = []
            for (radius, label) in rings {
            if let anchor = RingLabelGeometry.place(cx: Double(observer.x), cy: Double(observer.y), radius: Double(radius),
                left: 0, top: Double(chartTop), right: Double(size.width), bottom: Double(size.height - originBottom),
                width: Double(label.size.width + 12), height: Double(label.size.height + 6), angle: angle) {
                let box = CGRect(x: CGFloat(anchor.x) - label.size.width / 2 - 6,
                    y: CGFloat(anchor.y) - label.size.height / 2 - 3, width: label.size.width + 12, height: label.size.height + 6)
                if !labels.contains(where: { $0.1.intersects(box) }) { labels.append((label, box)) }
            }
            }
            return labels
        }
        let angle = headingUp ? -Double.pi / 2 : RingLabelGeometry.direction(cx: Double(observer.x), cy: Double(observer.y),
            left: 0, top: Double(chartTop), right: Double(size.width), bottom: Double(size.height - originBottom),
            previousAngle: RingLabelGeometry.rotatedAngle(hitTargets.ringLabelAngle,
                previousHeading: hitTargets.ringLabelHeading, heading: headingDeg), visibleCount: { placements($0).count })
        hitTargets.ringLabelAngle = angle
        hitTargets.ringLabelHeading = headingDeg
        for (label, box) in placements(angle) {
            // 距離の数字は白い札に載せ、円や帯と重なっても読めるようにする。
            ctx.fill(Path(roundedRect: box, cornerRadius: box.height / 2), with: .color(.white.opacity(0.9)))
            ctx.draw(label.text, at: CGPoint(x: box.minX + 6, y: box.minY + 3), anchor: .topLeading)
        }
    }

    private func drawPeaks(_ ctx: GraphicsContext, size: CGSize, observer: CGPoint, pxPerKm: CGFloat, chartTop: CGFloat, styles: TextStyles) -> [PlacedPeak] {
        let gap: CGFloat = 2
        let chartBottom = size.height - originBottom

        // 山の位置が画面に入りうる、現在地からの最大の距離(画面の最も遠い角まで)。
        let corners = [CGPoint(x: 0, y: chartTop), CGPoint(x: size.width, y: chartTop), CGPoint(x: 0, y: chartBottom), CGPoint(x: size.width, y: chartBottom)]
        let farthestCorner = corners.map { hypot($0.x - observer.x, $0.y - observer.y) }.max() ?? 0
        let reach = (farthestCorner / reachStep).rounded(.up) * reachStep

        let key = PeakSelection.Key(mountains: mountains, pxPerKm: pxPerKm, observerAltitudeM: altitudeM,
            textScale: textScale, maxPeaks: maxPeaks, reach: reach, width: size.width, chartHeight: chartBottom - chartTop)
        let selection = peakSelection
        if selection.key != key {
            let viewArea = Double(size.width * (chartBottom - chartTop))
            selection.selected = PeakLayout.candidates(
                mountains, observerAltitudeM: altitudeM, keptIds: selection.selectedIds,
                reachKm: Double(reach / pxPerKm),
                limit: PeakLayout.aroundLimit(maxPeaks: maxPeaks, reach: Double(reach), viewArea: viewArea)
            ).map { m in
                let icon = PeakIcon.of(m.mountain.elevationClass)
                let label = measuredText(ctx, m.mountain.name, size: styles.label, color: tapeInk)
                let labelHalf = label.size.width / 2 + labelPadX
                let box = ScreenBox(
                    left: Double(min(-icon.halfWidth, -labelHalf)),
                    top: Double(-icon.height),
                    right: Double(max(icon.halfWidth, labelHalf)),
                    bottom: Double(gap + label.size.height + labelPadY * 2)
                )
                return SelectedPeak(mountain: m, box: box)
            }
            selection.selectedIds = Set(selection.selected.map { $0.mountain.mountain.osmId })
            selection.key = key
        }

        let visible: [PlacedPeak] = selection.selected.compactMap { s -> PlacedPeak? in
            let o = DialGeometry.project(distanceKm: s.mountain.distanceKm, bearingDeg: s.mountain.bearingDeg, headingDeg: headingDeg)
            let p = CGPoint(x: observer.x + CGFloat(o.x) * pxPerKm, y: observer.y - CGFloat(o.y) * pxPerKm)
            guard p.x >= 0, p.x <= size.width, p.y - PeakIcon.maxHeight >= chartTop, p.y < chartBottom else { return nil }
            return PlacedPeak(mountain: s.mountain, position: p, box: ScreenBox(
                left: Double(p.x) + s.box.left, top: Double(p.y) + s.box.top,
                right: Double(p.x) + s.box.right, bottom: Double(p.y) + s.box.bottom))
        }
        let groups = PeakLayout.placeVisible(
            visible,
            limit: maxPeaks,
            box: { $0.box },
            drawnBefore: { selection.drawnIds.contains($0.mountain.mountain.osmId) },
            neighbors: { PeakLayout.areNeighbors($0.mountain, $1.mountain) },
            elevationM: { $0.mountain.mountain.elevationM },
            margin: newPeakMargin
        )
        selection.drawnIds = Set(groups.map { $0.peak.mountain.mountain.osmId })

        // 代表の山の山名と「ほか 3 山」の場所を先に決め、まとめた山のアイコンはそこを避けて描く。
        let placedBoxes = groups.map(\.peak.box)
        let labels = groups.map { measuredText(ctx, $0.peak.mountain.mountain.name, size: styles.label, color: tapeInk) }
        // 山名の札の範囲。
        let labelBoxes = groups.indices.map { i -> ScreenBox in
            let p = groups[i].peak.position
            let half = Double(labels[i].size.width / 2 + labelPadX)
            return ScreenBox(left: Double(p.x) - half, top: Double(p.y + gap), right: Double(p.x) + half,
                bottom: Double(p.y + gap + labels[i].size.height + labelPadY * 2))
        }
        let othersLabels: [(text: MeasuredText, box: ScreenBox)?] = groups.indices.map { i -> (text: MeasuredText, box: ScreenBox)? in
            let group = groups[i]
            guard !group.members.isEmpty else { return nil }
            // 「ほか 3 山」は山名の下に添える。ほかの山の山名と重なるときは添えない(タップすれば一覧は出る)。
            let peak = group.peak
            let others = measuredText(ctx, othersText(group.members.count), size: styles.others, color: tapeSubtle)
            let top = labelBoxes[i].bottom
            let half = Double(others.size.width / 2)
            let x = Double(peak.position.x)
            let below = ScreenBox(left: min(peak.box.left, x - half), top: top, right: max(peak.box.right, x + half), bottom: top + Double(others.size.height))
            if placedBoxes.contains(where: { $0 != peak.box && $0.intersects(below) }) { return nil }
            return (text: others, box: below)
        }
        let textBoxes = labelBoxes + othersLabels.compactMap { $0?.box }

        // まとめた山のアイコンは薄く描き、代表の山のアイコンと山名を上に重ねる。標高が不明な山と、
        // どれかの山名や「ほか 3 山」にかかる山は描かない(一覧には残る)。描いたアイコンを押すと一覧を開く。
        var faded = ctx
        faded.opacity = memberIconAlpha
        var memberTargets: [PlacedPeak] = []
        for group in groups {
            let members = group.members.map(\.mountain)
            for member in group.members where member.mountain.mountain.elevationM != nil {
                let icon = PeakIcon.of(member.mountain.mountain.elevationClass)
                let p = member.position
                let iconBox = ScreenBox(left: Double(p.x - icon.halfWidth), top: Double(p.y - icon.height),
                    right: Double(p.x + icon.halfWidth), bottom: Double(p.y))
                if textBoxes.contains(where: { $0.intersects(iconBox) }) { continue }
                drawPeakIcon(faded, at: p, icon: icon, shadow: false)
                memberTargets.append(PlacedPeak(mountain: group.peak.mountain, position: p, box: iconBox, members: members))
            }
        }
        let reps = groups.indices.map { i -> PlacedPeak in
            let group = groups[i]
            let peak = group.peak
            let p = peak.position
            let label = labels[i]
            let icon = PeakIcon.of(peak.mountain.mountain.elevationClass)
            drawPeakIcon(ctx, at: p, icon: icon)
            drawNameChip(ctx, label: label, at: p, box: labelBoxes[i], edge: icon.color)
            var box = peak.box
            if let others = othersLabels[i] {
                ctx.draw(others.text.text, at: CGPoint(x: p.x - others.text.size.width / 2, y: CGFloat(others.box.top)), anchor: .topLeading)
                box = ScreenBox(left: min(box.left, others.box.left), top: box.top,
                    right: max(box.right, others.box.right), bottom: others.box.bottom)
            }
            return PlacedPeak(mountain: peak.mountain, position: p, box: box, members: group.members.map(\.mountain))
        }
        return reps + memberTargets
    }

    /// 山アイコンを描く。白い縁と影で地面の色から浮かせ、右の斜面を少し暗くして立体に見せる。
    /// 形の点は、底辺の中点を原点に、横は半幅、縦は高さを 1 とした割合で決める(Android と同じ)。
    private func drawPeakIcon(_ ctx: GraphicsContext, at p: CGPoint, icon: PeakIcon, shadow: Bool = true) {
        let w = icon.halfWidth
        let h = icon.height
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: p.x + x * w, y: p.y - y * h) }
        var body = Path()
        body.move(to: pt(-1, 0))
        body.addQuadCurve(to: pt(-0.154, 0.93), control: pt(-0.615, 0.419))
        body.addQuadCurve(to: pt(0.154, 0.93), control: pt(0, 1.07))
        body.addQuadCurve(to: pt(1, 0), control: pt(0.615, 0.419))
        body.closeSubpath()
        if shadow {
            ctx.fill(body.offsetBy(dx: 0, dy: 1.5), with: .color(shadowColor))
        }
        ctx.stroke(body, with: .color(.white), style: StrokeStyle(lineWidth: 2.5, lineJoin: .round))
        ctx.fill(body, with: .color(icon.color))
        var slope = Path()
        slope.move(to: pt(0.077, 0.884))
        slope.addQuadCurve(to: pt(0.769, 0.093), control: pt(0.462, 0.465))
        slope.addLine(to: pt(0.231, 0.093))
        slope.closeSubpath()
        ctx.fill(slope, with: .color(icon.shade.opacity(0.35)))
        if icon.snow {
            var snow = Path()
            snow.move(to: pt(-0.423, 0.605))
            snow.addQuadCurve(to: pt(0, 1), control: pt(-0.154, 0.977))
            snow.addQuadCurve(to: pt(0.423, 0.605), control: pt(0.154, 0.977))
            snow.addLine(to: pt(0.192, 0.512))
            snow.addLine(to: pt(0, 0.628))
            snow.addLine(to: pt(-0.192, 0.512))
            snow.closeSubpath()
            ctx.fill(snow, with: .color(.white))
        }
    }

    /// 山名を白い札に載せ、札の縁をアイコンと同じ標高の色([edge])にする。どの山が高いかがひと目で分かるようにする。
    private func drawNameChip(_ ctx: GraphicsContext, label: MeasuredText, at p: CGPoint, box: ScreenBox, edge: Color) {
        let rect = CGRect(x: box.left, y: box.top, width: box.right - box.left, height: box.bottom - box.top)
        ctx.fill(Path(roundedRect: rect.offsetBy(dx: 0, dy: 1), cornerRadius: rect.height / 2), with: .color(shadowColor))
        ctx.fill(Path(roundedRect: rect, cornerRadius: rect.height / 2), with: .color(.white))
        let border: CGFloat = 2
        let inner = rect.insetBy(dx: border / 2, dy: border / 2)
        ctx.stroke(Path(roundedRect: inner, cornerRadius: inner.height / 2), with: .color(edge), lineWidth: border)
        ctx.draw(label.text, at: CGPoint(x: p.x - label.size.width / 2, y: rect.minY + labelPadY), anchor: .topLeading)
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

        drawViewCone(rotatedObserver(ctx, center: center), apex: at(0, -6))

        // 白い縁取り → 本体の順に描く。
        let halo = StrokeStyle(lineWidth: 4, lineJoin: .round)
        ctx.stroke(rock, with: .color(.white), style: halo)
        ctx.stroke(flag, with: .color(.white), style: halo)
        ctx.stroke(pole, with: .color(.white), style: StrokeStyle(lineWidth: 5, lineCap: .round))
        ctx.fill(rock, with: .color(summitRock))
        ctx.fill(lit, with: .color(summitRockLight))
        ctx.stroke(pole, with: .color(flagPole), style: StrokeStyle(lineWidth: 2, lineCap: .round))
        ctx.fill(flag, with: .color(northRed))

        let label = measuredText(ctx, summit.mountain.name, size: styles.label, color: tapeInk)
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
        let alpha = 1 - viewFanAlpha
        guard alpha > 0 else { return }
        let reach: CGFloat = 70
        let halfAngle = 22.0
        var cone = Path()
        cone.move(to: apex)
        cone.addArc(center: apex, radius: reach, startAngle: .degrees(-90 - halfAngle), endAngle: .degrees(-90 + halfAngle), clockwise: false)
        cone.closeSubpath()
        ctx.fill(
            cone,
            with: .radialGradient(
                Gradient(colors: [lensBlue.opacity(0.35 * alpha), lensBlue.opacity(0)]),
                center: apex,
                startRadius: 0,
                endRadius: reach
            )
        )
    }

    /// ヘディングアップで、上部の方位目盛りと同じ幅(tapeSpanDeg)の視野。[apex] から画面の外まで扇を広げ、
    /// 縁を橙色の線にして、扇の外側をうっすら暗くする。山や同心円より下に描き、山名を隠さない。
    private func drawViewFan(_ ctx: GraphicsContext, size: CGSize, apex: CGPoint) {
        let reach = hypot(size.width, size.height) * 2
        let half = tapeSpanDeg / 2 * .pi / 180
        let left = CGPoint(x: apex.x - reach * CGFloat(sin(half)), y: apex.y - reach * CGFloat(cos(half)))
        let right = CGPoint(x: apex.x + reach * CGFloat(sin(half)), y: apex.y - reach * CGFloat(cos(half)))
        var outside = Path(CGRect(x: apex.x - reach, y: apex.y - reach, width: reach * 2, height: reach * 2))
        outside.addPath(polygon([apex, left, right]))
        ctx.fill(outside, with: .color(fanShade.opacity(0.12 * viewFanAlpha)), style: FillStyle(eoFill: true))
        var edge = Path()
        edge.move(to: left)
        edge.addLine(to: apex)
        edge.addLine(to: right)
        ctx.stroke(edge, with: .color(fanEdge.opacity(viewFanAlpha)), style: StrokeStyle(lineWidth: 2, lineJoin: .round))
    }

    /// 現在地を表す双眼鏡。対物レンズを上(向いている方位)に向け、前方へ広がる視野を薄く描いて
    /// 「前を覗いている」ように見せる。同心円や山と重なっても埋もれないよう、白い丸の上に置く。
    /// タップの当たり判定用に、白い丸まで含めた範囲を返す。
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
        // 白い丸の上に置き、帯や円の上でも現在地が目立つようにする。
        let disc = CGRect(x: center.x - 18, y: center.y - 1 - 18, width: 36, height: 36)
        ctx.fill(Path(ellipseIn: disc.offsetBy(dx: 0, dy: 1.5)), with: .color(shadowColor))
        ctx.fill(Path(ellipseIn: disc), with: .color(.white))
        body(.white, grow: 2)
        body(binocularBody, grow: 0)
        ctx.fill(Path(ellipseIn: CGRect(x: center.x - 3, y: center.y + 2.5 - 3, width: 6, height: 6)), with: .color(binocularHinge))
        for side: CGFloat in [-1, 1] {
            // 前を向いたレンズ面を斜め後ろから見た楕円。
            let lens = CGPoint(x: center.x + side * 10, y: center.y - 10.5)
            ctx.fill(Path(ellipseIn: CGRect(x: lens.x - 5.5, y: lens.y - 2.5, width: 11, height: 5)), with: .color(lensBlue))
            ctx.fill(Path(ellipseIn: CGRect(x: lens.x - 3.5, y: lens.y - 1.5, width: 3, height: 1.4)), with: .color(.white.opacity(0.8)))
        }
        return CGRect(x: center.x - 20, y: center.y - 19, width: 40, height: 36)
    }

    /// 画面上部の方位目盛り。向いている方位が中央に来る。上端は高さが決まっているので文字の倍率を掛けない。
    private func drawTape(_ ctx: GraphicsContext, size: CGSize, tapeHeight: CGFloat) {
        let center = size.width / 2
        let half = tapeSpanDeg / 2
        let baseline: CGFloat = 1.5
        // 帯は中央ほど明るく、左右の端で背景に溶かす。帯の両端は視野の扇の縁と同じ方位なので、下端の線も扇の縁と同じ橙色にする。
        // 上端も山並みに溶かし、ヘッダーとの境目に筋を作らない。横のぼかしに縦のぼかしを重ねるため、別の層に描いて上側を削る。
        let band = Path(CGRect(x: 0, y: 0, width: size.width, height: tapeHeight))
        ctx.drawLayer { layer in
            layer.fill(
                band,
                with: .linearGradient(
                    Gradient(colors: [.white.opacity(0), .white.opacity(0.55), .white.opacity(0)]),
                    startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: size.width, y: 0)
                )
            )
            layer.blendMode = .destinationIn
            layer.fill(
                band,
                with: .linearGradient(
                    Gradient(stops: [
                        .init(color: .black.opacity(0), location: 0),
                        .init(color: .black, location: HeaderScenery.tapeBandFadeFraction),
                    ]),
                    startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: 0, y: tapeHeight)
                )
            )
        }
        ctx.fill(
            Path(CGRect(x: 0, y: tapeHeight - baseline, width: size.width, height: baseline)),
            with: .linearGradient(
                Gradient(colors: [fanEdge.opacity(0), fanEdge, fanEdge.opacity(0)]),
                startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: size.width, y: 0)
            )
        )
        let labelBottom = tapeHeight - baseline - tapeMajorTick
        for tick in DialGeometry.tapeTicks(headingDeg: headingDeg, spanDeg: tapeSpanDeg) {
            let x = center + CGFloat(tick.offsetDeg / tapeSpanDeg) * size.width
            // 線と数字は端へ行くほど少し薄くして、中央の方位に目が行くようにする。東西南北の文字は薄くしない。
            let edge = min(1, abs(tick.offsetDeg) / half)
            let opacity = 1 - 0.4 * edge * edge
            let major = tick.angleDeg % 10 == 0
            let length = major ? tapeMajorTick : tapeMinorTick
            ctx.stroke(
                line(CGPoint(x: x, y: tapeHeight - baseline), CGPoint(x: x, y: tapeHeight - baseline - length)),
                with: .color(tapeInk.opacity(opacity)),
                style: StrokeStyle(lineWidth: major ? 2.5 : 1.5, lineCap: .round)
            )
            let label: MeasuredText
            if let cardinal = DialGeometry.cardinalLabel(tick.angleDeg) {
                label = measuredText(ctx, cardinal, size: cardinal.count == 1 ? 20 : 14, color: cardinal == "N" ? northRed : tapeInk)
            } else if tick.angleDeg % 30 == 0 {
                label = measuredText(ctx, String(tick.angleDeg), size: 11, color: tapeSubtle.opacity(opacity))
            } else {
                continue
            }
            ctx.draw(label.text, at: CGPoint(x: x - label.size.width / 2, y: (labelBottom - label.size.height) / 2 + 1), anchor: .topLeading)
        }
    }

    /// 目盛りの中央を指す赤い印と、その下の淡い白の札に「北東 45°　標高 312m」(標高は分かるときだけ)。
    private func drawReadout(_ ctx: GraphicsContext, size: CGSize, tapeHeight: CGFloat, styles: TextStyles) {
        let center = size.width / 2
        let caret: CGFloat = 5
        ctx.fill(
            polygon([
                CGPoint(x: center, y: tapeHeight - 2 * caret),
                CGPoint(x: center + caret, y: tapeHeight),
                CGPoint(x: center - caret, y: tapeHeight),
            ]),
            with: .color(northRed)
        )
        let parts = readoutParts(headingDeg: headingDeg, altitudeM: altitudeM)
        let font = Font.system(size: styles.readout, weight: .bold)
        let text = ctx.resolve(Text(parts.direction).font(font).foregroundColor(tapeInk)
            + Text(parts.altitude).font(font).foregroundColor(tapeSubtle))
        let textSize = text.measure(in: CGSize(width: CGFloat.greatestFiniteMagnitude, height: .greatestFiniteMagnitude))
        let padX: CGFloat = 12
        let padY: CGFloat = 3
        let pill = CGRect(x: center - textSize.width / 2 - padX, y: tapeHeight + 4,
                          width: textSize.width + padX * 2, height: textSize.height + padY * 2)
        let radius = pill.height / 2
        // 札は目盛りの帯と同じくらいの淡い白にして、帯より目立たせない。同心円と重なっても文字が読める程度の濃さは残す。
        ctx.fill(Path(roundedRect: pill, cornerRadius: radius), with: .color(.white.opacity(0.5)))
        ctx.draw(text, at: CGPoint(x: pill.minX + padX, y: pill.minY + padY), anchor: .topLeading)
    }
}

/// 方位(「北東 45°」)と標高(「　標高 312m」、分からなければ空)に分けたもの。
func readoutParts(headingDeg: Double, altitudeM: Double?) -> (direction: String, altitude: String) {
    let deg = Int(headingDeg.rounded()) % 360
    let altitude = altitudeM.map { Strings.format("dial_altitude", groupedInteger(Int($0.rounded()))) } ?? ""
    return ("\(directionName(headingDeg)) \(deg)°", altitude)
}

/// 方位盤の文字の大きさ。設定の文字サイズ([scale])を山名・距離の目盛り・方位の表示に掛ける。
private struct TextStyles {
    let label: CGFloat
    /// 代表の山の山名の下に添える「ほか 3 山」。
    let others: CGFloat
    let ringLabel: CGFloat
    let readout: CGFloat

    init(scale: Double) {
        label = CGFloat(13 * scale)
        others = CGFloat(11 * scale)
        ringLabel = CGFloat(11 * scale)
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

/// 選んだ山の 1 件。範囲は山の位置を原点にしたアイコンと山名の範囲。
private struct SelectedPeak {
    let mountain: NearbyMountain
    let box: ScreenBox
}

/// 選び直すかどうかを決める、現在地から画面の角までの距離の刻み。地図を少し動かしただけでは選び直さない。
private let reachStep: CGFloat = 64

/// 新しく画面に出す山に求める、ほかの山との余白(pt)。境目で出たり消えたりしないようにする。
private let newPeakMargin = 4.0

/// 代表の山にまとめた山のアイコンの濃さ。代表の山と見分けられるように薄くする。
private let memberIconAlpha = 0.45

/// 方位盤に出す山の候補。向きによらずに周り全体から選び、山の一覧・表示範囲などが変わったときだけ選び直す。
/// 前回選んだ山と前回描いた山を覚えておき、境目にある山が出たり消えたりしないようにする。
private final class PeakSelection {
    struct Key: Equatable {
        let mountains: [NearbyMountain]
        let pxPerKm: CGFloat
        let observerAltitudeM: Double?
        let textScale: Double
        let maxPeaks: Int
        let reach: CGFloat
        let width: CGFloat
        let chartHeight: CGFloat
    }

    var key: Key?
    var selected: [SelectedPeak] = []
    var selectedIds: Set<Int64> = []
    var drawnIds: Set<Int64> = []
}

private struct PlacedPeak {
    let mountain: NearbyMountain
    let position: CGPoint
    /// アイコンと山名を合わせた範囲。重なりの判定とタップの当たり判定に使う。
    let box: ScreenBox
    /// 重なるので山名を省き、この山にまとめた山(優先順)。
    var members: [NearbyMountain] = []
}

/// 直近に描いた山。描画のたびに差し替え、タップ位置から山を引く。
private final class HitTargets {
    var ringLabelAngle: Double?
    var ringLabelHeading: Double?
    var peaks: [PlacedPeak] = []
    /// 現在地の山頂アイコンと山名。山と重なっても優先する。
    var summit: PlacedPeak?
    /// 双眼鏡の範囲。山頂アイコンを描いているときは nil。山と重なっても優先する。
    var observer: CGRect?
    var observerCenter = CGPoint.zero
    var observerRotation = 0.0

    /// [tap] が双眼鏡に当たったか。枠を [slop] だけ広げて判定する。
    func hitsObserver(_ tap: CGPoint, slop: CGFloat) -> Bool {
        let angle = CGFloat(-observerRotation * .pi / 180)
        let dx = tap.x - observerCenter.x
        let dy = tap.y - observerCenter.y
        let localTap = CGPoint(
            x: observerCenter.x + dx * cos(angle) - dy * sin(angle),
            y: observerCenter.y + dx * sin(angle) + dy * cos(angle)
        )
        return observer?.insetBy(dx: -slop, dy: -slop).contains(localTap) ?? false
    }

    /// [tap] を含む山のうち、アイコンが最も近いもの。枠を [slop] だけ広げて判定する。
    func find(_ tap: CGPoint, slop: CGFloat) -> PlacedPeak? {
        let x = Double(tap.x)
        let y = Double(tap.y)
        let s = Double(slop)
        func hit(_ p: PlacedPeak) -> Bool {
            x >= p.box.left - s && x <= p.box.right + s && y >= p.box.top - s && y <= p.box.bottom + s
        }
        if let summit, hit(summit) { return summit }
        return peaks.filter(hit).min { a, b in
            hypot(a.position.x - tap.x, a.position.y - tap.y) < hypot(b.position.x - tap.x, b.position.y - tap.y)
        }
    }
}

/// 標高の区分ごとの山アイコン。形は同じ丸みのある山で、色と大きさを変え、2000m 以上には頂に雪を載せる。
/// 色だけに頼らず、高さと雪の有無でも区別できるようにする。底辺の中点が山の位置に来る。
private enum PeakIcon {
    /// 1000m 未満(標高不明を含む): 緑の低い山。
    case low
    /// 1000m 以上 2000m 未満: 橙の山。
    case middle
    /// 2000m 以上: 紫の高い山に雪。
    case high

    var halfWidth: CGFloat {
        switch self {
        case .low: return 11
        case .middle: return 13
        case .high: return 15
        }
    }

    var height: CGFloat {
        switch self {
        case .low: return 13
        case .middle: return 19
        case .high: return 25
        }
    }

    var color: Color {
        switch self {
        case .low: return Color(hex: 0x3DBB5C)
        case .middle: return Color(hex: 0xF39A2B)
        case .high: return Color(hex: 0x8E6CD8)
        }
    }

    /// 右の斜面の陰の色。
    var shade: Color {
        switch self {
        case .low: return Color(hex: 0x23853B)
        case .middle: return Color(hex: 0xC46A0C)
        case .high: return Color(hex: 0x5B3FA8)
        }
    }

    var snow: Bool { self == .high }

    static let maxHeight: CGFloat = 25

    static func of(_ cls: ElevationClass) -> PeakIcon {
        switch cls {
        case .low: return .low
        case .middle: return .middle
        case .high: return .high
        }
    }
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
    /// タップした位置を渡す。iOS 15 には位置の分かるタップがないので、ほとんど動かさずに離したドラッグをタップとみなす。
    @ViewBuilder
    func onTapLocation(_ action: @escaping (CGPoint) -> Void) -> some View {
        if #available(iOS 16, *) {
            gesture(SpatialTapGesture().onEnded { action($0.location) })
        } else {
            gesture(DragGesture(minimumDistance: 0).onEnded { value in
                // ピンチやスクロールのつもりで指を動かしたときは山を開かない。
                if hypot(value.translation.width, value.translation.height) < 10 {
                    action(value.location)
                }
            })
        }
    }
}
