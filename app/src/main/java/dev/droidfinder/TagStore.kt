package dev.droidfinder

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

data class FinderTag(val name: String, val color: String)

val defaultTags =
    listOf("Red", "Orange", "Yellow", "Green", "Blue", "Purple").map { FinderTag(it, it) }

data class FinderCollection(
    val id: String,
    val name: String,
    val color: String = "Blue",
    val paths: List<String> = emptyList(),
)

data class TagSnapshot(val definitions: List<FinderTag>, val assignments: Map<String, Set<String>>)

data class OrganizationSnapshot(val tags: TagSnapshot, val collections: List<FinderCollection>)

internal fun libraryName(value: String, limit: Int = 48): String {
    val clean = value.trim()
    require(
        clean.isNotEmpty() &&
            clean.length <= limit &&
            clean.none { it.isISOControl() } &&
            Charsets.UTF_8.newEncoder().canEncode(clean),
    ) {
        "Use a name between 1 and $limit characters, without control characters."
    }
    return clean
}

internal fun libraryPath(value: String): Boolean =
    value.length in 1..8192 &&
        '\u0000' !in value &&
        (value.startsWith("/") || value.startsWith("content://"))

/** Shared tag/collection metadata. Publish to disk before exposing a new immutable snapshot. */
class TagStore(private val file: File) {
    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }

    private val writeLock =
        locks.computeIfAbsent(file.absoluteFile.toPath().normalize().toString()) { Any() }
    var definitions: List<FinderTag> = defaultTags
        private set

    var assignments: Map<String, Set<String>> = emptyMap()
        private set

    var collections: List<FinderCollection> = emptyList()
        private set

    var error: String? = null
        private set

    init {
        reload()
    }

    @Synchronized
    fun snapshot(): OrganizationSnapshot =
        OrganizationSnapshot(
            TagSnapshot(definitions.toList(), assignments.mapValues { it.value.toSet() }),
            collections.map { it.copy(paths = it.paths.toList()) },
        )

    private fun accept(value: OrganizationSnapshot) {
        definitions = value.tags.definitions.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
        assignments =
            value.tags.assignments.mapValues { it.value.toSet() }.filterValues { it.isNotEmpty() }
        collections =
            value.collections
                .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
                .map { it.copy(paths = it.paths.toList()) }
        error = null
    }

    @Synchronized
    fun reload(): OrganizationSnapshot {
        try {
            accept(read())
        } catch (e: Exception) {
            error =
                e.message ?: "Couldn't read the organization library. The original file was kept."
        }
        return snapshot()
    }

    private fun validate(value: OrganizationSnapshot) {
        require(value.tags.definitions.size <= 256) { "A library supports up to 256 tags." }
        require(
            value.tags.definitions
                .map { renameCaselessKey(libraryName(it.name)) }
                .distinct()
                .size == value.tags.definitions.size,
        ) {
            "Tag names must be unique."
        }
        value.tags.definitions.forEach {
            require(defaultTags.any { color -> color.color == it.color }) { "Choose a tag color." }
        }
        val names = value.tags.definitions.map { it.name }.toSet()
        require(value.tags.assignments.size <= 100_000) {
            "A library supports up to 100,000 tagged locations."
        }
        value.tags.assignments.forEach { (path, tags) ->
            require(libraryPath(path)) { "Invalid tagged location." }
            require(tags.all { it in names }) { "An assignment refers to an unknown tag." }
        }
        require(value.collections.size <= 128) { "A library supports up to 128 collections." }
        require(value.collections.map { it.id }.distinct().size == value.collections.size) {
            "Collection IDs must be unique."
        }
        require(
            value.collections.map { renameCaselessKey(libraryName(it.name, 64)) }.distinct().size ==
                value.collections.size,
        ) {
            "Collection names must be unique."
        }
        val characters =
            value.tags.assignments.entries.sumOf { (path, tags) ->
                path.length.toLong() + tags.sumOf { it.length.toLong() }
            } + value.collections.sumOf { c -> c.paths.sumOf { it.length.toLong() } }
        require(characters <= 16L * 1024 * 1024) { "This library would exceed 16 MiB." }
        require(value.collections.sumOf { it.paths.size } <= 100_000) {
            "A library supports up to 100,000 collection references."
        }
        value.collections.forEach { collection ->
            require(collection.id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "Invalid collection ID." }
            require(
                collection.paths.size <= 10_000 &&
                    collection.paths.distinct().size == collection.paths.size,
            ) {
                "A collection supports up to 10,000 distinct locations."
            }
            require(collection.paths.all(::libraryPath)) { "Invalid collection location." }
            require(defaultTags.any { it.color == collection.color }) {
                "Choose a collection color."
            }
        }
    }

    private fun read(): OrganizationSnapshot {
        if (!file.exists())
            return OrganizationSnapshot(TagSnapshot(defaultTags, emptyMap()), emptyList())
        require(file.isFile && file.length() <= 16L * 1024 * 1024) {
            "The library is invalid or larger than 16 MiB; the original file was kept."
        }
        val bytes =
            file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 16 * 1024 * 1024) {
                        "The library grew beyond 16 MiB; the original file was kept."
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        require(bytes.isNotEmpty()) {
            "The library is empty or damaged; the original file was kept."
        }
        require(bytes.size <= 16 * 1024 * 1024) {
            "The library grew beyond 16 MiB; the original file was kept."
        }
        val properties = Properties().apply { bytes.inputStream().use(::load) }
        val version = properties.getProperty("catalog")
        require(version == null || version == "1" || version == "2") {
            "This library version isn't supported. The original file was kept."
        }
        val keys = properties.stringPropertyNames()
        val known = mutableSetOf("catalog")
        val tags =
            keys
                .filter { it.startsWith("definition:") }
                .map {
                    known.add(it)
                    FinderTag(it.removePrefix("definition:"), properties.getProperty(it))
                }
                .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
                .let { if (it.isEmpty() && version == null) defaultTags else it }
        val values =
            keys
                .filter { it.startsWith("item:") }
                .associate {
                    known.add(it)
                    it.removePrefix("item:") to
                        properties
                            .getProperty(it)
                            .split('\u001f')
                            .filter(String::isNotEmpty)
                            .toSet()
                }
        val collectionIds =
            keys
                .filter { it.startsWith("collection:") }
                .map {
                    require(version == "2") { "Invalid legacy collection data." }
                    it.removePrefix("collection:").substringBefore(':')
                }
                .distinct()
                .also { require(it.size <= 128) { "Too many collections." } }
        val groups =
            collectionIds
                .map { id ->
                    fun field(suffix: String): String {
                        val key = "collection:$id:$suffix"
                        known.add(key)
                        return properties.getProperty(key) ?: error("Incomplete collection data.")
                    }
                    val name = field("name")
                    val color = field("color")
                    val count =
                        field("count").toIntOrNull()?.takeIf { it in 0..10_000 }
                            ?: error("Invalid collection size.")
                    FinderCollection(id, name, color, List(count) { field("item:$it") })
                }
                .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
        require(keys.all { it in known }) {
            "Unrecognized library fields; the original file was kept."
        }
        return OrganizationSnapshot(TagSnapshot(tags, values), groups).also(::validate)
    }

    private fun publish(value: OrganizationSnapshot) {
        validate(value)
        val properties =
            Properties().apply {
                setProperty("catalog", "2")
                value.tags.definitions.forEach { setProperty("definition:${it.name}", it.color) }
                value.tags.assignments
                    .filterValues { it.isNotEmpty() }
                    .forEach { (path, tags) ->
                        setProperty("item:$path", tags.sorted().joinToString("\u001f"))
                    }
                value.collections.forEach { c ->
                    setProperty("collection:${c.id}:name", c.name)
                    setProperty("collection:${c.id}:color", c.color)
                    setProperty("collection:${c.id}:count", c.paths.size.toString())
                    c.paths.forEachIndexed { i, path ->
                        setProperty("collection:${c.id}:item:$i", path)
                    }
                }
            }
        val output =
            object : ByteArrayOutputStream() {
                override fun write(value: Int) {
                    require(size() < 16 * 1024 * 1024) { "This library would exceed 16 MiB." }
                    super.write(value)
                }

                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    require(size().toLong() + count <= 16L * 1024 * 1024) {
                        "This library would exceed 16 MiB."
                    }
                    super.write(bytes, offset, count)
                }
            }
        properties.store(output, "DroidFinder organization library")
        val bytes = output.toByteArray()
        require(bytes.size <= 16 * 1024 * 1024) { "This library would exceed 16 MiB." }
        val parent = file.absoluteFile.parentFile!!
        parent.mkdirs()
        val staging = File.createTempFile(".${file.name}-", ".tmp", parent)
        try {
            FileOutputStream(staging).use {
                it.write(bytes)
                it.fd.sync()
            }
            try {
                Files.move(
                    staging.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            accept(value)
        } finally {
            staging.delete()
        }
    }

    @Synchronized
    private fun change(edit: (OrganizationSnapshot) -> OrganizationSnapshot) =
        synchronized(writeLock) {
            val current = reload()
            check(error == null) { error!! }
            val next = edit(current)
            if (next != current) publish(next)
        }

    fun define(name: String, color: String) = change { current ->
        val clean = libraryName(name)
        require(
            current.tags.definitions.none {
                renameCaselessKey(it.name) == renameCaselessKey(clean) && it.name != clean
            },
        ) {
            "A tag with that name already exists."
        }
        current.copy(
            tags =
                current.tags.copy(
                    definitions =
                        current.tags.definitions.filterNot { it.name == clean } +
                            FinderTag(clean, color),
                ),
        )
    }

    fun remove(name: String, expected: TagSnapshot? = null) = change { current ->
        check(expected == null || current.tags == expected) {
            "Tags changed while this confirmation was open. Review them again."
        }
        current.copy(
            tags =
                TagSnapshot(
                    current.tags.definitions.filterNot { it.name == name },
                    current.tags.assignments
                        .mapValues { it.value - name }
                        .filterValues { it.isNotEmpty() },
                ),
        )
    }

    fun assign(paths: Collection<String>, tag: String?) = change { current ->
        require(tag == null || current.tags.definitions.any { it.name == tag }) {
            "That tag no longer exists."
        }
        val updated = current.tags.assignments.toMutableMap()
        val remove = tag != null && paths.isNotEmpty() && paths.all { tag in updated[it].orEmpty() }
        paths.forEach { path ->
            val value =
                if (tag == null) emptySet()
                else if (remove) updated[path].orEmpty() - tag else updated[path].orEmpty() + tag
            if (value.isEmpty()) updated.remove(path) else updated[path] = value
        }
        current.copy(tags = current.tags.copy(assignments = updated))
    }

    fun replace(values: Map<String, Set<String>>) = change {
        it.copy(tags = it.tags.copy(assignments = values))
    }

    fun restore(catalog: List<FinderTag>, values: Map<String, Set<String>>) = change {
        it.copy(tags = TagSnapshot(catalog, values))
    }

    fun verifyTags(expected: TagSnapshot) {
        val actual = reload()
        check(error == null && actual.tags == expected) {
            "Tags changed since this action. Review the current library before continuing."
        }
    }

    fun restoreTags(expected: TagSnapshot, replacement: TagSnapshot) = change {
        check(it.tags == expected) {
            "Tags changed since this action. Review the current library before continuing."
        }
        it.copy(tags = replacement)
    }

    fun restoreCollections(expected: List<FinderCollection>, replacement: List<FinderCollection>) =
        change {
            check(it.collections == expected) {
                "Collections changed since this action. Review the library before continuing."
            }
            it.copy(collections = replacement)
        }

    fun verifyCollections(expected: List<FinderCollection>) {
        val actual = reload()
        check(error == null && actual.collections == expected) {
            "Collections changed since this action. Review the library before continuing."
        }
    }

    fun rename(old: String, name: String, color: String) = change { current ->
        val clean = libraryName(name)
        require(current.tags.definitions.any { it.name == old }) { "That tag no longer exists." }
        require(
            current.tags.definitions.none {
                it.name != old && renameCaselessKey(it.name) == renameCaselessKey(clean)
            },
        ) {
            "A tag with that name already exists."
        }
        current.copy(
            tags =
                TagSnapshot(
                    current.tags.definitions.map {
                        if (it.name == old) FinderTag(clean, color) else it
                    },
                    current.tags.assignments.mapValues { (_, tags) ->
                        tags.map { if (it == old) clean else it }.toSet()
                    },
                ),
        )
    }

    fun createCollection(name: String, color: String, paths: List<String>): String {
        val id = java.util.UUID.randomUUID().toString()
        change {
            it.copy(
                collections =
                    it.collections +
                        FinderCollection(id, libraryName(name, 64), color, paths.distinct()),
            )
        }
        return id
    }

    fun updateCollection(id: String, name: String, color: String) = change { current ->
        require(current.collections.any { it.id == id }) { "That collection no longer exists." }
        current.copy(
            collections =
                current.collections.map {
                    if (it.id == id) it.copy(name = libraryName(name, 64), color = color) else it
                },
        )
    }

    fun deleteCollection(id: String, expected: FinderCollection? = null) = change { current ->
        check(expected == null || current.collections.firstOrNull { it.id == id } == expected) {
            "This collection changed. Review it again."
        }
        current.copy(collections = current.collections.filterNot { it.id == id })
    }

    fun collectionItems(
        id: String,
        paths: List<String>,
        add: Boolean,
        expected: FinderCollection? = null,
    ) = change { current ->
        check(expected == null || current.collections.firstOrNull { it.id == id } == expected) {
            "This collection changed. Review its references again."
        }
        require(current.collections.any { it.id == id }) { "That collection no longer exists." }
        current.copy(
            collections =
                current.collections.map { c ->
                    if (c.id != id) c
                    else
                        c.copy(
                            paths =
                                if (add) (c.paths + paths).distinct() else c.paths - paths.toSet(),
                        )
                },
        )
    }

    fun relocate(pairs: List<Pair<String, String>>, copy: Boolean = false) {
        if (pairs.none { libraryPath(it.first) && libraryPath(it.second) }) return
        change { current ->
            val source = current.tags.assignments
            val updated = source.toMutableMap()
            if (!copy)
                source.keys
                    .filter { path -> pairs.any { pathWithin(path, it.first) } }
                    .forEach(updated::remove)
            source.forEach { (path, tags) ->
                val match =
                    pairs.filter { pathWithin(path, it.first) }.maxByOrNull { it.first.length }
                if (match != null) updated[match.second + path.removePrefix(match.first)] = tags
            }
            current.copy(
                tags = current.tags.copy(assignments = updated),
                collections =
                    if (copy) current.collections
                    else
                        current.collections.map {
                            it.copy(
                                paths = it.paths.map { path -> remapPath(path, pairs) }.distinct(),
                            )
                        },
            )
        }
    }

    fun migrate(legacy: Map<String, String>) {
        if (!file.exists() && assignments.isEmpty() && legacy.isNotEmpty())
            replace(legacy.mapValues { setOf(it.value) })
    }
}

fun pathWithin(path: String, parent: String) =
    path == parent ||
        (!parent.startsWith("@") && path.startsWith("$parent/")) ||
        (parent.startsWith("content://") &&
            (path.startsWith("$parent%2F") || path.startsWith("$parent%2f")))

fun remapPath(path: String, pairs: List<Pair<String, String>>): String {
    val match = pairs.filter { pathWithin(path, it.first) }.maxByOrNull { it.first.length }
    return if (match == null) path else match.second + path.removePrefix(match.first)
}
