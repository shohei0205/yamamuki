import XCTest
@testable import YamamukiCore

final class HeaderSceneryTests: XCTestCase {
    func testHeaderFitsSmallBannerAndChartStartsBelowTape() {
        XCTAssertGreaterThanOrEqual(DialGeometry.headerHeight, 50)
        XCTAssertEqual(HeaderScenery.height, DialGeometry.headerHeight + DialGeometry.tapeHeight)
        XCTAssertGreaterThan(DialGeometry.chartTop, HeaderScenery.height)
    }

    func testRidgesReachTheRightEdgeOnAnyWidth() {
        for layer in HeaderScenery.Layer.allCases {
            for width in [0.0, 320, 360, 361, 800, 1280] {
                let ridge = HeaderScenery.ridge(layer, width: width)
                XCTAssertGreaterThanOrEqual(ridge.segments.last!.endX, width, "\(layer) \(width)")
                XCTAssertEqual(ridge.segments.first!.controlX - ridge.segments.first!.endX / 2, 0, "\(layer) starts at x = 0")
                for (a, b) in zip(ridge.segments, ridge.segments.dropFirst()) { XCTAssertLessThan(a.endX, b.endX) }
            }
        }
    }

    func testRepeatedPatternJoinsWithoutStep() {
        for layer in HeaderScenery.Layer.allCases {
            let ridge = HeaderScenery.ridge(layer, width: 1000)
            let joins = ridge.segments.filter { $0.endX.truncatingRemainder(dividingBy: HeaderScenery.period) == 0 }
            XCTAssertGreaterThanOrEqual(joins.count, 2)
            for join in joins { XCTAssertEqual(join.endY, ridge.startY, "\(layer) at \(join.endX)") }
        }
    }

    func testRidgesAndPeakStayInsideTheScenery() {
        for layer in HeaderScenery.Layer.allCases {
            let ridge = HeaderScenery.ridge(layer, width: 360)
            let ys = [ridge.startY] + ridge.segments.flatMap { [$0.controlY, $0.endY] }
            XCTAssertTrue(ys.allSatisfy { $0 > 0 && $0 < HeaderScenery.height }, "\(layer)")
        }
        XCTAssertGreaterThan(HeaderScenery.peakTopY - HeaderScenery.crestRise, 0)
        XCTAssertLessThan(HeaderScenery.fadeTop, HeaderScenery.footY)
        XCTAssertLessThan(HeaderScenery.footY, HeaderScenery.height)
        XCTAssertEqual(HeaderScenery.peakCenterX(width: 360), 230.4, accuracy: 1e-9)
    }
}
