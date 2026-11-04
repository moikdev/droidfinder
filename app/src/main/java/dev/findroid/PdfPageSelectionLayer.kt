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
    val target = with(density) { 48.dp.roundToPx() }.toFloat()
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
                var index = currentMap
                var anchor = index?.wordAt(point.x, point.y, point.toleranceX, point.toleranceY)
                if (index != null) currentChange(anchor)
                start.consume()
                drag(start.id) { change ->
                    val next = touch(change.position, clamp = true)
                    if (index == null) {
                        index = currentMap
                        anchor = index?.wordAt(point.x, point.y, point.toleranceX, point.toleranceY)
                    }
                    if (anchor != null && currentMap === index)
                        currentChange(index!!.extend(anchor!!, next.x, next.y, next.toleranceX, next.toleranceY))
                    change.consume()
                }
            }
        }) {
        val boxes = remember(map, selection) { if (map != null && selection != null) map.boxes(selection) else emptyList() }
        Canvas(Modifier.fillMaxSize()) {
            for (box in boxes) drawRect(color.copy(alpha = .3f), Offset(box.left * this.size.width, box.top * this.size.height),
                Size((box.right - box.left) * this.size.width, (box.bottom - box.top) * this.size.height))
        }
        if (selecting && map != null && selection != null) {
            val handles = listOf(true, false).mapNotNull { start ->
                val edge = map.edge(selection, start) ?: return@mapNotNull null
                val point = Offset((if (start) edge.left else edge.right) * size.width, edge.bottom * size.height)
                val offset = IntOffset(
                    (point.x - if (start) target else 0f).roundToInt().coerceIn(0, (size.width - target).roundToInt().coerceAtLeast(0)),
                    point.y.roundToInt().coerceIn(0, (size.height - target).roundToInt().coerceAtLeast(0)),
                )
                Triple(start, point, offset)
            }.toMutableList()
            if (handles.size == 2) {
                val first = handles[0]; val last = handles[1]
                if (kotlin.math.abs(first.third.x - last.third.x) < target && kotlin.math.abs(first.third.y - last.third.y) < target) {
                    // Android removes covered portions from accessibility bounds. Keep both
                    // touch rectangles complete even when a short word fits near the page edge.
                    if (size.width >= 2 * target) {
                        val endX = maxOf(last.third.x, first.third.x + target.toInt()).coerceAtMost(size.width - target.toInt())
                        val startX = minOf(first.third.x, endX - target.toInt()).coerceAtLeast(0)
                        handles[0] = first.copy(third = IntOffset(startX, first.third.y))
                        handles[1] = last.copy(third = IntOffset(endX, last.third.y))
                    } else if (size.height >= 2 * target) {
                        val endY = maxOf(last.third.y, first.third.y + target.toInt()).coerceAtMost(size.height - target.toInt())
                        val startY = minOf(first.third.y, endY - target.toInt()).coerceAtLeast(0)
                        handles[0] = first.copy(third = IntOffset(first.third.x, startY))
                        handles[1] = last.copy(third = IntOffset(last.third.x, endY))
                    }
                }
            }
            for ((start, pointOnGlyph, offset) in handles) {
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
                    // Keep the visible knob near the glyph while its touch target remains 48 dp.
                    val radius = minOf(9.dp.toPx(), this.size.width / 2, this.size.height / 2)
                    val glyph = currentPointOnGlyph - Offset(offset.x.toFloat(), offset.y.toFloat())
                    val knob = Offset(
                        (glyph.x + if (start) -radius else radius).coerceIn(radius, this.size.width - radius),
                        (glyph.y + radius).coerceIn(radius, this.size.height - radius),
                    )
                    drawLine(color, glyph, knob, strokeWidth = 1.5.dp.toPx())
                    drawCircle(Color.White, radius, knob)
                    drawCircle(color, radius * 7 / 9, knob)
                    drawCircle(color.copy(alpha = .5f), radius, knob, style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}
