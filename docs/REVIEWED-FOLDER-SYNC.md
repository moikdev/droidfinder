# Reviewed folder synchronization

Proposal 43 extends Compare folders with a complete one-way review. Planning, preparation, guarded apply, metadata, Undo and the scrolling tablet interface are implemented on the `reviewed-folder-sync` branch. Findroid 0.46 introduces this feature. The final native regression, optimized core operations, real interruptions and font/display acceptance pass on both assigned tablets. The exact 0.46 preview is public. Actual public OTA passes on both tablets. Independent immutable-evidence verification remains pending.

## Review the changes first

Open a local folder in each pane, then choose **More → Compare folders… → Review synchronization…**. The review shows the complete source and destination paths. Choose **Left → right** or **Right → left**; changing direction starts a fresh comparison and clears the old choices.

The review separates additions, different contents, conflicts, destination-only items and unchanged items. Additions are initially selected. A newly added subtree is one selectable operation with every child disclosed below it. Updates require a separate replacement confirmation, and destination-only items require a separate Trash confirmation. Dates never decide which copy wins. Different file/folder types remain a manual conflict.

Choose **Review changes** to see the selected operations, then **Apply changes**. The app saves verified working copies beside the destination before an original can move. Closing the progress screen continues the operation; Stop retains the completed results and working data. A stopped run shows the verified publications, preserved originals and any pending native move intent rather than presenting it as a complete success.

**More → Synchronization records…** lists the immutable run intents saved before preparation. Records include the original source and destination, allocated working directory and manual move journal. A record does not prove completion. Reading it never retries, restores, removes or adopts any file. The destination and Trash remain explicit places to review after an interruption.

## Original preservation and Undo

The shared file-operation lifecycle owns the mutation gate, foreground-service lifetime, cancellation and final metadata save. Navigation and visual pane order do not replace the strict primary model as the operation owner. Tags and folder appearance copy with additions; references to preserved originals follow those originals into Trash.

Completed additions can use supported saved Undo. Runs containing replacements or destination-only Trash moves use the existing dependent, metadata-aware callbacks, so their Undo remains available for the current app session. Preserved originals remain in Trash after restart. A replacement never combines independent Created and Trashed steps whose validation would disagree about the occupied original name. Trash Undo/Redo restores the Favorite and other metadata routes along with the files.

The Undo checkpoint saves references durably while retaining its pending marker until the entire operation succeeds. Only the live writer may finalize that checkpoint. Reopening a pending checkpoint requires manual review and never replays an operation. If saved Undo becomes stale between opening and applying a review, synchronization stops before preparing working data. Partial or ambiguous apply failures retain a review barrier.

## Guarded file operations

Inspection supports separate, available local folders. It compares complete native snapshots, includes hidden files, rejects aliases, overlapping roots, links and special files, and checks every regular file with SHA-256. An incomplete, ambiguous, oversized or changed comparison cannot prepare a plan. Reads use pinned, bounded, non-following, nonblocking descriptors, with identity, length, end-of-file and post-read checks.

Preparation creates one new owned `.findroid-sync-…` directory beside the destination. Existing working directories are never reused. Its durable `review.json` binds the chosen operations, confirmations, native proofs and checksums; `prepared.json` records the saved roots. Preparation materializes only the disclosed nodes, so a file that arrives later cannot silently join a recursive copy. Partial writes, renamed parents, changed permissions, occupied working names and readback mismatches stop the run while retaining the data.

Before creating the apply journal or moving an original, the worker checks the complete prepared plan, original recovery space and mapping budget, and all projected journal records. Each replacement needs a preservation pair and a publication pair. Limits are 20,002 records, 64 KiB per record and 32 MiB total. Capacity tests include mixed plans, UTF-8 bytes and JSON escaping.

