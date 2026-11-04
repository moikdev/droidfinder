# Recovery inbox

Open **More → Recovery inbox…**, or search for **Recovery inbox** in the action launcher. The inbox brings interrupted work and preserved originals together, with a named next step for each record.

## What you can review

| Recorded situation | Next step |
|---|---|
| A failed, cancelled or interrupted transfer | **Review transfer** opens that transfer first, with its details expanded. Check completed files, errors and the destination before deciding what to do. |
| A saved partial copy | **Review saved copy** opens the existing saved-byte checks. Resuming and discarding each require their own review. |
| A folder merge that kept its source | Review the transfer and the recorded locations. Read the per-item results before retrying. |
| An original preserved during replacement | **Review Trash** opens the preserved originals. Restoring remains a separate action. |
| An interrupted folder synchronization | **Review synchronization records** opens its saved intent, source and destination. Review the published files and any prepared copies manually. |
| An earlier operation notice | **Review destination** opens its recorded folder. **Review transfers** opens the transfer center when transfer records are available. |
| Damaged transfer or Undo history | Open the existing history review or **Review storage access**, as offered by the record. Other readable sources remain available. |

Use the filters for transfers, preserved originals, synchronization, or records needing attention. **Show recorded locations** reveals complete paths so identically named folders are easier to distinguish. **Refresh records** reads the available sources again.

Opening or refreshing the inbox reads records. Starting a transfer, resuming a saved copy, restoring an original and removing saved bytes each remain separate actions. A missing transfer or an expired saved-copy link leaves the inbox open with an explanation.

## Returning after an interruption

A startup notice offers **Review files**, **Open recovery inbox**, and **Later**. Acknowledging it preserves its exact recorded contents before dismissing it. It remains discoverable in the inbox after restart, and a later successful operation keeps the earlier notice.

If the notice cannot be preserved safely, the app explains the problem. **Review without acknowledging** opens the inbox while keeping the notice available on the next start. A new document operation stops if it cannot preserve the previous notice first.

Review the recorded destination before retrying an interrupted operation. Completed documents and hidden `.findroid-*` working data may both be present. Findroid leaves the decision to retry, resume, restore or remove saved data to you.

## Storage and scope

Recorded locations can refer to disconnected storage. **Review storage access** opens the connection controls. The inbox reads saved app records; it does not search every destination for untracked working files or scan remote providers. While an operation is busy, it defers the Trash and synchronization scans and explains that they can be refreshed afterward. An actively running operation's marker is excluded from interruption notices.

Notice records are limited to 64 KiB each. A notice scan stops at 10,000 entries or 8 MiB of record bytes, explains the limit and keeps the remaining records. Linked, changed, special or damaged records stay available for manual review. Records are never automatically pruned.

The inbox does not turn every interrupted copy into a resumable transfer. Saved-copy resumption retains its documented [local-file and destination limits](PARTIAL-COPY-RESUME.md). Folder synchronization retains its [supported-storage and manual recovery limits](REVIEWED-FOLDER-SYNC.md).

## Validation status

The accepted public 0.47 preview passes 894 JVM cases with zero failures, errors or skips, and the same 532 distinct native cases in 57 fresh-process batches on each assigned API30/API36 tablet. Its 43 focused JVM and 27 focused Android recovery cases are subsets of those totals.

Both exact optimized previews pass direct public 0.46→0.47 upgrade, cold-start notice retention, retention after a later successful operation, and refusal to start document work when the earlier notice cannot be preserved. Each tablet passes 12 size/font/theme layouts, actual 240/300 dpi changes, complete 48 dp controls, and independently measured native text and raster-glyph enlargement. All 89 selected local image paths were actually inspected, including the separately checked README duplicate.

Android denies fixture hard-link creation on both tablets. Native acceptance verifies platform refusal or reader refusal according to that capability; actual multiple-link reader refusal is demonstrated by JVM fixtures. Actual public 0.46→0.47 download and Android installation pass on both tablets. Synthetic file bytes, Favorite and unrelated preference survive, and neither tablet offers the update again. Original data, Workspace and grants are restored with the exact public 0.47 APK. Independent anonymous verification of the immutable 902-file validation archive also passes, including every entry hash, public source/evidence bindings and recorded JVM/native totals. All six public assets match anonymous bytes and GitHub digests. Proposal 44 is implemented. [Validation](../VALIDATION.md) separates accepted results from rejected attempts and device/provider limits.
