// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

/**
 * このアプリが読める地表データの種類(カタログの datasets のキー)。yamamuki-data は同じ地表を収録ズームの違う
 * ファイルに分けて配るので、配布ファイルごとに種類を分ける。並びは選択画面の並び。
 */
enum class SurfaceDataset(val id: String) {
    /** ESA WorldCover 2021 の森林・水域と、国土地理院の標高タイルから作った陰影。ズーム 5〜9(約 17 MB)。 */
    ESA_WORLDCOVER_Z9("esa-worldcover-z9"),

    /** [ESA_WORLDCOVER_Z9] と同じ地表で、ズーム 5〜10(約 42 MB)まで収める。拡大したときに細かく見える。 */
    ESA_WORLDCOVER_Z10("esa-worldcover-z10"),
    ;

    /** データ本体のファイル名(manifest の fileName)。 */
    val fileName: String get() = "$id.sqlite3"

    companion object {
        /** [id] の種類。知らない ID なら null。 */
        fun of(id: String?): SurfaceDataset? = entries.firstOrNull { it.id == id }
    }
}

/** yamamuki-data が配る地表データの目印(manifest)。形式は yamamuki-data の formats/sqlite.md の「配布manifest」にある。 */
data class SurfaceManifest(
    /** データの版。生成時の UTC 日時と Actions の実行 ID をつないだ文字列。 */
    val version: String,
    /** データ本体(SQLite)の URL。 */
    val downloadUrl: String,
    /** データ本体の SHA-256(小文字の16進数)。 */
    val sha256: String,
    /** データ本体のバイト数。 */
    val sizeBytes: Long,
    /** SQLite の中の、地表のデータセットの ID(datasets の id)。 */
    val datasetId: String,
    /** 試験版のデータ(experimental が true)。 */
    val experimental: Boolean = false,
) {
    /** データを作った日(UTC)。「2026/10/07」の形。[version] から読めなければ null。 */
    val releaseDate: String? get() = PeakData.releaseDate(version)
}

/** 受け取った SQLite から読んだ、コンテナの目印と収録内容。 */
data class SurfaceContainerInfo(
    /** PRAGMA application_id。 */
    val applicationId: Long,
    /** PRAGMA user_version。 */
    val containerVersion: Long,
    /** manifest に書かれた地表のデータセットの、ズームの段の数([SurfaceData.LEVEL_COUNT_SQL])。 */
    val levelCount: Long,
)

/** 地表データが壊れている、または読めない形式だった。 */
class SurfaceDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

object SurfaceData {
    /** 読める manifest の形式の版。 */
    const val MANIFEST_SCHEMA_VERSION = 6

    /** 読めるデータ本体の形式の名前(manifest の dataSchemaFormat)。山むきの SQLite コンテナ。 */
    const val DATA_SCHEMA_FORMAT = "yamamuki-sqlite"

    /** 読める SQLite コンテナの版(manifest の dataSchemaVersion。SQLite の PRAGMA user_version と同じ値)。 */
    const val CONTAINER_VERSION = 1

    /** 山むきの SQLite コンテナの目印(PRAGMA application_id。ASCII で「YMDB」)。 */
    const val CONTAINER_APPLICATION_ID = 0x594D4442

    /** 地表のデータセットの種類(datasets の kind)。 */
    const val DATASET_KIND = "surface"

    /** 読める地表の形式の版(datasets の formatVersion)。 */
    const val SURFACE_FORMAT_VERSION = 1

    /** これより大きいデータは受け取らない。ズーム 9 までの版は約 17 MB、ズーム 10 までの版は約 42 MB。 */
    const val MAX_SIZE_BYTES = 100_000_000L

    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    /**
     * 受け取った SQLite から読んだ値で、[manifest] の地表データを収録した山むきの SQLite コンテナかを確かめる。
     * SQLite を開いて値を読むのはアプリ側で、SQLite として読めなかったときは [container] に null を渡す。
     * 違えば [SurfaceDataException] を投げる。
     */
    fun requireContainer(container: SurfaceContainerInfo?, manifest: SurfaceManifest) {
        if (container == null) throw SurfaceDataException("地表データを SQLite として読めません")
        if (container.applicationId != CONTAINER_APPLICATION_ID.toLong()) throw SurfaceDataException("山むきの SQLite ではありません")
        if (container.containerVersion != CONTAINER_VERSION.toLong()) {
            throw SurfaceDataException("このアプリが読めない形式の地表データです(データの版 ${container.containerVersion})。アプリを更新してください。")
        }
        if (container.levelCount <= 0) throw SurfaceDataException("地表のデータセット ${manifest.datasetId} がありません")
    }

