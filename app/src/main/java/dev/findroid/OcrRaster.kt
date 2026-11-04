package dev.findroid

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Consumes only an owned working bitmap; callers must never pass a shared viewer bitmap. */
internal suspend fun boundedOcrRaster(owned: Bitmap): Bitmap {
    try {
        currentCoroutineContext().ensureActive()
        val (width, height) = OcrPolicy.raster(owned.width, owned.height)
        if (width == owned.width && height == owned.height) return owned
        val result = Bitmap.createScaledBitmap(owned, width, height, true)
        owned.recycle()
        try { currentCoroutineContext().ensureActive(); return result }
        catch (error: Throwable) { result.recycle(); throw error }
    } catch (error: Throwable) { if (!owned.isRecycled) owned.recycle(); throw error }
}
