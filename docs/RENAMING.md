# Reviewed batch rename — 0.22.0

Select files or folders and choose **Actions → Rename with rules…**. The ordinary Rename button opens this tool for a multiple selection; a single item keeps the simple name editor. The rule preview captures the selected items rather than rereading a later explorer selection when Apply is pressed.

Actual native screenshots with synthetic QA files: [photo naming template](screenshots/rename-template.png) · [regex review](screenshots/rename-regex.png) · [rename options](screenshots/rename-options.png) · [dark appearance](screenshots/rename-dark.png) · [compact Rules](screenshots/rename-window-rules.png) · [compact Preview](screenshots/rename-window-preview.png) · [workspace](screenshots/rename-workspace.png) · [recovery locations](screenshots/rename-recovery.png). The recovery capture uses the debug provider fixture to simulate a failed restoration.

## Rules and full review

- Replace literal text or use regular expressions, with match-case and first-match options. Regex replacement supports numbered and named groups.
- Add a prefix/suffix, number names with a nonnegative start, positive step, padding and separator, or change case using a locale-independent rule.
- Apply ordinary rules to the name, the complete filename or just the extension. The last suffix is the extension; dotfiles and dotted folder names remain names. Extension-only rules leave folders and extensionless files unchanged.
- Templates produce the complete result. `{name}`, `{full}`, `{ext}`, `{dotext}`, `{index}`, `{parent}`, `{size}`, `{tag}` and `{tags}` use captured filesystem/provider/tag metadata. `{dotext}` includes the dot only when an extension exists.
- `{modified:yyyy-MM-dd}` formats the reported modification time in the timezone captured when the tool opened. Known local epoch/pre-epoch values remain valid; missing provider dates do not invent a date.
- `{taken:yyyy-MM-dd}`, `{camera}`, `{width}` and `{height}` use explicitly requested photo metadata. **Read photo metadata** reads EXIF headers under a 16 MiB stream budget per image, without generating a bitmap or copying the image into the preview cache. EXIF capture dates remain local wall-clock values, not a guessed timezone conversion. Unknown fields remain explicit template errors.
- Use `{{` and `}}` for literal braces. Metadata slashes, NUL and control characters become underscores; literal invalid names remain errors. Names remain within 240 UTF-8 bytes, invalid Unicode is rejected, and internal `.findroid-` working names cannot be produced.

Every item appears in the scrollable before/after table, including unchanged, excluded and invalid rows. Open a row for complete names and its location. Filter hides rows without changing the rename selection. Include/exclude controls recalculate numbering. Sort by captured order, name, natural name, date or size; reverse or move individual rows with the row menu.

The default collision option treats canonically equivalent Unicode and expanded uppercase keys as potential conflicts, including case variants, Greek sigma and sharp-S expansion. This is deliberately conservative across volumes. It can be disabled when distinct variants are intentional; actual filesystem/provider restrictions still apply. Unselected arrivals and excluded items keep their names and cannot be treated as vacated destinations.

## Saved presets

Save up to 32 named presets containing only rule and order settings. Loading does not select files or start a rename. Updating or deleting a selected preset is explicit; saving a new preset never silently overwrites another name. The shared store reloads before writes and publishes atomically. Unsupported, oversized or malformed catalogs are preserved and cannot be overwritten through the editor. File selections and paths are not stored in presets.

## Applying, Undo and recovery

The app rechecks source identity/metadata, parent locations, tags, capabilities and complete destination listings before mutation. A changed input or an incomplete provider listing requires another review. The preview retains its selection, exclusions and rule across activity recreation. Read jobs stop when their window is backgrounded; opening the tool or loading a preset never starts a mutation.

**Prepare protected session Undo** is on by default. It reads changed items before names are staged, so cancellation during a blocked provider read can stop before any rename. These checks preserve the existing edited-file guards in Undo/Redo. Turn it off for a metadata-only rename when the content read would be too expensive; a visible warning explains that applying clears session Undo and Redo. Neither mode overwrites an existing destination intentionally.

Changed names are first staged to allow swaps and case-only changes. Local case/normalization aliases are distinguished from unselected hard links. Cancellation/failure attempts to restore the original names without replacing new arrivals. If restoration is blocked, the app tries to publish a visible keep-both **Recovered …** name and records the exact current routes. Tags and navigation metadata follow those routes. **Recovery items** exposes readable locations, optional full paths and a copy-location control, and opens the current file or folder. Refresh follows recovered items rather than selecting replacements that arrived at their former names.

