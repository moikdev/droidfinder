"""Explicitly finish the reviewed trusted-path stop; never repeat an OTA or overwrite its receipt."""
import argparse
import fcntl
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shlex
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
MAIN = Path('/Users/LENOVO/.t3/projects/droidfinder')
ORIGINAL = MAIN / 'captures/persistent-undo'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True, choices=('emulator-5602', 'emulator-5582'))
p.add_argument('--attempt', required=True, type=Path)
p.add_argument('--review', required=True, type=Path)
a = p.parse_args()
a.attempt = a.attempt.resolve(); a.review = a.review.resolve()
assert a.attempt.is_relative_to(ROOT / 'captures/pdf-page-selection')
assert a.review.is_relative_to(ROOT / 'captures/pdf-page-selection')
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
original_path = a.attempt / 'verification.json'
original = json.loads(original_path.read_text())
review = json.loads(a.review.read_text())
assert review['explicitManualRestorationSelected'] and review['originalAttemptPreserved']
assert review['originalVerificationSha256'] == sha(original_path)
assert original['serial'] == a.serial and original['acceptanceComplete'] and not original['restorationComplete']
assert original['actualInstallerCompletionConfirmed'] and original['originalWorkspaceTreeKept']
public = MAIN / 'captures/release/0.49.0/Findroid-0.49.0-preview.apk'
assert sha(public) == original['apkSha256'] == 'c3e201f9129dfeef3d9e755c13a52eee684de7521834a0655b400a59cc5facf3'
final_path = a.attempt / 'verification-restoration-completed.json'
manual_path = a.attempt / 'manual-restoration.json'
assert not final_path.exists() and not manual_path.exists()
adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
PAIR = ('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
frozen_original = json.loads((ORIGINAL / 'candidate-build-6.json').read_text())
for name in PAIR: assert sha(MAIN / name) == frozen_original['files'][name]['sha256']

def call(*parts):
    return subprocess.run([adb, '-s', a.serial, *map(str, parts)], check=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180).stdout

def shell(*parts):
    return call('shell', '-T', shlex.join(map(str, parts)))

def installed(package):
    path = shell('pm', 'path', package).decode().strip().splitlines()[0].removeprefix('package:')
    return hashlib.sha256(call('exec-out', 'cat', path)).hexdigest()

def entries(data):
    values = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            name = member.name.rstrip('/')
            assert not Path(name).is_absolute() and '..' not in Path(name).parts
            assert member.isfile() or member.isdir()
            values[name] = hashlib.sha256(archive.extractfile(member).read()).hexdigest() if member.isfile() else None
    return values

spec = importlib.util.spec_from_file_location('explicit_public_ota_restoration', ORIGINAL / 'restore-public-tablets.py')
restore = importlib.util.module_from_spec(spec); spec.loader.exec_module(restore)
manual = dict(serial=a.serial, controllerSha256=sha(Path(__file__)), originalVerificationSha256=sha(original_path),
    explicitReviewSha256=sha(a.review), actualOtaNotRepeated=True, originalStoppedReceiptPreserved=True,
    restoredPublicApkSha256=sha(public), restorationComplete=False)
with (ORIGINAL / ('.' + a.serial + '-acceptance.lock')).open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    try:
        for name, package in zip(PAIR, ('dev.findroid', 'dev.findroid.test')):
            assert installed(package) == frozen_original['files'][name]['sha256'], 'Review any unexpected installed bytes before continuing'
        shell('am', 'force-stop', 'dev.findroid')
        managed = shell('run-as', 'dev.findroid', 'tar', '-cf', '-', '--exclude=files/Workspace',
            '--exclude=files/ProviderFixtures', 'shared_prefs', 'databases', 'no_backup', 'cache', 'files')
        with (a.attempt / 'PRIVATE-managed-after-path-stop.tar').open('xb') as out: out.write(managed)
        workspace = shell('run-as', 'dev.findroid', 'tar', '-cf', '-', 'files/Workspace')
        with (a.attempt / 'PRIVATE-workspace-after-path-stop.tar').open('xb') as out: out.write(workspace)
        assert entries(workspace) == entries((a.attempt / 'PRIVATE-workspace-before.tar').read_bytes())
        restored = restore.restore_one(a.serial, public)
        assert all(restored[k] for k in ('originalManagedMetadataDatabasesAndCacheRestored', 'originalFileAbsencesRestored',
            'originalDirectoryAbsencesRestored', 'originalUriGrantsKept', 'originalStorageModeRestored', 'originalScreenSizeRestored'))
        assert installed('dev.findroid') == sha(public)
        assert installed('dev.findroid.test') == frozen_original['files'][PAIR[1]]['sha256']
        # The original helper verifies metadata/grants/environment. It never changes Workspace.
        manual.update(restorationComplete=True, originalWorkspaceTreeKept=True, restoration=restored)
        completed = dict(original)
        completed.update(restorationComplete=True, originalWorkspaceTreeKept=True, originalDataRestored=True,
            originalUriGrantsKept=True, restoration=restored, originalStoppedVerification=str(original_path.relative_to(ROOT)),
            originalStoppedVerificationSha256=sha(original_path), explicitManualRestoration=True,
            manualRestorationReceipt=str(manual_path.relative_to(ROOT)), manualRestorationControllerSha256=sha(Path(__file__)))
        completed.pop('restorationError', None)
        with final_path.open('x') as out: out.write(json.dumps(completed, indent=2) + '\n')
    except BaseException as error:
        manual['error'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        with manual_path.open('x') as out: out.write(json.dumps(manual, indent=2) + '\n')
print(json.dumps(dict(serial=a.serial, restorationComplete=True, finalReceipt=str(final_path.relative_to(ROOT)))))
