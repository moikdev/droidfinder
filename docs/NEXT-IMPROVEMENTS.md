# What to improve next

Recommendations after the 0.31.0 Favorites drag update, reviewed against the current app on November 2, 2026. These are proposals, not a claim that the work has shipped. The implementation checklist remains in [ROADMAP.md](ROADMAP.md).

Findroid already has tabs, two panes, four views, Quick Look, tags, Smart Folders, batch rename, transfer recovery, ZIP browsing, storage analysis, Android sharing, and in-app updates. The next passes should make those tools easier to discover and dependable in everyday tablet use before expanding the main toolbar.

## Recommended order

1. **Finish the sidebar:** reorder favorites, remove/rename a shortcut from its own menu, hide unused built-in locations, and show unavailable folders clearly.
2. **Make selection and dragging unmistakable:** separate file selection, lifting an icon, pinning a folder, and moving files with consistent words and feedback.
3. **Back up the setup:** restore favorites, tags, workspaces, saved searches, and preferences on another installation, with a reviewed import.
4. **Improve finding things:** quick jump to a folder, then optional indexing, result highlights, and better search history.
5. **Make tablet input feel complete:** keyboard focus, trackpad behavior, large text, TalkBack, and high-contrast states.
6. **Add network locations:** start with one complete SMB workflow, then SFTP and WebDAV, including disconnect and reconnect behavior.

A small, finished increment should include its failure states, actual device checks, and a screenshot. Numbers below are stable discussion references, not a promise about delivery dates.

## Everyday clarity and control

1. **Explain gestures once, where they happen.** A dismissible hint can explain “Hold the name to select; hold the icon to drag,” with a replay option in Help.
2. **Keep primary actions predictable.** Preserve the position of Search, New folder, and More across tablet widths; move less-used commands into named groups.
3. **Explain unavailable commands.** Let a read-only, disconnected, or busy item explain why Rename or Move is unavailable and offer the relevant recovery action.
4. **Show the full destination before long moves.** Extend current drag and transfer feedback to disambiguate identical folder names with their parent and storage location.
5. **Improve multi-selection clarity.** Keep the selected count and affected scope visible when changing views or switching the active pane.
6. **Make Undo easier to reach.** Offer a consistent recent-action surface with a clear explanation when the file has changed and recovery cannot safely proceed.
7. **Use task-specific empty states.** Distinguish an empty folder, no search matches, a filtered folder, and a disconnected provider, each with a useful next action.
8. **Add a searchable action launcher.** Type “rename,” “show hidden files,” or a folder name without remembering which menu contains the command; complement the existing shortcut guide.
9. **Separate simple and advanced preferences.** Keep normal browsing choices easy to find and put archive budgets, cache controls, and expert transfer options in an advanced section.

## Sidebar and tablet workspaces

10. **Reorder favorites by dragging.** Show an insertion line and preserve the chosen order after restart; existing favorites currently use a set.
11. **Add a menu on each favorite.** Rename its sidebar label, remove the shortcut, reveal its original location, or open it in another tab/pane.
12. **Hide and reorder built-in locations.** Let people keep just the locations they use while retaining a simple Restore defaults action.
13. **Add collapsible favorite groups.** Organize Work, Personal, and Travel shortcuts without adding another permanent toolbar.
14. **Mark unavailable favorites.** Show which storage/location is missing and offer reconnect, repair, or remove; avoid presenting a missing folder as an empty one.
15. **Make identical favorite names distinct.** Show a parent path or storage subtitle when two shortcuts have the same folder name.
16. **Offer quick folder navigation.** Add a searchable folder/location switcher with recent destinations, breadcrumbs, and keyboard operation.
17. **Improve trackpad and stylus polish.** Validate precision scrolling, secondary-click, pen dragging, and accidental palm interaction on physical tablets.
18. **Extend workspace recovery.** Review unavailable locations when restoring a saved two-pane layout and allow replacing only the missing destinations.

## Search and large libraries

19. **Add an optional local index.** Index only chosen roots, with clear storage use, last refresh time, pause, rebuild, and delete controls.
20. **Highlight matches in results.** Explain whether the match came from a filename, tag, metadata, or file contents, with a relevant snippet when available.
21. **Add saved search history and suggestions.** Reuse recent queries and filters without needing to create a Smart Folder for every search.
22. **Choose search roots explicitly.** Search several chosen locations together and show which providers were included or unavailable.
23. **Extend exclusions to exact paths.** Build on the existing folder-name exclusions so one cache folder can be omitted without excluding every folder with that name.
24. **Offer natural date and size presets.** Chips such as “This week,” “Over 1 GB,” and “Images” can simplify the existing criteria editor.
25. **Support nested search rules.** Let advanced users combine groups such as “PDF or document, and either of these tags,” with a plain-language summary.
26. **Show index freshness and partial results.** Distinguish live scans, cached/offline matches, inaccessible roots, and capped results in one understandable status.
27. **Measure very large folders.** Record time to first item, scroll smoothness, memory use, and cancellation response at representative library sizes before changing defaults.

## Storage and Android integration

28. **Add SMB connections.** Browse a NAS or shared computer with saved hosts, credential management, reconnect, and tested copy/move failures.
29. **Add SFTP connections.** Include explicit server identity verification, key/password setup, and an understandable reconnect flow.
30. **Add WebDAV connections.** Support HTTPS servers with clear certificate, authentication, and offline errors.
31. **Pin selected network folders for offline use.** Show sync status and require review when both local and remote content changed.
32. **Strengthen removable-drive workflows.** Validate real USB/SD insertion, removal during transfers, low space, and reopening favorites after reconnecting.
33. **Extend reconnect to cloud-account sign-in.** Build on existing grant reconnection with a clearer provider-account authentication route and a reviewed retry at the intended destination.
34. **Bring capabilities into action explanations.** Extend the existing storage status dialog with per-folder reasons in menus for reading, writing, renaming, Trash, and unknown provider capabilities.
35. **Expand cross-app drag acceptance.** Test actual document editors, mail apps, note apps, and cloud providers; describe when a particular receiver needs Share instead.
36. **Broaden Android picker testing.** Validate Workspace visibility, save/create flows, grant revocation, and editing through other apps on physical devices.

