package io.github.shohei0205.yamamuki.core

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** 名前の 1 文字目が同じ山を同じ山塊とし、名前の数字を標高とみなす。 */
    private fun neighbors(a: String, b: String) = a[0] == b[0]
    private fun higher(a: String, b: String) = a.drop(1).toInt() > b.drop(1).toInt()

    private fun selectAround(items: List<String>, positions: Map<String, PlanOffset>, kept: Set<String> = emptySet()) =
        PeakLayout.selectAround(items.asSequence(), position = { positions.getValue(it) }, box = { square },
            neighbors = ::neighbors, higher = ::higher, keptBefore = { it in kept })

    @Test
    fun selectAroundKeepsMainPeakOfNeighbors() {
        // A2 は今の向きでは A1 と重ならないが、45° 回すと重なる。同じ山塊なので標高の高い A2 だけを残す。
        val positions = mapOf("A1" to PlanOffset(0.0, 0.0), "A2" to PlanOffset(25.0, 0.0), "A3" to PlanOffset(0.0, 100.0))
        assertEquals(listOf("A2", "A3"), selectAround(listOf("A1", "A2", "A3"), positions))
        assertEquals(listOf("A2", "A3"), selectAround(listOf("A2", "A1", "A3"), positions))
    }

    @Test
    fun selectAroundKeepsPeaksOfOtherMassifs() {
        // 別の山塊の山は、重なりうる位置でもここでは省かない(描くときに今の向きで判定する)。
        val positions = mapOf("A1" to PlanOffset(0.0, 0.0), "B1" to PlanOffset(5.0, 0.0))
        assertEquals(listOf("A1", "B1"), selectAround(listOf("A1", "B1"), positions))
    }

    @Test
    fun selectAroundKeepsPreviouslySelectedPairs() {
        val positions = mapOf("A2" to PlanOffset(0.0, 0.0), "A1" to PlanOffset(27.0, 0.0))
        assertEquals(listOf("A2"), selectAround(listOf("A2", "A1"), positions))
        // どちらも前回選んでいたら、少し近づいても両方残す。
        assertEquals(listOf("A2", "A1"), selectAround(listOf("A2", "A1"), positions, kept = setOf("A2", "A1")))
        assertEquals(listOf("A2"), selectAround(listOf("A2", "A1"), positions, kept = setOf("A2")))
    }

    @Test
    fun selectAroundStopsPullingItemsAtLimit() {
        val pulled = mutableListOf<Int>()
        val items = (0 until 10).asSequence().onEach { pulled += it }
        val placed = PeakLayout.selectAround(items, limit = 2, position = { PlanOffset(it * 100.0, 0.0) }, box = { square },
            neighbors = { _, _ -> true }, higher = { _, _ -> false })
        assertEquals(listOf(0, 1), placed)
        // 上限に達したあとの項目は取り出さない(山名の計測を省くため)。
        assertEquals(listOf(0, 1), pulled)
    }

    @Test
    fun neighborsAndHeight() {
        fun at(name: String, lat: Double, ele: Double?) = NearbyMountain(Mountain(name.hashCode().toLong(), name, lat, 137.0, ele), 5.0, 0.0)
        val oku = at("奥穂高岳", 36.2894, 3190.0)
        val jandarme = at("ジャンダルム", 36.2862, 3163.0)
        val far = at("遠い山", 36.40, 3000.0)
        assertTrue(PeakLayout.areNeighbors(oku, jandarme))
        assertFalse(PeakLayout.areNeighbors(oku, far))
        assertTrue(PeakLayout.isHigher(oku, jandarme))
        assertFalse(PeakLayout.isHigher(jandarme, oku))
        assertTrue(PeakLayout.isHigher(jandarme, at("不明", 36.2862, null)))
    }

    @Test
    fun aroundLimitScalesWithArea() {
        assertEquals(80, PeakLayout.aroundLimit(40, reachPx = 100.0, viewAreaPx = PI * 100 * 100 / 2))
        // 円が画面より狭くても、画面の上限より減らさない。
        assertEquals(40, PeakLayout.aroundLimit(40, reachPx = 10.0, viewAreaPx = 10000.0))
    }

    private fun boxAt(x: Float) = Box(x, 0f, x + 10f, 10f)

    @Test
    fun placeVisibleDropsOverlapsAtCurrentHeading() {
        val boxes = mapOf("A" to boxAt(0f), "B" to boxAt(5f), "C" to boxAt(20f))
        assertEquals(listOf("A", "C"), PeakLayout.placeVisible(listOf("A", "B", "C"), 10, { boxes.getValue(it) }, { false }))
        assertEquals(listOf("A"), PeakLayout.placeVisible(listOf("A", "B", "C"), 1, { boxes.getValue(it) }, { false }))
    }

    @Test
    fun placeVisibleKeepsDrawnPeaks() {
        val boxes = mapOf("A" to boxAt(0f), "B" to boxAt(5f), "C" to boxAt(40f))
        // 前回描いた B は、優先度の高い A が入ってきても残る。順は優先順のまま。
        assertEquals(listOf("B", "C"), PeakLayout.placeVisible(listOf("A", "B", "C"), 10, { boxes.getValue(it) }, { it != "A" }))
        // 上限に達しているときも、前回描いた山を先に残す。
        assertEquals(listOf("C"), PeakLayout.placeVisible(listOf("A", "C"), 1, { boxes.getValue(it) }, { it == "C" }))
    }

    @Test
    fun placeVisibleNeedsMarginForNewPeaks() {
        val boxes = mapOf("A" to boxAt(0f), "B" to boxAt(12f))
        // 2px しか離れていない B は、新しく出すときは余白 4px に足りないので出さないが、描いているなら残す。
        assertEquals(listOf("A"), PeakLayout.placeVisible(listOf("A", "B"), 10, { boxes.getValue(it) }, { it == "A" }, margin = 4f))
        assertEquals(listOf("A", "B"), PeakLayout.placeVisible(listOf("A", "B"), 10, { boxes.getValue(it) }, { true }, margin = 4f))
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
