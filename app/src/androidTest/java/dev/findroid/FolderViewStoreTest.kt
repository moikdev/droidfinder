package dev.findroid

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FolderViewStoreTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun atomicRoundTripRetainsAllViewAndColumnChoices() {
        val file = File(instrumentation.targetContext.cacheDir, "views-${System.nanoTime()}.json")
        val view =
            FolderView(
                ViewMode.COLUMNS,
                0,
                SortBy.SIZE,
                false,
                GroupBy.KIND,
                true,
                false,
                false,
                true,
                180,
                390,
                400,
                true,
                DetailColumns(false, true, true, 220, 140, 200, false),
            )
        try {
            val store = FolderViewStore(file)
            store.write(mapOf("/a" to view))
            assertEquals(mapOf("/a" to view), FolderViewStore(file).load())
            val parsed =
                FolderViewJson.view(
                    org.json.JSONObject(
                        """{"icons":999,"column":-1,"inspector":999,"columns":{"sizeWidth":-20,"kindWidth":999}}""",
                    ),
                )
            assertEquals(200, parsed.icons)
            assertEquals(150, parsed.column)
            assertEquals(480, parsed.inspector)
            assertEquals(60, parsed.columns.sizeWidth)
            assertEquals(260, parsed.columns.kindWidth)
        } finally {
            file.delete()
        }
    }

    @Test
    fun unknownAndDamagedFormatsArePreservedAndProtectedFromOverwrite() {
        val file = File(instrumentation.targetContext.cacheDir, "views-${System.nanoTime()}.json")
        try {
            for (original in listOf("""{"version":99,"folders":[]}""", "{broken")) {
                file.writeText(original)
                val store = FolderViewStore(file)
                assertTrue(store.load().isEmpty())
                assertNotNull(store.error)
                assertThrows(IllegalStateException::class.java) { store.write(emptyMap()) }
                assertEquals(original, file.readText())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun serializedWriterKeepsTheLatestChangeMadeDuringAnEarlierWrite() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes =
            java.util.Collections.synchronizedList(mutableListOf<Map<String, FolderView>>())
        val disk =
            object : FolderViewPersistence {
                override val error: String? = null

                override fun load() = emptyMap<String, FolderView>()

                override fun write(values: Map<String, FolderView>) {
                    if (writes.isEmpty()) {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    writes.add(values)
                }
            }
        lateinit var repo: FolderViews
        instrumentation.runOnMainSync {
            repo = FolderViews(disk)
            repo.put("/a", FolderView())
        }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { repo.put("/a", FolderView(mode = ViewMode.GALLERY)) }
            release.countDown()
            withTimeout(5000) { while (repo.saving) delay(10) }
            assertEquals(2, writes.size)
            assertEquals(ViewMode.GALLERY, writes.last().getValue("/a").mode)
            assertNull(repo.error)
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { repo.close() }
        }
    }

    @Test
    fun failedWritesKeepEditsAndRetryPersistsTheLatestState() = runBlocking {
        val fail = AtomicBoolean(true)
        var written = emptyMap<String, FolderView>()
        val disk =
            object : FolderViewPersistence {
                override val error: String? = null

                override fun load() = emptyMap<String, FolderView>()

                override fun write(values: Map<String, FolderView>) {
                    if (fail.get()) throw java.io.IOException("Simulated folder-settings failure")
                    written = values
                }
            }
        lateinit var repo: FolderViews
        instrumentation.runOnMainSync {
            repo = FolderViews(disk)
            repo.put("/a", FolderView())
        }
        try {
            withTimeout(5000) { while (repo.saving) delay(10) }
            assertEquals("Simulated folder-settings failure", repo.error)
            assertEquals(1, repo.values.size)
            fail.set(false)
            instrumentation.runOnMainSync {
                assertTrue(repo.put("/a", FolderView(mode = ViewMode.ICONS)))
                repo.retry()
            }
            withTimeout(5000) { while (repo.saving) delay(10) }
            assertNull(repo.error)
            assertEquals(ViewMode.ICONS, written.getValue("/a").mode)
        } finally {
            instrumentation.runOnMainSync { repo.close() }
        }
    }

    @Test
    fun moveUndoRestoresDisplacedDestinationViewsAndKeepsLaterSourceEdits() = runBlocking {
        val initial =
            mapOf(
                "/from" to FolderView(mode = ViewMode.ICONS),
                "/to" to FolderView(mode = ViewMode.COLUMNS),
                "/other" to FolderView(),
            )
        val disk =
            object : FolderViewPersistence {
                override val error: String? = null

                override fun load() = initial

                override fun write(values: Map<String, FolderView>) {}
            }
        lateinit var repo: FolderViews
        instrumentation.runOnMainSync {
            repo = FolderViews(disk)
            repo.relocate(listOf("/from" to "/to"))
            assertEquals(ViewMode.ICONS, repo.values.getValue("/to").mode)
            repo.put("/to", FolderView(mode = ViewMode.GALLERY))
            repo.relocate(listOf("/to" to "/from"))
            assertEquals(ViewMode.GALLERY, repo.values.getValue("/from").mode)
            assertEquals(ViewMode.COLUMNS, repo.values.getValue("/to").mode)
            repo.relocate(listOf("/from" to "/to"))
            assertEquals(ViewMode.GALLERY, repo.values.getValue("/to").mode)
            repo.relocate(listOf("/to" to "/from"))
            assertEquals(ViewMode.COLUMNS, repo.values.getValue("/to").mode)
            assertEquals(FolderView(), repo.values["/other"])
        }
        try {
            withTimeout(5000) { while (repo.saving) delay(10) }
        } finally {
            instrumentation.runOnMainSync { repo.close() }
        }
    }

    @Test
    fun copyKeepsExistingDestinationViewsAndCannotExceedTheFolderLimit() {
        val initial = (0 until 2000).associate { "/from/$it" to FolderView(mode = ViewMode.ICONS) }
        val disk =
            object : FolderViewPersistence {
                override val error: String? = null

                override fun load() = initial

                override fun write(values: Map<String, FolderView>) {
                    check(values.size <= 2000)
                }
            }
        instrumentation.runOnMainSync {
            val repo = FolderViews(disk)
            try {
                repo.relocate(listOf("/from" to "/copies"), copy = true)
                assertEquals(2000, repo.values.size)
                assertNotNull(repo.notice)
            } finally {
                repo.close()
            }
            val small =
                FolderViews(
                    object : FolderViewPersistence {
                        override val error: String? = null

                        override fun load() =
                            mapOf(
                                "/a" to FolderView(mode = ViewMode.ICONS),
                                "/b" to FolderView(mode = ViewMode.GALLERY),
                            )

                        override fun write(values: Map<String, FolderView>) {}
                    },
                )
            try {
                small.relocate(listOf("/a" to "/b"), copy = true)
                assertEquals(ViewMode.GALLERY, small.values.getValue("/b").mode)
            } finally {
                small.close()
            }
        }
    }

    @Test
    fun canonicalWorkspacePathsKeepTheirFriendlyDisplayLabels() {
        instrumentation.runOnMainSync {
            val app = instrumentation.targetContext.applicationContext as FinderApplication
            val model = app.model
            val folder = File(model.workspace, "Documents/Invoices")
            assertEquals(
                "Workspace / Documents / Invoices",
                model.displayPath(folder.canonicalPath),
            )
            assertEquals(model.displayPath(folder.path), model.displayPath(folder.canonicalPath))
            assertEquals("Workspace", model.displayPath(model.workspace.canonicalPath))
        }
    }
}
