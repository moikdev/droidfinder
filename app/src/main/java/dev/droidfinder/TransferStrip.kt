package dev.droidfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Clipboard edits and read-only navigation stay available while the mutation queue is busy. */
fun browseKeyDuringTransfer(event: KeyEvent, editing: Boolean): Boolean {
    if (event.isCtrlPressed || event.isMetaPressed)
        return event.key in
            setOf(
                Key.F,
                Key.L,
                Key.T,
                Key.W,
                Key.Tab,
                Key.PageUp,
                Key.PageDown,
                Key.One,
                Key.Two,
                Key.Three,
                Key.Four,
                Key.R,
                Key.C,
                Key.X,
                Key.I,
                Key.O,
            ) || editing && event.key in setOf(Key.A, Key.V, Key.Z, Key.Y)
    if (event.isAltPressed)
        return event.key in setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp)
    return editing ||
        event.key in
            setOf(
                Key.Spacebar,
                Key.Enter,
                Key.Escape,
                Key.DirectionUp,
                Key.DirectionDown,
                Key.DirectionLeft,
                Key.DirectionRight,
                Key.MoveHome,
                Key.MoveEnd,
                Key.F10,
            ) ||
        (event.utf16CodePoint > 31 && !Character.isISOControl(event.utf16CodePoint))
}

@Composable
fun TransferStrip(model: FinderModel) {
    HorizontalDivider(color = Border, thickness = .5.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(SubtleSurface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(
                model.busy.orEmpty(),
                color = Ink,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${model.transferProgress.items} items · ${formatSize(model.transferProgress.bytes)} · ${model.transferProgress.current}",
                color = Muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = model::cancelTransfer) { Text("Cancel transfer", fontSize = 11.sp) }
    }
}
