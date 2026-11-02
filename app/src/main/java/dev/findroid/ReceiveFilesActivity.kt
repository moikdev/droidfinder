@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.findroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import java.io.File

/** Holds incoming temporary URI access until copy streams have closed; no automatic saves. */
class ReceiveFilesActivity : ComponentActivity() {
    internal val incoming by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[
            IncomingShareModel::class.java]
    }

    private val chooseFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(incoming::connectDestination)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming.explorer.onResume()
        incoming.initialize(intent, savedInstanceState)
        setContent {
            DisposableEffect(incoming.explorer.darkTheme) {
                val controller =
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !incoming.explorer.darkTheme
                controller.isAppearanceLightNavigationBars = !incoming.explorer.darkTheme
                onDispose {}
            }
            IncomingShareScreen(
                incoming,
                close = { finish() },
                chooseFolder = { chooseFolder.launch(null) },
                openDestination = { path ->
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .putExtra("finderWindowId", java.util.UUID.randomUUID().toString())
                            .putExtra("finderLocation", path)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                            ),
                    )
                    finish()
                },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        incoming.saveState(outState)
        super.onSaveInstanceState(outState)
    }
}

@Composable
internal fun IncomingShareScreen(
    model: IncomingShareModel,
    close: () -> Unit,
    chooseFolder: () -> Unit,
    openDestination: (String) -> Unit,
) {
    val explorer = model.explorer
    val windowConfig = LocalConfiguration.current
    val phone = windowConfig.screenWidthDp < 600 || windowConfig.screenHeightDp < 480
    val short = windowConfig.screenHeightDp < 480
    var reviewPage by rememberSaveable { mutableIntStateOf(0) }
    var rename by remember { mutableStateOf<IncomingItem?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var closeWhenStopped by rememberSaveable { mutableStateOf(false) }
    fun requestClose() {
        if (model.saving) confirmStop = true else close()
    }
    BackHandler { requestClose() }
    LaunchedEffect(model.saving, closeWhenStopped) {
        if (closeWhenStopped && !model.saving) close()
    }
    CompositionLocalProvider(
        LocalFinderDark provides explorer.darkTheme,
        LocalFinderDensity provides if (phone) FinderDensity.COMFORTABLE else explorer.density,
        LocalPhoneLayout provides phone,
    ) {
        MaterialTheme(colorScheme = finderColors(explorer.darkTheme)) {
            Surface(Modifier.fillMaxSize(), color = Sidebar) {
                Box(
                    Modifier.fillMaxSize().systemBarsPadding().padding(if (phone) 8.dp else 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val config = LocalConfiguration.current
                    val wide = config.screenWidthDp >= 850
                    val compact = model.saving || model.result != null
                    val cardHeight =
                        if (phone) (config.screenHeightDp - 64f).coerceAtLeast(180f)
                        else
                            when {
                                model.saving -> 320f
                                model.result != null ->
                                    (288f + model.result!!.errors.size.coerceAtMost(4) * 36f)
                                        .coerceAtMost(440f)
                                else -> if (wide) 520f else 580f
                            }.coerceAtMost(config.screenHeightDp * .86f)
                    Surface(
                        Modifier.widthIn(max = if (compact) 600.dp else 860.dp)
                            .fillMaxWidth()
                            .height(cardHeight.dp)
                            .testTag("incoming-share"),
                        shape = RoundedCornerShape(14.dp),
                        color = CanvasBackground,
                        shadowElevation = 12.dp,
                    ) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = if (short) 48.dp else 64.dp)
                                    .padding(start = if (phone) 12.dp else 20.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.SaveAlt,
                                    null,
                                    tint = Blue,
                                    modifier = Modifier.size(25.dp),
                                )
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(
                                        "Save to Findroid",
                                        color = Ink,
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (model.textOnly) "Shared text"
                                        else
                                            "${model.items.size} shared file${if (model.items.size == 1) "" else "s"}",
                                        color = Muted,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                    )
                                }
                                ToolButton(
                                    Icons.Outlined.Close,
                                    "Close incoming share",
                                    ::requestClose,
                                )
                            }
                            HorizontalDivider(color = Border)
                            if (model.result != null) {
                                IncomingResult(model, Modifier.weight(1f), openDestination, close)
                            } else {
                                if (model.message != null || model.interrupted)
                                    Text(
                                        model.message
                                            ?: "The previous save may have been interrupted or already completed. Review the destination before saving another copy.",
                                        Modifier.fillMaxWidth()
                                            .background(SubtleSurface)
                                            .padding(14.dp),
                                        color = Muted,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                    )
                                if (model.saving) {
                                    val progress = explorer.transferProgress
                                    Column(
                                        Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            progress.phase.ifEmpty { "Saving shared files…" },
                                            color = Ink,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            progress.current,
                                            Modifier.padding(top = 10.dp),
                                            color = Muted,
                                            fontSize = 13.sp,
                                        )
                                        Spacer(Modifier.height(20.dp))
                                        progress.fraction?.let { fraction ->
                                            LinearProgressIndicator(
                                                progress = { fraction },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                                        Text(
                                            "${formatSize(progress.bytes)} copied",
                                            Modifier.padding(top = 12.dp),
                                            color = Muted,
                                            fontSize = 12.sp,
                                        )
                                        Text(
                                            "Completed files stay saved if you stop.",
                                            Modifier.padding(top = 12.dp),
                                            color = Muted,
                                            fontSize = 12.sp,
                                        )
                                    }
                                } else if (phone && short) {
                                    Row(Modifier.fillMaxWidth()) {
                                        FilterChip(
                                            reviewPage == 0,
                                            { reviewPage = 0 },
                                            label = { Text("Review files") },
                                            modifier =
                                                Modifier.weight(1f).padding(horizontal = 4.dp),
                                        )
                                        FilterChip(
                                            reviewPage == 1,
                                            { reviewPage = 1 },
                                            label = { Text("Destination") },
                                            modifier =
                                                Modifier.weight(1f).padding(horizontal = 4.dp),
                                        )
                                    }
                                    if (reviewPage == 0)
                                        IncomingFiles(
                                            model,
                                            Modifier.weight(1f).fillMaxWidth(),
                                            { rename = it },
                                        )
                                    else
                                        IncomingDestination(
                                            model,
                                            Modifier.weight(1f).fillMaxWidth(),
                                            { newFolder = true },
                                            chooseFolder,
                                        )
                                } else if (wide) {
                                    Row(Modifier.weight(1f).fillMaxWidth()) {
                                        IncomingFiles(
                                            model,
                                            Modifier.width(300.dp).fillMaxHeight(),
                                            { rename = it },
                                        )
                                        VerticalDivider(color = Border)
                                        IncomingDestination(
                                            model,
                                            Modifier.weight(1f).fillMaxHeight(),
                                            { newFolder = true },
                                            chooseFolder,
                                        )
                                    }
                                } else {
                                    Column(Modifier.weight(1f)) {
                                        IncomingFiles(
                                            model,
                                            Modifier.fillMaxWidth()
                                                .heightIn(max = if (phone) 170.dp else 230.dp),
                                            { rename = it },
                                        )
                                        HorizontalDivider(color = Border)
                                        IncomingDestination(
                                            model,
                                            Modifier.weight(1f).fillMaxWidth(),
                                            { newFolder = true },
                                            chooseFolder,
                                        )
                                    }
                                }
                                HorizontalDivider(color = Border)
                                if (phone)
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            if (model.saving) "Saving to ${model.folder.title}"
                                            else if (explorer.busy != null)
                                                "Wait for the current file operation to finish."
                                            else
                                                "To ${model.folder.title} · existing files are kept",
                                            color = Muted,
                                            fontSize = 11.sp,
                                            maxLines = if (short) 1 else 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            IncomingSaveButtons(model, close)
                                        }
                                    }
                                else
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            if (model.saving) "Saving to ${model.folder.title}"
                                            else if (explorer.busy != null)
                                                "Wait for the current file operation to finish."
                                            else
                                                "Existing files are kept; copies get numbered names.",
                                            Modifier.weight(1f),
                                            color = Muted,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp,
                                        )
                                        IncomingSaveButtons(model, close)
                                    }
                            }
                        }
                    }
                }
            }
            rename?.let { item ->
                NameDialog(
                    "Save as",
                    item.name,
                    "Use name",
                    { rename = null },
                    validation = ::incomingNameError,
                ) {
                    model.rename(item.id, it)
                    rename = null
                }
            }
            if (newFolder)
                NameDialog(
                    "New destination folder",
                    "Untitled folder",
                    "Create",
                    { newFolder = false },
                    validation = ::incomingNameError,
                ) {
                    model.createFolder(it)
                    newFolder = false
                }
            if (confirmStop)
                AlertDialog(
                    onDismissRequest = { confirmStop = false },
                    title = { Text("Stop saving these files?") },
                    text = {
                        Text(
                            "Completed files will remain in the destination. The unfinished file will be removed.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmStop = false
                                closeWhenStopped = true
                                model.stop()
                            },
                        ) {
                            Text("Stop and close")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmStop = false }) { Text("Keep saving") }
                    },
                )
        }
    }
}

