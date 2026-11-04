"""Isolated sync acceptance. Each assigned tablet is reserved and restored to exact public 0.45."""
from pathlib import Path
import argparse, fcntl, hashlib, importlib.util, io, json, os, shlex, shutil, subprocess, tarfile
p=argparse.ArgumentParser();p.add_argument('--serial',required=True,choices=('emulator-5602','emulator-5582'));p.add_argument('--attempt',required=True);a=p.parse_args()
main=Path('/Users/LENOVO/.t3/projects/droidfinder');branch=Path('/tmp/findroid-reviewed-sync');proof=main/'captures/persistent-undo'
api='api30' if a.serial=='emulator-5602' else 'api36';output=branch/'captures/reviewed-folder-sync'/('integrated-'+api+'-'+a.attempt)
assert not output.exists(),'Preserve every previous attempt'
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
 fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);output.mkdir(parents=True)
 frozen=json.loads((proof/'candidate-build-6.json').read_text())
 for relative in pair:assert digest(main/relative)==frozen['files'][relative]['sha256']
 assert installed('dev.findroid')==digest(public),'Start only from the exact restored public build'
 candidate={relative:digest(branch/relative) for relative in pair};private=[]
 for relative in pair:
  copy=output/('PRIVATE-'+Path(relative).name);shutil.copy2(branch/relative,copy);assert digest(copy)==candidate[relative];private.append(copy)
 source={str(f.relative_to(branch)):digest(f) for f in sorted((branch/'app/src').rglob('*')) if f.is_file()}
 receipt=dict(serial=a.serial,controllerSha256=digest(Path(__file__)),draftOnly=True,main045CanonicalCountsUnchanged=True,
  sourceCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=branch).decode().strip(),sourceSha256=source,apkSha256=candidate,
  immutableOriginalBytesVerifiedBeforeTests=False,restorationComplete=False)
 (output/'build-provenance.json').write_text(json.dumps(receipt,indent=2)+'\n')
 verified=False;workspace=None
 try:
  shell('am','force-stop','dev.findroid')
  for f in private:call('install','-r',f)
  shell('am','force-stop','dev.findroid')
  assert {r:installed(package) for r,package in zip(pair,('dev.findroid','dev.findroid.test'))}==candidate
  managed=shell('run-as','dev.findroid','tar','-cf','-','--exclude=files/Workspace','--exclude=files/ProviderFixtures','shared_prefs','databases','no_backup','cache','files')
  (output/'PRIVATE-managed-before.tar').write_bytes(managed)
  current={name:value[1] for name,value in entries(managed).items() if value[0]=='file'}
  baseline=json.loads((proof/'private-native-state'/a.serial/'PRIVATE-MANIFEST.json').read_text())['files']
  assert current==baseline,'Private bytes changed since the immutable baseline. Preserve current state; do not run tests or overwrite it.'
  workspace=shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(output/'PRIVATE-workspace-before.tar').write_bytes(workspace)
  verified=True;receipt['immutableOriginalBytesVerifiedBeforeTests']=True;receipt['originalManagedFileCount']=len(current)
  (output/'build-provenance.json').write_text(json.dumps(receipt,indent=2)+'\n')
  result=subprocess.run(['python3',str(branch/'scripts/check-device.py'),'--serial',a.serial,'--classes',
    'dev.findroid.FolderSyncDraftNativeTest,dev.findroid.FolderSyncApplyNativeTest,dev.findroid.FolderSyncWorkflowTest',
    '--timeout','720','--output',str(output/'native.txt')],cwd=branch,env={**os.environ,'ANDROID_HOME':str(Path.home()/'Library/Android/sdk')})
  for name in ('default-choices','completed-addition','confirmed-replacement-and-trash','stale-review-stopped','large-text-confirmation'):
   remote='cache/folder-sync-qa/'+name+'.png'
   check=subprocess.run([adb,'-s',a.serial,'shell','-T',shlex.join(['run-as','dev.findroid','test','-f',remote])],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
   if check.returncode==0:(output/(name+'.png')).write_bytes(call('exec-out','run-as','dev.findroid','cat',remote))
  font=call('exec-out','run-as','dev.findroid','cat','cache/folder-sync-qa/font-layout.json')
  (output/'font-layout.json').write_bytes(font)
  shell('am','force-stop','dev.findroid')
  after=shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(output/'PRIVATE-workspace-after.tar').write_bytes(after)
  receipt['originalWorkspaceTreeKept']=entries(after)==entries(workspace)
  assert receipt['originalWorkspaceTreeKept'],'Workspace changed beyond owned fixture cleanup; preserve before and after snapshots'
  report=json.loads((output/'native.json').read_text());receipt['nativePassed']=report['passed'];receipt['nativeFailed']=report['failed'];receipt['nativeSkipped']=report['skipped']
  assert result.returncode==0 and report['success'] and report['passed']==20 and report['failed']==report['skipped']==0
 finally:
  shell('am','force-stop','dev.findroid')
  for relative in pair:call('install','-r',main/relative)
  if verified:
   row=restore.restore_one(a.serial,public);(output/'restoration.json').write_text(json.dumps(row,indent=2)+'\n');receipt['restorationComplete']=True
  else:
   call('install','-r','-d',public);receipt['originalStateKeptWithoutBaselineOverwrite']=True
  assert installed('dev.findroid')==digest(public)
  assert installed('dev.findroid.test')==frozen['files'][pair[1]]['sha256']
  (output/'build-provenance.json').write_text(json.dumps(receipt,indent=2)+'\n')
 print(a.serial+': 20 sync checks passed; original data and exact public 0.45 restored.',flush=True)
