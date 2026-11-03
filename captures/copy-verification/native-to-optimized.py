"""Continue each assigned tablet only after its complete native acceptance finishes."""
import argparse,hashlib,json,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];PROOF=ROOT/'captures/copy-verification'
p=argparse.ArgumentParser();p.add_argument('--serial',required=True,choices=('emulator-5602','emulator-5582'));a=p.parse_args()
api='api30' if a.serial=='emulator-5602' else 'api36';report=PROOF/(api+'-regression.json')
receipt=dict(serial=a.serial,waitingForCompleteNativeAcceptance=True)
def save(): (PROOF/(api+'-post-native.json')).write_text(json.dumps(receipt,indent=2)+'\n')
def run(*parts):subprocess.run(list(map(str,parts)),cwd=ROOT,check=True)
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
save();deadline=time.monotonic()+7200
while not report.exists():
    assert time.monotonic()<deadline,'Native acceptance did not finish; no device mutation has started'
    time.sleep(1)
native=json.loads(report.read_text())
assert native['success'] and native['serial']==a.serial and native['passed']==426 and native['failed']==native['skipped']==0
assert len(native['isolatedBatches'])==48
for name,digest in native['apkSha256'].items():assert sha(ROOT/name)==digest
receipt.update(waitingForCompleteNativeAcceptance=False,nativeCases=426,nativeReportSha256=sha(report));save()
for suffix in ('.json','.txt'):
    source=report.with_suffix(suffix);target=PROOF/(api+'-final-native'+suffix)
    assert not target.exists();target.write_bytes(source.read_bytes())
run('python3',PROOF/'archive-owned-native-captures.py','--serial',a.serial)
receipt['ownedFullNativeScreenshotsRetainedPrivatelyAndRemoved']=True;save()
run('python3',PROOF/'private-native-state.py','restore','--serial',a.serial)
receipt['originalPrivateStateRestoredBeforeOptimizedAcceptance']=True;save()
checker=ROOT/'scripts/check-copy-verification.py';receipt['optimizedCheckerSha256']=sha(checker);save()
run('python3',checker,'--serial',a.serial,'--apk',ROOT/'app/build/outputs/apk/preview/app-preview.apk',
    '--previous-apk',ROOT/'captures/release/0.42.0/Findroid-0.42.0-preview.apk',
    '--provider-apk',PROOF/'provider/Findroid-verification-test-provider.apk','--output',PROOF/(api+'-optimized'))
assert sha(checker)==receipt['optimizedCheckerSha256']
accepted=json.loads((PROOF/(api+'-optimized/verification.json')).read_text());assert accepted['success'] and not accepted['cleanupErrors']
receipt['optimizedAcceptancePassed']=True;save()
print(api+' complete native and optimized acceptance passed',flush=True)
