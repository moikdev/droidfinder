@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import android.graphics.Bitmap
import android.provider.DocumentsContract
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import org.junit.*
import org.junit.Assert.*

class OrganizationWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private val state
        get() = model.organization

    private lateinit var folder: File
    private lateinit var provider: File
    private lateinit var original: OrganizationSnapshot
    private lateinit var topic: String
    private var root: String? = null

    @Before
    fun setup() {
        val id = System.nanoTime()
        original = model.session.tagStore.reload()
        topic = "Topic $id"
        folder =
            File(compose.activity.filesDir, "Workspace/Organize-$id").canonicalFile.apply {
                mkdirs()
            }
        File(folder, "First note.txt").writeText("FIRST_CONTENT_82736")
        File(folder, "Planning").mkdir()
        File(folder, "Planning/Second note.txt").writeText("SECOND_CONTENT_82736")
        File(folder, "Budget.csv").writeText("name,cost\nSample,42\n")
        val image = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        File(folder, "Photo.jpg").outputStream().use {
            image.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
        image.recycle()
        provider =
            File(compose.activity.filesDir, "ProviderFixtures/Organize-$id").apply { mkdirs() }
        File(provider, "readonly").mkdir()
        File(provider, "readonly/Cloud note.txt").writeText("CLOUD_CONTENT_82736")
        compose.runOnIdle {
            state.close()
            state.exportFilename = "Findroid-library.json"
            model.windowState.peekEntry.value = null
            if (model.dualPane) model.toggleDualPane()
            model.changeMode(ViewMode.LIST)
            model.navigate(folder.path)
            model.defineTag(topic, "Purple")
            model.session.history.clear()
            model.session.undoLabel.value = null
            model.session.redoLabel.value = null
        }
        compose.waitUntil(10_000) {
            !model.loading && model.entries.any { it.name == "First note.txt" }
        }
    }

    private fun entry(name: String) = Entry(File(folder, name))

    private fun opened(
        entries: List<Entry> = listOf(entry("First note.txt"), entry("Planning/Second note.txt")),
    ) {
        compose.runOnIdle { state.open(entries) }
        ready()
    }

    private fun ready() {
        compose.waitUntil(15_000) { !state.running && (state.ready || state.error != null) }
        compose.waitForIdle()
    }

    private fun settled() {
        compose.waitUntil(15_000) { !state.applying && !state.membersLoading && model.busy == null }
        ready()
    }

    private fun apply() {
        compose.runOnIdle { state.applyTags() }
        settled()
    }

    private fun undo() {
        compose.runOnIdle {
            state.close()
            model.undo()
        }
        compose.waitUntil(15_000) { model.busy == null }
        compose.waitForIdle()
    }

    private fun redo() {
        compose.runOnIdle { model.redo() }
        compose.waitUntil(15_000) { model.busy == null }
        compose.waitForIdle()
    }

    private fun connected(): Entry {
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/" + provider.name,
            )
        val location = model.documents.connect(tree, persist = false)
        root = location.uri
        compose.runOnIdle { model.session.connectedLocations.value = model.documents.locations }
        model.documents.list(location.uri)
        val readonly = model.documents.list(location.uri).first { it.name == "readonly" }
        return model.documents.list(readonly.path).single()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory =
            File(compose.activity.getExternalFilesDir(null), "organization-qa").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun newCollection(name: String = "Research set"): FinderCollection {
        compose.runOnIdle { state.create(name, "Blue", true) }
        settled()
        return model.collections.first { it.name == name }
    }

    @After
    fun cleanup() {
        FixtureDocumentsProvider.metadataGate?.countDown()
        FixtureDocumentsProvider.metadataGate = null
        FixtureDocumentsProvider.metadataDirectory = null
        FixtureDocumentsProvider.metadataOpened = null
        FixtureDocumentsProvider.beforeRename = null
        compose.waitUntil(15_000) { model.busy == null }
        compose.runOnIdle {
            state.dismissExport()
            state.close()
            model.session.history.clear()
            model.session.undoLabel.value = null
            model.session.redoLabel.value = null
            model.session.tagStore.restore(original.tags.definitions, original.tags.assignments)
            model.session.tagStore.restoreCollections(
                model.session.tagStore.snapshot().collections,
                original.collections,
            )
            model.syncLibrary()
            model.libraryNotice = null
            root?.let { model.documents.disconnect(it) }
            model.session.connectedLocations.value = model.documents.locations
            model.navigate(model.workspace.path)
        }
        compose.waitUntil(10_000) { !model.loading }
        folder.deleteRecursively()
        provider.deleteRecursively()
    }

    @Test
    fun closingOrganizationRestoresExplorerKeyboardFocus() {
        fun addressShortcut() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val now = android.os.SystemClock.uptimeMillis()
            for (action in
                listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                instrumentation.sendKeySync(
                    android.view.KeyEvent(
                        now,
                        android.os.SystemClock.uptimeMillis(),
                        action,
                        android.view.KeyEvent.KEYCODE_L,
                        0,
                        android.view.KeyEvent.META_CTRL_ON,
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            compose
                .onNode(hasContentDescription("Address bar") and hasSetTextAction())
                .assertExists()
                .assertIsFocused()
                .performKeyInput { pressKey(Key.Escape) }
        }
        opened()
        compose.onNodeWithTag("library-search-Find selection tags").performTextInput("Blue")
        compose.onNodeWithContentDescription("Close organization library").performClick()
        compose.waitForIdle()
        addressShortcut()

        opened()
        val collection = newCollection()
        compose.onNode(hasText("Collections") and hasClickAction()).performClick()
        compose.onNodeWithText("Open collection").performClick()
        compose.waitUntil(10_000) {
            !model.loading && model.location == "@collection:${collection.id}"
        }
        compose.waitForIdle()
        addressShortcut()
    }

    @Test
    fun mixedAssignmentsAreReviewedAndFilteredRowsRemainInTheApplyScope() {
        val a = entry("First note.txt")
        val b = entry("Planning/Second note.txt")
        compose.runOnIdle {
            model.session.tagStore.assign(listOf(a.path), "Blue")
            model.session.tagStore.assign(listOf(b.path), topic)
            model.syncLibrary()
        }
        opened(listOf(a, b))
        val before = model.tagAssignments
        compose.runOnIdle { state.change("Green", TagChange.ADD) }
        assertEquals(before, model.tagAssignments)
        compose.onNodeWithTag("library-search-Filter tag preview").performTextInput("missing ")
        compose.onNodeWithTag("library-search-Filter tag preview").performKeyInput {
            pressKey(Key.Spacebar)
        }
        assertNull(model.windowState.peekEntry.value)
        assertEquals(2, state.changed)
        compose.onNodeWithText("Apply tags").performClick()
        settled()
        assertEquals(setOf("Blue", "Green"), model.tagAssignments[organizationKey(a.path)])
        assertEquals(setOf(topic, "Green"), model.tagAssignments[organizationKey(b.path)])
        undo()
        assertEquals(before, model.tagAssignments)
        redo()
        assertEquals(setOf("Blue", "Green"), model.tagAssignments[organizationKey(a.path)])
        assertEquals("FIRST_CONTENT_82736", a.file.readText())
    }

    @Test
    fun frozenReviewAndDraftSurviveRecreationWithoutWritingTags() {
        opened()
        val paths = state.items.map { it.path }
        val before = model.tagAssignments
        compose.runOnIdle {
            state.change("Blue", TagChange.ADD)
            model.clearSelection()
            model.select(entry("Budget.csv"))
        }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(paths, state.items.map { it.path })
        assertEquals(TagChange.ADD, state.changes["Blue"])
        assertEquals(before, model.tagAssignments)
        assertTrue(state.visible)
        capture("selection")
    }

    @Test
    fun changedSourcesAndSelectedAssignmentsAreRejectedButUnrelatedTagsArePreserved() {
        val a = entry("First note.txt")
        opened(listOf(a))
        compose.runOnIdle { state.change("Blue", TagChange.ADD) }
        a.file.writeText("changed content and size")
        apply()
        assertNotNull(state.error)
        assertNull(model.tagAssignments[organizationKey(a.path)])
        compose.runOnIdle { state.refresh() }
        ready()
        compose.runOnIdle {
            model.session.tagStore.assign(listOf(a.path), "Red")
            model.syncLibrary()
        }
        apply()
        assertTrue(state.error.orEmpty().contains("Tags changed"))
        compose.runOnIdle { state.refresh() }
        ready()
        val b = entry("Budget.csv")
        compose.runOnIdle {
            model.session.tagStore.assign(listOf(b.path), topic)
            model.syncLibrary()
        }
        apply()
        assertEquals(setOf("Red", "Blue"), model.tagAssignments[organizationKey(a.path)])
        assertEquals(setOf(topic), model.tagAssignments[organizationKey(b.path)])
    }

    @Test
    fun collectionsBrowseAcrossRootsIncludingReadOnlyProviderItemsAndOpenInNewWindows() {
        val cloud = connected()
        assertFalse(cloud.writable)
        opened(listOf(entry("Budget.csv"), entry("Photo.jpg"), cloud))
        val collection = newCollection()
        assertEquals(
            setOf(entry("Budget.csv").path, entry("Photo.jpg").path, cloud.path),
            collection.paths.toSet(),
        )
        compose.runOnIdle {
            state.page = 0
            state.inspect(collection.id)
        }
        compose.waitUntil(15_000) { !state.membersLoading && state.members.size == 3 }
        capture("collection")
        compose.onNodeWithText("Open collection").performClick()
        compose.waitUntil(10_000) { !model.loading && model.entries.size == 3 }
        assertEquals("@collection:${collection.id}", model.location)
        compose.runOnIdle { model.goTo(model.workspace.path) }
        compose.waitUntil(10_000) {
            !model.loading &&
                !model.location.startsWith("@") &&
                File(model.location).canonicalPath == model.workspace.canonicalPath
        }
        compose.runOnIdle { model.goTo("@collection:${collection.id}") }
        compose.waitUntil(10_000) { !model.loading && model.entries.size == 3 }
        compose.runOnIdle {
            model.newTab(folder.path)
            model.newTab("@collection:${collection.id}")
        }
        compose.waitUntil(10_000) { !model.loading && model.entries.size == 3 }
        val app = compose.activity.application as FinderApplication
        val id = "organization-test-${System.nanoTime()}"
        lateinit var other: FinderModel
        compose.runOnIdle { other = app.windowModel(id, "@collection:${collection.id}") }
        try {
            compose.waitUntil(10_000) { !other.loading && other.entries.size == 3 }
            assertEquals(model.location, other.location)
        } finally {
            compose.runOnIdle { app.releaseWindow(id) }
        }
        assertTrue(File(folder, "Budget.csv").isFile)
        assertTrue(File(provider, "readonly/Cloud note.txt").isFile)
    }

    @Test
    fun localRenamesAndUndoRedoMoveReferencesWhileCopiesDoNotJoinTheCollection() {
        val a = entry("First note.txt")
        opened(listOf(a))
        val collection = newCollection()
        compose.runOnIdle {
            state.close()
            model.clearSelection()
            model.select(a)
            model.rename("Renamed.txt")
        }
        compose.waitUntil(10_000) { model.busy == null && File(folder, "Renamed.txt").exists() }
        assertEquals(
            listOf(File(folder, "Renamed.txt").path),
            model.collections.first { it.id == collection.id }.paths,
        )
        undo()
        assertEquals(listOf(a.path), model.collections.first { it.id == collection.id }.paths)
        redo()
        assertEquals(
            listOf(File(folder, "Renamed.txt").path),
            model.collections.first { it.id == collection.id }.paths,
        )
        compose.runOnIdle {
            model.clearSelection()
            model.select(Entry(File(folder, "Renamed.txt")))
            model.duplicate()
        }
        compose.waitUntil(10_000) {
            model.busy == null && folder.listFiles()!!.count { it.name.startsWith("Renamed") } == 2
        }
        assertEquals(1, model.collections.first { it.id == collection.id }.paths.size)
    }

    @Test
    fun removingReferencesAndDeletingCollectionsAreUndoableAndKeepOriginalFiles() {
        opened()
        val collection = newCollection()
        compose.runOnIdle {
            state.removeMembers(collection.id, listOf(entry("First note.txt").path), collection)
        }
        settled()
        assertEquals(1, model.collections.first { it.id == collection.id }.paths.size)
        assertTrue(File(folder, "First note.txt").exists())
        undo()
        assertEquals(2, model.collections.first { it.id == collection.id }.paths.size)
        opened()
        compose.runOnIdle {
            state.deleteCollection(
                collection.id,
                model.collections.first { it.id == collection.id },
            )
        }
        settled()
        assertTrue(model.collections.none { it.id == collection.id })
        undo()
        assertEquals(2, model.collections.first { it.id == collection.id }.paths.size)
        assertTrue(File(folder, "Planning/Second note.txt").exists())
    }

    @Test
    fun staleTagDeletionConfirmationCannotEraseNewAssignments() {
        opened(emptyList())
        compose.runOnIdle { state.page = 1 }
        compose.onNodeWithText("Find tags").performTextInput(topic)
        compose.onNodeWithContentDescription("Delete tag $topic").performClick()
        compose.runOnIdle {
            model.session.tagStore.assign(listOf(entry("Budget.csv").path), topic)
            model.syncLibrary()
        }
        compose.onNodeWithText("Delete", substring = false).performClick()
        settled()
        assertTrue(model.tagDefinitions.any { it.name == topic })
        assertEquals(setOf(topic), model.tagAssignments[organizationKey(entry("Budget.csv").path)])
        assertTrue(state.error.orEmpty().contains("changed"))
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun unavailableReferencesStayInTheLibraryAndCanBeRemovedWithoutFileDeletion() {
        opened()
        val collection = newCollection()
        File(folder, "First note.txt").delete()
        compose.runOnIdle {
            state.page = 0
            state.inspect(collection.id)
        }
        compose.waitUntil(10_000) {
            !state.membersLoading && state.members.any { it.problem != null }
        }
        assertEquals(2, model.collections.first { it.id == collection.id }.paths.size)
        compose.onNodeWithText("Unavailable (1)").performClick()
        compose.onNodeWithText("First note.txt").assertExists()
        compose.onNodeWithText("Second note.txt").assertDoesNotExist()
        capture("unavailable")
        compose.runOnIdle {
            state.removeMembers(
                collection.id,
                listOf(File(folder, "First note.txt").path),
                collection,
            )
        }
        settled()
        assertTrue(File(folder, "Planning/Second note.txt").exists())
    }

    @Test
    fun noSelectionExportUsesReviewedJsonKeepsCollisionsAndSurvivesRecreation() {
        val old = File(folder, "Findroid-library.json").apply { writeText("keep existing") }
        opened(emptyList())
        compose.runOnIdle { state.openExport() }
        val frozen = state.exportReview!!
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(frozen, state.exportReview)
        capture("export")
        compose.onNodeWithText("Choose destination…").performClick()
        compose.onNodeWithText("Export library to…").assertExists()
        try {
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(hasText("Save here") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (e: Throwable) {
            capture("destination-failure")
            throw AssertionError(
                "Destination readiness: location=${model.location}, folder=${model.folderPath}, create=${model.canCreate}, busy=${model.busy}; ${compose.onAllNodes(isRoot()).printToString()}",
                e,
            )
        }
        compose.onNodeWithText("Save here").assertIsEnabled().performClick()
        compose.waitUntil(15_000) { model.busy == null && state.exportReview == null }
        val exported = folder.listFiles()!!.first { it.extension == "json" && it != old }
        val text = exported.readText()
        assertEquals(
            "dev.findroid.organization",
            JsonParser.parseString(text).asJsonObject["format"].asString,
        )
        assertFalse(text.contains("FIRST_CONTENT_82736"))
        assertEquals("keep existing", old.readText())
        undo()
        assertFalse(exported.exists())
        assertEquals("keep existing", old.readText())
        redo()
        assertEquals(text, exported.readText())
    }

    @Test
    fun providerExportFailureCleansStagingAndAllowsTargetedRetry() {
        connected()
        val parent = root!!
        val old = File(provider, "Findroid-library.json").apply { writeText("keep") }
        opened(emptyList())
        compose.runOnIdle { state.openExport() }
        FixtureDocumentsProvider.beforeRename = { _, name ->
            if (name.endsWith(".json")) throw IOException("Export publication failed")
        }
        compose.runOnIdle { state.saveExport(parent) }
        compose.waitUntil(15_000) { !state.exportSaving && model.busy == null }
        assertNotNull(state.exportError)
        assertNotNull(state.exportReview)
        assertEquals("keep", old.readText())
        assertTrue(provider.listFiles()!!.none { it.name.startsWith(".findroid-") })
        FixtureDocumentsProvider.beforeRename = null
        compose.runOnIdle { state.saveExport(parent) }
        compose.waitUntil(15_000) {
            !state.exportSaving && model.busy == null && state.exportReview == null
        }
        assertEquals("keep", old.readText())
        assertEquals(2, provider.listFiles()!!.count { it.extension == "json" })
    }

    @Test
    fun stoppedProviderReviewDoesNotWriteMetadataOrLoseTheCatalog() {
        val cloud = connected()
        val before = model.session.tagStore.snapshot()
        val gate = CountDownLatch(1)
        val opened = CountDownLatch(1)
        FixtureDocumentsProvider.metadataDirectory =
            File(provider, "readonly/Cloud note.txt").canonicalPath
        FixtureDocumentsProvider.metadataGate = gate
        FixtureDocumentsProvider.metadataOpened = opened
        try {
            compose.runOnIdle { state.open(listOf(cloud)) }
            compose.waitUntil(5000) { opened.count == 0L }
            compose.runOnIdle { state.stop() }
            assertFalse(state.ready)
            assertFalse(state.running)
            assertEquals(before, model.session.tagStore.snapshot())
        } finally {
            gate.countDown()
            FixtureDocumentsProvider.metadataDirectory = null
            FixtureDocumentsProvider.metadataGate = null
        }
    }

    @Test
    fun fullPreviewReachesPastTenRowsAndExclusionChangesOnlyReviewedItems() {
        val entries =
            List(24) { index ->
                Entry(File(folder, "Item%02d.txt".format(index)).apply { writeText("item $index") })
            }
        opened(entries)
        compose.runOnIdle {
            state.change("Blue", TagChange.ADD)
            state.toggle(entries.last().path)
        }
        compose.onNodeWithTag("organization-tag-preview").performScrollToNode(hasText("Item23.txt"))
        compose
            .onNode(
                hasText("Item23.txt") and hasAnyAncestor(hasTestTag("organization-tag-preview")),
            )
            .assertExists()
        apply()
        assertEquals(23, model.tagAssignments.keys.count { it in entries.map { e -> e.path } })
        assertNull(model.tagAssignments[organizationKey(entries.last().path)])
    }

    @Test
    fun providerFolderRenameRemapsDescendantsAndReadOnlyItemsCanReceiveTags() {
        val cloud = connected()
        File(provider, "Plans").mkdir()
        File(provider, "Plans/Brief.txt").writeText("provider brief")
        val plans = model.documents.list(root!!).first { it.name == "Plans" }
        val child = model.documents.list(plans.path).single()
        opened(listOf(plans, child))
        val collection = newCollection("Provider references")
        compose.runOnIdle {
            state.close()
            model.navigate(root!!)
        }
        compose.waitUntil(10_000) { !model.loading && model.entries.any { it.path == plans.path } }
        compose.runOnIdle {
            model.clearSelection()
            model.select(model.entries.first { it.path == plans.path })
            model.rename("Renamed plans")
        }
        compose.waitUntil(10_000) {
            model.busy == null && File(provider, "Renamed plans/Brief.txt").exists()
        }
        val after = model.documents.list(root!!).first { it.name == "Renamed plans" }
        val afterChild = model.documents.list(after.path).single()
        assertEquals(
            setOf(after.path, afterChild.path),
            model.collections.first { it.id == collection.id }.paths.toSet(),
        )
        undo()
        assertEquals(
            setOf(plans.path, child.path),
            model.collections.first { it.id == collection.id }.paths.toSet(),
        )
        redo()
        assertEquals(
            setOf(after.path, afterChild.path),
            model.collections.first { it.id == collection.id }.paths.toSet(),
        )
        opened(listOf(after, afterChild, cloud))
        compose.runOnIdle { state.change("Green", TagChange.ADD) }
        apply()
        assertEquals(setOf("Green"), model.tagAssignments[organizationKey(cloud.path)])
        assertEquals("CLOUD_CONTENT_82736", File(provider, "readonly/Cloud note.txt").readText())
    }

    @Test
    fun protectedTagUndoRefusesAnUnrelatedLaterMetadataEdit() {
        opened(listOf(entry("First note.txt")))
        compose.runOnIdle { state.change("Blue", TagChange.ADD) }
        apply()
        compose.runOnIdle {
            state.close()
            model.session.tagStore.assign(listOf(entry("Budget.csv").path), topic)
            model.syncLibrary()
        }
        val before = model.tagAssignments
        undo()
        assertEquals(before, model.tagAssignments)
        assertTrue(model.message.orEmpty().contains("Tags changed"))
        assertNotNull(model.undoLabel)
    }

    @Test
    fun nativeTagAndCollectionEditorsSaveRenameAndDeleteWithUndo() {
        opened(emptyList())
        compose.runOnIdle { state.page = 1 }
        compose.onNodeWithText("New tag").performClick()
        compose.onNodeWithText("Tag name").performTextInput("Client review")
        compose.onNodeWithContentDescription("Green library color").performClick()
        compose.onNodeWithText("Save tag").performClick()
        settled()
        assertTrue(model.tagDefinitions.any { it.name == "Client review" && it.color == "Green" })
        compose.onNodeWithText("Find tags").performTextInput("Client review")
        compose.onNodeWithContentDescription("Edit tag Client review").performClick()
        compose.onNodeWithText("Tag name").performTextReplacement("Client archive")
        compose.onNodeWithContentDescription("Purple library color").performClick()
        compose.onNodeWithText("Save tag").performClick()
        settled()
        assertTrue(model.tagDefinitions.any { it.name == "Client archive" && it.color == "Purple" })
        compose
            .onNode(hasText("Collections", substring = false) and hasClickAction())
            .performClick()
        compose.onNodeWithContentDescription("New collection").performClick()
        compose.onNodeWithText("Collection name").performTextInput("Project library")
        compose.onNodeWithText("Save collection").performClick()
        settled()
        val collection = model.collections.first { it.name == "Project library" }
        compose.onNodeWithContentDescription("Edit collection Project library").performClick()
        compose.onNodeWithText("Collection name").performTextReplacement("Project archive")
        compose.onNodeWithContentDescription("Orange library color").performClick()
        compose.onNodeWithText("Save collection").performClick()
        settled()
        assertEquals("Orange", model.collections.first { it.id == collection.id }.color)
        compose.onNodeWithContentDescription("Delete collection Project archive").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        settled()
        assertTrue(model.collections.none { it.id == collection.id })
        undo()
        assertEquals("Project archive", model.collections.first { it.id == collection.id }.name)
    }

    @Test
    fun destinationPickerKeepsReadOnlyLocationVisibleUntilAnotherRootIsChosen() {
        val cloud = connected()
        compose.runOnIdle { model.navigate(cloud.parentPath!!) }
        compose.waitUntil(10_000) { !model.loading && model.folderInfo?.writable == false }
        opened(emptyList())
        compose.runOnIdle { state.openExport() }
        compose.onNodeWithText("Choose destination…").performClick()
        compose.waitUntil(5000) {
            compose
                .onAllNodesWithText("This location is read only. Choose another destination.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose
            .onNode(hasText("Save here") and hasAnyAncestor(hasTestTag("destination-picker")))
            .assertIsNotEnabled()
        capture("destination-readonly")
        compose
            .onNode(hasText("Workspace") and hasAnyAncestor(hasTestTag("destination-picker")))
            .performClick()
        compose.waitUntil(5000) {
            compose
                .onAllNodes(
                    hasText("Save here") and
                        isEnabled() and
                        hasAnyAncestor(hasTestTag("destination-picker")),
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(state.exportReview)
    }

    @Test
    fun corruptedLibraryBlocksMetadataWritesWithoutBlockingFileRecovery() {
        opened(listOf(entry("First note.txt")))
        newCollection()
        val file = File(compose.activity.filesDir, "tags.properties")
        val bytes = file.readBytes()
        try {
            file.writeText("catalog=unsupported\n")
            compose.runOnIdle { state.refresh() }
            ready()
            assertNotNull(model.libraryError)
            assertFalse(state.catalogReady)
            compose.runOnIdle {
                state.close()
                model.clearSelection()
                model.select(entry("First note.txt"))
                model.rename("Kept.txt")
            }
            compose.waitUntil(10_000) { model.busy == null && File(folder, "Kept.txt").exists() }
            assertNotNull(model.libraryNotice)
            assertEquals("FIRST_CONTENT_82736", File(folder, "Kept.txt").readText())
        } finally {
            file.writeBytes(bytes)
            model.session.tagStore.reload()
            compose.runOnIdle { model.syncLibrary() }
        }
        undo()
        assertEquals("FIRST_CONTENT_82736", File(folder, "First note.txt").readText())
    }
}
