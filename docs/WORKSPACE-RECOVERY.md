# Open a saved layout when storage has changed

Findroid 0.34 checks the folders used by a saved workspace before opening it. An available layout opens immediately. If a destination is missing, Findroid shows which tabs use it and lets you reconnect storage or choose replacement folders.

Use **More → Workspaces…** and open a named layout. The recovery flow stays inside Workspaces and adds no permanent toolbar controls.

## Repair only the destinations that need it

1. Review **Unavailable locations**. Each entry names the affected pane and tab. Several tabs using the same unavailable destination share one choice.
2. Choose **Connect storage folder…** if a document-provider grant needs reconnecting, then **Check locations again**. A destination that has become available keeps its original reference.
3. Otherwise choose **Choose replacement folder…** for the affected entry. Read-only folders are supported because opening a layout does not write to those folders.
4. Choose **Review locations**. Review each old-to-new mapping and any locations that remain unavailable.
5. Choose **Open once** to use the repaired layout for this session, or **Save repairs and open** to remember the repairs in that named workspace.

If you want to open with a location still unavailable, explicitly choose **Keep these locations unavailable for later repair**. The affected tab reports its ordinary storage error; Findroid does not create a missing folder or silently substitute Workspace. Cancel leaves the current panes and saved layout unchanged.

A save updates only the chosen saved workspace. It does not move files, remap Favorites, edit tags or collections, change another saved layout, or reconnect Android access automatically.

## What stays in place

| Part of the workspace | Recovery behavior |
| --- | --- |
| Working tabs | Their destinations, history, filters and navigation state stay unchanged. |
| Repaired tabs | Keep their tab identity in the saved layout, pin, query and filters. Stale selections, expansions and the old destination's scroll/budget state are cleared. |
| Pane layout | Keep active tabs, view settings, ratio, reversed order and active pane. |
| Old back/forward history | An unrelated missing historical destination does not block opening a currently available tab. |
| Hidden secondary pane | A single-pane layout checks its visible tabs; its stored hidden secondary pane remains intact. |
| Existing tags and collections | Their library destinations remain valid. Missing member files still follow the normal organization reference behavior. |
| Missing tag/collection/Smart Folder definition | The unavailable tab can be reviewed and replaced by a real folder. |

For an existing Smart Folder, Findroid also checks its search root. If that root is unavailable, a replacement opens the same filters on the chosen folder. The tab's extra query remains active. The shared Smart Folder stays unchanged, so another workspace using it is unaffected.

## Rechecking and saving

The choices and review survive Activity recreation. The final action rechecks original and replacement availability, the saved workspace, and relevant Smart Folder definitions. A changed grant, reconnected destination, edited layout or changed search requires a fresh review.

Saving waits for file operations and other workspace writes to finish. The shared workspace writer publishes the repaired catalog through its existing AtomicFile store before opening the layout. Other workspace mutations are blocked during that publication. If saving fails, the previous in-memory layout is retained and **Open once** remains available. A catalog changed on disk or using a future format is preserved instead of being overwritten by a stale review.

This is separate from [setup backup and restore](SETUP-BACKUP.md), which imports several setup sections and maps storage roots between tablets. Workspace recovery changes a specific saved tab destination; it does not infer relative provider IDs or migrate an entire storage tree.

## Repeatable acceptance

`docs/fixtures/workspace-recovery.json` contains only synthetic locations under `/storage/FINDROID-QA-LAYOUT`. The optimized host check maps them into its own temporary Workspace root, preserves original metadata/preferences, and uses native finger input on Android 11:

```sh
python3 scripts/check-workspace-recovery.py \
  --serial <dedicated-tablet> --apk <0.34-preview.apk> \
  --previous-apk <0.33-preview.apk> --output <evidence-directory>
```

The check exercises the actual Workspaces row, folder picker, Open once, saved repairs, restart, reopening, enlarged text, original file bytes, other-layout preservation and unrelated preferences. [VALIDATION.md](../VALIDATION.md) records completed results. Physical tablets, USB/SD removal, TalkBack and actual third-party providers remain separate acceptance work.
