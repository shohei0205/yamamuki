import XCTest
@testable import YamamukiCore

final class HeadingTests: XCTestCase {
    func testDeltaTakesShortestWay() {
        XCTAssertEqual(Heading.delta(359, 1), 2, accuracy: 1e-9)
        XCTAssertEqual(Heading.delta(1, 359), -2, accuracy: 1e-9)
        XCTAssertEqual(Heading.delta(0, 180), -180, accuracy: 1e-9)
    }

    func testDirectionIndexes() {
        XCTAssertEqual(Heading.directionIndex(0), 0)
        XCTAssertEqual(Heading.directionIndex(355), 0)
        XCTAssertEqual(Heading.directionIndex(20), 1)
        XCTAssertEqual(Heading.directionIndex(135), 6)
        XCTAssertEqual(Heading.directionIndex(-90), 12)
        XCTAssertEqual(Heading.directionIndex(340), 15)
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

    func testCandidatesAreLimitedByReachAndCount() {
        let all = [m("遠い", 3000, 30), m("低い", 100, 3), m("近い", 1000, 2)]
        XCTAssertEqual(PeakLayout.candidates(all, observerAltitudeM: 0, keptIds: [], reachKm: 10, limit: 5).map(\.mountain.name), ["近い", "低い"])
        XCTAssertEqual(PeakLayout.candidates(all, observerAltitudeM: 0, keptIds: [], reachKm: 50, limit: 1).map(\.mountain.name), ["近い"])
    }

    func testNeighbors() {
        func at(_ name: String, _ lat: Double) -> NearbyMountain {
            NearbyMountain(mountain: Mountain(osmId: Int64(name.hashValue), name: name, latitude: lat, longitude: 137, elevationM: 1000), distanceKm: 5, bearingDeg: 0)
        }
        XCTAssertTrue(PeakLayout.areNeighbors(at("奥穂高岳", 36.2894), at("ジャンダルム", 36.2862)))
        XCTAssertFalse(PeakLayout.areNeighbors(at("奥穂高岳", 36.2894), at("遠い山", 36.40)))
    }

    func testAroundLimitScalesWithArea() {
        XCTAssertEqual(PeakLayout.aroundLimit(maxPeaks: 40, reach: 100, viewArea: Double.pi * 100 * 100 / 2), 80)
        // 円が画面より狭くても、画面の上限より減らさない。
        XCTAssertEqual(PeakLayout.aroundLimit(maxPeaks: 40, reach: 10, viewArea: 10000), 40)
    }

    private struct Group: Equatable {
        let peak: String
        let members: [String]
        init(_ peak: String, _ members: [String] = []) {
            self.peak = peak
            self.members = members
        }
    }

    /// 名前の 1 文字目が同じ山を同じ山塊とし、名前の数字を標高とみなす。箱は x の位置に幅 10。
    private func place(_ visible: [String], _ xs: [String: Double], limit: Int = 10, drawn: Set<String> = [], margin: Double = 0) -> [Group] {
        PeakLayout.placeVisible(
            visible, limit: limit,
            box: { ScreenBox(left: xs[$0]!, top: 0, right: xs[$0]! + 10, bottom: 10) },
            drawnBefore: { drawn.contains($0) },
            neighbors: { $0.first == $1.first },
            elevationM: { Double($0.dropFirst()) },
            margin: margin
        ).map { Group($0.peak, $0.members) }
    }

    func testPlaceVisibleGroupsOverlapsAtCurrentHeading() {
        let xs = ["A1": 0.0, "B1": 5, "C1": 20]
        // B1 は A1 と重なるので山名を省き、A1 にまとめる。
        XCTAssertEqual(place(["A1", "B1", "C1"], xs), [Group("A1", ["B1"]), Group("C1")])
        // 上限で省いた C1 は、どの山とも重ならないのでまとめない。
        XCTAssertEqual(place(["A1", "B1", "C1"], xs, limit: 1), [Group("A1", ["B1"])])
    }

    func testPlaceVisibleKeepsDrawnPeaks() {
        let xs = ["A1": 0.0, "B1": 5, "C1": 40]
        // 前回描いた B1 は、優先度の高い A1 が入ってきても残る。順は優先順のまま。
        XCTAssertEqual(place(["A1", "B1", "C1"], xs, drawn: ["B1", "C1"]), [Group("B1", ["A1"]), Group("C1")])
        // 上限に達しているときも、前回描いた山を先に残す。
        XCTAssertEqual(place(["A1", "C1"], xs, limit: 1, drawn: ["C1"]), [Group("C1")])
    }

    func testPlaceVisibleNeedsMarginForNewPeaks() {
        let xs = ["A1": 0.0, "B1": 12]
        // 2pt しか離れていない B1 は、新しく出すときは余白 4pt に足りないので山名を出さないが、描いているなら残す。
        XCTAssertEqual(place(["A1", "B1"], xs, drawn: ["A1"], margin: 4), [Group("A1", ["B1"])])
        XCTAssertEqual(place(["A1", "B1"], xs, drawn: ["A1", "B1"], margin: 4), [Group("A1"), Group("B1")])
    }

    func testPlaceVisiblePrefersHigherNeighbor() {
        let xs = ["A2900": 0.0, "A3190": 5]
        // 仰角で先に来る A2900 が描いてあっても、そばの高い A3190 と重なるなら A3190 を出す。
        XCTAssertEqual(place(["A2900", "A3190"], xs, drawn: ["A2900"]), [Group("A3190", ["A2900"])])
    }

    func testPlaceVisibleResolvesChainOfNeighborsByHeight() {
        // A1 < A2 < A3 が鎖のように並び、A1 と A2、A2 と A3 は重なるが、A1 と A3 は重ならない。
        // 並ぶ順によらず、A3 を残して A2 を省き、A2 が省かれたので A1 は残す。
        let xs = ["A1": 0.0, "A2": 8, "A3": 16]
        XCTAssertEqual(place(["A1", "A2", "A3"], xs), [Group("A1"), Group("A3", ["A2"])])
        XCTAssertEqual(place(["A3", "A2", "A1"], xs), [Group("A3", ["A2"]), Group("A1")])
    }

    func testPlaceVisibleDoesNotLetOtherMassifsDominate() {
        // 別の山塊の高い山は、重なっても優先しない(優先順で先に置いた山が残る)。
        let xs = ["A1000": 0.0, "B3000": 5]
        XCTAssertEqual(place(["A1000", "B3000"], xs), [Group("A1000", ["B3000"])])
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
