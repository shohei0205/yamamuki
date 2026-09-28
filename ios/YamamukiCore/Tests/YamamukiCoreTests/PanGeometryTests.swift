import XCTest
@testable import YamamukiCore

final class PanGeometryTests: XCTestCase {
    private let tokyo = MapCenter(35.696, 139.814)

    private func screen(_ point: MapCenter, viewport: MapCenter, heading: Double, scale: Double) -> PlanOffset {
        let p = DialGeometry.project(
            distanceKm: GeoMath.distanceKm(tokyo.latitude, tokyo.longitude, point.latitude, point.longitude),
            bearingDeg: GeoMath.bearingDeg(tokyo.latitude, tokyo.longitude, point.latitude, point.longitude),
            headingDeg: heading)
        let offset = PanGeometry.observerOffset(tokyo, viewport: viewport, heading: heading)
        return PlanOffset(x: (p.x + offset.x) * scale, y: -(p.y + offset.y) * scale)
    }

    func testHeadingSwipeAcrossNorth() {
        for dx in [-90.0, 90.0] {
            let next = DialGeometry.swipedHeading(0, dx: dx, width: 360)
            let north = DialGeometry.tapeTicks(headingDeg: next, spanDeg: DialGeometry.tapeSpanDeg).first { $0.angleDeg == 0 }!
            XCTAssertEqual(north.offsetDeg / DialGeometry.tapeSpanDeg * 360, dx, accuracy: 1e-8)
        }
        XCTAssertEqual(DialGeometry.swipedHeading(45, dx: 10, width: 0), 45)
        XCTAssertEqual(DialGeometry.swipedHeading(0, dx: 2160, width: 360), 0)
    }

    func testObserverAndRingsFollowDragAtEveryHeading() {
        for heading in [0.0, 45, 90, 180, 270, 359] {
            let viewport = PanGeometry.drag(tokyo, dx: 60, dy: -120, scale: 10, heading: heading)
            let offset = PanGeometry.observerOffset(tokyo, viewport: viewport, heading: heading)
            XCTAssertEqual(offset.x * 10, 60, accuracy: 1e-7)
            XCTAssertEqual(-offset.y * 10, -120, accuracy: 1e-7)
        }
    }

    func testOffCenterRotationPreservesFingerPivot() {
        let viewport = PanGeometry.drag(tokyo, dx: 100, dy: -50, scale: 10, heading: 30)
        let landmark = PanGeometry.drag(tokyo, dx: -70, dy: 120, scale: 10, heading: 0)
        for heading in [0.0, 45, 270, 359] {
            let pivot = screen(landmark, viewport: viewport, heading: heading, scale: 10)
            let nextHeading = Heading.normalize(heading - 70)
            let next = PanGeometry.transformViewport(tokyo, viewport: viewport, previous: pivot, midpoint: pivot,
                oldScale: 10, newScale: 10, oldHeading: heading, newHeading: nextHeading)
            let actual = screen(landmark, viewport: next, heading: nextHeading, scale: 10)
            XCTAssertEqual(actual.x, pivot.x, accuracy: 1e-6)
            XCTAssertEqual(actual.y, pivot.y, accuracy: 1e-6)
        }
    }

    func testSimultaneousPinchRotationAndPan() {
        let landmark = PanGeometry.drag(tokyo, dx: -80, dy: 100, scale: 10, heading: 0)
        let before = screen(landmark, viewport: tokyo, heading: 20, scale: 10)
        let after = PlanOffset(x: before.x + 60, y: before.y - 40)
        let viewport = PanGeometry.transformViewport(tokyo, viewport: tokyo, previous: before, midpoint: after,
            oldScale: 10, newScale: 17, oldHeading: 20, newHeading: 315)
        let actual = screen(landmark, viewport: viewport, heading: 315, scale: 17)
        XCTAssertEqual(actual.x, after.x, accuracy: 1e-6)
        XCTAssertEqual(actual.y, after.y, accuracy: 1e-6)
    }

    func testResetRestoresObserverToOrigin() {
        let offset = PanGeometry.observerOffset(tokyo, viewport: tokyo, heading: 123)
        XCTAssertEqual(offset.x, 0)
        XCTAssertEqual(offset.y, 0)
    }

    func testInvalidInputAndDateLine() {
        XCTAssertEqual(PanGeometry.drag(tokyo, dx: 10, dy: 5, scale: 0, heading: 0), tokyo)
        XCTAssertEqual(PanGeometry.drag(tokyo, dx: .nan, dy: 0, scale: 10, heading: 0), tokyo)
        let p = PanGeometry.drag(MapCenter(0, 179.99), dx: -100, dy: 0, scale: 10, heading: 0)
        XCTAssertTrue(p.longitude < -179 && p.longitude >= -180)
        let zero = PlanOffset(x: 0, y: 0)
        XCTAssertEqual(PanGeometry.transformViewport(tokyo, viewport: tokyo, previous: zero, midpoint: zero,
            oldScale: 0, newScale: 10, oldHeading: 0, newHeading: 90), tokyo)
    }
}
