package dev.findroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FolderComparisonDialog(left: FinderModel, right: FinderModel, dismiss: () -> Unit) {
    val leftPath = remember { left.folderPath }
    val rightPath = remember { right.folderPath }
    var verify by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var token by remember { mutableStateOf<TransferControl?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<FolderComparison?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<ComparisonRow?>(null) }
    DisposableEffect(Unit) { onDispose { token?.cancel() } }
    LaunchedEffect(verify, generation) {
        token?.cancel()
        val control = TransferControl()
        token = control
        scanning = true
        result = null
        selected = null
        error = null
        try {
            result =
                withContext(Dispatchers.IO) {
                    check(leftPath != null && rightPath != null) { "Open a folder in each pane." }
                    check(left.allowedPath(leftPath) && right.allowedPath(rightPath)) {
                        "Reconnect the folders before comparing."
                    }
                    fun node(entry: Entry) =
                        ComparisonNode(
                            entry.path,
                            entry.name,
                            entry.folder,
                            entry.size.takeIf { entry.sizeKnown },
                            entry.modified,
                            entry.documentUri == null && Files.isSymbolicLink(entry.file.toPath()),
                        )
                    compareFolders(
                        leftPath,
                        rightPath,
                        read = { node(left.documents.read(it)) },
                        list = {
                            left.documents
                                .list(it)
                                .asSequence()
                                .filterNot { isWorkingFileName(it.name) }
                                .map(::node)
                                .asIterable()
                        },
                        fingerprint = { path, c -> left.documents.digest(path, c) },
                        verifyContents = verify,
                        control = control,
                    )
                }
        } catch (failure: Exception) {
            if (failure is CancellationException && !control.cancelled) throw failure
            error =
                if (control.cancelled) "Comparison stopped."
                else failure.message ?: "Couldn't compare these folders."
        } finally {
            if (token === control) scanning = false
        }
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            val limit = (maxHeight - 220.dp).coerceIn(100.dp, 420.dp)
            Surface(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().testTag("folder-comparison"),
                shape = RoundedCornerShape(14.dp),
                color = CanvasBackground,
                shadowElevation = 16.dp,
            ) {
                Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Compare folders",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Ink,
                            )
                            Text(
                                "${leftPath?.let(left::title) ?: "Left"}  ↔  ${rightPath?.let(right::title) ?: "Right"}",
                                color = Muted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ToolButton(Icons.Outlined.Close, "Close comparison", dismiss)
                    }
                    HorizontalDivider(color = Border)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Verify file contents",
                            Modifier.weight(1f),
                            fontSize = 12.sp,
                            color = Ink,
                        )
                        Switch(
                            checked = verify,
                            onCheckedChange = { verify = it },
                            modifier = Modifier.testTag("verify-comparison"),
                        )
                    }
                    Text(
                        if (verify)
                            "Reads file contents. Includes hidden files; links are not followed."
                        else "Metadata comparison. Matching sizes still need content verification.",
                        Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                        fontSize = 11.sp,
                        color = Muted,
                    )
                    if (scanning)
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Blue)
                    if (error != null)
                        Text(error!!, Modifier.padding(20.dp), fontSize = 12.sp, color = Muted)
                    if (result != null) {
                        val rows = result!!.rows
                        Text(
                            "${rows.size} items · ${rows.count { it.state==ComparisonState.IDENTICAL }} verified identical${if(result!!.incomplete) " · incomplete comparison" else ""}",
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            fontSize = 11.sp,
                            color = Muted,
                        )
                        if (rows.isEmpty())
                            Text(
                                if (result!!.incomplete) "The comparison limit was reached."
                                else "Both folders are empty.",
                                Modifier.padding(20.dp),
                                color = Muted,
                            )
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = limit)) {
                            items(rows, key = { it.relative + ":" + it.state.name }) { row ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            if (selected == row) SelectionSurface
                                            else CanvasBackground,
                                        )
                                        .clickable { selected = row }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            row.relative,
                                            Modifier.weight(1f).padding(end = 12.dp),
                                            fontSize = 12.sp,
                                            color = Ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            row.state.title,
                                            fontSize = 11.sp,
                                            color =
                                                if (row.state == ComparisonState.IDENTICAL) Blue
                                                else Muted,
                                        )
                                    }
                                    row.detail?.let {
                                        Text(
                                            it,
                                            Modifier.padding(top = 4.dp),
                                            fontSize = 11.sp,
                                            color = Muted,
                                        )
                                    }
                                }
                            }
                        }
                        if (
                            selected != null &&
                                '/' !in selected!!.relative &&
                                selected!!.relative != "."
                        ) {
                            val row = selected!!
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                TextButton(
                                    onClick = {
                                        left.transferPaths(
                                            listOf(row.left!!.path),
                                            rightPath!!,
                                            false,
                                        )
                                        dismiss()
                                    },
                                    enabled =
                                        row.left != null && rightPath != null && right.canCreate,
                                ) {
                                    Text("Copy left → right", fontSize = 12.sp)
                                }
                                TextButton(
                                    onClick = {
                                        right.transferPaths(
                                            listOf(row.right!!.path),
                                            leftPath!!,
                                            false,
                                        )
                                        dismiss()
                                    },
                                    enabled =
                                        row.right != null && leftPath != null && left.canCreate,
                                ) {
                                    Text("Copy right → left", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = {
                            left.primaryPane.reviewFolderSynchronization(leftPath!!, rightPath!!)
                            dismiss()
                        }, enabled = !scanning && leftPath != null && rightPath != null && left.busy == null,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("review-folder-sync")) { Text("Review synchronization…") }
                        TextButton(onClick = { left.primaryPane.showFolderSyncRecords(); dismiss() },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Synchronization records") }
                        if (scanning) TextButton(onClick = { token?.cancel() }) { Text("Stop") }
                        else TextButton(onClick = { generation++ }) { Text("Compare again") }
                    }
                }
            }
        }
    }
}
