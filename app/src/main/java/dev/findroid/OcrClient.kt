package dev.findroid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Results exist only for this explicit request; closing the preview unbinds and stops its worker. */
internal suspend fun recognizeOcrRaster(context: Context, language: OcrLanguage, raster: File, onStarted: () -> Unit = {}): OcrText =
    withTimeout(OcrPolicy.recognitionMillis + 10_000L) {
        suspendCancellableCoroutine { continuation ->
            val app = context.applicationContext
            val main = Handler(Looper.getMainLooper())
            var bound = false
            var worker: Messenger? = null
            lateinit var connection: ServiceConnection
            fun close() {
                runCatching { worker?.send(Message.obtain(null, OcrService.CANCEL)) }
                if (bound) { bound = false; runCatching { app.unbindService(connection) } }
            }
            fun fail(message: String) {
                if (continuation.isActive) continuation.resumeWithException(IOException(message))
                close()
            }
            val response = Messenger(Handler(Looper.getMainLooper()) { message ->
                when (message.what) {
                    OcrService.STARTED -> { if (continuation.isActive) onStarted(); true }
                    OcrService.RESULT -> {
                        val text = message.data.getString("text").orEmpty()
                        if (text.length > OcrPolicy.maximumText) fail("The OCR result exceeded its text budget.")
                        else {
                            if (continuation.isActive) continuation.resume(OcrText(text, message.data.getInt("confidence").coerceIn(0,100), message.data.getBoolean("limited")))
                            close()
                        }
                        true
                    }
                    OcrService.ERROR -> { fail(message.data.getString("error") ?: "Recognition couldn't finish."); true }
                    else -> false
                }
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (!continuation.isActive) { close(); return }
                    worker = Messenger(binder)
                    try {
                        ParcelFileDescriptor.open(raster, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            worker!!.send(Message.obtain(null, OcrService.START).apply {
                                replyTo = response
                                data = Bundle().apply { putString("language", language.id); putParcelable("raster", descriptor) }
                            })
                        }
                    } catch (_: Exception) { fail("Couldn't start local text recognition.") }
                }
                override fun onServiceDisconnected(name: ComponentName) { fail("The recognition worker ended. Try a smaller or clearer image.") }
                override fun onNullBinding(name: ComponentName) { fail("The local recognition worker is unavailable.") }
                override fun onBindingDied(name: ComponentName) { fail("The recognition worker stopped. Start recognition again when ready.") }
            }
            continuation.invokeOnCancellation { main.post { close() } }
            main.post {
                if (continuation.isActive) {
                    try {
                        bound = app.bindService(Intent(app, OcrService::class.java), connection, Context.BIND_AUTO_CREATE)
                        if (!bound) fail("Couldn't open the local recognition worker.")
                    } catch (_: Exception) { fail("Couldn't open the local recognition worker.") }
                }
            }
        }
    }
