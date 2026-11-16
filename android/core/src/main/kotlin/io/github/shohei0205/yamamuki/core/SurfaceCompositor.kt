// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 地表データのレイヤーの種類。ID と役割(画素の読み方)の組は、地表の形式の版 1 で決まっている。
 * データセットや配色が変わっても同じ意味で使う。
 */
enum class SurfaceLayerKind(val id: String, val role: String) {
    /** 森林の濃さ。0 は森林なし、255 は全面が森林。 */
    FOREST("forest", "mask"),

    /** 水域(海・川・湖)の濃さ。0 は水域なし、255 は全面が水域。 */
    WATER("water", "mask"),

    /** 標高から作った陰影。中立の値との差で、下に描いた色の明るさを変える。 */
    HILLSHADE("hillshade", "hillshade"),
    ;

    companion object {
        /**
         * SQLite の surface_layers の id と role から種類を決める。大文字と小文字を区別して比べる。
         * 知らない ID と、ID と役割の組が決まりと違う行は null(その種類のレイヤーとして扱わない)。
         */
        fun of(id: String?, role: String?): SurfaceLayerKind? = entries.firstOrNull { it.id == id && it.role == role }
    }
}

/** 地表を塗る色。どれも 0xRRGGBB。データに入っている既定の色ではなく、アプリの配色を渡す。 */
data class SurfacePalette(
    /** 森林でも水域でもない地面の色。 */
    val ground: Int,
    val forest: Int,
    val water: Int,
) {
    companion object {
        /**
         * 方位盤の背景に使う配色。地面は方位盤の地面と同じクリーム色で、森林と水域は山のアイコンや山名の札が
         * 埋もれないように淡くする。
         */
        val DIAL = SurfacePalette(ground = 0xFFF4D8, forest = 0xC5DDA8, water = 0xA8D4EE)
    }
}

/** 陰影の掛け方。陰影の画素が [neutral] のとき明るさを変えず、差に [strength] を掛けた分だけ明るく・暗くする。 */
data class SurfaceShade(val neutral: Int, val strength: Double) {
    companion object {
        /**
         * 陰影のレイヤーの設定(surface_layers の settings_json)を読む。
         * JSON として読めない、neutral が 1〜255 の整数でない、strength が 0 以上の数でないときは null。
         */
        fun parse(settingsJson: String): SurfaceShade? {
            val root = try {
                SimpleJson.parse(settingsJson)
            } catch (e: IllegalArgumentException) {
                return null
            } as? Map<*, *> ?: return null
            val neutral = when (val value = root["neutral"]) {
                is Long -> value
                is Double -> value.toLong().takeIf { it.toDouble() == value }
                else -> null
            }?.takeIf { it in 1..255 } ?: return null
            val strength = when (val value = root["strength"]) {
                is Long -> value.toDouble()
                is Double -> value
                else -> return null
            }
            if (!strength.isFinite() || strength < 0.0) return null
            return SurfaceShade(neutral.toInt(), strength)
        }
    }
}

/**
 * 地表のタイル 1 枚ぶんの、森林・陰影・水域を 1 枚の画像に合成する。
 * 画像の復号はアプリ側で行い、ここには 1 画素 1 バイト(0〜255)の濃さを渡す。
 * 計算は整数で行い、Android 版と iOS 版で同じ色になるようにしている。
 */
object SurfaceCompositor {
    /** 陰影の倍率を整数で持つときの 1.0。 */
    private const val GAIN_ONE = 1024

    /**
     * 森林の濃さで地面の色から森林の色へ塗り、陰影で明るさを変え、その上に水域の色を濃さの分だけ重ねる。
     * 水域の色には陰影を掛けない。
     *
     * 無いレイヤーは null で渡す。森林が無い場所は地面を塗らず、水域だけを濃さを透明度にして描く(水域も無ければ透明)。
     * 陰影が無いか [shade] が null なら、明るさを変えない。
     *
     * @return 左上から行ごとに並べた、一辺 [tilePixels] の画像。1 画素は 0xAARRGGBB(色に透明度を掛けていない)。
     * @throws IllegalArgumentException 渡した濃さの長さが [tilePixels] の 2 乗と合わないとき。
     */
    fun compose(
        tilePixels: Int,
        forest: ByteArray?,
        hillshade: ByteArray?,
        water: ByteArray?,
        palette: SurfacePalette,
        shade: SurfaceShade?,
    ): IntArray {
        require(tilePixels > 0) { "タイルの一辺 $tilePixels が正の数ではありません" }
        val count = tilePixels * tilePixels
        for ((name, layer) in listOf("forest" to forest, "hillshade" to hillshade, "water" to water)) {
            require(layer == null || layer.size == count) { "$name の画素数 ${layer?.size} が $count ではありません" }
        }
        val gains = if (hillshade != null && shade != null) gainTable(shade) else null
        val groundR = palette.ground shr 16 and 0xFF
        val groundG = palette.ground shr 8 and 0xFF
        val groundB = palette.ground and 0xFF
        val forestR = palette.forest shr 16 and 0xFF
        val forestG = palette.forest shr 8 and 0xFF
        val forestB = palette.forest and 0xFF
        val waterR = palette.water shr 16 and 0xFF
        val waterG = palette.water shr 8 and 0xFF
        val waterB = palette.water and 0xFF

        val out = IntArray(count)
        for (i in 0 until count) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            if (forest != null) {
                val m = forest[i].toInt() and 0xFF
                a = 255
                r = mix(groundR, forestR, m)
                g = mix(groundG, forestG, m)
                b = mix(groundB, forestB, m)
                if (gains != null && hillshade != null) {
                    val gain = gains[hillshade[i].toInt() and 0xFF]
                    r = scale(r, gain)
                    g = scale(g, gain)
                    b = scale(b, gain)
                }
            }
            if (water != null) {
                val m = water[i].toInt() and 0xFF
                if (a == 255) {
                    r = mix(r, waterR, m)
                    g = mix(g, waterG, m)
                    b = mix(b, waterB, m)
                } else if (m > 0) {
                    a = m
                    r = waterR
                    g = waterG
                    b = waterB
                }
            }
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** 陰影の画素の値ごとの、明るさの倍率([GAIN_ONE] が 1.0)。 */
    private fun gainTable(shade: SurfaceShade): IntArray = IntArray(256) { value ->
        val gain = 1.0 + shade.strength * (value - shade.neutral) / shade.neutral
        max(0, (gain * GAIN_ONE).roundToInt())
    }

    /** [from] から [to] へ、濃さ [m](0〜255)の分だけ寄せた色。 */
    private fun mix(from: Int, to: Int, m: Int): Int = (from * (255 - m) + to * m + 127) / 255

    private fun scale(color: Int, gain: Int): Int = min(255, (color * gain + GAIN_ONE / 2) / GAIN_ONE)
}
