package dev.findroid

import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class FolderSyncPreservationIntent(val original: String, val recovery: String)

/** Append-only manual evidence. No loading, replay, rollback, pruning or deletion is provided. */
internal class FolderSyncPublicationLog private constructor(
    val directory: String,
    private val parent: PartialFileStamp,
    private val owned: PartialFileStamp,
    private val resolvedParent: String,
    private val storage: PartialCopyStorage,
) {
    private val records = linkedMapOf<String, PartialFileStamp>()
    private data class Pending(val kind: String, val from: String, val to: String)
    private var pending: Pending? = null
    private var blocked = false
    private var finished = false
    private var bytesWritten = 0L

    private fun guard() {
        val parentPath = File(directory).parentFile!!.path
        check(storage.stat(parentPath)?.sameObject(parent) == true && storage.canonical(parentPath) == resolvedParent &&
            storage.stat(directory)?.sameObject(owned) == true && storage.canonical(directory) == File(resolvedParent, File(directory).name).path) {
            "The synchronization journal location changed. Keep its data for manual review."
        }
        val actual = java.nio.file.Files.newDirectoryStream(File(directory).toPath()).use { it.map { p -> p.toString() }.toSet() }
        check(actual == records.keys && records.all { (path, stamp) -> storage.stat(path) == stamp }) {
            "The synchronization journal changed. Keep its data for manual review."
        }
    }

    private fun append(record: JsonObject) {
        check(!blocked && !finished) { "This synchronization journal is closed or needs manual review." }
        try {
            guard()
            val bytes = record.toString().toByteArray(Charsets.UTF_8)
            check(records.size < 20_002 && bytes.size <= 64 * 1024 && bytesWritten + bytes.size <= 32L * 1024 * 1024) {
                "This synchronization journal needs a smaller plan."
            }
            val path = File(directory, records.size.toString().padStart(5, '0') + ".json").path
            storage.open(path, writable = true, createNew = true).use { handle ->
                val fresh = handle.stamp()
                check(fresh.regular && !fresh.directory && fresh.links == 1L && fresh.size == 0L && fresh.device == owned.device && storage.stat(path) == fresh)
                var offset = 0
                while (offset < bytes.size) {
                    val count = handle.write(bytes, offset, bytes.size - offset)
                    check(count in 1..(bytes.size - offset)); offset += count
                }
                val complete = handle.stamp()
                check(complete.sameObject(fresh) && complete.regular && complete.links == 1L && complete.size == bytes.size.toLong() && storage.stat(path) == complete)
                handle.sync()
                check(handle.stamp() == complete && storage.stat(path) == complete)
                val expectedHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(folderSyncDigest(path, complete, TransferControl(), storage) == expectedHash && handle.stamp() == complete) {
                    "The saved synchronization record did not match its intended bytes. Keep the journal for manual review."
                }
                records[path] = complete
            }
            storage.open(directory, directory = true).use { handle -> check(handle.stamp().sameObject(owned)); handle.sync() }
            guard()
            bytesWritten += bytes.size
        } catch (error: Exception) {
            blocked = true
            throw error
        }
    }

    private fun mutationRecord(value: Pending, status: String) = JsonObject().apply {
        addProperty("schema", 1); addProperty("event", value.kind + "-" + status)
        addProperty(if (value.kind == "preservation") "original" else "working", value.from)
        addProperty(if (value.kind == "preservation") "recovery" else "destination", value.to)
    }

    private fun beginMutation(value: Pending) {
        check(pending == null) { "The previous move needs manual review before another can start." }
        check(listOf(value.from, value.to).all { File(it).isAbsolute && File(it).toPath().normalize().toString() == it } &&
            value.from != value.to) { "The synchronization move intent is invalid." }
        append(mutationRecord(value, "intent"))
        pending = value
    }

    private fun completeMutation(value: Pending) {
        check(pending == value) { "The completed move does not match its recorded intent." }
        append(mutationRecord(value, "complete"))
        pending = null
    }

    @Synchronized
    fun begin(intent: FolderSyncPublicationIntent) = beginMutation(Pending("publication", intent.working, intent.destination))

    @Synchronized
    fun complete(intent: FolderSyncPublicationIntent) = completeMutation(Pending("publication", intent.working, intent.destination))

    @Synchronized
    fun beginPreservation(intent: FolderSyncPreservationIntent) = beginMutation(Pending("preservation", intent.original, intent.recovery))

    @Synchronized
    fun completePreservation(intent: FolderSyncPreservationIntent) = completeMutation(Pending("preservation", intent.original, intent.recovery))

    @Synchronized
    fun finish() {
        check(pending == null) { "A publication is still pending. Keep its journal for manual review." }
        append(JsonObject().apply { addProperty("schema", 1); addProperty("event", "finished") })
        finished = true
    }

    companion object {
        fun create(
            parentPath: String,
            working: String,
            destination: String,
            storage: PartialCopyStorage = AndroidPartialCopyStorage(),
            owner: () -> String = { UUID.randomUUID().toString() },
        ): FolderSyncPublicationLog {
            val parent = storage.stat(parentPath) ?: error("The synchronization journal parent is unavailable.")
            val resolved = storage.canonical(parentPath)
            val parentNode = File(resolved).toPath()
            check(File(parentPath).isAbsolute && parent.directory && !parent.regular && listOf(working, destination).all { path ->
                File(path).isAbsolute && File(path).toPath().normalize().toString() == path &&
                    !parentNode.startsWith(File(storage.canonical(path)).toPath())
            }) { "Choose a journal location outside the reviewed and working folders." }
            val id = owner()
            check(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "The synchronization journal identifier is invalid." }
            val directory = File(parentPath, "sync-" + id).path
            check(storage.stat(directory) == null) { "This synchronization journal already exists. Its original data was kept." }
            check(storage.stat(parentPath)?.sameObject(parent) == true && storage.canonical(parentPath) == resolved)
            storage.createDirectory(directory)
            val owned = storage.stat(directory) ?: error("The new synchronization journal is unavailable.")
            check(owned.directory && !owned.regular && owned.device == parent.device)
            val log = FolderSyncPublicationLog(directory, parent, owned, resolved, storage)
            log.append(JsonObject().apply {
                addProperty("schema", 1); addProperty("event", "created")
                addProperty("purpose", "Manual review only; never replay automatically")
                addProperty("workingDirectory", working); addProperty("destination", destination)
            })
            storage.open(parentPath, directory = true).use { handle -> check(handle.stamp().sameObject(parent)); handle.sync() }
            return log
        }
    }
}
