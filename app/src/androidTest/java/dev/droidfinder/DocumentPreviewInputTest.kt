package dev.droidfinder

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

class DocumentPreviewInputTest {
    @Test
    fun cancellationEndsAnIdlePipeReadBeforeTheWriterCloses() {
        val pipe = ParcelFileDescriptor.createPipe()
        val control = ListingControl()
        val reading = CountDownLatch(1)
        val waits = java.util.concurrent.atomic.AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stream = control.stream {
                DocumentPreviewInput(
                    AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH),
                ) {
                    control.check()
                    if (waits.incrementAndGet() >= 2) reading.countDown()
                }
            }
            val future =
                executor.submit<Int> {
                    stream.use {
                        it.read()
                    }
                }
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            control.cancel()
            val failure =
                assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }
            assertTrue(failure.cause is kotlinx.coroutines.CancellationException)
            // The writer is intentionally still open: cancellation must not depend on EOF.
            assertTrue(pipe[1].fileDescriptor.valid())
        } finally {
            pipe[1].close()
            runCatching { pipe[0].close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun declaredAssetOffsetAndLengthDoNotExposeSurroundingBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file =
            File(context.cacheDir, "slice-${System.nanoTime()}").apply {
                writeText("prefix-payload-suffix")
            }
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            DocumentPreviewInput(AssetFileDescriptor(descriptor, 7, 7), {}).use {
                assertEquals("payload", it.readBytes().toString(Charsets.UTF_8))
                assertEquals(-1, it.read())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun unknownLengthPipeReadsChunksAndFinishesAtEof() {
        val pipe = ParcelFileDescriptor.createPipe()
        val expected = ByteArray(150_000) { (it % 251).toByte() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(expected) }
            }
            DocumentPreviewInput(
                    AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH),
                    {},
                )
                .use { assertArrayEquals(expected, it.readBytes()) }
            writer.get(3, TimeUnit.SECONDS)
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelingACacheWaitReleasesOnlyThatReader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder =
            File(context.filesDir, "ProviderFixtures/CacheWait-${System.nanoTime()}").apply {
                mkdirs()
            }
        File(folder, "Gate.jpg").writeText("waiting")
        val tree =
            android.provider.DocumentsContract.buildTreeDocumentUri(
                "dev.droidfinder.fixtures",
                "root/${folder.name}",
            )
        val uri =
            android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree,
                "root/${folder.name}/Gate.jpg",
            )
        val gateway = DocumentAccess(context)
        val entry = gateway.read(uri.toString())
        val first = ListingControl()
        val second = ListingControl()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val opened = CountDownLatch(1)
        dev.droidfinder.testing.FixtureDocumentsProvider.transferGate = gate
        dev.droidfinder.testing.FixtureDocumentsProvider.gateOpened = opened
        val executor = Executors.newFixedThreadPool(2)
        try {
            val producing = executor.submit<File> { gateway.materialize(entry, first) }
            assertTrue(opened.await(3, TimeUnit.SECONDS))
            val waiting =
                executor.submit<File> {
                    started.countDown()
                    gateway.materialize(entry, second)
                }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            second.cancel()
            val cancelled =
                assertThrows(ExecutionException::class.java) { waiting.get(2, TimeUnit.SECONDS) }
            assertTrue(cancelled.cause is kotlinx.coroutines.CancellationException)
            assertFalse(producing.isDone)
            first.cancel()
            assertThrows(ExecutionException::class.java) { producing.get(2, TimeUnit.SECONDS) }
            assertFalse(File(entry.file.parentFile, "preview.tmp").exists())
        } finally {
            first.cancel()
            second.cancel()
            gate.countDown()
            executor.shutdownNow()
            dev.droidfinder.testing.FixtureDocumentsProvider.transferGate = null
            dev.droidfinder.testing.FixtureDocumentsProvider.gateOpened = null
            folder.deleteRecursively()
        }
    }
}
