# Reviewed folder synchronization — implementation in progress

Proposal 43 adds a one-way review to folder comparison. This branch contains the planner, read-only inspection, working-copy preparation and a complete guarded apply engine. Its tablet interface, lifecycle/metadata/Undo integration, complete-worker native acceptance and a release remain pending. The feature is not exposed in the app yet.

The planned review separates additions, proposed updates, conflicts, destination-only items and unchanged items. Additions are initially selected. Updates require a separate replacement confirmation; moving destination-only items to Trash requires its own confirmation. Neither timestamps nor file sizes choose which copy should win. Different file/folder types remain a manual conflict.

A newly added subtree is one selectable operation with every descendant disclosed. Preparation materializes exactly those reviewed nodes. It never recursively copies a live directory after review, so a file added later cannot silently join the operation.

## Inspection and preparation

Inspection supports separate, available local folders. It compares complete native snapshots, rejects aliases and overlapping roots, refuses links and special files, and checks original file contents with SHA-256. The comparison must include every item in both native snapshots, including hidden files. Regular-file sizes come from the native snapshot. Incomplete, ambiguous, oversized or changed comparisons cannot prepare a plan.

Reads use pinned regular-file descriptors with non-following and nonblocking opens. The code checks identities, exact lengths, end-of-file and content hashes. Both original folder snapshots must still match after the reads finish. The review holds the original proofs and digests; reopening a review does not invent replacement identities for them.

Preparation creates a new owned `.findroid-sync-…` directory beside the destination folder. That location must support preparation on the destination filesystem. An existing working directory is never reused. An immutable `review.json` records the selected operations, confirmations, original proofs and checksums before any working copy is made. `prepared.json` records the completed staged roots.

Working copies are saved and read back before they can pass the preparation gate. Partial writes, rewrites, renamed parents, changed permissions, newly arrived files, occupied working names and readback mismatches stop preparation. Original source and destination trees remain in place. On interruption, cancellation or failure, the owned record and working data remain available for manual review. There is no automatic replay or recursive cleanup.

The final read-only gate verifies the original folders, their contents and every prepared copy again. Later edits, occupied destination names and changes to working files invalidate the prepared plan.

Draft transition checks also validate the exact membership, identities and hashes of relocated trees. The complete apply worker validates both the declared destination-tree change and the relocated contents. Directory timestamp changes are limited to the affected ancestors; an unrelated edit or newly arrived file cannot become a fresh baseline for later operations.

The draft also has a dedicated original-preservation primitive. It requires same-filesystem native no-replace rename, records the exact original path before the move, and verifies the relocated tree against its old identities and content hashes. It refuses a recursive-copy fallback for these originals. A failure after a completed rename reports the preserved Trash item and its durable mapping instead of hiding the partial result. Its JVM tests cover collisions, changed reviews, last-moment edits and post-move faults; Complete-worker Android and model integration remain pending.

The draft publication primitive moves one fully prepared root with native no-replace rename. It requires a durable intent callback outside the reviewed trees, checks the staged bytes and both exact trees again after that callback, and validates the resulting destination and working-tree transitions. A collision keeps the arriving file and the staged copy. An error after publication keeps the completed result and reports its destination for manual review. The complete worker composes this primitive; it is not an automatic recovery path.

The draft publication journal allocates a fresh app-private run directory outside the compared and working trees. Each intent, completed publication and run completion gets a new immutable record, synchronized and read back before it is accepted. A pending intent prevents another publication. Partial writes, unexpected files, rewritten records, redirected parents and a readback mismatch close the writer and preserve its existing bytes. Existing run directories are never reopened for writing. The journal contains manual evidence only; it has no replay, rollback, pruning or deletion API. The original-preservation primitive supplies its exact original and Trash target to the journal after the original-path mapping is durable and before the native move; it rechecks the original after that callback. Failed intent recording prevents the original from moving. Preservation and publication completions cannot be confused or interleaved while a move is pending. The complete worker budgets and sequences these records before changing originals.

The draft Trash preflight checks all originals in a selected plan before any recovery bucket is made. It rejects cross-filesystem or changed originals, overlapping selections, unknown or insufficient recovery space, and a projected complete original-path mapping above the existing 16 MiB limit. A returned recovery location can be pinned through the later original-preservation move, so a replaced Trash root is refused. The complete worker runs this preflight for all selected originals before its first change.

## Complete apply engine

The complete-plan worker now connects the preserved-original and publication primitives. Before creating its journal or moving an original, it verifies the entire prepared plan, recovery capacity and mapping budget, and the complete journal capacity. Each replacement needs both a preservation and a publication pair of records. Boundary tests cover the 20,002-record limit, 64 KiB per-record limit and 32 MiB total, including UTF-8 and JSON escaping.

Between moves, and again after each durable intent, the worker requires the source, destination, working copies and every preserved original to match their expected trees. Only the declared move can change the next expected tree. Recovery mappings are checked against the actual pinned, bounded, non-following on-disk primary index as well as memory. This does not claim an atomic compare-and-swap against every uncooperative external index writer.

Completed roots, original recovery paths and pending native move intents remain in an explicit returned failure or cancellation state. A callback or metadata-flush failure cannot hide a move that already happened. The worker neither rolls files back nor resumes an old journal. Model callbacks must still provide metadata and history integration before this is exposed to users.

The complete apply engine passes 833 JVM cases with zero failures, errors or skips; lint reports zero errors, 147 warnings and 12 hints. These branch totals are separate from the accepted 0.45 release. Local source/report evidence is preserved in `/tmp/findroid-reviewed-sync-complete-apply-evidence-1.zip`; this local archive is not a public release validation package.

## Work still required

- Integrate metadata, the transfer lifecycle and appropriate Undo behavior.
- Add the named, scrolling tablet review and completion controls without adding permanent toolbar icons.
- Exercise actual Android descriptors, stale reviews, cancellation, collisions, process death and recovery on both assigned API levels.
- Inspect the optimized tablet interface and complete public release and OTA acceptance.

JVM coverage currently includes planning, inspection, digest reads, working-file staging, complete subtree preparation, the final read-only gate, original-preserving Trash rename and guarded publication. Complete device and release acceptance have not been claimed for this draft.

Four Android foundation cases now compile against actual descriptor and native rename implementations. They cover disclosed subtree publication, preserving an updated original in Trash, a kernel destination collision and an original edited during intent recording. All four pass on each assigned API30/API36 tablet with the same exact branch app/test APK pair, zero failures or skips. The immutable original data, URI grants and storage/font/display settings were restored under each tablet reservation; API30 returned to public 0.44 before its real OTA, and API36 returned to exact public 0.45 after its real OTA. The complete apply worker and UI/release acceptance remain pending. These draft executions are separate from the 0.45 release totals.