## Transfers, recovery, and comparison

37. **Run independent transfers concurrently.** Add a small concurrency limit while serializing operations that touch overlapping source/destination paths.
38. **Strengthen background transfers.** Choose Android execution appropriate to each operation and make foreground/background status understandable.
39. **Improve throughput and time estimates.** Use a rolling estimate and distinguish preparation, copying, verification, and provider waiting from a stalled operation.
40. **Offer optional verification for important copies.** Build on existing checksums with a reviewed post-copy verification option and per-item results.
41. **Support durable partial-copy resume where safe.** Only resume when source identity, destination state, and backend capabilities prove it is safe; explain restarts elsewhere.
42. **Make Undo survive restarts.** Persist enough verified recovery information to reconstruct eligible actions without overwriting files that changed later.
43. **Add reviewed folder synchronization.** Extend existing comparison beyond top-level copy with a dry run showing additions, updates, conflicts, and deletions separately.
44. **Add a recovery inbox.** Bring interrupted transfers, partial provider operations, and preserved conflict backups into one place with specific next steps.
45. **Explain low-space plans in detail.** Extend existing capacity preflight with a staging-space breakdown, unknown-provider capacity, and destination alternatives before work starts.

## Preview and archive tools

46. **Select text directly on PDF pages.** Complement the existing selectable reading text with selection anchored to the rendered page.
47. **Add optional OCR.** Search or copy text from scanned PDFs and images, with explicit local processing/download choices and resource limits.
48. **Preview office documents.** Start with a small, documented read-only format set and explain fidelity limits rather than promising complete Office compatibility.
49. **Compare files visually.** Extend checksums and folder comparison with text differences and a side-by-side image comparison.
50. **Add a dedicated image review workflow.** Compare several photos, flag candidates, and apply tags to the reviewed selection using the existing image viewer.
51. **Review and edit photo metadata.** Add explicit metadata editing/removal with an affected-field preview and an Undo route where possible.
52. **Support encrypted ZIP archives.** Add password entry, documented encryption compatibility, and clear incorrect-password versus corrupt-archive errors.
53. **Add selected additional archive formats.** Prioritize reading/extraction for TAR and 7z after implementation, licensing, resource limits, and compatibility review.
54. **Validate huge and malformed previews.** Test multi-GiB archives, huge images, long PDFs, and corrupt media with bounded memory and prompt cancellation.

## Organization and personalization

55. **Export and restore the whole setup.** Back up favorites, tags, Smart Folders, collections, workspaces, and preferences; review missing roots and merges on import.
56. **Make saved references portable.** Offer explicit root remapping when restoring to another tablet instead of assuming old absolute paths still exist.
57. **Add reviewed organization rules.** Preview rules such as “move screenshots older than 30 days” and keep execution manual until the behavior is trusted.
58. **Extend reusable operation presets.** Save common copy, archive, and cleanup options alongside existing rename presets, with a visible review before applying.
59. **Offer workspace-specific sidebar sets.** Keep a focused set of locations for each named workspace while retaining a shared global list as the default.
60. **Add new-file templates.** Create a note, Markdown file, CSV, or chosen template from New without leaving the current folder.
61. **Make metadata backup more discoverable.** Bring the existing scoped tag/collection export into the organization workflow and explain what remains reference-only.
62. **Add per-location privacy controls.** Choose whether thumbnails, previews, search indexing, and recent-item entries are retained for a location.
63. **Consider an optional encrypted vault later.** Define recovery, locking, export, backups, and interoperability before building a second storage system.

## Accessibility, performance, and release quality

64. **Complete a TalkBack workflow audit.** Verify file names, selected states, both panes, menus, dialogs, drag alternatives, and focus restoration with a screen reader.
65. **Test large text and display scaling.** Keep filenames, actions, dialogs, and the sidebar usable at the largest supported sizes without clipped controls.
66. **Add contrast and motion preferences.** Include clear high-contrast selection, system theme following, and reduced animation where useful.
67. **Make shortcuts configurable.** Build on existing keyboard support with remapping, conflict detection, and a help view that shows the active bindings.
68. **Add localization and RTL support.** Begin with the languages users need and verify mirrored layout, filenames, dates, and number formatting.
69. **Benchmark startup and everyday browsing.** Track cold start, first usable folder, thumbnail work, memory, battery, and background wakeups on real tablets.
70. **Make optimized-build UI checks repeatable.** Cover tablet landscape/portrait, split screen, large text, and light/dark states with representative screenshot comparisons.
71. **Expand the failure matrix.** Exercise process death, grant loss, drive removal, permission changes, full storage, and provider timeouts at meaningful operation boundaries.
72. **Finish release infrastructure.** Establish long-term signing/recovery, documented upgrade tests, and dependable CI when hosted capacity is available; keep current local verification while Actions remains manual-only.

## Scope guardrails

- Preserve the tablet focus. Improve split-screen and tablet input before expanding phone-specific features.
- Keep advanced features in contextual surfaces rather than filling the toolbar with more icons.
- Do not promise unrestricted Android protected-folder access or universal third-party drag support.
- New storage systems, vaults, synchronization, and automatic rules need complete recovery designs; they are larger projects than interface polish.
- Physical tablet, removable-drive, and third-party app coverage must be reported separately from emulator results.
