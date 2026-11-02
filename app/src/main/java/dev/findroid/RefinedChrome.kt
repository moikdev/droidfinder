@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.findroid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Primary commands keep their names and order; file selection has its own scoped controls. */
@Composable
fun RefinedToolbar(
    model: FinderModel,
    action: (String) -> Unit,
    addressActive: Boolean,
    addressValue: androidx.compose.ui.text.input.TextFieldValue,
    onAddressChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    addressFocus: FocusRequester,
    onAddressFocused: (Boolean) -> Unit,
    onAddressCommit: () -> Unit,
    onAddressClose: () -> Unit,
    searchActive: Boolean,
    onSearch: () -> Unit,
    searchFocus: FocusRequester,
    focusRequest: Int,
    onSearchFocused: (Boolean) -> Unit,
    onCloseSearch: () -> Unit,
    menuExpanded: Boolean,
    onMenuChange: (Boolean) -> Unit,
) {
    if (LocalPhoneLayout.current) {
        PhoneToolbar(
            model,
            action,
            addressActive,
            addressValue,
            onAddressChange,
            addressFocus,
            onAddressFocused,
            onAddressCommit,
            onAddressClose,
            searchActive,
            onSearch,
            searchFocus,
            focusRequest,
            onSearchFocused,
            onCloseSearch,
            menuExpanded,
            onMenuChange,
        )
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth / androidx.compose.ui.platform.LocalDensity.current.fontScale
        val veryNarrow = availableWidth < 600.dp
        val navigationExpanded = availableWidth >= 800.dp
        val separateActions = availableWidth < 580.dp
        Column(Modifier.fillMaxWidth().background(AppBackground).testTag("tablet-toolbar")) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    "Back",
                    { model.back() },
                    enabled = model.tabs[model.activeTab].index > 0,
                )
                if (!veryNarrow)
                    ToolButton(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        "Forward",
                        model::forward,
                        enabled =
                            model.tabs[model.activeTab].index <
                                model.tabs[model.activeTab].history.lastIndex,
                    )
                if (navigationExpanded)
                    ToolButton(
                        Icons.Outlined.ArrowUpward,
                        "Enclosing folder",
                        model::up,
                        enabled = model.parentPath() != null,
                    )
                if (addressActive) {
                    var placed by remember { mutableStateOf(false) }
                    LaunchedEffect(placed, focusRequest) { if (placed) addressFocus.requestFocus() }
                    BasicTextField(
                        value = addressValue,
                        onValueChange = onAddressChange,
                        singleLine = true,
                        modifier =
                            Modifier.weight(1f)
                                .padding(horizontal = 8.dp)
                                .height(36.dp)
                                .semantics { contentDescription = "Address bar" }
                                .focusRequester(addressFocus)
                                .onGloballyPositioned { placed = true }
                                .onFocusChanged { onAddressFocused(it.isFocused) }
                                // Hardware Enter must commit before an IME edits the selected path.
                                .onPreInterceptKeyBeforeSoftKeyboard {
                                    if (it.key == Key.Enter || it.key == Key.NumPadEnter) {
                                        if (it.type == KeyEventType.KeyDown) onAddressCommit()
                                        true
                                    } else false
                                }
                                .onPreviewKeyEvent {
                                    if (
                                        it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                                            (it.key == Key.Enter || it.key == Key.NumPadEnter)
                                    ) {
                                        onAddressCommit()
                                        true
                                    } else false
                                },
                        textStyle = TextStyle(color = Ink, fontSize = 12.sp),
                        cursorBrush = SolidColor(Blue),
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                                autoCorrectEnabled = false,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                            ),
                        keyboardActions =
                            androidx.compose.foundation.text.KeyboardActions(
                                onGo = { onAddressCommit() },
                            ),
                        decorationBox = { inner ->
                            Row(
                                Modifier.fillMaxSize()
                                    .background(CanvasBackground, RoundedCornerShape(7.dp))
                                    .border(1.dp, Blue, RoundedCornerShape(7.dp))
                                    .padding(start = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.FolderOpen,
                                    null,
                                    Modifier.size(16.dp),
                                    tint = Muted,
                                )
                                Box(
                                    Modifier.weight(1f).padding(horizontal = 8.dp),
                                ) {
                                    inner()
                                }
                                ToolButton(
                                    Icons.Outlined.Close,
                                    "Close address bar",
                                    onAddressClose,
                                    compact = true,
                                )
                            }
                        },
                    )
                } else if (searchActive) {
                    var placed by remember { mutableStateOf(false) }
                    LaunchedEffect(placed, focusRequest) { if (placed) searchFocus.requestFocus() }
                    BasicTextField(
                        value = model.query,
                        onValueChange = model::updateQuery,
                        modifier =
                            Modifier.weight(1f)
                                .padding(horizontal = 8.dp)
                                .height(36.dp)
                                .focusRequester(searchFocus)
                                .onGloballyPositioned { placed = true }
                                .onFocusChanged { onSearchFocused(it.isFocused) },
                        textStyle = TextStyle(color = Ink, fontSize = 13.sp, letterSpacing = 0.sp),
                        cursorBrush = SolidColor(Blue),
                        singleLine = true,
                        decorationBox = { inner ->
                            Row(
                                Modifier.fillMaxSize()
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(CanvasBackground)
                                    .border(1.dp, Border, RoundedCornerShape(7.dp))
                                    .padding(start = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = Muted)
                                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                    if (model.query.isEmpty())
                                        Text("Search this location", fontSize = 12.sp, color = Muted)
                                    inner()
                                }
                                ToolButton(
                                    Icons.Outlined.Close,
                                    "Close search",
                                    onCloseSearch,
                                    compact = true,
                                )
                            }
                        },
                    )
                    ToolButton(
                        Icons.Outlined.FilterAlt,
                        "Search filters",
                        { action("filters") },
                        active = model.effectiveSearchRules.active,
                    )
                } else {
                    Text(model.title(), Modifier.weight(1f).padding(start = 8.dp, end = 12.dp),
                        fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ink,
                        letterSpacing = (-.35).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!addressActive && !searchActive) ViewModeSelector(model)
                if (!separateActions) TabletPrimaryActions(model, action, searchActive, onSearch, onCloseSearch, menuExpanded, onMenuChange)
            }
            if (separateActions) FlowRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End) {
                TabletPrimaryActions(model, action, searchActive, onSearch, onCloseSearch, menuExpanded, onMenuChange)
            }
        }
    }
    HorizontalDivider(color = Border, thickness = .5.dp)
}

