#!/usr/bin/env python3
"""Check an explicit OCR acceptance selection and freeze its public evidence archive.

Private device snapshots and raw window captures are never selected. The archive
is created only after the selected files have been committed and privacy reviewed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]
PROOF=ROOT/'captures/ocr'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
read=lambda p:json.loads(p.read_text())


def owned(name):
    path=(ROOT/name).resolve()
    assert path.is_relative_to(ROOT) and path.is_file(),name
    assert not any('PRIVATE' in part or part in ('private-native-state','private-native-screenshots','native-original') for part in path.parts)
    assert path.suffix not in ('.apk','.tar','.db','.sqlite','.jks','.keystore','.bin')
    return path


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--selection',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--include-ota',action='store_true')
    p.add_argument('--prepare-file-list',type=Path)
    a=p.parse_args()
    chosen=read(a.selection); frozen_path=owned(chosen['frozenCandidateManifest']);frozen=read(frozen_path)
    assert frozen['jvm']==dict(tests=964,failures=0,errors=0,skipped=0)
    assert (frozen['version'],frozen['versionCode'])==('0.50.0-preview',52)
    for name,digest in frozen['sourceFiles'].items():assert sha(ROOT/name)==digest,name
    for name,row in frozen['files'].items():assert sha(ROOT/name)==row['sha256'],name
    pair={name:frozen['files'][name]['sha256'] for name in ('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')}
    expected=frozen['files']['app/build/outputs/apk/preview/app-preview.apk']['sha256']
    files={owned(name) for name in frozen['sourceFiles']}
    files|={owned(name) for name in frozen['files'] if name.endswith(('.xml','.html')) and ('test-results/' in name or '/reports/' in name)}
    files|={owned('captures/release/0.50.0/'+name) for name in ('BUILDINFO.json','SHA256SUMS','findroid-update.json')}
    files|={owned(name) for name in chosen['supportingFiles']}
    files|={a.selection.resolve(),frozen_path}
    checker_hashes={sha(owned(name)) for name in chosen['optimizedCheckers']}
    images={}; identities=[]
    for api,serial in (('api30','emulator-5602'),('api36','emulator-5582')):
        paths=chosen['tablets'][api];folder=ROOT/paths['nativeFolder'];native=read(folder/'native.json')
        assert native['serial']==serial and native['success'] and native['runnerCompleted'] and native['runnerSucceeded']
        assert native['passed']==45 and native['failed']==native['skipped']==0
        assert native['apkSha256']==native['installedApkSha256']==pair
        assert len(native['isolatedBatches'])==8 and all(c['code']==0 for c in native['cases'])
        cases={(c['class'],c['test']) for c in native['cases']};assert len(cases)==45;identities.append(cases)
        provenance=read(folder/'build-provenance.json')
        assert provenance['sourceCommit']==frozen['sourceCommit'] and provenance['frozenReleaseCandidate'] and not provenance['draftOnly']
        assert provenance['nativeRegressionComplete'] and provenance['nativeFocusedComplete'] and provenance['nativePassed']==45
        assert provenance['immutableOriginalBytesVerifiedBeforeTests'] and provenance['restorationComplete'] and provenance['originalWorkspaceTreeKept']
        alias=PROOF/(api+'-final-native.json')
        assert alias.read_bytes()==(folder/'native.json').read_bytes()
        files|={alias,folder/'native.json',folder/'native.txt',folder/'build-provenance.json',folder/'batches/contract.json'}
        for batch in native['isolatedBatches']:
            for key,hashkey in (('report','reportSha256'),('log','logSha256')):
                path=owned(batch[key]);assert sha(path)==batch[hashkey];files.add(path)
        for name in paths['nativeImages']:
            path=owned(name);images[str(path.relative_to(ROOT))]=sha(path);files.add(path)
        for section in ('core','layouts'):
            path=owned(paths['optimized'][section]);result=read(path)
            assert result['serial']==serial and result['section'] in (section,'all')
            assert result['sourceCommit']==frozen['sourceCommit'] and result['apkSha256']==expected
            assert result['operationsUseExactOptimizedApk'] and result['immutableOriginalBytesVerifiedBeforeTests']
            assert result['acceptanceComplete'] and result['restorationComplete'] and result['originalWorkspaceTreeKept']
            assert result['checkerSha256'] in checker_hashes
            assert all(result['restoration'][key] for key in ('originalManagedMetadataDatabasesAndCacheRestored','originalFileAbsencesRestored','originalDirectoryAbsencesRestored','originalUriGrantsKept','originalStorageModeRestored','originalScreenSizeRestored','originalEnvironmentMatchesPersistedBaseline'))
            if section=='core':
                fact=next(r for r in result['checks'] if r['name']=='public51-to52-local-image-and-scanned-pdf')
                assert all(fact[k] for k in ('actualPinnedHttpsDownload','realAndroidModelImport','reviewedRemovalAndCancellation','installedModelSurvivesColdStart','actualClipboardRead','findMatch','sourceBytesKept','noImplicitRecognition'))
                assert len(result['clipboardProofs'])>=3
            else:
                layouts=result['layouts'];assert len(layouts)==12
                assert {(r['widthDp'],r['heightDp'],r['fontScale'],r['dark']) for r in layouts}=={(w,h,f,d) for w,h in ((1280,800),(800,1280),(600,800)) for f in (1.0,2.0) for d in (False,True)}
                assert all(r['actualClipboardTextChecked'] and r['allNamedControlsHaveComplete48DpBounds'] and r['sourceBytesKept'] and r['recognizedPassageActuallyVisible'] for r in layouts)
                assert len(result['clipboardProofs'])==12
                assert len(result['textVisibilityProofs'])==12 and all(r['twoCompleteGlyphLines'] for r in result['textVisibilityProofs'])
                assert len(result['systemFontGroups'])==6 and all(r['actualTextAndGlyphGrowth'] for r in result['systemFontGroups'])
                controls=result['controlProofs'][:]
                if 'explicitLayoutResume' in result:
                    resume=result['explicitLayoutResume'];previous=owned(resume['previousResult'])
                    assert sha(previous)==resume['previousResultSha256']
                    old=read(previous);assert old['restorationComplete'] and old['apkSha256']==expected
                    names=set(resume['reusedLayouts'])
                    controls += [row for row in old['controlProofs'] if row['name'] in names and 'label' in row]
                    files.add(previous)
                required={(f"layout-{r['widthDp']}x{r['heightDp']}-font{r['fontScale']}-"+('dark' if r['dark'] else 'light'),label) for r in layouts for label in ('Copy recognized text','Find in recognized text','Language and recognition options…','Close OCR')}
                found={(row['name'],row['label']) for row in controls if 'label' in row and row['bounds'][2]-row['bounds'][0]>=48*row['density']-1 and row['bounds'][3]-row['bounds'][1]>=48*row['density']-1}
                assert required<=found,'Missing complete named 48 dp controls'
            files.add(path)
            for row in result['captures']:
                image=path.parent/row['path'];assert image.name==row['path'] and row['browserBackdropExcluded'] and sha(image)==row['sha256']
                owned(str(image.relative_to(ROOT)));images[str(image.relative_to(ROOT))]=sha(image);files.add(image)
        if a.include_ota:
            path=owned(paths['ota']);ota=read(path)
            assert ota['acceptanceComplete'] and ota['publicFeedUpdateVerified'] and ota['actualInstallerCompletionConfirmed']
            assert ota['restorationComplete'] and ota['originalWorkspaceTreeKept'] and ota['originalDataRestored'] and ota['originalUriGrantsKept']
            assert ota['apkSha256']==expected and ota['sourceCommit']==frozen['sourceCommit']
            assert (ota['fromVersionCode'],ota['expectedVersionCode'])==(51,52)
            files.add(path)
            detail=path.parent/'public-ota/verification.json';facts=read(detail)
            assert all(facts[k] for k in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes','installerCompletionConfirmedBeforeRelaunch','originalFileBytes','legacyFavoritePreserved','sentinelPreferencePreserved','noRepeatOffer','restoredOriginalSetup'))
            files|={detail,path.parent/'ota-driver.txt'}
            for image in sorted((path.parent/'public-ota').glob('*.png')):
                owned(str(image.relative_to(ROOT)));images[str(image.relative_to(ROOT))]=sha(image);files.add(image)
    assert identities[0]==identities[1]
    review_path=owned(chosen['screenshotReview']);review=read(review_path)
    assert {r['path']:r['sha256'] for r in review['images'] if r['inspected']}==images
    files.add(review_path)
    names=sorted(str(path.relative_to(ROOT)) for path in files)
    if a.prepare_file_list:
        assert not a.prepare_file_list.exists()
        a.prepare_file_list.write_text(json.dumps(names,indent=2)+'\n');print(len(names));return
    privacy=read(owned(chosen['privacyReview']));allowlist=owned(chosen['publicationAllowlist'])
    assert privacy['explicitAllowlistChecked'] and privacy['privateRecoveryFilesExcluded'] and privacy['literalIdentifierMatches']==0
    assert privacy['publicationAllowlistSha256']==sha(allowlist) and read(allowlist)==names
    files|={owned(chosen['privacyReview']),allowlist}
    assert not a.output.exists()
    evidence_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    for path in sorted(files):
        name=str(path.relative_to(ROOT));raw=path.read_bytes();stored=subprocess.check_output(['git','show',evidence_commit+':'+name],cwd=ROOT)
        assert (raw.replace(b'\r\n',b'\n') if name.endswith('.bat') else raw)==stored,('Uncommitted evidence',name)
    manifest=dict(version='0.50.0-preview',versionCode=52,applicationSourceCommit=frozen['sourceCommit'],nativeTestSourceCommit=frozen['sourceCommit'],
        evidenceCommit=evidence_commit,previewApkSha256=expected,jvmCases=964,nativeCasesPerApi=45,isolatedBatchesPerApi=8,
        nativeScope='OCR plus existing image/PDF integration regressions; unrelated native suites are outside this increment.',
        frozenCandidateManifest=str(frozen_path.relative_to(ROOT)),frozenSourceBindingsComplete=True,publicOtaIncluded=a.include_ota,
        files=[dict(path=str(path.relative_to(ROOT)),sha256=sha(path),bytes=path.stat().st_size) for path in sorted(files)])
    with zipfile.ZipFile(a.output,'x',compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(files):archive.write(path,str(path.relative_to(ROOT)))
        archive.writestr('EVIDENCE.json',json.dumps(manifest,indent=2)+'\n')
    a.output.chmod(0o444)
    receipt=a.output.with_suffix('.json');assert not receipt.exists()
    receipt.write_text(json.dumps(dict(archive=a.output.name,sha256=sha(a.output),bytes=a.output.stat().st_size,files=len(files),publicOtaIncluded=a.include_ota,checkerSha256=sha(Path(__file__))),indent=2)+'\n')
    print(receipt.read_text())


if __name__=='__main__':main()
