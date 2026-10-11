package io.github.shohei0205.yamamuki.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 端末に残した、取り込んだときの manifest とデータ本体(gz)の組。 */
class ArchivedPeakData(val manifest: ByteArray, val data: ByteArray)

/**
 * 取り込んだ配信データの manifest と本体(gz)を、端末に 1 組だけ残しておく場所。
 * アプリが読まない項目(manifest の出典、地点の tags など)も含めて残し、読み込み処理の版が上がったら、
 * 通信せずに保存データを作り直すのに使う。
 */
interface PeakDataArchive {
    /** 残した manifest と gz。どちらかが無いか読めなければ null。 */
    suspend fun read(): ArchivedPeakData?

    /**
     * 前の組を [manifest] と [data] で置き換える。失敗しても例外は投げない
     * (次の作り直しで SHA-256 が合わず、取り直すだけ)。
     */
    suspend fun write(manifest: ByteArray, data: ByteArray)

    /** 残した組を消す。 */
    suspend fun delete()
}

/**
 * [directory] に manifest.json と osm-peaks.json.gz を残す。書くときは一時ファイルに書いてから名前を変え、
 * 書きかけのファイルを残さない。2 つの置き換えの間で止まったときは組が食い違うが、
 * 取り込み済みの記録の SHA-256 と合わなくなるので、作り直しには使われない。
 */
class FilePeakDataArchive(private val directory: File) : PeakDataArchive {
    private val manifestFile = File(directory, MANIFEST_NAME)
    private val dataFile = File(directory, DATA_NAME)

    override suspend fun read(): ArchivedPeakData? = withContext(Dispatchers.IO) {
        try {
            if (manifestFile.isFile && dataFile.isFile) ArchivedPeakData(manifestFile.readBytes(), dataFile.readBytes()) else null
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun write(manifest: ByteArray, data: ByteArray) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        replace(manifestFile, manifest)
        replace(dataFile, data)
    }

    override suspend fun delete() = withContext(Dispatchers.IO) {
        manifestFile.delete()
        dataFile.delete()
        Unit
    }

    private fun replace(file: File, bytes: ByteArray) {
        val temp = File(file.path + ".tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        } catch (e: IOException) {
            temp.delete()
        }
    }

    companion object {
        const val MANIFEST_NAME = "manifest.json"
        const val DATA_NAME = "osm-peaks.json.gz"
    }
}
