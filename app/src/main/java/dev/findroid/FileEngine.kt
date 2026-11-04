package dev.findroid

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local disk operations. Never overwrites an existing item. Call from an IO dispatcher. */
class FileEngine(
    private val stateDir: File,
    private val freeBytes: (File) -> Long? = { runCatching { it.usableSpace }.getOrNull() },
) {
    private var historyPublication: ((File, File) -> Unit)? = null
    internal fun <T> withHistoryPublication(publish: ((File, File) -> Unit)?, work: () -> T): T {
        val previous = historyPublication
        historyPublication = publish
        return try { work() } finally { historyPublication = previous }
    }
    private fun moveEntry(source: Path, target: Path) {
        val publish = historyPublication
        if (publish == null) Files.move(source, target) else publish(source.toFile(), target.toFile())
    }

    private val trashDir = File(stateDir, "Trash").apply { mkdirs() }
    private val indexFile = File(stateDir, "trash.properties")
    private val indexBackup = File(stateDir, "trash.properties.bak")
    internal var trashIndexNotice: String? = null
        private set
    private var indexWritable = true
    private val index = loadIndex()

    private fun loadIndex(): Properties {
        fun read(file: File) = Properties().apply {
            require(file.length() <= 16L * 1024 * 1024) { "Trash recovery records exceed 16 MiB." }
            file.inputStream().use(::load)
        }
        if (!indexFile.exists() && !indexBackup.exists()) return Properties()
        return try {
            read(indexFile)
        } catch (_: Exception) {
            val recovered = runCatching { read(indexBackup) }.getOrNull()
            val damaged = listOf(indexFile) + if (recovered == null) listOf(indexBackup) else emptyList()
            damaged.filter { it.exists() }.forEach { file ->
                try {
                    moveEntry(file.toPath(), File(file.path + ".damaged-${UUID.randomUUID()}").toPath())
                } catch (_: IOException) {
                    // Browsing must remain possible; never overwrite records we couldn't preserve.
                    indexWritable = false
                }
            }
            trashIndexNotice = if (recovered != null)
                "Trash recovery records were restored from a backup. Review original locations before restoring items."
            else "Trash recovery records could not be read. Files were kept in Trash; copy items to a folder to recover them."
            recovered ?: Properties()
        }
    }

    fun validateName(name: String): String = validateFilename(name)

    fun uniqueFile(parent: File, name: String): File {
        var result = File(parent, name)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var number = 2
        while (result.entryExists()) {
            result = File(parent, "${name.take(dot)} $number${name.drop(dot)}")
            number++
        }
        return result
    }

    fun createFolder(parent: File, name: String): File =
        File(parent, validateName(name)).also {
            check(!it.entryExists()) { "An item with that name already exists." }
            check(it.mkdir()) { "Couldn't create the folder. Check storage access." }
        }

    fun rename(source: File, name: String): File {
        val target = File(source.parentFile!!, validateName(name))
        if (source == target) return source
        if (!Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            moveEntry(source.toPath(), target.toPath())
            return target
        }
        return renameBatch(listOf(RenameProposal(source, name))).single().second
    }

    /** Staging all names first permits swaps; a failed batch restores the original names. */
    fun renameBatch(
        proposals: List<RenameProposal>,
        control: TransferControl? = null,
    ): List<Pair<File, File>> {
        control?.check()
        requireRenameDestinations(proposals)
        val changed = proposals.filter { it.file.name != it.name }
        val targets = changed.map { File(it.file.parentFile!!, validateName(it.name)) }
        require(targets.map { it.absolutePath }.distinct().size == targets.size) {
            "Two items would have the same name."
        }
        require(
            changed.none { a ->
                changed.any { b -> a != b && a.file.toPath().startsWith(b.file.toPath()) }
            },
        ) {
            "Rename a folder and its contents in separate operations."
        }
        val positions = changed.associate { it.file to it.file }.toMutableMap()
        try {
            changed.forEach { proposal ->
                control?.item(proposal.file.name)
                val temporary =
                    File(proposal.file.parentFile!!, ".findroid-rename-${UUID.randomUUID()}")
                moveEntry(proposal.file.toPath(), temporary.toPath())
                positions[proposal.file] = temporary
            }
            changed.zip(targets).forEach { (proposal, target) ->
                control?.check()
                moveEntry(positions.getValue(proposal.file).toPath(), target.toPath())
                positions[proposal.file] = target
            }
        } catch (error: Exception) {
            // Move completed destinations out of the way before restoring cycles.
            positions.toMap().forEach { (original, current) ->
                if (current != original && current.entryExists()) {
                    val temporary =
                        File(original.parentFile!!, ".findroid-rollback-${UUID.randomUUID()}")
                    runCatching {
                        moveEntry(current.toPath(), temporary.toPath())
                        positions[original] = temporary
                    }
                }
            }
            val failures =
                positions
                    .filter { (original, current) -> current != original }
                    .filter { (original, current) ->
                        runCatching {
                            moveEntry(current.toPath(), original.toPath())
                            positions[original] = original
                        }
                            .isFailure
                    }
            if (failures.isNotEmpty())
                throw RenameRecoveryException(
                    failures.map { (original, current) ->
                        val visible = runCatching {
                            val recovered =
                                uniqueFile(
                                    original.parentFile!!,
                                    recoveredRenameName(original.name),
                                )
                            moveEntry(current.toPath(), recovered.toPath())
                            recovered
                        }
                            .getOrDefault(current)
                        original.absolutePath to visible.absolutePath
                    },
                    error,
                )
            throw error
        }
        return changed.zip(targets).map { (proposal, target) -> proposal.file to target }
    }

    internal fun checkDestination(source: File, parent: File) {
        require(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not supported." }
        val origin = source.canonicalFile
        val destination = parent.canonicalFile
        require(origin != destination && !destination.toPath().startsWith(origin.toPath())) {
            "A folder can't be put inside itself."
        }
        check(parent.isDirectory && parent.canWrite()) { "This folder isn't writable." }
    }

    private fun copyTree(
        source: File,
        target: File,
        control: TransferControl,
        traversal: TransferTraversal = TransferTraversal(),
        depth: Int = 0,
        verification: CopyVerificationSession? = null,
    ) {
        traversal.visit(source.absolutePath, depth)
        control.item(source.name)
        check(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not supported." }
        if (source.isDirectory) {
            check(target.mkdir()) { "Couldn't create ${target.name}." }
            val children = source.listFiles() ?: throw IOException("Couldn't read ${source.name}.")
            children.forEach { copyTree(it, File(target, it.name), control, traversal, depth + 1, verification) }
            check(source.list()?.toSet() == children.map { it.name }.toSet()) {
                "The source folder changed while copying. The source was kept."
            }
        } else {
            check(source.isFile) { "${source.name} is no longer available." }
            val size = source.length()
            val modified = source.lastModified()
            val digest = verification?.begin(source.absolutePath)
            if (verification != null) control.phase("Copying", size, TransferStage.COPYING)
            val copied = source.inputStream().use { input ->
                target.outputStream().use { output ->
                    control.copy(if (digest == null) input else java.security.DigestInputStream(input, digest), output, size)
                }
            }
            fun unchanged() = copied == size && source.length() == size && source.lastModified() == modified
            check(unchanged()) {
                "The source changed or the copy was incomplete. The source was kept."
            }
            if (verification != null) {
                verification.verify(source.absolutePath, copied, digest!!.digest(), control) { target.inputStream() }
                check(unchanged()) { "The source changed while verifying the copy. The source was kept." }
            }
        }
        target.setLastModified(source.lastModified())
    }

    fun copy(
        source: File,
        parent: File,
        move: Boolean = false,
        control: TransferControl = TransferControl(),
        requestedName: String? = null,
        phaseTitle: String = if (move) "Moving" else "Copying",
        verification: CopyVerificationSession? = null,
    ): File {
        require(!move || verification == null) { "Optional readback verification is available for copies." }
        control.check()
        checkDestination(source, parent)
        require(
            !move ||
                source.parentFile?.canonicalFile != parent.canonicalFile ||
                (requestedName != null && requestedName != source.name),
        ) {
            "This item is already in that folder."
        }
        val target =
            requestedName?.let {
                File(parent, validateName(it)).also { target ->
                    check(!target.entryExists()) { "$it already exists." }
                }
            } ?: uniqueFile(parent, source.name)
        if (move) {
            control.phase(phaseTitle, stage = TransferStage.FINISHING)
            control.item(source.name)
            control.check()
            // Publication must not be followed by a cancellation check that hides its result.
            try {
                control.storage(TransferIo.STORAGE) { moveEntry(source.toPath(), target.toPath()) }
                return target
            } catch (error: java.nio.file.FileAlreadyExistsException) {
                throw error
            } catch (_: IOException) {
                // Cross-volume directory moves need a staged copy before source cleanup.
            }
        }
        control.phase("Checking files", stage = TransferStage.PREPARING)
        val estimate = estimateLocalTransfer(listOf(source.path), control)
        requireTransferSpace(estimate.knownBytes, freeBytes(parent))
        control.phase(phaseTitle, estimate.totalBytes, TransferStage.COPYING)
        // Hide incomplete copies and only expose the result after a successful copy.
        val staging = File(parent, ".findroid-${UUID.randomUUID()}")
        try {
            copyTree(source, staging, control, verification = verification)
            control.check()
            control.phase("Publishing copy", stage = TransferStage.FINISHING)
            control.storage(TransferIo.DESTINATION) { moveEntry(staging.toPath(), target.toPath()) }
        } catch (error: Exception) {
            staging.deleteTreeWithoutLinks()
            throw error
        }
        verification?.published(verification.results.associate { result ->
            result.source to if (result.source == source.absolutePath) target.absolutePath
                else File(target, File(result.source).relativeTo(source.absoluteFile).path).absolutePath
        })
        if (move && !source.deleteTreeWithoutLinks())
            throw TransferPublicationException(
                target.absolutePath,
                "Copied to ${target.absolutePath}, but some original items could not be removed. The destination copy was kept.",
            )
        return target
    }

    /**
     * An exact destination is required for Undo/Redo; refuse collisions instead of inventing a
     * name.
     */
    fun relocate(source: File, target: File, control: TransferControl = TransferControl()): File {
        control.check()
        check(!target.entryExists()) {
            "${target.name} already exists. Move it before undoing this operation."
        }
        check(target.parentFile?.isDirectory == true) { "The original folder is unavailable." }
        checkDestination(source, target.parentFile!!)
        try {
            moveEntry(source.toPath(), target.toPath())
            return target
        } catch (error: java.nio.file.FileAlreadyExistsException) {
            throw error
        } catch (_: IOException) {
            // Keep the original until the staged cross-volume copy has been published.
        }
        val temporary = copy(source, target.parentFile!!, control = control)
        try {
            if (temporary != target) moveEntry(temporary.toPath(), target.toPath())
        } catch (error: Exception) {
            temporary.deleteTreeWithoutLinks()
            throw error
        }
        check(source.deleteTreeWithoutLinks()) {
            "The destination copy was kept, but some original items could not be removed."
        }
        return target
    }

    @Synchronized
    private fun saveIndex() {
        check(indexWritable) { "Trash records could not be preserved. Copy recovery files to another folder before changing Trash." }
        val staging = File.createTempFile(".trash-index-", ".tmp", stateDir)
        try {
            staging.outputStream().use { output ->
                index.store(output, "Findroid trash original paths")
                output.fd.sync()
            }
            if (indexFile.isFile)
                Files.copy(indexFile.toPath(), indexBackup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(staging.toPath(), indexFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), indexFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            staging.delete()
        }
    }

    @Synchronized
    fun trash(source: File, control: TransferControl = TransferControl()): File {
        check(source.exists()) { "This item is no longer available." }
        val id = UUID.randomUUID().toString()
        val bucket = File(trashDir, id).apply { check(mkdir()) }
        // Persist recovery information before touching the source.
        index.setProperty(id, source.absolutePath)
        index.setProperty("$id.trashedAt", System.currentTimeMillis().toString())
        saveIndex()
        try {
            return copy(
                source,
                bucket,
                move = true,
                control = control,
                phaseTitle = "Saving recovery copy",
            )
        } catch (error: Exception) {
            if (bucket.listFiles().isNullOrEmpty()) {
                bucket.delete()
                index.remove(id)
                index.remove("$id.trashedAt")
                saveIndex()
            }
            throw error
        }
    }

    /** A stale in-memory Trash map must never overwrite changed on-disk recovery records. */
    @Synchronized
    internal fun checkSyncRecoveryIndex(
        storage: PartialCopyStorage = AndroidPartialCopyStorage(),
        control: TransferControl = TransferControl(),
    ) {
        check(indexWritable && trashIndexNotice == null) { "Review Trash recovery records before synchronizing originals." }
        control.check()
        val expected = storage.stat(indexFile.path)
        if (expected == null) {
            check(index.isEmpty() && storage.stat(indexBackup.path) == null) { "Trash recovery records changed. Keep their bytes and review them before synchronizing." }
            return
        }
        check(expected.regular && !expected.directory && expected.links == 1L && expected.size in 0..(16L * 1024 * 1024) &&
            storage.canonical(indexFile.path) == File(stateDir.canonicalFile, indexFile.name).path) {
            "Trash recovery records are linked, oversized or redirected. Keep them for manual review."
        }
        val bytes = ByteArray(expected.size.toInt())
        storage.open(indexFile.path).use { handle ->
            control.guard(handle) {
                check(handle.stamp() == expected) { "Trash recovery records changed before they could be checked." }
                var offset = 0
                while (offset < bytes.size) {
                    control.check()
                    val count = handle.read(bytes, offset, minOf(64 * 1024, bytes.size - offset))
                    check(count in 1..minOf(64 * 1024, bytes.size - offset)) { "Trash recovery records ended before their reviewed size." }
                    offset += count
                }
                check(handle.read(ByteArray(1), 0, 1) in -1..0 && handle.stamp() == expected && storage.stat(indexFile.path) == expected) {
                    "Trash recovery records changed while they were checked."
                }
            }
        }
        val actual = Properties().apply { bytes.inputStream().use(::load) }
        check(actual == index && storage.stat(indexFile.path) == expected) {
            "Trash recovery records changed. Their bytes were kept; review them before synchronizing."
        }
    }

    /** Checks every reviewed original before the apply worker can change its first destination. */
    @Synchronized
    internal fun reviewSyncTrash(
        originals: Collection<UndoPathProof>,
        files: UndoHistoryFiles,
        storage: PartialCopyStorage = AndroidPartialCopyStorage(),
        control: TransferControl = TransferControl(),
    ): FolderSyncTrashLocation {
        checkSyncRecoveryIndex(storage, control)
        check(indexWritable && trashIndexNotice == null) { "Review Trash recovery records before synchronizing replacements or destination-only items." }
        check(originals.isNotEmpty() && originals.size <= 10_000 && originals.map { it.path }.toSet().size == originals.size) {
            "The original recovery plan is empty, duplicated or too large. Review it again."
        }
        val identity = storage.stat(trashDir.path) ?: error("Trash is unavailable.")
        val resolved = storage.canonical(trashDir.path)
        check(identity.directory && !identity.regular) { "Trash is unavailable." }
        val projected = Properties().apply { putAll(index) }
        val paths = originals.map { it.path }.toSet()
        for (proof in originals) {
            check(File(proof.path).isAbsolute && File(proof.path).toPath().normalize().toString() == proof.path &&
                proof.entries[proof.path] != null && proof.entries.all { (path, stamp) ->
                    (path == proof.path || path.startsWith(proof.path.trimEnd('/') + "/")) && stamp.device == identity.device &&
                        stamp.regular != stamp.directory && (stamp.directory || stamp.links == 1L)
                }) { "This storage cannot preserve every reviewed original with native rename. Keep those items or choose another local destination." }
            check(generateSequence(File(proof.path).parentFile) { it.parentFile }.none { it.path in paths } &&
                !File(resolved).toPath().startsWith(File(proof.resolved).toPath()) &&
                !File(proof.resolved).toPath().startsWith(File(resolved).toPath())) {
                "The recovery plan overlaps another original or its own Trash storage. Choose separate folders and review again."
            }
            check(files.observe(proof.path) == proof) { "A reviewed original changed. Compare again." }
            var id: String
            do { id = UUID.randomUUID().toString() } while (projected.containsKey(id) || projected.containsKey("$id.trashedAt"))
            projected.setProperty(id, proof.path)
            projected.setProperty("$id.trashedAt", Long.MAX_VALUE.toString())
        }
        val bytes = java.io.ByteArrayOutputStream().use { buffer -> projected.store(buffer, "Findroid trash original paths"); buffer.size().toLong() }
        check(bytes <= 16L * 1024 * 1024) { "The complete recovery mapping would exceed the Trash record limit. Choose fewer changes and review existing Trash records." }
        val reserve = 1024L * 1024 + bytes * 3 + originals.size * 8192L
        val available = freeBytes(trashDir)
        check(available != null && available >= reserve) { "There is not enough verified recovery space for this complete plan. Original files were kept." }
        check(storage.stat(trashDir.path)?.sameObject(identity) == true && storage.canonical(trashDir.path) == resolved &&
            originals.all { files.observe(it.path) == it }) { "The recovery location or a reviewed original changed during preflight." }
        checkSyncRecoveryIndex(storage, control)
        return FolderSyncTrashLocation(trashDir.path, resolved, identity)
    }

    /** Reviewed synchronization never falls back to recursively copying a live original. */
    @Synchronized
    internal fun trashReviewedByRename(
        expected: UndoPathProof,
        contents: Map<String, String>,
        files: UndoHistoryFiles,
        storage: PartialCopyStorage = AndroidPartialCopyStorage(),
        control: TransferControl = TransferControl(),
        recovery: FolderSyncTrashLocation? = null,
        recordIntent: (FolderSyncPreservationIntent) -> Unit = {},
    ): File {
        var preserved: File? = null
        var moveAttempted = false
        try {
            check(indexWritable && trashIndexNotice == null) { "Review Trash recovery records before moving synchronization originals." }
            checkSyncRecoveryIndex(storage, control)
            val source = File(expected.path)
            val identity = expected.entries[expected.path] ?: error("The reviewed original is unavailable.")
            val trashIdentity = storage.stat(trashDir.path) ?: error("Trash is unavailable.")
            val resolvedTrash = storage.canonical(trashDir.path)
            check(recovery == null || recovery.path == trashDir.path && recovery.resolved == resolvedTrash && trashIdentity.sameObject(recovery.identity)) {
                "The preflighted recovery location changed. Original files were kept."
            }
            check(trashIdentity.directory && !trashIdentity.regular && identity.device == trashIdentity.device &&
                expected.entries.values.all { it.device == identity.device } &&
                !File(resolvedTrash).toPath().startsWith(File(expected.resolved).toPath()) &&
                !File(expected.resolved).toPath().startsWith(File(resolvedTrash).toPath())) {
                "This storage cannot preserve the reviewed original with an atomic rename. Keep destination-only items or choose another folder."
            }
            check(expected.entries.keys.all { it == expected.path || it.startsWith(expected.path.trimEnd('/') + "/") } &&
                contents.keys == expected.entries.filterValues { it.regular }.keys) { "The original recovery review is incomplete." }
            control.check()
            check(files.observe(expected.path) == expected) { "The reviewed original changed. Compare again." }
            for ((path, hash) in contents) {
                check(folderSyncDigest(path, expected.entries.getValue(path), control, storage) == hash) { "The reviewed original contents changed. Compare again." }
            }
            check(files.observe(expected.path) == expected) { "The reviewed original changed while it was checked. Compare again." }
            checkSyncRecoveryIndex(storage, control)
            val id = UUID.randomUUID().toString()
            val bucket = File(trashDir, id)
            storage.createDirectory(bucket.path)
            val bucketIdentity = storage.stat(bucket.path) ?: error("The recovery folder is unavailable.")
            check(bucketIdentity.directory && !bucketIdentity.regular && bucketIdentity.device == trashIdentity.device)
            index.setProperty(id, source.absolutePath)
            index.setProperty("$id.trashedAt", System.currentTimeMillis().toString())
            saveIndex()
            files.syncDirectory(stateDir)
            control.check()
            val target = File(bucket, source.name)
            recordIntent(FolderSyncPreservationIntent(source.path, target.path))
            control.check()
            checkSyncRecoveryIndex(storage, control)
            check(storage.stat(trashDir.path)?.sameObject(trashIdentity) == true && storage.canonical(trashDir.path) == resolvedTrash &&
                storage.stat(bucket.path)?.sameObject(bucketIdentity) == true && files.observe(expected.path) == expected) {
                "The reviewed original or its recovery location changed. Original files were kept."
            }
            moveAttempted = true
            files.moveNoReplace(source, target)
            preserved = target
            val recovered = files.observe(target.path)
            checkFolderSyncRelocation(expected.path, expected.entries, recovered, contents) { path, stamp ->
                folderSyncDigest(path, stamp, control, storage)
            }
            files.sync(setOf(target.path, source.path))
            checkSyncRecoveryIndex(storage, control)
            return target
        } catch (error: Exception) {
            throw FolderSyncRecoveryFailure(preserved, error, moveAttempted)
        }
    }

    @Synchronized
    fun trashItems(): List<File> =
        trashDir
            .listFiles()
            ?.filter { !Files.isSymbolicLink(it.toPath()) && it.isDirectory }
            ?.flatMap { it.listFiles()?.toList().orEmpty() }
            .orEmpty()

    @Synchronized
    fun trashedAt(item: File): Long? =
        index.getProperty("${item.parentFile?.name}.trashedAt")?.toLongOrNull()?.takeIf { it > 0 }

    @Synchronized
    internal fun trashInventory(check: () -> Unit): Pair<List<File>, List<String>> {
        val items = mutableListOf<File>()
        val issues = mutableListOf<String>()
        trashIndexNotice?.let(issues::add)
        Files.newDirectoryStream(trashDir.toPath()).use { buckets ->
            for (bucket in buckets) {
                check()
                if (
                    Files.isSymbolicLink(bucket) ||
                        !Files.isDirectory(bucket, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                ) {
                    issues += "An unsupported Trash bucket was excluded."
                    continue
                }
                try {
                    Files.newDirectoryStream(bucket).use { children ->
                        for (child in children) {
                            check()
                            if (items.size == 10_000) {
                                issues +=
                                    "Trash inventory limited to 10,000 items; refresh after cleaning."
                                return items to issues
                            }
                            items += child.toFile()
                        }
                    }
                } catch (error: IOException) {
                    issues += "A Trash bucket could not be read: ${error.message}"
                }
            }
        }
        return items to issues
    }

    internal fun ownsRecovery(item: File): Boolean =
        item.isAbsolute && item.toPath().normalize().toString() == item.path && item.parentFile?.parentFile?.absoluteFile == trashDir.absoluteFile

    fun originalPath(item: File): String? = index.getProperty(item.parentFile?.name)

    @Synchronized
    fun rememberOriginal(item: File, path: String, parent: String) {
        val id = item.parentFile!!.name
        index.setProperty(id, path)
        index.setProperty("$id.parent", parent)
        saveIndex()
    }

    fun originalParent(item: File): String? = index.getProperty("${item.parentFile?.name}.parent")

    @Synchronized
    fun rememberMappings(item: File, pairs: List<Pair<String, String>>) {
        val prefix = "${item.parentFile!!.name}.mapping:"
        pairs.forEach { (from, to) -> index.setProperty(prefix + from, to) }
        saveIndex()
    }

    fun originalMappings(item: File): List<Pair<String, String>> {
        val prefix = "${item.parentFile!!.name}.mapping:"
        return index
            .stringPropertyNames()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) to index.getProperty(it) }
    }

    @Synchronized
    fun restore(item: File, control: TransferControl = TransferControl()): File {
        val original =
            originalPath(item)?.let(::File) ?: error("The original location is unavailable.")
        val parent = original.parentFile ?: error("The original folder is unavailable.")
        check(parent.isDirectory) {
            "The original folder no longer exists. Copy the item to another folder instead."
        }
        val result = copy(item, parent, move = true, control = control)
        removeBucket(item)
        return result
    }

    internal fun restoreExact(item: File, target: File): File {
        check(ownsRecovery(item) && originalPath(item) == target.absolutePath) { "The saved recovery location changed. Review Trash before continuing." }
        relocate(item, target)
        removeBucket(item)
        return target
    }

    private fun removeBucket(item: File) {
        val bucket = item.parentFile!!
        if (bucket.listFiles().isNullOrEmpty()) {
            bucket.delete()
            index
                .stringPropertyNames()
                .filter { it == bucket.name || it.startsWith("${bucket.name}.") }
                .forEach(index::remove)
            saveIndex()
        }
    }

    @Synchronized
    fun deleteForever(item: File) {
        deleteTrashControlled(item)
    }

    @Synchronized
    internal fun deleteReviewedTrash(item: TrashSpaceItem, check: () -> Unit): Boolean {
        check()
        if (
            !item.disk.complete ||
                trashedAt(item.file) != item.trashedAt ||
                originalPath(item.file) != item.original
        )
            return false
        val fresh = inspectDisk(item.file, check)
        if (!fresh.complete || fresh.revision != item.disk.revision) return false
        deleteTrashControlled(item.file, check)
        return true
    }

    private fun deleteTrashControlled(item: File, check: () -> Unit = {}) {
        check(item.parentFile?.canonicalFile?.parentFile == trashDir.canonicalFile) {
            "Only items in Trash can be permanently deleted."
        }
        // Do not follow symbolic links, including links inside a folder moved to Trash.
        Files.walkFileTree(
            item.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    check()
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    check()
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        removeBucket(item)
    }

    fun compress(
        items: List<File>,
        parent: File,
        control: TransferControl = TransferControl(),
        name: String? = null,
        level: Int = -1,
    ): File {
        require(items.isNotEmpty())
        require(level in -1..9) { "Invalid ZIP compression level." }
        items.forEach { checkDestination(it, parent) }
        val target =
            uniqueFile(
                parent,
                zipFilename(
                    name
                        ?: if (items.size == 1)
                            (if (items.first().isDirectory) items.first().name
                            else items.first().nameWithoutExtension)
                        else "Archive",
                ),
            )
        val staging = File(parent, ".findroid-${UUID.randomUUID()}")
        try {
            ZipOutputStream(staging.outputStream().buffered()).use { zip ->
                zip.setLevel(level)
                fun add(file: File, path: String) {
                    control.item(file.name)
                    check(!Files.isSymbolicLink(file.toPath())) {
                        "Symbolic links are not supported."
                    }
                    zip.putNextEntry(
                        ZipEntry(safeZipPath(path) + if (file.isDirectory) "/" else "").apply {
                            time = file.lastModified()
                        },
                    )
                    if (file.isFile) file.inputStream().use { control.copy(it, zip) }
                    zip.closeEntry()
                    if (file.isDirectory)
                        (file.listFiles() ?: error("Couldn't read ${file.name}.")).forEach {
                            add(it, "$path/${it.name}")
                        }
                }
                val roots = mutableSetOf<String>()
                items.forEach { file ->
                    var root = file.name
                    var n = 2
                    while (!roots.add(root)) root =
                        if (file.isDirectory) "${file.name} ${n++}"
                        else
                            "${file.nameWithoutExtension} ${n++}${file.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()}"
                    add(file, root)
                }
            }
            control.check()
            moveEntry(staging.toPath(), target.toPath())
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
        return target
    }

    fun extract(
        archive: File,
        parent: File,
        control: TransferControl = TransferControl(),
        selection: Set<String>? = null,
        limits: ZipLimits = ZipLimits(),
    ): File {
        control.check()
        val stamp = archive.length() to archive.lastModified()
        return openZip(archive, limits, control).use { zip ->
            control.guard(zip) {
                val contents = inspectZipOpen(zip, limits, control)
                val items = selectedZipItems(contents, selection, control::check)
                val total = validateZipWork(items, limits)
                requireTransferSpace(total, freeBytes(parent))
                val target = uniqueFile(parent, archive.nameWithoutExtension.ifBlank { "Archive" })
                val staging =
                    File(parent, ".findroid-${UUID.randomUUID()}").apply { check(mkdir()) }
                try {
                    control.phase("Extracting ZIP", total, TransferStage.COPYING)
                    val budget = ZipReadBudget()
                    val modified = mutableListOf<Pair<File, Long>>()
                    for (item in items) {
                        control.item(item.path)
                        val file = File(staging, item.path).canonicalFile
                        check(
                            file.toPath().startsWith(staging.canonicalFile.toPath()) &&
                                file != staging.canonicalFile,
                        ) {
                            "Unsafe archive path: ${item.path}"
                        }
                        if (item.folder) check(file.isDirectory || file.mkdirs())
                        else {
                            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                            check(!file.exists()) { "Duplicate archive entry: ${item.path}" }
                            file.outputStream().use { output ->
                                control.guard(output) {
                                    readZipMember(zip, item, output, limits, budget, control)
                                }
                            }
                        }
                        if (item.modified >= 0) modified += file to item.modified
                    }
                    control.check()
                    check(stamp == (archive.length() to archive.lastModified())) {
                        "The archive changed during extraction. Try again."
                    }
                    modified
                        .sortedByDescending { it.first.path.length }
                        .forEach { (file, time) -> file.setLastModified(time) }
                    moveEntry(staging.toPath(), target.toPath())
                } catch (error: Exception) {
                    staging.deleteTreeWithoutLinks()
                    throw error
                }
                target
            }
        }
    }
}

fun validateFilename(name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) {
        "Enter a name without slashes."
    }
    require(name.toByteArray().size <= 240) { "That name is too long." }
    return name
}
