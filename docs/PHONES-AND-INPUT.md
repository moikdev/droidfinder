# Phones and per-gesture input — 0.25.0 preview

This working-tree preview extends the existing tablet explorer to regular phones. It uses the same storage engine, provider access, transfers, recovery, tags, searches and saved tabs. The latest GitHub-published preview remains 0.24.0. The phone screen matrix below was recorded for 0.25.0; the current Findroid rebrand and rerun results are recorded in [validation](../VALIDATION.md#findroid-0260).

## Opening and selecting

- **Touch or stylus tap:** enter a folder or open a file immediately. Files use Android's default app and temporary read permission; if Android has no handler, Findroid falls back to Peek. ZIPs open the built-in archive browser.
- **Touch hold:** start multiple selection. While that selection mode is active, taps toggle items. Back or the selection close button finishes selection. Copy and Cut on the phone action bar also finish selection, so the next folder tap can navigate to the destination.
- **Mouse or cursor single-click:** highlight/select the item. **Double-click:** enter or open it. This applies to names, file icons, list, grid, columns, gallery, connected providers and archive members.
- Ctrl/Meta-click toggles selection; Shift-click selects a range. A plain mouse click returns to exclusive selection. Right-click keeps the existing context-menu behavior; middle-click a folder opens a background tab. Icon dragging, selection dragging, marquee selection and scrolling remain available.
- Archive checkboxes select members for extraction. A touch tap on an archive folder enters it; a tap on a member opens its preview. A mouse single-click only focuses it; a double-click enters/previews it. The explicit Browse/Preview buttons remain single-action controls.

Input comes from each gesture's pointer type and native source, including mouse/touchpad sources that report a finger tool. Connecting a mouse does not put subsequent screen touches into a mouse mode. There is no manual input switch. Touch activation does not wait for a double-click timeout. Mouse activation uses Android's double-click timing and spatial limits and requires consecutive completed clicks on the same target. Another target, background/control press, scrolling, a cancelled drag, modifiers or a modal interaction breaks the sequence.

Accessibility activation opens an item; separate Select, Open, Quick Look and Show actions semantics remain available. Keyboard selection and navigation retain the existing shortcuts. The Android tests exercise these semantics; physical TalkBack and hardware trackpad testing remain open.

## Phone layout

Phone controls are used below 600 dp width, and in short windows below 480 dp height, including landscape phones. Larger windows retain the tablet sidebar, toolbar, details columns and pane layout.

- A compact toolbar shows the location or selected count, search, contextual actions and Back. Search/address fields take the available width and request focus after placement.
- The bottom action bar keeps **Locations**, **New**, **View**, **Transfers** and available **Paste** reachable. During selection it provides Copy/Cut/Trash or Trash restore/delete, plus a menu for the full selection. Capability and transfer guards still apply.
- **Locations** opens a scrollable sheet with Workspace, storage, providers, favorites, collections, Smart Folders, tags and Trash. Selecting a location closes it. Short windows omit the storage-capacity footer to keep the location list usable.
- List rows show readable names, metadata and per-item actions without a desktop table's fixed columns. Grid labels retain room for larger text. Columns start with the current folder at full width on phones; swipe horizontally to inspect the selected folder's next column. Selecting that child with a mouse does not navigate or lose the selected entry.
- Gallery uses the full browsing width rather than reserving a metadata sidebar. Peek and archive browsing remain separate, bounded windows. Phone archive extraction buttons share the width; short-window archive controls move to the header and menu.
- Incoming Android shares use a phone-sized review and destination browser, reachable Save/Cancel controls and wrapping result actions. Short landscape windows switch between Review files and Destination; the destination header scrolls with the folders.
- Multiple tabs use a compact overview control instead of a permanent desktop strip. The same tab/history/search/selection state survives activity recreation. Short windows keep tab management in the menu to leave more room for files.
- Dual panes remain independently navigable through pane switches on phones. Tablet-sized windows can show them side by side. Resizing changes the presentation without overwriting the saved desktop density or folder view.

Phone controls retain comfortable touch targets even when Compact spacing is saved for a larger window. Settings explain that distinction. Android's storage authorization, read-only provider capabilities, protected directories and recoverable file-operation rules are unchanged.

## Validation

The Android input workflows use synthetic local and provider files. Touch and mouse events are injected separately; file-open tests monitor Android ACTION_VIEW launches, rather than treating a selection change as an open. Cases cover every main view, file glyphs, provider folders, native mouse-source/finger-tool input, modifier selection, cancelled touch scrolling, child columns, ZIP browsing, file actions, phone copy/paste, create/rename/Trash/restore, search, Peek, tab overview and activity recreation.

Verified locally on **2026-11-01–02**, using isolated Android 16 / API 36 emulators:

| Configuration | Result |
| --- | --- |
| Pixel 7 phone profile, approximately 411 × 914 dp | **24/24** input and incoming-share workflows passed. |
| 360 × 800 dp portrait | **28/28** input, multi-tag and search-scope workflows passed. |
| 800 × 360 dp landscape | **16/16** input workflows passed, plus the incoming-share UI workflow. |
| 320 × 640 dp, 1.5× system text | **16/16** input workflows passed, plus the incoming-share UI workflow. |
| 1280 × 800 dp tablet | **72** shared-input and desktop/navigation/archive workflow checks passed; **20** Windows-shortcut, Peek and provider checks passed in focused reruns; the final native drag suite passed **12/12**. Four phone-only cases were skipped on the tablet. |

The initial tablet regression run exposed three legacy test helpers that used an accessibility/touch open action to select a file. They now inject mouse selection. The native edge-scroll test also waited on clipped row bounds, which allowed it to resume scrolling while targeting a folder; it now waits for the scroll range to reach its end. The affected suites passed after those harness corrections.

`./scripts/check.sh` passed **251 JVM tests**, Android lint (**zero errors, 138 warnings, 11 hints**), the optimized installable preview and unsigned release builds. Preview packaging verified the signature, non-debuggable manifest, exclusion of the debug fixture provider and Workspace provider permissions. The preview has no INTERNET permission. It was installed and cold-launched on both the dedicated phone and tablet emulators without an AndroidRuntime startup error.

The earlier 0.25.0 package is `captures/release/0.25.0/DroidFinder-0.25.0-preview.apk` (7,813,395 bytes). Its SHA-256 is:

```text
c13cc875c186373dbce0b0c7f9be4adbca3b42991991d123907cf373c9332a97
```

`python3 scripts/package-preview.py --skip-build` produced this development-signed APK, the unsigned release, checksums and build metadata. `BUILDINFO.json` records the current source commit and the uncommitted working-tree state. GitHub publication remains at 0.24.0.

Native captures with synthetic QA files:

- [Phone files](screenshots/phone-files.png), [selection](screenshots/phone-selection.png), [icons](screenshots/phone-icons.png) and [Locations](screenshots/phone-locations.png).
- [Peek](screenshots/phone-peek.png), [tab overview](screenshots/phone-tabs.png) and [archive browsing](screenshots/phone-archive.png).
- [Incoming share review](screenshots/phone-share-review.png) and [saved result](screenshots/phone-share-result.png).
- [Landscape browsing](screenshots/phone-landscape.png) and [landscape sharing](screenshots/phone-landscape-share.png).
- [320 dp with enlarged text](screenshots/phone-small-large-text.png), [small archive](screenshots/phone-small-archive.png) and [small share review](screenshots/phone-small-share.png).

Display and font overrides were restored after testing. Physical phones/tablets, TalkBack, hardware keyboards/trackpads, USB/SD devices, third-party cloud providers and older Android versions still need their own coverage. Remaining feature work is tracked in [the roadmap](ROADMAP.md).
