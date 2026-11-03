# Transfer planning and tablet controls — 0.7.0

This increment continues the [full improvement roadmap](ROADMAP.md). Dual panes, concurrent transfers, indexing and the other unchecked items remain in progress.

## Source totals and phase progress

Before a queued job runs, Findroid inventories its selected sources using metadata. Transfers show estimated content bytes, file/folder counts and the destination's reported available space at the start. Unknown provider sizes, inaccessible entries, cycles and traversal limits produce explicit partial totals. They are never silently counted as an exact zero-byte transfer. The source inventory is bounded to 100,000 entries and 128 levels and checks cancellation between entries; provider metadata queries can still block.

The progress bar describes the current copy phase. It resets for a recovery copy rather than mixing the backup's bytes with the incoming files. Checking, verification and Undo preparation use their own status. Time estimates need sufficient measured transfer time, exclude pauses and disappear in the app when progress reports become stale. A size that grows past its initial estimate returns to indeterminate progress. A fast same-volume move can finish without streaming bytes. See [Transfer progress](TRANSFER-PROGRESS.md) for the recent-rate window, phase estimates, storage waits and responsive controls introduced in 0.42.0.

The notification shows stable phase/byte progress and retains Pause/Resume and Cancel controls. Live speed/ETA stays in the app, so a stalled notification cannot retain a stale countdown. Source totals and available-at-start values survive restart through a transactional database upgrade from 0.6. Runtime speed, phase progress and pause do not promise byte-offset resume after process death. Interrupted work still requires explicit review.

## Capacity checks

Staged local copies and cross-provider copies compare their known minimum content size with reported capacity before creating the destination staging item. Provider capacity is used only when its root matches the destination. Missing, negative or inaccessible provider capacity remains unknown; it does not become a false zero-space error. Same-volume moves try the direct rename first.

If a recoverable replacement cannot fit, the existing original is restored and the incoming source stays intact. Recovery backups use the same copying checks. Space readings are advisory rather than reservations: metadata overhead, quotas, delayed provider updates and other apps can change what fits. Unknown sizes and unreported capacity still require the filesystem/provider to enforce its limits; ordinary copy failures retain sources and clean staging when possible.

## Comfortable and Compact density

Preferences offers Comfortable tablet controls and Compact mouse/keyboard density. The choice is persisted. Comfortable mode provides larger primary toolbar controls, view segments, file action buttons, tabs, sidebar rows, list/column rows, menus and preview controls. Compact mode fits more files on screen.

The toolbar adapts to its actual content width, including sidebar use. Narrow windows collapse secondary commands into menus while retaining Search and Peek for selection. Copy/Cut/Rename/Trash remain direct actions where there is room; Restore remains direct in a roomy Trash view. Menu navigation and preview commands cover collapsed controls. Selecting a file never inserts another toolbar row. In narrow Comfortable layouts, date/kind columns yield space to filenames; the measured layout test preserves useful filename width.

Trash is pinned above storage information in the sidebar. The rest of the sidebar scrolls independently. The transfer window is capped at 560 dp and wraps its content instead of filling the screen. Current file, progress and pause/resume are primary; source/destination paths, capacity and per-file results are collapsed behind Details. Finished history stays out of the active queue until requested. Large queues and expanded details scroll inside a bounded body. Provider files with unknown metadata size display **Unknown size** in browsing, Peek and conflict details.

## Verification

Verified locally on 2026-10-31: **70 JVM tests and all 78 Android workflow tests passed together**, with zero failures or skips. A final narrow-list refinement passed both density tests afterwards, including the new measured filename-width check, bringing coverage to 79 distinct Android tests. Lint completed with zero errors and 64 warnings. Optimized development-signed preview and unsigned release APKs built successfully; signature/manifest checks passed. GitHub Actions remains manual-only and no hosted runners were used.

Added coverage includes bounded consumption of huge child sequences, metadata-only source inventories, partial/unknown counts, cycles/limits/cancellation, low-space rejection before staging, same-volume rename on low space, unknown capacity, protected replacement rollback, pause-aware rates, changing sizes, database schema upgrades, provider unknown-size display, measured touch target bounds, density persistence, determinate provider transfers, ETA and notification controls.

Phone layouts remain deferred. Physical storage/provider and accessibility coverage remains tracked in the full roadmap.

[Compact native transfer window](screenshots/transfer-compact.png), captured during the Android workflow test with synthetic provider data. The test also enforces a compact single-job window size.

Pointer regression checks hit actual filename text, wait for real Android input frames between native mouse events, and cycle through all four views four times. These checks exercise dialog-to-activity transitions without mixing synthetic touch and platform mouse sequences.

## Native optimized-preview checks

The optimized APK was installed over the QA workspace and visually checked in 1280 × 800 dp landscape, 800 × 1280 dp portrait and a 640 × 800 dp tablet window, including Comfortable/Compact density and light/dark appearance. Narrow Comfortable lists preserve filename width by hiding secondary columns. Display overrides and light appearance were restored afterwards.

A real copy of the synthetic `Notes.md` into `Studio` completed through the native controls. Compact completed history, expanded Details and Open folder were inspected, and the copied text was verified in native Peek; Android reported no runtime crash during these checks. The distributed app does not seed user storage.

[Compact density](screenshots/planning-compact.png) · [Comfortable density](screenshots/planning-comfortable.png) · [Portrait](screenshots/planning-portrait.png) · [Narrow window](screenshots/planning-window.png) · [Dark appearance](screenshots/planning-dark.png) · [Completed transfer](screenshots/transfer-history-compact.png) · [Expanded details](screenshots/transfer-details-compact.png)
