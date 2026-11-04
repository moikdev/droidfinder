"""Bind unchanged committed comparison inputs and completed local outputs to a receipt."""
import argparse,hashlib,json,shutil,subprocess
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--log',type=Path,action='append',required=True);p.add_argument('--optimized',action='store_true');a=p.parse_args()
a.output=a.output.resolve();a.log=[p.resolve() for p in a.log]
assert not a.output.exists()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
inputs=set()
for folder in ('app/src','interop-client/src','gradle'):
 inputs.update(p for p in (ROOT/folder).rglob('*') if p.is_file())
inputs.update(ROOT/n for n in ('.gitattributes','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','app/build.gradle.kts','app/proguard-rules.pro','interop-client/build.gradle.kts','scripts/build-update-fixtures.py'))
tracked=set(subprocess.check_output(['git','ls-files'],cwd=ROOT,text=True).splitlines())
assert all(str(p.relative_to(ROOT)) in tracked for p in inputs)
subprocess.run(['git','diff','--exit-code','HEAD','--',*sorted(str(p.relative_to(ROOT)) for p in inputs)],cwd=ROOT,check=True,stdout=subprocess.DEVNULL)
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
outputs={ROOT/n for n in ('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')}
xmls=list((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'));outputs.update(xmls)
jvm={k:sum(int(ET.parse(p).getroot().get(k,0)) for p in xmls) for k in ('tests','failures','errors','skipped')};assert jvm==dict(tests=1017,failures=0,errors=0,skipped=0),jvm
logs=[]
for i,path in enumerate(a.log):
 assert 'BUILD SUCCESSFUL' in path.read_text()
 target=a.output.parent/(a.output.stem+f'-gradle-{i+1}.txt');assert not target.exists();shutil.copy2(path,target)
 logs.append(dict(path=str(target.relative_to(ROOT)),sha256=sha(target)))
lint={}
if a.optimized:
 for module in ('app','interop-client'):
  report=ROOT/module/'build/reports/lint-results-debug.xml';outputs.add(report)
  counts=Counter(node.get('severity') for node in ET.parse(report).getroot().findall('issue'));assert not counts['Error'];lint[module]=dict(counts)
 for variant in ('preview','release'):
  outputs.add(ROOT/f'app/build/outputs/apk/{variant}/app-{variant}{"-unsigned" if variant=="release" else ""}.apk')
  outputs.add(ROOT/f'app/build/outputs/mapping/{variant}/mapping.txt')
 release=ROOT/'captures/release/0.53.0'
 info=json.loads((release/'BUILDINFO.json').read_text());assert info['source_commit']==commit and info['version_code']=='55'
 outputs.update(p for p in release.iterdir() if p.is_file())
report=dict(version='0.53.0-preview' if a.optimized else '0.53.0',versionCode=55,sourceCommit=commit,
 sourceFiles={str(p.relative_to(ROOT)):sha(p) for p in sorted(inputs)},files={str(p.relative_to(ROOT)):dict(sha256=sha(p),bytes=p.stat().st_size) for p in sorted(outputs)},
 jvm=jvm,lint=lint,buildLogs=logs,allCompiledDraftInputsMatchCommittedSource=True)
a.output.write_text(json.dumps(report,indent=2)+'\n');a.output.chmod(0o444)
print(json.dumps(dict(sourceCommit=commit,jvm=jvm,inputs=len(inputs),outputs=len(outputs),lint=lint)))
