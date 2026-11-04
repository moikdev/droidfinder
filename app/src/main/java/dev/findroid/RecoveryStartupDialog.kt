package dev.findroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Acknowledgment saves the exact old marker before dismissing it; every control scrolls with text. */
@Composable
internal fun RecoveryStartupDialog(model: FinderModel, close: () -> Unit) {
    val owner = model.primaryPane
    val notice = owner.interruptedTransfer?.let(RecoveryNotice::fromText) ?: return
    fun acknowledge(next: () -> Unit = {}) = owner.dismissRecovery { close(); next() }
    val density = LocalDensity.current
    Dialog({ acknowledge() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("recovery-startup"),
                color = CanvasBackground, shape = RoundedCornerShape(14.dp), border = BorderStroke(.5.dp, Border)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("recovery-startup-list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("A previous operation needs review", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(notice.label ?: "The previous operation did not record its completion.", color = Ink, fontSize = 15.sp)
                    Text("Completed files were kept. Hidden .findroid-* working data may remain in the destination. Review the files before retrying. Acknowledging this notice keeps an exact copy in Recovery inbox.", color = Muted, fontSize = 14.sp)
                    notice.destination?.let { path -> SelectionContainer { Text("Recorded destination\n$path", color = Ink, fontSize = 13.sp) } }
                    owner.recoveryMarkerProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                    if (owner.recoveryAcknowledging) Text("Saving the notice…", color = Muted, fontSize = 14.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        notice.destination?.let { path ->
                            TextButton(onClick = { acknowledge {
                                if (owner.allowedPath(path)) { owner.activatePane(); owner.navigate(path) }
                                else { owner.showRecoveryInbox(); owner.recoveryInboxNotice("Reconnect the recorded destination in storage access before opening it.") }
                            } }, enabled = !owner.recoveryAcknowledging, modifier = Modifier.heightIn(min = 48.dp)) { Text("Review files") }
                        }
                        TextButton(onClick = { acknowledge(owner::showRecoveryInbox) }, enabled = !owner.recoveryAcknowledging,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Open recovery inbox") }
                        TextButton(onClick = { acknowledge() }, enabled = !owner.recoveryAcknowledging,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("recovery-later")) { Text("Later") }
                        if (owner.recoveryMarkerProblem != null) TextButton(onClick = { close(); owner.showRecoveryInbox() },
                            enabled = !owner.recoveryAcknowledging, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Review without acknowledging")
                        }
                    }
                }
            }
        }
        }
    }
}
