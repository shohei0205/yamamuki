// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import java.text.Normalizer
import java.util.Locale

/**
 * 山の検索。山名・よみがな・別名のどれかに、入力した文字を含む山を探す。先頭が一致した山を先に並べる。
 * 作るときに全件の文字をそろえておき、入力が変わるたびに [search] で絞り込む。
 */
class MountainSearch(mountains: List<Mountain>) {
    private class Entry(val mountain: Mountain, val keys: List<String>)

    private val entries = mountains.map { m ->
        Entry(m, (listOf(m.name) + listOfNotNull(m.nameReading) + m.aliases).map(::normalize).filter { it.isNotEmpty() })
    }

    /**
     * 検索の結果。山名・よみがな・別名のどれかの先頭が入力と一致した山([prefix])と、途中に含む山([partial])に分ける。
     * 「や」で「槍ヶ岳(やりがたけ)」を探すときに、「〜山(やま)」の山が先に並ばないようにするため。
     */
    data class Results(val prefix: List<Mountain>, val partial: List<Mountain>) {
        /** 先頭が一致した山、途中に含む山の順に並べたもの。 */
        val all: List<Mountain> get() = prefix + partial

        fun isEmpty(): Boolean = prefix.isEmpty() && partial.isEmpty()

        companion object {
            val EMPTY = Results(emptyList(), emptyList())
        }
    }

    /**
     * [query] を含む山を、先頭が一致した山、途中に含む山の順に、合わせて最大 [limit] 件返す。
     * それぞれの中は ([latitude], [longitude]) から近い順。地点が無ければ標高の高い順(標高不明は最後)。
     * 並びが同じなら地点の ID の順。入力が空白だけなら空。
     * 入力が仮名か英数字 1 文字だけのときは、途中に含む山が多すぎて探しにくいので返さない(漢字 1 文字なら返す)。
     * ただし先頭が一致した山が 1 つも無いときは、何も出ないよりよいので返す。
     */
    fun search(query: String, latitude: Double?, longitude: Double?, limit: Int = MAX_RESULTS): Results {
        val key = normalize(query)
        if (key.isEmpty()) return Results.EMPTY
        val (prefix, partial) = entries.filter { e -> e.keys.any { it.contains(key) } }
            .partition { e -> e.keys.any { it.startsWith(key) } }
        val prefixSorted = sort(prefix.map { it.mountain }, latitude, longitude).take(limit)
        if (prefixSorted.isNotEmpty() && key.length == 1 && isKanaOrAlphanumeric(key[0])) return Results(prefixSorted, emptyList())
        val partialSorted = sort(partial.map { it.mountain }, latitude, longitude).take(limit - prefixSorted.size)
        return Results(prefixSorted, partialSorted)
    }

    private fun sort(hits: List<Mountain>, latitude: Double?, longitude: Double?): List<Mountain> =
        if (latitude != null && longitude != null) {
            hits.map { it to GeoMath.distanceKm(latitude, longitude, it.latitude, it.longitude) }
                .sortedWith(compareBy<Pair<Mountain, Double>> { it.second }.thenBy { it.first.id })
                .map { it.first }
        } else {
            hits.sortedWith(compareByDescending<Mountain> { it.elevationM ?: Double.NEGATIVE_INFINITY }.thenBy { it.id })
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

        /** そろえたあとの文字が、ひらがな(カタカナもひらがなにそろえてある)、長音記号、英数字(全角も半角にそろえてある)のどれか。 */
        private fun isKanaOrAlphanumeric(c: Char): Boolean =
            c in '\u3041'..'\u309F' || c == 'ー' || c in 'a'..'z' || c in '0'..'9'

        /** 小さいひらがな(ぁぃぅぇぉっゃゅょゎ)は大きいひらがなにする。 */
        private fun large(c: Char): Char = if (c in SMALL_KANA) c + 1 else c

        private const val SMALL_KANA = "ぁぃぅぇぉっゃゅょゎ"
    }
}
