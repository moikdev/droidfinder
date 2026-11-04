package dev.findroid

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
internal fun ImageQuickLook(entry: Entry, modifier: Modifier = Modifier) {
    ProtectPreviewCache(entry)
    val context = LocalContext.current
    val gateway = remember { DocumentAccess(context.applicationContext) }
    val identity = listOf(entry.path, entry.name, entry.size, entry.modified, entry.mimeType)
    var retry by remember(identity) { mutableIntStateOf(0) }
    val control = remember(identity, retry) { ListingControl() }
    var stopped by remember(control) { mutableStateOf(false) }
    DisposableEffect(control) { onDispose { control.cancel() } }
    val result by
        key(control) {
            produceState<Result<ImageSource>?>(null, control) {
                value = null
                var opened: ImageSource? = null
                try {
                    val file =
                        withContext(Dispatchers.IO) {
                            if (entry.documentUri != null) gateway.materialize(entry, control)
                            else entry.file
                        }
                    opened = ImageSource.open(file, control)
                    value = Result.success(opened)
                    awaitDispose { opened.close() }
                } catch (error: Exception) {
                    opened?.close()
                    if (error is CancellationException) return@produceState
                    if (!stopped) value = Result.failure(error)
                } catch (_: OutOfMemoryError) {
                    opened?.close()
                    value =
                        Result.failure(
                            IllegalStateException(
                                "Not enough memory for this preview. Close another preview and retry.",
                            ),
                        )
                }
            }
        }
    val source = result?.getOrNull()
    if (source == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                if (result == null && !stopped)
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(
                    if (stopped) "Image loading stopped"
                    else result?.exceptionOrNull()?.message ?: "Preparing image…",
                    Modifier.padding(top = 12.dp),
                    color = Muted,
                    fontSize = 12.sp,
                )
                if (result == null && !stopped)
                    TextButton(
                        onClick = {
                            stopped = true
                            control.cancel()
                        },
                    ) {
                        Text("Stop loading image")
                    }
                else TextButton(onClick = { retry++ }) { Text("Retry image preview") }
            }
        }
        return
    }
    key(identity) { ImageReader(source, entry, modifier) { retry++ } }
}

