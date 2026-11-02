package dev.findroid

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class IncomingItem(
    val id: Int,
    val uri: Uri?,
    val name: String,
    val mime: String = "application/octet-stream",
    val bytes: Long? = null,
    val selected: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val adjusted: Boolean = false,
    val renamed: Boolean = false,
)

internal data class IncomingPayload(
    val uris: List<Uri> = emptyList(),
    val text: String? = null,
    val error: String? = null,
)

internal data class IncomingSaveResult(
    val saved: List<String>,
    val errors: List<Pair<Int, String>>,
    val cancelled: Boolean = false,
)

internal data class IncomingFolder(
    val path: String,
    val title: String,
    val children: List<Entry> = emptyList(),
    val writable: Boolean = false,
    val loading: Boolean = true,
    val note: String? = null,
)

internal fun incomingFileName(original: String): String {
    fun unsafe(c: Char) = c == '/' || c == '\u0000' || c.isISOControl() ||
        c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069'
    val adjusted = original.any(::unsafe)
    var at = if (adjusted) original.indexOfFirst { it != '.' }.let { if (it < 0) original.length else it } else 0
    var bytes = 0
    // Provider display names are untrusted. Never repeatedly encode and trim a huge name.
    val value = buildString {
        while (at < original.length) {
            val code = original.codePointAt(at)
            val next = if (code <= Char.MAX_VALUE.code && unsafe(code.toChar())) "_"
                else String(Character.toChars(code))
            val size = next.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 240) break
            append(next)
            bytes += size
            at += Character.charCount(code)
        }
    }
    return validateFilename(
        if (value.isBlank() || value in setOf(".", "..") || isWorkingFileName(value)) "Shared file"
        else value,
    )
}

internal fun incomingPayload(intent: Intent): IncomingPayload = runCatching {
    require(intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
        "No shared files were supplied."
    }
    val uris = linkedSetOf<Uri>()
    if (intent.action == Intent.ACTION_SEND)
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let(uris::add)
    else
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let(uris::addAll)
    intent.clipData?.let { clip ->
        require(clip.itemCount <= 1000) { "Share up to 1,000 files at a time." }
        for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
    }
    require(uris.size <= 1000) { "Share up to 1,000 files at a time." }
    val text =
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    require(text == null || text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
        "Shared text is too large. Share it as a file instead."
    }
    require(uris.isNotEmpty() || !text.isNullOrEmpty()) {
        "This app didn't provide a file or text to save."
    }
    IncomingPayload(uris.toList(), text?.takeIf { it.isNotEmpty() })
}
    .getOrElse {
        IncomingPayload(error = it.message ?: "Couldn't read this share. Try sharing it again.")
    }

internal class IncomingShareModel(app: Application) : AndroidViewModel(app) {
    val explorer = (app as FinderApplication).model
    private val ownerPackage = app.packageName
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("incoming-share", 0)
    private var initialized = false
    private var eligibleMetadata = emptySet<Int>()
    private var payload = IncomingPayload()
    private var metadataControl = ListingControl()
    private var metadataJob: Job? = null
    private var folderControl = ListingControl()
    private var folderJob: Job? = null
    private var folderGeneration = 0
    private var ownTransfer: TransferControl? = null
    var items by mutableStateOf<List<IncomingItem>>(emptyList())
        private set

    var folder by mutableStateOf(IncomingFolder(explorer.workspace.path, "Workspace"))
        private set

    var saving by mutableStateOf(false)
        private set

    var result by mutableStateOf<IncomingSaveResult?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var interrupted by mutableStateOf(false)
        private set

    val selected
        get() = items.filter { it.selected && it.error == null && !it.loading }

    val text
        get() = payload.text

    val textOnly
        get() = payload.text != null && payload.uris.isEmpty()

    val metadataLoading
        get() = items.any { it.loading }

    val canRetryMetadata
        get() = items.any { it.id in eligibleMetadata && it.error != null }

