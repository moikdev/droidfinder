"""Opt in to the production updater source on the assigned API36 test tablet."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import time

root=Path(__file__).resolve().parents[2]
evidence=root/'captures/copy-verification'
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
serial='emulator-5582'
expected=json.loads((root/'captures/release/0.43.0/findroid-update.json').read_text())

def call(*parts):
    return subprocess.run([adb,'-s',serial,*map(str,parts)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True,timeout=180).stdout

call('shell','am','force-stop','dev.findroid')
for p in ('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
    call('install','-r',root/p)
started=time.monotonic()
result=subprocess.run([adb,'-s',serial,'shell','am','instrument','-w','-r','-e','class','dev.findroid.AppUpdateLiveFeedTest','-e','liveUpdates','true','dev.findroid.test/androidx.test.runner.AndroidJUnitRunner'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=300)
text=result.stdout.decode(errors='replace')
(evidence/'api36-live-feed.txt').write_text(text)
spec=importlib.util.spec_from_file_location('check_device',root/'scripts/check-device.py')
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
report=helper.summarize(text,result.returncode)
assert report['success'] and report['passed']==1 and report['failed']==report['skipped']==0,report
actual=json.loads(call('exec-out','run-as','dev.findroid','cat','files/live-update-acceptance.json'))
for key in ('packageName','versionCode','versionName','sha256','bytes'):
    assert actual[key]==expected[key],(key,actual[key],expected[key])
report.update(serial=serial,durationSeconds=round(time.monotonic()-started,1),downloadedVersionCode=actual['versionCode'],downloadedVersionName=actual['versionName'],downloadedApkSha256=actual['sha256'])
call('shell','run-as','dev.findroid','rm','-f','files/live-update-acceptance.json')
(evidence/'api36-live-feed.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
