package io.github.shohei0205.yamamuki.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * yamamuki-data が配る全国の山頂データの、最新版の目印(manifest.json)。
 * 形式は yamamuki-data の README.md の「manifest.json」と、points/README.md にある。
 */
data class PeakManifest(
    val schemaVersion: Int,
    /** データの版。生成時の UTC 日時と Actions の実行 ID をつないだ文字列。 */
    val version: String,
    /** データ本体(gzip で圧縮した JSON 配列)の URL。 */
    val downloadUrl: String,
    /** データ本体の SHA-256(小文字の16進数)。 */
    val sha256: String,
    /** データ本体のバイト数。 */
    val sizeBytes: Long,
    /** 収録した山の数。版 4 は mountainCount、版 5 は pointCount、版 6 は metadata.pointCount。 */
    val mountainCount: Int,
    /**
     * 元にした OSM データの基準日時(UTC、例: 2026-09-30T20:21:22Z)。版 6 は元データの出典([mainSourceName])の latestTimestamp。
     * 版 5 で省略されたときと、版 6 で元データの出典か latestTimestamp が無いときは空文字。
     */
    val sourceTimestamp: String,
) {
    /** データを作った日(UTC)。「2026/10/07」の形。[version] から読めなければ null。 */
    val releaseDate: String? get() = PeakData.releaseDate(version)
}

/** 取り込み済みの配信データ。設定画面に出し、次の確認で同じ版なら取り直さない。 */
data class InstalledPeakData(
    val version: String,
    val sourceTimestamp: String,
    val mountainCount: Int,
    /**
     * manifest を取ったときの目印。次の確認で送り、変わっていなければ manifest も取り直さない。
     * 参照先ファイルから読んだときは manifest の SHA-256、manifest を直接読んだときは HTTP の ETag。
     */
    val manifestEtag: String?,
    val installedAtMillis: Long,
    /** 保存データを作った読み込み処理の版([PeakData.READER_VERSION])。記録が無い古い版のアプリで取り込んだときは 0。 */
    val readerVersion: Int = 0,
    /** 取り込んだデータ本体(gz)の SHA-256。端末に残した gz がこの版のものかを確かめる。 */
    val dataSha256: String? = null,
    /** 取り込んだときの manifest の SHA-256。端末に残した manifest がこの版のものかを確かめる。 */
    val manifestSha256: String? = null,
) {
    /** データを作った日(UTC)。「2026/10/07」の形。[version] から読めなければ null。 */
    val releaseDate: String? get() = PeakData.releaseDate(version)
}

/**
 * 確認した manifest のうち、まだ取り込んでいない版。データ本体を取る前に、大きさを利用者に見せるのに使う。
 * [PeakDataUpdater.install] に渡すと、この manifest のとおりに取り込む。
 */
class PeakDataOffer(
    val manifest: PeakManifest,
    /** 取ったときの manifest の中身。取り込んだら端末に残す。 */
    internal val manifestBody: String,
    internal val etag: String?,
) {
    /** ダウンロードするデータ本体のバイト数。 */
    val sizeBytes: Long get() = manifest.sizeBytes
}

/** 配信データが壊れている、または読めない形式だった。 */
class PeakDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** manifest の取得結果。 */
sealed interface ManifestResponse {
    /** 送った ETag と同じで、前回から変わっていない(HTTP 304)。 */
    data object NotModified : ManifestResponse

    data class Fetched(val body: String, val etag: String?) : ManifestResponse
}

/** 山データをどこから読むか。ビルドの種類と、開発用のビルドの取得先の設定で決まる。 */
enum class PeakDataChannel {
    /** 配布用のビルド: yamamuki の参照先ファイル(current.json)の stable が指す manifest。 */
    STABLE,

    /** 開発用のビルドの既定: 参照先ファイルの dev が指す manifest。dev が無ければ stable。 */
    DEV,

    /** 開発用のビルドで取得先を dev にしたとき: yamamuki-data の dev が公開する最新の manifest を直接。 */
    DATA_DEV,
}

