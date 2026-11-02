# Findroid implementation backlog

Goal: implement the full improvement roadmap requested after 0.4.0. This file tracks delivery; an unchecked item is not implemented. Phone layouts and per-gesture touch/mouse behavior are now part of the requested scope. A release is a verified increment, not completion of the whole roadmap.

## 1. Interface and input
- [x] Regular-phone layout and per-gesture touch/stylus versus mouse activation (local 0.25.0 preview). Native device/accessibility/provider matrices remain open.
- [x] Consistent read-only commands during transfers across toolbar, menus and keyboard (0.5.0).
- [x] Comfortable/compact density, accessible primary targets and adaptive tablet controls (0.7.0).
- [ ] Complete the remaining spacing/dialog/input/accessibility refinements.
- [ ] Grouped overflow menus, stable primary actions, useful loading/empty/error/permission states.
- [ ] Focus navigation, mouse/trackpad scrolling, drag feedback, edge scrolling and spring-loaded folders. Windows keyboard controls, selection focus, Ctrl-wheel and a searchable guide are implemented in 0.8.0. Fractional-motion resizing, destination feedback, proportional edge scrolling and spring-loaded folders/tabs are implemented in 0.11.0; broader focus/accessibility and trackpad validation remain.

## 2. Transfer center and conflicts
- [x] Durable copy/move queue, completed history, per-item errors and retry remaining items (0.5.0).
- [x] In-process boundary pause/resume, queue reorder/cancel, byte progress and average speed (0.5.0).
- [x] Metadata preflight totals, explicit partial counts and phase estimates (0.7.0).
- [ ] Safe independent concurrent transfers.
- [x] Explicit interruption review; never silently replay an ambiguous published move/copy (0.5.0).
- [x] Conflict comparison: keep both, skip, rename and replace with recoverable Undo/Redo (0.5.0).
- [x] Folder merge with per-member conflict handling, local/provider recovery and nested retry destinations (0.6.0).
- [x] Apply choices to remaining conflicts, capability/descendant checks and changed-destination guard (0.5.0); ancestor replacement rejection (0.6.0).
- [x] Known-capacity checks before staged copies and replacement rollback (0.7.0).
- [x] Dedicated Unicode/case-only rename coverage (0.22.0): local and provider cases, normalization, host case aliases, edited-file guards, hard links, Undo/Redo and rollback/recovery routes.
- [x] Optional transfer notifications with pause/resume/cancel (0.5.0).
- [ ] Appropriate Android background execution per local/network operation.

## 3. Tablet workspaces and navigation
- [x] Two independently navigable, resizable panes with tabs, selection, history and search (0.8.0).
- [x] Copy/move to other pane, native cross-pane dragging with Undo, pane swapping and recursive folder comparison (0.8.0). Comparison supports explicit top-level copying; deeper synchronization remains future work.
- [x] Per-tab scroll/focus/expansion restoration, pinned tabs, active-tab reveal and tab overview (0.9.0).
- [x] Touch tab reorder, named workspaces and per-folder view/sort/group/column preferences (0.9.0–0.10.0), including pane defaults, folder profile management and detail-column resizing.

## 4. Performance and search
- [x] Shared natural filename sorting, Unicode decimal digits, stable name/path tie-breaks across views/searches/archives/destination browsers, and a large-folder batch throttling correction (local 0.27.0 preview); see NATURAL-SORTING.md.
- [ ] Progressive large-folder loading, bounded shared thumbnail caches and deduplicated work; invalidate thumbnails when provider names/types change while document URIs remain stable. Shared bounded caching, request deduplication/cancellation and name/type invalidation are implemented in 0.9.0; primary folder/search progressive loading, stop/restart, explicit limits and larger per-tab budgets are implemented in 0.12.0. Auxiliary child listings, indexed paging and measured large-catalog performance remain open.
- [ ] Cancellable verification/preview/search, measured startup/scroll/memory/battery benchmarks. Primary search cancellation is implemented in 0.12.0; provider preview streams and PDF text searches gain cancellation in 0.13.0. Complete preview/verification coverage and measured benchmarks remain open.
- [ ] Optional local index scoped to chosen roots, filename/tag/metadata/document-content search.
- [ ] Highlighted matches/snippets, scope/exclusions, suggestions/history and Smart Folder editor.
  - Unreleased increment: the existing criteria editor now supports All/Any/None tag matching, including saved Smart Folders, per-tab restoration and tag-rename remapping. See TAG-SEARCH.md. This is not an arbitrary nested Boolean editor or a persistent search index.
  - The same increment adds this-folder-only/recursive scope and exact folder-name exclusions, pruned before scanning subfolders, with persisted rules and a visible scope summary. Choosing different roots, path-specific exclusions and indexed snippets remain open.
- [ ] Clear incomplete/offline/inaccessible results and index maintenance controls. Primary scans now expose stopped/capped/provider-pending/read errors and retry details (0.12.0); index maintenance remains open.

