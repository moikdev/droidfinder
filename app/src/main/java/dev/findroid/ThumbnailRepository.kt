package dev.findroid

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class ThumbnailKey(
    val path: String,
    val name: String,
    val modified: Long,
    val size: Long,
    val mime: String,
    val edge: Int = 256,
)

data class ThumbnailStats(val bytes: Int, val cached: Int, val requests: Int, val readers: Int)

/** Shared across panes/windows. Eviction never recycles a bitmap still displayed by Compose. */
class ThumbnailRepository(
    context: Context,
    private val budget: Int = 24 * 1024 * 1024,
    private val decode: ((ThumbnailKey, CancellationSignal) -> Bitmap?)? = null,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val permits = Semaphore(4)
    private val cache =
        object : LruCache<ThumbnailKey, Bitmap>(budget) {
            override fun sizeOf(key: ThumbnailKey, value: Bitmap) = value.allocationByteCount
        }

    private class Request {
        val cancellation = CancellationSignal()
        lateinit var job: Deferred<Bitmap?>
        var readers = 0
    }

    private val requests = mutableMapOf<ThumbnailKey, Request>()

    suspend fun get(key: ThumbnailKey): Bitmap? {
        val request = mutex.withLock {
            cache.get(key)?.let {
                return it
            }
            (requests[key]
                    ?: Request().also { work ->
                        work.job =
                            scope.async(start = CoroutineStart.LAZY) {
                                try {
                                    val bitmap = permits.withPermit {
                                        ensureActive()
                                        work.cancellation.throwIfCanceled()
                                        try {
                                            if (decode != null)
                                                decode.invoke(key, work.cancellation)
                                            else if (isDocumentPath(key.path))
                                                app.contentResolver.loadThumbnail(
                                                    Uri.parse(key.path),
                                                    android.util.Size(key.edge, key.edge),
                                                    work.cancellation,
                                                )
                                            else decodeBitmap(java.io.File(key.path), key.edge)
                                        } catch (error: OutOfMemoryError) {
                                            mutex.withLock { cache.evictAll() }
                                            null
                                        } catch (e: Exception) {
                                            ensureActive()
                                            null
                                        }
                                    }
                                    ensureActive()
                                    if (bitmap != null && bitmap.allocationByteCount <= budget)
                                        mutex.withLock {
                                            if (requests[key] === work) cache.put(key, bitmap)
                                        }
                                    bitmap
                                } finally {
                                    withContext(NonCancellable) {
                                        mutex.withLock {
                                            if (requests[key] === work) requests.remove(key)
                                        }
                                    }
                                }
                            }
                        requests[key] = work
                    })
                .also {
                    it.readers++
                    it.job.start()
                }
        }
        try {
            return request.job.await()
        } finally {
            withContext(NonCancellable) {
                val cancel = mutex.withLock {
                    request.readers--
                    (request.readers == 0 && !request.job.isCompleted).also {
                        if (it && requests[key] === request) requests.remove(key)
                    }
                }
                if (cancel) {
                    request.job.cancel()
                    // A provider's cancellation hook can make a Binder call; keep it off the UI
                    // thread.
                    scope.launch { request.cancellation.cancel() }
                }
            }
        }
    }

    suspend fun stats() = mutex.withLock {
        ThumbnailStats(
            cache.size(),
            cache.snapshot().size,
            requests.size,
            requests.values.sumOf { it.readers },
        )
    }

    suspend fun clear() = mutex.withLock { cache.evictAll() }
}
