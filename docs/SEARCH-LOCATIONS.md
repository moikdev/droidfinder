# Search across chosen folders

Development increment for 0.38. The published preview remains 0.37 while native and optimized-build acceptance runs.

Open **More → Search locations…**, or find “Search locations” in the action launcher. Choose up to ten folders from Workspace, tablet storage, connected providers, tabs, or Favorites. Read-only folders can be included.

Use **Add folder…** to open the folder chooser. **Remove** edits the draft list. **Search** applies the complete list; **Cancel** keeps the current scope. At least one folder must remain. The first folder names the search and is its normal browsing anchor.

Search locations are folder references. Choosing one does not grant new Android storage access. Connect a provider through **Storage locations…** before adding its folders. Tags, collections, Recents and Trash keep their own membership rules; open an ordinary folder or Smart Folder before choosing a combined folder scope.

## Keep useful results when storage is unavailable

Findroid checks each chosen folder independently. A missing first folder does not prevent later folders from returning results. Available matches remain visible if another folder is disconnected, read-only, still loading, or fails partway through scanning.

Open **Result details** to review every chosen location:

| Status | Meaning |
|---|---|
| Complete | The chosen folder's requested scan finished. |
| Unavailable | The folder could not be listed. Other chosen folders can still contribute results. |
| Incomplete | Some descendants or file checks failed, or the scan stopped or reached a limit. |
| Provider is still loading | The provider says more results may be prepared later. Refresh to check again. |
| Not scanned | This folder has not been reached yet. A stopped or limited search does not claim it is complete. |
| Excluded by this search | An exact exclusion intentionally removes this chosen location. |

Use **Search locations…** in Result details to change the list. Reconnecting the same provider refreshes affected searches while keeping their chosen scope. Replacing a connection with a different provider still requires the existing reference review; Findroid does not guess opaque child identifiers.

## Apply filters consistently

Name, type, tags, date, size and content filters apply to every chosen folder. **Include subfolders** applies independently to each one. **Chosen folders only** checks their immediate children. Exact exclusions retain their literal references; folder-name exclusions apply by name.

Overlapping folders or different grants to the same document return one result per document. Traversal still consumes the shared scan budget, so overlapping roots can take more work. **Stop**, **Restart**, **Refresh** and **Load more** retain their existing bounded-search behavior.

This is a live scan. Folder watches and provider notifications can refresh chosen roots, but do not form a recursive background index. Use Refresh after changes that a provider or nested folder does not announce.

## Save and reuse the scope

Each tab keeps its own chosen scope across restart. Normal navigation to another folder clears the override. Saving a Smart Folder stores all chosen locations; deliberately editing its scope updates that saved search, including its first root.

Recent searches keep the complete scope with the filters:

- **Search here**, including a typing suggestion, clears the saved location list and uses the current folder or library.
- **Search original location** restores the chosen list. It can proceed when at least one saved root is available, with unavailable roots disclosed in Result details. If none is available, the current tab stays in place.
- Reusing history from a Smart Folder creates an independent plain search and leaves the saved Smart Folder intact.

Setup backups inventory chosen roots in Smart Folders, saved/open tabs and scan-budget scopes, so the existing reviewed mapping includes them. Workspace recovery can replace a missing secondary search root without changing the working anchor. Repairing a Smart Folder inside a saved workspace creates independent rules for that restored tab and preserves the shared saved search.

Saved criteria and backups contain references, not result entries, snippets or file bytes. Recent history remains outside setup backups. See [Search criteria](SEARCH-CRITERIA.md), [Recent searches](RECENT-SEARCHES.md), and [Setup backup](SETUP-BACKUP.md).
