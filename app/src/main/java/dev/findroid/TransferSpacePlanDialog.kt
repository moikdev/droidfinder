package dev.findroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private data class TransferSpaceDisplay(
    val stamp: Pair<TransferSpaceRequest, Int>,
    val loading: Boolean = true,
    val inspection: TransferSpaceInspection? = null,
    val error: String? = null,
)

@Composable
internal fun TransferSpacePlanDialog(model: FinderModel, request: TransferSpaceRequest) {
    var choosing by remember(request) { mutableStateOf(false) }
    var revision by remember(request) { mutableIntStateOf(0) }
    val control = remember(request, revision) { TransferControl() }
    DisposableEffect(control) { onDispose { control.cancel() } }
    val stamp = request to revision
    val produced by produceState(TransferSpaceDisplay(stamp), request, revision) {
        value = TransferSpaceDisplay(stamp)
        value = try {
            withContext(Dispatchers.IO) {
                check(model.busy == null) { "Wait for the current operation before inspecting space." }
                check(model.allowedPath(request.destination) && request.paths.all(model::allowedPath)) {
                    "Reconnect storage before reviewing this transfer."
                }
                TransferSpaceDisplay(stamp, loading = false, inspection = model.documents.inspectTransferSpace(request, control))
            }
        } catch (error: Exception) {
            coroutineContext.ensureActive()
            TransferSpaceDisplay(stamp, loading = false, error = if (control.cancelled) "Inspection stopped. No transfer was started."
                else error.message ?: "Couldn't inspect transfer space. Reconnect storage or try another destination.")
        }
    }
    // produceState retains its last value until the replacement producer starts. A changed
    // destination or refresh must immediately hide that earlier budget and disable confirmation.
    val state = produced.takeIf { it.stamp == stamp } ?: TransferSpaceDisplay(stamp)
    if (choosing) {
        DestinationDialog(model, request.move, titleOverride = "Choose another transfer destination",
            confirmLabel = "Review this destination", initialFolder = request.destination,
            onDestination = { model.changeTransferSpaceDestination(request, it) }, dismiss = { choosing = false; revision++ })
        return
    }
    val density = LocalDensity.current
    Dialog(model::dismissTransferSpace, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
                Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("transfer-space-review"),
                    color = CanvasBackground, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag("transfer-space-list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Review transfer space", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("${if (request.move) "Move" else "Copy"} · Selected items: ${request.paths.size} · ${request.policy.title}", color = Ink, fontSize = 14.sp)
                        SelectionContainer { Text("Destination\n${model.displayPath(request.destination)}\n${request.destination}", color = Ink, fontSize = 13.sp) }
                        if (state.loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Reading file sizes, matching destination names and reported free space…", color = Muted, fontSize = 14.sp)
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                        state.inspection?.let { inspection ->
                            val plan = inspection.plan
                            Text("Files: ${plan.contents.files} · Folders: ${plan.contents.folders} · ${if (plan.contents.complete) "Content bytes" else "Known content, at least"}: ${formatSize(plan.contents.knownBytes)}",
                                color = Ink, fontSize = 14.sp)
                            for ((index, budget) in plan.budgets.withIndex()) {
                                HorizontalDivider()
                                Text(if (plan.budgets.size == 1) "Storage budget" else "Storage budget ${index + 1}", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                for (requirement in budget.requirements) {
                                    Text("${requirement.title}: ${if (!requirement.complete) "at least " else ""}${formatSize(requirement.knownBytes)}", color = Ink, fontSize = 14.sp)
                                    SelectionContainer { Text(requirement.location.path, color = Muted, fontSize = 12.sp) }
                                }
                                if (budget.requirements.size > 1) Text("Combined demand on the same observed storage volume: ${formatSize(budget.knownBytes)}", color = Ink, fontSize = 14.sp)
                                Text("Reported free space: ${budget.availableBytes?.let(::formatSize) ?: "Not reported by this location"}", color = Ink, fontSize = 14.sp)
                                if (budget.overflow) Text("The known demand exceeds the supported byte range. An exact shortfall cannot be shown.", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                                else if (budget.assessment == TransferSpaceAssessment.INSUFFICIENT)
                                    Text("Shortfall for this allowance: ${formatSize(budget.shortfallBytes!!)}", color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
                                else budget.remainingAfterKnownContent?.let { Text("Left after known contents: ${formatSize(it)} before overhead", color = Muted, fontSize = 14.sp) }
                                Text(budget.assessment.explanation, color = Muted, fontSize = 14.sp)
                            }
                            HorizontalDivider()
                            Text("Working copies and recovery", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(plan.stagingExplanation, color = Muted, fontSize = 14.sp)
                            Text(inspection.recoveryExplanation, color = Muted, fontSize = 14.sp)
                            plan.cautions.forEach { Text(it, color = Muted, fontSize = 13.sp) }
                            if (plan.knownShortfall) Text("Choose another destination or free space, then refresh the review before starting.", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.loading) TextButton(onClick = control::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop inspection") }
                            else TextButton(onClick = { revision++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh space estimate") }
                            TextButton(onClick = { control.cancel(); choosing = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose another destination…") }
                            TextButton(onClick = model::dismissTransferSpace, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                            Button(onClick = { model.confirmTransferSpace(request) }, enabled = !state.loading && state.inspection?.plan?.knownShortfall == false && model.busy == null,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("start-reviewed-transfer")) {
                                Text(if (request.move) "Move to destination" else "Copy to destination")
                            }
                        }
                    }
                }
            }
        }
    }
}
