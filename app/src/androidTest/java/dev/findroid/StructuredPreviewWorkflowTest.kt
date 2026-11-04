package dev.findroid

import android.content.ClipboardManager
import android.provider.DocumentsContract
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.findroid.testing.FixtureDocumentsProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class StructuredPreviewWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var folder: File
    private val model
        get() = compose.activity.model

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setup() {
        folder =
            File(compose.activity.filesDir, "Workspace/Structured-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(folder, "Brief.md")
            .writeText(
                "# Ocean light\n\n**Clear** ideas and `small details`.\n\n- [x] Ready\n\n<script>window.bad = true</script>",
            )
        File(folder, "Data.csv")
            .writeText("Name,Notes\nOcean,\"Quoted, value\"\nJournal,\"Line one\nLine two\"\n")
        File(folder, "Config.json")
            .writeText("{\"workspace\":\"Ocean light\",\"count\":12,\"ready\":true}")
        File(folder, "Broken.json").writeText("{broken:}")
        File(folder, "Encoding.txt").writeBytes(byteArrayOf(99, 97, 102, -23))
        File(folder, "Large.txt")
            .writeText("prefix\n" + "line\n".repeat(110_000) + "beyond first limit\n")
        compose.runOnIdle {
            if (model.dualPane) model.toggleDualPane()
            model.changeDensity(FinderDensity.COMFORTABLE)
            model.changeMode(ViewMode.LIST)
            model.changeGrouping(GroupBy.NONE)
            model.clearSelection()
            model.navigate(folder.path)
        }
        compose.waitUntil(10_000) { !model.loading && model.entries.size == 6 }
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

    private fun open(name: String) {
        compose.onNode(hasText(name) and hasClickAction()).performMouseInput { click() }
        compose.waitUntil(5_000) {
            model.focused?.name == name && compose.activity.window.decorView.hasWindowFocus()
        }
        key(KeyEvent.KEYCODE_SPACE)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("structured-preview").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    @Test
    fun markdownReadSourceAndLiteralHtmlDoNotExecute() {
        open("Brief.md")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("markdown-reader").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Ocean light").assertExists()
        compose.onNodeWithText("☑ Ready").assertExists()
        compose.onNodeWithText("HTML · shown as text").assertExists()
        compose.onNodeWithText("Source").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("# Ocean light").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Copy loaded text").performClick()
        compose.runOnIdle {
            assertTrue(
                compose.activity
                    .getSystemService(ClipboardManager::class.java)
                    .primaryClip!!
                    .getItemAt(0)
                    .text
                    .toString()
                    .contains("<script>"),
            )
        }
        key(KeyEvent.KEYCODE_SPACE)
        compose.onNodeWithTag("peek-window").assertDoesNotExist()
    }

    @Test
    fun csvQuotedCellsCanBeInspectedAndCopied() {
        open("Data.csv")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("csv-table").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Quoted, value").performClick()
        compose.onNodeWithText("Cell value").assertExists()
        compose.onNodeWithText("Copy cell").performClick()
        compose.runOnIdle {
            assertEquals(
                "Quoted, value",
                compose.activity
                    .getSystemService(ClipboardManager::class.java)
                    .primaryClip!!
                    .getItemAt(0)
                    .text
                    .toString(),
            )
        }
        compose
            .onNodeWithContentDescription("First row is header")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithText("3 rows · 2 columns").assertExists()
        compose.onNodeWithText("Source").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Name,Notes").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun jsonFormatsAndMalformedJsonKeepsReadableSource() {
        open("Config.json")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("  \"count\": 12,").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Copy loaded text").performClick()
        compose.runOnIdle {
            assertTrue(
                compose.activity
                    .getSystemService(ClipboardManager::class.java)
                    .primaryClip!!
                    .getItemAt(0)
                    .text
                    .toString()
                    .contains("\n"),
            )
        }
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
        open("Broken.json")
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(hasText("Couldn't format JSON", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose
            .onNode(hasText("{broken:}") and hasAnyAncestor(hasTestTag("structured-preview")))
            .assertExists()
    }

    @Test
    fun searchOwnsSpacesAndEscapeReturnsToPeek() {
        open("Brief.md")
        // The container appears while its text is still loading; Find is disabled until ready.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Find in document") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Find in document").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("preview-find").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("preview-find").performTextInput("Ocean")
        key(KeyEvent.KEYCODE_SPACE)
        compose.onNodeWithTag("preview-find").assertTextContains("Ocean ")
        compose.onNodeWithTag("peek-window").assertExists()
        compose.onNodeWithTag("preview-find").performTextInput("light")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("1 / 1").fetchSemanticsNodes().isNotEmpty()
        }
        // Hardware-shortcut behavior is tested after dismissing the software IME,
        // which consumes the first Escape itself on Android 11.
        instrumentation.runOnMainSync {
            android.view.inspector.WindowInspector.getGlobalWindowViews()
                .firstOrNull { it.hasWindowFocus() }?.let { view ->
                    view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(view.windowToken, 0)
                }
        }
        compose.waitUntil(5_000) {
            android.view.inspector.WindowInspector.getGlobalWindowViews()
                .filter { it.hasWindowFocus() }
                .none { it.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
        }
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("preview-find").assertDoesNotExist()
        key(KeyEvent.KEYCODE_SPACE)
        compose.onNodeWithTag("peek-window").assertDoesNotExist()
    }

    @Test
    fun encodingRecoveryAndLargerExplicitBudget() {
        open("Encoding.txt")
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(hasText("Couldn't decode", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Auto").performClick()
        compose.onNodeWithText("windows-1252").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("café").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
        open("Large.txt")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Load up to 2 MiB").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Load up to 2 MiB").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Load up to 2 MiB").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription("Copy loaded text").performClick()
        compose.waitUntil(10_000) {
            compose.activity
                .getSystemService(ClipboardManager::class.java)
                .primaryClip
                ?.getItemAt(0)
                ?.uri != null
        }
        val item =
            compose.activity
                .getSystemService(ClipboardManager::class.java)
                .primaryClip!!
                .getItemAt(0)
        assertTrue(item.coerceToText(compose.activity).toString().contains("beyond first limit"))
    }

    @Test
    fun mimeOnlyProviderTextAndBlockedReadCancellation() {
        val provider =
            File(compose.activity.filesDir, "ProviderFixtures/Structured-${System.nanoTime()}")
                .apply { mkdirs() }
        val file =
            File(provider, "No extension").apply {
                writeText("# Provider document\n\nReadable through its MIME type.")
            }
        File(provider, "Gate.txt").writeText("blocked")
        FixtureDocumentsProvider.mimeOverrides = mapOf(file.canonicalPath to "text/markdown")
        val gate = CountDownLatch(1)
        val opened = CountDownLatch(1)
        FixtureDocumentsProvider.transferGate = gate
        FixtureDocumentsProvider.gateOpened = opened
        val tree =
            DocumentsContract.buildTreeDocumentUri(
                "dev.findroid.fixtures",
                "root/${provider.name}",
            )
        try {
            compose.runOnIdle { model.connectLocation(tree, false) }
            compose.waitUntil(10_000) {
                !model.loading && model.entries.any { it.name == "No extension" }
            }
            open("No extension")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Provider document").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Close Quick Look").performClick()
            open("Gate.txt")
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("Stop preview").performClick()
            compose.onNodeWithText("Preview stopped").assertExists()
            compose.onNodeWithContentDescription("Close Quick Look").performClick()
            gate.countDown()
        } finally {
            gate.countDown()
            FixtureDocumentsProvider.transferGate = null
            FixtureDocumentsProvider.gateOpened = null
            FixtureDocumentsProvider.mimeOverrides = emptyMap()
            if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithContentDescription("Close Quick Look").performClick()
            compose.runOnIdle {
                model.navigate(folder.path)
                model.connectedLocations
                    .firstOrNull { it.name == provider.name }
                    ?.let { model.disconnectLocation(it.uri) }
            }
            provider.deleteRecursively()
        }
    }
}
