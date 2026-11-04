"""Save named synthetic file comparison preview captures, then restore immutable managed state."""
from pathlib import Path
import hashlib
import json
import os
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
ORIGINAL = Path('/Users/LENOVO/.t3/projects/droidfinder/captures/persistent-undo')
assert len(sys.argv) == 3 and sys.argv[1] == 'restore' and sys.argv[2] in ('emulator-5602', 'emulator-5582')
serial = sys.argv[2]
output = Path(os.environ['FINDROID_COMPARISON_NATIVE_OUTPUT']).resolve()
assert output.is_relative_to(ROOT / 'captures/file-comparison') and output.is_dir()
adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
counter = output / 'PRIVATE-reset-counter.json'
index = json.loads(counter.read_text()) if counter.exists() else 0
baseline = json.loads((ORIGINAL / 'private-native-state' / serial / 'PRIVATE-MANIFEST.json').read_text())['files']
folder = 'cache/office-qa'
allowed = {'word-find-and-copy.png', 'workbook-stored-values.png', 'slide-section-and-find.png', 'provider-stopped.png'}

def run(*parts, check=True):
    return subprocess.run([adb, '-s', serial, *map(str, parts)], check=check,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)

sets = {folder: allowed, 'cache/file-comparison-qa': {'text-differences.png','linked-images.png','nested-file-comparison.png','unsupported-pair.png','provider-stopped.png','gesture-proof.json'}}
for capture_folder, names in sets.items():
    result = run('shell', '-T', shlex.join(['run-as', 'dev.findroid', 'ls', capture_folder]), check=False)
    captured = []
    if result.returncode == 0:
        for name in result.stdout.decode().splitlines():
            assert name in names
            relative = capture_folder + '/' + name
            data = run('exec-out', 'run-as', 'dev.findroid', 'cat', relative).stdout
            digest = hashlib.sha256(data).hexdigest()
            if baseline.get(relative) == digest:
                continue
            assert len(data) <= 4 * 1024 * 1024
            if name.endswith('.png'): assert data.startswith(b'\x89PNG\r\n\x1a\n')
            target = output / 'images' / ('after-batch-' + str(index).zfill(3)) / capture_folder.rpartition('/')[2] / name
            target.parent.mkdir(parents=True, exist_ok=True)
            assert not target.exists()
            target.write_bytes(data)
            captured.append(dict(path=str(target.relative_to(ROOT)), sha256=digest))
    if captured:
        (target.parent / 'capture-provenance.json').write_text(json.dumps(dict(serial=serial,
            completedBatch=index, files=captured, syntheticComparisonPreviewOnly=True,
            privateDiagnostics=capture_folder.endswith('-fail')), indent=2) + '\n')
subprocess.run([sys.executable, str(ORIGINAL / 'private-native-state.py'), 'restore', '--serial', serial], check=True)
counter.write_text(json.dumps(index + 1) + '\n')
