package dev.findroid

import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun StorageLocationsDialog(
    model: FinderModel,
    grant: () -> Unit,
    connect: () -> Unit,
    reconnect: (ConnectedLocation) -> Unit,
    providerApp: (String) -> Unit,
    dismiss: () -> Unit,
) {
    val monitor = model.storage
    val items = monitor.items
    var selected by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf<ConnectedLocation?>(null) }
    val focused =
        items.firstOrNull { it.target.id == selected }
            ?: items.firstOrNull { it.target.path == model.location }
            ?: items.firstOrNull()
    LaunchedEffect(Unit) { monitor.refresh(model.hasAccess) }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val config = LocalConfiguration.current
        val narrow = config.screenWidthDp < 800
        Surface(
            Modifier.width(
                    (config.screenWidthDp * (if (narrow) .96f else .88f)).coerceAtMost(720f).dp,
                )
                .height((config.screenHeightDp * .86f).coerceAtMost(480f).dp)
                .testTag("storage-locations"),
            shape = RoundedCornerShape(12.dp),
            color = CanvasBackground,
            shadowElevation = 18.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Storage, null, tint = Blue)
                    Text(
                        "Storage locations",
                        Modifier.weight(1f).padding(start = 12.dp),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )
                    if (monitor.checking)
                        TextButton(onClick = monitor::stop) { Text("Stop", fontSize = 12.sp) }
                    ToolButton(
                        Icons.Outlined.Refresh,
                        "Refresh storage status",
                        { monitor.refresh(model.hasAccess) },
                    )
                    ToolButton(Icons.Outlined.Close, "Close storage locations", dismiss)
                }
                HorizontalDivider(color = Border)
                if (narrow) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().testTag("storage-list"),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(items, key = { it.target.id }) { status ->
                            StorageCard(
                                status,
                                model,
                                grant,
                                connect,
                                reconnect,
                                providerApp,
                                { remove = it },
                                dismiss,
                            )
                        }
                    }
                } else
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            Modifier.width(210.dp)
                                .fillMaxHeight()
                                .background(Sidebar)
                                .testTag("storage-list"),
                            contentPadding = PaddingValues(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(items, key = { it.target.id }) { status ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            if (focused?.target?.id == status.target.id)
                                                SelectionSurface
                                            else Sidebar,
                                            RoundedCornerShape(7.dp),
                                        )
                                        .clickable { selected = status.target.id }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        storageIcon(status.target.kind),
                                        null,
                                        tint =
                                            if (focused?.target?.id == status.target.id) Blue
                                            else Muted,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(
                                            status.target.name,
                                            color = Ink,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            status.health.label,
                                            color = if (status.canOpen) Muted else Blue,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp,
                                            modifier = Modifier.padding(top = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                        VerticalDivider(color = Border)
                        focused?.let { status ->
                            Column(
                                Modifier.weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(18.dp),
                            ) {
                                StorageCard(
                                    status,
                                    model,
                                    grant,
                                    connect,
                                    reconnect,
                                    providerApp,
                                    { remove = it },
                                    dismiss,
                                )
                            }
                        }
                    }
                HorizontalDivider(color = Border)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Android controls folder permissions and drive availability.",
                        Modifier.weight(1f),
                        fontSize = 10.sp,
                        color = Muted,
                    )
                    TextButton(onClick = connect) { Text("Connect folder…", fontSize = 12.sp) }
                }
            }
        }
    }
    remove?.let { root ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Remove saved connection?") },
            text = { Text("Findroid will release its folder permission for ${root.name}.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.disconnectLocation(root.uri)
                        remove = null
                    },
                ) {
                    Text("Remove connection")
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun storageIcon(kind: StorageKind) =
    when (kind) {
        StorageKind.WORKSPACE -> Icons.Outlined.Workspaces
        StorageKind.TABLET -> Icons.Outlined.TabletMac
        StorageKind.VOLUME -> Icons.Outlined.Usb
        StorageKind.PROVIDER -> Icons.Outlined.FolderShared
    }

@Composable
private fun StorageCard(
    status: StorageStatus,
    model: FinderModel,
    grant: () -> Unit,
    connect: () -> Unit,
    reconnect: (ConnectedLocation) -> Unit,
    providerApp: (String) -> Unit,
    remove: (ConnectedLocation) -> Unit,
    dismiss: () -> Unit,
) {
    val target = status.target
    val root = model.connectedLocations.firstOrNull { it.uri == target.path }
    Column(Modifier.fillMaxWidth().testTag("storage-card-${target.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(storageIcon(target.kind), null, Modifier.size(28.dp), tint = Blue)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(target.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    status.health.label,
                    color = Blue,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (status.health == StorageHealth.CHECKING)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp))
        Text(
            status.detail,
            Modifier.padding(top = 12.dp),
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        status.provider?.let { StorageField("Provider", it) }
        status.grantNote?.let { StorageField("Permission", it) }
        if (target.kind != StorageKind.PROVIDER) StorageField("Folder", target.path)
        StorageField(
            "Space",
            if (status.available != null && status.total != null)
                "${formatSize(status.available)} available of ${formatSize(status.total)}"
            else if (status.available != null)
                "${formatSize(status.available)} available (provider storage)"
            else if (target.kind == StorageKind.PROVIDER) "Not reported or not accessible"
            else "Unavailable",
        )
        if (status.canOpen) {
            Text(
                "Folder capabilities",
                Modifier.padding(top = 12.dp),
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = Ink,
            )
            Text(
                "Read · ${if(status.create)"Create" else "No create"}${if(target.kind==StorageKind.PROVIDER)" · ${if(status.rename)"Rename"else "No rename"} · ${if(status.delete)"Delete"else "No delete"}"else ""}",
                Modifier.padding(top = 6.dp),
                fontSize = 11.sp,
                color = Muted,
            )
            if (target.kind == StorageKind.PROVIDER)
                Text(
                    "Individual files can have different capabilities.",
                    Modifier.padding(top = 6.dp),
                    fontSize = 10.sp,
                    color = Muted,
                )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status.canOpen)
                Button(
                    onClick = {
                        if (target.kind == StorageKind.VOLUME) model.refreshStorageVolumes()
                        model.navigate(target.path)
                        dismiss()
                    },
                ) {
                    Text("Open folder", fontSize = 12.sp)
                }
            else if (status.health == StorageHealth.NEEDS_ACCESS)
                Button(onClick = grant) { Text("Allow device access", fontSize = 12.sp) }
            else if (root != null && status.health == StorageHealth.GRANT_REQUIRED)
                Button(onClick = { reconnect(root) }) {
                    Text("Reconnect folder…", fontSize = 12.sp)
                }
            else
                TextButton(
                    onClick = { model.storage.refresh(model.hasAccess) },
                    enabled = !model.storage.checking,
                ) {
                    Text("Try again", fontSize = 12.sp)
                }
            if (root != null && status.health != StorageHealth.GRANT_REQUIRED)
                TextButton(onClick = { reconnect(root) }) { Text("Reconnect…", fontSize = 12.sp) }
            else if (target.kind in setOf(StorageKind.TABLET, StorageKind.VOLUME))
                TextButton(onClick = connect) { Text("Folder access…", fontSize = 12.sp) }
            if (root != null) {
                Spacer(Modifier.weight(1f))
                ToolButton(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    "Provider app",
                    {
                        Uri.parse(root.uri).authority?.let(providerApp)
                    },
                )
                ToolButton(
                    Icons.Outlined.LinkOff,
                    "Remove connection",
                    { remove(root) },
                    enabled = model.busy == null,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StorageField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            label,
            Modifier.width(76.dp).padding(end = 8.dp),
            fontSize = 11.sp,
            lineHeight = 18.sp,
            color = Muted,
        )
        Text(value, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp, color = Ink)
    }
}

@Composable
internal fun StorageRecovery(model: FinderModel, issue: StorageIssue, grant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp).testTag("storage-recovery"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (issue.protected) Icons.Outlined.Lock else Icons.Outlined.FolderOff,
            null,
            Modifier.size(38.dp),
            tint = Muted,
        )
        Text(
            issue.title,
            Modifier.padding(top = 16.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = Ink,
        )
        Text(
            issue.detail,
            Modifier.widthIn(max = 430.dp).padding(top = 10.dp),
            fontSize = 12.sp,
            color = Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Row(Modifier.padding(top = 12.dp)) {
            if (issue.needsAccess)
                OutlinedButton(onClick = grant) { Text("Allow device access", fontSize = 12.sp) }
            else
                OutlinedButton(onClick = { model.storageRequested = true }) {
                    Text("Storage locations", fontSize = 12.sp)
                }
            TextButton(onClick = model::refresh) { Text("Try again", fontSize = 12.sp) }
        }
        TextButton(onClick = { model.navigate(model.workspace.path) }) {
            Text("Open Workspace", fontSize = 12.sp)
        }
    }
}
