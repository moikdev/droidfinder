"""Freeze already-built 0.47 outputs and bind every application input to its committed bytes."""
from pathlib import Path
import hashlib,json,shutil,subprocess,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[2];proof=root/'captures/recovery-inbox';target=proof/'candidate-build-1.json'
assert not target.exists(),'Never replace an existing frozen candidate'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
old=json.loads((root/'captures/reviewed-folder-sync/candidate-build-1.json').read_text())
selectors=['dev.findroid.RecoveryNoticeNativeTest','dev.findroid.RecoveryInboxWorkflowTest']+(root/'captures/reviewed-folder-sync/native-regression-classes.txt').read_text().strip().split(',')
(proof/'native-regression-classes.txt').write_text(','.join(selectors)+'\n')
source=set(old['sourceFiles'])|{'app/proguard-rules.pro','.gitattributes'}|{str(p.relative_to(root)) for p in (root/'app/src').rglob('*') if p.is_file()}
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
for name in source:
 raw=(root/name).read_bytes();stored=subprocess.check_output(['git','show',commit+':'+name],cwd=root)
 assert raw==stored or name.endswith('.bat') and raw==stored.replace(b'\n',b'\r\n'),name
outputs={p.replace('0.46.0','0.47.0') for p in old['files']}|{str(p.relative_to(root)) for p in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')}
counts={key:sum(int(ET.parse(p).getroot().get(key,0)) for p in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')) for key in ('tests','failures','errors','skipped')}
assert counts==dict(tests=894,failures=0,errors=0,skipped=0)
issues=ET.parse(root/'app/build/reports/lint-results-debug.xml').getroot().findall('issue')
lint={key:sum(i.get('severity')==value for i in issues) for key,value in [('errors','Error'),('warnings','Warning'),('hints','Hint')]};assert lint==dict(errors=0,warnings=148,hints=12)
for name in ['candidate-build-2-gradle.txt','candidate-build-4-debug-native.txt','candidate-package-1.txt']:
 assert not (proof/name).exists()
for source_log,name in [('/tmp/findroid-recovery-inbox-candidate-build-2.log','candidate-build-2-gradle.txt'),('/tmp/findroid-recovery-inbox-native-build-4.log','candidate-build-4-debug-native.txt'),('/tmp/findroid-recovery-inbox-package-1.log','candidate-package-1.txt')]:shutil.copyfile(source_log,proof/name)
info=json.loads((root/'captures/release/0.47.0/BUILDINFO.json').read_text());assert info['source_commit']==commit and info['version_code']=='49'
report=dict(version='0.47.0-preview',versionCode=49,purpose='Frozen candidate 1; final acceptance pending',sourceCommit=commit,
 sourceFiles={name:sha(root/name) for name in sorted(source)},files={name:dict(sha256=sha(root/name),bytes=(root/name).stat().st_size) for name in sorted(outputs)},
 regressionSelectorsSha256=sha(proof/'native-regression-classes.txt'),gradleBuildLogSha256=sha(proof/'candidate-build-2-gradle.txt'),debugAndNativeBuildLogSha256=sha(proof/'candidate-build-4-debug-native.txt'),packageLogSha256=sha(proof/'candidate-package-1.txt'),jvm=counts,lint=lint)
target.write_text(json.dumps(report,indent=2)+'\n');target.chmod(0o444)
for name in outputs:(root/name).chmod(0o444)
print(json.dumps(dict(sourceCommit=commit,sourceInputs=len(source),frozenOutputs=len(outputs),jvm=counts,lint=lint,previewSha256=sha(root/'app/build/outputs/apk/preview/app-preview.apk')),indent=2))
