// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

/**
 * 地点の `tags`(「日本百名山」などの分類ラベル)で分けた山のグループ。
 * タグ名はコードに持たず、保存している山データにあるものをそのまま一覧にする。
 */
data class TagGroup(
    val name: String,
    /** このタグを持つ山の数。 */
    val count: Int,
)

object TagGroups {
    /**
     * 山ごとのタグの一覧から、グループの一覧を作る。同じ名前はまとめて山の数を数え、名前順(文字コード順)に並べる。
     * 1 つの山に同じタグが重なっていても 1 山と数える。
     */
    fun of(tagLists: Iterable<List<String>>): List<TagGroup> {
        val counts = HashMap<String, Int>()
        for (tags in tagLists) {
            for (tag in tags.toSet()) {
                if (tag.isNotEmpty()) counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts.map { TagGroup(it.key, it.value) }.sortedBy { it.name }
    }

    /**
     * 実際に絞り込みに使うグループ。選んだもののうち、今の山データにあるものだけを名前順に返す。
     * 山データの更新で選んだタグが無くなったときに、山が 1 つも出なくなるのを防ぐ(空なら絞り込まない)。
     * [groups] がまだ分からない(読み込み中)ときは null を渡し、選んだものをそのまま使う。
     */
    fun effective(selected: Set<String>, groups: List<TagGroup>?): List<String> {
        if (groups == null) return selected.sorted()
        return groups.map { it.name }.filter { it in selected }
    }
}

/** [groups] のどれかのタグを持つ山か。[groups] が空なら絞り込まないので、どの山も当てはまる。 */
fun Mountain.inTagGroups(groups: Collection<String>): Boolean =
    groups.isEmpty() || tags.any { it in groups }
