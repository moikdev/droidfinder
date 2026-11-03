package dev.findroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

@Composable
internal fun PartialCopyReviewDialog(model: FinderModel, onOpenDestination: () -> Unit) {
    val state = model.partialCopyReview ?: return
    var discard by remember(state.token) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = model::cancelPartialCopyReview,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            val contentLimit = (maxHeight - 40.dp).coerceAtLeast(80.dp)
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("saved-copy-review"),
                color = CanvasBackground,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                border = BorderStroke(.5.dp, Border),
            ) {
                Column(Modifier.padding(20.dp).heightIn(max = contentLimit).verticalScroll(rememberScrollState())) {
                    Text("Review saved copy", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Column(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("${formatSize(state.record.offset)} saved of ${formatSize(state.record.sourceStamp.size)}",
                            color = Ink, fontSize = 15.sp)
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Source: ${model.displayPath(state.record.source)}", color = Ink, fontSize = 13.sp)
                                Text("Destination: ${model.displayPath(state.record.target)}", color = Ink, fontSize = 13.sp)
                            }
                        }
                        if (state.checking) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Checking the entire source and its saved prefix. Closing this review keeps the saved bytes.",
                                color = Muted, fontSize = 13.sp)
                            if (state.progress.phaseBytes > 0)
                                Text("${formatSize(state.progress.phaseBytes)} checked", color = Muted, fontSize = 13.sp)
                        }
                        state.resume?.let { approval ->
                            Text("The saved bytes match the source. Resuming will check them again, copy the remaining bytes, and read the complete copy back before publishing it.",
                                color = Ink, fontSize = 13.sp)
                            if (approval.discardedTailBytes > 0)
                                Text("${formatSize(approval.discardedTailBytes)} beyond the saved checkpoint was not trusted. It will be discarded and copied again.",
                                    color = Muted, fontSize = 13.sp)
                            if (state.record.verifyCopies)
                                Text("SHA-256 verification remains enabled. Results will appear in the new transfer.", color = Muted, fontSize = 13.sp)
                        }
                        state.cleanup?.let { cleanup ->
                            if (state.record.publishing)
                                Text(when {
                                    !cleanup.destinationAccessible -> "The destination could not be checked. Review its folder before starting another copy."
                                    cleanup.destinationMatches -> "A copy is already at the destination. Open its folder to review it. Cleanup removes only the private saved file."
                                    cleanup.destination != null -> "Another item is at the destination. It will be kept. Review it before starting another copy."
                                    else -> "No item was found at the destination. This copy must be restarted after its saved bytes are discarded."
                                }, color = Ink, fontSize = 13.sp)
                            if (cleanup.observedStaging == null)
                                Text("The private saved file is already absent. Discard clears its recovery record and any empty working folder.",
                                    color = Muted, fontSize = 13.sp)
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                        Text("Closing or cancelling this review starts no copy. A restart always requires a new review.",
                            color = Muted, fontSize = 12.sp)
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.resume != null && !state.checking)
                            Button(onClick = model::resumeSavedCopy, enabled = !state.applying,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("resume-saved-copy")) {
                                Text(if (state.applying) "Saving…" else "Resume saved copy")
                            }
                        if (state.cleanup != null && !state.checking)
                            TextButton(onClick = { discard = true }, enabled = !state.applying,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("discard-saved-copy")) {
                                Text("Discard saved bytes…")
                            }
                        if (!state.checking)
                            TextButton(onClick = { model.reviewSavedCopy(state.jobId) }, enabled = !state.applying,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Check again") }
                        TextButton(onClick = {
                            model.navigate(File(state.record.target).parent!!)
                            model.cancelPartialCopyReview()
                            onOpenDestination()
                        }, enabled = !state.applying, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open destination folder") }
                        TextButton(onClick = model::cancelPartialCopyReview, enabled = !state.applying,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.checking) "Cancel review" else "Close") }
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text("Discard this saved copy?") },
        text = { Text("Remove ${formatSize(state.cleanup?.observedStaging?.size ?: 0L)} of private working data for ${File(state.record.source).name}. The source and every item in the destination folder will be kept. These saved bytes cannot be reused after discarding.") },
        confirmButton = { TextButton(onClick = { discard = false; model.discardSavedCopy() }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Discard saved bytes")
        } },
        dismissButton = { TextButton(onClick = { discard = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep saved bytes") } },
    )
}