/** 参照先ファイル(current.json)の、読む manifest の場所と SHA-256。 */
data class PeakDataPointer(val manifestUrl: String, val manifestSha256: String)

/** 配信データの取得元。テストではフェイクに差し替える。 */
interface PeakDataSource {
    /** @param etag 前回の ETag。null なら必ず取得する。 */
    suspend fun fetchManifest(etag: String?): ManifestResponse

    suspend fun fetchData(url: String): ByteArray
}

object PeakData {
    /**
     * 山データの参照先ファイル。読ませる manifest を yamamuki 側で決めるため、yamamuki の Pages に置いている。
     * stable(配布用)と dev(開発用、省略可能)に、manifest の URL と SHA-256 が書いてある。
     * 書き換えは「山データの差し替え PR を作る」ワークフローの PR で行う。
     */
    const val POINTER_URL = "https://shohei0205.github.io/yamamuki/data/osm-peaks/current.json"

    /** 参照先ファイルが指してよい manifest の置き場所。Release の manifest を書き換えずにコピーしたもの。 */
    const val POINTER_MANIFEST_URL_PREFIX = "https://shohei0205.github.io/yamamuki/data/osm-peaks/manifests/"

    /** 読める参照先ファイルの形式の版。 */
    const val POINTER_SCHEMA_VERSION = 1

    /**
     * 開発版の最新版の manifest の URL。開発用のビルド(Android の debug、iOS の Debug)で、取得先を dev にしたときに直接読む
     * ([PeakDataChannel.DATA_DEV])。開発版が取れないときに正式版へ自動で切り替えることはしない(yamamuki-data の方針)。
     * yamamuki-data の dev が公開する置き場所(points/osm-peaks-dev/)。manifest の版は、yamamuki-data の dev に合わせて変わる
     * (2026-10-18 から版 6)。
     */
    const val DEV_MANIFEST_URL = "https://shohei0205.github.io/yamamuki-data/points/osm-peaks-dev/manifest.json"

    /** [channel] から山データを読む取得元。 */
    fun source(channel: PeakDataChannel, httpClient: HttpClient, userAgent: String): PeakDataSource = when (channel) {
        PeakDataChannel.STABLE -> PointerPeakDataSource(httpClient, useDev = false, userAgent = userAgent)
        PeakDataChannel.DEV -> PointerPeakDataSource(httpClient, useDev = true, userAgent = userAgent)
        PeakDataChannel.DATA_DEV -> HttpPeakDataSource(httpClient, DEV_MANIFEST_URL, userAgent = userAgent)
    }

    /**
     * 参照先ファイルを読み、読む manifest の場所を返す。[useDev] なら dev を、dev が無ければ stable を読む。
     * よその URL を指していたり、SHA-256 の形がおかしかったりしたら読まない。
     */
    fun parsePointer(body: String, useDev: Boolean): PeakDataPointer {
        val root = try {
            SimpleJson.parse(body)
        } catch (e: IllegalArgumentException) {
            throw PeakDataException("山データの参照先ファイルを読めません", e)
        } as? Map<*, *> ?: throw PeakDataException("山データの参照先ファイルがオブジェクトではありません")
        val schemaVersion = root["schemaVersion"] as? Long
        if (schemaVersion != POINTER_SCHEMA_VERSION.toLong()) {
            throw PeakDataException("このアプリが読めない形式の山データの参照先です(形式の版 ${schemaVersion ?: "不明"})。アプリを更新してください。")
        }
        val channel = if (useDev && root["dev"] != null) "dev" else "stable"
        val entry = root[channel] as? Map<*, *> ?: throw PeakDataException("山データの参照先ファイルに $channel がありません")
        val url = entry["manifestUrl"] as? String
        if (url == null || !url.startsWith(POINTER_MANIFEST_URL_PREFIX) ||
            !MANIFEST_NAME.matches(url.removePrefix(POINTER_MANIFEST_URL_PREFIX))
        ) {
            throw PeakDataException("山データの参照先ファイルの $channel が、決まった置き場所の manifest を指していません")
        }
        val sha256 = entry["manifestSha256"] as? String
        if (sha256 == null || !SHA256_HEX.matches(sha256)) {
            throw PeakDataException("山データの参照先ファイルの $channel の manifestSha256 が SHA-256 ではありません")
        }
        return PeakDataPointer(url, sha256)
    }

