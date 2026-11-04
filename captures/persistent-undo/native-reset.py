"""Preserve owned dialog proofs, then restore immutable private originals between native batches."""
from pathlib import Path
import hashlib,json,subprocess,sys,time
assert len(sys.argv)==3 and sys.argv[1]=='restore' and sys.argv[2] in ('emulator-5602','emulator-5582')
root=Path(__file__).resolve().parents[2];proof=Path(__file__).resolve().parent;serial=sys.argv[2]
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb');api='api30' if serial=='emulator-5602' else 'api36'
result=subprocess.run([adb,'-s',serial,'shell','run-as','dev.findroid','ls','cache/persistent-undo-qa'],capture_output=True,text=True)
if result.returncode==0 and result.stdout.strip():
    folder=proof/(api+'-native-images')
    if folder.exists():folder.rename(folder.with_name(folder.name+'-prior-attempt-'+str(time.time_ns())))
    folder.mkdir();images=[]
    allowed={'saved.png','reset-review.png','changed-file.png','interrupted-review.png','session-boundary.png','large-text-reset.png'}
    for name in result.stdout.splitlines():
        assert name in allowed
        data=subprocess.check_output([adb,'-s',serial,'exec-out','run-as','dev.findroid','cat','cache/persistent-undo-qa/'+name])
        assert data[:8]==b'\x89PNG\r\n\x1a\n';(folder/name).write_bytes(data)
        images.append(dict(path=name,sha256=hashlib.sha256(data).hexdigest()))
    artifacts={}
    for package in ('dev.findroid','dev.findroid.test'):
        path=subprocess.check_output([adb,'-s',serial,'shell','pm','path',package],text=True).strip().removeprefix('package:')
        artifacts[package]=hashlib.sha256(subprocess.check_output([adb,'-s',serial,'exec-out','cat',path])).hexdigest()
    (folder/'capture-provenance.json').write_text(json.dumps(dict(serial=serial,installedApkSha256=artifacts,images=images),indent=2)+'\n')
# Retain only a new owned failure image privately before restoring the exact baseline.
owned='files/archive-open-failure.png'
original=json.loads((proof/'private-native-state'/serial/'PRIVATE-MANIFEST.json').read_text())['files'].get(owned)
failed=subprocess.run([adb,'-s',serial,'exec-out','run-as','dev.findroid','cat',owned],capture_output=True)
if failed.returncode==0 and failed.stdout[:8]==b'\x89PNG\r\n\x1a\n' and hashlib.sha256(failed.stdout).hexdigest()!=original:
    folder=proof/'PRIVATE-native-failures'/api;folder.mkdir(mode=0o700,parents=True,exist_ok=True)
    image=folder/('archive-open-'+str(time.time_ns())+'.png');image.write_bytes(failed.stdout);image.chmod(0o600)
subprocess.run([sys.executable,str(Path(__file__).with_name('private-native-state.py')),'restore','--serial',serial],check=True)
