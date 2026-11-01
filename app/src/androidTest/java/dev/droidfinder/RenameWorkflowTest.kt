@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.droidfinder

import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.exifinterface.media.ExifInterface
import dev.droidfinder.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

class RenameWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private val state
        get() = model.batchRename

    private lateinit var folder: File
    private lateinit var provider: File
    private var root: String? = null
    private var presetIds = emptySet<String>()

    @Before
    fun setup() {
        val id = System.nanoTime()
        folder = File(compose.activity.filesDir, "Workspace/Rename-$id").apply { mkdirs() }
        File(folder, "File 2.txt").writeText("second")
        File(folder, "File 10.txt").writeText("tenth")
        File(folder, "Other.txt").writeText("keep")
        provider = File(compose.activity.filesDir, "ProviderFixtures/Rename-$id").apply { mkdirs() }
        File(provider, "Source.txt").writeText("provider source")
        File(provider, "Second.txt").writeText("provider second")
        presetIds = model.session.renamePresetStore.load().map { it.id }.toSet()
        compose.runOnIdle {
            state.close()
            model.windowState.peekEntry.value = null
            model.session.history.clear()
            model.session.undoLabel.value = null
            model.session.redoLabel.value = null
            if (model.dualPane) model.toggleDualPane()
            model.changeMode(ViewMode.LIST)
            model.navigate(folder.path)
        }
        compose.waitUntil(10_000) { !model.loading && model.entries.any { it.name == "Other.txt" } }
    }

    private fun local(vararg names: String) = names.map { Entry(File(folder, it)) }

    private fun opened(entries: List<Entry> = local("File 2.txt", "File 10.txt")) {
        compose.runOnIdle { state.open(entries) }
        previewed()
    }

    private fun previewed() {
        compose.waitUntil(15_000) {
            !state.loading && !state.planning && (state.plan != null || state.error != null)
        }
        compose.waitForIdle()
    }

    private fun configure(rule: RenameRecipe) {
        compose.runOnIdle { state.configure(rule) }
        previewed()
    }

    private fun apply() {
        compose.runOnIdle { state.apply() }
        compose.waitUntil(15_000) { !state.applying && model.busy == null }
        compose.waitForIdle()
    }

    private fun connected() {
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.droidfinder.fixtures",
                "root/" + provider.name,
            )
        val location = model.documents.connect(tree, persist = false)
        root = location.uri
        compose.runOnIdle { model.session.connectedLocations.value = model.documents.locations }
        model.documents.list(location.uri)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory =
            File(compose.activity.getExternalFilesDir(null), "rename-qa").apply { mkdirs() }
        val bitmap =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot()
        assertNotNull(bitmap)
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun uri(name: String) =
        DocumentsContract.buildDocumentUriUsingTree(
                Uri.parse(root!!),
                "root/" + provider.name + "/" + name,
            )
            .toString()

    @After
    fun cleanup() {
        FixtureDocumentsProvider.transferGate?.countDown()
        FixtureDocumentsProvider.listingGate?.countDown()
        FixtureDocumentsProvider.beforeRename = null
        compose.runOnIdle { if (state.applying) model.cancelTransfer() else state.stop(false) }
        compose.waitUntil(15_000) { model.busy == null }
        compose.runOnIdle {
            state.close()
            model.session.history.clear()
            model.session.undoLabel.value = null
            model.session.redoLabel.value = null
            root?.let(model::disconnectLocation)
            model.navigate(model.workspace.path)
            val store = model.session.tagStore
            store.replace(
                store.assignments.filterKeys {
                    !it.contains(folder.name) && !it.contains(provider.name)
                },
            )
            model.session.tagAssignments.value = store.assignments
        }
        FixtureDocumentsProvider.transferGate = null
        FixtureDocumentsProvider.gateOpened = null
        FixtureDocumentsProvider.listingGate = null
        FixtureDocumentsProvider.listingGateOpened = null
        FixtureDocumentsProvider.listingDirectory = null
        model.session.renamePresetStore
            .load()
            .filter { it.id !in presetIds }
            .forEach { model.session.renamePresetStore.remove(it.id) }
        folder.deleteRecursively()
        provider.deleteRecursively()
    }

    @Test
    fun frozenSelectionNaturalOrderNumberingExclusionAndUndoWork() {
        opened()
        configure(
            RenameRecipe(
                method = RenameMethod.NUMBER,
                text = "Trip",
                start = 7,
                step = 2,
                padding = 3,
                separator = "_",
            ),
        )
        compose.runOnIdle {
            state.sort(RenameOrder.NATURAL)
            model.clearSelection()
            model.select(model.entries.first { it.name == "Other.txt" })
        }
        previewed()
        compose.onNodeWithText("Trip_007.txt").assertIsDisplayed()
        compose.onNodeWithText("Rename items").performClick()
        compose.waitUntil(10_000) { File(folder, "Trip_007.txt").exists() && model.busy == null }
        assertEquals("second", File(folder, "Trip_007.txt").readText())
        assertEquals("tenth", File(folder, "Trip_009.txt").readText())
        assertEquals("keep", File(folder, "Other.txt").readText())
        compose.runOnIdle { model.undo() }
        compose.waitUntil(10_000) { model.busy == null && File(folder, "File 2.txt").exists() }
        compose.runOnIdle { model.redo() }
        compose.waitUntil(10_000) { model.busy == null && File(folder, "Trip_009.txt").exists() }
        assertEquals("tenth", File(folder, "Trip_009.txt").readText())
    }

    @Test
    fun rowOrderMenuFilterAndExclusionDoNotChangeHiddenRenameScope() {
        opened()
        configure(RenameRecipe(method = RenameMethod.NUMBER, text = "Ordered"))
        compose.onNodeWithContentDescription("Order File 10.txt, " + folder.name).performClick()
        compose.onNodeWithText("Move up").performClick()
        previewed()
        assertEquals("File 10.txt", state.plan!!.rows.first().input.name)
        compose.onNodeWithTag("rename-preview-filter").performTextInput("File 2")
        assertEquals(2, state.plan!!.rows.size)
        compose.onNodeWithContentDescription("Include File 2.txt, " + folder.name).performClick()
        previewed()
        assertEquals(1, state.plan!!.changes.size)
        apply()
        assertEquals("tenth", File(folder, "Ordered 1.txt").readText())
        assertTrue(File(folder, "File 2.txt").exists())
    }

    @Test
    fun changedSourceAndNewDestinationAreRecheckedBeforeAnyRename() {
        opened()
        configure(RenameRecipe(method = RenameMethod.PREFIX, text = "new-"))
        File(folder, "File 2.txt").appendText("changed")
        apply()
        assertTrue(state.visible)
        assertTrue(state.error!!.contains("changed"))
        assertFalse(File(folder, "new-File 10.txt").exists())
        compose.runOnIdle { state.refresh() }
        previewed()
        File(folder, "new-File 10.txt").writeText("arrival")
        apply()
        assertEquals("arrival", File(folder, "new-File 10.txt").readText())
        assertFalse(File(folder, "new-File 2.txt").exists())
        assertTrue(state.error != null)
    }

    @Test
    fun photoMetadataIsExplicitAndMissingTokensNeverInventDates() {
        val photo = File(folder, "Photo.jpg")
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(photo).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:02:03 10:20:30")
            setAttribute(ExifInterface.TAG_MAKE, "ACME")
            setAttribute(ExifInterface.TAG_MODEL, "One")
            saveAttributes()
        }
        opened(local("Photo.jpg"))
        configure(
            RenameRecipe(
                method = RenameMethod.TEMPLATE,
                template = "{taken:yyyyMMdd}_{camera}_{index}{dotext}",
                padding = 3,
            ),
        )
        assertFalse(state.plan!!.canApply)
        compose.runOnIdle { state.loadPhotoMetadata() }
        previewed()
        assertEquals("20240203_ACME One_001.jpg", state.plan!!.changes.single().name)
        assertEquals(40, state.plan!!.changes.single().input.photo!!.width)
        assertEquals(30, state.plan!!.changes.single().input.photo!!.height)
        capture("template")
        apply()
        assertTrue(File(folder, "20240203_ACME One_001.jpg").exists())
        compose.runOnIdle { model.undo() }
        compose.waitUntil(10_000) { model.busy == null && photo.exists() }
        assertEquals(
            "2024:02:03 10:20:30",
            ExifInterface(photo).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
        )
    }

    @Test
    fun savedPresetDialogAndRecreationRetainRulesWithoutStartingRenames() {
        opened()
        configure(RenameRecipe(method = RenameMethod.NUMBER, text = "Saved", padding = 3))
        compose.onNodeWithContentDescription("Save rename preset").performClick()
        compose
            .onNode(hasSetTextAction() and hasText("Preset name"))
            .performTextInput("Native rename-" + folder.name)
        compose.onNodeWithText("Save preset").performClick()
        compose.waitUntil(10_000) { state.presetId != null && !state.presetSaving }
        compose.onNodeWithText("Save rename preset").assertDoesNotExist()
        val savedId = state.presetId!!
        compose.runOnIdle { state.toggle(File(folder, "File 2.txt").path) }
        previewed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertTrue(state.visible)
        assertEquals("Saved", state.recipe.text)
        assertTrue(File(folder, "File 2.txt").path in state.excluded)
        assertTrue(File(folder, "File 2.txt").exists())
        val saved = model.session.renamePresetStore.load().first { it.id == savedId }
        compose.runOnIdle {
            state.configure(RenameRecipe())
            state.loadPreset(saved)
        }
        previewed()
        assertEquals(3, state.recipe.padding)
        assertEquals("Saved", state.recipe.text)
    }

    @Test
    fun localCaseOnlyAndCanonicalUnicodeNamesRoundTripExactlyOnAndroid() {
        val source = File(folder, "MiXeD.txt").apply { writeText("case contents") }
        opened(local(source.name))
        configure(RenameRecipe(method = RenameMethod.CASE, letterCase = RenameLetterCase.LOWER))
        apply()
        assertTrue(File(folder, "mixed.txt").exists())
        assertFalse(source.exists())
        compose.runOnIdle { model.undo() }
        compose.waitUntil(10_000) { model.busy == null && source.exists() }
        opened(local(source.name))
        configure(
            RenameRecipe(
                method = RenameMethod.TEMPLATE,
                template = "Cafe\u0301 📷{dotext}",
                normalization = RenameNormalization.NFC,
            ),
        )
        apply()
        assertTrue(File(folder, "Café 📷.txt").exists())
        assertFalse(File(folder, "Cafe\u0301 📷.txt").exists())
        compose.runOnIdle { model.undo() }
        compose.waitUntil(10_000) { model.busy == null && source.exists() }
        assertEquals("case contents", source.readText())
    }

    @Test
    fun mixedProviderAndLocalRenamePreservesTagsAndUndoRedo() {
        connected()
        val sourceUri = uri("Source.txt")
        val tag = model.tagDefinitions.first().name
        compose.runOnIdle {
            model.session.tagStore.assign(listOf(sourceUri), tag)
            model.session.tagAssignments.value = model.session.tagStore.assignments
        }
        opened(listOf(Entry(File(folder, "File 2.txt")), model.documents.read(sourceUri)))
        configure(RenameRecipe(method = RenameMethod.PREFIX, text = "New-"))
        apply()
        val newUri = uri("New-Source.txt")
        assertTrue(File(provider, "New-Source.txt").exists())
        assertEquals(setOf(tag), model.tagAssignments[organizationKey(newUri)])
        compose.runOnIdle { model.undo() }
        compose.waitUntil(10_000) { model.busy == null && File(provider, "Source.txt").exists() }
        assertEquals(setOf(tag), model.tagAssignments[organizationKey(sourceUri)])
        assertTrue(File(folder, "File 2.txt").exists())
        compose.runOnIdle { model.redo() }
        compose.waitUntil(10_000) {
            model.busy == null && File(provider, "New-Source.txt").exists()
        }
        assertEquals(setOf(tag), model.tagAssignments[organizationKey(newUri)])
    }

    @Test
    fun stoppedProviderUndoPreparationClosesBlockedStreamBeforeWriterResumes() {
        File(provider, "Gate.bin").writeBytes(ByteArray(128 * 1024) { 7 })
        connected()
        val source = uri("Gate.bin")
        opened(listOf(model.documents.read(source)))
        configure(RenameRecipe(method = RenameMethod.PREFIX, text = "New-"))
        assertTrue(
            "The blocked-read fixture must have a complete executable preview.",
            state.plan!!.canApply,
        )
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        FixtureDocumentsProvider.transferGate = gate
        FixtureDocumentsProvider.gateOpened = entered
        compose.runOnIdle { state.apply() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        model.cancelTransfer()
        compose.waitUntil(7_000) { !state.applying && model.busy == null }
        assertEquals(1L, gate.count)
        assertTrue(File(provider, "Gate.bin").exists())
        assertFalse(File(provider, "New-Gate.bin").exists())
        assertNotNull(state.error)
    }

    @Test
    fun providerFailedRollbackPreservesArrivalAndRemapsRecoveryTags() {
        connected()
        val a = uri("Source.txt")
        val b = uri("Second.txt")
        val tag = model.tagDefinitions.first().name
        compose.runOnIdle {
            model.session.tagStore.assign(listOf(a), tag)
            model.session.tagAssignments.value = model.session.tagStore.assignments
        }
        opened(listOf(model.documents.read(a), model.documents.read(b)))
        configure(RenameRecipe(method = RenameMethod.PREFIX, text = "New-"))
        var injected = false
        FixtureDocumentsProvider.beforeRename = { _, name ->
            if (name == "New-Second.txt" && !injected) {
                injected = true
                File(provider, "Source.txt").writeText("external arrival")
                throw java.io.FileNotFoundException("Injected publication failure")
            }
        }
        apply()
        assertTrue(injected)
        assertEquals("external arrival", File(provider, "Source.txt").readText())
        assertEquals("provider second", File(provider, "Second.txt").readText())
        val recovery = provider.listFiles()!!.single { it.name == "Recovered Source.txt" }
        assertEquals("provider source", recovery.readText())
        assertEquals(setOf(tag), model.tagAssignments[organizationKey(uri(recovery.name))])
        assertNull(model.undoLabel)
        assertNotNull(state.error)
        compose.onNodeWithText("Recovery items").performClick()
        capture("recovery")
        compose.onNodeWithText("Show full locations").performClick()
        compose.onNodeWithText(uri(recovery.name)).assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { state.refresh() }
        previewed()
        assertTrue(state.inputs.any { it.id == uri(recovery.name) })
        assertFalse(state.inputs.any { it.id == a })
        apply()
        assertEquals("external arrival", File(provider, "Source.txt").readText())
        assertEquals("provider source", File(provider, "New-Recovered Source.txt").readText())
    }

    @Test
    fun skippingUndoPreparationIsExplicitAndClearsStaleHistory() {
        opened()
        configure(RenameRecipe(method = RenameMethod.PREFIX, text = "Quick-"))
        compose.runOnIdle { state.prepareUndo = false }
        compose
            .onNodeWithText("Undo preparation is off. Applying will clear session Undo and Redo.")
            .assertIsDisplayed()
        apply()
        assertTrue(File(folder, "Quick-File 2.txt").exists())
        assertNull(model.undoLabel)
        assertNull(model.redoLabel)
    }

    @Test
    fun completePreviewReachesPastTenRowsAndKeyboardFilterKeepsItsScope() {
        val files =
            (1..20).map { number ->
                File(folder, "Catalog $number.txt").apply { writeText("row $number") }
            }
        opened(files.map(::Entry))
        configure(RenameRecipe(method = RenameMethod.NUMBER, text = "Review", padding = 3))
        compose.onNodeWithTag("rename-preview-list").performScrollToNode(hasText("Review 020.txt"))
        compose.onNodeWithText("Review 020.txt").assertIsDisplayed()
        compose.onNodeWithTag("rename-preview-filter").performTextInput("Catalog")
        compose.onNodeWithTag("rename-preview-filter").performKeyInput { pressKey(Key.Spacebar) }
        compose.onNodeWithTag("rename-preview-filter").assertTextContains("Catalog ")
        assertNull(model.windowState.peekEntry.value)
        compose.onNodeWithTag("rename-preview-filter").performTextReplacement("Catalog 20")
        compose.onNodeWithText("1 of 20").assertIsDisplayed()
        assertEquals(20, state.plan!!.rows.size)
        assertEquals(20, state.plan!!.changes.size)
        assertTrue(files.all { it.exists() })
    }

    @Test
    fun providerCaseAndUnicodeNamesRetainActualContentAndUndo() {
        connected()
        opened(listOf(model.documents.read(uri("Source.txt"))))
        configure(RenameRecipe(method = RenameMethod.CASE, letterCase = RenameLetterCase.LOWER))
        apply()
        assertTrue(File(provider, "source.txt").exists())
        assertFalse(File(provider, "Source.txt").exists())
        compose.runOnIdle { model.undo() }
        compose.waitUntil(10_000) { model.busy == null && File(provider, "Source.txt").exists() }
        opened(listOf(model.documents.read(uri("Source.txt"))))
        configure(RenameRecipe(method = RenameMethod.TEMPLATE, template = "日本語_📷{dotext}"))
        apply()
        assertEquals("provider source", File(provider, "日本語_📷.txt").readText())
        compose.runOnIdle { model.undo() }
        compose.waitUntil(10_000) { model.busy == null && File(provider, "Source.txt").exists() }
        assertEquals("provider source", File(provider, "Source.txt").readText())
    }

    @Test
    fun aKnownLocalEpochDateIsNotTreatedAsMissingMetadata() {
        val source =
            File(folder, "Epoch.txt").apply {
                writeText("epoch")
                setLastModified(0)
            }
        opened(local(source.name))
        configure(
            RenameRecipe(method = RenameMethod.TEMPLATE, template = "{modified:yyyyMMdd}{dotext}"),
        )
        assertTrue(state.plan!!.canApply)
        val expected =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
                .format(java.time.Instant.EPOCH.atZone(java.time.ZoneId.of(state.plan!!.zone))) +
                ".txt"
        assertEquals(expected, state.plan!!.changes.single().name)
        apply()
        assertTrue(File(folder, expected).exists())
    }
}
