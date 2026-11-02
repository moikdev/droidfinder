package dev.findroid

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class SetupJsonTreeTest {
    @Test fun strictJsonRejectsDuplicateFieldsTrailingInputAndLenientSyntax() {
        listOf("{\"version\":1,\"version\":2}", "{} {}", "{/*comment*/\"a\":1}", "{a:1}", "[1,]", "[NaN]").forEach { text ->
            assertThrows(Exception::class.java) { SetupJsonTree.parse(text) }
        }
    }
    @Test fun nestedPayloadIsRejectedBeforeARecursiveAndroidParserReadsIt() {
        val text = "[".repeat(60) + "0" + "]".repeat(60)
        assertThrows(IllegalArgumentException::class.java) { SetupJsonTree.parse(text) }
    }
    @Test fun readRejectsInvalidUtf8AndOverBudgetStreams() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { SetupJsonTree.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertThrows(IllegalArgumentException::class.java) { SetupJsonTree.read(ByteArrayInputStream(ByteArray(SetupJsonTree.MAX_BYTES + 1) { 32 })) }
    }
    @Test fun unicodeAndIntegerPrecisionSurviveStrictParsing() {
        val text = "{\"name\":\"旅の写真 📷\",\"bytes\":9223372036854775807,\"enabled\":true}"
        val root = SetupJsonTree.parse(text).asJsonObject
        assertEquals(Long.MAX_VALUE, root.get("bytes").asLong)
        assertEquals("旅の写真 📷", root.get("name").asString)
        assertEquals(text.toByteArray().toList(), SetupJsonTree.read(text.byteInputStream()).toByteArray().toList())
    }
}