@Composable
private fun IncomingFiles(
    model: IncomingShareModel,
    modifier: Modifier,
    rename: (IncomingItem) -> Unit,
) {
    Column(modifier.background(SubtleSurface).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Files to save",
                color = Ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(6.dp),
            )
            if (model.canRetryMetadata)
                ToolButton(
                    Icons.Outlined.Refresh,
                    "Retry shared file details",
                    model::retryMetadata,
                    compact = true,
                )
        }
        if (model.items.isEmpty())
            Text(
                "No readable files were supplied.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(6.dp),
            )
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(model.items, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        item.selected,
                        { model.select(item.id, it) },
                        enabled = item.error == null && !item.loading,
                        modifier =
                            Modifier.semantics { contentDescription = "Include ${item.name}" },
                    )
                    FileGlyph(
                        Entry(
                            File(item.name),
                            name = item.name,
                            folder = false,
                            size = item.bytes ?: 0L,
                            documentUri = item.uri,
                            mimeType = item.mime,
                            sizeKnown = item.bytes != null,
                        ),
                        30.dp,
                        thumbnail = false,
                    )
                    Column(Modifier.weight(1f).padding(start = 9.dp)) {
                        Text(
                            item.name,
                            color = Ink,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.error
                                ?: if (item.loading) "Checking file…"
                                else if (item.adjusted) "Name adjusted — you can rename it"
                                else item.bytes?.let(::formatSize) ?: "Size not provided",
                            color = Muted,
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            maxLines = 3,
                        )
                    }
                    if (!item.loading && item.error == null)
                        ToolButton(
                            Icons.Outlined.Edit,
                            "Rename incoming ${item.name}",
                            { rename(item) },
                            compact = true,
                        )
                }
            }
        }
        model.text?.let { text ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Border)
            Text(
                text.take(700),
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 7,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IncomingDestination(
    model: IncomingShareModel,
    modifier: Modifier,
    createFolder: () -> Unit,
    chooseFolder: () -> Unit,
) {
    val explorer = model.explorer
    val roots = buildList {
        add(explorer.workspace.path to "Workspace")
        if (explorer.hasAccess) add(explorer.deviceRoot.path to "This device")
        explorer.volumes.forEach { add(it.path to it.name) }
        explorer.connectedLocations.forEach { add(it.uri to it.name) }
        explorer.favorites.forEach { add(it to explorer.favoriteTitle(it)) }
    }
        .distinctBy { it.first }
    LazyColumn(modifier.fillMaxWidth().padding(14.dp).testTag("incoming-destination-folders")) {
        item {
            Text("Destination", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                roots.forEach { (path, title) ->
                    FilterChip(
                        selected = model.folder.path == path,
                        onClick = { model.browse(path) },
                        label = { Text(title, fontSize = 11.sp, maxLines = 1) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val parent = explorer.parentPath(model.folder.path)
                ToolButton(
                    Icons.Outlined.ArrowUpward,
                    "Parent save destination",
                    { parent?.let(model::browse) },
                    enabled = parent != null,
                )
                Text(
                    model.folder.title,
                    Modifier.weight(1f),
                    color = Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ToolButton(
                    Icons.Outlined.FolderOpen,
                    "Choose Android destination folder",
                    chooseFolder,
                )
                ToolButton(
                    Icons.Outlined.CreateNewFolder,
                    "New destination folder",
                    createFolder,
                    enabled = model.folder.writable && explorer.busy == null,
                )
                ToolButton(
                    Icons.Outlined.Refresh,
                    "Refresh save destination",
                    { model.browse(model.folder.path) },
                )
            }
            HorizontalDivider(color = Border)
            if (model.folder.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            model.folder.note?.let {
                Text(
                    it,
                    Modifier.padding(vertical = 8.dp),
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            if (!model.folder.loading && model.folder.children.isEmpty())
                Text(
                    if (model.folder.writable)
                        "Save here, or create a folder to organize these files."
                    else "Choose a writable destination.",
                    Modifier.padding(vertical = 16.dp),
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
        }
        items(model.folder.children, key = { it.path }) { entry ->
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { model.browse(entry.path) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolderGlyph(28.dp)
                Text(
                    entry.name,
                    Modifier.weight(1f).padding(horizontal = 10.dp),
                    color = Ink,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Outlined.ChevronRight,
                    null,
                    tint = Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun IncomingResult(
    model: IncomingShareModel,
    modifier: Modifier,
    open: (String) -> Unit,
    close: () -> Unit,
) {
    val result = model.result!!
    Column(modifier.fillMaxWidth().padding(if (LocalPhoneLayout.current) 12.dp else 24.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
            item {
                Icon(
                    if (result.errors.isEmpty()) Icons.Outlined.CheckCircle
                    else Icons.Outlined.Info,
                    null,
                    tint = Blue,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    if (result.cancelled) "Save stopped"
                    else if (result.errors.isEmpty()) "Saved to ${model.folder.title}"
                    else "Some files couldn't be saved",
                    Modifier.padding(top = 12.dp),
                    color = Ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${result.saved.size} saved · ${result.errors.size} not saved",
                    Modifier.padding(top = 8.dp),
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
            items(result.errors) { (id, error) ->
                Text(
                    "${model.items.firstOrNull { it.id == id }?.name ?: "File"}: $error",
                    Modifier.padding(vertical = 6.dp),
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (result.errors.isNotEmpty())
                TextButton(onClick = model::retryFailed) { Text("Select failed files") }
            TextButton(onClick = close) { Text("Done") }
            Button(onClick = { open(model.folder.path) }) { Text("Open destination") }
        }
    }
}

@Composable
private fun RowScope.IncomingSaveButtons(model: IncomingShareModel, close: () -> Unit) {
    val size = if (LocalPhoneLayout.current) Modifier.weight(1f) else Modifier
    if (model.saving) TextButton(onClick = model::stop, modifier = size) { Text("Stop saving") }
    else {
        TextButton(onClick = close, modifier = size) { Text("Cancel") }
        Button(
            onClick = model::save,
            modifier = size,
            enabled =
                model.selected.isNotEmpty() &&
                    !model.metadataLoading &&
                    model.folder.writable &&
                    !model.folder.loading &&
                    model.explorer.busy == null,
        ) {
            Text(
                if (model.interrupted) "Save another copy"
                else if (model.textOnly) "Save text"
                else "Save ${model.selected.size} file${if (model.selected.size == 1) "" else "s"}",
                maxLines = 2,
            )
        }
    }
}

private fun incomingNameError(name: String): String? = runCatching {
    validateFilename(name)
    require(!isWorkingFileName(name)) { "That name is reserved for working files." }
}
    .exceptionOrNull()
    ?.message
