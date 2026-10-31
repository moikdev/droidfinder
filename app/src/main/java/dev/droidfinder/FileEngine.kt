package dev.droidfinder

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Local disk operations. Never overwrites an existing item. Call from an IO dispatcher. */
class FileEngine(private val stateDir: File) {
    private val trashDir = File(stateDir, "Trash").apply { mkdirs() }
    private val indexFile = File(stateDir, "trash.properties")
    private val index = Properties().apply { if (indexFile.exists()) indexFile.inputStream().use { load(it) } }

    fun validateName(name: String): String = validateFilename(name)

    fun uniqueFile(parent: File, name: String): File {
        var result = File(parent, name)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var number = 2
        while (result.exists()) { result = File(parent, "${name.take(dot)} $number${name.drop(dot)}"); number++ }
        return result
    }

    fun createFolder(parent: File, name: String): File = File(parent, validateName(name)).also {
        check(!it.exists()) { "An item with that name already exists." }
        check(it.mkdir()) { "Couldn't create the folder. Check storage access." }
    }

    fun rename(source: File, name: String): File {
        val target = File(source.parentFile!!, validateName(name))
        if (source == target) return source
        check(!target.exists()) { "An item with that name already exists." }
        check(source.renameTo(target)) { "Couldn't rename ${source.name}." }
        return target
    }

    private fun checkDestination(source: File, parent: File) {
        require(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not supported." }
        val origin = source.canonicalFile
        val destination = parent.canonicalFile
        require(origin != destination && !destination.toPath().startsWith(origin.toPath())) { "A folder can't be put inside itself." }
        check(parent.isDirectory && parent.canWrite()) { "This folder isn't writable." }
    }

    private fun copyTree(source: File, target: File) {
        check(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not supported." }
        if (source.isDirectory) {
            check(target.mkdir()) { "Couldn't create ${target.name}." }
            val children = source.listFiles() ?: throw IOException("Couldn't read ${source.name}.")
            children.forEach { copyTree(it, File(target, it.name)) }
        } else {
            check(source.isFile) { "${source.name} is no longer available." }
            source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            check(source.length() == target.length()) { "The copy was incomplete." }
        }
        target.setLastModified(source.lastModified())
    }

    fun copy(source: File, parent: File, move: Boolean = false): File {
        checkDestination(source, parent)
        require(!move || source.parentFile?.canonicalFile != parent.canonicalFile) { "This item is already in that folder." }
        val target = uniqueFile(parent, source.name)
        if (move && source.renameTo(target)) return target
        // Hide incomplete copies and only expose the result after a successful copy.
        val staging = File(parent, ".droidfinder-${UUID.randomUUID()}")
        try {
            copyTree(source, staging)
            check(!target.exists() && staging.renameTo(target)) { "Couldn't finish the copy." }
        } catch (error: Exception) { staging.deleteRecursively(); throw error }
        if (move) check(source.deleteRecursively()) { "Copied ${source.name}, but couldn't remove the original. Both copies were kept." }
        return target
    }

    @Synchronized private fun saveIndex() {
        val staging = File(stateDir, "trash.properties.tmp")
        staging.outputStream().use { index.store(it, "DroidFinder trash original paths") }
        Files.move(staging.toPath(), indexFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized fun trash(source: File): File {
        check(source.exists()) { "This item is no longer available." }
        val id = UUID.randomUUID().toString()
        val bucket = File(trashDir, id).apply { check(mkdir()) }
        // Persist recovery information before touching the source.
        index.setProperty(id, source.absolutePath)
        saveIndex()
        try { return copy(source, bucket, move = true) }
        catch (error: Exception) {
            if (bucket.listFiles().isNullOrEmpty()) { bucket.delete(); index.remove(id); saveIndex() }
            throw error
        }
    }

    @Synchronized fun trashItems(): List<File> = trashDir.listFiles()?.flatMap { it.listFiles()?.toList().orEmpty() }.orEmpty()
    fun originalPath(item: File): String? = index.getProperty(item.parentFile?.name)

    @Synchronized fun restore(item: File): File {
        val original = originalPath(item)?.let(::File) ?: error("The original location is unavailable.")
        val parent = original.parentFile ?: error("The original folder is unavailable.")
        check(parent.isDirectory) { "The original folder no longer exists. Copy the item to another folder instead." }
        val result = copy(item, parent, move = true)
        removeBucket(item)
        return result
    }

    private fun removeBucket(item: File) { val bucket = item.parentFile!!; if (bucket.listFiles().isNullOrEmpty()) { bucket.delete(); index.remove(bucket.name); saveIndex() } }
    @Synchronized fun deleteForever(item: File) {
        check(item.parentFile?.canonicalFile?.parentFile == trashDir.canonicalFile) { "Only items in Trash can be permanently deleted." }
        // Do not follow symbolic links, including links inside a folder moved to Trash.
        Files.walkFileTree(item.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE }
        })
        removeBucket(item)
    }

    fun compress(items: List<File>, parent: File): File {
        require(items.isNotEmpty())
        val target = uniqueFile(parent, if (items.size == 1) "${items.first().nameWithoutExtension}.zip" else "Archive.zip")
        val staging = File(parent, ".droidfinder-${UUID.randomUUID()}")
        try {
            ZipOutputStream(staging.outputStream().buffered()).use { zip ->
                fun add(file: File, path: String) {
                    check(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are not supported." }
                    zip.putNextEntry(ZipEntry(path + if (file.isDirectory) "/" else ""))
                    if (file.isFile) file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    if (file.isDirectory) (file.listFiles() ?: error("Couldn't read ${file.name}.")).forEach { add(it, "$path/${it.name}") }
                }
                items.forEach { add(it, it.name) }
            }
            check(!target.exists() && staging.renameTo(target)) { "Couldn't save the archive." }
        } catch (error: Exception) { staging.delete(); throw error }
        return target
    }

    fun extract(archive: File, parent: File): File {
        val target = uniqueFile(parent, archive.nameWithoutExtension)
        val staging = File(parent, ".droidfinder-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            var count = 0
            var bytes = 0L
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(++count <= 10_000) { "This archive has too many entries (limit: 10,000)." }
                    val file = File(staging, entry.name).canonicalFile
                    check(file.toPath().startsWith(staging.canonicalFile.toPath()) && file != staging.canonicalFile) { "Unsafe archive path: ${entry.name}" }
                    if (entry.isDirectory) check(file.isDirectory || file.mkdirs()) else {
                        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                        check(!file.exists()) { "Duplicate archive entry: ${entry.name}" }
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val n = zip.read(buffer); if (n < 0) break; bytes += n; check(bytes <= 2L * 1024 * 1024 * 1024) { "Archive exceeds the 2 GB extraction limit." }; output.write(buffer, 0, n) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            check(!target.exists() && staging.renameTo(target)) { "Couldn't finish extracting." }
        } catch (error: Exception) { staging.deleteRecursively(); throw error }
        return target
    }
}

fun validateFilename(name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) { "Enter a name without slashes." }
    require(name.toByteArray().size <= 240) { "That name is too long." }
    return name
}
