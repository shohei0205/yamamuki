import XCTest
@testable import YamamukiCore

final class PeakDataCreditsTests: XCTestCase {
    private let manifest = """
    {
      "schemaVersion": 5,
      "version": "20261010T022253Z-38016527834-1",
      "sourceUrl": "https://download.geofabrik.de/asia/japan-261009.osm.pbf",
      "license": "ODbL-1.0",
      "attribution": "© OpenStreetMap contributors",
      "supplementSources": {
        "日本百名山": {
          "source": "Wikipedia「日本百名山」 https://ja.wikipedia.org/w/index.php?title=日本百名山&oldid=111092405",
          "license": "CC BY-SA 4.0",
          "attribution": "Wikipedia の投稿者",
          "changes": "osmId を付与する。"
        },
        "九州百名山": { "license": "  ", "source": "一覧" },
        "壊れた出典": "文字列",
        "空": {}
      }
    }
    """

    func testReadsTopLevelAndSupplementSources() throws {
        let credits = try XCTUnwrap(PeakDataCreditsReader.parse(Data(manifest.utf8)))
        XCTAssertEqual(credits.releaseDate, "2026/10/10")
        XCTAssertEqual(credits.license, "ODbL-1.0")
        XCTAssertEqual(credits.attribution, "© OpenStreetMap contributors")
        XCTAssertEqual(credits.sourceUrl, "https://download.geofabrik.de/asia/japan-261009.osm.pbf")
        // 出典 ID の文字コード順。九(4E5D) < 日(65E5) < 空(7A7A)。値がオブジェクトでないものは飛ばす。
        XCTAssertEqual(credits.supplements, [
            SupplementSource(id: "九州百名山", source: "一覧", license: nil, attribution: nil, changes: nil),
            SupplementSource(
                id: "日本百名山",
                source: "Wikipedia「日本百名山」 https://ja.wikipedia.org/w/index.php?title=日本百名山&oldid=111092405",
                license: "CC BY-SA 4.0",
                attribution: "Wikipedia の投稿者",
                changes: "osmId を付与する。"
            ),
            SupplementSource(id: "空", source: nil, license: nil, attribution: nil, changes: nil),
        ])
    }

