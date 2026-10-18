package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeakDataCreditsTest {
    private val manifest = """
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
    """.trimIndent()

    @Test
    fun readsTopLevelAndSupplementSources() {
        val credits = PeakDataCreditsReader.parse(manifest)!!
        assertEquals("2026/10/10", credits.releaseDate)
        assertEquals("ODbL-1.0", credits.license)
        assertEquals("© OpenStreetMap contributors", credits.attribution)
        assertEquals("https://download.geofabrik.de/asia/japan-261009.osm.pbf", credits.sourceUrl)
        // 出典 ID の文字コード順。九(4E5D) < 日(65E5) < 空(7A7A)。値がオブジェクトでないものは飛ばす。
        assertEquals(
            listOf(
                SupplementSource("九州百名山", source = "一覧", license = null, attribution = null, changes = null),
                SupplementSource(
                    "日本百名山",
                    source = "Wikipedia「日本百名山」 https://ja.wikipedia.org/w/index.php?title=日本百名山&oldid=111092405",
                    license = "CC BY-SA 4.0",
                    attribution = "Wikipedia の投稿者",
                    changes = "osmId を付与する。",
                ),
                SupplementSource("空", null, null, null, null),
            ),
            credits.supplements,
        )
    }

    @Test
    fun oldDataWithoutSupplementSourcesHasNoSupplements() {
        val credits = PeakDataCreditsReader.parse("""{"version": "x", "license": "ODbL-1.0"}""")!!
        assertNull(credits.releaseDate)
        assertNull(credits.attribution)
        assertEquals(emptyList(), credits.supplements)
        assertEquals(emptyList(), PeakDataCreditsReader.parse("""{"supplementSources": {}}""")!!.supplements)
        assertEquals(emptyList(), PeakDataCreditsReader.parse("""{"supplementSources": []}""")!!.supplements)
    }

    // manifest 版 6。出典は metadata.sources に並び、データ本体のファイル名の頭と同じ名前のものが元データ。
    private val manifestV6 = """
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
    """.trimIndent()

    @Test
    fun readsMetadataSourcesOfManifestV6() {
        val credits = PeakDataCreditsReader.parse(manifestV6)!!
        assertEquals("2026/10/18", credits.releaseDate)
        assertEquals("ODbL-1.0", credits.license)
        assertEquals("© OpenStreetMap contributors", credits.attribution)
        assertEquals("https://download.geofabrik.de/asia/japan-261017.osm.pbf", credits.sourceUrl)
        // 元データ以外の出典を、出典名の文字コード順に出す。国(56FD) < 日(65E5)。名前の無いものは飛ばす。
        assertEquals(
            listOf(
                SupplementSource("国土地理院", source = null, license = "PDL-1.0", attribution = "出典：国土地理院", changes = null),
                SupplementSource(
                    "日本百名山",
                    source = "Wikipedia「日本百名山」",
                    license = "CC BY-SA 4.0",
                    attribution = "Wikipedia「日本百名山」の投稿者",
                    changes = "OSM の地点 ID に対応付けた。",
                ),
            ),
            credits.supplements,
        )
    }

    @Test
    fun manifestV6WithoutMainSourceListsAllAsSupplements() {
        val credits = PeakDataCreditsReader.parse(manifestV6.replace("\"osm-peaks.json.gz\"", "\"other.json.gz\""))!!
        assertNull(credits.license)
        assertNull(credits.sourceUrl)
        assertEquals(listOf("osm-peaks", "国土地理院", "日本百名山"), credits.supplements.map { it.id })
        assertEquals(emptyList(), PeakDataCreditsReader.parse("""{"metadata": {}}""")!!.supplements)
    }

    @Test
    fun unreadableManifestIsNull() {
        assertNull(PeakDataCreditsReader.parse("{"))
        assertNull(PeakDataCreditsReader.parse("[]"))
    }

    @Test
    fun readsOnlyTheManifestOfTheInstalledData() {
        val bytes = manifest.toByteArray(Charsets.UTF_8)
        val installed = InstalledPeakData("v", "", 1, null, 0, manifestSha256 = PeakData.sha256Hex(bytes))
        assertEquals("ODbL-1.0", PeakDataCreditsReader.read(installed, bytes)?.license)
        // 取り込み済みの記録が無い・manifest が残っていない・別の版の manifest のときは読まない。
        assertNull(PeakDataCreditsReader.read(null, bytes))
        assertNull(PeakDataCreditsReader.read(installed.copy(manifestSha256 = null), bytes))
        assertNull(PeakDataCreditsReader.read(installed, null))
        assertNull(PeakDataCreditsReader.read(installed.copy(manifestSha256 = "0".repeat(64)), bytes))
    }

    @Test
    fun splitsUrlsOutOfText() {
        assertEquals(listOf(LinkText.Part("説明だけ", false)), LinkText.split("説明だけ"))
        assertEquals(
            listOf(
                LinkText.Part("山名一覧: CC BY-SA 4.0 ", false),
                LinkText.Part("https://creativecommons.org/licenses/by-sa/4.0/", true),
                LinkText.Part(" ; 地図: ", false),
                LinkText.Part("https://ja.wikipedia.org/w/index.php?title=日本百名山&action=history", true),
            ),
            LinkText.split("山名一覧: CC BY-SA 4.0 https://creativecommons.org/licenses/by-sa/4.0/ ; 地図: https://ja.wikipedia.org/w/index.php?title=日本百名山&action=history"),
        )
    }

    @Test
    fun urlStopsBeforePunctuationAndUnbalancedParenthesis() {
        assertEquals(
            listOf(LinkText.Part("（", false), LinkText.Part("https://example.com/a", true), LinkText.Part("）を参照。", false)),
            LinkText.split("（https://example.com/a）を参照。"),
        )
        assertEquals(
            listOf(LinkText.Part("(", false), LinkText.Part("https://example.com/a", true), LinkText.Part(").", false)),
            LinkText.split("(https://example.com/a)."),
        )
        // URL の中で対応している括弧は含める。
        assertEquals(
            listOf(LinkText.Part("https://en.wikipedia.org/wiki/Fuji_(mountain)", true)),
            LinkText.split("https://en.wikipedia.org/wiki/Fuji_(mountain)"),
        )
        assertEquals(listOf(LinkText.Part("https:// だけ", false)), LinkText.split("https:// だけ"))
    }
}