@Composable
private fun TabletPrimaryActions(
    model: FinderModel, action: (String) -> Unit, searchActive: Boolean,
    onSearch: () -> Unit, onCloseSearch: () -> Unit,
    menuExpanded: Boolean, onMenuChange: (Boolean) -> Unit,
) {
    ToolbarAction("Search", if (searchActive) onCloseSearch else onSearch, active = searchActive,
        modifier = Modifier.testTag("primary-search"))
    ToolbarAction("New folder", { action("new") }, unavailableReason = model.commandProblem("new")?.reason,
        modifier = Modifier.testTag("primary-new-folder"))
    Box(Modifier.testTag("primary-more")) {
        ToolbarAction("More", { onMenuChange(true) }, label = "Actions")
        ActionMenu(menuExpanded, { onMenuChange(false) }, model, action)
    }
}

/** Named commands stay understandable without hovering or memorizing symbols. */
@Composable
internal fun ToolbarAction(
    text: String,
    onClick: () -> Unit,
    label: String = text,
    enabled: Boolean = true,
    dropdown: Boolean = false,
    icon: ImageVector? = null,
    active: Boolean = false,
    unavailableReason: String? = null,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = if (LocalFinderDensity.current.touch) 48.dp else 38.dp)
            .semantics { contentDescription = label; unavailableReason?.let { stateDescription = "Unavailable: $it" } },
        shape = RoundedCornerShape(7.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (unavailableReason != null) Muted else if (active) SelectedInk else Ink,
            containerColor = if (active) SelectionSurface else androidx.compose.ui.graphics.Color.Transparent,
        ),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp, maxLines = 1)
        if (dropdown) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
        }
    }
}
