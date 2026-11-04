package dev.findroid

import android.view.KeyEvent as AndroidKeyEvent
import android.view.Window
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageReviewDialog(model: FinderModel) {
    val state = model.imageReview
    val review = state.selection ?: return
    val entry = state.active ?: return
    var comparison by remember(state) { mutableStateOf<List<FileComparisonTarget>?>(null) }
    val focus = remember { FocusRequester() }
    val keys = remember(entry.path) { PeekKeys() }
    val thumbnails = rememberLazyListState()
    LaunchedEffect(review.active, review.flaggedOnly) { thumbnails.animateScrollToItem(review.index) }
    Dialog(state::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val handle by rememberUpdatedState<(AndroidKeyEvent) -> Boolean>({ event ->
            when {
                event.action != AndroidKeyEvent.ACTION_DOWN || comparison != null || model.organization.visible -> false
                keys.handle?.invoke(event) == true -> true
                keys.editing || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed || event.isShiftPressed -> false
                event.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE -> { state.close(); true }
                event.keyCode == AndroidKeyEvent.KEYCODE_F -> { if (event.repeatCount == 0) state.update { it.toggleFlag() }; true }
                event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { state.update { it.step(-1) }; true }
                event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { state.update { it.step(1) }; true }
                event.keyCode == AndroidKeyEvent.KEYCODE_MOVE_HOME -> { state.update { it.step(Int.MIN_VALUE) }; true }
                event.keyCode == AndroidKeyEvent.KEYCODE_MOVE_END -> { state.update { it.step(Int.MAX_VALUE) }; true }
                else -> false
            }
        })
        DisposableEffect(window) {
            val previous = window?.callback
            val callback = previous?.let { object : Window.Callback by it {
                override fun dispatchKeyEvent(event: AndroidKeyEvent) = handle(event) || previous.dispatchKeyEvent(event)
            } }
            if (window != null && callback != null) window.callback = callback
            onDispose { if (window?.callback === callback) window?.callback = previous }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            val controlsHeight = maxHeight * .28f
            Surface(Modifier.widthIn(max = 1200.dp).fillMaxSize().testTag("image-review")
                .semantics { paneTitle = "Review images" }.focusRequester(focus)
                .onPreviewKeyEvent { handle(it.nativeKeyEvent) }.focusable(),
                shape = RoundedCornerShape(14.dp), color = CanvasBackground, shadowElevation = 16.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Review images", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Ink)
                        ToolButton(Icons.Outlined.Close, "Close image review", state::close)
                    }
                    HorizontalDivider(color = Border)
                    Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight).verticalScroll(rememberScrollState()).testTag("image-review-controls")) {
                        Text("${review.index + 1} of ${review.shown.size}${if (review.flaggedOnly) " flagged" else " images"} · ${review.flagged.size} flagged",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("image-review-summary"), color = Ink)
                        Text(entry.name, Modifier.padding(horizontal = 16.dp).testTag("image-review-name"), style = MaterialTheme.typography.titleSmall, color = Ink)
                        Text(model.displayPath(entry.parentPath ?: entry.path), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                        FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { state.update { it.step(-1) } }, enabled = review.index > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous image") }
                            TextButton(onClick = { state.update { it.step(1) } }, enabled = review.index < review.shown.lastIndex, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next image") }
                            TextButton(onClick = { state.update { it.toggleFlag() } }, modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (entry.path in review.flagged) "Flagged" else "Not flagged" }) {
                                Icon(if (entry.path in review.flagged) Icons.Outlined.Flag else Icons.Outlined.OutlinedFlag, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text(if (entry.path in review.flagged) "Unflag image" else "Flag image")
                            }
                            TextButton(onClick = { state.update { it.filter(!it.flaggedOnly) } }, enabled = review.flagged.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (review.flaggedOnly) "Show all images" else "Show flagged only")
                            }
                            TextButton(onClick = { model.organization.open(state.flaggedEntries) }, enabled = review.flagged.isNotEmpty() && model.busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tag flagged…") }
                            TextButton(onClick = { state.update { it.clearFlags() } }, enabled = review.flagged.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear flags") }
                            TextButton(onClick = { state.update { it.pinReference() } }, enabled = review.reference != entry.path, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use as reference") }
                            TextButton(onClick = {
                                comparison = review.comparison?.map { path -> state.entries.first { it.path == path }.let {
                                    FileComparisonTarget(it.path, it.name, model.displayPath(it.parentPath ?: it.path))
                                } }
                            }, enabled = review.comparison != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Compare with reference…") }
                            if (review.reference != null)
                                TextButton(onClick = { state.update { it.clearReference() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear reference") }
                        }
                        review.reference?.let { path -> Text("Reference: ${state.entries.first { it.path == path }.name}", Modifier.padding(horizontal = 16.dp), color = Muted, style = MaterialTheme.typography.bodySmall) }
                        Text("Flags last until this review closes. Tag flagged opens a review before saving. ← / → browse · F flag · Shift + arrows pan.",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = Border)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Only the active image owns a full viewer/decoder. Child dialogs release it.
                        if (comparison == null && !model.organization.visible) key(entry.path) {
                            CompositionLocalProvider(LocalPeekKeys provides keys) { ImageQuickLook(entry, Modifier.fillMaxSize()) }
                        }
                    }
                    HorizontalDivider(color = Border)
                    LazyRow(Modifier.fillMaxWidth().height(92.dp).testTag("image-review-thumbnails"), state = thumbnails, contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.entries.filter { it.path in review.shown }, key = { it.path }) { image ->
                            Column(Modifier.width(120.dp).fillMaxHeight().background(if (image.path == entry.path) SelectionSurface else SubtleSurface, RoundedCornerShape(6.dp))
                                .border(1.dp, if (image.path == entry.path) Blue else Border, RoundedCornerShape(6.dp))
                                .clickable { state.update { it.select(image.path) } }.semantics(mergeDescendants = true) {
                                    contentDescription = "Review ${image.name}"
                                    selected = image.path == entry.path
                                    stateDescription = buildList { add(if (image.path in review.flagged) "Flagged" else "Not flagged"); if (image.path == review.reference) add("Reference") }.joinToString(" · ")
                                }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    FileGlyph(image, 48.dp)
                                    if (image.path in review.flagged) Icon(Icons.Outlined.Flag, null, Modifier.size(18.dp).align(Alignment.TopEnd), tint = Blue)
                                }
                                Text(image.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
    comparison?.let { FileComparisonDialog(it, model.documents, model::allowedPath) { comparison = null } }
}
