package dev.findroid

import android.content.Context
import android.graphics.Bitmap
import android.provider.DocumentsContract
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*

class SearchScopeWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var folder: File
    private lateinit var originalSmartIds: Set<String>
    private var providerRoot: String? = null
    private var providerFolder: File? = null
    private val all =
        setOf(
            "Report current.txt",
            "Report stored.txt",
            "Report active.txt",
            "Report previous.txt",
            "Report retained.txt",
        )
    private val excluded = setOf("Report current.txt", "Report active.txt", "Report retained.txt")

    @Before
    fun setup() {
        originalSmartIds = model.smartFolders.map { it.id }.toSet()
        folder =
            File(model.workspace, "Search scope QA-${System.nanoTime()}").canonicalFile.apply {
                mkdirs()
            }
        listOf(
                "Report current.txt",
                "Archive/Report stored.txt",
                "Active/Report active.txt",
                "Active/Archive/Report previous.txt",
                "Active/Archive-copy/Report retained.txt",
            )
            .forEach {
                File(folder, it).apply {
                    parentFile!!.mkdirs()
                    writeText("Synthetic report notes")
                }
            }
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.windowState.peekEntry.value = null
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            model.navigate(folder.path)
            model.updateSearchRules(SearchRules(name = "Report", kind = "Text document"))
        }
        loaded()
        assertEquals(all, names())
    }

    private fun loaded() {
        compose.waitUntil(15_000) { !model.loading }
        compose.waitForIdle()
    }

    private fun names() = model.entries.map { it.name }.toSet()

    private fun openFilters() {
        if (
            compose
                .onAllNodesWithContentDescription("Search filters")
                .fetchSemanticsNodes()
                .isEmpty()
        )
            compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Search filters").performClick()
        compose.onNodeWithText("Search criteria").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val input =
                compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
            WindowInspector.getGlobalWindowViews().forEach {
                input.hideSoftInputFromWindow(it.windowToken, 0)
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5000) {
            var hidden = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                hidden =
                    WindowInspector.getGlobalWindowViews().none {
                        ViewCompat.getRootWindowInsets(it)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
            }
            hidden
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5000)
        // Native IME/surface transactions can trail Compose and inset state by a few frames.
        android.os.SystemClock.sleep(350)
        val directory =
            File(compose.activity.getExternalFilesDir(null), "search-scope-qa").apply { mkdirs() }
        val bitmap = automation.takeScreenshot()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @After
    fun cleanup() {
        FixtureDocumentsProvider.listingFailure = null
        compose
            .onAllNodesWithText("Cancel")
            .fetchSemanticsNodes()
            .takeIf { it.isNotEmpty() }
            ?.let {
                compose.onNodeWithText("Cancel").performClick()
            }
        compose.runOnIdle {
            model.listingDetailsRequested = false
            model.smartFolders
                .filterNot { it.id in originalSmartIds }
                .forEach { model.removeSmartFolder(it.id) }
            model.navigate(model.workspace.path)
            model.updateSearchRules(SearchRules())
            providerRoot?.let { model.documents.disconnect(it) }
            model.session.connectedLocations.value = model.documents.locations
        }
        loaded()
        folder.deleteRecursively()
        providerFolder?.deleteRecursively()
    }

    @Test
    fun scopeDraftApplyCancelAndClearUseTheActualCriteriaEditor() {
        openFilters()
        compose.onNodeWithTag("search-depth-false").performClick()
        compose.onNodeWithTag("search-excluded-folders").performTextReplacement("Archive")
        assertTrue(model.effectiveSearchRules.includeSubfolders)
        assertTrue(model.effectiveSearchRules.excludedFolderNames.isEmpty())
        capture("search-scope-criteria")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(all, names())
        openFilters()
        compose.onNodeWithTag("search-depth-false").performClick()
        compose.onNodeWithText("Apply filters").performClick()
        loaded()
        assertEquals(setOf("Report current.txt"), names())
        compose.activityRule.scenario.recreate()
        loaded()
        assertFalse(model.effectiveSearchRules.includeSubfolders)
        openFilters()
        compose.onNodeWithTag("search-depth-true").performClick()
        compose.onNodeWithTag("search-excluded-folders").performTextReplacement("Archive\nArchive")
        compose.onNodeWithText("Apply filters").performClick()
        loaded()
        assertEquals(excluded, names())
        assertEquals(setOf("Archive"), model.effectiveSearchRules.excludedFolderNames)
        compose.onNodeWithText("Folder and subfolders · 1 folder name excluded").assertIsDisplayed()
        capture("search-scope-results")
        compose.onNodeWithContentDescription("Result details").performClick()
        compose.onNodeWithText("Excluded folder names (exact match):").assertIsDisplayed()
        compose.onNodeWithText("Archive", useUnmergedTree = true).assertExists()
        compose.runOnIdle { model.listingDetailsRequested = false }
        openFilters()
        compose.onNodeWithText("Clear").performClick()
        loaded()
        assertEquals(SearchRules(), model.effectiveSearchRules)
        assertEquals(setOf("Report current.txt", "Active", "Archive"), names())
    }

    @Test
    fun tabsPersistScopeImmediatelyAndKeepTheirIndependentResults() {
        val rules =
            SearchRules(
                name = "Report",
                includeSubfolders = false,
                excludedFolderNames = setOf("Archive"),
            )
        compose.runOnIdle { model.updateSearchRules(rules) }
        loaded()
        val array =
            JSONArray(compose.activity.getSharedPreferences("finder", 0).getString("tabs", "[]"))
        val tabs = NavigationJson.tabs(array, model.workspace.path)
        assertEquals(rules.copy(name = ""), tabs[model.activeTab].rules)
        assertEquals("Report", tabs[model.activeTab].search)
        val previous = model.tabs[model.activeTab].id
        compose.runOnIdle { model.newTab(folder.path) }
        loaded()
        assertFalse(model.effectiveSearchRules.scoped)
        assertEquals(3, model.entries.size)
        compose.runOnIdle { model.closeTab(model.activeTab) }
        loaded()
        assertEquals(previous, model.tabs[model.activeTab].id)
        assertEquals(rules, model.effectiveSearchRules)
        assertEquals(setOf("Report current.txt"), names())
    }

    @Test
    fun smartFolderScopeSurvivesFreshSessionsAndRefreshesOtherPanes() {
        val rules =
            SearchRules(
                name = "Report",
                kind = "Text document",
                excludedFolderNames = setOf("Archive"),
            )
        compose.runOnIdle {
            model.updateSearchRules(rules)
            model.saveSmartFolder("Current reports")
        }
        loaded()
        assertEquals(excluded, names())
        val smart = model.currentSmartFolder!!
        val restored = FinderSession(compose.activity.application)
        try {
            assertEquals(smart, restored.smartFolders.value.single { it.id == smart.id })
        } finally {
            restored.transferJournal.close()
        }
        val other = model.secondaryPane
        compose.runOnIdle { other.navigate(model.location) }
        compose.waitUntil(15_000) { !other.loading && other.entries.size == 3 }
        compose.runOnIdle { model.updateSearchRules(rules.copy(includeSubfolders = false)) }
        compose.waitUntil(15_000) { !model.loading && !other.loading && other.entries.size == 1 }
        assertEquals(setOf("Report current.txt"), names())
        assertEquals(names(), other.entries.map { it.name }.toSet())
    }

    @Test
    fun providerScopeSkipsExcludedOrUnrequestedFoldersWithoutOfflineWarnings() {
        val provider = File(compose.activity.filesDir, "ProviderFixtures/${folder.name}")
        folder.copyRecursively(provider)
        providerFolder = provider
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/${folder.name}",
            )
        compose.runOnIdle { model.connectLocation(tree, false) }
        compose.waitUntil(15_000) { model.location.startsWith("content://") && !model.loading }
        providerRoot = model.location
        FixtureDocumentsProvider.listingFailure = File(provider, "Archive").canonicalPath
        compose.runOnIdle {
            model.updateSearchRules(
                SearchRules(name = "Report", excludedFolderNames = setOf("Archive")),
            )
        }
        loaded()
        assertEquals(excluded, names())
        assertFalse(model.listingProgress.incomplete)
        compose.runOnIdle {
            model.updateSearchRules(SearchRules(name = "Report", includeSubfolders = false))
        }
        loaded()
        assertEquals(setOf("Report current.txt"), names())
        assertFalse(model.listingProgress.incomplete)
        compose.runOnIdle { model.updateSearchRules(SearchRules(name = "Report")) }
        loaded()
        assertEquals(all - "Report stored.txt", names())
        assertEquals(1, model.listingProgress.issueCount)
        assertTrue(model.listingProgress.incomplete)
    }
}
