package dev.findroid

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.KeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

private suspend fun <T> pdfResult(work: suspend () -> T): Result<T> =
    try {
        Result.success(work())
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.failure(error)
    }

@Composable
fun PdfQuickLook(entry: Entry, modifier: Modifier = Modifier, sourcePath: String = entry.path) {
    BoxWithConstraints(modifier) {
        PdfQuickLookContent(entry, Modifier.fillMaxSize(), sourcePath, maxWidth < 480.dp)
    }
}

@Composable
private fun PdfQuickLookContent(
    entry: Entry,
    modifier: Modifier,
    sourcePath: String,
    compactNavigation: Boolean,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val session =
        remember(sourcePath, entry.modified, entry.size) {
            PdfReadingSession(context.applicationContext, entry)
        }
    val store = remember { PdfReadingStore(context) }
    val zoomState = remember(session) { BitmapZoomState() }
    val saved = remember(session) { store.read(sourcePath, entry.modified, entry.size) }
    var page by remember(session) { mutableIntStateOf(saved.page) }
    var bookmarks by remember(session) { mutableStateOf(saved.bookmarks) }
    val opening by
        produceState<Result<Int>?>(null, session) {
            value = null
            value = pdfResult { session.pageCount() }
        }
    val pages = opening?.getOrNull() ?: 0
    var sidebar by remember(session) { mutableStateOf(!compactNavigation) }
    var rail by remember(session) { mutableStateOf("pages") }
    var readText by remember(session) { mutableStateOf(false) }
    var pageJump by remember { mutableStateOf(false) }
    var searchOpen by remember(session) { mutableStateOf(false) }
    var query by remember(session) { mutableStateOf("") }
    var matches by remember(session) { mutableStateOf<List<PdfMatch>>(emptyList()) }
    var activeMatch by remember(session) { mutableIntStateOf(-1) }
    var scanned by remember(session) { mutableIntStateOf(0) }
    var searchIssues by remember(session) { mutableIntStateOf(0) }
    var issueDetails by remember(session) { mutableStateOf<List<PdfSearchIssue>>(emptyList()) }
    var showIssues by remember(session) { mutableStateOf(false) }
    var searching by remember(session) { mutableStateOf(false) }
    var stopped by remember(session) { mutableStateOf(false) }
    var capped by remember(session) { mutableStateOf(false) }
    var searchJob by remember(session) { mutableStateOf<Job?>(null) }
    var restart by remember(session) { mutableIntStateOf(0) }
    var focusRequest by remember { mutableIntStateOf(0) }
    val searchFocus = remember { FocusRequester() }
    val keys = LocalPeekKeys.current
    LaunchedEffect(pages) {
        if (pages > 0) {
            page = page.coerceIn(0, pages - 1)
            bookmarks = bookmarks.filter { it < pages }.toSet()
        }
    }
    LaunchedEffect(page, bookmarks) {
        delay(250)
        store.save(sourcePath, entry.modified, entry.size, PdfReadingPosition(page, bookmarks))
    }
    DisposableEffect(session) {
        onDispose {
            store.save(sourcePath, entry.modified, entry.size, PdfReadingPosition(page, bookmarks))
            session.close()
        }
    }
    fun selectMatch(index: Int, finishEditing: Boolean = false) {
        if (finishEditing) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
        if (matches.isEmpty()) return
        activeMatch = Math.floorMod(index, matches.size)
        page = matches[activeMatch].page
        if (compactNavigation) sidebar = false
    }
    fun find() {
        searchOpen = true
        sidebar = true
        focusRequest++
    }
    val handleKey by
        rememberUpdatedState<(KeyEvent) -> Boolean>(
            { event ->
                when {
                    event.action != KeyEvent.ACTION_DOWN -> false
                    (event.isCtrlPressed || event.isMetaPressed) &&
                        event.keyCode == KeyEvent.KEYCODE_F -> {
                        find()
                        true
                    }
                    event.keyCode == KeyEvent.KEYCODE_F3 && searchOpen -> {
                        selectMatch(activeMatch + if (event.isShiftPressed) -1 else 1)
                        true
                    }
                    event.keyCode == KeyEvent.KEYCODE_ESCAPE && searchOpen -> {
                        searchOpen = false
                        if (compactNavigation) sidebar = false
                        keys?.editing = false
                        true
                    }
                    keys?.editing == true || event.isAltPressed -> false
                    event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN && pages > 0 -> {
                        page = (page + 1).coerceAtMost(pages - 1)
                        true
                    }
                    event.keyCode == KeyEvent.KEYCODE_PAGE_UP && pages > 0 -> {
                        page = (page - 1).coerceAtLeast(0)
                        true
                    }
                    event.isCtrlPressed &&
                        event.keyCode == KeyEvent.KEYCODE_MOVE_HOME &&
                        pages > 0 -> {
                        page = 0
                        true
                    }
                    event.isCtrlPressed &&
                        event.keyCode == KeyEvent.KEYCODE_MOVE_END &&
                        pages > 0 -> {
                        page = pages - 1
                        true
                    }
                    else -> false
                }
            },
        )
    DisposableEffect(keys, session) {
        val route: (KeyEvent) -> Boolean = { handleKey(it) }
        keys?.handle = route
        onDispose {
            if (keys?.handle === route) {
                keys.handle = null
                keys.editing = false
            }
        }
    }
    LaunchedEffect(searchOpen, focusRequest) {
        if (searchOpen) searchFocus.requestFocus() else keys?.editing = false
    }
    LaunchedEffect(query, searchOpen, pages, restart) {
        matches = emptyList()
        activeMatch = -1
        scanned = 0
        searchIssues = 0
        issueDetails = emptyList()
        capped = false
        stopped = false
        if (!searchOpen || query.isBlank() || pages == 0) {
            searching = false
            return@LaunchedEffect
        }
        val job = currentCoroutineContext().job
        searchJob = job
        searching = true
        try {
            delay(220)
            searchPdfPages(
                pages,
                query,
                { session.text(it) },
                publish = { batch ->
                    matches = batch.matches
                    scanned = batch.scanned
                    searchIssues = batch.issueCount
                    issueDetails = batch.issues
                    capped = batch.capped
                    if (activeMatch < 0 && matches.isNotEmpty()) {
                        activeMatch = 0
                        page = matches.first().page
                    }
                },
            )
        } finally {
            if (searchJob === job) {
                searching = false
                searchJob = null
            }
        }
    }
    val image by
        produceState<Result<Bitmap>?>(null, session, page, pages) {
            value = null
            value =
                if (pages > 0) pdfResult { session.render(page.coerceIn(0, pages - 1)) } else null
        }
    val text by
        produceState<Result<PdfPageText>?>(null, session, page, readText, query, pages) {
            value = null
            value =
                if (pages > 0 && (readText || query.isNotBlank()))
                    pdfResult { session.text(page.coerceIn(0, pages - 1)) }
                else null
        }
    val contents by
        produceState<Result<PdfContents>?>(null, session, rail) {
            if (rail == "contents") value = pdfResult { session.outline() }
        }
    val selected = matches.getOrNull(activeMatch)?.takeIf { it.page == page }
    val pageMatches = remember(matches, page) { matches.filter { it.page == page } }
    val markers by
        produceState(
            emptyList<RectF>() to emptyList<RectF>(),
            text,
            pageMatches,
            selected,
            searchOpen,
        ) {
            value = emptyList<RectF>() to emptyList()
            if (searchOpen)
                text?.getOrNull()?.let { content ->
                    value =
                        withContext(Dispatchers.Default) {
                            pdfMatchBounds(content, pageMatches) to
                                pdfMatchBounds(content, listOfNotNull(selected))
                        }
                }
        }
    val (highlights, activeBounds) = markers
    Column(modifier.testTag("pdf-preview")) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(
                Icons.AutoMirrored.Outlined.ViewSidebar,
                "PDF pages and bookmarks",
                { sidebar = !sidebar },
                active = sidebar,
            )
            Text(
                if (readText) "Page text" else "Document",
                Modifier.weight(1f).padding(start = 4.dp),
                color = Muted,
                fontSize = 12.sp,
            )
            ToolButton(
                Icons.Outlined.Search,
                "Find in PDF",
                ::find,
                enabled = pages > 0,
                active = searchOpen,
            )
            ToolButton(
                Icons.AutoMirrored.Outlined.TextSnippet,
                if (readText) "Show PDF page" else "Read PDF text",
                { readText = !readText },
                enabled = pages > 0,
                active = readText,
            )
            ToolButton(
                if (page in bookmarks) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                if (page in bookmarks) "Remove page bookmark" else "Bookmark this page",
                {
                    bookmarks =
                        if (page in bookmarks) bookmarks - page
                        else (bookmarks + page).take(500).toSet()
                },
                enabled = pages > 0 && (page in bookmarks || bookmarks.size < 500),
                active = page in bookmarks,
            )
        }
        if (searchOpen) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    query,
                    { query = it },
                    placeholder = { Text("Find in document", fontSize = 12.sp) },
                    singleLine = true,
                    modifier =
                        Modifier.weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("pdf-search")
                            .focusRequester(searchFocus)
                            .onFocusChanged { keys?.editing = it.isFocused },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions =
                        KeyboardActions(
                            onSearch = {
                                keyboard?.hide()
                                focusManager.clearFocus()
                            },
                        ),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                )
                Text(
                    if (matches.isEmpty()) "0" else "${activeMatch + 1}/${matches.size}",
                    Modifier.padding(horizontal = 8.dp),
                    fontSize = 11.sp,
                    color = Muted,
                )
                ToolButton(
                    Icons.Outlined.KeyboardArrowUp,
                    "Previous PDF match",
                    { selectMatch(activeMatch - 1, true) },
                    enabled = matches.isNotEmpty(),
                )
                ToolButton(
                    Icons.Outlined.KeyboardArrowDown,
                    "Next PDF match",
                    { selectMatch(activeMatch + 1, true) },
                    enabled = matches.isNotEmpty(),
                )
                ToolButton(Icons.Outlined.Close, "Close PDF search", { searchOpen = false })
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        query.isBlank() -> "Enter text to search this PDF"
                        searching -> "Searching · $scanned of $pages pages"
                        stopped -> "Stopped · $scanned of $pages pages searched"
                        capped -> "First 2,000 matches · refine the search"
                        searchIssues > 0 ->
                            if (searchIssues == 1) "1 page could not be fully searched"
                            else "$searchIssues pages could not be fully searched"
                        matches.isEmpty() ->
                            "No text matches in $pages ${if (pages == 1) "page" else "pages"}"
                        else ->
                            "${matches.size} ${if (matches.size == 1) "match" else "matches"} in $pages ${if (pages == 1) "page" else "pages"}"
                    },
                    Modifier.weight(1f),
                    color = Muted,
                    fontSize = 11.sp,
                )
                if (searchIssues > 0)
                    TextButton(onClick = { showIssues = true }) {
                        Text("Details", fontSize = 11.sp)
                    }
                if (searching)
                    TextButton(
                        onClick = {
                            stopped = true
                            searchJob?.cancel()
                            searching = false
                        },
                    ) {
                        Text("Stop", fontSize = 11.sp)
                    }
                else if (stopped || searchIssues > 0)
                    TextButton(onClick = { restart++ }) { Text("Restart", fontSize = 11.sp) }
            }
        }
        HorizontalDivider(color = Border, thickness = .5.dp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val narrow = maxWidth < 700.dp
            val availableWidth = maxWidth
            val showRail = sidebar
            Row(Modifier.fillMaxSize()) {
                if (showRail) {
                    val showMatches = searchOpen && query.isNotBlank()
                    Column(
                        Modifier.width(if (compactNavigation) availableWidth else if (narrow) 144.dp else if (showMatches) 220.dp else 176.dp)
                            .fillMaxHeight()
                            .background(SubtleSurface),
                    ) {
                        if (!showMatches)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                ToolButton(
                                    Icons.Outlined.ViewModule,
                                    "PDF thumbnails",
                                    { rail = "pages" },
                                    active = rail == "pages",
                                )
                                ToolButton(
                                    Icons.Outlined.Bookmarks,
                                    "Saved PDF bookmarks",
                                    { rail = "bookmarks" },
                                    active = rail == "bookmarks",
                                )
                                ToolButton(
                                    Icons.AutoMirrored.Outlined.FormatListBulleted,
                                    "PDF document contents",
                                    { rail = "contents" },
                                    active = rail == "contents",
                                )
                            }
                        when {
                            showMatches ->
                                LazyColumn(Modifier.fillMaxSize().testTag("pdf-matches")) {
                                    items(matches.withIndex().toList(), key = { it.index }) {
                                        (index, match) ->
                                        Column(
                                            Modifier.fillMaxWidth()
                                                .background(
                                                    if (index == activeMatch) SelectionSurface
                                                    else Color.Transparent,
                                                )
                                                .clickable { selectMatch(index, true) }
                                                .padding(12.dp),
                                        ) {
                                            Text(
                                                "Page ${match.page + 1}",
                                                fontSize = 11.sp,
                                                color = Ink,
                                            )
                                            Text(
                                                match.snippet,
                                                fontSize = 11.sp,
                                                lineHeight = 16.sp,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                color = Muted,
                                                modifier = Modifier.padding(top = 4.dp),
                                            )
                                        }
                                    }
                                }
                            rail == "pages" -> {
                                val state = rememberLazyListState()
                                LaunchedEffect(page, pages) {
                                    if (pages > 0)
                                        state.animateScrollToItem(page.coerceIn(0, pages - 1))
                                }
                                LazyColumn(
                                    state = state,
                                    modifier = Modifier.fillMaxSize().testTag("pdf-thumbnails"),
                                ) {
                                    items(pages, key = { it }) { at ->
                                        PdfThumbnail(session, at, page == at) {
                                            page = at
                                            if (compactNavigation) sidebar = false
                                        }
                                    }
                                }
                            }
                            rail == "bookmarks" -> {
                                if (bookmarks.isEmpty())
                                    Text(
                                        "Bookmark pages to return to them here.",
                                        Modifier.padding(14.dp),
                                        color = Muted,
                                        fontSize = 11.sp,
                                        lineHeight = 17.sp,
                                    )
                                LazyColumn(Modifier.fillMaxSize().testTag("pdf-bookmarks")) {
                                    items(bookmarks.sorted(), key = { it }) { at ->
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .background(
                                                    if (page == at) SelectionSurface
                                                    else Color.Transparent,
                                                ),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            TextButton(
                                                onClick = { page = at; if (compactNavigation) sidebar = false },
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text("Page ${at + 1}", fontSize = 12.sp)
                                            }
                                            ToolButton(
                                                Icons.Outlined.Close,
                                                "Remove bookmark for page ${at + 1}",
                                                { bookmarks = bookmarks - at },
                                                compact = true,
                                            )
                                        }
                                    }
                                }
                            }
                            else -> {
                                val outline = contents?.getOrNull()?.items
                                if (contents == null)
                                    Box(
                                        Modifier.fillMaxWidth().padding(20.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                else if (contents!!.isFailure)
                                    Text(
                                        contents!!.exceptionOrNull()?.message
                                            ?: "Contents unavailable",
                                        Modifier.padding(14.dp),
                                        color = Muted,
                                        fontSize = 11.sp,
                                    )
                                else if (outline.isNullOrEmpty())
                                    Text(
                                        "This PDF has no internal page links.",
                                        Modifier.padding(14.dp),
                                        color = Muted,
                                        fontSize = 11.sp,
                                    )
                                if (contents?.getOrNull()?.limited == true)
                                    Text(
                                        "Some contents could not be shown",
                                        Modifier.padding(12.dp),
                                        color = Muted,
                                        fontSize = 11.sp,
                                    )
                                LazyColumn(Modifier.fillMaxSize().testTag("pdf-contents")) {
                                    items(outline.orEmpty()) { item ->
                                        Text(
                                            item.title,
                                            Modifier.fillMaxWidth()
                                                .background(
                                                    if (page == item.page) SelectionSurface
                                                    else Color.Transparent,
                                                )
                                                .clickable { page = item.page; if (compactNavigation) sidebar = false }
                                                .padding(
                                                    start =
                                                        (12 + item.depth * 10).coerceAtMost(80).dp,
                                                    end = 10.dp,
                                                    top = 12.dp,
                                                    bottom = 12.dp,
                                                ),
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp,
                                            color = Ink,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    VerticalDivider(color = Border, thickness = .5.dp)
                }
                if (!compactNavigation || !sidebar) Box(
                    Modifier.weight(1f).fillMaxHeight().background(SubtleSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        opening?.isFailure == true ->
                            Text(
                                "Couldn't open this PDF. " +
                                    (opening!!.exceptionOrNull()?.message
                                        ?: "Try opening it with another app."),
                                Modifier.padding(20.dp),
                                color = Muted,
                            )
                        readText ->
                            PdfSelectableText(
                                text,
                                matches.filter { searchOpen && it.page == page },
                                entry,
                            )
                        image?.isSuccess == true ->
                            ZoomableBitmap(
                                image!!.getOrThrow(),
                                "PDF page ${page + 1}",
                                Modifier.fillMaxSize().padding(12.dp),
                                controls = false,
                                highlights = highlights,
                                activeHighlights = activeBounds,
                                stateKey = sourcePath,
                                zoomState = zoomState,
                                paper = true,
                            )
                        image?.isFailure == true ->
                            Text(
                                image!!.exceptionOrNull()?.message ?: "Couldn't render this page",
                                Modifier.padding(20.dp),
                                color = Muted,
                            )
                        else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
        HorizontalDivider(color = Border, thickness = .5.dp)
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(
                Icons.Outlined.FirstPage,
                "First PDF page",
                { page = 0 },
                enabled = page > 0 && pages > 0,
            )
            ToolButton(
                Icons.Outlined.ChevronLeft,
                "Previous PDF page",
                { page-- },
                enabled = page > 0 && pages > 0,
            )
            TextButton(onClick = { pageJump = true }, enabled = pages > 0) {
                Text(
                    if (pages > 0) "Page ${page + 1} of $pages" else "Opening PDF…",
                    color = Ink,
                    fontSize = 12.sp,
                )
            }
            ToolButton(
                Icons.Outlined.ChevronRight,
                "Next PDF page",
                { page++ },
                enabled = page + 1 < pages,
            )
            ToolButton(
                Icons.AutoMirrored.Outlined.LastPage,
                "Last PDF page",
                { page = pages - 1 },
                enabled = page + 1 < pages,
            )
            Spacer(Modifier.weight(1f))
            if (!readText && !compactNavigation) BitmapZoomButtons(zoomState)
        }
        if (!readText && compactNavigation) Row(
            Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BitmapZoomButtons(zoomState)
        }
    }
    if (showIssues)
        AlertDialog(
            onDismissRequest = { showIssues = false },
            title = { Text("PDF search details") },
            text = {
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "$scanned of $pages pages checked · results are incomplete",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    issueDetails.forEach {
                        Text(
                            "Page ${it.page + 1}: ${it.reason}",
                            color = Ink,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    if (searchIssues > issueDetails.size)
                        Text(
                            "${searchIssues - issueDetails.size} more pages need attention",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                }
            },
            confirmButton = { TextButton(onClick = { showIssues = false }) { Text("Done") } },
        )
    if (pageJump) {
        var number by remember { mutableStateOf((page + 1).toString()) }
        val valid = number.toIntOrNull()?.takeIf { it in 1..pages }
        AlertDialog(
            onDismissRequest = { pageJump = false },
            title = { Text("Go to PDF page") },
            text = {
                OutlinedTextField(
                    number,
                    { number = it },
                    label = { Text("Page number (1–$pages)") },
                    singleLine = true,
                    modifier = Modifier.testTag("pdf-page-number"),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Go,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onGo = {
                                if (valid != null) {
                                    page = valid - 1
                                    pageJump = false
                                }
                            },
                        ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        page = valid!! - 1
                        pageJump = false
                    },
                    enabled = valid != null,
                ) {
                    Text("Go")
                }
            },
            dismissButton = { TextButton(onClick = { pageJump = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PdfThumbnail(
    session: PdfReadingSession,
    page: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val result by
        produceState<Result<Bitmap>?>(null, session, page) {
            value = pdfResult { session.render(page, thumbnail = true) }
        }
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) SelectionSurface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(12.dp)
            .testTag("pdf-thumb-$page"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.width(96.dp)
                .height(126.dp)
                .background(Color.White, RoundedCornerShape(3.dp))
                .border(
                    if (selected) 1.5.dp else .5.dp,
                    if (selected) Blue else Border,
                    RoundedCornerShape(3.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            val image = result?.getOrNull()
            if (image != null)
                Image(
                    image.asImageBitmap(),
                    "Thumbnail of PDF page ${page + 1}",
                    Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit,
                )
            else if (result?.isFailure == true)
                Icon(Icons.Outlined.BrokenImage, "Page thumbnail unavailable", tint = Muted)
            else CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
        }
        Text(
            "${page + 1}",
            Modifier.padding(top = 6.dp),
            color = if (selected) SelectedInk else Muted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PdfSelectableText(result: Result<PdfPageText>?, matches: List<PdfMatch>, entry: Entry) {
    val context = LocalContext.current
    val content = result?.getOrNull()
    if (result == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    else if (result.isFailure)
        Text(
            result.exceptionOrNull()?.message ?: "Text unavailable",
            Modifier.padding(20.dp),
            color = Muted,
        )
    else
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (content!!.limited) "Text is limited on this page"
                    else if (content.text.isBlank()) "This page has no searchable text layer"
                    else "Select text to copy a passage",
                    Modifier.weight(1f),
                    color = Muted,
                    fontSize = 11.sp,
                )
                ToolButton(
                    Icons.Outlined.ContentCopy,
                    "Copy PDF page text",
                    {
                        context
                            .getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(entry.name, content.text))
                    },
                    enabled = content.text.isNotBlank(),
                )
            }
            SelectionContainer(
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .testTag("pdf-page-text"),
            ) {
                Text(
                    buildAnnotatedString {
                        append(content!!.text)
                        matches.forEach {
                            if (it.start >= 0 && it.end <= length)
                                addStyle(
                                    SpanStyle(background = Color(0xFFFFD75E), color = Color.Black),
                                    it.start,
                                    it.end,
                                )
                        }
                    },
                    color = Ink,
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
}
