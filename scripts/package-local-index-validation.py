#!/usr/bin/env python3
"""Validate and freeze the 0.40.0 local-index evidence from explicit public allowlists."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROOF = ROOT / 'captures/local-search-index'
RELEASE = ROOT / 'captures/release/0.40.0'
REQUIRED = (
    'success', 'directPreviousPublicUpgrade', 'sourceChoiceNeverOptsIn', 'actualFolderPicker',
    'chosenRootsAndExclusions', 'snapshotDatesVisible', 'currentMetadataRevalidation',
    'missingCandidatesNotActionable', 'newPathsRequireRefresh', 'manualRefreshFindsNewPaths',
    'indexedSmartFolderSurvivesRestart', 'customWindowTaskReplacement', 'independentPersistedSources',
    'actualProcessDeathDuringRefresh', 'previousGenerationRetainedAfterProcessDeath',
    'actualPauseAndRestart', 'previousGenerationRetainedAfterPause',
    'removeDuringRefreshRejectsLateWorker', 'actualCacheDeletionKeepsDocuments',
    'canceledCacheDeletionKeepsSnapshots', 'lateWorkerDoesNotRecreateDeletedCache',
    'originalSyntheticDocumentBytes', 'restoredOriginalSetupAndCache',
    'syntheticFixtureRemoved', 'restoredOriginalStorageAccess',
)
DOCS = (
    'README.md', 'VALIDATION.md', 'PARITY.md', 'docs/LOCAL-SEARCH-INDEX.md',
    'docs/SEARCH-RULE-GROUPS.md', 'docs/SEARCH-LOCATIONS.md', 'docs/RECENT-SEARCHES.md',
    'docs/SEARCH-CRITERIA.md', 'docs/SETUP-BACKUP.md', 'docs/IMPROVEMENT-TRACKER.md',
    'docs/NEXT-IMPROVEMENTS.md', 'docs/APP-UPDATES.md',
    'docs/releases/0.40.0.md', 'docs/releases/0.40.0-update.txt',
)
SCRIPTS = (
    'scripts/check-device.py', 'scripts/check-device-batches.py', 'scripts/check-local-search-index.py',
    'scripts/check-ota-upgrade.py', 'scripts/package-preview.py', 'scripts/package-local-index-validation.py',
    'scripts/android/acceptance.py', 'scripts/android/NativeTap.java',
    'captures/workspace-recovery/native-environment.py',
)
IMAGES = ('local-search-index-tablet', 'local-search-index-results', 'local-search-index-large-text', 'app-updates-0400')


def read(path):
    return json.loads(path.read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def accepted(report):
    assert report['success'] and report['failed'] == report['skipped'] == 0
    assert report['runnerCompleted'] and report['runnerSucceeded']


def bounds(node):
    return list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))


def full_control(tree, title, label, footer, width, height, minimum):
    parents = {child: parent for parent in tree.iter() for child in parent}
    def clickable_ancestor(node):
        while node in parents:
            node = parents[node]
            if node.get('clickable') == 'true':
                return True
        return False
    heads = [node for node in tree.iter('node') if node.get('text') == title
             and len(bounds(node)) == 4 and bounds(node)[3] > bounds(node)[1] and not clickable_ancestor(node)]
    assert len(heads) == 1, (title, len(heads))
    scope = heads[0]
    while not any(n.get('text') == footer or n.get('content-desc') == footer for n in scope.iter('node')):
        scope = parents[scope]
    for node in scope.iter('node'):
        if node.get('text') != label and node.get('content-desc') != label:
            continue
        while node.get('clickable') != 'true' and node in parents:
            node = parents[node]
        box = bounds(node)
        if len(box) != 4:
            continue
        l, t, r, b = box
        if node.get('clickable') != 'true' or node.get('enabled') != 'true' or not (
            0 <= l < r <= width and 0 <= t < b <= height and r-l >= minimum and b-t >= minimum
        ):
            continue
        parent, clipped = node, False
        while parent in parents:
            parent = parents[parent]
            if parent.get('scrollable') == 'true':
                pl, pt, pr, pb = bounds(parent)
                clipped |= not (pl <= l < r <= pr and pt <= t < b <= pb)
        if not clipped:
            return True
    return False


def reviewed_images(folder, receipt='visual-review.json'):
    review = read(folder / receipt)
    assert review['actualScreenshotsInspected'] and review['images']
    files = [folder / receipt]
    for image in review['images']:
        name = image['name']
        assert Path(name).name == name and Path(name).suffix == '.png'
        path = folder / name
        assert sha(path) == image['sha256'], path
        files.append(path)
    return files


def validate_local(expected):
    files = []
    tests = list((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    totals = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for path in tests:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
    assert totals == dict(tests=489, failures=0, errors=0, skipped=0), totals
    lint = ROOT / 'app/build/reports/lint-results-debug.xml'
    assert not any(n.get('severity') in ('Error', 'Fatal') for n in ET.parse(lint).getroot())
    files += tests + [lint]
    source = read(PROOF / 'native-source-hashes.json')
    for name, digest in source.items():
        assert sha(ROOT / name) == digest, name
    identities = []
    for api, serial in ((30, 'emulator-5602'), (36, 'emulator-5582')):
        report = read(PROOF / f'api{api}-final-native.json')
        accepted(report)
        assert report['serial'] == serial and report['passed'] == 353 and len(report['isolatedBatches']) == 38
        assert report['apkSha256'] == report['installedApkSha256'] == {k: v for k, v in source.items() if k.endswith('.apk')}
        cases = {(row['class'], row['test']) for row in report['cases']}
        assert len(cases) == 353
        identities.append(cases)
        for row in report['isolatedBatches']:
            for path_key, hash_key in (('report', 'reportSha256'), ('log', 'logSha256')):
                path = ROOT / row[path_key]
                assert path.is_relative_to(PROOF / f'api{api}-final-batches') and sha(path) == row[hash_key]
                files.append(path)
        files += [PROOF / f'api{api}-final-native.json', PROOF / f'api{api}-final-native.txt',
                  PROOF / f'api{api}-final-batches/contract.json']
        benchmark = read(PROOF / f'api{api}/benchmark.json')
        assert benchmark['api'] == api and benchmark['fixtureFiles'] == 6000 and benchmark['fixtureFolders'] == 200
        assert benchmark['live']['results'] == benchmark['indexed']['results'] == 1000
        assert benchmark['previousSnapshotRetained'] and 0 <= benchmark['pauseLatencyMs'] < 1000
        assert benchmark['snapshotBuildMs'] > 0 and benchmark['cacheBytesAfterBuild'] > 0
        capture_receipt = read(PROOF / f'api{api}/native-capture-hashes.json')
        assert capture_receipt['nativeApkSha256'] == report['apkSha256'] and capture_receipt['api'] == api
        for name, digest in capture_receipt['files'].items():
            assert (ROOT / name).is_relative_to(PROOF / f'api{api}') and sha(ROOT / name) == digest
        files += [PROOF / f'api{api}/benchmark.json', PROOF / f'api{api}/native-capture-hashes.json',
                  PROOF / f'api{api}/screenshots/contact-sources.json']
        files += reviewed_images(PROOF / f'api{api}/screenshots')
        folder = PROOF / f'api{api}-optimized'
        opt = read(folder / 'verification.json')
        assert opt['apkSha256'] == opt['restoredApkSha256'] == expected and all(opt[k] for k in REQUIRED)
        checker = read(PROOF / 'accepted-checkers.json')[str(api)]
        checker_path = ROOT / checker['path']
        assert checker_path == ROOT / 'scripts/check-local-search-index.py'
        assert opt['checkerSha256'] == checker['sha256'] == sha(checker_path)
        files.append(checker_path)
        assert (opt['fromVersionCode'], opt['toVersionCode']) == (41, 42)
        assert opt['previousApkSha256'] == sha(ROOT / 'captures/nested-search-rules/public/Findroid-0.39.0-preview.apk')
        assert opt['cleanupErrors'] == [] and opt['originalStorageAccessMode'] in {'allow', 'ignore', 'deny', 'default', 'foreground'}
        assert checker['fixturePreparationDeadlineSeconds'] == 1800
        assert opt['fixturePreparationDeadlineSeconds'] == checker['fixturePreparationDeadlineSeconds']
        assert 0 < opt['fixturePreparationSeconds'] <= checker['fixturePreparationDeadlineSeconds']
        assert opt['customWindowIterations'] == 5 and opt['processDeathFixtureFiles'] == 50_000
        assert {(r['widthDp'], float(r['fontScale'])) for r in opt['layouts']} == {(w, s) for w in (600, 800, 1280) for s in (1.0, 1.6)}
        for row in opt['layouts']:
            assert all(row[k] for k in ('fullTouchBounds', 'managementAndPickerReachable', 'draftReturned'))
            key = f'{row["widthDp"]}dp-font{row["fontScale"]}'
            for suffix, title, footer, labels in (
                ('criteria', 'Search criteria', 'Apply filters', ('Cancel', 'Clear', 'Apply filters', 'Close Search criteria')),
                ('manager', 'Search index', 'Done', ('Add local folder…', 'Check folders', 'Delete index cache…', 'Done', 'Close Search index')),
                ('picker', 'Index this folder', 'Cancel', ('Cancel', 'Index this folder', 'Close Index this folder')),
                ('details', 'Result details', 'Close Result details', ('Close Result details',)),
            ):
                image = folder / (key + '-' + suffix + '.png')
                width, height = struct.unpack('>II', image.read_bytes()[16:24])
                tree = ET.parse(folder / (key + '-' + suffix + '-capture.xml')).getroot()
                for label in labels:
                    assert full_control(tree, title, label, footer, width, height, 48 * width / row['widthDp']), (api, key, title, label)
        smart = read(folder / 'synthetic-smart-folders.json')
        assert len(smart) == 1 and smart[0]['rules']['source'] == 'LOCAL_INDEX' and len(smart[0]['rules']['locations']) == 2
        roots = read(folder / 'synthetic-catalog.json')
        paused = read(folder / 'synthetic-paused-catalog.json')
        assert len(roots) == len(paused) == 2 and all(r['state'] == 'READY' and r['generation'] for r in roots)
        retained = next(r for r in paused if r['state'] == 'PAUSED')
        assert retained['generation'] == next(r['generation'] for r in roots if r['path'] == retained['path'])
        files += reviewed_images(folder)
        files += [folder / name for name in ('verification.json', 'inspection-retries.json', 'synthetic-catalog.json',
                  'synthetic-tabs.json', 'synthetic-smart-folders.json', 'synthetic-paused-catalog.json', 'contact-sources.json')]
        files += list(folder.glob('*.xml'))
    assert identities[0] == identities[1]
    writer = read(PROOF / 'fixture-writer-check.json')
    assert writer['serial'] == 'emulator-5602' and writer['installedPreviewSha256'] == expected
    assert all(writer[k] for k in ('realBlockedRemoteTarStopped', 'hostAdbExited', 'syntheticFolderRemoved', 'applicationDataUntouched'))
    assert writer['checkerSha256'] == sha(ROOT / 'scripts/check-local-search-index.py')
    io = read(PROOF / 'checker-io-verification.json')
    assert {r['serial'] for r in io} == {'emulator-5602', 'emulator-5582'}
    for row in io:
        assert row['checkerSha256'] == sha(ROOT / 'scripts/check-local-search-index.py')
        assert row['otaCheckerSha256'] == sha(ROOT / 'scripts/check-ota-upgrade.py')
        assert row['installedPreviewSha256'] == expected
        assert all(row[k] for k in ('actualReaderFunctionsExecuted', 'missingFileIsNone', 'binaryBytesPreserved',
            'unreadableFileIsErrorNotAbsence', 'originalMetadataBytesAndAbsencesUnchanged'))
    restored = read(PROOF / 'original-restoration-audit.json')
    assert {r['serial'] for r in restored} == {'emulator-5602', 'emulator-5582'}
    for row in restored:
        assert row['installedPreviewSha256'] == expected
        assert all(row[k] for k in ('originalBackupsUnchanged', 'allOriginalMetadataBytesAndKnownAbsencesVerified',
            'syntheticIndexCacheRemoved', 'originalDocumentTreesUntouched', 'uriGrantsUntouched'))
    files += [PROOF / name for name in ('fixture-writer-check.json', 'checker-io-verification.json',
              'original-restoration-audit.json', 'verify-checker-io.py', 'audit-original-restoration.py')]
    files += [PROOF / 'native-source-hashes.json', PROOF / 'native-classes.txt',
              PROOF / 'accepted-checkers.json', PROOF / 'checker-readiness-diff.txt']
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--check-local', action='store_true')
    args = parser.parse_args()
    if args.check_local:
        preview = ROOT / 'app/build/outputs/apk/preview/app-preview.apk'
        files = validate_local(sha(preview))
        report = dict(previewApkSha256=sha(preview), jvmCases=489, nativeCasesPerApi=353, batchesPerApi=38,
                      allLocalGatesPassed=True, validatedFiles=len(set(files)))
        (PROOF / 'local-acceptance.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
        return
    assert args.output and not args.output.exists(), 'Choose a new immutable archive path.'
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip(), 'Commit accepted source and docs first.'
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    info = read(RELEASE / 'BUILDINFO.json')
    assert info['version'] == '0.40.0-preview' and int(info['version_code']) == 42 and not info['working_tree_dirty']
    for item in info['artifacts']:
        assert sha(RELEASE / item['name']) == item['sha256']
    expected = info['artifacts'][0]['sha256']
    for path in (ROOT / 'app/src/main').rglob('*'):
        if path.is_file():
            assert subprocess.check_output(['git', 'show', info['source_commit'] + ':' + str(path.relative_to(ROOT))], cwd=ROOT) == path.read_bytes()
    assert subprocess.check_output(['git', 'show', info['source_commit'] + ':app/build.gradle.kts'], cwd=ROOT) == (ROOT / 'app/build.gradle.kts').read_bytes()
    files = validate_local(expected) + [ROOT / name for name in DOCS + SCRIPTS]
    files += [ROOT / 'docs/screenshots' / (name + '.png') for name in IMAGES]
    files += list((ROOT / 'app/src').rglob('*.kt')) + list((ROOT / 'app/src/main').rglob('*.xml'))
    files += [ROOT / name for name in ('app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties')]
    files += [RELEASE / name for name in ('BUILDINFO.json', 'SHA256SUMS', 'findroid-update.json')]
    for api in (30, 36):
        folder = PROOF / f'api{api}-ota'
        ota = read(folder / 'verification.json')
        assert (ota['fromVersionCode'], ota['toVersionCode']) == (41, 42) and ota['restoredApkSha256'] == expected
        assert all(ota[k] for k in ('publicFeedDownloadAndAndroidInstall', 'installedApkMatchesPublicBytes',
            'originalFileBytes', 'legacyFavoritePreserved', 'sentinelPreferencePreserved', 'updatedSidebarVisible', 'noRepeatOffer', 'restoredOriginalSetup'))
        files += [folder / 'verification.json'] + list(folder.glob('*.png')) + list(folder.glob('*.xml'))
    public = read(PROOF / 'public-downloads.json')
    assert public['public'] and public['sourceCommit'] == info['source_commit'] and len(public['assets']) == 5
    assert all(r['publicDownloadMatchesLocal'] and r['githubDigestMatches'] for r in public['assets'])
    live = read(PROOF / 'api36-live-feed.json')
    accepted(live)
    assert live['passed'] == 1 and live['downloadedVersionCode'] == 42 and live['downloadedApkSha256'] == expected
    restored = read(PROOF / 'restored-public-tablets.json')
    assert len(restored) == 2 and all(r['privateOriginalMetadataAndPreferencesRestored'] and r['installedPublicApkSha256'] == expected and float(r['fontScale']) == 1 for r in restored)
    assert read(PROOF / 'signing-continuity.json')['sameSigningCertificate']
    for name, digest in read(PROOF / 'safety-review.json')['reviewedSources'].items():
        assert sha(ROOT / name) == digest, name
    rejected = read(PROOF / 'rejected-attempts.json')
    assert rejected['rejectedAttemptsExcludedFromAcceptance'] and all(r['countedTowardFinalAcceptance'] is False for r in rejected['attempts'])
    assert sha(ROOT / 'docs/screenshots/app-updates-0400.png') == sha(PROOF / 'api30-ota/up-to-date.png')
    ota_review = read(PROOF / 'ota-visual-review.json')
    assert ota_review['actualScreenshotInspected'] and ota_review['sha256'] == sha(PROOF / 'api30-ota/up-to-date.png')
    for name in ('root-identity-final-build.txt', 'cursor-test-build.txt', 'public-downloads.json', 'api36-live-feed.json',
                 'api36-live-feed.txt', 'restored-public-tablets.json', 'signing-continuity.json', 'rejected-attempts.json',
                 'local-acceptance.json', 'ota-visual-review.json', 'safety-review.json', 'release-package.txt',
                 'verify-public.py', 'verify-validation.py', 'run-live-feed.py', 'restore-public-tablets.py', 'prepare-visual-review.py', 'write-release-body.py', 'finalize-docs.py'):
        files.append(PROOF / name)
    files = sorted(set(files))
    for path in files:
        assert path.is_file() and path.resolve().is_relative_to(ROOT), path
        assert path.suffix in ('.py', '.kt', '.kts', '.java', '.md', '.txt', '.json', '.xml', '.png') or path.name == 'SHA256SUMS' or path.name == 'gradle.properties', path
        assert 'native-original' not in path.parts and 'bugreport' not in path.name and 'accepted-build' not in path.parts, path
    native_commit = subprocess.check_output(['git', 'log', '-1', '--format=%H', '--', 'app/src/androidTest'], cwd=ROOT, text=True).strip()
    manifest = dict(applicationSourceCommit=info['source_commit'], nativeTestSourceCommit=native_commit,
                    evidenceCommit=commit, previewApkSha256=expected, jvmCases=489, nativeCasesPerApi=353,
                    isolatedBatchesPerApi=38, files=[dict(path=str(p.relative_to(ROOT)), bytes=p.stat().st_size, sha256=sha(p)) for p in files])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'x', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('EVIDENCE.json', json.dumps(manifest, indent=2) + '\n')
        for path in files:
            archive.write(path, path.relative_to(ROOT))
    report = dict(archive=args.output.name, bytes=args.output.stat().st_size, sha256=sha(args.output), entries=len(files)+1,
                  **{k: manifest[k] for k in ('applicationSourceCommit', 'nativeTestSourceCommit', 'evidenceCommit', 'jvmCases', 'nativeCasesPerApi', 'isolatedBatchesPerApi')})
    (PROOF / 'validation-package.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
