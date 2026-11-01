# Finder parity worklist

Latest pass: [Storage analysis and checksums, 0.20.0](docs/ANALYSIS.md). Earlier: [Android picker and incoming shares, 0.19.0](docs/ANDROID-INTEGRATION.md). Earlier: [Storage and recovery, 0.18.0](docs/STORAGE.md). Earlier: [Image Peek, 0.17.0](docs/IMAGE-PEEK.md). Earlier: [Archive browser, 0.16.0](docs/ARCHIVES.md). Earlier: [Structured Peek, 0.15.0](docs/STRUCTURED-PEEK.md). Earlier: [Media Peek, 0.14.0](docs/MEDIA-PEEK.md). Earlier: [PDF Peek, 0.13.0](docs/PDF-PEEK.md). Earlier: [Progressive folders and search, 0.12.0](docs/LOADING.md). Earlier: [Dragging, spring loading and precise resizing, 0.11.0](docs/DRAGGING.md). Earlier: [Folder views and detail columns, 0.10.0](docs/FOLDER-VIEWS.md). Earlier: [Navigation, saved layouts and shared thumbnails, 0.9.0](docs/NAVIGATION.md). Earlier: [Workspaces, tabs, icons and desktop input, 0.8.0](docs/WORKSPACES.md). Earlier: [Compact transfers, planning and tablet density, 0.7.0](docs/TRANSFER-PLANNING.md). Earlier: [Folder merging and member recovery, 0.6.0](docs/FOLDER-MERGE.md). Earlier: [Transfer and conflict foundation, 0.5.0](docs/TRANSFER-CENTER.md). The [full improvement roadmap](docs/ROADMAP.md) remains in progress. Earlier: [Desktop workflows, 0.4.0](docs/DESKTOP-WORKFLOWS.md), [Refined Finder and Peek, 0.3.0](docs/REFINED-FINDER.md).

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
- [x] Action/destination feedback, hover opening for folders/tabs with cancellation restoration, proportional viewport edge scrolling, and fractional-dp resizing (0.11.0).
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

## Tablet controls and planning pass

- [x] Content-sized transfer utility window with expandable paths/results and bounded history.
- [x] Persisted Comfortable/Compact density and width-aware toolbar/list columns.
- [x] Metadata-only transfer estimates, advisory capacity checks and phase-aware progress.
- [x] Pause-aware fresh rate/ETA, persistent estimates and schema upgrade coverage.

## Dual-pane pass

- [x] Independent pane tabs/history/search/selection and actual-width file layouts.
- [x] One shared operation session, clipboard, mutation guard, queue, journal and Undo/Redo.
- [x] Active-pane toolbar/sidebar/keyboard routing, resize/swap and narrow-window switching.
- [x] Copy/move to other pane with destination snapshots and source-aware native drag payloads.
- [x] Recursive read-only comparison, optional content verification and top-level copying through the queue.

## Desktop keyboard and visual pass

- [x] Adjacent new-tab button, reachable overflow control and active-tab reveal.
- [x] File-type symbols, folded-paper icons and MIME-based classification.
- [x] Windows-style tab/window, address/search, file operations and view shortcuts, with a searchable F1 guide.
- [x] Separate keyboard focus, additive ranges, navigation tree, Ctrl-wheel and explicit narrow-pane inspectors.
- [x] Independent Android document tasks sharing the operation session; Android retains global OS shortcuts.

## Navigation and preview reuse pass

- [x] Per-tab selection, independent keyboard focus, expanded folders and scoped viewport restoration.
- [x] Protected pinned tabs, searchable overview and touch reorder handles.
- [x] Named two-pane layouts with atomic storage, write-failure retry and mutation path remapping.
- [x] Shared byte-budget thumbnail cache, deduplicated work, consumer-aware cancellation and provider name/type refresh.

## Folder-view pass

- [x] Opt-in folder layouts, independent pane defaults and shared updates across windows.
- [x] Detail-column visibility, fixed/adaptive widths and native separator dragging.
- [x] Profile management, atomic persistence/retry, canonical local keys and scoped provider keys.
- [x] Workspace-default isolation and profile relocation/replacement recovery.

## Loading pass

- [x] Progressive primary listings/search, usable partial results, Stop/Restart and stale-request guards.
- [x] Explicit scan/result limits, scoped higher budgets, provider loading/error notices and incomplete empty states.
- [x] Streaming text-content matching beyond 1 MiB, cancellation and read-error reporting.
- [x] Saved-viewport interaction guards and stable status-row geometry during native dragging.

## PDF Peek pass

- [x] Page thumbnails, jump-to-page, internal document contents and saved page bookmarks/reading position.
- [x] Cancellable document text search, result snippets/highlights, next/previous matches and explicit partial results.
- [x] Selectable reading-text view, page-text copying, image-only/permission/error states and keyboard editing ownership.
- [x] Shared page/zoom controls, retained zoom, bounded panning and cancellable provider materialization.
- [ ] Direct text selection on rendered pages, OCR/password entry and tiled high-resolution PDF rendering.

