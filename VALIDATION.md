# Tablet preview verification

Latest: **0.4.0-preview — Desktop workflows**. See [the current UI, workflows, and validation](docs/DESKTOP-WORKFLOWS.md). [0.3.0 Refined Finder and Peek](docs/REFINED-FINDER.md) is also preserved. The record below applies to the earlier 0.2.0 preview.

Version: **0.2.0-preview**. Verified locally on 2026-10-31 using a dedicated Android 16 / API 36 tablet emulator. These checks cover implemented Android workflows; they do not certify identical behavior to every macOS Finder service.

Published [preview release](https://github.com/moikdev/droidfinder/releases/tag/v0.2.0-preview), built from `f399ca769b2f526adc744b57b5ad950860dbc65a`. The 2,461,555-byte installable APK has SHA-256 `ce16f380409e47dc0f3ce63d43657bfa531c29fa1cdc2e6d44e9aed1cb398ff1`; GitHub's uploaded-asset digest matches. The unsigned release and checksums are included alongside it.

## Automated checks

- 40 JVM tests: file-operation collisions, symlinks, safe ZIP extraction, batch rename/rollback, session Undo/Redo and changed-file guards, metadata path remapping, multiple/custom tags, shortcuts, Smart Folder rules, streaming and blocked-stream cancellation.
- 26 Android workflow tests passed together in the final full run, with zero failures/skips: browsing views, selection and keyboard/pointer input, folder disclosure, create/copy/move/rename/Trash/restore, batch rename and history, Smart Folders, tags, folder sharing, PDF/audio Quick Look, provider capability restrictions and transfers, background service lifetime, and cancellation cleanup.
- Android lint, optimized installable preview, and optimized unsigned release builds.
- Preview APK signature verified with Android's `apksigner`; debug fixture provider excluded and no app-owned INTERNET permission in the preview manifest.

Commands:

```sh
./scripts/check.sh
ANDROID_SERIAL=emulator-5582 ./gradlew connectedDebugAndroidTest
```

The PDF test's initial immediate lookup raced preview composition. The harness now waits for the control; the complete advanced-workflow class passed after that correction. Streaming cancellation tests issue the thread-safe cancellation token directly so ActivityScenario's idle synchronization cannot delay cancellation until after a short transfer has finished.

## Native integration and layout checks

Test files were created explicitly for QA on the isolated emulator; the distributed app does not seed user storage.

- Android's native touch drag-and-drop moved `Source.txt` into `Archive`; its 27-byte content was preserved and the original disappeared after completion.
- The real Android DocumentsUI folder picker granted access to `Documents/DroidFinder-QA`. The app listed an existing document and created `Scoped-test` without all-files permission. Access survived installing the optimized preview and a cold process restart.
- The optimized preview installed successfully and cold-launched. Landscape at 1280 × 800 dp, portrait at 800 × 1280 dp, and a compact 640 × 800 dp window were visually inspected. Compact layouts use the view menu and omit the side preview.
- Light and dark appearance and the tablet action menus were inspected. The final touch/accessibility adjustment makes each preference row a labeled switch with a minimum 48 dp touch height; tapping the Dark appearance label toggled the optimized app successfully. System display overrides were restored after checking layouts.

Screenshots use synthetic QA documents:

- [Landscape](docs/screenshots/tablet-landscape.png)
- [Portrait](docs/screenshots/tablet-portrait.png)
- [Compact window](docs/screenshots/compact-window.png)
- [Dark appearance](docs/screenshots/tablet-dark.png)

## Hosted checks and remaining external coverage

GitHub Actions is configured, but GitHub declined to start the runner because the account has a billing/spending-limit issue. No hosted CI success is claimed. See [the blocked run](https://github.com/moikdev/droidfinder/actions/runs/41160966010).

Physical USB/SD hardware, third-party cloud accounts, and cross-app drag targets require device/app-specific testing. Internal native dragging and URI-based import/export paths are implemented; external apps control which drops they accept. Video playback uses Android's native player and remains codec/device-dependent. Phone-specific layout, direct SMB/NFS/SFTP clients, Apple-only services and operating-system privileges remain outside this preview; see [PARITY.md](PARITY.md).
