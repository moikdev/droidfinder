"""Collect explicit, validated public evidence; never include private restoration payloads."""
import argparse,hashlib,json,subprocess,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2];PROOF=Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--selection',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--prepare-list',type=Path);a=p.parse_args()
a.selection=a.selection.resolve();a.output=a.output.resolve()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
read=lambda p:json.loads(p.read_text())
selected=read(a.selection);frozen_path=PROOF/selected['build'];frozen=read(frozen_path)
def owned(name):
 path=(ROOT/name).resolve();assert path.is_relative_to(ROOT) and path.is_file()
 assert not any(part.startswith('PRIVATE') or part=='__pycache__' for part in path.relative_to(ROOT).parts),name
 return path
files={frozen_path,a.selection}
for name,digest in frozen['sourceFiles'].items():
 path=owned(name);assert sha(path)==digest;files.add(path)
for name,row in frozen['files'].items():
 path=owned(name);assert sha(path)==row['sha256']
 if name.endswith('.xml') and '/test-results/' in name or name.endswith('lint-results-debug.xml'):files.add(path)
for row in frozen['buildLogs']:
 path=owned(row['path']);assert sha(path)==row['sha256'];files.add(path)
assert frozen['jvm']==dict(tests=1000,failures=0,errors=0,skipped=0)
for module in ('app','interop-client'):
 assert not any(n.get('severity')=='Error' for n in ET.parse(ROOT/module/'build/reports/lint-results-debug.xml').getroot().findall('issue'))
images={};identities=[]
for api,serial in (('api30','emulator-5602'),('api36','emulator-5582')):
 paths=selected[api];native_path=owned(paths['native']);native=read(native_path);folder=native_path.parent
 provenance=read(folder/'build-provenance.json')
 assert native['success'] and native['passed']==29 and native['failed']==native['skipped']==0
 assert provenance['nativeFocusedComplete'] and provenance['restorationComplete'] and provenance['originalWorkspaceTreeKept'] and provenance['immutableOriginalBytesVerifiedBeforeTests']
 assert provenance['sourceCommit']==frozen['sourceCommit']
 assert native['apkSha256']=={name:frozen['files'][name]['sha256'] for name in native['apkSha256']}
 cases={(r['class'],r['test']) for r in native['cases']};assert len(cases)==29;identities.append(cases)
 files|={native_path,folder/'native.txt',folder/'build-provenance.json',folder/'batches/contract.json'}
 for batch in native['isolatedBatches']:
  for key,digestkey in (('report','reportSha256'),('log','logSha256')):
   path=owned(batch[key]);assert sha(path)==batch[digestkey];files.add(path)
 for name in paths['nativeImages']:
  path=owned(name);images[str(path.relative_to(ROOT))]=sha(path);files.add(path)
 for section in ('core','layouts'):
  path=owned(paths[section]);r=read(path);assert r['serial']==serial and r['acceptanceComplete'] and r['restorationComplete'] and r['originalWorkspaceTreeKept']
  assert r['sourceCommit']==frozen['sourceCommit'] and r['apkSha256']==frozen['files']['app/build/outputs/apk/preview/app-preview.apk']['sha256']
  assert r['immutableOriginalBytesVerifiedBeforeTests'] and r['operationsUseExactOptimizedApk']
  if section=='core':assert {x['name'] for x in r['checks'] if x['name'].startswith('core-')}=={'core-text','core-image'} and len([x for x in r['clipboardProofs'] if x['name'].startswith('core-')])==2
  else:
   assert len(r['layouts'])==12 and len(r['systemFontGroups'])==3
   assert {(x['widthDp'],x['heightDp'],x['fontScale'],x['dark']) for x in r['layouts']}=={(w,h,f,d) for w,h in ((1280,800),(800,1280),(600,800)) for f in (1.0,2.0) for d in (False,True)}
   assert all(x['allNamedControlsHaveComplete48DpBounds'] and x['sourceBytesKept'] for x in r['layouts'])
   assert {x['kind'] for x in r['layouts'] if x['dark']}=={x['kind'] for x in r['layouts'] if not x['dark']}=={'text','image'}
   for x in r['layouts']:
    name=f"layout-{x['widthDp']}x{x['heightDp']}-font{x['fontScale']}-"+('dark' if x['dark'] else 'light')
    controls=[v for v in r['controlProofs'] if v['name']==name and 'label' in v];assert len(controls)==7
    assert all(v['bounds'][2]-v['bounds'][0]>=48*v['density']-1 and v['bounds'][3]-v['bounds'][1]>=48*v['density']-1 for v in controls)
  if 'explicitLayoutResume' in r:
   resume=r['explicitLayoutResume'];previous=owned(resume['previousResult']);old=read(previous)
   assert sha(previous)==resume['previousResultSha256'] and old['restorationComplete'] and old['originalWorkspaceTreeKept']
   assert old['sourceCommit']==r['sourceCommit'] and old['apkSha256']==r['apkSha256'] and len(old['layouts'])==resume['completedLayoutsReused']==9
   for layout in old['layouts']:assert layout in r['layouts']
   for check in old['checks']:assert check in r['checks']
   for image in old['captures']:assert image in r['captures']
   files.add(previous)
  files.add(path)
  for row in r['captures']:
   image=owned(str((path.parent/row['path']).relative_to(ROOT)));assert sha(image)==row['sha256'] and row['syntheticSetupOnly'];images[str(image.relative_to(ROOT))]=sha(image);files.add(image)
 if 'ota' in paths:
  path=owned(paths['ota']);r=read(path);assert r['acceptanceComplete'] and r['restorationComplete'] and r['originalWorkspaceTreeKept']
  assert (r['fromVersionCode'],r['expectedVersionCode'])==(53,54);files.add(path)
  files|={path.parent/'ota-driver.txt',path.parent/'public-ota/verification.json'}
  for image in (path.parent/'public-ota').glob('*.png'):files.add(image);images[str(image.relative_to(ROOT))]=sha(image)
