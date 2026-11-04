"""Verify the held-save regression against old/new app bytes, retaining every attempt."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import fcntl,hashlib,json,subprocess
root=Path(__file__).resolve().parents[2];proof=Path(__file__).resolve().parent
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
old=proof/'candidate-build-5/app/build/outputs/apk/debug/app-debug.apk'
new=root/'app/build/outputs/apk/debug/app-debug.apk';tests=root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
method='dev.findroid.FinderWorkflowTest#snackbarUndoRestoresTrashedFile'
output=proof/'snackbar-save-gate';output.mkdir(exist_ok=False)
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
identities=dict(oldAppSha256=sha(old),newAppSha256=sha(new),testApkSha256=sha(tests))
assert identities['oldAppSha256']!=identities['newAppSha256']
def work(serial):
 api='api30' if serial=='emulator-5602' else 'api36'
 with (proof/('.'+serial+'-acceptance.lock')).open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX)
  with (output/(api+'-driver.txt')).open('x') as log:
   def run(args,check=True):return subprocess.run(list(map(str,args)),cwd=root,stdout=log,stderr=subprocess.STDOUT,check=check)
   try:
    run([adb,'-s',serial,'install','-r',tests])
    stages=[('old',old)] if api=='api30' else []
    stages += [('new',new)]
    rows=[]
    for name,apk in stages:
     run([adb,'-s',serial,'install','-r',apk])
     run(['python3',proof/'native-reset.py','restore',serial])
     run([adb,'-s',serial,'shell','am','start','--windowingMode','1','-n','dev.findroid/.MainActivity'])
     logpath=output/(api+'-'+name+'.txt')
     result=run(['python3',root/'scripts/check-device.py','--serial',serial,'--classes',method,'--output',logpath],check=False)
     report=json.loads(logpath.with_suffix('.json').read_text())
     rows.append(dict(stage=name,report=str(logpath.with_suffix('.json').relative_to(root)),appSha256=sha(apk)))
     if name=='old':
      assert result.returncode==1 and report['failed']==1 and report['passed']==0
      assert 'assert count' in report['cases'][0]['error'].lower(), 'The negative control must fail at the premature Undo assertion.'
     else:assert result.returncode==0 and report['success'] and report['passed']==1
    return dict(serial=serial,oldAppPrematureActionReproduced=api=='api30',newAppHoldsActionUntilSaveCompletes=True,results=rows)
   finally:
    run(['python3',proof/'native-reset.py','restore',serial])
    run([adb,'-s',serial,'install','-r','-d',root/'captures/release/0.44.0/Findroid-0.44.0-preview.apk'])
errors=[];rows=[]
try:
 with ThreadPoolExecutor(max_workers=2) as pool:
  futures=[pool.submit(work,s) for s in ('emulator-5602','emulator-5582')]
  for future in futures:
   try:rows.append(future.result())
   except Exception as error:errors.append(str(error))
finally:
 with (output/'restoration.txt').open('x') as log:
  result=subprocess.run(['python3',proof/'restore-public-tablets.py'],cwd=root,stdout=log,stderr=subprocess.STDOUT)
 if result.returncode:errors.append('Inspect original-state restoration')
assert not errors,errors
assert identities==dict(oldAppSha256=sha(old),newAppSha256=sha(new),testApkSha256=sha(tests))
(output/'verification.json').write_text(json.dumps(dict(**identities,regressionReproducedAndCorrected=True,tablets=rows),indent=2)+'\n')
print('Held-save regression: old app exposes Undo too early; corrected app passes on both tablets. Original states and public0.44 restored.')
