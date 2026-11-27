package slussen.testing

import java.math.BigDecimal

/**
 * A strict reader of JSON text (RFC 8259) for the tests of the Journal Export (UC-011 BR-007): an object becomes a
 * [Map] that keeps its order, an array a [List], a number a [BigDecimal], and `true`, `false` and `null` themselves. Text
 * that is not exactly one JSON value throws, so that a test proves the file is complete JSON and nothing else.
 */
object JsonText {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.space()
        require(reader.at == text.length) { "Text follows the JSON value at ${reader.at}." }
        return value
    }

    private class Reader(private val text: String) {
        var at = 0

        fun space() {
            while (at < text.length && text[at] in " \t\r\n") at++
        }

        fun value(): Any? {
            space()
            require(at < text.length) { "The JSON text ends where a value was expected." }
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("Unexpected '$c' at $at.")
            }
        }

        private fun word(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "Unexpected text at $at." }
            at += word.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            at++
            space()
            if (text[at] == '}') return map.also { at++ }
            while (true) {
                space()
                val key = string()
                space()
                require(text[at] == ':') { "':' expected at $at." }
                at++
                require(key !in map) { "The key $key appears twice." }
                map[key] = value()
                space()
                when (text[at]) {
                    ',' -> at++
                    '}' -> return map.also { at++ }
                    else -> throw IllegalArgumentException("',' or '}' expected at $at.")
                }
            }
        }

        private fun array(): List<Any?> {
            val list = mutableListOf<Any?>()
            at++
            space()
            if (text[at] == ']') return list.also { at++ }
            while (true) {
                list += value()
                space()
                when (text[at]) {
                    ',' -> at++
                    ']' -> return list.also { at++ }
                    else -> throw IllegalArgumentException("',' or ']' expected at $at.")
                }
            }
        }

        private fun string(): String {
            require(text[at] == '"') { "'\"' expected at $at." }
            at++
            val out = StringBuilder()
            while (true) {
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        when (val e = text[at++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> out.append(text.substring(at, at + 4).toInt(16).toChar()).also { at += 4 }
                            else -> throw IllegalArgumentException("Unknown escape '\\$e'.")
                        }
                    }
                    c < ' ' -> throw IllegalArgumentException("A control character in a string at ${at - 1}.")
                    else -> out.append(c)
                }
            }
        }

        private fun number(): BigDecimal {
            val start = at
            if (text[at] == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            val literal = text.substring(start, at)
            require(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(literal)) { "Not a JSON number: $literal." }
            return BigDecimal(literal)
        }
    }
}
