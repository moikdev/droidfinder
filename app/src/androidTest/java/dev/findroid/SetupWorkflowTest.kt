package dev.findroid

import android.graphics.Bitmap
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class SetupWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private val state get() = model.setup
    private lateinit var storage: SetupStorage
    private lateinit var before: SetupSnapshot
    private lateinit var beforeFiles: Map<String, ByteArray?>
    private lateinit var beforePrefs: Map<String, Map<String, SetupPref>>
    private lateinit var base: File
    private lateinit var target: File
    private var provider: File? = null
    private var connected: String? = null
    private val old = "/storage/OLD-TABLET"
    private val rootId = "old-storage"

    @Before fun setup() {
        compose.waitUntil(15_000) { model.busy == null && !model.loading && !model.workspaceSaving && !model.folderViews.saving }
        storage = SetupStorage(compose.activity.application)
        compose.runOnIdle { state.close(); before = storage.snapshot(model) }
        beforeFiles = SetupTransaction.FILES.associateWith { File(compose.activity.filesDir, it).takeIf(File::isFile)?.readBytes() }
        beforePrefs = AndroidSetupPreferences(compose.activity).read()
        base = File(model.workspace, "Setup-${System.nanoTime()}").canonicalFile.apply { mkdirs() }
        target = File(base, "Restored tablet").apply { mkdir() }
        File(target, "Projects").mkdir()
        File(target, "Projects/Keep.txt").writeText("ORIGINAL_SETUP_FILE_82736")
        File(target, "Reference").mkdir()
        File(target, "Reference/Notes.md").writeText("Reference notes remain unchanged")
        compose.runOnIdle {
            model.windowState.peekEntry.value = null
            model.activatePane()
            if (model.dualPane) model.toggleDualPane()
            model.navigate(target.path)
            model.changeMode(ViewMode.LIST)
            state.open()
        }
        compose.waitUntil(15_000) { !model.loading }
        compose.waitForIdle()
    }
    @After fun cleanup() {
        FixtureDocumentsProvider.listingDirectory = null; FixtureDocumentsProvider.listingTitle = null
        FixtureDocumentsProvider.listingExtras = null; FixtureDocumentsProvider.listingFailure = null
        FixtureDocumentsProvider.metadataFailure = null; FixtureDocumentsProvider.metadataDenied = false
        FixtureDocumentsProvider.listingGate?.countDown(); FixtureDocumentsProvider.listingGate = null
        compose.waitUntil(20_000) { !state.applying && !state.working && model.busy == null }
        compose.runOnIdle {
            state.close()
            connected?.let(model.documents::disconnect)
            model.session.connectedLocations.value = model.documents.locations
            storage.publish(model, before)
            before.openTabs?.let(model::restoreWorkspaceSnapshot)
        }
        compose.waitUntil(15_000) { !model.loading && !model.folderViews.saving && !model.workspaceSaving }
        beforeFiles.forEach { (name, bytes) ->
            val file = File(compose.activity.filesDir, name)
            if (bytes == null) file.delete() else file.writeBytes(bytes)
            File(file.path + ".bak").delete(); File(file.path + ".new").delete()
        }
        AndroidSetupPreferences(compose.activity).write(beforePrefs)
        base.deleteRecursively(); provider?.deleteRecursively()
    }
    private fun pane(path: String) = PaneSnapshot(listOf(FinderTab(listOf(path))), 0, ViewMode.LIST, 0, SortBy.NAME, true, GroupBy.NONE, false, true, true, false, 120, 240, 280)
    private fun incoming(missing: Boolean = false): SetupArchive {
        val project = "$old/Projects"
        val file = "$project/Keep.txt"
        val snapshot = SetupSnapshot(
            SidebarLayout(listOf(FavoriteShortcut("project", project, "Writing projects", "work")) + if (missing) listOf(FavoriteShortcut("missing", "$old/Missing folder", "Offline notes")) else emptyList(), listOf(FavoriteGroup("work", "Work"))),
            OrganizationSnapshot(TagSnapshot(defaultTags + FinderTag("Research", "Purple"), mapOf(file to setOf("Research"))), listOf(FinderCollection("references", "Research set", paths = listOf(file)))),
            listOf(SmartFolder(UUID.randomUUID().toString(), "Writing notes", project, SearchRules(name = "Keep", tags = setOf("Research")))),
            listOf(SavedWorkspace(UUID.randomUUID().toString(), "Writing desk", pane(project), pane("$old/Reference"), true, false, .5f, "primary")),
            SavedWorkspace("open-tabs", "Open tabs", pane(project), pane("$old/Reference"), true, false, .5f, "primary"),
            mapOf(project to FolderView(mode = ViewMode.GALLERY)), mapOf(project to FolderStyle("Green", "✎")),
            listOf(RenamePreset(UUID.randomUUID().toString(), "Numbered drafts", RenameRecipe(method = RenameMethod.NUMBER))),
            before.preferences.copy(dark = !before.preferences.dark))
        return SetupArchive(roots = listOf(SetupRoot(rootId, "Old tablet storage", old, SetupRootKind.LOCAL)), references = snapshot.locations().sorted().map { SetupReference(it, rootId, it.removePrefix("$old/").split('/')) }, snapshot = snapshot)
    }
    private fun load(value: SetupArchive = incoming(), map: String? = target.path) {
        compose.runOnIdle { state.loadText(SetupJson.encode(value)) }
        settled()
        assertNull(state.error)
        assertNotNull(state.archive)
        if (map != null) compose.runOnIdle { state.target(rootId, map) }
    }
    private fun settled() { compose.waitUntil(30_000) { !state.working && !state.applying }; compose.waitForIdle() }
    private fun review() {
        compose.onNodeWithTag("setup-review").performClick()
        settled()
        assertNull(state.error)
        assertNotNull(state.review)
    }
    private fun apply() {
        compose.onNodeWithTag("setup-apply").performClick()
        settled()
        assertNull(state.error)
    }
    private fun untouched() {
        assertEquals("ORIGINAL_SETUP_FILE_82736", File(target, "Projects/Keep.txt").readText())
        assertEquals("Reference notes remain unchanged", File(target, "Reference/Notes.md").readText())
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(compose.activity.getExternalFilesDir(null), "setup-qa").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun providerRoot(): String {
        val directory = File(compose.activity.filesDir, "ProviderFixtures/Setup-${System.nanoTime()}").apply { mkdirs() }
        provider = directory
        File(directory, "Projects").mkdir()
        File(directory, "Projects/Keep.txt").writeText("Provider original content")
        File(directory, "Reference").mkdir()
        val tree = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${directory.name}")
        val location = model.documents.connect(tree, persist = false)
        connected = location.uri
        compose.runOnIdle { model.session.connectedLocations.value = model.documents.locations }
        return location.uri
    }

    @Test fun overviewExplainsSetupScopeAndExportsAReadablePortableBackup() {
        compose.onNodeWithText("Create setup backup").assertExists()
        capture("overview")
        compose.onNodeWithTag("setup-export").performClick()
        settled()
        assertNull(state.error)
        val archive = SetupJson.decode(state.exportBytes!!.toString(Charsets.UTF_8))
        assertEquals(before.sidebar, archive.snapshot.sidebar)
        assertTrue(archive.roots.any { it.kind == SetupRootKind.WORKSPACE })
        assertFalse(state.exportBytes!!.toString(Charsets.UTF_8).contains("ORIGINAL_SETUP_FILE_82736"))
        compose.onNodeWithTag("setup-save").assertIsEnabled()
        untouched()
    }
    @Test fun reviewedMergeRemapsAllSectionsAndKeepsOriginalFilesAndCurrentPreferences() {
        val demo = incoming()
        File(compose.activity.getExternalFilesDir(null), "setup-qa").apply { mkdirs() }
            .resolve("portable-demo.json").writeText(SetupJson.encode(demo))
        load(demo)
        capture("import-choices")
        review()
        assertEquals(0, state.review!!.resolution.issueCount)
        assertTrue(state.review!!.resolution.changed >= 3)
        capture("review")
        apply()
        compose.onNodeWithTag("setup-notice").assertTextContains("Setup restored", substring = true)
        assertTrue(model.favorites.any { it == File(target, "Projects").path })
        assertEquals(setOf("Research"), model.tagAssignments[File(target, "Projects/Keep.txt").canonicalPath])
        assertTrue(model.savedWorkspaces.any { it.name == "Writing desk" })
        assertTrue(model.savedWorkspaces.any { it.name == "Restored open tabs" })
        assertEquals(before.preferences.dark, model.darkTheme)
        assertEquals(ViewMode.GALLERY, model.folderViews.values[File(target, "Projects").canonicalPath]?.mode)
        assertTrue(storage.previous.isFile)
        val safety = SetupJson.decode(storage.previous.readText())
        assertEquals(before.sidebar, safety.snapshot.sidebar)
        capture("restored")
        untouched()
    }
    @Test fun replacementFolderPickerAcceptsTheActualTouchChoice() {
        load(map = null)
        compose.onNodeWithTag("setup-root-$rootId").performScrollTo().performClick()
        compose.onNodeWithText("Replacement root folder").assertExists()
        compose.onNodeWithText("Use this folder").performClick()
        compose.waitForIdle()
        assertEquals(target.path, state.targets[rootId])
        review()
        assertEquals(0, state.review!!.resolution.issueCount)
    }
    @Test fun missingReferencesRequireAnExplicitKeepChoiceBeforeRestore() {
        load(incoming(missing = true))
        review()
        assertEquals(1, state.review!!.resolution.issueCount)
        compose.onNodeWithTag("setup-apply").assertIsNotEnabled()
        compose.onNodeWithTag("setup-keep-unavailable").performScrollTo().performClick()
        compose.onNodeWithTag("setup-apply").assertIsEnabled()
        apply()
        assertTrue(File(target, "Missing folder").path in model.favorites)
        assertFalse(File(target, "Missing folder").exists())
        untouched()
    }
    @Test fun choosingOnlySidebarDoesNotChangeTagsWorkspacesPresetsOrTheme() {
        load()
        compose.runOnIdle { SetupSection.entries.filter { it != SetupSection.SIDEBAR }.forEach { state.section(it, false) }; state.mode(true) }
        review(); apply()
        assertEquals(listOf(File(target, "Projects").path), model.favorites.toList())
        assertEquals(before.organization, model.session.tagStore.snapshot())
        assertEquals(before.workspaces, model.savedWorkspaces)
        assertEquals(before.presets, model.session.renamePresetStore.load())
        assertEquals(before.preferences.dark, model.darkTheme)
        untouched()
    }
    @Test fun replacePreferencesHasAReviewAndSurvivesARereadFromDisk() {
        load()
        compose.runOnIdle { SetupSection.entries.filter { it != SetupSection.PREFERENCES }.forEach { state.section(it, false) }; state.mode(true) }
        review(); apply()
        assertEquals(!before.preferences.dark, model.darkTheme)
        assertEquals(!before.preferences.dark, compose.activity.getSharedPreferences("finder", 0).getBoolean("dark", before.preferences.dark))
        untouched()
    }
    @Test fun aStaleSetupReviewRefusesToOverwriteAChangedPreference() {
        load(); review()
        val oldSidebar = model.sidebar
        compose.runOnIdle { model.toggleTheme() }
        compose.onNodeWithTag("setup-apply").performClick()
        settled()
        assertTrue(state.error.orEmpty().contains("current setup changed"))
        assertEquals(oldSidebar, model.sidebar)
        assertFalse(storage.previous.exists() && beforeFiles["setup-previous.json"] == null)
        untouched()
    }
    @Test fun malformedAndFutureBackupsLeaveTheWholeSetupIntact() {
        val fingerprint = storage.transaction.fingerprint()
        val future = org.json.JSONObject(SetupJson.encode(incoming())).put("version", 999).toString()
        compose.runOnIdle { state.loadText(future) }; settled()
        compose.onNodeWithTag("setup-error").assertTextContains("newer Findroid", substring = true)
        assertEquals(fingerprint, storage.transaction.fingerprint())
        assertNull(state.archive)
        compose.runOnIdle { state.loadText("{\"version\":1,\"version\":2}") }; settled()
        assertNotNull(state.error)
        assertEquals(fingerprint, storage.transaction.fingerprint())
        untouched()
    }
    @Test fun closingAnUnappliedReviewDoesNotPersistAnyImportedMetadata() {
        val original = storage.transaction.fingerprint()
        load(); review()
        compose.onNodeWithText("Close", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("setup-dialog").assertDoesNotExist()
        assertEquals(original, storage.transaction.fingerprint())
        untouched()
    }
    @Test fun readOnlyProviderMappingUsesExistingDocumentIdsAndNeedsNoWriteGrant() {
        val root = providerRoot()
        val readonly = File(provider!!, "readonly").apply { mkdir() }
        File(readonly, "Projects").mkdir(); File(readonly, "Projects/Keep.txt").writeText("Read only provider bytes")
        File(readonly, "Reference").mkdir()
        val destination = model.documents.list(root).first { it.name == "readonly" }
        assertFalse(destination.writable)
        load(map = destination.path)
        review()
        assertEquals(0, state.review!!.resolution.issueCount)
        val actual = model.documents.list(model.documents.list(destination.path).first { it.name == "Projects" }.path).single().path
        assertEquals(actual, state.review!!.resolution.paths["$old/Projects/Keep.txt"])
        apply()
        assertEquals(setOf("Research"), model.tagAssignments[actual])
        assertEquals("Read only provider bytes", File(readonly, "Projects/Keep.txt").readText())
        untouched()
    }
    @Test fun providerLoadingResultsAreNeverTreatedAsCompleteMappings() {
        val root = providerRoot()
        FixtureDocumentsProvider.listingDirectory = provider!!.canonicalPath
        FixtureDocumentsProvider.listingExtras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
        load(map = root)
        review()
        assertTrue(state.review!!.resolution.issueCount > 0)
        assertEquals("$old/Projects/Keep.txt", state.review!!.resolution.paths["$old/Projects/Keep.txt"])
        assertTrue(state.review!!.resolution.issues.any { it.reason.contains("still loading") })
        compose.onNodeWithTag("setup-apply").assertIsNotEnabled()
        untouched()
    }
    @Test fun grantLossAfterReviewRequiresAFreshAvailabilityReview() {
        val root = providerRoot()
        load(map = root); review()
        assertEquals(0, state.review!!.resolution.issueCount)
        compose.runOnIdle { model.documents.disconnect(root); model.session.connectedLocations.value = model.documents.locations; connected = null }
        compose.onNodeWithTag("setup-apply").performClick(); settled()
        assertTrue(state.error.orEmpty().contains("Storage availability changed"))
        assertEquals(before.sidebar, model.sidebar)
        untouched()
    }
    @Test fun previousSetupCanBeReviewedAndRestoredWithoutGuessingRoots() {
        load(); review(); apply()
        compose.onNodeWithTag("setup-previous").performScrollTo().performClick(); settled()
        assertNull(state.error)
        assertEquals(before.sidebar, state.archive!!.snapshot.sidebar)
        compose.runOnIdle { SetupSection.entries.filter { it != SetupSection.SIDEBAR }.forEach { state.section(it, false) }; state.mode(true) }
        review()
        if (state.review!!.resolution.issueCount > 0) compose.runOnIdle { state.acknowledgeUnavailable(true) }
        apply()
        assertEquals(before.sidebar, model.sidebar)
        untouched()
    }
    @Test fun restoredTabsOpenOnlyWhenRequestedAfterTheRestore() {
        val current = model.location
        load(); review(); apply()
        assertEquals(current, model.location)
        compose.onNodeWithText("Open restored tabs workspace").performScrollTo().performClick()
        compose.waitUntil(15_000) { !model.workspaceRecovery.working && !model.loading && !model.secondaryPane.loading }
        assertEquals(File(target, "Projects").path, model.location)
        assertTrue(model.dualPane)
        assertEquals(File(target, "Reference").path, model.secondaryPane.location)
        untouched()
    }
    @Test fun ambiguousProviderNamesStayUnmappedForIndividualRepair() {
        val root = providerRoot()
        val other = File(provider!!, "Alternate projects").apply { mkdir() }
        FixtureDocumentsProvider.listingDirectory = other.canonicalPath
        FixtureDocumentsProvider.listingTitle = "Projects"
        load(map = root); review()
        assertTrue(state.review!!.resolution.issueCount > 0)
        assertEquals("$old/Projects", state.review!!.resolution.paths["$old/Projects"])
        assertTrue(state.review!!.resolution.issues.any { it.reason.contains("several items") })
        compose.onNodeWithTag("setup-apply").assertIsNotEnabled()
        untouched()
    }
    @Test fun anOpaqueReferenceWithoutRelativeNamesIsKeptForRepair() {
        val uri = "content://old.provider/tree/root/document/opaque-27"
        val original = incoming()
        val snapshot = original.snapshot.mapLocations(original.snapshot.locations().associateWith { uri })
        val source = original.copy(roots = listOf(SetupRoot(rootId, "Old provider", "content://old.provider/tree/root/document/root", SetupRootKind.PROVIDER)), references = listOf(SetupReference(uri, rootId, null)), snapshot = snapshot)
        load(source); review()
        assertEquals(1, state.review!!.resolution.issueCount)
        assertEquals(uri, state.review!!.resolution.paths[uri])
        assertTrue(state.review!!.resolution.issues.single().reason.contains("No relative path"))
        assertEquals(before.sidebar, model.sidebar)
        untouched()
    }

}
