package dev.droidfinder

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Paths
import java.time.Instant

enum class TagChange(val title: String) {
    KEEP("Keep"),
    ADD("Add"),
    REMOVE("Remove"),
}

internal fun reviewedTags(
    before: Set<String>,
    changes: Map<String, TagChange>,
    clear: Boolean = false,
): Set<String> {
    val result = if (clear) linkedSetOf() else before.toMutableSet()
    changes.forEach { (name, change) ->
        when (change) {
            TagChange.KEEP -> Unit
            TagChange.ADD -> result.add(name)
            TagChange.REMOVE -> result.remove(name)
        }
    }
    return result.toSet()
}

internal data class LibraryExport(
    val snapshot: OrganizationSnapshot,
    val workspace: String,
    val selection: Set<String>? = null,
    val created: String = Instant.now().toString(),
) {
    val assignments =
        if (selection == null) snapshot.tags.assignments
        else selection.associateWith { snapshot.tags.assignments[it].orEmpty() }
    val definitions =
        if (selection == null) snapshot.tags.definitions
        else snapshot.tags.definitions.filter { it.name in assignments.values.flatten().toSet() }
    val collections =
        if (selection == null) snapshot.collections
        else
            snapshot.collections.mapNotNull {
                val paths = it.paths.filter { path -> path in selection }
                if (paths.isEmpty()) null else it.copy(paths = paths)
            }
    val locations = (assignments.keys + collections.flatMap { it.paths }).toSet()

    fun json(): String {
        // Android exposes app storage through both /data/user/0 and /data/data.
        // Resolve only the trusted Workspace root, never referenced file contents.
        val workspaceRoots =
            listOf(workspace, File(workspace).canonicalPath)
                .map { Paths.get(it).normalize() }
                .distinct()
        fun location(path: String) =
            JsonObject().apply {
                val workspaceRoot =
                    if (path.startsWith("content://")) null
                    else workspaceRoots.firstOrNull { Paths.get(path).normalize().startsWith(it) }
                when {
                    path.startsWith("content://") -> {
                        addProperty("type", "provider")
                        addProperty("uri", path)
                    }
                    workspaceRoot != null -> {
                        addProperty("type", "workspace")
                        addProperty(
                            "path",
                            workspaceRoot.relativize(Paths.get(path).normalize()).toString(),
                        )
                    }
                    else -> {
                        addProperty("type", "local")
                        addProperty("path", path)
                    }
                }
            }
        val root =
            JsonObject().apply {
                addProperty("format", "dev.droidfinder.organization")
                addProperty("version", 1)
                addProperty("created", created)
                addProperty("scope", if (selection == null) "library" else "selection")
                add(
                    "tags",
                    JsonArray().apply {
                        definitions.forEach { tag ->
                            add(
                                JsonObject().apply {
                                    addProperty("name", tag.name)
                                    addProperty("color", tag.color)
                                },
                            )
                        }
                    },
                )
                add(
                    "assignments",
                    JsonArray().apply {
                        assignments.toSortedMap().forEach { (path, tags) ->
                            add(
                                JsonObject().apply {
                                    add("location", location(path))
                                    add("tags", JsonArray().apply { tags.sorted().forEach(::add) })
                                },
                            )
                        }
                    },
                )
                add(
                    "collections",
                    JsonArray().apply {
                        collections.forEach { collection ->
                            add(
                                JsonObject().apply {
                                    addProperty("id", collection.id)
                                    addProperty("name", collection.name)
                                    addProperty("color", collection.color)
                                    add(
                                        "items",
                                        JsonArray().apply {
                                            collection.paths.forEach { add(location(it)) }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        return (GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) +
                "\n")
            .also {
                require(it.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024) {
                    "This export exceeds 32 MiB. Export a smaller selection."
                }
            }
    }
}
