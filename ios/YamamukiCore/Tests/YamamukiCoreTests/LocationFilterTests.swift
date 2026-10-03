import XCTest
@testable import YamamukiCore

final class LocationFilterTests: XCTestCase {
    func testAcceptsFirstLocation() {
        XCTAssertTrue(LocationFilter().accept(timeMs: 0, accuracyM: 800))
    }

    func testRejectsUnknownAccuracy() {
        let filter = LocationFilter()
        XCTAssertFalse(filter.accept(timeMs: 0, accuracyM: nil))
        XCTAssertFalse(filter.accept(timeMs: 0, accuracyM: -1))
        XCTAssertFalse(filter.accept(timeMs: 0, accuracyM: .nan))
    }

    func testRejectsCoarseLocationBetweenGpsFixes() {
        // GPS(誤差 10m) の合間に届いたネットワーク位置(誤差 800m)は捨てる。
        let filter = LocationFilter()
        XCTAssertTrue(filter.accept(timeMs: 0, accuracyM: 10))
        XCTAssertFalse(filter.accept(timeMs: 5_000, accuracyM: 800))
        XCTAssertTrue(filter.accept(timeMs: 10_000, accuracyM: 12))
    }

    func testAcceptsSlightlyWorseAccuracy() {
        let filter = LocationFilter()
        XCTAssertTrue(filter.accept(timeMs: 0, accuracyM: 10))
        XCTAssertTrue(filter.accept(timeMs: 5_000, accuracyM: 60))
    }

    func testAcceptsBetterAccuracy() {
        let filter = LocationFilter()
        XCTAssertTrue(filter.accept(timeMs: 0, accuracyM: 800))
        XCTAssertTrue(filter.accept(timeMs: 5_000, accuracyM: 10))
    }

    func testAcceptsCoarseLocationWhenGoodOnesStop() {
        // 良い位置が 1 分届かなければ、粗い位置でも使い、以降はそれを基準にする。
        let filter = LocationFilter()
        XCTAssertTrue(filter.accept(timeMs: 0, accuracyM: 10))
        XCTAssertFalse(filter.accept(timeMs: 30_000, accuracyM: 800))
        XCTAssertTrue(filter.accept(timeMs: 60_000, accuracyM: 800))
        XCTAssertTrue(filter.accept(timeMs: 65_000, accuracyM: 700))
    }

    func testOldLastKnownLocationDoesNotBlockNewFixes() {
        // 起動直後に使った古い位置(1 時間前、誤差 5m)の後でも、今の粗い位置を使う。
        let filter = LocationFilter()
        XCTAssertTrue(filter.accept(timeMs: 0, accuracyM: 5))
        XCTAssertTrue(filter.accept(timeMs: 3_600_000, accuracyM: 300))
    }

    func testRejectsOlderOrSameTime() {
        let filter = LocationFilter()
        XCTAssertTrue(filter.accept(timeMs: 10_000, accuracyM: 10))
        XCTAssertFalse(filter.accept(timeMs: 10_000, accuracyM: 5))
        XCTAssertFalse(filter.accept(timeMs: 5_000, accuracyM: 5))
    }
}
