import XCTest
@testable import YamamukiCore

final class HeadingTests: XCTestCase {
    func testDeltaTakesShortestWay() {
        XCTAssertEqual(Heading.delta(359, 1), 2, accuracy: 1e-9)
        XCTAssertEqual(Heading.delta(1, 359), -2, accuracy: 1e-9)
        XCTAssertEqual(Heading.delta(0, 180), -180, accuracy: 1e-9)
    }

    func testDirectionNames() {
        XCTAssertEqual(Heading.directionName(0), "北")
        XCTAssertEqual(Heading.directionName(355), "北")
        XCTAssertEqual(Heading.directionName(20), "北北東")
        XCTAssertEqual(Heading.directionName(135), "南東")
        XCTAssertEqual(Heading.directionName(-90), "西")
    }

}

final class DialGeometryTests: XCTestCase {
    func testProjectRelativeToHeading() {
        let ahead = DialGeometry.project(distanceKm: 10, bearingDeg: 90, headingDeg: 90)
        XCTAssertEqual(ahead.x, 0, accuracy: 1e-9)
        XCTAssertEqual(ahead.y, 10, accuracy: 1e-9)

        let right = DialGeometry.project(distanceKm: 5, bearingDeg: 10, headingDeg: 280)
        XCTAssertEqual(right.x, 5, accuracy: 1e-9)
        XCTAssertEqual(right.y, 0, accuracy: 1e-9)

        let behind = DialGeometry.project(distanceKm: 3, bearingDeg: 180, headingDeg: 0)
        XCTAssertEqual(behind.y, -3, accuracy: 1e-9)
    }

    func testZoomIsClamped() {
        XCTAssertEqual(DialGeometry.zoomedRange(15, zoom: 2), 7.5, accuracy: 1e-9)
        XCTAssertEqual(DialGeometry.zoomedRange(3, zoom: 10), DialGeometry.minRangeKm, accuracy: 1e-9)
        XCTAssertEqual(DialGeometry.zoomedRange(60, zoom: 0.1), DialGeometry.maxRangeKm, accuracy: 1e-9)
        XCTAssertEqual(DialGeometry.zoomedRange(15, zoom: 0), 15, accuracy: 1e-9)
    }

    func testFetchRadiusCoversScreenCorners() {
        XCTAssertEqual(DialGeometry.fetchRadiusKm(DialGeometry.minRangeKm), 20)
        XCTAssertEqual(DialGeometry.fetchRadiusKm(DialGeometry.defaultRangeKm), 50)
        XCTAssertEqual(DialGeometry.fetchRadiusKm(DialGeometry.maxRangeKm), 120)
    }

    func testRingSteps() {
        XCTAssertEqual(DialGeometry.ringStepKm(15), 5)
        XCTAssertEqual(DialGeometry.ringStepKm(3), 1)
        XCTAssertEqual(DialGeometry.ringStepKm(80), 20)
        XCTAssertEqual(DialGeometry.ringStepKm(0.5), 0.5)
        XCTAssertEqual(DialGeometry.ringLabel(0.5), "500m")
        XCTAssertEqual(DialGeometry.ringLabel(10), "10km")
    }

    func testTapeTicksWrapAroundNorth() {
        let ticks = DialGeometry.tapeTicks(headingDeg: 3, spanDeg: 20)
        XCTAssertEqual(ticks.map(\.angleDeg), [355, 0, 5, 10])
        XCTAssertEqual(ticks.map(\.offsetDeg), [-8, -3, 2, 7])
        XCTAssertEqual(DialGeometry.cardinalLabel(0), "N")
        XCTAssertEqual(DialGeometry.cardinalLabel(225), "SW")
        XCTAssertNil(DialGeometry.cardinalLabel(10))
    }
}

final class DeclutterTests: XCTestCase {
    private func m(_ name: String, _ ele: Double?, _ km: Double) -> NearbyMountain {
        NearbyMountain(mountain: Mountain(osmId: Int64(name.hashValue), name: name, latitude: 0, longitude: 0, elevationM: ele), distanceKm: km, bearingDeg: 0)
    }

    func testKeepsHigherPriorityWhenOverlapping() {
        let boxes = [
            "A": ScreenBox(left: 0, top: 0, right: 10, bottom: 10),
            "B": ScreenBox(left: 5, top: 5, right: 15, bottom: 15),
            "C": ScreenBox(left: 20, top: 0, right: 30, bottom: 10),
        ]
        XCTAssertEqual(declutter(["A", "B", "C"]) { boxes[$0]! }, ["A", "C"])
        XCTAssertEqual(declutter(["A", "B", "C"], limit: 1) { boxes[$0]! }, ["A"])
    }

    func testPriorityPrefersHighThenNear() {
        let sorted = [m("低", 500, 1), m("不明", nil, 0.5), m("高遠", 2000, 9), m("高近", 2000, 3)]
            .sorted(by: displayPriority)
        XCTAssertEqual(sorted.map(\.mountain.name), ["高近", "高遠", "低", "不明"])
    }

    func testElevationClass() {
        func cls(_ ele: Double?) -> ElevationClass {
            Mountain(osmId: 1, name: "山", latitude: 0, longitude: 0, elevationM: ele).elevationClass
        }
        XCTAssertEqual(cls(nil), .low)
        XCTAssertEqual(cls(999.9), .low)
        XCTAssertEqual(cls(1000), .middle)
        XCTAssertEqual(cls(1999.9), .middle)
        XCTAssertEqual(cls(2000), .high)
        XCTAssertEqual(cls(3776), .high)
    }
}
