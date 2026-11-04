package dev.findroid

import android.content.Context
import java.io.Closeable
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.sync.Mutex

internal object OcrOperations {
    val gate = Mutex()
    fun models(context: Context) = OcrModelStore(java.io.File(context.noBackupFilesDir, "ocr")) { from, to ->
        NativeFilePublication.moveNoReplace(from.absolutePath, to.absolutePath)
    }
}

/** Close an outstanding network/provider read without blocking the Compose UI. */
internal class OcrReadControl {
    private val cancelled = AtomicBoolean(false)
    private val started = System.nanoTime()
    private val lock = Any()
    private val actions = mutableSetOf<() -> Unit>()
    private val deadline = deadlines.schedule({ cancel() }, 120, TimeUnit.SECONDS)
    fun check() {
        check(System.nanoTime() - started < 120_000_000_000L) { "Loading the OCR language exceeded two minutes. The incomplete bytes were kept." }
        if (cancelled.get()) throw CancellationException("OCR data loading stopped")
    }
    fun track(action: () -> Unit): Closeable {
        synchronized(lock) { check(); actions.add(action) }
        return Closeable { synchronized(lock) { actions.remove(action) } }
    }
    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        deadline.cancel(false)
        val pending = synchronized(lock) { actions.toList().also { actions.clear() } }
        cleanup.execute { pending.forEach { runCatching(it) } }
    }
    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "Findroid OCR transfer deadline").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
        private val cleanup = Executors.newSingleThreadExecutor { task -> Thread(task, "Findroid OCR cancellation").apply { isDaemon = true } }
    }
}

/** Only the pinned public model is fetched. No image, PDF, recognized text or pathname is sent. */
internal fun downloadOcrModel(
    store: OcrModelStore,
    language: OcrLanguage,
    control: OcrReadControl,
    progress: (Long) -> Unit,
): OcrModelState {
    require(language == OcrPolicy.language(language.id))
    control.check()
    val connection = URL(language.downloadUrl).openConnection() as HttpsURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Accept-Encoding", "identity")
    connection.setRequestProperty("User-Agent", "Findroid-language-model-download")
    val tracked = control.track { connection.disconnect() }
    try {
        control.check()
        require(connection.responseCode == 200) { "The language download wasn't available. No model was installed." }
        require(connection.contentEncoding == null || connection.contentEncoding.equals("identity", true)) { "Unexpected language download encoding. No model was installed." }
        val length = connection.contentLengthLong
        require(length == -1L || length == language.bytes) { "The language download size changed. No model was installed." }
        connection.inputStream.use { input ->
            control.track { input.close() }.use {
                return store.install(language, input, control::check, progress)
            }
        }
    } finally {
        tracked.close()
        connection.disconnect()
    }
}
