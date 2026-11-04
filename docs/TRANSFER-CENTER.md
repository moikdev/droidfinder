# Transfer and conflict foundation — 0.5.0

Current transfer guides: [progress and estimates](TRANSFER-PROGRESS.md), [optional copy verification](COPY-VERIFICATION.md), [reviewed partial-copy recovery](PARTIAL-COPY-RESUME.md), and [Undo after reopening](PERSISTENT-UNDO.md). The rest of this page records the original 0.5.0 foundation; later increments extend its behavior.

This is the first implementation increment of the [full improvement roadmap](ROADMAP.md). The full roadmap remains active. Dual panes, indexing, direct network connections, expanded formats and the other unchecked items are not included in this increment.

## Queued copy and move

Copy/move requests now enter a durable queue instead of being rejected while another operation runs. One operation executes at a time so file history, destinations and provider state remain serialized. Internal drag-and-drop, paste and destination-picker transfers use the queue. Other mutations remain guarded while a transfer runs.

Open **Transfers** from the progress strip or Actions menu. The center shows source/destination, state, per-file results, transferred bytes and average speed while active. Queue entries can move earlier/later, pause, resume or cancel. A paused active transfer holds the current operation; paused queued entries allow other ready jobs to proceed.

Pause waits at a stream boundary in the current process. It does not promise seek-based resume after process death or make an unresponsive provider support pausing. Cancellation closes registered streams and wakes paused workers. Fingerprint verification is not yet interruptible, so a cancel during that phase can take longer; making verification cancellable remains in the roadmap. Android foreground-service limits still apply.

The notification has pause/resume and cancel actions for queued transfers. Notification permission is optional; an explicit Enable notifications button requests it or opens system notification settings. Browsing does not require that permission.

## Durable results and interruption review

Jobs, sources, destination and per-file outcomes are written through a versioned atomic journal with a previous snapshot. The journal checkpoints the in-flight source before copying and the published destination afterwards. A journal-write failure blocks new queued work until saving succeeds. An unreadable journal can be preserved under a unique damaged filename before explicitly resetting the history.

Unfinished jobs become **Interrupted — review destination** on a new process launch. They do not automatically replay. Review destination and Retry remaining are explicit actions. Retry excludes published destinations and skipped items, and uses Keep both rather than replacing existing files. Publication may precede its result checkpoint during a process death; retry explains that ambiguity. A move whose destination exists but source removal failed is recorded for review and excluded from blind retry.

Completed job history survives a process restart. Undo/Redo remains session-based in this release. Clearing finished transfer records does not remove files or discard the current session's Undo history.

## Conflicts and replacement

The default cross-folder conflict behavior asks what to do. Preferences can select Ask each time, Keep both or Skip existing. Copying an item into its own folder retains the familiar unused-name behavior.

The conflict dialog compares incoming and existing type, size, modification time and location. Choices are Keep both, Skip, Rename incoming, or Replace with recovery. Keep both, Skip and Replace can apply to remaining conflicts. A rename must be a valid filename, and exact-name copy refuses a collision.

Source capabilities, destination access and invalid parent/descendant moves are checked before prompting or moving an existing item to recovery. If the destination's displayed metadata changes while choosing Replace, the operation stops for review.

Replacement stores the original in recoverable Trash. A dependent history step swaps the new item and recovery copy during Undo/Redo; copy and move replacements protect subsequent edits through fingerprints. Failure to build an Undo record is reported without blindly retrying the already-published file. A failed copy restores the original when possible; partial publication keeps recovery copies and reports the result for review.

Folder merge is not implemented yet. Replacing a folder replaces the complete folder with recovery; it does not merge its members. Per-file source changes made concurrently by another app are not filesystem snapshots.

## Interaction consistency

Selection commands stay in stable toolbar positions during a transfer. Copy/cut, selection clearing, Peek, ZIP browsing, sidebar/preview controls, keyboard navigation and menus remain available. Mutating commands that do not use the queue stay disabled or guarded. Internal drag targets accept queued transfers; external URI imports retain their existing serialized behavior.

Operation completion schedules the refreshed listing before reporting idle. Tests wait for expected visible results where a filesystem write can precede UI refresh. Multi-window mouse tests inject platform mouse events and activate dialog buttons through their semantic click action, avoiding mixed synthetic input streams during window transitions.

## Validation

Verified locally on 2026-10-31: **54 JVM tests and all 49 Android workflow tests passed**, with no failures or skips. Android lint completed with zero errors; existing dependency/API/style warnings remain. The complete Android suite passed together in one run. A final checkbox alignment adjustment is checked separately with the five conflict tests.

The optimized development-signed preview and unsigned release build successfully. The packaging script verifies signature and manifest properties and writes SHA256SUMS and BUILDINFO.json. Existing GitHub-hosted runners remain blocked by account billing; no hosted CI success is claimed.

Native tablet screenshots with synthetic test files:

- [Paused transfer with queued jobs](screenshots/transfer-center.png)
- [File conflict choices in the optimized preview](screenshots/file-conflict.png)
- [Completed transfer history in the optimized preview](screenshots/transfer-history.png)

The final optimized APK installed and cold-launched successfully. Native checks copied a synthetic document to another folder, triggered a second-copy conflict, chose Keep both, verified both filenames in the destination, reviewed both completed jobs, and granted optional notification permission through the real Android prompt. The permission banner disappeared afterwards. AndroidRuntime logged no errors. The paused-transfer screenshot uses the debug fixture provider; other screenshots use the optimized preview. [Download 0.5.0-preview](https://github.com/moikdev/findroid/releases/tag/v0.5.0-preview).

Added tests cover journal round-trip/backup/reset, Unicode paths, partial publication, pause/resume/cancel, exact-name copies, queued provider transfers, notification Pause, queue reorder/cancel, retrying only failed items, copy/move replacement Undo/Redo, changed-file protection, rename/skip-all, changed destinations and descendant rejection.

Installable APK: 2618047 bytes; SHA-256 `52decd57d5b0882b7f38e8a08cddacb5cfc83232cdf287a5b91238e48709c167`. Version code 5, version name `0.5.0-preview`.
