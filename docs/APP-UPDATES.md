# App updates

The 0.29.0 preview adds an in-app updater at **More → App updates**, also linked from Preferences. The tablet toolbar stays simple. This is an APK update flow: Android presents its installation confirmation, and may first ask you to allow Findroid as an installation source.

## Behavior

- Check automatically is enabled initially. Checks run at most once per day when the app opens or resumes. It can be disabled; manual checks remain available.
- Downloads start only after **Download update**. Progress, cancellation, retry and release notes are visible in the update dialog.
- A download must match the declared size and SHA-256. Its package must be `dev.findroid`, its version code must be newer, its version name must match the manifest, its minimum Android version must be supported, and its signing certificate must match the installed app.
- The downloaded package is verified again before installation. Completed downloads can be recovered after a process restart; incomplete and invalid cached packages are discarded.
- Updating preserves Android's existing app identity and data. Do not uninstall the app to update it. Subsequent APKs must use the same signing key and an increasing version code.
- No file content or credentials are uploaded. HTTPS requests fetch release metadata and the APK. The APK contains no GitHub access token. Network errors do not block file browsing.

## Release feed and activation

Source code, installable APKs, `findroid-update.json`, checksums and release notes are published together in **`moikdev/findroid`**. The updater reads that repository’s public releases, including previews, and checks up to five recent release manifests for the highest newer version code. No separate feed repository or GitHub token is needed.

The initial public updater build is [**0.29.0-preview**](https://github.com/moikdev/findroid/releases/tag/v0.29.0-preview). The repository was subsequently renamed from `moikdev/droidfinder` to `moikdev/findroid`. Version 0.29 rejects the renamed feed; install the **current preview APK** once, over the existing app. Version 0.30 reads the current repository and accepts the known old release URLs in published manifests and cached downloads. Versions 0.30 and later use the in-app controls, including the 0.31.0 Favorites update. An older pre-0.26 DroidFinder APK has a different app ID and installs separately; its private Workspace must be copied out before uninstalling it.

Run `scripts/package-preview.py --notes <release-notes-file>` with JDK 17 to build and validate the preview and unsigned release. The script writes the signed installable APK, unsigned release, SHA256SUMS, BUILDINFO.json and `findroid-update.json` under `captures/release/<version>/`. The manifest contains the exact APK digest/size and its release asset URL. Publish the signed preview and manifest together under the matching `v<version>-preview` tag in `moikdev/findroid`. The unsigned release cannot be installed directly.

Keep the signing key stable and backed up privately. These development previews use the existing local development key. Never upload the key to the feed or embed it in the APK. Hosted CI remains manual-only.

## Validation

JVM tests cover manifest bounds, source restrictions, credential/cleartext rejection, path traversal and tampered/truncated/oversized downloads. Native tests use APK fixtures generated with the current checkout's debug key; the fixture private key and APKs stay in ignored build outputs and never enter a shipping build. Native checks cover package/version/certificate/Android compatibility, cancellation/retry, cached-download verification and recovery, update controls, feed errors and Android's installer handoff.

Build and native results are recorded in [VALIDATION.md](../VALIDATION.md). Public manifest/APK downloads through the production source passed on both API 30 and API 36. The exact optimized preview shows [“You’re up to date”](screenshots/app-updates-030.png) against the published feed. The release also includes a validation archive with the artifact manifest, final test logs, installation-preservation evidence and live feed checks. No physical-device or third-party provider success is implied by emulator checks.


The **0.31.0** release additionally passes a real **0.30 → 0.31** in-app download and Android installer confirmation on the API 30 tablet. File bytes, saved favorites, and a sentinel preference are retained, and the upgraded app reports [“You're up to date”](screenshots/app-updates-031.png). The production-source public APK download/package/version/signer check also passes on API 36. The validation archive includes these reports and actual updater screenshots.

To run the opt-in public-feed acceptance check after installing the debug and test APKs on a dedicated emulator:

```sh
adb -s emulator-5582 shell am instrument -w -r \
  -e class dev.findroid.AppUpdateLiveFeedTest -e liveUpdates true \
  dev.findroid.test/androidx.test.runner.AndroidJUnitRunner
```

This downloads the actual public release through the production source, checks its bytes/package/version/signing certificate and confirms that its version is not offered again. It does not install an equal-version APK. Installer acceptance is covered separately by the native installer and permission-flow checks.

## 0.32.0 public upgrade acceptance

A real 0.31 → 0.32 public-feed download and Android installation passes on the dedicated API30 tablet emulator. Installed APK bytes match the public release; the original synthetic file, legacy favorite and sentinel preference survive. The new version reports up to date. The API36 production-source check validates the public package/version/signature and rejects a repeat offer. Both devices are restored to the exact public optimized APK. This is emulator acceptance; the broader physical/OEM update matrix remains open.

Repeat the installed-update workflow with `scripts/check-ota-upgrade.py`. It requires the older signed APK, anonymously downloaded target APK, expected version code and a dedicated tablet serial; it backs up/restores the original setup and uses its own synthetic data. Actual result: [0.32.0 updater](screenshots/app-updates-032.png).

## 0.33.0 public upgrade acceptance

The real **0.32 → 0.33** in-app public-feed download and Android installer upgrade passes on the dedicated API30 tablet. The installed APK matches the anonymously downloaded public bytes. The original synthetic file, Favorite and unrelated preference survive, and the updated app reports [up to date](screenshots/app-updates-033.png). The production-source API36 check passes 1/1 for the public package/version/signature and no repeat offer. Both tablets are restored to the exact public optimized APK.

The [setup backup guide](SETUP-BACKUP.md) explains 0.33's explicit export and reviewed import. Setup backups exclude updater state and Android installation permissions. The release keeps 0.32's signing certificate; continue installing over the existing app. Physical/OEM installation acceptance remains open.
