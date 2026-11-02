# Findroid 0.28.0 production audit

Audited locally on **2026-11-02 (Asia/Tokyo)**. Application ID: `dev.findroid`; version code: `28`.

This increment fixes confirmed data-safety and compatibility defects and adds repeatable release checks. It is a development preview. Production signing, physical storage/provider coverage, accessibility acceptance, and reliable hosted checks remain release requirements. Nothing was published, pushed, or signed with production credentials during this audit.

## Scope and method

The audit covered local storage and symlinks, document-provider capabilities/listings/streams, transfers and recovery, Trash persistence, archives, incoming shares, exported Android providers, backup policy, dependency advisories, previews, keyboard/pointer behavior, phone/tablet layout, and optimized packaging. Existing workflow tests exercise navigation, tabs/workspaces, search and Smart Folders, tags and reference repair, renaming, analysis/checksums, housekeeping, drag/drop, cancellation, transfer persistence, and cross-app document access.

Validation uses dedicated emulators only:

| Device | Android | Layout |
| --- | --- | --- |
| `emulator-5602` | Android 11 / API 30, minimum supported version | 1280 × 800 dp tablet |
| `emulator-5582` | Android 16 / API 36 | 1280 × 800 dp tablet |
| `emulator-5590` | Android 16 / API 36 | approximately 411 × 914 dp phone |

Synthetic QA documents are used. Logs and machine-readable reports are retained locally in `captures/audit-0.28.0/`. Older failed runs are retained as evidence; they are not counted as passing runs.

## Confirmed defects and changes

### Deletion through directory links — data loss

Kotlin `File.deleteRecursively()` traversed a directory symlink and deleted a file outside the requested tree in a standalone reproduction. All production recursive deletion now uses `Files.walkFileTree` without `FOLLOW_LINKS`. A symlink is deleted as an entry; its target is preserved. Deletion stops at the first filesystem error rather than continuing to remove unrelated siblings.

Regression coverage includes nested file/directory/dangling links, a link used as the root, and deleting a trashed tree. Production cleanup paths, staged-transfer cleanup, cache/archive cleanup, and permanent Trash deletion use the same implementation.

### Destination collisions and rename rollback — possible overwrite or incomplete recovery

A dangling symlink still reserves its filename. Collision checks now use `NOFOLLOW_LINKS`, including unique names, exact transfers, folder creation, and history destinations. Local moves use `Files.move` without replacement and preserve an already-existing destination instead of using `File.renameTo`'s replacement behavior. Cross-volume transfers retain staged-copy fallback.

Three regressions failed against the previous implementation: a dangling-link name collision, exact copy/relocation into a dangling destination, and a destination created immediately before the move. Rename rollback also checks link-entry existence, allowing a failed rename cycle to restore dangling links without leaving recovery aliases.

These checks protect the tested collision windows. They do not provide a filesystem snapshot or a universal transaction against arbitrary concurrent external writers.

### Incomplete provider listings — possible incomplete folder move

Browsing may show partial provider results, but mutating operations must not treat them as a complete source or collision inventory. `DocumentAccess.list` rejects `EXTRA_LOADING` and provider `EXTRA_ERROR` responses. A folder cannot be deleted as a completed move after only the first provider page was enumerated.

Native tests cover loading/error source folders and incomplete destination listings. Progressive browsing continues to report provider state through its separate streaming path.

### Truncated streams and changing source trees — possible incomplete publication

Transfer streaming now validates the actual count against known metadata. Premature EOF fails; an overlong chunk is rejected before writing beyond the expected size. Unknown-size streams remain supported. Local copies recheck size/timestamp and folder membership; provider copies recheck available metadata and folder membership before publication. Failed work cleans staging and preserves the source.

