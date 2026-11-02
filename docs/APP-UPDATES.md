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

Source code, installable APKs, `findroid-update.json`, checksums and release notes are published together in **`moikdev/droidfinder`**. The updater reads that repository’s public releases, including previews, and checks up to five recent release manifests for the highest newer version code. No separate feed repository or GitHub token is needed.

The initial public updater build is **0.29.0-preview**. Installing it enables future updates from this repository. An older pre-0.26 DroidFinder APK has a different app ID and installs separately; its private Workspace must be copied out before uninstalling it.

Run `scripts/package-preview.py --notes <release-notes-file>` with JDK 17 to build and validate the preview and unsigned release. The script writes the signed installable APK, unsigned release, SHA256SUMS, BUILDINFO.json and `findroid-update.json` under `captures/release/<version>/`. The manifest contains the exact APK digest/size and its release asset URL. Publish the signed preview and manifest together under the matching `v<version>-preview` tag in `moikdev/droidfinder`. The unsigned release cannot be installed directly.

Keep the signing key stable and backed up privately. These development previews use the existing local development key. Never upload the key to the feed or embed it in the APK. Hosted CI remains manual-only.

## Validation

JVM tests cover manifest bounds, source restrictions, credential/cleartext rejection, path traversal and tampered/truncated/oversized downloads. Native tests use APK fixtures generated with the current checkout's debug key; the fixture private key and APKs stay in ignored build outputs and never enter a shipping build. Native checks cover package/version/certificate/Android compatibility, cancellation/retry, cached-download verification and recovery, update controls, feed errors and Android's installer handoff.

Build and native results are recorded in `VALIDATION.md`. The release also includes a validation archive with the artifact manifest, final test logs, installation-preservation evidence and live feed checks. No physical-device or third-party provider success is implied by emulator checks.


To run the opt-in public-feed acceptance check after installing the debug and test APKs on a dedicated emulator:

```sh
adb -s emulator-5582 shell am instrument -w -r \
  -e class dev.findroid.AppUpdateLiveFeedTest -e liveUpdates true \
  dev.findroid.test/androidx.test.runner.AndroidJUnitRunner
```

This downloads the actual public release through the production source, checks its bytes/package/version/signing certificate and confirms that its version is not offered again. It does not install an equal-version APK. Installer acceptance is covered separately by the native installer and permission-flow checks.
