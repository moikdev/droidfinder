"""Freeze already-built PDF selection outputs and bind application inputs to public source."""
from pathlib import Path
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]
PROOF=ROOT/'captures/pdf-page-selection'
TARGET=PROOF/'candidate-build-2.json'
assert not TARGET.exists(),'Never replace a frozen candidate'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
prior=json.loads((ROOT/'captures/transfer-space-plans/candidate-build-2.json').read_text())
source=set(prior['sourceFiles'])|{'app/proguard-rules.pro','.gitattributes'}|{str(p.relative_to(ROOT)) for p in (ROOT/'app/src').rglob('*') if p.is_file()}
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
for name in source:
    raw=(ROOT/name).read_bytes();stored=subprocess.check_output(['git','show',commit+':'+name],cwd=ROOT)
    assert raw==stored or name.endswith('.bat') and raw==stored.replace(b'\n',b'\r\n'),name
reports=sorted((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
counts={key:sum(int(ET.parse(p).getroot().get(key,0)) for p in reports) for key in ('tests','failures','errors','skipped')}
assert counts==dict(tests=948,failures=0,errors=0,skipped=0)
issues=ET.parse(ROOT/'app/build/reports/lint-results-debug.xml').getroot().findall('issue')
lint={key:sum(i.get('severity')==value for i in issues) for key,value in [('errors','Error'),('warnings','Warning'),('hints','Hint')]}
assert lint['errors']==0,lint
outputs={p.replace('0.48.0','0.49.0') for p in prior['files'] if '/test-results/' not in p}|{str(p.relative_to(ROOT)) for p in reports}
info=json.loads((ROOT/'captures/release/0.49.0/BUILDINFO.json').read_text())
assert info['source_commit']==commit and info['version_code']=='51'
report=dict(version='0.49.0-preview',versionCode=51,purpose='Frozen candidate 2 with separate accessible handle targets and page selection retained while panning; release acceptance pending',sourceCommit=commit,
    sourceFiles={name:sha(ROOT/name) for name in sorted(source)},files={name:dict(sha256=sha(ROOT/name),bytes=(ROOT/name).stat().st_size) for name in sorted(outputs)},
    regressionSelectorsSha256=sha(PROOF/'native-regression-classes.txt'),gradleBuildLogSha256=sha(PROOF/'candidate-build-2-gradle.txt'),packageLogSha256=sha(PROOF/'candidate-package-2.txt'),
    jvm=counts,lint=lint)
TARGET.write_text(json.dumps(report,indent=2)+'\n');TARGET.chmod(0o444)
for name in outputs:(ROOT/name).chmod(0o444)
print(json.dumps(dict(sourceCommit=commit,sourceInputs=len(source),frozenOutputs=len(outputs),jvm=counts,lint=lint,previewSha256=sha(ROOT/'app/build/outputs/apk/preview/app-preview.apk')),indent=2))
