package dev.droidfinder

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
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Local disk operations. Never overwrites an existing item. Call from an IO dispatcher. */
class FileEngine(private val stateDir: File) {
    private val trashDir = File(stateDir, "Trash").apply { mkdirs() }
    private val indexFile = File(stateDir, "trash.properties")
    private val index =
        Properties().apply { if (indexFile.exists()) indexFile.inputStream().use { load(it) } }

    fun validateName(name: String): String = validateFilename(name)

    fun uniqueFile(parent: File, name: String): File {
        var result = File(parent, name)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var number = 2
        while (result.exists()) {
            result = File(parent, "${name.take(dot)} $number${name.drop(dot)}")
            number++
        }
        return result
    }

    fun createFolder(parent: File, name: String): File =
        File(parent, validateName(name)).also {
            check(!it.exists()) { "An item with that name already exists." }
            check(it.mkdir()) { "Couldn't create the folder. Check storage access." }
        }

    fun rename(source: File, name: String): File {
        val target = File(source.parentFile!!, validateName(name))
        if (source == target) return source
        check(!target.exists()) { "An item with that name already exists." }
        Files.move(source.toPath(), target.toPath())
        return target
    }

    /** Staging all names first permits swaps; a failed batch restores the original names. */
    fun renameBatch(proposals: List<RenameProposal>): List<Pair<File, File>> {
        val sources = proposals.map { it.file.absolutePath }.toSet()
        val changed = proposals.filter { it.file.name != it.name }
        val targets = changed.map { File(it.file.parentFile!!, validateName(it.name)) }
        require(targets.map { it.absolutePath }.distinct().size == targets.size) {
            "Two items would have the same name."
        }
        targets.forEach {
            require(!it.exists() || it.absolutePath in sources) { "${it.name} already exists." }
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
                val temporary =
                    File(proposal.file.parentFile!!, ".droidfinder-rename-${UUID.randomUUID()}")
                Files.move(proposal.file.toPath(), temporary.toPath())
                positions[proposal.file] = temporary
            }
            changed.zip(targets).forEach { (proposal, target) ->
                Files.move(positions.getValue(proposal.file).toPath(), target.toPath())
                positions[proposal.file] = target
            }
        } catch (error: Exception) {
            // Move completed destinations out of the way before restoring cycles.
            positions.toMap().forEach { (original, current) ->
                if (current != original && current.exists()) {
                    val temporary =
                        File(original.parentFile!!, ".droidfinder-rollback-${UUID.randomUUID()}")
                    runCatching {
                        Files.move(current.toPath(), temporary.toPath())
                        positions[original] = temporary
                    }
                }
            }
            val failures =
                positions
                    .filter { (original, current) -> current != original }
                    .filter { (original, current) ->
                        runCatching { Files.move(current.toPath(), original.toPath()) }.isFailure
                    }
            if (failures.isNotEmpty())
                throw IOException(
                    "Rename failed. Recovery files were kept: ${failures.values.joinToString { it.absolutePath }}",
                    error,
                )
            throw error
        }
        return changed.zip(targets).map { (proposal, target) -> proposal.file to target }
    }

    private fun checkDestination(source: File, parent: File) {
        require(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not supported." }
        val origin = source.canonicalFile
        val destination = parent.canonicalFile
        require(origin != destination && !destination.toPath().startsWith(origin.toPath())) {
            "A folder can't be put inside itself."
        }
        check(parent.isDirectory && parent.canWrite()) { "This folder isn't writable." }
    }

    private fun copyTree(source: File, target: File, control: TransferControl) {
        control.item(source.name)
        check(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not supported." }
        if (source.isDirectory) {
            check(target.mkdir()) { "Couldn't create ${target.name}." }
            val children = source.listFiles() ?: throw IOException("Couldn't read ${source.name}.")
            children.forEach { copyTree(it, File(target, it.name), control) }
        } else {
            check(source.isFile) { "${source.name} is no longer available." }
            source.inputStream().use { input ->
                target.outputStream().use { output -> control.copy(input, output) }
            }
            check(source.length() == target.length()) { "The copy was incomplete." }
        }
        target.setLastModified(source.lastModified())
    }

    fun copy(
        source: File,
        parent: File,
        move: Boolean = false,
        control: TransferControl = TransferControl(),
    ): File {
        control.check()
        checkDestination(source, parent)
        require(!move || source.parentFile?.canonicalFile != parent.canonicalFile) {
            "This item is already in that folder."
        }
        val target = uniqueFile(parent, source.name)
        if (move && source.renameTo(target)) {
            control.item(source.name)
            return target
        }
        // Hide incomplete copies and only expose the result after a successful copy.
        val staging = File(parent, ".droidfinder-${UUID.randomUUID()}")
        try {
            copyTree(source, staging, control)
            control.check()
            Files.move(staging.toPath(), target.toPath())
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
        if (move)
            check(source.deleteRecursively()) {
                "Copied to ${target.absolutePath}, but some original items could not be removed. The destination copy was kept."
            }
        return target
    }

    /**
     * An exact destination is required for Undo/Redo; refuse collisions instead of inventing a
     * name.
     */
    fun relocate(source: File, target: File, control: TransferControl = TransferControl()): File {
        control.check()
        check(!target.exists()) {
            "${target.name} already exists. Move it before undoing this operation."
        }
        check(target.parentFile?.isDirectory == true) { "The original folder is unavailable." }
        checkDestination(source, target.parentFile!!)
        if (source.renameTo(target)) return target
        val temporary = copy(source, target.parentFile!!, control = control)
        try {
            if (temporary != target) Files.move(temporary.toPath(), target.toPath())
        } catch (error: Exception) {
            temporary.deleteRecursively()
            throw error
        }
        check(source.deleteRecursively()) {
            "The destination copy was kept, but some original items could not be removed."
        }
        return target
    }

    @Synchronized
    private fun saveIndex() {
        val staging = File(stateDir, "trash.properties.tmp")
        staging.outputStream().use { index.store(it, "DroidFinder trash original paths") }
        Files.move(
            staging.toPath(),
            indexFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    @Synchronized
    fun trash(source: File, control: TransferControl = TransferControl()): File {
        check(source.exists()) { "This item is no longer available." }
        val id = UUID.randomUUID().toString()
        val bucket = File(trashDir, id).apply { check(mkdir()) }
        // Persist recovery information before touching the source.
        index.setProperty(id, source.absolutePath)
        saveIndex()
        try {
            return copy(source, bucket, move = true, control = control)
        } catch (error: Exception) {
            if (bucket.listFiles().isNullOrEmpty()) {
                bucket.delete()
                index.remove(id)
                saveIndex()
            }
            throw error
        }
    }

    @Synchronized
    fun trashItems(): List<File> =
        trashDir.listFiles()?.flatMap { it.listFiles()?.toList().orEmpty() }.orEmpty()

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
        check(item.parentFile?.canonicalFile?.parentFile == trashDir.canonicalFile) {
            "Only items in Trash can be permanently deleted."
        }
        // Do not follow symbolic links, including links inside a folder moved to Trash.
        Files.walkFileTree(
            item.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
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
    ): File {
        require(items.isNotEmpty())
        val target =
            uniqueFile(
                parent,
                if (items.size == 1) "${items.first().nameWithoutExtension}.zip" else "Archive.zip",
            )
        val staging = File(parent, ".droidfinder-${UUID.randomUUID()}")
        try {
            ZipOutputStream(staging.outputStream().buffered()).use { zip ->
                fun add(file: File, path: String) {
                    control.item(file.name)
                    check(!Files.isSymbolicLink(file.toPath())) {
                        "Symbolic links are not supported."
                    }
                    zip.putNextEntry(ZipEntry(path + if (file.isDirectory) "/" else ""))
                    if (file.isFile) file.inputStream().use { control.copy(it, zip) }
                    zip.closeEntry()
                    if (file.isDirectory)
                        (file.listFiles() ?: error("Couldn't read ${file.name}.")).forEach {
                            add(it, "$path/${it.name}")
                        }
                }
                items.forEach { add(it, it.name) }
            }
            control.check()
            Files.move(staging.toPath(), target.toPath())
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
        return target
    }

    fun extract(archive: File, parent: File, control: TransferControl = TransferControl()): File {
        val target = uniqueFile(parent, archive.nameWithoutExtension)
        val staging = File(parent, ".droidfinder-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            var count = 0
            var bytes = 0L
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    control.item(entry.name)
                    check(++count <= 10_000) {
                        "This archive has too many entries (limit: 10,000)."
                    }
                    val file = File(staging, entry.name).canonicalFile
                    check(
                        file.toPath().startsWith(staging.canonicalFile.toPath()) &&
                            file != staging.canonicalFile,
                    ) {
                        "Unsafe archive path: ${entry.name}"
                    }
                    if (entry.isDirectory) check(file.isDirectory || file.mkdirs())
                    else {
                        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                        check(!file.exists()) { "Duplicate archive entry: ${entry.name}" }
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                control.check()
                                val n = zip.read(buffer)
                                if (n < 0) break
                                bytes += n
                                check(bytes <= 2L * 1024 * 1024 * 1024) {
                                    "Archive exceeds the 2 GB extraction limit."
                                }
                                output.write(buffer, 0, n)
                                control.bytes(n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            control.check()
            Files.move(staging.toPath(), target.toPath())
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
        return target
    }
}

fun validateFilename(name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) {
        "Enter a name without slashes."
    }
    require(name.toByteArray().size <= 240) { "That name is too long." }
    return name
}
