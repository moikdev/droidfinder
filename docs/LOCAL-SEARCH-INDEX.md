# Optional local search index

Available in 0.40.0. Live folders remain the default search source.

The local index saves a snapshot of filenames, paths, types, sizes and modification dates for folders you choose. Search reads saved entries and checks matching files again, which avoids repeating the full folder traversal. It does not guarantee a shorter complete search. It stores no document contents or tags, sends nothing to a server, and runs no scheduled background indexing.

## Choose a folder explicitly

1. Open **More → Search index…**, or find **Search index…** in the action launcher or Advanced preferences.
2. Choose **Add local folder…**. Browse a folder on the tablet and confirm **Index this folder**.
3. Wait for **Snapshot available**. The dialog shows the indexed folder, entry count, scan start and finish, current folder availability, and measured cache bytes.
4. Open **Search criteria** and choose **Local index** when you want to search saved snapshots. **Live folders** performs the existing folder search.

Choosing Local index, opening a Smart Folder, restoring tabs, or importing a setup does not register any folder or start an index refresh. A search that lacks snapshot coverage explains which folder is unavailable and offers **Use live folders**. Choose Live folders for document providers, tags, collections and Trash. An indexed request for an unsupported source explains the limitation; it never silently falls back to scanning that source.

## Understand the date

A snapshot is a record of a completed scan, not a guarantee that every file is still unchanged. Search results and **Result details** identify the source, snapshot owner folder, scan dates, refresh state and entries checked. The app checks matching files again using their current metadata, tags and text criteria before showing them as actionable results.

Those checks remove outdated matches. They cannot discover a new or renamed path. They can also miss a file rejected by its old size or date, even if it now matches the filters. Choose **Refresh snapshot** to update the saved entries, or **Use live folders** to repeat the search against folders. Observed changes appear as a refresh hint. An absence of a hint does not mean every recursive or external change was observed.

Chosen roots, folder depth, hidden-file settings, excluded folder names, exact exclusions, nested rule groups, dates, sizes and text criteria still apply. Overlapping roots share candidate revalidation and content reads. Unavailable roots remain visible in Result details.

## Pause, recover or remove

A refresh stages a new generation separately. The previous complete snapshot remains searchable until the new scan finishes. A paused, interrupted, failed or over-limit scan keeps that previous generation. **Resume (restart scan)** starts a new scan; it does not promise to continue from the exact stopped position.

**Remove folder…** removes that folder's registration and snapshots. **Delete index cache…** stops refresh work and removes all registrations and cache files. Both explain the consequence before confirming. Original documents, Favorites, tags, saved searches and recent search history are kept. Saved indexed searches may need Live folders or a newly built snapshot afterward.

Future, damaged or unsupported cache data is kept until explicit cache deletion. The app does not automatically erase it. Index data lives in Android's private no-backup storage and is excluded from setup exports. A setup carries a saved search's source choice, never indexed roots or cached entries.

## Resource limits

The initial implementation allows 10 local roots, 100,000 entries per root and 250,000 stored rows across active, staged and leased snapshots. Scans also bound folder count, depth, path length, saved path bytes and the main SQLite database. Hitting a limit leaves the previous snapshot intact and explains how to use a smaller folder, free storage or remove an unused cache. Search result and scan budgets still apply independently.

Filesystem and database work run off the UI thread. Symbolic links, internal working files and special files are excluded from traversal. Replaced roots and escaping cached paths are rejected; content searches check the actual opened local file descriptor before reading.

## Measured library behavior

A warm metadata-only **debug instrumentation** fixture on each emulator contains **6,000 files in 200 folders**, saves **6,200 entries**, and returns **1,000 identical matches**. The first usable result and complete time are separate measurements; sampled process PSS is retained in the full JSON. These measurements include instrumentation/memory-sampling overhead and do not establish real-tablet startup, battery life or a general speedup.

| API | Snapshot build | Cache bytes | Live first / complete | Indexed first / complete | Pause |
|---|---:|---:|---:|---:|---:|
| 30 | 3.53 s | 4,898,816 | 155.2 / 536.7 ms | 19.7 / 1295.8 ms | 0.93 ms |
| 36 | 3.65 s | 4,943,872 | 209.2 / 1507.8 ms | 41.9 / 1908.3 ms | 9.04 ms |


A complete indexed search can be slower despite earlier initial matches. Choose the source that fits your work; Live folders remain the default. The larger physical-tablet/library performance proposal remains open.

## Validation

489 JVM tests and 353 native cases per API30/API36 tablet pass, with 38 fresh-process batches per API and zero failures/errors/skips. The exact optimized previous-public upgrade, six real window/font combinations per API, 50,000-file process-death/pause/remove/delete checks and actual public OTA installation pass on both tablets. Actual screenshots are inspected. [Detailed validation](../VALIDATION.md).

The immutable validation archive is being finalized. Proposals 19 and 26 remain pending its independent public-artifact check.
