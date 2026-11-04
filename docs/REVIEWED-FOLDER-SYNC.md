# Reviewed folder synchronization — release acceptance in progress

Proposal 43 extends Compare folders with a complete one-way review. Planning, preparation, guarded apply, metadata, Undo and the scrolling tablet interface are implemented on the `reviewed-folder-sync` branch. The public 0.45 release does not contain this feature. Full regression, optimized-build, cold-process recovery and public OTA acceptance are still required before shipping it.

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

Original preservation requires native no-replace rename on the same filesystem as Findroid's Trash. Cross-filesystem replacements and Trash moves stop before the first original changes; there is no recursive-copy fallback for a live original. Publication also uses native no-replace rename. A kernel collision keeps both the arriving item and the staged copy.

Between moves and after each durable intent, the source, destination, working copies and preserved originals must match their expected complete trees. Only the declared transition changes the next expected tree. Recovery mappings are compared with the actual pinned, bounded on-disk primary index as well as memory. This does not claim atomic compare-and-swap protection against every uncooperative external index writer.

Every intent, completed move and run completion gets a fresh journal record, synchronized and read back. A pending intent prevents another move. Unexpected files, rewritten records, redirected parents, partial writes and readback mismatches close the writer and preserve its bytes. There is no journal replay, rollback, automatic cleanup or pruning. A Workspace-root target receives access only to its allocated working sibling and exact parent, rather than general app-private storage access.

## Validation so far

The integrated source passes **851 JVM cases**, with zero failures, errors or skips. Lint reports **zero errors, 147 warnings and 12 hints**. The complete source and reports are retained locally in `/tmp/findroid-reviewed-sync-integrated-evidence-6.zip` (SHA-256 `4e32d4ce0de077aa8503c948ddfb68243bd7b0c4f7aea1309d75102d1d308e2d`); it is not a release validation package.

The matching debug app/test APK pair passes **20 focused native cases on each API30/API36 tablet**, with zero failures or skips: four original foundation cases, eight complete-worker cases and eight tablet-flow cases. These cover actual descriptors and kernel collisions, original preservation, stale reviews and saved Undo, cancellation, metadata/flush failures, default additions, independent confirmations, direction changes, tags, folder appearance, Favorite restoration, Undo/Redo, primary ownership and scrolling large text.

The font test measures the rendered confirmation label growing from 36 to 67 pixels under an explicitly propagated composition font scale of 2.0. It does not claim an actual system font-setting change. Ten dialog captures were visually inspected. The API36 large-text capture shows a partially visible Apply row at that scroll position; this focused pass is not a complete-bounds layout matrix.

Both tablets started with the immutable original private bytes verified. After testing, their original Workspace trees, managed metadata/databases/cache, URI grants, storage/font/display settings and exact public 0.45 APK were restored. The managed original file counts remain 495 on API30 and 443 on API36. Receipts and cropped synthetic dialog captures are in `captures/reviewed-folder-sync/integrated-api{30,36}-3/`. Private snapshots and APK copies are excluded from publication.

Earlier attempts remain preserved locally. The first run passed 17/19 on both APIs and exposed a real Favorite-routing Undo bug plus a non-normalized tag assertion. The next passed 19/20 on both APIs; its remaining failure was the screenshot test writing its font receipt before creating the owned capture directory. Both were corrected before the accepted 20/20 runs. The final large-text test also requires a measured increase, rather than assuming an inherited density override enlarged the dialog.

These draft counts are separate from the accepted 0.45 release totals. The authorized tracker remains **33/72 complete**, with proposal 43 still in progress until its full release acceptance finishes.

## Remaining acceptance

- Run the full native regression suite against the final versioned candidate.
- Check optimized upgrade, real process death during preparation/publication, cold-process record review, collisions, grant/access changes and supported recovery boundaries on both assigned APIs.
- Check tablet landscape/portrait and split screen, light/dark states, real system font/display changes, and complete named 48 dp controls.
- Freeze the candidate, verify signing and native-library alignment, publish the prerelease and immutable evidence, and exercise the real public OTA on both tablets.
