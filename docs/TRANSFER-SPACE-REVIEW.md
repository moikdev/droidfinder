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

## Validation

Proposal 45 is implemented in public **0.48.0-preview**, accepted on November 4, 2026. The frozen build passes **930 JVM cases** with no failures, errors or skips and lint **0 errors / 148 warnings / 12 hints**. Both assigned API30/API36 tablets pass the same **549 distinct native cases in 59 isolated batches**, including 17 focused space-planning cases. Both pass four optimized workflows, 12 size/font/theme layouts, actual 240/300 dpi checks, complete 48 dp named controls and native text/glyph growth in six groups per API.

Both tablets pass actual public **49→50 OTA**, installer completion, exact installed bytes, original-file/Favorite/preference retention and no repeated offer. All **124 selected local and OTA image paths** were inspected. Original Workspace, managed metadata/databases/cache and recorded absences, URI grants and device settings were restored; public 0.48 remains installed.

Candidate 1 was stopped safely to improve inherited chooser rows. Retained candidate-2 attempts include a portrait navigation stop, assigned-AVD disconnect recovery, an API36 native setup timeout with the entire unchanged final batch retried, and an API36 OTA navigation stop when the automatic update dialog covered the toolbar. Partial/failed attempts remain excluded from accepted totals, and verified separate recovery preserves original data. Application and frozen build bytes did not change during acceptance.

The immutable validation archive is **13,443,892 bytes**, SHA-256 `568379eadc4786a1ca9e0c086068e5262834934b21e1fdc443cf6d7c32d09d21`. Its 966 listed files plus EVIDENCE.json match the public source and evidence commits. All six public assets match anonymous downloads and GitHub digests. Public API metadata used the configured GitHub CLI after the anonymous API rate limit; the repository was checked as public, and asset downloads remained anonymous.

[Full validation](../VALIDATION.md) · [Release notes](releases/0.48.0.md).
