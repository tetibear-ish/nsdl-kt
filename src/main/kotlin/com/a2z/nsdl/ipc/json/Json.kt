package com.a2z.nsdl.ipc.json

class JsonParseException(message: String) : Exception(message)
class JsonWriteException(message: String) : Exception(message)

/**
 * Hand-written JSON for the wire protocol: Map<String, Any?>, List<Any?>, String, Long, Double,
 * Boolean and null only. No external JSON library is available offline (see PLAN.md).
 */
object Json {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? = Parser(text).parseDocument()

    fun write(value: Any?): String = StringBuilder().also { writeValue(value, it) }.toString()

    private fun writeValue(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Long -> out.append(value)
            is Int -> out.append(value)
            is Double -> {
                if (value.isNaN() || value.isInfinite()) throw JsonWriteException("cannot write non-finite double: $value")
                out.append(value)
            }
            is String -> writeString(value, out)
            is List<*> -> writeList(value, out)
            is Map<*, *> -> writeMap(value, out)
            else -> throw JsonWriteException("cannot write value of type ${value::class}: $value")
        }
    }

    private fun writeList(list: List<*>, out: StringBuilder) {
        out.append('[')
        list.forEachIndexed { i, item ->
            if (i > 0) out.append(',')
            writeValue(item, out)
        }
        out.append(']')
    }

    private fun writeMap(map: Map<*, *>, out: StringBuilder) {
        out.append('{')
        map.entries.forEachIndexed { i, (key, value) ->
            if (key !is String) throw JsonWriteException("map key must be a String, was ${key?.let { it::class }}: $key")
            if (i > 0) out.append(',')
            writeString(key, out)
            out.append(':')
            writeValue(value, out)
        }
        out.append('}')
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c.code < 0x20 -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        private var pos = 0
        private var depth = 0

        fun parseDocument(): Any? {
            val value = parseValue()
            skipWhitespace()
            if (pos != text.length) throw JsonParseException("trailing data at $pos")
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) throw JsonParseException("unexpected end of input")
            return when (val c = text[pos]) {
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '[' -> parseArray()
                '{' -> parseObject()
                else -> if (c == '-' || c.isDigit()) parseNumber() else throw JsonParseException("unexpected character '$c' at $pos")
            }
        }

        private fun parseArray(): List<Any?> = withDepth {
            expect('[')
            val items = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return@withDepth items }
            while (true) {
                items += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; break }
                    else -> throw JsonParseException("expected ',' or ']' at $pos")
                }
            }
            items
        }

        private fun parseObject(): Map<String, Any?> = withDepth {
            expect('{')
            val entries = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return@withDepth entries }
            while (true) {
                skipWhitespace()
                val key = parseString()
                if (key in entries) throw JsonParseException("duplicate key '$key' at $pos")
                skipWhitespace()
                expect(':')
                entries[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; break }
                    else -> throw JsonParseException("expected ',' or '}' at $pos")
                }
            }
            entries
        }

        private fun <T> withDepth(block: () -> T): T {
            depth++
            if (depth > MAX_DEPTH) throw JsonParseException("nesting exceeds the limit of $MAX_DEPTH at $pos")
            try {
                return block()
            } finally {
                depth--
            }
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, pos)) throw JsonParseException("invalid literal at $pos")
            pos += literal.length
            return value
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw JsonParseException("unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> sb.append(parseEscape())
                    c.code < 0x20 -> throw JsonParseException("raw control character (0x%02x) at %d".format(c.code, pos - 1))
                    else -> sb.append(c)
                }
            }
        }

        private fun parseEscape(): Char {
            if (pos >= text.length) throw JsonParseException("unterminated escape")
            return when (val e = text[pos++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> parseUnicodeEscape()
                else -> throw JsonParseException("invalid escape '\\$e' at ${pos - 1}")
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (pos + 4 > text.length) throw JsonParseException("truncated unicode escape at $pos")
            val hex = text.substring(pos, pos + 4)
            val code = hex.toIntOrNull(16) ?: throw JsonParseException("invalid unicode escape '\\u$hex' at $pos")
            pos += 4
            return code.toChar()
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            val intStart = pos
            while (pos < text.length && text[pos].isDigit()) pos++
            val intPart = text.substring(intStart, pos)
            if (intPart.length > 1 && intPart[0] == '0') throw JsonParseException("leading zero at $intStart")
            var isDouble = false
            if (peek() == '.') {
                isDouble = true
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                isDouble = true
                pos++
                if (peek() == '+' || peek() == '-') pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            return if (isDouble) raw.toDouble() else raw.toLong()
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun expect(c: Char) {
            if (peek() != c) throw JsonParseException("expected '$c' at $pos")
            pos++
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
        }
    }
}
