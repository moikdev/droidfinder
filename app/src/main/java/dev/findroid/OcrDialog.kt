package dev.findroid

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.*

/** An explicit single-page/image operation. No recognition/download starts on preview or dialog open. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun OcrDialog(
    title: String,
    raster: suspend () -> Bitmap,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { OcrOperations.models(context) }
    var languageId by rememberSaveable { mutableStateOf("eng") }
    val language = OcrPolicy.language(languageId)
    var refresh by remember { mutableIntStateOf(0) }
    var model by remember(languageId) { mutableStateOf<OcrModelState?>(null) }
    var storageError by remember(languageId) { mutableStateOf<String?>(null) }
    var partials by remember { mutableStateOf<List<OcrStoredFile>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var control by remember { mutableStateOf<OcrReadControl?>(null) }
    var result by remember { mutableStateOf<OcrText?>(null) }
    var resultLanguage by remember { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var section by remember { mutableIntStateOf(0) }
    var activeMatch by remember { mutableIntStateOf(-1) }
    var confirmModel by remember { mutableStateOf<OcrModelState?>(null) }
    var confirmPartials by remember { mutableStateOf<List<OcrStoredFile>?>(null) }
    val scroll = rememberScrollState()
    val bringText = remember { BringIntoViewRequester() }
    var textLayout by remember(result, section) { mutableStateOf<TextLayoutResult?>(null) }
    DisposableEffect(Unit) { onDispose { control?.cancel(); job?.cancel() } }
    LaunchedEffect(languageId, refresh) {
        model = null
        storageError = null
        if (BuildConfig.DEBUG) android.util.Log.d("FindroidOCR", "Language review started")
        try {
            val current = withContext(Dispatchers.IO) { store.state(language) to store.incomplete() }
            withContext(Dispatchers.Main.immediate) {
                model = current.first
                partials = current.second
            }
            if (BuildConfig.DEBUG) android.util.Log.d("FindroidOCR", "Language review complete: verified=${current.first.verified}")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            withContext(Dispatchers.Main.immediate) {
                storageError = "Couldn't review OCR storage. Retry the review before recognizing text."
            }
            if (BuildConfig.DEBUG) android.util.Log.d("FindroidOCR", "Language review failed: ${error.javaClass.simpleName}")
        }
    }
    fun action(label: String, work: suspend (OcrReadControl) -> Unit) {
        if (busy != null || job?.isActive == true) return
        busy = label
        val read = OcrReadControl()
        control = read
        job = scope.launch {
            if (!OcrOperations.gate.tryLock()) { status = "Another OCR task is active. Finish or stop it first."; busy = null; control = null; read.cancel(); return@launch }
            busy = label
            status = null
            try { work(read) }
            catch (error: CancellationException) { status = "Stopped. Start again when ready."; throw error }
            catch (error: Exception) { status = error.message?.take(240) ?: "This OCR operation couldn't finish." }
            catch (_: OutOfMemoryError) { status = "Not enough memory. Try a smaller image." }
            finally { read.cancel(); OcrOperations.gate.unlock(); busy = null; control = null; refresh++ }
        }
    }
    fun importLanguage(uri: Uri) = action("Importing language…") { read ->
        withContext(Dispatchers.IO) {
            val signal = CancellationSignal()
            read.track { signal.cancel() }.use {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r", signal)
                    ?: error("Couldn't open this language file.")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    read.track { input.close() }.use { store.install(language, input, read::check) }
                }
            }
        }
        status = "${language.label} is ready for offline recognition."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importLanguage(uri) }
    val text = result?.text.orEmpty()
    val windows = remember(text) { OcrPolicy.windows(text) }
    val matches by produceState<List<PdfMatch>>(emptyList(), text, query) {
        value = emptyList()
        if (query.isNotBlank()) {
            delay(150)
            value = withContext(Dispatchers.Default) { pdfMatches(0, text, query, 2000) }
        }
    }
    LaunchedEffect(matches) { activeMatch = if (matches.isEmpty()) -1 else 0 }
    LaunchedEffect(activeMatch, matches) {
        matches.getOrNull(activeMatch)?.let { match ->
            section = windows.indexOfFirst { match.start in it }.coerceAtLeast(0)
        }
    }
    LaunchedEffect(section, activeMatch, textLayout) {
        val layout = textLayout ?: return@LaunchedEffect
        val window = windows.getOrNull(section) ?: return@LaunchedEffect
        val match = matches.getOrNull(activeMatch)
        val bounds = if (match != null && match.start in window) layout.getBoundingBox(match.start - window.first) else Rect(0f, 0f, 1f, 1f)
        bringText.bringIntoView(bounds)
    }
    Dialog(onDismissRequest = { control?.cancel(); job?.cancel(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 840.dp).fillMaxWidth(.94f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).testTag("ocr-dialog")) {
                Text("Recognize text", style = MaterialTheme.typography.titleLarge)
                Text(title, Modifier.padding(top = 4.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Processed on this tablet. Your document stays unchanged. Review recognized text for mistakes.")
                    if (showOptions || result == null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OcrPolicy.languages.forEach { choice ->
                                FilterChip(selected = languageId == choice.id, onClick = { languageId = choice.id },
                                    enabled = busy == null, label = { Text(choice.label) }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                        val current = model
                        Text(when {
                            storageError != null -> storageError!!
                            current == null -> "Reviewing language storage…"
                            current.verified -> "${language.label} · ready offline · ${formatSize(language.bytes)}"
                            current.stored != null -> "The stored language file has changed. Review and remove it before importing another model."
                            else -> "${language.label} needs a ${formatSize(language.bytes)} language model. Download it from GitHub or import the same verified file."
                        }, style = MaterialTheme.typography.bodyMedium)
                        if (storageError != null) TextButton(onClick = { refresh++ }, enabled = busy == null,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry language review") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (current != null && current.stored == null) {
                                TextButton(onClick = {
                                    action("Downloading ${language.label}…") { read ->
                                        withContext(Dispatchers.IO) {
                                            downloadOcrModel(store, language, read) { count ->
                                                scope.launch { if (control === read) busy = "Downloading ${language.label} · ${count * 100 / language.bytes}%" }
                                            }
                                        }
                                        status = "${language.label} is ready for offline recognition."
                                    }
                                }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Download language (${formatSize(language.bytes)})") }
                                TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = busy == null,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Import downloaded language…") }
                            }
                            if (current?.stored != null) TextButton(onClick = { confirmModel = current }, enabled = busy == null,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove language…") }
                            if (partials.isNotEmpty()) TextButton(onClick = { confirmPartials = partials }, enabled = busy == null,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Review incomplete downloads (${partials.size})…") }
                        }
                        if (busy != null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(busy!!)
                            TextButton(onClick = { control?.cancel(); job?.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop OCR task") }
                        } else Button(onClick = {
                            action("Recognizing text on this tablet…") { read ->
                                result = null
                                var temporary: File? = null
                                try {
                                    val image = raster()
                                    try {
                                        withContext(Dispatchers.IO) {
                                            read.check()
                                            val directory = File(context.cacheDir, "ocr-rasters").apply { check(mkdirs() || isDirectory) }
                                            val file = File.createTempFile("page-", ".png", directory)
                                            temporary = file
                                            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Couldn't prepare the OCR raster." } }
                                            read.check()
                                        }
                                    } finally { image.recycle() }
                                    result = recognizeOcrRaster(context, language, requireNotNull(temporary))
                                    resultLanguage = language.label
                                    if (result!!.text.isNotBlank()) showOptions = false
                                    query = ""; section = 0
                                    status = if (result!!.text.isBlank()) "No text was recognized. Try a clearer image or another language." else "Recognition finished."
                                } finally { withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() } }
                            }
                        }, enabled = current?.verified == true, modifier = Modifier.heightIn(min = 48.dp)) { Text("Recognize this image or page") }
                    } else TextButton(onClick = { showOptions = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Language and recognition options…") }
                    status?.let { Text(it, Modifier.testTag("ocr-status")) }
                    result?.let { recognized ->
                        HorizontalDivider()
                        Text(if (recognized.limited) "Only the first 200,000 characters are available." else "Recognized text · review before copying", style = MaterialTheme.typography.titleMedium)
                        Text("${resultLanguage.orEmpty()} · average confidence ${recognized.confidence}%. Recognition can miss or misread text.", style = MaterialTheme.typography.bodySmall)
                        if (recognized.text.isNotBlank()) {
                            TextButton(onClick = {
                                scope.launch {
                                    try { copyPreviewText(context, title, recognized.text); status = "Copied recognized text" }
                                    catch (error: Exception) { if (error is CancellationException) throw error; status = "Couldn't copy recognized text." }
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy recognized text") }
                            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Find in recognized text") },
                                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ocr-search"))
                            if (query.isNotBlank()) {
                                Text(if (matches.isEmpty()) "No matches in recognized text" else "${activeMatch + 1} of ${matches.size}${if(matches.size==2000) "+" else ""} matches")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { activeMatch = Math.floorMod(activeMatch - 1, matches.size) }, enabled = matches.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous OCR match") }
                                    TextButton(onClick = { activeMatch = Math.floorMod(activeMatch + 1, matches.size) }, enabled = matches.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Next OCR match") }
                                }
                            }
                            if (windows.isNotEmpty()) {
                                val sectionIndex = section.coerceIn(windows.indices)
                                val window = windows[sectionIndex]
                                Text("Text section ${sectionIndex + 1} of ${windows.size}", style = MaterialTheme.typography.bodySmall)
                                if (windows.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { section-- }, enabled = section > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous text section") }
                                    TextButton(onClick = { section++ }, enabled = section < windows.lastIndex, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next text section") }
                                }
                                val normal = MaterialTheme.colorScheme.secondaryContainer
                                val active = MaterialTheme.colorScheme.primaryContainer
                                val ink = MaterialTheme.colorScheme.onSurface
                                SelectionContainer(Modifier.testTag("ocr-recognized-text")) {
                                    Text(buildAnnotatedString {
                                        append(text.substring(window))
                                        matches.forEachIndexed { index, match ->
                                            val start = maxOf(match.start, window.first) - window.first
                                            val end = minOf(match.end, window.last + 1) - window.first
                                            if (start < end) addStyle(SpanStyle(background = if(index == activeMatch) active else normal, color = ink), start, end)
                                        }
                                    }, style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.bringIntoViewRequester(bringText).testTag("ocr-text-content"), onTextLayout = { textLayout = it })
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { control?.cancel(); job?.cancel(); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close OCR") }
            }
        }
    }
    confirmModel?.let { reviewed ->
        AlertDialog(onDismissRequest = { confirmModel = null }, title = { Text("Remove ${reviewed.language.label} language?") },
            text = { Text("This removes the reviewed ${formatSize(reviewed.stored!!.bytes)} language file. Your documents and recognized text stay unchanged. You can import or download the language again.") },
            confirmButton = { TextButton(onClick = { confirmModel = null; action("Removing language…") { withContext(Dispatchers.IO) { store.removeReviewedModel(reviewed) }; status = "Language removed." } }) { Text("Remove language") } },
            dismissButton = { TextButton(onClick = { confirmModel = null }) { Text("Keep language") } })
    }
    confirmPartials?.let { reviewed ->
        AlertDialog(onDismissRequest = { confirmPartials = null }, title = { Text("Discard incomplete language downloads?") },
            text = { Text("${reviewed.size} incomplete files use ${formatSize(reviewed.sumOf { it.bytes })}. This removes only these reviewed incomplete language downloads. Installed languages and documents stay in place.") },
            confirmButton = { TextButton(onClick = { confirmPartials = null; action("Discarding incomplete downloads…") { withContext(Dispatchers.IO) { store.discardReviewedIncomplete(reviewed) }; status = "Reviewed incomplete downloads discarded." } }) { Text("Discard incomplete downloads") } },
            dismissButton = { TextButton(onClick = { confirmPartials = null }) { Text("Keep incomplete downloads") } })
    }
}
