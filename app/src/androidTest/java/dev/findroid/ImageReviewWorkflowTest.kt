package dev.findroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import java.security.MessageDigest
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class ImageReviewWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = compose.activity.model
    private val state get() = model.imageReview
    private lateinit var folder: File
    private lateinit var original: OrganizationSnapshot
    private lateinit var hashes: Map<String, List<Byte>>
    @Before fun setup() {
        original = model.session.tagStore.reload()
        folder = File(model.workspace, "ReviewQA-${System.nanoTime()}").apply { check(mkdirs()) }
        for ((index, name) in listOf("01 Morning.png", "02 Noon.png", "03 Evening.png").withIndex()) {
            val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); val paint = Paint()
            canvas.drawColor(Color.rgb(160 + index * 30, 205 - index * 12, 225 - index * 25))
            paint.color = Color.rgb(38, 115, 145); canvas.drawRect(0f, 280f, 900f, 600f, paint)
            paint.color = Color.rgb(245, 218, 150); canvas.drawCircle(220f + index * 220, 180f, 55f, paint)
            paint.color = Color.rgb(36, 63, 71)
            canvas.drawPath(android.graphics.Path().apply { moveTo(0f,520f); lineTo(360f,310f); lineTo(540f,600f); lineTo(0f,600f); close() }, paint)
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        hashes = currentHashes()
        compose.runOnIdle {
            state.close(); model.organization.close()
            if (model.dualPane) model.toggleDualPane()
            if (model.previewVisible) model.togglePreview()
            model.changeMode(ViewMode.LIST); model.changeGrouping(GroupBy.NONE); model.clearSelection(); model.navigate(folder.path)
        }
        compose.waitUntil(15_000) { !model.loading && model.entries.size == 3 }
    }
    private fun currentHashes() = folder.listFiles()!!.associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
    private fun photos() = model.entries.filter { it.reviewableImage }
    private fun click(label: String) {
        val node = compose.onNodeWithText(label, substring=false)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }
    private fun ready() = compose.waitUntil(20_000) { compose.onAllNodesWithTag("image-viewport").fetchSemanticsNodes().isNotEmpty() }
    private fun open() {
        compose.runOnIdle { model.clearSelection(); photos().forEach { model.select(it, multiple=true) } }
        compose.onNodeWithContentDescription("Actions").performClick()
        click("Review images…"); ready()
    }
    private fun organizeReady() {
        compose.waitUntil(15_000) { !model.organization.running && (model.organization.ready || model.organization.error != null) }
    }
    private fun capture(name: String) {
        val output = File(compose.activity.cacheDir, "image-review-qa").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { compose.onNodeWithTag("image-review").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @After fun cleanup() {
        compose.waitUntil(15_000) { model.busy == null }
        compose.runOnIdle {
            model.organization.close(); state.close()
            model.session.history.resetSavedHistory(); model.syncHistory()
            model.session.tagStore.restore(original.tags.definitions, original.tags.assignments); model.syncLibrary()
            model.navigate(model.workspace.path); model.clearSelection()
        }
        assertEquals(hashes, currentHashes()); check(folder.deleteTreeWithoutLinks())
    }
    @Test fun menuFlagsCandidatesFiltersAndComparesReferenceWithoutChangingBrowserSelection() {
        open(); val selection = model.selected.toSet()
        click("Use as reference"); click("Flag image"); click("Next image"); click("Next image"); click("Flag image")
        click("Show flagged only")
        assertEquals(listOf(photos()[0].path, photos()[2].path), state.selection!!.shown)
        capture("flagged-review")
        click("Compare with reference…")
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("image-comparison").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Left image").assertIsDisplayed(); compose.onNodeWithContentDescription("Right image").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close file comparison").performClick(); ready()
        click("Unflag image"); assertEquals(photos()[0].path, state.selection!!.active)
        click("Unflag image"); assertFalse(state.selection!!.flaggedOnly); assertEquals(selection, model.selected)
    }
    @Test fun taggingOnlyFlaggedCandidatesNeedsApplyAndSupportsUndoRedo() {
        val entries = photos()
        compose.runOnIdle { model.session.tagStore.assign(listOf(entries[1].path), "Blue"); model.syncLibrary() }
        val before = model.tagAssignments
        open(); click("Flag image"); click("Next image"); click("Next image"); click("Flag image"); click("Tag flagged…"); organizeReady()
        assertEquals(setOf(entries[0].path, entries[2].path), model.organization.included.map { it.path }.toSet())
        compose.runOnIdle { model.organization.change("Green", TagChange.ADD) }
        assertEquals(before, model.tagAssignments)
        compose.onNodeWithContentDescription("Close organization library").performClick(); ready()
        assertEquals(before, model.tagAssignments); assertEquals(2, state.selection!!.flagged.size)
        click("Tag flagged…"); organizeReady()
        compose.runOnIdle { model.organization.change("Green", TagChange.ADD) }
        click("Apply tags")
        compose.waitUntil(15_000) { model.busy == null && !model.organization.applying }
        assertEquals(setOf("Green"), model.tagAssignments[entries[0].organizationPath])
        assertEquals(setOf("Blue"), model.tagAssignments[entries[1].organizationPath])
        assertEquals(setOf("Green"), model.tagAssignments[entries[2].organizationPath])
        compose.onNodeWithContentDescription("Close organization library").performClick(); ready()
        compose.onNodeWithContentDescription("Close image review").performClick()
        compose.runOnIdle { model.undo() }; compose.waitUntil(15_000) { model.busy == null }; assertEquals(before, model.tagAssignments)
        compose.runOnIdle { model.redo() }; compose.waitUntil(15_000) { model.busy == null }
        assertEquals(setOf("Green"), model.tagAssignments[entries[0].organizationPath]); assertEquals(setOf("Blue"), model.tagAssignments[entries[1].organizationPath])
    }
    @Test fun frozenOrderFlagsAndReferenceSurviveActivityRecreation() {
        open(); click("Use as reference"); click("Next image"); click("Flag image"); click("Show flagged only")
        val review = state.selection!!; val selected = model.selected.toSet()
        compose.activityRule.scenario.recreate(); ready()
        assertEquals(review, state.selection); assertEquals(selected, model.selected)
        capture("retained-review")
        click("Show all images"); click("Clear flags"); assertTrue(state.selection!!.flagged.isEmpty())
    }
    @Test fun keyboardNavigationFlagsAndViewerZoomAreScopedToReview() {
        open()
        compose.onNodeWithTag("image-review").performKeyInput { pressKey(Key.F); pressKey(Key.DirectionRight); pressKey(Key.F) }
        assertEquals(2, state.selection!!.flagged.size); assertEquals(photos()[1].path, state.selection!!.active)
        ready(); compose.onNodeWithTag("image-review").performKeyInput { pressKey(Key.One) }
        compose.onNodeWithTag("image-viewport").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "100% · center 0.500, 0.500"))
        compose.onNodeWithTag("image-review").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.DirectionRight); keyUp(Key.ShiftLeft) }
        assertEquals(photos()[1].path, state.selection!!.active)
        compose.onNodeWithTag("image-review").performKeyInput { pressKey(Key.Escape) }
        assertNull(state.selection); assertEquals(3, model.selected.size)
    }
    @Test fun mixedSelectionGetsAnExplanationAndNoReview() {
        val note = File(folder, "Notes.txt").apply { writeText("not an image") }
        try {
            compose.runOnIdle { model.clearSelection(); model.refresh() }; compose.waitUntil(10_000) { !model.loading && model.entries.size == 4 }
            compose.runOnIdle { model.select(model.entries.first { it.name == note.name }); model.select(photos().first(), multiple=true) }
            compose.onNodeWithContentDescription("Actions").performClick(); click("Review images…")
            compose.onNodeWithText("Select 1–200 images", substring=true).assertIsDisplayed(); assertNull(state.selection)
        } finally { check(note.delete()) }
    }
    @Test fun closeDiscardsOnlySessionDecisionsAndNewReviewStartsClean() {
        open(); click("Flag image"); click("Use as reference")
        compose.onNodeWithContentDescription("Close image review").performClick(); assertNull(state.selection)
        open(); assertTrue(state.selection!!.flagged.isEmpty()); assertNull(state.selection!!.reference)
        compose.onNodeWithText("Previous image", substring=false).performScrollTo().assertIsNotEnabled()
        click("Next image"); click("Next image"); compose.onNodeWithText("Next image", substring=false).performScrollTo().assertIsNotEnabled()
    }
    @Test fun unreadableImageCanBeSkippedWithoutLosingTheReview() {
        val broken = File(folder, "00 Broken.png").apply { writeText("not an image") }
        try {
            compose.runOnIdle { state.open(listOf(Entry(broken), photos().first())) }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Couldn't read the image header.", substring=false).fetchSemanticsNodes().isNotEmpty() }
            capture("unreadable-review")
            click("Next image"); ready(); assertEquals(photos().first().path, state.selection!!.active)
        } finally { compose.runOnIdle { state.close() }; check(broken.delete()) }
    }
    @Test fun removedCandidateCannotAcquireTagsFromAStaleReview() {
        open(); click("Flag image"); click("Tag flagged…"); organizeReady()
        val image = state.active!!.file; val bytes = image.readBytes(); check(image.delete())
        try {
            compose.runOnIdle { model.organization.change("Green", TagChange.ADD) }
            click("Apply tags"); compose.waitUntil(15_000) { model.busy == null && !model.organization.applying }
            assertEquals(original.tags.assignments, model.tagAssignments); assertNotNull(model.organization.error)
        } finally { image.writeBytes(bytes) }
    }
    @Test fun readOnlyProviderImagesCanBeReviewedAndTaggedWithoutWritingPhotoBytes() {
        val provider = File(compose.activity.filesDir, "ProviderFixtures/Review-${System.nanoTime()}/readonly").apply { check(mkdirs()) }
        val image = File(provider, "Cloud.png").apply { writeBytes(File(folder, "01 Morning.png").readBytes()) }
        val bytes = image.readBytes()
        val uri = DocumentsContract.buildTreeDocumentUri("dev.findroid.fixtures", "root/${provider.parentFile!!.name}")
        var root: String? = null
        try {
            val location = model.documents.connect(uri, persist=false); root = location.uri
            val parent = model.documents.list(location.uri).single(); val cloud = model.documents.list(parent.path).single()
            assertFalse(cloud.writable)
            compose.runOnIdle { model.session.connectedLocations.value = model.documents.locations; state.open(listOf(cloud)) }; ready()
            click("Flag image"); click("Tag flagged…"); organizeReady()
            compose.runOnIdle { model.organization.change("Green", TagChange.ADD) }; click("Apply tags")
            compose.waitUntil(15_000) { model.busy == null && !model.organization.applying }
            assertEquals(setOf("Green"), model.tagAssignments[cloud.organizationPath]); assertArrayEquals(bytes, image.readBytes())
        } finally {
            compose.runOnIdle { model.organization.close(); state.close(); root?.let { model.documents.disconnect(it) }; model.session.connectedLocations.value = model.documents.locations }
            check(provider.parentFile!!.deleteTreeWithoutLinks())
        }
    }
}
