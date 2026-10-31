# Desktop workflows — 0.4.0

This pass builds on Refined Finder and Space-to-Peek for Android tablets. It addresses cursor context menus, tabs, the view-selector design, and ZIP workflows. It is an independent Android implementation of common desktop file-explorer behavior.

## Controls and menus

The four-way view selector now uses equally sized icons, a softer neutral track, rounded inset selection, a subtle shadow, hover feedback and Ctrl 1–4 tooltips. Selection remains visible without a heavy blue block. The group exposes selected radio-button semantics. Toolbar tooltips are non-focusable so hovering does not intercept keyboard input.

Right-click a file or folder in any of the four views to open a menu at the cursor. Right-click within an existing selection to keep that selection. Right-click empty space for folder actions. Menus stay within the window and scroll when needed. Touch users can use the per-file action button or toolbar menu; long-press retains multiple selection. Shift F10 opens the keyboard equivalent, with arrows and Escape for navigation/dismissal.

Menus expose actions appropriate to the selection: open/Peek/Open With, Get Info, new tab, copy/cut/paste into a folder, duplicate, rename/batch rename, destinations, compression/extraction, tags, favorites, folder appearance, path copy, sharing and recoverable Trash. Background actions include new folder/text file, paste, selection, view/sort/group settings, refresh and folder information. Unsupported provider writes and concurrent file mutations are disabled.

## Tabs

- The tab strip remains visible with one tab and while items are selected. Each tab has a close button; the plus button always creates a tab.
- Drag tabs with a mouse to reorder. Middle-click a tab to close it; middle-click a folder to open a background tab.
- Right-click or long-press a tab for new/duplicate, move left/right, close, close others and reopen closed tab.
- Ctrl/Meta T and W create/close; Ctrl/Meta Shift T reopens; Ctrl Tab / Ctrl Shift Tab and Ctrl Page Up/Down cycle tabs.
- Each tab retains its own location, navigation history, query and search filters. These survive a process restart. Selection and expanded folders are kept when switching tabs in the current session.

Closing the final tab resets it to Workspace. History is bounded to 100 locations per tab; reopen retains the last 20 closed tabs in the current session. Previous saved tab locations migrate automatically.

## ZIP creation and browsing

Compress to ZIP opens a dialog with the archive name, destination and Balanced/Fast/Smallest/No compression choices. Originals are kept, collisions choose an unused name, and duplicate source basenames are preserved with distinct names. ZIP creation/extraction supports local and connected provider storage and participates in session Undo/Redo.

Open or double-click a ZIP to browse its directory without extracting. Nested folders, filenames, sizes and the uncompressed total are shown. Extract here, Extract to… and Open With are available. This is a read-only archive directory browser; it does not edit archives or preview individual members in place.

Inspection and extraction reject unsafe paths, duplicate/conflicting entries and corrupt archives. Limits are 10,000 ZIP entries, 20,000 inferred directories and 2 GiB of uncompressed data. Extraction uses staging and restores timestamps where storage supports them. Provider ZIP browsing uses the bounded 128 MiB preview cache; larger provider archives can still use extraction, which applies the same archive safety checks.

## Transfers and other desktop actions

A compact progress strip replaces the blocking transfer overlay. Browse folders, go back/forward, search, switch/create/close tabs, select files, copy/cut a new clipboard selection, use Get Info or Peek while a transfer runs. Cancel remains available. Completion preserves a newer selection and clipboard rather than clearing them when an older move finishes. File mutations remain serialized to protect operation history and destinations.

Existing desktop workflows remain available: four views, range/multiple/rectangle selection, drag-and-drop, batch rename, Undo/Redo, tags, Smart Folders, recoverable Trash, sharing and Space-to-Peek for supported photos, videos, audio, PDFs and text.

## Verification

Verified locally on 2026-10-31: **48 JVM tests and all 42 Android workflow tests passed**, with no failures or skips. The final Android suite passed together in one run. Android lint completed with zero errors (existing dependency/API/style warnings remain), and both optimized preview and unsigned release builds succeeded.

Verification commands:

```sh
./scripts/check.sh
ANDROID_SERIAL=emulator-5582 ./gradlew connectedDebugAndroidTest
```

The installable preview uses version code 4 and version name `0.4.0-preview`. Its APK signature verifies. The installable APK is 2552511 bytes, SHA-256 `f4231ab7c2ede309278e4182ea1c84fc3c8bb51a7fae4b283c4b6f11dc065b7c`. Manifest inspection confirms that neither the debug fixture provider nor an app-owned INTERNET permission is included.

The repeated context-menu test initially read native window focus from the test thread between Get Info dismissal and the next click. The harness now reads View state on the UI thread and drains window-dismiss callbacks before injecting mouse input. Failure captures remain available for future regressions.

The added JVM tests cover ZIP naming/collisions, duplicate basenames, compression levels, timestamp restoration, implicit folders, corrupt/unsafe paths and empty ZIPs. Added Android tests exercise actual secondary/middle mouse buttons, tab dragging and shortcuts, selection preservation, named compression and archive browsing/extraction, independent/persisted tab search/history, provider parent-cache merging, and browsing/copying during a gated provider move.

Tests use the dedicated API 36 `DroidFinder_Tablet_API_36` emulator on port 5582. Test documents and media are synthetic. The debug provider is excluded from preview/release builds. Real USB/SD devices, third-party provider accounts and codec support still require device testing. Hosted GitHub Actions runners remain blocked by the account's billing/spending-limit issue; no hosted CI success is claimed.

Native visual checks used the optimized development-signed APK: 1280 × 800 dp landscape, 800 × 1280 dp portrait and a 640 × 800 dp compact window, plus light/dark appearance, file actions, compression and archive contents. The final preview installed over the previous development build, cold-launched successfully and produced no AndroidRuntime errors during these checks. Display overrides were restored.

[Download 0.4.0-preview](https://github.com/moikdev/droidfinder/releases/tag/v0.4.0-preview). Release assets include the installable preview, unsigned release APK and SHA256SUMS.

## Boundaries

Android storage grants and protected app directories still apply. Apple-only services, macOS aliases/extended attributes, OS disk administration and direct SMB/NFS/SFTP are outside this preview. ZIP is the supported archive format; encrypted ZIP, RAR, 7z and TAR are not implemented. Phone-specific layout remains deferred. See [the full parity boundaries](../PARITY.md) and [storage/recovery details](../README.md#storage-privacy-and-recovery).

## Screenshots

Actual app screens with synthetic QA files:

- [Tablet and tabs](screenshots/desktop-landscape.png)
- [File context menu](screenshots/desktop-context.png)
- [Compression options](screenshots/desktop-compress.png)
- [Archive browser](screenshots/desktop-archive.png)
- [Compact window](screenshots/desktop-compact.png)
- [Portrait](screenshots/desktop-portrait.png)
- [Dark appearance](screenshots/desktop-dark.png)
