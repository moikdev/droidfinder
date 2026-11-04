"""Run the actual public 50→51 OTA, then restore all original bytes and the public 0.49 APK."""
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
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
MAIN = Path('/Users/LENOVO/.t3/projects/droidfinder')
PROOF = MAIN/'captures/persistent-undo'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, choices=('emulator-5602','emulator-5582'))
parser.add_argument('--output', required=True, type=Path)
a = parser.parse_args()
assert not a.output.exists(), 'Preserve earlier OTA attempts'
adb = str(Path.home()/'Library/Android/sdk/platform-tools/adb')
old = MAIN/'captures/release/0.48.0/Findroid-0.48.0-preview.apk'
new = ROOT/'captures/release/0.49.0/Findroid-0.49.0-preview.apk'
restored_public = MAIN/'captures/release/0.49.0/Findroid-0.49.0-preview.apk'
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(new) == sha(restored_public)


def call(*parts):
    return subprocess.run([adb,'-s',a.serial,*map(str,parts)], check=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180).stdout


def shell(*parts):
    return call('shell','-T',shlex.join(map(str,parts)))


def installed(package='dev.findroid'):
    path = shell('pm','path',package).decode().strip().splitlines()[0].removeprefix('package:')
    return hashlib.sha256(call('exec-out','cat',path)).hexdigest()


def entries(payload):
    result = {}
    with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
        for row in archive:
            name = row.name.rstrip('/')
            assert not Path(name).is_absolute() and '..' not in Path(name).parts and (row.isfile() or row.isdir())
            result[name] = hashlib.sha256(archive.extractfile(row).read()).hexdigest() if row.isfile() else None
    return result


spec = importlib.util.spec_from_file_location('recovery_public_original_restoration', PROOF/'restore-public-tablets.py')
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)
with (PROOF/('.'+a.serial+'-acceptance.lock')).open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX|fcntl.LOCK_NB)
    a.output.mkdir(parents=True)
    frozen = json.loads((ROOT/'captures/pdf-page-selection/candidate-build-2.json').read_text())
    for path,row in frozen['files'].items():
        assert sha(ROOT/path) == row['sha256']
    assert sha(new) == frozen['files']['captures/release/0.49.0/Findroid-0.49.0-preview.apk']['sha256']
    for path,digest in frozen['sourceFiles'].items(): assert sha(ROOT/path)==digest
    url = 'https://github.com/moikdev/findroid/releases/download/v0.49.0-preview/Findroid-0.49.0-preview.apk'
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent':'Findroid-public-acceptance'}), timeout=120) as response:
        anonymous = response.read(16*1024*1024)
    assert hashlib.sha256(anonymous).hexdigest() == sha(new) and len(anonymous) == new.stat().st_size
    report = dict(serial=a.serial, expectedVersionCode=51, fromVersionCode=50,
        apkSha256=sha(new), sourceCommit=frozen['sourceCommit'],
        controllerSha256=sha(Path(__file__)), publicAnonymousApkBytesMatch=True,
        acceptanceComplete=False, restorationComplete=False, originalDataRestored=False, originalUriGrantsKept=False)
    verified = False
    workspace = None
    try:
        assert installed() in {sha(old),sha(new)}, 'Start only from an exact accepted public APK'
        report['startingInstalledApkSha256'] = installed()
        shell('am','force-stop','dev.findroid')
        call('install','-r',ROOT/'app/build/outputs/apk/debug/app-debug.apk')
        shell('am','force-stop','dev.findroid')
        payload = shell('run-as','dev.findroid','tar','-cf','-','--exclude=files/Workspace','--exclude=files/ProviderFixtures',
            'shared_prefs','databases','no_backup','cache','files')
        (a.output/'PRIVATE-managed-before.tar').write_bytes(payload)
        baseline = json.loads((PROOF/'private-native-state'/a.serial/'PRIVATE-MANIFEST.json').read_text())['files']
        assert {n:v for n,v in entries(payload).items() if v is not None} == baseline
        workspace = shell('run-as','dev.findroid','tar','-cf','-','files/Workspace')
        (a.output/'PRIVATE-workspace-before.tar').write_bytes(workspace)
        verified = True
        result = subprocess.run(['python3',str(ROOT/'scripts/check-ota-upgrade.py'),
            '--serial',a.serial,'--from-apk',str(old),'--to-apk',str(new),'--expected-version-code','51',
            '--output',str(a.output/'public-ota')],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        (a.output/'ota-driver.txt').write_bytes(result.stdout)
        assert result.returncode == 0, 'Public OTA stopped; inspect the retained driver and screenshots'
        ota = json.loads((a.output/'public-ota/verification.json').read_text())
        assert (ota['fromVersionCode'],ota['toVersionCode']) == (50,51)
        assert all(ota[key] for key in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes',
            'installerCompletionConfirmedBeforeRelaunch','originalFileBytes','legacyFavoritePreserved',
            'sentinelPreferencePreserved','updatedSidebarVisible','noRepeatOffer','restoredOriginalSetup'))
        assert ota['restoredApkSha256'] == sha(new) and installed() == sha(new)
        report['publicFeedUpdateVerified'] = True
        report['installedApkSha256'] = installed()
        report['actualInstallerCompletionConfirmed'] = True
        report['acceptanceComplete'] = True
    except BaseException as error:
        report['error'] = type(error).__name__+': '+str(error)
        raise
    finally:
        try:
            shell('am','force-stop','dev.findroid')
            for path in (MAIN/'app/build/outputs/apk/debug/app-debug.apk', MAIN/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
                call('install','-r','-d',path)
            if verified:
                after = shell('run-as','dev.findroid','tar','-cf','-','files/Workspace')
                (a.output/'PRIVATE-workspace-after.tar').write_bytes(after)
                report['originalWorkspaceTreeKept'] = entries(after) == entries(workspace)
                report['restoration'] = restore.restore_one(a.serial, restored_public)
                report['originalDataRestored'] = report['restoration']['originalManagedMetadataDatabasesAndCacheRestored']
                report['originalUriGrantsKept'] = report['restoration']['originalUriGrantsKept']
                assert installed() == sha(new) and report['originalWorkspaceTreeKept']
                report['restorationComplete'] = True
            else:
                call('install','-r','-d',old)
        except BaseException as error:
            report['restorationError'] = type(error).__name__+': '+str(error)
            raise
        finally:
            (a.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
