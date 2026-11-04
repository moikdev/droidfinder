"""Run the frozen native suites and optimized checks on only the two assigned tablets."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import fcntl,hashlib,json,subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[2];proof=Path(__file__).resolve().parent
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
frozen=json.loads((proof/'candidate-build-6.json').read_text())
assert (frozen['version'],frozen['versionCode'])==('0.45.0-preview',47)
for name,row in frozen['files'].items():assert hashlib.sha256((root/name).read_bytes()).hexdigest()==row['sha256']
for name,digest in frozen['sourceFiles'].items():assert hashlib.sha256((root/name).read_bytes()).hexdigest()==digest

def run_one(serial):
    api='api30' if serial=='emulator-5602' else 'api36'
    with (proof/(api+'-final-driver.txt')).open('x') as log:
        for relative in ('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
            subprocess.run([adb,'-s',serial,'install','-r',str(root/relative)],check=True,stdout=log,stderr=subprocess.STDOUT)
        subprocess.run(['python3',str(root/'scripts/check-device-batches.py'),'--serial',serial,
            '--classes-file',str(proof/'native-regression-classes.txt'),'--output',str(proof/(api+'-regression.txt')),
            '--batch-directory',str(proof/(api+'-native-batches')),'--reset-script',str(proof/'native-reset.py')],check=True,stdout=log,stderr=subprocess.STDOUT)
        subprocess.run(['python3',str(proof/'after-native.py'),'--serial',serial],check=True,stdout=log,stderr=subprocess.STDOUT)
    return api

def work(serial):
    with (proof/('.'+serial+'-acceptance.lock')).open('a') as device_lock:
        fcntl.flock(device_lock,fcntl.LOCK_EX)
        return run_one(serial)

errors=[]
try:
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures={pool.submit(work,serial):serial for serial in ('emulator-5602','emulator-5582')}
        for future in as_completed(futures):
            try:print(future.result()+' final native and optimized acceptance passed',flush=True)
            except Exception as error:errors.append(futures[future]+': '+str(error));print(errors[-1],flush=True)
finally:
    with (proof/'after-final-acceptance-restoration.txt').open('x') as log:
        restored=subprocess.run(['python3',str(proof/'restore-public-tablets.py')],stdout=log,stderr=subprocess.STDOUT)
    if restored.returncode:errors.append('Original state and public APK restoration failed; inspect the retained log')
assert not errors, '\n'.join(errors)
