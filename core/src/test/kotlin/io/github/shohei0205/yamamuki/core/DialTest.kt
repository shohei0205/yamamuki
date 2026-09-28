package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeadingTest {
    @Test
    fun deltaTakesShortestWay() {
        assertEquals(2.0, Heading.delta(359.0, 1.0), 1e-9)
        assertEquals(-2.0, Heading.delta(1.0, 359.0), 1e-9)
        assertEquals(-180.0, Heading.delta(0.0, 180.0), 1e-9)
    }

    @Test
    fun directionNames() {
        assertEquals("北", Heading.directionName(0.0))
        assertEquals("北", Heading.directionName(355.0))
        assertEquals("北北東", Heading.directionName(20.0))
        assertEquals("南東", Heading.directionName(135.0))
        assertEquals("西", Heading.directionName(-90.0))
    }

    @Test
    fun azimuthWhenFlat() {
        // 水平、上端が北
        assertEquals(0.0, Heading.azimuthFromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))!!, 1e-6)
        // 水平、上端が東(端末の X 軸が南)
        assertEquals(90.0, Heading.azimuthFromRotationMatrix(floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))!!, 1e-6)
    }

    @Test
    fun azimuthWhenUpright() {
        // 縦に立てて背面が北(Y 軸が上、Z 軸が南)
        assertEquals(0.0, Heading.azimuthFromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f))!!, 1e-6)
        // 縦に立てて背面が西(X 軸が南、Z 軸が東)
        assertEquals(270.0, Heading.azimuthFromRotationMatrix(floatArrayOf(0f, 0f, 1f, -1f, 0f, 0f, 0f, 1f, 0f))!!, 1e-6)
    }

    @Test
    fun azimuthFaceDownAndDegenerate() {
        // 水平に持って画面を下に向け、背面が真上(Y が北、Z が下)→ Y - Z の水平成分は Y のみなので北
        assertEquals(0.0, Heading.azimuthFromRotationMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f))!!, 1e-6)
        // Y と Z の水平成分が打ち消し合う退化ケースは方位なし
        assertNull(Heading.azimuthFromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0.5f, 0.5f, 0f, 0f, 0f)))
    }

    @Test
    fun filterFollowsAcrossNorth() {
        val filter = HeadingFilter(alpha = 0.5)
        assertEquals(350.0, filter.update(350.0), 1e-9)
        assertEquals(355.0, filter.update(0.0), 1e-9)
        assertEquals(357.5, filter.update(0.0), 1e-9)
    }
}

class DialGeometryTest {
    @Test
    fun projectRelativeToHeading() {
        val ahead = DialGeometry.project(10.0, bearingDeg = 90.0, headingDeg = 90.0)
        assertEquals(0.0, ahead.x, 1e-9)
        assertEquals(10.0, ahead.y, 1e-9)

        val right = DialGeometry.project(5.0, bearingDeg = 10.0, headingDeg = 280.0)
        assertEquals(5.0, right.x, 1e-9)
        assertEquals(0.0, right.y, 1e-9)

        val behind = DialGeometry.project(3.0, bearingDeg = 180.0, headingDeg = 0.0)
        assertEquals(-3.0, behind.y, 1e-9)
    }

    @Test
    fun zoomIsClamped() {
        assertEquals(7.5, DialGeometry.zoomedRange(15.0, 2f), 1e-9)
        assertEquals(DialGeometry.MIN_RANGE_KM, DialGeometry.zoomedRange(3.0, 10f), 1e-9)
        assertEquals(DialGeometry.MAX_RANGE_KM, DialGeometry.zoomedRange(60.0, 0.1f), 1e-9)
        assertEquals(15.0, DialGeometry.zoomedRange(15.0, 0f), 1e-9)
    }

    @Test
    fun fetchRadiusCoversScreenCorners() {
        assertEquals(20.0, DialGeometry.fetchRadiusKm(DialGeometry.MIN_RANGE_KM), 1e-9)
        assertEquals(50.0, DialGeometry.fetchRadiusKm(DialGeometry.DEFAULT_RANGE_KM), 1e-9)
        assertEquals(120.0, DialGeometry.fetchRadiusKm(DialGeometry.MAX_RANGE_KM), 1e-9)
    }

    @Test
    fun ringSteps() {
        assertEquals(5.0, DialGeometry.ringStepKm(15.0), 1e-9)
        assertEquals(1.0, DialGeometry.ringStepKm(3.0), 1e-9)
        assertEquals(20.0, DialGeometry.ringStepKm(80.0), 1e-9)
        assertEquals(0.5, DialGeometry.ringStepKm(0.5), 1e-9)
        assertEquals("500m", DialGeometry.ringLabel(0.5))
        assertEquals("10km", DialGeometry.ringLabel(10.0))
    }

    @Test
    fun tapeTicksWrapAroundNorth() {
        val ticks = DialGeometry.tapeTicks(headingDeg = 3.0, spanDeg = 20.0)
        assertEquals(listOf(355, 0, 5, 10), ticks.map { it.angleDeg })
        assertEquals(listOf(-8.0, -3.0, 2.0, 7.0), ticks.map { it.offsetDeg })
        assertEquals("N", DialGeometry.cardinalLabel(0))
        assertEquals("SW", DialGeometry.cardinalLabel(225))
        assertNull(DialGeometry.cardinalLabel(10))
    }
}

class DeclutterTest {
    private fun m(name: String, ele: Double?, km: Double) =
        NearbyMountain(Mountain(name.hashCode().toLong(), name, 0.0, 0.0, ele), km, 0.0)

    @Test
    fun keepsHigherPriorityWhenOverlapping() {
        val boxes = mapOf(
            "A" to Box(0f, 0f, 10f, 10f),
            "B" to Box(5f, 5f, 15f, 15f),
            "C" to Box(20f, 0f, 30f, 10f),
        )
        assertEquals(listOf("A", "C"), declutter(listOf("A", "B", "C")) { boxes.getValue(it) })
        assertEquals(listOf("A"), declutter(listOf("A", "B", "C"), limit = 1) { boxes.getValue(it) })
    }

    @Test
    fun stopsPullingItemsAtLimit() {
        val pulled = mutableListOf<Int>()
        val items = (0 until 10).asSequence().onEach { pulled += it }
        val placed = declutter(items, limit = 2) { Box(it * 20f, 0f, it * 20f + 10f, 10f) }
        assertEquals(listOf(0, 1), placed)
        // 上限に達したあとの項目は取り出さない(山名の計測を省くため)。
        assertEquals(listOf(0, 1), pulled)
    }

    @Test
    fun priorityPrefersHighThenNear() {
        val sorted = listOf(m("低", 500.0, 1.0), m("不明", null, 0.5), m("高遠", 2000.0, 9.0), m("高近", 2000.0, 3.0))
            .sortedWith(displayPriority)
        assertEquals(listOf("高近", "高遠", "低", "不明"), sorted.map { it.mountain.name })
    }

    @Test
    fun elevationClass() {
        fun cls(ele: Double?) = Mountain(1, "山", 0.0, 0.0, ele).elevationClass()
        assertEquals(ElevationClass.LOW, cls(null))
        assertEquals(ElevationClass.LOW, cls(999.9))
        assertEquals(ElevationClass.MIDDLE, cls(1000.0))
        assertEquals(ElevationClass.MIDDLE, cls(1999.9))
        assertEquals(ElevationClass.HIGH, cls(2000.0))
        assertEquals(ElevationClass.HIGH, cls(3776.0))
    }
}