    /** 参照先ファイルが指してよい manifest のファイル名(Release のタグ + .json)。 */
    private val MANIFEST_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.json")
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    /**
     * データを作った日(UTC)を「2026/10/07」の形で返す。データの版の先頭の日時(例: 20261007T071401Z-…)から読む。
     * 版がこの形でなければ null。
     */
    fun releaseDate(version: String): String? =
        RELEASE_DATE.find(version)?.destructured?.let { (y, m, d) -> "$y/$m/$d" }

    private val RELEASE_DATE = Regex("^(\\d{4})(\\d{2})(\\d{2})T\\d{6}Z")

    /**
     * 設定で自動の確認をオンにしたとき、新しい山データがあるかを確かめる間隔(30 日)。
     * 読むのは参照先ファイル(数百バイト)で、山データはそれほど頻繁に更新しないので、月に 1 回ほどで足りる。
     */
    const val AUTO_CHECK_INTERVAL_MILLIS = 30L * 24 * 60 * 60 * 1000

    /**
     * 自動の確認をする時期か。まだ確かめたことが無い([lastCheckedAtMillis] が null)か、前回から
     * [AUTO_CHECK_INTERVAL_MILLIS] 以上たったら確かめる。端末の時計が戻って前回が未来になったときも確かめる
     * (そのままだと、時計が追いつくまで確かめなくなるため)。
     */
    fun autoCheckDue(lastCheckedAtMillis: Long?, nowMillis: Long): Boolean {
        if (lastCheckedAtMillis == null || lastCheckedAtMillis > nowMillis) return true
        return nowMillis - lastCheckedAtMillis >= AUTO_CHECK_INTERVAL_MILLIS
    }

    /** SHA-256 を小文字の16進数で返す。 */
    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /**
     * 読める manifest の形式の版。版 4 で downloadUrl が入った。版 5 で件数が pointCount になり、
     * データ本体の版(dataSchemaVersion)が別に書かれるようになった。版 6 で件数と出典が metadata にまとまり、
     * 形式の名前(schemaFormat・dataSchemaFormat)が入った。
     */
    val SUPPORTED_SCHEMA_VERSIONS = setOf(4, 5, 6)

    /**
     * 読めるデータ本体の版(manifest 版 5 以降の dataSchemaVersion)。地点データ版 5 は、山ごとの項目(osmId・name・latitude・
     * longitude・elevationM)が版 4 までと同じで、ID・種別・タグ・画像などの項目が増えただけなので、そのまま読める。
     * 版 6 は地点の配列が文書の points に入り、osmId の代わりに id(osm:<ノード番号>)で OSM の地点を表す。
     */
    val SUPPORTED_DATA_SCHEMA_VERSIONS = setOf(5, 6)

    /** manifest 版 6 の形式の名前(schemaFormat)。 */
    const val MANIFEST_SCHEMA_FORMAT = "yamamuki-manifest"

    /** 読める地点データの形式の名前(manifest 版 6 の dataSchemaFormat)。 */
    const val DATA_SCHEMA_FORMAT = "yamamuki-points"

    /** 地点データ版 6 の、OSM のノードを表す ID の頭。後ろはノード番号。 */
    private const val OSM_ID_PREFIX = "osm:"

    /**
     * 保存データを作る読み込み処理の版。[parseMountains] で読む項目を増やしたり、保存の形を変えたりしたら上げる。
     * 上がると、端末に残した gz から保存データを作り直す([PeakDataUpdater.rebuild])。gz が無ければ、次の確認で取り直す。
     */
    const val READER_VERSION = 2

    /** これより大きいデータは受け取らない。今は約 0.45 MB で、yamamuki-data も 5 MB を超えたら公開を止める。 */
    const val MAX_SIZE_BYTES = 20_000_000L

