# Trash and cache — 0.21.0

Open **Actions → Trash & cache…**, or use the link in Finder preferences. The compact panel measures local Trash and known disposable caches. Measurements are read-only. Cleanup has a separate scrollable review and confirmation.

Actual optimized-app screenshots: [Trash](screenshots/housekeeping-trash.png) · [Review](screenshots/housekeeping-review.png) · [Completion](screenshots/housekeeping-removed.png) · [Cache categories](screenshots/housekeeping-categories.png) · [Dark](screenshots/housekeeping-dark.png) · [Narrow tablet window](screenshots/housekeeping-window.png).

## Trash

- See logical file sizes, original locations and the date each item entered Trash. The date is saved with recovery metadata and survives process restart; an old document does not immediately become an expired Trash item.
- Choose a review age of 7, 30 or 90 days, or manual selection. **Select expired** selects only items with a recorded Trash date older than that threshold. Legacy entries without dates stay available for manual selection and restoration.
- Retention is a reviewed policy: nothing is deleted automatically. Changing a threshold or opening the panel does not remove files.
- Select individual items or all measurable items, review the complete list, then confirm **Delete permanently**. The cleanup rechecks recursive metadata identity and recorded recovery information. Changed, incomplete or unreadable items are skipped, with completed/skipped outcomes shown.
- Permanent removal cannot be undone. The confirmation explains that session Undo/Redo is cleared when Trash deletion is attempted, including attempts that skip a changed item. **Open Trash** returns to ordinary browsing and Restore without deleting anything.

## Cache

The disk inventory covers provider preview units, archive member snapshots, PDF scratch files, prepared folder shares, stream-backed text clipboard files and abandoned shortcut staging. It excludes Workspace originals, settings, document grants, transfer journals, private transfer staging and recovery records. Unknown cache directories are left alone.

Choose a 1-, 7- or 30-day age threshold and **Select expired**, select all eligible idle units, or select a category. The confirmation lists cached filenames where available, category and size. Units are rechecked before removal.

Native previews pin cache-backed files across panes and windows. Provider materialization, archive inflation and clipboard writes also acquire leases; PDF scratch remains protected until the text reader closes. Cleanup atomically detaches only idle units under the lease monitor, then deletes outside that monitor. A new reader regenerates the original path. A stopped deletion leaves a discoverable `.cleanup-*` unit for a later review.

This cleanup skips prepared shares and text clips younger than 24 hours. This grace period is not a durable sharing guarantee: Android and the existing bounded archive/clipboard caches may reclaim cached files sooner, and another app may need a shared URI for longer. Open external apps are not tracked as native preview leases. Refresh after closing another explorer window's preview to update eligibility.

Thumbnail memory has separate usage/count information and **Clear memory**. Clearing evicts cached bitmap references without recycling images that are still displayed; visible images or in-flight decodes can refill it. Disk-cache cleanup preserves file-operation Undo/Redo.

## Limits and interruption

- Sizes are logical file lengths, not allocated blocks or guaranteed reclaimed drive space. Symbolic links are never followed. Cache paths containing symbolic-link ancestors are excluded.
- Trash and cache each retain up to 10,000 top-level units and measure up to 100,000 entries, with a folder-depth limit of 128. A capped or failed unit stays incomplete and cannot be selected for cleanup. Totals show a lower-bound marker for incomplete/capped inventories; measurement notices identify exclusions and failures. The first 100 notices are retained with the full count. Totals saturate instead of overflowing.
- Metadata checks include relative paths, filesystem identity when available, type, length and modification time. They are not full-content hashes and cannot detect edits that preserve all reported metadata. Cache writes performed by the app additionally cooperate through leases.
- Cleanup uses the existing serialized foreground file-operation path. It cannot overlap another app-owned mutation or a Workspace-provider mutation. Stop checks run during measurement and between individual removals. A folder can be partly removed when deletion fails or is cancelled; the outcome explicitly calls for review. Completed deletions are never replayed automatically.
- Read-only measurements stop when the explorer window is backgrounded. Activity recreation retains the panel, report and selection. Review dialogs dismiss safely on recreation without starting cleanup. Threshold preferences persist; snapshots are not a persistent index. Switching views returns to the top, and completed cleanup reveals its outcome even when the selection was scrolled out of view.

## Local validation

The local SDK 36 / JDK 17 build passes **178 JVM tests**, optimized preview/unsigned release builds, and lint with **0 errors / 98 warnings**. Thirteen new domain cases cover logical sizing, links, metadata identity, caps/depth/cancellation, persisted and legacy Trash dates, cache allowlists and escaping ancestors, shared-file grace, changed units, late readers, interrupted-detach recovery, nested leases and overflow-safe totals.

A combined **40-case native regression** passed on the dedicated Android 16 / API 36 emulator. It covers housekeeping, analysis, archives, PDF reading, image Peek and the shared thumbnail repository. A final **25-case native run** also passed: seven housekeeping cases, ten existing Finder cases, five provider cases and three transfer-lifecycle cases. New native cases exercise confirmation/cancel/removal, changed-file skips, cache/original isolation, readers opened after review, an actual cache-backed Peek, age/category selection, activity recreation and persisted thresholds.

A final seven-case housekeeping rerun passed after the scrolled-result reveal fix. The assertion targets the panel result separately from the underlying snackbar.

The final optimized APK removed only the selected synthetic backup through the confirmation flow and automatically revealed its completion after a scrolled review. Dark and narrow tablet layouts were inspected; physical display size/density and light appearance were restored.

Native screenshots use synthetic QA files only; distributed builds do not seed them. Hosted GitHub runners are not used.

Remaining index, backup, network, accessibility, hardware/provider and release work is tracked in [the full roadmap](ROADMAP.md). Phone layout remains deferred.
