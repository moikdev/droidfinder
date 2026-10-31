package dev.droidfinder

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

data class TransferProgress(val bytes: Long = 0, val items: Int = 0, val current: String = "")

/** Shared cancellation token. Checks run between files and between 64 KiB stream chunks. */
class TransferControl(private val progress: (TransferProgress) -> Unit = {}) {
    @Volatile
    var cancelled: Boolean = false
        private set

    private var bytes = 0L
    private var items = 0
    private var current = ""
    private val activeStreams = ConcurrentHashMap.newKeySet<Closeable>()

    @Synchronized
    fun cancel() {
        if (cancelled) return
        cancelled = true
        val streams = activeStreams.toList()
        if (streams.isNotEmpty())
            Thread({ streams.forEach { runCatching { it.close() } } }, "cancel-file-transfer")
                .apply {
                    isDaemon = true
                    start()
                }
    }

    fun check() {
        if (cancelled || Thread.currentThread().isInterrupted)
            throw CancellationException("Transfer cancelled. Completed items were kept.")
    }

    fun item(name: String) {
        check()
        current = name
        items++
        report()
    }

    fun bytes(count: Int) {
        check()
        bytes += count
        report()
    }

    private fun report() = progress(TransferProgress(bytes, items, current))

    fun copy(input: InputStream, output: OutputStream) {
        check()
        activeStreams.add(input)
        activeStreams.add(output)
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                bytes(count)
            }
        } catch (error: Exception) {
            check()
            throw error
        } finally {
            activeStreams.remove(input)
            activeStreams.remove(output)
        }
    }
}
