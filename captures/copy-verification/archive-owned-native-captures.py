"""Retain owned full native screenshots privately and remove only the three test paths."""
import argparse,hashlib,json,struct,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2];proof=root/'captures/copy-verification'
p=argparse.ArgumentParser();p.add_argument('--serial',required=True,choices=('emulator-5602','emulator-5582'));a=p.parse_args()
api='api30' if a.serial=='emulator-5602' else 'api36'
r=json.loads((proof/(api+'-regression.json')).read_text());assert r['success'] and r['passed']==426
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
def call(*parts):return subprocess.check_output([adb,'-s',a.serial,*parts])
folder=proof/api/'private-native-screenshots';folder.mkdir(mode=0o700,exist_ok=False)
rows=[]
for name in ('provider-wait','recent-rate','paused'):
 path='/sdcard/findroid-progress-'+name+'.png'
 data=call('exec-out','cat',path);assert data[:8]==b'\x89PNG\r\n\x1a\n'
 assert struct.unpack('>II',data[16:24])==(1920,1200)
 target=folder/('final-suite-'+name+'.png');assert not target.exists()
 target.write_bytes(data);target.chmod(0o600)
 assert hashlib.sha256(call('exec-out','cat',path)).digest()==hashlib.sha256(data).digest()
 call('shell','rm','--',path)
 result=subprocess.run([adb,'-s',a.serial,'shell','test','!','-e',path],capture_output=True);assert result.returncode==0
 rows.append(dict(name=name,retainedPrivately=True,removedOnlyOwnedSharedCapture=True))
receipt=dict(serial=a.serial,nativeApkPair=r['apkSha256'],fullScreenshotsExcludedFromPublication=True,files=rows)
(proof/(api+'-owned-capture-cleanup.json')).write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(dict(serial=a.serial,ownedScreenshotsRetainedPrivatelyAndRemoved=len(rows))))
