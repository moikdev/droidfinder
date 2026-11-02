@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.findroid

import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

class ReferenceRepairWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private val state
        get() = model.organization

    private val repair
        get() = state.repair

    private lateinit var original: OrganizationSnapshot
    private lateinit var base: File
    private lateinit var alias: File
    private lateinit var source: File
    private lateinit var target: File
    private lateinit var provider: File
    private var connected: String? = null

    @Before
    fun setup() {
        val id = System.nanoTime()
        original = model.session.tagStore.reload()
        base =
            File(compose.activity.filesDir, "Workspace/Reference-$id").canonicalFile.apply {
                mkdirs()
            }
        source =
            File(base, "Original/Research notes.md").apply {
                parentFile!!.mkdir()
                writeText("original payload")
            }
        target =
            File(base, "Replacement/Research notes.md").apply {
                parentFile!!.mkdir()
                writeText("replacement payload")
            }
        alias = File(base, "Alias")
        Files.createSymbolicLink(alias.toPath(), source.parentFile!!.toPath())
        provider =
            File(compose.activity.filesDir, "ProviderFixtures/Reference-$id").apply { mkdirs() }
        File(provider, "readonly").mkdir()
        File(provider, "readonly/Cloud notes.md").writeText("provider payload")
        compose.runOnIdle {
            state.repair.dismiss()
            state.locations.dismiss()
            state.close()
            model.windowState.peekEntry.value = null
            if (model.dualPane) model.toggleDualPane()
            model.changeMode(ViewMode.LIST)
            model.updateSearchRules(SearchRules())
            model.changeGrouping(GroupBy.NONE)
            model.navigate(base.path)
            model.session.history.clear()
            model.session.undoLabel.value = null
            model.session.redoLabel.value = null
        }
        loaded()
    }

    private fun loaded() {
        compose.waitUntil(15_000) { !model.loading && model.busy == null }
        compose.waitForIdle()
    }

    private fun settled() {
        compose.waitUntil(20_000) { !repair.running && !repair.applying && model.busy == null }
        compose.waitForIdle()
    }

    private fun library(paths: List<String> = listOf(source.path)): String {
        var id = ""
        compose.runOnIdle {
            model.session.tagStore.assign(paths, "Blue")
            id =
                model.session.tagStore.createCollection(
                    "Field notebooks ${base.name}",
                    "Orange",
                    paths,
                )
            model.syncLibrary()
            state.open(emptyList(), 0)
        }
        compose.waitUntil(15_000) { state.ready }
        return id
    }

    private fun review(old: String = source.path, replacement: Entry = Entry(target)) {
        compose.runOnIdle {
            repair.open(old)
            repair.select(replacement)
        }
        settled()
        assertNotNull("Repair preparation: ${repair.error}", repair.review)
    }

    private fun apply() {
        compose.runOnIdle { repair.apply() }
        settled()
    }

    private fun undo() {
        compose.runOnIdle {
            repair.dismiss()
            state.locations.dismiss()
            state.close()
            model.undo()
        }
        loaded()
    }

    private fun redo() {
        compose.runOnIdle { model.redo() }
        loaded()
    }

    private fun cloud(): Entry {
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/" + provider.name,
            )
        val root = model.documents.connect(tree, persist = false)
        connected = root.uri
        compose.runOnIdle { model.session.connectedLocations.value = model.documents.locations }
        val folder = model.documents.list(root.uri).single()
        return model.documents.list(folder.path).single()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5000)
        val directory =
            File(compose.activity.getExternalFilesDir(null), "reference-qa").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @After
    fun cleanup() {
        FixtureDocumentsProvider.metadataGate?.countDown()
        FixtureDocumentsProvider.metadataGate = null
        FixtureDocumentsProvider.metadataOpened = null
        FixtureDocumentsProvider.metadataDirectory = null
        FixtureDocumentsProvider.listingFailure = null
        FixtureDocumentsProvider.listingExtras = null
        compose.waitUntil(20_000) { model.busy == null }
        compose.runOnIdle {
            repair.dismiss()
            state.locations.dismiss()
            state.close()
            model.session.history.clear()
            model.session.undoLabel.value = null
            model.session.redoLabel.value = null
            val store = model.session.tagStore
            store.restoreOrganization(store.reload(), original)
            model.syncLibrary()
            connected?.let { model.documents.disconnect(it) }
            model.session.connectedLocations.value = model.documents.locations
            model.updateSearchRules(SearchRules())
            model.changeGrouping(GroupBy.NONE)
            model.navigate(model.workspace.path)
        }
        loaded()
        Files.deleteIfExists(alias.toPath())
        base.deleteRecursively()
        provider.deleteRecursively()
    }

    @Test
    fun aliasTagsAppearInNormalBrowsingFiltersAndRenameUndoRedo() {
        val spelling = File(alias, source.name).path
        val id = library(listOf(spelling, source.path))
        assertEquals(listOf(source.path), model.collections.first { it.id == id }.paths)
        compose.runOnIdle {
            state.close()
            model.navigate(alias.path)
            model.updateSearchRules(SearchRules(tag = "Blue"))
        }
        loaded()
        assertEquals(listOf(source.name), model.entries.map { it.name })
        val entry = model.entries.single()
        assertEquals(spelling, entry.path)
        assertEquals(source.path, entry.organizationPath)
        compose.runOnIdle {
            model.select(entry)
            model.rename("Renamed.md")
        }
        loaded()
        val renamed = File(source.parentFile, "Renamed.md")
        assertTrue(renamed.exists())
        assertEquals(setOf("Blue"), model.tagAssignments[renamed.path])
        assertEquals(listOf(renamed.path), model.collections.first { it.id == id }.paths)
        undo()
        assertEquals(listOf(source.path), model.collections.first { it.id == id }.paths)
        redo()
        assertEquals("original payload", renamed.readText())
    }

    @Test
    fun movingFolderThroughAnAliasRemapsSavedChildrenAcrossUndoRedo() {
        val child =
            File(source.parentFile, "Folder/Child.txt").apply {
                parentFile!!.mkdir()
                writeText("child")
            }
        val id = library(listOf(File(alias, "Folder").path, File(alias, "Folder/Child.txt").path))
        compose.runOnIdle {
            state.close()
            model.navigate(alias.path)
        }
        loaded()
        compose.runOnIdle {
            model.select(model.entries.first { it.name == "Folder" })
            model.rename("Renamed folder")
        }
        loaded()
        val moved = File(source.parentFile, "Renamed folder")
        assertEquals(
            listOf(moved.path, File(moved, "Child.txt").path),
            model.collections.first { it.id == id }.paths,
        )
        undo()
        assertTrue(child.exists())
        assertEquals(
            listOf(child.parent, child.path),
            model.collections.first { it.id == id }.paths,
        )
        redo()
        assertEquals("child", File(moved, "Child.txt").readText())
    }

    @Test
    fun androidAppStorageSpellingsAndFinalSymlinkEntriesHaveCorrectTagIdentity() {
        val lexical =
            File(compose.activity.filesDir, "Workspace/${base.name}/Original/${source.name}").path
        val link = File(source.parentFile, "Shortcut.md")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        compose.runOnIdle {
            model.session.tagStore.assign(listOf(lexical), "Blue")
            model.session.tagStore.assign(listOf(link.path), "Green")
            model.syncLibrary()
            model.navigate(source.parent)
        }
        loaded()
        val actual = model.entries.first { it.name == source.name }
        val shortcut = model.entries.first { it.name == link.name }
        assertEquals(setOf("Blue"), model.tagAssignments[actual.organizationPath])
        assertEquals(setOf("Green"), model.tagAssignments[shortcut.organizationPath])
        assertNotEquals(actual.organizationPath, shortcut.organizationPath)
    }

    @Test
    fun readOnlyProviderRelinkReviewSurvivesRecreationAndUndoKeepsPayloads() {
        val replacement = cloud()
        val id = library()
        compose.runOnIdle {
            model.session.tagStore.assign(listOf(replacement.path), "Green")
            model.session.tagStore.collectionItems(id, listOf(replacement.path), true)
            model.session.tagStore.createCollection(
                "Second ${base.name}",
                "Blue",
                listOf(source.path),
            )
            model.syncLibrary()
        }
        assertTrue(source.delete())
        review(replacement = replacement)
        assertFalse(replacement.writable)
        assertEquals(2, repair.review!!.repair.affectedCollections.size)
        val frozen = repair.review
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(frozen, repair.review)
        capture("provider-review")
        compose.onNodeWithText("Relink location").performClick()
        settled()
        assertEquals(setOf("Blue", "Green"), model.tagAssignments[replacement.path])
        assertEquals(listOf(replacement.path), model.collections.first { it.id == id }.paths)
        assertEquals("provider payload", File(provider, "readonly/Cloud notes.md").readText())
        undo()
        assertEquals(setOf("Blue"), model.tagAssignments[source.path])
        assertEquals(setOf("Green"), model.tagAssignments[replacement.path])
        redo()
        assertEquals(setOf("Blue", "Green"), model.tagAssignments[replacement.path])
    }

    @Test
    fun changedReplacementAndReappearingOriginalRequireAnotherReview() {
        library()
        source.delete()
        review()
        val baseline = model.session.tagStore.snapshot()
        target.appendText(" changed")
        apply()
        assertEquals(baseline, model.session.tagStore.snapshot())
        assertTrue(repair.visible)
        assertNotNull(repair.error)
        compose.runOnIdle { repair.select(Entry(target)) }
        settled()
        source.writeText("arrived again")
        apply()
        assertEquals(baseline, model.session.tagStore.snapshot())
        assertTrue(repair.error!!.contains("original location"))
        compose.runOnIdle { repair.select(Entry(target)) }
        settled()
        apply()
        assertEquals("arrived again", source.readText())
        assertEquals("replacement payload changed", target.readText())
    }

    @Test
    fun catalogChangesBlockRepairAndLaterEditsBlockUndoWithoutLosingMetadata() {
        library()
        review()
        compose.runOnIdle { model.session.tagStore.define("Later ${base.name}", "Purple") }
        apply()
        assertNotNull(repair.error)
        assertTrue(source.path in model.session.tagStore.assignments)
        compose.runOnIdle { repair.select(Entry(target)) }
        settled()
        apply()
        compose.runOnIdle {
            model.session.tagStore.createCollection(
                "Later collection ${base.name}",
                "Blue",
                listOf(target.path),
            )
        }
        undo()
        assertTrue(target.path in model.session.tagStore.assignments)
        assertTrue(
            model.session.tagStore.collections.any { it.name.startsWith("Later collection") },
        )
        assertNotNull(model.message)
    }

    @Test
    fun collectionsOnlyRelinkKeepsOriginalTagsAndFiles() {
        val id = library()
        review()
        compose.runOnIdle { repair.tags(false) }
        apply()
        assertEquals(listOf(target.path), model.collections.first { it.id == id }.paths)
        assertEquals(setOf("Blue"), model.tagAssignments[source.path])
        assertNull(model.tagAssignments[target.path])
        assertEquals("original payload", source.readText())
        assertEquals("replacement payload", target.readText())
        undo()
        assertEquals(listOf(source.path), model.collections.first { it.id == id }.paths)
    }

    @Test
    fun opaqueProviderIdRepairDoesNotGuessChildIdsOrGrantAccess() {
        val replacement = cloud()
        val root = Uri.parse(connected!!)
        val old =
            DocumentsContract.buildDocumentUriUsingTree(
                    root,
                    "root/${provider.name}/missing-opaque-id",
                )
                .toString()
        val child =
            DocumentsContract.buildDocumentUriUsingTree(
                    root,
                    "root/${provider.name}/other-opaque-id",
                )
                .toString()
        val id = library(listOf(old, child))
        review(old, replacement)
        apply()
        assertEquals(listOf(replacement.path, child), model.collections.first { it.id == id }.paths)
        assertEquals(setOf("Blue"), model.tagAssignments[child])
        assertFalse(model.documents.read(replacement.path).writable)
        undo()
        assertEquals(listOf(old, child), model.collections.first { it.id == id }.paths)
    }

    @Test
    fun savedLocationsPagesIncludeTagOnlyAndUnavailableItemsAndSearchCollectionNames() {
        library()
        val names = (0..204).map { File(base, "Saved-$it.txt").path }
        compose.runOnIdle {
            model.session.tagStore.assign(names, "Blue")
            model.syncLibrary()
            state.locations.open()
            state.locations.search(base.name + "/Saved-")
        }
        compose.waitUntil(20_000) { !state.locations.running }
        assertEquals(205, state.locations.total)
        assertEquals(200, state.locations.rows.size)
        assertTrue(state.locations.rows.all { it.item.problem != null && it.tags == setOf("Blue") })
        compose.onNodeWithText("Next").performClick()
        compose.waitUntil(20_000) { !state.locations.running }
        assertEquals(5, state.locations.rows.size)
        assertEquals(1, state.locations.page)
        compose.onNodeWithText("201–205 of 205").assertExists()
        capture("saved-locations")
        compose.runOnIdle { state.locations.search("Field notebooks ${base.name}") }
        compose.waitUntil(20_000) { !state.locations.running }
        assertEquals(1, state.locations.total)
        assertEquals(source.path, state.locations.rows.single().item.path)
    }

    @Test
    fun nativeLocationDetailsCanChooseAReadOnlyReplacementWithoutWritingOnOpen() {
        val replacement = cloud()
        library()
        source.delete()
        compose.runOnIdle {
            state.locations.open()
            state.locations.search(source.path)
        }
        compose.waitUntil(15_000) { !state.locations.running }
        compose
            .onNodeWithContentDescription("Details for saved location ${source.name}")
            .performClick()
        compose.onNodeWithText("Locate replacement…").performClick()
        compose.runOnIdle { repair.chooseFolder(replacement.parentPath!!) }
        settled()
        val baseline = model.session.tagStore.snapshot()
        capture("picker")
        compose.onNodeWithText(replacement.name).performClick()
        settled()
        assertNotNull(repair.review)
        assertEquals(baseline, model.session.tagStore.snapshot())
        compose.onNodeWithContentDescription("Close Review relink").performClick()
        assertEquals(baseline, model.session.tagStore.snapshot())
        assertTrue(state.locations.visible)
        review(replacement = replacement)
        apply()
        compose.waitUntil(15_000) { !state.locations.running }
        assertEquals(0, state.locations.total)
        assertTrue(state.locations.rows.isEmpty())
    }

    @Test
    fun stoppedProviderPreparationDoesNotPublishOrReviveLateResults() {
        val replacement = cloud()
        library()
        val gate = CountDownLatch(1)
        val opened = CountDownLatch(1)
        FixtureDocumentsProvider.metadataDirectory = File(provider, "readonly").canonicalPath
        FixtureDocumentsProvider.metadataGate = gate
        FixtureDocumentsProvider.metadataOpened = opened
        val baseline = model.session.tagStore.snapshot()
        compose.runOnIdle {
            repair.open(source.path)
            repair.chooseFolder(replacement.parentPath!!)
        }
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        compose.runOnIdle { repair.stop() }
        gate.countDown()
        compose.waitForIdle()
        assertFalse(repair.running)
        assertNull(repair.review)
        assertEquals(baseline, model.session.tagStore.snapshot())
        assertTrue(repair.error!!.contains("stopped"))
    }

    @Test
    fun replacementSearchCanReachBeyondInitialListingBudget() {
        library()
        val folder = File(base, "Many").apply { mkdir() }
        repeat(1005) { File(folder, "Candidate-$it.txt").writeText("") }
        compose.runOnIdle {
            repair.open(source.path)
            repair.chooseFolder(folder.path)
        }
        settled()
        assertEquals(1000, repair.entries.size)
        assertTrue(repair.listing.resultLimit)
        compose.runOnIdle { repair.search("Candidate-1004.txt") }
        settled()
        assertEquals(listOf("Candidate-1004.txt"), repair.entries.map { it.name })
        assertFalse(repair.listing.incomplete)
        assertNull(repair.review)
    }

    @Test
    fun retargetingAParentAliasToAnotherEntryRequiresNewReview() {
        library()
        val parentAlias = File(base, "Replacement alias")
        val other = File(base, "Other entry").apply { mkdir() }
        val otherLink = File(other, target.name)
        target.copyTo(otherLink)
        assertTrue(otherLink.setLastModified(target.lastModified()))
        assertEquals(target.length(), otherLink.length())
        assertEquals(target.lastModified(), otherLink.lastModified())
        Files.createSymbolicLink(parentAlias.toPath(), target.parentFile!!.toPath())
        try {
            review(replacement = Entry(File(parentAlias, target.name)))
            val before = model.session.tagStore.snapshot()
            Files.delete(parentAlias.toPath())
            Files.createSymbolicLink(parentAlias.toPath(), other.toPath())
            apply()
            assertNotNull(repair.error)
            assertEquals(before, model.session.tagStore.snapshot())
            assertTrue(repair.visible)
            compose.runOnIdle { repair.select(Entry(File(parentAlias, target.name))) }
            settled()
            apply()
            assertEquals(setOf("Blue"), model.tagAssignments[otherLink.path])
            assertNull(model.tagAssignments[target.path])
            assertEquals("replacement payload", target.readText())
            undo()
            assertEquals(before, model.session.tagStore.snapshot())
        } finally {
            Files.deleteIfExists(parentAlias.toPath())
        }
    }

    @Test
    fun savedProviderSpellingRemainsRepairableWhenMetadataReturnsCanonicalUri() {
        val replacement = cloud()
        val saved = replacement.path.replace("%2F", "%2f")
        assertNotEquals(saved, replacement.path)
        val id = library(listOf(saved))
        compose.runOnIdle {
            state.locations.open()
            state.locations.search(saved)
        }
        compose.waitUntil(15_000) { !state.locations.running }
        val row = state.locations.rows.single().item
        assertEquals(saved, row.path)
        assertEquals(replacement.path, row.entry!!.path)
        compose
            .onNodeWithContentDescription("Details for saved location ${replacement.name}")
            .performClick()
        compose.onNodeWithText("Locate replacement…").performClick()
        compose.runOnIdle { repair.select(replacement) }
        settled()
        apply()
        assertEquals(listOf(replacement.path), model.collections.first { it.id == id }.paths)
        assertEquals(setOf("Blue"), model.tagAssignments[replacement.path])
        assertNull(model.tagAssignments[saved])
        assertEquals("provider payload", File(provider, "readonly/Cloud notes.md").readText())
        undo()
        assertEquals(listOf(saved), model.collections.first { it.id == id }.paths)
    }
}
