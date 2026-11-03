package dev.findroid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class TransferProgressTest {
    @Test
    fun recentRateAdaptsToFasterAndSlowerStorageInsteadOfKeepingTheLifetimeAverage() {
        var now = 0L
        val control = TransferControl(clockMillis = { now })
        control.phase("Copying", 100_000, TransferStage.COPYING)
        repeat(24) { now += 500; control.bytes(100) }
        assertEquals(200L, control.snapshot.bytesPerSecond)
        repeat(12) { now += 500; control.bytes(1000) }
        assertEquals(2000L, control.snapshot.bytesPerSecond)
        assertEquals(43L, control.snapshot.secondsRemaining)
        repeat(12) { now += 500; control.bytes(100) }
        assertEquals(200L, control.snapshot.bytesPerSecond)
        assertEquals(15_600L, control.snapshot.bytes)
    }

    @Test
    fun rateNeedsAnObservationWindowAndExpiresWithoutNewBytes() {
        var now = 0L
        val control = TransferControl(clockMillis = { now })
        control.phase("Copying", 1000, TransferStage.COPYING)
        now = 500; control.bytes(100)
        assertNull(control.snapshot.bytesPerSecond)
        now = 1500; control.bytes(200)
        assertEquals(200L, control.snapshot.bytesPerSecond)
        now = 5000; control.refreshProgress()
        assertNull(control.snapshot.bytesPerSecond)
        assertNull(control.snapshot.secondsRemaining)
        assertEquals(300L, control.snapshot.bytes)
        assertEquals(1500L, control.snapshot.lastProgressAtMillis)
        assertEquals(5000L, control.snapshot.updatedAtMillis)
    }

    @Test
    fun recoveryFromALongReadWarmsUpAgainWithoutInventingABurst() {
        var now = 0L
        val control = TransferControl(clockMillis = { now })
        control.phase("Copying", 10_000)
        now = 2000; control.bytes(200)
        assertEquals(100L, control.snapshot.bytesPerSecond)
        now = 20_000; control.bytes(2000)
        assertNull(control.snapshot.bytesPerSecond)
        assertNull(control.snapshot.secondsRemaining)
        repeat(3) { now += 500; control.bytes(100) }
        assertEquals(200L, control.snapshot.bytesPerSecond)
    }

    @Test
    fun pausedTimeDoesNotDiluteTheRecentWindowOrAddCopiedBytes() {
        var now = 0L
        val control = TransferControl(clockMillis = { now })
        control.phase("Copying", 1000)
        now = 2000; control.bytes(200)
        control.pause()
        now = 102_000; control.refreshProgress()
        assertEquals(2000L, control.snapshot.activeMillis)
        assertEquals(200L, control.snapshot.bytes)
        val paused = transferPhaseSummary(control.snapshot, true, now)
        assertTrue(paused.contains("Paused"))
        assertFalse(paused.contains("/s"))
        assertFalse(paused.contains("left"))
        control.resume()
        assertEquals(100L, control.snapshot.bytesPerSecond)
        assertEquals(8L, control.snapshot.secondsRemaining)
        now += 1000; control.bytes(100)
        assertEquals(100L, control.snapshot.bytesPerSecond)
    }

    @Test
    fun verificationHasSeparateByteCountsAndDoesNotInflateTransferredTotals() {
        var now = 0L
        val control = TransferControl(clockMillis = { now })
        control.phase("Copying", 1000, TransferStage.COPYING)
        now = 2000; control.bytes(200)
        control.phase("Verifying source", 1000, TransferStage.VERIFYING)
        assertNull(control.snapshot.bytesPerSecond)
        assertEquals(0L, control.snapshot.phaseBytes)
        now += 2000; control.verifiedBytes(500)
        assertEquals(200L, control.snapshot.bytes)
        assertEquals(500L, control.snapshot.phaseBytes)
        assertEquals(250L, control.snapshot.bytesPerSecond)
        assertEquals(2L, control.snapshot.secondsRemaining)
        control.phase("Publishing copy", stage = TransferStage.FINISHING)
        assertNull(control.snapshot.fraction)
        assertNull(control.snapshot.secondsRemaining)
        assertTrue(transferPhaseSummary(control.snapshot, false, now).contains("Finishing"))
    }

    @Test
    fun preparationReportsInspectedItemsWithoutClaimingCopiedDataOrAnEta() {
        var now = 0L
        val control = TransferControl(clockMillis = { now })
        control.phase("Checking files", stage = TransferStage.PREPARING)
        repeat(20) { now += 1000; control.inspected() }
        control.refreshProgress()
        assertEquals(20, control.snapshot.inspectedItems)
        assertEquals(0L, control.snapshot.bytes)
        assertEquals(0L, control.snapshot.phaseBytes)
        assertNull(control.snapshot.secondsRemaining)
        val text = transferPhaseSummary(control.snapshot, false, now)
        assertTrue(text.contains("20 items inspected"))
        assertFalse(text.contains("No new progress"))
    }

    @Test
    fun longProviderReadReportsItsActualWaitAndLeavesTheOriginalBytesIntact() {
        var now = 0L
        val control = TransferControl(clockMillis = { now })
        val sourceBytes = byteArrayOf(11, 23, 45)
        var inspected = false
        val input = object : ByteArrayInputStream(sourceBytes) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!inspected) {
                    inspected = true
                    now += 4000
                    control.refreshProgress()
                    assertEquals(TransferIo.PROVIDER, control.snapshot.io)
                    assertEquals("Waiting for the storage provider", control.snapshot.waitingMessage(now))
                    assertEquals(0L, control.snapshot.bytes)
                    assertNull(control.snapshot.secondsRemaining)
                }
                return super.read(bytes, offset, length)
            }
        }
        val output = ByteArrayOutputStream()
        assertEquals(3L, control.copy(input, output, 3, sourceIsProvider = true))
        control.refreshProgress()
        assertArrayEquals(sourceBytes, output.toByteArray())
        assertNull(control.snapshot.io)
        assertNull(control.snapshot.waitingMessage(now))
    }

    @Test
    fun aBlockedLocalWriteIsDescribedAsTheDestinationAndNotAProvider() {
        var now = 0L
        val control = TransferControl(clockMillis = { now })
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                now += 4000
                control.refreshProgress()
                assertEquals(TransferIo.DESTINATION, control.snapshot.io)
                assertEquals("Waiting for the destination", control.snapshot.waitingMessage(now))
                assertEquals(0L, control.snapshot.bytes)
            }
        }
        assertEquals(3L, control.copy(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 3))
        assertEquals(3L, control.snapshot.bytes)
    }

    @Test
    fun storageWaitScopesRestoreTheirParentAndClearOnFailure() {
        var now = 0L
        val control = TransferControl(clockMillis = { now })
        control.storage(TransferIo.PROVIDER) {
            now = 4000; control.refreshProgress()
            assertEquals("Waiting for the storage provider", control.snapshot.waitingMessage(now))
            val failure = runCatching {
                control.storage(TransferIo.DESTINATION) {
                    now = 8000; control.refreshProgress()
                    assertEquals("Waiting for the destination", control.snapshot.waitingMessage(now))
                    throw IOException("Synthetic write failure")
                }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            control.refreshProgress()
            assertEquals(TransferIo.PROVIDER, control.snapshot.io)
        }
        control.refreshProgress()
        assertNull(control.snapshot.io)
    }

    @Test
    fun recentRateRetainsBoundedHistoryDuringDenseLongCopies() {
        val window = TransferRateWindow()
        for (time in 1L..100_000L) window.record(time, time * 100)
        assertTrue(window.retainedSamples <= 25)
        assertEquals(100_000L, window.rate(100_000))
    }

    @Test
    fun timeEstimatesNameTheirPhaseAndDoNotOverflowForHugeKnownFiles() {
        val progress = TransferProgress(phaseTotalBytes = Long.MAX_VALUE, bytesPerSecond = 1)
        val description = transferEta(progress)!!
        assertTrue(description.contains("left in this phase"))
        assertFalse(description.contains("-"))
    }

    @Test
    fun anUnansweredRequestDoesNotBecomeAClaimThatTheTransferHasFailed() {
        val progress = TransferProgress(phase = "Checking files", stage = TransferStage.PREPARING,
            updatedAtMillis = 20_000, io = TransferIo.PROVIDER, ioStartedAtMillis = 0)
        val text = transferPhaseSummary(progress, false, 20_000)
        assertEquals("Waiting for the storage provider", text)
        assertFalse(text.contains("stalled", ignoreCase = true))
        assertFalse(text.contains("failed", ignoreCase = true))
    }
}
