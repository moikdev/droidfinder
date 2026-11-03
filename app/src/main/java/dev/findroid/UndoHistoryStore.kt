package dev.findroid

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class LoadedUndoHistory(val state: SavedUndoHistory?, val problem: String? = null)
internal interface UndoHistoryPersistence {
    fun load(): LoadedUndoHistory
    fun begin(label: String)
    fun save(state: SavedUndoHistory, complete: Boolean)
    fun reset(): String?
}

/** One ready snapshot plus a separate write-ahead marker. No backup is silently replayed. */
internal class UndoHistoryStore(
    private val directory: File,
    private val syncDirectory: (File) -> Unit,
    private val beforeWrite: (String) -> Unit = {},
) : UndoHistoryPersistence {
    private val journal get() = File(directory, "history.json")
    private val pending get() = File(directory, "pending.txt")
    private var blocked = false
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun checkDirectory() {
        check(!Files.isSymbolicLink(directory.toPath()) && (!exists(directory) || directory.isDirectory)) { "Saved Undo storage changed. Its original data was kept." }
    }
    private fun read(file: File, limit: Int): String {
        check(!Files.isSymbolicLink(file.toPath()) && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() <= limit) { "Saved Undo data could not be read safely." }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                check(output.size() + count <= limit); output.write(buffer, 0, count)
            }
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
        }
    }
    override fun load(): LoadedUndoHistory {
        return try {
            checkDirectory()
            if (!exists(directory)) LoadedUndoHistory(null)
            else {
                val interrupted = exists(pending)
                val unexpected = directory.listFiles()?.any { it.name !in setOf("history.json", "pending.txt") } ?: error("Saved Undo storage is unavailable.")
                val state = if (exists(journal)) UndoHistoryJson.decode(read(journal, UndoHistoryJson.MAX_BYTES)) else null
                if (interrupted || unexpected) {
                    blocked = true
                    LoadedUndoHistory(state, "An operation or Undo save stopped before completion. Review the files and saved actions; nothing will run automatically.")
                } else if (state == null) {
                    blocked = true
                    LoadedUndoHistory(null, "The saved Undo snapshot is missing. Its original storage was kept for review.")
                } else LoadedUndoHistory(state)
            }
        } catch (_: Exception) {
            blocked = true
            LoadedUndoHistory(null, "Saved Undo data could not be read. Its original storage was kept; review it before resetting the list.")
        }
    }
    private fun prepare() {
        check(!blocked) { "Review saved Undo before changing its recovery data." }
        checkDirectory()
        if (exists(journal)) check(!Files.isSymbolicLink(journal.toPath()) && Files.isRegularFile(journal.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Saved Undo data changed. Its original data was kept." }
        if (!exists(directory)) {
            check(directory.mkdir()) { "Couldn't create saved Undo storage." }
            syncDirectory(directory.parentFile!!)
        }
    }
    private fun write(file: File, text: String) {
        beforeWrite(file.name)
        check(!exists(file) || !Files.isSymbolicLink(file.toPath()) && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        val temporary = File(directory, ".write-" + UUID.randomUUID())
        check(temporary.createNewFile())
        try {
            temporary.outputStream().use { output -> output.write(text.toByteArray()); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(directory)
        } finally {
            if (exists(temporary)) Files.delete(temporary.toPath())
        }
    }
    override fun begin(label: String) {
        prepare()
        check(!exists(pending)) { "An earlier saved Undo operation needs review." }
        try { write(pending, "Saved Undo operation in progress\n" + label.take(512) + "\n") }
        catch (error: Exception) { blocked = true; throw error }
    }
    override fun save(state: SavedUndoHistory, complete: Boolean) {
        prepare(); check(exists(pending)) { "The saved Undo operation marker is missing." }
        try {
            write(journal, UndoHistoryJson.encode(state))
            if (complete) {
                beforeWrite("complete")
                Files.delete(pending.toPath())
                syncDirectory(directory)
            } else blocked = true
        } catch (error: Exception) {
            // If the final directory sync failed, retain a visible review marker where possible.
            if (!exists(pending)) runCatching { write(pending, "Saved Undo completion needs review.\n") }
            blocked = true
            throw error
        }
    }
    override fun reset(): String? {
        checkDirectory()
        val preserved = if (exists(directory)) {
            val target = File(directory.parentFile, ".undo-history-preserved-" + UUID.randomUUID())
            check(!exists(target))
            Files.move(directory.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(directory.parentFile!!)
            target.name
        } else null
        blocked = false
        return preserved
    }
}