Incoming URI imports and Save to Findroid use the same verified, cancellable stream path. Metadata is read again on retry. Native tests cover short provider streams into local/provider destinations, a visible share failure/retry, unknown sizes, changed source metadata, and a new folder member appearing during a copy. JVM tests include exact/unknown/empty streams, early EOF, overlong streams, local source edits, and changing folder membership.

Metadata rechecks detect the tested changes; a provider can omit size/timestamps or change content without exposing a new revision. External mutation after a successful check remains outside the guarantees of Android's general document-provider interface.

### Archive self-inclusion and unbounded traversal — recursive growth or process failure

Archive creation rejects an output directory inside any selected source folder before creating staging. Local/provider recursive copies reject repeated document identities, depth above 128, and more than 100,000 entries. Existing ZIP entry, expanded-byte, compression-ratio, CRC, method, and path-traversal limits remain enforced.

### Damaged Trash metadata — startup failure

Malformed `trash.properties` data could throw while constructing the file engine. Loading is now bounded to 16 MiB and falls back to a valid backup. Damaged bytes are preserved under a unique `.damaged-…` name. If both copies are unreadable, the files remain browsable in Trash and the app explains how to recover them manually. If preserving damaged records fails, the app refuses to overwrite them.

Index writes use unique staging, `fsync`, a backup, and atomic replacement where the filesystem supports it. Regression tests verify startup, original-location recovery from backup, and preservation of damaged bytes and trashed files.

### ZIP64 on Android 11 — valid archives could not be opened

A valid small ZIP64 fixture lists correctly but fails during member reads in Android 11's `java.util.zip.ZipFile` with `invalid LOC header (bad signature)`. The same bytes read correctly on API 36. This was reproduced outside Findroid with a standalone dex program; logs are under `captures/audit-0.28.0/zip-runtime/`.

Archive reading now uses Apache Commons Compress **1.28.0**. `ZipPlatformTest` verifies both streamed checking and extraction on the minimum supported Android version. Existing application limits and actual-byte/CRC checks remain in effect. Multi-GiB payload benchmarking remains open.

### ZIP indexing — limits applied after allocation

A valid ZIP containing 120,000 empty files exhausted a **64 MiB JVM heap** while Commons Compress built its entry/name index, before Findroid's default 10,000-entry check ran. The baseline and probe source are preserved under `captures/audit-0.28.0/archive-metadata/`.

All archive opens now validate metadata before constructing the reader. The preflight checks both declared and actual central-directory counts, bounds captured metadata to **8 MiB**, bounds filename metadata to **16 KiB per header**, and allows at most **128 extra fields per header / 65,536 total**. These fixed indexing limits also apply when the editable extraction limits are raised. ZIP32 self-extracting offsets, ZIP64 directory/member offsets, truncation and single-volume consistency are checked without inflating payloads. Malformed count/extra-field records are rejected rather than trusted.

Indexing reads the validated header snapshots on the same open channel, preventing a replacement or rewrite from supplying unchecked metadata between validation and indexing. Reads and scans honor pause/cancellation and close the source on rejection. Browsing, integrity checks, extraction, and member Peek all use this opener, including cached provider archives.

Under the same 64 MiB heap, the 120,000-file fixture now exits normally with a limit error, while a valid **10,000-file** fixture opens successfully. Sixteen additional JVM regressions cover forged counts, aggregate metadata, central/local extra fields, malformed ZIP64 offsets, valid self-extracting archives (including empty ZIP64 archives with long prefixes) and long comments, cancellation, source closure, and a source rewrite before indexing. Native regressions check oversized and forged-count rejection without publishing extraction output, and verify empty ZIP64 self-extractor listing, checking, and extraction.

[Oversized archive on tablet](screenshots/audit-zip-metadata-tablet.png) · [Phone](screenshots/audit-zip-metadata-phone.png)

### Android 11 reconnect — ignored starting folder

