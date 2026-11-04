"""Derive the focused receipt from three accepted batches in the complete native suite."""
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[2];proof=Path(__file__).resolve().parent
classes={'dev.findroid.PersistentUndoNativeTest','dev.findroid.PersistentUndoWorkflowTest','dev.findroid.CommandClarityWorkflowTest'}
for api,serial in (('api30','emulator-5602'),('api36','emulator-5582')):
    path=proof/(api+'-regression.json');native=json.loads(path.read_text())
    assert native['success'] and native['serial']==serial and native['passed']==485 and len(native['isolatedBatches'])==52
    batches=[b for b in native['isolatedBatches'] if b['selector'] in classes];assert len(batches)==3
    cases=[c for c in native['cases'] if c['class'] in classes];assert len(cases)==46 and all(c['code']==0 for c in cases)
    assert sum(b['passed'] for b in batches)==46
    fields=('success','runnerCompleted','runnerSucceeded','adbExitCode','serial','apkSha256','installedApkSha256','timedOut')
    report={key:native[key] for key in fields}
    report.update(passed=len(cases),skipped=0,failed=0,cases=cases,isolatedBatches=batches,
        derivedFromFullNativeAcceptance=True,fullAcceptanceSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
        selectionDescription='22 persistent-Undo native checks, 8 workflow checks and 16 existing command-clarity checks; no separate test execution counted.')
    output=proof/(api+'-focused.json');log=proof/(api+'-focused.txt');assert not output.exists() and not log.exists()
    output.write_text(json.dumps(report,indent=2)+'\n')
    log.write_text('Focused cases selected from the accepted complete native suite. No additional execution is claimed.\n'+''.join('\nBatch: '+b['selector']+'\n'+(root/b['log']).read_text() for b in batches))
    print(api+': 46 focused cases derived from the accepted 485, without double counting.')
