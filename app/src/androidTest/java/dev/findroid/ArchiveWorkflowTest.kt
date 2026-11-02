package dev.findroid

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.provider.DocumentsContract
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class ArchiveWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var folder: File
    private lateinit var savedLimits: ZipLimits
    private val model
        get() = compose.activity.model

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setup() {
        savedLimits = ZipLimitsStore(compose.activity).read()
        ZipLimitsStore(compose.activity).write(ZipLimits())
        folder =
            File(compose.activity.filesDir, "Workspace/Archive-${System.nanoTime()}").apply {
                mkdirs()
            }
        val picture = ByteArrayOutputStream()
        Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(60, 100, 125))
            compress(Bitmap.CompressFormat.PNG, 100, picture)
            recycle()
        }
        val pdfBytes = ByteArrayOutputStream()
        val pdf = PdfDocument()
        repeat(2) { page ->
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(400, 500, page + 1).create())
            p.canvas.drawText(
                "Archive guide ${page + 1}",
                30f,
                60f,
                Paint().apply { textSize = 22f },
            )
            pdf.finishPage(p)
        }
        pdf.writeTo(pdfBytes)
        pdf.close()
        val video = instrumentation.context.assets.open("peek-sample.mp4").use { it.readBytes() }
        zip(
            File(folder, "Package.zip"),
            linkedMapOf(
                "Docs/Brief.md" to "# Ocean archive\n\nA file inside a ZIP.".toByteArray(),
                "Docs/Data.csv" to "Name,Count\nOcean,3\n".toByteArray(),
                "Docs/Guide.pdf" to pdfBytes.toByteArray(),
                "Media/Photo.png" to picture.toByteArray(),
                "Media/Video.mp4" to video,
                "Notes.txt" to "Notes remain in the archive".toByteArray(),
                "Empty/" to byteArrayOf(),
            ),
        )
        val broken = File(folder, "Broken.zip")
        val payload = "CORRUPTION_PAYLOAD".toByteArray()
        zip(broken, mapOf("Bad.txt" to payload), true)
        val bytes = broken.readBytes()
        val at =
            bytes.indices.first { i ->
                i + payload.size <= bytes.size &&
                    payload.indices.all { bytes[i + it] == payload[it] }
            }
        bytes[at] = (bytes[at].toInt() xor 1).toByte()
        broken.writeBytes(bytes)
        zip(
            File(folder, "Limits.zip"),
            mapOf("Large.bin" to ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }),
            true,
        )
        instrumentation.context.assets.open("zip64-small.zip").use { input ->
            File(folder, "ZIP64.zip").outputStream().use(input::copyTo)
        }
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.changeDensity(FinderDensity.COMFORTABLE)
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            model.clearSelection()
            model.navigate(folder.path)
        }
        compose.waitUntil(10_000) {
            !model.loading && model.entries.any { it.name == "Package.zip" }
        }
    }

    private fun zip(file: File, entries: Map<String, ByteArray>, stored: Boolean = false) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (path, bytes) ->
                val e = ZipEntry(path)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.compressedSize = e.size
                    e.crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(e)
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    @After
    fun cleanup() {
        if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Close Quick Look").performClick()
        if (
            compose
                .onAllNodesWithContentDescription("Close archive")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
            compose.onNodeWithContentDescription("Close archive").performClick()
        ZipLimitsStore(compose.activity).write(savedLimits)
        compose.runOnIdle {
            model.navigate(model.workspace.path)
            model.clearSelection()
        }
        folder.deleteRecursively()
    }

    private fun open(name: String = "Package.zip") {
        compose.onNode(hasText(name) and hasClickAction()).performMouseInput { click() }
        compose.waitUntil(5000) { model.focused?.name == name }
        if (compose.onAllNodesWithContentDescription("Browse archive").fetchSemanticsNodes().isEmpty()) {
            // Compact toolbars omit this button; tapping the ZIP is the normal phone open action.
            compose.onNode(hasText(name) and hasClickAction()).performClick()
        } else {
            compose.onNodeWithContentDescription("Browse archive").performClick()
        }
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithText("ZIP archive · read-only contents")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        try {
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithContentDescription("Select visible archive items")
                    .fetchSemanticsNodes()
                    .isNotEmpty() &&
                    compose
                        .onAllNodes(hasText("Extract here") and isEnabled())
                        .fetchSemanticsNodes()
                        .isNotEmpty()
            }
        } catch (error: Exception) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(compose.activity.filesDir, "archive-open-failure.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            throw AssertionError(
                "Archive $name; canCreate=${model.canCreate}; busy=${model.busy}; limits=${ZipLimitsStore(compose.activity).read()}\n${compose.onNodeWithTag("archive-browser").printToString()}",
                error,
            )
        }
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun waitText(value: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(value, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun searchSelectionExtractionAndUndoPreserveArchiveAndFolders() {
        val source = File(folder, "Package.zip")
        val before = source.readBytes()
        open()
        compose.onNodeWithTag("archive-search").performTextInput("Brief")
        compose.onNodeWithTag("archive-search").performImeAction()
        waitText("Brief.md")
        compose.onNodeWithContentDescription("Select Docs/Brief.md").performClick()
        compose.waitUntil(5000) {
            compose
                .onAllNodes(hasText("Extract selected") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Extract selected").performClick()
        compose.waitUntil(15_000) {
            model.busy == null && File(folder, "Package/Docs/Brief.md").exists()
        }
        assertFalse(File(folder, "Package/Docs/Data.csv").exists())
        assertFalse(File(folder, "Package/Media").exists())
        assertArrayEquals(before, source.readBytes())
        compose.runOnIdle { model.undo() }
        compose.waitUntil(10_000) { model.busy == null && !File(folder, "Package").exists() }
        compose.runOnIdle { model.redo() }
        compose.waitUntil(10_000) {
            model.busy == null && File(folder, "Package/Docs/Brief.md").exists()
        }
    }

    @Test
    fun memberPeekSupportsMarkdownCsvPdfImagesVideoAndSpaceClose() {
        val source = File(folder, "Package.zip")
        val before = source.readBytes()
        open()
        compose.onNodeWithContentDescription("Browse Docs").performClick()
        compose.onNodeWithContentDescription("Preview Docs/Brief.md").performClick()
        waitText("Ocean archive")
        waitText("Package.zip / Docs/Brief.md")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("csv-table").fetchSemanticsNodes().isNotEmpty()
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitText("Page 1 of 2")
        key(KeyEvent.KEYCODE_SPACE)
        compose.onNodeWithTag("archive-browser").assertExists()
        compose.onNodeWithContentDescription("Parent archive folder").performClick()
        compose.onNodeWithContentDescription("Browse Media").performClick()
        compose.onNodeWithContentDescription("Preview Media/Photo.png").performClick()
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithContentDescription("Preview of Photo.png")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(15_000) {
            compose
                .onAllNodes(hasContentDescription("Play video") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Play video").performClick()
        compose.onNodeWithContentDescription("Pause video").assertExists()
        key(KeyEvent.KEYCODE_SPACE)
        assertFalse(File(folder, "Package").exists())
        assertArrayEquals(before, source.readBytes())
    }

    @Test
    fun integrityCheckDistinguishesValidCorruptAndZip64() {
        open()
        compose.onNodeWithContentDescription("Check ZIP integrity").performClick()
        waitText("All 6 files passed size/CRC checks")
        compose.onNodeWithContentDescription("Close archive").performClick()
        open("Broken.zip")
        compose.onNodeWithContentDescription("Check ZIP integrity").performClick()
        waitText("1 file failed")
        compose.onNodeWithText("Details").performClick()
        waitText("Bad.txt")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Close archive").performClick()
        assertEquals(22L, inspectZip(File(folder, "ZIP64.zip")).size)
        open("ZIP64.zip")
        compose.onNodeWithContentDescription("Check ZIP integrity").performClick()
        waitText("1 file passed size/CRC checks")
        compose.onNodeWithContentDescription("Preview Readme.txt").performClick()
        waitText("ZIP64 preview fixture")
    }

    @Test
    fun limitsKeepSelectionAndBlockOversizedPreviewAndExtraction() {
        open("Limits.zip")
        compose.onNodeWithContentDescription("Select Large.bin").performClick()
        compose.onNodeWithContentDescription("ZIP limits").performClick()
        compose
            .onNodeWithText("Peek cache per file (MiB)")
            .performScrollTo()
            .performTextReplacement("1")
        compose.onNodeWithText("Apply limits").performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(hasText("Extract selected") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Preview Large.bin").performClick()
        waitText("exceeds the archive Peek limit")
        compose.onNodeWithContentDescription("ZIP limits").performClick()
        compose.onNodeWithText("Expanded data (MiB)").performTextReplacement("1")
        compose.onNodeWithText("Per file (MiB)").performTextReplacement("1")
        compose.onNodeWithText("Apply limits").performClick()
        waitText("exceeds the per-file limit")
        compose.onNodeWithText("Extract selected").assertIsNotEnabled()
    }

    @Test
    fun archiveSearchOwnsSpaceAndMouseContextActionsWork() {
        open()
        compose.onNodeWithTag("archive-search").performTextInput("Read")
        key(KeyEvent.KEYCODE_SPACE)
        // Slow emulators may repeat a held native key; verify that search owns its spaces.
        compose.onNodeWithTag("archive-search").assertTextContains("Read ", substring = true)
        compose.onNodeWithContentDescription("Clear archive search").performClick()
        compose.onNodeWithText("Notes.txt").performMouseInput {
            click(button = MouseButton.Secondary)
        }
        compose.onNodeWithText("Peek").performClick()
        waitText("Notes remain in the archive")
        key(KeyEvent.KEYCODE_SPACE)
        compose.onNodeWithTag("archive-browser").assertExists()
    }

    @Test
    fun providerArchiveSelectedMembersPublishAndUndo() {
        val provider =
            File(compose.activity.filesDir, "ProviderFixtures/Archive-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(folder, "Package.zip").copyTo(File(provider, "Provider.zip"))
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/${provider.name}",
            )
        try {
            compose.runOnIdle { model.connectLocation(tree, false) }
            compose.waitUntil(10_000) {
                !model.loading && model.entries.any { it.name == "Provider.zip" }
            }
            open("Provider.zip")
            compose.onNodeWithContentDescription("Select Docs").performClick()
            compose.onNodeWithText("Extract selected").performClick()
            compose.waitUntil(15_000) {
                model.busy == null && File(provider, "Provider/Docs/Data.csv").exists()
            }
            assertFalse(File(provider, "Provider/Media").exists())
            compose.runOnIdle { model.undo() }
            compose.waitUntil(10_000) { model.busy == null && !File(provider, "Provider").exists() }
        } finally {
            if (
                compose
                    .onAllNodesWithContentDescription("Close archive")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            )
                compose.onNodeWithContentDescription("Close archive").performClick()
            compose.runOnIdle {
                model.navigate(folder.path)
                model.connectedLocations
                    .firstOrNull { it.uri.contains(provider.name) }
                    ?.let { model.disconnectLocation(it.uri) }
            }
            provider.deleteRecursively()
        }
    }
}
