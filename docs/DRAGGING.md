# Dragging and precise resizing — 0.11.0

The tablet uses one stable native drag target and live, clipped folder/viewport bounds. Lazy rows are eligible only while present in their viewport’s visible-item list; attached but prefetched rows cannot supply drop targets. Pointer movement updates the destination immediately. Folders revealed by scrolling or hover navigation can receive the same drag without restarting it.

![Cross-pane drop feedback](screenshots/dragging-cross-pane.png)

Actual optimized Android captures with synthetic QA files: [Workspace](screenshots/dragging-workspace.png) · [Hold to open](screenshots/dragging-hover.png) · [Opened folder](screenshots/dragging-opened.png) · [Cancel restores](screenshots/dragging-restored.png) · [Dark](screenshots/dragging-dark.png) · [Portrait](screenshots/dragging-portrait.png) · [Fitted column](screenshots/dragging-fit-before.png) · [After manual resize](screenshots/dragging-fit-after.png).

## Behavior

- Mouse: drag a file icon. Touch: select files, then hold the selection bar's drag grip.
- Start with Ctrl to copy, the default or Shift to move, or Alt / Ctrl+Shift to create `.finderlink` shortcuts. The chosen mode and source paths remain fixed for that drag.
- A compact status panel identifies the action, item count and destination. Blue outlines show a valid folder; a red outline and explanation identify an invalid destination. Storage and provider checks run off the UI thread and run again before dispatching the operation.
- Hold over a writable folder, sidebar location, breadcrumb or inactive tab for 800 ms to open it. A small progress ring shows the wait. The browser background is drop-only and never opens another folder automatically.
- Cancelled drags restore temporary navigation, history, selection and folder views. A successful drop keeps the destination pane's opened route and restores any other temporarily browsed panes. Explicit navigation/tab changes made outside the drag retain ownership of their state.
- Hold near a scrolling viewport's edge to scroll, with speed proportional to edge proximity. Move into the center or leave the viewport to stop. Details/content lists, icon grids, browsing columns, the gallery strip and sidebar participate. An inner viewport takes priority when it can scroll; otherwise a parent horizontal viewport can scroll.
- The source payload and destination are captured before queued work, so entering a different folder does not change which files are transferred. Copy/move use the normal journaled queue, conflict handling and session Undo.
- Native URI permissions remain held for external imports until the import completes. Third-party app drag compatibility still requires testing against those apps.

Resize separators preserve fractional dp movement within each gesture and read current widths/ratios for each update. Slow one-pixel movements at tablet density accumulate instead of being discarded. The first manual drag after fitted/adaptive sizing starts from the displayed width instead of jumping to an old stored width. Sidebar, pane split, inspector, browsing columns and detail-column separators share the precision behavior and horizontal-resize cursor.

## Local validation

Local JVM checks passed **97/97**. Android lint completed with **0 errors / 65 warnings**. Debug, optimized development-signed preview and unsigned release variants built locally.

A targeted Android batch ran **39 checks: 37 passed, 2 failed**. One drag driver used a row’s stale/prefetched coordinates while edge scrolling; it now waits for a fully visible row, stops scrolling, and verifies the displayed destination before releasing. The import-completion fixture initially placed its URI source outside the configured FileProvider cache root; it now uses the existing `shared/` root. A subsequent clean drag/provider run passed **16/16**. The final drag run passed **11/11** after adding immediate pointer feedback. Two subsequent resize checks passed **2/2**, including a fitted-width starting-point regression and slow one-pixel motion. This is focused validation across runs, not a claim that the entire Android suite ran cleanly in one batch. The existing all-view passive-pane native right-click stress check passed in the broader batch.

The new Android scenarios cover touch hover/drop/Undo, cancellation restoring history/selection/view, tab hover cancellation, preserving explicit navigation, Ctrl-copy/default-move with a native mouse, invalid self-drops, list/grid/gallery edge scrolling, newly revealed destinations, cross-pane restoration, single-pixel/fitted-width resizing, and exactly-once completion for successful/failed local/provider URI imports. Import completion is dispatched from the operation’s finalization hook so permission cleanup also runs when setup fails before file work begins.

Tests use the dedicated API 36 tablet emulator at 1920 × 1200, density 240. GitHub-hosted runners are not used. The full [roadmap](ROADMAP.md) remains active, including broader focus/accessibility, physical-device and external-provider coverage.

Optimized native QA confirmed cross-pane feedback, folder hover opening with remembered view changes, cancellation restoring the original Icons view and selection, Content/Columns dragging, and dark/portrait layouts. Landscape size, Light appearance and Comfortable density were restored. Screenshots use the optimized APK and synthetic QA files.

The final fitted-date resize was also checked on the optimized APK: a 45 px separator drag widened the displayed column from about 125 to 145 dp, rather than jumping to the old stored width. Both dedicated resize scenarios passed locally.
