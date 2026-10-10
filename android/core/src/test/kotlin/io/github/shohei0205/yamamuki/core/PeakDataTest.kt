package io.github.shohei0205.yamamuki.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeakDataTest {
    // yamamuki-data の peaks/README.md の例と同じ形。2 件目は標高もふりがなも無い。
    private val json = """
        [{"osmId":3403990450,"name":"万三郎岳","latitude":34.8627963,"longitude":139.0018525,"elevationM":1405.6,
          "nameReading":"ばんざぶろうだけ","aliases":["天城山"],"wikipediaUrl":"https://ja.wikipedia.org/wiki/%E5%A4%A9%E5%9F%8E%E5%B1%B1","wikidataUrl":null},
         {"osmId":12,"name":"名無し標高","latitude":43.5,"longitude":142.9,"elevationM":null,
          "nameReading":null,"aliases":[],"wikipediaUrl":null,"wikidataUrl":"https://www.wikidata.org/wiki/Q1"}]
    """.trimIndent()
    private val gzip = gzip(json)

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(version: String = "v1", data: ByteArray = gzip, schemaVersion: Int = 4) = """
        {"schemaVersion":$schemaVersion,"version":"$version","downloadUrl":"https://example.com/japan-mountains.json.gz",
         "fileName":"japan-mountains.json.gz","sha256":"${sha256(data).uppercase()}","sizeBytes":${data.size},
         "uncompressedSizeBytes":${json.length},"mountainCount":2,"sourceTimestamp":"2026-09-30T20:21:22Z",
         "latestMountainTimestamp":"2026-09-29T08:06:38Z","sourceUrl":"https://download.geofabrik.de/asia/japan-260929.osm.pbf",
         "license":"ODbL-1.0","attribution":"© OpenStreetMap contributors"}
    """.trimIndent()

    @Test
    fun parsesManifest() {
        val m = PeakData.parseManifest(manifest())
        assertEquals("v1", m.version)
        assertEquals("https://example.com/japan-mountains.json.gz", m.downloadUrl)
        assertEquals(sha256(gzip), m.sha256, "16進数は小文字にそろえる")
        assertEquals(gzip.size.toLong(), m.sizeBytes)
        assertEquals(2, m.mountainCount)
        assertEquals("2026-09-30T20:21:22Z", m.sourceTimestamp)
    }

    // yamamuki-data の README.md の manifest 版 5 と同じ形。件数は pointCount、データ本体の版は dataSchemaVersion。
    private fun manifestV5(dataSchemaVersion: String = "5", sourceTimestamp: String? = "2026-09-30T20:21:22Z") = """
        {"schemaVersion":5,"dataSchemaVersion":$dataSchemaVersion,"name":"山頂","version":"v1",
         "fileName":"osm-peaks.json.gz","downloadUrl":"https://example.com/osm-peaks.json.gz",
         "sha256":"${sha256(gzip)}","sizeBytes":${gzip.size},"uncompressedSizeBytes":${json.length},"pointCount":2,
         ${sourceTimestamp?.let { "\"sourceTimestamp\":\"$it\"," }.orEmpty()}
         "license":"ODbL-1.0","attribution":"© OpenStreetMap contributors"}
    """.trimIndent()

    @Test
    fun parsesManifestV5() {
        val m = PeakData.parseManifest(manifestV5())
        assertEquals(5, m.schemaVersion)
        assertEquals("https://example.com/osm-peaks.json.gz", m.downloadUrl)
        assertEquals(2, m.mountainCount)
        assertEquals("2026-09-30T20:21:22Z", m.sourceTimestamp)
        // 版 5 では元データの日時を省略できる。
        assertEquals("", PeakData.parseManifest(manifestV5(sourceTimestamp = null)).sourceTimestamp)
    }

    @Test
    fun rejectsUnknownSchemaVersion() {
        val e = assertFailsWith<PeakDataException> { PeakData.parseManifest(manifest(schemaVersion = 6)) }
        assertTrue(e.message!!.contains("アプリを更新"))
        assertFailsWith<PeakDataException> { PeakData.parseManifest(manifest(schemaVersion = 3)) }
    }

    @Test
    fun rejectsUnknownDataSchemaVersion() {
        val e = assertFailsWith<PeakDataException> { PeakData.parseManifest(manifestV5(dataSchemaVersion = "6")) }
        assertTrue(e.message!!.contains("アプリを更新"))
        // データ本体の版が分からないときは、manifest の版から推測せずに断る。
        assertFailsWith<PeakDataException> { PeakData.parseManifest(manifestV5(dataSchemaVersion = "null")) }
    }

    @Test
    fun rejectsBrokenManifest() {
        assertFailsWith<PeakDataException> { PeakData.parseManifest("""{"schemaVersion":4""") }
        assertFailsWith<PeakDataException> { PeakData.parseManifest("""{"schemaVersion":4,"version":"v1"}""") }
        assertFailsWith<PeakDataException> { PeakData.parseManifest(manifest().replace("https://example.com", "http://example.com")) }
    }

    @Test
    fun verifiesSizeAndSha256() {
        val m = PeakData.parseManifest(manifest())
        PeakData.verify(gzip, m)
        assertFailsWith<PeakDataException> { PeakData.verify(gzip.copyOf(gzip.size - 1), m) }
        val tampered = gzip.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<PeakDataException> { PeakData.verify(tampered, m) }
    }

    @Test
    fun parsesMountains() {
        val mountains = PeakData.parseMountains(gzip)
        assertEquals(
            listOf(
                Mountain(3403990450, "万三郎岳", 34.8627963, 139.0018525, 1405.6),
                Mountain(12, "名無し標高", 43.5, 142.9, null),
            ),
            mountains,
        )
    }

    @Test
    fun parsesPointsV5() {
        // 地点データ版 5。osmId の無い地点と、山頂以外の種別の地点は飛ばす。種別の無い地点は山頂として読む。
        val v5 = gzip(
            """
            [{"id":"1","type":"peak","osmId":1,"name":"山頂","latitude":35.0,"longitude":138.0,"elevationM":100.0,"tags":["日本百名山"]},
             {"id":"2","osmId":2,"name":"種別不明","latitude":35.1,"longitude":138.1,"type":null},
             {"id":"3","type":"parking","osmId":3,"name":"駐車場","latitude":35.2,"longitude":138.2},
             {"id":"4","type":"peak","name":"OSM と関連付けの無い山","latitude":35.3,"longitude":138.3}]
            """.trimIndent(),
        )
        assertEquals(
            listOf(Mountain(1, "山頂", 35.0, 138.0, 100.0), Mountain(2, "種別不明", 35.1, 138.1, null)),
            PeakData.parseMountains(v5),
        )
    }

    @Test
    fun rejectsDataThatIsNotGzip() {
        assertFailsWith<PeakDataException> { PeakData.parseMountains(json.toByteArray()) }
    }

    @Test
    fun tilesCoverSeaBetweenMountains() {
        val tiles = PeakData.tilesOf(PeakData.parseMountains(gzip))
        // 北緯 20〜46°・東経 122〜154° の矩形。山のない海のタイルも含む。
        assertTrue(Tile.of(40.0, 141.0) in tiles)
        assertTrue(Tile.of(20.1, 153.9) in tiles)
        // 日本の外の陸地は含めない(「データがありません」と知らせるため)。
        assertTrue(Tile.of(35.1, 129.05) !in tiles, "釜山")
        assertTrue(Tile.of(33.4, 126.5) !in tiles, "済州島")
        assertTrue(Tile.of(37.5, 127.0) !in tiles, "ソウル")
        assertTrue(Tile.of(43.1, 131.9) !in tiles, "ウラジオストク")
        assertTrue(Tile.of(45.8, 142.5) !in tiles, "サハリンの南端")
        // 境目の近くにある日本の島や岬のタイルは含める。
        assertTrue(Tile.of(34.4, 129.3) in tiles, "対馬")
        assertTrue(Tile.of(45.52, 141.94) in tiles, "宗谷岬")
        assertTrue(Tile.of(42.15, 139.45) in tiles, "奥尻島")
        assertTrue(Tile.of(45.3, 148.5) in tiles, "択捉島")
        assertTrue(Tile.of(32.7, 128.8) in tiles, "五島列島")
        // 範囲の外にある山も、その山のタイルまで広げて取り込む。
        assertTrue(Tile.of(50.2, 160.3) in PeakData.tilesOf(listOf(Mountain(1, "外", 50.2, 160.3, null))))
        // 日本の外の陸地でも、山のあるタイルは含める。
        assertTrue(Tile.of(35.1, 129.05) in PeakData.tilesOf(listOf(Mountain(1, "外", 35.1, 129.05, null))))
    }

    private class FakeSource(var manifestBody: String, var data: ByteArray, var etag: String? = "\"e1\"") : PeakDataSource {
        val sentEtags = mutableListOf<String?>()
        var dataCalls = 0

        override suspend fun fetchManifest(etag: String?): ManifestResponse {
            sentEtags += etag
            return if (etag != null && etag == this.etag) ManifestResponse.NotModified else ManifestResponse.Fetched(manifestBody, this.etag)
        }

        override suspend fun fetchData(url: String): ByteArray {
            dataCalls++
            return data
        }
    }

    @Test
    fun importsIntoCacheAndSkipsWhenUnchanged() = runTest {
        val source = FakeSource(manifest(), gzip)
        val cache = InMemoryMountainCache()
        val updater = PeakDataUpdater(source, cache, clock = { 1_000L })

        val first = updater.update(installed = null)
        assertIs<PeakDataUpdater.Result.Updated>(first)
        assertEquals("v1", first.installed.version)
        assertEquals(2, first.installed.mountainCount)
        assertEquals(setOf(3403990450L, 12L), cache.mountains.keys)
        assertEquals(1_000L, cache.tiles[Tile.of(40.0, 141.0)])
        assertNull(source.sentEtags.single(), "初回は ETag を送らない")

        // ETag が同じなら manifest もデータも取り直さない。
        val second = updater.update(first.installed)
        assertIs<PeakDataUpdater.Result.UpToDate>(second)
        assertEquals("\"e1\"", source.sentEtags.last())
        assertEquals(1, source.dataCalls)

        // ETag が変わっても版が同じなら、データは取り直さない。
        source.etag = "\"e2\""
        val third = updater.update(second.installed)
        assertIs<PeakDataUpdater.Result.UpToDate>(third)
        assertEquals("\"e2\"", third.installed.manifestEtag)
        assertEquals(1, source.dataCalls)
    }

    @Test
    fun newVersionReplacesOldMountains() = runTest {
        val cache = InMemoryMountainCache()
        val source = FakeSource(manifest(), gzip)
        val updater = PeakDataUpdater(source, cache)
        val installed = updater.update(null).installed

        val newData = gzip("""[{"osmId":3403990450,"name":"万三郎岳","latitude":34.8627963,"longitude":139.0018525,"elevationM":1406}]""")
        source.manifestBody = manifest(version = "v2", data = newData)
        source.data = newData
        source.etag = "\"e2\""
        val result = updater.update(installed)

        assertIs<PeakDataUpdater.Result.Updated>(result)
        assertEquals(1406.0, cache.mountains.getValue(3403990450).elevationM)
        assertEquals(setOf(3403990450L), cache.mountains.keys, "新しい版で消えた山はキャッシュからも消す")
    }

    @Test
    fun brokenDataLeavesCacheUntouched() = runTest {
        val cache = InMemoryMountainCache()
        val source = FakeSource(manifest(), gzip.copyOf(gzip.size - 1))
        assertFailsWith<PeakDataException> { PeakDataUpdater(source, cache).update(null) }
        assertTrue(cache.mountains.isEmpty())
        assertTrue(cache.tiles.isEmpty())
    }

    private class InMemoryArchive(var data: ByteArray? = null) : PeakDataArchive {
        override suspend fun read() = data
        override suspend fun write(data: ByteArray) { this.data = data }
        override suspend fun delete() { data = null }
    }

    @Test
    fun keepsImportedGzAndRecordsReaderVersion() = runTest {
        val archive = InMemoryArchive()
        val installed = PeakDataUpdater(FakeSource(manifest(), gzip), InMemoryMountainCache(), archive).update(null).installed
        assertTrue(archive.data!!.contentEquals(gzip), "読まない項目も含めて、受け取った gz をそのまま残す")
        assertEquals(PeakData.READER_VERSION, installed.readerVersion)
        assertEquals(sha256(gzip), installed.dataSha256)
    }

    @Test
    fun brokenDataKeepsPreviousGz() = runTest {
        val archive = InMemoryArchive(byteArrayOf(1, 2, 3))
        val source = FakeSource(manifest(), gzip.copyOf(gzip.size - 1))
        assertFailsWith<PeakDataException> { PeakDataUpdater(source, InMemoryMountainCache(), archive).update(null) }
        assertTrue(archive.data!!.contentEquals(byteArrayOf(1, 2, 3)))
    }

    /** 古い読み込み処理で gz を取り込んだときの記録。 */
    private fun oldInstalled(sha256: String? = sha256(gzip)) = InstalledPeakData(
        version = "v1", sourceTimestamp = "", mountainCount = 1, manifestEtag = "\"e1\"", installedAtMillis = 0,
        readerVersion = PeakData.READER_VERSION - 1, dataSha256 = sha256,
    )

    @Test
    fun rebuildsFromKeptGzWithoutNetwork() = runTest {
        val source = FakeSource(manifest(), gzip)
        val cache = InMemoryMountainCache()
        val updater = PeakDataUpdater(source, cache, InMemoryArchive(gzip), clock = { 2_000L })

        val rebuilt = updater.rebuild(oldInstalled())
        assertEquals(oldInstalled().copy(mountainCount = 2, readerVersion = PeakData.READER_VERSION), rebuilt)
        assertEquals(setOf(3403990450L, 12L), cache.mountains.keys)
        assertEquals(2_000L, cache.tiles[Tile.of(40.0, 141.0)])
        assertTrue(source.sentEtags.isEmpty() && source.dataCalls == 0, "通信しない")

        // 作り直したあとは、同じ版なら取り直さない。
        assertNull(updater.rebuild(rebuilt))
        assertIs<PeakDataUpdater.Result.UpToDate>(updater.update(rebuilt))
        assertEquals(0, source.dataCalls)
    }

    @Test
    fun refetchesSameVersionWhenKeptGzIsMissing() = runTest {
        for ((archive, installed) in listOf(
            InMemoryArchive() to oldInstalled(),
            InMemoryArchive(gzip) to oldInstalled(sha256 = "0".repeat(64)),
            InMemoryArchive(gzip) to oldInstalled(sha256 = null),
            InMemoryArchive(gzip) to oldInstalled().copy(readerVersion = 0),
        )) {
            val source = FakeSource(manifest(), gzip)
            val cache = InMemoryMountainCache()
            val updater = PeakDataUpdater(source, cache, archive)
            if (installed.dataSha256 != sha256(gzip) || archive.data == null) {
                assertNull(updater.rebuild(installed))
                assertTrue(cache.mountains.isEmpty())
            }
            // 作り直せなかったら、データの版が同じでも ETag を送らずに取り直す。
            val result = updater.update(installed)
            assertIs<PeakDataUpdater.Result.Updated>(result)
            assertNull(source.sentEtags.single())
            assertEquals(1, source.dataCalls)
            assertEquals(PeakData.READER_VERSION, result.installed.readerVersion)
        }
    }

    @Test
    fun fileArchiveReplacesAndDeletesGz() = runTest {
        val dir = kotlin.io.path.createTempDirectory("peak-data").toFile()
        try {
            val archive = FilePeakDataArchive(File(dir, "data/osm-peaks.json.gz"))
            assertNull(archive.read())
            archive.write(byteArrayOf(1, 2))
            archive.write(gzip)
            assertTrue(archive.read()!!.contentEquals(gzip), "前の gz は置き換える")
            assertEquals(listOf("osm-peaks.json.gz"), File(dir, "data").list()!!.toList(), "一時ファイルを残さない")
            archive.delete()
            assertNull(archive.read())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun httpSourceSendsEtagAndHandlesNotModified() = runTest {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seen += request.headers[HttpHeaders.IfNoneMatch]
            when {
                request.url.toString() == PeakData.DEV_MANIFEST_URL && request.headers[HttpHeaders.IfNoneMatch] == "\"e1\"" ->
                    respond("", HttpStatusCode.NotModified)
                request.url.toString() == PeakData.DEV_MANIFEST_URL ->
                    respond(manifest(), headers = headersOf(HttpHeaders.ETag, "\"e1\""))
                else -> respond(gzip)
            }
        }
        val source = HttpPeakDataSource(HttpClient(engine), PeakData.DEV_MANIFEST_URL)

        val fetched = source.fetchManifest(null)
        assertIs<ManifestResponse.Fetched>(fetched)
        assertEquals("\"e1\"", fetched.etag)
        assertIs<ManifestResponse.NotModified>(source.fetchManifest("\"e1\""))
        assertEquals(listOf(null, "\"e1\""), seen)
        assertTrue(source.fetchData("https://example.com/japan-mountains.json.gz").contentEquals(gzip))
    }

    private val stableUrl = PeakData.POINTER_MANIFEST_URL_PREFIX + "osm-peaks-20261007T071401Z-37585754546-1.json"
    private val devUrl = PeakData.POINTER_MANIFEST_URL_PREFIX + "osm-peaks-dev-20261010T022253Z-38016527834-1.json"

    private fun entry(url: String, sha256: String) = """{"manifestUrl":"$url","manifestSha256":"$sha256"}"""

    private fun pointer(stable: String? = entry(stableUrl, "a".repeat(64)), dev: String? = null, schemaVersion: Int = 1) =
        listOfNotNull("\"schemaVersion\":$schemaVersion", stable?.let { "\"stable\":$it" }, dev?.let { "\"dev\":$it" })
            .joinToString(",", "{", "}")

    @Test
    fun pointerPicksStableOrDev() {
        val withDev = pointer(dev = entry(devUrl, "b".repeat(64)))
        assertEquals(PeakDataPointer(stableUrl, "a".repeat(64)), PeakData.parsePointer(withDev, useDev = false))
        assertEquals(PeakDataPointer(devUrl, "b".repeat(64)), PeakData.parsePointer(withDev, useDev = true))
        // dev が無ければ、開発用のビルドも stable を読む。
        assertEquals(PeakDataPointer(stableUrl, "a".repeat(64)), PeakData.parsePointer(pointer(), useDev = true))
    }

    @Test
    fun pointerRejectsUnexpectedContent() {
        val bad = listOf(
            pointer(schemaVersion = 2),
            pointer(stable = null),
            pointer(stable = null, dev = entry(devUrl, "b".repeat(64))),
            pointer(stable = entry("https://shohei0205.github.io/yamamuki-data/peaks/manifest.json", "a".repeat(64))),
            pointer(stable = entry(PeakData.POINTER_MANIFEST_URL_PREFIX + "sub/x.json", "a".repeat(64))),
            pointer(stable = entry(PeakData.POINTER_MANIFEST_URL_PREFIX + "x.txt", "a".repeat(64))),
            pointer(stable = entry(stableUrl, "A".repeat(64))),
            pointer(stable = entry(stableUrl, "a".repeat(63))),
            "[]",
            "{",
        )
        for (body in bad) {
            assertFailsWith<PeakDataException>(body) { PeakData.parsePointer(body, useDev = false) }
        }
        // 開発用のビルドは、dev が壊れていても stable に切り替えない。
        assertFailsWith<PeakDataException> { PeakData.parsePointer(pointer(dev = entry(devUrl, "x")), useDev = true) }
    }

    @Test
    fun pointerSourceFollowsPointerAndSkipsSameManifest() = runTest {
        val manifest = manifest(schemaVersion = 4)
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            val url = request.url.toString()
            requests += url
            when (url) {
                PeakData.POINTER_URL -> respond(pointer(stable = entry(stableUrl, sha256(manifest.toByteArray()))))
                stableUrl -> respond(manifest)
                else -> respond(gzip)
            }
        }
        val source = PeakData.source(PeakDataChannel.STABLE, HttpClient(engine), userAgent = "test")

        val fetched = source.fetchManifest(null)
        assertIs<ManifestResponse.Fetched>(fetched)
        assertEquals(manifest, fetched.body)
        assertEquals(sha256(manifest.toByteArray()), fetched.etag)
        // 指す manifest が前回と同じなら、manifest は取り直さない。
        assertIs<ManifestResponse.NotModified>(source.fetchManifest(fetched.etag))
        assertEquals(listOf(PeakData.POINTER_URL, stableUrl, PeakData.POINTER_URL), requests)
    }

    @Test
    fun pointerSourceRejectsManifestWithWrongSha256() = runTest {
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                PeakData.POINTER_URL -> respond(pointer(stable = entry(stableUrl, "a".repeat(64))))
                else -> respond(manifest())
            }
        }
        val source = PointerPeakDataSource(HttpClient(engine), useDev = false)
        assertFailsWith<PeakDataException> { source.fetchManifest(null) }
    }

    @Test
    fun pointerSourceReportsHttpError() = runTest {
        val source = PointerPeakDataSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }), useDev = true)
        assertFailsWith<PeakDataException> { source.fetchManifest(null) }
    }

    @Test
    fun simpleJsonReadsEscapesAndNumbers() {
        assertEquals(
            mapOf("a" to listOf(1L, -2.5, 3e2, true, false, null), "s" to "改行\n\"引用\"é/"),
            SimpleJson.parse("""{"a":[1,-2.5,3e2,true,false,null],"s":"改行\n\"引用\"é\/"}"""),
        )
        assertFailsWith<IllegalArgumentException> { SimpleJson.parse("[1,2") }
        assertFailsWith<IllegalArgumentException> { SimpleJson.parse("[1] x") }
    }
}
