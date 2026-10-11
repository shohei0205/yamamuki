package io.github.shohei0205.yamamuki.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * yamamuki の Pages に置いた山データの参照先ファイル(site/data/osm-peaks/current.json)が指す manifest を、
 * アプリの読み込み処理で読めるかを確かめる。差し替えの PR で、このアプリが読めない manifest を入れないため。
 * 形の細かい確認(コピーが Release と同じか、追加だけか)は .github/scripts/peak_data.py が行う。
 * iOS の PeakDataPointerTests.testSitePointerIsReadableByThisApp と同じ確認。
 */
class SitePeakDataTest {
    // Gradle のテストは android/core を作業フォルダにして動く。
    private val siteDir = File("../../site/data/osm-peaks")

    @Test
    fun pointedManifestsAreReadableByThisApp() {
        val body = File(siteDir, "current.json").readText()
        // 配布用(stable)と、開発用の既定(dev、無ければ stable)の両方を確かめる。
        for (useDev in listOf(false, true)) {
            val pointer = PeakData.parsePointer(body, useDev)
            val bytes = File(siteDir, "manifests/" + pointer.manifestUrl.substringAfterLast('/')).readBytes()
            assertEquals(pointer.manifestSha256, PeakData.sha256Hex(bytes), "${pointer.manifestUrl} の SHA-256 が参照先ファイルと合いません")
            // 読めない形式なら PeakDataException になる。
            val manifest = PeakData.parseManifest(bytes.toString(Charsets.UTF_8))
            assertTrue(manifest.mountainCount > 0, "${pointer.manifestUrl} の件数が 0 です")
        }
    }
}
