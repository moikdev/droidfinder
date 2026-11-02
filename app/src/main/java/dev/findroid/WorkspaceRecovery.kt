package dev.findroid

/** Only destinations of tabs that the saved layout will open need recovery. */
internal data class WorkspaceDestination(val path: String, val uses: List<String>)

internal fun SavedWorkspace.openDestinations(virtualSearches: Map<String, SearchRules> = emptyMap()): List<WorkspaceDestination> {
    val uses = linkedMapOf<String, MutableList<String>>()
    fun pane(value: PaneSnapshot, primary: Boolean) {
        val side = if (primary != reversed) "Left pane" else "Right pane"
        value.tabs.forEachIndexed { index, tab ->
            val label = if (dual) "$side · tab ${index + 1}" else "Tab ${index + 1}"
            val search = virtualSearches[tab.location]
            val additional = if (search != null) search.locations.drop(1) else tab.rules.locations
            (listOf(tab.location) + additional).distinct().forEach { path ->
                uses.getOrPut(path) { mutableListOf() } += label
            }
        }
    }
    pane(primary, true)
    if (dual) pane(secondary, false)
    return uses.map { (path, values) -> WorkspaceDestination(path, values.toList()) }
}

/** An explicit destination choice never remaps a working tab or guesses provider child IDs. */
internal fun SavedWorkspace.repairDestinations(
    unavailable: Set<String>,
    replacements: Map<String, String>,
    virtualSearches: Map<String, SearchRules> = emptyMap(),
): SavedWorkspace {
    require(replacements.keys.all { it in unavailable }) { "Only unavailable destinations can be repaired." }
    require(replacements.values.all { it.isNotBlank() && !it.startsWith("@") }) { "Choose a real replacement folder." }
    require(replacements.keys.all { key -> openDestinations(virtualSearches).any { it.path == key } })
    fun pane(value: PaneSnapshot): PaneSnapshot = value.copy(tabs = value.tabs.map { tab ->
        val old = tab.location
        val saved = virtualSearches[old]
        val effective = saved ?: tab.rules
        val target = replacements[old]?.takeIf { it != old }
        val changedRoots = effective.locations.any { replacements[it]?.let { target -> target != it } == true }
        if (target == null && !changedRoots) tab else {
            // Changing one root materializes a Smart Folder in this tab, keeping
            // the shared saved search and all of its other roots independent.
            var rules = saved?.let { it.copy(name = "", nameTerms = (it.nameTerms + listOfNotNull(it.name.takeIf(String::isNotBlank))).distinct()) } ?: tab.rules
            rules = rules.remapLocations { replacements[it] ?: it }
            if (target != null && rules.locations.isNotEmpty())
                rules = rules.copy(locations = (listOf(target) + rules.locations.drop(1)).distinct())
            val destination = target ?: if (saved != null) rules.locations.first() else old
            tab.copy(
                history = tab.history.map { if (it == old) destination else it },
                rules = rules,
                navigation = tab.navigation.copy(
                    selected = emptySet(), lead = null, anchor = null, expanded = emptySet(), multiple = false,
                    positions = tab.navigation.positions.filterKeys { it.location != old },
                    budgets = tab.navigation.budgets.filterKeys { key ->
                        key.location != old && key.root != old && key.rules.locations.none { it in replacements }
                    },
                ),
            )
        }
    })
    return copy(primary = pane(primary), secondary = if (dual) pane(secondary) else secondary)
}

internal data class WorkspaceDestinationStatus(val destination: WorkspaceDestination, val problem: String?, val search: SmartFolder? = null)
internal data class WorkspaceRecoveryReview(
    val original: SavedWorkspace,
    val originals: List<WorkspaceDestinationStatus>,
    val destinations: List<WorkspaceDestinationStatus>,
    val replacements: Map<String, String>,
    val repaired: SavedWorkspace,
) {
    val unresolved get() = destinations.count { it.problem != null }
}
