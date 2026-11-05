// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.settings

import android.Manifest
import android.app.Application
import android.content.Context
import io.github.shohei0205.yamamuki.core.InstalledPeakData
import io.github.shohei0205.yamamuki.core.PeakDataset
import io.github.shohei0205.yamamuki.data.PeakDataStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 初回起動の確認を済ませたかの記録が、自動バックアップから戻った設定に引きずられないかを確かめる。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppSettingsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        listOf(AppSettings.PREFS_NAME, AppSettings.FIRST_LAUNCH_PREFS_NAME).forEach { prefs(it).edit().clear().commit() }
        PeakDataset.entries.forEach { PeakDataStore(context, it).clear() }
    }

    @Test
    fun firstLaunchIsNotAsked() {
        assertFalse(AppSettings(context).settings.value.peakDataAsked)
    }

    @Test
    fun answerIsKeptOutsideBackedUpSettings() {
        AppSettings(context).update { it.copy(peakDataAsked = true) }

        assertFalse(prefs(AppSettings.PREFS_NAME).contains(KEY))
        assertTrue(AppSettings(context).settings.value.peakDataAsked)
    }

    @Test
    fun restoredAnswerWithoutPeakDataIsAskedAgain() {
        // 0.10.0 までのバックアップから settings だけが戻った状態(山データの記録と位置情報の許可は戻らない)。
        prefs(AppSettings.PREFS_NAME).edit().putBoolean(KEY, true).commit()

        assertFalse(AppSettings(context).settings.value.peakDataAsked)
        assertFalse(prefs(AppSettings.PREFS_NAME).contains(KEY))
    }

    @Test
    fun answerIsCarriedOverWhenPeakDataIsInstalled() {
        prefs(AppSettings.PREFS_NAME).edit().putBoolean(KEY, true).commit()
        PeakDataStore(context, PeakDataset.OSM_PEAKS).save(installed())

        assertTrue(AppSettings(context).settings.value.peakDataAsked)
    }

    @Test
    fun answerIsCarriedOverWhenLocationIsGranted() {
        // 「あとで」を選んでから位置情報を許した利用者は、山データが無くても答え済みのまま。
        prefs(AppSettings.PREFS_NAME).edit().putBoolean(KEY, true).commit()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertTrue(AppSettings(context).settings.value.peakDataAsked)
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun installed() = InstalledPeakData(
        version = "2026-10-07",
        sourceTimestamp = "",
        mountainCount = 1,
        manifestEtag = null,
        installedAtMillis = 0,
        readerVersion = 1,
        dataSha256 = null,
        manifestSha256 = null,
        experimental = false,
    )

    private companion object {
        const val KEY = "peak_data_asked"
    }
}
