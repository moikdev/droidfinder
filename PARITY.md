# Finder parity worklist

The target is Finder's general file-explorer workflows on Android tablets. This is an independent app, not a macOS runtime. Each implementation pass must build, exercise the affected workflows, and update this file before the work is considered complete.

Reference behaviors: [Finder organization](https://support.apple.com/guide/mac-help/organize-files-in-folders-mh26885/mac), [batch rename](https://support.apple.com/guide/mac-help/rename-files-folders-and-disks-mchlp1144/mac), [tags](https://support.apple.com/guide/mac-help/tag-files-and-folders-mchlp15236/mac), and [Smart Folders](https://support.apple.com/guide/mac-help/create-or-change-a-smart-folder-mchlp2804/mac).

## Baseline

- [x] Native Android tablet shell, sidebar, back/forward/up, breadcrumbs, tabs.
- [x] Icons, list, columns, gallery, preview pane, basic Quick Look.
- [x] Local copy/move, rename, duplicate, recoverable Trash, ZIP, sharing.
- [x] Recursive name search, Recents, hidden files, favorites, color tags.
- [x] Development-signed installable APK and optimized release build.

## Improvement loop

- [ ] Safe multi-step Undo/Redo for file operations, with changed-file protection.
- [ ] Batch rename: replace, add text, numbered format, collision preview.
- [ ] New Folder with Selection, destination picker, reveal original folder.
- [ ] Custom tags, multiple tags per item, tag management, persistent metadata.
- [ ] Grouping by kind/date/tag, icon zoom, resizable columns and preview pane.
- [ ] Saved Smart Folders with name/kind/date/size/tag/content criteria.
- [ ] Complete PDF navigation/zoom, oriented images, audio/video Quick Look.
- [ ] Mouse context menus in every view, selection ranges, type-to-select.
- [ ] Native drag-and-drop onto folders, tabs/sidebar, and import/export with Android apps.
- [ ] Android document-provider locations, persistable grants, read-only capabilities.
- [ ] Cross-provider/local copy/move, create/rename, provider previews/sharing.
- [ ] Cancellable transfers with byte/item progress, recovery journal and lifecycle handling.
- [ ] Favorites/sidebar management, aliases as portable app shortcuts, folder appearance.
- [ ] Preference controls, folder information, display/extension options, dark theme.
- [ ] Expanded safety and workflow tests, tablet landscape/portrait/small-window checks.
- [ ] GitHub source, build instructions, installable preview release and final verification.

## Platform equivalents and boundaries

- AirDrop is proprietary to Apple's platforms. Android's system share sheet and nearby-sharing targets are the equivalent.
- iCloud and other cloud/network storage are accessed through installed Android document providers. The app will not impersonate an Apple ID login or request a user's cloud password. Direct SMB/NFS/SFTP clients and Apple-device synchronization are separate integrations, not emulated macOS services.
- macOS aliases, resource forks, extended attributes, Finder extensions, Handoff, AppleScript, Quick Actions provided by macOS apps, disk formatting/eject privileges, and protected system/app directories do not have identical Android APIs. App shortcuts and Android intents cover portable workflows; Android's access rules remain in force.
- Phone layout remains out of scope at the user's request. A compact tablet/window layout is in scope.
- Physical USB/SD hardware and third-party cloud providers need real-device/account testing; automated provider tests must use isolated fixtures.
