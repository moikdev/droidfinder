package dev.findroid

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** Shared cancellation token. Checks run between files and between 64 KiB stream chunks. */
class TransferControl(
    private val clockMillis: () -> Long = ::transferClockMillis,
    private val progress: (TransferProgress) -> Unit = {},
) {
    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    var paused = false
        private set

    private val pauseLock = Object()

    fun pause() {
        synchronized(pauseLock) {
            if (!paused) pausedAt = clockMillis()
            paused = true
        }
        report()
    }

    fun resume() {
        synchronized(pauseLock) {
            pausedAt?.let { pausedMillis += (clockMillis() - it).coerceAtLeast(0) }
            pausedAt = null
            paused = false
            pauseLock.notifyAll()
        }
        report()
    }

    private var bytes = 0L
    private var items = 0
    private var current = ""
    private var phase = ""
    private var stage = TransferStage.WORKING
    private val rate = TransferRateWindow()
    private var lastProgressAt = clockMillis()
    private data class StorageCall(val kind: TransferIo, val started: Long)
    private var storageCall: StorageCall? = null
    private var phaseBytes = 0L
    private var inspectedItems = 0
    private var phaseTotalBytes: Long? = null
    private var phaseStarted = clockMillis()
    private var pausedMillis = 0L
    private var pausedAt: Long? = null

    /** Reporting a phase must not hide a result by throwing after atomic publication. */
    fun phase(title: String, totalBytes: Long? = null, stage: TransferStage = TransferStage.WORKING) {
        synchronized(pauseLock) {
            phase = title
            this.stage = stage
            rate.reset()
            phaseBytes = 0
            inspectedItems = 0
            phaseTotalBytes = totalBytes?.takeIf { it >= 0 }
            phaseStarted = clockMillis()
            lastProgressAt = phaseStarted
            pausedMillis = 0
            pausedAt = phaseStarted.takeIf { paused }
        }
        report()
    }

    @Volatile
    var snapshot = TransferProgress()
        private set

    private val activeStreams = ConcurrentHashMap.newKeySet<Closeable>()

    @Synchronized
    fun cancel() {
        if (cancelled) return
        cancelled = true
        resume()
        val streams = activeStreams.toList()
        if (streams.isNotEmpty())
            Thread({ streams.forEach { runCatching { it.close() } } }, "cancel-file-transfer")
                .apply {
                    isDaemon = true
                    start()
                }
    }

    fun check() {
        synchronized(pauseLock) {
            while (paused && !cancelled && !Thread.currentThread().isInterrupted) pauseLock.wait(
                250,
            )
        }
        if (cancelled || Thread.currentThread().isInterrupted)
            throw CancellationException("Transfer cancelled. Completed items were kept.")
    }

    fun item(name: String) {
        check()
        synchronized(pauseLock) {
            current = name
            items++
            lastProgressAt = clockMillis()
        }
        report()
    }

    fun inspected() {
        check()
        synchronized(pauseLock) {
            inspectedItems++
            lastProgressAt = clockMillis()
        }
        report()
    }

    fun bytes(count: Int) = countBytes(count, copied = true)

    /** Verification advances its own phase without inflating the transferred-byte total. */
    fun verifiedBytes(count: Int) = countBytes(count, copied = false)

    private fun countBytes(count: Int, copied: Boolean) {
        require(count >= 0)
        check()
        if (count == 0) return
        synchronized(pauseLock) {
            if (copied) bytes += count
            phaseBytes += count
            val now = clockMillis()
            lastProgressAt = now
            rate.record(activeMillis(now), phaseBytes)
            if (phaseTotalBytes?.let { phaseBytes > it } == true) phaseTotalBytes = null
        }
        report()
    }

    private fun activeMillis(now: Long): Long =
        (now - phaseStarted - pausedMillis - (pausedAt?.let { now - it } ?: 0))
            .coerceAtLeast(0)

    /** The running foreground service samples long calls; no timer is kept while idle. */
    fun refreshProgress() = report()

    /** Track the exact blocking request without publishing every short stream read/write. */
    fun <T> storage(kind: TransferIo, work: () -> T): T {
        val call = StorageCall(kind, clockMillis())
        val previous = synchronized(pauseLock) {
            val previous = storageCall
            storageCall = call
            previous
        }
        try {
            return work()
        } finally {
            synchronized(pauseLock) {
                if (storageCall === call) storageCall = previous
            }
        }
    }

    @Synchronized
    private fun report() {
        val value =
            synchronized(pauseLock) {
                val now = clockMillis()
                val active = activeMillis(now)
                TransferProgress(
                    bytes = bytes,
                    items = items,
                    current = current,
                    phase = phase,
                    phaseBytes = phaseBytes,
                    phaseTotalBytes = phaseTotalBytes,
                    activeMillis = active,
                    updatedAtMillis = now,
                    bytesPerSecond = rate.rate(active),
                    stage = stage,
                    io = storageCall?.kind,
                    ioStartedAtMillis = storageCall?.started ?: 0,
                    lastProgressAtMillis = lastProgressAt,
                    inspectedItems = inspectedItems,
                ).also { snapshot = it }
            }
        progress(value)
    }

    fun <T> guard(stream: Closeable, work: () -> T): T {
        activeStreams.add(stream)
        try {
            check()
            return work()
        } catch (error: Exception) {
            check()
            throw error
        } finally {
            activeStreams.remove(stream)
        }
    }

    internal suspend fun <T> guardSuspending(stream: Closeable, work: suspend () -> T): T {
        activeStreams.add(stream)
        try {
            check()
            return work()
        } catch (error: Exception) {
            check()
            throw error
        } finally {
            activeStreams.remove(stream)
        }
    }

    fun copy(
        input: InputStream,
        output: OutputStream,
        expectedBytes: Long? = null,
        sourceIsProvider: Boolean = false,
        destinationIsProvider: Boolean = false,
    ): Long {
        require(expectedBytes == null || expectedBytes >= 0)
        var copied = 0L
        check()
        activeStreams.add(input)
        activeStreams.add(output)
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val count = storage(if (sourceIsProvider) TransferIo.PROVIDER else TransferIo.SOURCE) { input.read(buffer) }
                if (count < 0) {
                    check(expectedBytes == null || copied == expectedBytes) {
                        "The source changed or the provider returned an incomplete file. The source was kept."
                    }
                    return copied
                }
                check(expectedBytes == null || count <= expectedBytes - copied) {
                    "The source grew beyond its reported size. The source was kept."
                }
                storage(if (destinationIsProvider) TransferIo.PROVIDER else TransferIo.DESTINATION) { output.write(buffer, 0, count) }
                copied += count
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
