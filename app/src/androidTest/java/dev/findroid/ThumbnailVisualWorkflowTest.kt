package dev.findroid

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThumbnailVisualWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun stableDocumentIdentityCannotKeepAnOldPhotoAfterItsTypeChanges() {
        val file = File(compose.activity.cacheDir, "Thumbnail-${System.nanoTime()}.png")
        val bitmap =
            Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.RED)
            }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val entry =
            mutableStateOf(Entry(file, name = "Photo.png", mimeType = "application/octet-stream"))
        try {
            compose.activity.setContent { MaterialTheme { FileGlyph(entry.value, 64.dp) } }
            compose.waitUntil(8000) {
                compose
                    .onAllNodesWithContentDescription("Photo.png")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.runOnIdle { entry.value = entry.value.copy(name = "Notes.txt") }
            compose.onNodeWithText("TXT").assertExists()
            compose.onAllNodesWithContentDescription("Photo.png").assertCountEquals(0)
            compose.runOnIdle {
                entry.value = entry.value.copy(name = "Report", mimeType = "application/pdf")
            }
            compose.onNodeWithText("PDF").assertExists()
        } finally {
            file.delete()
        }
    }
}
