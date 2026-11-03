package dev.findroid

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Streaming directory access also lets fault tests model removal and cancellation at exact nodes. */
internal interface LocalIndexTree {
    fun validateRoot(path: String)
    fun rootIdentity(path: String): String? = null
    suspend fun children(path: String, root: String, control: ListingControl, each: suspend (IndexRecord) -> Unit)
}

internal class LocalFileIndexTree : LocalIndexTree {
    override fun validateRoot(path: String) {
        check(indexPath(path) && currentIndexedFile(path, path).isDirectory) { "Choose an available local folder." }
    }

    override fun rootIdentity(path: String) = localIndexFileIdentity(path)

    override suspend fun children(path: String, root: String, control: ListingControl, each: suspend (IndexRecord) -> Unit) {
        control.check()
        val folder = currentIndexedFile(path, root)
        check(folder.isDirectory) { "An indexed folder was replaced during refresh." }
        Files.newDirectoryStream(folder.toPath()).use { stream ->
            for (child in stream) {
                control.check()
                currentCoroutineContext().ensureActive()
                val attrs = Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink || !attrs.isDirectory && !attrs.isRegularFile) continue
                val file = child.toFile()
                check(file.canonicalPath == file.absolutePath && indexWithin(file.absolutePath, root)) {
                    "A folder changed location during refresh. The previous snapshot was kept."
                }
                if (isWorkingFileName(file.name)) continue
                each(IndexRecord(file.absolutePath, folder.absolutePath, file.name, attrs.isDirectory,
                    if (attrs.isDirectory) 0 else attrs.size().coerceAtLeast(0), attrs.lastModifiedTime().toMillis().coerceAtLeast(0)))
            }
        }
    }
}

/** A complete new generation is published separately; this scanner never replaces the old one. */
internal class LocalIndexScanner(
    private val store: LocalIndexStore,
    private val tree: LocalIndexTree = LocalFileIndexTree(),
    private val limits: LocalIndexLimits = LocalIndexLimits(),
    private val descend: (IndexRecord) -> Boolean = { true },
) {
    suspend fun scan(token: IndexBuildToken, control: ListingControl, progress: suspend (IndexBuildProgress) -> Unit = {}): IndexBuildProgress {
        control.check()
        tree.validateRoot(token.path)
        val identity = tree.rootIdentity(token.path)
        check(token.filesystemIdentity == null || token.filesystemIdentity == identity) { "The indexed folder was replaced before refresh. Try again." }
        data class Folder(val path: String, val depth: Int)
        val pending = ArrayDeque<Folder>()
        pending.add(Folder(token.path, 0))
        val scheduled = mutableSetOf(token.path)
        val batch = ArrayList<IndexRecord>(limits.batch)
        var entries = 0
        var folders = 0
        var bytes = 0L
        suspend fun flush() {
            control.check()
            currentCoroutineContext().ensureActive()
            if (batch.isNotEmpty()) {
                store.append(token, batch.toList())
                batch.clear()
            }
            progress(IndexBuildProgress(entries, folders, bytes))
        }
        while (pending.isNotEmpty()) {
            control.check()
            currentCoroutineContext().ensureActive()
            val folder = pending.removeFirst()
            if (folders >= limits.folders) throw IndexBuildLimit("The folder limit was reached. Index a smaller folder.")
            folders++
            tree.children(folder.path, token.path, control) { raw ->
                control.check()
                currentCoroutineContext().ensureActive()
                val row = raw.checked(token.path, limits)
                if (entries >= limits.entriesPerRoot) throw IndexBuildLimit("The ${limits.entriesPerRoot} entry limit was reached. Index a smaller folder.")
                if (row.valueBytes > limits.valueBytes - bytes) throw IndexBuildLimit("The saved-path budget was reached. Index a smaller folder.")
                entries++
                bytes += row.valueBytes
                batch += row
                if (row.folder && descend(row) && scheduled.add(row.path)) {
                    if (folder.depth >= limits.depth) throw IndexBuildLimit("The folder-depth limit was reached. Index a smaller folder.")
                    if (scheduled.size > limits.folders) throw IndexBuildLimit("The folder limit was reached. Index a smaller folder.")
                    pending.add(Folder(row.path, folder.depth + 1))
                }
                if (batch.size >= limits.batch) flush()
            }
            // Empty and slow folders still expose progress and give cancellation a boundary.
            flush()
        }
        control.check()
        currentCoroutineContext().ensureActive()
        tree.validateRoot(token.path)
        check(tree.rootIdentity(token.path) == identity) { "The indexed folder was replaced during refresh. The previous snapshot was kept." }
        return IndexBuildProgress(entries, folders, bytes)
    }
}
