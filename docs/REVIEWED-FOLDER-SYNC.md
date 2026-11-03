# Reviewed folder synchronization — implementation in progress

Proposal 43 adds a one-way review to folder comparison. This branch contains the planner, read-only inspection and working-copy preparation. Applying a plan, its tablet interface, native acceptance and a release remain pending. The feature is not exposed in the app yet.

The planned review separates additions, proposed updates, conflicts, destination-only items and unchanged items. Additions are initially selected. Updates require a separate replacement confirmation; moving destination-only items to Trash requires its own confirmation. Neither timestamps nor file sizes choose which copy should win. Different file/folder types remain a manual conflict.

A newly added subtree is one selectable operation with every descendant disclosed. Preparation materializes exactly those reviewed nodes. It never recursively copies a live directory after review, so a file added later cannot silently join the operation.

## Inspection and preparation

Inspection supports separate, available local folders. It compares complete native snapshots, rejects aliases and overlapping roots, refuses links and special files, and checks original file contents with SHA-256. The comparison must include every item in both native snapshots, including hidden files. Regular-file sizes come from the native snapshot. Incomplete, ambiguous, oversized or changed comparisons cannot prepare a plan.

Reads use pinned regular-file descriptors with non-following and nonblocking opens. The code checks identities, exact lengths, end-of-file and content hashes. Both original folder snapshots must still match after the reads finish. The review holds the original proofs and digests; reopening a review does not invent replacement identities for them.

Preparation creates a new owned `.findroid-sync-…` directory beside the destination folder. That location must support preparation on the destination filesystem. An existing working directory is never reused. An immutable `review.json` records the selected operations, confirmations, original proofs and checksums before any working copy is made. `prepared.json` records the completed staged roots.

Working copies are saved and read back before they can pass the preparation gate. Partial writes, rewrites, renamed parents, changed permissions, newly arrived files, occupied working names and readback mismatches stop preparation. Original source and destination trees remain in place. On interruption, cancellation or failure, the owned record and working data remain available for manual review. There is no automatic replay or recursive cleanup.

The final read-only gate verifies the original folders, their contents and every prepared copy again. Later edits, occupied destination names and changes to working files invalidate the prepared plan.

Draft transition checks also validate the exact membership, identities and hashes of relocated trees. A future apply worker must validate both the declared destination-tree change and the relocated contents. Directory timestamp changes are limited to the affected ancestors; an unrelated edit or newly arrived file cannot become a fresh baseline for later operations.

The draft also has a dedicated original-preservation primitive. It requires same-filesystem native no-replace rename, records the exact original path before the move, and verifies the relocated tree against its old identities and content hashes. It refuses a recursive-copy fallback for these originals. A failure after a completed rename reports the preserved Trash item and its durable mapping instead of hiding the partial result. Its JVM tests cover collisions, changed reviews, last-moment edits and post-move faults; Android and apply-worker integration remain pending.

The draft publication primitive moves one fully prepared root with native no-replace rename. It requires a durable intent callback outside the reviewed trees, checks the staged bytes and both exact trees again after that callback, and validates the resulting destination and working-tree transitions. A collision keeps the arriving file and the staged copy. An error after publication keeps the completed result and reports its destination for manual review. This primitive is not a complete apply worker or an automatic recovery path.

## Work still required

- Connect guarded native publication to a complete apply worker, with a fresh remaining-plan check between operations.
- Connect the reviewed original-preservation primitive to replacements and separately confirmed Trash items, including recovery-space and filesystem preflight for the entire plan.
- Record completed and interrupted operations without hiding a partially completed result.
- Integrate metadata, the transfer lifecycle and appropriate Undo behavior.
- Add the named, scrolling tablet review and completion controls without adding permanent toolbar icons.
- Exercise actual Android descriptors, stale reviews, cancellation, collisions, process death and recovery on both assigned API levels.
- Inspect the optimized tablet interface and complete public release and OTA acceptance.

JVM coverage currently includes planning, inspection, digest reads, working-file staging, complete subtree preparation, the final read-only gate, original-preserving Trash rename and guarded publication. Device and release acceptance have not been claimed for this draft.
