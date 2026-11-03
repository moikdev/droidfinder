package dev.findroid

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Read-only member snapshots, published only after full size/CRC validation. */
internal object ArchivePreviewCache {
    private val lock = Mutex()
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val leases = java.util.concurrent.ConcurrentHashMap<String, java.io.Closeable>()

    fun release(entry: Entry) {
        entry.file.parentFile?.path?.let {
            active.remove(it)
            leases.remove(it)?.close()
        }
    }

    suspend fun read(
        context: Context,
        archive: File,
        item: ZipItem,
        limits: ZipLimits,
        control: TransferControl,
    ): Entry {
        var prepared: Entry? = null
        try {
            return withContext(Dispatchers.IO) {
                lock.withLock {
                    CacheLeases.pin(File(context.cacheDir, "shared/archive-peek")).use {
                        control.check()
                        val cap =
                            minOf(limits.previewBytes, limits.memberBytes, limits.expandedBytes)
                        if (item.size > cap)
                            throw ZipLimitException(
                                "This file exceeds the archive Peek limit (${cap / 1024 / 1024} MiB). Extract it to preview it in its own folder.",
                            )
                        val root = File(context.cacheDir, "shared/archive-peek").apply { mkdirs() }
                        // The current snapshot stays below 256 MiB; older snapshots are evicted
                        // before
                        // inflation.
                        val directories = root.listFiles().orEmpty()
                        val pinned = directories.filter { it.path in active }
                        fun bytes(file: File) =
                            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        var retained = pinned.sumOf(::bytes)
                        check(pinned.size < 8 && retained <= 512L * 1024 * 1024 - item.size) {
                            "Close another archive preview to free cache space."
                        }
                        var slots = 7 - pinned.size
                        directories
                            .filter { it.path !in active }
                            .sortedByDescending { it.lastModified() }
                            .forEach { older ->
                                val size = bytes(older)
                                if (slots <= 0 || retained + size > 512L * 1024 * 1024 - item.size)
                                    older.deleteTreeWithoutLinks()
                                else {
                                    retained += size
                                    slots--
                                }
                            }
                        val space = root.usableSpace
                        if (space > 0 && item.size > (space - 16L * 1024 * 1024).coerceAtLeast(0))
                            error(
                                "Not enough cache space for this preview. Extract to another location.",
                            )
                        val folder =
                            File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
                        val target = File(folder, item.path.substringAfterLast('/'))
                        val temporary = File(folder, ".preview-part")
                        val stamp = archive.length() to archive.lastModified()
                        try {
                            openZip(archive, limits, control).use { zip ->
                                control.guard(zip) {
                                    val fresh =
                                        inspectZipOpen(zip, limits, control).items.firstOrNull {
                                            it.path == item.path
                                        }
                                    check(fresh == item && !item.folder) {
                                        "Archive contents changed. Reopen the archive."
                                    }
                                    val bounded =
                                        limits.copy(memberBytes = cap, expandedBytes = cap)
                                    validateZipWork(listOf(item), bounded)
                                    control.phase("Preparing archive preview", item.size, TransferStage.COPYING)
                                    control.item(item.path)
                                    temporary.outputStream().use { output ->
                                        control.guard(output) {
                                            readZipMember(
                                                zip,
                                                item,
                                                output,
                                                bounded,
                                                ZipReadBudget(),
                                                control,
                                            )
                                        }
                                    }
                                }
                            }
                            control.check()
                            check(stamp == (archive.length() to archive.lastModified())) {
                                "The archive changed while preparing this preview."
                            }
                            Files.move(temporary.toPath(), target.toPath())
                            if (item.modified >= 0) target.setLastModified(item.modified)
                            Entry(target, writable = false, canDelete = false, canRename = false)
                                .also {
                                    prepared = it
                                    active.add(folder.path)
                                    leases[folder.path] = CacheLeases.pin(folder)
                                }
                        } catch (error: Exception) {
                            folder.deleteTreeWithoutLinks()
                            throw error
                        }
                    }
                }
            }
        } catch (error: Exception) {
            prepared?.let(::release)
            throw error
        }
    }
}
