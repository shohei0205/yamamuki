import XCTest
@testable import YamamukiCore

final class RingLabelGeometryTests: XCTestCase {
    private func place(_ x: Double, _ y: Double, _ radius: Double, previous: Double? = nil) -> RingLabelAnchor? {
        RingLabelGeometry.place(cx: x, cy: y, radius: radius, left: 0, top: 76, right: 360, bottom: 748,
            width: 60, height: 22, gap: 2, previousAngle: previous)
    }

    func testNormalViewKeepsLabelAboveRing() throws {
        let p = try XCTUnwrap(place(180, 748, 200))
        XCTAssertEqual(p.x, 180)
        XCTAssertEqual(p.y, 535)
    }

    func testOffscreenObserverGetsLabelOnVisibleArc() throws {
        for (x, y) in [(-100.0, 900.0), (500.0, 900.0), (180.0, -150.0), (-100.0, 400.0)] {
            let p = try XCTUnwrap(place(x, y, 400))
            XCTAssertTrue((30.0...330.0).contains(p.x))
            XCTAssertTrue((87.0...737.0).contains(p.y))
            XCTAssertEqual(hypot(p.x - x, p.y - y), 400, accuracy: 1e-7)
        }
    }

    func testSmallPanRetainsPreviousAngle() throws {
        let before = try XCTUnwrap(place(-100, 900, 400))
        let after = try XCTUnwrap(place(-99, 899, 400, previous: before.angle))
        XCTAssertEqual(before.angle, after.angle)
        XCTAssertEqual(after.x - before.x, 1, accuracy: 1e-7)
        XCTAssertEqual(after.y - before.y, -1, accuracy: 1e-7)
    }

    func testInvisibleOrTooSmallAreaHasNoLabel() {
        XCTAssertNil(place(-500, 400, 1000))
        XCTAssertNil(place(180, 400, 0))
        XCTAssertNil(RingLabelGeometry.place(cx: 10, cy: 10, radius: 50, left: 0, top: 0, right: 20, bottom: 20,
            width: 60, height: 22, gap: 2))
    }
}