## 5. Peek and archives
- [ ] PDF text search/selection, thumbnails/bookmarks; richer image metadata and large-image navigation. PDF thumbnails, embedded contents, saved page bookmarks/position, cancellable text-layer search and selectable reading text are implemented in 0.13.0; richer image metadata and region-based large-image navigation are implemented in 0.17.0; direct selection on the rendered PDF page remains open.
- [x] Video subtitles/speed/resume, structured data/code/Markdown/CSV previews. Media3 playback and subtitles/speed/resume are implemented in 0.14.0. Native Markdown, CSV/TSV, strict JSON formatting and code/text previews with explicit bounds and cancellable streams are implemented in 0.15.0; see docs/STRUCTURED-PEEK.md for supported rendering and limits.
- [x] Preview members inside archives, selective extraction, archive search and integrity testing (0.16.0).
- [ ] Encrypted ZIP and additional archive formats after compatibility/licensing checks.
- [ ] ZIP64/large-file verification and configurable resource limits with archive-bomb protection. Persisted count/byte/member/ratio/cache budgets, streamed CRC/size checks, cancellation and small ZIP64 fixtures are implemented in 0.16.0; the Android 11 reader incompatibility is fixed with Commons Compress in 0.28.0; actual multi-GiB payload verification and measured large-file performance remain open.

## 6. Storage and integration
- [x] Clear volume/provider status, space/capabilities, reconnect and expired-grant flows (0.18.0). Physical USB/SD and third-party provider matrices remain open.
- [ ] Direct SMB/SFTP/WebDAV, saved connections, secure credentials and offline handling.
- [ ] Save-to-Findroid share destination, Android document provider and cross-app drag/multi-window. Independent Android windows with shared transfers/history are implemented in 0.8.0; Workspace DocumentsProvider and reviewed Save-to-Findroid shares are implemented in 0.19.0; external cross-app dragging and the device/provider matrix remain open.
- [x] Honor Android protected directories; no claim of unrestricted private-app access (0.18.0).

## 7. Analysis and organization
- [x] Space analysis by folder/category, large/old/duplicate/empty-file discovery with review before cleanup (0.20.0). Logical-size snapshots, full-content duplicate checks, provider unknown sizes, limits/partial states and recoverable review/Undo are implemented; persistent indexing, cross-root duplicate scopes and device performance matrices remain separate open work.
- [x] Trash/cache size and retention, checksum generation/verification. Checksums shipped in 0.20.0; persisted Trash dates, reviewed age policies, bounded inventories, native preview leases and reviewed idle-cache cleanup are implemented in 0.21.0. Retention requires confirmation and does not silently delete expired items.
- [x] Rich batch rename/templates/metadata, virtual collections and improved tag editing/search/export. Reviewed rename/templates/presets shipped in 0.22.0. Cross-folder reference collections, searchable tag management, reviewed mixed-tag changes, guarded session history and scoped metadata JSON export ship in 0.23.0; see ORGANIZATION.md for path identity and export limits. Reference rebinding shipped in 0.24.0; multi-tag search is in the unreleased increment above. Persistent indexing and broader backup/restore remain separate work.
- [x] Canonical organization-location identity across local parent aliases, reference rebinding and provider-controlled identity changes (0.24.0). Paged saved-location review, explicit authorized replacement selection, exact-reference/tag merge review and atomic protected Undo are implemented. Final symlinks remain distinct entries; opaque provider IDs and external moves require explicit review rather than guessed descendant mappings. See REFERENCES.md.
- [ ] Reusable operation presets and previewable organization rules. Rename presets with explicit update/delete and protected persistence are implemented in 0.22.0; broader operation presets and organization rules remain open.

## 8. Recovery, privacy and accessibility
- [x] Transactional transfer database, legacy migration, merge member outcomes and corruption preservation (0.6.0).
- [ ] Safely reconstructable persistent Undo and expanded partial-failure recovery.
- [ ] Settings/favorites/tags/workspace backup and restore; per-location preview/index/cache controls.
- [ ] Optional encrypted vault with explicit recovery, opt-in diagnostics without document disclosure.
- [ ] Configurable shortcuts, TalkBack, large text, RTL and localization.

## 9. Release quality
- [x] Local safety/security/compatibility audit with link-safe deletion, verified transfer streams, partial-provider mutation guards, Trash-index recovery, dependency scanning, and explicit package privacy checks (0.28.0); see PRODUCTION-AUDIT.md.
- [ ] Physical tablet/USB/SD/provider matrix and multiple Android versions. Dedicated API 30 and API 36 emulator coverage is included in the 0.28.0 audit; physical storage and third-party provider acceptance remain open.
- [ ] Interruption, low-space, huge-folder, corrupt-file and disconnect tests.
- [ ] Visual/performance regression checks and modular navigation/storage/UI architecture.
- [x] Repeatable build/package/signature/manifest/checksum validation script (0.5.0).
- [ ] Production signing, dependable hosted CI and upgrade matrix.

## External validation and deferred scope

Hardware/provider credentials and production signing are not supplied by the repository. Implement testable support locally, clearly record external coverage, and do not claim physical-device or hosted-CI success without evidence. GitHub Actions is manual-only at the user’s request because hosted quota is exhausted. Run checks on this PC, or use the authorized Linux box if needed. Do not spend money or alter billing. Phone layout and per-gesture activation are implemented in the local 0.25.0 preview; see PHONES-AND-INPUT.md. The user requests a screenshot with every UI-change update; capture the actual Android UI, inspect it, and include current screenshots in the user-facing result.
