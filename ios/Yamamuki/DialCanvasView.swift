import QuartzCore
import SwiftUI
import YamamukiCore

/// 方位盤の地面のクリーム色。
let dialGround = Color(hex: 0xFFF4D8)
/// 距離の円の間の帯。現在地に近い帯から順に塗り、ここにない遠くの帯は地面の色のままにする。
private let groundBands = [Color(hex: 0xFFE7B0), Color(hex: 0xFFEDC2), Color(hex: 0xFFF1CF)]
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
    /// 1 画面に出す山名の上限。山名を出さない山はアイコンだけで描く。
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
    /// 山名やアイコンの濃さを変えている途中か。途中の間は毎フレーム描き直す。
    @State private var animating = false

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
        TimelineView(.animation(minimumInterval: nil, paused: !animating)) { timeline in
            Canvas { context, size in
                // 時刻を読むことで、濃さを変えている間は TimelineView の刻みごとに描き直される。
                peakSelection.frameDate = timeline.date
                draw(context, size: CGSize(width: size.width, height: size.height - bottomBleed))
            } symbols: {
                ForEach(PeakIconSymbol.all, id: \.id) { $0 }
            }
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

    /// 方位盤に山を描く。表示の優先度は、現在地から見上げる角度(仰角、[displayScore])に、モードごとに次の値を上乗せして決める。
    ///
    /// ヘディングアップ(端末を向けた方向が上):
    /// - 正面に近い山ほど優先する。正面で +1°、視野の扇の端(左右 30°)で 0([PeakLayout.forwardBonus])。
    /// - 表示範囲を広げるほど高い山を優先する。表示範囲 20km から 60km にかけて、標高 1000m あたり 0° から 2° まで増やす
    ///   ([PeakLayout.heightBonus])。遠くまで表示しているときは、近くの低い山より遠くの高い山の名前を知りたいことが多いため。
    /// - 視野の扇の中の山は、山名を出さない山もアイコンだけで描く。扇の端の 8° では、外へ行くほど薄くする。
    ///
    /// 手動位置モード(地図を指で動かす):
    /// - 仰角だけで決める(向きと表示範囲による上乗せはしない)。
    /// - 画面の中央の円(直径は画面の幅)の中の山は、山名を出さない山もアイコンだけで描く。円の縁では、外へ行くほど薄くする。
    ///
    /// どちらのモードでも:
    /// - 前回描いた山は少し優先する([PeakLayout.keptBonusDeg])。
    /// - 画面に入る山を優先度の高い順に置き、山名を [maxPeaks] 件まで出す。重なる山は山名を省き、代表の山の「ほか 3 山」にまとめる。
    ///   すぐそばの山どうし([PeakLayout.neighborKm] 以内)は、優先度によらず標高の高い山を代表にする。
    /// - 山名を出さない山のアイコンは、優先度が低いほど薄くする。山名や「ほか 3 山」にかかるアイコンは一番薄くする。
    /// - 山名とアイコンの濃さは時間をかけて変え、向きを変えたときに急に出たり消えたりしないようにする。
    private func drawPeaks(_ ctx: GraphicsContext, size: CGSize, observer: CGPoint, pxPerKm: CGFloat, chartTop: CGFloat, styles: TextStyles) -> [PlacedPeak] {
        let gap: CGFloat = 2
        let chartBottom = size.height - originBottom

        // 山は、画面より一回り大きい、画面の周りの円から集める。地図を少し動かしたり向きを少し変えたりしただけでは集め直さない。
        let halfDiagonal = hypot(size.width, chartBottom - chartTop) / 2
        let reach = halfDiagonal + reachStep
        // 画面の中央の位置(現在地からの距離と方角)。画面の中央を軸に回しても変わらない。
        let center = CGPoint(x: size.width / 2 - observer.x, y: (chartTop + chartBottom) / 2 - observer.y)
        let centerKm = Double(hypot(center.x, center.y) / pxPerKm)
        let centerBearingDeg = Heading.normalize(headingDeg + atan2(Double(center.x), Double(-center.y)) * 180 / .pi)

        // ヘディングアップでは、正面に近い山を優先する(向けた先の山が、横の山に押し出されにくくする)。
        let forwardHeading: Double? = headingUp ? headingDeg : nil
        // ヘディングアップでは、表示範囲を広げるほど高い山を優先する(遠くの高い山の名前を知りたいことが多いため)。
        let headingUpRangeKm: Double? = headingUp ? Double((chartBottom - chartTop) / pxPerKm) : nil
        func score(_ m: NearbyMountain) -> Double {
            m.displayScore(observerAltitudeM: altitudeM)
                + (headingUpRangeKm.map { PeakLayout.heightBonus(elevationM: m.mountain.elevationM, rangeKm: $0) } ?? 0)
        }

        let key = PeakSelection.Key(mountains: mountains, pxPerKm: pxPerKm, observerAltitudeM: altitudeM,
            textScale: textScale, maxPeaks: maxPeaks, width: size.width, chartHeight: chartBottom - chartTop, headingUp: headingUp)
        let selection = peakSelection
        // 集めたあとは、向きを変えたり地図を動かしたりして、画面の角が円からはみ出すまで集め直さない。
        let outOfCircle = PeakLayout.planeDistanceKm(centerKm, centerBearingDeg, selection.centerKm, selection.centerBearingDeg)
            + Double(halfDiagonal / pxPerKm) > selection.radiusKm
        if selection.key != key || outOfCircle {
            selection.centerKm = centerKm
            selection.centerBearingDeg = centerBearingDeg
            selection.radiusKm = Double(reach / pxPerKm)
            selection.around = mountains.filter {
                PeakLayout.planeDistanceKm($0.distanceKm, $0.bearingDeg, centerKm, centerBearingDeg) <= selection.radiusKm
            }
            if selection.labelTextScale != textScale {
                selection.labels.removeAll()
                selection.labelTextScale = textScale
            }
            selection.key = key
        }

        // 画面に入る山すべてを、優先順に並べて山名の候補にする。山名の数は [maxPeaks] までにする。
        // 上端では、描いている山(アイコンだけの山を含む)は山の位置が上端を越えるまで残し、新しく出す山はアイコン全体が入ってから出す。
        // 正面の少し先の山は、向きをわずかに変えるだけで上端を出入りするため(正面に向けたときが最も上に来る)。
        var positions: [Int64: CGPoint] = [:]
        var onScreen: [NearbyMountain] = []
        for m in selection.around {
            let o = DialGeometry.project(distanceKm: m.distanceKm, bearingDeg: m.bearingDeg, headingDeg: headingDeg)
            let p = CGPoint(x: observer.x + CGFloat(o.x) * pxPerKm, y: observer.y - CGFloat(o.y) * pxPerKm)
            let top = selection.shownIds.contains(m.mountain.osmId) ? chartTop : chartTop + PeakIcon.maxHeight
            guard p.x >= 0, p.x <= size.width, p.y >= top, p.y < chartBottom else { continue }
            positions[m.mountain.osmId] = p
            onScreen.append(m)
        }
        let visible: [PlacedPeak] = PeakLayout.priorityOrder(onScreen, observerAltitudeM: altitudeM, keptIds: selection.drawnIds,
            headingDeg: forwardHeading, rangeKm: headingUpRangeKm).map { m in
            let id = m.mountain.osmId
            let p = positions[id] ?? .zero
            // 山名の大きさは山ごとに一度だけ測る(画面に入る山が多いと、毎回測ると重い)。
            let measured: (text: MeasuredText, box: ScreenBox)
            if let cached = selection.labels[id] {
                measured = cached
            } else {
                let icon = PeakIcon.of(m.mountain.elevationClass)
                let label = measuredText(ctx, m.mountain.name, size: styles.label, color: tapeInk)
                let labelHalf = label.size.width / 2 + labelPadX
                measured = (label, ScreenBox(
                    left: Double(min(-icon.halfWidth, -labelHalf)),
                    top: Double(-icon.height),
                    right: Double(max(icon.halfWidth, labelHalf)),
                    bottom: Double(gap + label.size.height + labelPadY * 2)
                ))
                selection.labels[id] = measured
            }
            let rel = measured.box
            return PlacedPeak(mountain: m, position: p, box: ScreenBox(
                left: Double(p.x) + rel.left, top: Double(p.y) + rel.top,
                right: Double(p.x) + rel.right, bottom: Double(p.y) + rel.bottom), label: measured.text)
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
        selection.shownIds = selection.drawnIds.union(groups.flatMap { $0.members.map(\.mountain.mountain.osmId) })

        // 山名は、出すときも外すときも [labelFadeSeconds] かけて濃さを変える。外した山名は、薄くなりきるまでその場に描く。
        let now = CACurrentMediaTime()
        let dt = (!selection.fading || selection.lastTime == 0) ? 1.0 / 60 : min(max(now - selection.lastTime, 0), 0.1)
        selection.lastTime = now
        let fadeStep = dt / labelFadeSeconds
        for id in selection.drawnIds { selection.labelAlpha[id] = min(1, (selection.labelAlpha[id] ?? 0) + fadeStep) }
        let visibleById = Dictionary(visible.map { ($0.mountain.mountain.osmId, $0) }, uniquingKeysWith: { a, _ in a })
        var fadingOut: [(peak: PlacedPeak, alpha: Double)] = []
        for (id, value) in selection.labelAlpha where !selection.drawnIds.contains(id) {
            let alpha = value - fadeStep
            if let peak = visibleById[id], alpha > 0 {
                selection.labelAlpha[id] = alpha
                fadingOut.append((peak, alpha))
            } else {
                selection.labelAlpha[id] = nil
            }
        }
        let fadingOutIds = Set(fadingOut.map { $0.peak.mountain.mountain.osmId })

        // 代表の山の山名と「ほか 3 山」の場所を先に決め、アイコンだけの山はそこを避けて描く。
        let placedBoxes = groups.map(\.peak.box)
        func labelBox(_ peak: PlacedPeak) -> ScreenBox {
            let p = peak.position
            let size = peak.label?.size ?? .zero
            let half = Double(size.width / 2 + labelPadX)
            return ScreenBox(left: Double(p.x) - half, top: Double(p.y + gap), right: Double(p.x) + half,
                bottom: Double(p.y + gap + size.height + labelPadY * 2))
        }
        let labelBoxes = groups.map { labelBox($0.peak) }
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

        // 山名を出さない山のアイコンを描く。描くのは次の 2 つ。標高が不明な山は描かない(一覧には残る)。
        // - まとめた山(「ほか 3 山」に数えた山)。押すと、まとめた山の一覧を開く。
        // - 視界の正面(手動位置モードでは画面の中央)の山。山の多い方角から少ない方角へ向けたときに、
        //   山が急に現れたように見えないようにする。範囲の縁では、外へ行くほど薄くする。
        // 濃さは優先度のスコアが低いほど薄くし、山名や「ほか 3 山」にかかる山は一番薄くして、押しても反応しない(山名を押しやすくする)。
        // 濃さは [iconFadeSeconds] かけて変え、まとめた山でなくなった山や範囲を出た山も、急に消さずにだんだん薄くする。
        let focusCenter = CGPoint(x: size.width / 2, y: (chartTop + chartBottom) / 2)
        let focusRadius = size.width * focusRadiusRatio
        var owners: [Int64: PeakGroup<PlacedPeak>] = [:]
        for group in groups { for member in group.members { owners[member.mountain.mountain.osmId] = group } }
        let iconStep = dt / iconFadeSeconds
        var iconTargets: [PlacedPeak] = []
        var iconFading = false
        for peak in visible {
            let m = peak.mountain
            let id = m.mountain.osmId
            if m.mountain.elevationM == nil { continue }
            if selection.drawnIds.contains(id) || fadingOutIds.contains(id) {
                // 山名を出している間は山名と一緒に描く。山名を外し終えたら、アイコンだけの濃さから続ける。
                selection.iconAlpha[id] = scoreAlpha(score(m))
                continue
            }
            let p = peak.position
            let icon = PeakIcon.of(m.mountain.elevationClass)
            let iconBox = ScreenBox(left: Double(p.x - icon.halfWidth), top: Double(p.y - icon.height),
                right: Double(p.x + icon.halfWidth), bottom: Double(p.y))
            let owner = owners[id]
            let focus: Double
            if headingUp {
                focus = (tapeSpanDeg / 2 - abs(Heading.delta(headingDeg, m.bearingDeg))) / fanEdgeFadeDeg
            } else {
                focus = Double((focusRadius - hypot(p.x - focusCenter.x, p.y - focusCenter.y)) / (focusRadius * focusEdgeFadeRatio))
            }
            let weight = owner != nil ? 1 : min(max(focus, 0), 1)
            let underText = textBoxes.contains(where: { $0.intersects(iconBox) })
            let target = (weight <= 0 || p.y < chartTop + PeakIcon.maxHeight) ? 0
                : (underText ? minIconAlpha : scoreAlpha(score(m))) * weight
            let current = selection.iconAlpha[id] ?? 0
            let alpha = current < target ? min(target, current + iconStep) : max(target, current - iconStep)
            if alpha != target { iconFading = true }
            if alpha <= 0 {
                selection.iconAlpha[id] = nil
                continue
            }
            selection.iconAlpha[id] = alpha
            var faded = ctx
            faded.opacity = alpha
            drawPeakIcon(faded, at: p, icon: icon, shadow: false)
            if underText || target <= 0 { continue }
            if let owner {
                iconTargets.append(PlacedPeak(mountain: owner.peak.mountain, position: p, box: iconBox, members: owner.members.map(\.mountain)))
            } else {
                iconTargets.append(PlacedPeak(mountain: m, position: p, box: iconBox))
            }
        }
        // 画面の外へ出た山は忘れる。
        selection.iconAlpha = selection.iconAlpha.filter { visibleById[$0.key] != nil }
        let fading = selection.labelAlpha.values.contains { $0 < 1 } || iconFading
        if fading != selection.fading {
            selection.fading = fading
            // 濃さを変えている間だけ、毎フレーム描き直す(描画の途中では状態を変えられないので、描き終えてから切り替える)。
            DispatchQueue.main.async { animating = fading }
        }

        // 山名を外した山は、アイコンをアイコンだけの濃さへ、山名を透明へ近づけながら描く。
        for (peak, alpha) in fadingOut {
            guard let label = peak.label else { continue }
            let icon = PeakIcon.of(peak.mountain.mountain.elevationClass)
            var faded = ctx
            faded.opacity = labeledIconAlpha(scoreAlpha(score(peak.mountain)), alpha)
            drawPeakIcon(faded, at: peak.position, icon: icon, shadow: false)
            var chip = ctx
            chip.opacity = alpha
            drawNameChip(chip, label: label, at: peak.position, box: labelBox(peak), edge: icon.color)
        }
        let reps = groups.indices.map { i -> PlacedPeak in
            let group = groups[i]
            let peak = group.peak
            let p = peak.position
            let icon = PeakIcon.of(peak.mountain.mountain.elevationClass)
            let alpha = selection.labelAlpha[peak.mountain.mountain.osmId] ?? 1
            var faded = ctx
            faded.opacity = labeledIconAlpha(scoreAlpha(score(peak.mountain)), alpha)
            drawPeakIcon(faded, at: p, icon: icon, shadow: alpha >= 1)
            var chip = ctx
            chip.opacity = alpha
            if let label = peak.label {
                drawNameChip(chip, label: label, at: p, box: labelBoxes[i], edge: icon.color)
            }
            var box = peak.box
            if let others = othersLabels[i] {
                chip.draw(others.text.text, at: CGPoint(x: p.x - others.text.size.width / 2, y: CGFloat(others.box.top)), anchor: .topLeading)
                box = ScreenBox(left: min(box.left, others.box.left), top: box.top,
                    right: max(box.right, others.box.right), bottom: others.box.bottom)
            }
            return PlacedPeak(mountain: peak.mountain, position: p, box: box, members: group.members.map(\.mountain))
        }
        return reps + iconTargets
    }

    /// 山アイコンを [p](底辺の中点)に描く。標高の区分と影の有無ごとに一度だけ描いた図([PeakIconSymbol])を貼る。
    /// 1 画面に数百のアイコンを描くことがあるので、毎回形を作って描くより軽くする。濃さは [ctx] の不透明度で変える。
    private func drawPeakIcon(_ ctx: GraphicsContext, at p: CGPoint, icon: PeakIcon, shadow: Bool = true) {
        let symbol = PeakIconSymbol(icon: icon, shadow: shadow)
        guard let resolved = ctx.resolveSymbol(id: symbol.id) else { return }
        ctx.draw(resolved, at: p, anchor: symbol.anchor)
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
        let disc = CGRect(x: center.x - 23, y: center.y - 1 - 23, width: 46, height: 46)
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
        return CGRect(x: center.x - 23, y: center.y - 24, width: 46, height: 46)
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
/// 候補を選ぶ円を、画面の角(画面の中央から対角線の半分)より広く取る幅。
/// 地図を少し動かしたり向きを少し変えたりしただけでは選び直さない(ヘディングアップでは、向きを 10〜15° ほど変えるごとに選び直す)。
private let reachStep: CGFloat = 64

/// 新しく画面に出す山に求める、ほかの山との余白(pt)。境目で出たり消えたりしないようにする。
private let newPeakMargin = 4.0

/// アイコンだけで描く山(まとめた山と、視界の正面の山名を出さない山)の濃さの範囲。山名を出す山と見分けられるように薄くする。
private let minIconAlpha = 0.15
private let maxIconAlpha = 0.6

/// アイコンを最も濃く描く仰角(°)。
private let scoreAlphaFullDeg = 3.0

/// 手動位置モードで、山名を出さない山もアイコンで描く画面の中央の円の半径(画面の幅に対する比)。
private let focusRadiusRatio: CGFloat = 0.5

/// 山名を出し入れするときに、濃さを変える時間(秒)。
private let labelFadeSeconds = 0.25

/// 山名を出さない山のアイコンの濃さを、0 から 1 まで変えるのにかける時間(秒)。
private let iconFadeSeconds = 0.5

/// ヘディングアップで、視野の扇の端からこの角度(°)の内側まで、アイコンをだんだん薄くする。
private let fanEdgeFadeDeg = 8.0

/// 手動位置モードで、画面の中央の円の縁から半径のこの割合の内側まで、アイコンをだんだん薄くする。
private let focusEdgeFadeRatio: CGFloat = 0.2

/// アイコンだけで描く山の濃さ。優先度のスコア(仰角、°)が低いほど薄くする。
/// [scoreAlphaFullDeg] 以上で [maxIconAlpha]、0° 以下で [minIconAlpha]。
private func scoreAlpha(_ scoreDeg: Double) -> Double {
    minIconAlpha + (maxIconAlpha - minIconAlpha) * min(max(scoreDeg / scoreAlphaFullDeg, 0), 1)
}

/// 山名を出す山のアイコンの濃さ。山名の濃さ([labelAlpha])に合わせて、アイコンだけの濃さ([base])から 1 へ近づける。
private func labeledIconAlpha(_ base: Double, _ labelAlpha: Double) -> Double {
    base + (1 - base) * labelAlpha
}

/// 方位盤に出す山の候補。画面より一回り大きい、画面の周りの円から選び、山の一覧・表示範囲などが変わったときと、
/// 画面がその円からはみ出したときだけ選び直す。
/// 前回選んだ山と前回描いた山を覚えておき、境目にある山が出たり消えたりしないようにする。
private final class PeakSelection {
    struct Key: Equatable {
        let mountains: [NearbyMountain]
        let pxPerKm: CGFloat
        let observerAltitudeM: Double?
        let textScale: Double
        let maxPeaks: Int
        let width: CGFloat
        let chartHeight: CGFloat
        let headingUp: Bool
    }

    var key: Key?
    var drawnIds: Set<Int64> = []
    /// 前回描いた山と、そこにまとめてアイコンだけを描いた山。
    var shownIds: Set<Int64> = []
    /// 山を集めた円の中心(現在地からの距離と方角)と半径(km)。
    var centerKm = 0.0
    var centerBearingDeg = 0.0
    var radiusKm = 0.0
    /// 円の中にある、すべての山。
    var around: [NearbyMountain] = []
    /// 山ごとの山名と、山の位置を原点にしたアイコンと山名の範囲。文字の大きさが変わったら測り直す。
    var labels: [Int64: (text: MeasuredText, box: ScreenBox)] = [:]
    var labelTextScale: Double?
    /// 山名の濃さ(0〜1)。山名を出す山は 1 へ、山名を外した山は 0 へ近づける。0 になったら除く。
    var labelAlpha: [Int64: Double] = [:]
    /// 山名を出さない山のアイコンの濃さ。画面の外へ出た山は除く。
    var iconAlpha: [Int64: Double] = [:]
    /// 前回描いた時刻(秒)。山名とアイコンの濃さを時間に合わせて変える。
    var lastTime: CFTimeInterval = 0
    /// 山名かアイコンの濃さを変えている途中か。
    var fading = false
    /// 直近に描いた TimelineView の時刻。
    var frameDate = Date.distantPast
}

private struct PlacedPeak {
    let mountain: NearbyMountain
    let position: CGPoint
    /// アイコンと山名を合わせた範囲。重なりの判定とタップの当たり判定に使う。
    let box: ScreenBox
    /// 重なるので山名を省き、この山にまとめた山(優先順)。
    var members: [NearbyMountain] = []
    /// 山名。タップの当たり判定だけに使う山は nil。
    var label: MeasuredText? = nil
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
private enum PeakIcon: CaseIterable {
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

/// 山アイコンの形を描く。白い縁と影で地面の色から浮かせ、右の斜面を少し暗くして立体に見せる。
/// 形の点は、底辺の中点を原点に、横は半幅、縦は高さを 1 とした割合で決める(Android と同じ)。
private func drawPeakIconShape(_ ctx: GraphicsContext, at p: CGPoint, icon: PeakIcon, shadow: Bool) {
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

/// 山アイコンを一度だけ描いておく図。方位盤の [Canvas] に symbols として渡し、描くときは貼るだけにする。
/// 図にしておくと、薄く描いたときもアイコン全体が一様に薄くなる(縁や斜面が本体から透けない)。
private struct PeakIconSymbol: View {
    let icon: PeakIcon
    let shadow: Bool

    /// 白い縁と影がはみ出す分の余白。山の頂は高さの 1.07 倍まで膨らむ。
    private static let pad: CGFloat = 3
    private static let shadowOffset: CGFloat = 1.5

    static let all = PeakIcon.allCases.flatMap { icon in [true, false].map { PeakIconSymbol(icon: icon, shadow: $0) } }

    var id: Int { (PeakIcon.allCases.firstIndex(of: icon) ?? 0) * 2 + (shadow ? 1 : 0) }
    private var bottom: CGFloat { Self.pad + icon.height * 1.1 }
    private var size: CGSize { CGSize(width: (Self.pad + icon.halfWidth) * 2, height: bottom + Self.pad + Self.shadowOffset) }
    /// 図の中で、山の位置(底辺の中点)に当たる点。
    var anchor: UnitPoint { UnitPoint(x: 0.5, y: bottom / size.height) }

    var body: some View {
        Canvas { ctx, _ in
            drawPeakIconShape(ctx, at: CGPoint(x: size.width / 2, y: bottom), icon: icon, shadow: shadow)
        }
        .frame(width: size.width, height: size.height)
        .tag(id)
    }
}
