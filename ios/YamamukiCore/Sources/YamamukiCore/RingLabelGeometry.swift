import Foundation

public struct RingLabelAnchor {
    public let x: Double
    public let y: Double
    public let angle: Double
}

public enum RingLabelGeometry {
    /// 全距離で共通の方向。前の方向で表示できる間は固定し、見切れたら中央へ向け直す。
    public static func direction(cx: Double, cy: Double, left: Double, top: Double, right: Double, bottom: Double,
        previousAngle: Double? = nil, minimumSpan: Double = 48, visibleCount: ((Double) -> Int)? = nil) -> Double {
        func visibleSpan(_ angle: Double) -> Double {
            var near = 0.0
            var far = Double.infinity
            func clip(_ origin: Double, _ delta: Double, _ low: Double, _ high: Double) -> Bool {
                if abs(delta) < 1e-9 { return origin >= low && origin <= high }
                let a = (low - origin) / delta, b = (high - origin) / delta
                near = max(near, min(a, b))
                far = min(far, max(a, b))
                return far >= near
            }
            guard left < right, top < bottom, clip(cx, cos(angle), left, right), clip(cy, sin(angle), top, bottom) else { return 0 }
            return max(0, far - near)
        }
        if let angle = previousAngle, angle.isFinite {
            if let count = visibleCount {
                if count(angle) >= 2 { return angle }
            } else if visibleSpan(angle) >= minimumSpan { return angle }
        }
        let dx = (left + right) / 2 - cx
        let dy = (top + bottom) / 2 - cy
        let next = hypot(dx, dy) < 1e-6 ? -Double.pi / 2 : atan2(dy, dx)
        if let count = visibleCount, let old = previousAngle, old.isFinite, count(next) <= count(old) { return old }
        return next
    }

    /// 円と共通の半直線の交点に文字の中心を置く。収まらない数字は別方向へずらさず省く。
    public static func place(cx: Double, cy: Double, radius: Double, left: Double, top: Double,
        right: Double, bottom: Double, width: Double, height: Double, angle: Double) -> RingLabelAnchor? {
        guard radius > 0, [cx, cy, radius, left, top, right, bottom, width, height, angle].allSatisfy({ $0.isFinite }) else { return nil }
        let x = cx + radius * cos(angle)
        let y = cy + radius * sin(angle)
        guard x - width / 2 >= left, x + width / 2 <= right, y - height / 2 >= top, y + height / 2 <= bottom else { return nil }
        return RingLabelAnchor(x: x, y: y, angle: angle)
    }
}
