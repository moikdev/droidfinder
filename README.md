# droidfinder

A native Android tablet file explorer inspired by macOS Finder. Written in Kotlin and Jetpack Compose, with real local file operations. This is an independent implementation, not Apple code or a macOS compatibility layer.

## Run

Requires JDK 17+, Android SDK 36, and an Android 11+ device. The layout targets tablets, ideally 900 dp or wider, and supports landscape, portrait, and resizable windows. A dedicated phone layout is deferred.

```sh
./gradlew assemblePreview
adb install -r app/build/outputs/apk/preview/app-preview.apk
```

Open DroidFinder and choose **Connect tablet storage**, then enable Android's **Allow access to manage all files** setting. Alternatively, create folders in the private Workspace without granting storage access. The app starts empty; it never populates your storage with sample files.

The installable preview is optimized and signed with Android's development key, for local testing. `./gradlew assembleDebug` produces a debuggable APK at `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew assembleRelease` produces an optimized, unsigned APK; configure your own signing credentials for distribution.

## Finder workflows implemented

| Area | Behavior |
| --- | --- |
| Navigation | Sidebar, favorites, shared tablet storage, mounted SD/USB volumes, breadcrumb path bar, editable Go to Folder, back/forward/up, independent tab history |
| Views | Icons, sortable list, browsing columns, gallery/filmstrip, optional preview pane |
| Selection | Tap to select, double-tap to open, long-press for multiple selection, Ctrl/Meta-click to toggle, Shift-click for ranges, select all, right-click actions in list view |
| Organizing | Create folder, rename, copy/cut/paste across folders or tabs, duplicate, six color tags, favorite folders |
| File actions | Open with Android apps, share files with temporary URI grants, Get Info, copy full path |
| Quick Look | Images, first page of PDFs, bounded UTF-8 text previews, folder item counts; previous/next controls |
| Search | Case-insensitive file-name search including subfolders, cancellation/debouncing, Recents, tag filters, hidden-file toggle |
| Trash | Recoverable deletion, restore to original location, Undo last move to Trash, explicit confirmation before permanent deletion or emptying |
| Archives | ZIP compression and extraction, traversal/duplicate-entry checks, bounded extraction |
| Persistence | Tabs and active tab, view/sort options, hidden-file/preview preferences, favorites, tags, Trash recovery index |
| Changes outside the app | Directory observation and refresh when the app resumes |

Keyboard: Ctrl/Meta F search, L go to folder, T new tab, W close tab, N new folder, A select all, C/X/V copy/cut/paste, D duplicate, I info, period hidden files, Z undo last Trash operation. Alt Left/Right/Up navigates. Up/Down selects files; Shift extends selection. Space opens Quick Look; Enter opens selection; F2 renames; Delete moves to Trash. Standard text-field shortcuts remain available while editing.

## Storage and safety

- This is a local file manager and declares no Internet permission. Android's all-files access is user-controlled; other apps' private directories remain inaccessible. See [Android's all-files access documentation](https://developer.android.com/training/data-storage/manage-all-files). Google Play distribution requires the corresponding file-manager permission review.
- Workspace and Trash are stored in this app's private data. **Uninstalling the app or clearing its data deletes Workspace, Trash, tags, and favorites.** Restore important files to shared storage first. Trash consumes internal storage, including when the original was on USB/SD.
- Copies, restores, moves, and extracted archives choose an unused destination name rather than overwrite. Copies use hidden staging paths and only expose a completed result. Cross-volume moves copy before removing the source. If source removal fails, both copies are kept and the error says so.
- Batch operations stop at the first error and report completed counts where available. Completed work is retained; there is no automatic rollback of an entire batch. Keep the app open during operations; background process-death recovery is not implemented. Incomplete `.droidfinder-*` staging items can remain after process termination and are hidden from normal listings.
- Symlinks are not copied or compressed. Permanent Trash deletion does not follow symlinks. ZIP extraction rejects paths outside its destination, limits archives to 10,000 entries / 2 GiB uncompressed, and removes staging output on failure.
- Search/Recents examine up to 50,000 entries; listings show at most 5,000 results and display “results limited” when capped. Recents shows the 200 newest modified files in the accessible primary storage (or Workspace without permission). Restricted `Android` subtrees are skipped during recursive search. Search matches names, not file contents.
- Tags are app-local metadata with one color per item, not macOS extended attributes. App-initiated rename/move/copy keeps tags in sync; changes made by other apps do not update stored tag paths.

## Remaining parity gaps

Cloud/document-provider connections, SMB/NFS/SFTP, AirDrop, Spotlight content indexing and Smart Folders, drag-and-drop transfers, bulk rename, Finder-style grouping, resizable columns, arbitrary/custom multiple tags, aliases/symlinks, full operation undo, persistent background transfers, full-document PDF navigation, in-app audio/video playback, image EXIF orientation, localization, and a phone layout are not implemented. Other apps can open supported files through Android's app picker. Physical removable-storage hardware still needs device testing.

## Verify

```sh
./gradlew testDebugUnitTest lintDebug
# With a running Android tablet emulator/device:
./gradlew connectedDebugAndroidTest
```

18 disk-operation tests cover collisions, nested copies, moves, Trash persistence/restoration, symlink safety, archive round-trips, and malicious ZIP paths. Seven Compose tests exercise create/rename/copy/Trash/restore, all four views and text Quick Look, recursive search, multiple selection/tags, independent tab history, Trash undo, and keyboard shortcuts on an API 36 tablet emulator.

## Code map

- `FileEngine.kt`: file operations and persistent Trash journal; tested on JVM temporary directories.
- `FinderModel.kt`: navigation, selection, preferences, IO scheduling, clipboard, file observation, and search.
- `FinderApp.kt`: tablet shell, sidebar, toolbar, tabs, actions, dialogs, and keyboard shortcuts.
- `FinderViews.kt`: file views, gestures, preview pane, breadcrumb/status bars, and onboarding.
- `FileVisuals.kt`: folder/document graphics, bounded previews, and file metadata.
- `MainActivity.kt`: Android lifecycle, storage settings, FileProvider, opening, and sharing.
