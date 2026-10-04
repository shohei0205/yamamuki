package io.github.shohei0205.yamamuki.core

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

class PeakLayoutTest {
    private fun m(name: String, ele: Double?, km: Double) =
        NearbyMountain(Mountain(name.hashCode().toLong(), name, 0.0, 0.0, ele), km, 0.0)

    @Test
    fun elevationAngleAccountsForEarthCurve() {
        // 1km 先の 1000m 上は、ほぼ 45° に見える。
        assertEquals(45.0, GeoMath.elevationAngleDeg(0.0, 1000.0, 1.0), 0.01)
        // 100km 先では、地球の丸みで約 683m 沈む(屈折で少し浮き上がった後の値)。
        assertEquals(0.0, GeoMath.elevationAngleDeg(0.0, 682.8, 100.0), 0.001)
        assertTrue(GeoMath.elevationAngleDeg(0.0, 600.0, 100.0) < 0.0)
        // 自分より低い山は負の角度になる。
        assertTrue(GeoMath.elevationAngleDeg(1500.0, 1400.0, 1.0) < 0.0)
    }

    @Test
    fun priorityPrefersHigherLookingPeaks() {
        // 近くの低い山は、遠くの高い山より見かけが高いので先。標高不明は最後。
        val sorted = listOf(m("富士", 3776.0, 80.0), m("不明", null, 0.5), m("裏山", 300.0, 2.0), m("遠い丘", 300.0, 30.0))
            .sortedWith(displayPriority(observerAltitudeM = 0.0))
        assertEquals(listOf("裏山", "富士", "遠い丘", "不明"), sorted.map { it.mountain.name })
    }

    @Test
    fun priorityUsesObserverAltitude() {
        val near = m("近い", 1400.0, 1.0)
        val far = m("遠い", 2000.0, 20.0)
        assertEquals(listOf("近い", "遠い"), listOf(far, near).sortedWith(displayPriority(null)).map { it.mountain.name })
        // 自分が 1500m にいると、1400m の山は見下ろすので後ろに回る。
        assertEquals(listOf("遠い", "近い"), listOf(near, far).sortedWith(displayPriority(1500.0)).map { it.mountain.name })
    }

    @Test
    fun keptPeaksGetBonus() {
        val a = m("A", 1000.0, 10.0)
        val b = m("B", 1010.0, 10.0)
        assertEquals(listOf("B", "A"), PeakLayout.priorityOrder(listOf(a, b), 0.0).map { it.mountain.name })
        // 前回選んだ A は、わずかな差なら B より先に残る。
        val kept = setOf(a.mountain.osmId)
        assertEquals(listOf("A", "B"), PeakLayout.priorityOrder(listOf(a, b), 0.0, kept).map { it.mountain.name })
    }

    private val square = Box(-10f, -10f, 10f, 10f)

    @Test
    fun clearanceIsFarthestCorner() {
        assertEquals(hypot(20.0, 20.0), PeakLayout.clearance(square, square), 1e-9)
        // 山名が下に長く出る範囲どうしでも、縦横それぞれの最大のずれで決まる。
        val label = Box(-30f, -20f, 30f, 15f)
        assertEquals(hypot(35.0, 30.0), PeakLayout.clearance(label, square.copy(left = -5f, right = 5f)), 1e-9)
    }

    @Test
    fun selectAroundDropsPeaksThatOverlapAtSomeHeading() {
        val positions = mapOf("A" to PlanOffset(0.0, 0.0), "B" to PlanOffset(25.0, 0.0), "C" to PlanOffset(0.0, 30.0))
        // B は今の向きでは A と重ならないが、45° 回すと重なるので捨てる。
        val placed = PeakLayout.selectAround(sequenceOf("A", "B", "C"), position = { positions.getValue(it) }, box = { square })
        assertEquals(listOf("A", "C"), placed)
        assertEquals(listOf("A"), PeakLayout.selectAround(sequenceOf("A", "B", "C"), limit = 1, position = { positions.getValue(it) }, box = { square }))
    }

    @Test
    fun selectAroundKeepsPreviouslySelectedPairs() {
        val positions = mapOf("A" to PlanOffset(0.0, 0.0), "B" to PlanOffset(27.0, 0.0))
        fun select(kept: Set<String>) = PeakLayout.selectAround(sequenceOf("A", "B"),
            position = { positions.getValue(it) }, box = { square }, keptBefore = { it in kept })
        assertEquals(listOf("A"), select(emptySet()))
        // どちらも前回選んでいたら、少し近づいても両方残す。
        assertEquals(listOf("A", "B"), select(setOf("A", "B")))
        assertEquals(listOf("A"), select(setOf("A")))
    }

    @Test
    fun selectAroundStopsPullingItemsAtLimit() {
        val pulled = mutableListOf<Int>()
        val items = (0 until 10).asSequence().onEach { pulled += it }
        val placed = PeakLayout.selectAround(items, limit = 2, position = { PlanOffset(it * 100.0, 0.0) }, box = { square })
        assertEquals(listOf(0, 1), placed)
        // 上限に達したあとの項目は取り出さない(山名の計測を省くため)。
        assertEquals(listOf(0, 1), pulled)
    }

    @Test
    fun aroundLimitScalesWithArea() {
        assertEquals(80, PeakLayout.aroundLimit(40, reachPx = 100.0, viewAreaPx = PI * 100 * 100 / 2))
        // 円が画面より狭くても、画面の上限より減らさない。
        assertEquals(40, PeakLayout.aroundLimit(40, reachPx = 10.0, viewAreaPx = 10000.0))
    }

    @Test
    fun capVisibleKeepsDrawnPeaks() {
        assertEquals(listOf("A", "B"), PeakLayout.capVisible(listOf("A", "B", "C"), 2) { false })
        // 前回描いた C は、優先度の高い B が入ってきても残る。順は優先順のまま。
        assertEquals(listOf("A", "C"), PeakLayout.capVisible(listOf("A", "B", "C"), 2) { it != "B" })
        assertEquals(listOf("A", "B", "C"), PeakLayout.capVisible(listOf("A", "B", "C"), 5) { false })
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