    fun parseManifest(body: String): PeakManifest {
        val root = try {
            SimpleJson.parse(body)
        } catch (e: IllegalArgumentException) {
            throw PeakDataException("manifest を読めません", e)
        } as? Map<*, *> ?: throw PeakDataException("manifest がオブジェクトではありません")
        fun string(key: String) = root[key] as? String ?: throw PeakDataException("manifest に $key がありません")
        fun long(key: String) = (root[key] as? Long) ?: throw PeakDataException("manifest の $key が整数ではありません")

        val schemaVersion = long("schemaVersion").toInt()
        if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            throw PeakDataException("このアプリが読めない形式の山データです(形式の版 $schemaVersion)。アプリを更新してください。")
        }
        // 版 5 は、データ本体の版が分からなければ読めないものとして扱う(manifest の版から推測しない)。
        if (schemaVersion >= 5) {
            val dataSchemaVersion = (root["dataSchemaVersion"] as? Long)?.toInt()
            if (dataSchemaVersion !in SUPPORTED_DATA_SCHEMA_VERSIONS) {
                throw PeakDataException("このアプリが読めない形式の山データです(データの版 ${dataSchemaVersion ?: "不明"})。アプリを更新してください。")
            }
        }
        // 版 6 は、形式の名前が違う(別の種類のデータの)manifest を読まない。
        if (schemaVersion >= 6) {
            if (root["schemaFormat"] != MANIFEST_SCHEMA_FORMAT) throw PeakDataException("manifest の schemaFormat が $MANIFEST_SCHEMA_FORMAT ではありません")
            if (root["dataSchemaFormat"] != DATA_SCHEMA_FORMAT) throw PeakDataException("manifest の dataSchemaFormat が $DATA_SCHEMA_FORMAT ではありません")
        }
        val mountainCount = when {
            schemaVersion >= 6 -> (root["metadata"] as? Map<*, *>)?.get("pointCount") as? Long
                ?: throw PeakDataException("manifest の metadata.pointCount が整数ではありません")
            schemaVersion == 5 -> long("pointCount")
            else -> long("mountainCount")
        }
        val manifest = PeakManifest(
            schemaVersion = schemaVersion,
            version = string("version"),
            downloadUrl = string("downloadUrl"),
            sha256 = string("sha256").lowercase(),
            sizeBytes = long("sizeBytes"),
            mountainCount = mountainCount.toInt(),
            sourceTimestamp = when {
                schemaVersion >= 6 -> mainSource(root)?.get("latestTimestamp") as? String ?: ""
                schemaVersion == 5 -> root["sourceTimestamp"] as? String ?: ""
                else -> string("sourceTimestamp")
            },
        )
        if (!manifest.downloadUrl.startsWith("https://")) throw PeakDataException("manifest の downloadUrl が https ではありません")
        if (manifest.sizeBytes !in 1..MAX_SIZE_BYTES) throw PeakDataException("manifest のサイズ ${manifest.sizeBytes} バイトは受け取れません")
        return manifest
    }

    /**
     * manifest 版 6 の metadata.sources のうち、元データの出典の名前。データ本体のファイル名(例: osm-peaks.json.gz)の頭(osm-peaks)。
     * 出典の並びに元データと補足の区別は無いので、名前で見分ける。
     */
    internal fun mainSourceName(root: Map<*, *>): String? =
        (root["fileName"] as? String)?.trim()?.substringBefore('.')?.ifEmpty { null }

    /** manifest 版 6 の、元データの出典([mainSourceName] と同じ name の metadata.sources)。無ければ null。 */
    private fun mainSource(root: Map<*, *>): Map<*, *>? {
        val name = mainSourceName(root) ?: return null
        return ((root["metadata"] as? Map<*, *>)?.get("sources") as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>().firstOrNull { (it["name"] as? String)?.trim() == name }
    }

    /** 受け取ったデータ本体が、manifest のサイズと SHA-256 に合うか確かめる。 */
    fun verify(data: ByteArray, manifest: PeakManifest) {
        if (data.size.toLong() != manifest.sizeBytes) {
            throw PeakDataException("山データのサイズが合いません(${data.size} バイト、manifest では ${manifest.sizeBytes} バイト)")
        }
        if (sha256Hex(data) != manifest.sha256) throw PeakDataException("山データの SHA-256 が合いません")
    }

    /**
     * gzip を展開し、山を読む。版 5 までは JSON 配列、版 6 は文書の points の配列を読む。
     * 名前・座標・OSM のノード番号(版 5 までは osmId、版 6 は id の osm:<番号>)のない項目と、山頂以外の種別(type)の項目は飛ばす。
     * 種別の無い項目(版 4 までと、版 5 で種別不明のもの)は山頂として読む。
     * 読み仮名・別名・Wikipedia と Wikidata の URL・タグ・画像も読む。形の合わない値は、無いものとして扱う。
     * 読み仮名が配列(版 6)なら先頭を、別名が {name, nameReading} の配列(版 6)なら name を読む。
     * それ以外の知らない項目は読まない(端末に残した gz には残る)。
     */
    fun parseMountains(gzip: ByteArray): List<Mountain> {
        val json = try {
            GZIPInputStream(ByteArrayInputStream(gzip)).use { it.readBytes() }.toString(Charsets.UTF_8)
        } catch (e: java.io.IOException) {
            throw PeakDataException("山データを展開できません", e)
        }
        val root = try {
            SimpleJson.parse(json)
        } catch (e: IllegalArgumentException) {
            throw PeakDataException("山データを読めません", e)
        }
        val items = root as? List<*> ?: (root as? Map<*, *>)?.get("points") as? List<*>
            ?: throw PeakDataException("山データが配列でも、points の配列を持つ文書でもありません")
        return items.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            if ((m["type"] as? String ?: PEAK_TYPE) != PEAK_TYPE) return@mapNotNull null
            val name = (m["name"] as? String)?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            Mountain(
                osmId = m["osmId"] as? Long ?: osmIdOf(m["id"]) ?: return@mapNotNull null,
                name = name,
                latitude = (m["latitude"] as? Number)?.toDouble() ?: return@mapNotNull null,
                longitude = (m["longitude"] as? Number)?.toDouble() ?: return@mapNotNull null,
                elevationM = (m["elevationM"] as? Number)?.toDouble(),
                pointId = trimmed(m["id"]),
                nameReading = trimmed(m["nameReading"]) ?: (m["nameReading"] as? List<*>)?.firstNotNullOfOrNull(::trimmed),
                aliases = names(m["aliases"]).filter { it != name },
                wikipediaUrl = httpsUrl(m["wikipediaUrl"]),
                wikidataUrl = httpsUrl(m["wikidataUrl"]),
                tags = names(m["tags"]),
                graphic = graphic(m["graphic"]),
            )
        }.distinctBy { it.osmId }
    }

    /** 前後の空白を除いた文字列。文字列でないか空なら null。 */
    private fun trimmed(value: Any?): String? = (value as? String)?.trim()?.ifEmpty { null }

    /** 地点データ版 6 の id(osm:<ノード番号>)から、OSM のノード番号を読む。OSM 以外の地点や形の違う ID は null。 */
    private fun osmIdOf(value: Any?): Long? =
        (value as? String)?.takeIf { it.startsWith(OSM_ID_PREFIX) }?.removePrefix(OSM_ID_PREFIX)
            ?.takeIf { id -> id.isNotEmpty() && id.all { it in '0'..'9' } }?.toLongOrNull()

    /**
     * 別名やタグの配列。文字列でない・空の要素と重複を除く。配列でなければ空。
     * 要素が {name, ...} のオブジェクト(版 6 の別名)なら name を読む。
     */
    private fun names(value: Any?): List<String> =
        (value as? List<*>).orEmpty().mapNotNull { trimmed(it) ?: trimmed((it as? Map<*, *>)?.get("name")) }.distinct()

    /** https の URL だけを受け取る。 */
    private fun httpsUrl(value: Any?): String? = trimmed(value)?.takeIf { it.startsWith("https://") }

    /** 地点の画像。SVG が無いか大きすぎるときは画像なし、倍率が正の有限の数でないときは 1 倍とする。 */
    private fun graphic(value: Any?): PointGraphic? {
        val g = value as? Map<*, *> ?: return null
        val svg = (g["svg"] as? String)?.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_SVG_BYTES }
            ?: return null
        val scale = (g["scale"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 } ?: 1.0
        return PointGraphic(svg, scale)
    }

    /** 地点の画像の SVG の大きさの上限。yamamuki-data の決まり(100,000 バイト以内)と同じ。 */
    private const val MAX_SVG_BYTES = 100_000

    /** 地点データ版 5 の、山頂の種別。 */
    private const val PEAK_TYPE = "peak"

    /**
     * 配信データが対象にする範囲。日本の端の島(沖ノ鳥島・南鳥島・与那国島・択捉島)まで入る矩形。
     * この範囲のタイル([FOREIGN_AREAS] を除く)はすべて取り込んだデータで置き換えるので、新しい版で消えた山や、以前 Overpass で取った山は残らない。
     */
    val COVERAGE = BoundingBox(south = 20.0, west = 122.0, north = 46.0, east = 154.0)

    /**
     * [COVERAGE] のうち日本の外の陸地(配信データに山が無い所)。ここのタイルは取得済みにしないので、
     * 「データがありません」と知らせる。0.5° のタイルの境目にそろえ、日本の島(対馬・宗谷岬・択捉島など)のタイルは含めない。
     */
    val FOREIGN_AREAS = listOf(
        BoundingBox(south = 34.0, west = 122.0, north = 43.0, east = 129.0), // 朝鮮半島・中国の遼東半島と山東半島
        BoundingBox(south = 35.0, west = 129.0, north = 43.0, east = 130.0), // 朝鮮半島の東岸(釜山から北)
        BoundingBox(south = 33.0, west = 125.0, north = 34.0, east = 127.0), // 済州島と朝鮮半島の南西の島
        BoundingBox(south = 37.0, west = 130.5, north = 38.0, east = 131.0), // 鬱陵島
        BoundingBox(south = 29.0, west = 122.0, north = 31.5, east = 123.0), // 中国の舟山群島
        BoundingBox(south = 42.0, west = 129.0, north = 46.0, east = 139.0), // ロシアの沿海地方
        BoundingBox(south = 45.5, west = 142.0, north = 46.0, east = 144.0), // サハリンの南端
        BoundingBox(south = 45.5, west = 149.0, north = 46.0, east = 154.0), // 得撫島から北の千島列島
        BoundingBox(south = 20.0, west = 144.5, north = 21.0, east = 146.0), // 北マリアナ諸島の北端
    )

    /**
     * 取り込むタイル。[COVERAGE] と山のある範囲を合わせた矩形のタイルを、[FOREIGN_AREAS] を除いてすべて取得済みにする。
     * 山が 0 件の海のタイルも含めないと、海に近い場所で「一部の山データがありません」と出てしまう。
     * 山のあるタイルは、[FOREIGN_AREAS] の中でも含める。
     */
    fun tilesOf(mountains: List<Mountain>): List<Tile> {
        if (mountains.isEmpty()) return emptyList()
        val withMountains = mountains.mapTo(HashSet()) { Tile.of(it.latitude, it.longitude) }
        return Tile.covering(
            BoundingBox(
                south = minOf(COVERAGE.south, mountains.minOf { it.latitude }),
                west = minOf(COVERAGE.west, mountains.minOf { it.longitude }),
                north = maxOf(COVERAGE.north, mountains.maxOf { it.latitude }),
                east = maxOf(COVERAGE.east, mountains.maxOf { it.longitude }),
            ),
        ).filter { tile -> tile in withMountains || !isForeign(tile) }
    }

    /**
     * 方位盤で、取得していなくても欠けたものとして数えないタイル。現在地が日本側なら、[FOREIGN_AREAS] のタイルは
     * 配信データに無いのが当たり前なので数えない(国境の近くで「一部の山データがありません」が出続けないように)。
     * 現在地が [FOREIGN_AREAS] の中なら、どのタイルも数えて「データがありません」と知らせる。
     */
    fun ignoresMissing(latitude: Double, longitude: Double): (Tile) -> Boolean =
        if (isForeign(Tile.of(latitude, longitude))) { _ -> false } else ::isForeign

    /** タイルの中心が [FOREIGN_AREAS] に入るか。 */
    fun isForeign(tile: Tile): Boolean {
        val b = tile.bounds
        val lat = (b.south + b.north) / 2
        val lon = (b.west + b.east) / 2
        return FOREIGN_AREAS.any { it.contains(lat, lon) }
    }
}

