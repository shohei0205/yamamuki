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
    fun directionIndexes() {
        assertEquals(0, Heading.directionIndex(0.0))
        assertEquals(0, Heading.directionIndex(355.0))
        assertEquals(1, Heading.directionIndex(20.0))
        assertEquals(6, Heading.directionIndex(135.0))
        assertEquals(12, Heading.directionIndex(-90.0))
        assertEquals(15, Heading.directionIndex(340.0))
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

    @Test
    fun filterAlphaKeepsResponseTimeForSlowerSensor() {
        assertEquals(0.15, HeadingFilter.alphaForPeriod(20.0), 1e-9)
        // 60 ミリ秒ごとに 1 回動かした結果は、20 ミリ秒ごとに 3 回動かした結果と同じになる。
        val fast = HeadingFilter(alpha = HeadingFilter.alphaForPeriod(20.0))
        val slow = HeadingFilter(alpha = HeadingFilter.alphaForPeriod(60.0))
        fast.update(0.0)
        slow.update(0.0)
        fast.update(90.0)
        fast.update(90.0)
        assertEquals(fast.update(90.0), slow.update(90.0), 1e-9)
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

    @Test
    fun candidatesAreLimitedByReachAndCount() {
        val near = m("近い", 1000.0, 2.0)
        val far = m("遠い", 3000.0, 30.0)
        val nearLow = m("低い", 100.0, 3.0)
        val all = listOf(far, nearLow, near)
        assertEquals(listOf("近い", "低い"), PeakLayout.candidates(all, 0.0, emptySet(), reachKm = 10.0, limit = 5).map { it.mountain.name })
        assertEquals(listOf("近い"), PeakLayout.candidates(all, 0.0, emptySet(), reachKm = 50.0, limit = 1).map { it.mountain.name })
    }

    @Test
    fun candidatesAroundScreenCenter() {
        // 現在地のそばの高い山は、画面の周りの円(現在地から北東 60km の点を中心に 5km)の外なので選ばない。
        val nearHigh = NearbyMountain(Mountain(1, "手前", 0.0, 0.0, 2000.0), 5.0, 180.0)
        val target = NearbyMountain(Mountain(2, "筑波山", 0.0, 0.0, 877.0), 60.0, 45.0)
        val next = NearbyMountain(Mountain(3, "宝篋山", 0.0, 0.0, 461.0), 62.0, 47.0)
        val far = NearbyMountain(Mountain(4, "外", 0.0, 0.0, 900.0), 60.0, 60.0)
        val all = listOf(nearHigh, target, next, far)
        assertEquals(listOf("手前"), PeakLayout.candidates(all, 0.0, emptySet(), reachKm = 70.0, limit = 1).map { it.mountain.name })
        assertEquals(listOf("筑波山", "宝篋山"),
            PeakLayout.candidates(all, 0.0, emptySet(), reachKm = 5.0, limit = 2, centerKm = 60.0, centerBearingDeg = 45.0)
                .map { it.mountain.name })
    }

    @Test
    fun candidatesKeepDrawnPeaks() {
        val high = m("高い", 3000.0, 10.0)
        val mid = m("中", 2000.0, 10.0)
        val low = m("低い", 1000.0, 10.0)
        val out = m("外", 500.0, 40.0)
        val all = listOf(high, mid, low, out)
        // 前回描いた「低い」は上限によらず残し、残りの枠を優先順に埋める。円の外の山は描いていても残さない。
        val drawn = setOf(low.mountain.osmId, out.mountain.osmId)
        assertEquals(listOf("高い", "低い"),
            PeakLayout.candidates(all, 0.0, emptySet(), reachKm = 20.0, limit = 2, drawnIds = drawn).map { it.mountain.name })
    }

    @Test
    fun forwardBonusFadesToTheEdgeOfTheFan() {
        assertEquals(1.0, PeakLayout.forwardBonus(0.0, 0.0), 1e-9)
        assertEquals(0.5, PeakLayout.forwardBonus(15.0, 0.0), 1e-9)
        assertEquals(2.0 / 3, PeakLayout.forwardBonus(350.0, 0.0), 1e-9)
        assertEquals(0.0, PeakLayout.forwardBonus(45.0, 0.0), 1e-9)
    }

    @Test
    fun headingUpPrefersPeaksAhead() {
        // 横の山のほうが少し高く見えても、ヘディングアップでは正面の山を先にする。
        val ahead = NearbyMountain(Mountain(1, "正面", 0.0, 0.0, 1000.0), 10.0, 0.0)
        val side = NearbyMountain(Mountain(2, "横", 0.0, 0.0, 1100.0), 10.0, 90.0)
        assertEquals(listOf("横", "正面"), PeakLayout.priorityOrder(listOf(ahead, side), 0.0).map { it.mountain.name })
        assertEquals(listOf("正面", "横"),
            PeakLayout.priorityOrder(listOf(ahead, side), 0.0, headingDeg = 0.0).map { it.mountain.name })
        assertEquals(listOf("正面"),
            PeakLayout.candidates(listOf(ahead, side), 0.0, emptySet(), reachKm = 20.0, limit = 1, headingDeg = 0.0)
                .map { it.mountain.name })
    }

    @Test
    fun heightBonusGrowsWithRange() {
        assertEquals(0.0, PeakLayout.heightBonus(2000.0, 20.0), 1e-9)
        assertEquals(2.0, PeakLayout.heightBonus(2000.0, 40.0), 1e-9)
        assertEquals(4.0, PeakLayout.heightBonus(2000.0, 60.0), 1e-9)
        assertEquals(4.0, PeakLayout.heightBonus(2000.0, 80.0), 1e-9)
        assertEquals(0.0, PeakLayout.heightBonus(null, 80.0), 1e-9)
    }

    @Test
    fun wideRangePrefersHighPeaksFarAway() {
        // 近くの低い山のほうが高く見えても、表示範囲が広いときは遠くの高い山を先にする。
        val near = NearbyMountain(Mountain(1, "近くの低い山", 0.0, 0.0, 400.0), 5.0, 0.0)
        val far = NearbyMountain(Mountain(2, "遠くの高い山", 0.0, 0.0, 2500.0), 70.0, 0.0)
        assertEquals(listOf("近くの低い山", "遠くの高い山"),
            PeakLayout.priorityOrder(listOf(near, far), 0.0, rangeKm = 15.0).map { it.mountain.name })
        assertEquals(listOf("遠くの高い山", "近くの低い山"),
            PeakLayout.priorityOrder(listOf(near, far), 0.0, rangeKm = 80.0).map { it.mountain.name })
    }

    @Test
    fun planeDistance() {
        assertEquals(5.0, PeakLayout.planeDistanceKm(3.0, 0.0, 4.0, 90.0), 1e-9)
        assertEquals(2.0, PeakLayout.planeDistanceKm(1.0, 350.0, 1.0, 170.0), 1e-9)
        assertEquals(0.0, PeakLayout.planeDistanceKm(7.0, 30.0, 7.0, 30.0), 1e-9)
    }

    @Test
    fun neighbors() {
        fun at(name: String, lat: Double) = NearbyMountain(Mountain(name.hashCode().toLong(), name, lat, 137.0, 1000.0), 5.0, 0.0)
        assertTrue(PeakLayout.areNeighbors(at("奥穂高岳", 36.2894), at("ジャンダルム", 36.2862)))
        assertFalse(PeakLayout.areNeighbors(at("奥穂高岳", 36.2894), at("遠い山", 36.40)))
    }

    @Test
    fun aroundLimitScalesWithArea() {
        assertEquals(80, PeakLayout.aroundLimit(40, reachPx = 100.0, viewAreaPx = PI * 100 * 100 / 2))
        // 円が画面より狭くても、画面の上限より減らさない。
        assertEquals(40, PeakLayout.aroundLimit(40, reachPx = 10.0, viewAreaPx = 10000.0))
    }

    /** 名前の 1 文字目が同じ山を同じ山塊とし、名前の数字を標高とみなす。箱は x の位置に幅 10。 */
    private fun place(
        visible: List<String>,
        xs: Map<String, Float>,
        limit: Int = 10,
        drawn: Set<String> = emptySet(),
        margin: Float = 0f,
    ): List<Pair<String, List<String>>> =
        PeakLayout.placeVisible(
            visible, limit,
            box = { Box(xs.getValue(it), 0f, xs.getValue(it) + 10f, 10f) },
            drawnBefore = { it in drawn },
            neighbors = { a, b -> a[0] == b[0] },
            elevationM = { it.drop(1).toDoubleOrNull() },
            margin = margin,
        ).map { it.peak to it.members }

    @Test
    fun placeVisibleGroupsOverlapsAtCurrentHeading() {
        val xs = mapOf("A1" to 0f, "B1" to 5f, "C1" to 20f)
        // B1 は A1 と重なるので山名を省き、A1 にまとめる。
        assertEquals(listOf("A1" to listOf("B1"), "C1" to emptyList()), place(listOf("A1", "B1", "C1"), xs))
        // 上限で省いた C1 は、どの山とも重ならないのでまとめない。
        assertEquals(listOf("A1" to listOf("B1")), place(listOf("A1", "B1", "C1"), xs, limit = 1))
    }

    @Test
    fun placeVisibleKeepsDrawnPeaks() {
        val xs = mapOf("A1" to 0f, "B1" to 5f, "C1" to 40f)
        // 前回描いた B1 は、優先度の高い A1 が入ってきても残る。順は優先順のまま。
        assertEquals(listOf("B1" to listOf("A1"), "C1" to emptyList()), place(listOf("A1", "B1", "C1"), xs, drawn = setOf("B1", "C1")))
        // 上限に達しているときも、前回描いた山を先に残す。
        assertEquals(listOf("C1" to emptyList<String>()), place(listOf("A1", "C1"), xs, limit = 1, drawn = setOf("C1")))
    }

    @Test
    fun placeVisibleNeedsMarginForNewPeaks() {
        val xs = mapOf("A1" to 0f, "B1" to 12f)
        // 2px しか離れていない B1 は、新しく出すときは余白 4px に足りないので山名を出さないが、描いているなら残す。
        assertEquals(listOf("A1" to listOf("B1")), place(listOf("A1", "B1"), xs, drawn = setOf("A1"), margin = 4f))
        assertEquals(listOf("A1" to emptyList(), "B1" to emptyList()), place(listOf("A1", "B1"), xs, drawn = setOf("A1", "B1"), margin = 4f))
    }

    @Test
    fun placeVisiblePrefersHigherNeighbor() {
        val xs = mapOf("A2900" to 0f, "A3190" to 5f)
        // 仰角で先に来る A2900 が描いてあっても、そばの高い A3190 と重なるなら A3190 を出す。
        assertEquals(listOf("A3190" to listOf("A2900")), place(listOf("A2900", "A3190"), xs, drawn = setOf("A2900")))
    }

    @Test
    fun placeVisibleResolvesChainOfNeighborsByHeight() {
        // A1 < A2 < A3 が鎖のように並び、A1 と A2、A2 と A3 は重なるが、A1 と A3 は重ならない。
        // 並ぶ順によらず、A3 を残して A2 を省き、A2 が省かれたので A1 は残す。
        val xs = mapOf("A1" to 0f, "A2" to 8f, "A3" to 16f)
        val expected = listOf("A1" to emptyList(), "A3" to listOf("A2"))
        assertEquals(expected, place(listOf("A1", "A2", "A3"), xs))
        assertEquals(listOf("A3" to listOf("A2"), "A1" to emptyList()), place(listOf("A3", "A2", "A1"), xs))
    }

    @Test
    fun placeVisibleDoesNotLetOtherMassifsDominate() {
        // 別の山塊の高い山は、重なっても優先しない(優先順で先に置いた山が残る)。
        val xs = mapOf("A1000" to 0f, "B3000" to 5f)
        assertEquals(listOf("A1000" to listOf("B3000")), place(listOf("A1000", "B3000"), xs))
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
