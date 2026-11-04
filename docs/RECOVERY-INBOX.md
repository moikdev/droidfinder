# Recovery inbox

Proposal 44 is in progress. This draft is not included in the public 0.46 preview. Final release, optimized restart/layout checks and public OTA acceptance remain pending.

Open **More → Recovery inbox…**, or search for “recovery” in **Find an action…**. The inbox brings saved evidence into one place and explains the next step for each item. Opening or refreshing it never resumes a transfer, restores an original, adopts a changed file or removes working data.

## What appears

- Interrupted, failed and cancelled transfers, including uncertain in-flight sources and saved partial-copy checkpoints. **Review transfer** opens the chosen existing job and its details. **Review saved copy** opens the separate source/working-file checks before any resume or discard decision.
- Originals preserved in Findroid Trash, including copies saved before replacement. **Review Trash** opens the existing reviewed restore flow. An occupied original name stays protected.
- Immutable synchronization intents, with their recorded source, destination, working folder and manual journal. An intent does not prove that a run finished. **Review synchronization records** opens the read-only records screen.
- Damaged transfer or saved Undo histories, with links to the existing reviewed reset screens. These resets preserve their old journals and remain separate decisions.
- Earlier operation notices, including operations without a transfer job. **Review destination** opens the recorded location after checking allowed storage access. A recorded path does not prove that its files still exist or that an operation completed.

Use the filters to focus on transfers, preserved originals, synchronization or records needing attention. Recorded locations expand only when requested and can be selected as text. **Review storage access** opens the storage screen when a location needs reconnecting. A missing job or busy saved-copy review keeps the inbox open and explains what needs to be refreshed.

## Notices stay available after dismissal

The startup notice is preserved byte for byte in app-private recovery storage before it is acknowledged. Its acknowledgment identifies the exact notice shown; it cannot suppress a later operation. Dismissed notices remain discoverable in the inbox after reopening. A new operation preserves the previous marker before replacing it. A failed operation keeps its marker and an immutable copy; a successful operation removes only its own unchanged marker.

If a notice cannot be preserved safely, the old marker stays and a new operation stops before changing documents. The foreground service still closes. The startup screen offers **Review without acknowledging** so the user can inspect the available evidence. No acknowledgment is saved by that choice. Linked, special, oversized, changed or damaged records stay in place for manual review. This increment does not automatically repair app-private records.

## Storage and tablet limits

The inbox reads the existing transfer/Undo histories, Findroid Trash, synchronization records and operation notices. It does not search every destination for untracked working files or scan remote providers. While an operation is active, it defers Trash and synchronization listing; refresh after the operation settles. A running operation's marker is excluded from interrupted-operation notices.

A notice is at most 64 KiB. A notice scan stops at 10,000 entries or 8 MiB of record bytes, explains that limit and leaves every remaining file untouched. A source that cannot be listed produces its own notice; other available sources remain visible. Records are never automatically pruned.

The whole inbox and startup notice scroll, including their named controls. Filters and actions wrap at enlarged text sizes, with at least 48 dp control heights. No permanent toolbar button is added. The strict primary pane owns recovery state and actions even when pane order is reversed.

## Draft verification

The current draft passes **894 JVM cases**, with zero failures, errors or skips; 43 focused recovery cases are a subset. Both assigned API30/API36 tablets pass **27 focused Android cases**, with zero failures or skips. These include exact marker preservation, primary ownership, safe existing-job/saved-copy links, independent source failures, service closure after a preservation failure and measurable Compose title enlargement. All four selected dialog images were actually inspected. These composition-font tests do not substitute for actual Android system-font acceptance.

The first draft run retained 21 passing cases and two failures per tablet: hard-link creation returned EACCES before app review, and the title did not enlarge. A measurement recheck retained 22 passing cases and confirmed that the dialog needed to carry the inherited density. Both recovery dialogs now do so, matching the established synchronization dialog. The final focused run passes all 27 cases. Each attempt retained its results and restored original managed files, Workspace trees, URI grants and settings, leaving the exact public 0.46 installed. Actual app hard-link refusal is covered by the JVM fixture where links can be created. On the assigned tablets Android refuses fixture link creation; the Android check verifies either that platform refusal or the app's guarded refusal according to capability.

The complete native regression, optimized cold-process/restart and actual system-font/layout acceptance, public OTA and independently verified immutable archive remain pending. Proposal 44 remains open.
