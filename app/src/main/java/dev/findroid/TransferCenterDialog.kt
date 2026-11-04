package dev.findroid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun TransferCenterDialog(
    model: FinderModel,
    onDismiss: () -> Unit,
    onEnableNotifications: () -> Unit = {},
    initialJobId: String? = null,
) {
    var retry by remember { mutableStateOf<TransferJob?>(null) }
    var resetJournal by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(initialJobId != null) }
    var visibleJobs by remember { mutableIntStateOf(20) }
    val live = model.transferJobs.filter { !it.terminal }
    val past = model.transferJobs.filter { it.terminal }.sortedByDescending { it.finished }
    val allShown = live + if (history || live.isEmpty()) past else emptyList()
    // A link from Recovery opens the chosen existing record without replaying it.
    val shown = initialJobId?.let { id -> allShown.sortedBy { if (it.id == id) 0 else 1 } } ?: allShown
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            val bodyLimit = (maxHeight - 120.dp).coerceIn(80.dp, 420.dp)
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("transfer-center"),
                color = CanvasBackground,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(.5.dp, Border),
                shadowElevation = 16.dp,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Transfers",
                            Modifier.weight(1f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink,
                        )
                        if (live.size > 1)
                            Text(
                                "${live.size} in queue",
                                Modifier.padding(end = 8.dp),
                                fontSize = 11.sp,
                                color = Muted,
                            )
                        ToolButton(Icons.Outlined.Close, "Close transfers", onDismiss)
                    }
                    HorizontalDivider(color = Border, thickness = .5.dp)
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = bodyLimit)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        model.journalError?.let { error ->
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                Text(
                                    error,
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                )
                                if (model.damagedTransferJournal)
                                    TextButton(onClick = { resetJournal = true }) {
                                        Text("Preserve damaged history and reset…")
                                    }
                                else
                                    TextButton(onClick = model::retryTransferJournal) {
                                        Text("Retry saving history")
                                    }
                            }
                        }
                        if (shown.isEmpty())
                            Box(
                                Modifier.fillMaxWidth().height(96.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("No transfers yet", color = Muted, fontSize = 13.sp)
                            }
                        shown.take(visibleJobs).forEachIndexed { index, job ->
                            key(job.id) { TransferJobCard(job, model, { retry = job }, onDismiss, initiallyExpanded = job.id == initialJobId) }
                            if (index < minOf(shown.size, visibleJobs) - 1)
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 20.dp),
                                    color = Border,
                                    thickness = .5.dp,
                                )
                        }
                        if (shown.size > visibleJobs)
                            TextButton(
                                onClick = { visibleJobs += 20 },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                Text("Show more transfers (${visibleJobs} of ${shown.size})")
                            }
                    }
                    if (past.isNotEmpty() || !model.notificationsEnabled && live.isNotEmpty()) {
                        HorizontalDivider(color = Border, thickness = .5.dp)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (past.isNotEmpty() && live.isNotEmpty())
                                TextButton(onClick = { history = !history }) {
                                    Icon(
                                        if (history) Icons.Outlined.ExpandLess
                                        else Icons.Outlined.History,
                                        null,
                                        Modifier.size(16.dp),
                                    )
                                    Text(
                                        if (history) "Hide history" else "History (${past.size})",
                                        Modifier.padding(start = 6.dp),
                                        fontSize = 11.sp,
                                    )
                                }
                            else if (past.isNotEmpty())
                                Text(
                                    "${past.size} finished",
                                    Modifier.padding(start = 12.dp),
                                    fontSize = 11.sp,
                                    color = Muted,
                                )
                            Spacer(Modifier.weight(1f))
                            if (!model.notificationsEnabled && live.isNotEmpty())
                                TextButton(onClick = onEnableNotifications) {
                                    Text("Enable notifications", fontSize = 11.sp)
                                }
                            if (past.isNotEmpty())
                                TextButton(onClick = model::clearTransferHistory) {
                                    Text("Clear finished", fontSize = 11.sp)
                                }
                        }
                    }
                }
            }
        }
    }
    PartialCopyReviewDialog(model, onDismiss)
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
                    "Review the destination first. ${job.retryTargets.size} unfinished items can be retried in their original destination folders. Published, skipped, and ambiguous in-flight items are excluded. Retrying keeps both copies. Review partial moves and Trash before continuing." +
                        if (job.verifyCopies) " SHA-256 readback verification will be repeated." else "",
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
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(job.id) { mutableStateOf(initiallyExpanded) }
    var visibleMembers by remember(job.id) { mutableIntStateOf(50) }
    var visibleVerifications by remember(job.id) { mutableIntStateOf(20) }
    val active = job.id == model.activeTransferId
    val progress = model.transferProgress
    val paused = job.state == TransferState.PAUSED
    val now = if (active) rememberTransferClock() else 0L
    val destination =
        model.displayPath(job.destination).substringAfterLast(" / ").substringAfterLast('/')
    val singleName =
        job.paths.singleOrNull()?.let { path ->
            if (isDocumentPath(path)) model.documents.knownTitle(path) ?: "Source item"
            else model.title(path)
        }
    val title =
        if (active && progress.current.isNotEmpty()) progress.current else singleName ?: job.title
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).testTag("transfer-job-${job.id}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Blue.copy(alpha = .08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        job.state == TransferState.COMPLETED -> Icons.Outlined.Check
                        job.move -> Icons.AutoMirrored.Outlined.DriveFileMove
                        else -> Icons.Outlined.ContentCopy
                    },
                    null,
                    Modifier.size(18.dp),
                    tint = SelectedInk,
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink,
                )
                Text(
                    when {
                        paused -> "Paused · $destination"
                        active ->
                            "${progress.phase.ifEmpty { if (job.move) "Moving" else "Copying" }} · $destination"
                        else -> "${job.state.title} · $destination"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
            if (paused)
                FilledTonalButton(
                    onClick = { model.resumeTransfer(job.id) },
                    shape = RoundedCornerShape(7.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text("Resume", fontSize = 12.sp)
                }
            else if (!job.terminal)
                TextButton(onClick = { model.pauseTransfer(job.id) }) {
                    Text("Pause", fontSize = 12.sp)
                }
            if (!job.terminal)
                ToolButton(
                    Icons.Outlined.Close,
                    "Cancel transfer",
                    { model.cancelQueuedTransfer(job.id) },
                    compact = true,
                )
            else if (job.retryTargets.isNotEmpty())
                TextButton(onClick = retry) { Text("Retry…", fontSize = 12.sp) }
        }
        if (active) {
            val fraction = progress.fraction
            Spacer(Modifier.height(12.dp))
            if (fraction != null)
                Box(
                    Modifier.fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Border)
                        .progressSemantics(fraction),
                ) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Blue))
                }
            else if (paused)
                Box(
                    Modifier.fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Border),
                )
            else
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(4.dp),
                    color = Blue,
                    trackColor = Border,
                )
            Text(
                transferPhaseSummary(progress, paused, now).ifEmpty { "Checking files…" },
                Modifier.fillMaxWidth().padding(top = 8.dp),
                fontSize = 12.sp,
                color = Muted,
            )
        }
        job.partialCopy?.let { saved ->
            Text("Saved checkpoint · ${formatSize(saved.offset)} of ${formatSize(saved.sourceStamp.size)}",
                Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 12.sp, color = Ink)
            if (job.terminal)
                TextButton(onClick = { model.reviewSavedCopy(job.id) }, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("review-saved-copy-${job.id}")) { Text("Review saved copy…") }
        }
        if (job.reusedBytes > 0)
            Text("${formatSize(job.reusedBytes)} of checked saved bytes reused · ${formatSize(job.bytes)} newly copied",
                Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 12.sp, color = Muted)
        if (job.verifyCopies)
            Text("Copy verification · ${copyVerificationSummary(job)}", Modifier.fillMaxWidth().padding(top = 8.dp),
                fontSize = 12.sp, color = Ink)
        if (job.state == TransferState.INTERRUPTED)
            Text(
                "Review the destination before retrying.",
                Modifier.padding(top = 10.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
            )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    Modifier.size(16.dp),
                )
                Text(
                    if (expanded) "Hide details" else "Details",
                    Modifier.padding(start = 4.dp),
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!active && job.state in setOf(TransferState.QUEUED, TransferState.PAUSED)) {
                ToolButton(
                    Icons.Outlined.ArrowUpward,
                    "Move transfer earlier",
                    { model.moveQueuedTransfer(job.id, -1) },
                    compact = true,
                )
                ToolButton(
                    Icons.Outlined.ArrowDownward,
                    "Move transfer later",
                    { model.moveQueuedTransfer(job.id, 1) },
                    compact = true,
                )
            }
            TextButton(
                onClick = {
                    model.navigate(job.destination)
                    dismiss()
                },
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(15.dp))
                Text("Open folder", Modifier.padding(start = 6.dp), fontSize = 11.sp)
            }
        }
        if (expanded) {
            HorizontalDivider(color = Border, thickness = .5.dp)
            Column(
                Modifier.padding(top = 12.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                TransferDetail(
                    "From",
                    job.paths.firstOrNull()?.let(model::displayPath).orEmpty() +
                        if (job.paths.size > 1) " + ${job.paths.size - 1} more" else "",
                )
                TransferDetail("To", model.displayPath(job.destination))
                job.partialCopyNotice?.let { TransferDetail("Recovery", it) }
                job.estimate?.let { estimate ->
                    TransferDetail("Contents", estimate.description())
                    if (!estimate.complete)
                        TransferDetail(
                            "Unavailable",
                            listOfNotNull(
                                    "${estimate.unknownFiles} file sizes"
                                        .takeIf { estimate.unknownFiles > 0 },
                                    "${estimate.unreadable} items"
                                        .takeIf { estimate.unreadable > 0 },
                                    "Planning limit reached".takeIf { estimate.limited },
                                )
                                .joinToString(" · "),
                        )
                    TransferDetail(
                        "Free at start",
                        job.availableAtStart?.let(::formatSize) ?: "Not reported",
                    )
                }
                TransferDetail(
                    "Results",
                    "${job.successful.size} completed · ${job.results.count { it.skipped }} skipped · ${job.results.count { it.error != null }} need attention",
                )
            }
            TransferDetail("Verification", copyVerificationSummary(job))
            if (job.verifications.isNotEmpty()) {
                Text("Verification results", Modifier.padding(top = 12.dp), color = Ink, fontWeight = FontWeight.Medium)
                job.verifications.take(visibleVerifications).forEach { result ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(model.displayPath(result.source), color = Ink, fontSize = 12.sp)
                        Text(when {
                            result.status == CopyVerificationStatus.VERIFIED && result.destination == null -> "Verified, but this copy was not published"
                            result.status == CopyVerificationStatus.VERIFIED -> "Verified and copied · ${formatSize(result.bytes)}"
                            result.status == CopyVerificationStatus.SKIPPED -> "Skipped · original kept"
                            else -> result.status.title + " · original kept"
                        }, color = Muted, fontSize = 12.sp)
                        result.error?.let { Text(it, color = Muted, fontSize = 12.sp) }
                        result.expectedSha256?.let { Text("Source SHA-256: $it", color = Muted, fontSize = 11.sp) }
                        result.actualSha256?.let { Text("Copy SHA-256: $it", color = Muted, fontSize = 11.sp) }
                    }
                }
                if (job.verifications.size > visibleVerifications)
                    TextButton(onClick = { visibleVerifications += 20 }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Show more verification results ($visibleVerifications of ${job.verifications.size})")
                    }
            }
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
                job.mergePlans
                    .firstOrNull { it.source == source }
                    ?.let { plan ->
                        Text(
                            "Folder merge · ${plan.members.size} planned units · ${plan.cleanup.title}",
                            fontSize = 11.sp,
                            color = Muted,
                        )
                        plan.recovery?.let {
                            Text(
                                "Source recovery: ${model.displayPath(it)}",
                                fontSize = 11.sp,
                                color = Muted,
                            )
                        }
                        plan.members.take(visibleMembers).forEach { member ->
                            Text(
                                model.displayPath(member.source),
                                Modifier.padding(top = 8.dp, start = 12.dp),
                                fontSize = 11.sp,
                                color = Ink,
                            )
                            Text(
                                member.error
                                    ?: if (member.skipped) "Skipped — original kept"
                                    else
                                        member.destination?.let {
                                            "Copied → ${model.displayPath(it)} · ${member.cleanup.title}"
                                        }
                                            ?: if (job.inFlight == member.source)
                                                "Needs review — may have been published"
                                            else "Not completed",
                                Modifier.padding(start = 12.dp, bottom = 4.dp),
                                fontSize = 11.sp,
                                color = Muted,
                            )
                            member.recovery?.let {
                                Text(
                                    "Recovery: ${model.displayPath(it)}",
                                    Modifier.padding(start = 12.dp),
                                    fontSize = 11.sp,
                                    color = Muted,
                                )
                            }
                        }
                        if (plan.members.size > visibleMembers) {
                            TextButton(onClick = { visibleMembers += 50 }) {
                                Text(
                                    "Show more merge items (${visibleMembers} of ${plan.members.size})",
                                )
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun TransferDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(82.dp), fontSize = 11.sp, color = Muted)
        Text(value, Modifier.weight(1f), fontSize = 11.sp, color = Ink)
    }
}
