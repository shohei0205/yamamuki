// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import kotlin.test.*

class MountainSearchTest {
    private val yari = Mountain("osm:1", "槍ヶ岳", 36.3420, 137.6476, 3180.0, nameReading = "やりがたけ")
    private val fuji = Mountain("osm:2", "富士山", 35.3606, 138.7274, 3776.0, nameReading = "ふじさん")
    private val koFuji = Mountain("osm:3", "小富士", 35.3712, 138.7639, 1979.0, nameReading = "こふじ")
    private val mitsutoge = Mountain("osm:4", "三ツ峠山", 35.5469, 138.8133, 1785.0, aliases = listOf("開運山"))
    private val unknown = Mountain("osm:5", "富士見台", 35.4000, 137.6000, null)
    private val search = MountainSearch(listOf(yari, fuji, koFuji, mitsutoge, unknown))

    /** 河口湖駅のあたり。小富士 → 富士山 → 富士見台の順に近い。「富士」で探すと、小富士は途中に含む山なので最後になる。 */
    private val kawaguchiko = 35.4986 to 138.7690

    @Test fun matchesNameReadingAndAliases() {
        assertEquals(listOf(yari), search.search("槍", null, null).all)
        assertEquals(listOf(yari), search.search("やりが", null, null).all)
        assertEquals(listOf(mitsutoge), search.search("開運山", null, null).all)
    }

    @Test fun ignoresKanaWidthCaseAndSpaces() {
        assertEquals(listOf(yari), search.search("ヤリガタケ", null, null).all)
        assertEquals(listOf(yari), search.search("ﾔﾘｶﾞﾀｹ", null, null).all)
        assertEquals(listOf(yari), search.search("槍ケ岳", null, null).all)
        assertEquals(listOf(mitsutoge), search.search("三ッ峠", null, null).all)
        assertEquals(listOf(fuji), search.search(" ふじ さん ", null, null).all)
    }

    @Test fun sortsByDistanceFromGivenPoint() {
        val (lat, lon) = kawaguchiko
        assertEquals(listOf(fuji, unknown, koFuji), search.search("富士", lat, lon).all)
    }

    @Test fun sortsByElevationWithoutPoint() {
        assertEquals(listOf(fuji, unknown, koFuji), search.search("富士", null, null).all)
    }

    @Test fun limitsResultsAndIgnoresBlankQuery() {
        assertEquals(listOf(fuji), search.search("富士", null, null, limit = 1).all)
        assertEquals(emptyList(), search.search("  ", null, null).all)
        assertEquals(emptyList(), search.search("穂高", null, null).all)
    }

    /** 「やま」で探すと、近くの「〜山(やま)」より、先頭が一致した山を先に並べる。 */
    @Test fun listsPrefixMatchesBeforePartialMatches() {
        val takayama = Mountain("osm:6", "高山", 35.5000, 138.7700, 1000.0, nameReading = "たかやま")
        val yamabushi = Mountain("osm:7", "山伏", 35.2500, 138.3700, 2014.0, nameReading = "やまぶし")
        val yamatai = Mountain("osm:8", "山大岳", 36.0000, 137.0000, 1500.0, nameReading = "やまたいだけ")
        val s = MountainSearch(listOf(takayama, yamatai, yamabushi))
        val (lat, lon) = kawaguchiko
        val results = s.search("やま", lat, lon)
        assertEquals(listOf(yamabushi, yamatai), results.prefix)
        assertEquals(listOf(takayama), results.partial)
        assertEquals(listOf(yamabushi, yamatai, takayama), results.all)
        // 件数の上限は、先頭が一致した山から数える。
        assertEquals(MountainSearch.Results(listOf(yamabushi), emptyList()), s.search("やま", lat, lon, limit = 1))
        assertEquals(MountainSearch.Results(listOf(yamabushi, yamatai), listOf(takayama)), s.search("やま", lat, lon, limit = 3))
    }

    /**
     * 仮名か英数字 1 文字だけのときは、先頭が一致した山だけを返す(「や」で「〜山(やま)」を出さない)。
     * 漢字 1 文字なら途中に含む山も返す。先頭が一致した山が無ければ、仮名や英数字 1 文字でも途中に含む山を返す。
     */
    @Test fun omitsPartialMatchesForOneKanaOrAlphanumeric() {
        val takayama = Mountain("osm:6", "高山", 35.5000, 138.7700, 1000.0, nameReading = "たかやま")
        val peak1 = Mountain("osm:9", "大平山 第1ピーク (Ohirayama Peak 1)", 36.0, 138.0, 500.0)
        val s = MountainSearch(listOf(takayama, yari, peak1))
        // 先頭一致が無いときは、1 文字でも部分一致を返す。
        assertEquals(MountainSearch.Results(emptyList(), listOf(peak1)), s.search("1", null, null))
        assertEquals(MountainSearch.Results(emptyList(), listOf(peak1)), s.search("１", null, null))
        assertEquals(MountainSearch.Results(emptyList(), listOf(peak1)), s.search("P", null, null))
        assertEquals(MountainSearch.Results(emptyList(), listOf(takayama)), s.search("か", null, null))
        assertEquals(MountainSearch.Results(emptyList(), listOf(peak1)), s.search("pe", null, null))
        assertEquals(MountainSearch.Results(listOf(yari), emptyList()), s.search("や", null, null))
        assertEquals(MountainSearch.Results(listOf(yari), emptyList()), s.search("ﾔ", null, null))
        assertEquals(MountainSearch.Results(listOf(takayama), emptyList()), s.search("高", null, null))
        assertEquals(MountainSearch.Results(emptyList(), listOf(takayama, peak1)), s.search("山", null, null))
        assertEquals(MountainSearch.Results(emptyList(), listOf(yari)), s.search("岳", null, null))
    }

    @Test fun omitsPartialMatchesForOneAlphanumericWithPrefixMatch() {
        val peakX = Mountain("osm:10", "Peak X", 35.0, 138.0, 1200.0)
        val peak1 = Mountain("osm:9", "大平山 第1ピーク (Ohirayama Peak 1)", 36.0, 138.0, 500.0)
        val first = Mountain("osm:11", "1峰", 35.0, 138.0, 900.0)
        val s = MountainSearch(listOf(peakX, peak1, first))
        // 先頭一致があれば、英数字 1 文字では部分一致を返さない。
        assertEquals(MountainSearch.Results(listOf(peakX), emptyList()), s.search("p", null, null))
        assertEquals(MountainSearch.Results(listOf(peakX), emptyList()), s.search("Ｐ", null, null))
        assertEquals(MountainSearch.Results(listOf(first), emptyList()), s.search("1", null, null))
        assertEquals(MountainSearch.Results(listOf(first), emptyList()), s.search("１", null, null))
        // 2 文字なら部分一致も返す。
        assertEquals(MountainSearch.Results(listOf(peakX), listOf(peak1)), s.search("pe", null, null))
    }

    @Test fun matchesPrefixOfAliases() {
        val results = search.search("開運", null, null)
        assertEquals(listOf(mitsutoge), results.prefix)
        assertEquals(emptyList(), results.partial)
    }

    @Test fun normalizesKana() {
        assertEquals("やりがたけ", MountainSearch.normalize("ヤリガタケ"))
        assertEquals("槍け岳", MountainSearch.normalize("槍ヶ岳"))
        assertEquals("abc", MountainSearch.normalize("ＡＢ C"))
    }
}
