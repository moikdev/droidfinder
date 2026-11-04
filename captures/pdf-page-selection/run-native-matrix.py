"""Run frozen PDF native regression while preserving exact original tablet state."""
import argparse
import fcntl
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
MAIN = Path('/Users/LENOVO/.t3/projects/droidfinder')
ORIGINAL = MAIN / 'captures/persistent-undo'
PAIR = ('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True, choices=('emulator-5602', 'emulator-5582'))
p.add_argument('--output', required=True, type=Path)
p.add_argument('--build-receipt', type=Path, required=True)
p.add_argument('--expected-cases', type=int, required=True)
a = p.parse_args()
a.build_receipt = a.build_receipt.resolve()
assert a.build_receipt.is_relative_to(ROOT / 'captures/pdf-page-selection') and a.expected_cases > 0
a.output = a.output.resolve()
assert a.output.is_relative_to(ROOT / 'captures/pdf-page-selection') and not a.output.exists()
adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
public = MAIN / 'captures/release/0.48.0/Findroid-0.48.0-preview.apk'
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(public) == '3e624f98fcbe354d45d3581cd066cb0f2d02e0d6b2c44a66d79b6955e3a8653a'
build = json.loads(a.build_receipt.read_text())
assert build['jvm'] == dict(tests=948, failures=0, errors=0, skipped=0)
for name, row in build['files'].items():
    assert sha(ROOT / name) == row['sha256'], ('Draft bytes changed; preserve and rebuild the draft receipt', name)
for name, digest in build.get('sourceFiles', {}).items():
    assert sha(ROOT / name) == digest, ('Draft source differs from its compiled receipt', name)
original_build = json.loads((ORIGINAL / 'candidate-build-6.json').read_text())
for name in PAIR:
    assert sha(MAIN / name) == original_build['files'][name]['sha256']


def call(*parts):
    return subprocess.run([adb, '-s', a.serial, *map(str, parts)], check=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180).stdout


def shell(*parts):
    return call('shell', '-T', shlex.join(map(str, parts)))


def installed(package):
    path = shell('pm', 'path', package).decode().strip().splitlines()[0].removeprefix('package:')
    return hashlib.sha256(call('exec-out', 'cat', path)).hexdigest()


def entries(payload):
    result = {}
    with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
        for member in archive:
            name = member.name.rstrip('/')
            assert not name.startswith('/') and '..' not in Path(name).parts
            assert member.isfile() or member.isdir(), 'Unexpected archive entry; preserve it privately for review'
            result[name] = hashlib.sha256(archive.extractfile(member).read()).hexdigest() if member.isfile() else None
    return result


spec = importlib.util.spec_from_file_location('restore_originals', ORIGINAL / 'restore-public-tablets.py')
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)
with (ORIGINAL / ('.' + a.serial + '-acceptance.lock')).open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    assert installed('dev.findroid') == sha(public), 'Require exact accepted public 0.48 and completed restoration'
    a.output.mkdir(parents=True)
    copies = []
    for name in PAIR:
        copy = a.output / ('PRIVATE-' + Path(name).name)
        shutil.copy2(ROOT / name, copy)
        assert sha(copy) == build['files'][name]['sha256']
        copies.append(copy)
    source = {str(f.relative_to(ROOT)): sha(f) for f in sorted((ROOT / 'app/src').rglob('*')) if f.is_file()}
    receipt = dict(serial=a.serial, draftOnly=False, frozenReleaseCandidate=True,
        sourceCommit=build['sourceCommit'], sourceSha256=source,
        controllerSha256=sha(Path(__file__)), draftBuildReceiptSha256=sha(a.build_receipt), draftBuildReceipt=str(a.build_receipt.relative_to(ROOT)),
        expectedNativeCases=a.expected_cases,
        apkSha256={name: sha(ROOT / name) for name in PAIR}, main045RestorationPairUnchanged=True,
        immutableOriginalBytesVerifiedBeforeTests=False, nativeRegressionComplete=False, restorationComplete=False)
    verified = False
    workspace = None
    try:
        shell('am', 'force-stop', 'dev.findroid')
        for copy in copies:
            call('install', '-r', copy)
        shell('am', 'force-stop', 'dev.findroid')
        assert {name: installed(package) for name, package in zip(PAIR, ('dev.findroid', 'dev.findroid.test'))} == receipt['apkSha256']
        managed = shell('run-as', 'dev.findroid', 'tar', '-cf', '-', '--exclude=files/Workspace',
            '--exclude=files/ProviderFixtures', 'shared_prefs', 'databases', 'no_backup', 'cache', 'files')
        (a.output / 'PRIVATE-managed-before.tar').write_bytes(managed)
        baseline = json.loads((ORIGINAL / 'private-native-state' / a.serial / 'PRIVATE-MANIFEST.json').read_text())['files']
        assert {name: value for name, value in entries(managed).items() if value is not None} == baseline
        workspace = shell('run-as', 'dev.findroid', 'tar', '-cf', '-', 'files/Workspace')
        (a.output / 'PRIVATE-workspace-before.tar').write_bytes(workspace)
        verified = True
        receipt['immutableOriginalBytesVerifiedBeforeTests'] = True
        with (a.output / 'batch-driver.txt').open('x') as log:
            result = subprocess.run(['python3', str(ROOT / 'scripts/check-device-batches.py'),
                '--serial', a.serial, '--classes-file', str(ROOT / 'captures/pdf-page-selection/native-regression-classes.txt'),
                '--output', str(a.output / 'native.txt'), '--batch-directory', str(a.output / 'batches'),
                '--reset-script', str(ROOT / 'captures/pdf-page-selection/native-matrix-reset.py')],
                cwd=ROOT, env={**os.environ, 'ANDROID_HOME': str(Path.home() / 'Library/Android/sdk'),
                'FINDROID_PDF_NATIVE_OUTPUT': str(a.output)}, stdout=log, stderr=subprocess.STDOUT)
        receipt['batchDriverExitCode'] = result.returncode
        if (a.output / 'native.json').exists():
            native = json.loads((a.output / 'native.json').read_text())
            receipt.update(nativePassed=native['passed'], nativeFailed=native['failed'], nativeSkipped=native['skipped'])
            assert result.returncode == 0 and native['success'] and native['passed'] == a.expected_cases and native['failed'] == native['skipped'] == 0
            receipt['nativeRegressionComplete'] = True
        else:
            raise RuntimeError('Native regression stopped; retain every attempt and inspect the batch driver')
    except BaseException as error:
        receipt['error'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        try:
            shell('am', 'force-stop', 'dev.findroid')
            for name in PAIR:
                call('install', '-r', '-d', MAIN / name)
            if verified:
                after = shell('run-as', 'dev.findroid', 'tar', '-cf', '-', 'files/Workspace')
                (a.output / 'PRIVATE-workspace-after.tar').write_bytes(after)
                receipt['originalWorkspaceTreeKept'] = entries(after) == entries(workspace)
                receipt['restoration'] = restore.restore_one(a.serial, public)
                assert receipt['originalWorkspaceTreeKept'], 'Preserve unexpected Workspace differences privately'
                receipt['restorationComplete'] = True
            else:
                call('install', '-r', '-d', public)
                receipt['originalStateKeptWithoutBaselineOverwrite'] = True
            assert installed('dev.findroid') == sha(public)
            assert installed('dev.findroid.test') == original_build['files'][PAIR[1]]['sha256']
            assert source == {str(f.relative_to(ROOT)): sha(f) for f in sorted((ROOT / 'app/src').rglob('*')) if f.is_file()}
        except BaseException as error:
            receipt['restorationError'] = type(error).__name__ + ': ' + str(error)
            raise
        finally:
            (a.output / 'build-provenance.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({k: receipt[k] for k in ('serial', 'nativeRegressionComplete', 'nativePassed', 'restorationComplete')}), flush=True)
