package dev.findroid

import com.google.re2j.Matcher
import com.google.re2j.Pattern
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class RenameMethod(val title: String) {
    REPLACE("Replace text"),
    PREFIX("Add before name"),
    SUFFIX("Add after name"),
    NUMBER("Name and index"),
    CASE("Change case"),
    TEMPLATE("Template"),
}

internal enum class RenamePart(val title: String) {
    STEM("Name only"),
    FULL("Full filename"),
    EXTENSION("Extension only"),
}

internal enum class RenameLetterCase(val title: String) {
    KEEP("Keep case"),
    LOWER("lowercase"),
    UPPER("UPPERCASE"),
    TITLE("Title Case"),
}

internal enum class RenameNormalization(val title: String) {
    KEEP("Keep Unicode"),
    NFC("Unicode NFC"),
    NFD("Unicode NFD"),
}

internal enum class RenameOrder(val title: String) {
    CURRENT("Current order"),
    NAME("Name"),
    NATURAL("Natural name"),
    MODIFIED("Modified date"),
    SIZE("File size"),
}

internal data class RenamePhoto(
    val taken: LocalDateTime? = null,
    val camera: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

internal data class RenameInput(
    val id: String,
    val parent: String,
    val name: String,
    val folder: Boolean,
    val modified: Long?,
    val bytes: Long?,
    val revision: String,
    val parentName: String = "",
    val tags: List<String> = emptyList(),
    val ancestors: Set<String> = emptySet(),
    val photo: RenamePhoto? = null,
    val problem: String? = null,
    val mime: String = "application/octet-stream",
)

internal data class RenameRecipe(
    val method: RenameMethod = RenameMethod.REPLACE,
    val part: RenamePart = RenamePart.STEM,
    val text: String = "",
    val replacement: String = "",
    val regex: Boolean = false,
    val matchCase: Boolean = true,
    val firstOnly: Boolean = false,
    val start: Long = 1,
    val step: Long = 1,
    val padding: Int = 1,
    val separator: String = " ",
    val template: String = "{name} - {index}{dotext}",
    val letterCase: RenameLetterCase = RenameLetterCase.KEEP,
    val normalization: RenameNormalization = RenameNormalization.KEEP,
    val portableConflicts: Boolean = true,
) {
    companion object {
        fun legacy(rule: RenameRule) =
            RenameRecipe(
                method = RenameMethod.valueOf(rule.mode.name),
                part =
                    if (rule.mode in listOf(RenameMode.REPLACE, RenameMode.PREFIX)) RenamePart.FULL
                    else RenamePart.STEM,
                text = rule.text,
                replacement = rule.replacement,
                start = rule.start.toLong(),
            )
    }
}

internal data class RenameSibling(val id: String, val parent: String, val name: String)

internal data class RenameRow(
    val input: RenameInput,
    val name: String?,
    val included: Boolean,
    val error: String?,
) {
    val changed
        get() = included && name != null && input.name != name && error == null
}

internal data class RenamePlan(
    val rows: List<RenameRow>,
    val recipe: RenameRecipe,
    val zone: String,
) {
    val changes
        get() = rows.filter { it.changed }

    val errors
        get() = rows.count { it.included && it.error != null }

    val canApply
        get() = changes.isNotEmpty() && errors == 0
}

private fun splitRenameName(name: String, folder: Boolean): Pair<String, String> {
    val dot = if (folder) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
    return if (dot < 0) name to "" else name.take(dot) to name.substring(dot + 1)
}

private fun recipeCase(value: String, style: RenameLetterCase): String =
    when (style) {
        RenameLetterCase.KEEP -> value
        RenameLetterCase.LOWER -> value.lowercase(Locale.ROOT)
        RenameLetterCase.UPPER -> value.uppercase(Locale.ROOT)
        RenameLetterCase.TITLE -> {
            var beginning = true
            buildString {
                value.codePoints().toArray().forEach { code ->
                    val letter = Character.isLetterOrDigit(code)
                    val character = String(Character.toChars(code))
                    append(
                        if (letter && beginning) character.uppercase(Locale.ROOT)
                        else character.lowercase(Locale.ROOT),
                    )
                    val mark =
                        Character.getType(code) in
                            listOf(
                                Character.NON_SPACING_MARK.toInt(),
                                Character.COMBINING_SPACING_MARK.toInt(),
                                Character.ENCLOSING_MARK.toInt(),
                            )
                    if (!mark) beginning = !letter
                }
            }
        }
    }

private fun normalizedRename(value: String, mode: RenameNormalization): String =
    when (mode) {
        RenameNormalization.KEEP -> value
        RenameNormalization.NFC -> Normalizer.normalize(value, Normalizer.Form.NFC)
        RenameNormalization.NFD -> Normalizer.normalize(value, Normalizer.Form.NFD)
    }

private fun metadataFilename(value: String): String {
    require(value.length <= 1024) { "Metadata is too long for a filename." }
    return value
        .map { if (it == '/' || it == '\u0000' || it.isISOControl()) '_' else it }
        .joinToString("")
}

private class RecipeRenamer(private val recipe: RenameRecipe, private val zone: ZoneId) {
    private val pattern =
        if (recipe.method == RenameMethod.REPLACE && recipe.text.isNotEmpty()) {
            require(recipe.text.length <= 512 && recipe.replacement.length <= 512) {
                "Find and replacement text are limited to 512 characters."
            }
            Pattern.compile(
                if (recipe.regex) recipe.text else Pattern.quote(recipe.text),
                if (recipe.matchCase) 0 else Pattern.CASE_INSENSITIVE,
            )
        } else null

    init {
        recipe.validateShape()
        require(recipe.text.length <= 512 && recipe.template.length <= 512) {
            "Use at most 512 characters."
        }
        require(recipe.start >= 0 && recipe.step in 1..1_000_000 && recipe.padding in 1..12) {
            "Use a nonnegative start, a step from 1 to 1,000,000 and 1–12 digits."
        }
        require(recipe.separator.length <= 16) { "The number separator is too long." }
    }

    private fun index(position: Int): String =
        Math.addExact(recipe.start, Math.multiplyExact(position.toLong(), recipe.step))
            .toString()
            .padStart(recipe.padding, '0')

    fun name(input: RenameInput, position: Int): String {
        require(input.name.length <= 1024) { "The source name is too long to preview." }
        val (stem, extension) = splitRenameName(input.name, input.folder)
        val selected =
            when (recipe.part) {
                RenamePart.STEM -> stem
                RenamePart.FULL -> input.name
                RenamePart.EXTENSION -> extension
            }
        if (
            recipe.part == RenamePart.EXTENSION &&
                (input.folder || extension.isEmpty()) &&
                recipe.method != RenameMethod.TEMPLATE
        )
            return input.name
        val result =
            when (recipe.method) {
                RenameMethod.PREFIX -> recipe.text + selected
                RenameMethod.SUFFIX -> selected + recipe.text
                RenameMethod.NUMBER ->
                    recipe.text +
                        (if (recipe.text.isEmpty()) "" else recipe.separator) +
                        index(position)
                RenameMethod.CASE -> recipeCase(selected, recipe.letterCase)
                RenameMethod.TEMPLATE -> template(input, stem, extension, position)
                RenameMethod.REPLACE -> {
                    if (pattern == null) selected
                    else {
                        val matcher = pattern.matcher(selected)
                        val buffer = StringBuffer()
                        while (matcher.find()) {
                            matcher.appendReplacement(
                                buffer,
                                if (recipe.regex) recipe.replacement
                                else Matcher.quoteReplacement(recipe.replacement),
                            )
                            require(buffer.length <= 240) { "The resulting name is too long." }
                            if (recipe.firstOnly) break
                        }
                        matcher.appendTail(buffer)
                        buffer.toString()
                    }
                }
            }
        val assembled =
            if (recipe.method == RenameMethod.TEMPLATE) result
            else
                when (recipe.part) {
                    RenamePart.STEM -> result + if (extension.isEmpty()) "" else ".$extension"
                    RenamePart.FULL -> result
                    RenamePart.EXTENSION -> "$stem.$result"
                }
        val name = normalizedRename(assembled, recipe.normalization)
        require(!isWorkingFileName(name)) { "That prefix is reserved for working files." }
        require(Charsets.UTF_8.newEncoder().canEncode(name)) {
            "The resulting name contains invalid Unicode."
        }
        return validateFilename(name)
    }

    private fun template(
        input: RenameInput,
        stem: String,
        extension: String,
        position: Int,
    ): String {
        val source = recipe.template
        val out = StringBuilder()
        var cursor = 0
        while (cursor < source.length) {
            val char = source[cursor]
            if (char == '{' && source.getOrNull(cursor + 1) == '{') {
                out.append('{')
                cursor += 2
            } else if (char == '}' && source.getOrNull(cursor + 1) == '}') {
                out.append('}')
                cursor += 2
            } else if (char == '{') {
                val end = source.indexOf('}', cursor + 1)
                require(end >= 0) { "Close the template token with }." }
                val token = source.substring(cursor + 1, end)
                val key = token.substringBefore(':')
                val format = token.substringAfter(':', "yyyy-MM-dd")
                val value =
                    when (key) {
                        "name" -> stem
                        "full" -> input.name
                        "ext" -> extension
                        "dotext" -> if (extension.isEmpty()) "" else ".$extension"
                        "index" -> index(position)
                        "parent" -> metadataFilename(input.parentName)
                        "size" -> input.bytes?.toString() ?: error("File size is unavailable.")
                        "tag" -> metadataFilename(input.tags.sorted().firstOrNull().orEmpty())
                        "tags" -> metadataFilename(input.tags.sorted().joinToString("_"))
                        "camera" ->
                            metadataFilename(
                                input.photo?.camera
                                    ?: error("Load photo metadata; no camera is available."),
                            )
                        "width" ->
                            input.photo?.width?.toString()
                                ?: error("Load photo metadata; image width is unavailable.")
                        "height" ->
                            input.photo?.height?.toString()
                                ?: error("Load photo metadata; image height is unavailable.")
                        "modified",
                        "taken" -> {
                            require(format.length in 1..64) {
                                "Use a date pattern up to 64 characters."
                            }
                            val formatter = DateTimeFormatter.ofPattern(format, Locale.ROOT)
                            if (key == "modified")
                                formatter.format(
                                    Instant.ofEpochMilli(
                                            input.modified
                                                ?: error("Modified date is unavailable."),
                                        )
                                        .atZone(zone),
                                )
                            else
                                formatter.format(
                                    input.photo?.taken
                                        ?: error(
                                            "Load photo metadata; capture date is unavailable.",
                                        ),
                                )
                        }
                        else -> error("Unknown template token: {$token}")
                    }
                require(':' !in token || key in listOf("modified", "taken")) {
                    "Only date tokens accept a format."
                }
                out.append(value)
                cursor = end + 1
            } else {
                require(char != '}') { "Use }} for a literal closing brace." }
                out.append(char)
                cursor++
            }
            require(out.length <= 240) { "The resulting name is too long." }
        }
        return out.toString()
    }
}

internal fun RenameRecipe.validateShape() {
    require(text.length <= 512 && replacement.length <= 512 && template.length <= 512) {
        "Use at most 512 characters in each recipe field."
    }
    require(start >= 0 && step in 1..1_000_000 && padding in 1..12) {
        "Use a nonnegative start, a step from 1 to 1,000,000 and 1–12 digits."
    }
    require(separator.length <= 16) { "The number separator is too long." }
}

internal fun renameFieldText(value: String, limit: Int): String {
    var end = minOf(value.length, limit)
    if (
        end > 0 &&
            end < value.length &&
            Character.isHighSurrogate(value[end - 1]) &&
            Character.isLowSurrogate(value[end])
    )
        end--
    return value.substring(0, end)
}

internal fun orderedRenameInputs(
    inputs: List<RenameInput>,
    order: RenameOrder,
    reverse: Boolean,
): List<RenameInput> {
    val result =
        when (order) {
            RenameOrder.CURRENT -> inputs
            RenameOrder.NAME ->
                inputs.sortedWith(
                    compareBy<RenameInput> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name },
                )
            RenameOrder.NATURAL ->
                inputs.sortedWith { a, b -> NaturalFilenameOrder.compare(a.name, b.name) }
            RenameOrder.MODIFIED -> inputs.sortedBy { it.modified ?: Long.MAX_VALUE }
            RenameOrder.SIZE -> inputs.sortedBy { it.bytes ?: Long.MAX_VALUE }
        }
    return if (reverse) result.reversed() else result
}

