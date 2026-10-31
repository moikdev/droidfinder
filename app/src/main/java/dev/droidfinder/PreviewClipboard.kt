package dev.droidfinder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val previewClipboardLock = Mutex()

/** Large text uses Android's stream-backed clipboard instead of a large Binder transaction. */
internal suspend fun copyPreviewText(context: Context, label: String, text: String) =
    previewClipboardLock.withLock {
        var snapshot: File? = null
        var published = false
        try {
            val clip =
                if (text.length <= 65_536) ClipData.newPlainText(label, text)
                else
                    withContext(Dispatchers.IO) {
                        val directory =
                            File(context.cacheDir, "shared/text-clips").apply { mkdirs() }
                        val file = File.createTempFile("text-", ".txt", directory)
                        snapshot = file
                        file.writeText(text, Charsets.UTF_8)
                        var retained = file.length()
                        directory
                            .listFiles()
                            .orEmpty()
                            .filter { it != file }
                            .sortedByDescending { it.lastModified() }
                            .forEachIndexed { index, older ->
                                retained += older.length()
                                if (index >= 7 || retained > 32L * 1024 * 1024) older.delete()
                            }
                        ClipData.newUri(
                            context.contentResolver,
                            label,
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.files",
                                file,
                            ),
                        )
                    }
            currentCoroutineContext().ensureActive()
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            published = true
        } finally {
            if (!published) snapshot?.delete()
        }
    }
