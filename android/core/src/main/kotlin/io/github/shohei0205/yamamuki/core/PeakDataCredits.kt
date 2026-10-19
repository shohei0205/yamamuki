package io.github.shohei0205.yamamuki.core

/**
 * 取り込み済みの山データの出典とライセンス。設定の「データの出典とライセンス」に出す。
 * 取り込んだときに端末へ残した manifest から読むので、オフラインでも読め、地点データと別の版の出典が混ざらない。
 * 値は manifest の文字列をそのまま持つ(説明文や複数の URL が入ることがある)。空の項目は null。
 */
data class PeakDataCredits(
    /** データを作った日(UTC)。「2026/10/07」の形。 */
    val releaseDate: String?,
    /** manifest の license(例: ODbL-1.0)。版 6 は元データの出典の license。 */
    val license: String?,
    /** manifest の attribution(例: © OpenStreetMap contributors)。版 6 は元データの出典の attribution。 */
    val attribution: String?,
    /** manifest の sourceUrl。元にした OSM データの URL。版 6 は元データの出典の source。 */
    val sourceUrl: String?,
    /**
     * タグ・よみがななどの補足に使った資料(manifest の supplementSources)。出典 ID の文字コード順。
     * 版 6 は metadata.sources のうち、元データ以外の出典。
     */
    val supplements: List<SupplementSource>,
)

/** 補足に使った資料 1 件。出典 ID はタグ名とは関係なく、見出しとして出すだけ。 */
data class SupplementSource(
    val id: String,
    /** 資料名・参照 URL。 */
    val source: String?,
    /** ライセンス名・版・説明ページの URL。 */
    val license: String?,
    /** 著作者名・必要なクレジット。 */
    val attribution: String?,
    /** 資料から行った加工・変更。 */
    val changes: String?,
)

object PeakDataCreditsReader {
    /**
     * 端末に残した manifest から、取り込み済みの山データの出典を読む。
     * 取り込み済みの記録が無い、manifest が残っていない(出典を残す前の版のアプリで取り込んだ)、
     * 記録の SHA-256 と合わない、読めないときは null。
     */
    fun read(installed: InstalledPeakData?, manifest: ByteArray?): PeakDataCredits? {
        val sha256 = installed?.manifestSha256 ?: return null
        if (manifest == null) return null
        if (PeakData.sha256Hex(manifest) != sha256) return null
        return parse(manifest.toString(Charsets.UTF_8))
    }

    /**
     * manifest の本文から出典を読む。JSON のオブジェクトでなければ null。
     * supplementSources が無い・空・オブジェクトでない古いデータでは、補足は空になる。
     * 出典の値がオブジェクトでないものは飛ばし、属性がどれも無い出典は ID だけを出す。
     * 版 6 は metadata.sources を読む([parseSources])。元データの出典が無ければ null。
     */
    fun parse(body: String): PeakDataCredits? {
        val root = try {
            SimpleJson.parse(body)
        } catch (e: IllegalArgumentException) {
            return null
        } as? Map<*, *> ?: return null
        if (root["metadata"] is Map<*, *>) return parseSources(root)
        val supplements = (root["supplementSources"] as? Map<*, *>).orEmpty().mapNotNull { (key, value) ->
            val id = (key as? String)?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            val item = value as? Map<*, *> ?: return@mapNotNull null
            SupplementSource(
                id = id,
                source = text(item["source"]),
                license = text(item["license"]),
                attribution = text(item["attribution"]),
                changes = text(item["changes"]),
            )
        }.sortedBy { it.id }
        return PeakDataCredits(
            releaseDate = (root["version"] as? String)?.let(PeakData::releaseDate),
            license = text(root["license"]),
            attribution = text(root["attribution"]),
            sourceUrl = text(root["sourceUrl"]),
            supplements = supplements,
        )
    }

    /**
     * manifest 版 6 の metadata.sources から出典を読む。データ本体のファイル名の頭と同じ名前の出典
     * ([PeakData.mainSourceName])を元データとし、それ以外を補足に使った資料として出す。name の無い出典は飛ばす。
     * 元データの出典が無いとき(yamamuki-data が出典の名前やファイル名を変えたときなど)は null を返し、
     * 画面はアプリが持つ OpenStreetMap の表示に戻す(ライセンスの表示を消さないため)。
     */
    private fun parseSources(root: Map<*, *>): PeakDataCredits? {
        val sources = ((root["metadata"] as Map<*, *>)["sources"] as? List<*>).orEmpty().mapNotNull { value ->
            val item = value as? Map<*, *> ?: return@mapNotNull null
            SupplementSource(
                id = text(item["name"]) ?: return@mapNotNull null,
                source = text(item["source"]),
                license = text(item["license"]),
                attribution = text(item["attribution"]),
                changes = text(item["changes"]),
            )
        }
        val dataName = PeakData.mainSourceName(root)
        val main = sources.firstOrNull { it.id == dataName } ?: return null
        return PeakDataCredits(
            releaseDate = (root["version"] as? String)?.let(PeakData::releaseDate),
            license = main.license,
            attribution = main.attribution,
            sourceUrl = main.source,
            supplements = sources.filter { it !== main }.sortedBy { it.id },
        )
    }

    private fun text(value: Any?): String? = (value as? String)?.trim()?.ifEmpty { null }
}

/** 文章の中の URL を、リンクにできるよう切り分ける。 */
object LinkText {
    /** 文章の一部。[isUrl] が true なら、[text] は開ける URL。 */
    data class Part(val text: String, val isUrl: Boolean)

    /**
     * [text] を、URL とそれ以外に分ける。URL は http:// か https:// で始まり、空白か、
     * 日本語の括弧・句読点の手前までとする(「title=日本百名山」のように URL の中の日本語は含める)。
     * 末尾の「.」「,」「;」などと、対応する「(」の無い「)」は URL に含めない。
     */
    fun split(text: String): List<Part> {
        val parts = ArrayList<Part>()
        var last = 0
        for (match in URL.findAll(text)) {
            val url = trimEnd(match.value)
            // 「https://」だけのものはリンクにしない。
            if (url.endsWith("://")) continue
            val start = match.range.first
            if (start > last) parts += Part(text.substring(last, start), false)
            parts += Part(url, true)
            last = start + url.length
        }
        if (last < text.length) parts += Part(text.substring(last), false)
        return parts
    }

    private fun trimEnd(url: String): String {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            val unbalanced = c == ')' && url.substring(0, end).count { it == '(' } < url.substring(0, end).count { it == ')' }
            if (c in TRAILING || unbalanced) end-- else break
        }
        return url.substring(0, end)
    }

    private val URL = Regex("https?://[^\\s<>\"（）「」『』【】、。，；]+")
    private const val TRAILING = ".,;:!?'"
}
