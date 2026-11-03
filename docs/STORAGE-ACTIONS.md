# Understand available file actions

Development draft for 0.41.0. This increment is not yet a published release. The public preview remains 0.40.0 while device and release acceptance continues.

Select files or a folder, then choose **More → Available actions…**. With no selection, the window describes the current folder. The command is also in file and background context menus and the searchable action launcher. Each pane uses its own folder and selection.

The window uses information from the last folder load. Opening it does not contact a storage provider. **Refresh access info** explicitly reloads Android's saved permissions and the current folder in the background. **Storage locations…** opens the existing reconnect and storage-management surface.

## What each action means

| Action | Information shown |
|---|---|
| Read | The local filesystem's loaded read-access result, or Android's saved read permission for a provider document. A saved permission cannot promise that every document will open. |
| Create items | Whether a folder reports support for adding children. A file is not a destination folder. |
| Change contents | Whether a file reports write support. Creating children in a folder is a separate capability. |
| Rename | The selected item's rename support and current loaded access. Delete support does not imply rename support. |
| Delete | The selected item's delete support and current loaded access. Removing a document from one parent is not the same as deleting its original. Connected roots remain protected; remove their connection in Storage locations instead. |
| Move to Trash | Whether Findroid has the deletion capability its recovery workflow requires. Provider items first receive a local recovery copy before the original is deleted. |

**Reported available** means the last loaded information permits the action. The operation checks again before changing files. Providers can change access or reject an operation after a folder was loaded.

**Unavailable** explains an explicit restriction: for example a read-only saved permission, a document that does not advertise rename support, a missing location, or a protected connected root. Relevant blocked commands include the selected name and location in their explanation and offer Copy or Storage locations where appropriate. Busy-operation, empty-selection and Trash recovery rules still take precedence.

**Not reported** means information is missing or has not loaded. Missing, null, malformed, negative or oversized provider capability values remain unknown. They are not described as an explicit read-only grant. Findroid conservatively disables mutations that require an unreported flag.

A provider folder's flags describe that folder. They cannot authorize actions on all its children. An explicitly reported zero means the provider advertises none of the relevant mutation capabilities. A missing saved grant alone does not prove revocation: temporary access can still work.

## Review a mixed selection

Each action counts every selected item as reported available, unavailable, not reported, or not applicable. It displays up to three named examples, prioritizing unavailable items, and includes their location. The counts cover the complete selection. Looking at the explanation does not change the selection.

## Understand provider Trash

Findroid saves a local recovery copy, then asks the provider to delete the original. It checks read access and local free space during that operation. It does not use a provider's own Trash. Restoring copies the data back and may create new document addresses. Documents that do not advertise deletion support cannot be moved through this workflow.

An item already in Findroid's Trash must be restored before editing or renaming. Its action details explain this rather than offering another move to Trash.

## Validation status

The initial 513 JVM checks pass. Real resolver/cursor and browser UI tests cover independent flags, omitted/malformed flags, controlled cached read-only grants, mixed selections, explicit refresh, lost access, named recovery actions and a provider that would block if contacted. Final native regression, optimized public-upgrade/layout checks, real Android grant/revoke behavior, public OTA installation and independent artifact verification remain release gates. Controlled cached-grant tests are separate from actual system-picker permission acceptance.

Physical/OEM, TalkBack and arbitrary third-party provider behavior remain separate acceptance work. No new permanent toolbar icons are added.

Android's capability definitions: [Document columns and flags](https://developer.android.com/reference/android/provider/DocumentsContract.Document), [typed cursor values](https://developer.android.com/reference/android/database/Cursor#getType(int)), and [persisted URI permissions](https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions).
