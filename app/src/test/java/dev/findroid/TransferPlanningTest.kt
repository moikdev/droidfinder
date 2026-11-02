package dev.findroid

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TransferPlanningTest {
    @Test
    fun inventoryIncludesEmptyFoldersAndCountsNestedFilesWithoutOpeningContents() {
        val nodes =
            mapOf(
                "root" to EstimateNode("root", true),
                "empty" to EstimateNode("empty", true),
                "zero" to EstimateNode("zero", false, 0),
                "data" to EstimateNode("data", false, 120),
            )
        val estimate =
            estimateTransfer(
                listOf("root"),
                { nodes.getValue(it) },
                {
                    if (it == "root")
                        listOf(
                            nodes.getValue("empty"),
                            nodes.getValue("zero"),
                            nodes.getValue("data"),
                        )
                    else emptyList()
                },
            )
        assertEquals(TransferEstimate(120, 2, 2), estimate)
        assertEquals(120L, estimate.totalBytes)
    }

    @Test
    fun unavailableAndUnknownMetadataProducesPartialTotalsRatherThanFalseZeroes() {
        val estimate =
            estimateTransfer(
                listOf("missing", "root"),
                {
                    if (it == "missing") error("Offline") else EstimateNode("root", true)
                },
                {
                    listOf(
                        EstimateNode("known", false, 12),
                        EstimateNode("unknown", false),
                        EstimateNode("negative", false, -1),
                    )
                },
            )
        assertEquals(12, estimate.knownBytes)
        assertEquals(2, estimate.unknownFiles)
        assertEquals(1, estimate.unreadable)
        assertNull(estimate.totalBytes)
    }

    @Test
    fun cyclesLinksAndLimitsDoNotBecomeExactTotals() {
        val node = EstimateNode("root", true)
        val cycle =
            estimateTransfer(
                listOf("root"),
                { node },
                { listOf(node, EstimateNode("link", true, link = true)) },
            )
        assertEquals(2, cycle.unreadable)
        assertNull(cycle.totalBytes)
        val limited =
            estimateTransfer(
                listOf("root"),
                { node },
                { listOf(EstimateNode("child", false, 2)) },
                limit = 1,
            )
        assertTrue(limited.limited)
        assertNull(limited.totalBytes)
        val overflow =
            estimateTransfer(
                listOf("root"),
                { node },
                {
                    listOf(EstimateNode("a", false, Long.MAX_VALUE), EstimateNode("b", false, 1))
                },
            )
        assertEquals(Long.MAX_VALUE, overflow.knownBytes)
        assertTrue(overflow.limited)
    }

    @Test
    fun canceledInventoryNeverListsOrWrites() {
        val control = TransferControl().apply { cancel() }
        var read = false
        assertTrue(
            runCatching {
                estimateTransfer(
                    listOf("root"),
                    {
                        read = true
                        EstimateNode(it, true)
                    },
                    { error("Must not list") },
                    control,
                )
            }
                .exceptionOrNull() is CancellationException,
        )
        assertFalse(read)
    }

    @Test
    fun lowSpaceCopyStopsBeforeStagingButSameVolumeRenameStillWorks() {
        val root = Files.createTempDirectory("space-check").toFile()
        try {
            val engine = FileEngine(File(root, "state"), freeBytes = { 1 })
            val source = File(root, "Source.txt").apply { writeText("data") }
            val target = File(root, "Out").apply { mkdir() }
            val error = runCatching { engine.copy(source, target) }.exceptionOrNull()
            assertTrue(error is InsufficientSpaceException)
            assertEquals("data", source.readText())
            assertTrue(target.list()!!.isEmpty())
            val moved = engine.copy(source, target, move = true)
            assertEquals("data", moved.readText())
            assertFalse(source.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownCapacityAllowsSafeStagedCopyAndKnownLowerBoundIsEnforced() {
        val root = Files.createTempDirectory("unknown-space").toFile()
        try {
            val engine = FileEngine(File(root, "state"), freeBytes = { null })
            val source = File(root, "Source.txt").apply { writeText("data") }
            val target = File(root, "Out").apply { mkdir() }
            assertEquals("data", engine.copy(source, target).readText())
            requireTransferSpace(50, null)
            requireTransferSpace(50, -1)
            requireTransferSpace(50, 50)
            assertTrue(
                runCatching { requireTransferSpace(50, 49) }.exceptionOrNull()
                    is InsufficientSpaceException,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun phaseRateAndEtaExcludePausedTimeAndDoNotMixRecoveryBytes() {
        var clock = 0L
        val token = TransferControl(clockMillis = { clock })
        token.phase("Copying", 1000)
        clock = 2000
        token.bytes(200)
        assertEquals(.2f, token.snapshot.fraction!!, .001f)
        assertEquals(100L, token.snapshot.bytesPerSecond)
        assertEquals(8L, token.snapshot.secondsRemaining)
        token.pause()
        clock = 102000
        token.resume()
        assertEquals(2000L, token.snapshot.activeMillis)
        assertEquals(8L, token.snapshot.secondsRemaining)
        token.phase("Saving recovery copy", 300)
        clock += 2000
        token.bytes(100)
        assertEquals(300, token.snapshot.bytes)
        assertEquals(100, token.snapshot.phaseBytes)
        assertEquals(4L, token.snapshot.secondsRemaining)
        assertTrue(
            "A sample newer than the UI clock tick is fresh",
            token.snapshot.fresh(clock - 500),
        )
        assertTrue(token.snapshot.fresh(clock + 3000))
        assertFalse(token.snapshot.fresh(clock + 3001))
        assertFalse(token.snapshot.fresh(clock + 4000))
    }

    @Test
    fun changedAndUnknownSizesDoNotInventPercentagesOrEtas() {
        var clock = 0L
        val token = TransferControl(clockMillis = { clock })
        token.phase("Copying", 10)
        clock = 2000
        token.bytes(20)
        assertNull(token.snapshot.phaseTotalBytes)
        assertNull(token.snapshot.fraction)
        assertNull(token.snapshot.secondsRemaining)
        token.phase("Checking files")
        assertNull(token.snapshot.bytesPerSecond)
        assertNull(token.snapshot.fraction)
        token.phase("Copying", 0)
        assertNull(token.snapshot.fraction)
    }

    @Test
    fun boundedInventoryDoesNotConsumeAnEntireHugeChildSequence() {
        var produced = 0
        val estimate =
            estimateTransfer(
                listOf("root"),
                { EstimateNode(it, true) },
                {
                    sequence {
                        repeat(1_000_000) {
                            produced++
                            yield(EstimateNode("file-$it", false, 1))
                        }
                    }
                        .asIterable()
                },
                limit = 10,
            )
        assertTrue(estimate.limited)
        assertEquals(9, estimate.files)
        assertTrue("Only the bounded planning window should be consumed", produced <= 10)
        assertNull(estimate.totalBytes)
    }
}
