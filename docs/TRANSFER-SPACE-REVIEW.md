# Review space before copying or moving

In **Copy to…** or **Move to…**, choose a destination and select **Review space and alternatives…**. The review reads file metadata and reported free capacity before you start a transfer.

The destination path, selected count and conflict policy stay visible. **Choose another destination…** opens the folder chooser and recalculates the plan for that location. **Refresh space estimate** checks again. **Close** leaves the documents in place.

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

Proposal 45 is in progress in a separate checkout. A development build passed 930 JVM cases, including 36 focused space-planning cases, and 17 focused metadata and interaction cases on each assigned API30/API36 tablet. Both controllers restored original bytes, Workspace, grants and the exact public 0.46 APK. Those runs are draft evidence; final source binding, full native regression, exact optimized layouts/upgrades and public release acceptance remain pending. This guide describes the candidate behavior and does not mark the proposal complete.