    fun initialize(intent: Intent, restored: Bundle?) {
        if (initialized) return
        initialized = true
        payload = incomingPayload(intent)
        message = payload.error
        interrupted =
            restored?.getBoolean("saveWasRunning") == true ||
                restored?.getBoolean("saveWasCompleted") == true
        val requested =
            restored?.getString("incomingFolder")
                ?: prefs.getString("folder", explorer.workspace.path)!!
        browse(requested)
        items =
            payload.uris.mapIndexed { i, uri ->
                val error =
                    when {
                        uri.scheme != "content" || uri.toString().length > 16_384 ->
                            "This app didn't provide a readable file. Share it again from Files."
                        uri.authority in
                            setOf("${ownerPackage}.files", "${ownerPackage}.documents") ->
                            "This file is already managed by Findroid. Use Copy to folder in the explorer."
                        else -> null
                    }
                IncomingItem(
                    i,
                    uri,
                    "Shared file ${i + 1}",
                    selected = error == null,
                    loading = error == null,
                    error = error,
                )
            } +
                if (payload.text != null)
                    listOf(
                        IncomingItem(
                            payload.uris.size,
                            null,
                            "Shared text.txt",
                            "text/plain",
                            payload.text!!.toByteArray().size.toLong(),
                            selected = payload.uris.isEmpty(),
                        ),
                    )
                else emptyList()
        val selectedBefore = restored?.getIntegerArrayList("incomingSelected")?.toSet()
        val namesBefore = restored?.getStringArrayList("incomingNames").orEmpty()
        items = items.map { item ->
            val name =
                namesBefore
                    .getOrNull(item.id)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { validateFilename(it) }.getOrNull() }
            item.copy(
                name = name ?: item.name,
                renamed = name != null,
                selected =
                    item.error == null && (selectedBefore?.contains(item.id) ?: item.selected),
            )
        }
        eligibleMetadata =
            items.filter { it.uri != null && it.error == null && it.loading }.map { it.id }.toSet()
        loadMetadata()
    }

    fun saveState(out: Bundle) {
        out.putIntegerArrayList(
            "incomingSelected",
            ArrayList(items.filter { it.selected }.map { it.id }),
        )
        out.putStringArrayList(
            "incomingNames",
            ArrayList(items.map { if (it.renamed) it.name else "" }),
        )
        out.putString("incomingFolder", folder.path)
        out.putBoolean("saveWasRunning", saving || interrupted)
        out.putBoolean("saveWasCompleted", result != null)
    }

    fun select(id: Int, selected: Boolean) {
        if (!saving) items = items.map { if (it.id == id) it.copy(selected = selected) else it }
    }

    fun rename(id: Int, name: String) {
        validateFilename(name)
        require(!isWorkingFileName(name)) { "That name is reserved for working files." }
        if (!saving)
            items = items.map {
                if (it.id == id) it.copy(name = name, adjusted = false, renamed = true) else it
            }
    }

    fun retryMetadata() {
        if (saving) return
        items = items.map {
            if (it.id in eligibleMetadata && it.error != null)
                it.copy(error = null, loading = true, selected = true)
            else it
        }
        loadMetadata()
    }

    private fun loadMetadata() {
        metadataControl.cancel()
        metadataJob?.cancel()
        val token = ListingControl().also { metadataControl = it }
        metadataJob = viewModelScope.launch {
            val slots = Semaphore(4)
            coroutineScope {
                items
                    .filter { it.uri != null && it.error == null && it.loading }
                    .forEach { original ->
                        launch {
                            val loaded = slots.withPermit {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val uri = original.uri!!
                                        var name = original.name
                                        var size: Long? = null
                                        token.withSignal { signal ->
                                            resolver
                                                .query(
                                                    uri,
                                                    arrayOf(
                                                        OpenableColumns.DISPLAY_NAME,
                                                        OpenableColumns.SIZE,
                                                    ),
                                                    null,
                                                    null,
                                                    null,
                                                    signal,
                                                )
                                                ?.use { c ->
                                                    if (c.moveToFirst()) {
                                                        c.getColumnIndex(
                                                                OpenableColumns.DISPLAY_NAME,
                                                            )
                                                            .takeIf { it >= 0 && !c.isNull(it) }
                                                            ?.let { name = c.getString(it) }
                                                        c.getColumnIndex(OpenableColumns.SIZE)
                                                            .takeIf { it >= 0 && !c.isNull(it) }
                                                            ?.let {
                                                                size =
                                                                    c.getLong(it).takeIf { it >= 0 }
                                                            }
                                                    }
                                                }
                                        }
                                        token.check()
                                        val safe =
                                            if (original.renamed) original.name
                                            else incomingFileName(name)
                                        original.copy(
                                            name = safe,
                                            bytes = size,
                                            mime =
                                                resolver.getType(uri) ?: "application/octet-stream",
                                            loading = false,
                                            adjusted = !original.renamed && safe != name,
                                        )
                                    }
                                        .getOrElse { error ->
                                            token.check()
                                            original.copy(
                                                loading = false,
                                                selected = false,
                                                error =
                                                    if (error is SecurityException)
                                                        "Access wasn't granted. Share this file again."
                                                    else
                                                        "File details are unavailable. Retry or share this file again.",
                                            )
                                        }
                                }
                            }
                            token.check()
                            items = items.map { if (it.id == original.id) loaded else it }
                        }
                    }
            }
        }
    }

    fun browse(path: String) {
        if (saving) return
        folderControl.cancel()
        folderJob?.cancel()
        val generation = ++folderGeneration
        val token = ListingControl().also { folderControl = it }
        folder = IncomingFolder(path, explorer.title(path))
        folderJob = viewModelScope.launch {
            val loaded =
                withContext(Dispatchers.IO) {
                    runCatching {
                        explorer.documents.refreshGrantState()
                        check(explorer.allowedPath(path)) {
                            "Reconnect this folder or choose Workspace."
                        }
                        val info = explorer.documents.read(path, token)
                        check(info.folder) { "Choose a folder." }
                        val children = mutableListOf<Entry>()
                        var scanned = 0
                        var limited = false
                        val reply =
                            explorer.documents.visitChildren(path, token) { entry ->
                                if (++scanned > 20_000 || children.size >= 2000) {
                                    limited = true
                                    false
                                } else {
                                    if (entry.folder && !isWorkingFileName(entry.name))
                                        children.add(entry)
                                    true
                                }
                            }
                        token.check()
                        IncomingFolder(
                            path,
                            info.name,
                            children.sortedWith(
                                compareBy<Entry, String>(NaturalFilenameOrder) { it.name }.thenBy { it.path },
                            ),
                            info.writable,
                            false,
                            reply.error
                                ?: if (limited)
                                    "Folder list is limited. Open a subfolder or connect a specific destination."
                                else if (reply.loading)
                                    "The provider is still loading. Refresh to check again."
                                else reply.info,
                        )
                    }
                        .getOrElse { error ->
                            token.check()
                            IncomingFolder(
                                path,
                                explorer.title(path),
                                loading = false,
                                note = error.message ?: "Folder unavailable",
                            )
                        }
                }
            if (generation == folderGeneration) folder = loaded
        }
    }

    fun connectDestination(uri: Uri) {
        if (saving) return
        val generation = ++folderGeneration
        folderControl.cancel()
        folderJob?.cancel()
        folder = folder.copy(loading = true, writable = false, note = "Connecting folder…")
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (uri.authority == "${ownerPackage}.documents")
                        (getApplication<Application>() as FinderApplication)
                            .workspaceDocuments
                            .ids
                            .file(android.provider.DocumentsContract.getTreeDocumentId(uri))
                            .path
                    else explorer.documents.connect(uri).uri
                }
            }
                .onSuccess { path ->
                    explorer.session.connectedLocations.value = explorer.documents.locations
                    explorer.storage.refresh(explorer.hasAccess)
                    if (generation == folderGeneration) browse(path)
                }
                .onFailure { error ->
                    if (generation == folderGeneration)
                        folder =
                            folder.copy(
                                loading = false,
                                note = error.message ?: "Couldn't connect folder",
                            )
                }
        }
    }

    fun createFolder(name: String) {
        if (!folder.writable || saving || explorer.busy != null) return
        val parent = folder.path
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    WorkspaceMutationGate.change {
                        explorer.documents.create(parent, validateFilename(name), true)
                    }
                }
            }
                .onSuccess {
                    if (folder.path == parent) browse(it)
                    else message = "Folder created in the previous destination"
                }
                .onFailure { message = it.message ?: "Couldn't create folder" }
        }
    }

    fun save() {
        val selected = selected
        if (selected.isEmpty() || !folder.writable || saving || explorer.busy != null) return
        saving = true
        result = null
        message = null
        interrupted = false
        val destination = folder.path
        explorer.saveIncoming(
            selected,
            destination,
            payload.text,
            onStarted = { ownTransfer = it },
        ) { outcome ->
            ownTransfer = null
            saving = false
            result = outcome
            prefs.edit().putString("folder", destination).apply()
        }
    }

    fun retryFailed() {
        val ids = result?.errors?.map { it.first }?.toSet().orEmpty()
        items = items.map { it.copy(selected = it.id in ids) }
        result = null
    }

    fun stop() {
        ownTransfer?.cancel()
    }

    fun clearMessage() {
        message = null
    }

    override fun onCleared() {
        metadataControl.cancel()
        folderControl.cancel()
        super.onCleared()
    }
}
