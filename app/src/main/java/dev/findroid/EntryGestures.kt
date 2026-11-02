@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.findroid

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration

/** Shared by regular and archive entries. Touch does not wait for a double-click timeout. */
internal fun Modifier.entryGestures(
    model: FinderModel,
    key: String,
    touch: (control: Boolean, shift: Boolean) -> Unit,
    mouse: (double: Boolean, control: Boolean, shift: Boolean) -> Unit,
    hold: (mouse: Boolean) -> Unit,
    context: (Offset?) -> Unit,
    middle: (() -> Unit)? = null,
): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var control by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var isMouse by remember { mutableStateOf(false) }
    var gesture by remember { mutableLongStateOf(0) }
    var releasedAt by remember { mutableLongStateOf(0) }
    val currentTouch by rememberUpdatedState(touch)
    val currentMouse by rememberUpdatedState(mouse)
    val currentHold by rememberUpdatedState(hold)
    val currentContext by rememberUpdatedState(context)
    val currentMiddle by rememberUpdatedState(middle)
    val configuration = LocalViewConfiguration.current
    val slop =
        android.view.ViewConfiguration.get(LocalContext.current).scaledDoubleTapSlop.toFloat()
    val window = model.windowState
    val clicks = window.fileClicks
    onGloballyPositioned { coordinates = it }
        .motionEventSpy { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN)
                window.nativeMousePressTime =
                    if (
                        event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                    )
                        event.eventTime
                    else Long.MIN_VALUE
        }
        .pointerInput(key) {
            awaitPointerEventScope {
                var secondaryDown = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Release) {
                        releasedAt = event.changes.firstOrNull()?.uptimeMillis ?: 0
                        if (secondaryDown) {
                            secondaryDown = false
                            currentContext(
                                coordinates?.localToWindow(event.changes.first().position),
                            )
                            event.changes.forEach { it.consume() }
                        }
                    }
                    if (event.type == PointerEventType.Press) {
                        val change = event.changes.firstOrNull()
                        isMouse =
                            change?.type == PointerType.Mouse ||
                                change?.uptimeMillis == window.nativeMousePressTime
                        gesture = clicks.press
                        control =
                            event.keyboardModifiers.isCtrlPressed ||
                                event.keyboardModifiers.isMetaPressed
                        shift = event.keyboardModifiers.isShiftPressed
                        if (event.buttons.isTertiaryPressed) {
                            clicks.reset()
                            currentMiddle?.invoke()
                            event.changes.forEach { it.consume() }
                        } else if (event.buttons.isSecondaryPressed) {
                            clicks.reset()
                            secondaryDown = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        }
        .pointerInput(key) {
            detectTapGestures(
                onTap = { position ->
                    if (isMouse) {
                        val point = coordinates?.localToWindow(position) ?: position
                        val double =
                            if (control || shift) {
                                clicks.reset()
                                false
                            } else
                                clicks.mouseClick(
                                    key,
                                    gesture,
                                    releasedAt,
                                    point.x,
                                    point.y,
                                    configuration.doubleTapMinTimeMillis,
                                    configuration.doubleTapTimeoutMillis,
                                    slop,
                                )
                        currentMouse(double, control, shift)
                    } else {
                        clicks.reset()
                        currentTouch(control, shift)
                    }
                },
                onLongPress = {
                    clicks.reset()
                    currentHold(isMouse)
                },
            )
        }
}