/**
 * 配信データの最新版を確かめ、新しければ取得・検証してキャッシュに取り込む。
 * 取り込む前に失敗したら、キャッシュの内容はそのまま残る。
 * 取り込んだ manifest とデータ本体は [archive] に組で残し、読み込み処理の版が上がったら、そこから作り直す。
 */
class PeakDataUpdater(
    private val source: PeakDataSource,
    private val cache: MountainCache,
    private val archive: PeakDataArchive? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 確認の結果。 */
    sealed interface Check {
        /** 取り込み済みの版が最新だった。 */
        data class UpToDate(val installed: InstalledPeakData) : Check

        /** 取り込める版がある。データ本体はまだ取っていない。 */
        data class Available(val offer: PeakDataOffer) : Check
    }

    /**
     * manifest だけを取って、取り込める版があるかを確かめる。データ本体は取りに行かないので、
     * 大きさ([PeakDataOffer.sizeBytes])を利用者に見せてから [install] で取り込める。
     * @param installed 取り込み済みの版。null なら manifest の版によらず取り込める版として返す。
     * 古い読み込み処理で作った保存データ([rebuild] で作り直せなかったもの)も、版によらず取り込める版として返す。
     */
    suspend fun check(installed: InstalledPeakData?): Check {
        val current = installed?.takeIf { it.readerVersion >= PeakData.READER_VERSION }
        val response = source.fetchManifest(current?.manifestEtag)
        if (response is ManifestResponse.NotModified && current != null) return Check.UpToDate(current)
        val fetched = response as? ManifestResponse.Fetched
            ?: throw PeakDataException("manifest が返ってきませんでした")
        val manifest = PeakData.parseManifest(fetched.body)
        if (current != null && current.version == manifest.version) {
            return Check.UpToDate(current.copy(manifestEtag = fetched.etag))
        }
        return Check.Available(PeakDataOffer(manifest, fetched.body, fetched.etag))
    }

    /** [check] で見つけた版のデータ本体を取得・検証して取り込む。 */
    suspend fun install(offer: PeakDataOffer): InstalledPeakData {
        val manifest = offer.manifest
        val data = source.fetchData(manifest.downloadUrl)
        PeakData.verify(data, manifest)
        val mountains = PeakData.parseMountains(data)
        if (mountains.isEmpty()) throw PeakDataException("山データが空です")
        cache.replaceTiles(PeakData.tilesOf(mountains), mountains, clock())
        // 保存データを作り直せてから置き換える。途中で失敗したら、前の版の組が残る。
        val manifestBytes = offer.manifestBody.toByteArray(Charsets.UTF_8)
        archive?.write(manifestBytes, data)
        return InstalledPeakData(
            version = manifest.version,
            sourceTimestamp = manifest.sourceTimestamp,
            mountainCount = mountains.size,
            manifestEtag = offer.etag,
            installedAtMillis = clock(),
            readerVersion = PeakData.READER_VERSION,
            dataSha256 = manifest.sha256,
            manifestSha256 = PeakData.sha256Hex(manifestBytes),
        )
    }

    /**
     * [installed] が古い読み込み処理で作った保存データなら、端末に残した manifest と gz から通信せずに作り直す。
     * 作り直したら新しい記録を、作り直す必要が無いか、manifest か gz が無い・SHA-256 が合わない・読めないときは null を返す。
     * null のときに作り直しが必要なら、次の [check] で取り込める版として返す。
     */
    suspend fun rebuild(installed: InstalledPeakData?): InstalledPeakData? {
        if (installed == null || installed.readerVersion >= PeakData.READER_VERSION) return null
        val archived = archive?.read() ?: return null
        if (installed.manifestSha256 == null || PeakData.sha256Hex(archived.manifest) != installed.manifestSha256) return null
        if (installed.dataSha256 == null || PeakData.sha256Hex(archived.data) != installed.dataSha256) return null
        val mountains = try {
            val manifest = PeakData.parseManifest(archived.manifest.toString(Charsets.UTF_8))
            PeakData.verify(archived.data, manifest)
            PeakData.parseMountains(archived.data)
        } catch (e: PeakDataException) {
            return null
        }
        if (mountains.isEmpty()) return null
        cache.replaceTiles(PeakData.tilesOf(mountains), mountains, clock())
        return installed.copy(mountainCount = mountains.size, readerVersion = PeakData.READER_VERSION)
    }
}

