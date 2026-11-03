package dev.findroid

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.io.RandomAccessFile
import org.junit.*
import org.junit.Assert.*

class SearchExperienceWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var folder: File
    private var providerFolder: File? = null
    private var providerRoot: String? = null
    private lateinit var originalSmartIds: Set<String>
    private val bytes = "Synthetic context KEEP_CONTEXT_719 around needle in a report."

    @Before fun setup() {
        compose.waitUntil(15_000) { !model.loading && model.busy == null }
        originalSmartIds = model.smartFolders.map { it.id }.toSet()
        folder = File(model.workspace, "Search experience QA-${System.nanoTime()}").canonicalFile.apply { mkdirs() }
        listOf("Report needle.txt", "Cache/Report hidden.txt", "Work/Cache/Report retained.txt", "Work/Other.txt").forEach {
            File(folder, it).apply { parentFile!!.mkdirs(); writeText(bytes) }
        }
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.windowState.peekEntry.value = null
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            if (model.previewVisible) model.togglePreview()
            model.navigate(folder.path)
            model.updateSearchRules(SearchRules())
        }
        loaded()
    }

    private fun loaded() { compose.waitUntil(20_000) { !model.loading }; compose.waitForIdle() }
    private fun openFilters() {
        if (compose.onAllNodesWithContentDescription("Search filters").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Search filters").performClick()
        compose.onNodeWithText("Search criteria").assertIsDisplayed()
    }
    private fun criterion(tag: String): SemanticsNodeInteraction {
        val target = compose.onNodeWithTag(tag)
        val form = compose.onNodeWithTag("search-criteria-scroll")
        repeat(12) {
            // A chip's closest scroll ancestor is its horizontal row. Reach that
            // choice horizontally, then move the outer form until its full bounds fit.
            target.performScrollTo()
            val scale = compose.activity.resources.displayMetrics.density
            val viewport = form.fetchSemanticsNode().boundsInRoot
            val bounds = target.getUnclippedBoundsInRoot()
            val top = bounds.top.value * scale
            val bottom = bounds.bottom.value * scale
            if (top >= viewport.top && bottom <= viewport.bottom) {
                return target.assertIsDisplayed()
            }
            val delta = if (top < viewport.top) top - viewport.top - 8f * scale
                else bottom - viewport.bottom + 8f * scale
            form.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            compose.waitForIdle()
        }
        error("The complete search control is unreachable: $tag")
    }

    private fun hideKeyboard() {
        compose.runOnIdle {
            val input = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            WindowInspector.getGlobalWindowViews().forEach { input.hideSoftInputFromWindow(it.windowToken, 0) }
        }
        compose.waitUntil(5000) {
            var hidden = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                hidden = WindowInspector.getGlobalWindowViews().none {
                    ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
            }
            hidden
        }
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
    }
    private fun capture(name: String) {
        hideKeyboard()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5000)
        val directory = File(compose.activity.filesDir, "search-experience-qa").apply { mkdirs() }
        val bitmap = automation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun provider(): String {
        val base = File(compose.activity.filesDir, "ProviderFixtures").apply { mkdirs() }
        val chosen = File(base, "Search-${System.nanoTime()}").apply { mkdir() }
        providerFolder = chosen
        listOf("Report needle.txt", "Cache/Report hidden.txt", "Work/Cache/Report retained.txt").forEach {
            File(chosen, it).apply { parentFile!!.mkdirs(); writeText(bytes) }
        }
        val uri = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${chosen.name}")
        val root = model.documents.connect(uri, persist = false).uri
        providerRoot = root
        compose.runOnIdle {
            model.session.connectedLocations.value = model.documents.locations
            model.navigate(root)
        }
        loaded()
        return root
    }

    @After fun cleanup() {
        FixtureDocumentsProvider.ancestryUnsupported = false
        FixtureDocumentsProvider.contentReads.clear()
        FixtureDocumentsProvider.ancestryChecks.set(0)
        FixtureDocumentsProvider.ancestryCalls.clear()
        compose.runOnIdle {
            model.listingDetailsRequested = false
            model.setup.close()
            model.smartFolders.filterNot { it.id in originalSmartIds }.forEach { model.removeSmartFolder(it.id) }
            model.navigate(model.workspace.path)
            model.updateSearchRules(SearchRules())
            providerRoot?.let(model.documents::disconnect)
            model.session.connectedLocations.value = model.documents.locations
        }
        loaded()
        folder.deleteRecursively()
        providerFolder?.deleteRecursively()
    }

    @Test fun literalMatchReasonsAndHighlightsAppearInAllFourViewsWithoutPersistingSnippets() {
        compose.runOnIdle { model.updateSearchRules(SearchRules(name = "Report", content = "needle")) }
        loaded()
        val path = File(folder, "Report needle.txt").path
        for (mode in ViewMode.entries) {
            compose.runOnIdle { model.changeMode(mode) }
            loaded()
            capture("matches-${mode.name.lowercase()}")
            compose.onAllNodesWithTag("search-evidence-$path", useUnmergedTree = true).onFirst().assertIsDisplayed()
            val nodes = compose.onAllNodes(hasText("Report needle.txt", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("Highlighted original filename in $mode", nodes.any { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.spanStyles.isNotEmpty() } })
        }
        val preferences = compose.activity.getSharedPreferences("finder", 0).getString("tabs", "")!!
        assertFalse(preferences.contains("KEEP_CONTEXT_719"))
        val setup = SetupStorage(compose.activity.application).snapshot(model)
        val search = setup.openTabs!!.primary.tabs.single { it.id == model.tabs[model.activeTab].id }.rules
        assertEquals("needle", search.content)
        assertFalse(NavigationJson.rules(search).toString().contains("KEEP_CONTEXT_719"))
    }

    @Test fun exactSelectionExclusionIsADraftThenPersistsAndCanBeRemoved() {
        val cache = File(folder, "Cache")
        compose.runOnIdle { model.select(model.entries.single { it.path == cache.path }) }
        openFilters()
        compose.onNodeWithTag("search-exclude-selection").performScrollTo().performClick()
        assertTrue(model.effectiveSearchRules.excludedLocations.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        openFilters()
        compose.onNodeWithTag("search-exclude-selection").performScrollTo().performClick()
        capture("exact-exclusion-criteria")
        compose.onNodeWithText("Apply filters").performClick()
        loaded()
        assertEquals(listOf(SearchExclusion(cache.path)), model.effectiveSearchRules.excludedLocations)
        assertTrue(model.entries.any { it.path == File(folder, "Work/Cache/Report retained.txt").path })
        assertFalse(model.entries.any { pathWithin(it.path, cache.path) })
        compose.activityRule.scenario.recreate(); loaded()
        assertEquals(listOf(SearchExclusion(cache.path)), model.effectiveSearchRules.excludedLocations)
        openFilters()
        compose.onNodeWithTag("search-remove-exclusion-${cache.path}").performScrollTo().performClick()
        compose.onNodeWithText("Apply filters").performClick(); loaded()
        assertEquals(SearchRules(), model.effectiveSearchRules)
        assertTrue(model.entries.any { it.path == cache.path })
    }

    @Test fun actualFolderPickerAddsAnExactRootExclusionWithoutChangingNameExclusions() {
        openFilters()
        compose.onNodeWithTag("search-exclude-folder").performScrollTo().performClick()
        compose.onNodeWithTag("destination-picker").assertIsDisplayed()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Exclude this folder").fetchSemanticsNodes().singleOrNull()?.let { !it.config.contains(SemanticsProperties.Disabled) } == true }
        compose.onNodeWithText("Exclude this folder").performClick()
        compose.onNodeWithText("Apply filters").performClick(); loaded()
        assertTrue(model.entries.isEmpty())
        assertFalse(model.listingProgress.incomplete)
        assertEquals(listOf(SearchExclusion(folder.path)), model.effectiveSearchRules.excludedLocations)
        assertTrue(model.effectiveSearchRules.excludedFolderNames.isEmpty())
    }

    @Test fun naturalChoicesAndExpertByteValuesUseTheActualEditorAndSurviveRestart() {
        compose.runOnIdle { model.updateSearchRules(SearchRules(minimumBytes = 1025, days = 1)) }; loaded()
        openFilters()
        compose.onNodeWithTag("search-minimum-kb").performScrollTo().assertTextContains("1.0009765625")
        compose.onNodeWithTag("search-minimum-kb").performTextReplacement("nonsense")
        compose.onNodeWithText("Apply filters").assertIsNotEnabled()
        hideKeyboard()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1025L, model.effectiveSearchRules.minimumBytes)
        assertEquals(SearchDate.ANY, model.effectiveSearchRules.date)
        openFilters()
        hideKeyboard()
        criterion("search-date-THIS_WEEK").performClick().assertIsSelected()
        criterion("search-kind-Image").performClick().assertIsSelected()
        criterion("search-size-OVER_1_GB").performClick().assertIsSelected()
        capture("natural-criteria")
        compose.onNodeWithText("Apply filters").performClick(); loaded()
        assertEquals(SearchDate.THIS_WEEK, model.effectiveSearchRules.date)
        assertEquals(0, model.effectiveSearchRules.days)
        assertEquals(SearchSize.OVER_1_GB, model.effectiveSearchRules.size)
        assertEquals(0L, model.effectiveSearchRules.minimumBytes)
        assertEquals("Image", model.effectiveSearchRules.kind)
        compose.activityRule.scenario.recreate(); loaded()
        assertEquals(SearchDate.THIS_WEEK, model.effectiveSearchRules.date)
        assertEquals(SearchSize.OVER_1_GB, model.effectiveSearchRules.size)
    }

    @Test fun realSparseFileProvesStrictGigabyteBoundaryAndRefreshReevaluatesCalendarChoices() {
        val large = File(folder, "Large.bin")
        RandomAccessFile(large, "rw").use { it.setLength(1024L * 1024 * 1024) }
        compose.runOnIdle { model.updateSearchRules(SearchRules(size = SearchSize.OVER_1_GB)) }; loaded()
        assertTrue(model.entries.isEmpty())
        RandomAccessFile(large, "rw").use { it.setLength(1024L * 1024 * 1024 + 1) }
        compose.runOnIdle { model.refresh() }; loaded()
        assertEquals(listOf(large.path), model.entries.map { it.path })
        assertTrue(model.entries.single().searchEvidence!!.reasons.contains("Size: Over 1 GB"))
        File(folder, "Old.txt").apply { writeText(bytes); setLastModified(System.currentTimeMillis() - 3L * 86_400_000) }
        compose.runOnIdle { model.updateSearchRules(SearchRules(date = SearchDate.TODAY)) }; loaded()
        assertFalse(model.entries.any { it.name == "Old.txt" })
        assertTrue(model.entries.any { it.path == large.path })
    }

    @Test fun providerScanKeepsSiblingCachesUsesOneReadAndNormalizesTreeAliases() {
        val root = provider()
        val cache = model.documents.list(root).single { it.name == "Cache" }.path
        val cacheUri = Uri.parse(cache)
        val alias = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(cacheUri.authority!!, "root"), DocumentsContract.getDocumentId(cacheUri)).toString()
        assertEquals(model.documents.searchIdentity(cache), model.documents.searchIdentity(alias))
        FixtureDocumentsProvider.contentReads.clear()
        FixtureDocumentsProvider.ancestryChecks.set(0)
        FixtureDocumentsProvider.ancestryCalls.clear()
        compose.runOnIdle { model.updateSearchRules(SearchRules(name = "Report", content = "needle", excludedLocations = listOf(SearchExclusion(alias)))) }; loaded()
        assertEquals(setOf("Report needle.txt", "Report retained.txt"), model.entries.map { it.name }.toSet())
        assertEquals(1, FixtureDocumentsProvider.contentReads[File(providerFolder, "Report needle.txt").canonicalPath]!!.get())
        assertNull(FixtureDocumentsProvider.contentReads[File(providerFolder, "Cache/Report hidden.txt").canonicalPath])
        assertTrue(model.entries.all { it.searchEvidence?.snippet?.text?.contains("needle") == true })
        val searchChecks = FixtureDocumentsProvider.ancestryCalls.count { it.first == DocumentsContract.getDocumentId(cacheUri) }
        assertTrue("Ancestry is cached per folder: $searchChecks", searchChecks in 1..4)
        assertFalse(model.listingProgress.incomplete)
        capture("provider-matches")
        compose.runOnIdle { model.saveSmartFolder("Explained provider reports") }; loaded()
        val smart = model.currentSmartFolder!!
        val fresh = FinderSession(compose.activity.application)
        try { assertEquals(smart, fresh.smartFolders.value.single { it.id == smart.id }) }
        finally { fresh.transferJournal.close() }
    }

    @Test fun providerUnknownAncestryReportsSkippedResultsBeforeOpeningContents() {
        val root = provider()
        val cache = model.documents.list(root).single { it.name == "Cache" }.path
        FixtureDocumentsProvider.ancestryUnsupported = true
        FixtureDocumentsProvider.contentReads.clear()
        compose.runOnIdle { model.updateSearchRules(SearchRules(content = "needle", excludedLocations = listOf(SearchExclusion(cache)))) }; loaded()
        assertTrue(model.entries.isEmpty())
        assertTrue(model.listingProgress.incomplete)
        assertTrue(model.listingProgress.issues.any { it.reason.contains("exclusions couldn't be verified") })
        assertTrue(FixtureDocumentsProvider.contentReads.isEmpty())
        capture("provider-incomplete-exclusions")
    }
}
