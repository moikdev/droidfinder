package dev.droidfinder

import java.io.File
import java.nio.file.Files
import java.util.Properties
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrganizationIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store() = TagStore(File(temporary.root, "library"))

    @Test
    fun parentAliasesShareIdentityButSelectedSymlinksAndHardLinksStaySeparate() {
        val real = temporary.newFolder("Real").canonicalFile
        val original = File(real, "A.txt").apply { writeText("unchanged") }
        val alias = File(temporary.root, "Alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val link = File(real, "Shortcut.txt")
        Files.createSymbolicLink(link.toPath(), original.toPath())
        val hard = File(real, "Hard.txt")
        Files.createLink(hard.toPath(), original.toPath())
        assertTrue(Files.isSameFile(hard.toPath(), original.toPath()))
        assertEquals(original.path, organizationKey(File(alias, "A.txt").path))
        assertEquals(link.path, organizationKey(link.path))
        assertNotEquals(organizationKey(link.path), organizationKey(original.path))
        assertNotEquals(organizationKey(hard.path), organizationKey(original.path))
        assertNotEquals(organizationKey(alias.path), organizationKey(real.path))
    }

    @Test
    fun missingChildrenKeepCanonicalParentsAndProviderCapabilitiesStayOpaque() {
        val real = temporary.newFolder("Real").canonicalFile
        val alias = File(temporary.root, "Alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        assertEquals(
            File(real, "Missing/Note.txt").path,
            organizationKey(File(alias, "Missing/Note.txt").path),
        )
        val uri = "content://provider/tree/scoped%2Froot/document/opaque%3A42"
        assertEquals(uri, organizationKey(uri))
        assertNotEquals(
            organizationKey(uri),
            organizationKey(uri.replace("scoped%2Froot", "anotherScope")),
        )
        assertEquals("@tag:Work/Ideas", organizationKey("@tag:Work/Ideas"))
    }

    @Test
    fun legacyAliasAssignmentsMergeWithoutWritingUntilMutationAndCollectionsDeduplicate() {
        val real = temporary.newFolder("Real").canonicalFile
        val alias = File(temporary.root, "Alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val a = File(alias, "Note.txt").path
        val b = File(real, "Note.txt").path
        val file = File(temporary.root, "library")
        Properties()
            .apply {
                setProperty("catalog", "2")
                setProperty("definition:Blue", "Blue")
                setProperty("definition:Green", "Green")
                setProperty("item:$a", "Blue")
                setProperty("item:$b", "Green")
                setProperty("collection:c:name", "Reading")
                setProperty("collection:c:color", "Blue")
                setProperty("collection:c:count", "2")
                setProperty("collection:c:item:0", a)
                setProperty("collection:c:item:1", b)
            }
            .also { p -> file.outputStream().use { p.store(it, "legacy aliases") } }
        val bytes = file.readBytes()
        val value = TagStore(file)
        assertNull(value.error)
        assertEquals(mapOf(b to setOf("Blue", "Green")), value.assignments)
        assertEquals(listOf(b), value.collections.single().paths)
        assertArrayEquals(bytes, file.readBytes())
        value.assign(listOf(a, b), "Blue")
        assertEquals(mapOf(b to setOf("Green")), value.assignments)
        assertEquals(value.snapshot(), TagStore(file).snapshot())
    }

    @Test
    fun alternatePathMovesRemapTagsAndChildrenAndCopiesDoNotJoinCollections() {
        val real = temporary.newFolder("Real").canonicalFile
        val alias = File(temporary.root, "Alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val old = File(real, "Before").apply { mkdir() }
        val child = File(old, "Note.txt").apply { writeText("kept") }
        val value = store()
        value.assign(listOf(File(alias, "Before/Note.txt").path), "Blue")
        value.createCollection("Reading", "Blue", listOf(File(alias, "Before").path, child.path))
        val moved = File(real, "After")
        assertTrue(old.renameTo(moved))
        value.relocate(listOf(File(alias, "Before").path to moved.path))
        assertEquals(setOf("Blue"), value.assignments[File(moved, "Note.txt").path])
        assertEquals(
            listOf(moved.path, File(moved, "Note.txt").path),
            value.collections.single().paths,
        )
        value.relocate(listOf(moved.path to File(alias, "Copy").path), copy = true)
        assertEquals(setOf("Blue"), value.assignments[File(real, "Copy/Note.txt").path])
        assertEquals(2, value.collections.single().paths.size)
        assertEquals("kept", File(moved, "Note.txt").readText())
    }

    @Test
    fun repairUpdatesAllExactReferencesMergesTagsAndDeduplicatesWithoutTouchingDescendants() {
        val value = store()
        value.assign(listOf("/old"), "Blue")
        value.assign(listOf("/new"), "Green")
        value.assign(listOf("/old/child"), "Purple")
        value.createCollection("A", "Blue", listOf("/old", "/new", "/old/child"))
        value.createCollection("B", "Green", listOf("/old"))
        val before = value.snapshot()
        val repair = ReferenceRepair(before, "/old", "/new")
        assertEquals(2, repair.affectedCollections.size)
        value.restoreOrganization(before, repair.after)
        assertFalse("/old" in value.assignments)
        assertEquals(setOf("Blue", "Green"), value.assignments["/new"])
        assertEquals(setOf("Purple"), value.assignments["/old/child"])
        assertEquals(listOf("/new", "/old/child"), value.collections.first().paths)
        value.restoreOrganization(repair.after, before)
        assertEquals(before, value.snapshot())
    }

    @Test
    fun providerRepairChangesOnlyReviewedUriAndCanKeepTagsAtOriginal() {
        val old = "content://p/tree/root/document/old-id"
        val target = "content://p/tree/other/document/new-id"
        val unrelated = "content://p/tree/root/document/child-id"
        val value = store()
        value.assign(listOf(old), "Blue")
        value.createCollection("A", "Blue", listOf(old, unrelated))
        val plan = ReferenceRepair(value.snapshot(), old, target, carryTags = false)
        assertEquals(setOf("Blue"), plan.after.tags.assignments[old])
        assertFalse(target in plan.after.tags.assignments)
        assertEquals(listOf(target, unrelated), plan.after.collections.single().paths)
    }

    @Test
    fun staleRepairAndUndoCannotEraseLaterOrganizationEdits() {
        val value = store()
        value.assign(listOf("/old"), "Blue")
        val original = value.snapshot()
        val plan = ReferenceRepair(original, "/old", "/new")
        value.define("Later", "Green")
        assertTrue(runCatching { value.restoreOrganization(original, plan.after) }.isFailure)
        assertTrue(value.definitions.any { it.name == "Later" })
        val fresh = ReferenceRepair(value.snapshot(), "/old", "/new")
        value.restoreOrganization(fresh.before, fresh.after)
        value.createCollection("Later collection", "Blue", listOf("/elsewhere"))
        assertTrue(runCatching { value.restoreOrganization(fresh.after, fresh.before) }.isFailure)
        assertTrue("/new" in value.assignments)
    }

    @Test
    fun sameAndUnsavedLocationsAreRejectedBeforeRepair() {
        val value = store().apply { assign(listOf("/old"), "Blue") }
        assertTrue(runCatching { ReferenceRepair(value.snapshot(), "/old", "/old") }.isFailure)
        assertTrue(runCatching { ReferenceRepair(value.snapshot(), "/missing", "/new") }.isFailure)
        assertTrue(runCatching { ReferenceRepair(value.snapshot(), "/old", "@recent") }.isFailure)
    }

    @Test
    fun renamingAFolderSymlinkMovesOnlyItsOwnMetadata() {
        val real = temporary.newFolder("Real folder").canonicalFile
        val child = File(real, "Child.txt").apply { writeText("kept") }
        val link = File(temporary.root, "Shortcut")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        val value = store()
        value.assign(listOf(link.path), "Blue")
        value.assign(listOf(child.path), "Green")
        value.createCollection("Both", "Blue", listOf(link.path, child.path))
        val renamed = File(temporary.root, "Renamed shortcut")
        assertTrue(link.renameTo(renamed))
        value.relocate(listOf(link.path to renamed.path))
        assertEquals(setOf("Blue"), value.assignments[organizationKey(renamed.path)])
        assertEquals(setOf("Green"), value.assignments[child.path])
        assertEquals(
            listOf(organizationKey(renamed.path), child.path),
            value.collections.single().paths,
        )
        assertEquals("kept", child.readText())
    }

    @Test
    fun writersThroughCatalogParentAliasesShareThePublicationLock() {
        val real = temporary.newFolder("Catalog").canonicalFile
        val alias = File(temporary.root, "Catalog alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val first = TagStore(File(real, "library"))
        val second = TagStore(File(alias, "library"))
        val start = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads =
            (0 until 24).map { index ->
                Thread {
                    try {
                        start.await()
                        (if (index % 2 == 0) first else second).define("Tag $index", "Blue")
                    } catch (e: Throwable) {
                        failures.add(e)
                    }
                }
                    .apply { start() }
            }
        start.countDown()
        threads.forEach { it.join() }
        assertTrue(failures.toString(), failures.isEmpty())
        val result = TagStore(File(real, "library"))
        assertEquals(30, result.definitions.size)
        assertEquals(result.snapshot(), TagStore(File(alias, "library")).snapshot())
    }

    @Test
    fun wholeCatalogRestoreReturnsThePublishedNormalizedSnapshot() {
        val real = temporary.newFolder("Destination").canonicalFile
        val alias = File(temporary.root, "Destination alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val value = store().apply { assign(listOf("/old"), "Blue") }
        val before = value.snapshot()
        val raw = ReferenceRepair(before, "/old", File(alias, "New.txt").path).after
        val published = value.restoreOrganization(before, raw)
        assertEquals(value.reload(), published)
        assertEquals(setOf(File(real, "New.txt").path), published.tags.assignments.keys)
        value.restoreOrganization(published, before)
        assertEquals(before, value.snapshot())
    }
}