@OptIn(kotlinx.coroutines.FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
private fun ImageReader(source: ImageSource, entry: Entry, modifier: Modifier, reload: () -> Unit) {
    val savedIdentity = listOf(entry.path, entry.name, entry.modified, entry.size)
    var absolute by rememberSaveable(*savedIdentity.toTypedArray()) { mutableDoubleStateOf(0.0) }
    var cx by rememberSaveable(*savedIdentity.toTypedArray()) { mutableDoubleStateOf(.5) }
    var cy by rememberSaveable(*savedIdentity.toTypedArray()) { mutableDoubleStateOf(.5) }
    var turns by rememberSaveable(*savedIdentity.toTypedArray()) { mutableIntStateOf(0) }
    var showInfo by rememberSaveable(*savedIdentity.toTypedArray()) { mutableStateOf(false) }
    var showOcr by remember(source) { mutableStateOf(false) }
    var revealLocation by remember(source) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize(1, 1)) }
    var menu by remember { mutableStateOf(false) }
    val geometry =
        remember(source, turns) {
            ImageGeometry(source.width, source.height, source.orientation, turns)
        }
    val base =
        ImageViewport(
            geometry,
            viewport.width.toDouble(),
            viewport.height.toDouble(),
            1.0,
            ImagePoint(cx * geometry.width, cy * geometry.height),
        )
    val view = base.copy(scale = if (absolute == 0.0) base.fit else absolute).clamped()
    val current by rememberUpdatedState(view)
    fun update(v: ImageViewport) {
        absolute = v.scale
        cx = v.center.x / geometry.width
        cy = v.center.y / geometry.height
    }
    fun fit() {
        absolute = 0.0
        cx = .5
        cy = .5
    }
    fun zoom(scale: Double, anchor: ImagePoint? = null) {
        update(if (anchor == null) current.zoom(scale) else current.zoom(scale, anchor))
    }
    fun rotate(delta: Int) {
        turns = (turns + delta + 4) % 4
        fit()
    }
    val request =
        remember(view) {
            ImageRequest(
                geometry.rawRegion(view.visible(48.0)),
                imageSample(
                    geometry.rawRegion(view.visible(48.0)).width,
                    geometry.rawRegion(view.visible(48.0)).height,
                    imagePreferredSample(view.scale),
                ),
            )
        }
    var patch by remember(source) { mutableStateOf<ImagePatch?>(null) }
    var detailError by remember(source) { mutableStateOf<String?>(null) }
    var decoding by remember(source) { mutableStateOf(false) }
    val latestRequest by rememberUpdatedState(request)
    LaunchedEffect(source) {
        snapshotFlow { latestRequest }
            .debounce(90)
            .collectLatest { next ->
                detailError = null
                if (!source.regionSupported) return@collectLatest
                decoding = true
                try {
                    patch = source.patch(next)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    detailError = error.message ?: "Image detail unavailable"
                } catch (_: OutOfMemoryError) {
                    detailError =
                        "Not enough memory for sharper detail. The overview is still available."
                } finally {
                    decoding = false
                }
            }
    }
    val keys = LocalPeekKeys.current
    val handle by
        rememberUpdatedState<(KeyEvent) -> Boolean>(
            { e ->
                when {
                    e.keyCode == KeyEvent.KEYCODE_ESCAPE && showInfo -> {
                        showInfo = false
                        true
                    }
                    (e.isCtrlPressed || e.isMetaPressed) && e.keyCode == KeyEvent.KEYCODE_I -> {
                        showInfo = !showInfo
                        true
                    }
                    e.isAltPressed || e.isCtrlPressed || e.isMetaPressed -> false
                    e.isShiftPressed &&
                        e.keyCode in
                            listOf(
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT,
                                KeyEvent.KEYCODE_DPAD_UP,
                                KeyEvent.KEYCODE_DPAD_DOWN,
                            ) -> {
                        val dx =
                            when (e.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> 96.0
                                KeyEvent.KEYCODE_DPAD_RIGHT -> -96.0
                                else -> 0.0
                            }
                        val dy =
                            when (e.keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> 96.0
                                KeyEvent.KEYCODE_DPAD_DOWN -> -96.0
                                else -> 0.0
                            }
                        update(current.pan(dx, dy))
                        true
                    }
                    e.keyCode in
                        listOf(
                            KeyEvent.KEYCODE_PLUS,
                            KeyEvent.KEYCODE_EQUALS,
                            KeyEvent.KEYCODE_NUMPAD_ADD,
                        ) -> {
                        zoom(current.scale * 1.25)
                        true
                    }
                    e.keyCode in
                        listOf(KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT) -> {
                        zoom(current.scale / 1.25)
                        true
                    }
                    e.keyCode == KeyEvent.KEYCODE_0 -> {
                        fit()
                        true
                    }
                    e.keyCode == KeyEvent.KEYCODE_1 -> {
                        zoom(1.0)
                        true
                    }
                    e.keyCode == KeyEvent.KEYCODE_R -> {
                        rotate(if (e.isShiftPressed) -1 else 1)
                        true
                    }
                    else -> false
                }
            },
        )
    DisposableEffect(keys) {
        keys?.handle = { handle(it) }
        onDispose { keys?.handle = null }
    }
    val clipboard = LocalClipboardManager.current
    BoxWithConstraints(modifier) {
        val sideInfo = maxWidth >= 800.dp
        val controlsHeight = (maxHeight * .4f).coerceAtLeast(48.dp)
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(AppBackground)) {
                    Canvas(
                        Modifier.fillMaxSize()
                            .testTag("image-viewport")
                            .semantics {
                                contentDescription = "Preview of ${entry.name}"
                                stateDescription =
                                    "${imageZoomPercent(view.scale)} · center ${"%.3f".format(java.util.Locale.ROOT,view.center.x/geometry.width)}, ${"%.3f".format(java.util.Locale.ROOT,view.center.y/geometry.height)}"
                            }
                            .onSizeChanged { viewport = it }
                            .pointerInput(source, geometry) {
                                detectTransformGestures { centroid, pan, scale, _ ->
                                    val v =
                                        current
                                            .zoom(
                                                current.scale * scale,
                                                ImagePoint(
                                                    centroid.x.toDouble(),
                                                    centroid.y.toDouble(),
                                                ),
                                            )
                                            .pan(pan.x.toDouble(), pan.y.toDouble())
                                    update(v)
                                }
                            }
                            .pointerInput(source, geometry) {
                                detectTapGestures(
                                    onDoubleTap = { p ->
                                        if (current.scale > current.fit * 1.01) fit()
                                        else
                                            zoom(
                                                maxOf(1.0, current.fit * 2),
                                                ImagePoint(p.x.toDouble(), p.y.toDouble()),
                                            )
                                    },
                                )
                            }
                            .pointerInput(source, geometry) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        if (e.type == PointerEventType.Scroll) {
                                            val delta =
                                                e.changes.fold(Offset.Zero) { a, c ->
                                                    a + c.scrollDelta
                                                }
                                            val p = e.changes.firstOrNull()?.position ?: Offset.Zero
                                            if (
                                                e.keyboardModifiers.isCtrlPressed ||
                                                    e.keyboardModifiers.isMetaPressed
                                            )
                                                zoom(
                                                    current.scale *
                                                        2.0.pow(-delta.y.toDouble() / 4),
                                                    ImagePoint(p.x.toDouble(), p.y.toDouble()),
                                                )
                                            else
                                                update(
                                                    current.pan(-delta.x * 48.0, -delta.y * 48.0),
                                                )
                                            e.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            },
                    ) {
                        drawPhoto(
                            source.overview,
                            ImagePixels(0, 0, source.width, source.height),
                            view,
                        )
                        patch?.let { drawPhoto(it.bitmap, it.region, view) }
                    }
                    if (
                        view.visible().width < geometry.width - .1 ||
                            view.visible().height < geometry.height - .1
                    )
                        ImageNavigator(
                            source,
                            view,
                            Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        ) { point ->
                            update(current.copy(center = point).clamped())
                        }
                    if (decoding)
                        LinearProgressIndicator(
                            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp),
                        )
                }
                Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight)
                    .background(ToolbarSurface).verticalScroll(rememberScrollState())) {
                    FlowRow(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 6.dp),
                    ) {
                        ToolButton(
                            Icons.Outlined.Remove,
                            "Zoom out",
                            { zoom(current.scale / 1.25) },
                            enabled = view.scale > view.fit / 4 * 1.01,
                        )
                        Box {
                            TextButton(
                                onClick = { menu = true },
                                modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp),
                            ) {
                                Text("${imageZoomPercent(view.scale)}", fontSize = 12.sp)
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Fit image") },
                                    onClick = {
                                        fit()
                                        menu = false
                                    },
                                )
                                listOf(25, 50, 100, 200, 400, 800).forEach { percent ->
                                    DropdownMenuItem(
                                        text = { Text("$percent%") },
                                        onClick = {
                                            zoom(percent / 100.0)
                                            menu = false
                                        },
                                    )
                                }
                            }
                        }
                        ToolButton(
                            Icons.Outlined.Add,
                            "Zoom in",
                            { zoom(current.scale * 1.25) },
                            enabled = view.scale < 7.99,
                        )
                        TextButton(
                            onClick = { fit() },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Fit image" },
                        ) {
                            Text("Fit", fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = { zoom(1.0) },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Actual pixels" },
                        ) {
                            Text("1:1", fontSize = 12.sp)
                        }
                        ToolButton(
                            Icons.Outlined.RotateRight,
                            "Rotate view clockwise",
                            { rotate(1) },
                        )
                        ToolButton(
                            Icons.Outlined.Info,
                            "Image information",
                            { showInfo = !showInfo },
                            active = showInfo,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${geometry.width} × ${geometry.height}",
                            color = Muted,
                            fontSize = 10.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            when {
                                detailError != null -> detailError!!
                                !source.regionSupported -> "Sampled preview · regions unavailable"
                                decoding -> "Sharpening…"
                                patch == null -> "Preparing detail…"
                                request.sample > imagePreferredSample(view.scale) ->
                                    "Detail resolution limited"
                                else -> "Detail ready"
                            },
                            color = Muted,
                            fontSize = 10.sp,
                            modifier =
                                Modifier.semantics {
                                    contentDescription =
                                        if (!decoding && patch != null) "Image detail ready"
                                        else "Image detail loading"
                                },
                        )
                        if (detailError != null)
                            TextButton(onClick = reload) { Text("Reload", fontSize = 10.sp) }
                    }
                    TextButton(onClick = { showOcr = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Recognize image text…") }
                }
            }
            if (showInfo && sideInfo) {
                VerticalDivider(Modifier.fillMaxHeight(), color = Border)
                PhotoInfo(
                    source.details,
                    revealLocation,
                    { revealLocation = true },
                    { clipboard.setText(AnnotatedString(source.details.text(revealLocation))) },
                    { showInfo = false },
                    Modifier.width(260.dp).fillMaxHeight(),
                )
            }
        }
        if (showOcr) OcrDialog(entry.name, { source.ocrRaster(turns) }) { showOcr = false }
        if (showInfo && !sideInfo)
            AlertDialog(
                onDismissRequest = { showInfo = false },
                title = { Text("Image information") },
                text = {
                    PhotoInfo(
                        source.details,
                        revealLocation,
                        { revealLocation = true },
                        { clipboard.setText(AnnotatedString(source.details.text(revealLocation))) },
                        { showInfo = false },
                        Modifier.heightIn(max = 440.dp),
                        heading = false,
                    )
                },
                confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Done") } },
            )
    }
}

