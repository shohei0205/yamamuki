package io.github.shohei0205.yamamuki.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 取り込んだ配信データの本体(gz)を、端末に 1 つだけ残しておく場所。
 * アプリが読まない項目も含めて残し、読み込み処理の版が上がったら、通信せずに保存データを作り直すのに使う。
 */
interface PeakDataArchive {
    /** 残した gz。無いか読めなければ null。 */
    suspend fun read(): ByteArray?

    /** 前の gz を [data] で置き換える。失敗しても例外は投げない(次の作り直しで SHA-256 が合わず、取り直すだけ)。 */
    suspend fun write(data: ByteArray)

    /** 残した gz を消す。 */
    suspend fun delete()
}

/** [file] に gz を残す。書くときは一時ファイルに書いてから名前を変え、書きかけのファイルを残さない。 */
class FilePeakDataArchive(private val file: File) : PeakDataArchive {
    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (file.isFile) file.readBytes() else null
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val temp = File(file.path + ".tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeBytes(data)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        } catch (e: IOException) {
            temp.delete()
        }
        Unit
    }

    override suspend fun delete() = withContext(Dispatchers.IO) {
        file.delete()
        Unit
    }
}
