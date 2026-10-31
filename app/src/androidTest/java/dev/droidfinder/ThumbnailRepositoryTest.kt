package dev.droidfinder

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ThumbnailRepositoryTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun key(name: String) = ThumbnailKey("/image/$name", name, 1, 1, "image/png")

    @Test
    fun simultaneousConsumersShareOneDecode() = runBlocking {
        val calls = AtomicInteger()
        val began = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val repo =
            ThumbnailRepository(context, 8192) { _, _ ->
                calls.incrementAndGet()
                began.countDown()
                gate.await(5, TimeUnit.SECONDS)
                bitmap
            }
        val jobs = List(8) { async(Dispatchers.Default) { repo.get(key("same")) } }
        try {
            assertTrue(began.await(3, TimeUnit.SECONDS))
            withTimeout(3000) { while (repo.stats().readers < 8) delay(10) }
            gate.countDown()
            jobs.forEach { assertSame(bitmap, it.await()) }
            assertEquals(1, calls.get())
            assertEquals(1, repo.stats().cached)
        } finally {
            gate.countDown()
            jobs.forEach { it.cancel() }
        }
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedItemsWithinTheByteBudget() = runBlocking {
        val counts = mutableMapOf<String, Int>()
        val repo =
            ThumbnailRepository(context, 8192) { k, _ ->
                synchronized(counts) { counts[k.name] = (counts[k.name] ?: 0) + 1 }
                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            }
        val a = repo.get(key("A"))
        repo.get(key("B"))
        assertSame(a, repo.get(key("A")))
        repo.get(key("C"))
        assertEquals(2, repo.stats().cached)
        assertTrue(repo.stats().bytes <= 8192)
        assertSame(a, repo.get(key("A")))
        repo.get(key("B"))
        assertEquals(2, counts["B"])
        repo.clear()
        assertEquals(0, repo.stats().bytes)
    }

    @Test
    fun cancellingOneReaderKeepsTheOtherReaderAndLastCancellationSignalsTheProvider() =
        runBlocking {
            val began = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val repo =
                ThumbnailRepository(context) { _, signal ->
                    signal.setOnCancelListener {
                        cancelled.countDown()
                        gate.countDown()
                    }
                    began.countDown()
                    gate.await(5, TimeUnit.SECONDS)
                    signal.throwIfCanceled()
                    null
                }
            val first = async(Dispatchers.Default) { repo.get(key("held")) }
            val second = async(Dispatchers.Default) { repo.get(key("held")) }
            try {
                assertTrue(began.await(3, TimeUnit.SECONDS))
                withTimeout(3000) { while (repo.stats().readers < 2) delay(10) }
                first.cancelAndJoin()
                assertEquals(1L, cancelled.count)
                second.cancelAndJoin()
                assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            } finally {
                gate.countDown()
                first.cancel()
                second.cancel()
            }
        }

    @Test
    fun identityChangesInvalidateTheCacheAndFailedLoadsCanRetry() = runBlocking {
        val calls = AtomicInteger()
        val original = key("Photo.png")
        val repo =
            ThumbnailRepository(context) { _, _ ->
                if (calls.incrementAndGet() == 1) null
                else Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
        assertNull(repo.get(original))
        assertNotNull(repo.get(original))
        repo.get(original.copy(name = "Renamed.png"))
        repo.get(original.copy(modified = 2))
        repo.get(original.copy(mime = "image/jpeg"))
        assertEquals(5, calls.get())
    }

    @Test
    fun decoderMemoryPressureFallsBackWithoutKeepingCachedBitmaps() = runBlocking {
        var fail = false
        val repo =
            ThumbnailRepository(context, 8192) { _, _ ->
                if (fail) throw OutOfMemoryError("Simulated decoder allocation failure")
                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            }
        assertNotNull(repo.get(key("first")))
        fail = true
        assertNull(repo.get(key("failed")))
        assertEquals(0, repo.stats().bytes)
    }
}
