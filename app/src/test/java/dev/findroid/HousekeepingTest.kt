package dev.findroid

import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HousekeepingTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun directory(name: String) = temporary.newFolder(name)

    private fun contents(root: File, path: String, text: String = "abc") =
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    @Test
    fun inventoryCountsLogicalBytesAndNeverFollowsLinks() {
        val root = directory("tree")
        contents(root, "a.txt")
        contents(root, "nested/b.txt", "12345")
        File(root, "empty").mkdir()
        val outside = directory("outside")
        contents(outside, "unrelated.txt", "do not count")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        val stats = inspectDisk(root)
        assertTrue(stats.complete)
        assertEquals(8, stats.bytes)
        assertEquals(2, stats.files)
        assertEquals(3, stats.folders)
        assertEquals(1, stats.links)
        assertEquals(stats.revision, inspectDisk(root).revision)
        val old = File(root, "a.txt").lastModified()
        File(root, "a.txt").setLastModified(old - 5000)
        assertNotEquals(stats.revision, inspectDisk(root).revision)
    }

    @Test
    fun incompleteAndCancelledMeasurementsAreExplicit() {
        val root = directory("bounded")
        repeat(10) { contents(root, "$it.txt") }
        val report = inspectDisk(root, limit = 3)
        assertFalse(report.complete)
        assertTrue(report.files < 10)
        assertNotNull(report.issue)
        assertThrows(CancellationException::class.java) {
            inspectDisk(root, { throw CancellationException() })
        }
    }

    @Test
    fun deepFoldersArePartialAndTotalsNeverOverflow() {
        val root = directory("depth")
        var nested = root
        repeat(130) { nested = File(nested, "d").apply { mkdir() } }
        contents(nested, "deep.txt")
        val report = inspectDisk(root)
        assertFalse(report.complete)
        assertTrue(report.issue!!.contains("128"))
        assertEquals(Long.MAX_VALUE, listOf(Long.MAX_VALUE - 2, 10L).logicalBytes { it })
        assertEquals(5, listOf(-10L, 5L).logicalBytes { it })
    }

    @Test
    fun trashAgePersistsIndependentlyOfSourceModificationAndRestore() {
        val state = directory("state")
        val source = contents(directory("source"), "old.txt").apply { setLastModified(1_000_000) }
        val before = System.currentTimeMillis()
        val engine = FileEngine(state)
        val trashed = engine.trash(source)
        val time = engine.trashedAt(trashed)!!
        assertTrue(time >= before)
        assertEquals(time, FileEngine(state).trashedAt(trashed))
        val item = TrashSpaceItem(trashed, engine.originalPath(trashed), time, inspectDisk(trashed))
        assertFalse(item.expired(30, time + 29 * HOUSEKEEPING_DAY))
        assertTrue(item.expired(30, time + 30 * HOUSEKEEPING_DAY))
        assertFalse(item.expired(0, time + 100 * HOUSEKEEPING_DAY))
        val restored = engine.restore(trashed)
        assertEquals("abc", restored.readText())
        assertTrue(engine.trashItems().isEmpty())
        assertNull(engine.trashedAt(trashed))
    }

    @Test
    fun legacyTrashHasNoInventedAgeAndChangedReviewIsKept() {
        val state = directory("legacy-state")
        val engine = FileEngine(state)
        val trashed = engine.trash(contents(directory("legacy-source"), "keep.txt"))
        val index = File(state, "trash.properties")
        val props = Properties().apply { index.inputStream().use { load(it) } }
        props.remove("${trashed.parentFile!!.name}.trashedAt")
        index.outputStream().use { props.store(it, "legacy") }
        val reloaded = FileEngine(state)
        val item =
            TrashSpaceItem(
                trashed,
                reloaded.originalPath(trashed),
                reloaded.trashedAt(trashed),
                inspectDisk(trashed),
            )
        assertNull(item.trashedAt)
        assertFalse(item.expired(7, System.currentTimeMillis()))
        trashed.appendText("changed")
        assertFalse(reloaded.deleteReviewedTrash(item) {})
        assertTrue(trashed.exists())
        val fresh = item.copy(disk = inspectDisk(trashed))
        assertTrue(reloaded.deleteReviewedTrash(fresh) {})
        assertFalse(trashed.exists())
    }

    @Test
    fun trashBucketLinksCannotExposeOtherFolders() {
        val state = directory("trash-link-state")
        val engine = FileEngine(state)
        val outside = directory("trash-link-outside")
        contents(outside, "keep.txt")
        Files.createSymbolicLink(File(state, "Trash/linked-bucket").toPath(), outside.toPath())
        assertTrue(engine.trashItems().isEmpty())
        assertTrue(File(outside, "keep.txt").exists())
    }

    @Test
    fun cacheInventoryOnlyIncludesKnownUnitsAndExcludesEscapingLinks() {
        val root = directory("cache-inventory")
        contents(root, "documents/key/preview.pdf")
        contents(root, "shared/archive-peek/member/page.png")
        contents(root, "shared/share-id/Bundle.zip")
        contents(root, "shared/text-clips/text.txt")
        contents(root, "shortcuts/job/link.finderlink")
        contents(root, "pdf-scratch/temp.bin")
        contents(root, "unmanaged/keep.bin")
        val outside = directory("cache-outside")
        contents(outside, "private.txt")
        Files.createSymbolicLink(File(root, "documents/escape").toPath(), outside.toPath())
        val (items, issues) = CacheMaintenance(root).inspect()
        assertEquals(6, items.size)
        assertEquals(CacheKind.entries.toSet(), items.map { it.kind }.toSet())
        assertTrue(issues.any { it.contains("symbolic link") })
        assertTrue(items.none { it.file.name in setOf("escape", "unmanaged") })
    }

    @Test
    fun symlinkedCacheAncestorIsNeverTraversedOrCleaned() {
        val root = directory("cache-ancestor")
        val outside = directory("cache-ancestor-outside")
        contents(outside, "key/private.txt")
        Files.createSymbolicLink(File(root, "documents").toPath(), outside.toPath())
        val (items, issues) = CacheMaintenance(root).inspect()
        assertTrue(items.isEmpty())
        assertFalse(issues.isEmpty())
        assertTrue(File(outside, "key/private.txt").exists())
    }

    @Test
    fun cacheExpiryUsesUnitTimeAndShareGraceRatherThanOldSourceTime() {
        val root = directory("cache-expiry")
        contents(root, "shared/share/old.txt").setLastModified(1_000_000)
        val now = System.currentTimeMillis()
        File(root, "shared/share").setLastModified(now - HOUSEKEEPING_DAY / 2)
        val maintenance = CacheMaintenance(root)
        var item = maintenance.inspect().first.single()
        assertFalse(item.eligible(now))
        File(root, "shared/share").setLastModified(now - 8 * HOUSEKEEPING_DAY)
        item = maintenance.inspect().first.single()
        assertTrue(item.eligible(now))
        assertTrue(item.eligible(now, 7))
        assertFalse(item.eligible(now, 30))
    }

    @Test
    fun changedCacheAndActiveReadersAreSkippedIncludingReadersOpenedAfterReview() {
        val root = directory("cache-readers")
        val file = contents(root, "documents/key/page.pdf")
        val maintenance = CacheMaintenance(root)
        val first = maintenance.inspect().first.single()
        CacheLeases.pin(file).use {
            assertTrue(maintenance.inspect().first.single().protected)
            assertFalse(maintenance.remove(first, System.currentTimeMillis()))
        }
        assertFalse(CacheLeases.protected(file))
        file.appendText("changed")
        assertFalse(maintenance.remove(first, System.currentTimeMillis()))
        val fresh = maintenance.inspect().first.single()
        var lease: java.io.Closeable? = null
        try {
            assertFalse(
                maintenance.remove(fresh, System.currentTimeMillis()) {
                    if (lease == null) lease = CacheLeases.pin(file)
                },
            )
            assertTrue(file.exists())
        } finally {
            lease?.close()
        }
        assertTrue(
            maintenance.remove(maintenance.inspect().first.single(), System.currentTimeMillis()),
        )
        assertFalse(file.exists())
    }

    @Test
    fun cacheRemovalCannotTargetOriginalsOrCategoryRoots() {
        val root = directory("cache-guards")
        contents(root, "documents/key/page.pdf")
        val maintenance = CacheMaintenance(root)
        val item = maintenance.inspect().first.single()
        val outside = contents(directory("cache-original"), "original.txt")
        assertThrows(IllegalArgumentException::class.java) {
            maintenance.remove(item.copy(file = outside), System.currentTimeMillis())
        }
        assertThrows(IllegalArgumentException::class.java) {
            maintenance.remove(
                item.copy(file = File(root, "documents")),
                System.currentTimeMillis(),
            )
        }
        assertTrue(outside.exists())
    }

    @Test
    fun stoppedCacheDeletionRemainsDiscoverableForAnotherReview() {
        val root = directory("cache-stop")
        contents(root, "documents/key/page.pdf")
        val maintenance = CacheMaintenance(root)
        val item = maintenance.inspect().first.single()
        var calls = 0
        assertThrows(CancellationException::class.java) {
            maintenance.remove(item, System.currentTimeMillis()) {
                if (++calls == 4) throw CancellationException()
            }
        }
        assertFalse(item.file.exists())
        val remaining = maintenance.inspect().first.single()
        assertTrue(remaining.file.name.startsWith(".cleanup-"))
        assertTrue(maintenance.remove(remaining, System.currentTimeMillis()))
        assertTrue(maintenance.inspect().first.isEmpty())
    }

    @Test
    fun nestedPinsStayProtectedUntilEveryReaderCloses() {
        val root = directory("leases")
        val file = contents(root, "documents/key/page.pdf")
        val one = CacheLeases.pin(file)
        val two = CacheLeases.pin(file)
        one.close()
        one.close()
        assertTrue(CacheLeases.protected(file.parentFile!!))
        two.close()
        assertFalse(CacheLeases.protected(file.parentFile!!))
    }
}
