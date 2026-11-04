"""Resume exact candidate 1; preserve the stopped attempt and use only identical accepted batches.
The original controller and reset scripts remain unchanged. All final restoration still runs.
"""
from pathlib import Path
import argparse, fcntl, hashlib, importlib.util, io, json, os, shlex, shutil, subprocess, tarfile
p=argparse.ArgumentParser();p.add_argument('--serial',required=True,choices=('emulator-5602','emulator-5582'));a=p.parse_args()
main=Path('/Users/LENOVO/.t3/projects/droidfinder');branch=Path('/tmp/findroid-reviewed-sync');proof=main/'captures/persistent-undo'
api='api30' if a.serial=='emulator-5602' else 'api36';output=branch/'captures/reviewed-folder-sync'/('candidate-1-'+api+'-native')
assert output.is_dir() and (output/'restoration.json').exists(), 'Resume only a fully restored earlier attempt'
assert not (output/'native.json').exists(), 'This acceptance is already complete'
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb');pair=('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def call(*args):return subprocess.run([adb,'-s',a.serial,*map(str,args)],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180).stdout
def shell(*args):return call('shell','-T',shlex.join(map(str,args)))
def installed(package):return hashlib.sha256(call('exec-out','cat',shell('pm','path',package).decode().strip().splitlines()[0].removeprefix('package:'))).hexdigest()
def entries(payload):
 result={}
 with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
  for m in archive:
   name=m.name.rstrip('/');assert not name.startswith('/') and '..' not in Path(name).parts
   result[name]=('file',hashlib.sha256(archive.extractfile(m).read()).hexdigest()) if m.isfile() else ('directory',) if m.isdir() else ('link',m.linkname)
 return result
