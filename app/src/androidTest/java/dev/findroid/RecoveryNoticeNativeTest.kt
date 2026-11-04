package dev.findroid

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real Android descriptor identities, non-following opens and synchronized durable marker writes. */
class RecoveryNoticeNativeTest {
    private lateinit var root: File
    private lateinit var marker: File
    private lateinit var notices: File
    private lateinit var store: RecoveryNoticeStore
    private val fs = AndroidPartialCopyStorage()
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "recovery-notice-native-" + UUID.randomUUID()).apply { check(mkdir()) }
        marker = File(root, "active-transfer.txt"); notices = File(root, "Notices"); store = RecoveryNoticeStore(notices)
    }
    @After fun cleanup() { if (::root.isInitialized) check(root.deleteRecursively()) }
    private fun begin() = store.begin(marker, "Copy synthetic project", "/recorded/雪\nsecond line", 123L)
    private fun refuses(work: () -> Unit) { try { work(); fail("Expected protected refusal") } catch (_: Exception) { } }
    @Test fun durableFailedMarkerAndArchiveSurviveFreshStoreConstruction() {
        val token = begin(); val bytes = marker.readBytes(); store.finish(marker, token, false)
        assertArrayEquals(bytes, marker.readBytes()); assertArrayEquals(bytes, File(notices, token.notice.id + ".txt").readBytes())
        assertEquals(listOf(token.notice), RecoveryNoticeStore(notices).load().records)
        assertEquals("/recorded/雪\nsecond line", token.notice.destination)
    }
    @Test fun acknowledgmentRetainsTheExactNativeMarkerIdentity() {
        marker.writeText("Move synthetic\n/destination\n1"); val notice = store.current(marker)!!; val stamp = fs.stat(marker.path)
        assertEquals(notice, store.acknowledge(marker, notice.id)); assertEquals(stamp, fs.stat(marker.path))
        assertEquals(listOf(notice), RecoveryNoticeStore(notices).load().records)
    }
    @Test fun nextOperationPreservesEarlierBytesBeforeReplacingItsMarker() {
        marker.writeText("Move synthetic\n/first\n1"); val old = store.current(marker)!!; val bytes = marker.readBytes()
        val next = begin(); store.finish(marker, next, true)
        assertFalse(marker.exists()); assertArrayEquals(bytes, File(notices, old.id + ".txt").readBytes())
    }
    @Test fun changedMarkerRefusesCompletionAndAStaleAcknowledgment() {
        val token = begin(); marker.writeText("Different synthetic operation\n/other\n9")
        refuses { store.finish(marker, token, true) }; refuses { store.acknowledge(marker, token.notice.id) }
        assertEquals("Different synthetic operation", store.current(marker)!!.label)
    }
    @Test fun identicalReplacementInodeIsKeptForReview() {
        val token = begin(); val bytes = marker.readBytes(); Os.rename(marker.path, File(root, "Held").path); marker.writeBytes(bytes)
        refuses { store.finish(marker, token, true) }; assertArrayEquals(bytes, marker.readBytes())
    }
    @Test fun finalSymlinkCannotReadOrChangeItsTarget() {
        val target = File(root, "Original").apply { writeText("keep original") }; Os.symlink(target.path, marker.path)
        refuses { store.current(marker) }; refuses { store.preserve(marker) }; refuses { begin() }
        assertEquals("keep original", target.readText()); assertFalse(notices.exists())
    }
    @Test fun redirectedArchiveDirectoryIsRefusedWithoutWritingOutsideIt() {
        val target = File(root, "Original folder").apply { mkdir() }; Os.symlink(target.path, notices.path)
        marker.writeText("Copy synthetic\n/target\n1"); val bytes = marker.readBytes()
        refuses { begin() }; assertArrayEquals(bytes, marker.readBytes()); assertTrue(target.listFiles()!!.isEmpty())
    }
    @Test fun fifoMarkerIsRefusedWithoutBlockingOrOpeningIt() {
        Os.mkfifo(marker.path, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        val started = System.nanoTime(); refuses { store.current(marker) }; refuses { begin() }
        assertTrue((System.nanoTime() - started) < 2_000_000_000L); assertTrue(OsConstants.S_ISFIFO(Os.lstat(marker.path).st_mode))
    }
    @Test fun hardLinksAreRefusedByThePlatformOrByTheNoticeReader() {
        val target = File(root, "Original").apply { writeText("keep original") }
        try { Os.link(target.path, marker.path) }
        catch (error: ErrnoException) {
            assertTrue(error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM)
            assertFalse(marker.exists()); assertNull(store.current(marker)); assertEquals("keep original", target.readText())
            return // Platform capability evidence; actual nlink>1 reader refusal is covered on the JVM.
        }
        refuses { begin() }; assertEquals(2L, Os.lstat(marker.path).st_nlink); assertEquals("keep original", target.readText())
    }
    @Test fun anUnreadableArchiveDoesNotHideASeparateValidRecord() {
        val token = begin(); store.finish(marker, token, false)
        val damaged = File(notices, "Keep.txt").apply { writeText("keep malformed bytes") }
        val result = RecoveryNoticeStore(notices).load()
        assertEquals(listOf(token.notice), result.records); assertTrue(result.problems.isNotEmpty()); assertEquals("keep malformed bytes", damaged.readText())
    }
    @Test fun openingAndListingExistingRecordsDoNotRewriteThem() {
        val token = begin(); store.finish(marker, token, false)
        val archived = File(notices, token.notice.id + ".txt"); val before = fs.stat(archived.path)
        repeat(3) { assertEquals(listOf(token.notice), store.load().records) }
        assertEquals(before, fs.stat(archived.path))
    }
    @Test fun liveMarkerIsOnlyAnInterruptedNoticeInAFreshStore() {
        val token = begin(); assertNull(store.currentForReview(marker))
        assertEquals(token.notice, RecoveryNoticeStore(notices).currentForReview(marker))
        store.finish(marker, token, true); assertFalse(marker.exists())
    }
}
