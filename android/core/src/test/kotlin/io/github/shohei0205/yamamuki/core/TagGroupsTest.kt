// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagGroupsTest {
    private fun peak(id: Long, vararg tags: String) = Mountain("osm:$id", "山$id", 35.0, 138.0, 1000.0, tags = tags.toList())

    @Test
    fun groupsAreCountedAndSortedByName() {
        val groups = TagGroups.of(
            listOf(
                listOf("日本百名山"),
                listOf("日本百名山", "九州百名山"),
                listOf("九州百名山", "九州百名山"),
                emptyList(),
                listOf("北海道百名山"),
            ),
        )
        // 文字コード順。九(4E5D) < 北(5317) < 日(65E5)。
        assertEquals(
            listOf(TagGroup("九州百名山", 2), TagGroup("北海道百名山", 1), TagGroup("日本百名山", 2)),
            groups,
        )
    }

    @Test
    fun noTagsMeansNoGroups() {
        assertEquals(emptyList(), TagGroups.of(listOf(emptyList(), listOf(""))))
    }

    @Test
    fun effectiveDropsGroupsMissingFromData() {
        val groups = listOf(TagGroup("九州百名山", 100), TagGroup("日本百名山", 100))
        assertEquals(listOf("日本百名山"), TagGroups.effective(setOf("日本百名山", "消えたグループ"), groups))
        assertEquals(emptyList(), TagGroups.effective(setOf("消えたグループ"), groups))
        assertEquals(emptyList(), TagGroups.effective(emptySet(), groups))
    }

    @Test
    fun effectiveKeepsSelectionWhileGroupsAreUnknown() {
        assertEquals(listOf("九州百名山", "日本百名山"), TagGroups.effective(setOf("日本百名山", "九州百名山"), null))
    }

    @Test
    fun peaksInAnySelectedGroupMatch() {
        val selected = listOf("九州百名山", "日本百名山")
        assertTrue(peak(1, "日本百名山").inTagGroups(selected))
        assertTrue(peak(2, "九州百名山", "その他").inTagGroups(selected))
        assertFalse(peak(3, "北海道百名山").inTagGroups(selected))
        assertFalse(peak(4).inTagGroups(selected))
    }

    @Test
    fun emptySelectionShowsEveryPeak() {
        assertTrue(peak(1).inTagGroups(emptyList()))
        assertTrue(peak(2, "日本百名山").inTagGroups(emptyList()))
    }
}
