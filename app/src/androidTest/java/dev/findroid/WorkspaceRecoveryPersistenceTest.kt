package dev.findroid

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Faults use an isolated window/store so an intentionally damaged catalog cannot poison others. */
class WorkspaceRecoveryPersistenceTest {
    private fun pane(path: String) = PaneSnapshot(listOf(FinderTab(listOf(path))), 0, ViewMode.LIST, 0, SortBy.NAME, true, GroupBy.NONE, false, true, false, false, 120, 240, 280)
    private class Disk(var values: List<SavedWorkspace>) : WorkspacePersistence {
        var external: WorkspacePersistence? = null
        override val error get() = external?.error
        var fail = false
        var entered: CountDownLatch? = null
        var release: CountDownLatch? = null
        override fun load() = external?.load() ?: values
        override fun write(values: List<SavedWorkspace>) {
            entered?.countDown(); release?.let { check(it.await(8, TimeUnit.SECONDS)) }
            check(!fail) { "Synthetic workspace disk failure" }
            this.values = values
        }
    }
    private fun exercise(body: suspend (FinderModel, Disk, SavedWorkspace, File, ViewModelStore) -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as FinderApplication
        val id = "workspace-repair-${System.nanoTime()}"
        val base = File(app.filesDir, "Workspace/$id").canonicalFile.apply { mkdirs() }
        val replacement = File(base, "Replacement").apply { mkdir() }
        val saved = SavedWorkspace("writing", "Writing layout", pane(File(base, "Missing").path), pane(base.path), true, false, .5f, "primary")
        val disk = Disk(listOf(saved))
        val owner = ViewModelStore()
        lateinit var model: FinderModel
        try {
            instrumentation.runOnMainSync {
                model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(type: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return FinderModel(app, FinderSession(app, disk), "primary", FinderWindowState(app, id)) as T
                    }
                })[FinderModel::class.java]
                model.openWorkspace(saved.id)
            }
            withTimeout(10_000) { while (model.workspaceRecovery.working) delay(10) }
            instrumentation.runOnMainSync {
                model.workspaceRecovery.replacement(saved.primary.tabs.single().location, replacement.path)
                model.workspaceRecovery.prepareReview()
            }
            withTimeout(10_000) { while (model.workspaceRecovery.working) delay(10) }
            assertNull(model.workspaceRecovery.error); assertNotNull(model.workspaceRecovery.review)
            body(model, disk, saved, replacement, owner)
        } finally {
            disk.release?.countDown()
            withTimeout(10_000) { while (model.workspaceSaving) delay(10) }
            instrumentation.runOnMainSync { owner.clear() }
            listOf("finder-window-$id", "finder-window-$id-primary", "finder-window-$id-secondary").forEach(app::deleteSharedPreferences)
            base.deleteRecursively()
        }
    }
    private suspend fun finished(model: FinderModel) = withTimeout(10_000) {
        while (model.workspaceRecovery.working || model.workspaceRecovery.applying) delay(10)
    }
    @Test fun failedPublicationKeepsTheOriginalLibraryAndStillAllowsOpenOnce() = exercise { model, disk, saved, replacement, _ ->
        disk.fail = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model.workspaceRecovery.apply(true) }
        finished(model)
        assertTrue(model.workspaceRecovery.error.orEmpty().contains("disk failure"))
        assertEquals(listOf(saved), disk.values); assertEquals(listOf(saved), model.savedWorkspaces)
        assertNull(model.busy); assertFalse(model.session.workspaceRepairing.value)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model.workspaceRecovery.apply(false) }
        finished(model)
        assertFalse(model.workspaceRecovery.visible); assertEquals(replacement.path, model.location)
        assertEquals(listOf(saved), disk.values)
    }
    @Test fun anExternallyChangedLibraryIsKeptWhenReviewIsStale() = exercise { model, disk, saved, _, _ ->
        val changed = saved.copy(name = "Changed outside the review")
        disk.values = listOf(changed)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model.workspaceRecovery.apply(true) }
        finished(model)
        assertTrue(model.workspaceRecovery.error.orEmpty().contains("changed on disk"))
        assertEquals(listOf(changed), disk.values)
        assertEquals(listOf(saved), model.savedWorkspaces)
        assertTrue(model.workspaceRecovery.visible)
    }
    @Test fun aPendingRepairBlocksOtherWorkspaceEditsUntilAtomicPublicationFinishes() = exercise { model, disk, saved, replacement, _ ->
        disk.entered = CountDownLatch(1); disk.release = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { model.workspaceRecovery.apply(true) }
        assertTrue(disk.entered!!.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            assertFalse(model.saveWorkspace("Must not race"))
            assertFalse(model.renameWorkspace(saved.id, "Must not rename"))
            model.removeWorkspace(saved.id); model.retryWorkspaceSave()
            assertEquals(listOf(saved), model.savedWorkspaces)
            model.openWorkspace(saved.id)
            assertTrue(model.workspaceRecovery.applying)
            assertEquals("Wait for the workspace repairs to finish saving.", model.message)
            assertTrue(model.workspaceSaving)
        }
        disk.release!!.countDown(); finished(model)
        assertEquals(replacement.path, disk.values.single().primary.tabs.single().location)
        assertEquals(disk.values, model.savedWorkspaces)
        assertFalse(model.session.workspaceRepairing.value)
    }
    @Test fun aFutureWorkspaceCatalogIsPreservedAndCannotBeReplacedByRepairs() = exercise { model, disk, saved, replacement, _ ->
        val file = File(replacement.parentFile, "future-workspaces.json")
        val bytes = """{"version":99,"workspaces":[]}""".toByteArray()
        file.writeBytes(bytes)
        disk.external = SavedWorkspaceStore(file)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model.workspaceRecovery.apply(true) }
        finished(model)
        assertNotNull(model.workspaceRecovery.error)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf(saved), model.savedWorkspaces)
        assertTrue(model.workspaceRecovery.visible)
    }
    @Test fun aClosedPrimaryKeepsItsControllerReadableForOutgoingComposition() = exercise { model, _, _, _, owner ->
        val controller = model.workspaceRecovery
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            assertSame(controller, model.secondaryPane.workspaceRecovery)
            owner.clear()
            assertTrue(model.windowState.panes.isEmpty())
            assertSame(controller, model.workspaceRecovery)
        }
    }
    @Test fun closingTheWindowDuringPublicationFinishesSavingWithoutRecreatingItsPanes() = exercise { model, disk, _, replacement, owner ->
        disk.entered = CountDownLatch(1); disk.release = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { model.workspaceRecovery.apply(true) }
        assertTrue(disk.entered!!.await(5, TimeUnit.SECONDS))
        val controller = model.workspaceRecovery
        instrumentation.runOnMainSync { owner.clear() }
        disk.release!!.countDown()
        withTimeout(10_000) { while (controller.working || controller.applying) delay(10) }
        assertEquals(replacement.path, disk.values.single().primary.tabs.single().location)
        assertTrue(model.windowState.panes.isEmpty())
        assertNull(model.session.busy.value)
    }
}
