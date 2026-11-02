package dev.findroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommandExplanationDialog(value: CommandExplanation, recover: (String) -> Unit, dismiss: () -> Unit) {
    val height = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height.toDp() - 48.dp).coerceAtLeast(240.dp) }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth(.94f).heightIn(max = height).testTag("command-explanation"), shape = MaterialTheme.shapes.extraLarge) {
            Column {
                Text("${value.title} is unavailable", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(value.problem.reason, fontSize = 15.sp)
                    Text(value.scope, color = Muted, fontSize = 12.sp)
                }
                HorizontalDivider()
                FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    value.problem.recovery?.let { recovery -> Button({ recover(recovery.command) }, Modifier.testTag("command-recovery")) { Text(recovery.label) } }
                    TextButton(dismiss) { Text("Back to files") }
                }
            }
        }
    }
}
