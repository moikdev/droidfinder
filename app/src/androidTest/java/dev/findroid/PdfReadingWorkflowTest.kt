package dev.findroid

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import java.security.MessageDigest
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class PdfReadingWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = compose.activity.model

    private lateinit var fixture: File
    private lateinit var guide: File
    private val titles =
        listOf(
            "A calmer workspace",
            "Ocean light",
            "Files within reach",
            "Working with tabs",
            "Keep your place",
            "Ready for the next idea",
        )

    @Before
    fun setup() {
        fixture =
            File(compose.activity.filesDir, "Workspace/Pdf-${System.nanoTime()}").apply { mkdirs() }
        guide = File(fixture, "Field guide.pdf")
        val pdf = PdfDocument()
        try {
            titles.forEachIndexed { index, title ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 780, index + 1).create())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                page.canvas.drawColor(Color.WHITE)
                paint.color = Color.rgb(37, 101, 212)
                page.canvas.drawRect(40f, 38f, 68f, 43f, paint)
                paint.textSize = 11f
                paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                page.canvas.drawText("FINDROID / FIELD NOTES", 40f, 70f, paint)
                paint.color = Color.rgb(36, 46, 58)
                paint.textSize = 30f
                page.canvas.drawText(title, 40f, 137f, paint)
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 14f
                val lines =
                    listOf(
                        "A thoughtful file explorer for the Android tablet.",
                        "Browse, organize and preview without losing your place.",
                        if (index in listOf(1, 3))
                            "Ocean light makes room for a clearer perspective."
                        else "Simple controls bring the important details into focus.",
                        "Use tabs for separate projects and Peek for a closer look.",
                    )
                lines.forEachIndexed { i, line ->
                    page.canvas.drawText(line, 40f, 190f + 30f * i, paint)
                }
                paint.color = Color.rgb(236, 242, 250)
                page.canvas.drawRect(40f, 360f, 560f, 650f, paint)
                paint.color = Color.rgb(69, 114, 162)
                paint.textSize = 22f
                page.canvas.drawText("0${index + 1}", 66f, 410f, paint)
                paint.textSize = 12f
                page.canvas.drawText("Explore your workspace", 66f, 450f, paint)
                paint.color = Color.GRAY
                paint.textSize = 10f
                page.canvas.drawText("Findroid — preview study", 40f, 742f, paint)
                page.canvas.drawText("${index + 1} / 6", 530f, 742f, paint)
                pdf.finishPage(page)
            }
            guide.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
        PDFBoxResourceLoader.init(compose.activity.applicationContext)
        val outlined = File(fixture, "outlined.pdf")
        PDDocument.load(guide).use { doc ->
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            listOf(0, 3, 5).forEach { at ->
                outline.addLast(
                    PDOutlineItem().apply {
                        title = titles[at]
                        destination = PDPageFitDestination().apply { page = doc.getPage(at) }
                    },
                )
            }
            doc.save(outlined)
        }
        check(guide.delete() && outlined.renameTo(guide))
        File(fixture, "Unavailable.pdf").writeText("This is deliberately not a PDF.")
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            if (model.sort != SortBy.NAME || !model.ascending) model.changeSort(SortBy.NAME)
            model.navigate(fixture.path)
        }
        compose.waitUntil(15_000) { !model.loading && model.entries.size == 2 }
    }

    @After
    fun cleanup() {
        if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Close Quick Look").performClick()
        compose.runOnIdle {
            model.navigate(model.workspace.path)
            model.clearSelection()
        }
        fixture.deleteRecursively()
    }

    private fun key(code: Int) {
        compose.waitUntil(5_000) {
            android.view.inspector.WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() }
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun open() {
        compose.onNode(hasText("Field guide.pdf") and hasClickAction()).performMouseInput { click() }
        compose.waitUntil(5000) { model.focused?.path == guide.path }
        key(KeyEvent.KEYCODE_SPACE)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Page 1 of 6").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Page 6 of 6").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showNavigation() {
        if (compose.onAllNodesWithContentDescription("Saved PDF bookmarks").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("PDF pages and bookmarks").performClick()
        compose.onNodeWithContentDescription("Saved PDF bookmarks").assertIsDisplayed()
    }

    private fun capture(name: String) {
        if (
            name == "pdf-draft-pages" &&
                InstrumentationRegistry.getArguments().getString("keepPdfPreview") == "true"
        ) {
            val preview =
                File(compose.activity.filesDir, "Workspace/PDF preview QA").apply { mkdirs() }
            guide.copyTo(File(preview, guide.name), overwrite = true)
        }
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun compactNavigationExposesThumbnailsBookmarksAndContents() {
        org.junit.Assume.assumeTrue(compose.activity.resources.configuration.screenWidthDp < 480)
        open()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("PDF page 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("PDF page 1").assertIsDisplayed()
        capture("audit-phone-pdf-page")
        showNavigation()
        compose.onNodeWithTag("pdf-thumbnails").assertIsDisplayed()
        compose.onNodeWithTag("pdf-thumb-2").performScrollTo().performClick()
        compose.onNodeWithText("Page 3 of 6").assertExists()
        compose.onNodeWithTag("pdf-thumbnails").assertDoesNotExist()
        compose.onNodeWithContentDescription("Bookmark this page").performClick()
        showNavigation()
        compose.onNodeWithContentDescription("Saved PDF bookmarks").performClick()
        compose.onNodeWithText("Page 3", substring = false).assertIsDisplayed()
        capture("audit-phone-pdf-bookmarks")
        compose.onNodeWithContentDescription("PDF document contents").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Working with tabs").fetchSemanticsNodes().isNotEmpty()
        }
        capture("audit-phone-pdf-contents")
        compose.onNodeWithText("Working with tabs").performClick()
        compose.onNodeWithTag("pdf-contents").assertDoesNotExist()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("PDF page 4").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("PDF page 4").assertIsDisplayed()
    }

    @Test
    fun searchUsesSpacesAndFindsMatchesAcrossTheDocument() {
        open()
        showNavigation()
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithContentDescription("Thumbnail of PDF page 1")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        capture("pdf-draft-pages")
        compose.onNodeWithContentDescription("Find in PDF").performClick()
        compose.onNodeWithTag("pdf-search").performTextInput("ocean")
        key(KeyEvent.KEYCODE_SPACE)
        compose.onNodeWithTag("pdf-search").performTextInput("light")
        compose.onNodeWithTag("peek-window").assertExists()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("3 matches in 6 pages").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Page 2 of 6").assertExists()
        compose.onNodeWithContentDescription("Next PDF match").performClick()
        compose.onNodeWithContentDescription("Next PDF match").performClick()
        compose.onNodeWithText("Page 4 of 6").assertExists()
        capture("pdf-draft-search")
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("pdf-search").assertDoesNotExist()
        compose.onNodeWithTag("peek-window").assertExists()
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("peek-window").assertDoesNotExist()
    }

    @Test
    fun bookmarksAndReadingPositionSurviveReopeningWithoutEditingThePdf() {
        fun hash() = MessageDigest.getInstance("SHA-256").digest(guide.readBytes()).toList()
        val original = hash()
        open()
        compose.onNodeWithContentDescription("Last PDF page").performClick()
        compose.onNodeWithContentDescription("Bookmark this page").performClick()
        showNavigation()
        compose.onNodeWithContentDescription("Saved PDF bookmarks").performClick()
        compose.onNodeWithText("Page 6").assertExists()
        capture("pdf-draft-bookmarks")
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
        open()
        compose.onNodeWithText("Page 6 of 6").assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Page 6 of 6").fetchSemanticsNodes().isNotEmpty()
        }
        showNavigation()
        compose.waitUntil(5000) {
            val nodes = compose.onAllNodesWithTag("pdf-thumb-5").fetchSemanticsNodes()
            if (nodes.isEmpty()) false
            else {
                val item = nodes.single().boundsInRoot
                val viewport =
                    compose.onNodeWithTag("pdf-thumbnails").fetchSemanticsNode().boundsInRoot
                item.top >= viewport.top && item.bottom <= viewport.bottom
            }
        }
        compose.onNodeWithContentDescription("Remove page bookmark").assertExists()
        assertEquals(original, hash())
    }

    @Test
    fun selectableTextCopiesAndEmbeddedContentsNavigate() {
        open()
        compose.onNodeWithContentDescription("Read PDF text").performClick()
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithText("Explore your workspace", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Copy PDF page text").performClick()
        compose.runOnIdle {
            val clip =
                compose.activity
                    .getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip
            assertTrue(clip!!.getItemAt(0).text.contains("Explore your workspace"))
        }
        capture("pdf-draft-text")
        showNavigation()
        compose.onNodeWithContentDescription("PDF document contents").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Working with tabs").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Working with tabs").performClick()
        compose.onNodeWithText("Page 4 of 6").assertExists()
    }

    @Test
    fun pageJumpAndDocumentKeyboardNavigationPreserveZoom() {
        open()
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.onNodeWithText("Page 1 of 6").performClick()
        compose.onNodeWithTag("pdf-page-number").performTextReplacement("0")
        compose.onNodeWithText("Go").assertIsNotEnabled()
        compose.onNodeWithTag("pdf-page-number").performTextReplacement("4")
        compose.onNodeWithText("Go").performClick()
        compose.onNodeWithText("Page 4 of 6").assertExists()
        key(KeyEvent.KEYCODE_PAGE_DOWN)
        compose.onNodeWithText("Page 5 of 6").assertExists()
        compose.onNodeWithText("125%").assertExists()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0, KeyEvent.META_CTRL_ON),
        )
        instrumentation.sendKeySync(
            KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F, 0, KeyEvent.META_CTRL_ON),
        )
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("pdf-search").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pdf-search").performTextInput("Ocean light")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("3 matches in 6 pages").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pdf-search").performImeAction()
        compose.onNodeWithText("Page 2 of 6").assertExists()
    }

    @Test
    fun closingAProviderPeekClosesItsBlockedDownloadBeforeTheProviderReturns() {
        val source =
            File(compose.activity.filesDir, "ProviderFixtures/Pdf-${System.nanoTime()}").apply {
                mkdirs()
            }
        guide.copyTo(File(source, "Gate.pdf"))
        guide.copyTo(File(source, "Ready.pdf"))
        val gate = java.util.concurrent.CountDownLatch(1)
        val opened = java.util.concurrent.CountDownLatch(1)
        dev.findroid.testing.FixtureDocumentsProvider.transferGate = gate
        dev.findroid.testing.FixtureDocumentsProvider.gateOpened = opened
        val tree =
            android.provider.DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/${source.name}",
            )
        try {
            compose.runOnIdle {
                if (model.previewVisible) model.togglePreview()
                model.connectLocation(tree, false)
            }
            compose.waitUntil(15_000) {
                !model.loading && model.entries.any { it.name == "Gate.pdf" }
            }
            val remote = model.entries.first { it.name == "Gate.pdf" }
            compose.onNode(hasText("Gate.pdf") and hasClickAction()).performMouseInput { click() }
            compose.waitUntil(5000) { model.focused?.path == remote.path }
            key(KeyEvent.KEYCODE_SPACE)
            assertTrue(opened.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val staging = File(remote.file.parentFile, "preview.tmp")
            compose.waitUntil(5000) { staging.exists() }
            compose.onNodeWithContentDescription("Close Quick Look").performClick()
            compose.waitUntil(5000) { !staging.exists() }
            assertFalse(
                "A canceled preview must not publish a partial cache file",
                remote.file.exists(),
            )
            compose.onNode(hasText("Ready.pdf") and hasClickAction()).performMouseInput { click() }
            compose.waitUntil(5000) { model.focused?.name == "Ready.pdf" }
            key(KeyEvent.KEYCODE_SPACE)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Page 1 of 6").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Bookmark this page").performClick()
            compose.onNodeWithContentDescription("Close Quick Look").performClick()
            compose.waitUntil(5000) { compose.activity.window.decorView.hasWindowFocus() }
            val ready = model.focused!!
            assertTrue(
                PdfReadingStore(compose.activity)
                    .read(ready.path, ready.modified, ready.size)
                    .bookmarks
                    .contains(0),
            )
            key(KeyEvent.KEYCODE_SPACE)
            compose.waitUntil(15_000) {
                compose
                    .onAllNodesWithContentDescription("Remove page bookmark")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } finally {
            gate.countDown()
            dev.findroid.testing.FixtureDocumentsProvider.transferGate = null
            dev.findroid.testing.FixtureDocumentsProvider.gateOpened = null
            if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithContentDescription("Close Quick Look").performClick()
            compose.runOnIdle {
                model.navigate(fixture.path)
                model.connectedLocations
                    .filter { it.uri.contains(source.name) }
                    .forEach { model.disconnectLocation(it.uri) }
            }
            source.deleteRecursively()
        }
    }

    @Test
    fun malformedPdfReportsFailureAndPreviousFileStillOpens() {
        compose.onNode(hasText("Unavailable.pdf") and hasClickAction()).performMouseInput { click() }
        compose.waitUntil(5000) { model.focused?.name == "Unavailable.pdf" }
        key(KeyEvent.KEYCODE_SPACE)
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithText("Couldn't open this PDF.", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("peek-window").assertExists()
        compose.onNodeWithContentDescription("Previous file").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Page 1 of 6").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun imageOnlyPageRemainsViewableAndClearlyHasNoSelectableText() {
        val bitmap =
            android.graphics.Bitmap.createBitmap(600, 780, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(
            "A scanned page",
            60f,
            120f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 32f
            },
        )
        val document = PdfDocument()
        val file = File(fixture, "Scanned.pdf")
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 780, 1).create())
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
            bitmap.recycle()
        }
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(15_000) { !model.loading && model.entries.size == 3 }
        compose.onNode(hasText("Scanned.pdf") and hasClickAction()).performMouseInput { click() }
        compose.waitUntil(5000) { model.focused?.name == "Scanned.pdf" }
        key(KeyEvent.KEYCODE_SPACE)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Page 1 of 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Read PDF text").performClick()
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithText("This page has no searchable text layer")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Copy PDF page text").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Show PDF page").performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithContentDescription("PDF page 1")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun providerPdfWithoutAnExtensionUsesItsDeclaredMimeType() {
        val source =
            File(compose.activity.filesDir, "ProviderFixtures/PdfMime-${System.nanoTime()}").apply {
                mkdirs()
            }
        val remote = guide.copyTo(File(source, "Provider document"))
        dev.findroid.testing.FixtureDocumentsProvider.mimeOverrides =
            mapOf(remote.canonicalPath to "application/pdf")
        val tree =
            android.provider.DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/${source.name}",
            )
        try {
            compose.runOnIdle { model.connectLocation(tree, false) }
            compose.waitUntil(15_000) {
                !model.loading && model.entries.any { it.name == remote.name }
            }
            compose.onNode(hasText(remote.name) and hasClickAction()).performMouseInput { click() }
            compose.waitUntil(5000) { model.focused?.name == remote.name }
            key(KeyEvent.KEYCODE_SPACE)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Page 1 of 6").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Find in PDF").assertIsEnabled()
            compose.onNodeWithTag("pdf-preview").assertExists()
        } finally {
            if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithContentDescription("Close Quick Look").performClick()
            compose.runOnIdle {
                model.navigate(fixture.path)
                model.connectedLocations
                    .filter { it.uri.contains(source.name) }
                    .forEach { model.disconnectLocation(it.uri) }
            }
            dev.findroid.testing.FixtureDocumentsProvider.mimeOverrides = emptyMap()
            source.deleteRecursively()
        }
    }
}
