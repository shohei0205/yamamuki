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

    /// 名前の 1 文字目が同じ山を同じ山塊とし、名前の数字を標高とみなす。
    private func selectAround(_ items: [String], _ positions: [String: PlanOffset], kept: Set<String> = []) -> [String] {
        PeakLayout.selectAround(items, position: { positions[$0]! }, box: { _ in square },
            neighbors: { $0.first == $1.first }, higher: { Int($0.dropFirst())! > Int($1.dropFirst())! },
            keptBefore: { kept.contains($0) })
    }

    func testSelectAroundKeepsMainPeakOfNeighbors() {
        // A2 は今の向きでは A1 と重ならないが、45° 回すと重なる。同じ山塊なので標高の高い A2 だけを残す。
        let positions = ["A1": PlanOffset(x: 0, y: 0), "A2": PlanOffset(x: 25, y: 0), "A3": PlanOffset(x: 0, y: 100)]
        XCTAssertEqual(selectAround(["A1", "A2", "A3"], positions), ["A2", "A3"])
        XCTAssertEqual(selectAround(["A2", "A1", "A3"], positions), ["A2", "A3"])
    }

    func testSelectAroundKeepsPeaksOfOtherMassifs() {
        // 別の山塊の山は、重なりうる位置でもここでは省かない(描くときに今の向きで判定する)。
        let positions = ["A1": PlanOffset(x: 0, y: 0), "B1": PlanOffset(x: 5, y: 0)]
        XCTAssertEqual(selectAround(["A1", "B1"], positions), ["A1", "B1"])
    }

    func testSelectAroundKeepsPreviouslySelectedPairs() {
        let positions = ["A2": PlanOffset(x: 0, y: 0), "A1": PlanOffset(x: 27, y: 0)]
        XCTAssertEqual(selectAround(["A2", "A1"], positions), ["A2"])
        // どちらも前回選んでいたら、少し近づいても両方残す。
        XCTAssertEqual(selectAround(["A2", "A1"], positions, kept: ["A2", "A1"]), ["A2", "A1"])
        XCTAssertEqual(selectAround(["A2", "A1"], positions, kept: ["A2"]), ["A2"])
    }

    func testSelectAroundStopsPullingItemsAtLimit() {
        var pulled: [Int] = []
        let items = (0..<10).lazy.map { i -> Int in pulled.append(i); return i }
        let placed = PeakLayout.selectAround(items, limit: 2, position: { PlanOffset(x: Double($0) * 100, y: 0) }, box: { _ in square },
            neighbors: { _, _ in true }, higher: { _, _ in false })
        XCTAssertEqual(placed, [0, 1])
        // 上限に達したあとの項目は取り出さない(山名の計測を省くため)。
        XCTAssertEqual(pulled, [0, 1])
    }

    func testNeighborsAndHeight() {
        func at(_ name: String, _ lat: Double, _ ele: Double?) -> NearbyMountain {
            NearbyMountain(mountain: Mountain(osmId: Int64(name.hashValue), name: name, latitude: lat, longitude: 137, elevationM: ele), distanceKm: 5, bearingDeg: 0)
        }
        let oku = at("奥穂高岳", 36.2894, 3190)
        let jandarme = at("ジャンダルム", 36.2862, 3163)
        let far = at("遠い山", 36.40, 3000)
        XCTAssertTrue(PeakLayout.areNeighbors(oku, jandarme))
        XCTAssertFalse(PeakLayout.areNeighbors(oku, far))
        XCTAssertTrue(PeakLayout.isHigher(oku, jandarme))
        XCTAssertFalse(PeakLayout.isHigher(jandarme, oku))
        XCTAssertTrue(PeakLayout.isHigher(jandarme, at("不明", 36.2862, nil)))
    }

    func testAroundLimitScalesWithArea() {
        XCTAssertEqual(PeakLayout.aroundLimit(maxPeaks: 40, reach: 100, viewArea: Double.pi * 100 * 100 / 2), 80)
        // 円が画面より狭くても、画面の上限より減らさない。
        XCTAssertEqual(PeakLayout.aroundLimit(maxPeaks: 40, reach: 10, viewArea: 10000), 40)
    }

    private func boxAt(_ x: Double) -> ScreenBox { ScreenBox(left: x, top: 0, right: x + 10, bottom: 10) }

    func testPlaceVisibleDropsOverlapsAtCurrentHeading() {
        let boxes = ["A": boxAt(0), "B": boxAt(5), "C": boxAt(20)]
        XCTAssertEqual(PeakLayout.placeVisible(["A", "B", "C"], limit: 10, box: { boxes[$0]! }, drawnBefore: { _ in false }), ["A", "C"])
        XCTAssertEqual(PeakLayout.placeVisible(["A", "B", "C"], limit: 1, box: { boxes[$0]! }, drawnBefore: { _ in false }), ["A"])
    }

    func testPlaceVisibleKeepsDrawnPeaks() {
        let boxes = ["A": boxAt(0), "B": boxAt(5), "C": boxAt(40)]
        // 前回描いた B は、優先度の高い A が入ってきても残る。順は優先順のまま。
        XCTAssertEqual(PeakLayout.placeVisible(["A", "B", "C"], limit: 10, box: { boxes[$0]! }, drawnBefore: { $0 != "A" }), ["B", "C"])
        // 上限に達しているときも、前回描いた山を先に残す。
        XCTAssertEqual(PeakLayout.placeVisible(["A", "C"], limit: 1, box: { boxes[$0]! }, drawnBefore: { $0 == "C" }), ["C"])
    }

    func testPlaceVisibleNeedsMarginForNewPeaks() {
        let boxes = ["A": boxAt(0), "B": boxAt(12)]
        // 2pt しか離れていない B は、新しく出すときは余白 4pt に足りないので出さないが、描いているなら残す。
        XCTAssertEqual(PeakLayout.placeVisible(["A", "B"], limit: 10, box: { boxes[$0]! }, drawnBefore: { $0 == "A" }, margin: 4), ["A"])
        XCTAssertEqual(PeakLayout.placeVisible(["A", "B"], limit: 10, box: { boxes[$0]! }, drawnBefore: { _ in true }, margin: 4), ["A", "B"])
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