    /**
     * [requireContainer] に渡す、ズームの段の数を数える SQL。引数は順に、データセットの ID・種類([DATASET_KIND])・
     * 地表の形式の版([SURFACE_FORMAT_VERSION])。
     */
    const val LEVEL_COUNT_SQL =
        "SELECT COUNT(*) FROM surface_levels AS l JOIN datasets AS d USING(dataset_key) " +
            "WHERE d.id = ? AND d.kind = ? AND d.format_version = ?"

    /** [dataset] の manifest を読む。山データなど、ほかの種類の manifest は読まない。 */
    fun parseManifest(body: String, dataset: SurfaceDataset): SurfaceManifest {
        val root = try {
            SimpleJson.parse(body)
        } catch (e: IllegalArgumentException) {
            throw SurfaceDataException("manifest を読めません", e)
        } as? Map<*, *> ?: throw SurfaceDataException("manifest がオブジェクトではありません")
        fun string(key: String) = root[key] as? String ?: throw SurfaceDataException("manifest に $key がありません")
        fun long(key: String) = (root[key] as? Long) ?: throw SurfaceDataException("manifest の $key が整数ではありません")

        val schemaVersion = long("schemaVersion")
        if (schemaVersion != MANIFEST_SCHEMA_VERSION.toLong()) {
            throw SurfaceDataException("このアプリが読めない形式の地表データです(形式の版 $schemaVersion)。アプリを更新してください。")
        }
        if (root["schemaFormat"] != PeakData.MANIFEST_SCHEMA_FORMAT) {
            throw SurfaceDataException("manifest の schemaFormat が ${PeakData.MANIFEST_SCHEMA_FORMAT} ではありません")
        }
        if (root["dataSchemaFormat"] != DATA_SCHEMA_FORMAT) {
            throw SurfaceDataException("manifest の dataSchemaFormat が $DATA_SCHEMA_FORMAT ではありません")
        }
        val containerVersion = root["dataSchemaVersion"] as? Long
        if (containerVersion != CONTAINER_VERSION.toLong()) {
            throw SurfaceDataException("このアプリが読めない形式の地表データです(データの版 ${containerVersion ?: "不明"})。アプリを更新してください。")
        }
        // 収録ズームの違うファイルを取り違えないように、データ本体のファイル名で種類を確かめる。
        if (root["fileName"] != dataset.fileName) {
            throw SurfaceDataException("manifest の fileName が ${dataset.fileName} ではありません(${root["fileName"] ?: "なし"})")
        }
        // 地表のデータセットを 1 つだけ収録したファイルを読む。形式の版が違うものは、アプリの更新を促す。
        val surfaces = (root["datasets"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.filter { it["kind"] == DATASET_KIND }
        val surface = surfaces.singleOrNull() ?: throw SurfaceDataException("manifest の地表のデータセットが 1 つではありません(${surfaces.size} 件)")
        val formatVersion = surface["formatVersion"] as? Long
        if (formatVersion != SURFACE_FORMAT_VERSION.toLong()) {
            throw SurfaceDataException("このアプリが読めない形式の地表データです(地表の形式の版 ${formatVersion ?: "不明"})。アプリを更新してください。")
        }
        val datasetId = (surface["id"] as? String)?.ifEmpty { null } ?: throw SurfaceDataException("manifest の地表のデータセットに id がありません")

        val manifest = SurfaceManifest(
            version = string("version"),
            downloadUrl = string("downloadUrl"),
            sha256 = string("sha256").lowercase(),
            sizeBytes = long("sizeBytes"),
            datasetId = datasetId,
            experimental = root["experimental"] == true,
        )
        if (!manifest.downloadUrl.startsWith("https://")) throw SurfaceDataException("manifest の downloadUrl が https ではありません")
        if (!SHA256_HEX.matches(manifest.sha256)) throw SurfaceDataException("manifest の sha256 が SHA-256 の形ではありません")
        if (manifest.sizeBytes !in 1..MAX_SIZE_BYTES) throw SurfaceDataException("manifest のサイズ ${manifest.sizeBytes} バイトは受け取れません")
        return manifest
    }
}
