#!/usr/bin/env python3
"""Build and validate development-signed preview artifacts; never publishes or changes signing keys."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"


def run(*args, capture=False):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True,
                            text=True, stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def sdk_directory():
    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if configured:
        return Path(configured)
    properties = ROOT / "local.properties"
    if properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.removeprefix("sdk.dir=").replace("\\ ", " ").replace("\\:", ":"))
    raise SystemExit("Set ANDROID_SDK_ROOT or sdk.dir in local.properties.")


def manifest(analyzer, apk):
    root = ET.fromstring(run(analyzer, "manifest", "print", apk, capture=True))
    application = root.find("application")
    if application is None or application.get(ANDROID + "debuggable", "false") == "true":
        raise SystemExit(f"Refusing to package a debuggable or invalid app: {apk}")
    if root.get("package") != "dev.findroid":
        raise SystemExit(f"Unexpected application ID: {apk}")
    if (application.get(ANDROID + "allowBackup") != "false" or
            application.get(ANDROID + "fullBackupContent") != "false" or
            not application.get(ANDROID + "dataExtractionRules")):
        raise SystemExit(f"Private file-management state must not be backed up implicitly: {apk}")
    rules_path = run(analyzer, "resources", "value", "--config", "default", "--type", "xml",
                     "--name", "data_extraction_rules", apk, capture=True)
    rules = ET.fromstring(run(analyzer, "resources", "xml", "--file", rules_path, apk, capture=True))
    domains = {"root", "file", "database", "sharedpref", "external", "device_root",
               "device_file", "device_database", "device_sharedpref"}
    for mode in ("cloud-backup", "device-transfer"):
        policy = rules.find(mode)
        excluded = set() if policy is None else {
            node.get("domain") for node in policy.findall("exclude") if node.get("path") == "."}
        if not domains.issubset(excluded) or (policy is not None and policy.findall("include")):
            raise SystemExit(f"Private data is not fully excluded from {mode}: {apk}")
    permissions = {permission.get(ANDROID + "name") for permission in root.findall("uses-permission")}
    if not {"android.permission.INTERNET", "android.permission.REQUEST_INSTALL_PACKAGES"}.issubset(permissions):
        raise SystemExit(f"Missing app update permissions: {apk}")
    if application.get(ANDROID + "usesCleartextTraffic") != "false":
        raise SystemExit(f"App update traffic must require HTTPS: {apk}")
    files = [provider for provider in application.findall("provider")
             if provider.get(ANDROID + "name") == "androidx.core.content.FileProvider"]
    if len(files) != 1 or any(files[0].get(ANDROID + key) != value for key, value in {
            "exported": "false", "grantUriPermissions": "true", "authorities": "dev.findroid.files"}.items()):
        raise SystemExit(f"File sharing provider contract is invalid: {apk}")
    with zipfile.ZipFile(apk) as archive:
        expected = ["assets/" + str(path.relative_to(ROOT / "app/src/main/assets"))
                    for path in (ROOT / "app/src/main/assets/licenses").glob("*") if path.is_file()]
        missing = sorted(set(expected) - set(archive.namelist()))
        if missing:
            raise SystemExit(f"Missing third-party license assets in {apk}: {missing}")
    if any(".fixtures" in provider.get(ANDROID + "authorities", "")
           for provider in application.findall("provider")):
        raise SystemExit(f"Debug fixture provider is present: {apk}")
    workspace = [provider for provider in application.findall("provider")
                 if provider.get(ANDROID + "name", "").endswith(".WorkspaceDocumentsProvider")]
    if len(workspace) != 1 or any(workspace[0].get(ANDROID + key) != value for key, value in {
            "permission": "android.permission.MANAGE_DOCUMENTS",
            "exported": "true", "grantUriPermissions": "true",
            "authorities": root.get("package", "") + ".documents"}.items()):
        raise SystemExit(f"Workspace provider permission/grant contract is invalid: {apk}")
    return root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--notes", type=Path, help="Release notes included in the update manifest")
    parser.add_argument("--device", action="store_true", help="Also run UI tests on ANDROID_SERIAL after building")
    parser.add_argument("--skip-build", action="store_true", help="Validate/package already-built APKs")
    args = parser.parse_args()
    if args.device and not os.environ.get("ANDROID_SERIAL"):
        parser.error("--device requires ANDROID_SERIAL for a dedicated tablet/emulator")
    if not args.skip_build:
        run(ROOT / "gradlew", ":app:testDebugUnitTest", ":app:lintDebug",
            ":app:assemblePreview", ":app:assembleRelease")
    if args.device:
        run(ROOT / "gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest")
        run(sys.executable, ROOT / "scripts/check-device.py", "--serial", os.environ["ANDROID_SERIAL"],
            "--install", "--output", ROOT / "captures/package-device-check.txt")
    sdk = sdk_directory()
    analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    if not analyzer.exists():
        raise SystemExit("Install Android SDK command-line tools (latest) for APK inspection.")
    build_tools = [p for p in (sdk / "build-tools").iterdir() if (p / "apksigner").exists()]
    if not build_tools:
        raise SystemExit("Install Android SDK build-tools for signature validation.")
    signer = sorted(build_tools, key=lambda p: tuple(int(s) if s.isdigit() else -1 for s in p.name.split(".")))[-1] / "apksigner"
    preview = ROOT / "app/build/outputs/apk/preview/app-preview.apk"
    release = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
    preview_info, release_info = manifest(analyzer, preview), manifest(analyzer, release)
    version = preview_info.get(ANDROID + "versionName", "")
    if not version.endswith("-preview"):
        raise SystemExit("Expected a preview version suffix.")
    base_version = version.removesuffix("-preview")
    if (release_info.get(ANDROID + "versionName") != base_version or
            release_info.get(ANDROID + "versionCode") != preview_info.get(ANDROID + "versionCode") or
            release_info.get("package") != preview_info.get("package")):
        raise SystemExit("Preview and unsigned release versions differ; rebuild both variants.")
    run(signer, "verify", "--verbose", preview)
    destination = ROOT / "captures/release" / base_version
    destination.mkdir(parents=True, exist_ok=True)
    artifacts = []
    for source, name in [(preview, f"Findroid-{version}.apk"),
                         (release, f"Findroid-{base_version}-unsigned.apk")]:
        target = destination / name
        shutil.copyfile(source, target)
        artifacts.append({"name": name, "bytes": target.stat().st_size, "sha256": digest(target)})
    update = {
        "packageName": "dev.findroid", "versionCode": int(preview_info.get(ANDROID + "versionCode")),
        "versionName": version, "apkName": artifacts[0]["name"], "bytes": artifacts[0]["bytes"],
        "sha256": artifacts[0]["sha256"],
        "downloadUrl": f"https://github.com/moikdev/findroid/releases/download/v{version}/{artifacts[0]['name']}",
        "notes": args.notes.read_text()[:4000] if args.notes else "Improvements and fixes for Findroid.",
    }
    (destination / "findroid-update.json").write_text(json.dumps(update, indent=2) + "\n")
    (destination / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {item['name']}\n" for item in artifacts))
    metadata = {"version": version, "version_code": preview_info.get(ANDROID + "versionCode"),
                "application_id": preview_info.get("package"),
                "source_commit": run("git", "rev-parse", "HEAD", capture=True),
                "working_tree_dirty": bool(run("git", "status", "--porcelain", capture=True)),
                "signing": "development-preview; release APK is unsigned",
                "artifacts": artifacts}
    (destination / "BUILDINFO.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps({"directory": str(destination), **metadata}, indent=2))


if __name__ == "__main__":
    main()
