"""Publish a bounded progress record; this is not full-candidate acceptance."""
from pathlib import Path
import hashlib, json
ROOT=Path(__file__).resolve().parents[2]
PROOF=ROOT/'captures/transfer-space-plans'
sha=lambda path:hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
manifest=PROOF/'candidate-build-2.json'
frozen=json.loads(manifest.read_text())
assert frozen['jvm']==dict(tests=930,failures=0,errors=0,skipped=0)
assert frozen['lint']==dict(errors=0,warnings=148,hints=12)
for name,digest in frozen['sourceFiles'].items(): assert sha(ROOT/name)==digest,name
for name,record in frozen['files'].items():
    assert sha(ROOT/name)==record['sha256'] and (ROOT/name).stat().st_size==record['bytes'],name
pair={name:row['sha256'] for name,row in frozen['files'].items() if name in (
    'app/build/outputs/apk/debug/app-debug.apk',
    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')}
assert len(pair)==2
selection=[
    'docs/TRANSFER-SPACE-REVIEW.md','docs/releases/0.48.0.md','docs/IMPROVEMENT-TRACKER.md','VALIDATION.md',
    'scripts/check-transfer-space.py','scripts/package-transfer-space-validation.py','scripts/check-ota-upgrade.py',
    'captures/release/0.48.0/BUILDINFO.json','captures/release/0.48.0/SHA256SUMS',
    'captures/release/0.48.0/findroid-update.json']
for name in (
    'collect-focused-progress.py','candidate-build-2.json','candidate-2-source-binding.json',
    'candidate-build-3-gradle.txt','candidate-package-2.txt','check-build-artifacts.py',
    'check-source-binding.py','freeze-candidate.py','preview-apksigner.txt','previous-apksigner.txt',
    'preview-native-libraries.json','release-native-libraries.json','preview-zipalign.txt','release-zipalign.txt',
    'signing-continuity.json','run-native-matrix.py','run-public-ota.py','native-matrix-reset.py',
    'native-regression-classes.txt','checker-optimized-attempt-1.py','checker-optimized-attempt-1-source.json'):
    selection.append(str((PROOF/name).relative_to(ROOT)))
selection += [str(path.relative_to(ROOT)) for path in sorted((PROOF/'superseded-candidate-1').iterdir()) if path.is_file()]
rows=[]
for api,serial in [('api30','emulator-5602'),('api36','emulator-5582')]:
    folder=PROOF/f'candidate-2-{api}-native/batches'
    contract=folder/'contract.json'
    contents=json.loads(contract.read_text())
    assert contents['serial']==serial and contents['apkSha256']==pair
    assert len(contents['selectors'])==59
    selection.append(str(contract.relative_to(ROOT)))
    cases=[];reports=[]
    for name,count in [('01-TransferSpaceInspectionNativeTest',8),('02-TransferSpaceWorkflowTest',9)]:
        report=folder/(name+'.json');log=folder/(name+'.txt')
        data=json.loads(report.read_text())
        assert data['success'] and data['passed']==count and data['failed']==data['skipped']==0
        assert len(data['cases'])==count and all(case['code']==0 for case in data['cases'])
        cases += [(case['class'],case['test']) for case in data['cases']]
        assert log.is_file()
        reports.append(dict(report=str(report.relative_to(ROOT)),reportSha256=sha(report),log=str(log.relative_to(ROOT)),logSha256=sha(log),passed=count))
        selection += [str(report.relative_to(ROOT)),str(log.relative_to(ROOT))]
    assert len(set(cases))==17
    rows.append(dict(serial=serial,contract=str(contract.relative_to(ROOT)),contractSha256=sha(contract),
        recordedApkHashesMatchFrozenPair=True,passed=17,failed=0,skipped=0,reports=reports))
report=dict(sourceCommit=frozen['sourceCommit'],frozenManifestSha256=sha(manifest),
    focusedSubsetOnly=True,fullNativeAcceptancePending=True,optimizedAcceptancePending=True,
    publicOtaAcceptancePending=True,proposal45Accepted=False,implementedProposals=35,
    totalProposals=72,jvm=frozen['jvm'],lint=frozen['lint'],frozenSourceInputs=395,
    frozenOutputs=104,allFrozenSourcesAndOutputsUnchanged=True,tablets=rows,
    hashScope='Each tablet batch contract records the exact frozen debug/test pair. Per-batch reports record cases; final native aggregate and controller will verify installed APK hashes and restoration after all batches. No per-batch installed-hash claim.')
target=PROOF/'candidate-2-focused-progress.json';assert not target.exists()
target.write_text(json.dumps(report,indent=2)+'\n')
selection.append(str(target.relative_to(ROOT)))
filelist=PROOF/'progress-publication-files.json';assert not filelist.exists()
selection.append(str(filelist.relative_to(ROOT)))
filelist.write_text(json.dumps(sorted(set(selection)),indent=2)+'\n')
print(json.dumps(dict(selectedFiles=len(set(selection)),focusedPassedPerApi=17,accepted=False)))
