package com.a2z.nsdl.ipc.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonTest {
    @Test
    fun `parses the literals true, false and null`() {
        assertEquals(true, Json.parse("true"))
        assertEquals(false, Json.parse("false"))
        assertEquals(null, Json.parse("null"))
    }

    @Test
    fun `parses a plain string with no escapes`() {
        assertEquals("hello", Json.parse("\"hello\""))
    }

    @Test
    fun `parses an integer as a Long`() {
        val value = Json.parse("42")
        assertEquals(42L, value)
        assertTrue(value is Long)
    }

    @Test
    fun `parses a decimal as a Double`() {
        val value = Json.parse("3.5")
        assertEquals(3.5, value)
        assertTrue(value is Double)
    }

    @Test
    fun `parses a negative number`() {
        assertEquals(-42L, Json.parse("-42"))
    }

    @Test
    fun `parses an empty array and one with mixed values`() {
        assertEquals(emptyList<Any?>(), Json.parse("[]"))
        assertEquals(listOf(1L, "two", true, null), Json.parse("""[1, "two", true, null]"""))
    }

    @Test
    fun `parses an empty object and one with several keys`() {
        assertEquals(emptyMap<String, Any?>(), Json.parse("{}"))
        assertEquals(mapOf("a" to 1L, "b" to "x"), Json.parse("""{"a": 1, "b": "x"}"""))
    }

    @Test
    fun `parses nested arrays and objects`() {
        assertEquals(
            mapOf("items" to listOf(mapOf("id" to 1L), mapOf("id" to 2L))),
            Json.parse("""{"items": [{"id": 1}, {"id": 2}]}"""),
        )
    }

    @Test
    fun `parses standard backslash escapes`() {
        assertEquals("a\"b\\c/d\bf\u000Cn\nr\rt\t", Json.parse(""""a\"b\\c\/d\bf\fn\nr\rt\t""""))
    }

    @Test
    fun `parses a unicode escape`() {
        val backslash = 0x5C.toChar()
        val jsonInput = "\"" + backslash + "u00e9\""
        assertEquals(0x00e9.toChar().toString(), Json.parse(jsonInput))
    }

    @Test
    fun `parses a surrogate pair unicode escape as one character outside the BMP`() {
        val backslash = 0x5C.toChar()
        val jsonInput = "\"" + backslash + "uD83D" + backslash + "uDE00\""
        val expected = String(Character.toChars(0x1F600))
        assertEquals(expected, Json.parse(jsonInput))
    }

    @Test
    fun `rejects a raw control character inside a string`() {
        val jsonInput = "\"a" + 0x01.toChar() + "b\""
        assertThrows(JsonParseException::class.java) { Json.parse(jsonInput) }
    }

    @Test
    fun `rejects trailing data after a complete value`() {
        assertThrows(JsonParseException::class.java) { Json.parse("true false") }
        assertThrows(JsonParseException::class.java) { Json.parse("42 extra") }
    }

    @Test
    fun `rejects a number with a leading zero`() {
        assertThrows(JsonParseException::class.java) { Json.parse("01") }
        assertThrows(JsonParseException::class.java) { Json.parse("-01") }
        assertEquals(0L, Json.parse("0"), "bare zero is fine")
        assertEquals(0.5, Json.parse("0.5"), "zero before a decimal point is fine")
    }

    @Test
    fun `rejects a duplicate key in an object`() {
        assertThrows(JsonParseException::class.java) { Json.parse("""{"a": 1, "a": 2}""") }
    }

    @Test
    fun `rejects nesting deeper than the configured limit`() {
        val deeplyNested = "[".repeat(200) + "]".repeat(200)
        assertThrows(JsonParseException::class.java) { Json.parse(deeplyNested) }
    }

    @Test
    fun `writes each scalar type`() {
        assertEquals("null", Json.write(null))
        assertEquals("true", Json.write(true))
        assertEquals("42", Json.write(42L))
        assertEquals("3.5", Json.write(3.5))
        assertEquals("\"hi\"", Json.write("hi"))
    }

    @Test
    fun `writes a string's special characters escaped`() {
        assertEquals("\"a\\\"b\\\\c\"", Json.write("a\"b\\c"))
    }

    @Test
    fun `writes lists and maps`() {
        assertEquals("[1,2,3]", Json.write(listOf(1L, 2L, 3L)))
        assertEquals("{\"a\":1}", Json.write(mapOf("a" to 1L)))
    }

    @Test
    fun `a written structure parses back to an equal value`() {
        val original = mapOf(
            "name" to "printer1",
            "count" to 3L,
            "ratio" to 1.5,
            "active" to true,
            "tags" to listOf("a", "b", null),
        )
        assertEquals(original, Json.parse(Json.write(original)))
    }

    @Test
    fun `rejects writing NaN`() {
        assertThrows(JsonWriteException::class.java) { Json.write(Double.NaN) }
    }
}
