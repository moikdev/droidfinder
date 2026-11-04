"""Freeze an Office candidate after building and packaging; bind it to committed inputs."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[2]
proof=ROOT/'captures/office-previews'
target=proof/'candidate-build-3.json'
assert not target.exists()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
draft=json.loads((proof/'draft-build-10.json').read_text())
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
for name,digest in draft['sourceFiles'].items():
    raw=(ROOT/name).read_bytes()
    assert sha(ROOT/name)==digest,name
    stored=subprocess.check_output(['git','show',commit+':'+name],cwd=ROOT)
    assert raw==stored or name.endswith('.bat') and raw==stored.replace(b'\n',b'\r\n'),name
for name,row in draft['files'].items():assert sha(ROOT/name)==row['sha256'],name
assert 'BUILD SUCCESSFUL' in (proof/'draft-build-10-gradle.txt').read_text()
release=ROOT/'captures/release/0.51.0'
info=json.loads((release/'BUILDINFO.json').read_text())
assert info['source_commit']==commit and info['version_code']=='53'
outputs=set(draft['files'])|{str(p.relative_to(ROOT)) for p in release.iterdir() if p.is_file()}
outputs|={'app/build/outputs/apk/preview/app-preview.apk','app/build/outputs/apk/release/app-release-unsigned.apk',
          'app/build/outputs/mapping/preview/mapping.txt','app/build/outputs/mapping/release/mapping.txt'}
frozen=proof/'candidate-3-frozen';frozen.mkdir()
for name in sorted(outputs):
    copy=frozen/name;copy.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,copy)
    assert sha(copy)==sha(ROOT/name)
    copy.chmod(0o444)
report=dict(version='0.51.0-preview',versionCode=53,sourceCommit=commit,
    sourceFiles=draft['sourceFiles'],files={name:dict(sha256=sha(ROOT/name),bytes=(ROOT/name).stat().st_size) for name in sorted(outputs)},
    jvm=draft['jvm'],lint=draft['lint'],compiledDraftReceipt='captures/office-previews/draft-build-10.json',
    compiledDraftReceiptSha256=sha(proof/'draft-build-10.json'),allCompiledDraftInputsMatchCommittedSource=True,
    optimizedBuildLogSha256=sha(proof/'draft-build-10-gradle.txt'))
target.write_text(json.dumps(report,indent=2)+'\n');target.chmod(0o444)
print(json.dumps(dict(sourceCommit=commit,inputs=len(report['sourceFiles']),outputs=len(outputs),jvm=report['jvm'],previewSha256=sha(ROOT/'app/build/outputs/apk/preview/app-preview.apk'))))
