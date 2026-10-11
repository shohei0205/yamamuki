package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MountainTextTest {
    private val fuji = Mountain(1L, "富士山", 35.3605556, 138.7273889, 3776.24)

    @Test
    fun elevation() {
        assertEquals("3,776 m", fuji.elevationText())
        assertNull(fuji.copy(elevationM = null).elevationText())
        assertEquals("852 m", elevationText(851.6))
        assertNull(elevationText(null))
    }

    @Test
    fun coordinate() {
        assertEquals("35.36056°", degreeText(fuji.latitude))
        assertEquals("138.72739°", degreeText(fuji.longitude))
        assertEquals("33.86880°", degreeText(-33.8688))
        assertEquals("151.20930°", degreeText(-151.2093))
    }

    @Test
    fun distance() {
        assertEquals("850 m", distanceText(0.8504))
        assertEquals("12.3 km", distanceText(12.34))
        assertEquals("1,234.6 km", distanceText(1234.56))
    }

    @Test
    fun minElevationFilter() {
        assertEquals(true, fuji.meetsMinElevation(0))
        assertEquals(true, fuji.copy(elevationM = null).meetsMinElevation(0))
        assertEquals(true, fuji.meetsMinElevation(3776))
        assertEquals(false, fuji.meetsMinElevation(3800))
        assertEquals(false, fuji.copy(elevationM = null).meetsMinElevation(100))
    }

    @Test
    fun byteSize() {
        assertEquals("512 B", byteSizeText(512))
        assertEquals("820 KB", byteSizeText(820L * 1024))
        assertEquals("1.3 MB", byteSizeText(1_363_149))
    }

    @Test
    fun summitIsNearestWithinRadius() {
        fun near(id: Long, km: Double) = NearbyMountain(fuji.copy(osmId = id), km, 0.0)
        assertEquals(null, summitAt(emptyList()))
        assertEquals(null, summitAt(listOf(near(1, 0.031), near(2, 3.0))))
        assertEquals(2L, summitAt(listOf(near(1, 0.025), near(2, 0.01), near(3, 5.0)))?.mountain?.osmId)
        assertEquals(1L, summitAt(listOf(near(1, 0.03)))?.mountain?.osmId)
    }
}
