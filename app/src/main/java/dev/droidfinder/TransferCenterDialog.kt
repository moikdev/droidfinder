package dev.droidfinder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun TransferCenterDialog(
    model: FinderModel,
    onDismiss: () -> Unit,
    onEnableNotifications: () -> Unit = {},
) {
    var retry by remember { mutableStateOf<TransferJob?>(null) }
    var resetJournal by remember { mutableStateOf(false) }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(.9f)
                .widthIn(max = 900.dp)
                .fillMaxHeight(.86f)
                .testTag("transfer-center"),
            color = CanvasBackground,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Transfers",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink,
                        )
                        Text(
                            "Queued work and per-file results · one operation at a time",
                            fontSize = 12.sp,
                            color = Muted,
                        )
                    }
                    TextButton(
                        onClick = model::clearTransferHistory,
                        enabled = model.transferJobs.any { it.terminal },
                    ) {
                        Text("Clear finished")
                    }
                    ToolButton(Icons.Outlined.Close, "Close transfers", onDismiss)
                }
                HorizontalDivider(color = Border)
                if (!model.notificationsEnabled)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Allow notifications to control transfers outside the app.",
                            Modifier.weight(1f),
                            fontSize = 12.sp,
                            color = Muted,
                        )
                        TextButton(onClick = onEnableNotifications) { Text("Enable notifications") }
                    }
                model.journalError?.let { error ->
                    Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    if (model.damagedTransferJournal)
                        TextButton(onClick = { resetJournal = true }) {
                            Text("Preserve damaged history and reset…")
                        }
                    else
                        TextButton(onClick = model::retryTransferJournal) {
                            Text("Retry saving history")
                        }
                }
                if (model.transferJobs.isEmpty())
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No transfers yet", color = Muted)
                    }
                else
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            model.transferJobs.sortedBy { if (it.terminal) 1 else 0 },
                            key = { it.id },
                        ) { job ->
                            TransferJobCard(job, model, { retry = job }, onDismiss)
                        }
                    }
                HorizontalDivider(color = Border)
                Text(
                    "Paused transfers keep their place. Interrupted work requires destination review before retrying.",
                    Modifier.padding(16.dp),
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
        }
    }
    if (resetJournal)
        AlertDialog(
            onDismissRequest = { resetJournal = false },
            title = { Text("Reset damaged transfer history?") },
            text = {
                Text(
                    "The damaged journal files will be kept in private app storage for recovery. The transfer list will start empty. Your source and destination files will not be changed. Review interrupted destinations before submitting new transfers.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.preserveAndResetTransferJournal()
                        resetJournal = false
                    },
                ) {
                    Text("Preserve and reset")
                }
            },
            dismissButton = { TextButton(onClick = { resetJournal = false }) { Text("Cancel") } },
        )
    retry?.let { job ->
        AlertDialog(
            onDismissRequest = { retry = null },
            title = { Text("Retry remaining items?") },
            text = {
                Text(
                    "Review the destination first. ${job.remaining.size} items have no recorded destination. After an interruption, an item may already have been published before its result was recorded. Retrying keeps both copies rather than overwriting. A partially removed move source needs manual review.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.retryTransfer(job.id)
                        retry = null
                    },
                ) {
                    Text("Retry remaining")
                }
            },
            dismissButton = { TextButton(onClick = { retry = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TransferJobCard(
    job: TransferJob,
    model: FinderModel,
    retry: () -> Unit,
    dismiss: () -> Unit,
) {
    var expanded by remember(job.id) { mutableStateOf(false) }
    val active = job.id == model.activeTransferId
    Surface(
        color = SubtleSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(.5.dp, Border),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (job.move) Icons.AutoMirrored.Outlined.DriveFileMove
                    else Icons.Outlined.ContentCopy,
                    null,
                    tint = SelectedInk,
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(job.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
                    Text(job.state.title, fontSize = 12.sp, color = Muted)
                }
                if (job.state == TransferState.RUNNING || job.state == TransferState.QUEUED)
                    TextButton(onClick = { model.pauseTransfer(job.id) }) { Text("Pause") }
                if (job.state == TransferState.PAUSED)
                    TextButton(onClick = { model.resumeTransfer(job.id) }) { Text("Resume") }
                if (!job.terminal)
                    TextButton(onClick = { model.cancelQueuedTransfer(job.id) }) { Text("Cancel") }
                else if (job.remaining.isNotEmpty()) TextButton(onClick = retry) { Text("Retry…") }
            }
            if (active) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))
                Text(
                    "${formatSize(model.transferProgress.bytes)} transferred · ${model.transferProgress.current}",
                    fontSize = 11.sp,
                    color = Muted,
                )
                val elapsed = ((System.currentTimeMillis() - job.started) / 1000).coerceAtLeast(1)
                Text(
                    "Average ${formatSize(model.transferProgress.bytes / elapsed)}/s · total and remaining time may be unknown",
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
            Text(
                "From: ${job.paths.firstOrNull()?.let(model::displayPath).orEmpty()}${if (job.paths.size > 1) " + ${job.paths.size - 1} more" else ""}",
                Modifier.padding(top = 8.dp),
                fontSize = 11.sp,
                color = Muted,
            )
            Text("To: ${model.displayPath(job.destination)}", fontSize = 11.sp, color = Muted)
            Text(
                "${job.successful.size} completed · ${job.results.count { it.skipped }} skipped · ${job.results.count { it.error != null }} need attention",
                Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                color = Ink,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        model.navigate(job.destination)
                        dismiss()
                    },
                ) {
                    Text("Review destination")
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide files" else "Show files")
                }
                if (!active && job.state in setOf(TransferState.QUEUED, TransferState.PAUSED)) {
                    ToolButton(
                        Icons.Outlined.ArrowUpward,
                        "Move transfer earlier",
                        { model.moveQueuedTransfer(job.id, -1) },
                    )
                    ToolButton(
                        Icons.Outlined.ArrowDownward,
                        "Move transfer later",
                        { model.moveQueuedTransfer(job.id, 1) },
                    )
                }
            }
            if (expanded)
                job.paths.forEach { source ->
                    val result = job.results.lastOrNull { it.source == source }
                    HorizontalDivider(color = Border)
                    Text(
                        model.displayPath(source),
                        Modifier.padding(top = 8.dp),
                        fontSize = 11.sp,
                        color = Ink,
                    )
                    Text(
                        result?.error
                            ?: if (result?.skipped == true) "Skipped — original kept"
                            else
                                result?.destination?.let { "Completed → ${model.displayPath(it)}" }
                                    ?: if (job.inFlight == source)
                                        "In progress at interruption or current copy"
                                    else "Not completed",
                        Modifier.padding(bottom = 8.dp),
                        fontSize = 11.sp,
                        color = Muted,
                    )
                }
        }
    }
}
