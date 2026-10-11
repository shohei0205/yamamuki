import XCTest
@testable import YamamukiCore

final class MountainTextTests: XCTestCase {
    private let fuji = Mountain(osmId: 1, name: "富士山", latitude: 35.3605556, longitude: 138.7273889, elevationM: 3776.24)

    private func variant(id: Int64 = 1, lat: Double? = nil, lon: Double? = nil, ele: Double?) -> Mountain {
        Mountain(osmId: id, name: fuji.name, latitude: lat ?? fuji.latitude, longitude: lon ?? fuji.longitude, elevationM: ele)
    }

    func testElevation() {
        XCTAssertEqual(fuji.elevationText, "3,776 m")
        XCTAssertNil(variant(ele: nil).elevationText)
        XCTAssertEqual(elevationText(851.6), "852 m")
        XCTAssertNil(elevationText(nil))
    }

    func testCoordinate() {
        XCTAssertEqual(degreeText(fuji.latitude), "35.36056°")
        XCTAssertEqual(degreeText(fuji.longitude), "138.72739°")
        XCTAssertEqual(degreeText(-33.8688), "33.86880°")
        XCTAssertEqual(degreeText(-151.2093), "151.20930°")
    }

    func testDistance() {
        XCTAssertEqual(distanceText(0.8504), "850 m")
        XCTAssertEqual(distanceText(12.34), "12.3 km")
        XCTAssertEqual(distanceText(1234.56), "1,234.6 km")
    }

    func testMinElevationFilter() {
        XCTAssertTrue(fuji.meetsMinElevation(0))
        XCTAssertTrue(variant(ele: nil).meetsMinElevation(0))
        XCTAssertTrue(fuji.meetsMinElevation(3776))
        XCTAssertFalse(fuji.meetsMinElevation(3800))
        XCTAssertFalse(variant(ele: nil).meetsMinElevation(100))
    }

    func testByteSize() {
        XCTAssertEqual(byteSizeText(512), "512 B")
        XCTAssertEqual(byteSizeText(820 * 1024), "820 KB")
        XCTAssertEqual(byteSizeText(1_363_149), "1.3 MB")
    }

    func testSummitIsNearestWithinRadius() {
        func near(_ id: Int64, _ km: Double) -> NearbyMountain {
            NearbyMountain(mountain: variant(id: id, ele: 3776), distanceKm: km, bearingDeg: 0)
        }
        XCTAssertNil(summitAt([]))
        XCTAssertNil(summitAt([near(1, 0.031), near(2, 3.0)]))
        XCTAssertEqual(summitAt([near(1, 0.025), near(2, 0.01), near(3, 5.0)])?.mountain.osmId, 2)
        XCTAssertEqual(summitAt([near(1, 0.03)])?.mountain.osmId, 1)
    }
}
