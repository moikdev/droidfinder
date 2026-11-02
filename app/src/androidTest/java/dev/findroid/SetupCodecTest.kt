package dev.findroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SetupCodecTest {
    private fun uuid() = UUID.randomUUID().toString()
    private fun sample(): SetupArchive {
        val root = "/storage/OLD-TABLET"
        val folder = "$root/Projects"
        val file = "$folder/Keep.txt"
        val rules = SearchRules(name = "Keep", tags = setOf("Research"), content = "needle", includeSubfolders = false, excludedFolderNames = setOf("Cache"))
        val tab = FinderTab(listOf(folder, "@collection:collection"), index = 0, search = "Keep", rules = rules, pinned = true,
            navigation = TabNavigation(setOf(file), file, file, setOf(folder), true, mapOf(ViewportKey(folder, "list", "query") to ViewportAnchor(file, 2, 12)), mapOf(ListingScope(folder, rules, root = root) to ListingBudget())))
        val pane = PaneSnapshot(listOf(tab), 0, ViewMode.COLUMNS, 2, SortBy.MODIFIED, false, GroupBy.TAG, true, true, true, false, 140, 280, 300, defaults = FolderView(mode = ViewMode.COLUMNS))
        val workspace = SavedWorkspace(uuid(), "Research layout", pane, pane, true, true, .6f, "secondary")
        val snapshot = SetupSnapshot(SidebarLayout(listOf(FavoriteShortcut("favorite", folder, "旅の資料", "group")), listOf(FavoriteGroup("group", "Research", true)), hidden = setOf(SidebarBuiltin.MUSIC)),
            OrganizationSnapshot(TagSnapshot(listOf(FinderTag("Research", "Purple")), mapOf(file to setOf("Research"))), listOf(FinderCollection("collection", "References", "Blue", listOf(file)))),
            listOf(SmartFolder(uuid(), "Research notes", folder, rules)), listOf(workspace), workspace.copy(id = "open-tabs", name = "Open tabs"),
            mapOf(folder to FolderView(mode = ViewMode.GALLERY)), mapOf(folder to FolderStyle("Green", "📷")), listOf(RenamePreset(uuid(), "Numbered notes", RenameRecipe(method = RenameMethod.NUMBER, start = 5, padding = 3))),
            SetupPreferences(dark = true, sidebarWidth = 240, zip = ZipLimits(entries = 2000)))
        val source = SetupRoot("old", "Old tablet storage", root, SetupRootKind.LOCAL)
        val references = snapshot.locations().sorted().map { path -> SetupReference(path, source.id, path.removePrefix(root).trimStart('/').takeIf { it.isNotEmpty() }?.split('/') ?: emptyList()) }
        return SetupArchive(roots = listOf(source), references = references, snapshot = snapshot)
    }
    private fun reject(change: (JSONObject) -> Unit) {
        val json = JSONObject(SetupJson.encode(sample()))
        change(json)
        assertThrows(Exception::class.java) { SetupJson.decode(json.toString()) }
    }

    @Test fun fullSetupRoundTripRetainsOrderingRulesTabsAndPreferences() {
        val source = sample()
        val restored = SetupJson.decode(SetupJson.encode(source))
        assertEquals(source, restored)
        assertTrue(restored.snapshot.workspaces.single().primary.tabs.single().pinned)
        assertEquals("旅の資料", restored.snapshot.sidebar.favorites.single().label)
        assertEquals(setOf("Research"), restored.snapshot.searches.single().rules.filterTags)
        assertEquals("needle", restored.snapshot.searches.single().rules.content)
        assertEquals("📷", restored.snapshot.folderStyles.values.single().symbol)
        assertTrue(restored.snapshot.preferences.dark)
    }
    @Test fun futureFormatAndUnknownFieldsAreRejectedWithoutDowngrading() {
        reject { it.put("version", 2) }
        reject { it.put("futureSection", JSONObject()) }
        reject { it.getJSONObject("setup").getJSONObject("preferences").put("execute", "code") }
    }
    @Test fun traversalAndInventedLocalRelativeRootsAreRejected() {
        reject { it.getJSONArray("references").getJSONObject(0).put("segments", JSONArray(listOf("..", "secret"))) }
        reject { it.getJSONArray("references").getJSONObject(0).put("segments", JSONArray(listOf("Different"))) }
    }
    @Test fun missingAndRepeatedReferenceInventoryCannotDropMetadataSilently() {
        reject { it.getJSONArray("references").remove(0) }
        reject { it.getJSONArray("references").put(it.getJSONArray("references").getJSONObject(0)) }
        reject { it.getJSONArray("references").getJSONObject(0).put("root", "not-a-root") }
    }
    @Test fun permissiveNavigationDecoderCannotSilentlyNormalizeAnImportedBackup() {
        reject { it.getJSONObject("setup").getJSONObject("workspaces").getJSONArray("workspaces").getJSONObject(0).getJSONObject("primary").put("active", 99) }
        reject { it.getJSONObject("setup").getJSONObject("workspaces").getJSONArray("workspaces").getJSONObject(0).getJSONObject("primary").getJSONArray("tabs").getJSONObject(0).put("future", true) }
        reject { it.getJSONObject("setup").getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("tagMatch", "UNSUPPORTED") }
    }
    @Test fun overCapacityWorkspacesCannotBeTruncatedToAnApparentlySuccessfulRestore() {
        reject { root ->
            val values = root.getJSONObject("setup").getJSONObject("workspaces").getJSONArray("workspaces")
            val first = values.getJSONObject(0).toString()
            for (i in 1..24) values.put(JSONObject(first).put("id", uuid()).put("name", "Layout $i"))
        }
    }
    @Test fun stringCoercionsFractionalIntegersAndOutOfRangePreferencesAreRejected() {
        reject { it.getJSONObject("setup").getJSONObject("preferences").put("dark", "true") }
        reject { it.getJSONObject("setup").getJSONObject("preferences").put("sidebarWidth", 200.5) }
        reject { it.getJSONObject("setup").getJSONObject("preferences").getJSONObject("zip").put("entries", 9_999_999) }
    }
    @Test fun repeatedTagAssignmentsAndCollectionItemsAreRejected() {
        reject { it.getJSONObject("setup").getJSONArray("assignments").put(it.getJSONObject("setup").getJSONArray("assignments").getJSONObject(0)) }
        reject { it.getJSONObject("setup").getJSONArray("collections").getJSONObject(0).getJSONArray("paths").put("/storage/OLD-TABLET/Projects/Keep.txt") }
    }
    @Test fun providerReferencesCanKeepTheirOpaqueOriginalIdsAndUnknownRelativePath() {
        val uri = "content://example.provider/tree/root/document/opaque-719"
        val root = SetupRoot("provider", "Cloud folder", "content://example.provider/tree/root/document/root", SetupRootKind.PROVIDER)
        val source = sample()
        val mapped = source.snapshot.mapLocations(source.snapshot.locations().associateWith { uri })
            .copy(sidebar = SidebarLayout(listOf(FavoriteShortcut("favorite", uri))), organization = OrganizationSnapshot(TagSnapshot(defaultTags, mapOf(uri to setOf("Red"))), emptyList()), folderViews = emptyMap(), folderStyles = emptyMap())
        val backup = source.copy(roots = listOf(root), references = listOf(SetupReference(uri, root.id, null)), snapshot = mapped)
        val restored = SetupJson.decode(SetupJson.encode(backup))
        assertNull(restored.references.single().segments)
        assertEquals(uri, restored.snapshot.sidebar.favorites.single().path)
    }
    @Test fun backupCarriesNoStorageGrantsCredentialsUserFilesOrTransferJournal() {
        val json = SetupJson.encode(sample())
        assertFalse(json.contains("persistedUriPermissions"))
        assertFalse(json.contains("accessToken"))
        assertFalse(json.contains("transfer-journal"))
        assertFalse(json.contains("fileContents"))
        assertEquals("dev.findroid.setup", JSONObject(json).getString("format"))
    }
    @Test fun semanticSearchRulesRoundTripWithoutStoringSnippetsAndLegacyRulesStayCanonical() {
        val original = sample()
        val legacyText = SetupJson.encode(original)
        assertFalse(legacyText.contains("excludedLocations"))
        assertFalse(NavigationJson.rules(original.snapshot.searches.single().rules).has("date"))
        assertEquals(original, SetupJson.decode(legacyText))
        val old = original.snapshot.searches.single().root
        val rules = original.snapshot.searches.single().rules.copy(date = SearchDate.THIS_WEEK, size = SearchSize.OVER_1_GB,
            excludedLocations = listOf(SearchExclusion(old), SearchExclusion("$old/Keep.txt", false)))
        val updated = original.copy(snapshot = original.snapshot.copy(searches = original.snapshot.searches.map { it.copy(rules = rules) }))
        val text = SetupJson.encode(updated)
        assertEquals(updated, SetupJson.decode(text))
        assertFalse(text.contains("snippet"))
        val json = NavigationJson.rules(SearchRules(days = 1, minimumBytes = 1025))
        assertEquals(SearchRules(days = 1, minimumBytes = 1025), NavigationJson.rules(json))
        assertEquals(SearchDate.ANY, NavigationJson.rules(json).date)
    }

    @Test fun malformedAndFutureSearchOptionsCannotSilentlyChangeImportedCatalogs() {
        reject { it.getJSONObject("setup").getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("date", "LAST_ERA") }
        reject { it.getJSONObject("setup").getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("size", "HUGE") }
        reject { it.getJSONObject("setup").getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("excludedLocations", JSONArray().put(JSONObject().put("path", "/valid").put("folder", "true"))) }
        reject { it.getJSONObject("setup").getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("excludedLocations", JSONArray().put(JSONObject().put("path", "@recent").put("folder", true))) }
        reject { it.getJSONObject("setup").getJSONArray("searches").getJSONObject(0).getJSONObject("rules").put("excludedLocations", JSONArray(List(101) { index -> JSONObject().put("path", "/item-$index").put("folder", true) })) }
    }

}
