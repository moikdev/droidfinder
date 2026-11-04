package dev.findroid

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*

internal class OcrFixture(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "ocr")
    val store = OcrOperations.models(context)
    init { check(!root.entryExists()) { "The dedicated fixture must start without original OCR data; preserve it before testing." } }
    fun install(language: OcrLanguage = OcrPolicy.language("eng")) {
        InstrumentationRegistry.getInstrumentation().context.assets.open("ocr/${language.filename}").use {
            check(store.install(language, it).verified)
        }
    }
    fun close() {
        if (!root.entryExists()) return
        // Remove only the exact model installed by this fixture and empty owned directories.
        for (language in OcrPolicy.languages) {
            val state = store.state(language)
            if (state.stored != null) { check(state.verified); store.removeReviewedModel(state) }
        }
        check(store.incomplete().isEmpty())
        for (name in listOf("tessdata", "incomplete")) check(File(root, name).delete())
        check(root.delete()) { "Unexpected OCR bytes were retained for review." }
    }
}

internal fun ocrTestImage(width: Int = 1000, height: Int = 400, dense: Boolean = false): Bitmap =
    Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val canvas = Canvas(this)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = if (dense) 25f else 64f; typeface = Typeface.create(Typeface.SANS_SERIF,Typeface.NORMAL) }
        if (dense) for (line in 0 until (height - 80) / 35) canvas.drawText("Ocean light Far shore Ocean light Far shore Ocean light Far shore Ocean light",40f,60f+line*35f,paint)
        else { canvas.drawText("Ocean light",70f,130f,paint);canvas.drawText("Far shore",70f,240f,paint) }
    }
internal fun ocrWorkerPid(context: Context): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
    ?.firstOrNull { it.processName == "${context.packageName}:ocr" }?.pid
