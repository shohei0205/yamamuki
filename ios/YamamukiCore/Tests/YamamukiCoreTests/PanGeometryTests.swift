import XCTest
@testable import YamamukiCore

final class PanGeometryTests: XCTestCase {
    private let tokyo = MapCenter(35.696, 139.814)
    /// 高さ 800pt の画面で 1km = 10pt になる表示範囲。
    private let range800 = (800 - DialGeometry.chartInset) / 10

    func testReturnPathIsStraightOnScreenWhileHeadingAndGpsChange() {
        for offset in [PlanOffset(x: 12, y: -8), PlanOffset(x: -30, y: 20), PlanOffset(x: 0, y: 0)] {
            for targetHeading in [10.0, 90.0, 180.0, 270.0] {
                for t in [0.0, 0.25, 0.5, 0.75, 1.0] {
                    let fraction = t * t * (3 - 2 * t)
                    // 復帰中に GPS の現在地が少しずつ動いても、双眼鏡は画面上の直線に沿って戻る。
                    let observer = MapCenter(tokyo.latitude + 0.004 * t, tokyo.longitude + 0.006 * t)
                    let heading = PanGeometry.returnHeading(350, target: targetHeading, fraction: fraction)
                    let viewport = PanGeometry.returnViewport(observer, initialOffset: offset, heading: heading, fraction: fraction)
                    let actual = PanGeometry.observerOffset(observer, viewport: viewport, heading: heading)
                    XCTAssertEqual(actual.x, offset.x * (1 - fraction), accuracy: 1e-7)
                    XCTAssertEqual(actual.y, offset.y * (1 - fraction), accuracy: 1e-7)
                    if t == 1 { XCTAssertEqual(observer, viewport) }
                }
            }
        }
    }

    func testReturnHeadingCrossesNorthTheShortWay() {
        XCTAssertEqual(PanGeometry.returnHeading(350, target: 10, fraction: 0.5), 0, accuracy: 1e-8)
        XCTAssertEqual(PanGeometry.returnHeading(10, target: 310, fraction: 0.5), 340, accuracy: 1e-8)
        XCTAssertEqual(PanGeometry.returnHeading(350, target: 10, fraction: 0), 350, accuracy: 1e-8)
        XCTAssertEqual(PanGeometry.returnHeading(350, target: 10, fraction: 1), 10, accuracy: 1e-8)
    }

    func testHeadingDragKeepsChosenPivotAcrossNorthAndRepeatedUpdates() {
        for aroundCenter in [false, true] {
            var viewport = PanGeometry.drag(tokyo, dx: -100, dy: -100, scale: 10, heading: 359)
            var heading = 359.0
            let pivot = aroundCenter ? PlanOffset(x: 0, y: DialGeometry.originBottom - 800 / 2) : screen(tokyo, viewport: viewport, heading: heading, scale: 10)
            let landmark = aroundCenter ? PanGeometry.transformViewport(tokyo, viewport: viewport,
                previous: pivot, midpoint: PlanOffset(x: 0, y: 0), oldScale: 10, newScale: 10,
                oldHeading: heading, newHeading: heading) : tokyo
            for dx in [-12.0, -60.0, 180.0, -360.0] {
                let nextHeading = DialGeometry.swipedHeading(heading, dx: dx, width: 360)
                viewport = PanGeometry.rotateViewport(tokyo, viewport: viewport, heading: heading, nextHeading: nextHeading,
                    rangeKm: range800, canvasHeight: 800, aroundCenter: aroundCenter)
                heading = nextHeading
                let actual = screen(landmark, viewport: viewport, heading: heading, scale: 10)
                XCTAssertEqual(actual.x, pivot.x, accuracy: 1e-6)
                XCTAssertEqual(actual.y, pivot.y, accuracy: 1e-6)
            }
        }
    }

    func testVisibleBinocularsStayFixedEvenAfterPanning() {
        for heading in [45.0, 90.0, 270.0, 359.0] {
            let viewport = PanGeometry.drag(tokyo, dx: -100, dy: -100, scale: 10, heading: heading)
            XCTAssertTrue(PanGeometry.isObserverVisible(tokyo, viewport: viewport, heading: heading,
                rangeKm: range800, canvasWidth: 360, canvasHeight: 800))
            let before = screen(tokyo, viewport: viewport, heading: heading, scale: 10)
            for progress in [0.0, 0.25, 0.5, 0.75, 1.0] {
                let next = PanGeometry.northUpViewport(tokyo, viewport: viewport, heading: heading,
                    rangeKm: range800, canvasHeight: 800, progress: progress, aroundCenter: false)
                let after = screen(tokyo, viewport: next, heading: PanGeometry.northUpHeading(heading, progress: progress), scale: 10)
                XCTAssertEqual(before.x, after.x, accuracy: 1e-6)
                XCTAssertEqual(before.y, after.y, accuracy: 1e-6)
            }
            for (dx, dy) in [(300.0, -100.0), (-300.0, -100.0), (0.0, -750.0), (0.0, 100.0)] {
                let outside = PanGeometry.drag(tokyo, dx: dx, dy: dy, scale: 10, heading: heading)
                XCTAssertFalse(PanGeometry.isObserverVisible(tokyo, viewport: outside, heading: heading,
                    rangeKm: range800, canvasWidth: 360, canvasHeight: 800))
            }
        }
    }