spec=importlib.util.spec_from_file_location('restore_originals',proof/'restore-public-tablets.py');restore=importlib.util.module_from_spec(spec);spec.loader.exec_module(restore)
public=main/'captures/release/0.45.0/Findroid-0.45.0-preview.apk'
with (proof/('.'+a.serial+'-acceptance.lock')).open('a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 frozen=json.loads((proof/'candidate-build-6.json').read_text())
 candidate_frozen=json.loads((branch/'captures/reviewed-folder-sync/candidate-build-1.json').read_text())
 for name,row in candidate_frozen['files'].items():assert digest(branch/name)==row['sha256']
 for name,sha in candidate_frozen['sourceFiles'].items():assert digest(branch/name)==sha
 for relative in pair:assert digest(main/relative)==frozen['files'][relative]['sha256']
 assert installed('dev.findroid')==digest(public),'Start only from the exact restored public build'
 candidate={relative:digest(branch/relative) for relative in pair};private=[]
 for relative in pair:
  copy=output/('PRIVATE-'+Path(relative).name);assert digest(copy)==candidate[relative];private.append(copy)
 source={str(f.relative_to(branch)):digest(f) for f in sorted((branch/'app/src').rglob('*')) if f.is_file()}
 receipt=dict(serial=a.serial,controllerSha256=digest(Path(__file__)),resetScriptSha256=digest(branch/'captures/reviewed-folder-sync/native-matrix-reset.py'),batchRunnerSha256=digest(branch/'scripts/check-device-batches.py'),testRunnerSha256=digest(branch/'scripts/check-device.py'),draftOnly=True,main045CanonicalCountsUnchanged=True,
  sourceCommit=candidate_frozen['sourceCommit'],candidateManifestSha256=digest(branch/'captures/reviewed-folder-sync/candidate-build-1.json'),sourceSha256=source,apkSha256=candidate,
  immutableOriginalBytesVerifiedBeforeTests=False,restorationComplete=False)
 (output/'resume-1-build-provenance.json').write_text(json.dumps(receipt,indent=2)+'\n')
 verified=False;workspace=None
 try:
  shell('am','force-stop','dev.findroid')
  for f in private:call('install','-r',f)
  shell('am','force-stop','dev.findroid')
  assert {r:installed(package) for r,package in zip(pair,('dev.findroid','dev.findroid.test'))}==candidate
  managed=shell('run-as','dev.findroid','tar','-cf','-','--exclude=files/Workspace','--exclude=files/ProviderFixtures','shared_prefs','databases','no_backup','cache','files')
  (output/'PRIVATE-managed-before-resume-1.tar').write_bytes(managed)
  current={name:value[1] for name,value in entries(managed).items() if value[0]=='file'}
  baseline=json.loads((proof/'private-native-state'/a.serial/'PRIVATE-MANIFEST.json').read_text())['files']
  assert current==baseline,'Private bytes changed since the immutable baseline. Preserve current state; do not run tests or overwrite it.'
  workspace=shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(output/'PRIVATE-workspace-before-resume-1.tar').write_bytes(workspace)
  verified=True;receipt['immutableOriginalBytesVerifiedBeforeTests']=True;receipt['originalManagedFileCount']=len(current)
  (output/'resume-1-build-provenance.json').write_text(json.dumps(receipt,indent=2)+'\n')
  driver=output/'batch-driver-resume-1.txt'
  with driver.open('x') as log:
   result=subprocess.run(['python3',str(branch/'scripts/check-device-batches.py'),'--serial',a.serial,
     '--classes-file',str(branch/'captures/reviewed-folder-sync/native-regression-classes.txt'),
     '--output',str(output/'native.txt'),'--batch-directory',str(output/'batches'),
     '--reset-script',str(branch/'captures/reviewed-folder-sync/native-matrix-reset.py'),'--resume'],
     cwd=branch,env={**os.environ,'ANDROID_HOME':str(Path.home()/'Library/Android/sdk')},stdout=log,stderr=subprocess.STDOUT)
  shell('am','force-stop','dev.findroid')
  after=shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(output/'PRIVATE-workspace-after-resume-1.tar').write_bytes(after)
  receipt['originalWorkspaceTreeKept']=entries(after)==entries(workspace)
  assert receipt['originalWorkspaceTreeKept'],'Workspace changed beyond owned fixture cleanup; preserve before and after snapshots'
  receipt['batchDriverExitCode']=result.returncode
  if (output/'native.json').exists():
   report=json.loads((output/'native.json').read_text());receipt['nativePassed']=report['passed'];receipt['nativeFailed']=report['failed'];receipt['nativeSkipped']=report['skipped']
   receipt['nativeRegressionComplete']=True
   assert result.returncode==0 and report['success'] and report['passed']==505 and report['failed']==report['skipped']==0
  else:
   receipt['nativeRegressionComplete']=False
   reports=[json.loads(p.read_text()) for p in sorted((output/'batches').glob('[0-9][0-9]-*.json'))]
   receipt['acceptedBatchesBeforeStop']=sum(r.get('success',False) for r in reports)
   receipt['acceptedCasesBeforeStop']=sum(r.get('passed',0) for r in reports if r.get('success',False))
   raise RuntimeError('Native regression stopped; inspect retained batch-driver.txt and individual reports. No complete acceptance claimed.')
 finally:
  shell('am','force-stop','dev.findroid')
  for relative in pair:call('install','-r','-d',main/relative)
  if verified:
   row=restore.restore_one(a.serial,public);(output/'resume-1-restoration.json').write_text(json.dumps(row,indent=2)+'\n');receipt['restorationComplete']=True
  else:
   call('install','-r','-d',public);receipt['originalStateKeptWithoutBaselineOverwrite']=True
  assert installed('dev.findroid')==digest(public)
  assert installed('dev.findroid.test')==frozen['files'][pair[1]]['sha256']
  (output/'resume-1-build-provenance.json').write_text(json.dumps(receipt,indent=2)+'\n')
 print(a.serial+': 505 native checks passed in 55 batches; original data and exact public 0.45 restored.',flush=True)
