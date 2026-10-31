package dev.droidfinder

import androidx.compose.ui.input.key.Key

/** Windows uses Ctrl; Finder's existing Meta view/duplicate aliases remain available. */
data class ExplorerShortcut(
    val action: String,
    val amount: Int = 0,
    val extend: Boolean = false,
    val focusOnly: Boolean = false,
    val additive: Boolean = false,
)

fun explorerShortcut(
    key: Key,
    ctrl: Boolean = false,
    meta: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
    editing: Boolean = false,
): ExplorerShortcut? {
    if (ctrl && meta) return null
    fun command(action: String, amount: Int = 0) = ExplorerShortcut(action, amount)
    val digits =
        listOf(
            Key.One,
            Key.Two,
            Key.Three,
            Key.Four,
            Key.Five,
            Key.Six,
            Key.Seven,
            Key.Eight,
            Key.Nine,
        )
    val digit = digits.indexOf(key) + 1
    val command =
        when {
            ctrl || meta ->
                when {
                    key == Key.E && meta && !ctrl && !shift -> command("newWindow")
                    key == Key.E && shift -> command("navigationTree")
                    key == Key.F || key == Key.E -> command("search")
                    key == Key.L -> command("path")
                    key == Key.T -> command(if (shift) "reopenTab" else "newTab")
                    key == Key.N -> command(if (shift) "new" else "newWindow")
                    key == Key.W || key == Key.F4 -> command("closeTab")
                    key == Key.Tab -> command("cycleTab", if (shift) -1 else 1)
                    key == Key.PageUp -> command("cycleTab", -1)
                    key == Key.PageDown -> command("cycleTab", 1)
                    digit > 0 && shift -> command("windowsView", digit)
                    digit > 0 && meta && digit <= 4 -> command("finderView", digit)
                    digit > 0 -> command("selectTab", digit)
                    key == Key.A -> command(if (shift) "clearSelection" else "selectAll")
                    key == Key.C -> command(if (shift) "copyPath" else "copy")
                    key == Key.X -> command("cut")
                    key == Key.V -> command("paste")
                    key == Key.Insert -> command("copy")
                    key == Key.D -> command(if (meta) "duplicate" else "trash")
                    key == Key.Z -> command(if (shift) "redo" else "undo")
                    key == Key.Y -> command("redo")
                    key == Key.R -> command("refresh")
                    key == Key.I -> command("info")
                    key == Key.O -> command("open")
                    key == Key.S && alt -> command("sidebar")
                    key == Key.Period -> command("hidden")
                    key == Key.Backslash -> command("dualPane")
                    key == Key.NumPadAdd || key == Key.Plus || key == Key.Equals ->
                        command("fitColumns")
                    key == Key.Spacebar -> command("toggleFocused")
                    key in
                        listOf(
                            Key.DirectionUp,
                            Key.DirectionDown,
                            Key.DirectionLeft,
                            Key.DirectionRight,
                        ) ->
                        ExplorerShortcut(
                            "moveSelection",
                            when (key) {
                                Key.DirectionUp -> -2
                                Key.DirectionDown -> 2
                                Key.DirectionLeft -> -1
                                else -> 1
                            },
                            shift,
                            !shift,
                            shift,
                        )
                    key == Key.MoveHome -> command("home")
                    key == Key.MoveEnd -> command("end")
                    key == Key.Delete || key == Key.Backspace ->
                        command(if (shift) "empty" else "trash")
                    else -> null
                }
            alt ->
                when (key) {
                    Key.D -> command("path")
                    Key.Enter,
                    Key.NumPadEnter -> command("info")
                    Key.P -> command(if (shift) "details" else "preview")
                    Key.DirectionLeft -> command("back")
                    Key.DirectionRight -> command("forward")
                    Key.DirectionUp -> command("up")
                    Key.F4 -> command("closeWindow")
                    Key.PageUp -> command("page", -1)
                    Key.PageDown -> command("page", 1)
                    Key.F -> command("actions")
                    else -> null
                }
            else ->
                when (key) {
                    Key.F1 -> command("shortcuts")
                    Key.F2 -> command("rename")
                    Key.F3 -> command("search")
                    Key.F4 -> command("path")
                    Key.F5 -> command("refresh")
                    Key.F6 -> command("focusArea", if (shift) -1 else 1)
                    Key.F10 -> command(if (shift) "context" else "actions")
                    Key.Menu -> command("context")
                    Key.F11 -> command("fullscreen")
                    Key.Enter,
                    Key.NumPadEnter -> command("open")
                    Key.Spacebar -> command("quicklook")
                    Key.Insert -> if (shift) command("paste") else null
                    Key.Delete -> command(if (shift) "delete" else "trash")
                    Key.Backspace -> command("back")
                    Key.DirectionUp -> ExplorerShortcut("moveSelection", -2, shift)
                    Key.DirectionDown -> ExplorerShortcut("moveSelection", 2, shift)
                    Key.DirectionLeft -> ExplorerShortcut("left", extend = shift)
                    Key.DirectionRight -> ExplorerShortcut("right", extend = shift)
                    Key.MoveHome -> ExplorerShortcut("home", extend = shift)
                    Key.MoveEnd -> ExplorerShortcut("end", extend = shift)
                    Key.PageUp -> ExplorerShortcut("page", -1, shift)
                    Key.PageDown -> ExplorerShortcut("page", 1, shift)
                    Key.NumPadAdd -> command("expand")
                    Key.NumPadSubtract -> command("collapse")
                    Key.NumPadMultiply -> command("expandAll")
                    Key.Escape -> command("cancel")
                    else -> null
                }
        }
    if (
        editing &&
            command?.action !in
                setOf(
                    "search",
                    "path",
                    "newTab",
                    "newWindow",
                    "closeTab",
                    "closeWindow",
                    "cycleTab",
                    "selectTab",
                    "reopenTab",
                    "focusArea",
                    "fullscreen",
                    "shortcuts",
                    "cancel",
                )
    )
        return null
    return command
}
