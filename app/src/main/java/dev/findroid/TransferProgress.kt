package dev.findroid

/** A phase describes current work, not completion of the whole queued job. */
enum class TransferStage {
    PREPARING,
    COPYING,
    VERIFYING,
    FINISHING,
    WORKING,
}

enum class TransferIo(val waitingMessage: String) {
    SOURCE("Waiting for the source file"),
    DESTINATION("Waiting for the destination"),
    PROVIDER("Waiting for the storage provider"),
    STORAGE("Waiting for storage"),
}

data class TransferProgress(
    val bytes: Long = 0,
    val items: Int = 0,
    val current: String = "",
    val phase: String = "",
    val phaseBytes: Long = 0,
    val phaseTotalBytes: Long? = null,
    val activeMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    val bytesPerSecond: Long? = null,
    val stage: TransferStage = TransferStage.WORKING,
    val io: TransferIo? = null,
    val ioStartedAtMillis: Long = 0,
    val lastProgressAtMillis: Long = updatedAtMillis,
    val inspectedItems: Int = 0,
) {
    val fraction: Float?
        get() =
            phaseTotalBytes
                ?.takeIf { it > 0 && phaseBytes <= it }
                ?.let { (phaseBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }

    val secondsRemaining: Long?
        get() {
            val rate = bytesPerSecond?.takeIf { it > 0 } ?: return null
            val total = phaseTotalBytes ?: return null
            if (phaseBytes > total || total <= 0) return null
            return kotlin.math.ceil((total - phaseBytes).toDouble() / rate).toLong()
        }

    // The UI clock ticks once a second; a newly reported sample can be newer than that tick.
    fun fresh(now: Long = transferClockMillis()) = now - updatedAtMillis <= 3000

    fun waitingMessage(now: Long = transferClockMillis()): String? =
        io?.takeIf { now - ioStartedAtMillis >= 3000 }?.waitingMessage
}

/** Bounded recent throughput; preparation, other phases and paused time are excluded. */
internal class TransferRateWindow {
    private data class Sample(val activeMillis: Long, val bytes: Long)
    private val samples = ArrayDeque<Sample>()
    private var lastBytesAt: Long? = null
    private var lastBytes = 0L

    internal val retainedSamples get() = samples.size

    init { reset() }

    fun reset(activeMillis: Long = 0, bytes: Long = 0) {
        samples.clear()
        samples.addLast(Sample(activeMillis, bytes))
        lastBytesAt = null
        lastBytes = bytes
    }

    fun record(activeMillis: Long, bytes: Long) {
        val previous = lastBytesAt
        if (bytes <= lastBytes) return
        // A long unanswered read is not evidence of an instantaneous burst on recovery.
        if (activeMillis < samples.last().activeMillis ||
            (previous ?: samples.first().activeMillis).let { activeMillis - it > 3000 }) {
            reset(activeMillis, bytes)
        } else if (activeMillis - samples.last().activeMillis >= 250) {
            samples.addLast(Sample(activeMillis, bytes))
        }
        lastBytesAt = activeMillis
        lastBytes = bytes
        trim(activeMillis)
    }

    fun rate(activeMillis: Long): Long? {
        val last = lastBytesAt ?: return null
        if (activeMillis - last > 3000 || activeMillis < last) return null
        trim(activeMillis)
        val first = samples.first()
        val elapsed = activeMillis - first.activeMillis
        if (elapsed < 1500 || lastBytes <= first.bytes) return null
        return ((lastBytes - first.bytes).toDouble() * 1000 / elapsed)
            .toLong().takeIf { it > 0 }
    }

    private fun trim(activeMillis: Long) {
        // At most one retained point per 250 ms, within the most recent six seconds.
        while (samples.size > 1 && samples.first().activeMillis < activeMillis - 6000)
            samples.removeFirst()
    }
}
