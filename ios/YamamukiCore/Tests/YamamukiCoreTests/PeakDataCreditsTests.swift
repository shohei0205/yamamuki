// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

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

    /// manifest 版 6(2026-10-20 の osm-peaks の正式版と同じ形)。出典は metadata.sources に並び、元データと補足の区別は無い。
    private let manifestV6 = """
    {
      "schemaVersion": 6,
      "version": "20261020T014651Z-39355252426-1",
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
            "name": "OpenStreetMap（日本・Geofabrik 配布）",
            "source": "https://download.geofabrik.de/asia/japan-261017.osm.pbf",
            "license": "ODbL-1.0",
            "attribution": "© OpenStreetMap contributors",
            "latestTimestamp": "2026-10-17T04:42:52Z"
          },
          { "license": "名前の無い出典" },
          "文字列"
        ],
        "pointCount": 14093
      }
    }
    """

    func testReadsAllMetadataSourcesOfManifestV6() throws {
        let credits = try XCTUnwrap(PeakDataCreditsReader.parse(Data(manifestV6.utf8)))
        XCTAssertEqual(credits.releaseDate, "2026/10/20")
        XCTAssertTrue(credits.listsAllSources)
        // 元データを名前で探さないので、トップの項目は出さない。
        XCTAssertNil(credits.license)
        XCTAssertNil(credits.attribution)
        XCTAssertNil(credits.sourceUrl)
        // 元データも含めたすべての出典を、manifest の並び順のまま出す(並べ替えない)。名前の無いものは飛ばす。
        XCTAssertEqual(credits.supplements, [
            SupplementSource(
                id: "日本百名山",
                source: "Wikipedia「日本百名山」",
                license: "CC BY-SA 4.0",
                attribution: "Wikipedia「日本百名山」の投稿者",
                changes: "OSM の地点 ID に対応付けた。"
            ),
            SupplementSource(id: "国土地理院", source: nil, license: "PDL-1.0", attribution: "出典：国土地理院", changes: nil),
            SupplementSource(
                id: "OpenStreetMap（日本・Geofabrik 配布）",
                source: "https://download.geofabrik.de/asia/japan-261017.osm.pbf",
                license: "ODbL-1.0",
                attribution: "© OpenStreetMap contributors",
                changes: nil
            ),
        ])
    }

    func testManifestV6ReadsSourcesWhateverTheFileName() throws {
        // 出典の名前がファイル名の頭と合わなくても(gsi-peaks など)、すべての出典を出す。
        let gsi = manifestV6.replacingOccurrences(of: "\"osm-peaks.json.gz\"", with: "\"gsi-peaks.json.gz\"")
        XCTAssertEqual(try XCTUnwrap(PeakDataCreditsReader.parse(Data(gsi.utf8))).supplements.count, 3)
        // 2026-10-18 の版のように、ファイル名の頭と同じ名前の出典があっても、ほかと同じに並べる。
        let oldName = manifestV6.replacingOccurrences(of: "OpenStreetMap（日本・Geofabrik 配布）", with: "osm-peaks")
        let old = try XCTUnwrap(PeakDataCreditsReader.parse(Data(oldName.utf8)))
        XCTAssertEqual(old.supplements.map(\.id), ["日本百名山", "国土地理院", "osm-peaks"])
        XCTAssertNil(old.license)
    }

    func testManifestV6WithoutNamedSourcesIsNil() {
        // 名前のある出典が無いときは読めないものとし、画面はアプリが持つ OpenStreetMap の表示に戻す。
        XCTAssertNil(PeakDataCreditsReader.parse(Data(#"{"metadata": {}}"#.utf8)))
        XCTAssertNil(PeakDataCreditsReader.parse(Data(#"{"metadata": {"sources": []}}"#.utf8)))
        XCTAssertNil(PeakDataCreditsReader.parse(Data(#"{"metadata": {"sources": [{"license": "ODbL-1.0"}]}}"#.utf8)))
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
