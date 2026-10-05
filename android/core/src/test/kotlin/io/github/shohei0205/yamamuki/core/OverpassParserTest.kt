package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OverpassParserTest {
    @Test
    fun parsesNamedNodes() {
        val body = listOf(
            "@id\t@lat\t@lon\tname\tname:ja\tele",
            "1\t35.3606\t138.7274\t富士山\t\t3776",
            "2\t35.0\t138.0\tMt. X\tエックス山\t1,234 m",
            "3\t35.1\t138.1\t\t\t",
            "4\t35.2\t138.2\t無標高山\t\t",
            "1\t35.3606\t138.7274\t富士山\t\t3776",
        ).joinToString("\n", postfix = "\n")

        val result = OverpassParser.parse(body)

        assertEquals(
            listOf(
                Mountain(1, "富士山", 35.3606, 138.7274, 3776.0),
                Mountain(2, "エックス山", 35.0, 138.0, 1234.0),
                Mountain(4, "無標高山", 35.2, 138.2, null),
            ),
            result,
        )
    }

    @Test
    fun skipsBrokenLines() {
        // 値に改行が入ると 1 件が 2 行に割れて列数が合わなくなる。
        val body = listOf(
            "@id\t@lat\t@lon\tname\tname:ja\tele",
            "5\t35.5\t138.5\t改行",
            "山\t\t500",
            "6\t35.6\t138.6\t正常山\t\t600",
        ).joinToString("\n")

        assertEquals(listOf(Mountain(6, "正常山", 35.6, 138.6, 600.0)), OverpassParser.parse(body))
    }

    @Test
    fun parsesEmptyResult() {
        assertEquals(emptyList(), OverpassParser.parse("@id\t@lat\t@lon\tname\tname:ja\tele\n"))
        assertEquals(emptyList(), OverpassParser.parse(""))
    }

    @Test
    fun rejectsUnexpectedFormat() {
        assertFailsWith<OverpassException> { OverpassParser.parse("""{"elements":[]}""") }
    }

    @Test
    fun parsesElevationVariants() {
        assertEquals(3776.0, OverpassParser.parseElevation("3776"))
        assertEquals(3776.5, OverpassParser.parseElevation("3776.5 m"))
        assertEquals(3776.0, OverpassParser.parseElevation("3776;3775"))
        assertEquals(304.8, OverpassParser.parseElevation("1000 ft")!!, 0.001)
        assertNull(OverpassParser.parseElevation("unknown"))
        assertNull(OverpassParser.parseElevation(null))
    }

    @Test
    fun queryUsesBboxOrder() {
        val q = OverpassQuery.peaks(BoundingBox(35.0, 138.0, 36.0, 139.0))
        kotlin.test.assertContains(q, """node["natural"="peak"]["name"](35.00000,138.00000,36.00000,139.00000);""")
        kotlin.test.assertContains(q, """[out:csv(::id,::lat,::lon,name,"name:ja",ele;true;"\t")]""")
    }
}
