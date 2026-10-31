package dev.droidfinder

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSaveFailureTest {
    @Test
    fun failedSaveIsReportedAndRetryWritesThePreservedLayout() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as FinderApplication
        val id = "save-test-${java.util.UUID.randomUUID()}"
        var fail = true
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var written = emptyList<SavedWorkspace>()
        val disk =
            object : WorkspacePersistence {
                override val error: String? = null

                override fun load() = emptyList<SavedWorkspace>()

                override fun write(values: List<SavedWorkspace>) {
                    if (fail) {
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw java.io.IOException("Simulated workspace storage failure")
                    }
                    written = values
                }
            }
        val store = ViewModelStore()
        lateinit var model: FinderModel
        try {
            instrumentation.runOnMainSync {
                model =
                    ViewModelProvider(
                        store,
                        object : ViewModelProvider.Factory {
                            override fun <T : androidx.lifecycle.ViewModel> create(
                                modelClass: Class<T>,
                            ): T {
                                @Suppress("UNCHECKED_CAST")
                                return FinderModel(
                                    app,
                                    FinderSession(app, disk),
                                    "primary",
                                    FinderWindowState(app, id),
                                )
                                    as T
                            }
                        },
                    )[FinderModel::class.java]
                assertTrue(model.saveWorkspace("Studio"))
                assertEquals("Saving Studio…", model.message)
                assertTrue(model.workspaceSaving)
            }
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            withTimeout(5000) { while (model.workspaceSaving) delay(10) }
            assertEquals("Simulated workspace storage failure", model.workspaceError)
            assertEquals("Simulated workspace storage failure", model.message)
            assertTrue(written.isEmpty())
            assertEquals("Studio", model.savedWorkspaces.single().name)
            fail = false
            instrumentation.runOnMainSync { model.retryWorkspaceSave() }
            withTimeout(5000) { while (model.workspaceSaving) delay(10) }
            assertNull(model.workspaceError)
            assertEquals("Studio", written.single().name)
            assertEquals("Workspaces saved", model.message)
        } finally {
            release.countDown()
            withTimeout(5000) { while (model.workspaceSaving) delay(10) }
            instrumentation.runOnMainSync { store.clear() }
            listOf("finder-window-$id", "finder-window-$id-primary", "finder-window-$id-secondary")
                .forEach(app::deleteSharedPreferences)
        }
    }
}
