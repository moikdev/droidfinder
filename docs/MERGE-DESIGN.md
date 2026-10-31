# Folder merge implementation notes

Status: implemented and locally verified for 0.6.0. See [the user-facing workflow and limitations](FOLDER-MERGE.md). The notes below describe the design; validation claims belong in the release record.

## Durable member plans

A merge needs a complete plan before writing members. Recursively pair source and destination directories that already exist. A source child whose destination directory does not exist is copied as one staged subtree; only existing directory pairs are recursively merged. File/type conflicts use the existing conflict resolver.

Persist root plans and per-member source, destination parent, outcome and cleanup state. Interrupted retries select unfinished members with their original destination parents, rather than copying the entire root again. An ambiguous publication remains review-only. Empty merges have an explicit plan record, so no-member and unplanned roots are distinguishable.

## History and move semantics

New subtrees are whole-copy history steps under existing destination parents. This keeps provider parent identities stable through Undo/Redo. Replacement uses the existing dependent replacement step.

For a successful complete move-merge, copy members first, verify that the source tree has not changed, then recoverably Trash the original source root. Record its Trash step last, so Undo restores the source root before undoing target copies/replacements. Existing destination folders are preserved.

For a merge with skipped/failed members, recoverably Trash only successfully copied source units; keep the source root and all existing source parent directories. This avoids dangling provider-parent identities in history. Errors after publication retain destinations/recovery copies and prevent blind retry. Source cleanup failures must guard Undo so it cannot remove the remaining good destination copy without source recovery.

## Storage and cancellation

Move job persistence from whole Properties snapshots to normalized SQLite rows before adding large member plans. Migrate 0.5 jobs transactionally, retain the legacy journal, and preserve corrupt database/legacy files rather than deleting them. Use incremental result/member updates, foreign keys, stable ordering and explicit transactions.

Make fingerprint/digest traversal cancellable where it participates in merge planning and source-cleanup verification. Do not claim resumable byte offsets or provider snapshots. Keep a clear distinction between copied members and completed source cleanup.

## Required checks

Local and fixture-provider copy/move merges; nested existing folders and new subtrees; empty folders; file/type conflicts; skip/replace/rename; complete and partial Undo/Redo; edits after the operation; source changes while copying; cancellation; process-interruption plans; retrying only unfinished members; SQLite migration, ordering, rollback and corruption preservation. Expand the existing tests rather than replacing safety coverage.
