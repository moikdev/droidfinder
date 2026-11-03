"""Private stopped-app state preservation. No payloads or names are printed or published."""
import argparse, hashlib, io, json, shlex, subprocess, tarfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
ADB=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
SERIALS=('emulator-5602','emulator-5582')
MANAGED=('shared_prefs','databases','no_backup','cache','files')
EXCLUDED=('files/Workspace','files/ProviderFixtures')
BASE=ROOT/'captures/copy-verification/private-native-state'

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
    if action=='prepare':
        assert not folder.exists(), 'Keep private original snapshots immutable'
        call(serial,'install','-r',ROOT/'app/build/outputs/apk/debug/app-debug.apk')
        call(serial,'install','-r',ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
        shell(serial,'am','force-stop','dev.findroid')
        data=snapshot(serial);files=contents(data)
        folder.mkdir(parents=True)
        archive.write_bytes(data);archive.chmod(0o600)
        (folder/'PRIVATE-MANIFEST.json').write_text(json.dumps(dict(sha256=hashlib.sha256(data).hexdigest(),files=files),indent=2)+'\n')
        return dict(serial=serial,privateOriginalSnapshotSaved=True,managedFileCount=len(files))
    before=json.loads((folder/'PRIVATE-MANIFEST.json').read_text());data=archive.read_bytes()
    assert hashlib.sha256(data).hexdigest()==before['sha256'] and contents(data)==before['files']
    current=contents(snapshot(serial))
    extra=sorted(set(current)-set(before['files']))
    for index in range(0,len(extra),100):
        names=extra[index:index+100];assert all(managed(name) for name in names)
        shell(serial,'run-as','dev.findroid','rm','-f','--',*names)
    shell(serial,'run-as','dev.findroid','tar','-xf','-',data=data)
    assert contents(snapshot(serial))==before['files'], 'Private original bytes and file absences must match'
    return dict(serial=serial,originalManagedMetadataDatabasesAndCacheRestored=True,originalFileAbsencesRestored=True,managedFileCount=len(before['files']),privateOriginalSnapshotUnchanged=True)

p=argparse.ArgumentParser();p.add_argument('action',choices=('prepare','restore'));p.add_argument('--serial',choices=SERIALS);args=p.parse_args()
with ThreadPoolExecutor(max_workers=2) as pool: rows=list(pool.map(lambda serial:work(serial,args.action),(args.serial,) if args.serial else SERIALS))
if args.action=='restore':
    receipt=BASE.parent/'native-state-restoration.json'
    retained={r['serial']:r for r in json.loads(receipt.read_text())} if receipt.exists() else {}
    retained.update({r['serial']:r for r in rows})
    receipt.write_text(json.dumps([retained[s] for s in SERIALS if s in retained],indent=2)+'\n')
print(json.dumps(rows,indent=2))
