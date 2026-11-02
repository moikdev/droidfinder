# Android preview verification

## Findroid 0.33.0 — setup backup, reviewed restore and portable references

Local checks on November 2, 2026:

- **347 JVM tests pass**, with no failures, errors or skips. New tests cover section boundaries, deterministic merges and repeated imports, Unicode library identity, navigation/reference remapping, strict JSON, stale reviews, every journal write failure, process-death boundaries, corrupt recovery and preservation of original bytes. Already-missing unrelated tab history does not create a new blocking restore warning.
- **105/105 native cases pass on each dedicated API30 and API36 tablet emulator**, with no skips: setup codec (10), setup workflow (16), and the previous 79 sidebar, browsing, Favorites/internal/outgoing/cross-app drag, updater, workspace and preview/input cases. Provider fixtures exercise actual returned IDs, read-only roots, duplicate names, loading state, grant loss after review and unrecorded opaque IDs. These fixtures are debug-only.
- **The optimized 0.33 preview passes on both APIs.** The host script installs actual 0.32 over a controlled baseline, upgrades in place, verifies installed APK bytes, exports through Android's CreateDocument dialog, and imports a synthetic portable backup through OpenDocument. Native touch chooses a replacement root, applies the reviewed Merge, and confirms restart persistence. Original file bytes, the existing Favorite and an unrelated sentinel preference survive. The private previous-setup copy is valid.
- **Shipping recovery checks pass on both APIs.** With the app stopped, the script seeds a prepared journal and a partial metadata write. Launching the optimized APK restores the exact journaled setup before browsing. A deliberately damaged recovery record blocks browsing, preserves metadata and offers Retry. These are controlled metadata boundaries; they do not imply physical full-storage/provider-failure acceptance.
- **Actual optimized screenshots were inspected on both APIs**, including font scale 1.6 with the Create backup and Close actions reachable. See [setup](docs/screenshots/setup-backup-tablet.png), [review](docs/screenshots/setup-restore-review-tablet.png), and [enlarged text](docs/screenshots/setup-backup-large-text-tablet.png). The scripts restore the exact original metadata, preferences and font scale without clearing app data, remove only their synthetic fixture roots, and leave the optimized APK installed.
- **Debug, native-test, optimized preview and unsigned release builds pass.** Lint reports **0 errors, 143 warnings and 11 hints**, unchanged from 0.32. The optimized preview is **8,242,351 bytes**, SHA-256 `810aaa89e1d84c9a838f76313c96337c89eb29610a35f69ed442776c5dc277e8`.

The first expanded regression bundle exposed an overbroad warning for pre-existing missing tab history; the final runs above include the scoped-reference fix. An earlier API30 drag edge-scroll failure passed in the complete final 105-case run. Initial optimized host runs also exposed stale picker-drawer selection and API30's generic shell taps being ignored by document file tiles. The host now waits for the roots drawer, targets its actual Downloads row, and supplies explicit native finger events on API30 through a transient shell helper. The same shipping APK passes the completed flows; these host corrections do not bypass the picker.

Repeatable optimized acceptance:

```sh
python3 scripts/check-setup.py --serial <dedicated-tablet> \
  --apk <0.33-preview.apk> --previous-apk <0.32-preview.apk> \
  --fixture <synthetic-portable-demo.json> --output <evidence-directory>
```

The validation archive includes the controlled portable fixture, final native reports, JVM/lint results, the repeatable host script and inspected UI captures. `scripts/package-setup-validation.py` uses an explicit whitelist; private original setup snapshots, exported personal setup JSON, signing keys and document bytes are excluded. All five immutable APK/feed/metadata assets were downloaded anonymously and match local bytes and GitHub digests. A **real 0.32 → 0.33 public-feed download and Android installer upgrade passes on API30**; installed APK bytes match the public preview, the original synthetic file/Favorite/preference survive, and the app reports [up to date](docs/screenshots/app-updates-033.png). The opt-in **API36 production-feed check passes 1/1**, checking public APK bytes, package, version, signature and absence of a repeat offer. The validation archive is verified separately after adding those results. Both dedicated tablets are restored to the exact public optimized APK.

