package dev.findroid

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileComparisonDialog(
    original: List<FileComparisonTarget>, gateway: DocumentAccess, allowed: (String) -> Boolean, dismiss: () -> Unit,
) {
    var reversed by remember { mutableStateOf(false) }
    val targets = remember(original, reversed) { if (reversed) original.reversed() else original }
    var attempt by remember { mutableIntStateOf(0) }
    val control = remember(targets, attempt) { ListingControl() }
    var result by remember(control) { mutableStateOf<FileComparisonResult?>(null) }
    var error by remember(control) { mutableStateOf<String?>(null) }
    var stopped by remember(control) { mutableStateOf(false) }
    var loading by remember(control) { mutableStateOf(true) }
    var active by remember(control) { mutableIntStateOf(0) }
    var copied by remember(control) { mutableStateOf(false) }
    var copying by remember(control) { mutableStateOf(false) }
    var limits by remember { mutableStateOf(false) }
    val zoom = remember(control) { ComparisonZoom() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val list = rememberLazyListState()
    DisposableEffect(control) { onDispose { control.cancel() } }
    LaunchedEffect(control) {
        var timedOut = false
        val timeout = launch { delay(FileComparisonPolicy.timeoutMillis); timedOut = true; control.cancel() }
        try { result = loadFileComparison(targets, gateway, allowed, control) }
        catch (failure: CancellationException) {
            if (!isActive) throw failure
            if (timedOut) error = "Comparison timed out. Try smaller files or reconnect the provider, then retry."
            else if (!stopped) throw failure
        } catch (failure: Exception) {
            if (!stopped) error = if (timedOut) "Comparison timed out. Try smaller files or reconnect the provider, then retry."
                else if (failure is java.nio.charset.CharacterCodingException) "Couldn't decode this text. Use UTF-8 or BOM-marked UTF-16 copies."
                else failure.message?.take(400) ?: "Couldn't compare these files."
        } catch (_: OutOfMemoryError) { error = "Not enough memory for this comparison. Close it and try smaller files." }
        finally { timeout.cancel(); loading = false }
    }
    val text = result?.text
    val shownTargets = result?.targets?.takeIf { it.size == targets.size } ?: targets
    LaunchedEffect(control, active, text) { text?.changes?.getOrNull(active)?.let { list.scrollToItem(it) } }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            val controlsHeight = maxHeight * .48f
            Surface(Modifier.widthIn(max = 1120.dp).fillMaxWidth().fillMaxHeight().testTag("file-comparison"),
                shape = RoundedCornerShape(14.dp), color = CanvasBackground, shadowElevation = 16.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Compare files", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Ink)
                        ToolButton(Icons.Outlined.Close, "Close file comparison", dismiss)
                    }
                    HorizontalDivider(color = Border)
                    Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight).verticalScroll(rememberScrollState()).testTag("file-comparison-controls")) {
                        shownTargets.forEachIndexed { index, target ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text("${if (index == 0) "Left" else "Right"} · ${target.name}", style = MaterialTheme.typography.titleSmall, color = Ink)
                                Text(target.location, style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                        FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { limits = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Comparison limits…") }
                            TextButton(onClick = { reversed = !reversed }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Swap sides") }
                            if (loading) TextButton(onClick = { stopped = true; control.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop comparison") }
                            else TextButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Compare again") }
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (stopped) Text("Comparison stopped. Retry with Compare again.", Modifier.padding(16.dp), color = Muted)
                        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                        if (text != null) {
                            Text(if (text.changes.isEmpty()) "No text differences" else "${text.changes.size} difference ${if (text.changes.size == 1) "group" else "groups"} · ${text.removed} removed · ${text.added} added",
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("file-comparison-summary"), color = Ink)
                            Text("Left: ${text.left.encoding} · Right: ${text.right.encoding} · Exact whitespace and line endings",
                                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                            FlowRow(Modifier.padding(horizontal = 8.dp)) {
                                TextButton(onClick = { active-- }, enabled = active > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous difference") }
                                TextButton(onClick = { active++ }, enabled = active < text.changes.lastIndex, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next difference") }
                                Text(if (text.changes.isEmpty()) "0 of 0" else "${active + 1} of ${text.changes.size}", Modifier.padding(16.dp), color = Muted)
                                TextButton(onClick = {
                                    scope.launch {
                                        copying = true
                                        try {
                                            val report = withContext(Dispatchers.Default) { text.report(shownTargets[0].name, shownTargets[1].name) }
                                            copyPreviewText(context, "File comparison", report); copied = true
                                        } catch (failure: CancellationException) { throw failure }
                                        catch (_: Exception) { error = "Couldn't copy the comparison report." }
                                        finally { copying = false }
                                    }
                                }, enabled = !copying, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy differences") }
                            }
                            if (copied) Text("Copied comparison report", Modifier.padding(horizontal = 16.dp), color = Muted)
                        }
                        if (result?.images?.size == 2) {
                            val scaleLabel = java.lang.String.format(java.util.Locale.ROOT, "%.1f", zoom.zoom).removeSuffix(".0")
                            Text("Linked zoom · ${scaleLabel}× · Drag either image to pan both", Modifier.padding(horizontal = 16.dp), color = Ink)
                            Text("Sampled previews; visual review does not prove file equality.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                            FlowRow(Modifier.padding(horizontal = 8.dp)) {
                                ToolButton(Icons.Outlined.Remove, "Zoom both images out", { zoom.zoom = (zoom.zoom - 1).coerceAtLeast(1f) }, enabled = zoom.zoom > 1)
                                TextButton(onClick = { zoom.zoom = 1f; zoom.pan = Offset.Zero }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fit both images") }
                                ToolButton(Icons.Outlined.Add, "Zoom both images in", { zoom.zoom = (zoom.zoom + 1).coerceAtMost(4f) }, enabled = zoom.zoom < 4)
                                ToolButton(Icons.Outlined.ArrowBack, "Pan both images left", { zoom.move(.25f, 0f) }, enabled = zoom.zoom > 1)
                                ToolButton(Icons.Outlined.ArrowForward, "Pan both images right", { zoom.move(-.25f, 0f) }, enabled = zoom.zoom > 1)
                                ToolButton(Icons.Outlined.ArrowUpward, "Pan both images up", { zoom.move(0f, .25f) }, enabled = zoom.zoom > 1)
                                ToolButton(Icons.Outlined.ArrowDownward, "Pan both images down", { zoom.move(0f, -.25f) }, enabled = zoom.zoom > 1)
                            }
                        }
                    }
                    HorizontalDivider(color = Border)
                    if (text != null) {
                        SelectionContainer(Modifier.weight(1f)) {
                            LazyColumn(Modifier.fillMaxSize().testTag("text-differences"), state = list) {
                                if (text.rows.isEmpty()) item { Text("Both text files are empty.", Modifier.padding(16.dp), color = Muted) }
                                itemsIndexed(text.rows) { index, row ->
                                    Row(Modifier.fillMaxWidth().background(if (row.changed) SelectionSurface else CanvasBackground)
                                        .border(if (text.changes.getOrNull(active) == index) 1.dp else 0.dp, if (row.changed) Blue else Border)
                                        .testTag("difference-row-$index")) {
                                        DifferenceCell(text.left, row.left, "L", row.changed, Modifier.weight(1f))
                                        DifferenceCell(text.right, row.right, "R", row.changed, Modifier.weight(1f))
                                    }
                                    HorizontalDivider(color = Border)
                                }
                            }
                        }
                    } else if (result?.images?.size == 2) {
                        Row(Modifier.weight(1f).fillMaxWidth().testTag("image-comparison")) {
                            result!!.images.forEachIndexed { index, image ->
                                Column(Modifier.weight(1f).fillMaxHeight()) {
                                    Text("${if (index == 0) "Left" else "Right"} · ${image.width} × ${image.height}${if (image.sampled) " · sampled" else ""}",
                                        Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = Ink)
                                    ComparisonImagePane(image, if (index == 0) "Left image" else "Right image", zoom, Modifier.weight(1f).fillMaxWidth())
                                }
                            }
                        }
                    } else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    if (limits) AlertDialog(onDismissRequest = { limits = false }, title = { Text("File comparison limits") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text("Read-only comparison; files aren't edited or merged. Each request reopens both files. Results are snapshots; compare again after external edits.\n\nText: complete UTF-8 or BOM-marked UTF-16 files up to 512 KiB, 2,000 lines and 8,192 characters per line. Spaces, tabs, case and line endings matter. Encoding/BOM differences are labelled separately. Limits refuse the comparison rather than comparing truncated prefixes.\n\nImages: supported Android raster decoders, first frame only, up to 64 MiB each. EXIF orientation is applied. Each image uses at most 1,048,576 decoded pixels and a 2,048-pixel edge. Zoom magnifies these previews; it is not a pixel-perfect or color-managed comparison. Normalized pan links corresponding regions of differently sized images. No image equality, checksum or metadata comparison is implied.\n\nPreparation stops after 15 seconds. Stop and closing cancel provider reads. Office documents, PDFs, binary files and mixed text/image pairs aren't supported.")
        }
    }, confirmButton = { TextButton(onClick = { limits = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close comparison limits") } })
}

@Composable
private fun DifferenceCell(text: ComparisonText, index: Int?, side: String, changed: Boolean, modifier: Modifier) {
    Column(modifier.padding(12.dp)) {
        if (index == null) Text("—", color = Muted)
        else {
            val line = text.lines[index]
            Text("$side ${index + 1}${if (changed) if (side == "L") " · Removed" else " · Added" else ""} · ${line.endingLabel}",
                color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(line.text.replace("\t", "⇥  ").ifEmpty { " " }, color = Ink, fontFamily = FontFamily.Monospace,
                fontWeight = if (changed) FontWeight.Medium else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private class ComparisonZoom {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    fun move(x: Float, y: Float) { pan = Offset((pan.x + x).coerceIn(-1f, 1f), (pan.y + y).coerceIn(-1f, 1f)) }
}

@Composable
private fun ComparisonImagePane(image: ComparisonImage, label: String, zoom: ComparisonZoom, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { scale, offset, _ ->
        zoom.zoom = (zoom.zoom * scale).coerceIn(1f, 4f)
        if (zoom.zoom == 1f) zoom.pan = Offset.Zero
        else {
            val fit = minOf(size.width.toFloat() / image.bitmap.width, size.height.toFloat() / image.bitmap.height)
            val overflowX = ((image.bitmap.width * fit * zoom.zoom - size.width) / 2).coerceAtLeast(0f)
            val overflowY = ((image.bitmap.height * fit * zoom.zoom - size.height) / 2).coerceAtLeast(0f)
            // The image under the finger moves by the finger's distance at every zoom level.
            // The other pane follows the same normalized position within its own overflow.
            zoom.move(if (overflowX > 0) offset.x / overflowX else 0f, if (overflowY > 0) offset.y / overflowY else 0f)
        }
    }
    val fit = minOf(size.width.toFloat() / image.bitmap.width, size.height.toFloat() / image.bitmap.height)
    val overflowX = ((image.bitmap.width * fit * zoom.zoom - size.width) / 2).coerceAtLeast(0f)
    val overflowY = ((image.bitmap.height * fit * zoom.zoom - size.height) / 2).coerceAtLeast(0f)
    Box(modifier.background(SubtleSurface).border(1.dp, Border).clipToBounds().onSizeChanged { size = it }.transformable(transform)) {
        Image(image.bitmap.asImageBitmap(), label, Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom.zoom; scaleY = zoom.zoom
            translationX = zoom.pan.x * overflowX; translationY = zoom.pan.y * overflowY
        }, contentScale = ContentScale.Fit)
    }
}