    func testOldDataWithoutSupplementSourcesHasNoSupplements() throws {
        let credits = try XCTUnwrap(PeakDataCreditsReader.parse(Data(#"{"version": "x", "license": "ODbL-1.0"}"#.utf8)))
        XCTAssertNil(credits.releaseDate)
        XCTAssertNil(credits.attribution)
        XCTAssertEqual(credits.supplements, [])
        XCTAssertEqual(PeakDataCreditsReader.parse(Data(#"{"supplementSources": {}}"#.utf8))?.supplements, [])
        XCTAssertEqual(PeakDataCreditsReader.parse(Data(#"{"supplementSources": []}"#.utf8))?.supplements, [])
    }

    /// manifest 版 6。出典は metadata.sources に並び、データ本体のファイル名の頭と同じ名前のものが元データ。
    private let manifestV6 = """
    {
      "schemaVersion": 6,
      "version": "20261018T115955Z-39125410649-1",
      "fileName": "osm-peaks.json.gz",
      "metadata": {
        "sources": [
          {
            "name": "日本百名山",
            "source": "Wikipedia「日本百名山」",
            "license": "CC BY-SA 4.0",
            "attribution": "Wikipedia「日本百名山」の投稿者",
            "changes": "OSM の地点 ID に対応付けた。"
          },
          { "name": "国土地理院", "license": "PDL-1.0", "attribution": "出典：国土地理院" },
          {
            "name": "osm-peaks",
            "source": "https://download.geofabrik.de/asia/japan-261017.osm.pbf",
            "license": "ODbL-1.0",
            "attribution": "© OpenStreetMap contributors",
            "latestTimestamp": "2026-10-17T04:42:52Z"
          },
          { "license": "名前の無い出典" },
          "文字列"
        ],
        "pointCount": 14038
      }
    }
    """

    func testReadsMetadataSourcesOfManifestV6() throws {
        let credits = try XCTUnwrap(PeakDataCreditsReader.parse(Data(manifestV6.utf8)))
        XCTAssertEqual(credits.releaseDate, "2026/10/18")
        XCTAssertEqual(credits.license, "ODbL-1.0")
        XCTAssertEqual(credits.attribution, "© OpenStreetMap contributors")
        XCTAssertEqual(credits.sourceUrl, "https://download.geofabrik.de/asia/japan-261017.osm.pbf")
        // 元データ以外の出典を、出典名の文字コード順に出す。国(56FD) < 日(65E5)。名前の無いものは飛ばす。
        XCTAssertEqual(credits.supplements, [
            SupplementSource(id: "国土地理院", source: nil, license: "PDL-1.0", attribution: "出典：国土地理院", changes: nil),
            SupplementSource(
                id: "日本百名山",
                source: "Wikipedia「日本百名山」",
                license: "CC BY-SA 4.0",
                attribution: "Wikipedia「日本百名山」の投稿者",
                changes: "OSM の地点 ID に対応付けた。"
            ),
        ])
    }

    func testManifestV6WithoutMainSourceIsNil() {
        // 元データの出典が見つからないときは読めないものとし、画面はアプリが持つ OpenStreetMap の表示に戻す。
        let otherFile = manifestV6.replacingOccurrences(of: "\"osm-peaks.json.gz\"", with: "\"other.json.gz\"")
        XCTAssertNil(PeakDataCreditsReader.parse(Data(otherFile.utf8)))
        let otherName = manifestV6.replacingOccurrences(of: "\"name\": \"osm-peaks\"", with: "\"name\": \"osm\"")
        XCTAssertNil(PeakDataCreditsReader.parse(Data(otherName.utf8)))
        XCTAssertNil(PeakDataCreditsReader.parse(Data(#"{"metadata": {}}"#.utf8)))
    }

    func testUnreadableManifestIsNil() {
        XCTAssertNil(PeakDataCreditsReader.parse(Data("{".utf8)))
        XCTAssertNil(PeakDataCreditsReader.parse(Data("[]".utf8)))
    }

    func testReadsOnlyTheManifestOfTheInstalledData() {
        let bytes = Data(manifest.utf8)
        let installed = InstalledPeakData(version: "v", sourceTimestamp: "", mountainCount: 1, manifestEtag: nil,
                                          installedAt: Date(), manifestSha256: PeakData.sha256Hex(bytes))
        XCTAssertEqual(PeakDataCreditsReader.read(installed: installed, manifest: bytes)?.license, "ODbL-1.0")
        // 取り込み済みの記録が無い・manifest が残っていない・別の版の manifest のときは読まない。
        var noSha = installed
        noSha.manifestSha256 = nil
        var otherSha = installed
        otherSha.manifestSha256 = String(repeating: "0", count: 64)
        XCTAssertNil(PeakDataCreditsReader.read(installed: nil, manifest: bytes))
        XCTAssertNil(PeakDataCreditsReader.read(installed: noSha, manifest: bytes))
        XCTAssertNil(PeakDataCreditsReader.read(installed: installed, manifest: nil))
        XCTAssertNil(PeakDataCreditsReader.read(installed: otherSha, manifest: bytes))
    }

    func testSplitsUrlsOutOfText() {
        XCTAssertEqual(LinkText.split("説明だけ"), [LinkText.Part("説明だけ", isUrl: false)])
        XCTAssertEqual(
            LinkText.split("山名一覧: CC BY-SA 4.0 https://creativecommons.org/licenses/by-sa/4.0/ ; 地図: https://ja.wikipedia.org/w/index.php?title=日本百名山&action=history"),
            [
                LinkText.Part("山名一覧: CC BY-SA 4.0 ", isUrl: false),
                LinkText.Part("https://creativecommons.org/licenses/by-sa/4.0/", isUrl: true),
                LinkText.Part(" ; 地図: ", isUrl: false),
                LinkText.Part("https://ja.wikipedia.org/w/index.php?title=日本百名山&action=history", isUrl: true),
            ]
        )
    }

    func testUrlStopsBeforePunctuationAndUnbalancedParenthesis() {
        XCTAssertEqual(
            LinkText.split("（https://example.com/a）を参照。"),
            [LinkText.Part("（", isUrl: false), LinkText.Part("https://example.com/a", isUrl: true), LinkText.Part("）を参照。", isUrl: false)]
        )
        XCTAssertEqual(
            LinkText.split("(https://example.com/a)."),
            [LinkText.Part("(", isUrl: false), LinkText.Part("https://example.com/a", isUrl: true), LinkText.Part(").", isUrl: false)]
        )
        // URL の中で対応している括弧は含める。
        XCTAssertEqual(
            LinkText.split("https://en.wikipedia.org/wiki/Fuji_(mountain)"),
            [LinkText.Part("https://en.wikipedia.org/wiki/Fuji_(mountain)", isUrl: true)]
        )
        XCTAssertEqual(LinkText.split("https:// だけ"), [LinkText.Part("https:// だけ", isUrl: false)])
    }
}
