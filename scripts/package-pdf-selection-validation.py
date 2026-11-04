#!/usr/bin/env python3
"""Validate an explicitly selected PDF-selection acceptance set and freeze its public evidence.

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
PROOF = ROOT / 'captures/pdf-page-selection'
RELEASE = ROOT / 'captures/release/0.49.0'
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
    frozen = read(PROOF / 'candidate-build-2.json')
    assert (frozen['version'], frozen['versionCode']) == ('0.49.0-preview', 51)
    assert frozen['jvm'] == dict(tests=948, failures=0, errors=0, skipped=0)
    assert frozen['lint'] == dict(errors=0, warnings=148, hints=12)
    for name, row in frozen['files'].items():
        assert sha(ROOT/name) == row['sha256'], name
    for name, expected in frozen['sourceFiles'].items():
        assert sha(ROOT/name) == expected, name
    expected = sha(RELEASE/'Findroid-0.49.0-preview.apk')
    assert expected == frozen['files']['captures/release/0.49.0/Findroid-0.49.0-preview.apk']['sha256']
    pair = {name: frozen['files'][name]['sha256'] for name in (
        'app/build/outputs/apk/debug/app-debug.apk',
        'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')}
    files = [args.selection.resolve(), PROOF/'candidate-build-2.json']
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
        identities.append(success(native, 563))
        assert len(identities[-1]) == 563 and len(native['isolatedBatches']) == 61
        assert native['apkSha256'] == native['installedApkSha256'] == pair
        provenance_path = owned(paths['nativeProvenance'])
        provenance = read(provenance_path)
        assert provenance['sourceCommit'] == frozen['sourceCommit'] and provenance['nativeRegressionComplete']
        assert provenance['frozenReleaseCandidate'] and not provenance['draftOnly']
        assert provenance['immutableOriginalBytesVerifiedBeforeTests'] and provenance['originalWorkspaceTreeKept']
        assert provenance['restorationComplete'] and provenance['nativePassed'] == 563 and provenance['nativeFailed'] == provenance['nativeSkipped'] == 0
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
        for section in ('core', 'layouts'):
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
        assert core_names >= {'direct-public50-to51-word-and-range',
            'zoom-and-pan-retain-selection', 'first-long-press-and-page-clear',
            'keyboard-and-preview-scope'}
        facts = {row['name']:row for row in accepted['core']['checks']}
        assert facts['direct-public50-to51-word-and-range']['wordClipboardExact']
        assert facts['direct-public50-to51-word-and-range']['twoLineClipboardExact']
        assert facts['zoom-and-pan-retain-selection']['realPageDrag']
        assert facts['zoom-and-pan-retain-selection']['clipboardStayedExact']
        assert facts['first-long-press-and-page-clear']['noTextLayerExplained']
        assert facts['first-long-press-and-page-clear']['emptyPageCopyDisabled']
        assert facts['keyboard-and-preview-scope']['keyboardSelectAllPassageAndLineBreaksVerified']
        assert facts['keyboard-and-preview-scope']['originalPdfBytesKept']
        assert len(accepted['core']['clipboardProofs']) >= 6
        layouts = accepted['layouts']['layouts']
        assert len(layouts) == 12
        assert {(r['widthDp'],r['heightDp'],r['fontScale'],r['dark']) for r in layouts} == {
            (w,h,f,d) for w,h in ((1280,800),(800,1280),(600,800)) for f in (1.0,2.0) for d in (False,True)}
        assert all(r['allNamedControlsHaveComplete48DpBounds'] and r['wordAndRetainedPanClipboardExact'] and r['originalPdfBytesKept'] for r in layouts)
        assert len(accepted['layouts']['systemFontGroups']) == 6
        assert len(accepted['layouts']['densityChecks']) == 2
        assert {r['actualDpi'] for r in accepted['layouts']['densityChecks']} == {240,300}
        assert len(accepted['layouts']['selectionHandleProofs']) >= 28
        assert all(r['complete48DpTarget'] for r in accepted['layouts']['selectionHandleProofs'])
        for result in accepted.values():
            assert result['clipboardProofs']
            for proof in result['clipboardProofs']:
                actual=proof['actualSyntheticText']
                assert proof['readByForegroundTestOnlyActivity'] and proof['operationsUsedOptimizedApp']
                assert (actual.rstrip('\n') if proof['pageEndLineBreaksAllowed'] else actual)==proof['expectedText']
                assert hashlib.sha256(actual.encode()).hexdigest()==proof['actualTextSha256']
        for group in accepted['layouts']['systemFontGroups']:
            assert group['actualTextAndGlyphGrowth'] and len(group['samples']) == 2
            normal,large=group['samples']
            assert normal['systemFontScale']==1.0 and large['systemFontScale']==2.0
            for key in ('textWidth','textHeight','rasterGlyphWidth','rasterGlyphHeight'):assert large[key]>normal[key]
            for sample in group['samples']:
                path=owned(str((owned(paths['optimized']['layouts']).parent/sample['image']).relative_to(ROOT)))
                assert sha(path)==sample['sha256'];files.append(path)
        for result_path in [owned(paths['optimized'][section]) for section in accepted]:
            result=read(result_path)
            for capture in result['captures']:
                path=owned(str((result_path.parent/capture['path']).relative_to(ROOT)))
                assert path.name.endswith('-surface.png') and capture['browserBackdropExcluded'] and sha(path)==capture['sha256']
                files.append(path)
            for name,digest in result['checkerDependencies'].items():
                path=owned(name);assert path in files and sha(path)==digest,('Missing exact checker dependency',name)
        sections[api] = {section:sha(owned(paths['optimized'][section])) for section in accepted}
    assert identities[0] == identities[1], 'Both APIs must run the same 563 distinct cases'
    review = read(owned(chosen['screenshotReview']))
    assert review['actualScreenshotsInspected'] and review['images']
    for image in review['images']:
        path = owned(image['path'])
        assert path.suffix == '.png' and sha(path) == image['sha256']
        files.append(path)
    files.append(owned(chosen['screenshotReview']))
    for kind in ('preview','release'):
        libraries = read(PROOF/'candidate-2-artifact-checks'/(kind+'-native-libraries.json'))
        assert libraries['allLoadSegmentsAlignedAtLeast16384'] and libraries['zipAlignmentPassed']
        assert len(libraries['libraries']) == 4
    assert read(PROOF/'candidate-2-artifact-checks/signing-continuity.json')['sameSigningCertificate']
    if args.include_ota:
        for api, serial in SERIALS.items():
            ota_path = owned(chosen['tablets'][api]['ota'])
            ota = read(ota_path)
            assert ota['serial'] == serial and ota['expectedVersionCode'] == 51
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
    manifest = dict(version='0.49.0-preview', versionCode=51,
        applicationSourceCommit=frozen['sourceCommit'], nativeTestSourceCommit=frozen['sourceCommit'],
        evidenceCommit=evidence_commit, previewApkSha256=expected, jvmCases=948,
        nativeCasesPerApi=563, isolatedBatchesPerApi=61, optimizedSections=sections,
        frozenCandidateManifest=str((PROOF/'candidate-build-2.json').relative_to(ROOT)),
        frozenSourceBindingsComplete=True, checkoutLineEndingsPreserved=True,
        buildTreeStatus='BUILDINFO honestly records generated reports and acceptance tooling in a dirty checkout; every frozen build source is bound individually to the public application commit.',
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
