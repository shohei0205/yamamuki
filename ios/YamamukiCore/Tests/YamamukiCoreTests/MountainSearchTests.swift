import XCTest
@testable import YamamukiCore

final class MountainSearchTests: XCTestCase {
    private let yari = Mountain(osmId: 1, name: "槍ヶ岳", latitude: 36.3420, longitude: 137.6476, elevationM: 3180, nameReading: "やりがたけ")
    private let fuji = Mountain(osmId: 2, name: "富士山", latitude: 35.3606, longitude: 138.7274, elevationM: 3776, nameReading: "ふじさん")
    private let koFuji = Mountain(osmId: 3, name: "小富士", latitude: 35.3712, longitude: 138.7639, elevationM: 1979, nameReading: "こふじ")
    private let mitsutoge = Mountain(osmId: 4, name: "三ツ峠山", latitude: 35.5469, longitude: 138.8133, elevationM: 1785, aliases: ["開運山"])
    private let unknown = Mountain(osmId: 5, name: "富士見台", latitude: 35.4000, longitude: 137.6000, elevationM: nil)
    private var search: MountainSearch { MountainSearch([yari, fuji, koFuji, mitsutoge, unknown]) }

    func testMatchesNameReadingAndAliases() {
        XCTAssertEqual(search.search("槍", latitude: nil, longitude: nil), [yari])
        XCTAssertEqual(search.search("やりが", latitude: nil, longitude: nil), [yari])
        XCTAssertEqual(search.search("開運山", latitude: nil, longitude: nil), [mitsutoge])
    }

    func testIgnoresKanaWidthCaseAndSpaces() {
        XCTAssertEqual(search.search("ヤリガタケ", latitude: nil, longitude: nil), [yari])
        XCTAssertEqual(search.search("ﾔﾘｶﾞﾀｹ", latitude: nil, longitude: nil), [yari])
        XCTAssertEqual(search.search("槍ケ岳", latitude: nil, longitude: nil), [yari])
        XCTAssertEqual(search.search("三ッ峠", latitude: nil, longitude: nil), [mitsutoge])
        XCTAssertEqual(search.search(" ふじ さん ", latitude: nil, longitude: nil), [fuji])
    }

    /// 河口湖駅のあたりから。小富士 → 富士山 → 富士見台の順に近い。
    func testSortsByDistanceFromGivenPoint() {
        XCTAssertEqual(search.search("富士", latitude: 35.4986, longitude: 138.7690), [koFuji, fuji, unknown])
    }

    func testSortsByElevationWithoutPoint() {
        XCTAssertEqual(search.search("富士", latitude: nil, longitude: nil), [fuji, koFuji, unknown])
    }

    func testLimitsResultsAndIgnoresBlankQuery() {
        XCTAssertEqual(search.search("富士", latitude: nil, longitude: nil, limit: 1), [fuji])
        XCTAssertEqual(search.search("  ", latitude: nil, longitude: nil), [])
        XCTAssertEqual(search.search("穂高", latitude: nil, longitude: nil), [])
    }

    func testNormalizesKana() {
        XCTAssertEqual(MountainSearch.normalize("ヤリガタケ"), "やりがたけ")
        XCTAssertEqual(MountainSearch.normalize("槍ヶ岳"), "槍け岳")
        XCTAssertEqual(MountainSearch.normalize("ＡＢ C"), "abc")
    }
}
