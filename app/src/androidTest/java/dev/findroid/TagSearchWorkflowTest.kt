package dev.findroid

import android.graphics.Bitmap
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*

class TagSearchWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var folder: File
    private lateinit var original: OrganizationSnapshot
    private lateinit var smartIds: Set<String>
    private var providerRoot: String? = null
    private var providerFolder: File? = null

    @Before
    fun setup() {
        original = model.session.tagStore.reload()
        smartIds = model.smartFolders.map { it.id }.toSet()
        folder =
            File(model.workspace, "Tag search QA-${System.nanoTime()}").canonicalFile.apply {
                mkdirs()
            }
        File(folder, "Research and approved.txt").writeText("project notes")
        File(folder, "Research only.txt").writeText("project notes")
        File(folder, "Untagged.txt").writeText("project notes")
        File(folder, "Drafts").mkdir()
        File(folder, "Drafts/Approved only.txt").writeText("project notes")
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.windowState.peekEntry.value = null
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            model.navigate(folder.path)
            model.updateSearchRules(SearchRules())
            val store = model.session.tagStore
            store.define("Research", "Blue")
            store.define("Approved", "Green")
            store.assign(
                listOf(
                    File(folder, "Research and approved.txt").path,
                    File(folder, "Research only.txt").path,
                ),
                "Research",
            )
            store.assign(
                listOf(
                    File(folder, "Research and approved.txt").path,
                    File(folder, "Drafts/Approved only.txt").path,
                ),
                "Approved",
            )
            model.syncLibrary()
            model.refresh()
            model.session.history.clear()
        }
        loaded()
    }

    private fun loaded() {
        compose.waitUntil(15_000) { !model.loading }
        compose.waitForIdle()
    }

    private fun results(mode: TagMatch, expected: Set<String>) {
        compose.runOnIdle {
            model.updateSearchRules(
                SearchRules(
                    tags = setOf("Research", "Approved"),
                    tagMatch = mode,
                    kind = "Text document",
                ),
            )
        }
        loaded()
        assertEquals(expected, model.entries.map { it.name }.toSet())
    }

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
        // Scroll the vertical form before targeting chips in its horizontal rows.
        compose.onNodeWithText("Minimum KB").performScrollTo()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5000)
        val directory =
            File(compose.activity.getExternalFilesDir(null), "tag-search-qa").apply { mkdirs() }
        val bitmap = automation.takeScreenshot()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @After
    fun cleanup() {
        compose
            .onAllNodesWithText("Cancel")
            .fetchSemanticsNodes()
            .takeIf { it.isNotEmpty() }
            ?.let {
                compose.onNodeWithText("Cancel").performClick()
            }
        compose.runOnIdle {
            model.smartFolders
                .filterNot { it.id in smartIds }
                .forEach { model.removeSmartFolder(it.id) }
            model.navigate(model.workspace.path)
            model.updateSearchRules(SearchRules())
            model.session.tagStore.restoreOrganization(model.session.tagStore.reload(), original)
            model.syncLibrary()
            providerRoot?.let { model.documents.disconnect(it) }
            model.session.connectedLocations.value = model.documents.locations
            model.session.history.clear()
            model.session.undoLabel.value = null
            model.session.redoLabel.value = null
        }
        loaded()
        folder.deleteRecursively()
        providerFolder?.deleteRecursively()
    }

    @Test
    fun allAnyAndNoneSearchRecursivelyWithoutIncludingFolders() {
        results(TagMatch.ALL, setOf("Research and approved.txt"))
        results(
            TagMatch.ANY,
            setOf("Research and approved.txt", "Research only.txt", "Approved only.txt"),
        )
        results(TagMatch.NONE, setOf("Untagged.txt"))
    }

    @Test
    fun criteriaAreDraftedUntilApplyAndSurviveActivityRecreation() {
        openFilters()
        compose.onNodeWithTag("search-tag-Research").performScrollTo().performClick()
        compose.onNodeWithTag("search-tag-Approved").performScrollTo().performClick()
        compose.onNodeWithTag("tag-match-ANY").performScrollTo().performClick()
        compose.onNodeWithTag("search-tag-Research").assertIsSelected()
        compose.onNodeWithTag("search-tag-Approved").assertIsSelected()
        capture("multi-tag-criteria")
        assertTrue(model.effectiveSearchRules.filterTags.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(model.effectiveSearchRules.filterTags.isEmpty())

        results(
            TagMatch.ANY,
            setOf("Research and approved.txt", "Research only.txt", "Approved only.txt"),
        )
        compose.activityRule.scenario.recreate()
        loaded()
        assertEquals(TagMatch.ANY, model.effectiveSearchRules.tagMatch)
        assertEquals(setOf("Research", "Approved"), model.effectiveSearchRules.filterTags)
        openFilters()
        compose.onNodeWithTag("tag-match-NONE").performScrollTo().performClick()
        compose.onNodeWithText("Apply filters").performClick()
        loaded()
        assertEquals(setOf("Untagged.txt"), model.entries.map { it.name }.toSet())
        openFilters()
        compose.onNodeWithText("No tag filter").performScrollTo().performClick()
        compose.onNodeWithText("Apply filters").performClick()
        loaded()
        assertTrue(model.effectiveSearchRules.filterTags.isEmpty())
        assertEquals(4, model.entries.size)
    }

    @Test
    fun savedSmartFolderPersistsItsModeAndFollowsTagRenameUndoRedo() {
        results(TagMatch.ALL, setOf("Research and approved.txt"))
        compose.runOnIdle { model.saveSmartFolder("Approved research") }
        loaded()
        val id = model.currentSmartFolder!!.id
        val prefs = compose.activity.getSharedPreferences("finder", 0)
        val array = JSONArray(prefs.getString("smartFolders", "[]"))
        val saved =
            (0 until array.length()).map(array::getJSONObject).single { it.getString("id") == id }
        assertEquals(model.effectiveSearchRules, NavigationJson.rules(saved.getJSONObject("rules")))
        capture("smart-folder-tag-results")
        compose.runOnIdle { model.renameTag("Research", "Study", "Blue") }
        loaded()
        assertEquals(setOf("Study", "Approved"), model.effectiveSearchRules.filterTags)
        assertEquals(setOf("Research and approved.txt"), model.entries.map { it.name }.toSet())
        compose.runOnIdle { model.undo() }
        compose.waitUntil(15_000) { model.busy == null && !model.loading }
        assertEquals(setOf("Research", "Approved"), model.effectiveSearchRules.filterTags)
        compose.runOnIdle { model.redo() }
        compose.waitUntil(15_000) { model.busy == null && !model.loading }
        assertEquals(setOf("Study", "Approved"), model.effectiveSearchRules.filterTags)
    }

    @Test
    fun tagChangesRefreshMembershipWithoutManuallyReloading() {
        results(TagMatch.ALL, setOf("Research and approved.txt"))
        compose.runOnIdle {
            model.select(model.entries.single())
            model.tag("Approved")
        }
        loaded()
        assertTrue(model.entries.isEmpty())
    }

    @Test
    fun legacyAndUnavailableSelectionsStayVisibleUntilExplicitlyCleared() {
        compose.runOnIdle {
            model.updateSearchRules(
                SearchRules(
                    tag = "Research",
                    tags = setOf("Retired topic"),
                    tagMatch = TagMatch.ANY,
                ),
            )
        }
        loaded()
        openFilters()
        compose.onNodeWithTag("search-tag-Research").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("search-tag-Retired topic").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Apply filters").performClick()
        loaded()
        assertEquals(setOf("Research", "Retired topic"), model.effectiveSearchRules.filterTags)
        assertEquals(TagMatch.ANY, model.effectiveSearchRules.tagMatch)
        openFilters()
        compose.onNodeWithText("Clear").performClick()
        loaded()
        assertFalse(model.effectiveSearchRules.active)
    }

    @Test
    fun everyModeLoadsFromPersistedSmartFoldersInAFreshSession() {
        for (mode in TagMatch.entries) {
            compose.runOnIdle {
                model.navigate(folder.path)
                model.updateSearchRules(
                    SearchRules(tags = setOf("Research", "Approved"), tagMatch = mode),
                )
                model.saveSmartFolder("Saved ${mode.title}")
            }
            loaded()
            val expected = model.currentSmartFolder!!
            val restored = FinderSession(compose.activity.application)
            try {
                assertEquals(expected, restored.smartFolders.value.single { it.id == expected.id })
            } finally {
                restored.transferJournal.close()
            }
        }
    }

    @Test
    fun editingSharedSmartRulesRefreshesTheOtherPane() {
        results(TagMatch.ALL, setOf("Research and approved.txt"))
        compose.runOnIdle { model.saveSmartFolder("Shared tag search") }
        loaded()
        val other = model.secondaryPane
        compose.runOnIdle { other.navigate(model.location) }
        compose.waitUntil(15_000) { !other.loading && other.entries.size == 1 }
        compose.runOnIdle {
            model.updateSearchRules(model.effectiveSearchRules.copy(tagMatch = TagMatch.ANY))
        }
        compose.waitUntil(15_000) { !model.loading && !other.loading && other.entries.size == 3 }
        assertEquals(model.entries.map { it.path }.toSet(), other.entries.map { it.path }.toSet())
    }

    @Test
    fun providerSearchUsesTheSameBooleanTagRules() {
        val provider = File(compose.activity.filesDir, "ProviderFixtures/${folder.name}")
        folder.copyRecursively(provider)
        providerFolder = provider
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/${folder.name}",
            )
        compose.runOnIdle { model.connectLocation(tree, false) }
        compose.waitUntil(15_000) {
            model.location.startsWith("content://") && !model.loading && model.entries.size == 4
        }
        loaded()
        providerRoot = model.location
        assertTrue(model.location.startsWith("content://"))
        val entries = model.entries.filterNot { it.folder }
        compose.runOnIdle {
            val store = model.session.tagStore
            store.assign(
                entries.filter { it.name.startsWith("Research") }.map { it.path },
                "Research",
            )
            store.assign(
                entries.filter { it.name == "Research and approved.txt" }.map { it.path },
                "Approved",
            )
            model.syncLibrary()
        }
        results(TagMatch.ALL, setOf("Research and approved.txt"))
        results(TagMatch.ANY, setOf("Research and approved.txt", "Research only.txt"))
        results(TagMatch.NONE, setOf("Untagged.txt", "Approved only.txt"))
    }
}
