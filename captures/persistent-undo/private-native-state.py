"""Private stopped-app state preservation. No payloads or names are printed or published."""
import argparse, hashlib, io, json, shlex, subprocess, tarfile, fcntl, os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
ADB=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
SERIALS=('emulator-5602','emulator-5582')
MANAGED=('shared_prefs','databases','no_backup','cache','files')
EXCLUDED=('files/Workspace','files/ProviderFixtures')
BASE=ROOT/'captures/persistent-undo/private-native-state'

def call(serial,*parts,data=None):
    r=subprocess.run([ADB,'-s',serial,*map(str,parts)],input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
    assert r.returncode==0, 'Private state transport failed on '+serial
    return r.stdout

def shell(serial,*parts,data=None): return call(serial,'shell','-T',shlex.join(map(str,parts)),data=data)
def managed(name):
    path=Path(name)
    return not path.is_absolute() and '..' not in path.parts and path.parts and path.parts[0] in MANAGED and not any(name==prefix or name.startswith(prefix+'/') for prefix in EXCLUDED)
def contents(data):
    files={}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            name=member.name.rstrip('/')
            assert managed(name), 'Unexpected private state scope'
            assert member.isdir() or member.isfile(), 'A private state symlink needs separate review'
            if member.isfile(): files[name]=hashlib.sha256(archive.extractfile(member).read()).hexdigest()
    return files

def snapshot(serial):
    existing=[]
    for name in MANAGED:
        r=subprocess.run([ADB,'-s',serial,'shell','-T',shlex.join(['run-as','dev.findroid','test','-d',name])],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180)
        if r.returncode==0: existing.append(name)
        else: assert r.returncode==1
    assert existing
    return shell(serial,'run-as','dev.findroid','tar','-cf','-',*['--exclude='+name for name in EXCLUDED],*existing)

def work(serial,action):
    folder=BASE/serial;archive=folder/'PRIVATE-STATE.tar'
    shell(serial,'am','force-stop','dev.findroid')
    if action=='enable':
        accepted=json.loads((ROOT/'captures/partial-copy-resume/accepted-build.json').read_text())
        installed=call(serial,'shell','pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        assert hashlib.sha256(call(serial,'exec-out','cat',installed)).hexdigest()==accepted['artifacts']['app/build/outputs/apk/preview/app-preview.apk']['sha256'], 'Expected the exact restored public 0.44 APK'
        call(serial,'install','-r',ROOT/'app/build/outputs/apk/debug/app-debug.apk')
        call(serial,'install','-r',ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
        shell(serial,'am','force-stop','dev.findroid')
    before=json.loads((folder/'PRIVATE-MANIFEST.json').read_text());data=archive.read_bytes()
    assert hashlib.sha256(data).hexdigest()==before['sha256'] and contents(data)==before['files']
    current=contents(snapshot(serial))
    if action=='enable':
        assert current==before['files'], 'Current private bytes must match immutable original state before any test'
        return dict(serial=serial,immutableOriginalBytesVerifiedBeforeTests=True,managedFileCount=len(current))
    extra=sorted(set(current)-set(before['files']))
    for index in range(0,len(extra),100):
        names=extra[index:index+100];assert all(managed(name) for name in names)
        shell(serial,'run-as','dev.findroid','rm','-f','--',*names)
    shell(serial,'run-as','dev.findroid','tar','-xf','-',data=data)
    def directories(payload):
        with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
            return {m.name.rstrip('/') for m in archive if m.isdir()}
    original_dirs=directories(data)
    extra_dirs=sorted(directories(snapshot(serial))-original_dirs,key=lambda name:(-name.count('/'),name))
    for name in extra_dirs:
        assert managed(name)
        shell(serial,'run-as','dev.findroid','rmdir','--',name)
    assert directories(snapshot(serial))==original_dirs, 'Original directory absences must match'
    assert contents(snapshot(serial))==before['files'], 'Private original bytes and file absences must match'
    return dict(serial=serial,originalManagedMetadataDatabasesAndCacheRestored=True,originalFileAbsencesRestored=True,originalDirectoryAbsencesRestored=True,managedFileCount=len(before['files']),privateOriginalSnapshotUnchanged=True)

p=argparse.ArgumentParser();p.add_argument('action',choices=('enable','restore'));p.add_argument('--serial',choices=SERIALS);args=p.parse_args()
with ThreadPoolExecutor(max_workers=2) as pool: rows=list(pool.map(lambda serial:work(serial,args.action),(args.serial,) if args.serial else SERIALS))
if args.action=='restore':
    receipt=BASE.parent/'native-state-restoration.json'
    with (BASE.parent/'.native-state-receipt.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        retained={r['serial']:r for r in json.loads(receipt.read_text())} if receipt.exists() else {}
        retained.update({r['serial']:r for r in rows})
        temporary=receipt.with_name(receipt.name+'.'+str(os.getpid())+'.tmp')
        temporary.write_text(json.dumps([retained[s] for s in SERIALS if s in retained],indent=2)+'\n')
        os.replace(temporary,receipt)
print(json.dumps(rows,indent=2))
