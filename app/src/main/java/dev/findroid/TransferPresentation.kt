package dev.findroid

import androidx.compose.runtime.*
import kotlinx.coroutines.delay

@Composable
fun rememberTransferClock(): Long {
    val now by
        produceState(transferClockMillis()) {
            while (true) {
                delay(1000)
                value = transferClockMillis()
            }
        }
    return now
}

fun transferPhaseSummary(
    progress: TransferProgress,
    paused: Boolean,
    now: Long = transferClockMillis(),
): String {
    val amount = transferPhaseAmount(progress)
    if (paused || !progress.fresh(now)) return amount
    val speed = progress.bytesPerSecond?.let { "${formatSize(it)}/s" }
    val eta = transferEta(progress)
    return listOfNotNull(amount.takeIf { it.isNotEmpty() }, speed, eta).joinToString(" · ")
}

fun transferPhaseAmount(progress: TransferProgress): String {
    val total = progress.phaseTotalBytes
    return when {
        total != null -> "${formatSize(progress.phaseBytes)} / ${formatSize(total)}"
        progress.phaseBytes > 0 -> "${formatSize(progress.phaseBytes)} copied"
        progress.phase.isEmpty() -> "${formatSize(progress.bytes)} transferred"
        else -> ""
    }
}

fun transferEta(progress: TransferProgress): String? {
    return progress.secondsRemaining
        ?.takeIf { it > 0 }
        ?.let { seconds ->
            when {
                seconds < 60 -> "About ${seconds}s left"
                seconds < 3600 -> "About ${(seconds + 59) / 60} min left"
                else -> "About ${(seconds + 3599) / 3600} h left"
            }
        }
}

fun TransferEstimate.description(): String =
    "${if (complete) "About" else "At least"} ${formatSize(knownBytes)} · ${files} ${if (files == 1) "file" else "files"} · ${folders} ${if (folders == 1) "folder" else "folders"}" +
        if (complete) "" else " · partial totals"
