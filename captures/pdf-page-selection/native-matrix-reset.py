"""Collect only named synthetic captures, then restore immutable metadata between native batches."""
from pathlib import Path
import hashlib,json,os,shlex,subprocess,sys,time
root=Path(__file__).resolve().parents[2];proof=root/'captures/pdf-page-selection'
original=Path('/Users/LENOVO/.t3/projects/droidfinder/captures/persistent-undo')
assert len(sys.argv)==3 and sys.argv[1]=='restore' and sys.argv[2] in ('emulator-5602','emulator-5582')
serial=sys.argv[2];api='api30' if serial=='emulator-5602' else 'api36';adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
output=Path(os.environ['FINDROID_PDF_NATIVE_OUTPUT']).resolve();assert output.is_relative_to(proof) and output.is_dir()
counter=output/'PRIVATE-reset-counter.json';index=json.loads(counter.read_text()) if counter.exists() else 0
baseline=json.loads((original/'private-native-state'/serial/'PRIVATE-MANIFEST.json').read_text())['files']
def run(*parts,check=True):return subprocess.run([adb,'-s',serial,*map(str,parts)],check=check,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
def shell(*parts,check=True):return run('shell','-T',shlex.join(map(str,parts)),check=check)
sets={'cache/pdf-page-selection-qa':{'word-and-handles.png','two-line-selection.png','zoomed-selection.png','direct-handle-drag.png','first-long-press-drag.png'},'cache/space-plan-qa':{'normal-review.png'},'cache/recovery-inbox-qa':{'normal-review.png','large-text-review.png'},
      'cache/persistent-undo-qa':{'saved.png','reset-review.png','changed-file.png','interrupted-review.png','session-boundary.png','large-text-reset.png'},
      'cache/folder-sync-qa':{'default-choices.png','completed-addition.png','confirmed-replacement-and-trash.png','stale-review-stopped.png','large-text-confirmation.png','font-layout.json'}}
images=[]
for folder,allowed in sets.items():
 result=shell('run-as','dev.findroid','ls',folder,check=False)
 if result.returncode:continue
 for name in result.stdout.decode().splitlines():
  assert name in allowed
  path=folder+'/'+name;data=run('exec-out','run-as','dev.findroid','cat',path).stdout;digest=hashlib.sha256(data).hexdigest()
  if baseline.get(path)==digest:continue
  assert data.startswith(b'\x89PNG\r\n\x1a\n') if name.endswith('.png') else isinstance(json.loads(data),dict)
  target=output/'images'/('after-batch-'+str(index).zfill(3))/Path(folder).name/name
  target.parent.mkdir(parents=True,exist_ok=True);assert not target.exists();target.write_bytes(data)
  images.append(dict(path=str(target.relative_to(root)),sha256=digest))
if images:
 receipt=output/'images'/('after-batch-'+str(index).zfill(3))/'capture-provenance.json'
 receipt.write_text(json.dumps(dict(serial=serial,completedBatch=index,images=images),indent=2)+'\n')
# Keep any newly generated archive failure screen private before removing owned test output.
path='files/archive-open-failure.png';failed=run('exec-out','run-as','dev.findroid','cat',path,check=False)
if failed.returncode==0 and failed.stdout.startswith(b'\x89PNG\r\n\x1a\n') and hashlib.sha256(failed.stdout).hexdigest()!=baseline.get(path):
 target=output/('PRIVATE-archive-failure-'+str(time.time_ns())+'.png');target.write_bytes(failed.stdout);target.chmod(0o600)
subprocess.run([sys.executable,str(original/'private-native-state.py'),'restore','--serial',serial],check=True)
counter.write_text(json.dumps(index+1)+'\n')

# A host-only stop request takes effect only after the original metadata was restored.
# The matrix owner then performs its normal finally restoration and public-APK restore.
stop_request=proof/('STOP-AFTER-BATCH-'+serial+'.json')
if stop_request.exists():
 request=json.loads(stop_request.read_text())
 assert request['serial']==serial and request['stopAfterRestoration'] is True
 print('Controlled matrix stop after completed batch and original metadata restoration.',flush=True)
 sys.exit(75)
