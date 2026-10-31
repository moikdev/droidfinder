#!/usr/bin/env python3
"""Build and validate development-signed preview artifacts; never publishes or changes signing keys."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
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
    if any("dev.droidfinder.fixtures" in provider.get(ANDROID + "authorities", "")
           for provider in application.findall("provider")):
        raise SystemExit(f"Debug fixture provider is present: {apk}")
    return root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", action="store_true", help="Also run UI tests on ANDROID_SERIAL after building")
    parser.add_argument("--skip-build", action="store_true", help="Validate/package already-built APKs")
    args = parser.parse_args()
    if args.device and not os.environ.get("ANDROID_SERIAL"):
        parser.error("--device requires ANDROID_SERIAL for a dedicated tablet/emulator")
    if not args.skip_build:
        run(ROOT / "gradlew", "testDebugUnitTest", "lintDebug", "assemblePreview", "assembleRelease")
    if args.device:
        run(ROOT / "gradlew", "connectedDebugAndroidTest")
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
            release_info.get(ANDROID + "versionCode") != preview_info.get(ANDROID + "versionCode")):
        raise SystemExit("Preview and unsigned release versions differ; rebuild both variants.")
    run(signer, "verify", "--verbose", preview)
    destination = ROOT / "captures/release" / base_version
    destination.mkdir(parents=True, exist_ok=True)
    artifacts = []
    for source, name in [(preview, f"DroidFinder-{version}.apk"),
                         (release, f"DroidFinder-{base_version}-unsigned.apk")]:
        target = destination / name
        shutil.copyfile(source, target)
        artifacts.append({"name": name, "bytes": target.stat().st_size, "sha256": digest(target)})
    (destination / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {item['name']}\n" for item in artifacts))
    metadata = {"version": version, "version_code": preview_info.get(ANDROID + "versionCode"),
                "source_commit": run("git", "rev-parse", "HEAD", capture=True),
                "working_tree_dirty": bool(run("git", "status", "--porcelain", capture=True)),
                "artifacts": artifacts}
    (destination / "BUILDINFO.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps({"directory": str(destination), **metadata}, indent=2))


if __name__ == "__main__":
    main()
