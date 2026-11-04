"""Retain owned shared captures privately, verify original environment, then run optimized acceptance."""
import argparse,base64,hashlib,json,re,shlex,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[2];proof=root/'captures/persistent-undo'
p=argparse.ArgumentParser();p.add_argument('--serial',required=True,choices=('emulator-5602','emulator-5582'));a=p.parse_args()
api='api30' if a.serial=='emulator-5602' else 'api36';adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
def call(*args):return subprocess.check_output([adb,'-s',a.serial,*map(str,args)],stderr=subprocess.PIPE)
def shell(*args):return call('shell',shlex.join(map(str,args)))
native=json.loads((proof/(api+'-regression.json')).read_text());assert native['success'] and native['passed']==485 and len(native['isolatedBatches'])==52
for suffix in ('.json','.txt'):
    target=proof/(api+'-final-native'+suffix);assert not target.exists();target.write_bytes((proof/(api+'-regression'+suffix)).read_bytes())
for path,digest in native['apkSha256'].items():assert hashlib.sha256((root/path).read_bytes()).hexdigest()==digest
folder=proof/(api+'-private-native-screenshots');folder.mkdir(mode=0o700,exist_ok=False);rows=[]
for name in ('provider-wait','recent-rate','paused'):
    path='/sdcard/findroid-progress-'+name+'.png';data=call('exec-out','cat',path);assert data[:8]==b'\x89PNG\r\n\x1a\n'
    target=folder/(name+'.png');target.write_bytes(data);target.chmod(0o600)
    assert hashlib.sha256(call('exec-out','cat',path)).digest()==hashlib.sha256(data).digest()
    shell('rm','--',path);assert subprocess.run([adb,'-s',a.serial,'shell','test','!','-e',path],capture_output=True).returncode==0
    rows.append(dict(name=name,retainedPrivately=True,removedOnlyOwnedSharedCapture=True))
(proof/(api+'-owned-capture-cleanup.json')).write_text(json.dumps(dict(serial=a.serial,nativeApkPair=native['apkSha256'],fullScreenshotsExcludedFromPublication=True,files=rows),indent=2)+'\n')
baseline=json.loads((proof/'private-native-state'/a.serial/'PRIVATE-ENVIRONMENT.json').read_text())
marker='FINDROID_PRIVATE_GRANT_AUDIT_'+str(time.time_ns());since=shell('date','+%m-%d %H:%M:%S').decode().strip()+'.000'
shell('log','-t','FindroidGrantAudit','-p','i',marker)
text=shell('am','instrument','-w','-r','-e','class','dev.findroid.StorageGrantRecoveryTest#auditPersistedPermissionsForSyntheticAcceptance','-e','storageQaAudit','true','dev.findroid.test/androidx.test.runner.AndroidJUnitRunner').decode()
assert 'OK (1 test)' in text and 'FAILURES' not in text
logs=shell('logcat','-d','-T',since,'-v','raw','-s','FindroidGrantAudit:I','System.out:I').decode()
found=re.findall(r'FINDROID_GRANT_AUDIT:([A-Za-z0-9+/=]+)',logs.rsplit(marker,1)[1]);assert len(found)==1
assert json.loads(base64.b64decode(found[0],validate=True))==baseline['originalGrants'], 'Native tests must keep original URI grants'
mode=re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)',shell('appops','get','dev.findroid','MANAGE_EXTERNAL_STORAGE').decode())
assert (mode.group(1) if mode else 'default')==baseline['originalAccess'], 'Native tests must keep original storage mode'
assert float(shell('settings','get','system','font_scale').decode().strip())==float(baseline['fontScale'])
subprocess.run(['python3',str(proof/'private-native-state.py'),'restore','--serial',a.serial],check=True,stdout=subprocess.PIPE)
(proof/(api+'-native-environment.json')).write_text(json.dumps(dict(serial=a.serial,originalEnvironmentMatchesPersistedBaseline=True,originalUriGrantsKept=True,originalStorageModeKept=True,originalFontScaleKept=True,originalManagedBytesRestoredBeforeOptimizedAcceptance=True),indent=2)+'\n')
print(api+' complete native acceptance and original environment verified',flush=True)
subprocess.run(['python3',str(root/'scripts/check-persistent-undo.py'),'--serial',a.serial,'--apk',str(root/'app/build/outputs/apk/preview/app-preview.apk'),'--previous-apk',str(root/'captures/release/0.44.0/Findroid-0.44.0-preview.apk'),'--output',str(proof/(api+'-optimized'))],check=True)
