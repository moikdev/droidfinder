package dev.findroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun FileAnalysisDialog(model: FinderModel) {
    val state = model.analysis
    val report = state.report
    var options by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var issues by remember { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val narrow = config.screenWidthDp < 850
    Dialog(
        onDismissRequest = state::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.width((config.screenWidthDp * .94f).coerceAtMost(820f).dp)
                .height((config.screenHeightDp * .88f).coerceAtMost(if (narrow) 560f else 520f).dp)
                .testTag("file-analysis"),
            shape = RoundedCornerShape(14.dp),
            color = CanvasBackground,
            shadowElevation = 18.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(62.dp).padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.DataUsage, null, tint = Blue)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            "Storage analysis",
                            color = Ink,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            state.root?.let(model::displayPath).orEmpty(),
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.running)
                        TextButton(onClick = state::stop, enabled = !state.stopping) {
                            Text(if (state.stopping) "Stopping…" else "Stop")
                        }
                    state.root?.let(model::parentPath)?.let { parent ->
                        ToolButton(
                            Icons.Outlined.ArrowUpward,
                            "Analyze enclosing folder",
                            { state.open(parent) },
                            enabled = !state.running && model.busy == null,
                        )
                    }
                    ToolButton(
                        Icons.Outlined.Refresh,
                        "Refresh analysis",
                        state::refresh,
                        enabled = !state.running && model.busy == null,
                    )
                    ToolButton(
                        Icons.Outlined.Tune,
                        "Analysis options",
                        { options = true },
                        enabled = !state.running,
                    )
                    if (report != null)
                        ToolButton(Icons.Outlined.Info, "Scan details", { issues = true })
                    ToolButton(Icons.Outlined.Close, "Close analysis", state::close)
                }
                HorizontalDivider(color = Border)
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .background(Sidebar)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AnalysisView.entries.forEach { view ->
                        FilterChip(
                            selected = state.view == view,
                            onClick = {
                                state.view = view
                                state.selected = emptySet()
                            },
                            label = { Text(view.title, fontSize = 12.sp) },
                        )
                    }
                }
                if (state.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(state.progress.stage, color = Ink, fontSize = 12.sp)
                        Text(
                            if (state.progress.bytes > 0) "${formatSize(state.progress.bytes)} read"
                            else "${state.progress.entries} entries",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                        Text(
                            state.progress.current,
                            Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                state.error?.let {
                    Text(
                        it,
                        Modifier.fillMaxWidth().background(SubtleSurface).padding(12.dp),
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (report?.incomplete == true) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(SubtleSurface)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                report.stopped -> "Scan stopped · results are incomplete"
                                report.limited -> "Entry limit reached · results are incomplete"
                                else ->
                                    "${report.issueCount} scan notices · results may be incomplete"
                            },
                            Modifier.weight(1f),
                            color = Muted,
                            fontSize = 12.sp,
                        )
                        TextButton(onClick = { issues = true }) { Text("Details") }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        report == null ->
                            Column(
                                Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    Icons.Outlined.FolderOpen,
                                    null,
                                    tint = Muted,
                                    modifier = Modifier.size(36.dp),
                                )
                                Text(
                                    if (state.running) "Measuring accessible files…"
                                    else "No analysis yet",
                                    color = Muted,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                if (!state.running)
                                    TextButton(onClick = state::refresh) { Text("Try again") }
                            }
                        state.view == AnalysisView.OVERVIEW ->
                            AnalysisOverview(model, report, narrow)
                        state.view == AnalysisView.DUPLICATES -> DuplicateResults(model)
                        else -> AnalysisFiles(model)
                    }
                }
                HorizontalDivider(color = Border)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.selected.isEmpty())
                            "Read-only scan · cleanup uses recoverable Trash"
                        else
                            "${analysisCount(state.selected.size, "file")} selected · review before cleanup",
                        Modifier.weight(1f),
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                    TextButton(
                        onClick = { state.selected = emptySet() },
                        enabled = state.selected.isNotEmpty(),
                    ) {
                        Text("Clear")
                    }
                    Button(
                        onClick = { confirm = true },
                        enabled =
                            state.selected.isNotEmpty() && !state.running && model.busy == null,
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Review cleanup")
                    }
                }
            }
        }
    }
    if (options) AnalysisOptions(state) { options = false }
    if (issues && report != null)
        AlertDialog(
            onDismissRequest = { issues = false },
            title = { Text("Scan details") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "${analysisCount(report.files.size, "file")} · ${analysisCount(report.folders, "folder")}\n${report.unknown} unknown sizes · ${report.linksSkipped} links skipped\n${report.hiddenExcluded} hidden items excluded\nLimit: ${state.limits.entries} entries",
                        fontSize = 13.sp,
                    )
                    report.issues.forEach { issue ->
                        Text(
                            "${model.title(issue.path)}: ${issue.reason}",
                            Modifier.padding(top = 10.dp),
                            fontSize = 12.sp,
                        )
                    }
                    if (report.issueCount > report.issues.size)
                        Text(
                            "${report.issueCount - report.issues.size} additional notices",
                            Modifier.padding(top = 10.dp),
                        )
                }
            },
            confirmButton = { TextButton(onClick = { issues = false }) { Text("Done") } },
        )
    if (confirm && report != null) {
        val chosen = report.files.filter { it.node.path in state.selected }
        val dupe = if (state.view == AnalysisView.DUPLICATES) state.duplicates else null
        val missingKeeper =
            dupe?.groups.orEmpty().any { group ->
                group.files.all { it.node.path in state.selected }
            }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Move ${analysisCount(chosen.size, "file")} to Trash?") },
            text = {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            "Files are rechecked before removal. Completed cleanup can be undone in the explorer. Nothing is permanently deleted.",
                            fontSize = 13.sp,
                        )
                    }
                    items(chosen, key = { it.node.path }) { file ->
                        Text(
                            file.relative,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (missingKeeper)
                        item {
                            Text(
                                "Keep at least one file from each duplicate group.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        model.trashAnalyzed(report, state.selected, dupe)
                    },
                    enabled = !missingKeeper && model.busy == null,
                ) {
                    Text("Move to Trash")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AnalysisOverview(model: FinderModel, report: AnalysisReport, narrow: Boolean) {
    val total = remember(report) { report.knownBytes }
    val categories =
        remember(report) {
            report.files
                .groupBy { it.node.category }
                .mapValues { (_, files) ->
                    files.fold(0L) { a, f -> analysisAdd(a, f.node.bytes ?: 0) }
                }
        }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (report.unknown > 0 || report.incomplete) "≥ " else "") + formatSize(total),
                    color = Ink,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${analysisCount(report.files.size, "file")} · ${analysisCount(report.folders, "folder")}${if (report.unknown > 0) " · ${report.unknown} unknown sizes" else ""}",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            Text(
                if (report.unknown > 0 || report.incomplete) "Known logical sizes"
                else "Logical file sizes",
                color = Muted,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().height(8.dp).background(Border, RoundedCornerShape(4.dp))) {
            FileCategory.entries
                .filter { (categories[it] ?: 0) > 0 }
                .forEach { kind ->
                    Box(
                        Modifier.weight(
                                ((categories[kind] ?: 0).toDouble() / total.coerceAtLeast(1))
                                    .toFloat()
                                    .coerceAtLeast(.00001f),
                            )
                            .fillMaxHeight()
                            .background(categoryColor(kind)),
                    )
                }
        }
        Spacer(Modifier.height(14.dp))
        if (narrow) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(FileCategory.entries.chunked(2)) { kinds ->
                    AnalysisCategoryGridRow(kinds, categories)
                }
                item {
                    Text(
                        "BY FOLDER",
                        Modifier.padding(top = 12.dp, bottom = 6.dp),
                        color = Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(report.buckets, key = { it.path }) { bucket ->
                    AnalysisBucketRow(model, report, bucket)
                }
            }
        } else
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Text(
                            "BY TYPE",
                            Modifier.padding(bottom = 6.dp),
                            color = Muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(FileCategory.entries.chunked(2)) { kinds ->
                        AnalysisCategoryGridRow(kinds, categories)
                    }
                }
                Spacer(Modifier.width(24.dp))
                VerticalDivider(color = Border)
                Spacer(Modifier.width(24.dp))
                LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                    item {
                        Text(
                            "BY FOLDER",
                            Modifier.padding(bottom = 6.dp),
                            color = Muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(report.buckets, key = { it.path }) { bucket ->
                        AnalysisBucketRow(model, report, bucket)
                    }
                }
            }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth()
                .background(SubtleSurface, RoundedCornerShape(8.dp))
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Find extra copies",
                    color = Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Reads candidate files to verify matching contents.",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            TextButton(
                onClick = model.analysis::checkDuplicates,
                enabled = !model.analysis.running && model.busy == null,
            ) {
                Text("Check contents")
            }
        }
    }
}

