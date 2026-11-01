package dev.droidfinder

import java.io.File
import java.io.IOException
import java.nio.file.Files

enum class RenameMode(val title: String) {
    REPLACE("Replace text"),
    PREFIX("Add before name"),
    SUFFIX("Add after name"),
    NUMBER("Name and index"),
}

data class RenameRule(
    val mode: RenameMode,
    val text: String,
    val replacement: String = "",
    val start: Int = 1,
) {
    fun name(original: String, index: Int, folder: Boolean): String {
        val dot = if (folder) -1 else original.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val stem = if (dot < 0) original else original.take(dot)
        val extension = if (dot < 0) "" else original.drop(dot)
        return when (mode) {
            RenameMode.REPLACE -> {
                require(text.isNotEmpty()) { "Enter the text to replace." }
                original.replace(text, replacement)
            }
            RenameMode.PREFIX -> text + original
            RenameMode.SUFFIX -> stem + text + extension
            RenameMode.NUMBER -> {
                require(start >= 0) { "The starting number must be positive." }
                "$text ${start.toLong() + index}$extension"
            }
        }.also(::validateFilename)
    }
}

data class RenameProposal(val file: File, val name: String)

internal class RenameRecoveryException(val changes: List<Pair<String, String>>, cause: Exception) :
    IOException(
        "Rename stopped. Recovery locations were kept: ${changes.joinToString { it.second }}. ${cause.message}",
        cause,
    )

internal fun recoveredRenameName(original: String): String {
    val dot = original.lastIndexOf('.').takeIf { it > 0 }
    val suffix =
        dot?.let { original.substring(it) }
            ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 80 }
            .orEmpty()
    val stem = if (suffix.isEmpty()) original else original.removeSuffix(suffix)
    val prefix = "Recovered "
    val limit = 220 - prefix.toByteArray().size - suffix.toByteArray().size
    var bytes = 0
    val text = buildString {
        for (code in stem.codePoints().toArray()) {
            val next = String(Character.toChars(code))
            val size = next.toByteArray(Charsets.UTF_8).size
            if (bytes + size > limit) break
            append(next)
            bytes += size
        }
    }
    return validateFilename(prefix + text + suffix)
}

internal fun renameCaselessKey(value: String): String =
    java.text.Normalizer.normalize(
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
            .uppercase(java.util.Locale.ROOT),
        java.text.Normalizer.Form.NFC,
    )

/** Distinguish a case/normalization alias from an unselected hard link with an actual name. */
internal fun renameTargetAvailable(
    target: File,
    sources: Collection<File>,
    actualNames: Set<String>? = null,
): Boolean {
    if (!Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
    val names =
        actualNames
            ?: Files.newDirectoryStream(target.parentFile!!.toPath()).use { entries ->
                entries.map { it.fileName.toString() }.toSet()
            }
    if (target.name in names) return sources.any { it.absolutePath == target.absolutePath }
    val folded = renameCaselessKey(target.name)
    return sources
        .asSequence()
        .filter {
            renameCaselessKey(it.name) == folded
        }
        .any { source ->
            runCatching { Files.isSameFile(target.toPath(), source.toPath()) }.getOrDefault(false)
        }
}

internal fun requireRenameDestinations(proposals: List<RenameProposal>) {
    require(proposals.map { it.file.absolutePath }.distinct().size == proposals.size) {
        "The rename contains repeated sources."
    }
    val targets = proposals.map { File(it.file.parentFile!!, validateFilename(it.name)) }
    require(targets.map { it.absolutePath }.distinct().size == targets.size) {
        "Two items would have the same name."
    }
    val sources = proposals.map { it.file }
    val names =
        targets
            .filter { Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
            .map { it.parentFile!! }
            .distinct()
            .associateWith { parent ->
                Files.newDirectoryStream(parent.toPath()).use { entries ->
                    entries.map { it.fileName.toString() }.toSet()
                }
            }
    targets.forEach { target ->
        require(renameTargetAvailable(target, sources, names[target.parentFile])) {
            "${target.name} already exists."
        }
    }
}

fun planRename(files: List<File>, rule: RenameRule): List<RenameProposal> {
    val proposals = files.mapIndexed { index, file ->
        RenameProposal(file, rule.name(file.name, index, file.isDirectory))
    }
    requireRenameDestinations(proposals)
    return proposals
}
