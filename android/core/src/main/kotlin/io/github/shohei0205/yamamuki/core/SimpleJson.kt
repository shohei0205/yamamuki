package io.github.shohei0205.yamamuki.core

/**
 * 配信データの manifest と山の一覧を読むための、小さな JSON の読み取り器。
 * core は Android に依存しないので org.json は使えず、読むのは決まった形の 2 種類だけなので、ライブラリは足さない。
 *
 * 値は、オブジェクトを Map、配列を List、文字列を String、数を Long(整数で表せるとき)か Double、
 * true / false を Boolean、null を null で返す。
 */
internal object SimpleJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipSpaces()
        val value = reader.readValue()
        reader.skipSpaces()
        if (!reader.atEnd) throw reader.error("JSON の後ろに余分な文字があります")
        return value
    }

    private class Reader(private val text: String) {
        private var pos = 0
        val atEnd: Boolean get() = pos >= text.length

        fun error(message: String) = IllegalArgumentException("$message(位置 $pos)")

        fun skipSpaces() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        fun readValue(): Any? {
            if (atEnd) throw error("JSON が途中で終わっています")
            return when (val c = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readWord("true", true)
                'f' -> readWord("false", false)
                'n' -> readWord("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw error("JSON として読めない文字「$c」があります")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++
            val result = LinkedHashMap<String, Any?>()
            skipSpaces()
            if (peek() == '}') { pos++; return result }
            while (true) {
                skipSpaces()
                if (peek() != '"') throw error("オブジェクトの名前がありません")
                val key = readString()
                skipSpaces()
                expect(':')
                skipSpaces()
                result[key] = readValue()
                skipSpaces()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return result }
                    else -> throw error("オブジェクトの区切りがありません")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++
            val result = ArrayList<Any?>()
            skipSpaces()
            if (peek() == ']') { pos++; return result }
            while (true) {
                skipSpaces()
                result += readValue()
                skipSpaces()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return result }
                    else -> throw error("配列の区切りがありません")
                }
            }
        }

        private fun readString(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd) throw error("文字列が閉じていません")
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd) throw error("文字列が閉じていません")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw error("\\u の後ろが足りません")
                                sb.append(text.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: throw error("\\u の後ろが16進数ではありません"))
                                pos += 4
                            }
                            else -> throw error("知らないエスケープ「\\$e」があります")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw error("数として読めません: $raw")
        }

        private fun readWord(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw error("JSON として読めない語があります")
            pos += word.length
            return value
        }

        private fun peek(): Char = if (atEnd) throw error("JSON が途中で終わっています") else text[pos]

        private fun expect(c: Char) {
            if (peek() != c) throw error("「$c」がありません")
            pos++
        }
    }
}
