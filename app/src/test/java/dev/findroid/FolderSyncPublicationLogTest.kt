package dev.findroid

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderSyncPublicationLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private inner class Fixture {
        val base = temporary.newFolder()
        val parent = File(base, "Private journal parent").apply { mkdir() }
        val working = File(base, "Working").apply { mkdir(); File(this, "copy-00000").writeText("prepared") }
        val destination = File(base, "Destination").apply { mkdir() }
        val storage = FolderSyncNioStorage()
        val intent = FolderSyncPublicationIntent(File(working, "copy-00000").path, File(destination, "New.txt").path)
        fun create(owner: () -> String = { "ce9a673d-464c-4c83-85a7-43ce0332dd3e" }) =
            FolderSyncPublicationLog.create(parent.path, working.path, destination.path, storage, owner)
        fun events(log: FolderSyncPublicationLog) = File(log.directory).listFiles()!!.sortedBy { it.name }.map {
            JsonParser.parseString(it.readText()).asJsonObject.get("event").asString
        }
    }
    private fun refused(work: () -> Unit) {
        try { work(); fail("Expected journal refusal") } catch (_: Exception) { }
    }
    @Test fun creationAndCompletedPublicationsKeepEveryImmutableRecord() {
        val f = Fixture(); val log = f.create(); val header = File(log.directory, "00000.json").readBytes()
        log.begin(f.intent); log.complete(f.intent); log.finish()
        assertEquals(listOf("created", "publication-intent", "publication-complete", "finished"), f.events(log))
        assertArrayEquals(header, File(log.directory, "00000.json").readBytes())
        assertEquals("prepared", File(f.intent.working).readText()); assertFalse(File(f.intent.destination).exists())
        refused { log.begin(f.intent) }; assertEquals(4, f.events(log).size)
    }
    @Test fun reopeningNeverReusesOrTruncatesAnExistingJournal() {
        val f = Fixture(); val log = f.create(); log.begin(f.intent)
        val bytes = File(log.directory).listFiles()!!.associate { it.name to it.readText() }
        refused { f.create() }
        assertEquals(bytes, File(log.directory).listFiles()!!.associate { it.name to it.readText() })
    }
    @Test fun anUnfinishedIntentBlocksAnotherMutationOrCompletionOfTheRun() {
        val f = Fixture(); val log = f.create(); log.begin(f.intent)
        refused { log.begin(f.intent.copy(destination = f.intent.destination + "-other")) }
        refused { log.finish() }; refused { log.complete(f.intent.copy(working = f.intent.working + "-other")) }
        assertEquals(listOf("created", "publication-intent"), f.events(log))
        log.complete(f.intent); log.finish(); assertEquals(4, f.events(log).size)
    }
    @Test fun aPartialIntentWriteIsRetainedAndBlocksLaterWrites() {
        val f = Fixture(); val log = f.create()
        f.storage.afterWrite = { if (it.endsWith("00001.json")) error("Synthetic full disk during intent") }
        refused { log.begin(f.intent) }; val partial = File(log.directory, "00001.json").readBytes()
        f.storage.afterWrite = {}; refused { log.begin(f.intent) }; refused { log.finish() }
        assertArrayEquals(partial, File(log.directory, "00001.json").readBytes())
        assertEquals("prepared", File(f.intent.working).readText())
    }
    @Test fun anIntentReadbackMismatchBlocksPublicationAndPreservesTheBadRecord() {
        val f = Fixture(); val log = f.create()
        f.storage.afterWrite = { path -> if (path.endsWith("00001.json")) {
            val bytes = File(path).readBytes(); bytes[0] = (bytes[0].toInt() xor 1).toByte(); File(path).writeBytes(bytes)
        } }
        refused { log.begin(f.intent) }; val changed = File(log.directory, "00001.json").readBytes()
        f.storage.afterWrite = {}; refused { log.begin(f.intent) }
        assertArrayEquals(changed, File(log.directory, "00001.json").readBytes())
        assertEquals("prepared", File(f.intent.working).readText()); assertFalse(File(f.intent.destination).exists())
    }
    @Test fun changedAndUnexpectedRecordsStopWithoutDeletingThem() {
        val f = Fixture(); val log = f.create(); val extra = File(log.directory, "unexpected.txt").apply { writeText("keep") }
        refused { log.begin(f.intent) }; assertEquals("keep", extra.readText()); assertEquals(2, File(log.directory).listFiles()!!.size)
        val g = Fixture(); val other = g.create(); val header = File(other.directory, "00000.json").apply { writeText("changed original record") }
        refused { other.begin(g.intent) }; assertEquals("changed original record", header.readText())
    }
    @Test fun redirectedJournalParentsAndLinkedRecordsAreRefused() {
        val f = Fixture(); val log = f.create(); val old = File(f.base, "Renamed parent")
        assertTrue(f.parent.renameTo(old)); f.parent.mkdir()
        refused { log.begin(f.intent) }; assertEquals(1, File(old, File(log.directory).name).listFiles()!!.size)
        val g = Fixture(); val other = g.create(); val original = File(other.directory, "00000.json")
        val moved = File(g.base, "Original header"); assertTrue(original.renameTo(moved)); Files.createSymbolicLink(original.toPath(), moved.toPath())
        refused { other.begin(g.intent) }; assertTrue(Files.isSymbolicLink(original.toPath())); assertTrue(moved.isFile)
    }
    @Test fun aJournalCannotBeCreatedInsideTheReviewedOrWorkingTree() {
        val f = Fixture()
        refused { FolderSyncPublicationLog.create(f.working.path, f.working.path, f.destination.path, f.storage) }
        refused { FolderSyncPublicationLog.create(f.destination.path, f.working.path, f.destination.path, f.storage) }
        assertTrue(f.parent.listFiles()!!.isEmpty()); assertTrue(f.destination.listFiles()!!.isEmpty())
    }
    @Test fun invalidOwnersAndRelativeMutationPathsAreRefusedWithoutInventingRecords() {
        val f = Fixture(); refused { f.create { "../old" } }; assertTrue(f.parent.listFiles()!!.isEmpty())
        val log = f.create(); refused { log.begin(f.intent.copy(working = "relative")) }
        assertEquals(listOf("created"), f.events(log))
    }
}
