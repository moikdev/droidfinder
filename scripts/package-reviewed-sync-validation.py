#!/usr/bin/env python3
"""Validate an explicitly selected 0.46 acceptance set and freeze its public evidence.

Never selects the newest attempt, includes a private snapshot, rebuilds an APK, or
publishes. The selection is reviewed before packaging. Public OTA evidence can be
added after the exact candidate has been released.
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROOF = ROOT / 'captures/reviewed-folder-sync'
RELEASE = ROOT / 'captures/release/0.46.0'
SERIALS = {'api30': 'emulator-5602', 'api36': 'emulator-5582'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text())


def owned(relative):
    path = (ROOT / relative).resolve()
    assert path.is_relative_to(ROOT) and path.is_file(), relative
    assert not any('PRIVATE' in part or part in ('private-native-state', 'private-native-screenshots') for part in path.parts)
    assert path.suffix not in ('.apk', '.tar', '.db', '.sqlite', '.keystore', '.jks', '.bin')
    return path


def success(report, count):
    assert report['success'] and report['runnerCompleted'] and report['runnerSucceeded']
    assert report['passed'] == count and report['failed'] == report['skipped'] == 0
    assert len(report['cases']) == count and all(c['code'] == 0 for c in report['cases'])
    return {(c['class'], c['test']) for c in report['cases']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selection', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--include-ota', action='store_true')
    parser.add_argument('--prepare-file-list', type=Path, help='After local checks, write the exact list for privacy review without creating an archive')
    args = parser.parse_args()
    assert not args.output.exists(), 'Use a new immutable archive for different acceptance bytes'
    chosen = read(args.selection)
    frozen = read(PROOF / 'candidate-build-1.json')
    assert (frozen['version'], frozen['versionCode']) == ('0.46.0-preview', 48)
    assert frozen['jvm'] == dict(tests=851, failures=0, errors=0, skipped=0)
    assert frozen['lint'] == dict(errors=0, warnings=147, hints=12)
    for name, row in frozen['files'].items():
        assert sha(ROOT/name) == row['sha256'], name
    for name, expected in frozen['sourceFiles'].items():
        assert sha(ROOT/name) == expected, name
    expected = sha(RELEASE/'Findroid-0.46.0-preview.apk')
    assert expected == 'd394b2b078da375335057baa349a01c2db58388a6463f3b15f8e0475f848a0d2'
    pair = {name: frozen['files'][name]['sha256'] for name in (
        'app/build/outputs/apk/debug/app-debug.apk',
        'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')}
    files = [args.selection.resolve(), PROOF/'candidate-build-1.json']
    files += [owned(name) for name in frozen['sourceFiles']]
    files += [owned('app/proguard-rules.pro'),owned('.gitattributes')]
    files += [owned(name) for name in frozen['files'] if name.endswith(('.xml','.html')) and ('test-results/' in name or '/reports/' in name)]
    files += [RELEASE/name for name in ('BUILDINFO.json','SHA256SUMS','findroid-update.json')]
    files += [owned(name) for name in chosen['supportingFiles']]
    accepted_checker_hashes = {sha(owned(name)) for name in chosen['supportingFiles'] if Path(name).name.startswith('checker-optimized-') and name.endswith('.py')}
    identities = []
    sections = {}
    for api, serial in SERIALS.items():
        paths = chosen['tablets'][api]
        folder = (ROOT/paths['nativeFolder']).resolve()
        assert folder.is_relative_to(PROOF)
        native = read(folder/'native.json')
        alias = PROOF/(api+'-final-native.json')
        assert alias.read_bytes() == (folder/'native.json').read_bytes()
        files.append(alias)
        assert native['serial'] == serial
        identities.append(success(native, 505))
        assert len(identities[-1]) == 505 and len(native['isolatedBatches']) == 55
        assert native['apkSha256'] == native['installedApkSha256'] == pair
        provenance_path = owned(paths['nativeProvenance'])
        provenance = read(provenance_path)
        assert provenance['sourceCommit'] == frozen['sourceCommit'] and provenance['nativeRegressionComplete']
        assert provenance['immutableOriginalBytesVerifiedBeforeTests'] and provenance['originalWorkspaceTreeKept']
        assert provenance['restorationComplete'] and provenance['nativePassed'] == 505 and provenance['nativeFailed'] == provenance['nativeSkipped'] == 0
        files += [folder/'native.json', folder/'native.txt', folder/'batches/contract.json', provenance_path]
        for batch in native['isolatedBatches']:
            for key, hashkey in (('report','reportSha256'), ('log','logSha256')):
                path = owned(batch[key])
                assert path.is_relative_to(folder/'batches') and sha(path) == batch[hashkey]
                files.append(path)
        # Every stopped batch explicitly selected for disclosure remains in the package.
        for name in paths.get('earlierAttempts', []):
            files.append(owned(name))
        accepted = {}
        for section in ('core', 'interruptions', 'layouts'):
            result_path = owned(paths['optimized'][section])
            result = read(result_path)
            assert result['serial'] == serial and result['section'] in (section, 'all')
            assert result['sourceCommit'] == frozen['sourceCommit'] and result['apkSha256'] == expected
            assert result['acceptanceComplete'] and result['restorationComplete'] and result['originalWorkspaceTreeKept']
            assert result['operationsUseExactOptimizedApk'] and result['immutableOriginalBytesVerifiedBeforeTests']
            assert result['checkerSha256'] in accepted_checker_hashes, ('Missing exact accepted checker source',result_path)
            restore = result['restoration']
            assert all(restore[key] for key in ('originalManagedMetadataDatabasesAndCacheRestored',
                'originalFileAbsencesRestored', 'originalDirectoryAbsencesRestored',
                'originalUriGrantsKept', 'originalStorageModeRestored', 'originalScreenSizeRestored'))
            files.append(result_path)
            accepted[section] = result
        core_names = {r['name'] for r in accepted['core']['checks']}
        assert core_names >= {'direct-public-47-to-48-upgrade', 'default-additions-keep-originals',
            'confirmed-replacement-and-trash', 'cold-record-review-does-not-replay', 'occupied-name-after-review'}
        assert {r['name'] for r in accepted['interruptions']['checks']} >= {
            'real-preparation-interruption', 'real-publication-interruption',
            'real-cancellation-interruption', 'real-access-loss-interruption'}
        for row in accepted['interruptions']['checks']:
            assert row['sourceBytesKept'] and row['coldProcessManualReview'] and row['noAutomaticReplayOrCleanup']
        publication = next(row for row in accepted['interruptions']['checks'] if row['name'] == 'real-publication-interruption')
        assert 0 < publication['completedFiles'] < 160
        layouts = accepted['layouts']['layouts']
        assert len(layouts) == 12 and accepted['layouts']['actualSystemFontEnlargementMeasured']
        assert {(r['widthDp'],r['heightDp'],r['systemFontScale'],r['dark']) for r in layouts} == {
            (w,h,f,d) for w,h in ((1280,800),(800,1280),(600,800)) for f in (1.0,2.0) for d in (False,True)}
        assert all(r['namedControlsCompleteAnd48Dp'] and r['noFileOperationApplied'] for r in layouts)
        assert len([r for r in accepted['layouts']['checks'] if r['name'].startswith('display-density-')]) == 2
        font_path = owned(paths['systemFontReceipt'])
        font = read(font_path)
        assert font['serial'] == serial and font['actualRasterGlyphsLarger'] and len(font['groups']) == 6
        files.append(font_path)
        for group in font['groups']:
            assert len(group['samples']) == 2
            for sample in group['samples']:
                image = owned(str((font_path.parent/sample['image']).relative_to(ROOT)))
                assert sha(image) == sample['sha256']
                files.append(image)
        sections[api] = {section:sha(owned(paths['optimized'][section])) for section in accepted}
    assert identities[0] == identities[1], 'Both APIs must run the same 505 distinct cases'
    review = read(owned(chosen['screenshotReview']))
    assert review['actualScreenshotsInspected'] and review['images']
    for image in review['images']:
        path = owned(image['path'])
        assert path.suffix == '.png' and sha(path) == image['sha256']
        files.append(path)
    files.append(owned(chosen['screenshotReview']))
    for kind in ('preview','release'):
        libraries = read(PROOF/(kind+'-native-libraries.json'))
        assert libraries['allLoadSegmentsAlignedAtLeast16384'] and libraries['zipAlignmentPassed']
        assert len(libraries['libraries']) == 4
    assert read(PROOF/'signing-continuity.json')['sameSigningCertificate']
    if args.include_ota:
        for api, serial in SERIALS.items():
            ota_path = owned(chosen['tablets'][api]['ota'])
            ota = read(ota_path)
            assert ota['serial'] == serial and ota['expectedVersionCode'] == 48
            assert ota['publicFeedUpdateVerified'] and ota['installedApkSha256'] == expected
            assert ota['originalDataRestored'] and ota['originalUriGrantsKept']
            assert ota['acceptanceComplete'] and ota['restorationComplete'] and ota['originalWorkspaceTreeKept']
            files.append(ota_path)
    files = sorted(set(owned(str(path.relative_to(ROOT))) for path in files))
    if args.prepare_file_list:
        assert not args.prepare_file_list.exists(), 'Preserve earlier allowlists'
        args.prepare_file_list.write_text(json.dumps([str(p.relative_to(ROOT)) for p in files],indent=2)+'\n')
        print(json.dumps(dict(explicitPublicationFiles=len(files),archiveCreated=False)))
        return
    privacy = read(owned(chosen['privacyReview']))
    allowed = read(owned(chosen['publicationAllowlist']))
    assert set(allowed) == {str(p.relative_to(ROOT)) for p in files}
    assert privacy['explicitAllowlistChecked'] and privacy['literalIdentifierMatches'] == 0
    assert privacy['publicationAllowlistSha256'] == sha(owned(chosen['publicationAllowlist']))
    evidence_commit = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    for path in files+[owned(chosen['privacyReview']),owned(chosen['publicationAllowlist'])]:
        relative = str(path.relative_to(ROOT))
        committed = subprocess.check_output(['git','show',evidence_commit+':'+relative],cwd=ROOT)
        payload = path.read_bytes()
        normalized = payload.replace(b'\r\n',b'\n') if relative.endswith('.bat') and b'*.bat text eol=crlf' in (ROOT/'.gitattributes').read_bytes().splitlines() else payload
        assert committed == normalized, ('Commit the exact safe evidence bytes before packaging',relative)
    for name in [*frozen['sourceFiles'],'app/proguard-rules.pro','.gitattributes']:
        committed = subprocess.check_output(['git','show',frozen['sourceCommit']+':'+name],cwd=ROOT)
        payload = (ROOT/name).read_bytes()
        normalized = payload.replace(b'\r\n',b'\n') if name.endswith('.bat') and b'*.bat text eol=crlf' in (ROOT/'.gitattributes').read_bytes().splitlines() else payload
        assert committed == normalized, ('Source differs from frozen public commit',name)
    all_files = sorted(set(files+[owned(chosen['privacyReview']),owned(chosen['publicationAllowlist'])]))
    manifest = dict(version='0.46.0-preview', versionCode=48,
        applicationSourceCommit=frozen['sourceCommit'], nativeTestSourceCommit=frozen['sourceCommit'],
        evidenceCommit=evidence_commit, previewApkSha256=expected, jvmCases=851,
        nativeCasesPerApi=505, isolatedBatchesPerApi=55, optimizedSections=sections,
        frozenCandidateManifest=str((PROOF/'candidate-build-1.json').relative_to(ROOT)),
        frozenSourceBindingsComplete=True, checkoutLineEndingsPreserved=True,
        buildTreeStatus='BUILDINFO honestly records generated tracked reports as dirty; every frozen source is bound individually to the public application commit.',
        publicOtaIncluded=args.include_ota,
        files=[dict(path=str(path.relative_to(ROOT)),sha256=sha(path),bytes=path.stat().st_size) for path in all_files])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in all_files:
            archive.write(path, str(path.relative_to(ROOT)))
        archive.writestr('EVIDENCE.json', json.dumps(manifest, indent=2)+'\n')
    args.output.chmod(0o444)
    receipt = args.output.with_suffix('.json')
    assert not receipt.exists()
    receipt.write_text(json.dumps(dict(archive=args.output.name,sha256=sha(args.output),bytes=args.output.stat().st_size,
        files=len(all_files),publicOtaIncluded=args.include_ota,checkerSha256=sha(Path(__file__))),indent=2)+'\n')
    receipt.chmod(0o444)
    print(receipt.read_text())


if __name__ == '__main__':
    main()
