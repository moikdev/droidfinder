package dev.findroid

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.*
import android.system.Os
import android.system.OsConstants
import com.googlecode.tesseract.android.TessBaseAPI
import java.util.concurrent.Executors

/** A visible-preview-only private worker. Stopping it cannot terminate the explorer/transfer process. */
class OcrService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var running = false
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            CANCEL -> { finishProcess(); true }
            START -> { start(message); true }
            else -> false
        }
    })
    override fun onBind(intent: Intent) = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { finishProcess(); return false }
    private fun finishProcess() {
        // This component runs only in dev.findroid:ocr. It never owns explorer metadata or documents.
        check(android.app.Application.getProcessName() == "$packageName:ocr")
        android.os.Process.killProcess(android.os.Process.myPid())
    }
    @Suppress("DEPRECATION")
    private fun start(message: Message) {
        val reply = message.replyTo ?: return
        val data = message.data
        data.classLoader = ParcelFileDescriptor::class.java.classLoader
        val descriptor = data.getParcelable<ParcelFileDescriptor>("raster") ?: return
        if (running) {
            descriptor.close()
            respond(reply, ERROR, Bundle().apply { putString("error", "Another OCR task is already running.") })
            return
        }
        running = true
        val timeout = Runnable {
            respond(reply, ERROR, Bundle().apply { putString("error", "Recognition stopped after 30 seconds. Try a smaller or clearer image.") })
            finishProcess()
        }
        main.postDelayed(timeout, OcrPolicy.recognitionMillis)
        executor.execute {
            var bitmap: Bitmap? = null
            var api: TessBaseAPI? = null
            try {
                val language = OcrPolicy.language(data.getString("language") ?: "")
                val state = OcrOperations.models(this).state(language)
                require(state.verified) { "Download or import the reviewed language before recognizing text." }
                descriptor.use { input ->
                    val stat = Os.fstat(input.fileDescriptor)
                    require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..32L * 1024 * 1024) { "The OCR raster is unavailable or too large." }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(input.fileDescriptor, null, bounds)
                    require(bounds.outWidth in 1..OcrPolicy.maximumEdge && bounds.outHeight in 1..OcrPolicy.maximumEdge &&
                        bounds.outWidth.toLong() * bounds.outHeight <= OcrPolicy.maximumPixels) { "The OCR raster exceeds its pixel budget." }
                    Os.lseek(input.fileDescriptor, 0, OsConstants.SEEK_SET)
                    bitmap = BitmapFactory.decodeFileDescriptor(input.fileDescriptor)
                        ?: error("Couldn't read the OCR raster.")
                }
                val engine = TessBaseAPI()
                api = engine
                check(engine.init(OcrOperations.models(this).engineDirectory, language.id, TessBaseAPI.OEM_LSTM_ONLY)) { "Couldn't initialize this OCR language. Its model was kept for review." }
                engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                engine.setImage(requireNotNull(bitmap))
                respond(reply, STARTED, Bundle())
                // Recognition itself is interruptible by terminating only this private process.
                // getUTF8Text alone is not interruptible in this wrapper; the hard deadline covers both calls.
                engine.getHOCRText(0)
                val result = OcrPolicy.result(engine.utF8Text.orEmpty(), engine.meanConfidence())
                respond(reply, RESULT, Bundle().apply {
                    putString("text", result.text)
                    putInt("confidence", result.confidence)
                    putBoolean("limited", result.limited)
                })
            } catch (_: OutOfMemoryError) {
                respond(reply, ERROR, Bundle().apply { putString("error", "Not enough memory for recognition. Try a smaller image.") })
            } catch (error: Exception) {
                respond(reply, ERROR, Bundle().apply { putString("error", error.message?.take(240) ?: "Text recognition couldn't finish.") })
            } catch (_: LinkageError) {
                respond(reply, ERROR, Bundle().apply { putString("error", "This device couldn't load the local OCR engine.") })
            } finally {
                runCatching { descriptor.close() }
                runCatching { api?.recycle() }
                bitmap?.recycle()
                main.removeCallbacks(timeout)
                main.post { running = false }
            }
        }
    }
    private fun respond(reply: Messenger, what: Int, data: Bundle) {
        runCatching { reply.send(Message.obtain(null, what).apply { this.data = data }) }
    }
    companion object {
        internal const val START = 1
        internal const val CANCEL = 2
        internal const val RESULT = 3
        internal const val ERROR = 4
        internal const val STARTED = 5
    }
}
