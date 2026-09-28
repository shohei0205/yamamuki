import Foundation

public struct RingLabelAnchor {
    public let x: Double
    public let y: Double
    public let angle: Double
}

public enum RingLabelGeometry {
    /// 寸法は同じ画面単位。ラベル全体が収まる円弧を選び、前回の角度を優先する。
    public static func place(cx: Double, cy: Double, radius: Double, left: Double, top: Double,
        right: Double, bottom: Double, width: Double, height: Double, gap: Double,
        previousAngle: Double? = nil) -> RingLabelAnchor? {
        guard radius > 0, [cx, cy, radius, left, top, right, bottom, width, height, gap].allSatisfy({ $0.isFinite }) else { return nil }
        let l = left + width / 2, r = right - width / 2
        let t = top + height / 2, b = bottom - height / 2
        guard l <= r, t <= b else { return nil }
        func fits(_ p: RingLabelAnchor) -> Bool {
            p.x >= l - 1e-7 && p.x <= r + 1e-7 && p.y >= t - 1e-7 && p.y <= b + 1e-7
        }
        func point(_ a: Double) -> RingLabelAnchor {
            RingLabelAnchor(x: cx + radius * cos(a), y: cy + radius * sin(a), angle: a)
        }
        let above = RingLabelAnchor(x: cx, y: cy - radius - height / 2 - gap, angle: -.pi / 2)
        if fits(above) { return above }
        if let a = previousAngle, a.isFinite, fits(point(a)) { return point(a) }
        var cuts = [0.0, 2 * Double.pi]
        func add(_ a: Double) { cuts.append((a + 2 * .pi).truncatingRemainder(dividingBy: 2 * .pi)) }
        for x in [l, r] {
            let ratio = (x - cx) / radius
            if (-1.0...1.0).contains(ratio) { let a = acos(ratio); add(a); add(-a) }
        }
        for y in [t, b] {
            let ratio = (y - cy) / radius
            if (-1.0...1.0).contains(ratio) { let a = asin(ratio); add(a); add(.pi - a) }
        }
        let sorted = Array(Set(cuts)).sorted()
        var intervals = zip(sorted, sorted.dropFirst()).filter { fits(point(($0.0 + $0.1) / 2)) }
        if intervals.count > 1, intervals.first!.0 == 0, intervals.last!.1 == 2 * .pi {
            let first = intervals.removeFirst()
            let last = intervals.removeLast()
            intervals.append((last.0, first.1 + 2 * .pi))
        }
        guard let longest = intervals.max(by: { $0.1 - $0.0 < $1.1 - $1.0 }) else { return nil }
        return point((longest.0 + longest.1) / 2)
    }
}
