import XCTest
@testable import YamamukiCore

final class MountainSearchTests: XCTestCase {
    private let yari = Mountain(id: "osm:1", name: "槍ヶ岳", latitude: 36.3420, longitude: 137.6476, elevationM: 3180, nameReading: "やりがたけ")
    private let fuji = Mountain(id: "osm:2", name: "富士山", latitude: 35.3606, longitude: 138.7274, elevationM: 3776, nameReading: "ふじさん")
    private let koFuji = Mountain(id: "osm:3", name: "小富士", latitude: 35.3712, longitude: 138.7639, elevationM: 1979, nameReading: "こふじ")
    private let mitsutoge = Mountain(id: "osm:4", name: "三ツ峠山", latitude: 35.5469, longitude: 138.8133, elevationM: 1785, aliases: ["開運山"])
    private let unknown = Mountain(id: "osm:5", name: "富士見台", latitude: 35.4000, longitude: 137.6000, elevationM: nil)
    private var search: MountainSearch { MountainSearch([yari, fuji, koFuji, mitsutoge, unknown]) }

    func testMatchesNameReadingAndAliases() {
        XCTAssertEqual(search.search("槍", latitude: nil, longitude: nil).all, [yari])
        XCTAssertEqual(search.search("やりが", latitude: nil, longitude: nil).all, [yari])
        XCTAssertEqual(search.search("開運山", latitude: nil, longitude: nil).all, [mitsutoge])
    }

    func testIgnoresKanaWidthCaseAndSpaces() {
        XCTAssertEqual(search.search("ヤリガタケ", latitude: nil, longitude: nil).all, [yari])
        XCTAssertEqual(search.search("ﾔﾘｶﾞﾀｹ", latitude: nil, longitude: nil).all, [yari])
        XCTAssertEqual(search.search("槍ケ岳", latitude: nil, longitude: nil).all, [yari])
        XCTAssertEqual(search.search("三ッ峠", latitude: nil, longitude: nil).all, [mitsutoge])
        XCTAssertEqual(search.search(" ふじ さん ", latitude: nil, longitude: nil).all, [fuji])
    }

    /// 河口湖駅のあたりから。小富士 → 富士山 → 富士見台の順に近い。「富士」で探すと、小富士は途中に含む山なので最後になる。
    func testSortsByDistanceFromGivenPoint() {
        XCTAssertEqual(search.search("富士", latitude: 35.4986, longitude: 138.7690).all, [fuji, unknown, koFuji])
    }

    func testSortsByElevationWithoutPoint() {
        XCTAssertEqual(search.search("富士", latitude: nil, longitude: nil).all, [fuji, unknown, koFuji])
    }

    func testLimitsResultsAndIgnoresBlankQuery() {
        XCTAssertEqual(search.search("富士", latitude: nil, longitude: nil, limit: 1).all, [fuji])
        XCTAssertEqual(search.search("  ", latitude: nil, longitude: nil).all, [])
        XCTAssertEqual(search.search("穂高", latitude: nil, longitude: nil).all, [])
    }

    /// 「やま」で探すと、近くの「〜山(やま)」より、先頭が一致した山を先に並べる。
    func testListsPrefixMatchesBeforePartialMatches() {
        let takayama = Mountain(id: "osm:6", name: "高山", latitude: 35.5000, longitude: 138.7700, elevationM: 1000, nameReading: "たかやま")
        let yamabushi = Mountain(id: "osm:7", name: "山伏", latitude: 35.2500, longitude: 138.3700, elevationM: 2014, nameReading: "やまぶし")
        let yamatai = Mountain(id: "osm:8", name: "山大岳", latitude: 36.0000, longitude: 137.0000, elevationM: 1500, nameReading: "やまたいだけ")
        let s = MountainSearch([takayama, yamatai, yamabushi])
        let results = s.search("やま", latitude: 35.4986, longitude: 138.7690)
        XCTAssertEqual(results.prefix, [yamabushi, yamatai])
        XCTAssertEqual(results.partial, [takayama])
        XCTAssertEqual(results.all, [yamabushi, yamatai, takayama])
        // 件数の上限は、先頭が一致した山から数える。
        XCTAssertEqual(s.search("やま", latitude: 35.4986, longitude: 138.7690, limit: 1), .init(prefix: [yamabushi], partial: []))
        XCTAssertEqual(s.search("やま", latitude: 35.4986, longitude: 138.7690, limit: 3), .init(prefix: [yamabushi, yamatai], partial: [takayama]))
    }

