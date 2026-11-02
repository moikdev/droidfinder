# Workspaces and desktop input — 0.8.0

This increment continues the [full roadmap](ROADMAP.md). Named workspaces, pinned tabs, per-folder preferences and the other unchecked items remain open.

## Browsing and commands

Choose **Show dual panes** in Actions, or press **Ctrl/Meta \\**. Each pane keeps its own tabs, navigation history, query, selection, view, sort, grouping and expanded folders. Clicking a pane makes it the target for the shared toolbar, sidebar and keyboard commands. The tinted pane header identifies that target. **F6 / Shift+F6** cycle browser panes, address and search focus areas; Actions also offers **Focus other pane** and **Swap panes**.

The divider resizes both browsers. Preferred ratio, pane order, active pane and dual-pane visibility persist alongside each pane's tabs and settings. Existing tabs remain in the primary pane without a destructive settings migration. Each browser adapts metadata columns to its actual width; a minimum usable pane width takes priority over the preferred ratio. When the available browser area is narrower than 800 dp, a switcher displays one pane at a time while retaining both navigation sessions. Phone-specific layout remains deferred.

One sidebar and one stationary toolbar serve both panes. In wide layouts, each pane has its own header, tab strip and path bar. The automatic side preview appears only when the individual pane has enough room. Alt+P / Alt+Shift+P explicitly open a preview/details inspector in narrower panes; Space/Peek remains available for files in either pane. Tab scroll/focus restoration across hidden panes and named workspace saving are separate roadmap items.

## One file-operation session

`FinderSession` owns the engine, document access, metadata stores, clipboard, mutation guard, Undo/Redo history, transfer control, journal mutex and queue state. `FinderModel` instances own pane navigation and independent directory-loading jobs. Opening a second pane does not open a second transfer store or create a competing mutation coordinator. Each `FinderWindowState` owns its secondary pane store and window-specific layout state. The application owns independent window stores that share the same operation session. Closing an extra window clears its observers and navigation models while queued or running transfers continue in the shared session. Share requests belong to their requesting window, so another open window cannot launch the same share sheet.

**Copy to other pane** and **Move to other pane** capture the destination folder before queuing. Moving to another location or switching panes while work waits cannot retarget the queued job. Existing conflicts, staging, merge plans, recovery, cancellation and history remain in use. Native drag payloads retain the source pane so a completed transfer clears its unchanged source selection rather than a destination selection.

Move/rename/Undo path mappings update all live panes’ open-tab histories, selected paths and expanded folders. Mutation completion refreshes live pane listings across windows. Shared provider title/notification caches support concurrent pane reads; persisted parent-cache updates keep their existing lock. Reconnecting storage routes the folder picker result to the pane that requested it. Unavailable grants still follow Android's access rules.

Folder changes clear stale rows and mark loading immediately, including during debounce. File-observer callbacks ignore events outside the requested change mask and stale watches from another location, preventing stopped-watch notifications from continually restarting listings.

## Folder comparison

With a real folder open in each pane, choose **Compare folders…**. It compares the physical left and right pane folders, including hidden files, while excluding Findroid's in-progress staging names. Matching names and sizes are **Contents not checked**, never automatically identical. Enable **Verify file contents** to read hashes. The results distinguish missing items, type/size/content differences, verified identical contents and inaccessible items.

Comparison is read-only and cancellable. It stops at a bounded 20,000-entry metadata budget across both sides and 64 levels, reports incomplete results, rejects ambiguous duplicate provider names and does not follow symbolic links. Ordinary changes during hashing become unavailable results. Files can change after comparison; it is a point-in-time check rather than a locked filesystem snapshot.

Select a top-level result to **Copy left → right** or **Copy right → left** through the normal queue and conflict resolver. Nested results are informational in this increment; selecting arbitrary nested members for synchronization remains future work. There is no automatic synchronization or deletion based on comparison results.

## Tabs, icons and Windows controls

The **+** button follows the last tab, including in an otherwise empty tab strip. It appends a tab and reveals it. When tabs overflow, the button stays reachable at the strip edge. Tabs also support numbered shortcuts, reopen, close-other, reordering and per-tab navigation/search state.

File icons use a folded-paper silhouette, semantic type symbol and extension badge, with distinct PDF, archive, image, video, audio, spreadsheet, presentation, code, APK, shortcut and unknown-file treatments. Small list icons use symbols rather than unreadable tiny extension text. MIME-only provider documents receive the same type classification. Image thumbnails remain available.

