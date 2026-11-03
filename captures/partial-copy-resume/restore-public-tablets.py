"""Restore immutable original managed bytes/environment, then the exact public 0.44 APK."""
from concurrent.futures import ThreadPoolExecutor
import base64,hashlib,json,re,shlex,subprocess,time
from pathlib import Path
from threading import Lock
root=Path(__file__).resolve().parents[2];proof=root/'captures/partial-copy-resume'
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb');serials=('emulator-5602','emulator-5582')
debug=root/'app/build/outputs/apk/debug/app-debug.apk';apk=proof/'public/Findroid-0.44.0-preview.apk'
expected=hashlib.sha256(apk.read_bytes()).hexdigest();restore_lock=Lock()
def call(serial,*parts):
    return subprocess.run([adb,'-s',serial,*map(str,parts)],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180).stdout
def shell(serial,*parts):return call(serial,'shell',shlex.join(map(str,parts)))
def grants(serial):
    marker='FINDROID_PRIVATE_GRANT_AUDIT_'+str(time.time_ns());since=shell(serial,'date','+%m-%d %H:%M:%S').decode().strip()+'.000'
    shell(serial,'log','-t','FindroidGrantAudit','-p','i',marker)
    text=shell(serial,'am','instrument','-w','-r','-e','class','dev.findroid.StorageGrantRecoveryTest#auditPersistedPermissionsForSyntheticAcceptance','-e','storageQaAudit','true','dev.findroid.test/androidx.test.runner.AndroidJUnitRunner').decode()
    assert 'OK (1 test)' in text and 'FAILURES' not in text
    logs=shell(serial,'logcat','-d','-T',since,'-v','raw','-s','FindroidGrantAudit:I','System.out:I').decode()
    found=re.findall(r'FINDROID_GRANT_AUDIT:([A-Za-z0-9+/=]+)',logs.rsplit(marker,1)[1]);assert len(found)==1
    return json.loads(base64.b64decode(found[0],validate=True))
def work(serial):
    baseline=json.loads((proof/'private-native-state'/serial/'PRIVATE-ENVIRONMENT.json').read_text())
    shell(serial,'am','force-stop','dev.findroid');call(serial,'install','-r',debug)
    try:
        assert grants(serial)==baseline['originalGrants'], 'Original URI grants must survive the full acceptance'
        shell(serial,'settings','put','system','font_scale',baseline['fontScale'])
        shell(serial,'appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE',baseline['originalAccess'])
        mode=re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)',shell(serial,'appops','get','dev.findroid','MANAGE_EXTERNAL_STORAGE').decode())
        assert (mode.group(1) if mode else 'default')==baseline['originalAccess']
        scale=shell(serial,'settings','get','system','font_scale').decode().strip();assert float(scale)==float(baseline['fontScale'])
    finally:
        # Grant audits themselves can write preferences; restore only after they have stopped.
        with restore_lock:
            result=subprocess.run(['python3',str(proof/'private-native-state.py'),'restore','--serial',serial],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        rows=json.loads(result.stdout);assert len(rows)==1 and rows[0]['serial']==serial
        call(serial,'install','-r',apk)
    row=rows[0];assert row['originalManagedMetadataDatabasesAndCacheRestored'] and row['originalFileAbsencesRestored']
    installed=shell(serial,'pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
    digest=hashlib.sha256(call(serial,'exec-out','cat',installed)).hexdigest();assert digest==expected
    return dict(**row,installedPublicApkSha256=digest,fontScale=scale,originalEnvironmentMatchesPersistedBaseline=True,originalUriGrantsKept=True,originalStorageModeRestored=True)
with ThreadPoolExecutor(max_workers=2) as pool:rows=list(pool.map(work,serials))
(proof/'restored-public-tablets.json').write_text(json.dumps(rows,indent=2)+'\n');print(json.dumps(rows,indent=2))