Published [0.33.0-preview](https://github.com/moikdev/findroid/releases/tag/v0.33.0-preview) from clean source commit `b128052892a745513110fad1d358a91904df4824`. The signing certificate remains `e7bee2db540fcbb0633d82870b2c4e3d14ecc0c18474b1a7c73d47eb0bf92c08`, matching 0.32.

This increment implements proposals **55, 56 and 61**, bringing the implemented total to **16 of 72**. Proposal 18's ordinary saved-workspace recovery remains separate from setup import. Physical tablets, TalkBack, USB/SD removal, the largest text/display scales and actual third-party providers remain open in [the tracker](docs/IMPROVEMENT-TRACKER.md). GitHub Actions remains manual-only.

## Findroid 0.32.0 — sidebar management and clearer navigation

Local checks on November 2, 2026:

- **317 JVM tests pass**, with no failures, errors or skips. New checks cover deterministic legacy migration, order/label/group round trips, stale insertion rejection, relocation, damaged/future schemas, command matching and keyboard bindings.
- **79/79 native cases pass on each dedicated API 30 and API 36 tablet emulator**, with no skips: sidebar management (12), browsing clarity (10), Favorites pinning (11), internal drag (13), outgoing drag (4), cross-app drag (5), updater (6), workspace/input (9), and advanced previews/input (9).
- **Optimized APK acceptance passes on both APIs.** Install actual 0.31 over a seeded legacy setup, upgrade to 0.32, reorder a favorite with native touch input, restart, and verify saved order, every fixture's original bytes, and an unrelated sentinel preference. Compact density keeps all migration targets visible for this host gesture; the native suite also covers comfortable density. Label/group/missing-folder menus are captured in comfortable density, with additional font-scale-1.6 screenshots. The original setup is restored without uninstalling or clearing app data.
- A **real 0.31 → 0.32 public-feed download and Android installation passes on API 30**. The installed base APK matches the anonymously downloaded release byte for byte; the synthetic original file, legacy favorite and sentinel preference survive. The updated app reports [up to date](docs/screenshots/app-updates-032.png). The acceptance script restores the original setup and exact public optimized APK afterward.
- The opt-in **production-feed check passes 1/1 on API 36**, verifying public download bytes, package, version, signer and absence of a repeat offer. The exact optimized APK is restored afterward. Five immutable release assets match anonymous public downloads; the validation ZIP is checked separately after its public acceptance results are added.
- The signed preview is **8,144,047 bytes**, SHA-256 `8de2bf832c04fcd5b7f3782eb6c3de3501957fd18e8f2e1c4523166d17d7cfac`. Source commit `0e7f105c2eadd91298745935d74235e8769c829a` was clean at packaging. The signing certificate matches 0.31.
- **Debug, optimized preview and unsigned release builds pass.** Lint reports **0 errors, 143 warnings and 11 hints**. The preview retains the previous signing certificate; unsigned release and preview package/version contracts are checked during packaging.

The device checks exposed launcher focus timing and a short-window empty-state layout that could hide its action behind the keyboard. The fixes are covered by the native command-launcher and clear-search tests. Android 11's interop fixture also left an empty legacy split root at a class boundary: that initial batch was recorded as incomplete, the tablet was reset to full screen, and the final 79-case run puts cross-app cases last. The final reports require a completed instrumentation runner, not only a successful adb exit code.

Repeatable optimized acceptance: `scripts/check-sidebar.py --serial <tablet> --apk <preview.apk> --previous-apk <0.31-preview.apk> --output <evidence-directory>`. It uses synthetic folders and temporarily backs up/restores setup preferences. The host script correctly quotes Android shell paths and resolves insertion gaps from visual label centers rather than Android's expanded semantic touch bounds. UI XML, PNGs and verification JSON accompany its result. `scripts/package-sidebar-validation.py` whitelists the evidence for publication and excludes signing keys, credentials and private document contents.

This increment implements proposals **1, 4, 5, 7, 8, 9 and 10–16**. Proposal 3's command explanations have begun in the launcher; broader menu explanations remain open. The remaining work and external acceptance are tracked in [IMPROVEMENT-TRACKER.md](docs/IMPROVEMENT-TRACKER.md). Physical trackpad/stylus, TalkBack, removable drives, maximum text/display settings and third-party provider matrices are not implied by emulator success. Actions remains manual-only; checks run locally.

## Findroid 0.31.0 — drag folders into Favorites

Validated **2026-11-02** on dedicated Android 11/API 30 and Android 16/API 36 tablets at 1920 × 1200, density 240. The Favorites heading and its labeled **Drag folders here** area now save folder references through the existing native drag controller. File transfers onto existing sidebar folder rows keep their previous copy/move behavior.

Published [**0.31.0-preview**](https://github.com/moikdev/findroid/releases/tag/v0.31.0-preview) from source commit `b19b8eec363126b421b3df13aaa82b0a0b8d6f7b`. Packaging records a clean source tree and the same signing certificate as 0.30. All five immutable APK/feed/metadata assets were downloaded anonymously and matched their local bytes and GitHub digests.

- **308 JVM tests** pass, with zero failures, errors or skips. Debug, native-test, optimized preview and unsigned release builds succeed. Android lint reports **zero errors, 143 warnings and 11 hints**.
- Final focused native batches pass **39/39 on API 30 and 39/39 on API 36**, with no skips. Each includes 11 Favorites checks, 13 existing drag/resize workflows, four outgoing payload/control checks, five real cross-app drops, and six updater checks. These are focused suites, not the complete Android instrumentation suite.
- Favorites checks cover native touch icons, mouse/Ctrl and multi-folder payloads, the named Drag control, duplicate and parent-alias handling, cancellation, pinning after spring navigation, read-only connected folders, disappearing sources, and transfer/Undo through an existing favorite. A model-level check also protects built-in locations against duplicate saved shortcuts.
- The **exact optimized APK** is exercised through native touchscreen events on both tablets without preselecting the folder. Host acceptance restarts the app process, opens the persisted favorite, and verifies original file bytes plus exactly one saved reference after a same-key debug reinstall. It then restores the exact optimized preview. No uninstall or data clearing is used.
- A **real 0.30 → 0.31 in-app update** passes on API 30: the installed 0.30 app discovers and downloads the public release, Android's installer confirms the upgrade, and the new app exposes the Favorites drop area. Original file bytes, every saved favorite, and a sentinel preference survive. Private bytes are compared after a same-key debug reinstall, then the exact anonymously downloaded optimized preview is restored. The updater subsequently reports [“You're up to date”](docs/screenshots/app-updates-031.png).
- The opt-in **production-feed download check passes 1/1 on API 36**, validating the public APK bytes, package, version and signer and confirming that the latest version is not offered again. The exact optimized preview is restored afterward. API 30's acceptance above covers the actual public download and installation instead of a fixture handoff.
- The installable preview is **8,078,511 bytes**, SHA-256 `978427aba98eab699bcf6423756b59629a89a7ef6f80f2bee0e4973752e6d587`. Build information and checksums accompany the release. Actual optimized screenshots: [during the drag](docs/screenshots/favorites-drag-tablet.png) and [after restarting](docs/screenshots/favorites-sidebar-tablet.png).

Repeatable host acceptance: `scripts/check-favorites-drag.py --serial <dedicated-tablet> --apk <preview.apk> --output <evidence-directory>`. The output includes UI XML, actual screenshots and a verification JSON. Reusing its output directory removes only the previous synthetic favorite recorded by that run; other favorites and file contents are retained. An early native cancellation assertion was corrected to wait for Android's drag-ended callback, which can follow Compose idleness on API 36.

Evidence is retained in `captures/favorites-drag/` and packaged in the release validation archive. Folder drags from another process are not offered as permanent favorites; connected locations must already be authorized in Findroid. Favorite ordering, labels, groups, and per-row management are proposed follow-up work. Physical tablets, USB/SD devices, and third-party provider/receiver compatibility are not implied by these emulator results. GitHub Actions remains manual-only.


## Findroid 0.30.0 — external file dragging

Validated **2026-11-02**. The touch entry point missing from the simplified tablet toolbar is restored as a labeled **Drag** control. Holding a file icon also initiates a native Android global drag; mouse initiation remains direct. Holding a selected icon preserves the visible selection and transfers its frozen file set. Mixed file selections advertise all MIME types. Cross-app drops grant temporary read access and keep the originals. Folder/mixed-folder selections retain internal dragging; Share supports ZIP export for external destinations.

Published [**0.30.0-preview**](https://github.com/moikdev/findroid/releases/tag/v0.30.0-preview) from source commit `291d5b9fa10ae90ab571494a7443eb21ed0dd481`. Build information records a clean source tree. The installable preview is **8,078,507 bytes**, SHA-256 `3ed8b90849628c63b7d48809d881ab54e3a93ad52c04d099fdd3351fac9e24c2`, signed with the same certificate as 0.29.

- **308 JVM tests** passed with zero failures, errors or skips. Debug/test, optimized preview, unsigned release and the separate interop QA app built successfully. Android lint completed with **zero errors, 142 warnings and 11 hints**.
- Final focused native batches pass **15/15 on API 36 and 15/15 on API 30**: five real cross-app drops, four payload/control checks and six updater checks on each version. Cross-app cases cover touch icons, the labeled Drag control, a selected icon carrying several files, mouse dragging and connected-provider files.
- Separate broader drag/input runs pass **34 checks with 4 phone-only skips on API 36**, and **29 checks with 4 phone-only skips on API 30**. These cover internal moves/copies, spring loading, cancellation, Undo and ordinary touch/mouse selection. They ran before the final unknown-tool fallback and updater-address adjustment; focused final batches and exact optimized-APK checks cover those adjustments. This is targeted validation across runs, not a clean full Android-suite claim.
- A different-UID receiving application copies actual URI contents in split screen. Tests compare names, byte counts and SHA-256; assert no write access, no access before the drop, no access to an unselected sibling, no access after permission release, and no cross-process exposure of local drag state.
- The exact optimized preview was exercised with native touchscreen events on **Android 11/API 30 and Android 16/API 36** at 1920 × 1200, density 240. The API 30 run holds a selected file icon; API 36 holds Drag. Both copy a PNG and text file, preserve two-file selection and verify the original bytes after a same-key debug reinstall. The exact preview is then restored. [Actual optimized screenshot](docs/screenshots/external-drag-tablet.png).

The repository was renamed from `moikdev/droidfinder` to **`moikdev/findroid`** after 0.29 shipped. That installed version rejects the renamed feed's asset URLs, so updating from 0.29 requires installing the 0.30 APK once over the app. Version 0.30 reads the current repository and accepts only the known legacy repository address in older published manifests/cached downloads. A new regression checks that unrelated repositories remain rejected.

After publication, anonymous downloads of the signed/unsigned APKs, manifest, checksums and build information matched local bytes and GitHub's recorded digests. Opt-in production-source acceptance passes **1/1 on API 30 and 1/1 on API 36**, downloading the public 0.30 APK, verifying its bytes/package/version/signing certificate, and confirming the installed latest version is not offered again. The exact optimized app shows [“You're up to date”](docs/screenshots/app-updates-030.png) against the public feed.

An API 30 package-replacement check reproduced 0.29's renamed-feed error, then installed the anonymously downloaded 0.30 APK using `adb install -r`. File bytes and a sentinel preference were preserved. Data was compared after a same-key debug reinstall and the exact preview restored. This records the manual APK migration required after the repository rename; the existing in-app installer flow remains covered by the updater checks and earlier installer acceptance records below.

Test-driver corrections during development included Android 11 cold split-screen launch flags, waiting for a committed source frame on Android 16, and host UI automation that focuses each window and distinguishes image/text accessibility nodes. Earlier failing runs are superseded by the final named batches above. The host script reports native input as touchscreen; some Android input tools report an unknown pointer tool, which now follows touch hold recognition. Physical pen/eraser hardware, physical tablets, and specific third-party receiving/cloud apps remain unverified.

Reproduction: [dragging guide](docs/DRAGGING.md), `ExternalDragWorkflowTest`, the separate `interop-client` receiver, and `scripts/check-external-drag.py`. Release assets include checksums, build information, the OTA manifest and a validation archive. GitHub-hosted checks remain manual-only; all checks here ran locally.


## Findroid 0.29.0 public release

Published **2026-11-02**: [source and README](https://github.com/moikdev/droidfinder), [release and APKs](https://github.com/moikdev/droidfinder/releases/tag/v0.29.0-preview). Source and OTA releases share the public `moikdev/droidfinder` repository. The release tag points to source commit `20efcb39cecf15888096de74a970479e1a2da81c`; build information records a clean source tree.

The final configuration passes **307 JVM tests**, **8/8 focused native API 36 checks**, **6/6 native API 30 updater checks**, and optimized builds/lint with zero errors. The exact preview completed the Android 11 install-source permission and system-installer flow using a signed next-version fixture, preserving a test file and a shared preference.

After publication, **1/1 live native check passed on each of API 30 and API 36**. Both used the production update source to fetch the public release manifest and APK, verify bytes/package/version/signing certificate, and check that the same version is not offered again. An anonymous host download also matched the local APK, update manifest and GitHub asset digest. The optimized preview’s actual update dialog reports **“You're up to date.”** [Screenshot](docs/screenshots/app-updates-tablet.png).

Published preview: **8,062,131 bytes**, SHA-256 `f026884edbf0a497d291ec1a6dfd502cebcc2076d36a3327c5f38c8365049fb0`. The release includes the unsigned APK, OTA manifest, APK checksums, build information and a validation archive containing test logs, JVM XML, lint XML, live publication checks and installation-preservation evidence. This verifies a public download plus local upgrade acceptance; it does not claim a newer public version was installed over this same initial version.

Earlier records below describe the artifacts and publication state at the time of each local run.


## Findroid 0.28.0

A local production-readiness audit on **2026-11-02** fixed link-following deletion, local collision/rollback failures, incomplete provider listings/streams, source-tree changes, archive self-inclusion, damaged Trash-index startup, Android 11 ZIP64 reads, and unreachable/clipped phone PDF controls. It also pins patched Guava, bundles the archive dependency notices, and makes backup/transfer exclusions explicit. See the [full audit and limits](docs/PRODUCTION-AUDIT.md).

- **301 JVM tests passed**, zero failures/errors/skips. Android lint: **zero errors, 137 warnings, 11 hints**.
- The earlier combined audit recorded **315 native cases passed on each tablet** (API 30 and API 36), with eight skips and no unresolved failures per device, across completed broad and focused runs. These are combined per-case results; the initial full runs had failures, and their logs remain preserved. Six skips are phone-only cases and two are opt-in grant helpers.
- The earlier final phone preview/storage/share run passed **all 48 cases** with no skips or failures. The earlier phone input class passed its 16 cases.
- Follow-up archive/storage runs passed **15/15 on API 30**, **10/10 on the API 36 tablet**, and **10/10 on the API 36 phone**. The 64 MiB heap probe now rejects the 120,000-entry ZIP safely and accepts 10,000 entries. Real picker grants passed on all three devices; Android 11 read-only, revoke, cold-start and same-folder reconnect checks also passed. The metadata and reconnect regressions are documented in the audit.
- Debug/test, optimized preview, and unsigned release builds passed. Packaging checks validate compiled backup exclusions, package/version, development signature, provider permissions, no INTERNET permission, fixture exclusion, licenses, and checksums. The Guava advisory's three map classes and Bouncy Castle's trust-all helper are entirely removed from both optimized variants.
- The packaged optimized preview cold-launched and passed native ZIP64 listing/CRC/extraction and unsupported-codec UI smoke checks on all three devices without AndroidRuntime crashes.

Package: `captures/release/0.28.0/Findroid-0.28.0-preview.apk` (**8,045,619 bytes**). SHA-256: `24e9a41c8b2edf483cae2fd98e562317f8cf3fe7f8563a7fb11c64e33960e529`. The unsigned release, `SHA256SUMS`, and dirty-tree `BUILDINFO.json` are beside it. No release was published. Production signing, physical storage/provider coverage, accessibility acceptance, and dependable hosted CI remain open.

Inspected actual Android screenshots: [PDF page and zoom](docs/screenshots/audit-pdf-phone-page.png) · [Bookmarks](docs/screenshots/audit-pdf-phone-bookmarks.png) · [Contents](docs/screenshots/audit-pdf-phone-contents.png).

## Findroid 0.27.0

This local increment adds natural filename ordering across views, folder/search listings, archive paths and destination browsers, including Unicode decimal digits, leading zeroes, arbitrary-length numbers and stable name/path ties. It also fixes large-folder batching: the delay before the next update starts after sorting/publication finishes, preventing an expensive batch from triggering another full sort for every subsequent file. See [sorting behavior and captures](docs/NATURAL-SORTING.md).

Verified locally on **2026-11-02**, using dedicated Android 16 / API 36 emulators (1280 × 800 dp tablet and approximately 411 × 914 dp phone):

- **265 JVM tests passed**, including ten filename-ordering cases, a 50,000-name fixture and a new slow-publication regression.
- **49 tablet workflow cases passed across focused runs**: four new sorting workflows, ten progressive-loading workflows, eight folder-view workflows, thirteen rename workflows and fourteen saved-location/reference-repair workflows.
- **12 phone workflow cases passed**: four new sorting workflows and eight incoming-share workflows. The final sorting class passed again on both devices after the batching fix.
- The large-folder workflow retained the **20,000-result limit**, loaded all **20,002** available entries after Load more, and restored the higher budget through history and serialized tab state. Other loading cases covered cancellation, stopped results, partial selection, scroll restoration, provider errors and native dragging.
- Android lint completed with **zero errors, 138 warnings and 11 hints**. Debug/test APKs, the optimized installable preview and unsigned release built successfully.
- Packaging verified the development signature, non-debuggable manifests, Workspace provider permissions, correct `dev.findroid` identity and exclusion of the fixture provider. Both packaged manifests omit INTERNET permission. Packaged checksums match their metadata.
- The final optimized preview installed and cold-launched on both devices without AndroidRuntime startup errors.

The initial combined tablet run passed all 35 folder-view/rename/reference cases and failed four loading cases. Three older selection helpers tapped files, invoking the app's tap-to-open behavior; they now inject mouse selection. The large-folder deadline also reproduced in a focused rerun and exposed the batching bug above. After that correction, the complete ten-case loading suite passed without increasing its deadlines.

Package: `captures/release/0.27.0/Findroid-0.27.0-preview.apk` (7,813,391 bytes).

```text
SHA-256: c19abb779c475c7bbced09fcaea36672e661f83c6da82d9ccfd42f58cd6399af
```

The package directory includes the unsigned APK, SHA256SUMS and BUILDINFO.json. Metadata records the uncommitted working-tree state. No GitHub release was published. Native captures use synthetic QA documents: [tablet files](docs/screenshots/natural-sorting-tablet.png), [phone files](docs/screenshots/natural-sorting-phone.png), [tablet archive](docs/screenshots/natural-sorting-archive-tablet.png) and [phone archive](docs/screenshots/natural-sorting-archive-phone.png). Physical-device, older-Android and third-party provider matrices remain open.

## Findroid 0.26.0

The local 0.26.0 preview renames the app to **Findroid**, including its Android application ID and source namespace (`dev.findroid`), document/share providers, interop QA client, share-sheet entries, notifications, shortcut labels, export defaults, Gradle project, release artifacts and repository links. It installs separately from the earlier application. GitHub repository renaming is handled by the repository owner.

New files use `.findroid-` staging names and `Findroid shortcut v1` shortcut headers. Existing shortcut headers still resolve, and both generations of working-file prefixes remain reserved and excluded from browsing, analysis and Workspace exports. Existing file-operation and storage restrictions still apply.

Verified locally on **2026-11-02**, using isolated Android 16 / API 36 phone and tablet emulators:

- **254 JVM tests passed**, including reading existing shortcut headers, writing new Findroid shortcuts, sanitizing both generations of reserved share names and excluding interrupted legacy working files from analysis.
- **32 phone workflows passed together**: touch/mouse input, phone actions and views, incoming shares and the Workspace document provider under the new application ID.
- **7 tablet/provider/export workflows passed**: connected-provider operations, sharing URIs, the renamed organization JSON export and staging cleanup. The export-destination readiness check hit its deadline in the initial batch and passed in an isolated rerun; its diagnostic now handles multiple dialog roots.
- Android lint completed with **zero errors, 138 warnings and 11 hints**. Debug/test APKs, optimized preview, unsigned release and the separate interop QA client built successfully.
- Packaging verified the development signature, non-debuggable app, correct Workspace provider permissions and exclusion of the debug fixture provider. Manifest checks confirmed `Findroid`, `Save to Findroid`, `dev.findroid.documents`, `dev.findroid.files` and no INTERNET permission. Release DEX files contain no previous application namespace.
- The optimized preview installed and cold-launched on both phone and tablet; AndroidRuntime recorded no startup errors.

Package: `captures/release/0.26.0/Findroid-0.26.0-preview.apk` (7,813,391 bytes).

```text
SHA-256: 22aa5c11f77e231f8d850f04dc4983c5fbd1736a40c94f368feebc50216dd8d3
```

The package directory also includes the unsigned APK, SHA256SUMS and BUILDINFO.json. Build metadata records `dev.findroid` and the uncommitted working-tree state. Packaging did not publish a GitHub release.

Current native screenshots: [Tablet](docs/screenshots/findroid-tablet.png), [phone](docs/screenshots/findroid-phone.png), [selection](docs/screenshots/findroid-selection.png), [Locations](docs/screenshots/findroid-locations.png) and [incoming share](docs/screenshots/findroid-share.png). Earlier feature captures and checksums remain historical release evidence.

Local **0.25.0-preview**: [phone and input validation](docs/PHONES-AND-INPUT.md) records per-gesture activation, phone layouts, incoming shares, screen/text-size coverage, tablet regressions, captures and the packaged APK checksum. The final build passes 251 JVM tests, lint with zero errors and optimized preview/release builds. The same source includes [multi-tag search, scan depth and exclusions](docs/TAG-SEARCH.md). The published release record below remains unchanged.

Latest: **0.24.0-preview — Saved locations and reference repair**. See [reference validation](docs/REFERENCES.md). [0.23.0 organization](docs/ORGANIZATION.md) is preserved. See [organization validation](docs/ORGANIZATION.md). [0.22.0 reviewed rename](docs/RENAMING.md) is preserved. See [rename validation](docs/RENAMING.md). [0.21.0 housekeeping](docs/HOUSEKEEPING.md) is preserved. [0.20.0 analysis and checksums](docs/ANALYSIS.md) is preserved. [0.19.0 Android integration](docs/ANDROID-INTEGRATION.md) is preserved. [0.18.0 Storage and recovery](docs/STORAGE.md) is preserved. [0.17.0 Image Peek](docs/IMAGE-PEEK.md) is preserved. [0.16.0 Archive browser](docs/ARCHIVES.md) is preserved. [0.15.0 Structured Peek](docs/STRUCTURED-PEEK.md) is preserved. [0.14.0 Media Peek](docs/MEDIA-PEEK.md) is preserved. [0.13.0 PDF Peek](docs/PDF-PEEK.md) is preserved. [0.12.0 progressive loading](docs/LOADING.md) is preserved. [0.11.0 dragging and resizing](docs/DRAGGING.md) is preserved. [0.10.0 folder views and columns](docs/FOLDER-VIEWS.md) is preserved. [0.9.0 navigation and shared thumbnails](docs/NAVIGATION.md) is preserved. [0.8.0 workspaces and desktop input](docs/WORKSPACES.md) is preserved. [0.7.0 compact transfers and density](docs/TRANSFER-PLANNING.md) is preserved. [0.6.0 folder merging](docs/FOLDER-MERGE.md), [0.5.0 transfer foundation](docs/TRANSFER-CENTER.md), [0.4.0 desktop workflows](docs/DESKTOP-WORKFLOWS.md) and [0.3.0 Refined Finder and Peek](docs/REFINED-FINDER.md) are preserved. The record below applies to the earlier 0.2.0 preview.

Version: **0.2.0-preview**. Verified locally on 2026-10-31 using a dedicated Android 16 / API 36 tablet emulator. These checks cover implemented Android workflows; they do not certify identical behavior to every macOS Finder service.

Published [preview release](https://github.com/moikdev/findroid/releases/tag/v0.2.0-preview), built from `f399ca769b2f526adc744b57b5ad950860dbc65a`. The 2,461,555-byte installable APK has SHA-256 `ce16f380409e47dc0f3ce63d43657bfa531c29fa1cdc2e6d44e9aed1cb398ff1`; GitHub's uploaded-asset digest matches. The unsigned release and checksums are included alongside it.

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
- The real Android DocumentsUI folder picker granted access to `a dedicated QA folder under Documents`. The app listed an existing document and created `Scoped-test` without all-files permission. Access survived installing the optimized preview and a cold process restart.
- The optimized preview installed successfully and cold-launched. Landscape at 1280 × 800 dp, portrait at 800 × 1280 dp, and a compact 640 × 800 dp window were visually inspected. Compact layouts use the view menu and omit the side preview.
- Light and dark appearance and the tablet action menus were inspected. The final touch/accessibility adjustment makes each preference row a labeled switch with a minimum 48 dp touch height; tapping the Dark appearance label toggled the optimized app successfully. System display overrides were restored after checking layouts.

Screenshots use synthetic QA documents:

- [Landscape](docs/screenshots/tablet-landscape.png)
- [Portrait](docs/screenshots/tablet-portrait.png)
- [Compact window](docs/screenshots/compact-window.png)
- [Dark appearance](docs/screenshots/tablet-dark.png)

## Hosted checks and remaining external coverage

GitHub Actions is configured, but GitHub declined to start the runner because the account has a billing/spending-limit issue. No hosted CI success is claimed. See [the blocked run](https://github.com/moikdev/findroid/actions/runs/41160966010).

Physical USB/SD hardware, third-party cloud accounts, and cross-app drag targets require device/app-specific testing. Internal native dragging and URI-based import/export paths are implemented; external apps control which drops they accept. Video playback uses Android's native player and remains codec/device-dependent. Phone-specific layout was outside that earlier 0.2.0 preview and is implemented in the local 0.25.0 build above. Direct SMB/NFS/SFTP clients, Apple-only services and operating-system privileges remain outside the current implementation; see [PARITY.md](PARITY.md).


## Tablet toolbar simplification (2026-11-02)

The tablet toolbar now uses a current-view dropdown, Search, New folder and More. View options contain the four named layouts, sorting, grouping and preview visibility. Selection replaces browsing commands with named file actions. Clipboard Paste appears when available; narrower windows and larger text move secondary commands into More. Phone chrome is unchanged.

Validation: debug/test and optimized preview builds succeed; Android lint reports zero errors. **18/18 focused native tests pass on the Android 16 tablet**, covering create/rename/copy/paste/Trash/restore, view switching, keyboard view shortcuts, archive compression/browsing/extraction, context actions, selection stability and control density. Existing test selectors were scoped to the relevant menus/dialogs after labels became visible.

The exact signed optimized APK passes **seven UI smoke checks on Android 11**, including all four view modes, size sorting with explicit descending order, grouping, preview visibility, contextual actions, and a 700dp window with 150% system text. Device size and font settings were restored.

Artifact: `captures/toolbar-simplification/Findroid-toolbar-preview.apk` (development signed, version `0.28.0-preview`). This separate UI review build preserves the earlier release-audit artifact and its recorded evidence. Checks and digest: `captures/toolbar-simplification/verification.json`.

Actual optimized screenshots: [Tablet](captures/toolbar-simplification/optimized-tablet.png) · [Selection](captures/toolbar-simplification/optimized-selection.png) · [Narrow tablet](captures/toolbar-simplification/optimized-narrow.png) · [Large text](captures/toolbar-simplification/optimized-large-text.png).


## In-app updates (2026-11-02)

The local **0.29.0-preview** adds **More → App updates**, a Preferences link, optional daily checks, release notes, requested downloads, cancellation/retry, verified cached-download recovery and Android's installation confirmation. Downloads are checked for exact size/SHA-256, package, newer version, version name, supported Android version and matching installed signing certificate. Installation waits for cache restoration when returning from Android's install-source permission screen. See [app updates](docs/APP-UPDATES.md).

The final build passes **307 JVM tests** with zero failures/errors/skips, debug/test and optimized preview/release builds, and Android lint with **zero errors** (142 warnings). Final focused native checks pass **8/8 on API 36** and **6/6 on API 30**, including invalid packages/signers/versions, cached-file tampering, cancellation/retry, unavailable feeds, automatic-check preferences and deterministic installation during cache restoration. The API 36 run also covers core file operations and all view modes. An earlier separate Android installer smoke test passed 1/1.

The exact optimized APK passed the complete Android 11 permission/installation flow using a locally seeded signed version-30 fixture: restore and verify cache → request install-source permission → Android installation confirmation → version 29 to 30. A test file and shared preference survived. The original preview was restored afterward. This verifies installation and preservation locally; no live feed download is claimed. Evidence: `captures/ota/optimized-system-install.json`, native logs and `captures/ota/verification.json`.

Installable development-signed APK: `captures/release/0.29.0/Findroid-0.29.0-preview.apk` (**8,062,131 bytes**), SHA-256 `5830ceb9a68690c3286cfdb1213d7640ea325fb7afd5a06df7261aeffd182fcb`. The packaged update manifest matches its size/digest. Actual optimized screenshot: [App updates](captures/ota/updates-tablet.png).

**Live OTA delivery is pending publication approval.** The existing source repository is private; the configured separate `moikdev/findroid-updates` binary feed has not been created or published. The current UI accurately reports that the update source is not yet available. No hosted CI run was triggered.


## Public release preparation (2026-11-02)

The user authorized making `moikdev/droidfinder` public and publishing source and releases together. The updater and packaging manifest now use that repository. The README has been rewritten around installation, actual screenshots and practical features; all local README links/images resolve. The 1,233 historical blobs plus current publishable files were checked for common credential/private-key patterns; no matches or credential-file paths were found. Local signing keys and build outputs remain excluded from Git.

The rebuilt public-feed configuration passes **307 JVM tests**, **8/8 focused API 36 native checks** and **6/6 API 30 updater checks**, optimized builds, and lint with zero errors. The exact rebuilt preview again completed Android 11’s install-source permission → installer → version-30 fixture upgrade, preserving a test file and shared preference; the original preview was restored. Its size is **8,062,131 bytes**, SHA-256 `f026884edbf0a497d291ec1a6dfd502cebcc2076d36a3327c5f38c8365049fb0`. Source and manifest changes do not alter the recorded earlier local artifacts.

An opt-in `AppUpdateLiveFeedTest` uses the production GitHub update source to find a published manifest, download its APK on Android, verify its bytes/package/version/signing certificate and ensure that the same version is not offered again. It runs only when explicitly passed `-e liveUpdates true`. Live results are recorded after publication.
