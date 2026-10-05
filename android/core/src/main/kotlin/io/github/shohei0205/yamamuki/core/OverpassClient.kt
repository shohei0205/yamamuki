package io.github.shohei0205.yamamuki.core

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** 山データの取得元。テストではフェイクに差し替える。 */
fun interface MountainRemoteSource {
    suspend fun fetchPeaks(box: BoundingBox): List<Mountain>
}

/** @property httpStatus サーバーが返した HTTP ステータス。つながらなかったなど、応答がないときは null。 */
class OverpassException(
    message: String,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
) : Exception(message, cause)

/**
 * OSM Overpass API から natural=peak / natural=volcano の名前付きノードを取得する。
 * 最初のエンドポイントが失敗したら次のミラーを試す。
 */
class OverpassClient(
    private val httpClient: HttpClient,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val userAgent: String = "yamamuki-android",
) : MountainRemoteSource {

    override suspend fun fetchPeaks(box: BoundingBox): List<Mountain> {
        val query = OverpassQuery.peaks(box)
        // どのエンドポイントがなぜ失敗したか追えるよう、すべての失敗を残す。
        val errors = mutableListOf<Throwable>()
        for (endpoint in endpoints) {
            try {
                val response = httpClient.submitForm(
                    url = endpoint,
                    formParameters = parameters { append("data", query) },
                ) {
                    header(HttpHeaders.UserAgent, userAgent)
                }
                if (!response.status.isSuccess()) {
                    errors += OverpassException("HTTP ${response.status.value} from $endpoint", httpStatus = response.status.value)
                    continue
                }
                return OverpassParser.parse(response.bodyAsText())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += OverpassException("${e::class.simpleName} from $endpoint", e)
            }
        }
        val last = errors.lastOrNull()
        throw OverpassException("All Overpass endpoints failed", last, (last as? OverpassException)?.httpStatus).apply {
            errors.dropLast(1).forEach(::addSuppressed)
        }
    }

    companion object {
        /**
         * 以前は 2 番目に overpass.kumi.systems(現 overpass.private.coffee)を置いていたが、問い合わせに応答せず
         * 75 秒待ってタイムアウトするだけだったので外した(#34)。本家の 504・429 は同時に使える枠が空いていない
         * という意味なので、別のサーバーに回すより、少し待って同じサーバーに問い合わせ直すほうが通りやすい。
         */
        val DEFAULT_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
        )
    }
}

object OverpassQuery {
    /** 返してもらう列。[OverpassParser] はこの見出しで列を探す。 */
    internal val CSV_COLUMNS = listOf("::id", "::lat", "::lon", "name", "\"name:ja\"", "ele")

    /**
     * 山頂ノードを、使う項目だけのタブ区切り(見出し行付き)で返させる。
     * JSON (out body) だと出典・コメントなど不要なタグまで届くため、通信量が gzip 後でも 4 割ほど多い。
     */
    fun peaks(box: BoundingBox, timeoutSec: Int = 60): String {
        val bbox = listOf(box.south, box.west, box.north, box.east)
            .joinToString(",") { String.format(Locale.US, "%.5f", it) }
        return """
            [out:csv(${CSV_COLUMNS.joinToString(",")};true;"\t")][timeout:$timeoutSec];
            (
              node["natural"="peak"]["name"]($bbox);
              node["natural"="volcano"]["name"]($bbox);
            );
            out;
        """.trimIndent()
    }
}

object OverpassParser {
    /**
     * [OverpassQuery.peaks] の応答(タブ区切り、1 行目が "@id  @lat  @lon  name  name:ja  ele" の見出し)を読む。
     * 値に改行を含むなどで列数が合わない行は読み飛ばす。
     */
    fun parse(body: String): List<Mountain> {
        val lines = body.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return emptyList()
        val header = lines.next().split('\t')
        fun column(name: String): Int {
            val i = header.indexOf(name)
            if (i < 0) throw OverpassException("Overpass の応答に列 $name がありません: ${header.joinToString(" ")}")
            return i
        }
        val id = column("@id")
        val lat = column("@lat")
        val lon = column("@lon")
        val name = column("name")
        val nameJa = column("name:ja")
        val ele = column("ele")

        val mountains = mutableListOf<Mountain>()
        for (line in lines) {
            val cells = line.split('\t')
            if (cells.size != header.size) continue
            val displayName = cells[nameJa].trim().ifEmpty { cells[name].trim() }
            if (displayName.isEmpty()) continue
            mountains += Mountain(
                osmId = cells[id].toLongOrNull() ?: continue,
                name = displayName,
                latitude = cells[lat].toDoubleOrNull() ?: continue,
                longitude = cells[lon].toDoubleOrNull() ?: continue,
                elevationM = parseElevation(cells[ele].ifEmpty { null }),
            )
        }
        return mountains.distinctBy { it.osmId }
    }

    /**
     * ele タグを m 単位の数値にする。"3776", "3776 m", "3,776", "3776;3775", "12345 ft" などに対応。
     */
    fun parseElevation(raw: String?): Double? {
        if (raw == null) return null
        val first = raw.split(';').first().trim().lowercase(Locale.US)
        val match = Regex("""^(-?[\d,]*\.?\d+)\s*(m|meters?|metres?|ft|feet|')?$""").find(first)
            ?: return null
        val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return when (match.groupValues[2]) {
            "ft", "feet", "'" -> value * 0.3048
            else -> value
        }
    }
}
