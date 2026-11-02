@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** Run unchanged at phone and tablet sizes. Mouse injection uses actual pointer-source events. */
class InputWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var folder: File
    private lateinit var monitor: Instrumentation.ActivityMonitor
    private val phone
        get() =
            compose.activity.resources.configuration.let {
                it.screenWidthDp < 600 || it.screenHeightDp < 480
            }

    @Before
    fun setup() {
        folder =
            File(model.workspace, "Input QA-${System.nanoTime()}").canonicalFile.apply { mkdirs() }
        File(folder, "Trips/Nested").mkdirs()
        File(folder, "Trips/Itinerary.txt").writeText("Synthetic itinerary")
        File(folder, "Report.txt").writeText("Synthetic project report")
        File(folder, "Budget.txt").writeText("Synthetic budget")
        val filter =
            IntentFilter(Intent.ACTION_VIEW).apply {
                addDataScheme("content")
                addDataType("*/*")
            }
        monitor =
            InstrumentationRegistry.getInstrumentation()
                .addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, null), true)
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            model.windowState.peekEntry.value = null
            model.changeMode(ViewMode.LIST)
            model.changeDensity(FinderDensity.COMFORTABLE)
            model.changeGrouping(GroupBy.NONE)
            if (model.sort != SortBy.NAME) model.changeSort(SortBy.NAME)
            if (!model.ascending) model.changeSort(SortBy.NAME)
            model.navigate(folder.path)
            model.updateSearchRules(SearchRules())
            model.windowState.fileClicks.reset()
            model.session.clipboard.value = null
        }
        loaded()
    }

    @After
    fun cleanup() {
        InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
        compose.runOnIdle {
            model.windowState.peekEntry.value = null
            model.navigate(model.workspace.path)
            model.session.clipboard.value = null
        }
        loaded()
        folder.deleteRecursively()
    }

    private fun loaded() {
        compose.waitUntil(20_000) { !model.loading && model.busy == null }
        compose.waitForIdle()
    }

    private fun file(name: String): SemanticsNodeInteraction {
        if (model.mode == ViewMode.LIST) {
            val tag =
                if (phone) "browser-phone-list"
                else if (model.windowsLayout == 8) "browser-content" else "browser-list"
            compose.onNodeWithTag(tag).performScrollToNode(hasText(name))
        }
        val node =
            compose
                .onAllNodes(
                    hasText(name) and
                        hasClickAction() and
                        hasAnyAncestor(hasTestTag("file-browser")),
                )
                .onFirst()
        if (model.mode == ViewMode.LIST || model.mode == ViewMode.COLUMNS) node.performScrollTo()
        return node
    }

    private fun reset(mode: ViewMode = ViewMode.LIST) {
        compose.runOnIdle {
            model.navigate(folder.path)
            model.changeMode(mode)
            model.clearSelection()
            model.windowState.fileClicks.reset()
        }
        loaded()
    }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        loaded()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300, 5000)
        android.os.SystemClock.sleep(200)
        val directory =
            File(compose.activity.getExternalFilesDir(null), "phone-qa").apply { mkdirs() }
        val bitmap = automation.takeScreenshot()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun oneTouchOpensFoldersInEveryView() {
        for (mode in ViewMode.entries) {
            reset(mode)
            file("Trips").performTouchInput { click() }
            loaded()
            assertEquals("Touch in $mode", File(folder, "Trips").path, model.location)
        }
    }

    @Test
    fun oneTouchOpensFilesThroughAndroidInEveryView() {
        for (mode in ViewMode.entries) {
            reset(mode)
            val before = monitor.hits
            file("Report.txt").performTouchInput { click() }
            compose.waitUntil(5000) { monitor.hits > before }
            assertEquals("One open in $mode", before + 1, monitor.hits)
        }
    }

    @Test
    fun mouseSingleClickSelectsAndDoubleClickOpensInEveryView() {
        for (mode in ViewMode.entries) {
            reset(mode)
            val before = monitor.hits
            file("Report.txt").performMouseInput { click() }
            compose.waitForIdle()
            assertEquals(setOf(File(folder, "Report.txt").path), model.selected)
            assertEquals(before, monitor.hits)
            file("Report.txt").performMouseInput {
                advanceEventTime(100)
                click()
            }
            compose.waitUntil(5000) { monitor.hits == before + 1 }
            reset(mode)
            file("Trips").performMouseInput { click() }
            assertEquals(folder.path, model.location)
            assertTrue(File(folder, "Trips").path in model.selected)
            file("Trips").performMouseInput {
                advanceEventTime(100)
                click()
            }
            loaded()
            assertEquals(File(folder, "Trips").path, model.location)
        }
    }

    @Test
    fun touchAfterMouseSelectionStillOpensAndLongPressEntersSelectionMode() {
        file("Report.txt").performMouseInput { click() }
        file("Trips").performTouchInput { click() }
        loaded()
        assertEquals(File(folder, "Trips").path, model.location)
        reset()
        file("Report.txt").performTouchInput { longClick() }
        assertTrue(model.multiSelecting)
        file("Budget.txt").performTouchInput { click() }
        assertEquals(2, model.selected.size)
        assertEquals(0, monitor.hits)
        file("Report.txt").performTouchInput { click() }
        assertEquals(setOf(File(folder, "Budget.txt").path), model.selected)
        back()
        assertTrue(model.selected.isEmpty())
        assertEquals(folder.path, model.location)
    }

    @Test
    fun scrollingAndCancelledTouchDoNotOpenFiles() {
        repeat(50) { File(folder, "Document-%02d.txt".format(it)).writeText("Synthetic notes") }
        compose.runOnIdle { model.refresh() }
        loaded()
        compose.onNodeWithTag("file-browser").performTouchInput { swipeUp() }
        assertEquals(folder.path, model.location)
        assertEquals(0, monitor.hits)
        assertTrue(model.selected.isEmpty())
    }

    @Test
    fun fileActionsDoNotAccidentallyOpenTheFile() {
        file("Report.txt")
        compose.onNodeWithContentDescription("Actions for Report.txt").performTouchInput { click() }
        compose.onNodeWithTag("file-context-menu").assertExists()
        assertEquals(0, monitor.hits)
        compose.onNodeWithText("Get info").performScrollTo().performClick()
        compose.onAllNodesWithText("Report.txt", useUnmergedTree = true).onLast().assertExists()
    }

    @Test
    fun controlAndShiftMouseSelectionNeverActivateAFile() {
        file("Budget.txt").performMouseInput { click() }
        compose.onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
        file("Report.txt").performMouseInput { click() }
        compose.onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
        assertEquals(2, model.selected.size)
        assertEquals(0, monitor.hits)
        file("Trips").performMouseInput { click() }
        assertEquals(setOf(File(folder, "Trips").path), model.selected)
        assertEquals(folder.path, model.location)
        compose.onRoot().performKeyInput { keyDown(Key.ShiftLeft) }
        file("Report.txt").performMouseInput { click() }
        compose.onRoot().performKeyInput { keyUp(Key.ShiftLeft) }
        assertEquals(3, model.selected.size)
        assertEquals(0, monitor.hits)
    }

    @Test
    fun phoneLocationsSelectionCopyPasteAndViewControlsStayReachable() {
        Assume.assumeTrue(phone)
        capture("phone-files")
        file("Report.txt").performTouchInput { longClick() }
        capture("phone-selection")
        compose.onNodeWithText("Copy", useUnmergedTree = true).performClick()
        assertTrue(model.selected.isEmpty())
        file("Trips").performTouchInput { click() }
        loaded()
        compose.onNodeWithText("Paste", useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { File(folder, "Trips/Report.txt").exists() }
        loaded()
        assertEquals("Synthetic project report", File(folder, "Trips/Report.txt").readText())
        compose.onNodeWithText("View", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("phone-view-ICONS").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("browser-grid").assertExists()
        capture("phone-icons")
        compose.onNodeWithText("Locations", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("phone-locations").assertExists()
        capture("phone-locations")
        compose
            .onNode(
                hasText("Workspace") and hasAnyAncestor(hasTestTag("phone-locations")),
                useUnmergedTree = true,
            )
            .performScrollTo()
            .performClick()
        loaded()
        assertEquals(model.workspace.path, model.location)
        compose.onNodeWithTag("phone-locations").assertDoesNotExist()
    }

    @Test
    fun phoneCreateRenameTrashAndRestoreThroughVisibleActions() {
        Assume.assumeTrue(phone)
        compose.onNodeWithText("New", useUnmergedTree = true).performClick()
        compose.onNodeWithText("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Created on phone")
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { File(folder, "Created on phone").exists() }
        loaded()
        file("Report.txt").performTouchInput { longClick() }
        compose.onNodeWithText("More", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Rename…").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Renamed.txt")
        compose.onNodeWithText("Rename", useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { File(folder, "Renamed.txt").exists() }
        loaded()
        compose.runOnIdle { model.clearSelection() }
        file("Renamed.txt").performTouchInput { longClick() }
        compose.onNodeWithText("Trash", useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { !File(folder, "Renamed.txt").exists() }
        loaded()
        compose.onNodeWithText("Locations", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Trash", useUnmergedTree = true).performClick()
        loaded()
        val item =
            model.entries.single {
                model.engine.originalPath(it.file) == File(folder, "Renamed.txt").path
            }
        compose.runOnIdle {
            model.clearSelection()
            model.select(item, multiple = true)
        }
        compose.onNodeWithText("Restore", useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { File(folder, "Renamed.txt").exists() }
    }

    @Test
    fun phoneSearchBackAndRecreationKeepNavigationPredictable() {
        Assume.assumeTrue(phone)
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Search files").performTextInput("Itinerary")
        loaded()
        assertEquals(listOf("Itinerary.txt"), model.entries.map { it.name })
        compose.activityRule.scenario.recreate()
        loaded()
        assertEquals("Itinerary", model.query)
        compose.onNodeWithContentDescription("Search filters").performClick()
        compose.onNodeWithText("Search criteria").assertIsDisplayed()
        compose.onNodeWithText("Apply filters").assertIsDisplayed()
        capture("phone-search-criteria")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Close search").performClick()
        loaded()
        assertEquals(folder.path, model.location)
        assertEquals(3, model.entries.size)
    }

    @Test
    fun phonePeekAndTabOverviewSurviveActivityRecreation() {
        Assume.assumeTrue(phone)
        file("Report.txt").performTouchInput { longClick() }
        compose.onNodeWithText("More", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Quick Look").performClick()
        compose.onNodeWithTag("peek-window").assertIsDisplayed()
        capture("phone-peek")
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
        compose.runOnIdle { model.clearSelection() }
        val original = model.tabs[model.activeTab].id
        compose.onNodeWithText("New", useUnmergedTree = true).performClick()
        compose.onNodeWithText("New tab").performClick()
        loaded()
        file("Trips").performTouchInput { click() }
        loaded()
        val created = model.tabs[model.activeTab].id
        compose.activityRule.scenario.recreate()
        loaded()
        assertEquals(created, model.tabs[model.activeTab].id)
        compose.runOnIdle { model.tabOverviewRequested = true }
        compose.onNodeWithTag("tab-overview").assertIsDisplayed()
        capture("phone-tabs")
        compose
            .onNodeWithTag("overview-$original")
            .onChildren()
            .filter(hasClickAction())
            .onFirst()
            .performClick()
        loaded()
        assertEquals(original, model.tabs[model.activeTab].id)
        assertEquals(folder.path, model.location)
        compose.runOnIdle { model.closeTab(model.tabs.indexOfFirst { it.id == created }) }
    }

    @Test
    fun mouseSourceWithFingerToolIsTreatedAsACursorNotATouchTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun tap() {
            val point = file("Report.txt").fetchSemanticsNode().boundsInWindow.center
            val properties =
                arrayOf(
                    android.view.MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
                    },
                )
            val coords =
                arrayOf(
                    android.view.MotionEvent.PointerCoords().apply {
                        x = point.x
                        y = point.y
                        pressure = 1f
                        size = 1f
                    },
                )
            val down = android.os.SystemClock.uptimeMillis()
            for (action in
                listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event =
                    android.view.MotionEvent.obtain(
                        down,
                        android.os.SystemClock.uptimeMillis(),
                        action,
                        1,
                        properties,
                        coords,
                        0,
                        if (action == android.view.MotionEvent.ACTION_DOWN)
                            android.view.MotionEvent.BUTTON_PRIMARY
                        else 0,
                        1f,
                        1f,
                        0,
                        0,
                        android.view.InputDevice.SOURCE_MOUSE,
                        0,
                    )
                try {
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
                instrumentation.waitForIdleSync()
                compose.waitForIdle()
            }
        }
        tap()
        assertEquals(setOf(File(folder, "Report.txt").path), model.selected)
        assertEquals(0, monitor.hits)
        // Switch back to an actual touch immediately; the last mouse source must not stick.
        file("Trips").performTouchInput { click() }
        loaded()
        assertEquals(File(folder, "Trips").path, model.location)
    }

    @Test
    fun providerFoldersUseTheSamePerGestureActivation() {
        val provider = File(compose.activity.filesDir, "ProviderFixtures/${folder.name}")
        folder.copyRecursively(provider)
        var root: String? = null
        try {
            compose.runOnIdle {
                model.connectLocation(
                    android.provider.DocumentsContract.buildTreeDocumentUri(
                        "dev.findroid.fixtures",
                        "root/${folder.name}",
                    ),
                    false,
                )
            }
            compose.waitUntil(15_000) {
                model.location.startsWith("content://") &&
                    !model.loading &&
                    model.entries.any { it.name == "Trips" }
            }
            loaded()
            root = model.location
            assertTrue(root.startsWith("content://"))
            file("Trips").performMouseInput { click() }
            assertEquals(root, model.location)
            assertEquals("Trips", model.focused?.name)
            file("Trips").performTouchInput { click() }
            loaded()
            assertNotEquals(root, model.location)
            val before = monitor.hits
            file("Itinerary.txt").performTouchInput { click() }
            compose.waitUntil(5000) { monitor.hits == before + 1 }
        } finally {
            compose.runOnIdle {
                model.navigate(folder.path)
                root?.let { model.documents.disconnect(it) }
                model.session.connectedLocations.value = model.documents.locations
            }
            loaded()
            provider.deleteRecursively()
        }
    }

    @Test
    fun clickingFileGlyphsUsesTheSameRulesAsFileNames() {
        file("Report.txt")
        val glyph =
            compose.onNodeWithTag(
                "drag-icon-${File(folder, "Report.txt").path}",
                useUnmergedTree = true,
            )
        glyph.performMouseInput { click() }
        assertEquals(setOf(File(folder, "Report.txt").path), model.selected)
        assertEquals(0, monitor.hits)
        glyph.performTouchInput { click() }
        compose.waitUntil(5000) { monitor.hits == 1 }
        assertTrue(model.selected.isEmpty())
        assertEquals("Report.txt", model.focused?.name)
    }

    @Test
    fun mouseSelectionInChildColumnsDoesNotNavigateOrLoseTheSelectedEntry() {
        reset(ViewMode.COLUMNS)
        file("Trips").performMouseInput { click() }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Itinerary.txt").fetchSemanticsNodes().isNotEmpty()
        }
        // Reveal the next column on a narrow window before pointing at it.
        if (phone) compose.onNodeWithTag("file-browser").performTouchInput { swipeLeft() }
        file("Itinerary.txt").performMouseInput { click() }
        assertEquals(folder.path, model.location)
        assertEquals("Itinerary.txt", model.selectedEntries.single().name)
        assertEquals(0, monitor.hits)
        file("Itinerary.txt").performMouseInput {
            advanceEventTime(100)
            click()
        }
        compose.waitUntil(5000) { monitor.hits == 1 }
    }

    @Test
    fun archiveEntriesHonorTouchAndMouseActivationAndKeepExtractionReachable() {
        val zip = File(folder, "Sample.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(java.util.zip.ZipEntry("Docs/Note.txt"))
            it.write("Synthetic archive note".toByteArray())
            it.closeEntry()
        }
        compose.runOnIdle { model.refresh() }
        loaded()
        file("Sample.zip").performMouseInput { click() }
        compose.onNodeWithTag("archive-browser").assertDoesNotExist()
        file("Sample.zip").performMouseInput {
            advanceEventTime(100)
            click()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Docs").fetchSemanticsNodes().isNotEmpty()
        }
        fun member(name: String) =
            compose.onNode(
                hasText(name) and hasClickAction() and hasAnyAncestor(hasTestTag("archive-items")),
            )
        member("Docs").performMouseInput { click() }
        compose.onNodeWithText("Note.txt").assertDoesNotExist()
        member("Docs").performMouseInput {
            advanceEventTime(100)
            click()
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Note.txt").fetchSemanticsNodes().isNotEmpty()
        }
        member("Note.txt").performMouseInput { click() }
        compose.onNodeWithTag("peek-window").assertDoesNotExist()
        member("Note.txt").performMouseInput {
            advanceEventTime(100)
            click()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
        compose.onNodeWithContentDescription("Parent archive folder").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Docs").fetchSemanticsNodes().isNotEmpty()
        }
        member("Docs").performTouchInput { click() }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Note.txt").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Extract to…").assertIsDisplayed()
        compose.onNodeWithText("Extract here").assertIsDisplayed()
        capture("phone-archive")
        member("Note.txt").performTouchInput { click() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
        compose.onNodeWithContentDescription("Close archive").performClick()
    }
}
