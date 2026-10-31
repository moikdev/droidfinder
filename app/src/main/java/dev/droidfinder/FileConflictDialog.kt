package dev.droidfinder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FileConflictDialog(model: FinderModel, conflict: FileConflict) {
    var applyAll by remember(conflict) { mutableStateOf(false) }
    var rename by remember(conflict) { mutableStateOf(false) }
    var name by remember(conflict) { mutableStateOf(conflict.source.name) }
    val valid = runCatching { validateFilename(name) }.isSuccess && name != conflict.existing.name
    fun answer(action: ConflictAction) {
        model.resolveConflict(
            ConflictDecision(action, if (action == ConflictAction.RENAME) name else null, applyAll),
        )
    }
    AlertDialog(
        onDismissRequest = model::cancelTransfer,
        modifier = Modifier.testTag("file-conflict"),
        title = { Text("An item named “${conflict.source.name}” already exists") },
        text = {
            Column(
                Modifier.widthIn(min = 330.dp)
                    .heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Choose how to handle this conflict. Replaced originals are kept in DroidFinder Trash.",
                    fontSize = 12.sp,
                )
                ConflictDetails("Incoming", conflict.source, model::displayPath)
                ConflictDetails("Existing", conflict.existing, model::displayPath)
                if (rename)
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("New name") },
                        singleLine = true,
                        isError = !valid,
                        modifier = Modifier.fillMaxWidth(),
                    )
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            applyAll,
                            enabled = !rename,
                            role = Role.Checkbox,
                            onValueChange = { applyAll = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(applyAll, null, enabled = !rename)
                    Text(
                        "Apply this choice to remaining conflicts",
                        Modifier.padding(start = 8.dp),
                        fontSize = 12.sp,
                    )
                }
                TextButton(
                    onClick = { answer(ConflictAction.KEEP_BOTH) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Keep both")
                }
                TextButton(
                    onClick = { answer(ConflictAction.SKIP) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Skip this item")
                }
                TextButton(
                    onClick = {
                        if (rename) answer(ConflictAction.RENAME)
                        else {
                            rename = true
                            applyAll = false
                        }
                    },
                    enabled = !rename || valid,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (rename) "Use new name" else "Rename incoming…")
                }
                TextButton(
                    onClick = { answer(ConflictAction.REPLACE) },
                    enabled = conflict.existing.canDelete,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Replace with recovery")
                }
            }
        },
        confirmButton = { TextButton(onClick = model::cancelTransfer) { Text("Cancel transfer") } },
    )
}

@Composable
private fun ConflictDetails(label: String, entry: Entry, displayPath: (String) -> String) {
    Text(label, Modifier.padding(top = 14.dp), fontSize = 13.sp)
    Text(
        "${entry.kind} · ${if(entry.folder) "Folder contents" else formatSize(entry.size)} · ${formatDate(entry.modified)}",
        fontSize = 11.sp,
        color = Muted,
    )
    Text(displayPath(entry.path), Modifier.padding(bottom = 8.dp), fontSize = 11.sp, color = Muted)
}