/**
 * yamamuki の参照先ファイル(current.json)から manifest をたどって、配信データを取得する。
 * 参照先ファイルは毎回取り直し(数百バイト)、指す manifest の SHA-256 が前回と同じなら manifest は取り直さない。
 * manifest のコピーは書き換えない決まりなので、SHA-256 を ETag の代わりに使う。
 */
class PointerPeakDataSource(
    private val httpClient: HttpClient,
    private val useDev: Boolean,
    private val pointerUrl: String = PeakData.POINTER_URL,
    private val userAgent: String = "yamamuki-android",
) : PeakDataSource {
    override suspend fun fetchManifest(etag: String?): ManifestResponse {
        val pointer = PeakData.parsePointer(get(pointerUrl).bodyAsText(), useDev)
        if (pointer.manifestSha256 == etag) return ManifestResponse.NotModified
        val manifest = get(pointer.manifestUrl).readRawBytes()
        if (PeakData.sha256Hex(manifest) != pointer.manifestSha256) {
            throw PeakDataException("山データの manifest の SHA-256 が参照先ファイルと合いません")
        }
        return ManifestResponse.Fetched(manifest.toString(Charsets.UTF_8), pointer.manifestSha256)
    }

    override suspend fun fetchData(url: String): ByteArray = get(url).readRawBytes()

    private suspend fun get(url: String): HttpResponse {
        val response = httpClient.get(url) { header(HttpHeaders.UserAgent, userAgent) }
        if (!response.status.isSuccess()) {
            throw PeakDataException("サーバーが HTTP ${response.status.value} を返しました(${url.substringAfterLast('/')})。")
        }
        return response
    }
}

/** HTTP で manifest を直接読んで、配信データを取得する。 */
class HttpPeakDataSource(
    private val httpClient: HttpClient,
    private val manifestUrl: String,
    private val userAgent: String = "yamamuki-android",
) : PeakDataSource {
    override suspend fun fetchManifest(etag: String?): ManifestResponse {
        val response = httpClient.get(manifestUrl) {
            header(HttpHeaders.UserAgent, userAgent)
            if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
        }
        if (response.status == HttpStatusCode.NotModified) return ManifestResponse.NotModified
        checkStatus(response, manifestUrl)
        return ManifestResponse.Fetched(response.bodyAsText(), response.headers[HttpHeaders.ETag])
    }

    override suspend fun fetchData(url: String): ByteArray {
        val response = httpClient.get(url) { header(HttpHeaders.UserAgent, userAgent) }
        checkStatus(response, url)
        return response.readRawBytes()
    }

    private fun checkStatus(response: HttpResponse, url: String) {
        if (!response.status.isSuccess()) {
            throw PeakDataException("サーバーが HTTP ${response.status.value} を返しました(${url.substringAfterLast('/')})。")
        }
    }
}
