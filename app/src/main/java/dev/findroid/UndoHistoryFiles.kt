package dev.findroid

import java.io.File
import java.nio.file.Files

/** A missing endpoint is anchored to its nearest existing directory, without following final links. */
internal data class UndoPathProof(
    val path: String,
    val resolved: String,
    val anchor: String,
    val resolvedAnchor: String,
    val anchorDevice: Long,
    val anchorInode: Long,
    val entries: Map<String, PartialFileStamp>,
)

internal interface UndoHistoryFiles {
    fun observe(path: String): UndoPathProof
    fun sync(paths: Set<String>)
    fun syncDirectory(directory: File)
    fun moveNoReplace(from: File, to: File)
}

internal class AndroidUndoHistoryFiles(
    private val storage: PartialCopyStorage = AndroidPartialCopyStorage(),
) : UndoHistoryFiles {
    override fun observe(path: String): UndoPathProof {
        val resolved = storage.canonical(path)
        var anchor = File(path).parentFile ?: error("The saved location has no parent.")
        while (storage.stat(anchor.path) == null) anchor = anchor.parentFile ?: error("The saved folder is unavailable.")
        val parent = storage.stat(anchor.path) ?: error("The saved folder is unavailable.")
        check(parent.directory) { "The saved folder changed. Review Undo before continuing." }
        val resolvedAnchor = storage.canonical(anchor.path)
        val entries = linkedMapOf<String, PartialFileStamp>()
        fun visit(file: File, depth: Int) {
            check(depth <= 128 && entries.size < 10_000) { "This folder is too large for saved Undo. Its actions stay in this session." }
            val before = storage.stat(file.path) ?: return
            check(before.regular || before.directory) { "Saved Undo supports local regular files and folders. Links and special files stay in this session." }
            entries[file.path] = before
            if (before.directory) {
                Files.newDirectoryStream(file.toPath()).use { children -> children.sortedBy { it.toString() }.forEach { visit(it.toFile(), depth + 1) } }
            } else storage.open(file.path).use { check(it.stamp() == before) { "${file.name} changed while Undo was being checked." } }
            check(storage.stat(file.path) == before) { "${file.name} changed while Undo was being checked." }
        }
        visit(File(path), 0)
        check(storage.canonical(path) == resolved && storage.canonical(anchor.path) == resolvedAnchor &&
            storage.stat(anchor.path)?.let { it.directory && it.device == parent.device && it.inode == parent.inode } == true) {
            "The saved folder changed while Undo was being checked."
        }
        return UndoPathProof(path, resolved, anchor.path, resolvedAnchor, parent.device, parent.inode, entries)
    }

    override fun sync(paths: Set<String>) {
        val directories = linkedSetOf<String>()
        for (path in paths) {
            val proof = observe(path)
            proof.entries.forEach { (name, stamp) ->
                if (stamp.regular) storage.open(name).use { handle ->
                    check(handle.stamp() == stamp) { "A file changed before Undo could be saved." }
                    handle.sync()
                } else directories += name
            }
            directories += proof.anchor
        }
        directories.sortedByDescending { it.length }.forEach { syncDirectory(File(it)) }
    }

    override fun moveNoReplace(from: File, to: File) {
        try { NativeFilePublication.moveNoReplace(from.path, to.path) }
        catch (error: android.system.ErrnoException) {
            if (error.errno == android.system.OsConstants.EEXIST)
                throw java.nio.file.FileAlreadyExistsException(to.path)
            throw java.io.IOException("Couldn't safely move ${from.name}. ${error.message}", error)
        }
    }

    override fun syncDirectory(directory: File) {
        storage.open(directory.path, directory = true).use { it.sync() }
    }
}

internal fun checkUndoProofs(proofs: Collection<UndoPathProof>, files: UndoHistoryFiles) {
    proofs.forEach { expected ->
        check(files.observe(expected.path) == expected) {
            "${File(expected.path).name} or its folder changed after this action. Saved Undo stopped to protect the current files."
        }
    }
}
