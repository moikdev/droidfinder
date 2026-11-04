"""Validate an explicitly named PDF acceptance set before its privacy review.

The supplied configuration names every accepted native/core/layout/OTA attempt;
this tool never chooses the newest attempt or includes private recovery snapshots.
"""
import argparse
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
PROOF=ROOT/'captures/pdf-page-selection'
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--stage',choices=('local','ota'),required=True)
p.add_argument('--configuration',type=Path,required=True)
p.add_argument('--screenshot-review',type=Path,required=True)
p.add_argument('--prefix',required=True)
a=p.parse_args()
assert a.prefix and all(c.isalnum() or c=='-' for c in a.prefix)
output=PROOF/(a.prefix+'-selection.json');assert not output.exists(),'Preserve earlier selections'
configuration=json.loads(a.configuration.read_text());tablets=configuration['tablets'];assert set(tablets)=={'api30','api36'}
relative=lambda path:str(path.resolve().relative_to(ROOT))
support=set(configuration.get('supportingFiles',[]))
support.add(relative(a.configuration))
for name in ('candidate-1-review-publication-files.json','candidate-2-progress-publication-files.json'):
    support.update(json.loads((PROOF/name).read_text()))
    support.add(relative(PROOF/name))
support.update(relative(path) for path in PROOF.glob('*.py'))
support.update(relative(path) for path in PROOF.glob('checker-optimized-*-source.json'))
support.update(relative(path) for path in PROOF.glob('checker-optimized-attempt-*.py'))
support.update(relative(path) for path in PROOF.glob('input-*-optimized-attempt-*.java'))
support.update(['scripts/check-device.py','scripts/check-device-batches.py','scripts/check-ota-upgrade.py',
    'scripts/check-sync-public-privacy.py','scripts/check-pdf-page-selection.py','scripts/package-pdf-selection-validation.py',
    'scripts/verify-public-artifacts.py','scripts/verify-release-validation.py',
    'docs/releases/0.49.0.md','docs/PDF-PAGE-SELECTION.md','docs/IMPROVEMENT-TRACKER.md','VALIDATION.md'])
for api,row in tablets.items():
    for section in ('core','layouts'):
        path=ROOT/row['optimized'][section];result=json.loads(path.read_text())
        assert result['acceptanceComplete'] and result['restorationComplete'] and result['originalWorkspaceTreeKept'],(api,section)
        support.update(result['checkerDependencies'])
    provenance=json.loads((ROOT/row['nativeProvenance']).read_text())
    assert provenance['nativeRegressionComplete'] and provenance['restorationComplete'] and provenance['originalWorkspaceTreeKept']
    folder=ROOT/row['nativeFolder']
    for name in ('batch-driver.txt','restoration.json'):
        if (folder/name).is_file():support.add(relative(folder/name))
    support.update(relative(path) for path in (folder/'images').glob('after-batch-*/capture-provenance.json'))
    if a.stage=='ota':
        ota_path=ROOT/row['ota'];receipt=json.loads(ota_path.read_text())
        assert receipt['acceptanceComplete'] and receipt['restorationComplete'] and receipt['actualInstallerCompletionConfirmed']
        support.add(relative(ota_path.parent/'public-ota/verification.json'))
        support.add(relative(ota_path.parent/'ota-driver.txt'))
for path in (PROOF/'candidate-2-artifact-checks').iterdir():
    if path.is_file():support.add(relative(path))
for name in support:
    path=ROOT/name
    assert path.is_file() and path.resolve().is_relative_to(ROOT)
    assert not any('PRIVATE' in part or part in ('private-native-state','private-native-screenshots') for part in path.parts),name
review=json.loads(a.screenshot_review.read_text());assert review['actualScreenshotsInspected'] and review['images']
selection=dict(stage=a.stage,supportingFiles=sorted(support),tablets=tablets,screenshotReview=relative(a.screenshot_review),
    privacyReview=relative(PROOF/(a.prefix+'-privacy-review.json')),publicationAllowlist=relative(PROOF/(a.prefix+'-publication-files.json')),
    historicalAttemptsRetained=True,privateSnapshotsExcluded=True)
output.write_text(json.dumps(selection,indent=2)+'\n');print(output)
