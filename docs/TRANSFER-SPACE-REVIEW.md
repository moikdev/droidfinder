# Review space before copying or moving

In **Copy to…** or **Move to…**, choose a destination and select **Review space and alternatives…**. The review reads file metadata and reported free capacity before you start a transfer.

The destination path, selected count and conflict policy stay visible. **Choose another destination…** opens the folder chooser and recalculates the plan for that location. **Refresh space estimate** checks again. **Close** leaves the documents in place. The destination chooser scrolls its content, and folder rows have at least 48 dp touch targets.

## Read the budget

- **New destination contents** counts the incoming file sizes. An unfinished working copy becomes the published copy, so its payload is counted once.
- **Possible replacement recovery copies** allows for preserving matching destination originals in app storage. Asking, merging, skipping or a supported rename can need less than this conservative allowance. Repeated incoming names include an allowance for a later item replacing an earlier copy.
- **Reported free space** comes from the selected location. A provider that does not report capacity stays unknown. Free space from different locations is never added together.
- **Shortfall for this allowance** shows how far the reported capacity falls below the known budget. Unknown sizes and incomplete listings remain visible as lower bounds.

Local destinations and recovery copies share one budget only when the same storage-volume UUID is observed. If the physical relationship of provider and recovery storage is unknown, the review says that their combined demand has not been proven to fit.

Moves budget a full copy fallback. The review never treats a future source deletion as free space that already exists. Directory entries, allocation units, filesystem journals, provider overhead and other concurrent activity can require additional room.

## Starting the transfer

Choose **Copy to destination** or **Move to destination** to submit the reviewed sources and destination to the existing transfer engine. A known allowance shortfall disables that action until you choose another destination or refresh after freeing space. An unknown amount or capacity is explained without inventing an exact total.

This is a point-in-time review, and it does not reserve space. The transfer engine checks the current files, conflicts and available capacity as it works. Conflict decisions still happen in their existing review, and transfer results remain in **More → Transfers**.

## Development status

Proposal 45 remains in progress. Frozen candidate 2 passes **930 JVM cases**, including 36 focused space-planning cases, with no failures, errors or skips. Lint reports 0 errors, 148 warnings and 12 hints. All 395 source inputs match public commit `bf1ae1858bb071b92d42ac02f1057a82011cf00c`; all 104 frozen build/report outputs are unchanged.

Both assigned API30/API36 tablets now have the same **549 accepted native cases in 59 isolated batches**, including the 17 focused metadata and interaction cases and the destination row's 48 dp assertion. On API36, the first external-drag case hit Android's activity-launch setup timeout. The entire unchanged five-case final batch passed after recovering the assigned AVD; its earlier failed report is retained and excluded, and the first 58 accepted reports were reused without changing their bytes. All original Workspace bytes, managed data, URI grants, settings and exact public 0.47 APK were restored.

Both tablets pass all four exact optimized core checks, all 12 size/font/theme layouts, actual 240/300 dpi checks, complete 48 dp named controls and native text/glyph growth in all six groups per API. All 112 selected local image paths have been inspected. The first API30 layout attempt stopped when the checker could not horizontally reach a clipped browser action; a later device disconnect required separate, verified restoration. Those partial layouts remain excluded. The application/native source and frozen build bytes are unchanged. Actual public 49→50 OTA passes on both tablets; all 12 accepted OTA screenshots were inspected. Independent immutable-archive acceptance remains pending.

The earlier candidate was stopped between batches after code review found 44 dp rows in the existing destination chooser. Its original managed bytes, Workspace, grants and exact public 0.47 APK were restored on both tablets. Its application and frozen output bytes remain intact in a separate checkout; its partial regression is excluded from final accepted totals. Candidate 2 adds a scrolling chooser and complete 48 dp folder rows.

The first API36 public OTA check stopped before downloading because an automatic available-update dialog already covered the browser toolbar. The checker now accepts a fresh hierarchy containing the update dialog and its Installed label. A complete public 49→50 retry passes; the earlier failure and exact helper sources remain retained and excluded. Final controllers restore original bytes/absences, Workspace, grants and settings with the exact public 0.48 APK.