Original preservation requires native no-replace rename on the same filesystem as Findroid's Trash. Cross-filesystem replacements and Trash moves stop before the first original changes; there is no recursive-copy fallback for a live original. Publication also uses native no-replace rename. A kernel collision keeps both the arriving item and the staged copy. Some Android shared-storage filesystems reject that native operation. On the assigned Android 11 emulator, shared storage returns EINVAL: the optimized app stops with zero publications and keeps all 160 source files, 160 prepared copies and the manual journal. Workspace uses a supported filesystem on that tablet. This is a real storage limit, not a successful publication test; another device's shared storage may behave differently.

Between moves and after each durable intent, the source, destination, working copies and preserved originals must match their expected complete trees. Only the declared transition changes the next expected tree. Recovery mappings are compared with the actual pinned, bounded on-disk primary index as well as memory. This does not claim atomic compare-and-swap protection against every uncooperative external index writer.

Every intent, completed move and run completion gets a fresh journal record, synchronized and read back. A pending intent prevents another move. Unexpected files, rewritten records, redirected parents, partial writes and readback mismatches close the writer and preserve its bytes. There is no journal replay, rollback, automatic cleanup or pruning. A Workspace-root target receives access only to its allocated working sibling and exact parent, rather than general app-private storage access.

## Acceptance

The exact frozen candidate passes **851 JVM cases**, with zero failures, errors or skips. Lint reports **zero errors, 147 warnings and 12 hints**. Both assigned tablets pass the same **505 distinct native cases in 55 fresh-process batches**, with zero failures or skips in the accepted cases. The 20 focused synchronization cases are part of that regression count.

The exact optimized preview passes five core checks on each tablet: direct public **0.45/code47 → 0.46/code48** upgrade, additions with destination originals untouched, independently confirmed replacement and Trash preservation, cold read-only run-record review and refusal of a name arriving after review.

Each tablet also passes real preparation and partial-publication process death, user cancellation, and access revocation followed by an explicit force-stop. The last case does not measure an automatic permission-gate stop. Source bytes and every published item are checked. Cold manual record review leaves the complete fixture tree and records/journals unchanged. API30 partial publication uses supported Workspace storage; the userdebug emulator's existing root command only observes the owned synthetic fixture, while the app remains unprivileged. API36 partial publication uses shared storage. Each accepted publication interruption retained one published file out of 160.

Each tablet passes **12 combinations** of 1280×800, 800×1280 and 600×800 dp, Android system fonts 1.0 and 2.0, light and dark, plus real display-density changes to 240 and 300 dpi. Interacted named controls have complete 48 dp bounds. Both confirmation labels measurably enlarge. Native Close TextView bounds and actual raster glyph dimensions increase in all six size/theme groups per API. Composition-font native images alone are not system-font evidence.

All **91 selected local image paths** were actually inspected. Optimized public images are cropped to the dialog Surface to exclude the browser backdrop. This is a count of image paths, including glyph crops, not 91 distinct visual states.

Rejected and diagnostic attempts remain retained and excluded. Three native API30 batches passed unchanged whole-batch retries after UI waits failed. One optimized core checker expected a zero-selection button to be enabled. An API36 popup at fractional display density rounds its accessibility window inward by one physical pixel; the corrected checker allows only that boundary rounding and keeps the exact 48 dp target and scroll containment. Initial interruption checkers omitted the passive startup notice or expected shared-storage publication on API30's unsupported filesystem. An explicit diagnostic confirms the real protected stop described above, including all source and prepared bytes. No app source or frozen output changed for those retries.

Every local section verifies the immutable original managed bytes, original Workspace tree, URI grants, storage mode, font/display settings and exact public 0.45 restoration. Actual public 47→48 OTA passes on both tablets, including the Android installer, retained file bytes/Favorite/sentinel and no repeat offer. All 12 OTA images were inspected. Final restoration independently checks all 495 API30 and 443 API36 managed files/absences, Workspace trees, URI grants and original settings, then leaves the exact public 0.46 installed. The first API36 OTA invocation timed out opening More; an unchanged whole retry passed. Independent immutable-archive verification is the remaining acceptance step. See [VALIDATION.md](../VALIDATION.md).
