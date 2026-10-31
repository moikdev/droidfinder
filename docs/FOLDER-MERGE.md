# Folder merging and durable member recovery — 0.6.0

This increment extends the transfer foundation in 0.5.0. The [full roadmap](ROADMAP.md) remains in progress; this release does not complete it.

## Merge folders

When an incoming folder has the same name as an existing destination folder, choose **Merge folders**. Existing matching folders stay in place, destination-only files stay untouched, and matching files use the conflict dialog: Keep both, Skip, Rename incoming, or Replace with recovery. A Merge choice applied to remaining conflicts merges folders and still asks about files. Preferences also offer **Merge folders; ask for files**.

The complete merge plan is saved before any member is written. Existing folder pairs are expanded recursively. A new subtree is copied as one staged unit, including empty folders. This keeps Android document-provider parent identities stable through Undo/Redo. Symbolic folder links, cycles, ambiguous duplicate destination names, excessive depth and oversized plans are rejected. Planning is bounded to 50,000 inspected source entries and 128 levels; content verification is bounded to 100,000 entries and 128 levels.

## Move and Undo

Move-merges first copy their planned units. Before source cleanup, content hashes verify the unchanged source tree and successful destination copies. A complete move-merge sends the source root to recoverable Trash. If members were skipped or failed, only successfully copied source units go to Trash; the source root and existing source parent folders remain.

Undo restores source units before undoing destination copies and replacements. Redo reapplies those changes. Existing destination folders and unrelated files survive both directions. Explicit recovery mappings carry tags and folder appearance back from Trash during Undo, including provider restore identities, and return them to recovery on Redo. Changed-file checks stop Undo/Redo before modifying later edits. Replacing a destination folder that contains the incoming source is rejected before touching either location.

If a provider fails while removing a source, a completed recovery backup stays in Trash. The transfer records its location and blocks destructive Undo for that command, so Undo cannot remove the only remaining destination copy. Review the source, destination and Trash before further changes. These checks do not provide a filesystem snapshot or eliminate races with edits by other apps.

## Durable results and retry

Transfer history now uses a normalized SQLite database, with transactional checkpoints and incremental updates to changed rows. Existing 0.5 Properties journals migrate once in a transaction; the originals remain in app storage. Unreadable database and journal files are preserved for explicit recovery rather than silently deleted.

Expand **Show files** in Transfers to see each planned unit, destination, error, source-cleanup state and recovery location. Large member lists initially show 50 units, with a Show more action.

**Retry remaining** submits only unfinished, unpublished units to their original destination parents. It excludes skipped units, recorded destinations, failed source cleanup and the ambiguous in-flight unit from an interrupted operation. It never retries an entire partially merged root. Retries use Keep both and preserve completed work. Retrying units does not automatically remove leftover empty source parent folders. Review ambiguous items manually; no work is automatically replayed after process death.

Completed transfer history and merge plans survive restart. Undo/Redo remains a 50-command session history. Persistent Undo, byte-offset resume and universal provider cancellation are still pending. Merge verification checks cancellation between entries and chunks and closes registered streams; a provider's metadata query or stream open can still block.

## Verification

Verified locally on 2026-10-31: **61 JVM tests and 69 Android tests passed together in the full release run**, with zero failures or skips. A file/folder type-conflict case and three tag-recovery cases bring verified Android coverage to 73 distinct tests. After the tag-recovery adjustment, all 27 affected merge/provider/conflict tests passed together. Lint completed with zero errors and 59 warnings. Optimized preview and unsigned release APKs built successfully. Checks use the dedicated `DroidFinder_Tablet_API_36` emulator; GitHub Actions is manual-only and no hosted runners were used.

Coverage added for normalized database migration/order/incremental updates/rollback/corruption preservation, framed tree hashes and blocked-stream cancellation, nested/empty merges, local/provider combinations, copy and complete/partial move Undo/Redo, source/destination edits, simulated partial provider removal, ancestor replacement rejection, cancellation and nested member retries through the native UI.

The debug fixture provider and its failure injection are excluded from optimized preview/release APKs. Real third-party accounts, physical tablets, USB and SD remain external validation items.

[Merge choice in the optimized preview](screenshots/merge-choice.png) · [Native transfer details](screenshots/folder-merge.png). [Completed merge in the optimized preview](screenshots/merge-history.png). All use synthetic QA files; the partial-result screenshot comes from the Android workflow test.

The optimized preview installed and cold-launched successfully. Native tablet controls merged synthetic project folders, chose Keep both for a matching note, verified the incoming note in Peek, and exercised Undo/Redo. Undo kept the original and removed only the merged copies; Redo restored them. The transfer center showed the completed root and both durable member results. AndroidRuntime logged no errors during these checks.