assert identities[0]==identities[1]
review_path=owned(selected['imageReview']);review=read(review_path)
assert {r['path']:r['sha256'] for r in review['images'] if r['inspected']}==images;files.add(review_path)
for name in selected['supportingFiles']:files.add(owned(name))
names=sorted(str(p.relative_to(ROOT)) for p in files)
if a.prepare_list:
 assert not a.prepare_list.exists();a.prepare_list.write_text(json.dumps(names,indent=2)+'\n');print(len(names));raise SystemExit
privacy_path=owned(selected['privacyReview']);allowlist_path=owned(selected['publicationAllowlist']);privacy=read(privacy_path)
assert privacy['explicitAllowlistChecked'] and privacy['privateRecoveryFilesExcluded'] and privacy['literalIdentifierMatches']==0
assert privacy['publicationAllowlistSha256']==sha(allowlist_path) and read(allowlist_path)==names
files|={privacy_path,allowlist_path}
assert not a.output.exists()
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
for path in files:
 raw=subprocess.check_output(['git','show',commit+':'+str(path.relative_to(ROOT))],cwd=ROOT)
 assert raw==path.read_bytes() or path.suffix=='.bat' and path.read_bytes().replace(b'\r\n',b'\n')==raw
manifest=dict(version='0.52.0-preview',versionCode=54,applicationSourceCommit=frozen['sourceCommit'],nativeTestSourceCommit=frozen['sourceCommit'],
 evidenceCommit=commit,previewApkSha256=frozen['files']['app/build/outputs/apk/preview/app-preview.apk']['sha256'],jvmCases=1000,nativeCasesPerApi=29,isolatedBatchesPerApi=6,
 nativeScope='29 focused file-comparison, Office, structured preview and cancellable provider-input cases.',frozenCandidateManifest=str(frozen_path.relative_to(ROOT)),frozenSourceBindingsComplete=True,
 publicOtaIncluded=all('ota' in selected[k] for k in ('api30','api36')),files=[dict(path=str(p.relative_to(ROOT)),sha256=sha(p),bytes=p.stat().st_size) for p in sorted(files)])
with zipfile.ZipFile(a.output,'x',compression=zipfile.ZIP_DEFLATED) as archive:
 for path in sorted(files):archive.write(path,str(path.relative_to(ROOT)))
 archive.writestr('EVIDENCE.json',json.dumps(manifest,indent=2)+'\n')
a.output.chmod(0o444)
receipt=a.output.with_suffix('.json');assert not receipt.exists();receipt.write_text(json.dumps(dict(archive=a.output.name,sha256=sha(a.output),bytes=a.output.stat().st_size,files=len(files),publicOtaIncluded=manifest['publicOtaIncluded']),indent=2)+'\n');print(receipt.read_text())
