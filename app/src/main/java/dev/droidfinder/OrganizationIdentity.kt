package dev.droidfinder

import java.io.File
import java.nio.file.LinkOption

/** Entry identity resolves parent aliases but keeps a final symlink as its own entry. */
internal class OrganizationIdentity {
    private val parents = mutableMapOf<String, File>()

    fun key(path: String): String {
        if (!path.startsWith("/")) return path
        val file = File(path).absoluteFile
        return runCatching {
            val parent = file.parentFile
            val candidate =
                if (parent == null) file
                else File(parents.getOrPut(parent.path) { parent.canonicalFile }, file.name)
            // NOFOLLOW_LINKS preserves the final link, including links selected for rename/Trash.
            runCatching { candidate.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS).toString() }
                .getOrElse { candidate.toPath().normalize().toString() }
        }
            .getOrElse { file.toPath().normalize().toString() }
    }
}

internal fun organizationKey(path: String) = OrganizationIdentity().key(path)

internal fun normalizedOrganization(value: OrganizationSnapshot): OrganizationSnapshot {
    val identity = OrganizationIdentity()
    val assignments = linkedMapOf<String, Set<String>>()
    value.tags.assignments.forEach { (path, tags) ->
        val key = identity.key(path)
        assignments[key] = assignments[key].orEmpty() + tags
    }
    return value.copy(
        tags = value.tags.copy(assignments = assignments),
        collections =
            value.collections.map { it.copy(paths = it.paths.map(identity::key).distinct()) },
    )
}

internal data class ReferenceRepair(
    val before: OrganizationSnapshot,
    val source: String,
    val target: String,
    val carryTags: Boolean = true,
) {
    init {
        require(libraryPath(source) && libraryPath(target) && source != target) {
            "Choose a different file or folder."
        }
        require(
            source in before.tags.assignments || before.collections.any { source in it.paths },
        ) {
            "This location is no longer saved in the library. Refresh and review again."
        }
    }

    val affectedCollections = before.collections.filter { source in it.paths }
    val sourceTags = before.tags.assignments[source].orEmpty()
    val targetTags = before.tags.assignments[target].orEmpty()
    val after: OrganizationSnapshot
        get() {
            val tags = before.tags.assignments.toMutableMap()
            if (carryTags && sourceTags.isNotEmpty()) {
                tags.remove(source)
                tags[target] = targetTags + sourceTags
            }
            return before.copy(
                tags = before.tags.copy(assignments = tags),
                collections =
                    before.collections.map {
                        it.copy(
                            paths =
                                it.paths
                                    .map { path -> if (path == source) target else path }
                                    .distinct(),
                        )
                    },
            )
        }
}
