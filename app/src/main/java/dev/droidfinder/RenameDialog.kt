package dev.droidfinder

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ReviewedRenameDialog(model: FinderModel, openRecovery: (Entry) -> Unit) {
    val state = model.batchRename
    val config = LocalConfiguration.current
    val narrow = config.screenWidthDp < 900 || config.fontScale > 1.3f
    var page by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("") }
    var options by rememberSaveable { mutableStateOf(false) }
    var savePreset by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    var fullRecoveryLocations by rememberSaveable { mutableStateOf(false) }
    var recoveryNotice by remember { mutableStateOf<String?>(null) }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var recoveryError by remember { mutableStateOf<String?>(null) }
    var help by rememberSaveable { mutableStateOf(false) }
    val editable = !state.loading && !state.applying
    val preview = state.plan
    LaunchedEffect(state.presetNotice, state.presetSaving) {
        if (savePreset && state.presetNotice != null && !state.presetSaving) {
            savePreset = false
            presetName = ""
        }
    }
    Dialog(
        onDismissRequest = state::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.width((config.screenWidthDp * .94f).coerceAtMost(860f).dp)
                .height((config.screenHeightDp * .88f).coerceAtMost(540f).dp)
                .testTag("reviewed-rename"),
            shape = RoundedCornerShape(14.dp),
            color = CanvasBackground,
            shadowElevation = 18.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(62.dp).padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.DriveFileRenameOutline, null, tint = Blue)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            "Batch rename",
                            color = Ink,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (state.loading) state.progress
                            else
                                "${state.inputs.size} ${if (state.inputs.size == 1) "item" else "items"} · review every resulting name",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                    if (state.loading || state.applying)
                        TextButton(
                            onClick = {
                                if (state.applying) model.cancelTransfer() else state.stop()
                            },
                        ) {
                            Text("Stop")
                        }
                    else
                        ToolButton(
                            Icons.Outlined.Refresh,
                            "Refresh rename preview",
                            state::refresh,
                            enabled = model.busy == null,
                        )
                    ToolButton(
                        Icons.Outlined.Tune,
                        "Rename options",
                        { options = true },
                        enabled = editable,
                    )
                    ToolButton(Icons.Outlined.Info, "Rename help", { help = true })
                    ToolButton(Icons.Outlined.Close, "Close batch rename", state::close)
                }
                HorizontalDivider(color = Border)
                if (state.loading || state.planning || state.applying)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { problem ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Sidebar)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (state.recoveryRoutes.isNotEmpty())
                                "Some names could not be restored. Original data was kept; review the recovery items."
                            else problem,
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.recoveryRoutes.isNotEmpty())
                            TextButton(onClick = { recovery = true }) { Text("Recovery items") }
                    }
                }
                if (!state.prepareUndo)
                    Text(
                        "Undo preparation is off. Applying will clear session Undo and Redo.",
                        Modifier.fillMaxWidth()
                            .background(Sidebar)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                    )
                if (narrow)
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Sidebar)
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(page == 0, { page = 0 }, label = { Text("Rules") })
                        FilterChip(
                            page == 1,
                            { page = 1 },
                            label = {
                                Text(
                                    "Preview${preview?.errors?.takeIf { it > 0 }?.let { " · $it issues" }.orEmpty()}",
                                )
                            },
                        )
                    }
                if (narrow) {
                    if (page == 0)
                        RenameRuleControls(
                            state,
                            editable,
                            { savePreset = true },
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                    else
                        RenamePreview(
                            state,
                            filter,
                            { filter = it },
                            { details = it },
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                } else
                    Row(Modifier.weight(1f)) {
                        RenameRuleControls(
                            state,
                            editable,
                            { savePreset = true },
                            Modifier.width(280.dp).fillMaxHeight(),
                        )
                        VerticalDivider(color = Border)
                        RenamePreview(
                            state,
                            filter,
                            { filter = it },
                            { details = it },
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                HorizontalDivider(color = Border)
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 62.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when {
                            state.applying -> model.busy ?: "Renaming…"
                            state.loading || state.planning -> "Preparing preview…"
                            preview == null -> "Refresh to review names"
                            preview.errors > 0 -> "${preview.errors} need attention"
                            else ->
                                "${preview.changes.size} ${if (preview.changes.size == 1) "change" else "changes"} · ${preview.rows.count { !it.included }} excluded"
                        },
                        Modifier.weight(1f),
                        fontSize = 12.sp,
                        color =
                            if ((preview?.errors ?: 0) > 0) MaterialTheme.colorScheme.error
                            else Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = state::close) { Text("Cancel") }
                    Button(
                        onClick = state::apply,
                        enabled =
                            editable &&
                                !state.planning &&
                                model.busy == null &&
                                preview?.canApply == true,
                    ) {
                        Text("Rename items")
                    }
                }
            }
        }
    }
    if (recovery)
        AlertDialog(
            onDismissRequest = { recovery = false },
            title = { Text("Rename recovery") },
            text = {
                Column {
                    Text(
                        "Original data was kept. These are the current locations. New arrivals at the original names were left untouched.",
                        fontSize = 12.sp,
                    )
                    recoveryError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    recoveryNotice?.let { Text(it, color = Muted, fontSize = 11.sp) }
                    TextButton(onClick = { fullRecoveryLocations = !fullRecoveryLocations }) {
                        Text(
                            if (fullRecoveryLocations) "Hide full locations"
                            else "Show full locations",
                        )
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp).testTag("rename-recovery-list")) {
                        itemsIndexed(state.recoveryRoutes) { _, route ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                SelectionContainer {
                                    Column {
                                        Text(
                                            "From: " + model.displayPath(route.first),
                                            color = Muted,
                                            fontSize = 11.sp,
                                        )
                                        Text(
                                            model.displayPath(route.second),
                                            color = Ink,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (fullRecoveryLocations)
                                            Text(route.second, color = Muted, fontSize = 11.sp)
                                    }
                                }
                                Row {
                                    TextButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(route.second))
                                            recoveryNotice = "Location copied"
                                        },
                                    ) {
                                        Text("Copy location")
                                    }
                                    TextButton(
                                        onClick = {
                                            model.openRenameRecovery(route.second, openRecovery) {
                                                recoveryError = it
                                            }
                                        },
                                    ) {
                                        Text("Open recovery item")
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "Refresh uses these recovered items, not replacements at their former names.",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { recovery = false }) { Text("Done") } },
        )
    if (options)
        AlertDialog(
            onDismissRequest = { options = false },
            title = { Text("Rename options") },
            text = {
                Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState())) {
                    RenameChoice(
                        "Unicode",
                        "Unicode normalization",
                        state.recipe.normalization,
                        RenameNormalization.entries,
                        { it.title },
                    ) {
                        state.configure(state.recipe.copy(normalization = it))
                    }
                    RenameToggle(
                        "Treat case/Unicode-equivalent targets as conflicts",
                        state.recipe.portableConflicts,
                        editable,
                    ) {
                        state.configure(state.recipe.copy(portableConflicts = it))
                    }
                    Text(
                        "Helps avoid collisions when moving files between volumes. The actual filesystem or provider still decides which names it supports.",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                    RenameToggle("Prepare protected session Undo", state.prepareUndo, editable) {
                        state.prepareUndo = it
                    }
                    Text(
                        "Reads changed items before renaming to protect Undo from later edits. Turn off for a quick metadata-only rename; this clears session history.",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                    if (state.recipe.method == RenameMethod.REPLACE)
                        RenameToggle("Replace first match only", state.recipe.firstOnly, editable) {
                            state.configure(state.recipe.copy(firstOnly = it))
                        }
                }
            },
            confirmButton = { TextButton(onClick = { options = false }) { Text("Done") } },
        )
    if (savePreset)
        AlertDialog(
            onDismissRequest = { savePreset = false },
            title = { Text("Save rename preset") },
            text = {
                Column {
                    OutlinedTextField(
                        presetName,
                        { presetName = renameFieldText(it, 48) },
                        label = { Text("Preset name") },
                        singleLine = true,
                    )
                    Text(
                        "Saves the rule and ordering. Selected files and paths are not saved.",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    state.presetError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { state.savePreset(presetName) },
                    enabled = presetName.isNotBlank() && !state.presetSaving,
                ) {
                    Text("Save preset")
                }
            },
            dismissButton = { TextButton(onClick = { savePreset = false }) { Text("Cancel") } },
        )
    details?.let { id ->
        val row = preview?.rows?.firstOrNull { it.input.id == id }
        if (row != null)
            AlertDialog(
                onDismissRequest = { details = null },
                title = { Text("Name details") },
                text = {
                    SelectionContainer {
                        Column(
                            Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                        ) {
                            Text("CURRENT", color = Muted, fontSize = 10.sp)
                            Text(row.input.name, color = Ink)
                            Text(
                                "RESULT",
                                color = Muted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                            Text(row.name ?: "Excluded", color = Ink)
                            Text(
                                model.displayPath(row.input.parent),
                                color = Muted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                            row.error?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            row.input.photo?.let { photo ->
                                Text(
                                    listOfNotNull(
                                            photo.taken?.toString(),
                                            photo.camera,
                                            photo.width?.let { "$it × ${photo.height ?: "?"}" },
                                        )
                                        .joinToString("\n"),
                                    color = Muted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { details = null }) { Text("Done") } },
            )
    }
    if (help)
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("Rename rules") },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            "Every selected item is previewed. Excluding an item leaves it in place and recalculates numbering. Sort, reverse, or move rows up/down before applying. Filter hides rows without changing which files will be renamed. Tap a row for full names.\n\nTemplate tokens\n{name}  {full}  {ext}  {dotext}\n{index}  {parent}  {size}\n{tag}  {tags}\n{modified:yyyy-MM-dd}\n{taken:yyyy-MM-dd}  {camera}\n{width}  {height}\n\nUse {{ and }} for literal braces. Templates produce the complete filename; {dotext} preserves an existing extension, including extensionless files and folders. Modified dates use the captured device timezone. Capture dates use EXIF's local date/time; missing metadata is an error. Photo metadata is read only when requested, under a 16 MiB stream budget per image.\n\nRegular expressions use RE2/J syntax with numbered/named replacement groups. Lookaround and pattern backreferences are unsupported. Names must stay within 240 UTF-8 bytes. Metadata slashes/control characters become underscores; nothing is moved to another folder.\n\nApplying rechecks sources and destination names. A provider may take time to return from a rename. Stop rolls staged names back when possible; interrupted process recovery and permanent Undo remain separate work.",
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("Done") } },
        )
}

@Composable
private fun RenameRuleControls(
    state: RenameController,
    editable: Boolean,
    save: () -> Unit,
    modifier: Modifier,
) {
    val recipe = state.recipe
    var presetsOpen by remember { mutableStateOf(false) }
    var deletePreset by remember { mutableStateOf<RenamePreset?>(null) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { presetsOpen = true }, enabled = editable) {
                    Text(
                        state.presets.firstOrNull { it.id == state.presetId }?.name
                            ?: "Rename presets",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                }
                DropdownMenu(
                    presetsOpen,
                    { presetsOpen = false },
                    Modifier.heightIn(max = 320.dp),
                ) {
                    if (state.presets.isEmpty())
                        DropdownMenuItem(
                            text = { Text("No saved presets") },
                            onClick = {},
                            enabled = false,
                        )
                    state.presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = {
                                state.loadPreset(preset)
                                presetsOpen = false
                            },
                        )
                    }
                    state.presets
                        .firstOrNull { it.id == state.presetId }
                        ?.let { preset ->
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Update this preset") },
                                onClick = {
                                    state.savePreset(preset.name, update = true)
                                    presetsOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete this preset") },
                                onClick = {
                                    deletePreset = preset
                                    presetsOpen = false
                                },
                            )
                        }
                }
            }
            ToolButton(
                Icons.Outlined.BookmarkAdd,
                "Save rename preset",
                save,
                enabled = editable && !state.presetSaving,
            )
        }
        state.presetError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
        RenameChoice(
            "Method",
            "Rename method",
            recipe.method,
            RenameMethod.entries,
            { it.title },
            editable,
        ) {
            state.configure(recipe.copy(method = it))
        }
        if (recipe.method != RenameMethod.TEMPLATE)
            RenameChoice(
                "Apply to",
                "Rename part",
                recipe.part,
                RenamePart.entries,
                { it.title },
                editable,
            ) {
                state.configure(recipe.copy(part = it))
            }
        when (recipe.method) {
            RenameMethod.REPLACE -> {
                RenameField(recipe.text, "Find text", editable) {
                    state.configure(recipe.copy(text = it))
                }
                RenameField(recipe.replacement, "Replace with", editable) {
                    state.configure(recipe.copy(replacement = it))
                }
                RenameToggle("Regular expression", recipe.regex, editable) {
                    state.configure(recipe.copy(regex = it))
                }
                RenameToggle("Match case", recipe.matchCase, editable) {
                    state.configure(recipe.copy(matchCase = it))
                }
            }
            RenameMethod.PREFIX,
            RenameMethod.SUFFIX ->
                RenameField(recipe.text, "Text to add", editable) {
                    state.configure(recipe.copy(text = it))
                }
            RenameMethod.NUMBER ->
                RenameField(recipe.text, "Custom name", editable) {
                    state.configure(recipe.copy(text = it))
                }
            RenameMethod.CASE ->
                RenameChoice(
                    "Letter case",
                    "Rename letter case",
                    recipe.letterCase,
                    RenameLetterCase.entries,
                    { it.title },
                    editable,
                ) {
                    state.configure(recipe.copy(letterCase = it))
                }
            RenameMethod.TEMPLATE -> {
                OutlinedTextField(
                    recipe.template,
                    { state.configure(recipe.copy(template = renameFieldText(it, 512))) },
                    label = { Text("Filename template") },
                    minLines = 2,
                    maxLines = 4,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Use {dotext} to preserve the extension.", fontSize = 11.sp, color = Muted)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                            "{name}",
                            "{index}",
                            "{dotext}",
                            "{modified:yyyy-MM-dd}",
                            "{taken:yyyy-MM-dd}",
                        )
                        .forEach { token ->
                            AssistChip(
                                onClick = {
                                    state.configure(
                                        recipe.copy(
                                            template =
                                                (recipe.template + token).let {
                                                    renameFieldText(it, 512)
                                                },
                                        ),
                                    )
                                },
                                label = { Text(token, fontSize = 11.sp) },
                                enabled = editable,
                            )
                        }
                }
                TextButton(onClick = state::loadPhotoMetadata, enabled = editable) {
                    Text("Read photo metadata")
                }
                state.metadataStatus?.let { Text(it, color = Muted, fontSize = 11.sp) }
            }
        }
        if (recipe.method in listOf(RenameMethod.NUMBER, RenameMethod.TEMPLATE)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RenameNumber(recipe.start, "Start", editable, Modifier.weight(1f)) {
                    state.configure(recipe.copy(start = it))
                }
                RenameNumber(recipe.step, "Step", editable, Modifier.weight(1f)) {
                    state.configure(recipe.copy(step = it))
                }
            }
            RenameChoice(
                "Digits",
                "Number padding",
                recipe.padding,
                (1..12).toList(),
                { it.toString() },
                editable,
            ) {
                state.configure(recipe.copy(padding = it))
            }
            if (recipe.method == RenameMethod.NUMBER)
                RenameField(recipe.separator, "Separator", editable, 16) {
                    state.configure(recipe.copy(separator = it))
                }
        }
        HorizontalDivider(color = Border)
        RenameChoice(
            "Order",
            "Rename order",
            state.order,
            RenameOrder.entries,
            { it.title },
            editable,
        ) {
            state.sort(it)
        }
        RenameToggle("Reverse order", state.reverse, editable) { state.sort(state.order, it) }
    }
    deletePreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { deletePreset = null },
            title = { Text("Delete preset?") },
            text = { Text("Remove ${preset.name}? Files will not change.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.removePreset(preset.id)
                        deletePreset = null
                    },
                ) {
                    Text("Delete preset")
                }
            },
            dismissButton = { TextButton(onClick = { deletePreset = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RenamePreview(
    state: RenameController,
    filter: String,
    changeFilter: (String) -> Unit,
    details: (String) -> Unit,
    modifier: Modifier,
) {
    val plan = state.plan
    val rows =
        plan?.rows.orEmpty().filter {
            filter.isBlank() ||
                it.input.name.contains(filter, true) ||
                it.name?.contains(filter, true) == true ||
                it.error?.contains(filter, true) == true
        }
    val list = rememberLazyListState()
    val positions =
        remember(plan) {
            plan?.rows.orEmpty().mapIndexed { index, row -> row.input.id to index }.toMap()
        }
    var orderMenu by remember { mutableStateOf<String?>(null) }
    Column(modifier.padding(12.dp)) {
        OutlinedTextField(
            filter,
            changeFilter,
            placeholder = { Text("Filter preview") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("rename-preview-filter"),
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
            trailingIcon = {
                if (filter.isNotEmpty())
                    IconButton(onClick = { changeFilter("") }) {
                        Icon(Icons.Outlined.Close, "Clear preview filter", Modifier.size(18.dp))
                    }
            },
        )
        if (filter.isNotBlank())
            Text(
                "Filtering does not change the rename selection.",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${rows.size} of ${state.inputs.size}",
                Modifier.weight(1f),
                color = Muted,
                fontSize = 11.sp,
            )
            TextButton(
                onClick = { state.includeAll(true) },
                enabled = !state.loading && !state.applying,
            ) {
                Text("All", fontSize = 11.sp)
            }
            TextButton(
                onClick = { state.includeAll(false) },
                enabled = !state.loading && !state.applying,
            ) {
                Text("None", fontSize = 11.sp)
            }
            if ((plan?.errors ?: 0) > 0)
                TextButton(onClick = state::excludeErrors, enabled = !state.applying) {
                    Text("Exclude issues", fontSize = 11.sp)
                }
        }
        Row(Modifier.fillMaxWidth().padding(start = 44.dp, end = 44.dp, bottom = 6.dp)) {
            Text("CURRENT", Modifier.weight(1f), color = Muted, fontSize = 10.sp)
            Text("RESULT", Modifier.weight(1f), color = Muted, fontSize = 10.sp)
        }
        HorizontalDivider(color = Border)
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("rename-preview-list"),
        ) {
            itemsIndexed(rows, key = { _, row -> row.input.id }) { _, row ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        row.included,
                        { state.toggle(row.input.id) },
                        enabled = !state.applying && !state.loading,
                        modifier =
                            Modifier.semantics {
                                contentDescription =
                                    "Include ${row.input.name}, ${row.input.parentName}"
                            },
                    )
                    Row(
                        Modifier.weight(1f)
                            .clickable { details(row.input.id) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val entry =
                                remember(row.input) {
                                    Entry(
                                        java.io.File(row.input.id),
                                        name = row.input.name,
                                        folder = row.input.folder,
                                        size = row.input.bytes ?: 0,
                                        modified = row.input.modified ?: 0,
                                        writable = false,
                                        canRename = false,
                                        canDelete = false,
                                        mimeType = row.input.mime,
                                    )
                                }
                            FileGlyph(entry, 20.dp, thumbnail = false)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.input.name,
                                    fontSize = 12.sp,
                                    color = if (row.included) Ink else Muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    row.input.parentName,
                                    fontSize = 10.sp,
                                    color = Muted,
                                    maxLines = 1,
                                )
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    !row.included -> "Excluded"
                                    row.error != null -> row.name ?: "Needs attention"
                                    row.changed -> row.name!!
                                    else -> "Unchanged"
                                },
                                color =
                                    if (row.error != null) MaterialTheme.colorScheme.error
                                    else if (row.changed) Blue else Muted,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            row.error?.let {
                                Text(
                                    it,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Box {
                        val index = positions.getValue(row.input.id)
                        IconButton(
                            onClick = { orderMenu = row.input.id },
                            enabled = !state.applying && !state.loading,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                "Order " + row.input.name + ", " + row.input.parentName,
                                Modifier.size(18.dp),
                                tint = Muted,
                            )
                        }
                        DropdownMenu(orderMenu == row.input.id, { orderMenu = null }) {
                            DropdownMenuItem(
                                text = { Text("Move up") },
                                onClick = {
                                    state.move(row.input.id, -1)
                                    orderMenu = null
                                },
                                enabled = index > 0,
                            )
                            DropdownMenuItem(
                                text = { Text("Move down") },
                                onClick = {
                                    state.move(row.input.id, 1)
                                    orderMenu = null
                                },
                                enabled = index < plan!!.rows.lastIndex,
                            )
                        }
                    }
                }
                HorizontalDivider(color = Border)
            }
        }
    }
}

@Composable
private fun RenameField(
    value: String,
    label: String,
    enabled: Boolean,
    limit: Int = 512,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        { change(renameFieldText(it, limit)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RenameNumber(
    value: Long,
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    change: (Long) -> Unit,
) {
    var text by remember(value) { mutableStateOf(if (value < 0) "" else value.toString()) }
    OutlinedTextField(
        text,
        {
            text = it.take(20)
            change(text.toLongOrNull() ?: -1)
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun RenameToggle(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(selected, null, enabled = enabled)
        Text(label, color = Ink, fontSize = 12.sp)
    }
}

@Composable
private fun <T> RenameChoice(
    label: String,
    description: String,
    selected: T,
    values: List<T>,
    title: (T) -> String,
    enabled: Boolean = true,
    choose: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Muted, fontSize = 11.sp)
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = description
                    },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    title(selected),
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
            }
            DropdownMenu(open, { open = false }, Modifier.heightIn(max = 320.dp)) {
                values.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(title(value), fontSize = 13.sp) },
                        onClick = {
                            choose(value)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
