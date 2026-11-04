# Finder parity worklist

Current public preview: [Text selection on PDF pages, 0.49.0](docs/PDF-PAGE-SELECTION.md), with local, actual public OTA and independent immutable-archive acceptance complete. Earlier: [Detailed transfer-space review, 0.48.0](docs/TRANSFER-SPACE-REVIEW.md). Earlier: [Recovery inbox, 0.47.0](docs/RECOVERY-INBOX.md). Earlier: [Reviewed folder synchronization, 0.46.0](docs/REVIEWED-FOLDER-SYNC.md). Earlier: [Undo after reopening, 0.45.0](docs/PERSISTENT-UNDO.md). Earlier: [Review and resume saved local copies, 0.44.0](docs/PARTIAL-COPY-RESUME.md). Earlier: [Verify important copies before publication, 0.43.0](docs/COPY-VERIFICATION.md). Earlier: [Recent transfer speed, phase estimates and storage waits, 0.42.0](docs/TRANSFER-PROGRESS.md). Earlier: [Per-document storage action explanations, 0.41.0](docs/STORAGE-ACTIONS.md). Earlier: [Optional local search snapshots, 0.40.0](docs/LOCAL-SEARCH-INDEX.md). Earlier: [Nested All/Any/None search rules, 0.39.0](docs/SEARCH-RULE-GROUPS.md). Earlier: [Search across chosen folders, 0.38.1](docs/SEARCH-LOCATIONS.md). Earlier: [Recent searches and suggestions, 0.37.0](docs/RECENT-SEARCHES.md). Earlier: [Search explanations, exact exclusions and natural criteria, 0.36.0](docs/SEARCH-CRITERIA.md). Earlier: [Clear tablet commands and guarded Recent actions, 0.35.0](docs/COMMANDS-AND-UNDO.md). Earlier: [Saved-workspace recovery, 0.34.1](docs/WORKSPACE-RECOVERY.md). Earlier: [Reviewed setup backup, restore and portable references, 0.33.0](docs/SETUP-BACKUP.md). Earlier: [Sidebar management, clearer browsing and action/folder navigation, 0.32.0](docs/SIDEBAR-AND-NAVIGATION.md). Earlier: [Drag folders into Favorites, 0.31.0](docs/DRAGGING.md#pin-folders-in-favorites). Earlier: [Cross-app file dragging, 0.30.0](docs/DRAGGING.md) and [in-app updates](docs/APP-UPDATES.md). Earlier: [Safety and compatibility audit, 0.28.0](docs/PRODUCTION-AUDIT.md). Earlier: [Natural filename sorting, 0.27.0](docs/NATURAL-SORTING.md). Earlier: [Saved locations and reference repair, 0.24.0](docs/REFERENCES.md). Earlier: [Collections and reviewed tags, 0.23.0](docs/ORGANIZATION.md). Earlier: [Reviewed rename, 0.22.0](docs/RENAMING.md). Earlier: [Housekeeping, 0.21.0](docs/HOUSEKEEPING.md). Earlier: [Storage analysis and checksums, 0.20.0](docs/ANALYSIS.md). Earlier: [Android picker and incoming shares, 0.19.0](docs/ANDROID-INTEGRATION.md). Earlier: [Storage and recovery, 0.18.0](docs/STORAGE.md). Earlier: [Image Peek, 0.17.0](docs/IMAGE-PEEK.md). Earlier: [Archive browser, 0.16.0](docs/ARCHIVES.md). Earlier: [Structured Peek, 0.15.0](docs/STRUCTURED-PEEK.md). Earlier: [Media Peek, 0.14.0](docs/MEDIA-PEEK.md). Earlier: [PDF Peek, 0.13.0](docs/PDF-PEEK.md). Earlier: [Progressive folders and search, 0.12.0](docs/LOADING.md). Earlier: [Dragging, spring loading and precise resizing, 0.11.0](docs/DRAGGING.md). Earlier: [Folder views and detail columns, 0.10.0](docs/FOLDER-VIEWS.md). Earlier: [Navigation, saved layouts and shared thumbnails, 0.9.0](docs/NAVIGATION.md). Earlier: [Workspaces, tabs, icons and desktop input, 0.8.0](docs/WORKSPACES.md). Earlier: [Compact transfers, planning and tablet density, 0.7.0](docs/TRANSFER-PLANNING.md). Earlier: [Folder merging and member recovery, 0.6.0](docs/FOLDER-MERGE.md). Earlier: [Transfer and conflict foundation, 0.5.0](docs/TRANSFER-CENTER.md). The [full improvement roadmap](docs/ROADMAP.md) remains in progress. Earlier: [Desktop workflows, 0.4.0](docs/DESKTOP-WORKFLOWS.md), [Refined Finder and Peek, 0.3.0](docs/REFINED-FINDER.md).

The target is Finder's general file-explorer workflows on Android phones and tablets. This is an independent app, not a macOS runtime. Each implementation pass must build, exercise the affected workflows, and update this file before the work is considered complete.

Reference behaviors: [Finder organization](https://support.apple.com/guide/mac-help/organize-files-in-folders-mh26885/mac), [batch rename](https://support.apple.com/guide/mac-help/rename-files-folders-and-disks-mchlp1144/mac), [tags](https://support.apple.com/guide/mac-help/tag-files-and-folders-mchlp15236/mac), and [Smart Folders](https://support.apple.com/guide/mac-help/create-or-change-a-smart-folder-mchlp2804/mac).

## Baseline

- [x] Native Android tablet shell, sidebar, back/forward/up, breadcrumbs, tabs.
- [x] Icons, list, columns, gallery, preview pane, basic Quick Look.
- [x] Local copy/move, rename, duplicate, recoverable Trash, ZIP, sharing.
- [x] Recursive name search, Recents, hidden files, favorites, color tags. Ordered/labeled/grouped Favorites, shortcut menus, built-in visibility/order, unavailable-folder recovery and duplicate-name paths are included in 0.32.0.
- [x] Searchable action/folder launcher, quick folder navigation, gesture-tip replay, pane selection scope, actionable empty states and Browsing/Advanced preferences (0.32.0). Broader command explanations and full hardware/accessibility acceptance remain open.
- [x] Saved-workspace destination checks and reviewed repair (0.34.1): reconnect storage, choose actual replacement folders, Open once, or atomically save repairs in the selected layout. Working tabs, pane settings, pins and filters are preserved. Offline Smart Folder roots can be replaced for a tab without editing the shared search.
- [x] Whole-setup backup and reviewed selective restore, Merge/Replace, explicit root mapping, metadata-only export, previous-setup recovery and startup rollback (0.33.0). Android grants and original file contents must be restored separately.
- [x] Development-signed installable APK and optimized release build.

## Improvement loop

- [x] Safe multi-step Undo/Redo for file operations, with changed-file protection.
- [x] Named tablet Search/New folder/More controls with pane-scoped selection actions, shared command explanations and relevant recovery.
- [x] Recent actions shared across panes/windows, with affected paths, time, state and guarded Undo/Redo. Up to 50 actions; supported local history survives process restart in 0.45. Provider, replacement, organization and unsupported actions remain session-only. Ambiguous saved operations require manual review.
- [x] Batch rename: replace, add text, numbered format, collision preview.
- [x] New Folder with Selection, destination picker, reveal original folder.
- [x] Custom tags, multiple tags per item, tag management, persistent metadata.
- [x] Grouping by kind/date/tag, icon zoom, resizable columns and preview pane.
- [x] Saved Smart Folders with name/kind/date/size/tag/content criteria. All four views highlight filename matches and show match reasons and bounded content snippets (0.36.0). Calendar date/size/type choices, lossless byte bounds and exact file/folder exclusions survive saved searches and reviewed setup remapping; snippets remain transient.
- [x] Persistent recent searches with full-rule reuse here/original, touch/keyboard suggestions, Smart Folder independence, pause/remove/clear controls and stale-request guards (0.37.0). Up to 20 per tablet; no result entries/snippets in history or setup exports.
- [x] Up to ten chosen search folders with independent availability, overlapping-document deduplication, saved scopes, provider refresh and secondary-root workspace repair (0.38.1). Search here and typing suggestions use the current folder; original-location reuse keeps the full scope.
- [x] Complete PDF navigation/zoom, oriented images, audio/video Quick Look.
- [x] Mouse context menus in every view, selection ranges, type-to-select.
- [x] Native drag-and-drop onto folders, tabs/sidebar, and URI import/export with Android apps. Touch icons, labeled Drag and mouse initiation are verified against a separate-UID receiving app on API 30/36. Third-party destinations must accept Android file drops.
- [x] Action/destination feedback, hover opening for folders/tabs with cancellation restoration, proportional viewport edge scrolling, and fractional-dp resizing (0.11.0).
- [x] Android document-provider locations, persistable grants, read-only capabilities.
- [x] Cross-provider/local copy/move, create/rename, provider previews/sharing.
- [x] Cancellable transfers with byte/item progress, recovery journal and lifecycle handling. Recent speed, phase-specific estimates, preparation/verification counts and actual storage-wait explanations share responsive named controls; see [transfer progress](docs/TRANSFER-PROGRESS.md).
- [x] Favorites/sidebar management, aliases as portable app shortcuts, folder appearance. Folder-icon/selection drags can pin one or more folders on the named Favorites target (0.31.0), preserving originals and saved references. Favorite ordering, custom labels, groups and built-in location management ship in 0.32.0.
- [x] Preference controls, folder information, display/extension options, dark theme.
- [x] Expanded safety and workflow tests, tablet landscape/portrait/small-window checks.
- [x] GitHub source, build instructions, [installable preview release](https://github.com/moikdev/findroid/releases/tag/v0.4.0-preview) and final verification.

- [x] Reviewed one-way folder synchronization (0.46): complete content comparison, disclosed additions, independent replacement/Trash confirmations, preserved originals, guarded publication and manual run records. Requires supported local storage; Android 11 shared storage on the assigned emulator refuses safe publication, while Workspace passes. See [supported storage and recovery](docs/REVIEWED-FOLDER-SYNC.md).

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
- [x] Reviewed optional SHA-256 copy verification, actual-byte readback and durable per-file outcomes, including nested and unknown-size sources. Failed unpublished work is cleaned up where possible; reviewed replacement rollback keeps the prior destination. See [copy verification](docs/COPY-VERIFICATION.md) for limits and current acceptance.

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
- [x] Direct text selection on rendered PDF pages, retained while panning/zooming, with named handles and actual clipboard acceptance (0.49.0).
- [ ] OCR/password entry and tiled high-resolution PDF rendering.

## Verification

See [the current verification record](VALIDATION.md) for local test coverage, optimized APK checks and remaining device/provider coverage. [VALIDATION.md](VALIDATION.md) preserves earlier native integration results. Tests use the isolated API 36 tablet emulator. The broader improvement roadmap remains active; the platform boundaries below remain explicit.

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
- [x] Metadata-only copy/move space review (0.48.0): destination content and conservative replacement-recovery allowances, same-volume budgeting, reported/unknown capacity, known shortfall, refreshed estimates, alternative destinations and explicit start. The review does not reserve storage or credit future source deletion. [Details](docs/TRANSFER-SPACE-REVIEW.md).
- [x] Saved read-only permission handling, expired-grant recovery and same-tree reconnect with tab/history retention.
- [x] Explicit different-folder choice, provider-app access and connection removal without discarding tabs.
- [x] Protected-directory guidance and canonical local path checks, including symlinks.
- [ ] Physical USB/SD disconnect, third-party provider/account and multiple Android-version coverage.

## Android integration pass

- [x] Workspace document provider with Android URI grants, saved IDs, browsing/search, reads/writes and file operations.
- [x] Save-to-Findroid share destination with reviewed names/selection, local/provider destinations, staged keep-both publication, cancellation, partial retry and session Undo.
- [x] Compact completion, recreation without automatic replay, and an opt-out for new Workspace provider requests.
- [ ] Physical-device, third-party receiver and provider compatibility matrices beyond the separate-app API 30/36 drag checks.

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

## Collections and reviewed tags pass

- [x] Persistent cross-folder reference collections with stable IDs, sidebar access, ordinary explorer views, complete searchable membership and explicit unavailable references.
- [x] Searchable tag catalog, create/rename/color/delete review, mixed Keep/Add/Remove selection rules and a complete before/after preview.
- [x] Frozen review across recreation, source/assignment rechecks, guarded session history and scoped metadata-only JSON export through local/provider destinations.
- [x] Catalog migration, atomic bounded writes, corruption preservation and visible metadata-follow failure after published file operations.

See [supported behavior, bounds and validation](docs/ORGANIZATION.md). Multi-tag matching is described in the search foundation below. Reviewed whole-setup import/backup/restore ships in 0.33.0; see [SETUP-BACKUP.md](docs/SETUP-BACKUP.md). Recent-search history ships in 0.37.0; [optional local snapshots](docs/LOCAL-SEARCH-INDEX.md) ship in 0.40.0; physical-device validation remains open.

## Saved location identity and repair pass

- [x] Canonical parent-alias identity for organization metadata, preserving final symlinks and distinct hard-link entries.
- [x] Legacy alias assignment merging/membership deduplication, shared catalog locks and consistent browsing/filter/rename lookups.
- [x] Compact informational feedback with pass-through file gestures and interactive Undo/dismiss controls.
- [x] Paged inspection of tag-only and collection-only locations, with explicit unavailable entries.
- [x] Authorized replacement browsing, full relink review, optional tag merging, exact-reference updates across collections and protected atomic session Undo/Redo.

See [identity semantics, provider scope and validation](docs/REFERENCES.md). Opaque child IDs are reviewed individually; no storage grant or file payload is inferred from a reference.

## Search criteria foundation

- [x] All/Any/None matching across multiple exact tag names, combined with existing name/kind/date/size/content criteria.
- [x] Drafted criteria with Apply/Cancel, explicit no-filter state and selectable tags missing from the current catalog.
- [x] Shared rule serialization for Smart Folders, tabs, scan budgets and saved layouts; legacy single-tag rules remain readable.
- [x] Tag rename and Undo/Redo remapping, with live filtered membership refreshed after tag changes.
- [x] This-folder-only or recursive search, with exact folder-name exclusions pruned before traversal for local and connected storage.
- [x] Visible restricted-scope summary and exclusion details; legacy saved searches retain recursive behavior.

See [semantics, validation and remaining search scope](docs/TAG-SEARCH.md). Exact file/folder exclusions and bounded live content snippets ship in 0.36.0; [recent-search history and suggestions](docs/RECENT-SEARCHES.md) ship in 0.37.0. [Chosen search folders](docs/SEARCH-LOCATIONS.md) ship in 0.38.1. [Nested All/Any/None expressions](docs/SEARCH-RULE-GROUPS.md) ship in 0.39.0 with metadata leaves, safe drafts, strict bounded persistence and unknown-size handling. [Optional metadata snapshots](docs/LOCAL-SEARCH-INDEX.md) ship in 0.40.0, with explicit opt-in, current-candidate checks and visible freshness. Document contents are read during a search rather than stored in the index. Persistent content indexing remains open.

## Phone and input increment (0.25.0, local preview)

- [x] Phone portrait/landscape controls, scrollable Locations sheet, readable list rows and reachable file/clipboard actions.
- [x] Per-gesture touch/stylus tap to open, mouse click to select and double-click to open across browser views, providers and archive entries.
- [x] Explicit touch multi-selection, mouse modifiers, right/middle buttons, native drag compatibility and non-activating scroll gestures.
- [x] Phone columns/gallery, compact tabs, recreation, bounded previews, archive extraction controls and short-window adaptation.
- [x] Phone incoming-share review, scrollable destination browsing and reachable save/result actions.
- [x] Shared tablet/phone operation engine and preserved larger-window density/layout preferences.

See [behavior, validation and captures](docs/PHONES-AND-INPUT.md). Physical phone, TalkBack, keyboard/trackpad, USB/SD and third-party provider matrices remain open. This local preview does not complete the whole backlog.

## Natural sorting increment (0.27.0, local preview)

- [x] Shared number-aware filename comparison across browser views, folder/search listings, archive paths and folder pickers.
- [x] Unicode decimal digits, arbitrary-length numeric runs, leading-zero behavior and deterministic case/path ties.
- [x] Independent folder placement, correct reverse order and natural tie-breaks for date/size/kind sorting.
- [x] Large-folder batching interval measured after sorting/publication to avoid a full re-sort for each newly scanned entry.
- [x] Reused comparison for reviewed Natural name rename order; existing alphabetical rename order remains available.

See [behavior, validation and captures](docs/NATURAL-SORTING.md). This establishes stable locale-independent filename ordering; language-specific macOS collation and the remaining roadmap remain separate work.

## Safety and compatibility audit (0.28.0, local preview)

The [production audit](docs/PRODUCTION-AUDIT.md) fixes symlink-following deletion, collision/rename recovery, incomplete provider mutations and streams, changing source trees, damaged Trash recovery records, self-containing ZIP creation, Android 11 ZIP64 reads, and compact PDF navigation/zoom. It adds dependency scanning and compiled privacy/provider/license package checks.

Validation now covers 301 passing JVM tests, with subsequent focused native runs of 15/15 on API 30 and 10/10 on each API 36 layout. ZIP metadata limits run before reader allocations, including actual-count and extra-field checks. Android 11 reconnect now supplies a document URI; real grant, read-only, revoke, cold-start and same-folder recovery checks pass. Earlier combined broad/focused results remain 315 passing cases on each tablet and 48 in the phone preview run. Initial failures and corrected harnesses are recorded in the audit; no new full native-suite run is claimed. Production signing, physical/provider and accessibility acceptance, and dependable hosted CI remain open.
