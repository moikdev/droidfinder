package dev.findroid

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Immutable intent saved before preparation. Reading it never authorizes a move or replay. */
internal data class FolderSyncRunRecord(
    val id: String,
    val source: String,
    val destination: String,
    val working: String,
    val journal: String,
    val operations: Int,
    val createdAt: Long,
)
internal data class FolderSyncRecordsState(
    val records: List<FolderSyncRunRecord> = emptyList(),
    val checking: Boolean = true,
    val error: String? = null,
)

internal class FolderSyncRunRecords(private val directory: File, private val publicationParent: File) {
    private fun validate(value: FolderSyncRunRecord) {
        check(UUID.fromString(value.id).toString() == value.id && value.operations in 1..20_000 && value.createdAt >= 0)
        check(listOf(value.source, value.destination, value.working, value.journal).all {
            File(it).isAbsolute && File(it).toPath().normalize().toString() == it
        }) { "The saved synchronization paths could not be read safely. Keep the record for manual review." }
        check(value.working == File(File(value.destination).parentFile, ".findroid-sync-" + value.id).path &&
            value.journal == File(publicationParent, "sync-" + value.id).path)
    }
    private fun parent(storage: PartialCopyStorage): PartialFileStamp {
        val value = storage.stat(directory.path) ?: error("The synchronization record folder is unavailable.")
        check(value.directory && !value.regular && storage.canonical(directory.path) == File(directory.parentFile!!.canonicalFile, directory.name).path)
        return value
    }
    private fun bytes(path: String, storage: PartialCopyStorage, control: TransferControl): ByteArray {
        val expected = storage.stat(path) ?: error("A synchronization record is unavailable. Keep its folder for review.")
        check(expected.regular && !expected.directory && expected.links == 1L && expected.size in 1..65_536 &&
            storage.canonical(path) == File(directory.canonicalFile, File(path).name).path)
        val value = ByteArray(expected.size.toInt())
        storage.open(path).use { handle -> control.guard(handle) {
            check(handle.stamp() == expected)
            var offset = 0
            while (offset < value.size) {
                control.check(); val count = handle.read(value, offset, value.size - offset)
                check(count in 1..(value.size - offset)); offset += count
            }
            check(handle.read(ByteArray(1), 0, 1) in -1..0 && handle.stamp() == expected && storage.stat(path) == expected)
        } }
        return value
    }
    fun save(value: FolderSyncRunRecord, storage: PartialCopyStorage = AndroidPartialCopyStorage(), control: TransferControl = TransferControl()) {
        validate(value); control.check()
        if (storage.stat(directory.path) == null) {
            storage.createDirectory(directory.path)
            storage.open(directory.parentFile!!.path, directory = true).use { it.sync() }
        }
        val owned = parent(storage)
        val record = JsonObject().apply {
            addProperty("schema", 1); addProperty("id", value.id)
            addProperty("purpose", "Manual review only; never replay automatically")
            addProperty("source", value.source); addProperty("destination", value.destination)
            addProperty("working", value.working); addProperty("journal", value.journal)
            addProperty("operations", value.operations); addProperty("createdAt", value.createdAt)
        }.toString().toByteArray(Charsets.UTF_8)
        check(record.size <= 65_536)
        val path = File(directory, value.id + ".json").path
        storage.open(path, writable = true, createNew = true).use { handle ->
            val fresh = handle.stamp()
            check(fresh.regular && fresh.links == 1L && fresh.size == 0L && storage.stat(path) == fresh && fresh.device == owned.device)
            var offset = 0
            while (offset < record.size) {
                control.check(); val count = handle.write(record, offset, record.size - offset)
                check(count in 1..(record.size - offset)); offset += count
            }
            val complete = handle.stamp()
            check(complete.sameObject(fresh) && complete.links == 1L && complete.size == record.size.toLong() && storage.stat(path) == complete)
            handle.sync(); check(handle.stamp() == complete)
        }
        storage.open(directory.path, directory = true).use { check(it.stamp().sameObject(owned)); it.sync() }
        check(parent(storage).sameObject(owned) && bytes(path, storage, control).contentEquals(record))
    }
    fun load(storage: PartialCopyStorage = AndroidPartialCopyStorage(), control: TransferControl = TransferControl()): List<FolderSyncRunRecord> {
        control.check()
        if (storage.stat(directory.path) == null) return emptyList()
        val owned = parent(storage)
        val paths = Files.newDirectoryStream(directory.toPath()).use { stream ->
            val result = mutableListOf<String>()
            for (path in stream) {
                control.check(); check(result.size < 10_000) { "There are too many synchronization records to list. Their files were kept." }
                result += path.toString()
            }
            result.sorted()
        }
        val records = paths.map { path ->
            control.check()
            val json = JsonParser.parseString(bytes(path, storage, control).toString(Charsets.UTF_8)).asJsonObject
            check(json.keySet() == setOf("schema", "id", "purpose", "source", "destination", "working", "journal", "operations", "createdAt"))
            check(json["schema"].asInt == 1 && json["purpose"].asString == "Manual review only; never replay automatically")
            val value = FolderSyncRunRecord(json["id"].asString, json["source"].asString, json["destination"].asString,
                json["working"].asString, json["journal"].asString, json["operations"].asInt, json["createdAt"].asLong)
            validate(value); check(File(path).name == value.id + ".json")
            value
        }
        check(parent(storage) == owned && Files.newDirectoryStream(directory.toPath()).use { it.map { path -> path.toString() }.sorted() } == paths) {
            "Synchronization records changed while being read. Keep their files and check again."
        }
        return records.sortedWith(compareByDescending<FolderSyncRunRecord> { it.createdAt }.thenBy { it.id })
    }
}
