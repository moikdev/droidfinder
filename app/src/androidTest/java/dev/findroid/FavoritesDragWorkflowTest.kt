@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** Real native input verifies pinning separately from the sidebar's transfer destinations. */
class FavoritesDragWorkflowTest {
    @org.junit.Before
    fun resetOwnedUndoSessionForFixture() {
        compose.runOnIdle { resetOwnedUndoFixture(compose.activity.model) }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private lateinit var base: File
    private lateinit var projects: File
    private lateinit var design: File
    private lateinit var research: File
    private lateinit var archive: File
    private lateinit var note: File
    private lateinit var savedFavorites: Set<String>
    private var native: DragWorkflowTest.NativeDrag? = null
    private var providerRoot: String? = null
    private var fixture: File? = null

    @Before
    fun setup() {
        base = File(compose.activity.filesDir, "Workspace/Favorites-${System.nanoTime()}").apply { mkdirs() }
        projects = File(base, "Projects").apply { mkdir() }
        design = File(projects, "Design project").apply { mkdir() }
        File(design, "Brief.txt").writeText("Keep this project in its original folder.")
        research = File(projects, "Research notes").apply { mkdir() }
        archive = File(projects, "Archive").apply { mkdir() }
        note = File(projects, "Notes.txt").apply { writeText("sidebar transfer payload") }
        compose.runOnIdle {
            savedFavorites = model.favorites.toSet()
            if (model.dualPane) model.toggleDualPane()
            model.activatePane()
            if (!model.sidebarVisible) model.toggleSidebar()
            model.closeOtherTabs(model.activeTab)
            model.navigate(projects.path)
            model.updateQuery("")
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            if (model.sort != SortBy.NAME || !model.ascending) model.changeSort(SortBy.NAME)
        }
        loaded()
    }

    @After
    fun cleanup() {
        native?.cancel()
        compose.waitForIdle()
        compose.runOnIdle {
            (model.favorites - savedFavorites).forEach(model::favoritePath)
            (savedFavorites - model.favorites).forEach(model::favoritePath)
            model.navigate(model.workspace.path)
            providerRoot?.let(model::disconnectLocation)
            model.folderViews.values.keys.filter { pathWithin(it, folderViewKey(base.path)!!) }
                .forEach(model::forgetFolderView)
        }
        loaded()
        base.deleteRecursively()
        fixture?.deleteRecursively()
    }

    private fun loaded() {
        compose.waitUntil(15_000) { !model.loading && model.busy == null }
        compose.waitForIdle()
    }

    private fun start(
        paths: List<String> = listOf(design.path),
        mouse: Boolean = false,
        copy: Boolean = false,
        handle: Boolean = false,
    ): DragWorkflowTest.NativeDrag {
        compose.runOnIdle {
            model.clearSelection()
            paths.forEach { path -> model.select(model.entries.first { it.path == path }, multiple = true) }
        }
        compose.waitForIdle()
        val source = if (handle) compose.onNodeWithTag("drag-selection")
            else compose.onNodeWithTag("drag-icon-${paths.first()}", useUnmergedTree = true)
        return DragWorkflowTest.NativeDrag(
            source.fetchSemanticsNode().boundsInWindow.center, mouse, copy,
            { compose.waitForIdle() }, { compose.mainClock.advanceTimeBy(700) },
        ).also { native = it; it.start() }
    }

    private fun pinPoint(): Offset {
        compose.onNodeWithTag("favorites-drop-target").performScrollTo()
        return compose.onNodeWithTag("favorites-drop-target").fetchSemanticsNode().boundsInWindow.center
    }

    private fun hover(drag: DragWorkflowTest.NativeDrag) {
        drag.move(pinPoint())
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Release to add").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Add to Favorites").assertExists()
    }

