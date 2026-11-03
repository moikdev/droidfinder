"""Preserve original metadata; use shell-v2 exit status for optional binary reads."""
import concurrent.futures
import json
from pathlib import Path
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
ADB = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
FILES = ['tags.properties', 'workspaces.json', 'folder-views.json', 'folder-styles.properties',
         'rename-presets.properties', 'setup-previous.json', 'document-parents.properties']
SERIALS = {'emulator-5582', 'emulator-5602'}


def original_snapshot(serial):
    """Keep old backups immutable, recognizing only an exact raw-exec missing-file diagnostic.

    Old adb exec-out captures did not transport the remote exit status. One known absent
    metadata path was consequently saved as cat's diagnostic. It must remain absent,
    rather than being recreated as a metadata file. Real saved bytes are unchanged.
    """
    assert serial in SERIALS
    folder = ROOT / 'captures/workspace-recovery/native-original' / serial
    manifest = json.loads((folder / 'manifest.json').read_text())
    result = {}
    for name, backing in manifest.items():
        data = None if backing is None else (folder / backing).read_bytes()
        missing = {f'cat: {name}: No such file or directory\n'.encode(),
                   f'cat: {name}: No such file or directory\r\n'.encode()}
        result[name] = None if data in missing else data
    return result


def work(serial, action):
    assert serial in SERIALS
    assert action in {'prepare', 'restore'}
    destination = ROOT / 'captures/workspace-recovery/native-original' / serial
    destination.mkdir(parents=True, exist_ok=True)
    def call(*parts, data=None, check=True):
        result = subprocess.run([ADB, '-s', serial, *map(str, parts)], input=data,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
        if check and result.returncode:
            raise RuntimeError((parts, result.stderr.decode(errors='replace')))
        return result
    def shell(*parts, **kw): return call('shell', '-T', shlex.join(map(str, parts)), **kw)
    def read(path):
        script = ('if test -f ' + shlex.quote(path) + '; then cat ' + shlex.quote(path) +
                  '; elif test -e ' + shlex.quote(path) + '; then exit 42; else exit 44; fi')
        result = shell('run-as', 'dev.findroid', 'sh', '-c', script, check=False)
        if result.returncode == 44: return None
        assert result.returncode == 0, ('Could not read app-owned metadata', serial, path)
        return result.stdout
    def write(path, data):
        shell('run-as', 'dev.findroid', 'sh', '-c', 'cat > ' + shlex.quote(path), data=data)
    shell('am', 'force-stop', 'dev.findroid')
    if action == 'prepare':
        call('install', '-r', ROOT / 'app/build/outputs/apk/debug/app-debug.apk')
        call('install', '-r', ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
        if not (destination / 'manifest.json').exists():
            names = shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', '1', '-type', 'f').stdout.decode().splitlines()
            names += ['files/' + name for name in FILES]
            manifest = {}
            for index, name in enumerate(names):
                data = read(name)
                backing = None if data is None else f'{index}.bin'
                if backing is not None: (destination / backing).write_bytes(data)
                manifest[name] = backing
            (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    else:
        snapshot = original_snapshot(serial)
        shell('run-as', 'dev.findroid', 'mkdir', '-p', 'shared_prefs')
        current = shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', '1', '-type', 'f').stdout.decode().splitlines()
        for name in set(current) - set(snapshot): shell('run-as', 'dev.findroid', 'rm', '-f', name)
        for name, data in snapshot.items():
            if data is None: shell('run-as', 'dev.findroid', 'rm', '-f', name)
            else: write(name, data)
        for name in FILES:
            shell('run-as', 'dev.findroid', 'rm', '-f', 'files/' + name + '.bak', 'files/' + name + '.new')
        assert all(read(name) == data for name, data in snapshot.items()), 'Original metadata restoration must be exact.'
    print(serial, action, 'complete')


def main():
    action = sys.argv[1]
    serials = sys.argv[2:] or ['emulator-5582', 'emulator-5602']
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda serial: work(serial, action), serials))


if __name__ == '__main__': main()
