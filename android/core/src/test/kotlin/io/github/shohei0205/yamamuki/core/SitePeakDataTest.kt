package io.github.shohei0205.yamamuki.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * yamamuki の Pages に置いた山データのカタログ(site/data/catalog.json)の、このアプリが読む種類(osm-peaks・gsi-peaks)が指す
 * manifest を、アプリの読み込み処理で読めるかを確かめる。差し替えの PR で、このアプリが読めない manifest を入れないため。
 * 0.6.0・0.7.0 が読む参照先ファイル(osm-peaks/current.json)は、このアプリが読まないので確かめない。
 * 形の細かい確認(コピーが Release と同じか、追加だけか、current.json がカタログと同じか)は .github/scripts/peak_data.py が行う。
 * iOS の PeakDataCatalogTests.testSiteCatalogIsReadableByThisApp と同じ確認。
 */
class SitePeakDataTest {
    // Gradle のテストは android/core を作業フォルダにして動く。
    private val siteDir = File("../../site/data")

    /** 種類ごとに、出典の著作者表示に入っているはずの名前。 */
    private val attributionOwners = mapOf(
        PeakDataset.OSM_PEAKS to "OpenStreetMap",
        PeakDataset.GSI_PEAKS to "国土地理院",
    )

    @Test
    fun pointedManifestsAreReadableByThisApp() {
        val body = File(siteDir, "catalog.json").readText()
        // 配布用(stable)と、開発用の既定(dev、無ければ stable)の両方を確かめる。
        for (dataset in PeakDataset.entries) for (useDev in listOf(false, true)) {
            val pointer = PeakData.parseCatalog(body, useDev, dataset)
            val bytes = File(siteDir, "${dataset.id}/manifests/" + pointer.manifestUrl.substringAfterLast('/')).readBytes()
            assertEquals(pointer.manifestSha256, PeakData.sha256Hex(bytes), "${pointer.manifestUrl} の SHA-256 がカタログと合いません")
            // 読めない形式なら PeakDataException になる。
            val manifest = PeakData.parseManifest(bytes.toString(Charsets.UTF_8), dataset)
            assertTrue(manifest.mountainCount > 0, "${pointer.manifestUrl} の件数が 0 です")
            // 出典の画面に、元データの著作者表示が出るか(AGENTS.md の「© OpenStreetMap contributors の表示を消さない」)。
            // 出典を読めないとアプリが持つ表示に戻るが、読めても元データの出典が抜けていると、表示が消えてしまう。
            val credits = PeakDataCreditsReader.parse(bytes.toString(Charsets.UTF_8))
            val attributions = listOfNotNull(credits?.attribution) + credits?.supplements.orEmpty().mapNotNull { it.attribution }
            val owner = attributionOwners.getValue(dataset)
            assertTrue(attributions.any { owner in it }, "${pointer.manifestUrl} の出典に $owner の著作者表示がありません")
        }
    }
}
