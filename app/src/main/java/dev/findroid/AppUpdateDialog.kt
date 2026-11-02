package dev.findroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun AppUpdateDialog(updates: AppUpdateController, install: () -> Unit) {
    val state = updates.state
    val update = state.update
    AlertDialog(
        modifier = Modifier.testTag("app-updates"),
        onDismissRequest = updates::dismiss,
        title = { Text("App updates") },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Installed: ${BuildConfig.VERSION_NAME}")
                when {
                    state.checking -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Checking for updates…")
                    }
                    state.downloading -> {
                        LinearProgressIndicator(
                            progress = { (state.downloaded.toFloat() / (update?.bytes ?: 1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Downloading ${formatSize(state.downloaded)} of ${formatSize(update?.bytes ?: 0)}")
                    }
                    state.preparingInstall -> Text("Verifying the update…")
                    state.ready -> Text("${update?.versionName} is downloaded and ready to install.")
                    update != null -> Text("${update.versionName} is available · ${formatSize(update.bytes)}")
                    state.checked && state.error == null -> Text("You're up to date.")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (update?.notes?.isNotBlank() == true) Text(update.notes)
                if (state.ready)
                    Text("Android will ask you to confirm the installation. Your files and settings stay in place.")
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Check automatically", Modifier.weight(1f))
                    Switch(updates.automatic, updates::changeAutomatic,
                        Modifier.semantics { contentDescription = "Check automatically" })
                }
                Text("Checks once a day. Downloads start when you choose them. Only release information is requested; your files are never uploaded.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            when {
                state.downloading -> TextButton(updates::cancelDownload) { Text("Cancel download") }
                state.ready -> Button(install, enabled = !state.preparingInstall) { Text("Install update") }
                update != null -> Button(updates::download, enabled = !state.checking) { Text("Download update") }
                else -> TextButton({ updates.check() }, enabled = !state.checking) { Text("Check again") }
            }
        },
        dismissButton = { TextButton(updates::dismiss) { Text(if (state.downloading) "Hide" else "Close") } },
    )
}
