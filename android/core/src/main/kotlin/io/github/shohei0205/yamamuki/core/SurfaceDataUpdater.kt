// SPDX-FileCopyrightText: 2026 The yamamuki Authors
// SPDX-License-Identifier: MIT

package io.github.shohei0205.yamamuki.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** 取り込み済みの地表データ。選択画面に出し、次の確認で同じ版なら取り直さない。 */
data class InstalledSurfaceData(
    val version: String,
    /** データ本体(SQLite)のバイト数。 */
    val sizeBytes: Long,
    /** SQLite の中の、地表のデータセットの ID。 */
    val datasetId: String,
    /** manifest を取ったときの目印(カタログに書かれた manifest の SHA-256)。変わっていなければ manifest を取り直さない。 */
    val manifestEtag: String?,
    val installedAtMillis: Long,
    /** 取り込んだときの manifest の SHA-256。端末に残した manifest がこの版のものかを確かめる。 */
    val manifestSha256: String,
    val experimental: Boolean = false,
) {
    /** データを作った日(UTC)。「2026/10/07」の形。[version] から読めなければ null。 */
    val releaseDate: String? get() = PeakData.releaseDate(version)
}

/**
 * 確認した manifest のうち、まだ取り込んでいない版。データ本体を取る前に、大きさを利用者に見せるのに使う。
 * [SurfaceDataUpdater.install] に渡すと、この manifest のとおりに取り込む。
 */
class SurfaceDataOffer(
    val manifest: SurfaceManifest,
    internal val manifestBody: String,
    internal val etag: String?,
)

/** 地表データの取得元。テストではフェイクに差し替える。 */
interface SurfaceDataSource {
    /**
     * @param etag 前回の目印。null なら必ず取得する。
     * @return カタログにこの種類が無いときは null。
     */
    suspend fun fetchManifest(etag: String?): ManifestResponse?

    /**
     * データ本体を [to] に書く。山データと違って大きい(17 MB 以上)ので、メモリに載せずにファイルへ流す。
     * [maxBytes] を超えたら打ち切る。[onProgress] には受け取ったバイト数を渡す。
     */
    suspend fun download(url: String, to: File, maxBytes: Long, onProgress: (Long) -> Unit)
}

/**
 * 端末に置く地表データのファイル。SQLite はそのまま置いて読む。取り込んだ manifest も残し、出典の表示に使う。
 * [directory] は自動バックアップに入らない場所にする。
 */
class SurfaceDataFiles(val directory: File, dataset: SurfaceDataset) {
    /** データ本体(SQLite)。 */
    val database = File(directory, dataset.fileName)

    /** 取り込んだときの manifest。 */
    val manifest = File(directory, MANIFEST_NAME)

    /** ダウンロード中のファイル。確かめ終えてから [database] に置き換える。 */
    val partial = File(directory, "${dataset.fileName}.part")

    /** 残した manifest。無いか読めなければ null。 */
    suspend fun readManifest(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (manifest.isFile) manifest.readBytes() else null
        } catch (e: IOException) {
            null
        }
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        database.delete()
        manifest.delete()
        partial.delete()
        Unit
    }

    companion object {
        const val MANIFEST_NAME = "manifest.json"
    }
}

/**
 * 地表データの最新版を確かめ、新しければ取得・検証して端末に置く。
 * 確かめ終えるまでは別のファイルに書くので、途中で失敗しても前の版はそのまま残る。
 */
class SurfaceDataUpdater(
    private val source: SurfaceDataSource,
    private val files: SurfaceDataFiles,
    private val dataset: SurfaceDataset,
    /**
     * 受け取った SQLite が、manifest に書かれた地表のデータセットを収録した山むきの SQLite コンテナかを確かめる。
     * SQLite を開くのはアプリ側なので、ここでは呼ぶだけにする。読めなければ [SurfaceDataException] を投げる。
     */
    private val validate: suspend (File, SurfaceManifest) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 確認の結果。 */
    sealed interface Check {
        /** 取り込み済みの版が最新だった。 */
        data class UpToDate(val installed: InstalledSurfaceData) : Check

        /** 取り込める版がある。データ本体はまだ取っていない。 */
        data class Available(val offer: SurfaceDataOffer) : Check

        /** カタログにこの種類が無い(まだ配っていない)。取り込み済みの版があれば、そのまま使える。 */
        data object Unavailable : Check
    }

    /**
     * manifest だけを取って、取り込める版があるかを確かめる。データ本体は取りに行かないので、
     * 大きさを利用者に見せてから [install] で取り込める。
     * @param installed 取り込み済みの版。null なら manifest の版によらず取り込める版として返す。
     */
    suspend fun check(installed: InstalledSurfaceData?): Check {
        val response = source.fetchManifest(installed?.manifestEtag) ?: return Check.Unavailable
        if (response is ManifestResponse.NotModified && installed != null) return Check.UpToDate(installed)
        val fetched = response as? ManifestResponse.Fetched ?: throw SurfaceDataException("manifest が返ってきませんでした")
        val manifest = SurfaceData.parseManifest(fetched.body, dataset)
        if (installed != null && installed.version == manifest.version) {
            return Check.UpToDate(installed.copy(manifestEtag = fetched.etag))
        }
        return Check.Available(SurfaceDataOffer(manifest, fetched.body, fetched.etag))
    }

    /**
     * [check] で見つけた版のデータ本体を取得・検証して端末に置く。
     * @param onProgress 進み具合(0〜1)。
     */
    suspend fun install(offer: SurfaceDataOffer, onProgress: (Float) -> Unit = {}): InstalledSurfaceData {
        val manifest = offer.manifest
        val manifestBytes = offer.manifestBody.toByteArray(Charsets.UTF_8)
        withContext(Dispatchers.IO) {
            files.directory.mkdirs()
            files.partial.delete()
        }
        try {
            source.download(manifest.downloadUrl, files.partial, manifest.sizeBytes) { received ->
                onProgress((received.toDouble() / manifest.sizeBytes).toFloat().coerceIn(0f, 1f))
            }
            withContext(Dispatchers.IO) { SurfaceData.verify(files.partial, manifest) }
            validate(files.partial, manifest)
            withContext(Dispatchers.IO) {
                if (!files.partial.renameTo(files.database)) {
                    files.database.delete()
                    if (!files.partial.renameTo(files.database)) throw SurfaceDataException("地表データを保存できませんでした")
                }
                files.manifest.writeBytes(manifestBytes)
            }
        } catch (e: IOException) {
            throw SurfaceDataException("地表データを保存できませんでした", e)
        } finally {
            withContext(Dispatchers.IO) { files.partial.delete() }
        }
        return InstalledSurfaceData(
            version = manifest.version,
            sizeBytes = manifest.sizeBytes,
            datasetId = manifest.datasetId,
            manifestEtag = offer.etag,
            installedAtMillis = clock(),
            manifestSha256 = PeakData.sha256Hex(manifestBytes),
            experimental = manifest.experimental,
        )
    }
}

