package io.github.shohei0205.yamamuki.core

import java.text.Normalizer
import java.util.Locale

/**
 * 山の検索。山名・よみがな・別名のどれかに、入力した文字を含む山を探す。
 * 作るときに全件の文字をそろえておき、入力が変わるたびに [search] で絞り込む。
 */
class MountainSearch(mountains: List<Mountain>) {
    private class Entry(val mountain: Mountain, val keys: List<String>)

    private val entries = mountains.map { m ->
        Entry(m, (listOf(m.name) + listOfNotNull(m.nameReading) + m.aliases).map(::normalize).filter { it.isNotEmpty() })
    }

    /**
     * [query] を含む山を、([latitude], [longitude]) から近い順に最大 [limit] 件返す。
     * 地点が無ければ標高の高い順(標高不明は最後)。並びが同じなら osmId の順。入力が空白だけなら空。
     */
    fun search(query: String, latitude: Double?, longitude: Double?, limit: Int = MAX_RESULTS): List<Mountain> {
        val key = normalize(query)
        if (key.isEmpty()) return emptyList()
        val hits = entries.filter { e -> e.keys.any { it.contains(key) } }.map { it.mountain }
        val sorted = if (latitude != null && longitude != null) {
            hits.map { it to GeoMath.distanceKm(latitude, longitude, it.latitude, it.longitude) }
                .sortedWith(compareBy<Pair<Mountain, Double>> { it.second }.thenBy { it.first.osmId })
                .map { it.first }
        } else {
            hits.sortedWith(compareByDescending<Mountain> { it.elevationM ?: Double.NEGATIVE_INFINITY }.thenBy { it.osmId })
        }
        return sorted.take(limit)
    }

    companion object {
        /** 一度に返す件数の上限。 */
        const val MAX_RESULTS = 50

        /**
         * 比べるための文字の形。全角と半角(NFKC)、大文字と小文字、カタカナとひらがなの違いをなくし、空白を除く。
         * 小さい仮名は大きい仮名にそろえる(「三ッ峠」と「三ツ峠」、「槍ヶ岳」と「槍ケ岳」を同じに扱う)。
         */
        fun normalize(text: String): String {
            val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            val out = StringBuilder(nfkc.length)
            for (c in nfkc) {
                when {
                    c.isWhitespace() -> Unit
                    c == 'ヵ' || c == 'ゕ' -> out.append('か')
                    c == 'ヶ' || c == 'ゖ' -> out.append('け')
                    c in 'ァ'..'ヴ' -> out.append(large(c - 0x60))
                    else -> out.append(large(c))
                }
            }
            return out.toString()
        }

        /** 小さいひらがな(ぁぃぅぇぉっゃゅょゎ)は大きいひらがなにする。 */
        private fun large(c: Char): Char = if (c in SMALL_KANA) c + 1 else c

        private const val SMALL_KANA = "ぁぃぅぇぉっゃゅょゎ"
    }
}
