# Recent searches

Findroid can remember the queries and filters you finish using, so you can run a search again without creating a Smart Folder. Open **More → Recent searches…**, or find “Recent searches” in the action launcher.

## Finish a search

Typing still updates the results immediately. Findroid adds the finished search when you:

- Press the keyboard’s **Search** action or **Enter**.
- Apply search filters.
- Leave the edited search field, open a dialog, switch tabs, navigate away, or put the app in the background.

It does not add a history entry for every keystroke. Opening an old tab does not by itself create a new entry. Empty searches are omitted.

The list keeps up to 20 searches, most recently used first. Repeating the same query and filters in the same location moves the existing entry to the top. The same rules in different locations remain separate.

## Use a suggestion while typing

The search field offers up to three matching recent searches. A suggestion restores the complete query and filters in the current location. The prefix you typed to find that suggestion is discarded.

Tap a suggestion, or use **↓ / ↑** to highlight one and press **Enter**. Pressing Enter without highlighting a suggestion finishes the literal query you typed. Choose **Recent searches…** in the popup to review the full list. Finishing a query or choosing a suggestion returns keyboard focus to the file list, so **F4** can open the address bar immediately.

## Choose where to search

Use **Filter recent searches** to find a query by its name, filters, or original location. Each history entry offers these named actions:

| Action | Result |
|---|---|
| **Search here** | Copies its query and filters into the current folder or library. |
| **Search original location** | Checks the saved location, then opens it with the saved query and filters. |
| **Show filters** | Shows the criteria, original location, excluded folder names, and exact excluded files/folders. |
| **Remove** | Removes that entry from history immediately. |

If the original folder is unavailable, Findroid leaves the current tab and search in place. You can reconnect it through **Storage locations…**, or choose **Search here**. A slow provider cannot replace a tab, query, or active pane that you changed while it was checking access.

Reusing a search while viewing a Smart Folder creates a plain search at that Smart Folder’s real root. It keeps the shared Smart Folder’s saved rules intact.

Exact exclusions keep their saved file/folder references when you choose Search here. They are not translated to similarly named folders at the new location. Folder-name exclusions still apply by name. Relative date choices such as Today or This week are evaluated when the search runs again.

## Control history on this tablet

**Remember searches** is on by default. Turn it off to pause new entries; existing entries can still be reviewed, reused, or removed. **Clear history** immediately empties the list and keeps the recording preference you chose. Clearing, removing, or pausing also invalidates drafts in other panes, so they cannot bring back an entry you just cleared. Deliberately submitting the query again can add it back.

The list and its recording preference persist across restarts and are shared by Findroid’s panes and windows on the same tablet. They are excluded from setup export/import and Android’s automatic backups. The list stores query/filter text, location references, an identity, and a last-used time. It stores no result entries or content snippets.

History is bounded to 256 KiB overall and 64 KiB per entry. Large sets of rules can reduce the number of retained entries; a search that exceeds the per-entry limit can still be saved as a Smart Folder. If history cannot be read or its format is newer than this app understands, Findroid retains the original data and pauses recording until you explicitly clear it.

For search rules and result explanations, see [Search criteria](SEARCH-CRITERIA.md). For reusable named rules, use **More → Save search as Smart Folder…**.


## Reuse a search with several folders

From 0.38.1, history preserves every [chosen search folder](SEARCH-LOCATIONS.md). **Search original location** restores the full list and can proceed if at least one folder is available; Result details identifies unavailable roots. If all are unavailable, the current tab stays in place.

**Search here** and typing suggestions clear that saved location list and use the current folder or library with the copied filters. Suggestion summaries describe this current-folder action. Literal exclusions keep their original references. Reusing a Smart Folder search still creates independent rules.

From 0.39.0, history also preserves [nested rule groups](SEARCH-RULE-GROUPS.md). Typing suggestions keep a bounded description of the filters applied here; full history shows the complete group. Draft edits do not alter an older entry.

From 0.40.0, history also preserves the [Live folders or Local index source choice](LOCAL-SEARCH-INDEX.md). Restoring a search never registers index roots or starts a refresh. Index registrations and cache rows stay on the current installation.
