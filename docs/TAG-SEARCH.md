# Search criteria: tags and scan scope (unreleased)

This working-tree increment extends the existing Search criteria editor. It is not a new published release and does not complete the search roadmap.

## Behavior

Open **Search filters**, choose tags, then select a mode:

- **All** requires every selected tag. Other tags on an item are allowed.
- **Any** requires at least one selected tag.
- **None** excludes an item if it has any selected tag. Untagged items match.
- With no selected tags, all modes disable the tag filter. **No tag filter** clears only tag selections; **Clear** clears every search criterion.

The tag predicate is ANDed with name, additional name terms, kind, modification date, size and text-content criteria. Choose a file kind if folders should not appear. Search retains explicit scan limits, cancellation, provider access and partial-result reporting; this does not add an index or silently request storage access. Tag predicates run before opening file content streams.

## Scan scope

The criteria editor offers **This folder only** and **Include subfolders**. Existing searches default to including subfolders. Shallow search checks the root's immediate children against all criteria without opening child folders. Saving as a Smart Folder retains the depth and exclusions alongside its existing root.

**Exclude folder names** accepts one literal, case-sensitive name per line. Matching folders are omitted from results and never queued for traversal, including matches nested deeper in the tree. Names are not globs, paths or comma-separated patterns. `Archive` does not exclude `archive`, `Archive-copy`, or a file called `Archive`. Duplicate lines collapse, blank lines are ignored, and spaces in nonblank names are preserved. The chosen root is always scanned even if its own name appears in the exclusion list.

Scope changes remain drafts until **Apply filters**. **Cancel** leaves results unchanged, and **Clear** restores recursive defaults and removes every criterion. Restricted folder scans show their scope above the results; **Result details** lists the exact excluded names. Intentional exclusions do not mark results incomplete. Errors, pending providers, cancellation and limits inside the requested scope retain their existing reporting.

Local and connected-provider folder scans use the same traversal policy. Recents applies depth to each existing root. Collections, tags and Trash check saved items individually without recursive expansion: depth does not change membership, and exclusions remove only matching folder entries, not separately saved descendant files. This is a search filter, not an access restriction or deletion policy. Root selection, path-specific exclusions and provider-side indexes remain future work.

Tag names match the exact catalog identity, including spaces, punctuation, case and Unicode. Search does not split comma-containing tag names or infer spelling aliases. Selected names absent from the catalog stay visible in the editor and can be deselected: they are not silently dropped. All cannot match an absent tag, Any can still match another selected tag, and None excludes only tags actually assigned to an item.

Changes remain local to the editor until **Apply filters**. **Cancel** leaves active rules unchanged. Save a search as a Smart Folder using the existing menu; reopening its criteria edits the saved rules. Typing an additional query in a Smart Folder still narrows its saved rules.

## Persistence and changes

The same JSON rule codec now handles Smart Folders, per-tab state, scan budgets and saved layouts. Older `tag` rules retain their single-tag All behavior; new `tags` and `tagMatch` fields preserve the selected set and mode. If both old and new selections exist, their union is used. A missing or unrecognized mode defaults to All without discarding the selection.

Tag renames remap all selected names simultaneously through open/closed tabs, scan budgets, saved layouts and Smart Folders. Session Undo/Redo follows those routes. Folder-name exclusions remain literal names and are not renamed as tags. Changes to tag assignments or definitions refresh active tag-filtered panes; another pane showing an edited Smart Folder refreshes its result membership too. These remain Findroid-owned tags, not a claim of interoperable filesystem extended attributes.

The shared rule codec also stores `includeSubfolders` and `excludedFolderNames`. Missing fields restore recursive search with no exclusions. Applying criteria persists the tab immediately; independent tabs, closed tabs, budgets and saved workspaces keep their own rules. Editing a Smart Folder updates other panes showing it.

## Verification