private fun DrawScope.drawPhoto(bitmap: Bitmap, region: ImagePixels, view: ImageViewport) {
    val t = view.geometry.transform
    val s = view.scale
    val matrix =
        Matrix().apply {
            setValues(
                floatArrayOf(
                    (t.a * s).toFloat(),
                    (t.b * s).toFloat(),
                    ((t.x - view.center.x) * s + size.width / 2).toFloat(),
                    (t.c * s).toFloat(),
                    (t.d * s).toFloat(),
                    ((t.y - view.center.y) * s + size.height / 2).toFloat(),
                    0f,
                    0f,
                    1f,
                ),
            )
        }
    drawContext.canvas.nativeCanvas.apply {
        save()
        concat(matrix)
        drawBitmap(
            bitmap,
            null,
            RectF(
                region.left.toFloat(),
                region.top.toFloat(),
                region.right.toFloat(),
                region.bottom.toFloat(),
            ),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        restore()
    }
}

@Composable
private fun ImageNavigator(
    source: ImageSource,
    view: ImageViewport,
    modifier: Modifier,
    onMove: (ImagePoint) -> Unit,
) {
    val latest by rememberUpdatedState(onMove)
    val geometry = view.geometry
    Surface(
        modifier.size(144.dp, 104.dp),
        color = CanvasBackground,
        shape = RoundedCornerShape(6.dp),
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, Border),
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .padding(6.dp)
                .testTag("image-navigator")
                .semantics { contentDescription = "Image navigator" }
                .pointerInput(geometry) {
                    detectTapGestures { p ->
                        val fit =
                            minOf(
                                size.width.toDouble() / geometry.width,
                                size.height.toDouble() / geometry.height,
                            )
                        latest(
                            ImagePoint(
                                (p.x - (size.width - geometry.width * fit) / 2) / fit,
                                (p.y - (size.height - geometry.height * fit) / 2) / fit,
                            ),
                        )
                    }
                }
                .pointerInput(geometry) {
                    detectDragGestures { change, _ ->
                        val fit =
                            minOf(
                                size.width.toDouble() / geometry.width,
                                size.height.toDouble() / geometry.height,
                            )
                        latest(
                            ImagePoint(
                                (change.position.x - (size.width - geometry.width * fit) / 2) / fit,
                                (change.position.y - (size.height - geometry.height * fit) / 2) /
                                    fit,
                            ),
                        )
                        change.consume()
                    }
                },
        ) {
            val fit =
                minOf(
                    size.width.toDouble() / geometry.width,
                    size.height.toDouble() / geometry.height,
                )
            val mini =
                ImageViewport(
                    geometry,
                    size.width.toDouble(),
                    size.height.toDouble(),
                    fit,
                    ImagePoint(geometry.width / 2.0, geometry.height / 2.0),
                )
            drawPhoto(source.overview, ImagePixels(0, 0, source.width, source.height), mini)
            val r = view.visible()
            val x = (size.width - geometry.width * fit) / 2
            val y = (size.height - geometry.height * fit) / 2
            drawRect(
                Blue.copy(alpha = .15f),
                Offset((x + r.left * fit).toFloat(), (y + r.top * fit).toFloat()),
                androidx.compose.ui.geometry.Size(
                    (r.width * fit).toFloat(),
                    (r.height * fit).toFloat(),
                ),
            )
            drawRect(
                Blue,
                Offset((x + r.left * fit).toFloat(), (y + r.top * fit).toFloat()),
                androidx.compose.ui.geometry.Size(
                    (r.width * fit).toFloat(),
                    (r.height * fit).toFloat(),
                ),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun PhotoInfo(
    details: PhotoDetails,
    revealed: Boolean,
    reveal: () -> Unit,
    copy: () -> Unit,
    close: () -> Unit,
    modifier: Modifier,
    heading: Boolean = true,
) {
    Column(
        modifier.background(CanvasBackground).padding(14.dp).testTag("image-information"),
    ) {
        if (heading)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Image information",
                    Modifier.weight(1f),
                    color = Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                ToolButton(Icons.Outlined.Close, "Close image information", close)
            }
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            SelectionContainer {
                Column {
                    details.fields.forEach { (label, value) ->
                        Text(label, Modifier.padding(top = 12.dp), color = Muted, fontSize = 10.sp)
                        Text(value, color = Ink, fontSize = 12.sp)
                    }
                    if (revealed && details.coordinates != null) {
                        Text(
                            "Location",
                            Modifier.padding(top = 12.dp),
                            color = Muted,
                            fontSize = 10.sp,
                        )
                        Text(
                            java.lang.String.format(
                                java.util.Locale.ROOT,
                                "%.6f, %.6f",
                                details.coordinates.first,
                                details.coordinates.second,
                            ),
                            color = Ink,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            if (details.coordinates != null && !revealed)
                TextButton(onClick = reveal) { Text("Reveal location", fontSize = 12.sp) }
            TextButton(onClick = copy, modifier = Modifier.padding(top = 10.dp)) {
                Text("Copy image details", fontSize = 12.sp)
            }
        }
    }
}
