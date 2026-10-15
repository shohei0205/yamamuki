import XCTest
@testable import YamamukiCore

final class RingLabelGeometryTests: XCTestCase {
    func testRotationKeepsOffscreenObserverLabelsOnTheMap() throws {
        let initialAngle = -Double.pi / 2 + 0.1
        for heading in [0.0, 30.0, 90.0, 180.0, 270.0, 359.0] {
            let turn = -heading * .pi / 180
            func rotate(_ x: Double, _ y: Double) -> (Double, Double) {
                (180 + (x - 180) * cos(turn) - (y - 412) * sin(turn),
                 412 + (x - 180) * sin(turn) + (y - 412) * cos(turn))
            }
            let (cx, cy) = rotate(180, 900)
            func place(_ radius: Double, _ angle: Double) -> RingLabelAnchor? {
                RingLabelGeometry.place(cx: cx, cy: cy, radius: radius, left: 0, top: 76,
                    right: 360, bottom: 748, width: 60, height: 22, angle: angle)
            }
            let rotated = try XCTUnwrap(RingLabelGeometry.rotatedAngle(initialAngle, previousHeading: 0, heading: heading))
            let chosen = RingLabelGeometry.direction(cx: cx, cy: cy, left: 0, top: 76, right: 360, bottom: 748,
                previousAngle: rotated, visibleCount: { angle in [500.0, 600.0].filter { place($0, angle) != nil }.count })
            XCTAssertEqual(chosen, rotated)
            for radius in [500.0, 600.0] {
                let expected = rotate(180 + radius * cos(initialAngle), 900 + radius * sin(initialAngle))
                let actual = try XCTUnwrap(place(radius, chosen))
                XCTAssertEqual(actual.x, expected.0, accuracy: 1e-7)
                XCTAssertEqual(actual.y, expected.1, accuracy: 1e-7)
            }
        }
    }

    func testRotationCrossesNorthInBothDirectionsAndHandlesFirstFrame() throws {
        let angle = -Double.pi / 2
        XCTAssertEqual(try XCTUnwrap(RingLabelGeometry.rotatedAngle(angle, previousHeading: 359, heading: 1)),
            angle - 2 * .pi / 180, accuracy: 1e-7)
        XCTAssertEqual(try XCTUnwrap(RingLabelGeometry.rotatedAngle(angle, previousHeading: 1, heading: 359)),
            angle + 2 * .pi / 180, accuracy: 1e-7)
        XCTAssertEqual(RingLabelGeometry.rotatedAngle(angle, previousHeading: 45, heading: 45), angle)
        XCTAssertEqual(RingLabelGeometry.rotatedAngle(angle, previousHeading: nil, heading: 45), angle)
        XCTAssertNil(RingLabelGeometry.rotatedAngle(nil, previousHeading: nil, heading: 45))
    }

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

    func testEdgePutsLabelInsideScreenEdgeAlongTheRay() throws {
        func edge(_ cx: Double, _ cy: Double, _ angle: Double, minDistance: Double = 50) -> RingLabelAnchor? {
            RingLabelGeometry.edge(cx: cx, cy: cy, left: 0, top: 76, right: 360, bottom: 748,
                width: 40, height: 20, angle: angle, minDistance: minDistance)
        }
        let up = try XCTUnwrap(edge(180, 700, -Double.pi / 2))
        XCTAssertEqual(up.x, 180, accuracy: 1e-7)
        XCTAssertEqual(up.y, 86, accuracy: 1e-7)
        let right = try XCTUnwrap(edge(180, 700, 0))
        XCTAssertEqual(right.x, 340, accuracy: 1e-7)
        XCTAssertEqual(right.y, 700, accuracy: 1e-7)
        // 下へは縁まで近すぎるので省く。
        XCTAssertNil(edge(180, 700, Double.pi / 2))
        XCTAssertEqual(try XCTUnwrap(edge(180, 700, Double.pi / 2, minDistance: 0)).y, 738, accuracy: 1e-7)
        // 画面の外の中心からでも、画面を通る線には付ける。
        let fromBelow = try XCTUnwrap(edge(180, 900, -Double.pi / 2))
        XCTAssertEqual(fromBelow.y, 86, accuracy: 1e-7)
        XCTAssertNil(edge(180, 900, 0))
    }

    func testTickRingIndexPicksRingWithMostVisibleNumbers() {
        let angles = stride(from: 0, to: 360, by: 30).map { Double($0) * .pi / 180 }
        func pick(_ cx: Double, _ radii: [Double]) -> Int? {
            RingLabelGeometry.tickRingIndex(cx: cx, cy: 700, radii: radii, angles: angles,
                left: 0, top: 140, right: 360, bottom: 748, margin: 10)
        }
        // 外側の大きな円は縦長の画面では上の数字しか入らないので、内側の円を選ぶ。同じ数なら外側。
        XCTAssertEqual(pick(180, [100, 170, 360, 550]), 1)
        XCTAssertEqual(pick(180, [100]), 0)
        XCTAssertNil(pick(180, []))
        XCTAssertNil(pick(-2000, [100, 170]))
    }
}