## Verification

See [the current verification record](docs/STORAGE.md) for local test coverage, optimized APK checks and remaining device/provider coverage. [VALIDATION.md](VALIDATION.md) preserves earlier native integration results. Tests use the isolated `DroidFinder_Tablet_API_36` emulator. The broader improvement roadmap remains active; the platform boundaries below remain explicit.

## Platform equivalents and boundaries

- AirDrop is proprietary to Apple's platforms. Android's system share sheet and nearby-sharing targets are the equivalent.
- iCloud and other cloud/network storage are accessed through installed Android document providers. The app will not impersonate an Apple ID login or request a user's cloud password. Direct SMB/NFS/SFTP clients and Apple-device synchronization are separate integrations, not emulated macOS services.
- macOS aliases, resource forks, extended attributes, Finder extensions, Handoff, AppleScript, Quick Actions provided by macOS apps, disk formatting/eject privileges, and protected system/app directories do not have identical Android APIs. App shortcuts and Android intents cover portable workflows; Android's access rules remain in force.
- Phone layout remains out of scope at the user's request. A compact tablet/window layout is in scope.
- Physical USB/SD hardware and third-party cloud providers need real-device/account testing; automated provider tests must use isolated fixtures.

## Archive member pass

- [x] Read-only member Peek using native document/media readers and bounded cache snapshots.
- [x] Recursive archive path search, file/folder selection and selected extraction with preserved paths.
- [x] Cancellable size/CRC verification, explicit failure/incomplete results and persisted resource limits.
- [ ] Encrypted ZIP, additional formats and actual multi-GiB ZIP64 performance verification.

## Image Peek pass

- [x] Visible-region decoding, source-pixel zoom, bounded overview/detail output and navigator.
- [x] EXIF mirrored/rotated display, temporary view rotation and tablet pointer/touch/keyboard controls.
- [x] Source metadata, explicit location reveal/copy and activity recreation of the current view.
Image codec/device/performance coverage remains open in the roadmap; see the supported workflows and limits in docs/IMAGE-PEEK.md.

## Storage recovery pass

- [x] Local/provider/remembered volume status, reported free space and folder capabilities.
- [x] Saved read-only permission handling, expired-grant recovery and same-tree reconnect with tab/history retention.
- [x] Explicit different-folder choice, provider-app access and connection removal without discarding tabs.
- [x] Protected-directory guidance and canonical local path checks, including symlinks.
- [ ] Physical USB/SD disconnect, third-party provider/account and multiple Android-version coverage.

## Android integration pass

- [x] Workspace document provider with Android URI grants, saved IDs, browsing/search, reads/writes and file operations.
- [x] Save-to-DroidFinder share destination with reviewed names/selection, local/provider destinations, staged keep-both publication, cancellation, partial retry and session Undo.
- [x] Compact completion, recreation without automatic replay, and an opt-out for new Workspace provider requests.
- [ ] External cross-app drag/drop and physical device/third-party provider matrices.

See [supported behavior and limits](docs/ANDROID-INTEGRATION.md); other-app edits and already-open descriptors remain ordinary live file operations.

## Analysis and checksum pass

- [x] Folder/type logical-size totals, large/old/empty-file discovery and explicit incomplete/unknown states.
- [x] Full SHA-256 duplicate checks, including unknown provider sizes and hard-link exclusion, with configurable read limits.
- [x] Reviewed recoverable cleanup, changed-file/keep-one guards, partial outcomes and session Undo.
- [x] Complete-file checksums, expected-value comparison, copied digests and staged GNU-style checksum sidecars.
- [ ] Persistent indexes, multi-root duplicate discovery and measured hardware performance.

See [analysis behavior, limits and verification](docs/ANALYSIS.md).

## Housekeeping pass

- [x] Persisted Trash dates, bounded logical-size inventories and reviewed age-based selection.
- [x] Changed-item guards, explicit permanent-deletion confirmation and partial outcomes.
- [x] Known disposable-cache categories, native reader/writer leases, shared-file grace and reviewed cleanup.
- [x] Separate thumbnail-memory controls and retained panel/threshold preferences.

See [housekeeping behavior, limits and verification](docs/HOUSEKEEPING.md). Retention is reviewed; it does not automatically delete files.

## Reviewed rename pass

- [x] Complete before/after review, stable selection, row exclusion, filtering and ordering.
- [x] Literal/linear regex, name/extension rules, numbering, Unicode case/normalization and metadata templates.
- [x] Explicit EXIF reading, missing-field states and bounded cancellation.
- [x] Saved rename presets, activity recreation and source/destination rechecks.
- [x] Local/provider case-only and Unicode Undo/Redo, staged rollback and visible recovery routes.

See [supported behavior, limits and validation](docs/RENAMING.md). Other organization and recovery work remains in the full roadmap.
