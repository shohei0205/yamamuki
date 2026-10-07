import Foundation

extension NearbyMountain {
    /// 表示の優先度のスコア。現在地から山を見上げる角度(仰角、°)。
    /// 近くの低い山も、遠くの高い山も、見かけの高さで比べる。地平線の下に沈む遠くの山は低くなる。
    /// 標高が分からない山は、いちばん後ろに回す。
    /// [observerAltitudeM] は現在地の標高(海抜)。分からなければ 0m とみなす。
    public func displayScore(observerAltitudeM: Double?) -> Double {
        guard let ele = mountain.elevationM else { return -.infinity }
        return GeoMath.elevationAngleDeg(observerAltitudeM: observerAltitudeM ?? 0, targetElevationM: ele, distanceKm: distanceKm)
    }
}

/// 表示の優先順: 仰角の大きい順、標高不明は後ろ、同じなら近い順。`sorted(by:)` に渡す。
public func displayPriority(observerAltitudeM: Double?) -> (NearbyMountain, NearbyMountain) -> Bool {
    { a, b in
        let sa = a.displayScore(observerAltitudeM: observerAltitudeM)
        let sb = b.displayScore(observerAltitudeM: observerAltitudeM)
        if sa != sb { return sa > sb }
        return a.distanceKm < b.distanceKm
    }
}

/// 画面に描く山 1 件([peak])と、その山に重なるので山名を省き、アイコンだけを描く山([members]、優先順)。
public struct PeakGroup<T> {
    public let peak: T
    public let members: [T]

    public init(peak: T, members: [T]) {
        self.peak = peak
        self.members = members
    }
}

/// 方位盤に出す山の選び方。2 段階で決める。
///
/// 1. 候補を選ぶ([candidates])。山の一覧・表示範囲・文字の大きさなどが変わったときだけ、
///    現在地の周り全体(360°)から仰角の大きい順に選ぶ。手動位置モードで双眼鏡が画面の外にあるときは、
///    画面の周りの円から選ぶ(遠くの現在地の周りの山に、画面の山が押し出されないようにする)。
/// 2. 画面に描く山を決める([placeVisible])。描くたびに、画面に入る候補を今の向きで重ならないように並べる。
///    重なる山は山名を省いてアイコンだけを残し、代表の山にまとめる。すぐそばの山どうしは標高の高いほうを代表にする。
///    前回描いた山を先に置くので、向きを変えても、描いている山が後から入ってきた山に押し出されない。
///    新しく出す山は少し余白をとって判定し、境目で出たり消えたりしないようにする。
public enum PeakLayout {
    /// 前回選んだ山の仰角に上乗せする値(°)。GPS の標高の揺れで、順位が入れ替わって点滅しないようにする。
    public static let keptBonusDeg = 0.3

    /// 同じ山塊の主峰と肩・前衛峰とみなす、山どうしの距離(km)。
    /// 奥穂高岳とジャンダルム(約 0.4km)、奥穂高岳と前穂高岳(約 1.5km)、槍ヶ岳と中岳(約 1km)が入り、
    /// 尾根続きでも別の山として数えたい隣の峰(数 km 先)は入りにくい長さにする。
    public static let neighborKm = 3.0

    /// 選ぶ順。[displayPriority] の順に、前回選んだ山([keptIds]、OSM の ID)は [keptBonusDeg] だけ上乗せする。
    public static func priorityOrder(
        _ mountains: [NearbyMountain],
        observerAltitudeM: Double?,
        keptIds: Set<Int64> = []
    ) -> [NearbyMountain] {
        func score(_ m: NearbyMountain) -> Double {
            m.displayScore(observerAltitudeM: observerAltitudeM) + (keptIds.contains(m.mountain.osmId) ? keptBonusDeg : 0)
        }
        return mountains
            .map { (m: $0, score: score($0)) }
            .sorted { a, b in a.score != b.score ? a.score > b.score : a.m.distanceKm < b.m.distanceKm }
            .map(\.m)
    }

    /// [a] と [b] が [neighborKm] 以内にある、同じ山塊の山か。
    public static func areNeighbors(_ a: NearbyMountain, _ b: NearbyMountain) -> Bool {
        GeoMath.distanceKm(a.mountain.latitude, a.mountain.longitude, b.mountain.latitude, b.mountain.longitude) <= neighborKm
    }

