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
import org.junit.*
import org.junit.Assert.*

class NestedSearchWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var folder: File
    private lateinit var organization: OrganizationSnapshot
    private lateinit var smartIds: Set<String>
    private var providerFolder: File? = null
    private var providerRoot: String? = null
    private val expectedGroup = SearchRuleGroup(SearchRuleJoin.ALL, listOf(
        SearchRuleGroup(SearchRuleJoin.ANY, listOf(SearchKindRule("PDF document"), SearchKindRule("Text document"))),
        SearchRuleGroup(SearchRuleJoin.ANY, listOf(SearchTagRule("Review"), SearchTagRule("Approved"))),
    ))

    @Before fun setup() {
        compose.waitUntil(20_000) { !model.loading && model.busy == null }
        organization = model.session.tagStore.reload()
        smartIds = model.smartFolders.map { it.id }.toSet()
        folder = File(model.workspace, "Nested search QA-${System.nanoTime()}").canonicalFile.apply { mkdirs() }
        listOf("Report review.txt", "Report approved.pdf", "Report untagged.txt", "Cache/Report hidden.txt").forEach {
            File(folder, it).apply { parentFile!!.mkdirs(); writeText("Synthetic needle notes. ".repeat(100)) }
        }
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane(); model.windowState.peekEntry.value = null
            model.changeMode(ViewMode.LIST); model.changeGrouping(GroupBy.NONE)
            if (model.previewVisible) model.togglePreview()
            model.navigate(folder.path); model.updateSearchRules(SearchRules())
            model.session.tagStore.define("Review", "Blue")
            model.session.tagStore.define("Approved", "Green")
            model.session.tagStore.assign(listOf(File(folder, "Report review.txt").path, File(folder, "Cache/Report hidden.txt").path), "Review")
            model.session.tagStore.assign(listOf(File(folder, "Report approved.pdf").path), "Approved")
            model.syncLibrary(); model.refresh()
            model.recentSearchHistory.clear(); model.recentSearchHistory.setEnabled(true); model.discardSearchDraft()
        }
        loaded()
    }

    @After fun cleanup() {
        // A group and its condition editor replace their parent surface, so close each visible level.
        for (tag in listOf("cancel-group-rule", "cancel-rule-groups", "cancel-search-criteria")) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag(tag).performClick()
        }
        FixtureDocumentsProvider.contentReads.clear()
        FixtureDocumentsProvider.unknownSizePaths = emptySet()
        compose.runOnIdle {
            model.listingDetailsRequested = false
            model.smartFolders.filterNot { it.id in smartIds }.forEach { model.removeSmartFolder(it.id) }
            model.navigate(model.workspace.path); model.updateSearchRules(SearchRules())
            model.session.tagStore.restoreOrganization(model.session.tagStore.reload(), organization)
            model.syncLibrary()
            providerRoot?.let(model.documents::disconnect)
            model.session.connectedLocations.value = model.documents.locations
            model.recentSearchHistory.clear(); model.discardSearchDraft()
            model.session.history.resetSavedHistory(); model.syncHistory(); model.session.undoLabel.value = null; model.session.redoLabel.value = null
        }
        loaded(); folder.deleteRecursively(); providerFolder?.deleteRecursively()
    }

    private fun loaded() { compose.waitUntil(20_000) { !model.loading && model.busy == null }; compose.waitForIdle() }
    private fun results(vararg names: String) { loaded(); assertEquals(names.toSet(), model.entries.map { it.name }.toSet()) }
    private fun openFilters() {
        if (compose.onAllNodesWithContentDescription("Search filters").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Search filters").performClick()
        compose.onNodeWithTag("search-criteria").assertIsDisplayed()
    }
    private fun groups() { compose.onNodeWithTag("edit-search-rule-groups").performScrollTo().performClick() }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun condition(type: String, value: String) {
        click("add-group-rule"); click("rule-condition-type-$type")
        when (type) {
            "KIND" -> click("rule-kind-$value")
            "TAG" -> compose.onNodeWithTag("rule-tag-value").performScrollTo().performTextReplacement(value)
            "NAME" -> compose.onNodeWithTag("rule-name-value").performScrollTo().performTextReplacement(value)
        }
        hideKeyboard(); compose.onNodeWithTag("save-group-rule").performClick()
    }
    private fun hideKeyboard() {
        compose.runOnIdle {
            val input = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            WindowInspector.getGlobalWindowViews().forEach { input.hideSoftInputFromWindow(it.windowToken, 0) }
        }
        compose.waitUntil(5000) {
            var hidden = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                hidden = WindowInspector.getGlobalWindowViews().none { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            }
            hidden
        }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        hideKeyboard()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5000)
        val directory = File(compose.activity.filesDir, "nested-search-qa").apply { mkdirs() }
        val bitmap = automation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun provider(): String {
        val base = File(compose.activity.filesDir, "ProviderFixtures").apply { mkdirs() }
        val chosen = File(base, "Nested-${System.nanoTime()}").apply { mkdir() }; providerFolder = chosen
        File(chosen, "Report cloud.txt").writeText("needle cloud notes")
        File(chosen, "Unknown-size.txt").writeText("needle provider bytes")
        FixtureDocumentsProvider.unknownSizePaths = setOf(File(chosen, "Unknown-size.txt").canonicalPath)
        val uri = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${chosen.name}")
        val root = model.documents.connect(uri, persist = false).uri; providerRoot = root
        compose.runOnIdle { model.session.connectedLocations.value = model.documents.locations }
        return root
    }

    @Test fun actualEditorBuildsNestedKindAndTagAlternativesAndAppliesOnlyAtParent() {
        openFilters(); groups()
        compose.onNodeWithTag("apply-rule-groups").assertIsNotEnabled()
        click("add-rule-group"); click("rule-group-ANY")
        condition("KIND", "PDF document"); condition("KIND", "Text document")
        click("rule-group-parent"); click("add-rule-group"); click("rule-group-ANY")
        condition("TAG", "Review"); condition("TAG", "Approved")
        click("rule-group-parent"); capture("nested-kind-and-tag-draft")
        compose.onNodeWithTag("apply-rule-groups").performClick()
        assertNull(model.effectiveSearchRules.ruleGroup)
        compose.onNodeWithTag("search-rule-group-summary").assertTextEquals(expectedGroup.summary)
        compose.onNodeWithTag("apply-search-criteria").performClick()
        results("Report review.txt", "Report approved.pdf", "Report hidden.txt")
        assertEquals(expectedGroup, model.effectiveSearchRules.ruleGroup)
        capture("nested-kind-and-tag-results")
        compose.activityRule.scenario.recreate(); loaded()
        assertEquals(expectedGroup, model.effectiveSearchRules.ruleGroup)
    }

    @Test fun emptyGroupsAndConditionCancellationKeepTheCompleteParentDraft() {
        val root = provider()
        val before = SearchRules(name = "Report", days = 7, minimumBytes = 1025, maximumBytes = 8192,
            locations = listOf(folder.path, root), excludedLocations = listOf(SearchExclusion(File(folder, "Cache").path)),
            excludedFolderNames = setOf("Scratch"))
        compose.runOnIdle { model.updateSearchRules(before) }; loaded()
        openFilters()
        compose.onNodeWithTag("search-minimum-kb").performScrollTo().performTextReplacement("1.0009765625")
        compose.onNodeWithTag("search-excluded-folders").performScrollTo().performTextReplacement("Scratch\nPrivate")
        hideKeyboard(); groups(); click("add-rule-group")
        compose.onNodeWithTag("apply-rule-groups").assertIsNotEnabled()
        click("add-group-rule")
        compose.onNodeWithTag("rule-name-value").performTextReplacement("discarded")
        hideKeyboard(); compose.onNodeWithTag("cancel-group-rule").performClick()
        compose.onNodeWithTag("apply-rule-groups").assertIsNotEnabled()
        condition("NAME", "Report")
        click("edit-rule-child-0")
        compose.onNodeWithTag("rule-name-value").performTextReplacement("discarded edit")
        hideKeyboard(); compose.onNodeWithTag("cancel-group-rule").performClick()
        compose.onNode(hasText("Name contains “Report”") and hasAnyAncestor(hasTestTag("rule-child-0"))).assertIsDisplayed()
        compose.onNodeWithTag("cancel-rule-groups").performClick()
        assertEquals(before, model.effectiveSearchRules)
        compose.onNodeWithTag("search-minimum-kb").performScrollTo().assertTextContains("1.0009765625")
        compose.onNodeWithTag("search-excluded-folders").performScrollTo().assertTextContains("Scratch\nPrivate")
        groups(); condition("NAME", "Report"); compose.onNodeWithTag("apply-rule-groups").performClick()
        compose.onNodeWithTag("apply-search-criteria").performClick(); loaded()
        assertEquals(before.copy(excludedFolderNames = setOf("Scratch", "Private"),
            ruleGroup = SearchRuleGroup(SearchRuleJoin.ALL, listOf(SearchNameRule("Report")))), model.effectiveSearchRules)
        openFilters(); groups(); click("rule-group-NONE"); compose.onNodeWithTag("apply-rule-groups").performClick()
        compose.onNodeWithTag("cancel-search-criteria").performClick()
        assertEquals(SearchRuleJoin.ALL, model.effectiveSearchRules.ruleGroup!!.join)
    }

    @Test fun staleTabAndRuleChangesBlockGroupAndParentApplyOrClear() {
        val group = SearchRuleGroup(SearchRuleJoin.ALL, listOf(SearchNameRule("Report")))
        compose.runOnIdle { model.updateSearchRules(SearchRules(ruleGroup = group)) }; loaded()
        openFilters(); groups(); click("rule-group-NONE")
        compose.runOnIdle { model.newTab(folder.path) }; loaded()
        compose.onNodeWithTag("apply-rule-groups").performClick()
        compose.onNodeWithTag("rule-group-error").performScrollTo().assertTextContains("current tab or search changed", substring = true)
        assertNull(model.effectiveSearchRules.ruleGroup)
        compose.onNodeWithTag("cancel-rule-groups").performClick()
        compose.onNodeWithTag("apply-search-criteria").performClick()
        compose.onNodeWithTag("search-criteria-error").assertIsDisplayed()
        compose.onNodeWithTag("cancel-search-criteria").performClick()
        openFilters(); groups(); condition("NAME", "Report")
        compose.runOnIdle { model.updateSearchRules(SearchRules(name = "untagged")) }; loaded()
        compose.onNodeWithTag("apply-rule-groups").performClick()
        compose.onNodeWithTag("rule-group-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cancel-rule-groups").performClick()
        compose.onNodeWithTag("clear-search-criteria").performClick()
        assertEquals("untagged", model.effectiveSearchRules.name)
        compose.onNodeWithTag("search-criteria-error").assertIsDisplayed()
    }

    @Test fun smartFolderAndHistoryStayIndependentAcrossRestartAndTagRename() {
        val rules = SearchRules(ruleGroup = expectedGroup, excludedLocations = listOf(SearchExclusion(File(folder, "Cache").path)))
        compose.runOnIdle { model.updateSearchRules(rules); model.rememberCurrentSearch(); model.saveSmartFolder("Reviewed reports") }
        results("Report review.txt", "Report approved.pdf")
        val smart = model.currentSmartFolder!!
        val savedHistory = model.recentSearchHistory.state.searches.first { it.rules == rules }
        val restored = FinderSession(compose.activity.application)
        try { assertEquals(smart, restored.smartFolders.value.single { it.id == smart.id }) }
        finally { restored.transferJournal.close() }
        openFilters(); groups(); click("rule-group-NONE"); compose.onNodeWithTag("apply-rule-groups").performClick()
        compose.onNodeWithTag("cancel-search-criteria").performClick()
        assertEquals(rules, model.effectiveSearchRules)
        assertEquals(rules, savedHistory.rules)
        compose.runOnIdle { model.renameTag("Review", "Reviewed", "Blue") }
        results("Report review.txt", "Report approved.pdf")
        assertEquals(setOf("Reviewed", "Approved"), model.effectiveSearchRules.referencedTags)
        assertEquals(emptySet<String>(), model.effectiveSearchRules.filterTags)
        compose.activityRule.scenario.recreate(); loaded()
        assertEquals(smart.id, model.currentSmartFolder!!.id)
        assertEquals(expectedGroup.remapRuleTags(mapOf("Review" to "Reviewed")), model.effectiveSearchRules.ruleGroup)
        assertEquals(rules, savedHistory.rules)
    }

    @Test fun nestedTagAssignmentRefreshesMembershipWithoutManualRefresh() {
        compose.runOnIdle { model.updateSearchRules(SearchRules(ruleGroup = expectedGroup,
            excludedLocations = listOf(SearchExclusion(File(folder, "Cache").path)))) }
        results("Report review.txt", "Report approved.pdf")
        compose.runOnIdle { model.select(model.entries.single { it.name == "Report review.txt" }); model.tag("Review") }
        compose.waitUntil(20_000) { model.busy == null && !model.loading && model.entries.map { it.name }.toSet() == setOf("Report approved.pdf") }
        assertEquals(setOf("Review", "Approved"), model.effectiveSearchRules.referencedTags)
    }

    @Test fun chosenProviderRootsRespectGroupsExclusionsUnknownSizesAndOneContentRead() {
        val root = provider()
        val any = SearchRuleGroup(SearchRuleJoin.ANY, listOf(SearchSizeRule(minimumBytes = 1), SearchNameRule("Unknown-size")))
        val rules = SearchRules(content = "needle", ruleGroup = any, locations = listOf(folder.path, root),
            excludedLocations = listOf(SearchExclusion(File(folder, "Cache").path)))
        FixtureDocumentsProvider.contentReads.clear()
        compose.runOnIdle { model.updateSearchRules(rules) }
        results("Report review.txt", "Report untagged.txt", "Report cloud.txt", "Unknown-size.txt")
        assertEquals(1, FixtureDocumentsProvider.contentReads[File(providerFolder, "Unknown-size.txt").canonicalPath]?.get())
        assertEquals(1, FixtureDocumentsProvider.contentReads[File(providerFolder, "Report cloud.txt").canonicalPath]?.get())
        assertEquals(0, model.listingProgress.issueCount)
        FixtureDocumentsProvider.contentReads.clear()
        compose.runOnIdle { model.updateSearchRules(rules.copy(content = "", ruleGroup = SearchRuleGroup(SearchRuleJoin.NONE, listOf(SearchSizeRule(minimumBytes = 1))))) }
        results()
        assertTrue(model.listingProgress.issues.any { it.reason.startsWith("Unknown size:") })
        assertTrue(FixtureDocumentsProvider.contentReads.isEmpty())
        capture("unknown-size-rule-details")
        assertEquals(rules.locations, model.effectiveSearchRules.locations)
        assertEquals(rules.excludedLocations, model.effectiveSearchRules.excludedLocations)
    }
    @Test fun calendarAndExactSizeConditionsWithNoneCanBeEditedAndRemoved() {
        openFilters(); groups(); click("add-group-rule"); click("rule-condition-type-MODIFIED")
        click("rule-date-days")
        compose.onNodeWithTag("rule-days-value").performScrollTo().performTextReplacement("7")
        hideKeyboard(); compose.onNodeWithTag("save-group-rule").performClick()
        click("add-group-rule"); click("rule-condition-type-SIZE"); click("rule-size-ANY")
        compose.onNodeWithTag("rule-minimum-kb").performScrollTo().performTextReplacement("1.0009765625")
        compose.onNodeWithTag("rule-maximum-kb").performScrollTo().performTextReplacement("8")
        hideKeyboard(); compose.onNodeWithTag("save-group-rule").performClick()
        click("add-rule-group"); click("rule-group-NONE")
        condition("NAME", "untagged"); condition("NAME", "hidden"); condition("NAME", "review")
        click("remove-rule-child-2"); click("rule-group-parent")
        compose.onNodeWithTag("apply-rule-groups").performClick()
        compose.onNodeWithTag("apply-search-criteria").performClick()
        results("Report review.txt", "Report approved.pdf")
        val expected = SearchRuleGroup(SearchRuleJoin.ALL, listOf(SearchModifiedRule(SearchDate.ANY, 7),
            SearchSizeRule(1025, 8192), SearchRuleGroup(SearchRuleJoin.NONE, listOf(SearchNameRule("untagged"), SearchNameRule("hidden")))))
        assertEquals(expected, model.effectiveSearchRules.ruleGroup)
        openFilters(); groups(); click("edit-rule-child-1")
        click("rule-size-ANY")
        compose.onNodeWithTag("rule-minimum-kb").performScrollTo().assertTextContains("1.0009765625")
        compose.onNodeWithTag("rule-maximum-kb").performScrollTo().assertTextContains("8")
        hideKeyboard(); compose.onNodeWithTag("cancel-group-rule").performClick()
        compose.onNodeWithTag("cancel-rule-groups").performClick()
        compose.onNodeWithTag("cancel-search-criteria").performClick()
        assertEquals(expected, model.effectiveSearchRules.ruleGroup)
    }

}
