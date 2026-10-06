package io.github.shohei0205.yamamuki.core

import kotlin.test.*

class HeaderSceneryTest {
    @Test fun headerFitsSmallBannerAndChartStartsBelowTape() {
        assertTrue(DialGeometry.HEADER_HEIGHT_DP >= 50.0)
        assertEquals(DialGeometry.HEADER_HEIGHT_DP + DialGeometry.TAPE_HEIGHT_DP, HeaderScenery.HEIGHT_DP)
        assertTrue(DialGeometry.CHART_TOP_DP > HeaderScenery.HEIGHT_DP)
    }

    @Test fun ridgesReachTheRightEdgeOnAnyWidth() {
        for (layer in HeaderScenery.Layer.entries) {
            for (width in listOf(0.0, 320.0, 360.0, 361.0, 800.0, 1280.0)) {
                val ridge = HeaderScenery.ridge(layer, width)
                assertTrue(ridge.segments.last().endX >= width, "$layer $width")
                assertEquals(0.0, ridge.segments.first().controlX - (ridge.segments.first().endX) / 2, "$layer starts at x = 0")
                ridge.segments.zipWithNext { a, b -> assertTrue(a.endX < b.endX) }
            }
        }
    }

    @Test fun repeatedPatternJoinsWithoutStep() {
        for (layer in HeaderScenery.Layer.entries) {
            val ridge = HeaderScenery.ridge(layer, 1000.0)
            val joins = ridge.segments.filter { it.endX % HeaderScenery.PERIOD_DP == 0.0 }
            assertTrue(joins.size >= 2)
            for (join in joins) assertEquals(ridge.startY, join.endY, "$layer at ${join.endX}")
        }
    }

    @Test fun ridgesAndPeakStayInsideTheScenery() {
        for (layer in HeaderScenery.Layer.entries) {
            val ridge = HeaderScenery.ridge(layer, 360.0)
            val ys = listOf(ridge.startY) + ridge.segments.flatMap { listOf(it.controlY, it.endY) }
            assertTrue(ys.all { it > 0.0 && it < HeaderScenery.HEIGHT_DP }, "$layer")
        }
        assertTrue(HeaderScenery.PEAK_TOP_Y_DP - HeaderScenery.CREST_RISE_DP > 0.0)
        assertTrue(HeaderScenery.FADE_TOP_DP < HeaderScenery.FOOT_Y_DP)
        assertTrue(HeaderScenery.FOOT_Y_DP < HeaderScenery.HEIGHT_DP)
        // 空と山並みは、地面の色で塗りつぶし終えた高さより下で、風景の下端より上で止める。
        assertTrue(HeaderScenery.FADE_SOLID_Y_DP < HeaderScenery.FILL_BOTTOM_DP)
        assertTrue(HeaderScenery.FILL_BOTTOM_DP <= HeaderScenery.HEIGHT_DP - 2.0)
        for (layer in HeaderScenery.Layer.entries) {
            val ridge = HeaderScenery.ridge(layer, 360.0)
            assertTrue(ridge.segments.all { it.endY < HeaderScenery.FILL_BOTTOM_DP && it.controlY < HeaderScenery.FILL_BOTTOM_DP })
        }
        assertEquals(230.4, HeaderScenery.peakCenterX(360.0), 1e-9)
    }
}
