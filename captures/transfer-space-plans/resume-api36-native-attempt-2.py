"""Resume unchanged candidate 2 after one retained API36 activity-launch setup timeout."""
from pathlib import Path
import argparse, fcntl, hashlib, importlib.util, io, json, os, shlex, shutil, subprocess, tarfile, time
p=argparse.ArgumentParser();p.add_argument('--serial',required=True,choices=('emulator-5582',));a=p.parse_args()
main=Path('/Users/LENOVO/.t3/projects/droidfinder');branch=Path(__file__).resolve().parents[2];proof=main/'captures/persistent-undo'
api='api30' if a.serial=='emulator-5602' else 'api36';native=branch/'captures/transfer-space-plans'/('candidate-2-'+api+'-native');output=branch/'captures/transfer-space-plans'/('candidate-2-'+api+'-native-resume-2')
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
public=main/'captures/release/0.47.0/Findroid-0.47.0-preview.apk'
# The original queued optimized checker must first observe the failed native gate and
# end without opening a tablet. Never race the small gap between two queued commands.
while True:
 process=subprocess.run(['ps','-p','93732','-o','command='],capture_output=True,text=True)
 if process.returncode or not process.stdout.strip():break
 assert 'checker-optimized-attempt-1.py' in process.stdout,'The recorded sequence PID was reused; stop before any device action'
 time.sleep(1)
previous=json.loads((native/'build-provenance.json').read_text())
recovered=branch/'captures/transfer-space-plans/candidate-2-api36-disconnect-recovery-2/verification.json'
recovery=json.loads(recovered.read_text())
assert not previous['restorationComplete'] and recovery['restorationComplete'] and recovery['originalWorkspaceTreeKept']
assert recovery['restoration']['originalManagedMetadataDatabasesAndCacheRestored'] and recovery['restoration']['originalUriGrantsKept']
assert recovery['restoration']['installedPublicApkSha256']==digest(public)
assert recovery['allOriginalWorkspaceBytesVerifiedBeforeCleanup'] and recovery['existingUserdataPreserved']
assert not (native/'native.json').exists()
failed=native/'batches/59-ExternalDragWorkflowTest.json'
failure=json.loads(failed.read_text())
assert failure['passed']==4 and failure['failed']==1 and failure['skipped']==0
bad=[case for case in failure['cases'] if case['code']!=0]
assert len(bad)==1 and bad[0]['test']=='mouseIconCopiesWithoutMovingTheSource' and 'Could not launch the source activity' in bad[0]['error']
reused=[]
for path in sorted((native/'batches').glob('[0-9][0-9]-*.json')):
 if path==failed:continue
 report=json.loads(path.read_text());assert report['success'] and report['failed']==report['skipped']==0
 reused.append(dict(report=str(path.relative_to(branch)),reportSha256=digest(path),log=str(path.with_suffix('.txt').relative_to(branch)),logSha256=digest(path.with_suffix('.txt')),passed=report['passed']))
