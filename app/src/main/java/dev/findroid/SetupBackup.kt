package dev.findroid

import java.nio.file.Paths
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal data class SetupPreferences(
    val density: FinderDensity = FinderDensity.COMFORTABLE,
    val extensions: Boolean = true,
    val dark: Boolean = false,
    val sidebarWidth: Int = 206,
    val sidebarVisible: Boolean = true,
    val tagsVisible: Boolean = true,
    val gestureHintDismissed: Boolean = false,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    val zip: ZipLimits = ZipLimits(),
) {
    fun checked() = apply {
        require(sidebarWidth in 150..320) { "Invalid sidebar width in this backup." }
        require(conflictPolicy != ConflictPolicy.REPLACE) { "Automatic replacement cannot be restored as a default." }
        zip.validated()
    }
}

internal enum class SetupSection(val title: String) {
    SIDEBAR("Favorites and sidebar groups"),
    ORGANIZATION("Tags and collections"),
    SEARCHES("Smart Folders"),
    WORKSPACES("Saved workspaces"),
    OPEN_TABS("Open tabs as a saved workspace"),
    APPEARANCE("Folder views and appearance"),
    PRESETS("Rename presets"),
    PREFERENCES("Browsing and archive preferences"),
}

internal data class SetupSnapshot(
    val sidebar: SidebarLayout,
    val organization: OrganizationSnapshot,
    val searches: List<SmartFolder>,
    val workspaces: List<SavedWorkspace>,
    val openTabs: SavedWorkspace?,
    val folderViews: Map<String, FolderView>,
    val folderStyles: Map<String, FolderStyle>,
    val presets: List<RenamePreset>,
    val preferences: SetupPreferences,
) {
    fun count(section: SetupSection): Int = when (section) {
        SetupSection.SIDEBAR -> sidebar.favorites.size
        SetupSection.ORGANIZATION -> organization.tags.assignments.size + organization.collections.size
        SetupSection.SEARCHES -> searches.size
        SetupSection.WORKSPACES -> workspaces.size
        SetupSection.OPEN_TABS -> openTabs?.tabCount ?: 0
        SetupSection.APPEARANCE -> folderViews.size + folderStyles.size
        SetupSection.PRESETS -> presets.size
        SetupSection.PREFERENCES -> 1
    }

    /** These are reference strings only. Export never copies the referenced files. */
    fun locations(): Set<String> = buildSet {
        addAll(sidebar.paths)
        addAll(organization.tags.assignments.keys)
        organization.collections.forEach { addAll(it.paths) }
        searches.forEach { add(it.root); addAll(it.rules.locations); addAll(it.rules.excludedLocations.map { rule -> rule.path }) }
        addAll(folderViews.keys)
        addAll(folderStyles.keys)
        (workspaces + listOfNotNull(openTabs)).forEach { workspace ->
            (workspace.primary.tabs + workspace.secondary.tabs).forEach { tab ->
                addAll(tab.history)
                addAll(tab.rules.locations)
                addAll(tab.rules.excludedLocations.map { it.path })
                with(tab.navigation) {
                    addAll(selected); addAll(expanded)
                    lead?.let(::add); anchor?.let(::add)
                    positions.forEach { (key, value) -> add(key.location); value.path?.let(::add) }
                    budgets.keys.forEach { add(it.location); it.root?.let(::add); addAll(it.rules.locations); addAll(it.rules.excludedLocations.map { rule -> rule.path }) }
                }
            }
        }
    }.filterNot { it.startsWith("@") }.toSet()

    /** Exact reference replacement also works with opaque document IDs; no URI prefix guessing. */
    fun mapLocations(paths: Map<String, String>, tags: Map<String, String> = emptyMap()): SetupSnapshot {
        fun path(v: String) = paths[v] ?: v
        fun rules(v: SearchRules) = v.remapTags(tags).remapLocations(::path)
        fun tab(t: FinderTab): FinderTab {
            val n = t.navigation
            return t.copy(history = t.history.map(::path), rules = rules(t.rules), navigation = n.copy(
                selected = n.selected.map(::path).toSet(), lead = n.lead?.let(::path),
                anchor = n.anchor?.let(::path), expanded = n.expanded.map(::path).toSet(),
                positions = n.positions.map { (key, value) -> key.copy(location = path(key.location)) to value.copy(path = value.path?.let(::path)) }.toMap(),
                budgets = n.budgets.mapKeys { (key, _) -> key.copy(location = path(key.location), root = key.root?.let(::path), rules = rules(key.rules)) },
            ))
        }
        fun pane(p: PaneSnapshot) = p.copy(tabs = p.tabs.map(::tab))
        fun workspace(w: SavedWorkspace) = w.copy(primary = pane(w.primary), secondary = pane(w.secondary))
        val assignments = linkedMapOf<String, Set<String>>()
        organization.tags.assignments.forEach { (old, names) ->
            val target = path(old)
            assignments[target] = assignments[target].orEmpty() + names.map { tags[it] ?: it }
        }
        return copy(
            sidebar = sidebar.copy(favorites = sidebar.favorites.map { it.copy(path = path(it.path)) }.distinctBy { it.path }),
            organization = organization.copy(tags = organization.tags.copy(assignments = assignments), collections = organization.collections.map { it.copy(paths = it.paths.map(::path).distinct()) }),
            searches = searches.map { it.copy(root = path(it.root), rules = rules(it.rules)) },
            workspaces = workspaces.map(::workspace), openTabs = openTabs?.let(::workspace),
            folderViews = folderViews.entries.associate { path(it.key) to it.value },
            folderStyles = folderStyles.entries.associate { path(it.key) to it.value },
        )
    }
}

