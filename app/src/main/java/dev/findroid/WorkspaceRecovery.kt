package dev.findroid

/** Only destinations of tabs that the saved layout will open need recovery. */
internal data class WorkspaceDestination(val path: String, val uses: List<String>)

internal fun SavedWorkspace.openDestinations(): List<WorkspaceDestination> {
    val uses = linkedMapOf<String, MutableList<String>>()
    fun pane(value: PaneSnapshot, primary: Boolean) {
        val side = if (primary != reversed) "Left pane" else "Right pane"
        value.tabs.forEachIndexed { index, tab ->
            uses.getOrPut(tab.location) { mutableListOf() } +=
                if (dual) "$side · tab ${index + 1}" else "Tab ${index + 1}"
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
    require(replacements.keys.all { key -> openDestinations().any { it.path == key } })
    fun pane(value: PaneSnapshot): PaneSnapshot = value.copy(tabs = value.tabs.map { tab ->
        val target = replacements[tab.location]
        if (target == null || target == tab.location) tab else {
            val old = tab.location
            tab.copy(
                history = tab.history.map { if (it == old) target else it },
                // A Smart Folder's intrinsic name filter becomes a normal AND term;
                // the tab's query can then keep its usual name field on the new folder.
                rules = virtualSearches[old]?.let { it.copy(name = "", nameTerms = (it.nameTerms + listOfNotNull(it.name.takeIf(String::isNotBlank))).distinct()) } ?: tab.rules,
                navigation = tab.navigation.copy(
                    selected = emptySet(), lead = null, anchor = null, expanded = emptySet(), multiple = false,
                    positions = tab.navigation.positions.filterKeys { it.location != old },
                    budgets = tab.navigation.budgets.filterKeys { it.location != old && it.root != old },
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
