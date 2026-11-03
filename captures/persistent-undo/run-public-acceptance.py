"""Run actual public 46-to-47 updates on only the assigned tablets, then restore originals."""
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import fcntl
import hashlib
import json
import subprocess

root = Path(__file__).resolve().parents[2]
proof = Path(__file__).resolve().parent
public = proof / "public/Findroid-0.45.0-preview.apk"
previous = root / "captures/release/0.44.0/Findroid-0.44.0-preview.apk"
assert json.loads((proof / "local-acceptance.json").read_text())["localAcceptancePassed"]
receipt = json.loads((proof / "public-downloads.json").read_text())
expected = next(row["sha256"] for row in receipt["assets"] if row["name"] == public.name)
assert receipt["public"] and hashlib.sha256(public.read_bytes()).hexdigest() == expected

def update(serial):
    api = "api30" if serial == "emulator-5602" else "api36"
    with (proof / ("." + serial + "-acceptance.lock")).open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        with (proof / (api + "-public-ota-driver.txt")).open("x") as log:
            subprocess.run(["python3", str(root / "scripts/check-ota-upgrade.py"), "--serial", serial,
                "--from-apk", str(previous), "--to-apk", str(public), "--expected-version-code", "47",
                "--output", str(proof / (api + "-ota"))], check=True, stdout=log, stderr=subprocess.STDOUT)
    return api

errors = []
try:
    with ThreadPoolExecutor(max_workers=2) as pool:
        tasks = {pool.submit(update, serial): serial for serial in ("emulator-5602", "emulator-5582")}
        for task in as_completed(tasks):
            try:
                print(task.result() + " actual public update passed", flush=True)
            except Exception as error:
                errors.append(tasks[task] + ": " + str(error))
                print(errors[-1], flush=True)
    if not errors:
        with (proof / ".emulator-5582-acceptance.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            with (proof / "api36-public-feed-driver.txt").open("x") as log:
                subprocess.run(["python3", str(proof / "check-live-feed.py")], check=True,
                    stdout=log, stderr=subprocess.STDOUT)
finally:
    with (proof / "after-public-acceptance-restoration.txt").open("x") as log:
        restored = subprocess.run(["python3", str(proof / "restore-public-tablets.py"),
            "--public-apk", str(public)], stdout=log, stderr=subprocess.STDOUT)
    if restored.returncode:
        errors.append("Restoration needs review; inspect the retained log.")
assert not errors, "\n".join(errors)
print("Both actual public updates, production source, and original-state restoration passed.", flush=True)
