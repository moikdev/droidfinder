package dev.findroid

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

/** Shortcuts are references. Reordering, labeling or removing one never mutates its folder. */
data class FavoriteShortcut(
    val id: String,
    val path: String,
    val label: String? = null,
    val group: String? = null,
)

data class FavoriteGroup(val id: String, val name: String, val collapsed: Boolean = false)

enum class SidebarBuiltin(val label: String, val directory: String? = null) {
    RECENTS("Recents"),
    WORKSPACE("Workspace"),
    DOCUMENTS("Documents", "Documents"),
    DOWNLOADS("Downloads", "Download"),
    PICTURES("Pictures", "Pictures"),
    MOVIES("Movies", "Movies"),
    MUSIC("Music", "Music"),
}

data class SidebarLayout(
    val favorites: List<FavoriteShortcut> = emptyList(),
    val groups: List<FavoriteGroup> = emptyList(),
    val builtins: List<SidebarBuiltin> = SidebarBuiltin.entries.toList(),
    val hidden: Set<SidebarBuiltin> = emptySet(),
) {
    val paths: Set<String> get() = favorites.mapTo(linkedSetOf()) { it.path }

    fun checked(): SidebarLayout = apply {
        require(favorites.size <= 1000 && groups.size <= 64) { "The sidebar is full." }
        require(groups.map { it.id }.distinct().size == groups.size) { "Duplicate favorite group." }
        require(favorites.map { it.id }.distinct().size == favorites.size) { "Duplicate shortcut ID." }
        require(favorites.map { it.path }.distinct().size == favorites.size) { "Duplicate shortcut path." }
        require(builtins.size == SidebarBuiltin.entries.size && builtins.toSet() == SidebarBuiltin.entries.toSet()) {
            "Invalid built-in locations."
        }
        groups.forEach { require(validId(it.id)); libraryName(it.name) }
        favorites.forEach {
            require(validId(it.id) && libraryPath(it.path)) { "Invalid folder shortcut." }
            it.label?.let { label -> libraryName(label, 80) }
            require(it.group == null || groups.any { group -> group.id == it.group }) { "Unknown favorite group." }
        }
    }

    /** A stable destination ID avoids stale index errors while another window edits the list. */
    fun place(ids: List<String>, before: String?, group: String?): SidebarLayout {
        require(group == null || groups.any { it.id == group }) { "This favorite group was removed." }
        val chosen = ids.distinct().map { id ->
            favorites.firstOrNull { it.id == id } ?: error("This shortcut was removed.")
        }
        if (before in ids) return this
        val rest = favorites.filterNot { it.id in ids }.toMutableList()
        val index = if (before == null) rest.size else rest.indexOfFirst { it.id == before }.also {
            require(it >= 0 && rest[it].group == group) { "The insertion point changed. Try again." }
        }
        rest.addAll(index, chosen.map { it.copy(group = group) })
        return copy(favorites = rest).checked()
    }

    fun remap(names: List<Pair<String, String>>): SidebarLayout {
        val mapped = favorites.map { it.copy(path = remapPath(it.path, names)) }
        // Preserve the first label and order if two references converge after a reviewed relocation.
        return copy(favorites = mapped.distinctBy { it.path }).checked()
    }

    companion object {
        private fun validId(value: String) = value.length in 1..80 && value.all { it.isLetterOrDigit() || it == '-' }
        fun migrate(paths: Set<String>): SidebarLayout = SidebarLayout(
            favorites = paths.sortedWith(compareBy<String> { File(it).name.lowercase(java.util.Locale.ROOT) }.thenBy { it })
                .map { FavoriteShortcut(UUID.nameUUIDFromBytes(it.toByteArray(Charsets.UTF_8)).toString(), it) },
        ).checked()
    }
}

/** Explicit versioning and strict bounds keep a damaged/unknown setup intact, never silently reset. */
object SidebarJson {
    const val KEY = "sidebarLayoutV1"
    fun encode(layout: SidebarLayout): String {
        layout.checked()
        val root = JsonObject()
        root.addProperty("version", 1)
        root.add("favorites", JsonArray().apply {
            layout.favorites.forEach { item -> add(JsonObject().apply {
                addProperty("id", item.id)
                addProperty("path", item.path)
                item.label?.let { addProperty("label", it) }
                item.group?.let { addProperty("group", it) }
            }) }
        })
        root.add("groups", JsonArray().apply {
            layout.groups.forEach { item -> add(JsonObject().apply {
                addProperty("id", item.id)
                addProperty("name", item.name)
                addProperty("collapsed", item.collapsed)
            }) }
        })
        root.add("builtins", JsonArray().apply { layout.builtins.forEach { add(it.name) } })
        root.add("hidden", JsonArray().apply { layout.hidden.forEach { add(it.name) } })
        return root.toString()
    }

    fun decode(text: String): SidebarLayout {
        require(text.length <= 2_000_000) { "Sidebar setup is too large." }
        val root = JsonParser.parseString(text).asJsonObject
        require(root.get("version").asInt == 1) { "This sidebar setup needs a newer Findroid version." }
        val items = root.getAsJsonArray("favorites")
        val groups = root.getAsJsonArray("groups")
        require(items.size() <= 1000 && groups.size() <= 64) { "Sidebar setup is too large." }
        return SidebarLayout(
            favorites = items.map { value -> value.asJsonObject.let { item ->
                FavoriteShortcut(item.get("id").asString, item.get("path").asString,
                    item.get("label")?.asString, item.get("group")?.asString)
            } },
            groups = groups.map { value -> value.asJsonObject.let { item ->
                FavoriteGroup(item.get("id").asString, item.get("name").asString, item.get("collapsed").asBoolean)
            } },
            builtins = root.getAsJsonArray("builtins").map { SidebarBuiltin.valueOf(it.asString) },
            hidden = root.getAsJsonArray("hidden").mapTo(mutableSetOf()) { SidebarBuiltin.valueOf(it.asString) },
        ).checked()
    }
}
