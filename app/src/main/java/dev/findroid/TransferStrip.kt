package dev.findroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferStrip(model: FinderModel, openTransfers: () -> Unit) {
    val now = rememberTransferClock()
    val progress = model.transferProgress
    val paused = model.activeTransfer?.state == TransferState.PAUSED
    HorizontalDivider(color = Border, thickness = .5.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().background(SubtleSurface)) {
        // Leave room for the filename and phase summary as text grows or the pane narrows.
        val separateControls = maxWidth < 600.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (separateControls)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TransferStripIndicator(model, progress)
                    TransferStripText(model, progress, paused, now,
                        Modifier.weight(1f).padding(horizontal = 13.dp))
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TransferStripActions(model, openTransfers)
                }
            }
        else
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransferStripIndicator(model, progress)
                TransferStripText(model, progress, paused, now,
                    Modifier.weight(1f).padding(horizontal = 13.dp))
                TransferStripActions(model, openTransfers)
            }
    }
}

@Composable
private fun TransferStripIndicator(model: FinderModel, progress: TransferProgress) {
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
}

@Composable
private fun TransferStripText(
    model: FinderModel,
    progress: TransferProgress,
    paused: Boolean,
    now: Long,
    modifier: Modifier,
) {
    Column(modifier) {
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            transferPhaseSummary(progress, paused, now).ifEmpty {
                model.activeTransfer?.estimate?.description() ?: "Checking source totals"
            },
            color = Muted,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TransferStripActions(model: FinderModel, openTransfers: () -> Unit) {
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