    func testNorthUpKeepsScreenCenterAfterPanningAtDifferentSizesAndRanges() {
        let panned = PanGeometry.drag(tokyo, dx: 100, dy: -50, scale: 10, heading: 30)
        for viewport in [tokyo, panned] {
            for height in [480.0, 900.0] {
                for range in [10.0, 80.0] {
                    for heading in [0.0, 45.0, 90.0, 180.0, 359.0] {
                        let scale = (height - DialGeometry.chartInset) / range
                        let pivotY = DialGeometry.originBottom - height / 2
                        let center = DialGeometry.project(
                            distanceKm: GeoMath.distanceKm(tokyo.latitude, tokyo.longitude, viewport.latitude, viewport.longitude),
                            bearingDeg: GeoMath.bearingDeg(tokyo.latitude, tokyo.longitude, viewport.latitude, viewport.longitude),
                            headingDeg: 0)
                        let angle = heading * .pi / 180
                        let east = -pivotY * sin(angle) / scale
                        let north = -pivotY * cos(angle) / scale
                        let landmark = PanGeometry.drag(tokyo, dx: -(center.x + east), dy: center.y + north, scale: 1, heading: 0)
                        let before = screen(landmark, viewport: viewport, heading: heading, scale: scale)
                        XCTAssertEqual(before.x, 0, accuracy: 1e-6)
                        XCTAssertEqual(before.y, pivotY, accuracy: 1e-6)
                        for progress in [0.0, 0.25, 0.5, 0.75, 1.0] {
                            let animated = PanGeometry.northUpViewport(tokyo, viewport: viewport, heading: heading,
                                rangeKm: range, canvasHeight: height, progress: progress)
                            let angle = PanGeometry.northUpHeading(heading, progress: progress)
                            let position = screen(landmark, viewport: animated, heading: angle, scale: scale)
                            XCTAssertEqual(position.x, before.x, accuracy: 1e-6)
                            XCTAssertEqual(position.y, before.y, accuracy: 1e-6)
                            let normal = PanGeometry.northUpViewport(tokyo, viewport: tokyo, heading: heading,
                                rangeKm: range, canvasHeight: height, progress: progress, aroundCenter: false)
                            XCTAssertEqual(normal, tokyo)
                            let binoculars = screen(tokyo, viewport: normal, heading: angle, scale: scale)
                            XCTAssertEqual(binoculars.x, 0, accuracy: 1e-6)
                            XCTAssertEqual(binoculars.y, 0, accuracy: 1e-6)
                        }
                        let next = PanGeometry.northUpViewport(tokyo, viewport: viewport, heading: heading, rangeKm: range, canvasHeight: height)
                        let after = screen(landmark, viewport: next, heading: 0, scale: scale)
                        XCTAssertEqual(after.x, before.x, accuracy: 1e-6)
                        XCTAssertEqual(after.y, before.y, accuracy: 1e-6)
                        XCTAssertEqual(next, PanGeometry.northUpViewport(tokyo, viewport: next, heading: 0, rangeKm: range, canvasHeight: height))
                    }
                }
            }
        }
    }

    private func screen(_ point: MapCenter, viewport: MapCenter, heading: Double, scale: Double) -> PlanOffset {
        let p = DialGeometry.project(
            distanceKm: GeoMath.distanceKm(tokyo.latitude, tokyo.longitude, point.latitude, point.longitude),
            bearingDeg: GeoMath.bearingDeg(tokyo.latitude, tokyo.longitude, point.latitude, point.longitude),
            headingDeg: heading)
        let offset = PanGeometry.observerOffset(tokyo, viewport: viewport, heading: heading)
        return PlanOffset(x: (p.x + offset.x) * scale, y: -(p.y + offset.y) * scale)
    }

    func testNorthUpAnimationUsesShortestTurnAndStopsExactlyAtNorth() {
        XCTAssertEqual(PanGeometry.northUpHeading(90, progress: 0.5), 45, accuracy: 1e-8)
        XCTAssertEqual(PanGeometry.northUpHeading(270, progress: 0.5), 315, accuracy: 1e-8)
        XCTAssertEqual(PanGeometry.northUpHeading(359, progress: 0.5), 359.5, accuracy: 1e-8)
        XCTAssertEqual(PanGeometry.northUpHeading(90, progress: 0), 90)
        XCTAssertEqual(PanGeometry.northUpHeading(359, progress: 1), 0)
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

    func testCompassTapAlternatesBetweenNorthUpAndFollowing() {
        // 北が上なら端末の向きに合わせる。
        XCTAssertTrue(PanGeometry.compassTapFollows(heading: 0, following: false))
        XCTAssertTrue(PanGeometry.compassTapFollows(heading: 359.8, following: false))
        // 端末の向きに合わせている間や、回した後は北を上にする。
        XCTAssertFalse(PanGeometry.compassTapFollows(heading: 0, following: true))
        XCTAssertFalse(PanGeometry.compassTapFollows(heading: 45, following: false))
        XCTAssertFalse(PanGeometry.compassTapFollows(heading: 359, following: false))
    }

    func testRotationSlopIgnoresSmallTwistsUntilThreshold() {
        var slop = RotationSlop(thresholdDeg: 15)
        XCTAssertEqual(slop.consume(5), 0)
        XCTAssertEqual(slop.consume(-3), 0)
        XCTAssertEqual(slop.consume(10), 0)
        XCTAssertFalse(slop.rotating)
        // 合計が 15° を超えたところで回し始め、その後の変化はそのまま返す。
        XCTAssertEqual(slop.consume(4), 0)
        XCTAssertTrue(slop.rotating)
        XCTAssertEqual(slop.consume(2), 2)
        XCTAssertEqual(slop.consume(-1.5), -1.5)
        XCTAssertEqual(slop.consume(.nan), 0)
    }

    func testRotationSlopCountsBothDirections() {
        var slop = RotationSlop(thresholdDeg: 15)
        XCTAssertEqual(slop.consume(-16), 0)
        XCTAssertTrue(slop.rotating)
        XCTAssertEqual(slop.consume(-1), -1)
    }
}
