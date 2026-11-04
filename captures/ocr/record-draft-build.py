"""Bind a fresh OCR draft build to its inputs and checks without overwriting evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--attempt', type=int, required=True)
p.add_argument('--expected-native-cases', type=int, default=8)
a = p.parse_args()
proof = ROOT / 'captures/ocr'
target = proof / f'draft-build-{a.attempt}.json'
log = proof / f'draft-build-{a.attempt}-gradle.txt'
assert not target.exists() and 'BUILD SUCCESSFUL' in log.read_text()
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
prior = json.loads((proof / 'draft-build-4.json').read_text())
inputs = set(prior['sourceFiles']) | {
    str(f.relative_to(ROOT)) for f in (ROOT / 'app/src').rglob('*') if f.is_file()
}
outputs = set(prior['files'])
reports = sorted((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
counts = {k: sum(int(ET.parse(f).getroot().get(k, 0)) for f in reports)
          for k in ('tests', 'failures', 'errors', 'skipped')}
assert counts == dict(tests=964, failures=0, errors=0, skipped=0), counts
lint = {}
for module in ('app', 'interop-client'):
    issues = ET.parse(ROOT / module / 'build/reports/lint-results-debug.xml').getroot().findall('issue')
    lint[module] = {key: sum(i.get('severity') == key for i in issues)
                    for key in ('Error', 'Warning', 'Hint')}
    assert lint[module]['Error'] == 0
receipt = dict(sourceCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
    draftOnly=True, sourceTreeDirty=True, version='0.50.0', versionCode=52,
    jvm=counts, lint=lint, sourceFiles={name: sha(ROOT / name) for name in sorted(inputs)},
    files={name: dict(sha256=sha(ROOT / name), bytes=(ROOT / name).stat().st_size) for name in sorted(outputs)},
    buildLogSha256=sha(log), expectedFocusedNativeCases=a.expected_native_cases)
target.write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(dict(receipt=str(target.relative_to(ROOT)), jvm=counts, lint=lint)))
