package dev.findroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal data class VerifiedCopyRequest(
    val paths: List<String>,
    val destination: String,
    val policy: ConflictPolicy,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerifiedCopyReviewDialog(model: FinderModel, request: VerifiedCopyRequest) {
    Dialog(onDismissRequest = model::dismissVerifiedCopy, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            val bodyLimit = (maxHeight - 220.dp).coerceAtLeast(60.dp)
            Surface(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("verified-copy-review"),
                color = CanvasBackground, shape = RoundedCornerShape(14.dp),
                border = BorderStroke(.5.dp, Border), shadowElevation = 16.dp,
            ) {
                Column {
                    Text("Review verified copy", Modifier.padding(20.dp), fontWeight = FontWeight.Medium, color = Ink)
                    HorizontalDivider(color = Border)
                    Column(Modifier.fillMaxWidth().heightIn(max = bodyLimit)
                        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${request.paths.size} selected ${if (request.paths.size == 1) "file or folder" else "files or folders"}. Each file inside a folder is checked.", color = Ink)
                        Text("From", fontWeight = FontWeight.Medium, color = Ink)
                        request.paths.take(20).forEach { Text(model.displayPath(it), color = Muted) }
                        if (request.paths.size > 20) Text("${request.paths.size - 20} more selected items", color = Muted)
                        Text("To", fontWeight = FontWeight.Medium, color = Ink)
                        Text(model.displayPath(request.destination), color = Muted)
                        Text("Before making a copy available, Findroid reads it back and compares its SHA-256 and byte count with the bytes read from the source. This takes extra time and reads every destination file once.", color = Ink)
                        Text("If a copy cannot be read or does not match, its unfinished contents are discarded when possible. The source is kept. Existing source-change and file-safety checks still apply.", color = Muted)
                        Text("Results are saved in Transfers. Verification supports up to 10,000 file results per transfer; split larger selections into smaller copies.", color = Muted)
                    }
                    HorizontalDivider(color = Border)
                    FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = model::dismissVerifiedCopy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                        Button(onClick = model::confirmVerifiedCopy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy and verify") }
                    }
                }
            }
        }
    }
}
