package io.github.shohei0205.yamamuki.core

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * yamamuki の Pages に置いた山データの参照先ファイル(site/data/osm-peaks/current.json)が指す manifest を、
 * アプリの読み込み処理で読めるかを確かめる。差し替えの PR で、このアプリが読めない manifest を入れないため。
 * 形の細かい確認(コピーが Release と同じか、追加だけか)は .github/scripts/peak_data.py が行う。
 */
class SitePeakDataTest {
    // Gradle のテストは android/core を作業フォルダにして動く。
    private val siteDir = File("../../site/data/osm-peaks")
    private val manifestUrlPrefix = "https://shohei0205.github.io/yamamuki/data/osm-peaks/manifests/"

    @Test
    fun pointedManifestsAreReadableByThisApp() {
        val pointer = SimpleJson.parse(File(siteDir, "current.json").readText()) as Map<*, *>
        assertEquals(1L, pointer["schemaVersion"])
        assertTrue("stable" in pointer, "current.json に stable がありません")
        for (channel in listOf("stable", "dev")) {
            val entry = pointer[channel] as? Map<*, *> ?: continue
            val url = entry["manifestUrl"] as String
            assertTrue(url.startsWith(manifestUrlPrefix), "$channel の manifestUrl が manifests/ を指していません: $url")
            val bytes = File(siteDir, "manifests/" + url.removePrefix(manifestUrlPrefix)).readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(entry["manifestSha256"], digest, "$channel の manifestSha256 がファイルと合いません")
            // 読めない形式なら PeakDataException になる。
            val manifest = PeakData.parseManifest(bytes.toString(Charsets.UTF_8))
            assertTrue(manifest.mountainCount > 0, "$channel の manifest の件数が 0 です")
        }
    }
}