@Composable
private fun AnalysisCategoryGridRow(kinds: List<FileCategory>, totals: Map<FileCategory, Long>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        kinds.forEach { kind ->
            Box(Modifier.weight(1f)) { AnalysisCategoryRow(kind, totals[kind] ?: 0) }
        }
        if (kinds.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun AnalysisCategoryRow(kind: FileCategory, bytes: Long) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(categoryColor(kind), RoundedCornerShape(2.dp)))
        Text(
            kind.title,
            Modifier.weight(1f).padding(start = 8.dp),
            color = Ink,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(formatSize(bytes), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun AnalysisBucketRow(model: FinderModel, report: AnalysisReport, bucket: FolderUsage) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Blue, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                bucket.name,
                color = Ink,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${analysisCount(bucket.files, "file")}${if (bucket.unknown > 0) " · ${bucket.unknown} unknown sizes" else ""}",
                color = Muted,
                fontSize = 10.sp,
            )
        }
        Text(formatSize(bucket.bytes), color = Muted, fontSize = 12.sp)
        if (bucket.path != report.root.path)
            ToolButton(
                Icons.Outlined.ChevronRight,
                "Analyze ${bucket.name}",
                { model.analysis.open(bucket.path) },
                enabled = model.busy == null,
            )
    }
}

