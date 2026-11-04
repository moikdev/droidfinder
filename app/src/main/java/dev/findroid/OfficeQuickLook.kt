package dev.findroid

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

internal val Entry.officeFormat: OfficeFormat? get() = if(folder) null else officeFormat(extension,mime)

/** A bounded, read-only view of stored Office text, separate from document rendering. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OfficeQuickLook(entry: Entry, modifier: Modifier = Modifier) {
    val context=LocalContext.current
    val gateway=remember { DocumentAccess(context.applicationContext) }
    val scope=rememberCoroutineScope()
    val identity=listOf(entry.path,entry.modified,entry.size,entry.mimeType)
    val format=entry.officeFormat ?: return
    var attempt by remember(identity) { mutableIntStateOf(0) }
    val control=remember(identity,attempt) { ListingControl() }
    var stopped by remember(control) { mutableStateOf(false) }
    var selected by remember(identity) { mutableIntStateOf(0) }
    var query by remember(identity) { mutableStateOf("") }
    var active by remember(identity) { mutableIntStateOf(-1) }
    var status by remember(identity) { mutableStateOf<String?>(null) }
    var copying by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val keys=LocalPeekKeys.current
    var editing by remember { mutableStateOf(false) }
    DisposableEffect(control) { onDispose { control.cancel() } }
    val loaded by produceState<Result<OfficeDocument>?>(null,control) {
        value=null
        try {
            val result=withContext(Dispatchers.IO) {
                val job=currentCoroutineContext().job
                CacheLeases.pin(entry.file).use {
                    val file=gateway.materialize(entry,control,OfficePolicy.maximumFileBytes)
                    readOfficeDocument(file,format) { control.check();job.ensureActive() }
                }
            }
            value=Result.success(result)
        } catch(error: CancellationException) { throw error }
        catch(error: Exception) { if(!stopped)value=Result.failure(error) }
        catch(error: OutOfMemoryError) { value=Result.failure(IllegalStateException("Not enough memory for this Office preview. Open the document in another app.")) }
    }
    val document=loaded?.getOrNull()
    val section=document?.sections?.getOrNull(selected)
    val lines=remember(section) {
        section?.text?.lineSequence()?.flatMap { line ->
            if(line.isEmpty()) sequenceOf("") else sequence {
                var at=0
                while(at<line.length) {
                    val part=OfficePolicy.clip(line.substring(at,minOf(at+4000,line.length)),4000)
                    if(part.isEmpty()) { at++;continue }
                    yield(part);at+=part.length
                }
            }
        }?.toList().orEmpty()
    }
    val shownRows=section?.table?.rows?.map { it.joinToString("\t") } ?: lines
    val matches=remember(shownRows,query) { if(query.isBlank()) emptyList() else shownRows.indices.filter { shownRows[it].contains(query,true) }.take(2000) }
    val list=rememberLazyListState()
    val horizontal=rememberScrollState()
    LaunchedEffect(selected) { active=-1;list.scrollToItem(0);horizontal.scrollTo(0);status=null }
    LaunchedEffect(selected,matches) { active=if(matches.isEmpty()) -1 else 0 }
    LaunchedEffect(matches,active) { matches.getOrNull(active)?.let { row -> list.scrollToItem((row-if(section?.table!=null) 1 else 0).coerceAtLeast(0)) } }
    fun copy() {
        val text=section?.text?.takeIf { it.isNotBlank() } ?: return
        if(copying)return
        scope.launch {
            copying=true
            try { copyPreviewText(context,entry.name,text);status="Copied loaded section" }
            catch(error: CancellationException) { throw error }
            catch(_: Exception) { status="Couldn't copy the loaded text." }
            finally { copying=false }
        }
    }
    DisposableEffect(keys,section,editing,copying) {
        val handler: (KeyEvent)->Boolean = { event ->
            if(event.action==KeyEvent.ACTION_DOWN && !editing && (event.isCtrlPressed||event.isMetaPressed) && event.keyCode==KeyEvent.KEYCODE_C) { copy();true } else false
        }
        keys?.handle=handler;keys?.editing=editing
        onDispose { if(keys!=null && keys.handle===handler) { keys.handle=null;keys.editing=false } }
    }
    val controlsScroll=rememberScrollState()
    LaunchedEffect(identity) { controlsScroll.scrollTo(0) }
    BoxWithConstraints(modifier.testTag("office-preview")) {
        val availableHeight=maxHeight
        val controlsMaxHeight=availableHeight*0.6f
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().heightIn(max=if(section!=null)controlsMaxHeight else availableHeight)
                .verticalScroll(controlsScroll).testTag("office-preview-controls")) {
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(format.label,Modifier.padding(12.dp),style=MaterialTheme.typography.titleMedium)
                    TextButton(onClick={details=true},modifier=Modifier.heightIn(min=48.dp)) { Text("Preview limits…") }
                    if(document!=null) {
                        TextButton(onClick={selected--},enabled=selected>0,modifier=Modifier.heightIn(min=48.dp)) { Text("Previous section") }
                        TextButton(onClick={selected++},enabled=selected<document.sections.lastIndex,modifier=Modifier.heightIn(min=48.dp)) { Text("Next section") }
                        TextButton(onClick=::copy,enabled=section?.text?.isNotBlank()==true&&!copying,modifier=Modifier.heightIn(min=48.dp)) { Text("Copy loaded section") }
                    }
                }
                Text("Read-only text preview; layout and formatting can differ.",Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
                when {
                    stopped -> {
                        Text("Office preview stopped.",Modifier.padding(12.dp))
                        TextButton(onClick={attempt++},modifier=Modifier.heightIn(min=48.dp)) { Text("Retry Office preview") }
                    }
                    loaded==null -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp))
                        TextButton(onClick={stopped=true;control.cancel()},modifier=Modifier.heightIn(min=48.dp)) { Text("Stop Office preview") }
                    }
                    loaded!!.isFailure -> {
                        val error=loaded!!.exceptionOrNull()
                        Text(when(error) {
                            is PreviewSizeLimitException -> "Office text previews support files up to 64 MiB. Open this document in another app."
                            is java.util.zip.ZipException -> "Couldn't read this Office package. It may be damaged, encrypted, or use unsupported compression. Open it in another app."
                            is java.nio.charset.CharacterCodingException -> "This Office text uses an unsupported XML encoding. Open it in another app."
                            is org.xml.sax.SAXException -> "Couldn't read this Office XML. Open the document in another app."
                            else -> error?.message?.take(300) ?: "Couldn't prepare this Office preview."
                        },Modifier.padding(12.dp))
                        TextButton(onClick={attempt++},modifier=Modifier.heightIn(min=48.dp)) { Text("Retry Office preview") }
                    }
                    section!=null -> {
                        Text("${selected+1} of ${document!!.sections.size} · ${section.title}",Modifier.padding(12.dp),style=MaterialTheme.typography.titleSmall)
                        if(document.limited)Text("Partial preview. Copy and Find cover loaded content only.",Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
                        if(format==OfficeFormat.XLSX)Text("Stored values; formulas aren't recalculated.",Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(query,{query=OfficePolicy.clip(it,200)},label={Text("Find in loaded section")},singleLine=true,
                            modifier=Modifier.fillMaxWidth().padding(12.dp).testTag("office-search").then(Modifier.onOfficeEditing { editing=it }))
                        if(query.isNotBlank())FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(if(matches.isEmpty()) "No matching rows in loaded section" else "${active+1} of ${matches.size} matching rows",Modifier.padding(12.dp))
                            TextButton(onClick={active=Math.floorMod(active-1,matches.size)},enabled=matches.isNotEmpty(),modifier=Modifier.heightIn(min=48.dp)) { Text("Previous Office match") }
                            TextButton(onClick={active=Math.floorMod(active+1,matches.size)},enabled=matches.isNotEmpty(),modifier=Modifier.heightIn(min=48.dp)) { Text("Next Office match") }
                        }
                        status?.let { Text(it,Modifier.padding(horizontal=12.dp).testTag("office-status")) }
                    }
                }
            }
            if(section!=null&&!stopped)Box(Modifier.weight(1f).fillMaxWidth()) {
                if(section.text.isBlank())Text(if(document?.limited==true)"No more text is available within this preview's limits." else "No stored text was found in this section.",Modifier.padding(12.dp))
                else if(section.table!=null)CsvReadView(section.table,true,list,horizontal,query,matches.getOrNull(active))
                else SourceReadView(lines,list,horizontal,true,query,matches.getOrNull(active),false)
            }
        }
    }
    if(details)AlertDialog(onDismissRequest={details=false},title={Text("Office preview limits")},text={
        Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
            Text("DOCX: stored main-body text in reading order. XLSX: worksheet names and stored cell values, including available cached formula results. PPTX: slide text in presentation order.\n\nThis is a text preview. Page layout, fonts, drawings, charts, embedded media, headers, footers, notes, comments, hidden-content rules and editing behavior can differ or be omitted. Formulas, macros, external links and embedded programs never run.\n\nOnly unencrypted UTF-8 XML packages are supported. Limits: 64 MiB file, 2,048 ZIP parts, 4 MiB per XML part / 16 MiB read, 200,000 text characters, 8,192 characters per text value, 64 sections, 1,000 sheet rows, 32 data columns and 10,000 displayed grid cells across the workbook. Reaching a display limit is marked partial. Source files aren't rewritten.")
        }
    },confirmButton={TextButton(onClick={details=false},modifier=Modifier.heightIn(min=48.dp)){Text("Close preview limits")}})
}

private fun Modifier.onOfficeEditing(change: (Boolean)->Unit): Modifier = this.then(
    Modifier.onFocusChanged { change(it.hasFocus) }
)
