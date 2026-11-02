package dev.findroid

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.util.Properties
import java.util.UUID

internal data class RenamePreset(
    val id: String,
    val name: String,
    val recipe: RenameRecipe,
    val order: RenameOrder = RenameOrder.CURRENT,
    val reverse: Boolean = false,
)

/** No paths or selected items are saved. Invalid catalogs remain untouched until repaired. */
internal class RenamePresetStore(private val file: File) {
    var error: String? = null
        private set

    @Synchronized
    fun load(): List<RenamePreset> =
        try {
            error = null
            if (!file.exists()) emptyList()
            else {
                require(file.length() <= 1_048_576) { "Rename preset file is too large." }
                val p = Properties().apply { file.inputStream().use(::load) }
                require(p.getProperty("version") == "1") { "Rename preset format is unavailable." }
                val ids = p.getProperty("ids", "").split(',').filter(String::isNotEmpty)
                require(ids.size <= 32 && ids.distinct().size == ids.size) {
                    "Invalid preset catalog."
                }
                val result = ids.map { id ->
                    UUID.fromString(id)
                    fun text(key: String, fallback: String = ""): String =
                        p.getProperty("$id.$key", fallback).also {
                            require(it.length <= 512) { "Preset text exceeds 512 characters." }
                        }
                    val recipe =
                        RenameRecipe(
                            RenameMethod.valueOf(text("method", "REPLACE")),
                            RenamePart.valueOf(text("part", "STEM")),
                            text("text"),
                            text("replacement"),
                            text("regex", "false").toBooleanStrict(),
                            text("matchCase", "true").toBooleanStrict(),
                            text("firstOnly", "false").toBooleanStrict(),
                            text("start", "1").toLong(),
                            text("step", "1").toLong(),
                            text("padding", "1").toInt(),
                            text("separator", " "),
                            text("template", "{name} - {index}{dotext}"),
                            RenameLetterCase.valueOf(text("letterCase", "KEEP")),
                            RenameNormalization.valueOf(text("normalization", "KEEP")),
                            text("portableConflicts", "true").toBooleanStrict(),
                        )
                    require(
                        recipe.start >= 0 &&
                            recipe.step in 1..1_000_000 &&
                            recipe.padding in 1..12 &&
                            recipe.separator.length <= 16,
                    ) {
                        "Invalid preset numbering options."
                    }
                    val name = text("name")
                    validatePresetName(name)
                    RenamePreset(
                        id,
                        name,
                        recipe,
                        RenameOrder.valueOf(text("order", "CURRENT")),
                        text("reverse", "false").toBooleanStrict(),
                    )
                }
                require(
                    result.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size ==
                        result.size,
                ) {
                    "Preset names are repeated."
                }
                error = null
                result
            }
        } catch (failure: Exception) {
            error =
                "${failure.message ?: "Couldn't read rename presets"} The original file was preserved."
            emptyList()
        }

    @Synchronized
    fun save(
        name: String,
        recipe: RenameRecipe,
        order: RenameOrder,
        reverse: Boolean,
        id: String? = null,
    ): RenamePreset {
        recipe.validateShape()
        val current = load()
        check(error == null) { error!! }
        val clean = name.trim()
        validatePresetName(clean)
        require(current.none { it.id != id && it.name.equals(clean, true) }) {
            "A preset with that name already exists."
        }
        require(id == null || current.any { it.id == id }) {
            "That preset no longer exists. Save a new one."
        }
        require(id != null || current.size < 32) { "Keep at most 32 rename presets." }
        val preset = RenamePreset(id ?: UUID.randomUUID().toString(), clean, recipe, order, reverse)
        write(current.filterNot { it.id == preset.id } + preset)
        return preset
    }

    @Synchronized
    fun remove(id: String) {
        val current = load()
        check(error == null) { error!! }
        write(current.filterNot { it.id == id })
    }

    internal fun replace(values: List<RenamePreset>) {
        require(values.size <= 32) { "Keep at most 32 rename presets." }
        values.forEach { validatePresetName(it.name); it.recipe.validateShape(); UUID.fromString(it.id) }
        require(values.map { it.id }.distinct().size == values.size)
        require(values.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == values.size)
        write(values)
    }

    private fun write(values: List<RenamePreset>) {
        val p = Properties()
        p["version"] = "1"
        p["ids"] = values.joinToString(",") { it.id }
        values.forEach { preset ->
            fun put(key: String, value: Any) {
                p["${preset.id}.$key"] = value.toString()
            }
            val r = preset.recipe
            put("name", preset.name)
            put("method", r.method)
            put("part", r.part)
            put("text", r.text)
            put("replacement", r.replacement)
            put("regex", r.regex)
            put("matchCase", r.matchCase)
            put("firstOnly", r.firstOnly)
            put("start", r.start)
            put("step", r.step)
            put("padding", r.padding)
            put("separator", r.separator)
            put("template", r.template)
            put("letterCase", r.letterCase)
            put("normalization", r.normalization)
            put("portableConflicts", r.portableConflicts)
            put("order", preset.order)
            put("reverse", preset.reverse)
        }
        file.parentFile?.mkdirs()
        val staging = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            staging.outputStream().use { stream -> p.store(stream, "Findroid rename presets") }
            try {
                Files.move(staging.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), file.toPath(), REPLACE_EXISTING)
            }
        } finally {
            staging.delete()
        }
    }

    private fun validatePresetName(name: String) {
        require(name.isNotBlank() && name.length <= 48 && name.none { it.isISOControl() }) {
            "Use a preset name between 1 and 48 characters."
        }
    }
}