This is a sequence of ordinary live filesystem/provider operations, not a filesystem-wide transaction. Other apps and provider behavior can still race individual calls. Provider names can be constrained or normalized; an unexpected adjusted result triggers restoration rather than silently accepting a different preview. Some provider metadata/rename calls take time to return. A process killed between stages can leave working files; the existing interruption guidance and the remaining persistent recovery/Undo work remain applicable. Recovery-route details are retained in this app session, not a durable operation journal.

## Workspace document identity

The provider updates stable document IDs when it publishes a move or rename. Its delayed UI notification now updates explorer metadata without replaying that ID relocation. This keeps a later copy at the vacated path from being reassigned to the moved document. A native regression deliberately holds the UI queue while moving and copying, then verifies both IDs and the surviving copy after deleting the moved original. This test fails on the APK before the ID fix and passes on the fixed build.

## Live tablet resizing

The optimized screenshot pass exposed a density-change crash with an attached drag source in Compose UI 1.7.6. The mapped stack points to the direct pointer-node cast in that version. UI/Foundation and their UI test artifacts now use [1.8.3](https://developer.android.com/jetpack/androidx/releases/compose-ui#1.8.3); its [published LayoutNode source](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.8.3/ui-android-1.8.3-sources.jar) dispatches density callbacks through the node API. Explicitly naming the legacy drag gesture overload preserves the app’s existing mouse and touch start behavior. A native regression changes density twice while a drag source remains attached and checks its resulting pixel size.

## Bounds and platform scope

One reviewed batch supports up to 10,000 items, a combined 100,000 destination entries and 128 parent levels. A capped, pending or failed destination listing cannot produce an executable plan for its affected rows. Undo tree verification is bounded by 100,000 entries / 128 levels. Rule fields are limited to 512 characters; byte and numeric-overflow checks stay explicit. Symbolic-link selections are excluded from reviewed batches; ordinary single-item rename behavior remains separate.

Regex matching uses [RE2/J 1.8](https://github.com/google/re2j/releases/tag/re2j-1.8), whose [linear-time matching design and syntax limitations](https://github.com/google/re2j) avoid backtracking blow-ups. Lookaround and pattern backreferences are unsupported; replacement captures remain available. The upstream BSD-style license is distributed in the APK. No network permission is added.

## Validation

The local SDK 36 / JDK 17 build passes 200 JVM tests, optimized preview/unsigned release builds and lint with 0 errors / 122 warnings / 11 hints. Twenty-two new domain cases cover rules, regex replacement/unsupported patterns, templates and missing values, ordering/exclusion, collisions, Unicode/UTF-8 bounds, overflow, presets/corruption preservation, actual case aliases, hard links, staged cancellation and failed-rollback routes.

On the dedicated Android 16 / API 36 emulator, 67 distinct native workflows have passing results on the final production source: 13 rename, ten Finder, five provider, one digest, eight Workspace-provider, twelve drag, two density/layout, nine Windows-shortcut, six Peek and one live-density regression. These verify frozen selection, full previews past ten rows, keyboard editing ownership, ordering/exclusion, source/destination rechecks, EXIF dates/camera/dimensions, presets/recreation, case/Unicode names, mixed-root Undo/Redo and tags, blocked-stream cancellation, visible recovery, tab/button reachability, desktop input and previews.

The final broad 67-case run passed 66 cases and exposed a timing flaw in the edge-drag test: it sampled a folder position before the final scroll frame settled. Instrumented pointer/bounds tracing confirmed that the app correctly targeted the actual pointer position. The harness now parks over the header and waits for stable row bounds. The complete twelve-case drag class, both density/layout tests and the live-density regression then passed together (15 tests). The earlier narrow-layout test also now establishes its own adaptive-column defaults instead of inheriting custom settings from another class. No temporary pointer logging remains in the app.

Manual checks in the optimized preview saved and reloaded a photo-template preset, used `IMG_([0-9]+)` → `Shot_$1` to rename two synthetic JPEGs, and used Ctrl+Z to restore both original names. The final optimized APK repeated these checks, reloaded its saved preset, and survived live display-size/density changes with the rename tool open. Light, dark and 640 × 800 dp tablet-window layouts were inspected; the normal 1920 × 1200 / 240 dpi display and light appearance were restored.

The host filesystem also resolves Greek sigma variants to the same file; the actual rename test covers that alias rather than assuming ASCII case rules. Screenshots use only synthetic QA files. Hosted GitHub runners are not used; phone-specific layouts remain deferred.

The [full roadmap](ROADMAP.md) still includes virtual collections, richer tag editing/search/export, organization rules, persistent recovery, indexing, network clients and hardware/provider validation.
