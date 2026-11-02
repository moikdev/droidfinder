package dev.findroid

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StoragePreflightTest {
    @Test
    fun unknownProviderSizeStaysUnknownAndLowSpaceStopsBeforeCreatingDestination() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder =
            File(context.filesDir, "ProviderFixtures/Space-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(folder, "Unknown-size.bin").writeText("unknown metadata")
            File(folder, "Known.bin").writeText("known contents")
            val out = File(folder, "Out").apply { mkdir() }
            val tree =
                DocumentsContract.buildTreeDocumentUri(
                    "dev.findroid.fixtures",
                    "root/${folder.name}",
                )
            val root =
                DocumentsContract.buildDocumentUriUsingTree(tree, "root/${folder.name}").toString()
            val engine = FileEngine(File(folder, "state"))
            val docs = DocumentAccess(context, engine, spaceProbe = { 1 })
            val entries = docs.list(root)
            val unknown = entries.first { it.name == "Unknown-size.bin" }
            assertFalse(unknown.sizeKnown)
            assertEquals("Unknown size", unknown.displaySize)
            val estimate = docs.estimate(listOf(unknown.path), TransferControl())
            assertEquals(1, estimate.unknownFiles)
            assertNull(estimate.totalBytes)
            val known = entries.first { it.name == "Known.bin" }
            assertTrue(
                runCatching { docs.copy(known.path, out.path, true) }.exceptionOrNull()
                    is InsufficientSpaceException,
            )
            assertTrue(File(folder, "Known.bin").exists())
            assertTrue(out.list()!!.isEmpty())
            val unknownCapacity = DocumentAccess(context, engine, spaceProbe = { null })
            val copied = unknownCapacity.copy(unknown.path, out.path, false)
            assertEquals("unknown metadata", File(copied).readText())
            assertTrue(File(folder, "Unknown-size.bin").exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun lowSpaceReplacementRestoresProtectedOriginalAndKeepsIncomingSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder =
            File(context.filesDir, "ProviderFixtures/Replace-space-${System.nanoTime()}").apply {
                mkdirs()
            }
        try {
            File(folder, "Same.txt").writeText("incoming")
            val out = File(folder, "Out").apply { mkdir() }
            val original = File(out, "Same.txt").apply { writeText("original") }
            val tree =
                DocumentsContract.buildTreeDocumentUri(
                    "dev.findroid.fixtures",
                    "root/${folder.name}",
                )
            val root =
                DocumentsContract.buildDocumentUriUsingTree(tree, "root/${folder.name}").toString()
            val engine = FileEngine(File(folder, "state"))
            val docs = DocumentAccess(context, engine, spaceProbe = { 1 })
            val from = docs.list(root).first { it.name == "Same.txt" }.path
            var job =
                TransferJob(
                    paths = listOf(from),
                    destination = out.path,
                    move = true,
                    conflictPolicy = ConflictPolicy.REPLACE,
                )
            val steps = mutableListOf<FileStep>()
            runBlocking {
                TransferWorker(
                        job,
                        docs,
                        TransferControl(),
                        current = { job },
                        checkpoint = { job = it(job) },
                        ask = { _, _, _ -> error("Policy handles conflict") },
                        metadata = { _, _ -> },
                        steps = steps,
                    )
                    .run()
            }
            assertEquals("original", original.readText())
            assertEquals("incoming", File(folder, "Same.txt").readText())
            assertEquals(setOf("Same.txt"), out.list()!!.toSet())
            assertNull(job.results.single().destination)
            assertTrue(job.results.single().error!!.contains("Not enough space"))
            assertTrue(steps.isEmpty())
            assertEquals(8L, job.estimate!!.knownBytes)
            assertEquals(1L, job.availableAtStart)
        } finally {
            folder.deleteRecursively()
        }
    }
}
