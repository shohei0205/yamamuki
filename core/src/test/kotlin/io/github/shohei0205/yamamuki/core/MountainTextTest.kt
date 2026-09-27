package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MountainTextTest {
    private val fuji = Mountain(1L, "富士山", 35.3605556, 138.7273889, 3776.24)

    @Test
    fun elevation() {
        assertEquals("3,776 m", fuji.elevationText())
        assertEquals("不明", fuji.copy(elevationM = null).elevationText())
        assertEquals("852 m", elevationText(851.6))
        assertEquals("不明", elevationText(null))
    }

    @Test
    fun coordinate() {
        assertEquals("北緯 35.36056°\n東経 138.72739°", fuji.coordinateText())
        assertEquals("南緯 33.86880°\n西経 151.20930°", fuji.copy(latitude = -33.8688, longitude = -151.2093).coordinateText())
        assertEquals("北緯 35.68124°\n東経 139.76713°", coordinateText(35.681236, 139.767126))
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
