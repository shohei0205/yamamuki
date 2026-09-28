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
