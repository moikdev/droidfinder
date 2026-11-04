"""Prepare explicit local/final OCR evidence selections without choosing an attempt implicitly."""
import argparse
import json
from pathlib import Path
import shutil

ROOT=Path(__file__).resolve().parents[2]
PROOF=ROOT/'captures/ocr'
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--phase',choices=('local','ota'),required=True);a=p.parse_args()
target=PROOF/f'candidate-3-{a.phase}-selection.json';assert not target.exists()
support={
 'README.md','PARITY.md','VALIDATION.md','THIRD_PARTY.md','docs/IMPROVEMENT-TRACKER.md','docs/LOCAL-OCR.md','docs/releases/0.50.0.md',
 'scripts/check-local-ocr.py','scripts/package-local-ocr-validation.py','scripts/verify-release-validation.py','scripts/verify-public-artifacts.py',
 'scripts/check-sync-public-privacy.py','scripts/check-device.py','scripts/check-device-batches.py','scripts/check-ota-upgrade.py',
 'scripts/check-reviewed-folder-sync.py','scripts/check-local-search-index.py','scripts/android/acceptance.py','scripts/android/NativeTap.java',
 'captures/persistent-undo/private-native-state.py','captures/persistent-undo/restore-public-tablets.py',
}
proof_names=[
 'candidate-build-2.json','candidate-build-3.json','candidate-2-core-screenshot-review.json','draft-6-focused-screenshot-review.json',
 'candidate-1-dependency-audit.json','candidate-1-dependency-audit.txt','candidate-1-optimized-build.txt','candidate-2-package.txt',
 'check-build-artifacts.py','freeze-candidate.py','record-draft-build.py','prepare-selection.py',
 'candidate-2-native-controller.py','candidate-2-native-classes.txt','candidate-3-native-resume.py','candidate-3-tablet-native-classes.txt',
 'focused-controller-through-draft-6.py','preview-regression-controller-attempt-1.py','focused-native-reset.py',
 'run-focused-native.py','run-public-ota.py','license-review.json','license-review-2.json',
 'checker-optimized-core-1.py','checker-optimized-layouts-1.py','checker-optimized-layouts-2.py','checker-optimized-layouts-3.py','checker-optimized-layouts-4.py',
 'draft-build-4.json','draft-build-5.json','draft-build-5-gradle.txt','draft-build-6.json','draft-build-6-gradle.txt','draft-build-7.json','draft-build-7-gradle.txt',
 'text-visibility-attempt-1-review.json',
]
support|={'captures/ocr/'+name for name in proof_names}
support|={str(f.relative_to(ROOT)) for f in (PROOF/'candidate-2-artifact-checks').iterdir() if f.is_file() and f.suffix in ('.json','.txt')}
# Retain the stopped readiness, off-screen fixture and phone-only selector receipts.
for folder in ('draft-3-api36-native','draft-4-api36-native','draft-5-api36-native','draft-6-api30-native','draft-6-api36-native',
               'draft-6-api30-preview-regressions','draft-6-api36-preview-regressions','candidate-2-api30-native','candidate-2-api36-native'):
 base=PROOF/folder
 for filename in ('build-provenance.json','batch-driver.txt'):
  if (base/filename).is_file():support.add(str((base/filename).relative_to(ROOT)))
 support|={str(f.relative_to(ROOT)) for f in (base/'batches').glob('*') if f.is_file() and f.suffix in ('.json','.txt') and 'PRIVATE' not in f.name}
for api in ('api30','api36'):
 for attempt in (1,2):
  support.add(f'captures/ocr/candidate-3-{api}-optimized-layouts-{attempt}/result.json')
tables={}
for api in ('api30','api36'):
 folder=PROOF/f'candidate-3-{api}-native'
 tables[api]=dict(nativeFolder=str(folder.relative_to(ROOT)),
  nativeImages=[str(f.relative_to(ROOT)) for f in sorted((folder/'images').rglob('*.png')) if 'PRIVATE' not in str(f.relative_to(folder))],
  optimized=dict(core=f'captures/ocr/candidate-2-{api}-optimized-core-1/result.json',layouts=f'captures/ocr/candidate-3-{api}-optimized-layouts-3/result.json'),
  ota=f'captures/ocr/candidate-3-{api}-public-ota-1/verification.json')
selection=dict(frozenCandidateManifest='captures/ocr/candidate-build-3.json',supportingFiles=sorted(support),tablets=tables,
 optimizedCheckers=['captures/ocr/checker-optimized-core-1.py','captures/ocr/checker-optimized-layouts-3.py','captures/ocr/checker-optimized-layouts-4.py'],
 screenshotReview=f'captures/ocr/candidate-3-{a.phase}-screenshot-review.json',privacyReview=f'captures/ocr/candidate-3-{a.phase}-privacy-review.json',
 publicationAllowlist=f'captures/ocr/candidate-3-{a.phase}-publication-files.json',historicalAttemptsKept=True,privateSnapshotsExcluded=True)
assert all((ROOT/name).is_file() for name in support),[name for name in support if not (ROOT/name).is_file()]
target.write_text(json.dumps(selection,indent=2)+'\n');print(target.relative_to(ROOT))
