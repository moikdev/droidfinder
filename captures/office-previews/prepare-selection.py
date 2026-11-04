"""Select explicit Office evidence, retaining superseded and stopped attempts."""
import argparse,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];proof=ROOT/'captures/office-previews'
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--phase',choices=('local','ota'),required=True);a=p.parse_args()
target=proof/f'candidate-3-{a.phase}-selection.json';assert not target.exists()
support={'README.md','PARITY.md','VALIDATION.md','THIRD_PARTY.md','docs/IMPROVEMENT-TRACKER.md','docs/OFFICE-PREVIEWS.md','docs/releases/0.51.0.md',
 'scripts/check-office-previews.py','scripts/package-office-preview-validation.py','scripts/verify-release-validation.py','scripts/verify-public-artifacts.py',
 'scripts/check-sync-public-privacy.py','scripts/check-device.py','scripts/check-device-batches.py','scripts/check-ota-upgrade.py','scripts/audit-dependencies.py',
 'scripts/check-reviewed-folder-sync.py','scripts/check-local-search-index.py','scripts/android/acceptance.py','scripts/android/NativeTap.java',
 'captures/persistent-undo/private-native-state.py','captures/persistent-undo/restore-public-tablets.py'}
for name in ('candidate-build-1.json','candidate-build-2.json','candidate-build-3.json','freeze-candidate-3.py','check-build-artifacts-3.py','freeze-candidate.py','freeze-candidate-2.py','check-build-artifacts.py',
 'record-draft-build.py','run-focused-native.py','focused-native-reset.py','focused-native-classes.txt','run-public-ota.py','prepare-selection.py',
 'build-fixtures.py','candidate-3-api30-core-screenshot-review.json','candidate-3-api36-core-screenshot-review.json','candidate-3-api30-layout-screenshot-review.json','candidate-3-api36-layout-screenshot-review.json','build-layout-review.py','candidate-3-native-screenshot-review.json','candidate-2-native-screenshot-review.json','candidate-2-core-screenshot-review.json','updater-description.txt','checker-optimized-1.py','checker-optimized-2.py','checker-optimized-3.py','layout-fixture-stop-review.json','candidate-1-optimized-build.txt','candidate-1-package.txt',
 'candidate-2-optimized-build.txt','candidate-2-package.txt','candidate-3-package.txt','compact-body-stop-review.json','checker-optimized-4.py','checker-optimized-5.py','candidate-2-dependency-audit.json','candidate-2-dependency-audit.txt',
 'draft-build-environment-stops.json','draft-build-4-jvm-failure.xml','optimized-inputs-before-text-test-fix.json','optimized-inputs-after-text-test-fix.json',
 'premerge-publication-files.json','premerge-privacy-review.json'):
 support.add('captures/office-previews/'+name)
for attempt in (3,4,5,6,7,8,9,10):
 for suffix in ('.json','-gradle.txt'):
  path=proof/f'draft-build-{attempt}{suffix}'
  if path.is_file():support.add(str(path.relative_to(ROOT)))
for path in list((proof/'candidate-2-artifact-checks').iterdir())+list((proof/'candidate-3-artifact-checks').iterdir()):
 if path.is_file() and path.suffix in ('.txt','.json'):support.add(str(path.relative_to(ROOT)))
for api in ('api30','api36'):
 for base in [proof/f'draft-{attempt}-{api}-native' for attempt in (5,6,7)]+[proof/f'candidate-2-{api}-native']:
  for name in ('build-provenance.json','batch-driver.txt'):
   if (base/name).is_file():support.add(str((base/name).relative_to(ROOT)))
  support|={str(path.relative_to(ROOT)) for path in (base/'batches').glob('*') if path.is_file() and path.suffix in ('.txt','.json') and 'PRIVATE' not in path.name}
 support.add(f'captures/office-previews/candidate-1-{api}-optimized-core-1/result.json')
 support.add(f'captures/office-previews/candidate-2-{api}-optimized-layouts-1/result.json')
 support.add(f'captures/office-previews/candidate-2-{api}-optimized-core-1/result.json')
 if api=='api30':support.add('captures/office-previews/candidate-2-api30-optimized-layouts-2/result.json')
if a.phase=='ota':
 for name in ('candidate-3-local-selection.json','candidate-3-local-publication-files.json','candidate-3-local-privacy-review.json','candidate-3-local-screenshot-review.json','candidate-3-public-artifacts-initial.json'):
  support.add('captures/office-previews/'+name)
tables={}
for api in ('api30','api36'):
 folder=proof/f'candidate-3-{api}-native'
 tables[api]=dict(nativeFolder=str(folder.relative_to(ROOT)),nativeImages=[str(path.relative_to(ROOT)) for path in sorted((folder/'images').rglob('*.png')) if 'PRIVATE' not in str(path)],
 optimized=dict(core=f'captures/office-previews/candidate-3-{api}-optimized-core-1/result.json',layouts=f'captures/office-previews/candidate-3-{api}-optimized-layouts-1/result.json'),
 ota=f'captures/office-previews/candidate-3-{api}-public-ota-1/verification.json')
assert all((ROOT/name).is_file() for name in support),[name for name in support if not (ROOT/name).is_file()]
selection=dict(frozenCandidateManifest='captures/office-previews/candidate-build-3.json',supportingFiles=sorted(support),tablets=tables,
 optimizedCheckers=['captures/office-previews/checker-optimized-5.py'],
 screenshotReview=f'captures/office-previews/candidate-3-{a.phase}-screenshot-review.json',privacyReview=f'captures/office-previews/candidate-3-{a.phase}-privacy-review.json',
 publicationAllowlist=f'captures/office-previews/candidate-3-{a.phase}-publication-files.json',historicalAttemptsKept=True,privateSnapshotsExcluded=True)
target.write_text(json.dumps(selection,indent=2)+'\n');print(target.relative_to(ROOT))
