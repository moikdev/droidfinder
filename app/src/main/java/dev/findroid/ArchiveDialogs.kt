package dev.findroid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CompressDialog(
    model: FinderModel,
    onDismiss: () -> Unit,
    onCompress: (String, Int, Boolean) -> Unit,
) {
    val selected = remember { model.actionEntries }
    var name by remember {
        mutableStateOf(
            if (selected.size == 1)
                (if (selected.first().folder) selected.first().name
                else selected.first().name.substringBeforeLast('.', selected.first().name)) + ".zip"
            else "Archive.zip",
        )
    }
    var compression by remember { mutableStateOf(ZipCompression.BALANCED) }
    val valid = runCatching { zipFilename(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress to ZIP") },
        text = {
            Column(Modifier.widthIn(min = 340.dp)) {
                Text(
                    "${selected.size} item${if (selected.size == 1) "" else "s"} · originals are kept",
                    fontSize = 12.sp,
                    color = Muted,
                )
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Archive name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    isError = valid.isFailure,
                )
                if (valid.isFailure)
                    Text(
                        valid.exceptionOrNull()?.message.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                    )
                Text("Compression", Modifier.padding(top = 16.dp, bottom = 5.dp), fontSize = 12.sp)
                ZipCompression.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { compression = option }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(compression == option, { compression = option })
                        Text(option.title, fontSize = 12.sp)
                    }
                }
                Text(
                    "Destination: ${if (model.canCreate) model.title() else "Choose a writable folder"}",
                    Modifier.padding(top = 8.dp),
                    fontSize = 11.sp,
                    color = Muted,
                )
                TextButton(
                    onClick = { onCompress(valid.getOrThrow(), compression.level, true) },
                    enabled = valid.isSuccess,
                ) {
                    Text("Choose destination…")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCompress(valid.getOrThrow(), compression.level, !model.canCreate) },
                enabled = valid.isSuccess && selected.isNotEmpty(),
            ) {
                Text(if (model.canCreate) "Compress" else "Choose folder")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