internal enum class SetupRootKind { WORKSPACE, LOCAL, PROVIDER }
internal data class SetupRoot(val id: String, val name: String, val source: String, val kind: SetupRootKind)
internal data class SetupReference(val path: String, val root: String, val segments: List<String>?)
internal data class SetupArchive(
    val id: String = UUID.randomUUID().toString(),
    val created: String = Instant.now().toString(),
    val sourceVersion: String = BuildConfig.VERSION_NAME,
    val roots: List<SetupRoot>,
    val references: List<SetupReference>,
    val snapshot: SetupSnapshot,
)

internal fun setupNameKey(value: String) = value.lowercase(Locale.ROOT)
internal fun setupStableId(namespace: String, value: String) = UUID.nameUUIDFromBytes("$namespace\u0000$value".toByteArray(Charsets.UTF_8)).toString()
internal fun setupUniqueName(name: String, names: Collection<String>, limit: Int): String {
    val used = names.map(::setupNameKey).toSet()
    if (setupNameKey(name) !in used) return name
    for (i in 1..1000) {
        val suffix = if (i == 1) " (restored)" else " (restored $i)"
        val candidate = name.take(limit - suffix.length) + suffix
        if (setupNameKey(candidate) !in used) return candidate
    }
    error("Too many restored names. Import a smaller setup.")
}

internal fun setupLocalPath(value: String): Boolean = runCatching {
    value.startsWith("/") && value.length <= 8192 && value.none { it == '\u0000' } &&
        Paths.get(value).normalize().toString() == value.trimEnd('/').ifEmpty { "/" } &&
        Charsets.UTF_8.newEncoder().canEncode(value)
}.getOrDefault(false)

internal fun setupSegments(value: List<String>) = value.size <= 128 && value.all {
    it.isNotEmpty() && it != "." && it != ".." && it.length <= 1024 && '/' !in it && '\u0000' !in it && Charsets.UTF_8.newEncoder().canEncode(it)
}

internal data class SetupMerge(
    val value: SetupSnapshot,
    val notes: List<String>,
    val changedSections: Set<SetupSection>,
)