@Composable
private fun AnalysisFiles(model: FinderModel) {
    val state = model.analysis
    val files = state.files
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                analysisCount(files.size, "file"),
                Modifier.weight(1f),
                color = Muted,
                fontSize = 12.sp,
            )
            if (state.view == AnalysisView.LARGE) {
                listOf(10L, 100L, 1024L).forEach { size ->
                    TextButton(onClick = { state.configure(large = size * 1024 * 1024) }) {
                        Text(
                            if (size == 1024L) "1 GB+" else "$size MB+",
                            color = if (state.largeBytes == size * 1024 * 1024) Blue else Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            if (state.view == AnalysisView.OLD) {
                listOf(90, 365, 1095).forEach { days ->
                    TextButton(onClick = { state.configure(days = days) }) {
                        Text(
                            when (days) {
                                90 -> "90 days"
                                365 -> "1 year"
                                else -> "3 years"
                            },
                            color = if (days == state.oldDays) Blue else Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        if (files.isEmpty()) AnalysisEmpty("No matching files in this scan")
        else
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(files, key = { it.node.path }) { file -> AnalysisFileRow(model, file) }
            }
    }
}

@Composable
private fun DuplicateResults(model: FinderModel) {
    val state = model.analysis
    val result = state.duplicates
    if (result == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.ContentCopy, null, tint = Muted, modifier = Modifier.size(32.dp))
            Text(
                if (state.running) "Checking full file contents…"
                else "Metadata alone doesn't prove a duplicate",
                color = Ink,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "Full accessible streams are compared; unknown sizes are measured while reading. Limit: ${formatSize(state.limits.hashBytes)} read.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            if (!state.running)
                Button(onClick = state::checkDuplicates, enabled = model.busy == null) {
                    Text("Check contents")
                }
        }
        return
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${analysisCount(result.groups.size, "duplicate group")} · ${formatSize(result.groups.fold(0L) { a, g -> analysisAdd(a, g.extraBytes) })} in extra copies",
                    color = Ink,
                    fontSize = 13.sp,
                )
                Text(
                    "${result.checked} checked · ${formatSize(result.bytes)} read${if (result.incomplete) " · partial results${if (result.skipped > 0) ", ${result.skipped} not checked" else ""}${if (result.stopped) ", stopped" else ""}${if (result.scopeIncomplete) ", partial scan scope" else ""}" else ""}",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            TextButton(onClick = state::selectExtras, enabled = !state.running) {
                Text("Select extra copies")
            }
        }
        if (result.issues.isNotEmpty())
            Text(
                result.issues.take(2).joinToString(" · ") { it.reason },
                Modifier.padding(horizontal = 16.dp),
                color = Muted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        if (result.groups.isEmpty())
            AnalysisEmpty(
                if (result.incomplete) "No duplicates verified in the completed checks"
                else "No duplicate contents found",
            )
        else
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 10.dp)) {
                result.groups.forEach { group ->
                    item(key = "group:${group.hash}") {
                        Text(
                            "${group.files.size} matching files · ${formatSize(group.files.first().node.bytes ?: 0)} each · keep at least one",
                            Modifier.fillMaxWidth().background(SubtleSurface).padding(10.dp),
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                    items(group.files, key = { it.node.path }) { file ->
                        AnalysisFileRow(model, file)
                    }
                }
            }
    }
}

@Composable
private fun AnalysisFileRow(model: FinderModel, file: AnalyzedFile) {
    val state = model.analysis
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = file.node.path in state.selected,
            onCheckedChange = {
                if (it) state.selected += file.node.path else state.selected -= file.node.path
            },
            enabled = file.node.canDelete && !state.running && model.busy == null,
            modifier =
                Modifier.testTag("analysis-select:${file.node.name}").semantics {
                    contentDescription = "Select ${file.node.name}, ${file.relative}"
                },
        )
        FileGlyph(
            Entry(
                java.io.File(file.node.path),
                file.node.name,
                false,
                file.node.bytes ?: 0,
                file.node.modified,
                if (isDocumentPath(file.node.path)) android.net.Uri.parse(file.node.path) else null,
                mimeType = file.node.mime,
                sizeKnown = file.node.bytes != null,
            ),
            28.dp,
            thumbnail = false,
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                file.node.name,
                color = Ink,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (file.relative.substringBeforeLast('/', "").ifEmpty { "In this folder" }) +
                    if (state.view == AnalysisView.OLD)
                        " · ${java.text.DateFormat.getDateInstance().format(java.util.Date(file.node.modified))}"
                    else "",
                color = Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(file.node.bytes?.let(::formatSize) ?: "Unknown", color = Muted, fontSize = 12.sp)
        ToolButton(
            Icons.Outlined.FolderOpen,
            "Reveal ${file.node.name}",
            { model.revealAnalyzed(file.node) },
        )
    }
}

@Composable
private fun AnalysisEmpty(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

private fun categoryColor(kind: FileCategory): Color =
    when (kind) {
        FileCategory.IMAGES -> Color(0xFF418BE8)
        FileCategory.VIDEO -> Color(0xFF8E6BD1)
        FileCategory.AUDIO -> Color(0xFFDE759D)
        FileCategory.DOCUMENTS -> Color(0xFF40A598)
        FileCategory.ARCHIVES -> Color(0xFFE6AD54)
        FileCategory.APPS -> Color(0xFF79A95B)
        FileCategory.OTHER -> Color(0xFF9298A5)
    }

@Composable
private fun AnalysisOptions(state: AnalysisController, dismiss: () -> Unit) {
    var entries by remember { mutableIntStateOf(state.limits.entries) }
    var bytes by remember { mutableLongStateOf(state.limits.hashBytes) }
    var hidden by remember { mutableStateOf(state.includeHidden) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Analysis limits") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Maximum entries", fontSize = 13.sp)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(50_000, 100_000, 1_000_000).forEach { count ->
                        FilterChip(
                            selected = count == entries,
                            onClick = { entries = count },
                            label = {
                                Text(if (count == 1_000_000) "1 million" else "${count / 1000}k")
                            },
                        )
                    }
                }
                Text(
                    "Duplicate content-read budget",
                    Modifier.padding(top = 12.dp),
                    fontSize = 13.sp,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(1L, 8L, 64L, 1024L).forEach { gb ->
                        val n = gb * 1024 * 1024 * 1024
                        FilterChip(
                            selected = n == bytes,
                            onClick = { bytes = n },
                            label = { Text(if (gb == 1024L) "1 TB" else "$gb GB") },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(hidden, { hidden = it })
                    Text("Include hidden files", fontSize = 13.sp)
                }
                Text(
                    "Links and internal working files are skipped. Results measure logical file sizes, not allocated blocks or guaranteed recoverable space.",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    state.configure(AnalysisLimits(entries, bytes), hidden, rescan = true)
                    dismiss()
                },
            ) {
                Text("Apply and rescan")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun FileChecksumDialog(model: FinderModel) {
    val state = model.checksums
    val sum = state.result
    val destination = model.documents.parent(state.path)
    val clipboard = LocalClipboardManager.current
    val config = LocalConfiguration.current
    var menu by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf(false) }
    val expected =
        if (state.expected.isBlank()) null
        else runCatching { expectedChecksum(state.expected, state.algorithm) }
    Dialog(
        onDismissRequest = state::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.width((config.screenWidthDp * .94f).coerceAtMost(650f).dp)
                .height(
                    (config.screenHeightDp * .88f)
                        .coerceAtMost(
                            if (state.algorithm == ChecksumAlgorithm.SHA512) 440f else 410f,
                        )
                        .dp,
                )
                .testTag("file-checksum"),
            shape = RoundedCornerShape(14.dp),
            color = CanvasBackground,
            shadowElevation = 18.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(58.dp).padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Fingerprint, null, tint = Blue)
                    Text(
                        "Checksums",
                        Modifier.weight(1f).padding(start = 10.dp),
                        color = Ink,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ToolButton(Icons.Outlined.Close, "Close checksums", state::close)
                }
                HorizontalDivider(color = Border)
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                sum?.node?.name ?: model.title(state.path),
                                color = Ink,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (state.running) "${formatSize(state.bytes)} read"
                                else
                                    sum?.let { "${formatSize(it.bytes)} · full file contents" }
                                        ?: "No complete checksum",
                                color = Muted,
                                fontSize = 12.sp,
                            )
                        }
                        Box {
                            OutlinedButton(onClick = { menu = true }, enabled = !state.running) {
                                Text(state.algorithm.title)
                                Icon(Icons.Outlined.ArrowDropDown, null)
                            }
                            DropdownMenu(menu, { menu = false }) {
                                ChecksumAlgorithm.entries.forEach { algorithm ->
                                    DropdownMenuItem(
                                        text = { Text(algorithm.title) },
                                        onClick = {
                                            menu = false
                                            state.choose(algorithm)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Surface(color = SubtleSurface, shape = RoundedCornerShape(8.dp)) {
                        SelectionContainer {
                            Text(
                                sum?.hex
                                    ?: if (state.running) "Reading file…"
                                    else "Calculate to generate a complete checksum.",
                                Modifier.fillMaxWidth().padding(14.dp).testTag("checksum-value"),
                                color = Ink,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    OutlinedTextField(
                        state.expected,
                        { if (it.length <= 4096) state.expected = it },
                        label = { Text("Expected ${state.algorithm.title}") },
                        placeholder = { Text("Paste a checksum to verify") },
                        modifier = Modifier.fillMaxWidth().testTag("checksum-expected"),
                        maxLines = 3,
                        isError =
                            expected?.isFailure == true ||
                                expected?.getOrNull()?.let { sum != null && it != sum.hex } == true,
                    )
                    if (expected != null)
                        Text(
                            when {
                                expected.isFailure -> expected.exceptionOrNull()?.message.orEmpty()
                                sum == null -> "Calculate the complete checksum before comparing."
                                expected.getOrNull() == sum.hex -> "Checksum matches"
                                else -> "Checksum does not match"
                            },
                            color =
                                if (sum != null && expected.getOrNull() == sum.hex) Blue
                                else MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            modifier = Modifier.testTag("checksum-comparison"),
                        )
                    if (state.algorithm in setOf(ChecksumAlgorithm.MD5, ChecksumAlgorithm.SHA1))
                        Text(
                            "Compatibility checksum for existing downloads and manifests.",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    state.error?.let { Text(it, color = Muted, fontSize = 12.sp) }
                }
                HorizontalDivider(color = Border)
                Text(
                    destination?.let {
                        "Save in ${model.displayPath(it)} · existing files are kept"
                    } ?: "Open the containing folder before saving a checksum file",
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { sum?.let { clipboard.setText(AnnotatedString(it.hex)) } },
                        enabled = sum != null && !state.running,
                    ) {
                        Text("Copy")
                    }
                    TextButton(
                        onClick = { save = true },
                        enabled =
                            sum != null &&
                                destination != null &&
                                !state.running &&
                                model.busy == null,
                    ) {
                        Text("Save checksum file")
                    }
                    Button(onClick = { if (state.running) state.stop() else state.calculate() }) {
                        Text(if (state.running) "Stop" else "Calculate again")
                    }
                }
            }
        }
    }
    if (save && sum != null)
        NameDialog(
            "Save checksum file",
            checksumFilename(sum),
            "Save",
            { save = false },
            validation = { name ->
                runCatching {
                    validateFilename(name)
                    require(!isWorkingFileName(name)) {
                        "That name is reserved for working files."
                    }
                }
                    .exceptionOrNull()
                    ?.message
            },
            onConfirm = {
                model.saveChecksum(sum, it)
                save = false
            },
        )
}

private fun analysisCount(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"
