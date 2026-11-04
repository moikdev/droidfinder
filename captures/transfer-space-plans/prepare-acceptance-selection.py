"""Select explicit accepted space-review attempts; never infer acceptance from file age."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROOF = ROOT / 'captures/transfer-space-plans'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--stage', choices=('local', 'ota'), required=True)
a = p.parse_args()
stem = a.stage + '-acceptance-1'
output = PROOF / (stem + '-selection.json')
assert not output.exists(), 'Preserve previous selections'
tablets = {
    'api30': dict(nativeFolder='candidate-2-api30-native', nativeProvenance='candidate-2-api30-native/build-provenance.json',
        core='candidate-2-api30-optimized-core-1', layouts='candidate-2-api30-optimized-layouts-3', earlierAttempts=[
            'candidate-2-api30-optimized-layouts-1/result.json',
            'candidate-2-api30-optimized-layouts-2/result.json',
            'candidate-2-api30-disconnect-recovery-1/verification.json']),
    'api36': dict(nativeFolder='candidate-2-api36-native', nativeProvenance='candidate-2-api36-native-resume-2/build-provenance.json',
        core='candidate-2-api36-optimized-core-2', layouts='candidate-2-api36-optimized-layouts-2', earlierAttempts=[
            'candidate-2-api36-native/build-provenance.json',
            'candidate-2-api36-native/batches/59-ExternalDragWorkflowTest-before-retry-1.json',
            'candidate-2-api36-native/batches/59-ExternalDragWorkflowTest-before-retry-1.txt',
            'candidate-2-api36-disconnect-recovery-1/verification.json',
            'candidate-2-api36-disconnect-recovery-2/verification.json'])}
relative = lambda name: str((PROOF / name).relative_to(ROOT))
support = set(json.loads((PROOF / 'progress-publication-files.json').read_text()))
support.update(str(f.relative_to(ROOT)) for f in PROOF.glob('*.py'))
support.update(str(f.relative_to(ROOT)) for f in PROOF.glob('driver-candidate-2-*.txt'))
support.update(str(f.relative_to(ROOT)) for f in PROOF.glob('checker-optimized-*-source.json'))
support.update(str(f.relative_to(ROOT)) for f in PROOF.glob('ota-helper-*-source.json'))
support.update(str(f.relative_to(ROOT)) for f in PROOF.glob('candidate-2-*-public-ota-*-failure-review.json'))
support.update(str(f.relative_to(ROOT)) for f in PROOF.glob('candidate-2-*-screenshot-review*.json'))
support.update(relative(name) for name in ['candidate-2-native-pair-acceptance.json',
    'candidate-2-api30-layouts-attempt-1-navigation-stop.json', 'progress-privacy-review.json'])
support.update(['scripts/check-device.py', 'scripts/check-device-batches.py', 'scripts/check-ota-upgrade.py',
    'scripts/check-sync-public-privacy.py', 'scripts/check-transfer-space.py', 'scripts/package-transfer-space-validation.py',
    'scripts/verify-public-artifacts.py', 'scripts/verify-release-validation.py',
    'docs/releases/0.48.0.md', 'docs/TRANSFER-SPACE-REVIEW.md', 'docs/IMPROVEMENT-TRACKER.md', 'VALIDATION.md'])
for api, row in tablets.items():
    for section in ('core', 'layouts'):
        result = json.loads((PROOF / row[section] / 'result.json').read_text())
        assert result['acceptanceComplete'] and result['restorationComplete'], (api, section)
        support.update(result['checkerDependencies'])
    for folder in {row['nativeFolder'], str(Path(row['nativeProvenance']).parent)}:
        for name in ('restoration.json', 'batch-driver.txt'):
            if (PROOF / folder / name).is_file(): support.add(relative(folder + '/' + name))
    for capture in (PROOF / row['nativeFolder'] / 'images').glob('after-batch-*/capture-provenance.json'):
        support.add(str(capture.relative_to(ROOT)))
    row['nativeFolder'] = relative(row['nativeFolder'])
    row['nativeProvenance'] = relative(row['nativeProvenance'])
    row['earlierAttempts'] = [relative(name) for name in row['earlierAttempts']]
    row['optimized'] = {section: relative(row.pop(section) + '/result.json') for section in ('core', 'layouts')}
    if a.stage == 'ota':
        ota_folder = 'candidate-2-' + api + '-public-ota-' + ('2' if api == 'api36' else '1')
        row['ota'] = relative(ota_folder + '/verification.json')
        if api == 'api36':
            row['earlierAttempts'] += [relative('candidate-2-api36-public-ota-1/' + name) for name in ('verification.json', 'ota-driver.txt')]
        receipt = json.loads((ROOT / row['ota']).read_text())
        assert receipt['acceptanceComplete'] and receipt['restorationComplete']
        support.add(relative(ota_folder + '/public-ota/verification.json'))
        support.add(relative(ota_folder + '/ota-driver.txt'))
if a.stage == 'ota':
    support.update(relative(name) for name in ('public-five-assets-verification.json', 'candidate-2-public-ota-pair-acceptance.json'))
for name in support:
    assert (ROOT / name).is_file(), name
review = relative(stem + '-screenshot-review.json')
assert (ROOT / review).is_file()
selection = dict(stage=a.stage, supportingFiles=sorted(support), tablets=tablets, screenshotReview=review,
    privacyReview=relative(stem + '-privacy-review.json'), publicationAllowlist=relative(stem + '-publication-files.json'),
    historicalAttemptsRetained=True, privateSnapshotsExcluded=True)
output.write_text(json.dumps(selection, indent=2) + '\n')
print(output)
