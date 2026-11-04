"""Save named synthetic PDF preview captures, then restore immutable managed state."""
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
output = Path(os.environ['FINDROID_PDF_NATIVE_OUTPUT']).resolve()
assert output.is_relative_to(ROOT / 'captures/pdf-page-selection') and output.is_dir()
adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
counter = output / 'PRIVATE-reset-counter.json'
index = json.loads(counter.read_text()) if counter.exists() else 0
baseline = json.loads((ORIGINAL / 'private-native-state' / serial / 'PRIVATE-MANIFEST.json').read_text())['files']
folder = 'cache/pdf-page-selection-qa'
allowed = {'word-and-handles.png', 'two-line-selection.png', 'zoomed-selection.png', 'direct-handle-drag.png', 'first-long-press-drag.png'}

def run(*parts, check=True):
    return subprocess.run([adb, '-s', serial, *map(str, parts)], check=check,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)

result = run('shell', '-T', shlex.join(['run-as', 'dev.findroid', 'ls', folder]), check=False)
images = []
if result.returncode == 0:
    for name in result.stdout.decode().splitlines():
        assert name in allowed
        relative = folder + '/' + name
        data = run('exec-out', 'run-as', 'dev.findroid', 'cat', relative).stdout
        digest = hashlib.sha256(data).hexdigest()
        if baseline.get(relative) == digest:
            continue
        assert data.startswith(b'\x89PNG\r\n\x1a\n')
        target = output / 'images' / ('after-batch-' + str(index).zfill(3)) / name
        target.parent.mkdir(parents=True, exist_ok=True)
        assert not target.exists()
        target.write_bytes(data)
        images.append(dict(path=str(target.relative_to(ROOT)), sha256=digest))
if images:
    (target.parent / 'capture-provenance.json').write_text(json.dumps(dict(serial=serial,
        completedBatch=index, images=images, syntheticPdfPreviewOnly=True), indent=2) + '\n')
subprocess.run([sys.executable, str(ORIGINAL / 'private-native-state.py'), 'restore', '--serial', serial], check=True)
counter.write_text(json.dumps(index + 1) + '\n')
