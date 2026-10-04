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

/// 方位盤に出す山の選び方。
///
/// 端末の向きを変えるたびに選び直すと、画面の端の出入りや回転による重なり方の変化で山が点滅する。
/// そこで、地図をどの向きに回しても重ならない組み合わせを、現在地の周り全体(360°)から選んでおく。
/// 選び直すのは、山の一覧・表示範囲・文字の大きさなどが変わったときだけにする。
/// 画面には、選んだ山のうち画面に入るものを描くだけにする。
public enum PeakLayout {
    /// 前回選んだ山の仰角に上乗せする値(°)。GPS の標高の揺れで、順位が入れ替わって点滅しないようにする。
    public static let keptBonusDeg = 0.3

    /// 前回どちらも選んだ 2 つの山は、重なりの判定の距離をこの割合に縮め、少し近づいても残す。
    public static let keptClearanceRatio = 0.9

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

    /// 2 つの山の位置がこの距離(pt)より近いと、地図の向きによってはアイコンや山名が重なる。
    /// [a] と [b] は、それぞれの山の位置を原点にしたアイコンと山名の範囲。山名は地図を回しても水平のまま。
    public static func clearance(_ a: ScreenBox, _ b: ScreenBox) -> Double {
        // b の位置から見た a の位置がこの矩形に入ると重なる。位置の差は地図と一緒に回るので、
        // 矩形の最も遠い角までの距離より近ければ、どこかの向きで重なる。
        let dx = max(abs(a.right - b.left), abs(b.right - a.left))
        let dy = max(abs(a.bottom - b.top), abs(b.bottom - a.top))
        return hypot(dx, dy)
    }

    /// 地図をどの向きに回しても重ならないように、優先順の [items] から先に置いたものと重なるものを捨て、最大 [limit] 件を返す。
    /// [position] は現在地からの位置(pt、向きは問わない)、[box] は山の位置を原点にした範囲。
    /// [keptBefore] が true のもの同士は、重なりの判定を [keptClearanceRatio] だけ甘くする。
    /// [limit] 件に達したら残りは取り出さないので、`lazy` の列を渡せば以降の山名の計測などを省ける。
    public static func selectAround<S: Sequence>(
        _ items: S,
        limit: Int = .max,
        position: (S.Element) -> PlanOffset,
        box: (S.Element) -> ScreenBox,
        keptBefore: (S.Element) -> Bool = { _ in false }
    ) -> [S.Element] {
        var placed: [(item: S.Element, at: PlanOffset, box: ScreenBox, kept: Bool)] = []
        guard limit > 0 else { return [] }
        // for-in は次の項目を取り出してから本体に入るので、上限の確認は置いた直後にする。
        for item in items {
            let at = position(item)
            let b = box(item)
            let kept = keptBefore(item)
            let clear = !placed.contains { p in
                let ratio = kept && p.kept ? keptClearanceRatio : 1
                return hypot(at.x - p.at.x, at.y - p.at.y) < clearance(b, p.box) * ratio
            }
            if clear {
                placed.append((item: item, at: at, box: b, kept: kept))
                if placed.count >= limit { break }
            }
        }
        return placed.map(\.item)
    }

    /// 周り全体から選ぶ山の上限。画面に [maxPeaks] 件までの密度になるよう、
    /// 現在地から [reach] の円の面積と画面の面積 [viewArea] の比で増やす。
    public static func aroundLimit(maxPeaks: Int, reach: Double, viewArea: Double) -> Int {
        guard viewArea > 0, maxPeaks > 0 else { return max(maxPeaks, 0) }
        let ratio = max(1, Double.pi * reach * reach / viewArea)
        return Int(min((Double(maxPeaks) * ratio).rounded(.up), Double(Int.max / 2)))
    }

    /// 画面に入る山([visible]、優先順)を [limit] 件までに絞る。前回描いた山([drawnBefore])を先に残し、
    /// 優先度の高い山が画面の端から入ってきても、描いている山を押し出さないようにする。返す順は優先順のまま。
    public static func capVisible<T>(_ visible: [T], limit: Int, drawnBefore: (T) -> Bool) -> [T] {
        if visible.count <= limit { return visible }
        let indices = Array(visible.indices)
        let old = indices.filter { drawnBefore(visible[$0]) }
        let new = indices.filter { !drawnBefore(visible[$0]) }
        let keep = Set((old + new).prefix(limit))
        return indices.filter(keep.contains).map { visible[$0] }
    }
}
