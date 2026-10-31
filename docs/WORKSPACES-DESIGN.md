# Dual-pane workspace design

Status: planned next implementation phase; none of the items below are claimed as shipped. The full scope remains in [ROADMAP.md](ROADMAP.md).

## Ownership

Do not create two independent copies of the current app-owned `FinderModel`: each would own a transfer database snapshot, mutation guard, clipboard, metadata stores and history. Two independent writers would undermine the serialized transfer/recovery guarantees.

Extract a shared file session for `FileEngine`, `DocumentAccess`, tags/folder appearance, favorites/Smart Folders, clipboard, transfer jobs/store/control, mutation state, history and notification coordination. Each pane owns its navigation/session state: tabs, current tab, history/search, selection/anchor/focus, expanded folders and listing job. Queue submissions retain the initiating pane/tab/selection snapshot; changing active pane must not retarget an already-submitted operation.

App lifetime owns both pane sessions. Hiding a pane must not cancel its submitted work. The foreground service controls the one shared transfer coordinator. Path mappings and mutation completion update/refresh all pane sessions; per-pane directory loads remain independently cancellable. Audit mutable provider parent/title/cache maps before increasing concurrent directory reads.

## Tablet presentation

Use one shared sidebar, toolbar, transfer strip and dialog layer. Toolbar/keyboard commands act on the active pane. Each browser pane has its own tab strip, path/navigation header and file view. A subtle active-pane border/header state identifies the command target. Clicking or focusing a passive pane activates it before selection/keyboard actions.

Resize the divider with sensible minimum pane widths; persist the ratio. Preview inspection belongs to the active pane and must not reduce both file lists to unusable widths. Narrow tablet windows keep both sessions but show one browser with an explicit pane switcher. Phone layout remains deferred.

List metadata columns and view controls must adapt to the actual pane width. Filenames take priority over dates/kinds. Preserve the compact, content-sized transfer window introduced in 0.7; avoid duplicate toolbars or large empty surfaces.

## Cross-pane actions

Add Copy/Move to other pane, pane swap, keyboard focus switching and drag between panes. Display the destination pane/folder before the action. Reject non-writable or virtual destinations through existing capabilities. Transfers go through the shared durable queue and conflict resolver; they do not bypass staging, merge plans, recovery or history. Native drag payloads identify their originating pane so successful moves clear the appropriate unchanged selection.

Move/folder-merge metadata mappings must update all relevant navigation histories and selected paths. Connected providers and permissions stay shared, while listing errors/offline state belong to the pane that encountered them.

## Persistence and comparison

Migrate existing tabs/navigation to the first pane without losing the current workspace. Store the second pane separately. Keep app-wide theme/density/sidebar preferences shared; keep independent view/sort/group/search state per pane, then add per-folder preferences, pinned/reordered tabs, named workspaces and per-tab scroll/focus restoration.

Folder comparison must distinguish metadata matches from verified content equality. Pair names/types, report missing/changed/unknown items and optionally verify content with cancellation. Do not label same-size files identical without reading them. Comparison is read-only until the user chooses specific copy/move actions, which use the normal queue and conflicts.

## Required evidence

Exercise independent navigation/search/selection, active-pane command routing, resize/swap/persistence, keyboard/touch/mouse focus, cross-pane clipboard/drag, shared queue cancellation/recovery/history, provider permissions and path remapping, narrow-window fallback and comparison accuracy. Capture actual landscape, portrait and compact-window screenshots for each UI update. Existing file safety, transfer, Peek, archive and input workflows remain regression gates. Verification stays local; GitHub Actions remains manual-only.
