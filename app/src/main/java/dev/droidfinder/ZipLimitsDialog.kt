package dev.droidfinder

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal class ZipLimitsStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("zip-limits", Context.MODE_PRIVATE)

    fun read(): ZipLimits = runCatching {
        ZipLimits(
                prefs.getInt("entries", 10000),
                prefs.getLong("total", 2L shl 30),
                prefs.getLong("member", 512L shl 20),
                prefs.getInt("ratio", 1000),
                prefs.getLong("preview", 128L shl 20),
            )
            .validated()
    }
        .getOrDefault(ZipLimits())

    fun write(value: ZipLimits) {
        value.validated()
        prefs
            .edit()
            .putInt("entries", value.entries)
            .putLong("total", value.expandedBytes)
            .putLong("member", value.memberBytes)
            .putInt("ratio", value.ratio)
            .putLong("preview", value.previewBytes)
            .apply()
    }
}

@Composable
internal fun ZipLimitsDialog(current: ZipLimits, dismiss: () -> Unit, apply: (ZipLimits) -> Unit) {
    var entries by remember { mutableStateOf(current.entries.toString()) }
    var total by remember { mutableStateOf((current.expandedBytes shr 20).toString()) }
    var member by remember { mutableStateOf((current.memberBytes shr 20).toString()) }
    var ratio by remember { mutableStateOf(current.ratio.toString()) }
    var preview by remember { mutableStateOf((current.previewBytes shr 20).toString()) }
    fun use(value: ZipLimits) {
        entries = value.entries.toString()
        total = (value.expandedBytes shr 20).toString()
        member = (value.memberBytes shr 20).toString()
        ratio = value.ratio.toString()
        preview = (value.previewBytes shr 20).toString()
    }
    val valid = runCatching {
        val t = total.toLong()
        val m = member.toLong()
        val p = preview.toLong()
        require(t in 1..8192 && m in 1..t && p in 1..256)
        ZipLimits(entries.toInt(), t shl 20, m shl 20, ratio.toInt(), p shl 20).validated()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("ZIP limits") },
        text = {
            Column(
                Modifier.widthIn(min = 320.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row {
                    TextButton(onClick = { use(ZipLimits()) }) { Text("Standard") }
                    TextButton(
                        onClick = {
                            use(ZipLimits(5000, 512L shl 20, 128L shl 20, 500, 32L shl 20))
                        },
                    ) {
                        Text("Light")
                    }
                    TextButton(
                        onClick = {
                            use(ZipLimits(50000, 8L shl 30, 8L shl 30, 10000, 256L shl 20))
                        },
                    ) {
                        Text("Larger")
                    }
                }
                listOf(
                        "Entry count" to (entries to { value: String -> entries = value }),
                        "Expanded data (MiB)" to (total to { value: String -> total = value }),
                        "Per file (MiB)" to (member to { value: String -> member = value }),
                        "Expansion ratio" to (ratio to { value: String -> ratio = value }),
                        "Peek cache per file (MiB)" to
                            (preview to { value: String -> preview = value }),
                    )
                    .forEach { (label, field) ->
                        OutlinedTextField(
                            field.first,
                            { value ->
                                if (value.length <= 8 && value.all(Char::isDigit))
                                    field.second(value)
                            },
                            label = { Text(label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            keyboardOptions =
                                androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType =
                                        androidx.compose.ui.text.input.KeyboardType.Number,
                                ),
                        )
                    }
                Text(
                    "100–50,000 entries · up to 8 GiB expanded · Peek up to 256 MiB. Ratio checks apply after 1 MiB per file. Larger limits can take more time and storage.",
                    Modifier.padding(top = 12.dp),
                    color = Muted,
                    fontSize = 11.sp,
                )
                if (valid.isFailure)
                    Text(
                        "Enter valid limits; per-file size must fit within the expanded-data limit.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                    )
            }
        },
        confirmButton = {
            TextButton(onClick = { apply(valid.getOrThrow()) }, enabled = valid.isSuccess) {
                Text("Apply limits")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
