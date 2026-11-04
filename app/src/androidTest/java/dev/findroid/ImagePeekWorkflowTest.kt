package dev.findroid

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.DocumentsContract
import android.view.KeyEvent
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class ImagePeekWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var folder: File
    private val model
        get() = compose.activity.model

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setup() {
        folder =
            File(compose.activity.filesDir, "Workspace/Images-${System.nanoTime()}").apply {
                mkdirs()
            }
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { i, c ->
            paint.color = c
            canvas.drawRect(
                (i % 2 * 200).toFloat(),
                (i / 2 * 150).toFloat(),
                (i % 2 * 200 + 200).toFloat(),
                (i / 2 * 150 + 150).toFloat(),
                paint,
            )
        }
        for (o in 1..8) {
            val file = File(folder, "Orientation$o.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, o.toString())
                setAttribute(ExifInterface.TAG_MAKE, "Field Studio")
                setAttribute(ExifInterface.TAG_MODEL, "Atlas X1")
                setAttribute(ExifInterface.TAG_LENS_MODEL, "24 mm study lens")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:01 06:30:00")
                setAttribute(ExifInterface.TAG_F_NUMBER, "5.6")
                setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008")
                setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "200")
                setLatLong(35.5, 139.75)
                saveAttributes()
            }
        }
        bitmap.recycle()
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.changeDensity(FinderDensity.COMFORTABLE)
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            if (model.sort != SortBy.NAME) model.changeSort(SortBy.NAME)
            if (!model.ascending) model.changeSort(SortBy.NAME)
            model.clearSelection()
            model.navigate(folder.path)
        }
        compose.waitUntil(10_000) { !model.loading && model.entries.size == 8 }
    }

    @After
    fun cleanup() {
        if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Close Quick Look").performClick()
        compose.runOnIdle {
            model.navigate(model.workspace.path)
            model.clearSelection()
        }
        folder.deleteRecursively()
    }

    private fun key(code: Int, meta: Int = 0) {
        // Dialog dismissal and input-window focus return on separate framework turns.
        compose.waitUntil(5_000) {
            android.view.inspector.WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() }
        }
        val now = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        compose.waitForIdle()
    }

    private fun open(name: String, ready: Boolean = true) {
        // A refreshed item may be beyond the composed rows of the lazy table.
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText(name) and hasClickAction())
        compose.onNode(hasText(name) and hasClickAction()).performMouseInput { click() }
        compose.waitUntil(5000) { model.focused?.name == name }
        key(KeyEvent.KEYCODE_SPACE)
        if (ready) waitReady()
    }

    private fun waitReady() {
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithContentDescription("Image detail ready")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitText(value: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(value, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun close() {
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
    }

    private fun state() =
        compose
            .onNodeWithTag("image-viewport")
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]

    private fun refresh(name: String) {
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(10_000) { !model.loading && model.entries.any { it.name == name } }
    }

    @Test
    fun nativeCanvasHonorsAllExifOrientationsAndSpaceCloses() {
        val expected =
            listOf(
                listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
                listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
                listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
                listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
                listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
                listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
                listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
            )
        for (o in 1..8) {
            open("Orientation$o.jpg")
            val image = compose.onNodeWithTag("image-viewport").captureToImage()
            val pixels = image.toPixelMap()
            val w = if (o >= 5) 300 else 400
            val h = if (o >= 5) 400 else 300
            expected[o - 1].forEachIndexed { i, c ->
                val p =
                    pixels[
                        (image.width / 2 + (if (i % 2 == 0) -1 else 1) * w / 4),
                        (image.height / 2 + (if (i / 2 == 0) -1 else 1) * h / 4)]
                assertEquals("Orientation $o quadrant $i red", Color.red(c) / 255f, p.red, .07f)
                assertEquals(
                    "Orientation $o quadrant $i green",
                    Color.green(c) / 255f,
                    p.green,
                    .07f,
                )
                assertEquals("Orientation $o quadrant $i blue", Color.blue(c) / 255f, p.blue, .07f)
            }
            if (o == 1) {
                compose.onNodeWithTag("image-viewport").performTouchInput { doubleClick(center) }
                compose.waitUntil(5000) { state().startsWith("200%") }
                compose.onNodeWithTag("image-viewport").performTouchInput { doubleClick(center) }
                compose.waitUntil(5000) { state().startsWith("100%") }
            }
            key(KeyEvent.KEYCODE_SPACE)
            compose.onNodeWithTag("peek-window").assertDoesNotExist()
        }
    }

    @Test
    fun largeSourceUsesBoundedRegionsAndNavigatorPansAtActualPixels() {
        val file = File(folder, "A-Large.png")
        png(file, 6000, 4000)
        refresh(file.name)
        runBlocking {
            val source = ImageSource.open(file, ListingControl())
            try {
                assertTrue(source.overview.width <= 2048 && source.overview.height <= 2048)
                assertTrue(source.overview.byteCount <= 4 * 1024 * 1024)
                val patch = source.patch(ImageRequest(ImagePixels(3000, 2000, 3200, 2200), 1))!!
                assertEquals(200, patch.bitmap.width)
                assertEquals(Color.YELLOW, patch.bitmap.getPixel(15, 15))
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { source.patch(ImageRequest(ImagePixels(0, 0, 6000, 4000), 1)) }
                }
            } finally {
                source.close()
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { source.patch(ImageRequest(ImagePixels(0, 0, 100, 100), 1)) }
            }
        }
        open(file.name)
        compose.onNodeWithContentDescription("Actual pixels").performClick()
        waitReady()
        assertTrue(state().startsWith("100%"))
        val before = state()
        compose.onNodeWithTag("image-navigator").performTouchInput {
            click(percentOffset(.9f, .85f))
        }
        waitReady()
        assertNotEquals(before, state())
        val after = state()
        key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_SHIFT_ON)
        assertNotEquals(after, state())
        compose.onNodeWithContentDescription("Fit image").performClick()
        compose.onNodeWithTag("image-navigator").assertDoesNotExist()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitText("Orientation1.jpg")
        waitReady()
    }

    @Test
    fun metadataLocationIsExplicitAndRotationDoesNotModifySource() {
        val file = File(folder, "Orientation6.jpg")
        val before = file.readBytes()
        val modified = file.lastModified()
        open(file.name)
        compose.onNodeWithContentDescription("Image information").performClick()
        waitText("300 × 400 pixels")
        waitText("Field Studio Atlas X1")
        compose.onNodeWithText("Copy image details").performScrollTo().performClick()
        fun copied() =
            compose.activity
                .getSystemService(ClipboardManager::class.java)
                .primaryClip!!
                .getItemAt(0)
                .text
                .toString()
        assertTrue(copied().contains("1/125 s"))
        assertFalse(copied().contains("35.500000"))
        compose.onNodeWithText("Reveal location").performScrollTo().performClick()
        compose.onNodeWithText("Copy image details").performScrollTo().performClick()
        assertTrue(copied().contains("35.500000, 139.750000"))
        if (compose.activity.resources.configuration.screenWidthDp >= 1000)
            compose.onNodeWithContentDescription("Close image information").assertIsDisplayed()
        else compose.onNodeWithText("Done", substring = false).assertIsDisplayed()
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("image-information").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rotate view clockwise").performClick()
        waitText("400 × 300")
        assertArrayEquals(before, file.readBytes())
        assertEquals(modified, file.lastModified())
        key(KeyEvent.KEYCODE_SPACE)
    }

    @Test
    fun zoomWheelPanKeyboardAndRecreationKeepTheView() {
        val file = File(folder, "A-Large.png")
        png(file, 6000, 4000)
        refresh(file.name)
        open(file.name)
        key(KeyEvent.KEYCODE_R)
        key(KeyEvent.KEYCODE_1)
        waitReady()
        val before = state()
        key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON)
        assertNotEquals(before, state())
        compose.onNodeWithTag("peek-window").performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.CtrlLeft)
        }
        try {
            compose.onNodeWithTag("image-viewport").performMouseInput {
                moveTo(center)
                scroll(-2f)
            }
        } finally {
            compose.onNodeWithTag("peek-window").performKeyInput {
                keyUp(androidx.compose.ui.input.key.Key.CtrlLeft)
            }
        }
        assertFalse(state().startsWith("100%"))
        compose.onNodeWithContentDescription("Image information").performClick()
        val saved = state()
        compose.activityRule.scenario.recreate()
        waitReady()
        assertEquals(saved, state())
        waitText("4000 × 6000")
        compose.onNodeWithTag("image-information").assertExists()
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("image-information").assertDoesNotExist()
        key(KeyEvent.KEYCODE_0)
        compose.onNodeWithTag("image-navigator").assertDoesNotExist()
        key(KeyEvent.KEYCODE_SPACE)
    }

    @Test
    fun malformedOversizedAndChangedSourcesHaveRecoverableErrors() {
        val broken = File(folder, "Broken.jpg").apply { writeText("not an image") }
        refresh(broken.name)
        open(broken.name, false)
        waitText("Couldn't read the image header")
        compose.onNodeWithText("Retry image preview").assertExists()
        close()
        val sparse = File(folder, "Sparse.jpg")
        RandomAccessFile(sparse, "rw").use { it.setLength(513L * 1024 * 1024) }
        refresh(sparse.name)
        open(sparse.name, false)
        waitText("512 MiB local preview limit")
        close()
        val file = File(folder, "Orientation1.jpg")
        runBlocking {
            val source = ImageSource.open(file, ListingControl())
            try {
                file.appendBytes(byteArrayOf(1))
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { source.patch(ImageRequest(ImagePixels(0, 0, 100, 100), 1)) }
                }
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun mimeOnlyProviderImageAndBlockedPreparationCanStop() {
        val provider =
            File(compose.activity.filesDir, "ProviderFixtures/Images-${System.nanoTime()}").apply {
                mkdirs()
            }
        val file = File(provider, "No extension")
        File(folder, "Orientation6.jpg").copyTo(file)
        File(folder, "Orientation1.jpg").copyTo(File(provider, "Gate.jpg"))
        FixtureDocumentsProvider.mimeOverrides = mapOf(file.canonicalPath to "image/jpeg")
        val gate = CountDownLatch(1)
        val opened = CountDownLatch(1)
        FixtureDocumentsProvider.transferGate = gate
        FixtureDocumentsProvider.gateOpened = opened
        val previewWasVisible = model.previewVisible
        compose.runOnIdle { if (model.previewVisible) model.togglePreview() }
        val uri =
            DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/${provider.name}",
            )
        try {
            compose.runOnIdle { model.connectLocation(uri, false) }
            compose.waitUntil(10_000) {
                !model.loading && model.entries.any { it.name == "No extension" }
            }
            open("No extension")
            compose.onNodeWithContentDescription("Image information").performClick()
            waitText("300 × 400 pixels")
            close()
            open("Gate.jpg", false)
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            val partial = File(model.focused!!.file.parentFile, "preview.tmp")
            compose.waitUntil(5000) { partial.exists() }
            compose.onNodeWithText("Stop loading image").performClick()
            waitText("Image loading stopped")
            compose.waitUntil(5000) { !partial.exists() }
            close()
            gate.countDown()
        } finally {
            gate.countDown()
            FixtureDocumentsProvider.transferGate = null
            FixtureDocumentsProvider.gateOpened = null
            FixtureDocumentsProvider.mimeOverrides = emptyMap()
            FixtureDocumentsProvider.streamCancellation = null
            if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty()) close()
            compose.runOnIdle {
                model.navigate(folder.path)
                model.connectedLocations
                    .firstOrNull { it.name == provider.name }
                    ?.let { model.disconnectLocation(it.uri) }
            }
            compose.runOnIdle {
                if (model.previewVisible != previewWasVisible) model.togglePreview()
            }
            provider.deleteRecursively()
        }
    }

    private fun png(file: File, w: Int, h: Int) {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { zip ->
            val rows =
                Array(4) { part ->
                    ByteArray(1 + w * 3).apply {
                        for (x in 0 until w) {
                            val color =
                                when {
                                    part < 2 && x < w / 2 -> Color.RED
                                    part < 2 -> Color.GREEN
                                    x < w / 2 -> Color.BLUE
                                    else -> Color.YELLOW
                                }
                            val line = part % 2 == 1 || x % 20 == 0
                            this[1 + x * 3] = (if (line) 0 else Color.red(color)).toByte()
                            this[2 + x * 3] = (if (line) 0 else Color.green(color)).toByte()
                            this[3 + x * 3] = (if (line) 0 else Color.blue(color)).toByte()
                        }
                    }
                }
            repeat(h) { y ->
                zip.write(rows[(if (y < h / 2) 0 else 2) + (if (y % 20 == 0) 1 else 0)])
            }
        }
        DataOutputStream(file.outputStream()).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, bytes: ByteArray) {
                val tag = type.toByteArray(Charsets.US_ASCII)
                output.writeInt(bytes.size)
                output.write(tag)
                output.write(bytes)
                output.writeInt(
                    CRC32()
                        .apply {
                            update(tag)
                            update(bytes)
                        }
                        .value
                        .toInt(),
                )
            }
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use {
                it.writeInt(w)
                it.writeInt(h)
                it.write(byteArrayOf(8, 2, 0, 0, 0))
            }
            chunk("IHDR", header.toByteArray())
            chunk("IDAT", compressed.toByteArray())
            chunk("IEND", byteArrayOf())
        }
    }
}
