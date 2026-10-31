package dev.droidfinder

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

data class FinderTag(val name: String, val color: String)

val defaultTags =
    listOf("Red", "Orange", "Yellow", "Green", "Blue", "Purple").map { FinderTag(it, it) }

/** A tag can belong to many items, and each item can have several named tags. */
class TagStore(private val file: File) {
    var definitions: List<FinderTag> = defaultTags
        private set

    var assignments: Map<String, Set<String>> = emptyMap()
        private set

    init {
        if (file.exists()) {
            val properties = Properties().apply { file.inputStream().use(::load) }
            definitions =
                properties
                    .stringPropertyNames()
                    .filter { it.startsWith("definition:") }
                    .map { FinderTag(it.removePrefix("definition:"), properties.getProperty(it)) }
                    .sortedBy { it.name.lowercase() }
                    .let {
                        if (it.isEmpty() && !properties.containsKey("catalog")) defaultTags else it
                    }
            assignments =
                properties
                    .stringPropertyNames()
                    .filter { it.startsWith("item:") }
                    .associate {
                        it.removePrefix("item:") to
                            properties
                                .getProperty(it)
                                .split('\u001f')
                                .filter(String::isNotEmpty)
                                .toSet()
                    }
        }
    }

    private fun save() {
        val properties = Properties()
        properties["catalog"] = "1"
        definitions.forEach { properties["definition:${it.name}"] = it.color }
        assignments
            .filterValues { it.isNotEmpty() }
            .forEach { (path, tags) -> properties["item:$path"] = tags.joinToString("\u001f") }
        file.parentFile?.mkdirs()
        val staging = File(file.parentFile, "${file.name}.tmp")
        staging.outputStream().use { properties.store(it, "DroidFinder multiple named tags") }
        Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun define(name: String, color: String) {
        require(name.isNotBlank() && name.length <= 48 && name.none { it.isISOControl() }) {
            "Use a tag name between 1 and 48 characters."
        }
        val clean = name.trim()
        require(definitions.none { it.name.equals(clean, true) && it.name != clean }) {
            "A tag with that name already exists."
        }
        require(defaultTags.any { it.color == color }) { "Choose a tag color." }
        definitions = definitions.filterNot { it.name == clean } + FinderTag(clean, color)
        save()
    }

    fun remove(name: String) {
        definitions = definitions.filterNot { it.name == name }
        assignments = assignments.mapValues { it.value - name }.filterValues { it.isNotEmpty() }
        save()
    }

    fun assign(paths: Collection<String>, tag: String?) {
        require(tag == null || definitions.any { it.name == tag }) { "That tag no longer exists." }
        val updated = assignments.toMutableMap()
        val remove = tag != null && paths.isNotEmpty() && paths.all { tag in updated[it].orEmpty() }
        paths.forEach { path ->
            val value =
                if (tag == null) emptySet()
                else if (remove) updated[path].orEmpty() - tag else updated[path].orEmpty() + tag
            if (value.isEmpty()) updated.remove(path) else updated[path] = value
        }
        assignments = updated
        save()
    }

    fun replace(values: Map<String, Set<String>>) {
        assignments = values.mapValues { it.value.toSet() }
        save()
    }

    fun restore(catalog: List<FinderTag>, values: Map<String, Set<String>>) {
        definitions = catalog.toList()
        assignments = values.mapValues { it.value.toSet() }
        save()
    }

    fun rename(old: String, name: String, color: String) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 48 && clean.none { it.isISOControl() }) {
            "Use a tag name between 1 and 48 characters."
        }
        require(definitions.any { it.name == old }) { "That tag no longer exists." }
        require(definitions.none { it.name != old && it.name.equals(clean, true) }) {
            "A tag with that name already exists."
        }
        require(defaultTags.any { it.color == color }) { "Choose a color." }
        definitions = definitions.map { if (it.name == old) FinderTag(clean, color) else it }
        assignments = assignments.mapValues { (_, tags) ->
            tags.map { if (it == old) clean else it }.toSet()
        }
        save()
    }

    fun relocate(pairs: List<Pair<String, String>>, copy: Boolean = false) {
        val source = assignments.toMap()
        val updated = assignments.toMutableMap()
        if (!copy)
            source.keys
                .filter { path -> pairs.any { pathWithin(path, it.first) } }
                .forEach(updated::remove)
        source.forEach { (path, tags) ->
            val match = pairs.filter { pathWithin(path, it.first) }.maxByOrNull { it.first.length }
            if (match != null) updated[match.second + path.removePrefix(match.first)] = tags
        }
        assignments = updated
        save()
    }

    fun migrate(legacy: Map<String, String>) {
        if (!file.exists() && assignments.isEmpty() && legacy.isNotEmpty()) {
            assignments = legacy.mapValues { setOf(it.value) }
            save()
        }
    }
}

fun pathWithin(path: String, parent: String) =
    path == parent ||
        path.startsWith("$parent/") ||
        (parent.startsWith("content://") &&
            (path.startsWith("$parent%2F") || path.startsWith("$parent%2f")))

fun remapPath(path: String, pairs: List<Pair<String, String>>): String {
    val match = pairs.filter { pathWithin(path, it.first) }.maxByOrNull { it.first.length }
    return if (match == null) path else match.second + path.removePrefix(match.first)
}
