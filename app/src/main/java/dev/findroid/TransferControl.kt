package dev.findroid

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

data class TransferProgress(
    val bytes: Long = 0,
    val items: Int = 0,
    val current: String = "",
    val phase: String = "",
    val phaseBytes: Long = 0,
    val phaseTotalBytes: Long? = null,
    val activeMillis: Long = 0,
    val updatedAtMillis: Long = 0,
) {
    val fraction: Float?
        get() =
            phaseTotalBytes
                ?.takeIf { it > 0 && phaseBytes <= it }
                ?.let { (phaseBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }

    val bytesPerSecond: Long?
        get() =
            if (activeMillis >= 1500 && phaseBytes > 0)
                (phaseBytes.toDouble() * 1000 / activeMillis).toLong().coerceAtLeast(1)
            else null

    val secondsRemaining: Long?
        get() {
            val rate = bytesPerSecond ?: return null
            val total = phaseTotalBytes ?: return null
            if (phaseBytes > total || total <= 0) return null
            return kotlin.math.ceil((total - phaseBytes).toDouble() / rate).toLong()
        }

    // The UI clock ticks once a second; a newly reported sample can be newer than that tick.
    fun fresh(now: Long = transferClockMillis()) = now - updatedAtMillis <= 3000
}

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
    private var phaseBytes = 0L
    private var phaseTotalBytes: Long? = null
    private var phaseStarted = clockMillis()
    private var pausedMillis = 0L
    private var pausedAt: Long? = null

    /** Reporting a phase must not hide a result by throwing after atomic publication. */
    fun phase(title: String, totalBytes: Long? = null) {
        synchronized(pauseLock) {
            phase = title
            phaseBytes = 0
            phaseTotalBytes = totalBytes?.takeIf { it >= 0 }
            phaseStarted = clockMillis()
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
        }
        report()
    }

    fun bytes(count: Int) {
        check()
        synchronized(pauseLock) {
            bytes += count
            phaseBytes += count
            if (phaseTotalBytes?.let { phaseBytes > it } == true) phaseTotalBytes = null
        }
        report()
    }

    private fun report() {
        val value =
            synchronized(pauseLock) {
                val now = clockMillis()
                TransferProgress(
                    bytes,
                    items,
                    current,
                    phase,
                    phaseBytes,
                    phaseTotalBytes,
                    (now - phaseStarted - pausedMillis - (pausedAt?.let { now - it } ?: 0))
                        .coerceAtLeast(0),
                    now,
                )
            }
        snapshot = value
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

    fun copy(input: InputStream, output: OutputStream, expectedBytes: Long? = null): Long {
        require(expectedBytes == null || expectedBytes >= 0)
        var copied = 0L
        check()
        activeStreams.add(input)
        activeStreams.add(output)
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) {
                    check(expectedBytes == null || copied == expectedBytes) {
                        "The source changed or the provider returned an incomplete file. The source was kept."
                    }
                    return copied
                }
                check(expectedBytes == null || count <= expectedBytes - copied) {
                    "The source grew beyond its reported size. The source was kept."
                }
                output.write(buffer, 0, count)
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
