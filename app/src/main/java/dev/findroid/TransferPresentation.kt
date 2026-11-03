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
    if (paused) return listOf("Paused", amount).filter { it.isNotEmpty() }.joinToString(" · ")
    val waiting = progress.waitingMessage(now)
    if (waiting != null) return listOf(waiting, amount).filter { it.isNotEmpty() }.joinToString(" · ")
    val speed = progress.bytesPerSecond?.takeIf { progress.fresh(now) }?.let { "${formatSize(it)}/s" }
    val eta = transferEta(progress).takeIf { progress.fresh(now) }
    val status = when {
        now - progress.lastProgressAtMillis >= 10_000 -> "No new progress reported"
        speed != null -> null
        progress.stage == TransferStage.PREPARING -> "Preparing files…"
        progress.stage == TransferStage.VERIFYING -> "Checking file contents…"
        progress.stage == TransferStage.FINISHING -> "Finishing changes…"
        progress.stage == TransferStage.COPYING -> "Calculating recent speed…"
        else -> null
    }
    return listOfNotNull(amount.takeIf { it.isNotEmpty() }, speed, eta, status).joinToString(" · ")
}

fun transferPhaseAmount(progress: TransferProgress): String {
    val total = progress.phaseTotalBytes
    return when {
        total != null -> "${formatSize(progress.phaseBytes)} / ${formatSize(total)}"
        progress.phaseBytes > 0 -> "${formatSize(progress.phaseBytes)} " +
            if (progress.stage == TransferStage.VERIFYING) "checked" else "processed"
        progress.inspectedItems > 0 -> "${progress.inspectedItems} items inspected"
        progress.phase.isEmpty() -> "${formatSize(progress.bytes)} transferred"
        else -> ""
    }
}

fun transferEta(progress: TransferProgress): String? {
    return progress.secondsRemaining
        ?.takeIf { it > 0 }
        ?.let { seconds ->
            val duration = when {
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${(seconds - 1) / 60 + 1} min"
                else -> "${(seconds - 1) / 3600 + 1} h"
            }
            "About $duration left in this phase"
        }
}

fun TransferEstimate.description(): String =
    "${if (complete) "About" else "At least"} ${formatSize(knownBytes)} · ${files} ${if (files == 1) "file" else "files"} · ${folders} ${if (folders == 1) "folder" else "folders"}" +
        if (complete) "" else " · partial totals"
