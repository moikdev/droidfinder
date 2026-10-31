# DroidFinder implementation backlog

Goal: implement the full improvement roadmap requested after 0.4.0. This file tracks delivery; an unchecked item is not implemented. Phone-specific layouts remain deferred by the user. A release is a verified increment, not completion of the whole roadmap.

## 1. Interface and input
- [x] Consistent read-only commands during transfers across toolbar, menus and keyboard (0.5.0).
- [ ] Comfortable/compact density with accessible touch targets, consistent spacing/icons/dialogs.
- [ ] Grouped overflow menus, stable primary actions, useful loading/empty/error/permission states.
- [ ] Focus navigation, mouse/trackpad scrolling, drag feedback, edge scrolling and spring-loaded folders.

## 2. Transfer center and conflicts
- [x] Durable copy/move queue, completed history, per-item errors and retry remaining items (0.5.0).
- [x] In-process boundary pause/resume, queue reorder/cancel, byte progress and average speed (0.5.0).
- [ ] Preflight totals/ETA and safe independent concurrent transfers.
- [x] Explicit interruption review; never silently replay an ambiguous published move/copy (0.5.0).
- [x] Conflict comparison: keep both, skip, rename and replace with recoverable Undo/Redo (0.5.0).
- [x] Folder merge with per-member conflict handling, local/provider recovery and nested retry destinations (0.6.0).
- [x] Apply choices to remaining conflicts, capability/descendant checks and changed-destination guard (0.5.0); ancestor replacement rejection (0.6.0).
- [ ] Free-space preflight and dedicated Unicode/case-only rename coverage.
- [x] Optional transfer notifications with pause/resume/cancel (0.5.0).
- [ ] Appropriate Android background execution per local/network operation.

## 3. Tablet workspaces and navigation
- [ ] Two independently navigable, resizable panes with tabs, selection, history and search.
- [ ] Copy/move to other pane, drag between panes, swap panes and folder comparison.
- [ ] Per-tab scroll/focus/expansion restoration, pinned tabs, active-tab reveal and tab overview.
- [ ] Touch tab reorder, named workspaces and per-folder view/sort/group/column preferences.

## 4. Performance and search
- [ ] Progressive large-folder loading, bounded shared thumbnail caches and deduplicated work.
- [ ] Cancellable verification/preview/search, measured startup/scroll/memory/battery benchmarks.
- [ ] Optional local index scoped to chosen roots, filename/tag/metadata/document-content search.
- [ ] Highlighted matches/snippets, scope/exclusions, suggestions/history and Smart Folder editor.
- [ ] Clear incomplete/offline/inaccessible results and index maintenance controls.

## 5. Peek and archives
- [ ] PDF text search/selection, thumbnails/bookmarks; richer image metadata and large-image navigation.
- [ ] Video subtitles/speed/resume, structured data/code/Markdown/CSV previews.
- [ ] Preview members inside archives, selective extraction, archive search and integrity testing.
- [ ] Encrypted ZIP and additional archive formats after compatibility/licensing checks.
- [ ] ZIP64/large-file verification and configurable resource limits with archive-bomb protection.

## 6. Storage and integration
- [ ] Clear volume/provider status, space/capabilities, reconnect and expired-grant flows.
- [ ] Direct SMB/SFTP/WebDAV, saved connections, secure credentials and offline handling.
- [ ] Save-to-DroidFinder share destination, Android document provider and cross-app drag/multi-window.
- [ ] Honor Android protected directories; no claim of unrestricted private-app access.

## 7. Analysis and organization
- [ ] Space analysis by folder/category, large/old/duplicate/empty-file discovery with review before cleanup.
- [ ] Trash/cache size and retention, checksum generation/verification.
- [ ] Rich batch rename/templates/metadata, virtual collections and improved tag editing/search/export.
- [ ] Reusable operation presets and previewable organization rules.

## 8. Recovery, privacy and accessibility
- [x] Transactional transfer database, legacy migration, merge member outcomes and corruption preservation (0.6.0).
- [ ] Safely reconstructable persistent Undo and expanded partial-failure recovery.
- [ ] Settings/favorites/tags/workspace backup and restore; per-location preview/index/cache controls.
- [ ] Optional encrypted vault with explicit recovery, opt-in diagnostics without document disclosure.
- [ ] Configurable shortcuts, TalkBack, large text, RTL and localization.

## 9. Release quality
- [ ] Physical tablet/USB/SD/provider matrix and multiple Android versions.
- [ ] Interruption, low-space, huge-folder, corrupt-file and disconnect tests.
- [ ] Visual/performance regression checks and modular navigation/storage/UI architecture.
- [x] Repeatable build/package/signature/manifest/checksum validation script (0.5.0).
- [ ] Production signing, dependable hosted CI and upgrade matrix.

## External validation and deferred scope

Hardware/provider credentials and production signing are not supplied by the repository. Implement testable support locally, clearly record external coverage, and do not claim physical-device or hosted-CI success without evidence. GitHub Actions is manual-only at the user’s request because hosted quota is exhausted. Run checks on this PC, or use the authorized Linux box if needed. Do not spend money or alter billing. Phone layout remains deferred.
