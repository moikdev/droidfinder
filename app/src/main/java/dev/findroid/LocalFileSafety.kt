package dev.findroid

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** A dangling link still owns its name and must never be overwritten implicitly. */
internal fun File.entryExists(): Boolean = Files.exists(toPath(), LinkOption.NOFOLLOW_LINKS)

/** Delete the requested tree without visiting directory-link targets. Stop on the first failure. */
internal fun File.deleteTreeWithoutLinks(): Boolean = try {
    if (entryExists()) {
        Files.walkFileTree(toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
    true
} catch (_: IOException) {
    false
}

/** Provider cycles and deeply nested trees must fail before exhausting the process stack. */
internal class TransferTraversal {
    private val seen = hashSetOf<String>()

    fun visit(path: String, depth: Int) {
        require(depth <= 128 && seen.size < 100_000) {
            "This transfer exceeds the safe traversal limit (128 levels / 100,000 entries). Copy smaller folders separately."
        }
        require(seen.add(path)) { "A folder contains a repeated document. Transfer stopped to protect the source." }
    }
}
