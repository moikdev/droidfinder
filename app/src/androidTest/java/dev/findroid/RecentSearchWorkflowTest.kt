package dev.findroid

import android.content.Context
import android.graphics.Bitmap
import android.provider.DocumentsContract
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class RecentSearchWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private val history get() = model.recentSearchHistory
    private lateinit var base: File
    private lateinit var source: File
    private lateinit var target: File
    private var providerFile: File? = null
    private var providerRoot: String? = null
    private lateinit var smartIds: Set<String>

    @Before fun setup() {
        loaded()
        smartIds = model.smartFolders.map { it.id }.toSet()
        base = File(model.workspace, "Recent search QA-${System.nanoTime()}").apply { mkdirs() }
        source = File(base, "Writing project").apply { mkdirs() }
        target = File(base, "Research project").apply { mkdirs() }
        for (folder in listOf(source, target)) {
            File(folder, "Report 2026.txt").writeText("Synthetic context around needle in a report.")
            File(folder, "Notes.txt").writeText("Synthetic notes.")
            File(folder, "Cache").mkdir()
        }
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.windowState.peekEntry.value = null
            if (model.previewVisible) model.togglePreview()
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            model.navigate(source.path)
            model.updateSearchRules(SearchRules())
            history.setEnabled(true)
            history.clear()
            model.discardSearchDraft()
        }
        loaded()
    }
    @After fun cleanup() {
        FixtureDocumentsProvider.metadataGate?.countDown()
        FixtureDocumentsProvider.metadataDirectory = null
        FixtureDocumentsProvider.metadataGate = null
        FixtureDocumentsProvider.metadataOpened = null
        FixtureDocumentsProvider.metadataFailure = null
        FixtureDocumentsProvider.metadataDenied = false
        compose.runOnIdle {
            model.discardSearchDraft()
            model.secondaryPane.discardSearchDraft()
            model.smartFolders.filterNot { it.id in smartIds }.forEach { model.removeSmartFolder(it.id) }
            model.navigate(model.workspace.path)
            model.updateSearchRules(SearchRules())
            history.clear(); history.setEnabled(true)
            providerRoot?.let(model.documents::disconnect)
            model.session.connectedLocations.value = model.documents.locations
        }
        loaded()
        base.deleteRecursively(); providerFile?.deleteRecursively()
    }
    private fun loaded() { compose.waitUntil(20_000) { !model.loading && model.busy == null }; compose.waitForIdle() }
    private fun openSearch() {
        if (compose.onAllNodesWithContentDescription("Search files").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("Search").performClick()
    }
    private fun field() = compose.onNodeWithContentDescription("Search files")
    private fun historyClick(tag: String) {
        compose.onNodeWithTag("recent-searches-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
    private fun recent() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Recent searches…").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Recent searches · Search here").assertDoesNotExist()
        compose.onNodeWithText("Recent searches…").performScrollTo().performClick()
        compose.onNodeWithTag("recent-searches").assertIsDisplayed()
    }
    private fun use(item: RecentSearch, here: Boolean): String? {
        val request = compose.runOnIdle { compose.activity.lifecycleScope.async { model.reuseRecentSearch(item, here) } }
        return runBlocking { withTimeout(20_000) { request.await() } }
    }
    private fun remember(root: String = source.path, rules: SearchRules = SearchRules(name = "Report")): RecentSearch = compose.runOnIdle {
        assertTrue(history.remember(root, rules)); history.state.searches.first()
    }
    private fun capture(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5000)
        val folder = File(compose.activity.filesDir, "recent-searches-qa").apply { mkdirs() }
        val bitmap = automation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun provider(): Pair<File, String> {
        val file = File(compose.activity.filesDir, "ProviderFixtures/History-${System.nanoTime()}").apply { mkdirs() }
        File(file, "Report.txt").writeText("Synthetic provider report.")
        val uri = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${file.name}")
        val root = model.documents.connect(uri, persist = false).uri
        providerFile = file; providerRoot = root
        compose.runOnIdle { model.session.connectedLocations.value = model.documents.locations }
        return file to root
    }
    private fun gatedRequest(item: RecentSearch): Deferred<String?> {
        val file = providerFile!!
        FixtureDocumentsProvider.metadataDirectory = file.canonicalPath
        FixtureDocumentsProvider.metadataGate = CountDownLatch(1)
        FixtureDocumentsProvider.metadataOpened = CountDownLatch(1)
        val request = compose.runOnIdle { compose.activity.lifecycleScope.async { model.reuseRecentSearch(item, here = false) } }
        assertTrue("Provider validation did not start", FixtureDocumentsProvider.metadataOpened!!.await(7, TimeUnit.SECONDS))
        return request
    }

    @Test fun typingDoesNotRecordKeystrokesAndImeSubmitStoresOnlyTheFinishedQuery() {
        openSearch()
        field().performTextInput("Rep")
        compose.runOnIdle { assertTrue(history.state.searches.isEmpty()) }
        field().performTextInput("ort")
        compose.runOnIdle { assertTrue(history.state.searches.isEmpty()) }
        field().performImeAction(); loaded()
        compose.runOnIdle {
            assertEquals(listOf("Report"), history.state.searches.map { it.rules.name })
            assertEquals("Report", model.query)
        }
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.runOnIdle { assertEquals(1, history.state.searches.size); assertEquals("", model.query) }
    }
    @Test fun explicitlySubmittingARestoredOrClearedQueryStartsANewHistoryIntent() {
        compose.runOnIdle {
            model.updateQuery("Report")
            val original = model.tabs[model.activeTab].id
            model.newTab(target.path)
            model.switchTab(model.tabs.indexOfFirst { it.id == original })
            history.clear()
            model.flushNavigationState()
            assertTrue(history.state.searches.isEmpty())
        }
        loaded(); openSearch(); field().performImeAction(); loaded()
        compose.runOnIdle {
            assertEquals(listOf("Report"), history.state.searches.map { it.rules.name })
            assertEquals(source.path, history.state.searches.single().root)
            history.clear(); model.flushNavigationState()
            assertTrue(history.state.searches.isEmpty())
        }
        field().performClick(); field().performImeAction(); loaded()
        compose.runOnIdle { assertEquals(listOf("Report"), history.state.searches.map { it.rules.name }) }
    }
    @Test fun leavingAnEditedFieldRecordsOnceAndTheNamedMenuOpensHistory() {
        openSearch(); field().performTextInput("Notes")
        recent()
        compose.onNode(hasText("Notes") and hasAnyAncestor(hasTestTag("recent-searches"))).assertExists()
        compose.runOnIdle { assertEquals(listOf("Notes"), history.state.searches.map { it.rules.name }) }
        compose.onNodeWithText("Close", substring = false).performClick()
        compose.runOnIdle { assertEquals(1, history.state.searches.size) }
    }
    @Test fun focusLeavingTheSearchFieldFinishesItsDraftBeforeAnotherDialog() {
        openSearch(); field().performTextInput("Report"); loaded()
        compose.onNodeWithContentDescription("New folder").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("Report"), history.state.searches.map { it.rules.name }) }
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.runOnIdle { assertEquals(1, history.state.searches.size) }
    }
    @Test fun touchSuggestionReusesFullCriteriaWithoutRecordingTheTypedPrefix() {
        val rules = SearchRules(name = "Report", nameTerms = listOf("2026"), kind = "Text document", content = "needle",
            includeSubfolders = false, excludedFolderNames = setOf("Cache"), excludedLocations = listOf(SearchExclusion(File(source, "Private").path)))
        val item = remember(rules = rules)
        openSearch(); field().performTextInput("Rep")
        compose.onNodeWithTag("recent-search-suggestion-${item.id}").assertIsDisplayed()
        capture("suggestions")
        compose.onNodeWithTag("recent-search-suggestion-${item.id}").performClick(); loaded()
        compose.runOnIdle {
            assertEquals(rules, model.effectiveSearchRules)
            assertEquals(listOf("Report"), history.state.searches.map { it.rules.name })
            assertEquals(source.path, model.location)
        }
    }
    @Test fun arrowKeysAndEnterChooseTheHighlightedSuggestionWithoutPrefixPollution() {
        val older = remember(rules = SearchRules(name = "Report 2026", kind = "Text document"))
        remember(rules = SearchRules(name = "Report", includeSubfolders = false))
        openSearch(); field().performTextInput("Rep")
        field().performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown); pressKey(Key.Enter) }
        loaded()
        compose.runOnIdle {
            assertEquals(older.rules, model.effectiveSearchRules)
            assertEquals(2, history.state.searches.size)
            assertFalse(history.state.searches.any { it.rules.name == "Rep" })
        }
    }
    @Test fun enterWithoutAnArrowSelectionFinishesTheLiteralQuery() {
        remember(); openSearch(); field().performTextInput("Rep")
        field().performKeyInput { pressKey(Key.Enter) }; loaded()
        compose.runOnIdle {
            assertEquals("Rep", model.query)
            assertEquals(listOf("Rep", "Report"), history.state.searches.map { it.rules.name })
        }
    }
    @Test fun searchHereKeepsExactExclusionsAndOriginalLocationRestoresTheRoot() {
        val rules = SearchRules(name = "Report", includeSubfolders = false, excludedFolderNames = setOf("Cache", "Private"),
            excludedLocations = listOf(SearchExclusion(File(source, "Report 2026.txt").path, false)), date = SearchDate.TODAY,
            size = SearchSize.UNDER_1_MB, minimumBytes = 17)
        val item = remember(rules = rules)
        compose.runOnIdle { model.navigate(target.path) }; loaded(); recent()
        historyClick("recent-search-details-${item.id}")
        compose.onNodeWithText("Excluded folder names: Cache, Private").assertExists()
        capture("full-filters")
        historyClick("recent-search-here-${item.id}"); loaded()
        compose.runOnIdle { assertEquals(target.path, model.location); assertEquals(rules, model.effectiveSearchRules) }
        assertNull(use(item, here = false)); loaded()
        compose.runOnIdle { assertEquals(source.path, model.location); assertEquals(rules, model.effectiveSearchRules) }
    }
    @Test fun smartFolderReuseCreatesAnIndependentPlainSearchAtItsRealRoot() {
        val intrinsic = SearchRules(name = "Notes", tags = setOf("Research"))
        compose.runOnIdle { model.updateSearchRules(intrinsic); model.saveSmartFolder("Synthetic saved rules") }; loaded()
        val smart = model.currentSmartFolder!!
        val item = remember(root = target.path, rules = SearchRules(name = "Report", includeSubfolders = false))
        assertNull(use(item, here = true)); loaded()
        compose.runOnIdle {
            assertEquals(source.path, model.location); assertNull(model.currentSmartFolder)
            assertEquals(item.rules, model.effectiveSearchRules)
            assertEquals(intrinsic, model.smartFolders.single { it.id == smart.id }.rules)
            model.updateQuery("Changed")
            assertEquals(intrinsic, model.smartFolders.single { it.id == smart.id }.rules)
        }
    }
    @Test fun unavailableOriginalDoesNotChangeTheTabAndOffersSearchHere() {
        val missing = File(base, "Disconnected original")
        val item = remember(missing.path)
        val before = compose.runOnIdle { Triple(model.location, model.effectiveSearchRules, model.tabs[model.activeTab].id) }
        recent()
        historyClick("recent-search-original-${item.id}")
        compose.onNodeWithTag("recent-search-error").assertTextContains("original location isn't available", substring = true)
        compose.onNodeWithTag("recent-search-storage").assertExists()
        compose.runOnIdle { assertEquals(before, Triple(model.location, model.effectiveSearchRules, model.tabs[model.activeTab].id)) }
        capture("unavailable-original")
        historyClick("recent-search-here-${item.id}"); loaded()
        compose.runOnIdle { assertEquals(source.path, model.location); assertEquals("Report", model.query) }
    }
    @Test fun clearAndPausePreventStaleDraftsInAnotherPaneFromResurrectingHistory() {
        compose.runOnIdle {
            val other = model.secondaryPane
            other.navigate(target.path); other.updateSearchRules(SearchRules())
            other.updateQuery("Old other-pane draft")
            model.updateQuery("Old primary draft")
            assertTrue(history.clear()); model.flushNavigationState(); other.flushNavigationState()
            assertTrue(history.state.searches.isEmpty())
            other.updateQuery("Draft before pause"); assertTrue(history.setEnabled(false))
            model.updateQuery("Draft while paused"); assertTrue(history.setEnabled(true))
            model.rememberCurrentSearch(); other.rememberCurrentSearch()
            assertTrue(history.state.searches.isEmpty())
            other.updateQuery("New deliberate search"); other.rememberCurrentSearch()
            assertEquals(listOf("New deliberate search"), history.state.searches.map { it.rules.name })
        }
    }
    @Test fun historyIsSharedAcrossWindowsAndPersistsCriteriaWithoutContentEvidence() {
        val app = compose.activity.application as FinderApplication
        val windowId = "history-test-${System.nanoTime()}"
        try {
            val rules = SearchRules(name = "Report", content = "needle", minimumBytes = 17, date = SearchDate.TODAY)
            compose.runOnIdle {
                model.updateSearchRules(rules)
                val window = app.windowModel(windowId, target.path)
                assertSame(history, window.recentSearchHistory)
                window.updateQuery("Notes"); window.rememberCurrentSearch()
            }
            loaded()
            val prefs = compose.activity.getSharedPreferences("finder", Context.MODE_PRIVATE)
            val reloaded = RecentSearchHistory(PreferencesRecentSearchPersistence(prefs))
            assertEquals(history.state.searches, reloaded.state.searches)
            assertTrue(reloaded.state.searches.any { it.rules == rules })
            val raw = prefs.getString(RECENT_SEARCH_KEY, "")!!
            assertFalse(raw.contains("Synthetic context")); assertFalse(raw.contains("snippet")); assertFalse(raw.contains("entries"))
        } finally { compose.runOnIdle { app.releaseWindow(windowId) } }
    }
    @Test fun navigationAndActivityStopFinishDraftsWithoutRecordingRestoredQueriesAgain() {
        compose.runOnIdle { model.updateQuery("Report"); model.navigate(target.path) }; loaded()
        compose.runOnIdle {
            assertEquals(source.path, history.state.searches.single().root)
            model.updateQuery("Notes")
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        loaded()
        compose.runOnIdle {
            assertEquals(listOf("Notes", "Report"), history.state.searches.map { it.rules.name })
            assertTrue(model.back())
        }
        loaded()
        compose.runOnIdle { model.rememberCurrentSearch(); assertEquals(2, history.state.searches.size) }
    }
    @Test fun lateProviderValidationCannotOverwriteANewerQueryOrBlockTheUi() {
        val (_, root) = provider(); val item = remember(root)
        val request = gatedRequest(item)
        compose.runOnIdle { model.updateQuery("Newer query") }
        FixtureDocumentsProvider.metadataGate!!.countDown()
        val result = runBlocking { withTimeout(10_000) { request.await() } }
        assertTrue(result!!.contains("changed"))
        compose.runOnIdle { assertEquals(source.path, model.location); assertEquals("Newer query", model.query) }
    }
    @Test fun clearingHistoryWhileProviderValidationRunsCancelsTheReuse() {
        val (_, root) = provider(); val item = remember(root)
        val request = gatedRequest(item)
        compose.runOnIdle { history.clear() }
        FixtureDocumentsProvider.metadataGate!!.countDown()
        val result = runBlocking { withTimeout(10_000) { request.await() } }
        assertTrue(result!!.contains("Recent searches changed"))
        compose.runOnIdle { assertEquals(source.path, model.location); assertTrue(history.state.searches.isEmpty()) }
    }
    @Test fun changingActivePaneWhileProviderValidationRunsPreventsLateNavigation() {
        val (_, root) = provider(); val item = remember(root)
        val request = gatedRequest(item)
        compose.runOnIdle {
            model.toggleDualPane()
            model.secondaryPane.navigate(target.path)
            model.secondaryPane.activatePane()
        }
        FixtureDocumentsProvider.metadataGate!!.countDown()
        val result = runBlocking { withTimeout(10_000) { request.await() } }
        assertTrue(result!!.contains("changed"))
        compose.runOnIdle { assertEquals(source.path, model.location); assertEquals(target.path, model.secondaryPane.location) }
    }
    @Test fun cancelledProviderValidationLeavesCurrentLocationAndRulesIntact() {
        val (_, root) = provider(); val item = remember(root)
        val request = gatedRequest(item)
        request.cancel()
        FixtureDocumentsProvider.metadataGate!!.countDown()
        runBlocking { withTimeout(10_000) { request.join() } }
        compose.runOnIdle { assertEquals(source.path, model.location); assertEquals(SearchRules(), model.effectiveSearchRules) }
    }
    @Test fun historyDialogSupportsFilteringPausingRemovingAndClearing() {
        val report = remember(); remember(rules = SearchRules(name = "Notes")); recent()
        compose.onNodeWithTag("recent-search-filter").performTextInput("Report")
        compose.onNodeWithTag("recent-search-${report.id}").assertExists()
        compose.onNode(hasText("Notes") and hasAnyAncestor(hasTestTag("recent-searches"))).assertDoesNotExist()
        historyClick("remember-recent-searches")
        compose.runOnIdle { assertFalse(history.state.enabled) }
        historyClick("recent-search-remove-${report.id}")
        compose.runOnIdle { assertFalse(history.state.searches.any { it.id == report.id }) }
        compose.onNodeWithTag("clear-recent-searches").performClick()
        compose.runOnIdle { assertTrue(history.state.searches.isEmpty()); assertFalse(history.state.enabled) }
        compose.onNodeWithText("Close", substring = false).assertIsDisplayed()
        capture("empty-paused")
    }
}
