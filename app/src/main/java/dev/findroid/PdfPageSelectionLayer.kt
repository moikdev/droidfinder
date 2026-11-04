package dev.findroid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class PdfSelectionTouch(val x: Float, val y: Float, val toleranceX: Float, val toleranceY: Float)

/** Lives inside the same fitted and transformed page box as the bitmap and search marks. */
@Composable
internal fun PdfPageSelectionLayer(
    pageIdentity: Any,
    map: PdfSelectionMap?,
    selection: PdfPageSelection?,
    selecting: Boolean,
    zoom: Float,
    requestSelection: (PdfSelectionTouch) -> Unit,
    changeSelection: (PdfPageSelection?) -> Unit,
) {
    var size by remember(pageIdentity) { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val target = with(density) { 48.dp.toPx() }
    val tolerance = with(density) { 10.dp.toPx() } / zoom.coerceAtLeast(1f)
    val currentSelection by rememberUpdatedState(selection)
    val currentMap by rememberUpdatedState(map)
    val currentSelecting by rememberUpdatedState(selecting)
    val currentRequest by rememberUpdatedState(requestSelection)
    val currentChange by rememberUpdatedState(changeSelection)
    val currentTolerance by rememberUpdatedState(tolerance)
    val color = MaterialTheme.colorScheme.primary
    fun touch(point: Offset, clamp: Boolean = false): PdfSelectionTouch {
        val x = point.x / size.width.coerceAtLeast(1)
        val y = point.y / size.height.coerceAtLeast(1)
        return PdfSelectionTouch(if (clamp) x.coerceIn(0f, 1f) else x, if (clamp) y.coerceIn(0f, 1f) else y,
            currentTolerance / size.width.coerceAtLeast(1), currentTolerance / size.height.coerceAtLeast(1))
    }
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }.testTag("pdf-page-selection-layer")
        .semantics {
            contentDescription = "PDF page text selection"
            stateDescription = selection?.let { map?.selectedText(it)?.take(240) } ?: "No page text selected"
            customActions = listOf(CustomAccessibilityAction("Select all extracted page text") {
                val index = currentMap ?: return@CustomAccessibilityAction false
                currentChange(index.all()); true
            })
        }
        .pointerInput(pageIdentity) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val start = if (currentSelecting && currentMap != null) down
                    else awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val point = touch(start.position)
                currentRequest(point)
                val index = currentMap
                val anchor = index?.wordAt(point.x, point.y, point.toleranceX, point.toleranceY)
                if (index != null) currentChange(anchor)
                start.consume()
                drag(start.id) { change ->
                    val next = touch(change.position, clamp = true)
                    if (anchor != null && currentMap === index)
                        currentChange(index!!.extend(anchor, next.x, next.y, next.toleranceX, next.toleranceY))
                    change.consume()
                }
            }
        }) {
        val boxes = remember(map, selection) { if (map != null && selection != null) map.boxes(selection) else emptyList() }
        Canvas(Modifier.fillMaxSize()) {
            for (box in boxes) drawRect(color.copy(alpha = .3f), Offset(box.left * this.size.width, box.top * this.size.height),
                Size((box.right - box.left) * this.size.width, (box.bottom - box.top) * this.size.height))
        }
        if (map != null && selection != null) for (start in listOf(true, false)) {
            val edge = map.edge(selection, start) ?: continue
            val pointOnGlyph = Offset((if (start) edge.left else edge.right) * size.width, edge.bottom * size.height)
            var center = Offset(pointOnGlyph.x + (if (start) -target / 2 else target / 2), pointOnGlyph.y + target / 2)
            // Separate the 48 dp handles even for a one-glyph selection.
            if (!start) {
                val first = map.edge(selection, true)
                if (first != null && (edge.right - first.left) * size.width < target) center = center.copy(y = center.y + target)
            }
            val offset = IntOffset((center.x - target / 2).roundToInt().coerceIn(0, (size.width - target).roundToInt().coerceAtLeast(0)),
                (center.y - target / 2).roundToInt().coerceIn(0, (size.height - target).roundToInt().coerceAtLeast(0)))
            val currentPointOnGlyph by rememberUpdatedState(pointOnGlyph)
            val label = if (start) "Start of PDF text selection" else "End of PDF text selection"
            Canvas(Modifier.offset { offset }.size(48.dp).testTag(if (start) "pdf-selection-start" else "pdf-selection-end")
                .semantics {
                    contentDescription = label
                    customActions = listOf(-1 to "Move earlier", 1 to "Move later").map { (delta, name) ->
                        CustomAccessibilityAction(name) { changeSelection(map.stepHandle(selection, start, delta)); true }
                    }
                }.focusable()
                .pointerInput(map, start, pageIdentity) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val origin = currentSelection ?: return@awaitEachGesture
                        val dragOrigin = currentPointOnGlyph
                        var accumulated = Offset.Zero
                        down.consume()
                        drag(down.id) { change ->
                            accumulated += change.positionChange()
                            change.consume()
                            val point = touch(dragOrigin + accumulated, clamp = true)
                            currentChange(map.moveHandle(origin, start, point.x, point.y, point.toleranceX, point.toleranceY))
                        }
                    }
                }) {
                drawCircle(Color.White, 9.dp.toPx())
                drawCircle(color, 7.dp.toPx())
                drawCircle(color.copy(alpha = .5f), 9.dp.toPx(), style = Stroke(1.dp.toPx()))
            }
        }
    }
}
