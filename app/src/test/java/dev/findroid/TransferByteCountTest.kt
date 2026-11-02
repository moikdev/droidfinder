package dev.findroid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class TransferByteCountTest {
    @Test
    fun exactAndUnknownLengthStreamsReturnTheirActualCount() {
        for (expected in listOf(null, 70_000L)) {
            val source = ByteArray(70_000) { (it % 251).toByte() }
            val output = ByteArrayOutputStream()
            assertEquals(70_000L, TransferControl().copy(ByteArrayInputStream(source), output, expected))
            assertArrayEquals(source, output.toByteArray())
        }
    }

    @Test
    fun prematureEofIsAnErrorIncludingEmptyFilesWithPositiveAdvertisedSize() {
        for (actual in listOf(0, 3, 65_536)) {
            val output = ByteArrayOutputStream()
            val failure = runCatching {
                TransferControl().copy(ByteArrayInputStream(ByteArray(actual)), output, actual + 1L)
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("incomplete"))
        }
    }

    @Test
    fun unendingStreamCannotFillTheDestinationBeyondItsAdvertisedSize() {
        var reads = 0
        val source = object : InputStream() {
            override fun read() = 1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                check(reads < 10) { "The byte limit did not stop an unending stream" }
                bytes.fill(1, offset, offset + length)
                return length
            }
        }
        val output = ByteArrayOutputStream()
        val failure = runCatching { TransferControl().copy(source, output, 70_000) }.exceptionOrNull()
        assertTrue(failure!!.message.orEmpty().contains("grew"))
        assertEquals(2, reads)
        assertEquals(65_536, output.size())
    }

    @Test
    fun emptyStreamWithZeroAdvertisedSizeSucceeds() {
        assertEquals(0L, TransferControl().copy(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 0))
    }
}
