package io.github.shohei0205.yamamuki.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * yamamuki の Pages に置いた山データのカタログ(site/data/catalog.json)の osm-peaks が指す manifest を、
 * アプリの読み込み処理で読めるかを確かめる。差し替えの PR で、このアプリが読めない manifest を入れないため。
 * カタログのほかの種類(gsi-peaks)と、0.6.0・0.7.0 が読む参照先ファイル(osm-peaks/current.json)は、
 * このアプリが読まないので確かめない。
 * 形の細かい確認(コピーが Release と同じか、追加だけか、current.json がカタログと同じか)は .github/scripts/peak_data.py が行う。
 * iOS の PeakDataCatalogTests.testSiteCatalogIsReadableByThisApp と同じ確認。
 */
class SitePeakDataTest {
    // Gradle のテストは android/core を作業フォルダにして動く。
    private val siteDir = File("../../site/data")

    @Test
    fun pointedManifestsAreReadableByThisApp() {
        val body = File(siteDir, "catalog.json").readText()
        // 配布用(stable)と、開発用の既定(dev、無ければ stable)の両方を確かめる。
        for (useDev in listOf(false, true)) {
            val pointer = PeakData.parseCatalog(body, useDev)
            val bytes = File(siteDir, "${PeakData.DATASET}/manifests/" + pointer.manifestUrl.substringAfterLast('/')).readBytes()
            assertEquals(pointer.manifestSha256, PeakData.sha256Hex(bytes), "${pointer.manifestUrl} の SHA-256 がカタログと合いません")
            // 読めない形式なら PeakDataException になる。
            val manifest = PeakData.parseManifest(bytes.toString(Charsets.UTF_8))
            assertTrue(manifest.mountainCount > 0, "${pointer.manifestUrl} の件数が 0 です")
            // 出典の画面に出典が出るか(読めなければ、アプリが持つ OSM の表示に戻ってしまう)。
            val credits = PeakDataCreditsReader.parse(bytes.toString(Charsets.UTF_8))
            assertTrue(credits != null && (credits.license != null || credits.supplements.isNotEmpty()), "${pointer.manifestUrl} の出典を読めません")
        }
    }
}
