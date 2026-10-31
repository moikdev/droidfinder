package dev.droidfinder

import org.junit.Assert.*
import org.junit.Test

class NavigationStateTest {
    @Test
    fun relocationIncludesInactiveFocusExpansionAndViewportPaths() {
        val state =
            TabNavigation(
                setOf("/old/A.txt"),
                "/old/B.txt",
                "/old/A.txt",
                setOf("/old/Project"),
                true,
                mapOf(
                    ViewportKey("/old", "list", "search scope") to
                        ViewportAnchor("/old/A.txt", 42, 17),
                ),
            )
        val moved = state.remap(listOf("/old" to "/new"))
        assertEquals(setOf("/new/A.txt"), moved.selected)
        assertEquals("/new/B.txt", moved.lead)
        assertEquals("/new/A.txt", moved.anchor)
        assertEquals(setOf("/new/Project"), moved.expanded)
        assertEquals(
            ViewportAnchor("/new/A.txt", 42, 17),
            moved.positions[ViewportKey("/new", "list", "search scope")],
        )
        assertEquals("/old/B.txt", state.lead)
    }

    @Test
    fun browseLimitsFollowFolderMovesAndAreNormalizedWhenRestored() {
        val key = ListingScope("/old/Folder", SearchRules(tag = "Red"), root = "/old")
        val state = TabNavigation(budgets = mapOf(key to ListingBudget(40_000, 100_000)))
        val moved = state.remap(listOf("/old" to "/new", "@tag:Red" to "@tag:Urgent"))
        assertEquals(
            ListingBudget(40_000, 100_000),
            moved.budgets[ListingScope("/new/Folder", SearchRules(tag = "Urgent"), root = "/new")],
        )
        assertEquals(ListingBudget(50_000, 50_000), ListingBudget(Int.MAX_VALUE, -1).normalized())
        assertEquals(ListingBudget(50_000, 2_000_000), ListingBudget(50_000, 2_000_000).more())
    }

    @Test
    fun renamedTagsUpdateClosedTabHistoryAndItsSearchRules() {
        val tab =
            FinderTab(
                listOf("/folder", "@tag:Red"),
                rules = SearchRules(tag = "Red"),
                pinned = true,
            )
        val renamed = tab.remap(listOf("@tag:Red" to "@tag:Urgent"))
        assertEquals("@tag:Urgent", renamed.location)
        assertEquals("Urgent", renamed.rules.tag)
        assertEquals(tab.id, renamed.id)
        assertTrue(renamed.pinned)
    }

    @Test
    fun movedPrefixDoesNotAlterSiblingNames() {
        val tab =
            FinderTab(
                listOf("/work/Project-other"),
                navigation = TabNavigation(lead = "/work/Project-other/Notes.txt"),
            )
        assertEquals(tab, tab.remap(listOf("/work/Project" to "/archive/Project")))
    }

    @Test
    fun workspaceRelocationKeepsLayoutAndBothPanes() {
        val p =
            PaneSnapshot(
                listOf(FinderTab(listOf("/source/Folder"))),
                0,
                ViewMode.LIST,
                6,
                SortBy.SIZE,
                false,
                GroupBy.NONE,
                false,
                true,
                true,
                false,
                120,
                240,
                280,
            )
        val w =
            SavedWorkspace(
                "id",
                "Studio",
                p,
                p.copy(tabs = listOf(FinderTab(listOf("/target")))),
                true,
                true,
                .6f,
                "secondary",
            )
        val moved = w.remap(listOf("/source" to "/moved"))
        assertEquals("/moved/Folder", moved.primary.tabs.single().location)
        assertEquals("/target", moved.secondary.tabs.single().location)
        assertEquals(2, moved.tabCount)
        assertTrue(moved.reversed)
        assertEquals("secondary", moved.activePane)
    }
}
