package io.github.shohei0205.yamamuki.core

import java.util.Locale
import kotlin.math.abs

/** OSM の山頂ノード1件。 */
data class Mountain(
    val osmId: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** 標高(m)。OSM に ele タグが無い、または解釈できない場合は null。 */
    val elevationM: Double?,
)

/** 現在地から見た山。距離と方位角(真北基準、時計回り 0〜360°)を持つ。 */
data class NearbyMountain(
    val mountain: Mountain,
    val distanceKm: Double,
    val bearingDeg: Double,
)

/** ([latitude], [longitude]) から見たこの山の距離と方位。 */
fun Mountain.seenFrom(latitude: Double, longitude: Double): NearbyMountain = NearbyMountain(
    mountain = this,
    distanceKm = GeoMath.distanceKm(latitude, longitude, this.latitude, this.longitude),
    bearingDeg = GeoMath.bearingDeg(latitude, longitude, this.latitude, this.longitude),
)

/** 方位盤で山アイコンの色と形を分ける標高の区分。 */
enum class ElevationClass {
    /** 1000m 未満。標高不明もここに含める。 */
    LOW,

    /** 1000m 以上 2000m 未満。 */
    MIDDLE,

    /** 2000m 以上。 */
    HIGH,
}

fun Mountain.elevationClass(): ElevationClass {
    val ele = elevationM ?: return ElevationClass.LOW
    return when {
        ele < 1000.0 -> ElevationClass.LOW
        ele < 2000.0 -> ElevationClass.MIDDLE
        else -> ElevationClass.HIGH
    }
}

/** 詳細表示の標高。「1,212 m」、不明なら「不明」。山と現在地で共通に使う。 */
fun elevationText(elevationM: Double?): String {
    val ele = elevationM ?: return "不明"
    return String.format(Locale.US, "%,d m", Math.round(ele))
}

fun Mountain.elevationText(): String = elevationText(elevationM)

/** 詳細表示の緯度経度。狭い画面で途中で折り返さないよう、緯度と経度を改行で分ける。山と現在地で共通に使う。 */
fun coordinateText(latitude: Double, longitude: Double): String {
    val lat = String.format(Locale.US, "%.5f°", abs(latitude))
    val lon = String.format(Locale.US, "%.5f°", abs(longitude))
    return "${if (latitude >= 0) "北緯" else "南緯"} $lat\n${if (longitude >= 0) "東経" else "西経"} $lon"
}

fun Mountain.coordinateText(): String = coordinateText(latitude, longitude)

/** 詳細表示の距離。1km 未満は「850 m」、以上は「12.3 km」。 */
fun distanceText(distanceKm: Double): String =
    if (distanceKm < 1.0) {
        "${Math.round(distanceKm * 1000)} m"
    } else {
        String.format(Locale.US, "%,.1f km", distanceKm)
    }

/**
 * 標高が [minElevationM] 以上の山だけにする。0 以下なら絞り込まない。
 * 絞り込むときは、標高が不明な山は基準を満たすか分からないので除く。
 */
fun Mountain.meetsMinElevation(minElevationM: Int): Boolean {
    if (minElevationM <= 0) return true
    val ele = elevationM ?: return false
    return ele >= minElevationM
}

/** キャッシュ容量の表示。「820 KB」「1.3 MB」。 */
fun byteSizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${Math.round(bytes / 1024.0)} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/**
 * 現在地が山頂にいるとみなす水平距離。山頂に着いてから使う想定なので狭くとる
 * (屋外の GPS の水平誤差 5〜20m に少し余裕を持たせた値)。標高は GPS の誤差が大きいので判定に使わない。
 */
const val SUMMIT_RADIUS_KM = 0.03

/** 現在地から [radiusKm] 以内にある山のうち、いちばん近いもの。無ければ null。 */
fun summitAt(mountains: List<NearbyMountain>, radiusKm: Double = SUMMIT_RADIUS_KM): NearbyMountain? =
    mountains.filter { it.distanceKm <= radiusKm }.minByOrNull { it.distanceKm }
