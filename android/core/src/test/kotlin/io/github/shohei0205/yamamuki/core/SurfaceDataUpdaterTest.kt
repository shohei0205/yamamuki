// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurfaceDataUpdaterTest {
    private val dataset = SurfaceDataset.ESA_WORLDCOVER_Z9
    private val directory: File = Files.createTempDirectory("surface-data").toFile()
    private val files = SurfaceDataFiles(directory, dataset)

    /** データ本体の代わりの小さなバイト列(SQLite としては読めない)。 */
    private val data = ByteArray(200_000) { (it % 251).toByte() }
    private val dataUrl = "https://github.com/shohei0205/yamamuki-data/releases/download/esa-worldcover-1/esa-worldcover-z9.sqlite3"
    private val manifestUrl = "${dataset.manifestUrlPrefix}esa-worldcover-1.json"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun manifest(version: String = "20261112T164326Z-43261736372-1", bytes: ByteArray = data, size: Int = bytes.size) = """
        {"dataSchemaFormat":"yamamuki-sqlite","dataSchemaVersion":1,
         "datasets":[{"formatVersion":1,"id":"esa-worldcover-japan","kind":"surface"}],
         "downloadUrl":"$dataUrl","fileName":"esa-worldcover-z9.sqlite3",
         "schemaFormat":"yamamuki-manifest","schemaVersion":6,
         "sha256":"${PeakData.sha256Hex(bytes)}","sizeBytes":$size,"version":"$version"}
    """.trimIndent()

    private fun catalog(vararg entries: String) =
        """{"schemaVersion":1,"datasets":{"osm-peaks":{"stable":{"manifestUrl":"x","manifestSha256":"y"}}${entries.joinToString("") { ",$it" }}}}"""

    private fun entry(manifest: String, channel: String = "stable", url: String = manifestUrl) =
        """"${dataset.id}":{"$channel":{"manifestUrl":"$url","manifestSha256":"${PeakData.sha256Hex(manifest.toByteArray())}"}}"""

    /** 決まった manifest とデータ本体を返す取得元。 */
    private inner class FakeSource(var manifest: String?, var body: ByteArray = data) : SurfaceDataSource {
        var manifestFetches = 0
        var downloads = 0

        override suspend fun fetchManifest(etag: String?): ManifestResponse? {
            manifestFetches++
            val body = manifest ?: return null
            val current = PeakData.sha256Hex(body.toByteArray())
            return if (etag == current) ManifestResponse.NotModified else ManifestResponse.Fetched(body, current)
        }

        override suspend fun download(url: String, to: File, maxBytes: Long, onProgress: (Long) -> Unit) {
            downloads++
            assertEquals(dataUrl, url)
            to.writeBytes(body)
            onProgress(body.size.toLong() / 2)
            onProgress(body.size.toLong())
        }
    }

    private fun updater(source: SurfaceDataSource, validate: suspend (File, SurfaceManifest) -> Unit = { _, _ -> }) =
        SurfaceDataUpdater(source, files, dataset, validate, clock = { 1_000L })

    private suspend fun SurfaceDataUpdater.offer(installed: InstalledSurfaceData? = null): SurfaceDataOffer =
        assertIs<SurfaceDataUpdater.Check.Available>(check(installed)).offer

    @Test
    fun installsTheDataAndKeepsTheManifest() = runTest {
        val source = FakeSource(manifest())
        val updater = updater(source)
        val offer = updater.offer()
        assertEquals(data.size.toLong(), offer.manifest.sizeBytes)
        assertEquals(0, source.downloads)
        assertFalse(files.database.exists())

        val progress = mutableListOf<Float>()
        val installed = updater.install(offer) { progress += it }
        assertEquals("20261112T164326Z-43261736372-1", installed.version)
        assertEquals("2026/11/12", installed.releaseDate)
        assertEquals(data.size.toLong(), installed.sizeBytes)
        assertEquals("esa-worldcover-japan", installed.datasetId)
        assertEquals(1_000L, installed.installedAtMillis)
        assertEquals(PeakData.sha256Hex(manifest().toByteArray()), installed.manifestSha256)
        assertContentEquals(data, files.database.readBytes())
        assertContentEquals(manifest().toByteArray(), files.readManifest())
        assertFalse(files.partial.exists())
        assertEquals(listOf(0.5f, 1f), progress)

        // 同じ版なら取り直さない。
        assertEquals(SurfaceDataUpdater.Check.UpToDate(installed), updater.check(installed))
        // 新しい版が出たら、取り込める版として返す。
        source.manifest = manifest(version = "20261201T000000Z-1-1")
        assertEquals("20261201T000000Z-1-1", updater.offer(installed).manifest.version)
    }

    @Test
    fun sameVersionWithAnotherManifestIsUpToDate() = runTest {
        val source = FakeSource(manifest())
        val installed = updater(source).let { it.install(it.offer()) }
        // manifest のコピーだけが変わった(版は同じ)ときは、目印だけを新しくする。
        source.manifest = manifest() + "\n"
        val check = assertIs<SurfaceDataUpdater.Check.UpToDate>(updater(source).check(installed))
        assertEquals(PeakData.sha256Hex(source.manifest!!.toByteArray()), check.installed.manifestEtag)
        assertEquals(installed.version, check.installed.version)
    }

    @Test
    fun missingInCatalogIsUnavailable() = runTest {
        assertEquals(SurfaceDataUpdater.Check.Unavailable, updater(FakeSource(null)).check(null))
    }

    @Test
    fun brokenDataKeepsThePreviousVersion() = runTest {
        val source = FakeSource(manifest())
        val updater = updater(source)
        updater.install(updater.offer())

        // SHA-256 が合わない。
        source.manifest = manifest(version = "20261201T000000Z-1-1")
        val offer = updater.offer()
        source.body = data.copyOf().also { it[0] = 1 }
        assertFailsWith<SurfaceDataException> { updater.install(offer) }
        // サイズが合わない。
        source.body = data.copyOf(100)
        assertFailsWith<SurfaceDataException> { updater.install(offer) }
        // 山むきの SQLite として読めない。
        source.body = data
        val rejecting = updater(source) { _, _ -> throw SurfaceDataException("読めません") }
        assertFailsWith<SurfaceDataException> { rejecting.install(offer) }

        assertContentEquals(data, files.database.readBytes())
        assertContentEquals(manifest().toByteArray(), files.readManifest())
        assertFalse(files.partial.exists())
    }

    @Test
    fun validatorSeesTheDownloadedFileBeforeItIsPlaced() = runTest {
        var seen: Pair<Boolean, String>? = null
        val updater = updater(FakeSource(manifest())) { file, manifest -> seen = files.database.exists() to "${file.name}:${manifest.datasetId}" }
        updater.install(updater.offer())
        assertEquals(false to "esa-worldcover-z9.sqlite3.part:esa-worldcover-japan", seen)
    }

    @Test
    fun deleteRemovesTheFiles() = runTest {
        val updater = updater(FakeSource(manifest()))
        updater.install(updater.offer())
        files.delete()
        assertFalse(files.database.exists())
        assertNull(files.readManifest())
    }

    // region カタログからの取得

    private fun client(catalog: String, manifest: String = manifest(), requests: MutableList<String> = mutableListOf()) = HttpClient(
        MockEngine { request ->
            val url = request.url.toString()
            requests += url
            when (url) {
                PeakData.CATALOG_URL -> respond(catalog)
                manifestUrl -> respond(manifest)
                dataUrl -> respond(data)
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    )

    @Test
    fun catalogSourceFollowsThePointer() = runTest {
        val requests = mutableListOf<String>()
        val source = CatalogSurfaceDataSource(client(catalog(entry(manifest())), requests = requests), dataset, useDev = false)
        val fetched = assertIs<ManifestResponse.Fetched>(source.fetchManifest(null))
        assertEquals(manifest(), fetched.body)
        // 同じ manifest を指しているあいだは、manifest を取り直さない。
        assertEquals(ManifestResponse.NotModified, source.fetchManifest(fetched.etag))
        assertEquals(listOf(PeakData.CATALOG_URL, manifestUrl, PeakData.CATALOG_URL), requests)

        val to = File(directory, "download")
        val progress = mutableListOf<Long>()
        source.download(dataUrl, to, data.size.toLong()) { progress += it }
        assertContentEquals(data, to.readBytes())
        assertEquals(data.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun catalogSourceReadsDevOnlyInDevBuilds() = runTest {
        val devOnly = catalog(entry(manifest(), channel = "dev"))
        assertIs<ManifestResponse.Fetched>(CatalogSurfaceDataSource(client(devOnly), dataset, useDev = true).fetchManifest(null))
        // 配布用のビルドは stable だけを読む。
        assertFailsWith<SurfaceDataException> { CatalogSurfaceDataSource(client(devOnly), dataset, useDev = false).fetchManifest(null) }
    }

    @Test
    fun catalogSourceWithoutTheDatasetReturnsNull() = runTest {
        assertNull(CatalogSurfaceDataSource(client(catalog()), dataset, useDev = true).fetchManifest(null))
    }

    @Test
    fun catalogSourceRejectsBadPointersAndData() = runTest {
        // よその URL を指している。
        val elsewhere = catalog(entry(manifest(), url = "https://example.com/esa-worldcover-1.json"))
        assertFailsWith<SurfaceDataException> { CatalogSurfaceDataSource(client(elsewhere), dataset, useDev = false).fetchManifest(null) }
        // manifest の SHA-256 がカタログと合わない。
        val source = CatalogSurfaceDataSource(client(catalog(entry(manifest())), manifest = manifest() + " "), dataset, useDev = false)
        assertFailsWith<SurfaceDataException> { source.fetchManifest(null) }
        // manifest のサイズより大きいデータは途中で打ち切る。
        assertFailsWith<SurfaceDataException> { source.download(dataUrl, File(directory, "download"), 1_000) {} }
        assertFailsWith<SurfaceDataException> { source.download("https://example.com/none", File(directory, "download"), 1_000) {} }
    }

    // endregion
}
