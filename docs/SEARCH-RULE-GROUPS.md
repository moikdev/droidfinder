# Search rule groups

Use **More → Search filters… → Add rule groups…** to combine alternatives in a search. Each group has a named matching mode:

| Mode | A file is included when… | Example |
|---|---|---|
| **All** | Every rule in the group matches. | A text document tagged Review. |
| **Any** | At least one rule in the group matches. | A PDF or a text document. |
| **None** | No rule in the group matches. | A name containing neither Draft nor Backup. |

For example, to find PDFs or text documents tagged either Review or Approved, make an **All** group with two **Any** groups inside it:

```text
All
├─ Any
│  ├─ Kind is PDF document
│  └─ Kind is Text document
└─ Any
   ├─ Tag is Review
   └─ Tag is Approved
```

Choose **Add group**, select **Any**, and use **Add rule…** for each kind. Return with **Back to parent group**, then add the second group and its tag rules. The complete summary shows how the groups fit together.

A rule can check a name, kind, exact tag name, modified date, or size. Name matching ignores case. Tag matching uses the exact tag name. Calendar choices use the tablet's local date and first day of the week; past-day choices use rolling 24-hour periods. Size limits are inclusive and use **1 KB = 1,024 bytes**. Exact byte values imported from a saved setup retain their precision when edited.

**Apply groups** updates the Search criteria draft. **Apply filters** runs the search and, when editing a Smart Folder, saves its updated criteria. Canceling a condition, group editor, or Search criteria leaves the corresponding parent unchanged. The app blocks Apply or Clear if the active tab, location, or original search changes while the editor is open.

## Scope and other filters

Simple criteria and the entire group must match together. The single text-content query runs after the metadata rules pass and searches supported UTF-8 text documents. File contents are read at most once per admitted document in a scan, even when several alternatives match.

[Chosen folders](SEARCH-LOCATIONS.md), subfolder depth, excluded folder names, and exact file/folder exclusions apply to the whole search. An Any group cannot bypass an exclusion or add a location. Read-only connected folders can participate in the same search.

Size rules apply to files. Some providers report an unknown size. An Any group can still match through a known alternative, such as the filename; None cannot include a file simply because its size could not be checked. If the final decision still depends on that unknown size, the file is left out and Result details explains the incomplete check.

## Saved searches and limits

Tabs, Smart Folders, recent searches, workspaces, and reviewed setup backups preserve the groups. Editing a draft does not change an older history entry. Tag renames and reviewed setup tag remapping update nested tag rules. Assigning or removing a referenced tag refreshes search membership.

Groups allow up to **four group levels**, **48 total rules and groups**, and **8 KiB of combined UTF-8 rule values**. Every group must contain at least one rule before it can be applied. A single name value can contain up to 512 UTF-16 characters; kind and tag values can contain up to 128. The editor keeps empty groups as drafts and shows why they cannot yet be applied.

Older searches without groups keep their existing behavior. Unsupported or damaged group data is rejected instead of being treated as an unrestricted search. If a Smart Folder catalog cannot be fully read, the app preserves its stored data and blocks edits that would overwrite it. Use a compatible Findroid version or restore a reviewed setup backup to recover the catalog. Recent history also preserves unreadable stored data until an explicit Clear.

The editor adds no permanent toolbar icons. Its named actions live inside Search criteria, with fixed Cancel/Apply controls and a scrolling form for tablet use.
