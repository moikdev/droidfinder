package dev.droidfinder

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.droidfinder.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

class StorageWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var folder: File
    private lateinit var tree: Uri
    private var root: String? = null
    private var originalTabs: Set<String> = emptySet()

    @Before
    fun setup() {
        folder =
            File(compose.activity.filesDir, "ProviderFixtures/Storage-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(folder, "Child").mkdir()
        File(folder, "Child/Notes.txt").writeText("storage recovery fixture")
        File(folder, "readonly").mkdir()
        tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.droidfinder.fixtures",
                "root/${folder.name}",
            )
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            originalTabs = model.tabs.map { it.id }.toSet()
            model.changeDensity(FinderDensity.COMFORTABLE)
            model.changeGrouping(GroupBy.NONE)
            model.updateQuery("")
            model.connectLocation(tree, false)
        }
        loaded()
        compose.waitUntil(10_000) { model.location.contains(folder.name) && !model.loading }
        root = model.location
    }

    private fun loaded() {
        compose.waitUntil(10_000) { !model.loading && model.busy == null }
        compose.waitForIdle()
    }

    private fun target(path: String = root!!) =
        StorageTarget(path, path, folder.name, StorageKind.PROVIDER, grant = tree.toString())

    @After
    fun cleanup() {
        FixtureDocumentsProvider.metadataGate?.countDown()
        FixtureDocumentsProvider.metadataDirectory = null
        FixtureDocumentsProvider.metadataGate = null
        FixtureDocumentsProvider.metadataOpened = null
        FixtureDocumentsProvider.metadataFailure = null
        FixtureDocumentsProvider.metadataDenied = false
        if (
            compose
                .onAllNodesWithContentDescription("Close storage locations")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
            compose.onNodeWithContentDescription("Close storage locations").performClick()
        compose.runOnIdle {
            model.storage.stop()
            model.reconnectChoice = null
            model.navigate(model.workspace.path)
            root?.let { model.disconnectLocation(it) }
            model.secondaryPane.navigate(model.workspace.path)
            if (model.dualPane) model.toggleDualPane()
            model.tabs.indices
                .reversed()
                .filter { model.tabs[it].id !in originalTabs }
                .forEach(model::closeTab)
            model.storage.stop()
        }
        loaded()
        folder.deleteRecursively()
    }

    @Test
    fun statusUsesProviderCapabilitiesAndReportsSpaceWithoutScanningFiles() {
        val status = model.storage.probe(target(), false, ListingControl())
        assertEquals(StorageHealth.AVAILABLE, status.health)
        assertTrue(status.create && status.rename && status.delete)
        assertTrue(status.available != null && status.available!! > 0)
        val readonly = model.entries.first { it.name == "readonly" }.path
        val readStatus = model.storage.probe(target(readonly), false, ListingControl())
        assertEquals(StorageHealth.READ_ONLY, readStatus.health)
        assertFalse(readStatus.create || readStatus.rename || readStatus.delete)
        compose.runOnIdle { model.navigate(readonly) }
        loaded()
        assertFalse(model.canCreate)
        compose.runOnIdle { model.storageRequested = true }
        compose.onNodeWithTag("storage-locations").assertExists()
        compose.onNodeWithContentDescription("Close storage locations").performClick()
    }

    @Test
    fun permissionOfflineMissingProviderAndRemovedDriveRemainDistinct() {
        FixtureDocumentsProvider.metadataFailure = folder.canonicalPath
        FixtureDocumentsProvider.metadataDenied = true
        assertEquals(
            StorageHealth.GRANT_REQUIRED,
            model.storage.probe(target(), false, ListingControl()).health,
        )
        compose.runOnIdle { model.refresh() }
        loaded()
        assertEquals("Folder access expired", model.folderStorageIssue?.title)
        compose.onNodeWithTag("storage-recovery").assertExists()
        FixtureDocumentsProvider.metadataDenied = false
        assertEquals(
            StorageHealth.UNAVAILABLE,
            model.storage.probe(target(), false, ListingControl()).health,
        )
        val missing = target("content://dev.droidfinder.missing/tree/a/document/a")
        assertEquals(
            StorageHealth.PROVIDER_MISSING,
            model.storage.probe(missing, false, ListingControl()).health,
        )
        val drive =
            StorageTarget(
                "removed",
                "/storage/QA",
                "QA drive",
                StorageKind.VOLUME,
                Environment.MEDIA_REMOVED,
            )
        assertEquals(
            StorageHealth.UNAVAILABLE,
            model.storage.probe(drive, false, ListingControl()).health,
        )
        val tablet =
            StorageTarget("tablet", model.deviceRoot.path, "This tablet", StorageKind.TABLET)
        assertEquals(
            StorageHealth.NEEDS_ACCESS,
            model.storage.probe(tablet, false, ListingControl()).health,
        )
    }

    @Test
    fun sameFolderReconnectPreservesTabsPanesAndHistoryWhileDifferentFolderNeedsChoice() {
        val original = root!!
        val child = model.entries.first { it.name == "Child" }.path
        compose.runOnIdle {
            model.navigate(child)
            model.newTab(model.workspace.path, activate = false)
            model.toggleDualPane()
            model.secondaryPane.navigate(original)
        }
        loaded()
        compose.runOnIdle { model.flushNavigationState() }
        val before = model.tabs.map { it.copy(navigation = TabNavigation()) }
        val active = model.activeTab
        val tabId = model.tabs[active].id
        val viewportKey = model.viewportKey("list")
        val position = model.savedViewport(tabId, viewportKey)
        compose.runOnIdle { model.connectLocation(tree, false, original) }
        compose.waitUntil(10_000) {
            model.message == "Folder permission restored" &&
                !model.loading &&
                !model.secondaryPane.loading
        }
        assertEquals(before, model.tabs.map { it.copy(navigation = TabNavigation()) })
        assertEquals(position, model.savedViewport(tabId, viewportKey))
        assertEquals(active, model.activeTab)
        assertEquals(child, model.location)
        assertEquals(original, model.secondaryPane.location)
        val other =
            DocumentsContract.buildTreeDocumentUri(
                "dev.droidfinder.fixtures",
                "root/${folder.name}/Child",
            )
        compose.runOnIdle { model.connectLocation(other, false, original) }
        assertEquals(original, model.reconnectChoice?.original?.uri)
        assertEquals(other.toString(), model.reconnectChoice?.selected)
        assertEquals(child, model.location)
        assertFalse(model.connectedLocations.any { it.grant == other.toString() })
    }

    @Test
    fun disconnectKeepsTabsAndUnrelatedPaneAndProtectedPathsExplainAndroidLimits() {
        compose.runOnIdle {
            model.newTab(model.workspace.path, activate = false)
            model.toggleDualPane()
            model.secondaryPane.navigate("@trash")
        }
        loaded()
        val count = model.tabs.size
        compose.runOnIdle {
            model.session.busy.value = "Synthetic operation"
            try {
                model.disconnectLocation(root!!)
                assertTrue(model.connectedLocations.any { it.uri == root })
            } finally {
                model.session.busy.value = null
            }
            model.disconnectLocation(root!!)
        }
        loaded()
        assertEquals(count, model.tabs.size)
        assertEquals(root, model.location)
        assertEquals("@trash", model.secondaryPane.location)
        assertNotNull(model.folderStorageIssue)
        val protected = File(model.deviceRoot, "Android/data/another.app/files").path
        assertFalse(model.allowed(File(protected)))
        val link = File(model.workspace, "Protected-link-${System.nanoTime()}")
        java.nio.file.Files.createSymbolicLink(link.toPath(), File(protected).toPath())
        try {
            assertFalse(model.allowed(link))
        } finally {
            link.delete()
        }
        java.nio.file.Files.createSymbolicLink(link.toPath(), model.workspace.toPath())
        try {
            assertTrue(model.allowed(link))
        } finally {
            link.delete()
        }
        compose.runOnIdle { model.navigate(protected) }
        loaded()
        assertTrue(model.folderStorageIssue?.protected == true)
        compose.onNodeWithText("Protected by Android").assertExists()
        assertFalse(model.canCreate)
    }

    @Test
    fun stoppingMetadataCheckSuppressesLateProviderResultAndKeepsOtherStatuses() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        FixtureDocumentsProvider.metadataDirectory = folder.canonicalPath
        FixtureDocumentsProvider.metadataGate = release
        FixtureDocumentsProvider.metadataOpened = opened
        try {
            compose.runOnIdle { model.storage.refresh(false) }
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            compose.waitUntil(5_000) {
                model.storage.items.any {
                    it.target.id == "workspace" && it.health == StorageHealth.AVAILABLE
                }
            }
            compose.runOnIdle { model.storage.stop() }
            val stopped = model.storage.items
            assertFalse(model.storage.checking)
            assertEquals(StorageHealth.STOPPED, stopped.first { it.target.id == root }.health)
            release.countDown()
            // A subsequent generation must win even if an older provider query returns late.
            FixtureDocumentsProvider.metadataDirectory = null
            compose.runOnIdle { model.storage.refresh(false) }
            compose.waitUntil(10_000) { !model.storage.checking }
            assertEquals(
                StorageHealth.AVAILABLE,
                model.storage.items.first { it.target.id == root }.health,
            )
        } finally {
            release.countDown()
        }
    }
}