assert len(reused)==58 and sum(row['passed'] for row in reused)==544
with (proof/('.'+a.serial+'-acceptance.lock')).open('a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);output.mkdir(parents=True)
 frozen=json.loads((proof/'candidate-build-6.json').read_text())
 candidate_frozen=json.loads((branch/'captures/transfer-space-plans/candidate-build-2.json').read_text())
 for name,row in candidate_frozen['files'].items():assert digest(branch/name)==row['sha256']
 for name,sha in candidate_frozen['sourceFiles'].items():assert digest(branch/name)==sha
 for relative in pair:assert digest(main/relative)==frozen['files'][relative]['sha256']
 assert installed('dev.findroid')==digest(public),'Start only from the exact restored public build'
 candidate={relative:digest(branch/relative) for relative in pair};private=[]
 for relative in pair:
  copy=output/('PRIVATE-'+Path(relative).name);shutil.copy2(branch/relative,copy);assert digest(copy)==candidate[relative];private.append(copy)
 source={str(f.relative_to(branch)):digest(f) for f in sorted((branch/'app/src').rglob('*')) if f.is_file()}
 receipt=dict(serial=a.serial,controllerSha256=digest(Path(__file__)),resetScriptSha256=digest(branch/'captures/transfer-space-plans/native-matrix-reset.py'),batchRunnerSha256=digest(branch/'scripts/check-device-batches.py'),testRunnerSha256=digest(branch/'scripts/check-device.py'),draftOnly=False,frozenCandidate=True,main045RestorationPairUnchanged=True,
  sourceCommit=candidate_frozen['sourceCommit'],candidateManifestSha256=digest(branch/'captures/transfer-space-plans/candidate-build-2.json'),sourceSha256=source,apkSha256=candidate,
  immutableOriginalBytesVerifiedBeforeTests=False,restorationComplete=False)
 receipt.update(originalRestorationCompletedBySeparateDisconnectRecovery=True,disconnectRecoveryReceiptSha256=digest(recovered),previouslyAcceptedBatchesReused=58,previouslyAcceptedCasesReused=544,freshBatchesRequiredThisInvocation=1,originalFailedBatchExcluded=True,priorFailureRetained=True,priorNativeProvenanceSha256=digest(native/'build-provenance.json'),reusedReports=reused)
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
  driver=output/'batch-driver.txt'
  with driver.open('x') as log:
   result=subprocess.run(['python3',str(branch/'scripts/check-device-batches.py'),'--serial',a.serial,
     '--classes-file',str(branch/'captures/transfer-space-plans/native-regression-classes.txt'),
     '--resume','--output',str(native/'native.txt'),'--batch-directory',str(native/'batches'),
     '--reset-script',str(branch/'captures/transfer-space-plans/native-matrix-reset.py')],
     cwd=branch,env={**os.environ,'ANDROID_HOME':str(Path.home()/'Library/Android/sdk')},stdout=log,stderr=subprocess.STDOUT)
  shell('am','force-stop','dev.findroid')
  after=shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(output/'PRIVATE-workspace-after.tar').write_bytes(after)
  receipt['originalWorkspaceTreeKept']=entries(after)==entries(workspace)
  assert receipt['originalWorkspaceTreeKept'],'Workspace changed beyond owned fixture cleanup; preserve before and after snapshots'
  receipt['batchDriverExitCode']=result.returncode
  if (native/'native.json').exists():
   report=json.loads((native/'native.json').read_text());receipt['nativePassed']=report['passed'];receipt['nativeFailed']=report['failed'];receipt['nativeSkipped']=report['skipped']
   receipt['nativeRegressionComplete']=True
   for row in reused:
    assert digest(branch/row['report'])==row['reportSha256'] and digest(branch/row['log'])==row['logSha256']
   assert json.loads((native/'batches/59-ExternalDragWorkflowTest-before-retry-1.json').read_text())==failure
   assert result.returncode==0 and report['success'] and report['passed']==549 and report['failed']==report['skipped']==0
  else:
   receipt['nativeRegressionComplete']=False
   reports=[json.loads(p.read_text()) for p in sorted((native/'batches').glob('[0-9][0-9]-*.json'))]
   receipt['acceptedBatchesBeforeStop']=sum(r.get('success',False) for r in reports)
   receipt['acceptedCasesBeforeStop']=sum(r.get('passed',0) for r in reports if r.get('success',False))
   raise RuntimeError('Native regression stopped; inspect retained batch-driver.txt and individual reports. No complete acceptance claimed.')
 finally:
  shell('am','force-stop','dev.findroid')
  for relative in pair:call('install','-r','-d',main/relative)
  if verified:
   row=restore.restore_one(a.serial,public);(output/'restoration.json').write_text(json.dumps(row,indent=2)+'\n');receipt['restorationComplete']=True
  else:
   call('install','-r','-d',public);receipt['originalStateKeptWithoutBaselineOverwrite']=True
  assert installed('dev.findroid')==digest(public)
  assert installed('dev.findroid.test')==frozen['files'][pair[1]]['sha256']
  (output/'build-provenance.json').write_text(json.dumps(receipt,indent=2)+'\n')
 print(a.serial+': 549 native checks passed in 59 batches; original data and exact public 0.47 restored.',flush=True)
