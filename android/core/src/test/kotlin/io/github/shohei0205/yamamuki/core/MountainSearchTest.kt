package io.github.shohei0205.yamamuki.core

import kotlin.test.*

class MountainSearchTest {
    private val yari = Mountain(1, "槍ヶ岳", 36.3420, 137.6476, 3180.0, nameReading = "やりがたけ")
    private val fuji = Mountain(2, "富士山", 35.3606, 138.7274, 3776.0, nameReading = "ふじさん")
    private val koFuji = Mountain(3, "小富士", 35.3712, 138.7639, 1979.0, nameReading = "こふじ")
    private val mitsutoge = Mountain(4, "三ツ峠山", 35.5469, 138.8133, 1785.0, aliases = listOf("開運山"))
    private val unknown = Mountain(5, "富士見台", 35.4000, 137.6000, null)
    private val search = MountainSearch(listOf(yari, fuji, koFuji, mitsutoge, unknown))

    /** 河口湖駅のあたり。小富士 → 富士山 → 富士見台の順に近い。 */
    private val kawaguchiko = 35.4986 to 138.7690

    @Test fun matchesNameReadingAndAliases() {
        assertEquals(listOf(yari), search.search("槍", null, null))
        assertEquals(listOf(yari), search.search("やりが", null, null))
        assertEquals(listOf(mitsutoge), search.search("開運山", null, null))
    }

    @Test fun ignoresKanaWidthCaseAndSpaces() {
        assertEquals(listOf(yari), search.search("ヤリガタケ", null, null))
        assertEquals(listOf(yari), search.search("ﾔﾘｶﾞﾀｹ", null, null))
        assertEquals(listOf(yari), search.search("槍ケ岳", null, null))
        assertEquals(listOf(mitsutoge), search.search("三ッ峠", null, null))
        assertEquals(listOf(fuji), search.search(" ふじ さん ", null, null))
    }

    @Test fun sortsByDistanceFromGivenPoint() {
        val (lat, lon) = kawaguchiko
        assertEquals(listOf(koFuji, fuji, unknown), search.search("富士", lat, lon))
    }

    @Test fun sortsByElevationWithoutPoint() {
        assertEquals(listOf(fuji, koFuji, unknown), search.search("富士", null, null))
    }

    @Test fun limitsResultsAndIgnoresBlankQuery() {
        assertEquals(listOf(fuji), search.search("富士", null, null, limit = 1))
        assertEquals(emptyList(), search.search("  ", null, null))
        assertEquals(emptyList(), search.search("穂高", null, null))
    }

    @Test fun normalizesKana() {
        assertEquals("やりがたけ", MountainSearch.normalize("ヤリガタケ"))
        assertEquals("槍け岳", MountainSearch.normalize("槍ヶ岳"))
        assertEquals("abc", MountainSearch.normalize("ＡＢ C"))
    }
}
