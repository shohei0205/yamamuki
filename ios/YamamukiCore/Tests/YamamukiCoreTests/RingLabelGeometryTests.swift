import XCTest
@testable import YamamukiCore

final class RingLabelGeometryTests: XCTestCase {
    func testDirectionChangesWithOneLabelButStaysWithTwo() {
        func count(_ angle: Double) -> Int {
            [100.0, 200.0, 300.0].filter { radius in
                RingLabelGeometry.place(cx: 180, cy: 700, radius: radius, left: 0, top: 76, right: 360, bottom: 748,
                    width: 60, height: 22, angle: angle) != nil
            }.count
        }
        XCTAssertEqual(count(0), 1)
        XCTAssertEqual(count(-.pi / 4), 2)
        let changed = RingLabelGeometry.direction(cx: 180, cy: 700, left: 0, top: 76, right: 360, bottom: 748,
            previousAngle: 0, visibleCount: count)
        XCTAssertGreaterThanOrEqual(count(changed), 2)
        XCTAssertEqual(RingLabelGeometry.direction(cx: 180, cy: 700, left: 0, top: 76, right: 360, bottom: 748,
            previousAngle: -.pi / 4, visibleCount: count), -.pi / 4)
        XCTAssertEqual(RingLabelGeometry.direction(cx: 180, cy: 700, left: 0, top: 76, right: 360, bottom: 748,
            previousAngle: 0, visibleCount: { _ in 1 }), 0)
    }

    func testSmallMovesKeepDirectionEvenAcrossScreenCenter() {
        let angle = -Double.pi / 2
        for x in [175.0, 180.0, 185.0] {
            for y in [408.0, 412.0, 416.0, 740.0] {
                XCTAssertEqual(angle, RingLabelGeometry.direction(cx: x, cy: y, left: 32, top: 90,
                    right: 328, bottom: 734, previousAngle: angle))
            }
        }
    }

    func testOffscreenRayChoosesNewDirectionAndKeepsItOnSmallMoves() {
        let angle = RingLabelGeometry.direction(cx: 400, cy: 740, left: 32, top: 90, right: 328, bottom: 734, previousAngle: -.pi / 2)
        XCTAssertNotEqual(angle, -.pi / 2)
        for x in [398.0, 400.0, 402.0] {
            XCTAssertEqual(angle, RingLabelGeometry.direction(cx: x, cy: 740, left: 32, top: 90,
                right: 328, bottom: 734, previousAngle: angle))
        }
    }

    func testLabelsStayOnOneRayForEveryObserverPositionAndTextSize() {
        for (cx, cy) in [(180.0, 748.0), (-100.0, 900.0), (500.0, 900.0), (180.0, -150.0), (180.0, 412.0)] {
            let angle = RingLabelGeometry.direction(cx: cx, cy: cy, left: 0, top: 76, right: 360, bottom: 748)
            var count = 0
            for i in 1...20 {
                let radius = Double(i) * 50
                let width = i % 2 == 0 ? 60.0 : 90.0
                guard let p = RingLabelGeometry.place(cx: cx, cy: cy, radius: radius, left: 0, top: 76,
                    right: 360, bottom: 748, width: width, height: 22, angle: angle) else { continue }
                count += 1
                XCTAssertEqual((p.x - cx) * sin(angle) - (p.y - cy) * cos(angle), 0, accuracy: 1e-7)
                XCTAssertEqual(hypot(p.x - cx, p.y - cy), radius, accuracy: 1e-7)
                XCTAssertTrue(p.x - width / 2 >= 0 && p.x + width / 2 <= 360)
                XCTAssertTrue(p.y - 11 >= 76 && p.y + 11 <= 748)
            }
            XCTAssertGreaterThanOrEqual(count, 2)
        }
    }

    func testUsualViewPointsUpAndClippedLabelsAreNotMovedSideways() throws {
        let angle = RingLabelGeometry.direction(cx: 180, cy: 748, left: 0, top: 76, right: 360, bottom: 748)
        XCTAssertEqual(angle, -.pi / 2)
        let p = try XCTUnwrap(RingLabelGeometry.place(cx: 180, cy: 748, radius: 200, left: 0, top: 76,
            right: 360, bottom: 748, width: 60, height: 22, angle: angle))
        XCTAssertEqual(p.x, 180, accuracy: 1e-7)
        XCTAssertEqual(p.y, 548, accuracy: 1e-7)
        XCTAssertNil(RingLabelGeometry.place(cx: 180, cy: 748, radius: 700, left: 0, top: 76,
            right: 360, bottom: 748, width: 60, height: 22, angle: angle))
        XCTAssertNil(RingLabelGeometry.place(cx: 180, cy: 748, radius: 0, left: 0, top: 76,
            right: 360, bottom: 748, width: 60, height: 22, angle: angle))
    }
}
