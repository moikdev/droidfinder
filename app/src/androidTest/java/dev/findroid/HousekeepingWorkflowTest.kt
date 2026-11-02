package dev.findroid

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.*
import org.junit.Assert.*

class HousekeepingWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var folder: File
    private lateinit var cached: File
    private val trash = mutableListOf<File>()
    private var lease: java.io.Closeable? = null
    private var previousTrashDays = 30
    private var previousCacheDays = 7

    @Before
    fun setup() {
        val id = System.nanoTime()
        folder = File(compose.activity.filesDir, "Workspace/Housekeeping-$id").apply { mkdirs() }
        cached =
            File(compose.activity.cacheDir, "documents/housekeeping-$id/Preview.txt").apply {
                parentFile!!.mkdirs()
                writeText("A cached preview for housekeeping tests.")
            }
        compose.runOnIdle {
            previousTrashDays = model.housekeeping.trashDays
            previousCacheDays = model.housekeeping.cacheDays
            model.windowState.peekEntry.value = null
            model.housekeeping.close()
            model.housekeeping.changeView(HousekeepingView.TRASH)
        }
    }

    private fun trashed(name: String, content: String = "abc"): File {
        val file =
            File(folder, name).apply {
                writeText(content)
                setLastModified(1_000_000)
            }
        return model.engine.trash(file).also(trash::add)
    }

    private fun opened() {
        compose.runOnIdle { model.housekeeping.open() }
        scanned()
    }

    private fun scanned() {
        compose.waitUntil(15_000) {
            !model.housekeeping.running && model.housekeeping.report != null
        }
        compose.waitForIdle()
    }

    private fun completed() {
        compose.waitUntil(15_000) { model.busy == null && !model.housekeeping.running }
        compose.waitForIdle()
    }

    @After
    fun cleanup() {
        lease?.close()
        compose.runOnIdle { model.windowState.peekEntry.value = null }
        compose.waitUntil(15_000) { model.busy == null }
        compose.runOnIdle {
            model.housekeeping.close()
            model.housekeeping.changeTrashDays(previousTrashDays)
            model.housekeeping.changeCacheDays(previousCacheDays)
        }
        trash.filter { it.exists() }.forEach(model.engine::deleteForever)
        folder.deleteRecursively()
        cached.parentFile!!.deleteRecursively()
    }

    @Test
    fun trashUsesDeletionDateAndRequiresReviewBeforePermanentRemoval() {
        val removed = trashed("Reviewed housekeeping.txt")
        trashed("Newer housekeeping.txt")
        opened()
        val item = model.housekeeping.report!!.trash.first { it.file == removed }
        assertNotNull(item.trashedAt)
        assertFalse(item.expired(7, System.currentTimeMillis()))
        compose.runOnIdle {
            model.housekeeping.changeTrashDays(7)
            model.housekeeping.selectTrash(true)
        }
        assertFalse(removed.path in model.housekeeping.selected)
        compose.runOnIdle { model.housekeeping.toggle(removed.path) }
        compose.onNodeWithTag("housekeeping-list").performScrollToIndex(3)
        compose.onNodeWithText("Review cleanup").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(removed.exists())
        compose.onNodeWithText("Review cleanup").performClick()
        compose.onNodeWithText("Delete permanently").performClick()
        completed()
        assertFalse(removed.exists())
        assertTrue(model.housekeeping.outcome!!.startsWith("1 removed"))
        compose.onNode(
            hasText("1 removed · 0 skipped") and hasAnyAncestor(hasTestTag("housekeeping")),
        ).assertIsDisplayed()
    }

    @Test
    fun changedTrashIsSkippedAfterConfirmation() {
        val removed = trashed("Changed housekeeping.txt")
        opened()
        compose.runOnIdle { model.housekeeping.toggle(removed.path) }
        compose.onNodeWithText("Review cleanup").performClick()
        removed.appendText("Changed after review")
        compose.onNodeWithText("Delete permanently").performClick()
        completed()
        assertTrue(removed.exists())
        assertTrue(model.housekeeping.outcome!!.contains("1 skipped"))
        assertTrue(removed.readText().contains("Changed after review"))
    }

    @Test
    fun reviewedCacheCleanupKeepsOriginalsAndClearsOnlySelectedUnit() {
        val original = File(folder, "Original.txt").apply { writeText("Keep original") }
        opened()
        compose.onNodeWithText("Cache", useUnmergedTree = true).performClick()
        compose.runOnIdle { model.housekeeping.toggle(cached.parentFile!!.path) }
        compose.onNodeWithText("Review cleanup").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(cached.exists())
        compose.onNodeWithText("Review cleanup").performClick()
        compose.onNodeWithText("Clear cache").performClick()
        completed()
        assertFalse(cached.exists())
        assertEquals("Keep original", original.readText())
        assertTrue(model.housekeeping.outcome!!.startsWith("1 removed"))
    }

    @Test
    fun readerOpenedAfterReviewProtectsCacheAtDeletionTime() {
        opened()
        compose.onNodeWithText("Cache", useUnmergedTree = true).performClick()
        compose.runOnIdle { model.housekeeping.toggle(cached.parentFile!!.path) }
        compose.onNodeWithText("Review cleanup").performClick()
        lease = CacheLeases.pin(cached)
        compose.onNodeWithText("Clear cache").performClick()
        completed()
        assertTrue(cached.exists())
        assertTrue(model.housekeeping.outcome!!.contains("1 skipped"))
    }

    @Test
    fun cacheAgeAndCategoryControlsSelectEligibleUnitsTogether() {
        cached.parentFile!!.setLastModified(System.currentTimeMillis() - 8 * HOUSEKEEPING_DAY)
        opened()
        compose.onNodeWithText("Cache", useUnmergedTree = true).performClick()
        compose.runOnIdle { model.housekeeping.changeCacheDays(7) }
        compose.onNodeWithText("Select expired").performClick()
        assertTrue(cached.parentFile!!.path in model.housekeeping.selected)
        compose.runOnIdle { model.housekeeping.clearSelection() }
        val eligible =
            model.housekeeping.report!!
                .cache
                .filter {
                    it.kind == CacheKind.DOCUMENTS && it.eligible(System.currentTimeMillis())
                }
                .map { it.file.path }
                .toSet()
        compose.onNodeWithContentDescription("Select Provider previews").performClick()
        assertTrue(model.housekeeping.selected.containsAll(eligible))
        compose.onNodeWithContentDescription("Select Provider previews").performClick()
        assertTrue(model.housekeeping.selected.intersect(eligible).isEmpty())
    }

    @Test
    fun actualPeekProtectsCacheAndReleasesItWhenClosed() {
        compose.runOnIdle {
            model.windowState.peekCandidates.value = listOf(Entry(cached))
            model.windowState.peekEntry.value = Entry(cached)
        }
        compose.waitUntil(10_000) { CacheLeases.protected(cached.parentFile!!) }
        opened()
        assertTrue(
            model.housekeeping.report!!.cache.first { it.file == cached.parentFile }.protected,
        )
        compose.runOnIdle {
            model.housekeeping.close()
            model.windowState.peekEntry.value = null
        }
        compose.waitUntil(10_000) { !CacheLeases.protected(cached.parentFile!!) }
        opened()
        assertFalse(
            model.housekeeping.report!!.cache.first { it.file == cached.parentFile }.protected,
        )
    }

    @Test
    fun settingsAndSelectionSurviveActivityRecreationAndTrashNavigationWorks() {
        val removed = trashed("Retained housekeeping.txt")
        opened()
        compose.runOnIdle {
            model.housekeeping.changeTrashDays(90)
            model.housekeeping.changeCacheDays(30)
            model.housekeeping.toggle(removed.path)
        }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertTrue(model.housekeeping.visible)
        assertEquals(90, model.housekeeping.trashDays)
        assertEquals(30, model.housekeeping.cacheDays)
        assertTrue(removed.path in model.housekeeping.selected)
        val persisted =
            HousekeepingController(
                compose.activity.application,
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
                model.engine,
            )
        assertEquals(90, persisted.trashDays)
        assertEquals(30, persisted.cacheDays)
        compose.onNodeWithText("Open Trash").performClick()
        compose.waitUntil(10_000) { model.location == "@trash" && !model.loading }
        assertFalse(model.housekeeping.visible)
    }
}
