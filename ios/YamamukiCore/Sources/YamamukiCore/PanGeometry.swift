import Foundation

public struct MapCenter: Equatable, Sendable {
    public let latitude: Double
    public let longitude: Double
    public init(_ latitude: Double, _ longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }
}

public enum PanGeometry {
    /// 方角が変わっても双眼鏡を画面上の直線に沿って描画原点へ戻す。offsetはkm、yは上向き。
    public static func returnViewport(_ observer: MapCenter, initialOffset: PlanOffset, heading: Double, fraction: Double) -> MapCenter {
        let remaining = 1 - min(1, max(0, fraction))
        return drag(observer, dx: initialOffset.x * remaining, dy: -initialOffset.y * remaining, scale: 1, heading: heading)
    }

    public static func interpolateCenter(_ from: MapCenter, to: MapCenter, fraction: Double) -> MapCenter {
        if fraction <= 0 { return from }
        if fraction >= 1 || from == to { return to }
        let offset = DialGeometry.project(
            distanceKm: GeoMath.distanceKm(from.latitude, from.longitude, to.latitude, to.longitude),
            bearingDeg: GeoMath.bearingDeg(from.latitude, from.longitude, to.latitude, to.longitude), headingDeg: 0)
        return drag(from, dx: -offset.x * fraction, dy: offset.y * fraction, scale: 1, heading: 0)
    }

    public static func northUpHeading(_ heading: Double, progress: Double) -> Double {
        let t = min(1, max(0, progress))
        return t == 1 ? 0 : Heading.normalize(heading + Heading.delta(heading, 0) * t * t * (3 - 2 * t))
    }

    /// 描画される双眼鏡の中心が画面内にあるか。寸法はpt。
    public static func isObserverVisible(_ observer: MapCenter, viewport: MapCenter, heading: Double,
        rangeKm: Double, canvasWidth: Double, canvasHeight: Double) -> Bool {
        guard canvasWidth > 0, canvasHeight > DialGeometry.chartInset, rangeKm > 0 else { return false }
        let scale = (canvasHeight - DialGeometry.chartInset) / rangeKm
        let offset = observerOffset(observer, viewport: viewport, heading: heading)
        let x = canvasWidth / 2 + offset.x * scale
        let y = canvasHeight - DialGeometry.originBottom - offset.y * scale
        return x >= 0 && x <= canvasWidth && y >= DialGeometry.chartTop && y <= canvasHeight
    }

    /// 選んだ回転中心をアニメーション中も固定する。高さはpt。
    public static func northUpViewport(_ observer: MapCenter, viewport: MapCenter, heading: Double,
        rangeKm: Double, canvasHeight: Double, progress: Double = 1, aroundCenter: Bool = true) -> MapCenter {
        guard progress > 0, aroundCenter || observer != viewport else { return viewport }
        return rotateViewport(observer, viewport: viewport, heading: heading, nextHeading: northUpHeading(heading, progress: progress),
            rangeKm: rangeKm, canvasHeight: canvasHeight, aroundCenter: aroundCenter)
    }

    /// 双眼鏡または画面中央を固定して方角を変える。
    public static func rotateViewport(_ observer: MapCenter, viewport: MapCenter, heading: Double, nextHeading: Double,
        rangeKm: Double, canvasHeight: Double, aroundCenter: Bool) -> MapCenter {
        guard canvasHeight.isFinite, canvasHeight > DialGeometry.chartInset, rangeKm.isFinite, rangeKm > 0,
              heading != nextHeading, aroundCenter || observer != viewport else { return viewport }
        let scale = (canvasHeight - DialGeometry.chartInset) / rangeKm
        let offset = observerOffset(observer, viewport: viewport, heading: heading)
        let pivot = aroundCenter ? PlanOffset(x: 0, y: DialGeometry.originBottom - canvasHeight / 2)
            : PlanOffset(x: offset.x * scale, y: -offset.y * scale)
        return transformViewport(observer, viewport: viewport, previous: pivot, midpoint: pivot,
            oldScale: scale, newScale: scale, oldHeading: heading, newHeading: nextHeading)
    }

    public static func drag(_ center: MapCenter, dx: Double, dy: Double, scale: Double, heading: Double) -> MapCenter {
        guard scale.isFinite, scale > 0, dx.isFinite, dy.isFinite, heading.isFinite else { return center }
        let x = -dx / scale, y = dy / scale
        let distance = hypot(x, y)
        guard distance > 0 else { return center }
        let bearing = radians(heading) + atan2(x, y)
        let angular = distance / GeoMath.earthRadiusKm
        let lat = radians(center.latitude), lon = radians(center.longitude)
        let nextLat = asin(min(1, max(-1, sin(lat) * cos(angular) + cos(lat) * sin(angular) * cos(bearing))))
        let nextLon = lon + atan2(sin(bearing) * sin(angular) * cos(lat), cos(angular) - sin(lat) * sin(nextLat))
        return MapCenter(min(85, max(-85, degrees(nextLat))), Heading.normalize(degrees(nextLon) + 180) - 180)
    }

    public static func observerOffset(_ observer: MapCenter, viewport: MapCenter, heading: Double) -> PlanOffset {
        let p = DialGeometry.project(
            distanceKm: GeoMath.distanceKm(observer.latitude, observer.longitude, viewport.latitude, viewport.longitude),
            bearingDeg: GeoMath.bearingDeg(observer.latitude, observer.longitude, viewport.latitude, viewport.longitude),
            headingDeg: heading)
        return PlanOffset(x: -p.x, y: -p.y)
    }

    /// 中間点は描画原点からのpt。yは下向き。指の下の地点を回転・拡縮後も維持する。
    public static func transformViewport(_ observer: MapCenter, viewport: MapCenter,
        previous: PlanOffset, midpoint: PlanOffset, oldScale: Double, newScale: Double,
        oldHeading: Double, newHeading: Double) -> MapCenter {
        guard oldScale > 0, newScale > 0,
              [oldScale, newScale, oldHeading, newHeading, previous.x, previous.y, midpoint.x, midpoint.y].allSatisfy({ $0.isFinite })
        else { return viewport }
        func world(_ p: PlanOffset, _ scale: Double, _ heading: Double) -> PlanOffset {
            let a = radians(heading)
            return PlanOffset(x: (p.x * cos(a) - p.y * sin(a)) / scale,
                y: (-p.x * sin(a) - p.y * cos(a)) / scale)
        }
        let offset = observerOffset(observer, viewport: viewport, heading: 0)
        let before = world(previous, oldScale, oldHeading)
        let after = world(midpoint, newScale, newHeading)
        return drag(observer, dx: offset.x - before.x + after.x,
            dy: -offset.y + before.y - after.y, scale: 1, heading: 0)
    }
}
