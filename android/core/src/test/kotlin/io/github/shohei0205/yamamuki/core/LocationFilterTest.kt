package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationFilterTest {
    @Test
    fun acceptsFirstLocation() {
        assertTrue(LocationFilter().accept(0, 800.0))
    }

    @Test
    fun rejectsUnknownAccuracy() {
        val filter = LocationFilter()
        assertFalse(filter.accept(0, null))
        assertFalse(filter.accept(0, -1.0))
        assertFalse(filter.accept(0, Double.NaN))
    }

    @Test
    fun rejectsCoarseLocationBetweenGpsFixes() {
        // GPS(誤差 10m) の合間に届いたネットワーク位置(誤差 800m)は捨てる。
        val filter = LocationFilter()
        assertTrue(filter.accept(0, 10.0))
        assertFalse(filter.accept(5_000, 800.0))
        assertTrue(filter.accept(10_000, 12.0))
    }

    @Test
    fun acceptsSlightlyWorseAccuracy() {
        val filter = LocationFilter()
        assertTrue(filter.accept(0, 10.0))
        assertTrue(filter.accept(5_000, 60.0))
    }

    @Test
    fun acceptsBetterAccuracy() {
        val filter = LocationFilter()
        assertTrue(filter.accept(0, 800.0))
        assertTrue(filter.accept(5_000, 10.0))
    }

    @Test
    fun keepsRejectingCoarseLocationWhileStandingStill() {
        // 立ち止まって GPS が届かず、ネットワーク位置(誤差 800m)だけが 10 秒ごとに届いても、数分は使わない。
        val filter = LocationFilter()
        assertTrue(filter.accept(0, 8.0))
        for (t in 10_000L..300_000L step 10_000L) assertFalse(filter.accept(t, 800.0), "${t}ms")
    }

    @Test
    fun acceptsCoarseLocationWhenGoodOnesStopLong() {
        // 良い位置が 6 分ほど届かなければ、粗い位置でも使い、以降はそれを基準にする。
        val filter = LocationFilter()
        assertTrue(filter.accept(0, 10.0))
        assertFalse(filter.accept(360_000, 800.0))
        assertTrue(filter.accept(380_000, 800.0))
        assertTrue(filter.accept(385_000, 700.0))
    }

    @Test
    fun followsGpsWhoseAccuracyDropsGradually() {
        // GPS の誤差が 5m から 70m に落ちても(樹林帯など)、数秒で使い始める。
        val filter = LocationFilter()
        assertTrue(filter.accept(0, 5.0))
        assertFalse(filter.accept(5_000, 70.0))
        assertTrue(filter.accept(10_000, 70.0))
    }

    @Test
    fun oldLastKnownLocationDoesNotBlockNewFixes() {
        // 起動直後に使った古い位置(1 時間前、誤差 5m)の後でも、今の粗い位置を使う。
        val filter = LocationFilter()
        assertTrue(filter.accept(0, 5.0))
        assertTrue(filter.accept(3_600_000, 300.0))
    }

    @Test
    fun rejectsOlderOrSameTime() {
        val filter = LocationFilter()
        assertTrue(filter.accept(10_000, 10.0))
        assertFalse(filter.accept(10_000, 5.0))
        assertFalse(filter.accept(5_000, 5.0))
    }
}
