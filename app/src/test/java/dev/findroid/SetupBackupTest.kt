package dev.findroid

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SetupBackupTest {
    private fun id() = UUID.randomUUID().toString()
    private fun pane(path: String) = PaneSnapshot(listOf(FinderTab(listOf(path))), 0, ViewMode.LIST, 0, SortBy.NAME, true, GroupBy.NONE, false, true, true, false, 120, 240, 280)
    private fun workspace(id: String = id(), name: String = "Writing", path: String = "/old/Projects") = SavedWorkspace(id, name, pane(path), pane("/old/Review"), true, false, .5f, "primary")
    private fun empty() = SetupSnapshot(SidebarLayout(), OrganizationSnapshot(TagSnapshot(defaultTags, emptyMap()), emptyList()), emptyList(), emptyList(), null, emptyMap(), emptyMap(), emptyList(), SetupPreferences())
    private val all = SetupSection.entries.toSet()
    private val backup = "fixture-backup"

    @Test fun rootsReplaceExactReferencesAcrossTabsSelectionAnchorsAndBudgets() {
        val old = "/old/Projects"
        val t = FinderTab(listOf(old, "@recent"), index = 0, navigation = TabNavigation(setOf("$old/Note.txt"), "$old/Note.txt", "$old/Note.txt", setOf(old), true,
            mapOf(ViewportKey(old, "list") to ViewportAnchor("$old/Note.txt", 4, 18)), mapOf(ListingScope(old, root = old) to ListingBudget())))
        val p = pane(old).copy(tabs = listOf(t))
        val source = empty().copy(sidebar = SidebarLayout(listOf(FavoriteShortcut("fav", old))),
            organization = OrganizationSnapshot(TagSnapshot(defaultTags, mapOf("$old/Note.txt" to setOf("Red"))), listOf(FinderCollection("collection", "Notes", paths = listOf("$old/Note.txt")))),
            searches = listOf(SmartFolder(id(), "Notes", old, SearchRules(name = "Note"))),
            workspaces = listOf(workspace().copy(primary = p)), folderViews = mapOf(old to FolderView()), folderStyles = mapOf(old to FolderStyle()))
        val mapping = mapOf(old to "/new/Work", "$old/Note.txt" to "/new/Work/Note.txt")
        val result = source.mapLocations(mapping)
        assertFalse(old in result.locations())
        assertFalse("$old/Note.txt" in result.locations())
        assertTrue("/new/Work" in result.locations())
        assertEquals("@recent", result.workspaces.single().primary.tabs.single().history.last())
        assertEquals(setOf("/new/Work/Note.txt"), result.workspaces.single().primary.tabs.single().navigation.selected)
        assertEquals(old, source.sidebar.favorites.single().path)
    }

    @Test fun remappingNeverGuessesAnOpaqueProviderSuffix() {
        val a = "content://cloud/tree/root/document/random-31"
        val b = "content://cloud/tree/root/document/random-312"
        val source = empty().copy(organization = OrganizationSnapshot(TagSnapshot(defaultTags, mapOf(a to setOf("Red"), b to setOf("Blue"))), emptyList()))
        val result = source.mapLocations(mapOf(a to "/new/Document"))
        assertEquals(setOf("Blue"), result.organization.tags.assignments[b])
        assertEquals(setOf("Red"), result.organization.tags.assignments["/new/Document"])
        assertFalse(result.organization.tags.assignments.containsKey("/new/Document2"))
    }

    @Test fun convergingPathsCombineTagsAndCollectionReferencesWithoutDuplicates() {
        val source = empty().copy(organization = OrganizationSnapshot(TagSnapshot(defaultTags, mapOf("/old/a" to setOf("Red"), "/old/b" to setOf("Blue"))), listOf(FinderCollection("c", "Both", paths = listOf("/old/a", "/old/b")))))
        val result = source.mapLocations(mapOf("/old/a" to "/new/item", "/old/b" to "/new/item"))
        assertEquals(setOf("Red", "Blue"), result.organization.tags.assignments["/new/item"])
        assertEquals(listOf("/new/item"), result.organization.collections.single().paths)
    }

    @Test fun mergeKeepsCurrentFavoriteLabelsOrderGroupsAndBuiltins() {
        val current = empty().copy(sidebar = SidebarLayout(listOf(FavoriteShortcut("a", "/same", "My label")), listOf(FavoriteGroup("group", "Work", true)), hidden = setOf(SidebarBuiltin.MUSIC)))
        val incoming = empty().copy(sidebar = SidebarLayout(listOf(FavoriteShortcut("a", "/new", "New folder", "incoming"), FavoriteShortcut("b", "/same", "Old label")), listOf(FavoriteGroup("incoming", "work"))))
        val result = mergeSetup(current, incoming, all, false, backup).value.sidebar
        assertEquals(listOf("/same", "/new"), result.favorites.map { it.path })
        assertEquals("My label", result.favorites.first().label)
        assertEquals("group", result.favorites.last().group)
        assertEquals(setOf(SidebarBuiltin.MUSIC), result.hidden)
        assertTrue(result.groups.single().collapsed)
        assertEquals(2, result.favorites.map { it.id }.distinct().size)
    }

    @Test fun caseInsensitiveTagMergeRemapsSearchesAndVirtualTabLocations() {
        val current = empty().copy(organization = OrganizationSnapshot(TagSnapshot(listOf(FinderTag("review", "Blue")), emptyMap()), emptyList()))
        val incoming = empty().copy(organization = OrganizationSnapshot(TagSnapshot(listOf(FinderTag("Review", "Red")), mapOf("/file" to setOf("Review"))), emptyList()),
            searches = listOf(SmartFolder(id(), "Review files", "/old", SearchRules(tag = "Review", tags = setOf("Review")))),
            workspaces = listOf(workspace(path = "@tag:Review")))
        val result = mergeSetup(current, incoming, all, false, backup)
        assertEquals(listOf(FinderTag("review", "Blue")), result.value.organization.tags.definitions)
        assertEquals(setOf("review"), result.value.organization.tags.assignments["/file"])
        assertEquals(setOf("review"), result.value.searches.single().rules.filterTags)
        assertEquals("@tag:review", result.value.workspaces.single().primary.tabs.single().location)
        assertTrue(result.notes.any { "existing name and color" in it })
    }

    @Test fun collectionNameMergeRemapsSavedTabsToTheExistingCollectionId() {
        val current = empty().copy(organization = OrganizationSnapshot(TagSnapshot(defaultTags, emptyMap()), listOf(FinderCollection("existing", "Trip", "Blue", listOf("/one")))))
        val incoming = empty().copy(organization = OrganizationSnapshot(TagSnapshot(defaultTags, emptyMap()), listOf(FinderCollection("incoming", "trip", "Red", listOf("/two")))),
            workspaces = listOf(workspace(path = "@collection:incoming")))
        val result = mergeSetup(current, incoming, all, false, backup).value
        assertEquals(listOf("/one", "/two"), result.organization.collections.single().paths)
        assertEquals("Blue", result.organization.collections.single().color)
        assertEquals("@collection:existing", result.workspaces.single().primary.tabs.single().location)
    }

    @Test fun changedSearchWithSameNameGetsANewNameAndCorrectVirtualTab() {
        val searchId = id()
        val current = empty().copy(searches = listOf(SmartFolder(searchId, "Notes", "/current", SearchRules(name = "today"))))
        val incoming = empty().copy(searches = listOf(SmartFolder(searchId, "Notes", "/old", SearchRules(name = "archive"))), workspaces = listOf(workspace(path = "@smart:$searchId")))
        val result = mergeSetup(current, incoming, all, false, backup).value
        assertEquals(listOf("Notes", "Notes (restored)"), result.searches.map { it.name })
        assertEquals("@smart:${result.searches.last().id}", result.workspaces.single().primary.tabs.single().location)
    }

    @Test fun replaceOnlyAffectsChosenSectionsAndOpenTabsBecomeASavedWorkspace() {
        val current = empty().copy(sidebar = SidebarLayout(listOf(FavoriteShortcut("current", "/current"))), preferences = SetupPreferences(dark = true), workspaces = listOf(workspace()))
        val incoming = empty().copy(sidebar = SidebarLayout(listOf(FavoriteShortcut("incoming", "/incoming"))), openTabs = workspace(name = "Open tabs"))
        val result = mergeSetup(current, incoming, setOf(SetupSection.SIDEBAR, SetupSection.OPEN_TABS), true, backup)
        assertEquals("/incoming", result.value.sidebar.favorites.single().path)
        assertTrue(result.value.preferences.dark)
        assertEquals(2, result.value.workspaces.size)
        assertEquals("Restored open tabs", result.value.workspaces.last().name)
        assertEquals(current.openTabs, result.value.openTabs)
        assertEquals(current.organization, result.value.organization)
    }

    @Test fun mergeLeavesPreferencesAndFolderViewOverridesWhileReplaceRestoresThem() {
        val current = empty().copy(preferences = SetupPreferences(dark = true), folderViews = mapOf("/same" to FolderView(mode = ViewMode.COLUMNS)))
        val incoming = empty().copy(folderViews = mapOf("/same" to FolderView(mode = ViewMode.GALLERY), "/new" to FolderView()))
        val merged = mergeSetup(current, incoming, all, false, backup).value
        assertTrue(merged.preferences.dark)
        assertEquals(ViewMode.COLUMNS, merged.folderViews.getValue("/same").mode)
        assertTrue("/new" in merged.folderViews)
        val replaced = mergeSetup(current, incoming, setOf(SetupSection.PREFERENCES, SetupSection.APPEARANCE), true, backup).value
        assertFalse(replaced.preferences.dark)
        assertEquals(ViewMode.GALLERY, replaced.folderViews.getValue("/same").mode)
    }

    @Test fun excludedSectionsDoNotTriggerUnrelatedPresetOrNameConflicts() {
        val presetId = id()
        val existing = RenamePreset(presetId, "Name", RenameRecipe())
        val current = empty().copy(presets = listOf(existing))
        val incoming = empty().copy(presets = listOf(existing.copy(recipe = RenameRecipe(text = "changed")), existing.copy(id = setupStableId(backup, "preset:$presetId"), name = "Collision")))
        val result = mergeSetup(current, incoming, setOf(SetupSection.PREFERENCES), false, backup)
        assertEquals(current, result.value)
        assertTrue(result.changedSections.isEmpty())
    }

    @Test fun relativePathsRejectTraversalAbsoluteSegmentsAndInvalidUnicode() {
        assertTrue(setupSegments(listOf("Project", "File with spaces.txt")))
        assertFalse(setupSegments(listOf("..", "secret")))
        assertFalse(setupSegments(listOf("/storage/other")))
        assertFalse(setupSegments(listOf("a/b")))
        assertFalse(setupSegments(listOf("\uD800")))
        assertFalse(setupLocalPath("/old/../secret"))
        assertFalse(setupLocalPath("relative/folder"))
        assertTrue(setupLocalPath("/storage/emulated/0/Work"))
    }

    @Test fun sectionInventoryExcludesUnchosenRoots() {
        val source = empty().copy(sidebar = SidebarLayout(listOf(FavoriteShortcut("fav", "/favorites"))), organization = OrganizationSnapshot(TagSnapshot(defaultTags, mapOf("/tags" to setOf("Red"))), emptyList()), workspaces = listOf(workspace(path = "/tabs")))
        assertEquals(setOf("/favorites"), source.sectionLocations(setOf(SetupSection.SIDEBAR)))
        assertFalse("/tags" in source.sectionLocations(setOf(SetupSection.WORKSPACES)))
    }
    @Test fun reimportingAnUnchangedBackupReusesPreviouslyRenamedSearchesAndWorkspaces() {
        val searchId = id(); val workspaceId = id(); val presetId = id()
        val current = empty().copy(searches = listOf(SmartFolder(searchId, "Notes", "/current", SearchRules())),
            workspaces = listOf(workspace(workspaceId, path = "/current")),
            presets = listOf(RenamePreset(presetId, "Rename", RenameRecipe(text = "current"))))
        val source = empty().copy(searches = listOf(SmartFolder(searchId, "Notes", "/old", SearchRules(name = "backup"))),
            workspaces = listOf(workspace(workspaceId, path = "/old")),
            presets = listOf(RenamePreset(presetId, "Rename", RenameRecipe(text = "backup"))))
        val first = mergeSetup(current, source, all, false, backup)
        val second = mergeSetup(first.value, source, all, false, backup)
        assertEquals(first.value, second.value)
        assertTrue(second.changedSections.isEmpty())
    }

    @Test fun unicodeLibraryNamesUseTheSameIdentityAsTheOrganizationStore() {
        val current = empty().copy(organization = OrganizationSnapshot(TagSnapshot(listOf(FinderTag("Straße", "Blue")), emptyMap()), listOf(FinderCollection("current", "Café", paths = listOf("/current")))))
        val incoming = empty().copy(organization = OrganizationSnapshot(TagSnapshot(listOf(FinderTag("STRASSE", "Red")), mapOf("/old" to setOf("STRASSE"))), listOf(FinderCollection("source", "Cafe\u0301", paths = listOf("/old")))))
        val merged = mergeSetup(current, incoming, all, false, backup).value.organization
        assertEquals(listOf(FinderTag("Straße", "Blue")), merged.tags.definitions)
        assertEquals(setOf("Straße"), merged.tags.assignments["/old"])
        assertEquals(listOf("/current", "/old"), merged.collections.single().paths)
    }

    @Test fun reviewIncludesVirtualDestinationsWhoseOrganizationSectionWasNotImported() {
        val source = empty().copy(workspaces = listOf(workspace(path = "@collection:missing")))
        val missing = source.missingVirtualReferences()
        assertEquals(setOf("@collection:missing"), missing)
        val result = SetupResolution(emptyMap(), emptyList(), 0, 0).withVirtualReferences(source)
        assertEquals(1, result.issueCount)
        assertEquals("@collection:missing", result.issues.single().target)
    }

    @Test fun openTabsGetTheirRequestedNamedWorkspaceEvenWhenASavedLayoutHasIdenticalTabs() {
        val layout = workspace()
        val source = empty().copy(workspaces = listOf(layout), openTabs = layout.copy(id = "open-tabs", name = "Open tabs"))
        val first = mergeSetup(empty(), source, all, false, backup)
        assertEquals(listOf("Writing", "Restored open tabs"), first.value.workspaces.map { it.name })
        assertEquals(first.value, mergeSetup(first.value, source, all, false, backup).value)
    }

    @Test fun unrelatedExistingMissingTabHistoryDoesNotBlockRestoringPreferences() {
        val before = empty().copy(workspaces = listOf(workspace(path = "@tag:old-missing")))
        val after = before.copy(preferences = SetupPreferences(dark = true))
        assertEquals(0, SetupResolution(emptyMap(), emptyList(), 0, 0).withVirtualReferences(after, before).issueCount)
        val newlyMissing = after.copy(workspaces = after.workspaces + workspace(name = "New layout", path = "@tag:new-missing"))
        assertEquals(listOf("@tag:new-missing"), SetupResolution(emptyMap(), emptyList(), 0, 0).withVirtualReferences(newlyMissing, before).issues.map { it.target })
    }

    @Test fun exactSearchExclusionsAreInventoriedAndRemappedAcrossEveryRulesContainer() {
        val exclusions = listOf(SearchExclusion("/old/Cache"), SearchExclusion("content://cloud/document/opaque-1", false))
        val rules = SearchRules(excludedLocations = exclusions, date = SearchDate.THIS_MONTH, size = SearchSize.OVER_1_GB)
        val tab = FinderTab(listOf("/root"), rules = rules, navigation = TabNavigation(budgets = mapOf(ListingScope("/root", rules) to ListingBudget())))
        val workspace = workspace(path = "/root").copy(primary = pane("/root").copy(tabs = listOf(tab)))
        val source = empty().copy(searches = listOf(SmartFolder(id(), "Exact", "/root", rules)), workspaces = listOf(workspace), openTabs = workspace.copy(id = "open"))
        assertTrue(source.locations().containsAll(exclusions.map { it.path }))
        val mapping = mapOf("/old/Cache" to "/new/Cache", "content://cloud/document/opaque-1" to "content://new/document/random-7")
        val result = source.mapLocations(mapping)
        assertTrue(result.locations().containsAll(mapping.values))
        assertFalse(result.locations().any { it in mapping.keys })
        val allRules = listOf(result.searches.single().rules) + (result.workspaces + result.openTabs!!).flatMap { listOf(it.primary, it.secondary) }.flatMap { it.tabs }.flatMap { listOf(it.rules) + it.navigation.budgets.keys.map { it.rules } }
        allRules.filter { it.excludedLocations.isNotEmpty() }.forEach {
            assertEquals(mapping.values.toList(), it.excludedLocations.map { it.path })
            assertEquals(listOf(true, false), it.excludedLocations.map { it.folder })
            assertEquals(SearchDate.THIS_MONTH, it.date)
            assertEquals(SearchSize.OVER_1_GB, it.size)
        }
        assertEquals(exclusions, source.searches.single().rules.excludedLocations)
    }

}