Reconnect passed the saved bare tree URI as `EXTRA_INITIAL_URI`. Android 11's [DocumentsUI implementation](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-11.0.0_r48/src/com/android/documentsui/picker/ActionHandler.java) only launches an initial root or document URI, so it ignored that starting folder. Findroid now passes the connected root's document URI, preserving the same provider/document identity.

The opt-in `storageQaGrant=true` helper launches the existing reconnect path for exactly `Documents/Findroid-Storage-QA` and confirms through the real Android picker. It does not inject a grant. Before the fix it timed out at the storage root; after the fix it passed on the API 30 tablet, API 36 tablet, and API 36 phone.

The Android 11 check also exercised saved read-only permission, explicit revocation, and cold starts. Read-only access retained `Grant-check.txt` and disabled New folder. Revocation retained the original tab and showed **Folder access expired** / **Reconnect needed**. The normal Storage locations → Reconnect folder flow then opened the exact QA folder; real confirmation restored browsing across a cold start and allowed creation of `Restored-access`. Callback completion must be awaited before the cold restart; an earlier too-early restart and the original root-navigation attempts remain recorded separately. General shell-driven navigation from the system picker's storage root remains unreliable on this image; this change fixes the app's reconnect starting location.

[Read-only](screenshots/audit-api30-readonly.png) · [Revoked access](screenshots/audit-api30-revoked.png) · [Restored write access](screenshots/audit-api30-restored.png)

### PDF controls on phones — unreachable navigation and clipped zoom

Compact previews previously suppressed the thumbnails/bookmarks/contents sidebar, and the footer could clip the zoom buttons. Phones now open a full-width navigation panel from the PDF toolbar. Selecting a page, bookmark, contents item, or search match returns to the page. Zoom controls occupy their own centered row on narrow previews. Tablets retain the side-by-side navigation layout.

Native tests exercise the actual compact controls and save screenshots of the page, bookmarks, and document contents. Inspected captures: [page and zoom controls](screenshots/audit-pdf-phone-page.png), [bookmarks](screenshots/audit-pdf-phone-bookmarks.png), and [document contents](screenshots/audit-pdf-phone-contents.png).

### Android privacy and packaging

The existing no-backup policy is explicit for legacy backup and Android 12+ cloud/device transfer. All private credential/device-protected roots and app external files are excluded. Package validation checks the compiled manifest for backup settings, the exact `dev.findroid` identity, non-debuggable status, absence of app-owned INTERNET permission, the nonexported FileProvider grant contract, the MANAGE_DOCUMENTS-protected workspace provider, and exclusion of debug fixture authorities.

Commons Compress references optional XZ/Zstandard codecs in its general ZIP dispatcher. Narrow R8 rules cover those absent optional classes; Findroid rejects methods other than STORED/DEFLATED before opening member streams. JVM and Android tests exercise unsupported methods 12, 93, and 95.

New Commons and Guava license/notice assets are bundled and checked against the APK. The preview uses the development signing key; the release artifact remains unsigned.

## Dependency review

The initial resolved graph contained Guava **33.3.1-android**, matching **GHSA-xxph-c9ww-hj94 / CVE-2026-102554**. A Gradle constraint selects **33.7.2-android**. The post-change release graph has **114 runtime packages** and **zero matches in the recorded OSV query**. This is a dated database result, not a claim that every dependency is vulnerability-free.

A separate newly published, low-severity Guava hash-flooding advisory, **GHSA-v5x9-67m3-7m56**, had no patched release in the primary advisory and was not yet returned by the recorded OSV batch. It identifies `HashBiMap`, `HashMultimap`, and `LinkedHashMultimap`. Both final optimized variants list all three classes as entirely removed in R8 `usage.txt`, with no retained entries in `mapping.txt`; the exact checks are saved in `captures/audit-0.28.0/reachability.json`. The app does not expose a Java object-deserialization interface. Lint also flags a trust-all helper in Bouncy Castle's dependency; the empty `checkServerTrusted` implementation is `org.bouncycastle.est.jcajce.JcaJceUtils$1`. Bytecode inspection confirmed the warning, and both optimized variants remove the complete class. Lint retains the dependency-level warning because it analyzes input libraries before R8.

