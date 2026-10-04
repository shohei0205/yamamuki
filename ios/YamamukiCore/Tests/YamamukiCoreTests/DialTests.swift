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

final class PeakLayoutTests: XCTestCase {
    private func m(_ name: String, _ ele: Double?, _ km: Double) -> NearbyMountain {
        NearbyMountain(mountain: Mountain(osmId: Int64(name.hashValue), name: name, latitude: 0, longitude: 0, elevationM: ele), distanceKm: km, bearingDeg: 0)
    }

    func testElevationAngleAccountsForEarthCurve() {
        // 1km 先の 1000m 上は、ほぼ 45° に見える。
        XCTAssertEqual(GeoMath.elevationAngleDeg(observerAltitudeM: 0, targetElevationM: 1000, distanceKm: 1), 45, accuracy: 0.01)
        // 100km 先では、地球の丸みで約 683m 沈む(屈折で少し浮き上がった後の値)。
        XCTAssertEqual(GeoMath.elevationAngleDeg(observerAltitudeM: 0, targetElevationM: 682.8, distanceKm: 100), 0, accuracy: 0.001)
        XCTAssertLessThan(GeoMath.elevationAngleDeg(observerAltitudeM: 0, targetElevationM: 600, distanceKm: 100), 0)
        // 自分より低い山は負の角度になる。
        XCTAssertLessThan(GeoMath.elevationAngleDeg(observerAltitudeM: 1500, targetElevationM: 1400, distanceKm: 1), 0)
    }

    func testPriorityPrefersHigherLookingPeaks() {
        // 近くの低い山は、遠くの高い山より見かけが高いので先。標高不明は最後。
        let sorted = [m("富士", 3776, 80), m("不明", nil, 0.5), m("裏山", 300, 2), m("遠い丘", 300, 30)]
            .sorted(by: displayPriority(observerAltitudeM: 0))
        XCTAssertEqual(sorted.map(\.mountain.name), ["裏山", "富士", "遠い丘", "不明"])
    }

    func testPriorityUsesObserverAltitude() {
        let near = m("近い", 1400, 1)
        let far = m("遠い", 2000, 20)
        XCTAssertEqual([far, near].sorted(by: displayPriority(observerAltitudeM: nil)).map(\.mountain.name), ["近い", "遠い"])
        // 自分が 1500m にいると、1400m の山は見下ろすので後ろに回る。
        XCTAssertEqual([near, far].sorted(by: displayPriority(observerAltitudeM: 1500)).map(\.mountain.name), ["遠い", "近い"])
    }

    func testKeptPeaksGetBonus() {
        let a = m("A", 1000, 10)
        let b = m("B", 1010, 10)
        XCTAssertEqual(PeakLayout.priorityOrder([a, b], observerAltitudeM: 0).map(\.mountain.name), ["B", "A"])
        // 前回選んだ A は、わずかな差なら B より先に残る。
        XCTAssertEqual(PeakLayout.priorityOrder([a, b], observerAltitudeM: 0, keptIds: [a.mountain.osmId]).map(\.mountain.name), ["A", "B"])
    }

    private let square = ScreenBox(left: -10, top: -10, right: 10, bottom: 10)

    func testClearanceIsFarthestCorner() {
        XCTAssertEqual(PeakLayout.clearance(square, square), hypot(20, 20), accuracy: 1e-9)
        // 山名が下に長く出る範囲どうしでも、縦横それぞれの最大のずれで決まる。
        let label = ScreenBox(left: -30, top: -20, right: 30, bottom: 15)
        let narrow = ScreenBox(left: -5, top: -10, right: 5, bottom: 10)
        XCTAssertEqual(PeakLayout.clearance(label, narrow), hypot(35, 30), accuracy: 1e-9)
    }

    func testSelectAroundDropsPeaksThatOverlapAtSomeHeading() {
        let positions = ["A": PlanOffset(x: 0, y: 0), "B": PlanOffset(x: 25, y: 0), "C": PlanOffset(x: 0, y: 30)]
        // B は今の向きでは A と重ならないが、45° 回すと重なるので捨てる。
        XCTAssertEqual(PeakLayout.selectAround(["A", "B", "C"], position: { positions[$0]! }, box: { _ in square }), ["A", "C"])
        XCTAssertEqual(PeakLayout.selectAround(["A", "B", "C"], limit: 1, position: { positions[$0]! }, box: { _ in square }), ["A"])
    }

    func testSelectAroundKeepsPreviouslySelectedPairs() {
        let positions = ["A": PlanOffset(x: 0, y: 0), "B": PlanOffset(x: 27, y: 0)]
        func select(_ kept: Set<String>) -> [String] {
            PeakLayout.selectAround(["A", "B"], position: { positions[$0]! }, box: { _ in square }, keptBefore: { kept.contains($0) })
        }
        XCTAssertEqual(select([]), ["A"])
        // どちらも前回選んでいたら、少し近づいても両方残す。
        XCTAssertEqual(select(["A", "B"]), ["A", "B"])
        XCTAssertEqual(select(["A"]), ["A"])
    }

    func testSelectAroundStopsPullingItemsAtLimit() {
        var pulled: [Int] = []
        let items = (0..<10).lazy.map { i -> Int in pulled.append(i); return i }
        let placed = PeakLayout.selectAround(items, limit: 2, position: { PlanOffset(x: Double($0) * 100, y: 0) }, box: { _ in square })
        XCTAssertEqual(placed, [0, 1])
        // 上限に達したあとの項目は取り出さない(山名の計測を省くため)。
        XCTAssertEqual(pulled, [0, 1])
    }

    func testAroundLimitScalesWithArea() {
        XCTAssertEqual(PeakLayout.aroundLimit(maxPeaks: 40, reach: 100, viewArea: Double.pi * 100 * 100 / 2), 80)
        // 円が画面より狭くても、画面の上限より減らさない。
        XCTAssertEqual(PeakLayout.aroundLimit(maxPeaks: 40, reach: 10, viewArea: 10000), 40)
    }

    func testCapVisibleKeepsDrawnPeaks() {
        XCTAssertEqual(PeakLayout.capVisible(["A", "B", "C"], limit: 2) { _ in false }, ["A", "B"])
        // 前回描いた C は、優先度の高い B が入ってきても残る。順は優先順のまま。
        XCTAssertEqual(PeakLayout.capVisible(["A", "B", "C"], limit: 2) { $0 != "B" }, ["A", "C"])
        XCTAssertEqual(PeakLayout.capVisible(["A", "B", "C"], limit: 5) { _ in false }, ["A", "B", "C"])
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
