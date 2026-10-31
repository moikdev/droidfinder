package dev.droidfinder

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var root: File
    private lateinit var engine: FileEngine

    @Before
    fun setup() {
        root = temporary.newFolder("files")
        engine = FileEngine(temporary.newFolder("state"))
    }

    private fun zip(name: String, entries: Map<String, String>): File =
        File(root, name).also { file ->
            ZipOutputStream(file.outputStream()).use { output ->
                entries.forEach { (path, text) ->
                    output.putNextEntry(ZipEntry(path))
                    output.write(text.toByteArray())
                    output.closeEntry()
                }
            }
        }

    private fun reject(work: () -> Unit) {
        try {
            work()
            fail("Expected archive rejection")
        } catch (_: IllegalArgumentException) {} catch (_: java.util.zip.ZipException) {}
    }

    @Test
    fun namedCompressionKeepsSourceAndAvoidsOverwrites() {
        val source = File(root, "Notes.md").apply { writeText("original") }
        val first = engine.compress(listOf(source), root, name = "Project files")
        val second = engine.compress(listOf(source), root, name = "Project files.zip")
        assertEquals("Project files.zip", first.name)
        assertEquals("Project files 2.zip", second.name)
        assertEquals("original", source.readText())
        ZipFile(second).use {
            assertEquals("original", it.getInputStream(it.getEntry("Notes.md")).reader().readText())
        }
    }

    @Test
    fun dottedFolderNameIsPreserved() {
        val folder = File(root, "Project.v2").apply { mkdir() }
        File(folder, "Data.txt").writeText("content")
        val archive = engine.compress(listOf(folder), root)
        assertEquals("Project.v2.zip", archive.name)
        assertTrue(inspectZip(archive).items.any { it.path == "Project.v2/Data.txt" })
    }

    @Test
    fun equalNamesFromDifferentFoldersKeepBothContents() {
        val a = File(root, "A").apply { mkdir() }
        val b = File(root, "B").apply { mkdir() }
        val first = File(a, "Notes.txt").apply { writeText("first") }
        val second = File(b, "Notes.txt").apply { writeText("second") }
        val archive = engine.compress(listOf(first, second), root)
        ZipFile(archive).use {
            assertEquals("first", it.getInputStream(it.getEntry("Notes.txt")).reader().readText())
            assertEquals(
                "second",
                it.getInputStream(it.getEntry("Notes 2.txt")).reader().readText(),
            )
        }
    }

    @Test
    fun compressionLevelsProduceReadableArchives() {
        val source = File(root, "Large.txt").apply { writeText("repeated data\n".repeat(25_000)) }
        val plain = engine.compress(listOf(source), root, name = "Plain.zip", level = 0)
        val small = engine.compress(listOf(source), root, name = "Small.zip", level = 9)
        assertTrue(small.length() < plain.length() / 10)
        assertEquals(source.readText(), File(engine.extract(small, root), source.name).readText())
    }

    @Test
    fun timestampsSurviveRoundTrip() {
        val folder = File(root, "Folder").apply { mkdir() }
        val file = File(folder, "Data.txt").apply { writeText("time") }
        val stamp = 1_650_000_000_000L
        assertTrue(file.setLastModified(stamp))
        assertTrue(folder.setLastModified(stamp))
        val archive = engine.compress(listOf(folder), root)
        val extracted = engine.extract(archive, root)
        assertTrue(
            kotlin.math.abs(File(extracted, "Folder/Data.txt").lastModified() - stamp) < 2_000,
        )
        assertTrue(kotlin.math.abs(File(extracted, "Folder").lastModified() - stamp) < 2_000)
    }

    @Test
    fun browserInfersFoldersWithoutInflatingFiles() {
        val archive = zip("Nested.zip", mapOf("Projects/Design/Notes.txt" to "hello"))
        val index = inspectZip(archive)
        assertEquals(
            setOf("Projects", "Projects/Design", "Projects/Design/Notes.txt"),
            index.items.map { it.path }.toSet(),
        )
        assertEquals(5L, index.size)
        assertFalse(File(root, "Projects").exists())
    }

    @Test
    fun invalidAndUnsafeArchivesLeaveNoOutput() {
        val invalid = File(root, "Broken.zip").apply { writeText("not a ZIP file") }
        reject { engine.extract(invalid, root) }
        assertFalse(File(root, "Broken").exists())
        listOf("../escape.txt", "C:/escape.txt", "..\\escape.txt", "/absolute.txt")
            .forEachIndexed { index, path ->
                val archive = zip("Unsafe-$index.zip", mapOf(path to "rejected"))
                reject { inspectZip(archive) }
                reject { engine.extract(archive, root) }
                assertFalse(File(root, "Unsafe-$index").exists())
            }
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".droidfinder-") })
    }

    @Test
    fun emptyZipIsValidAndInvalidNamesAreRejected() {
        val archive = zip("Empty.zip", emptyMap())
        assertTrue(inspectZip(archive).items.isEmpty())
        assertTrue(engine.extract(archive, root).isDirectory)
        reject { zipFilename("../bad.zip") }
        reject { zipFilename(".zip") }
    }
}
