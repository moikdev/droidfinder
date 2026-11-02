package dev.findroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** One search field behavior for tablet chrome and the existing compact fallback. */
@Composable
internal fun SearchQueryField(
    model: FinderModel,
    modifier: Modifier,
    fieldModifier: Modifier,
    textStyle: TextStyle,
    onFocused: (Boolean) -> Unit,
    action: (String) -> Unit,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var dismissedQuery by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, focused) {
        // Android dialogs/popups can take the window focus without changing Compose's
        // field focus. Treat that as leaving the edited query as well.
        if (focused && !windowFocused) {
            dismissedQuery = model.query
            model.rememberCurrentSearch()
        }
    }
    val suggestions = recentSearchSuggestions(model.recentSearchHistory.state.searches, model.query)
    var selected by remember(model.query, suggestions.map { it.id }) { mutableIntStateOf(-1) }
    val expanded = focused && windowFocused && !applying && model.query.isNotBlank() && dismissedQuery != model.query && suggestions.isNotEmpty()
    DisposableEffect(model) { onDispose { model.rememberCurrentSearch() } }
    fun submit() {
        model.submitSearchQuery()
        dismissedQuery = model.query
        focus.clearFocus()
        keyboard?.hide()
    }
    fun choose(search: RecentSearch) {
        // Choosing a completed suggestion replaces the typed prefix; don't save the prefix.
        model.discardSearchDraft()
        dismissedQuery = model.query
        applying = true
        focus.clearFocus()
        keyboard?.hide()
        scope.launch {
            try { model.reuseRecentSearch(search, here = true)?.let { model.message = it } }
            finally { applying = false }
        }
    }
    Box(modifier) {
        BasicTextField(
            value = model.query,
            onValueChange = model::updateQuery,
            modifier = fieldModifier.onFocusChanged {
                if (focused && !it.isFocused) model.rememberCurrentSearch()
                if (!focused && it.isFocused) dismissedQuery = null
                focused = it.isFocused
                onFocused(it.isFocused)
            }.semantics { contentDescription = "Search files" }.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed || event.isShiftPressed) false
                else when {
                    expanded && event.key == Key.DirectionDown -> { selected = (selected + 1).coerceAtMost(suggestions.lastIndex); true }
                    expanded && event.key == Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(-1); true }
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        if (expanded && selected in suggestions.indices) choose(suggestions[selected]) else submit()
                        true
                    }
                    else -> false
                }
            },
            enabled = !applying,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(Blue),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            decorationBox = decorationBox,
        )
        DropdownMenu(expanded, { dismissedQuery = model.query }, properties = PopupProperties(focusable = false)) {
            Text("Recent searches · Search here", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = Muted)
            suggestions.forEachIndexed { index, search ->
                DropdownMenuItem(
                    text = { Column(Modifier.widthIn(max = 440.dp)) {
                        Text(search.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(search.summary, fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } },
                    onClick = { choose(search) },
                    modifier = Modifier.testTag("recent-search-suggestion-${search.id}")
                        .background(if (selected == index) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .semantics { this.selected = selected == index; contentDescription = "Search here: ${search.title}" },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Recent searches…") }, onClick = { submit(); action("recentSearches") })
        }
        if (applying) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = "Opening recent search" })
    }
}

private fun recentRootLabel(model: FinderModel, root: String): String =
    if (root.startsWith("@")) model.title(root) else model.displayPath(root)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecentSearchesDialog(model: FinderModel, action: (String) -> Unit, dismiss: () -> Unit) {
    val history = model.recentSearchHistory
    val state = history.state
    var filter by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var opening by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val height = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height.toDp() - 48.dp).coerceAtLeast(240.dp) }
    val here = model.recentSearchRoot()
    val choices = state.searches.filter { recentSearchMatches(it, filter) }
    fun reuse(search: RecentSearch, useHere: Boolean) {
        opening = search.id
        error = null
        scope.launch {
            try {
                val issue = model.reuseRecentSearch(search, useHere)
                if (issue == null) dismiss() else error = issue
            } finally { opening = null }
        }
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 780.dp).fillMaxWidth(.94f).heightIn(max = height).testTag("recent-searches"), shape = MaterialTheme.shapes.extraLarge) {
            Column {
                Text("Recent searches", Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("recent-searches-list"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item(key = "controls") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Reuse the full query and filters here, or search the original location. Exact exclusions keep their saved locations.", fontSize = 13.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Remember searches", fontWeight = FontWeight.Medium)
                                Text("Up to 20 on this tablet. Turn off to pause new entries. History is excluded from setup backups.", fontSize = 12.sp, color = Muted)
                            }
                            Switch(state.enabled, { history.setEnabled(it) }, Modifier.testTag("remember-recent-searches").semantics { contentDescription = "Remember searches" })
                        }
                        Text(if (here == null) "Open a folder or library to search here." else "Search here: ${recentRootLabel(model, here)}", fontSize = 13.sp)
                        if (!state.enabled) Text("Recording is paused. Existing searches can still be reused or removed.", fontSize = 13.sp)
                        OutlinedTextField(filter, { filter = it }, label = { Text("Filter recent searches") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("recent-search-filter"))
                        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("recent-search-notice")) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("recent-search-error")) }
                        if (error != null) TextButton({ dismiss(); action("storage") }, Modifier.testTag("recent-search-storage")) { Text("Storage locations…") }
                        if (opening != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Opening search…", fontSize = 13.sp) }
                    } }
                    if (choices.isEmpty()) item(key = "empty") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.searches.isEmpty()) "No recent searches" else "No matching recent searches", fontWeight = FontWeight.Medium)
                        Text(if (state.searches.isNotEmpty()) "Try another name, filter or location."
                            else if (state.enabled) "Apply filters, finish a query, or leave an edited search to add it here."
                            else "Turn on Remember searches to save the next searches you use.", fontSize = 13.sp, color = Muted)
                    } }
                    items(choices, key = { it.id }) { search -> Column(Modifier.fillMaxWidth().testTag("recent-search-${search.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(search.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${model.title(search.root)} · ${dates.format(Date(search.usedAt))}", fontSize = 12.sp, color = Muted)
                        Text(search.summary, fontSize = 13.sp, maxLines = if (search.id in expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                        if (search.id in expanded) {
                            Text("Original location: ${recentRootLabel(model, search.root)}", fontSize = 13.sp)
                            if (search.rules.excludedFolderNames.isNotEmpty())
                                Text("Excluded folder names: ${search.rules.excludedFolderNames.sorted().joinToString(", ")}", fontSize = 13.sp)
                            search.rules.excludedLocations.forEach { exclusion -> Text("Excluded ${if (exclusion.folder) "folder" else "file"}: ${recentRootLabel(model, exclusion.path)}", fontSize = 13.sp) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ reuse(search, true) }, enabled = here != null && opening == null, modifier = Modifier.testTag("recent-search-here-${search.id}")) { Text("Search here") }
                            OutlinedButton({ reuse(search, false) }, enabled = opening == null, modifier = Modifier.testTag("recent-search-original-${search.id}")) { Text("Search original location") }
                            TextButton({ expanded = if (search.id in expanded) expanded - search.id else expanded + search.id }, Modifier.testTag("recent-search-details-${search.id}")) { Text(if (search.id in expanded) "Hide filters" else "Show filters") }
                            TextButton({ history.remove(search.id) }, Modifier.testTag("recent-search-remove-${search.id}")) { Text("Remove") }
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    } }
                }
                HorizontalDivider()
                FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ history.clear() }, enabled = state.searches.isNotEmpty() || state.notice != null, modifier = Modifier.testTag("clear-recent-searches")) { Text("Clear history") }
                    TextButton(dismiss) { Text("Close") }
                }
            }
        }
    }
}
