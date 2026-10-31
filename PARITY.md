# Finder parity worklist

Latest pass: [Folder merging and member recovery, 0.6.0](docs/FOLDER-MERGE.md). Earlier: [Transfer and conflict foundation, 0.5.0](docs/TRANSFER-CENTER.md). The [full improvement roadmap](docs/ROADMAP.md) remains in progress. Earlier: [Desktop workflows, 0.4.0](docs/DESKTOP-WORKFLOWS.md), [Refined Finder and Peek, 0.3.0](docs/REFINED-FINDER.md).

The target is Finder's general file-explorer workflows on Android tablets. This is an independent app, not a macOS runtime. Each implementation pass must build, exercise the affected workflows, and update this file before the work is considered complete.

Reference behaviors: [Finder organization](https://support.apple.com/guide/mac-help/organize-files-in-folders-mh26885/mac), [batch rename](https://support.apple.com/guide/mac-help/rename-files-folders-and-disks-mchlp1144/mac), [tags](https://support.apple.com/guide/mac-help/tag-files-and-folders-mchlp15236/mac), and [Smart Folders](https://support.apple.com/guide/mac-help/create-or-change-a-smart-folder-mchlp2804/mac).

## Baseline

- [x] Native Android tablet shell, sidebar, back/forward/up, breadcrumbs, tabs.
- [x] Icons, list, columns, gallery, preview pane, basic Quick Look.
- [x] Local copy/move, rename, duplicate, recoverable Trash, ZIP, sharing.
- [x] Recursive name search, Recents, hidden files, favorites, color tags.
- [x] Development-signed installable APK and optimized release build.

## Improvement loop

- [x] Safe multi-step Undo/Redo for file operations, with changed-file protection.
- [x] Batch rename: replace, add text, numbered format, collision preview.
- [x] New Folder with Selection, destination picker, reveal original folder.
- [x] Custom tags, multiple tags per item, tag management, persistent metadata.
- [x] Grouping by kind/date/tag, icon zoom, resizable columns and preview pane.
- [x] Saved Smart Folders with name/kind/date/size/tag/content criteria.
- [x] Complete PDF navigation/zoom, oriented images, audio/video Quick Look.
- [x] Mouse context menus in every view, selection ranges, type-to-select.
- [x] Native drag-and-drop onto folders, tabs/sidebar, and URI import/export with Android apps. Internal native dragging verified; external app compatibility remains target-dependent.
- [x] Android document-provider locations, persistable grants, read-only capabilities.
- [x] Cross-provider/local copy/move, create/rename, provider previews/sharing.
- [x] Cancellable transfers with byte/item progress, recovery journal and lifecycle handling.
- [x] Favorites/sidebar management, aliases as portable app shortcuts, folder appearance.
- [x] Preference controls, folder information, display/extension options, dark theme.
- [x] Expanded safety and workflow tests, tablet landscape/portrait/small-window checks.
- [x] GitHub source, build instructions, [installable preview release](https://github.com/moikdev/droidfinder/releases/tag/v0.4.0-preview) and final verification.

## Desktop workflow pass

- [x] Cursor-positioned file/folder/background menus across all four views; touch action buttons and keyboard equivalents.
- [x] Permanent tabs, mouse reordering, middle-click, duplicate/close-others/reopen, persisted per-tab history/search.
- [x] Consistent view-selector icons, inset selected surface, hover states and non-interactive tooltips.
- [x] Named ZIP archives, compression levels, destination selection, safe read-only archive browsing.
- [x] Keep browsing, switching tabs, selecting, copying paths/items and previewing during a transfer.

## Transfer and merge pass

- [x] Durable serialized copy/move queue, pause/resume/cancel, per-item results and notification controls.
- [x] Conflict comparison, rename/skip/keep-both/recoverable replacement and apply to remaining choices.
- [x] Folder merge plans, nested conflicts, local/provider recovery and complete/partial move Undo/Redo.
- [x] Transactional SQLite migration/checkpoints, per-member retry destinations and explicit ambiguity review.
- [x] Cancellation-aware merge verification and source/destination content checks before cleanup.

## Verification

See [the current verification record](docs/FOLDER-MERGE.md) for local test coverage, optimized APK checks and remaining device/provider coverage. [VALIDATION.md](VALIDATION.md) preserves earlier native integration results. Tests use the isolated `DroidFinder_Tablet_API_36` emulator. The broader improvement roadmap remains active; the platform boundaries below remain explicit.

## Platform equivalents and boundaries

- AirDrop is proprietary to Apple's platforms. Android's system share sheet and nearby-sharing targets are the equivalent.
- iCloud and other cloud/network storage are accessed through installed Android document providers. The app will not impersonate an Apple ID login or request a user's cloud password. Direct SMB/NFS/SFTP clients and Apple-device synchronization are separate integrations, not emulated macOS services.
- macOS aliases, resource forks, extended attributes, Finder extensions, Handoff, AppleScript, Quick Actions provided by macOS apps, disk formatting/eject privileges, and protected system/app directories do not have identical Android APIs. App shortcuts and Android intents cover portable workflows; Android's access rules remain in force.
- Phone layout remains out of scope at the user's request. A compact tablet/window layout is in scope.
- Physical USB/SD hardware and third-party cloud providers need real-device/account testing; automated provider tests must use isolated fixtures.