internal fun planRecipeRename(
    inputs: List<RenameInput>,
    siblings: List<RenameSibling>,
    excluded: Set<String>,
    recipe: RenameRecipe,
    zone: ZoneId,
    check: () -> Unit = {},
): RenamePlan {
    require(inputs.size <= 10_000) { "Select at most 10,000 items for one reviewed rename." }
    require(inputs.map { it.id }.distinct().size == inputs.size) {
        "The selection contains repeated items."
    }
    val renamer = runCatching { RecipeRenamer(recipe, zone) }
    var position = 0
    var rows = inputs.map { input ->
        check()
        val included = input.id !in excluded
        if (!included) RenameRow(input, null, false, null)
        else {
            val current = position++
            val result = runCatching {
                require(input.problem == null) { input.problem!! }
                renamer.getOrThrow().name(input, current)
            }
            RenameRow(input, result.getOrNull(), true, result.exceptionOrNull()?.message)
        }
    }
    fun key(name: String) = if (recipe.portableConflicts) renameCaselessKey(name) else name
    val proposed =
        rows.filter { it.included && it.name != null }.groupBy { it.input.parent to key(it.name!!) }
    val selected = rows.filter { it.included }.map { it.input.id }.toSet()
    val occupied = siblings.filter { it.id !in selected }.groupBy { it.parent to key(it.name) }
    rows = rows.map { row ->
        check()
        if (!row.included || row.error != null || row.name == null) row
        else {
            val target = row.input.parent to key(row.name)
            val issue =
                when {
                    proposed.getValue(target).size > 1 ->
                        "Two selected items would have the same name."
                    occupied[target]?.isNotEmpty() == true ->
                        "An unselected item already uses this name."
                    row.input.ancestors.any { it in selected } ->
                        "Rename a selected folder and its contents separately."
                    else -> null
                }
            row.copy(error = issue)
        }
    }
    return RenamePlan(rows, recipe, zone.id)
}
