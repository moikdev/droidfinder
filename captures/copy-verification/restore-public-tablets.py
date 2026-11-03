"""Restore immutable private pre-0.43 managed bytes, then the exact public optimized APK."""
from concurrent.futures import ThreadPoolExecutor
import hashlib,json,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2];proof=root/'captures/copy-verification'
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb');serials=('emulator-5602','emulator-5582')
debug=root/'app/build/outputs/apk/debug/app-debug.apk';apk=proof/'public/Findroid-0.43.0-preview.apk'
expected=hashlib.sha256(apk.read_bytes()).hexdigest()
def call(serial,*parts):
 return subprocess.run([adb,'-s',serial,*map(str,parts)],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180).stdout
def enable(serial):
 call(serial,'shell','am','force-stop','dev.findroid');call(serial,'install','-r',debug)
with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(enable,serials))
subprocess.run(['python3',str(proof/'private-native-state.py'),'restore'],check=True)
restoration=json.loads((proof/'native-state-restoration.json').read_text());by_serial={row['serial']:row for row in restoration}
assert set(by_serial)==set(serials)
def work(serial):
 row=by_serial[serial];assert row['originalManagedMetadataDatabasesAndCacheRestored'] and row['originalFileAbsencesRestored']
 call(serial,'install','-r',apk)
 installed=call(serial,'shell','pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
 digest=hashlib.sha256(call(serial,'exec-out','cat',installed)).hexdigest();assert digest==expected
 scale=call(serial,'shell','settings','get','system','font_scale').decode().strip();assert float(scale)==1.0
 return dict(**row,installedPublicApkSha256=digest,fontScale=scale)
with ThreadPoolExecutor(max_workers=2) as pool:rows=list(pool.map(work,serials))
(proof/'restored-public-tablets.json').write_text(json.dumps(rows,indent=2)+'\n');print(json.dumps(rows,indent=2))