Ctrl shortcuts follow [Microsoft’s File Explorer shortcut reference](https://support.microsoft.com/en-au/accessibility/windows/keyboard-shortcuts-in-windows), with Android equivalents for views and windows. Ctrl+N opens an independent Android document task at the current location. Ctrl+W closes a tab, or its window when it is the last tab. Alt+F4 closes that window. Each window has separate navigation, panes and settings, with the shared clipboard/queue/history described above.

Alt+D, Ctrl+L and F4 focus an inline address bar with the current path selected. Ctrl+E, Ctrl+F and F3 focus search, including when it is already open. Editing fields retain normal text selection, clipboard and arrow controls. Address entry requests a URI keyboard and disables autocorrection. Ctrl+Shift+1–8 select Windows-style icon sizes, names list, details, tiles and content layouts; Ctrl+Shift+9 selects the existing gallery. Ctrl+1–9 select tabs. Meta+1–4 retain Finder view aliases, and Meta+D duplicates. Ctrl+D / Delete move to Trash. Shift+Delete requires a separate permanent-delete confirmation. Ctrl+Shift+N creates a folder; F2 renames and Enter confirms the name dialog.

Keyboard navigation keeps a separate focus lead: Ctrl+arrows move focus without losing selection, Ctrl+Space toggles the focused item, and Ctrl+Shift+arrows add ranges. Home/End and page navigation scroll the focus into view in lists, icon grids and the gallery filmstrip. Ctrl+Shift+E reveals the current folder in a bounded sidebar tree. Numpad + / − / * expand, collapse or recursively expand folders; Ctrl+Numpad+ fits detail columns to visible contents. Ctrl+wheel changes icon size. Ctrl-drag copies, Shift-drag moves, and Alt / Ctrl+Shift-drag creates inert app shortcuts.

F1 opens the searchable shortcut guide. F11 requests Android immersive full screen. Global Windows-key launchers, task switching and OS window placement belong to Android; these are not intercepted or emulated by the app. Phone-specific layout and configurable shortcut bindings remain deferred.

## Verification

Local verification uses the dedicated API 36 tablet emulator and this PC; GitHub Actions remains manual-only. Nine focused Windows-input workflows passed: adjacent/overflow tabs, inline address editing, numbered tabs and view styles, focus/range/scroll/tree navigation, Trash/Undo and permanent-delete confirmation, inspectors/search/help, real independent Android tasks, hardware rename/new-folder confirmation and Ctrl-wheel, and a transfer that completes after its originating window closes and is then undone from the main window.

The workspace tests exercise independent navigation/search/selection, active-pane clipboard routing, shared Undo, a gated queue’s fixed destination and mutation guard, Move/Undo navigation and metadata remapping, narrow switching, recreation/settings, comparison-driven copying, mouse activation across views, and usable minimum pane widths. Five JVM comparison tests cover metadata/content distinctions, unknown sizes, nested differences, case-sensitive names, changed/unreadable content, duplicate names, cycles/links, limits and cancellation. Shortcut resolution and file-type classification have separate JVM coverage.

The initial 96-case full Android run passed 94 cases. It exposed an ETA freshness bug (progress could be newer than the UI clock tick) and a test fixture that left a narrow inspector covering the next test’s file row. Both were corrected. The affected 18-case rerun passed 16 cases; its remaining failures were a duplicate text selector in the expanded four-view navigation check and intermittent native Gallery mouse injection. The corrected selector and native mouse case then passed together; the mouse case also passed a three-cycle stress run (12 native right-click/info-dialog transitions). Hardware address typing passed with Android focus/IME readiness checks. These are full-plus-targeted results, not a claimed clean single-run 96/96 result. Intermittent API 36 native-input test stability remains a release-quality follow-up.

The latest build passed all 89 JVM tests and lint with 0 errors / 72 warnings (SDK/dependency upgrade notices, storage API suggestions and KTX suggestions). Optimized preview and unsigned release APKs built locally. The preview installed over the actual 0.7.0 preview without clearing data: Documents and Invoices tabs, the active Documents location and all seven QA items remained. Native cross-pane touch dragging moved Notes.md from Documents into Workspace, preserving its 109-byte contents; Ctrl+Z restored the original and removed the moved copy. Native clipboard copying into the Studio pane also preserved the contents. In the optimized preview, a hardware-typed folder path opened successfully, Space opened/closed a three-page PDF Peek, and F11 toggled immersive full screen. Display overrides and light appearance were restored after checking portrait, compact and dark layouts.

Landscape, portrait and compact-window screenshots use synthetic QA documents. Physical USB/SD devices, third-party accounts and other Android versions remain external coverage. A phone-specific layout is still deferred.

Native optimized screenshots: [Landscape](screenshots/workspace-landscape.png) · [Portrait](screenshots/workspace-portrait.png) · [Compact window](screenshots/workspace-window.png) · [File icons](screenshots/file-icons.png) · [Dark appearance](screenshots/workspace-dark.png) · [PDF Peek](screenshots/workspace-peek.png) · [Shortcut guide](screenshots/windows-shortcuts.png).