    /// 候補の山。[priorityOrder] の順で、円の中心から [reachKm] 以内(画面に入りうる距離)の山を最大 [limit] 件。
    /// 円の中心は、現在地から [centerBearingDeg] の方角へ [centerKm] 離れた点。0 なら現在地。
    public static func candidates(
        _ mountains: [NearbyMountain],
        observerAltitudeM: Double?,
        keptIds: Set<Int64>,
        reachKm: Double,
        limit: Int,
        centerKm: Double = 0,
        centerBearingDeg: Double = 0
    ) -> [NearbyMountain] {
        Array(priorityOrder(mountains, observerAltitudeM: observerAltitudeM, keptIds: keptIds)
            .filter { planeDistanceKm($0.distanceKm, $0.bearingDeg, centerKm, centerBearingDeg) <= reachKm }
            .prefix(max(limit, 0)))
    }

    /// 現在地から見た 2 点(距離 km と方角)の、方位盤の平面上での距離(km)。
    public static func planeDistanceKm(_ aKm: Double, _ aBearingDeg: Double, _ bKm: Double, _ bBearingDeg: Double) -> Double {
        let delta = Heading.delta(aBearingDeg, bBearingDeg) * .pi / 180
        return (max(0, aKm * aKm + bKm * bKm - 2 * aKm * bKm * cos(delta))).squareRoot()
    }

    /// 周り全体から選ぶ山の上限。画面に [maxPeaks] 件までの密度になるよう、
    /// 現在地から [reach] の円の面積と画面の面積 [viewArea] の比で増やす。
    public static func aroundLimit(maxPeaks: Int, reach: Double, viewArea: Double) -> Int {
        guard viewArea > 0, maxPeaks > 0 else { return max(maxPeaks, 0) }
        let ratio = max(1, Double.pi * reach * reach / viewArea)
        return Int(min((Double(maxPeaks) * ratio).rounded(.up), Double(Int.max / 2)))
    }

    /// 画面に描く山を決める。画面に入る山([visible]、優先順)を、今の向きの範囲([box]、画面上の位置)で重ならないように置く。
    ///
    /// - すぐそばの山どうし([neighbors])が今重なるときは、標高([elevationM])の高い順に決め、高い山を残して低い山を省く。
    ///   どの順で山が来ても同じ結果になる。
    /// - 残りは前回描いた山([drawnBefore])を先に、それぞれ優先順に置き、置いた山と重なる山を省く。最大 [limit] 件。
    ///   優先度の高い山が画面の端から入ってきたり、向きが変わったりしても、描いている山は実際に重なるまで消えない。
    /// - 新しく出す山は、範囲を [margin] だけ広げて判定する(境目で出たり消えたりしないようにする)。
    /// - 省いた山は、重なっている置いた山(すぐそばの高い山があればその山)の [PeakGroup.members] に入れる。
    ///   上限で省いた山など、どの山とも重ならない山は入れない。
    ///
    /// 返す順は優先順のまま。
    public static func placeVisible<T>(
        _ visible: [T],
        limit: Int,
        box: (T) -> ScreenBox,
        drawnBefore: (T) -> Bool,
        neighbors: (T, T) -> Bool,
        elevationM: (T) -> Double?,
        margin: Double = 0
    ) -> [PeakGroup<T>] {
        let boxes = visible.map(box)
        let drawn = visible.map(drawnBefore)
        let heights = visible.map { elevationM($0) ?? -.infinity }
        func testBox(_ i: Int) -> ScreenBox {
            let b = boxes[i]
            return drawn[i] ? b : ScreenBox(left: b.left - margin, top: b.top - margin, right: b.right + margin, bottom: b.bottom + margin)
        }

        // そばの山どうしは、標高の高い順に決める。すでに残した高い山と今重なる山は省く。
        var dominator = [Int](repeating: -1, count: visible.count)
        var kept: [Int] = []
        // 同じ標高なら優先順のまま。
        for i in visible.indices.sorted(by: { heights[$0] != heights[$1] ? heights[$0] > heights[$1] : $0 < $1 }) {
            if let d = kept.first(where: { j in
                heights[j] > heights[i] && boxes[j].intersects(testBox(i)) && neighbors(visible[i], visible[j])
            }) {
                dominator[i] = d
            } else {
                kept.append(i)
            }
        }

        let free = visible.indices.filter { dominator[$0] < 0 }
        var placed: [Int] = []
        for i in free.filter({ drawn[$0] }) + free.filter({ !drawn[$0] }) {
            if placed.count >= limit { break }
            let b = testBox(i)
            if !placed.contains(where: { boxes[$0].intersects(b) }) { placed.append(i) }
        }

        let placedSet = Set(placed)
        var members: [Int: [Int]] = [:]
        for i in visible.indices where !placedSet.contains(i) {
            let b = testBox(i)
            let owner = placedSet.contains(dominator[i]) ? dominator[i] : placed.first { boxes[$0].intersects(b) }
            if let owner { members[owner, default: []].append(i) }
        }
        return visible.indices.filter(placedSet.contains).map { i in
            PeakGroup(peak: visible[i], members: (members[i] ?? []).map { visible[$0] })
        }
    }
}
