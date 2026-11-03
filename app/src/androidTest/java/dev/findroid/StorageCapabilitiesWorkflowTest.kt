@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import android.graphics.Bitmap
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real named browser commands use per-document cached facts without querying on menu open. */
class StorageCapabilitiesWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var fixture: File
    private lateinit var root: String
    private var gate: CountDownLatch? = null
    private val names = listOf("Editable.txt", "No changes.txt", "Not reported.txt", "Rename only.txt")

    @Before fun setup() {
        fixture = File(compose.activity.filesDir, "ProviderFixtures/Action-library-${System.nanoTime()}").apply { mkdirs() }
        names.forEach { File(fixture, it).writeText("Keep the original $it bytes.") }
        File(fixture, "Destination").mkdir()
        FixtureDocumentsProvider.capabilityFlags = mapOf(
            File(fixture, "No changes.txt").canonicalPath to 0,
            File(fixture, "Not reported.txt").canonicalPath to null,
            File(fixture, "Rename only.txt").canonicalPath to Document.FLAG_SUPPORTS_RENAME,
        )
        val tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${fixture.name}")
        root = DocumentsContract.buildDocumentUriUsingTree(tree, "root/${fixture.name}").toString()
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            model.changeDensity(FinderDensity.COMFORTABLE)
            model.updateQuery("")
            model.clearSelection()
            model.connectLocation(tree, false)
        }
        compose.waitUntil(20_000) { model.location == root && !model.loading && model.entries.size == 5 }
        compose.waitUntil(20_000) { !model.storage.checking }
        compose.waitForIdle()
    }

    @After fun cleanup() {
        gate?.countDown()
        FixtureDocumentsProvider.metadataGate = null
        FixtureDocumentsProvider.metadataOpened = null
        FixtureDocumentsProvider.metadataDirectory = null
        FixtureDocumentsProvider.metadataFailure = null
        FixtureDocumentsProvider.metadataDenied = false
        FixtureDocumentsProvider.capabilityFlags = emptyMap()
        FixtureDocumentsProvider.omittedCapabilityColumns = emptySet()
        for (title in listOf("Available actions", "Storage locations")) {
            compose.onAllNodesWithContentDescription("Close $title").fetchSemanticsNodes().firstOrNull()?.let {
                compose.onNodeWithContentDescription("Close $title").performClick()
            }
        }
        compose.runOnIdle {
            model.storage.stop()
            model.navigate(model.workspace.path)
            model.disconnectLocation(root)
        }
        compose.waitUntil(20_000) { !model.loading && model.busy == null }
        names.forEach { assertEquals("Keep the original $it bytes.", File(fixture, it).readText()) }
        fixture.deleteRecursively()
    }

    private fun select(vararg selected: String) = compose.runOnIdle {
        model.clearSelection()
        selected.forEach { name -> model.select(model.entries.single { it.name == name }, multiple = true) }
    }

    private fun open() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Available actions…").performScrollTo().performClick()
        compose.onNodeWithTag("available-actions").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("available-action-read").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun card(action: String) {
        compose.onNodeWithTag("available-actions-list").performScrollToNode(hasTestTag("available-action-$action"))
    }

    private fun textIn(action: String, text: String, substring: Boolean = false) =
        compose.onNode(hasText(text, substring = substring) and hasAnyAncestor(hasTestTag("available-action-$action")))

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "storage-actions-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun unknownFlagsHaveTheirOwnExplanationAndRefreshUpdatesOnlyCurrentFacts() {
        select("Not reported.txt")
        assertTrue(model.folderInfo!!.writable)
        assertFalse(model.selectedEntries.single().canRename)
        open()
        card("rename")
        textIn("rename", "Not reported").assertExists()
        textIn("rename", "The provider did not report document capability flags.", substring = true).assertExists()
        screenshot("not-reported")
        FixtureDocumentsProvider.capabilityFlags = FixtureDocumentsProvider.capabilityFlags +
            (File(fixture, "Not reported.txt").canonicalPath to Document.FLAG_SUPPORTS_RENAME)
        compose.onNodeWithTag("refresh-available-actions").performClick()
        compose.waitUntil(20_000) { !model.loading && !model.storageActionRefresh && model.selectedEntries.singleOrNull()?.canRename == true }
        card("rename")
        textIn("rename", "Reported available").assertExists()
        assertEquals(1, model.selected.size)
        assertFalse(model.selectedEntries.single().canDelete)
    }

    @Test fun openingAndClosingCachedDetailsNeverContactsABlockedProvider() {
        select("Editable.txt")
        val path = File(fixture, "Editable.txt").canonicalPath
        val before = FixtureDocumentsProvider.metadataQueries[path]?.get() ?: 0
        gate = CountDownLatch(1)
        val opened = CountDownLatch(1)
        FixtureDocumentsProvider.metadataDirectory = path
        FixtureDocumentsProvider.metadataGate = gate
        FixtureDocumentsProvider.metadataOpened = opened
        open()
        card("rename")
        textIn("rename", "Reported available").assertExists()
        compose.onNodeWithTag("close-available-actions").assertIsDisplayed().performClick()
        assertFalse("A cached explanation must not start a document query", opened.await(300, TimeUnit.MILLISECONDS))
        assertEquals(before, FixtureDocumentsProvider.metadataQueries[path]?.get() ?: 0)
        assertEquals(1L, gate!!.count)
    }

    @Test fun mixedSelectionReportsExactCountsAndBoundsItsNamedExamples() {
        select(*names.toTypedArray())
        open()
        compose.onNodeWithText("4 selected items · this pane").assertExists()
        card("rename")
        textIn("rename", "2 reported available · 1 unavailable · 1 not reported · 0 do not apply").assertExists()
        textIn("rename", "No changes.txt").assertExists()
        textIn("rename", "Not reported.txt").assertExists()
        screenshot("mixed-selection")
        card("write")
        textIn("write", "1 reported available · 2 unavailable · 1 not reported · 0 do not apply").assertExists()
        card("trash")
        textIn("trash", "Provider items use a local Findroid recovery copy before removal.", substring = true).assertExists()
        assertEquals(4, model.selected.size)
    }

    @Test fun currentRootDetailsDoNotPromiseTheSameCapabilitiesForItsChildren() {
        open()
        compose.onNodeWithText("Current folder · this pane").assertExists()
        card("create")
        textIn("create", "Reported available").assertExists()
        card("delete")
        textIn("delete", "Unavailable").assertExists()
        textIn("delete", "A connected root cannot be deleted.", substring = true).assertExists()
        compose.onNodeWithTag("close-available-actions").performClick()
        select("No changes.txt")
        open()
        card("delete")
        textIn("delete", "The provider does not advertise support for deleting this item on this document.").assertExists()
        card("trash")
        textIn("trash", "Unavailable").assertExists()
    }

    @Test fun anExplicitRefreshReportsRevokedAccessAndKeepsTheDialogUsable() {
        open()
        FixtureDocumentsProvider.metadataFailure = fixture.canonicalPath
        FixtureDocumentsProvider.metadataDenied = true
        compose.onNodeWithTag("refresh-available-actions").performClick()
        compose.waitUntil(20_000) { !model.loading && !model.storageActionRefresh && model.folderStorageIssue != null }
        card("read")
        textIn("read", "Unavailable").assertExists()
        textIn("read", model.folderStorageIssue!!.detail).assertExists()
        compose.onNodeWithTag("available-actions-storage").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("close-available-actions").assertIsDisplayed().performClick()
        assertEquals(root, model.location)
        assertTrue(fixture.isDirectory)
    }

    @Test fun contextMenuAndActionLauncherReachTheSameNamedDetails() {
        compose.runOnIdle { model.requestBackgroundContext() }
        compose.onNodeWithText("Available actions…").performScrollTo().performClick()
        compose.onNodeWithTag("available-actions").assertExists()
        compose.onNodeWithTag("close-available-actions").performClick()
        compose.onAllNodes(isRoot()).onLast().performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.ShiftLeft); pressKey(Key.P); keyUp(Key.ShiftLeft); keyUp(Key.CtrlLeft)
        }
        compose.onNodeWithTag("launcher-query").performTextInput("available actions")
        compose.onNodeWithTag("action-availableActions").performClick()
        compose.onNodeWithTag("available-actions").assertExists()
        compose.onNodeWithTag("close-available-actions").performClick()
    }

    @Test fun blockedRenameUsesTheSelectedDocumentsOwnReasonAndCopyRecovery() {
        select("No changes.txt")
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.F2) }
        compose.onNodeWithText("Rename is unavailable").assertExists()
        compose.onNodeWithText("The provider does not advertise support for renaming this item on this document.", substring = true).assertExists()
        compose.onNode(hasText("No changes.txt", substring = true) and hasAnyAncestor(hasTestTag("command-explanation"))).assertExists()
        compose.onNodeWithTag("command-recovery").assertTextContains("Copy to another folder…")
        compose.onNodeWithText("Back to files").performClick()
    }
}
