package dev.droidfinder

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.*

private data class ArchiveLoaded(val file: File, val contents: ZipContents)

private data class ArchivePlan(val files: Int, val bytes: Long, val issue: String? = null)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArchiveBrowserDialog(
    model: FinderModel,
    entry: Entry,
    onDismiss: () -> Unit,
    onExtract: (Boolean, Set<String>?, ZipLimits) -> Unit,
    onOpenWith: () -> Unit,
    onMemberOpenWith: (Entry) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ZipLimitsStore(context) }
    val identity = listOf(entry.path, entry.modified, entry.size, entry.name)
    var limits by remember(identity) { mutableStateOf(store.read()) }
    var showLimits by remember { mutableStateOf(false) }
    var reload by remember(identity) { mutableIntStateOf(0) }
    var prefix by remember(identity) { mutableStateOf("") }
    var query by remember(identity) { mutableStateOf("") }
    var selected by remember(identity) { mutableStateOf<Set<String>>(emptySet()) }
    var focused by remember(identity) { mutableStateOf<String?>(null) }
    var contextItem by remember { mutableStateOf<ZipItem?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    var focusRequest by remember { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val listingControl = remember(identity, limits, reload) { ListingControl() }
    val inspectControl = remember(listingControl) { TransferControl() }
    var stopped by remember(listingControl) { mutableStateOf(false) }
    val result by
        produceState<Result<ArchiveLoaded>?>(null, listingControl) {
            value = null
            try {
                value =
                    Result.success(
                        withContext(Dispatchers.IO) {
                            val local =
                                if (entry.documentUri != null)
                                    model.documents.materialize(entry, listingControl)
                                else entry.file
                            inspectControl.check()
                            ArchiveLoaded(local, inspectZip(local, limits, inspectControl))
                        },
                    )
            } catch (error: Exception) {
                if (error is CancellationException) return@produceState
                if (!stopped) value = Result.failure(error)
            }
        }
    val loaded = result?.getOrNull()
    val contents = loaded?.contents
    val children by
        produceState<List<ZipItem>>(emptyList(), contents, prefix, query) {
            value = emptyList()
            value =
                withContext(Dispatchers.Default) {
                    val job = currentCoroutineContext().job
                    contents
                        ?.items
                        .orEmpty()
                        .filter {
                            job.ensureActive()
                            if (query.isBlank()) it.path.substringBeforeLast('/', "") == prefix
                            else it.path.contains(query, ignoreCase = true)
                        }
                        .sortedWith(
                            compareByDescending<ZipItem> { it.folder }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.path },
                        )
                }
        }
    val plan by
        produceState<ArchivePlan?>(null, contents, selected, limits) {
            value = null
            if (contents != null)
                value =
                    withContext(Dispatchers.Default) {
                        val job = currentCoroutineContext().job
                        val chosen =
                            selectedZipItems(contents, selected.takeIf { it.isNotEmpty() }) {
                                job.ensureActive()
                            }
                        ArchivePlan(
                            chosen.count { !it.folder },
                            chosen.sumOf { it.size },
                            runCatching { validateZipWork(chosen, limits) }
                                .exceptionOrNull()
                                ?.message,
                        )
                    }
        }
    var verifyControl by remember { mutableStateOf<TransferControl?>(null) }
    var verifyProgress by remember { mutableStateOf(TransferProgress()) }
    var checkResult by remember { mutableStateOf<ZipCheckResult?>(null) }
    var checkMessage by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var previewControl by remember { mutableStateOf<TransferControl?>(null) }
    var previewProgress by remember { mutableStateOf(TransferProgress()) }
    var preparing by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var peek by remember { mutableStateOf<Entry?>(null) }
    var peekPath by remember { mutableStateOf<String?>(null) }
    fun checkArchive() {
        val source = loaded ?: return
        val control = TransferControl { progress -> verifyProgress = progress }
        verifyControl?.cancel()
        verifyControl = control
        checkResult = null
        checkMessage = null
        checking = true
        scope.launch {
            try {
                val checked =
                    withContext(Dispatchers.IO) { verifyZip(source.file, limits, control) }
                if (verifyControl === control) checkResult = checked
            } catch (error: Exception) {
                if (verifyControl === control)
                    checkMessage =
                        if (control.cancelled) "Check stopped · not all files were checked"
                        else error.message ?: "Couldn't check this ZIP."
            } finally {
                if (verifyControl === control) checking = false
            }
        }
    }
    fun requestPeek(item: ZipItem) {
        val source = loaded ?: return
        if (item.folder) {
            prefix = item.path
            query = ""
            return
        }
        val control = TransferControl { progress -> previewProgress = progress }
        previewControl?.cancel()
        previewControl = control
        previewError = null
        preparing = true
        scope.launch {
            try {
                val member =
                    ArchivePreviewCache.read(
                        context.applicationContext,
                        source.file,
                        item,
                        limits,
                        control,
                    )
                if (previewControl === control) {
                    peek?.let(ArchivePreviewCache::release)
                    peekPath = item.path
                    peek = member
                } else ArchivePreviewCache.release(member)
            } catch (error: Exception) {
                if (previewControl === control)
                    previewError =
                        if (control.cancelled) "Preview stopped"
                        else error.message ?: "Couldn't preview this member."
            } finally {
                if (previewControl === control) preparing = false
            }
        }
    }
    fun leaveSearch() {
        searchFocused = false
        focusManager.clearFocus()
        keyboard?.hide()
        rootFocus.requestFocus()
    }
    fun browse(item: ZipItem) {
        focused = item.path
        if (item.folder) {
            prefix = item.path
            query = ""
        }
        leaveSearch()
    }
    DisposableEffect(listingControl) {
        onDispose {
            listingControl.cancel()
            inspectControl.cancel()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            verifyControl?.cancel()
            previewControl?.cancel()
            peek?.let(ArchivePreviewCache::release)
        }
    }
    LaunchedEffect(limits, reload) {
        verifyControl?.cancel()
        verifyControl = null
        checking = false
        checkResult = null
        checkMessage = null
    }
    LaunchedEffect(reload) { selected = emptySet() }
    LaunchedEffect(contents) {
        contents?.let { value ->
            val paths = value.items.mapTo(hashSetOf()) { it.path }
            selected = selected.intersect(paths)
            if (focused !in paths) focused = null
        }
    }
    val cacheLimited = result?.exceptionOrNull() is PreviewSizeLimitException
    val selection = selected.takeIf { it.isNotEmpty() }
    val canExtract =
        (contents != null && plan != null && plan?.issue == null ||
            cacheLimited && selection == null) && model.busy == null
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(focusRequest) { if (focusRequest > 0) searchFocus.requestFocus() }
        LaunchedEffect(Unit) { rootFocus.requestFocus() }
        val configuration = LocalConfiguration.current
        val compact = configuration.screenWidthDp < 700
        val windowHeight = (configuration.screenHeightDp * .86f).coerceAtMost(620f).dp
        Surface(
            Modifier.fillMaxWidth(if (compact) .96f else .85f)
                .height(windowHeight)
                .imePadding()
                .testTag("archive-browser")
                .semantics { paneTitle = "Archive contents" }
                .focusRequester(rootFocus)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (event.type != KeyEventType.KeyDown) false
                    else
                        when {
                            (native.isCtrlPressed || native.isMetaPressed) &&
                                native.keyCode == AndroidKeyEvent.KEYCODE_F -> {
                                focusRequest++
                                true
                            }
                            native.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE &&
                                query.isNotEmpty() -> {
                                query = ""
                                leaveSearch()
                                true
                            }
                            searchFocused -> false
                            (native.isCtrlPressed || native.isMetaPressed) &&
                                native.keyCode == AndroidKeyEvent.KEYCODE_A -> {
                                selected = children.map { it.path }.toSet()
                                true
                            }
                            native.isAltPressed &&
                                native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                prefix = prefix.substringBeforeLast('/', "")
                                query = ""
                                true
                            }
                            native.keyCode in
                                listOf(
                                    AndroidKeyEvent.KEYCODE_SPACE,
                                    AndroidKeyEvent.KEYCODE_ENTER,
                                ) -> {
                                (children.firstOrNull { it.path == focused }
                                        ?: children.firstOrNull { it.path in selected })
                                    ?.let(::requestPeek)
                                true
                            }
                            else -> false
                        }
                }
                .focusable(),
            color = CanvasBackground,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 18.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.FolderZip, null, tint = Blue)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            entry.name,
                            color = Ink,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("ZIP archive · read-only contents", color = Muted, fontSize = 11.sp)
                    }
                    ToolButton(Icons.Outlined.Close, "Close archive", onDismiss)
                }
                HorizontalDivider(color = Border)
                Row(
                    Modifier.fillMaxWidth()
                        .height(48.dp)
                        .background(ToolbarSurface)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolButton(
                        Icons.Outlined.ArrowUpward,
                        "Parent archive folder",
                        {
                            prefix = prefix.substringBeforeLast('/', "")
                            query = ""
                            focused = null
                        },
                        enabled = prefix.isNotEmpty(),
                    )
                    Text(
                        if (query.isNotBlank()) "Search results"
                        else prefix.ifBlank { "Archive root" },
                        Modifier.weight(1f).padding(horizontal = 8.dp),
                        color = Ink,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ToolButton(
                        Icons.Outlined.VerifiedUser,
                        "Check ZIP integrity",
                        ::checkArchive,
                        enabled = loaded != null && !checking,
                    )
                    ToolButton(Icons.Outlined.Tune, "ZIP limits", { showLimits = true })
                    ToolButton(
                        Icons.Outlined.Refresh,
                        "Reload archive",
                        {
                            reload++
                            prefix = ""
                            query = ""
                        },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        singleLine = true,
                        placeholder = { Text("Search archive paths", fontSize = 12.sp) },
                        textStyle =
                            LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        modifier =
                            Modifier.weight(1f)
                                .height(50.dp)
                                .testTag("archive-search")
                                .focusRequester(searchFocus)
                                .onFocusChanged { searchFocused = it.isFocused },
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = ImeAction.Search,
                            ),
                        keyboardActions = KeyboardActions(onSearch = { leaveSearch() }),
                    )
                    if (query.isNotEmpty())
                        ToolButton(
                            Icons.Outlined.Close,
                            "Clear archive search",
                            {
                                query = ""
                                leaveSearch()
                            },
                        )
                    if (!compact)
                        Text(
                            "${archiveFileCount(contents?.items?.count { !it.folder } ?: 0)} · ${contents?.let { formatSize(it.size) } ?: "—"}",
                            Modifier.padding(start = 14.dp),
                            color = Muted,
                            fontSize = 11.sp,
                        )
                }
                if (checking || checkResult != null || checkMessage != null)
                    Column(
                        Modifier.fillMaxWidth()
                            .background(ToolbarSurface)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (checking)
                                    "Checking ${archiveFileCount(verifyProgress.items)} · ${formatSize(verifyProgress.bytes)}"
                                else
                                    checkMessage
                                        ?: checkResult!!.let {
                                            when {
                                                it.healthy ->
                                                    if (it.total == 0)
                                                        "Empty ZIP · no files to check"
                                                    else
                                                        "${if (it.total == 1) "1 file" else "All ${it.total} files"} passed size/CRC checks"
                                                it.limit != null ->
                                                    "Check incomplete · ${it.checked} of ${it.total} files checked"
                                                else ->
                                                    "${it.failures} ${if (it.failures == 1) "file" else "files"} failed · ${it.checked} checked"
                                            }
                                        },
                                Modifier.weight(1f),
                                color =
                                    if (checkResult?.failures ?: 0 > 0)
                                        MaterialTheme.colorScheme.error
                                    else Ink,
                                fontSize = 12.sp,
                            )
                            if (checking)
                                TextButton(onClick = { verifyControl?.cancel() }) {
                                    Text("Stop check")
                                }
                            else if (checkResult?.failures ?: 0 > 0 || checkResult?.limit != null)
                                TextButton(onClick = { details = true }) { Text("Details") }
                            ToolButton(
                                Icons.Outlined.Close,
                                "Hide ZIP check",
                                {
                                    verifyControl?.cancel()
                                    verifyControl = null
                                    checking = false
                                    checkResult = null
                                    checkMessage = null
                                },
                                compact = true,
                            )
                        }
                        if (checking) {
                            val fraction = verifyProgress.fraction
                            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                        }
                    }
                if (previewError != null)
                    Row(
                        Modifier.fillMaxWidth().background(SelectionSurface).padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(previewError!!, Modifier.weight(1f), color = Ink, fontSize = 11.sp)
                        ToolButton(
                            Icons.Outlined.Close,
                            "Dismiss archive preview message",
                            { previewError = null },
                            compact = true,
                        )
                    }
                Row(
                    Modifier.fillMaxWidth()
                        .height(if (LocalFinderDensity.current.touch) 48.dp else 36.dp)
                        .padding(start = 20.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        selected.isNotEmpty() &&
                            children.isNotEmpty() &&
                            children.all { it.path in selected },
                        { checked ->
                            selected =
                                if (checked) selected + children.map { it.path }
                                else selected - children.map { it.path }.toSet()
                        },
                        Modifier.size(LocalFinderDensity.current.targetSize).semantics {
                            contentDescription = "Select visible archive items"
                        },
                    )
                    Text(
                        "Name",
                        Modifier.weight(1f).padding(start = 39.dp),
                        color = Muted,
                        fontSize = 11.sp,
                    )
                    Text("Size", Modifier.width(85.dp), color = Muted, fontSize = 11.sp)
                    if (!compact)
                        Text("Packed", Modifier.width(85.dp), color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.width(if (LocalFinderDensity.current.touch) 48.dp else 34.dp))
                }
                HorizontalDivider(color = Border)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        stopped ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Archive loading stopped", color = Muted)
                                TextButton(onClick = { reload++ }) { Text("Restart") }
                            }
                        result == null ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                TextButton(
                                    onClick = {
                                        stopped = true
                                        listingControl.cancel()
                                        inspectControl.cancel()
                                    },
                                ) {
                                    Text("Stop loading archive")
                                }
                            }
                        result!!.isFailure ->
                            Text(
                                if (cacheLimited)
                                    "This archive exceeds the provider preview cache limit. You can still extract it to a folder."
                                else result!!.exceptionOrNull()?.message ?: "Couldn't open archive",
                                Modifier.padding(24.dp),
                                color = Muted,
                                fontSize = 13.sp,
                            )
                        children.isEmpty() ->
                            Text(
                                if (query.isNotBlank()) "No matching archive paths"
                                else "This archive folder is empty",
                                color = Muted,
                            )
                        else ->
                            LazyColumn(Modifier.fillMaxSize().testTag("archive-items")) {
                                items(children, key = { it.path }) { item ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .height(
                                                if (query.isNotBlank()) 64.dp
                                                else if (LocalFinderDensity.current.touch) 56.dp
                                                else 44.dp,
                                            )
                                            .background(
                                                if (item.path in selected || focused == item.path)
                                                    SelectionSurface
                                                else CanvasBackground,
                                            )
                                            .combinedClickable(
                                                onClick = { browse(item) },
                                                onDoubleClick = {
                                                    if (item.folder) browse(item)
                                                    else requestPeek(item)
                                                },
                                                onLongClick = {
                                                    focused = item.path
                                                    contextItem = item
                                                },
                                            )
                                            .pointerInput(item.path) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        if (
                                                            event.type == PointerEventType.Press &&
                                                                event.buttons.isSecondaryPressed
                                                        ) {
                                                            focused = item.path
                                                            contextItem = item
                                                            event.changes.forEach { it.consume() }
                                                        }
                                                    }
                                                }
                                            }
                                            .padding(start = 20.dp, end = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            item.path in selected,
                                            { checked ->
                                                selected =
                                                    if (checked) selected + item.path
                                                    else selected - item.path
                                            },
                                            Modifier.size(LocalFinderDensity.current.targetSize)
                                                .semantics {
                                                    contentDescription = "Select ${item.path}"
                                                },
                                        )
                                        FileGlyph(
                                            Entry(
                                                File(item.path.substringAfterLast('/')),
                                                folder = item.folder,
                                                size = item.size,
                                                modified = item.modified,
                                                writable = false,
                                                canDelete = false,
                                                canRename = false,
                                            ),
                                            27.dp,
                                        )
                                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                            Text(
                                                item.path.substringAfterLast('/'),
                                                color = Ink,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (query.isNotBlank())
                                                Text(
                                                    item.path.substringBeforeLast(
                                                        '/',
                                                        "Archive root",
                                                    ),
                                                    color = Muted,
                                                    fontSize = 10.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                        }
                                        Text(
                                            if (item.folder) "Folder" else formatSize(item.size),
                                            Modifier.width(85.dp),
                                            color = Muted,
                                            fontSize = 11.sp,
                                        )
                                        if (!compact)
                                            Text(
                                                if (item.folder) "—"
                                                else formatSize(item.compressedSize),
                                                Modifier.width(85.dp),
                                                color = Muted,
                                                fontSize = 11.sp,
                                            )
                                        ToolButton(
                                            if (item.folder) Icons.Outlined.ChevronRight
                                            else Icons.Outlined.Visibility,
                                            if (item.folder) "Browse ${item.path}"
                                            else "Preview ${item.path}",
                                            {
                                                if (item.folder) browse(item) else requestPeek(item)
                                            },
                                            compact = true,
                                        )
                                    }
                                }
                            }
                    }
                }
                HorizontalDivider(color = Border)
                if (plan?.issue != null)
                    Text(
                        plan!!.issue!!,
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        color = Muted,
                        fontSize = 11.sp,
                    )
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 58.dp)
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected.isNotEmpty())
                        TextButton(onClick = { selected = emptySet() }) {
                            Text("Clear ${selected.size}", fontSize = 11.sp)
                        }
                    else if (!compact)
                        TextButton(onClick = onOpenWith) { Text("Open with app", fontSize = 11.sp) }
                    Text(
                        plan
                            ?.let { "${archiveFileCount(it.files)} · ${formatSize(it.bytes)}" }
                            .orEmpty(),
                        Modifier.weight(1f).padding(horizontal = 8.dp),
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { onExtract(true, selection, limits) },
                        enabled = canExtract,
                    ) {
                        Text("Extract to…", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { onExtract(false, selection, limits) },
                        enabled =
                            canExtract && model.canCreate && !model.effectiveSearchRules.active,
                    ) {
                        Text(
                            if (selection == null) "Extract here" else "Extract selected",
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
    if (contextItem != null)
        AlertDialog(
            onDismissRequest = { contextItem = null },
            title = { Text(contextItem!!.path.substringAfterLast('/')) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            val item = contextItem!!
                            contextItem = null
                            if (item.folder) browse(item) else requestPeek(item)
                        },
                    ) {
                        Text(if (contextItem!!.folder) "Browse folder" else "Peek")
                    }
                    TextButton(
                        onClick = {
                            selected = selected + contextItem!!.path
                            contextItem = null
                        },
                    ) {
                        Text("Select item")
                    }
                    TextButton(
                        onClick = {
                            val path = contextItem!!.path
                            contextItem = null
                            onExtract(true, setOf(path), limits)
                        },
                    ) {
                        Text("Extract this item…")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { contextItem = null }) { Text("Done") } },
        )
    if (showLimits)
        ZipLimitsDialog(limits, { showLimits = false }) {
            limits = it
            store.write(it)
            showLimits = false
        }
    if (details)
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text("ZIP check results") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    checkResult?.limit?.let { Text(it, color = Ink, fontSize = 12.sp) }
                    checkResult?.details.orEmpty().forEach {
                        Text(it, Modifier.padding(vertical = 6.dp), color = Muted, fontSize = 12.sp)
                    }
                    if ((checkResult?.failures ?: 0) > 20)
                        Text("Only the first 20 failures are listed.", fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Done") } },
        )
    if (peek != null) {
        val candidates =
            children
                .filter { !it.folder }
                .map { item ->
                    item to
                        if (item.path == peekPath) peek!!
                        else
                            Entry(
                                File(context.cacheDir, "archive-virtual/${item.path}"),
                                name = item.path.substringAfterLast('/'),
                                folder = false,
                                size = item.size,
                                modified = item.modified,
                                writable = false,
                                canDelete = false,
                                canRename = false,
                            )
                }
        PeekOverlay(
            peek!!,
            candidates.map { it.second },
            { next ->
                candidates.firstOrNull { it.second.path == next.path }?.first?.let(::requestPeek)
            },
            onMemberOpenWith,
            {
                peek?.let(ArchivePreviewCache::release)
                peek = null
                peekPath = null
            },
            sourceLabel = "${entry.name} / ${peekPath.orEmpty()}",
        )
    }
    if (preparing)
        AlertDialog(
            onDismissRequest = { previewControl?.cancel() },
            title = { Text("Preparing preview") },
            text = {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "${formatSize(previewProgress.bytes)} read · checking size and CRC",
                        Modifier.padding(top = 12.dp),
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { previewControl?.cancel() }) { Text("Stop preview") }
            },
        )
}

private fun archiveFileCount(count: Int) = "$count ${if (count == 1) "file" else "files"}"
