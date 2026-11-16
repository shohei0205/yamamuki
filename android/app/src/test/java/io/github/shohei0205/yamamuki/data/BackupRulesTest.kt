// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.data

import io.github.shohei0205.yamamuki.core.PeakDataset
import io.github.shohei0205.yamamuki.core.SurfaceDataset
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自動バックアップから外す記録が、データの種類を足したときも漏れないことを確かめる。
 * 記録だけが戻って本体(noBackupFilesDir や DB)が戻らないと、入れ直したときに初回の山データの確認が出ない。
 */
class BackupRulesTest {
    private val rulesFiles = listOf("backup_rules.xml", "data_extraction_rules.xml")

    private fun excludedPrefs(fileName: String): Set<String> {
        val text = File("src/main/res/xml/$fileName").readText()
        return Regex("""<exclude domain="sharedpref" path="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun 山データの種類ごとの記録を外している() {
        val names = PeakDataset.entries.map { PeakDataStore.prefsName(it) + ".xml" }
        rulesFiles.forEach { file ->
            val excluded = excludedPrefs(file)
            names.forEach { assertTrue("$file に $it が無い", it in excluded) }
        }
    }

    @Test
    fun 地表データの種類ごとの記録を外している() {
        val names = SurfaceDataset.entries.map { "surface_data_${it.id}.xml" }
        rulesFiles.forEach { file ->
            val excluded = excludedPrefs(file)
            names.forEach { assertTrue("$file に $it が無い", it in excluded) }
        }
    }
}
