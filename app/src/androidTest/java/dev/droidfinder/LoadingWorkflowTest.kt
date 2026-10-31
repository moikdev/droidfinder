package dev.droidfinder

import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.droidfinder.testing.FixtureDocumentsProvider as Fixture
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

class LoadingWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var fixture: File
    private lateinit var local: File
    private lateinit var root: String

    @Before
    fun setup() {
        fixture =
            File(compose.activity.filesDir, "ProviderFixtures/Loading-${System.nanoTime()}").apply {
                mkdirs()
            }
        repeat(160) { File(fixture, "Note-%03d.txt".format(it)).writeText("searchable note $it") }
        local =
            File(compose.activity.filesDir, "Workspace/Loading-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(local, "Local.txt").writeText("local")
        Fixture.listingDirectory = fixture.canonicalPath
        Fixture.listingTitle = "Team documents"
        Fixture.listingClosed.set(0)
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            if (model.sort != SortBy.NAME || !model.ascending) model.changeSort(SortBy.NAME)
            model.changeDetailColumns(DetailColumns())
            model.connectLocation(
                DocumentsContract.buildTreeDocumentUri(
                    "dev.droidfinder.fixtures",
                    "root/${fixture.name}",
                ),
                false,
            )
        }
        compose.waitUntil(15_000) {
            model.location.contains(fixture.name) && !model.loading && model.entries.size == 160
        }
        compose.waitForIdle()
        root = model.location
    }

    private fun loaded() {
        compose.waitUntil(15_000) { !model.loading }
        compose.waitForIdle()
    }

    private fun gate() {
        compose.runOnIdle { model.navigate(local.path) }
        loaded()
        Fixture.listingGate = CountDownLatch(1)
        Fixture.listingGateOpened = CountDownLatch(1)
        compose.runOnIdle { model.navigate(root) }
        assertTrue(Fixture.listingGateOpened!!.await(5, TimeUnit.SECONDS))
        compose.waitUntil(5000) { model.loading && model.entries.size == 64 }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    @After
    fun cleanup() {
        Fixture.listingGate?.countDown()
        Fixture.transferGate?.countDown()
        Fixture.transferGate = null
        Fixture.gateOpened = null
        Fixture.streamCancellation = null
        Fixture.listingGate = null
        Fixture.listingGateOpened = null
        Fixture.listingExtras = null
        Fixture.listingFailure = null
        compose.runOnIdle {
            model.listingDetailsRequested = false
            model.stopLoading()
            model.navigate(model.workspace.path)
            model.connectedLocations
                .filter { it.uri.contains(fixture.name) }
                .forEach { model.disconnectLocation(it.uri) }
        }
        loaded()
        Fixture.listingDirectory = null
        Fixture.listingTitle = null
        fixture.deleteRecursively()
        local.deleteRecursively()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // UIAutomator captures SurfaceFlinger, which can trail Compose semantics by a frame.
        android.os.SystemClock.sleep(120)
        val bitmap =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot()
        File(compose.activity.filesDir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun batchesAreUsableAndStopRetainsResultsWithoutLatePublication() {
        gate()
        compose.onNodeWithTag("stop-listing").assertExists()
        capture("loading-progress-native")
        compose.onNode(hasText("Note-000.txt") and hasClickAction()).performClick()
        compose.waitUntil(5000) { model.selected.size == 1 }
        assertEquals(1, model.selected.size)
        compose.onNodeWithTag("stop-listing").performClick()
        assertFalse(model.loading)
        assertTrue(model.listingProgress.stopped)
        assertEquals(64, model.entries.size)
        compose.onNodeWithText("Stopped · 64 results kept").assertExists()
        capture("loading-stopped-native")
        Fixture.listingGate!!.countDown()
        compose.waitUntil(5000) { Fixture.listingClosed.get() >= 2 }
        assertEquals(64, model.entries.size)
        assertEquals(1, model.selected.size)
        compose.onNodeWithTag("retry-listing").performClick()
        loaded()
        assertEquals(160, model.entries.size)
        assertFalse(model.listingProgress.incomplete)
    }

    @Test
    fun navigationAndNewQueryIgnoreAnOlderBlockedScan() {
        gate()
        compose.runOnIdle { model.navigate(local.path) }
        loaded()
        assertEquals(listOf("Local.txt"), model.entries.map { it.name })
        Fixture.listingGate!!.countDown()
        compose.waitUntil(5000) { Fixture.listingClosed.get() >= 2 }
        assertEquals(local.path, model.location)
        assertEquals(listOf("Local.txt"), model.entries.map { it.name })
        compose.runOnIdle { model.navigate(root) }
        loaded()
        compose.runOnIdle { model.updateQuery("Note-159") }
        loaded()
        assertEquals(listOf("Note-159.txt"), model.entries.map { it.name })
    }

    @Test
    fun providerPendingAndOfflineSubfoldersAreVisibleAndCanRecover() {
        Fixture.listingExtras =
            Bundle().apply {
                putBoolean(DocumentsContract.EXTRA_LOADING, true)
                putString(DocumentsContract.EXTRA_INFO, "Showing cached fixture files")
            }
        compose.runOnIdle { model.refresh() }
        loaded()
        assertEquals(160, model.entries.size)
        assertTrue(model.listingProgress.providerPending)
        compose.onNodeWithText("Provider is still loading results").assertExists()
        Fixture.listingExtras = null
        compose.activity.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(
                "dev.droidfinder.fixtures",
                "root/${fixture.name}",
            ),
            null,
        )
        compose.waitUntil(10_000) { !model.loading && !model.listingProgress.providerPending }
        compose.waitForIdle()
        assertFalse(model.listingProgress.incomplete)
        val offline = File(fixture, "Offline").apply { mkdir() }
        File(offline, "Note-hidden.txt").writeText("offline note")
        Fixture.listingFailure = offline.canonicalPath
        compose.runOnIdle { model.updateQuery("Note") }
        loaded()
        assertEquals(160, model.entries.size)
        assertEquals(1, model.listingProgress.issueCount)
        compose.onNodeWithContentDescription("Result details").performClick()
        compose.onNodeWithText(model.listingProgress.issues.single().reason).assertExists()
        capture("loading-partial-native")
        compose.onNodeWithContentDescription("Close Result details").performClick()
        Fixture.listingFailure = null
        compose.onNodeWithTag("retry-listing").performClick()
        loaded()
        assertEquals(161, model.entries.size)
        assertFalse(model.listingProgress.incomplete)
    }

    @Test
    fun fullTextReadsBeyondOneMiBAndReportsUnreadableText() {
        File(fixture, "Long.txt").writeText("x".repeat(2 * 1024 * 1024) + "rare-pattern")
        File(fixture, "Binary.txt").writeBytes(byteArrayOf(0, 1, 2, 3))
        compose.runOnIdle { model.updateSearchRules(SearchRules(content = "rare-pattern")) }
        loaded()
        assertEquals(listOf("Long.txt"), model.entries.map { it.name })
        assertEquals(1, model.listingProgress.issueCount)
        assertTrue(model.listingProgress.issues.single().reason.contains("Binary"))
    }

    @Test
    fun stopClosesABlockedTextStreamAndAnIncompleteEmptySearchIsNotNoMatches() {
        File(fixture, "Gate.txt").writeText("waiting for data")
        Fixture.transferGate = CountDownLatch(1)
        Fixture.gateOpened = CountDownLatch(1)
        Fixture.streamCancellation = CountDownLatch(1)
        val before = Fixture.listingClosed.get()
        compose.runOnIdle { model.updateSearchRules(SearchRules(content = "missing")) }
        assertTrue(Fixture.gateOpened!!.await(5, TimeUnit.SECONDS))
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.runOnIdle { model.stopLoading() }
        assertFalse(model.loading)
        assertTrue(model.listingProgress.stopped)
        compose.waitUntil(5000) { Fixture.listingClosed.get() > before }
        compose.onNodeWithText("Scan stopped").assertExists()
        compose.onNodeWithText("No matching files").assertDoesNotExist()
    }

    @Test
    fun scrollingPartialResultsCancelsDeferredSavedAnchorRestoration() {
        compose.onNodeWithTag("browser-list").performScrollToIndex(120)
        compose.waitForIdle()
        gate()
        compose.onNodeWithTag("browser-list").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(0f, -350f), 600)
        }
        compose.waitForIdle()
        val key = model.viewportKey("list")
        val tab = model.tabs[model.activeTab].id
        val partial = compose.runOnIdle { model.savedViewport(tab, key) }
        assertNotNull(partial?.path)
        assertFalse(partial!!.path!!.contains("Note-120"))
        Fixture.listingGate!!.countDown()
        loaded()
        val after = compose.runOnIdle { model.savedViewport(tab, key) }
        assertEquals(partial.path, after?.path)
    }

    @Test
    fun selectingAPartialResultPreventsALateJumpToAnOlderViewport() {
        compose.onNodeWithTag("browser-list").performScrollToIndex(120)
        compose.waitForIdle()
        gate()
        compose.onNode(hasText("Note-000.txt") and hasClickAction()).performClick()
        compose.waitUntil(5000) { model.focused?.name == "Note-000.txt" }
        Fixture.listingGate!!.countDown()
        loaded()
        compose.onNode(hasText("Note-000.txt") and hasClickAction()).assertIsDisplayed()
        assertEquals("Note-000.txt", model.focused?.name)
    }

    @Test
    fun finishingAScanDoesNotMoveRowsUnderAnActiveNativeDrag() {
        gate()
        compose.onNode(hasText("Note-000.txt") and hasClickAction()).performClick()
        compose.waitUntil(5000) { model.selected.size == 1 }
        val grip =
            compose
                .onNodeWithContentDescription("Hold to drag selected files into a folder")
                .fetchSemanticsNode()
                .boundsInWindow
                .center
        val drag =
            DragWorkflowTest.NativeDrag(
                grip,
                false,
                false,
                { compose.waitForIdle() },
                { compose.mainClock.advanceTimeBy(700) },
            )
        try {
            drag.start()
            compose.onNodeWithTag("drag-feedback").assertExists()
            val before =
                compose.onNodeWithTag("listing-status").fetchSemanticsNode().boundsInWindow.height
            Fixture.listingGate!!.countDown()
            loaded()
            val after =
                compose.onNodeWithTag("listing-status").fetchSemanticsNode().boundsInWindow.height
            assertEquals(before, after, .1f)
            drag.cancel()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("drag-feedback").fetchSemanticsNodes().isEmpty()
            }
            compose.waitForIdle()
            assertFalse(model.loading)
            assertFalse(model.listingProgress.incomplete)
            compose.onNodeWithTag("listing-status").assertDoesNotExist()
        } finally {
            drag.cancel()
        }
    }

    @Test
    fun higherLimitsSurviveHistoryAndSerializedTabRestoration() {
        repeat(20_001) { File(local, "File-${it.toString().padStart(5, '0')}.txt").createNewFile() }
        compose.runOnIdle { model.navigate(local.path) }
        compose.waitUntil(30_000) { !model.loading }
        assertEquals(20_000, model.entries.size)
        assertTrue(model.listingProgress.resultLimit)
        val original = model.entries.map { it.path }.toSet()
        compose.runOnIdle { model.loadMoreResults() }
        compose.waitUntil(30_000) { !model.loading }
        assertEquals(20_002, model.entries.size)
        assertFalse(model.listingProgress.incomplete)
        val extra = model.entries.first { it.path !in original }
        compose.runOnIdle {
            model.select(extra)
            model.flushNavigationState()
            val tab = model.tabs[model.activeTab]
            val parsed =
                NavigationJson.tabs(
                        org.json.JSONArray().put(NavigationJson.tab(tab)),
                        model.workspace.path,
                    )
                    .single()
            assertEquals(tab.navigation.selected, parsed.navigation.selected)
            assertTrue(
                parsed.navigation.budgets.any { (key, value) ->
                    key.location == local.path && value.results == 40_000
                },
            )
            model.navigate(model.workspace.path)
        }
        loaded()
        compose.runOnIdle { assertTrue(model.back()) }
        compose.waitUntil(30_000) { !model.loading }
        assertEquals(local.path, model.location)
        assertEquals(20_002, model.entries.size)
        assertFalse(model.truncated)
    }

    @Test
    fun moreThanFiveThousandLocalEntriesAreAvailable() {
        repeat(6001) { File(local, "File-%05d.txt".format(it)).createNewFile() }
        compose.runOnIdle { model.navigate(local.path) }
        loaded()
        assertEquals(6002, model.entries.size)
        assertFalse(model.truncated)
        assertFalse(model.listingProgress.incomplete)
    }
}
