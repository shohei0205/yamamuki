package io.github.shohei0205.yamamuki.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
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

    @Test
    fun rejectsUnknownSchemaVersion() {
        val e = assertFailsWith<PeakDataException> { PeakData.parseManifest(manifest(schemaVersion = 5)) }
        assertTrue(e.message!!.contains("アプリを更新"))
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

    @Test
    fun manifestUrlFollowsBuildType() {
        assertEquals("https://shohei0205.github.io/yamamuki-data/peaks-dev/manifest.json", PeakData.manifestUrl(dev = true))
        assertEquals("https://shohei0205.github.io/yamamuki-data/peaks/manifest.json", PeakData.manifestUrl(dev = false))
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