    /// 仮名か英数字 1 文字だけのときは、先頭が一致した山だけを返す(「や」で「〜山(やま)」を出さない)。
    /// 漢字 1 文字なら途中に含む山も返す。先頭が一致した山が無ければ、仮名や英数字 1 文字でも途中に含む山を返す。
    func testOmitsPartialMatchesForOneKanaOrAlphanumeric() {
        let takayama = Mountain(id: "osm:6", name: "高山", latitude: 35.5000, longitude: 138.7700, elevationM: 1000, nameReading: "たかやま")
        let peak1 = Mountain(id: "osm:9", name: "大平山 第1ピーク (Ohirayama Peak 1)", latitude: 36.0, longitude: 138.0, elevationM: 500)
        let s = MountainSearch([takayama, yari, peak1])
        // 先頭一致が無いときは、1 文字でも部分一致を返す。
        XCTAssertEqual(s.search("1", latitude: nil, longitude: nil), .init(prefix: [], partial: [peak1]))
        XCTAssertEqual(s.search("１", latitude: nil, longitude: nil), .init(prefix: [], partial: [peak1]))
        XCTAssertEqual(s.search("P", latitude: nil, longitude: nil), .init(prefix: [], partial: [peak1]))
        XCTAssertEqual(s.search("か", latitude: nil, longitude: nil), .init(prefix: [], partial: [takayama]))
        XCTAssertEqual(s.search("pe", latitude: nil, longitude: nil), .init(prefix: [], partial: [peak1]))
        XCTAssertEqual(s.search("や", latitude: nil, longitude: nil), .init(prefix: [yari], partial: []))
        XCTAssertEqual(s.search("ﾔ", latitude: nil, longitude: nil), .init(prefix: [yari], partial: []))
        XCTAssertEqual(s.search("高", latitude: nil, longitude: nil), .init(prefix: [takayama], partial: []))
        XCTAssertEqual(s.search("山", latitude: nil, longitude: nil), .init(prefix: [], partial: [takayama, peak1]))
        XCTAssertEqual(s.search("岳", latitude: nil, longitude: nil), .init(prefix: [], partial: [yari]))
    }

    func testOmitsPartialMatchesForOneAlphanumericWithPrefixMatch() {
        let peakX = Mountain(id: "osm:10", name: "Peak X", latitude: 35.0, longitude: 138.0, elevationM: 1200)
        let peak1 = Mountain(id: "osm:9", name: "大平山 第1ピーク (Ohirayama Peak 1)", latitude: 36.0, longitude: 138.0, elevationM: 500)
        let first = Mountain(id: "osm:11", name: "1峰", latitude: 35.0, longitude: 138.0, elevationM: 900)
        let s = MountainSearch([peakX, peak1, first])
        // 先頭一致があれば、英数字 1 文字では部分一致を返さない。
        XCTAssertEqual(s.search("p", latitude: nil, longitude: nil), .init(prefix: [peakX], partial: []))
        XCTAssertEqual(s.search("Ｐ", latitude: nil, longitude: nil), .init(prefix: [peakX], partial: []))
        XCTAssertEqual(s.search("1", latitude: nil, longitude: nil), .init(prefix: [first], partial: []))
        XCTAssertEqual(s.search("１", latitude: nil, longitude: nil), .init(prefix: [first], partial: []))
        // 2 文字なら部分一致も返す。
        XCTAssertEqual(s.search("pe", latitude: nil, longitude: nil), .init(prefix: [peakX], partial: [peak1]))
    }

    func testMatchesPrefixOfAliases() {
        let results = search.search("開運", latitude: nil, longitude: nil)
        XCTAssertEqual(results.prefix, [mitsutoge])
        XCTAssertEqual(results.partial, [])
    }

    func testNormalizesKana() {
        XCTAssertEqual(MountainSearch.normalize("ヤリガタケ"), "やりがたけ")
        XCTAssertEqual(MountainSearch.normalize("槍ヶ岳"), "槍け岳")
        XCTAssertEqual(MountainSearch.normalize("ＡＢ C"), "abc")
    }
}
