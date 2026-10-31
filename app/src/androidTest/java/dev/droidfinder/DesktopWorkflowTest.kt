package dev.droidfinder

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DesktopWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var fixture: File
    private val model
        get() = compose.activity.model

    @Before
    fun setup() {
        fixture =
            File(compose.activity.filesDir, "Workspace/Desktop-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(fixture, "Notes.md").writeText("Desktop workflow")
        File(fixture, "Other.txt").writeText("Other content")
        File(fixture, "Target").mkdir()
        ZipOutputStream(File(fixture, "Package.zip").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Folder/Subfolder/Inside.txt"))
            zip.write("Archived content".toByteArray())
            zip.closeEntry()
        }
        compose.runOnIdle {
            model.closeOtherTabs(model.activeTab)
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            if (model.sort != SortBy.NAME) model.changeSort(SortBy.NAME)
            if (!model.ascending) model.changeSort(SortBy.NAME)
            model.navigate(fixture.path)
            model.clearSelection()
        }
        loaded()
    }

    private fun loaded() {
        compose.waitUntil(15_000) { !model.loading && model.busy == null }
        compose.waitForIdle()
    }

    private fun key(code: Int) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun select(name: String) {
        compose.onNodeWithText(name).performClick()
        compose.waitUntil(5000) { model.focused?.name == name }
        compose.waitForIdle()
    }

    private fun right(name: String) {
        try {
            // Read View state on the UI thread and drain window-dismiss callbacks.
            compose.waitUntil(5000) {
                compose.runOnIdle { compose.activity.window.decorView.hasWindowFocus() }
            }
            compose.onNodeWithText(name).performMouseInput { click(button = MouseButton.Secondary) }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("file-context-menu").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            val automation =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand(
                        "screencap -p /sdcard/droidfinder-context-failure.png",
                    ),
                )
                .use { it.readBytes() }
            throw AssertionError(
                "Right click failed in ${model.mode}, busy=${model.busy}, selected=${model.selected.size}, windowFocus=${compose.activity.window.decorView.hasWindowFocus()}",
                error,
            )
        }
    }

    @After
    fun cleanup() {
        if (
            compose.onAllNodesWithTag("file-context-menu").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("tab-context-menu").fetchSemanticsNodes().isNotEmpty()
        )
            key(KeyEvent.KEYCODE_ESCAPE)
        if (
            compose
                .onAllNodesWithContentDescription("Close archive")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
            compose.onNodeWithContentDescription("Close archive").performClick()
        compose.runOnIdle {
            model.closeOtherTabs(model.activeTab)
            model.navigate(model.workspace.path)
            model.clearSelection()
        }
        fixture.deleteRecursively()
    }

    @Test
    fun rightClickWorksInEveryBrowsingView() {
        ViewMode.entries.forEach { mode ->
            compose.runOnIdle {
                model.changeMode(mode)
                model.clearSelection()
            }
            loaded()
            right("Notes.md")
            compose.onNodeWithText("Compress to ZIP…").assertExists()
            compose.onNodeWithText("Get info").performClick()
            compose.onNodeWithText("Full path").assertExists()
            compose.onNodeWithText("Done").performClick()
        }
    }

    @Test
    fun rightClickKeepsAnExistingMultiSelection() {
        compose.runOnIdle { model.selectAll() }
        compose.onNodeWithText("Other.txt").performMouseInput {
            click(button = MouseButton.Secondary)
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("file-context-menu").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Rename 4 items…").assertExists()
        compose.onNodeWithText("Copy").performClick()
        compose.runOnIdle { assertEquals(4, model.clipboard!!.paths.size) }
    }

    @Test
    fun backgroundContextCreatesTextAndUndoRemovesIt() {
        compose.onNodeWithTag("file-browser").performMouseInput {
            moveTo(Offset(width - 20f, height - 25f))
            click(button = MouseButton.Secondary)
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("file-context-menu").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("New text file…").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Created.txt")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(10_000) { File(fixture, "Created.txt").isFile }
        loaded()
        compose.runOnIdle { model.undo() }
        compose.waitUntil(10_000) { !File(fixture, "Created.txt").exists() }
    }

    @Test
    fun namedZipFromToolbarCanBeBrowsedAndExtracted() {
        select("Notes.md")
        compose.onNodeWithContentDescription("Compress to ZIP").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("My archive.zip")
        compose.onNodeWithText("Compress").performClick()
        compose.waitUntil(10_000) { File(fixture, "My archive.zip").isFile }
        loaded()
        select("My archive.zip")
        compose.onNodeWithContentDescription("Browse archive").performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithText("ZIP archive · read-only contents")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Extract here").performClick()
        compose.waitUntil(10_000) { File(fixture, "My archive/Notes.md").exists() }
        loaded()
        assertEquals("Desktop workflow", File(fixture, "My archive/Notes.md").readText())
        assertTrue(File(fixture, "Notes.md").exists())
    }

    @Test
    fun doubleClickZipBrowsesNestedFoldersWithoutExtracting() {
        compose.onNodeWithText("Package.zip").performTouchInput { doubleClick() }
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithText("ZIP archive · read-only contents")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Browse Folder").performClick()
        compose.onNodeWithContentDescription("Browse Folder/Subfolder").performClick()
        compose.onNodeWithText("Inside.txt").assertExists()
        assertFalse(File(fixture, "Package").exists())
    }

    @Test
    fun tabSearchHistoryReorderReopenAndPersistence() {
        val firstId = model.tabs[model.activeTab].id
        compose.runOnIdle { model.updateQuery("Notes") }
        loaded()
        compose.onNodeWithContentDescription("New tab").performClick()
        loaded()
        val secondId = model.tabs[model.activeTab].id
        compose.runOnIdle {
            model.navigate(File(fixture, "Target").path)
            model.switchTab(0)
        }
        loaded()
        assertEquals("Notes", model.query)
        assertEquals(listOf("Notes.md"), model.entries.map { it.name })
        compose.runOnIdle { model.moveTab(0, 1) }
        assertEquals(firstId, model.tabs[model.activeTab].id)
        compose.runOnIdle {
            model.closeTab(model.tabs.indexOfFirst { it.id == secondId })
            model.reopenTab()
        }
        loaded()
        assertEquals(File(fixture, "Target").path, model.location)
        compose.runOnIdle { model.duplicateTab(model.activeTab) }
        loaded()
        assertEquals(3, model.tabs.size)
        compose.runOnIdle {
            val store = ViewModelStore()
            val owner =
                object : ViewModelStoreOwner {
                    override val viewModelStore = store
                }
            try {
                val fresh =
                    ViewModelProvider(
                        owner,
                        ViewModelProvider.AndroidViewModelFactory(compose.activity.application),
                    )[FinderModel::class.java]
                assertEquals(model.tabs.map { it.id }, fresh.tabs.map { it.id })
                assertEquals(model.tabs.map { it.history }, fresh.tabs.map { it.history })
                assertEquals("Notes", fresh.tabs.first { it.id == firstId }.search)
            } finally {
                store.clear()
            }
        }
    }

    @Test
    fun middleClickFolderOpensBackgroundTabAndTabContextCanDuplicate() {
        val current = model.location
        compose.onNodeWithText("Target").performMouseInput { click(button = MouseButton.Tertiary) }
        compose.waitUntil(5000) { model.tabs.size == 2 }
        assertEquals(current, model.location)
        compose.onAllNodesWithText("Target", useUnmergedTree = true).onFirst().performMouseInput {
            click(button = MouseButton.Secondary)
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("tab-context-menu").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Duplicate tab").performClick()
        loaded()
        assertEquals(3, model.tabs.size)
        assertEquals(File(fixture, "Target").path, model.location)
    }

    @Test
    fun mouseTabReorderMiddleCloseAndKeyboardShortcuts() {
        val first = model.tabs[model.activeTab].id
        compose.runOnIdle { model.newTab(File(fixture, "Target").path, activate = false) }
        val second = model.tabs.last().id
        compose.onNodeWithTag("tab-$first").performMouseInput {
            moveTo(center)
            press(MouseButton.Primary)
            moveTo(center + Offset(width * 1.25f, 0f), delayMillis = 150)
            release(MouseButton.Primary)
        }
        compose.waitUntil(5000) { model.tabs.first().id == second }
        assertEquals(first, model.tabs[model.activeTab].id)
        compose.runOnIdle { model.closeTab(model.activeTab) }
        loaded()
        compose.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.ShiftLeft)
            pressKey(Key.T)
            keyUp(Key.ShiftLeft)
            keyUp(Key.CtrlLeft)
        }
        loaded()
        assertEquals(first, model.tabs[model.activeTab].id)
        compose.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Tab)
            keyUp(Key.CtrlLeft)
        }
        loaded()
        assertEquals(second, model.tabs[model.activeTab].id)
        compose.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Four)
            keyUp(Key.CtrlLeft)
        }
        compose.runOnIdle { assertEquals(ViewMode.GALLERY, model.mode) }
        compose.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Two)
            keyUp(Key.CtrlLeft)
        }
        compose.onNodeWithContentDescription("List view").assertIsSelected()
        compose.onNodeWithTag("tab-$first").performMouseInput {
            click(button = MouseButton.Tertiary)
        }
        compose.waitUntil(5000) { model.tabs.size == 1 }
        assertEquals(second, model.tabs[model.activeTab].id)
    }

    @Test
    fun browsingAndClipboardSurviveAnActiveProviderMove() {
        val provider =
            File(compose.activity.filesDir, "ProviderFixtures/Background-${System.nanoTime()}")
                .apply { mkdirs() }
        val source =
            File(provider, "Gate.bin").apply { writeBytes(ByteArray(3 * 1024 * 1024) { 42 }) }
        val gate = java.util.concurrent.CountDownLatch(1)
        dev.droidfinder.testing.FixtureDocumentsProvider.transferGate = gate
        dev.droidfinder.testing.FixtureDocumentsProvider.gateOpened =
            java.util.concurrent.CountDownLatch(1)
        val target = File(fixture, "Target")
        try {
            val uri =
                android.provider.DocumentsContract.buildTreeDocumentUri(
                    "dev.droidfinder.fixtures",
                    "root/${provider.name}",
                )
            compose.runOnIdle { model.connectLocation(uri, false) }
            compose.waitUntil(10_000) {
                !model.loading && model.entries.any { it.name == "Gate.bin" }
            }
            compose.runOnIdle {
                model.select(model.entries.first { it.name == "Gate.bin" })
                model.copySelection(true)
                model.navigate(target.path)
            }
            loaded()
            compose.runOnIdle { model.paste() }
            assertTrue(
                dev.droidfinder.testing.FixtureDocumentsProvider.gateOpened!!.await(
                    8,
                    java.util.concurrent.TimeUnit.SECONDS,
                ),
            )
            compose.onNodeWithText("Cancel transfer").assertExists()
            compose.onNodeWithContentDescription("New tab").performClick()
            compose.runOnIdle {
                model.closeTab(0)
                model.navigate(fixture.path)
                assertTrue(model.back())
                assertEquals(target.path, model.location)
                model.forward()
            }
            compose.waitUntil(10_000) {
                !model.loading && model.entries.any { it.name == "Notes.md" }
            }
            select("Notes.md")
            compose.onRoot().performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.C)
                keyUp(Key.CtrlLeft)
            }
            gate.countDown()
            loaded()
            assertEquals(fixture.path, model.location)
            assertEquals("Notes.md", model.focused?.name)
            assertEquals(listOf(File(fixture, "Notes.md").path), model.clipboard!!.paths)
            assertEquals(3L * 1024 * 1024, File(target, "Gate.bin").length())
            assertFalse(source.exists())
        } finally {
            gate.countDown()
            dev.droidfinder.testing.FixtureDocumentsProvider.transferGate = null
            dev.droidfinder.testing.FixtureDocumentsProvider.gateOpened = null
            compose.runOnIdle {
                model.connectedLocations
                    .firstOrNull { it.name == provider.name }
                    ?.let { model.disconnectLocation(it.uri) }
            }
            provider.deleteRecursively()
        }
    }

    @Test
    fun providerParentCacheMergesIndependentReaders() {
        val provider =
            File(compose.activity.filesDir, "ProviderFixtures/Parents-${System.nanoTime()}").apply {
                mkdirs()
            }
        try {
            val uri =
                android.provider.DocumentsContract.buildTreeDocumentUri(
                    "dev.droidfinder.fixtures",
                    "root/${provider.name}",
                )
            compose.runOnIdle { model.connectLocation(uri, false) }
            compose.waitUntil(10_000) { !model.loading && model.location.contains(provider.name) }
            val first = DocumentAccess(compose.activity.application)
            val existing = first.create(model.location, "Existing", true)
            val stale = DocumentAccess(compose.activity.application)
            val added = first.create(model.location, "New parent", true)
            stale.list(existing)
            val fresh = DocumentAccess(compose.activity.application)
            assertEquals(model.location, fresh.parent(added))
        } finally {
            compose.runOnIdle {
                model.connectedLocations
                    .firstOrNull { it.name == provider.name }
                    ?.let { model.disconnectLocation(it.uri) }
            }
            provider.deleteRecursively()
        }
    }
}
