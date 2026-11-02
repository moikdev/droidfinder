package dev.findroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TransferStrip(model: FinderModel, openTransfers: () -> Unit) {
    val now = rememberTransferClock()
    val progress = model.transferProgress
    val paused = model.activeTransfer?.state == TransferState.PAUSED
    HorizontalDivider(color = Border, thickness = .5.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(SubtleSurface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model.busy != null) {
            val fraction = progress.fraction
            if (fraction != null)
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(19.dp),
                    strokeWidth = 2.dp,
                )
            else CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(
                (if (paused) "Paused · " else "") +
                    (progress.phase.takeIf { model.busy != null && it.isNotEmpty() }
                        ?: model.busy
                        ?: "Transfers queued or paused") +
                    progress.current
                        .takeIf { model.busy != null && it.isNotEmpty() }
                        ?.let { " · $it" }
                        .orEmpty(),
                color = Ink,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                transferPhaseSummary(progress, paused, now).ifEmpty {
                    model.activeTransfer?.estimate?.description() ?: "Checking source totals"
                },
                color = Muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = openTransfers) { Text("Transfers", fontSize = 11.sp) }
        if (model.activeTransfer != null)
            TextButton(
                onClick = {
                    model.activeTransferId?.let {
                        if (model.activeTransfer?.state == TransferState.PAUSED)
                            model.resumeTransfer(it)
                        else model.pauseTransfer(it)
                    }
                },
            ) {
                Text(
                    if (model.activeTransfer?.state == TransferState.PAUSED) "Resume" else "Pause",
                    fontSize = 11.sp,
                )
            }
        if (model.busy != null)
            TextButton(onClick = model::cancelTransfer) {
                Text("Cancel transfer", fontSize = 11.sp)
            }
    }
}
