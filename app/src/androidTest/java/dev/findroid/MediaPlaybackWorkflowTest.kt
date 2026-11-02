package dev.findroid

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalTestApi::class)
class MediaPlaybackWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var folder: File
    private lateinit var clip: File
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setup() {
        folder =
            File(compose.activity.filesDir, "Workspace/Media-${System.nanoTime()}").apply {
                mkdirs()
            }
        clip = File(folder, "Coastal light.mp4")
        instrumentation.context.assets.open("media-study.mp4").use { input ->
            clip.outputStream().use(input::copyTo)
        }
        compose.runOnIdle {
            val model = compose.activity.model
            if (model.dualPane) model.toggleDualPane()
            model.changeMode(ViewMode.LIST)
            model.clearSelection()
            model.navigate(folder.path)
        }
        compose.waitUntil(10_000) {
            !compose.activity.model.loading &&
                compose.activity.model.entries.any { it.name == clip.name }
        }
    }

    @After
    fun cleanup() {
        if (compose.onAllNodesWithTag("peek-window").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Close Quick Look").performClick()
        compose.runOnIdle { compose.activity.model.navigate(compose.activity.model.workspace.path) }
        folder.deleteRecursively()
    }

    private fun open() {
        compose.onNode(hasText(clip.name) and hasClickAction()).performMouseInput { click() }
        compose.waitUntil(5_000) { compose.activity.model.focused?.path == clip.path }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_SPACE)
        compose.onNodeWithTag("peek-window").assertExists()
        ready()
    }

    private fun ready() {
        try {
            compose.waitUntil(15_000) {
                compose
                    .onAllNodes(hasContentDescription("Play video") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (failure: Throwable) {
            println(compose.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
    }

    @Test
    fun controlsRestorePositionSpeedAndStayPausedAcrossRecreationAndBackground() {
        open()
        compose.onNodeWithContentDescription("Forward 10 seconds").performClick()
        compose.onNodeWithTag("media-position").assertTextEquals("0:10")
        compose.onNodeWithContentDescription("Playback speed").performClick()
        compose.onNodeWithText("1.5×").performClick()
        ready()
        compose.onNodeWithContentDescription("Play video").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Pause video").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Pause video").performClick()
        val pausedTime =
            compose
                .onNodeWithTag("media-position")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
                .text
        compose.onNodeWithContentDescription("Close Quick Look").performClick()
        open()
        compose.onNodeWithTag("media-position").assertTextEquals(pausedTime)
        compose.onNodeWithText("1.5×").assertExists()
        compose.activityRule.scenario.recreate()
        ready()
        compose.onNodeWithTag("media-position").assertTextEquals(pausedTime)
        compose.onNodeWithText("1.5×").assertExists()
        compose.onNodeWithContentDescription("Play video").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ready()
        compose.onNodeWithContentDescription("Pause video").assertDoesNotExist()
        compose.onNodeWithContentDescription("Restart playback").performClick()
        compose.onNodeWithTag("media-position").assertTextEquals("0:00")
        compose.onNodeWithContentDescription("Mute").performClick()
        compose.onNodeWithContentDescription("Unmute").assertExists()
    }

    @Test
    fun embeddedAndExternalSubtitlesRenderAndSurviveRelease() {
        lateinit var state: MediaPlayback
        val subtitle =
            File(folder, "Captions.vtt").apply {
                writeText("WEBVTT\n\n00:00:00.000 --> 00:00:30.000\nExternal caption works.\n")
            }
        compose.runOnIdle {
            state = MediaPlayback(compose.activity, Entry(clip))
            state.start()
        }
        try {
            compose.waitUntil(15_000) { state.ready && state.tracks.isNotEmpty() }
            compose.runOnIdle {
                state.chooseTrack(state.tracks.first())
                state.seek(4_000)
            }
            compose.waitUntil(10_000) {
                captions(state).any {
                    it.text.toString().contains("room to explore")
                }
            }
            compose.runOnIdle { state.loadSubtitle(Uri.fromFile(subtitle), MimeTypes.TEXT_VTT) }
            compose.waitUntil(10_000) {
                state.ready && state.tracks.any { it.label == "External subtitles" }
            }
            compose.runOnIdle {
                state.chooseTrack(state.tracks.first { it.label == "External subtitles" })
                state.seek(5_000)
            }
            compose.waitUntil(10_000) {
                captions(state).any {
                    it.text.toString() == "External caption works."
                }
            }
            compose.runOnIdle {
                state.stop()
                assertNull(state.player)
                state = MediaPlayback(compose.activity, Entry(clip))
                state.start()
            }
            compose.waitUntil(10_000) {
                state.ready && state.tracks.any { it.label == "External subtitles" }
            }
            compose.waitUntil(10_000) {
                captions(state).any { it.text.toString() == "External caption works." }
            }
            compose.runOnIdle {
                assertEquals(5_000, state.position)
                assertFalse(state.playing)
                state.chooseTrack(null)
            }
            compose.runOnIdle {
                state.stop()
                state = MediaPlayback(compose.activity, Entry(clip))
                assertTrue(state.subtitlesOff)
            }
        } finally {
            compose.runOnIdle { state.stop() }
        }
    }

    @Test
    fun srtCaptionsRemainVisibleAfterPausedAndPlayingSeeks() {
        val subtitle =
            File(folder, "Captions.srt").apply {
                writeText("1\n00:00:00,000 --> 00:00:30,000\nSRT caption survives seeking.\n")
            }
        lateinit var state: MediaPlayback
        compose.runOnIdle {
            state = MediaPlayback(compose.activity, Entry(clip))
            state.start()
        }
        try {
            compose.waitUntil(15_000) { state.ready }
            compose.runOnIdle {
                state.loadSubtitle(Uri.fromFile(subtitle), MimeTypes.APPLICATION_SUBRIP)
            }
            compose.waitUntil(15_000) { state.ready && state.selectedTrack == "external" }
            for (position in listOf(10_000L, 0L, 10_000L)) {
                compose.runOnIdle { state.seek(position) }
                Thread.sleep(700)
                compose.waitUntil(5_000) {
                    captions(state).any { it.text.toString() == "SRT caption survives seeking." }
                }
            }
            compose.waitUntil(15_000) { state.ready }
            compose.runOnIdle {
                state.toggle()
                state.seek(15_000)
            }
            Thread.sleep(700)
            compose.waitUntil(5_000) {
                state.playing &&
                    captions(state).any { it.text.toString() == "SRT caption survives seeking." }
            }
        } finally {
            compose.runOnIdle { state.stop() }
        }
    }

    @Test
    fun unreadableSubtitleFallsBackToPlayableVideo() {
        lateinit var state: MediaPlayback
        compose.runOnIdle {
            state = MediaPlayback(compose.activity, Entry(clip))
            state.start()
        }
        try {
            compose.waitUntil(15_000) { state.ready }
            compose.runOnIdle {
                state.loadSubtitle(
                    Uri.fromFile(File(folder, "Missing.srt")),
                    MimeTypes.APPLICATION_SUBRIP,
                )
            }
            compose.waitUntil(20_000) { state.ready && state.notice != null }
            compose.runOnIdle {
                assertNull(state.error)
                state.toggle()
            }
            compose.waitUntil(10_000) { state.playing }
        } finally {
            compose.runOnIdle { state.stop() }
        }
    }

    private fun captions(state: MediaPlayback): List<androidx.media3.common.text.Cue> {
        var result = emptyList<androidx.media3.common.text.Cue>()
        instrumentation.runOnMainSync { result = state.player?.currentCues?.cues.orEmpty() }
        return result
    }

    @Test
    fun changedFileDoesNotInheritAnotherFingerprintHistory() {
        val store = MediaProgressStore(compose.activity)
        val source = Entry(clip)
        store.write(source, MediaProgress(12_000, 2f))
        assertEquals(12_000, store.read(source).position)
        assertEquals(MediaProgress(), store.read(source.copy(modified = source.modified + 1)))
        assertEquals(MediaProgress(), store.read(source.copy(size = source.size + 1)))
        assertEquals("1:01:01", mediaTime(3_661_000))
        assertEquals("video", source.copy(name = "No extension", mimeType = "video/mp4").mediaKind)
        assertNull(source.copy(name = "Guide.pdf", mimeType = "video/mp4").mediaKind)
    }
}