/** The review computes a complete immutable result; Apply never reruns a different merge. */
internal fun mergeSetup(
    current: SetupSnapshot,
    incoming: SetupSnapshot,
    sections: Set<SetupSection>,
    replace: Boolean,
    backupId: String,
): SetupMerge {
    val notes = mutableListOf<String>()
    fun viewKeys(values: Map<String, FolderView>) = values.entries.associate { (path, view) ->
        (if (path.startsWith("content://")) path else java.io.File(path).canonicalPath) to view
    }
    var mapped = incoming.copy(folderViews = viewKeys(incoming.folderViews))
    var organization = current.organization
    val virtual = mutableMapOf<String, String>()
    if (SetupSection.ORGANIZATION in sections) {
        if (replace) organization = incoming.organization
        else {
            val definitions = current.organization.tags.definitions.toMutableList()
            val tagNames = mutableMapOf<String, String>()
            incoming.organization.tags.definitions.forEach { tag ->
                val same = definitions.firstOrNull { renameCaselessKey(it.name) == renameCaselessKey(tag.name) }
                if (same == null) definitions += tag
                else {
                    tagNames[tag.name] = same.name
                    virtual["@tag:${tag.name}"] = "@tag:${same.name}"
                    if (same != tag) notes += "Tag “${tag.name}” uses the existing name and color."
                }
            }
            mapped = mapped.mapLocations(emptyMap(), tagNames)
            val assignments = current.organization.tags.assignments.toMutableMap()
            mapped.organization.tags.assignments.forEach { (path, tags) -> assignments[path] = assignments[path].orEmpty() + tags }
            val collections = current.organization.collections.toMutableList()
            mapped.organization.collections.forEach { collection ->
                val same = collections.firstOrNull { renameCaselessKey(it.name) == renameCaselessKey(collection.name) }
                if (same != null) {
                    val index = collections.indexOf(same)
                    collections[index] = same.copy(paths = (same.paths + collection.paths).distinct())
                    virtual["@collection:${collection.id}"] = "@collection:${same.id}"
                    notes += "Collection “${collection.name}” combines references and keeps its existing color."
                } else {
                    val id = if (collections.any { it.id == collection.id }) setupStableId(backupId, "collection:${collection.id}") else collection.id
                    require(collections.none { it.id == id }) { "A collection ID conflicts. Review a different import mode." }
                    collections += collection.copy(id = id)
                    virtual["@collection:${collection.id}"] = "@collection:$id"
                }
            }
            organization = OrganizationSnapshot(TagSnapshot(definitions, assignments), collections)
        }
    }
    var searches = current.searches
    if (SetupSection.SEARCHES in sections) {
        if (replace) searches = mapped.searches
        else {
            val values = searches.toMutableList()
            mapped.searches.forEach { search ->
                val restoredId = setupStableId(backupId, "smart:${search.id}")
                val identical = values.firstOrNull { (it.name == search.name || it.id in setOf(search.id, restoredId)) && it.root == search.root && it.rules == search.rules }
                if (identical != null) virtual["@smart:${search.id}"] = "@smart:${identical.id}"
                else {
                    val name = setupUniqueName(search.name, values.map { it.name }, 128)
                    val id = if (values.any { it.id == search.id }) setupStableId(backupId, "smart:${search.id}") else search.id
                    require(values.none { it.id == id }) { "A Smart Folder ID conflicts. Review a different import mode." }
                    values += search.copy(id = id, name = name)
                    virtual["@smart:${search.id}"] = "@smart:$id"
                    if (name != search.name) notes += "Smart Folder “${search.name}” will be added as “$name”."
                }
            }
            searches = values
        }
    }
    mapped = mapped.mapLocations(virtual)
    var sidebar = current.sidebar
    if (SetupSection.SIDEBAR in sections) {
        if (replace) sidebar = mapped.sidebar
        else {
            val groups = current.sidebar.groups.toMutableList()
            val ids = mutableMapOf<String, String>()
            mapped.sidebar.groups.forEach { group ->
                val same = groups.firstOrNull { setupNameKey(it.name) == setupNameKey(group.name) }
                if (same != null) { ids[group.id] = same.id; if (same != group) notes += "Group “${group.name}” uses the existing group and collapse setting." }
                else {
                    val id = if (groups.any { it.id == group.id }) setupStableId(backupId, "group:${group.id}") else group.id
                    require(groups.none { it.id == id }) { "A sidebar group ID conflicts." }
                    groups += group.copy(id = id); ids[group.id] = id
                }
            }
            val favorites = sidebar.favorites.toMutableList()
            val identity = OrganizationIdentity()
            val knownPaths = favorites.mapTo(mutableSetOf()) { identity.key(it.path) }
            mapped.sidebar.favorites.forEach { item ->
                if (!knownPaths.add(identity.key(item.path))) notes += "Favorite “${item.label ?: java.io.File(item.path).name}” keeps its existing label and position."
                else {
                    val id = if (favorites.any { it.id == item.id }) setupStableId(backupId, "favorite:${item.id}") else item.id
                    require(favorites.none { it.id == id }) { "A Favorite ID conflicts." }
                    favorites += item.copy(id = id, group = item.group?.let { ids[it] })
                }
            }
            sidebar = sidebar.copy(favorites = favorites, groups = groups)
        }
    }
    val workspaces = (if (SetupSection.WORKSPACES in sections && replace) mapped.workspaces else current.workspaces).toMutableList()
    fun addWorkspace(value: SavedWorkspace) {
        val priorId = setupStableId(backupId, "workspace:${value.id}")
        val same = workspaces.firstOrNull { (it.id in setOf(value.id, priorId) || it.name == value.name) && it.copy(id = value.id, name = value.name) == value }
        if (same != null) return
        val name = setupUniqueName(value.name, workspaces.map { it.name }, 60)
        val id = if (workspaces.any { it.id == value.id }) setupStableId(backupId, "workspace:${value.id}") else value.id
        require(workspaces.none { it.id == id }) { "A saved workspace ID conflicts. Review a different import mode." }
        workspaces += value.copy(id = id, name = name)
        if (name != value.name) notes += "Workspace “${value.name}” will be added as “$name”."
    }
    if (SetupSection.WORKSPACES in sections && !replace) mapped.workspaces.forEach(::addWorkspace)
    if (SetupSection.OPEN_TABS in sections) mapped.openTabs?.let { addWorkspace(it.copy(id = setupStableId(backupId, "open-tabs"), name = "Restored open tabs")) }
    if (SetupSection.ORGANIZATION !in sections) {
        val unavailable = mapped.searches.flatMap { it.rules.referencedTags }.toSet() - organization.tags.definitions.map { it.name }.toSet()
        if (unavailable.isNotEmpty() && SetupSection.SEARCHES in sections) notes += "${unavailable.size} search tags are absent. Include Tags and collections to restore their definitions."
    }
    if (SetupSection.PREFERENCES in sections && !replace) notes += "Browsing and archive preferences keep this tablet’s current values in Merge mode. Use Replace selected sections to restore them."
    val presets = (if (SetupSection.PRESETS !in sections) current.presets else if (replace) mapped.presets else current.presets.toMutableList().also { values ->
        mapped.presets.forEach { preset ->
            if (values.none { it.recipe == preset.recipe && it.order == preset.order && it.reverse == preset.reverse && (it.name == preset.name || it.id in setOf(preset.id, setupStableId(backupId, "preset:${preset.id}"))) }) {
                val name = setupUniqueName(preset.name, values.map { it.name }, 48)
                val id = if (values.any { it.id == preset.id }) setupStableId(backupId, "preset:${preset.id}") else preset.id
                require(values.none { it.id == id }) { "A rename preset ID conflicts." }
                values += preset.copy(id = id, name = name)
                if (name != preset.name) notes += "Rename preset “${preset.name}” will be added as “$name”."
            }
        }
    })
    if (SetupSection.APPEARANCE in sections && !replace) {
        val viewConflicts = mapped.folderViews.count { (path, view) -> current.folderViews[path]?.let { it != view } ?: false }
        val styleConflicts = mapped.folderStyles.count { (path, style) -> current.folderStyles[path]?.let { it != style } ?: false }
        if (viewConflicts > 0) notes += "$viewConflicts folder views keep this tablet’s existing settings."
        if (styleConflicts > 0) notes += "$styleConflicts folders keep this tablet’s existing appearance."
    }
    val next = current.copy(
        sidebar = sidebar,
        organization = organization,
        searches = searches,
        workspaces = workspaces,
        folderViews = if (SetupSection.APPEARANCE !in sections) current.folderViews else if (replace) mapped.folderViews else mapped.folderViews + current.folderViews,
        folderStyles = if (SetupSection.APPEARANCE !in sections) current.folderStyles else if (replace) mapped.folderStyles else mapped.folderStyles + current.folderStyles,
        presets = if (SetupSection.PRESETS in sections) presets else current.presets,
        preferences = if (SetupSection.PREFERENCES in sections && replace) mapped.preferences else current.preferences,
    )
    val changed = sections.filter { section -> when (section) {
        SetupSection.OPEN_TABS -> next.workspaces != current.workspaces && mapped.openTabs != null
        SetupSection.SIDEBAR -> next.sidebar != current.sidebar
        SetupSection.ORGANIZATION -> next.organization != current.organization
        SetupSection.SEARCHES -> next.searches != current.searches
        SetupSection.WORKSPACES -> next.workspaces != current.workspaces
        SetupSection.APPEARANCE -> next.folderViews != current.folderViews || next.folderStyles != current.folderStyles
        SetupSection.PRESETS -> next.presets != current.presets
        SetupSection.PREFERENCES -> next.preferences != current.preferences
    } }.toSet()
    return SetupMerge(next, notes.distinct(), changed)
}

/** Virtual destinations also need review when their tag, collection or saved search is omitted. */
internal fun SetupSnapshot.missingVirtualReferences(): Set<String> {
    val known = setOf("@recent", "@trash") + organization.tags.definitions.map { "@tag:${it.name}" } +
        organization.collections.map { "@collection:${it.id}" } + searches.map { "@smart:${it.id}" }
    return (workspaces + listOfNotNull(openTabs)).flatMap { it.primary.tabs + it.secondary.tabs }
        .flatMap { tab -> tab.history + tab.navigation.positions.keys.map { it.location } + tab.navigation.budgets.keys.map { it.location } }
        .filter { it.startsWith("@") && it !in known }.toSet()
}
internal fun SetupResolution.withVirtualReferences(value: SetupSnapshot, before: SetupSnapshot? = null): SetupResolution {
    val missing = (value.missingVirtualReferences() - before?.missingVirtualReferences().orEmpty()).sorted()
    return copy(issues = (issues + missing.map { SetupReferenceIssue(it, it, "This saved tab refers to a tag, collection or Smart Folder absent from the restored setup.") }).take(100), issueCount = issueCount + missing.size)
}