Primary sources:

- https://github.com/advisories/GHSA-xxph-c9ww-hj94
- https://github.com/google/guava/security/advisories/GHSA-v5x9-67m3-7m56
- https://developer.android.com/identity/data/autobackup
- https://developer.android.com/reference/android/provider/DocumentsContract
- https://commons.apache.org/proper/commons-compress/

## Original native verification results

The original audit coverage below uses the latest result for each class/method across its complete broad and focused runs. Subsequent metadata/reconnect verification is recorded separately below. The broad runs were **not clean single-run passes**: API 30 initially had six failures and API 36 four. Follow-up runs exercised the corrected harnesses, the affected workflows, and the two additional native regressions. All those failures are resolved in the combined record. `captures/audit-0.28.0/coverage-final.json` records the evidence log for every case.

| Device | Passing unique cases | Skipped | Unresolved failures | Coverage |
| --- | ---: | ---: | ---: | --- |
| API 30 tablet | 315 | 8 | 0 | 323 unique cases across broad + focused runs |
| API 36 tablet | 315 | 8 | 0 | 323 unique cases across broad + focused runs |
| API 36 phone | 48 | 0 | 0 | Final complete preview/storage/share run |

The original tablet skips were six phone-only workflows and two opt-in real-grant helpers. The original root-navigation attempt did not establish a grant; its `api30-picker-*` / `api30-grant-*` evidence remains preserved. The subsequent reconnect fix and real-grant acceptance are documented above and in the follow-up record below. Phone layouts are covered by the final 48-case run and the earlier 16-case input class. The full Android 11 storage/archive follow-up before the broad suite completed 42 cases: **41 passed and one phone-only case skipped**.

Harness fixes preserve the intended user interaction: mouse selection before Space instead of touch-to-open, waiting for actual input-window focus after dialog dismissal, waiting for native popup composition, scrolling checksum status into view, waiting for media readiness before seeking/toggling playback, and dismissing Android 11's software IME before testing a hardware Escape shortcut. The drag fixture must cancel the native drag before cancelling its injected pointer on Android 11. The ETA fixture uses fewer, more widely spaced progress updates so the slower emulator can reach UI-test idle while the transfer remains active. No production media or drag behavior was changed to satisfy these harness assertions.

Earlier failed baseline logs remain available: `tablet-full-baseline.txt`, `phone-safety-preview.txt`, `phone-regression.txt`, `phone-final.txt`, and the interrupted first `api30-full.txt`. The original collision regressions also failed before their fixes. These are kept separately from the final per-case results.

The exact packaged optimized preview cold-launched on all three devices without an AndroidRuntime crash. Real UI smoke checks on each device listed a ZIP64 member, passed its size/CRC check, extracted it, and rejected an unsupported codec with a recoverable error. The extracted text was additionally opened in Quick Look and verified on the API 30 tablet and phone. XML/log/screenshot evidence is retained as `*-optimized-*` artifacts.

## Follow-up verification after the interrupted audit

The metadata guard and reconnect fix passed the complete **301-case JVM suite**, with no failures/errors/skips. Focused native runs passed **15/15 on API 30** (ten archive cases plus five storage cases), **10/10 on the API 36 tablet**, and **10/10 on the API 36 phone**, without skips. The three real-grant helpers were explicitly enabled on API 30; the new real-picker grant helper also passed on each API 36 layout. These focused results supplement the earlier broad-suite table; no new full native-suite run is claimed. `captures/audit-0.28.0/archive-metadata/verification-final.json` records their final counts, real-grant logs, optimized smoke results and APK digests.

