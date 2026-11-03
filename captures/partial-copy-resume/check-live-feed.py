"""Exercise the production update source and retain only its public artifact identity."""
import hashlib,importlib.util,json,shlex,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[2];proof=root/'captures/partial-copy-resume';serial='emulator-5582'
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
info=json.loads((root/'captures/release/0.44.0/BUILDINFO.json').read_text());expected=info['artifacts'][0]['sha256']
apk=proof/'public/Findroid-0.44.0-preview.apk';assert hashlib.sha256(apk.read_bytes()).hexdigest()==expected
spec=importlib.util.spec_from_file_location('native_result_parser',root/'scripts/check-device.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
def call(*args):return subprocess.run([adb,'-s',serial,*map(str,args)],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=180).stdout
def shell(*args):return call('shell',shlex.join(map(str,args)))
log=proof/'api36-live-feed.txt';report_path=proof/'api36-live-feed.json';assert not log.exists() and not report_path.exists()
shell('am','force-stop','dev.findroid');call('install','-r',root/'app/build/outputs/apk/debug/app-debug.apk')
started=time.monotonic()
try:
    with log.open('wb') as output:
        result=subprocess.run([adb,'-s',serial,'shell','am','instrument','-w','-r','-e','class','dev.findroid.AppUpdateLiveFeedTest','-e','liveUpdates','true','dev.findroid.test/androidx.test.runner.AndroidJUnitRunner'],stdout=output,stderr=subprocess.STDOUT,timeout=240)
    report=parser.summarize(log.read_text(),result.returncode)
    assert report['success'] and report['passed']==1 and report['failed']==report['skipped']==0
    update=json.loads(shell('run-as','dev.findroid','cat','files/live-update-acceptance.json'))
    assert update['versionCode']==46 and update['sha256']==expected
    report.update(serial=serial,durationSeconds=round(time.monotonic()-started,1),downloadedVersionCode=update['versionCode'],downloadedApkSha256=update['sha256'],productionSourceUsed=True)
finally:
    shell('am','force-stop','dev.findroid');call('install','-r',apk)
installed=shell('pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
assert hashlib.sha256(call('exec-out','cat',installed)).hexdigest()==expected
report['restoredExactPublicApk']=True;report_path.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