Focused JVM tests cover every All/Any/None truth-table combination, unrelated tags, empty selections, legacy rules, exact Unicode/punctuation names, combined criteria, content-stream avoidance, cancellation and simultaneous rename remapping. Android codec tests cover rule/tab/budget round trips and legacy/unknown-mode reads. Android workflows cover recursive local/provider matching, draft cancellation, Apply, clearing tags, Activity recreation, Smart Folder serialization, tag-rename Undo/Redo and live membership refresh.

Multi-tag baseline checks on 2026-11-01, before the scope follow-up:

- All 241 JVM tests pass, including nine tag-rule tests and the new multi-tag navigation remapping case.
- All 65 Android regression tests pass on the dedicated Android 16 / API 36 tablet emulator: eight tag-search workflows, four navigation codec cases, seven navigation workflows, nine advanced workflows, five provider workflows, 18 organization workflows and 14 reference-repair workflows.
- The criteria workflow also passes on a second emulator in a compact 960 × 1200 px window with 1.3× system text. The captured editor keeps Apply, Cancel and Clear visible. Display and font overrides are restored after the check.
- `./scripts/check.sh` passes: JVM tests, Android lint, optimized preview and unsigned release builds. Lint reports zero errors, 127 warnings and 11 hints; the configuration is unchanged.
- The optimized preview passes APK v2 signature verification. Its manifest excludes the debug fixture provider and has no app-owned INTERNET permission. Preview signing remains debug signing, not production signing.
- The optimized preview installs over the debug build and cold-launches successfully on the tablet emulator, with no AndroidRuntime error in the startup log window.

Actual Android tablet captures using synthetic QA documents:

- [Multi-tag criteria](screenshots/multi-tag-criteria.png)
- [Saved Smart Folder result](screenshots/smart-folder-tag-results.png)
- [Compact window with large text](screenshots/multi-tag-compact.png)

Scope follow-up checks on 2026-11-01:

- All 246 JVM tests pass. Added checks cover shallow traversal, pruning at multiple depths, literal Unicode/case-sensitive names, matching files versus folders, explicit reference collections, root provider issues and legacy serialization defaults.
- `./scripts/check.sh` passes on the final source: unit tests, Android lint, optimized preview and unsigned release. Lint has zero errors, 127 warnings and 11 hints; no suppressions or configuration changes were added.
- The final preview passes APK v2 signature verification. Its manifest is non-debuggable, excludes the fixture provider and has no app-owned INTERNET permission. It remains development-signed.
- Activity recreation with an open search exposed an early focus request to a toolbar field inside a deferred layout. Address/search focus is now requested after the field is placed; the scope recreation test passes with this fix.
- The 53-case Android regression batch passed 52 cases: all four scope workflows, five codecs, eight tag searches, seven navigation workflows, nine Windows keyboard workflows, ten desktop workflows and nine of ten loading workflows. The 20,002-file history-restoration check exceeded its existing 30-second timeout while optimized builds were running. With builds complete, all ten loading workflows passed together on the final APK, alongside the final tablet criteria workflow (11/11). The initial batch is not recorded as a clean full-suite pass.
- The criteria workflow also passes in a 640 × 800 dp window with 1.3× system text. Apply, Cancel and Clear remain visible; display and font overrides are restored after the check. These are functional checks, not measured performance guarantees or physical-device coverage.
- A final tablet capture rerun passes after allowing native IME/surface transactions to settle; the updated test APK also passes lint. The optimized preview installs over debug and cold-launches successfully, with no AndroidRuntime error in the startup log window.

Actual Android scope captures with synthetic QA documents:

- [Scope criteria](screenshots/search-scope-criteria.png)
- [Filtered results and visible scope](screenshots/search-scope-results.png)
- [Compact scope editor with large text](screenshots/search-scope-compact.png)

Hardware/provider matrices, nested Boolean expressions, a Smart Folder root-selection editor, path-specific exclusions, index maintenance, search suggestions/history and persistent indexing remain unfinished. No hosted CI is used for this increment.
