package dev.findroid

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.runtime.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

private val storageProbeDispatcher = Dispatchers.IO.limitedParallelism(4)

internal class StorageMonitor(
    private val context: Context,
    private val workspace: File,
    private val tablet: File,
    private val documents: DocumentAccess,
) {
    var items by mutableStateOf<List<StorageStatus>>(emptyList())
        private set

    var checking by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var control = ListingControl()
    private var generation = 0
    private val prefs = context.getSharedPreferences("storage-status", 0)

    fun stop() {
        generation++
        control.cancel()
        job?.cancel()
        checking = false
        items = items.map {
            if (it.health == StorageHealth.CHECKING)
                it.copy(
                    health = StorageHealth.STOPPED,
                    detail = "Refresh to check this location again.",
                )
            else it
        }
    }

    fun refresh(hasAccess: Boolean) {
        stop()
        val ticket = generation
        val token = ListingControl().also { control = it }
        checking = true
        job = scope.launch {
            try {
                val targets =
                    withContext(Dispatchers.IO) {
                        documents.refreshGrantState()
                        discover()
                    }
                if (ticket != generation) return@launch
                items = targets.map { StorageStatus(it, StorageHealth.CHECKING) }
                val slots = Semaphore(4)
                coroutineScope {
                    targets.forEach { target ->
                        launch {
                            val status = slots.withPermit {
                                withContext(storageProbeDispatcher) {
                                    probe(target, hasAccess, token)
                                }
                            }
                            if (ticket == generation)
                                items = items.map { if (it.target.id == target.id) status else it }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (ticket == generation)
                    items = items.map {
                        if (it.health == StorageHealth.CHECKING)
                            it.copy(
                                health = StorageHealth.UNAVAILABLE,
                                detail = error.message ?: "Couldn't check storage.",
                            )
                        else it
                    }
            } finally {
                if (ticket == generation) checking = false
            }
        }
    }

    private fun discover(): List<StorageTarget> {
        val saved = runCatching {
            val array = JSONArray(prefs.getString("volumes", "[]"))
            (0 until array.length()).map { array.getJSONObject(it) }
        }
            .getOrDefault(emptyList())
            .associateBy { it.getString("id") }
            .toMutableMap()
        val current = context.getSystemService(StorageManager::class.java).storageVolumes
        current
            .filter { !it.isPrimary }
            .forEach { v ->
                val id = v.uuid ?: v.directory?.path ?: return@forEach
                val path = v.directory?.path ?: saved[id]?.optString("path") ?: return@forEach
                saved[id] =
                    JSONObject()
                        .put("id", id)
                        .put("path", path)
                        .put("name", v.getDescription(context))
            }
        prefs.edit().putString("volumes", JSONArray(saved.values.toList()).toString()).apply()
        return buildList {
            add(StorageTarget("workspace", workspace.path, "Workspace", StorageKind.WORKSPACE))
            add(
                StorageTarget(
                    "tablet",
                    tablet.path,
                    "This device",
                    StorageKind.TABLET,
                    current.firstOrNull { it.isPrimary }?.state,
                ),
            )
            saved.forEach { (id, s) ->
                val volume = current.firstOrNull { it.uuid == id || it.directory?.path == id }
                add(
                    StorageTarget(
                        "volume:$id",
                        s.getString("path"),
                        s.getString("name"),
                        StorageKind.VOLUME,
                        volume?.state ?: Environment.MEDIA_REMOVED,
                    ),
                )
            }
            documents.locations.forEach {
                add(StorageTarget(it.uri, it.uri, it.name, StorageKind.PROVIDER, grant = it.grant))
            }
        }
    }

    internal fun probe(
        target: StorageTarget,
        access: Boolean,
        token: ListingControl,
    ): StorageStatus {
        token.check()
        if (target.kind != StorageKind.PROVIDER) {
            if (
                target.kind == StorageKind.VOLUME &&
                    target.state !in
                        setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)
            )
                return StorageStatus(
                    target,
                    StorageHealth.UNAVAILABLE,
                    "Reconnect this drive, then refresh.",
                )
            if (target.kind != StorageKind.WORKSPACE && !access)
                return StorageStatus(
                    target,
                    StorageHealth.NEEDS_ACCESS,
                    "Allow device storage access, or connect a specific folder using Android's picker.",
                )
            val folder = File(target.path)
            if (!folder.isDirectory || !folder.canRead())
                return StorageStatus(
                    target,
                    StorageHealth.UNAVAILABLE,
                    "This storage isn't readable. Reconnect it and refresh.",
                )
            val writable = folder.canWrite() && target.state != Environment.MEDIA_MOUNTED_READ_ONLY
            val stat = runCatching { StatFs(folder.path) }.getOrNull()
            return StorageStatus(
                target,
                if (writable) StorageHealth.AVAILABLE else StorageHealth.READ_ONLY,
                if (target.kind == StorageKind.WORKSPACE) "Private app storage"
                else if (writable) "Shared storage" else "Android reports read-only storage",
                stat?.availableBytes,
                stat?.totalBytes,
                create = writable,
            )
        }
        val uri = Uri.parse(target.path)
        val provider = context.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)
        if (provider == null)
            return StorageStatus(
                target,
                StorageHealth.PROVIDER_MISSING,
                "Install or enable the app that provides this location, then reconnect.",
            )
        val label = runCatching {
            provider.loadLabel(context.packageManager).toString()
        }
            .getOrDefault(uri.authority.orEmpty())
        try {
            val entry = documents.read(target.path, token)
            check(entry.folder) { "This saved location is no longer a folder." }
            val grants = documents.grantState(target.path)
            val writable = grants?.second != false
            val health =
                when {
                    !writable -> StorageHealth.READ_ONLY
                    entry.writable -> StorageHealth.AVAILABLE
                    entry.canRename || entry.canDelete -> StorageHealth.LIMITED
                    else -> StorageHealth.READ_ONLY
                }
            return StorageStatus(
                target,
                health,
                if (entry.writable) "Folder access is available"
                else "Creating items isn't supported here",
                available = documents.reportedAvailableBytes(target.path, token),
                create = entry.writable,
                rename = entry.canRename,
                delete = entry.canDelete,
                provider = label,
                grantNote =
                    if (grants?.first == true)
                        "Saved read${if(grants.second)" and write"else "-only"} permission"
                    else "Access is available; no saved grant is recorded",
            )
        } catch (error: Exception) {
            token.check()
            return StorageStatus(
                target,
                storageFailure(
                    target.kind,
                    error is SecurityException && documents.grantState(target.path)?.first != true,
                ),
                if (error is SecurityException && documents.grantState(target.path)?.first != true)
                    "Folder permission is missing or was revoked. Choose the original folder again."
                else
                    "The provider couldn't open this folder. Check the provider app, account, network or drive, then retry.",
                provider = label,
            )
        }
    }

    fun issue(path: String, error: Throwable, hasAccess: Boolean): StorageIssue {
        if (isDocumentPath(path)) {
            val root = documents.locations.firstOrNull { documents.sameTree(it.grant, path) }
            return StorageIssue(
                if (error is SecurityException && documents.grantState(path)?.first != true)
                    "Folder access expired"
                else "Location unavailable",
                if (error is SecurityException && documents.grantState(path)?.first != true)
                    "Choose this folder again to restore Android's permission."
                else
                    "The folder or provider couldn't be reached. Check its app, account, network or connected drive.",
                root,
            )
        }
        val file = runCatching { File(path).canonicalFile }.getOrDefault(File(path))
        val roots =
            listOf(tablet.path) +
                items.filter { it.target.kind == StorageKind.VOLUME }.map { it.target.path }
        if (
            protectedStoragePath(file.path, roots, context.packageName) ||
                (file.path.startsWith("/data/") && !file.toPath().startsWith(workspace.toPath()))
        )
            return StorageIssue(
                "Protected by Android",
                "Other apps' private data and system folders aren't exposed by Android. Choose shared storage or Workspace.",
                protected = true,
            )
        return if (!hasAccess && roots.any { file.path == it || file.path.startsWith("$it/") })
            StorageIssue(
                "Storage access needed",
                "Allow device storage access, or choose a specific folder through Android's picker.",
                needsAccess = true,
            )
        else
            StorageIssue(
                "Folder unavailable",
                "This folder may have moved, been removed, or belong to a disconnected drive. Check Storage locations and retry.",
            )
    }
}
