package dev.droidfinder

import java.io.File

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

fun planRename(files: List<File>, rule: RenameRule): List<RenameProposal> {
    val proposals = files.mapIndexed { index, file ->
        RenameProposal(file, rule.name(file.name, index, file.isDirectory))
    }
    val destinations = proposals.map { File(it.file.parentFile!!, it.name).absolutePath }
    require(destinations.distinct().size == destinations.size) {
        "Two items would have the same name."
    }
    val sources = files.map { it.absolutePath }.toSet()
    proposals.forEach {
        val target = File(it.file.parentFile!!, it.name)
        require(!target.exists() || target.absolutePath in sources) { "${it.name} already exists." }
    }
    return proposals
}