The first phone archive run passed the three engine/platform cases but failed six workflow cases because its helper expected a toolbar button hidden on phones. The helper now uses the normal tap-to-open action when that button is absent, and the complete nine-case phone rerun passed. Logs are `archive-metadata/phone-native.txt` and `phone-native-fixed.txt`. Under emulator load, a later native Space press produced two spaces; its exact-text assertion was corrected to check that search owns its spaces. The subsequent complete ten-case phone rerun passed. No production phone UI behavior was changed for these harness corrections.

The exact final optimized preview rejects the 120,000-entry ZIP with a recoverable limit error, opens the empty ZIP64 self-extractor, and lists/checks the ZIP64 member on all three emulators without an AndroidRuntime crash. Android 11 also retains the restored provider grant and synthetic files across a cold restart; its normal system-picker reconnect was additionally verified with the optimized app. Native runtime logs, XML and screenshots are retained under `captures/audit-0.28.0/`; bounded-heap and focused archive evidence is under its `archive-metadata/` subdirectory.

## Local validation and artifacts

- **301 JVM tests passed**, zero failures/errors/skips.
- Final Android lint: **zero errors, 137 warnings, 11 hints**. Categories: 67 KTX suggestions, 38 screen-configuration access suggestions, 16 dependency version suggestions, 11 state-boxing hints, 7 free-space access warnings, 5 additional version suggestions, and one each for target API, Gradle plugin version, modifier parameters, and the Bouncy Castle trust helper described above. The backup-policy warning was resolved. Updating the whole UI/SDK stack was not part of this safety increment.
- Debug/test builds, R8-optimized installable preview, and R8-optimized unsigned release all built. The first optimized attempt exposed absent optional XZ/Zstandard classes; narrow rules and explicit method-rejection coverage resolved it.
- Final packaging checks inspect the compiled backup XML as well as manifest attributes, signatures, provider permissions, license assets, package/version identity, and checksums.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `Findroid-0.28.0-preview.apk` | 8,045,619 | `24e9a41c8b2edf483cae2fd98e562317f8cf3fe7f8563a7fb11c64e33960e529` |
| `Findroid-0.28.0-unsigned.apk` | 8,037,419 | `761e04046cf5fec753c7dd7ee1fe353afe5a8cc646446a3fea8f1cf1aa3602e0` |

Artifacts, `SHA256SUMS`, and `BUILDINFO.json` are under `captures/release/0.28.0/`. The build records a dirty working tree; the existing rebrand and prior local changes were preserved, and this audit did not create a commit or claim a clean reproducible source revision.

## Repeating checks

Use JDK 17 and an installed Android SDK. On the audit machine:

```sh
export JAVA_HOME=/Users/LENOVO/Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1/Contents/Home
./scripts/check.sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/check-device.py --serial emulator-5582 --install \
  --output captures/audit-tablet.txt
python3 scripts/audit-dependencies.py --output captures/dependencies-audit.json
python3 scripts/package-preview.py --skip-build
```

`check-device.py` requires an explicit serial and parses instrumentation results instead of accepting adb exit status as proof of success. It rejects crashes, incomplete runs, and failed cases. JSON includes pass/skip/failure details. `audit-dependencies.py` fails if graph resolution or the advisory query is incomplete. GitHub Actions remains manual-only because hosted quota is exhausted.

## Release requirements outside this audit

- Production signing/ownership, distribution configuration, and an upgrade/recovery matrix for the final signing identity. The rebranded package installs separately from the pre-rebrand application ID.
- Physical tablet/phone testing, removable USB/SD storage, low-space/disconnect behavior on real volumes, and third-party provider/account latency and capability differences.
- TalkBack and external keyboard acceptance, large text, RTL/localization, stylus/trackpad and manufacturer window-management behavior.
- Measured startup/scroll/memory/battery benchmarks and actual multi-GiB archive payloads.
- Dependable hosted checks once quota is available. No quota/billing changes were made.
- Provider concurrency limits and the unpatched advisory must remain visible in release review; no clean-room security certification is claimed.