/**
 * yamamuki のカタログ(catalog.json)から [dataset] の manifest をたどって、地表データを取得する。
 * 山データ([CatalogPeakDataSource])と同じカタログを読み、指す manifest の SHA-256 が前回と同じなら manifest は取り直さない。
 */
class CatalogSurfaceDataSource(
    private val httpClient: HttpClient,
    private val dataset: SurfaceDataset,
    private val useDev: Boolean,
    private val catalogUrl: String = PeakData.CATALOG_URL,
    private val userAgent: String = "yamamuki-android",
    /** 山データの取得元と、取ったカタログを使い回す。null なら毎回取る。 */
    private val catalogCache: PeakCatalogCache? = null,
) : SurfaceDataSource {
    override suspend fun fetchManifest(etag: String?): ManifestResponse? {
        val body = catalogCache?.get(catalogUrl) { get(catalogUrl).bodyAsText() } ?: get(catalogUrl).bodyAsText()
        val pointer = try {
            PeakData.catalogPointer(body, dataset.id, dataset.manifestUrlPrefix, useDev)
        } catch (e: PeakDataException) {
            throw SurfaceDataException(e.message ?: "カタログを読めません", e)
        } ?: return null
        if (pointer.manifestSha256 == etag) return ManifestResponse.NotModified
        val manifest = get(pointer.manifestUrl).readRawBytes()
        if (PeakData.sha256Hex(manifest) != pointer.manifestSha256) {
            throw SurfaceDataException("地表データの manifest の SHA-256 がカタログと合いません")
        }
        return ManifestResponse.Fetched(manifest.toString(Charsets.UTF_8), pointer.manifestSha256)
    }

    override suspend fun download(url: String, to: File, maxBytes: Long, onProgress: (Long) -> Unit) {
        httpClient.prepareGet(url) {
            header(HttpHeaders.UserAgent, userAgent)
            // 大きいので、回線が遅いと全体では数分かかる。全体の制限を外し、途中で止まったときは読み込みの制限に任せる。
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
        }.execute { response ->
            requireSuccess(response, url)
            val channel = response.bodyAsChannel()
            withContext(Dispatchers.IO) {
                to.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var received = 0L
                    while (true) {
                        val read = channel.readAvailable(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        received += read
                        if (received > maxBytes) throw SurfaceDataException("地表データが manifest のサイズ($maxBytes バイト)より大きいです")
                        out.write(buffer, 0, read)
                        onProgress(received)
                    }
                }
            }
        }
    }

    private suspend fun get(url: String): HttpResponse =
        requireSuccess(httpClient.get(url) { header(HttpHeaders.UserAgent, userAgent) }, url)

    private fun requireSuccess(response: HttpResponse, url: String): HttpResponse = try {
        response.requireSuccess(url)
    } catch (e: PeakDataException) {
        throw SurfaceDataException(e.message ?: "サーバーがエラーを返しました", e)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

/** カタログが指してよい manifest の置き場所。Release の manifest を書き換えずにコピーしたもの。 */
val SurfaceDataset.manifestUrlPrefix: String get() = "${PeakData.SITE_DATA_URL}$id/manifests/"

/** 受け取ったデータ本体が、manifest のサイズと SHA-256 に合うか確かめる。ファイルを少しずつ読み、メモリに載せない。 */
fun SurfaceData.verify(file: File, manifest: SurfaceManifest) {
    if (file.length() != manifest.sizeBytes) {
        throw SurfaceDataException("地表データのサイズが合いません(${file.length()} バイト、manifest では ${manifest.sizeBytes} バイト)")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    if (digest.digest().joinToString("") { "%02x".format(it) } != manifest.sha256) throw SurfaceDataException("地表データの SHA-256 が合いません")
}