    private fun screenshot(name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    @Test
    fun touchPinsFolderWithoutMovingItAndDuplicateDropKeepsItSaved() {
        val jobs = model.transferJobs.size
        val drag = start()
        hover(drag)
        SystemClock.sleep(1100)
        compose.waitForIdle()
        assertEquals(projects.path, model.location)
        screenshot("favorites-drag-hover.png")
        drag.drop()
        compose.waitUntil(5000) { design.path in model.favorites }
        compose.onNodeWithTag("favorite-${design.path}").assertExists()
        assertEquals("Keep this project in its original folder.", File(design, "Brief.txt").readText())
        assertEquals(jobs, model.transferJobs.size)
        assertTrue(design.path in compose.activity.getSharedPreferences("finder", 0)
            .getStringSet("favorites", emptySet())!!)
        screenshot("favorites-drag-saved.png")

        val again = start()
        hover(again)
        again.drop()
        compose.waitUntil(5000) { model.message == "Already in Favorites" }
        assertEquals(1, model.favorites.count { it == design.path })
        assertEquals(jobs, model.transferJobs.size)
    }

    @Test
    fun mousePinsMultipleFoldersAndRestoresSpringLoadedLocation() {
        val jobs = model.transferJobs.size
        val drag = start(listOf(design.path, research.path), mouse = true, copy = true)
        val destination = compose.onNode(hasText(archive.name) and hasClickAction() and
            hasAnyAncestor(hasTestTag("file-browser"))).fetchSemanticsNode().boundsInWindow.center
        drag.move(destination)
        compose.waitUntil(10_000) { model.location == archive.path && !model.loading }
        hover(drag)
        drag.drop()
        compose.waitUntil(10_000) {
            model.favorites.containsAll(listOf(design.path, research.path)) && model.location == projects.path
        }
        loaded()
        assertTrue(design.isDirectory && research.isDirectory)
        assertTrue(archive.listFiles()!!.isEmpty())
        assertEquals(jobs, model.transferJobs.size)
    }

    @Test
    fun builtInFavoritesDoNotGetDuplicateSavedShortcuts() {
        compose.runOnIdle {
            model.addFavoritePaths(listOf(model.workspace.path, File(model.deviceRoot, "Download").path))
        }
        assertEquals(savedFavorites, model.favorites)
        assertEquals("Already in Favorites", model.message)
    }

    @Test
    fun parentAliasDoesNotCreateADuplicateFavorite() {
        val alias = "/data/data/dev.findroid/files/" + design.path.substringAfter("/files/")
        compose.runOnIdle { model.favoritePath(alias) }
        val before = model.favorites
        val drag = start()
        hover(drag)
        drag.drop()
        compose.waitUntil(5000) { model.message == "Already in Favorites" }
        assertEquals(before, model.favorites)
        assertTrue(design.exists())
    }

    @Test
    fun namedDragHandlePinsSeveralFolders() {
        val drag = start(listOf(design.path, research.path), handle = true)
        hover(drag)
        drag.drop()
        compose.waitUntil(5000) { model.favorites.containsAll(listOf(design.path, research.path)) }
        assertTrue(design.isDirectory && research.isDirectory)
    }

    @Test
    fun fileDropIsRejectedInsteadOfCreatingOrMovingAnything() {
        val jobs = model.transferJobs.size
        val drag = start(listOf(note.path))
        drag.move(pinPoint())
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Only folders can be added to Favorites").fetchSemanticsNodes().isNotEmpty()
        }
        drag.drop()
        compose.waitForIdle()
        assertEquals(savedFavorites, model.favorites)
        assertEquals("sidebar transfer payload", note.readText())
        assertEquals(jobs, model.transferJobs.size)
    }

    @Test
    fun mixedSelectionIsRejectedAsAWhole() {
        val drag = start(listOf(design.path, note.path), handle = true)
        drag.move(pinPoint())
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Only folders can be added to Favorites").fetchSemanticsNodes().isNotEmpty()
        }
        drag.drop()
        compose.waitForIdle()
        assertEquals(savedFavorites, model.favorites)
        assertTrue(design.isDirectory && note.isFile)
    }

    @Test
    fun cancellationDoesNotAddFavorites() {
        val drag = start()
        hover(drag)
        assertEquals(savedFavorites, model.favorites)
        drag.cancel()
        compose.waitForIdle()
        assertEquals(savedFavorites, model.favorites)
        // Native cancellation can finish after Compose goes idle (notably API 36).
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("drag-feedback").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(design.exists())
    }

    @Test
    fun disappearingFolderIsRevalidatedBeforeCommit() {
        val drag = start()
        hover(drag)
        design.deleteRecursively()
        drag.drop()
        compose.waitUntil(5000) { model.message?.contains("no longer available") == true }
        assertEquals(savedFavorites, model.favorites)
    }

    @Test
    fun readOnlyConnectedFolderCanBePinnedAndOpened() {
        fixture = File(compose.activity.filesDir, "ProviderFixtures/Favorites-${System.nanoTime()}")
            .apply { mkdirs(); File(this, "readonly").mkdir() }
        val tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${fixture!!.name}")
        compose.runOnIdle { model.connectLocation(tree, false) }
        compose.waitUntil(10_000) { !model.loading && model.entries.any { it.name == "readonly" } }
        providerRoot = model.location
        val folder = model.entries.single { it.name == "readonly" }
        assertFalse(folder.writable)
        val drag = start(listOf(folder.path))
        hover(drag)
        drag.drop()
        compose.waitUntil(5000) { folder.path in model.favorites }
        assertTrue(File(fixture, "readonly").isDirectory)
        compose.onNodeWithTag("favorite-${folder.path}").performClick()
        compose.waitUntil(10_000) { model.location == folder.path && !model.loading }
    }

    @Test
    fun droppingAFileOntoAnExistingFavoriteStillMovesAndUndoes() {
        compose.runOnIdle { model.favoritePath(archive.path) }
        val jobs = model.transferJobs.size
        val drag = start(listOf(note.path))
        val favorite = compose.onNodeWithTag("favorite-${archive.path}").performScrollTo()
        drag.move(favorite.fetchSemanticsNode().boundsInWindow.center)
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Move 1 item to Archive").fetchSemanticsNodes().isNotEmpty()
        }
        drag.drop()
        val moved = File(archive, note.name)
        compose.waitUntil(15_000) { moved.isFile && !note.exists() && model.busy == null }
        assertEquals("sidebar transfer payload", moved.readText())
        assertTrue(archive.path in model.favorites)
        assertEquals(jobs + 1, model.transferJobs.size)
        compose.runOnIdle { model.undo() }
        compose.waitUntil(15_000) { note.isFile && !moved.exists() && model.busy == null }
    }
}
