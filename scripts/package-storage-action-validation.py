#!/usr/bin/env python3
"""Validate and freeze the 0.41 storage-action evidence from an explicit public allowlist."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROOF = ROOT/'captures/storage-capabilities'
RELEASE = ROOT/'captures/release/0.41.0'
spec = importlib.util.spec_from_file_location('index_evidence_controls', ROOT/'scripts/package-local-index-validation.py')
controls = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controls)
ACTION_DESCRIPTIONS = {
    'Read': 'The local filesystem reported read access when this item was loaded.',
    'Create items': 'The local filesystem reported the access used for creating children in this folder when this item was loaded. The operation checks again before changes.',
    'Change contents': 'Create items describes adding children to this folder. Change contents applies to files.',
    'Rename': 'The local filesystem reported the access used for renaming this item when this item was loaded. The operation checks again before changes.',
    'Delete': 'The local filesystem reported the access used for deleting this item when this item was loaded. The operation checks again before changes.',
    'Move to Trash': 'Findroid moves this item to its Trash. The operation checks the source and recovery destination before changing files.',
}
REQUIRED = ('success', 'directPreviousPublicUpgrade', 'legacyFavoritePreserved', 'actualSystemPickerReadWriteGrant',
    'actualReadOnlyGrant', 'providerWindowRouteVerified', 'grantChangesUseDebugBridgeOptimizedUiChecked', 'actualGrantRevocation', 'unrelatedUriGrantsUnchanged', 'grantsAuditedBeforeSetup',
    'restoredOriginalUriGrants', 'restoredOriginalStorageAccess', 'readOnlyCreationExplainsRecovery',
    'originalSyntheticDocumentBytes', 'sentinelPreferencePreserved', 'restoredOriginalSetupAndCache', 'syntheticFixtureRemoved')
DOCS = ('README.md', 'PARITY.md', 'VALIDATION.md', 'docs/STORAGE-ACTIONS.md', 'docs/STORAGE.md',
    'docs/APP-UPDATES.md', 'docs/IMPROVEMENT-TRACKER.md', 'docs/NEXT-IMPROVEMENTS.md',
    'docs/releases/0.41.0.md', 'docs/releases/0.41.0-update.txt')
SCRIPTS = ('scripts/check-storage-actions.py', 'scripts/check-local-search-index.py', 'scripts/check-device.py',
    'scripts/check-device-batches.py', 'scripts/check-ota-upgrade.py', 'scripts/package-preview.py',
    'scripts/package-local-index-validation.py', 'scripts/package-storage-action-validation.py',
    'scripts/android/acceptance.py', 'scripts/android/NativeTap.java', 'scripts/verify-release-validation.py', 'captures/workspace-recovery/native-environment.py')
IMAGES = ('storage-actions-tablet', 'storage-actions-mixed', 'storage-actions-large-text', 'app-updates-0410')


def read(path): return json.loads(path.read_text())
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def successful(report):
    assert report['success'] and report['failed'] == report['skipped'] == 0
    assert report['runnerCompleted'] and report['runnerSucceeded']


def reviewed(folder):
    report = read(folder/'visual-review.json')
    assert report['actualScreenshotsInspected'] and report['images']
    assert {row['name'] for row in report['images']} == {path.name for path in folder.glob('*.png')}, 'Every actual capture and contact sheet needs inspection'
    files = [folder/'visual-review.json']
    for row in report['images']:
        name = row['name']
        assert Path(name).name == name and name.endswith('.png')
        path = folder/name
        assert sha(path) == row['sha256']
        files.append(path)
    return files


def readable_text(tree, text, width, height):
    parents = {c:p for p in tree.iter() for c in p}
    for node in tree.iter('node'):
        if node.get('text') != text: continue
        box = controls.bounds(node)
        if len(box) != 4: continue
        l,t,r,b = box
        if not (0<=l<r<=width and 0<=t<b<=height): continue
        clipped = False
        while node in parents:
            node = parents[node]
            if node.get('scrollable') == 'true':
                pl,pt,pr,pb = controls.bounds(node)
                clipped |= not (pl+2<=l<r<=pr-2 and pt+2<=t<b<=pb-2)
        if not clipped: return True
    return False


def validate_local(expected):
    files = []
    source = read(PROOF/'native-source-hashes.json')
    current_sources = subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','--','app/src','app/build.gradle.kts'],cwd=ROOT,text=True).splitlines()
    assert set(source) == set(current_sources) and len(source) == 315
    for name,digest in source.items(): assert sha(ROOT/name) == digest, name
    tests = sorted((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    totals = {k:sum(int(ET.parse(p).getroot().get(k,0)) for p in tests) for k in ('tests','failures','errors','skipped')}
    assert totals == dict(tests=513,failures=0,errors=0,skipped=0), totals
    lint = ROOT/'app/build/reports/lint-results-debug.xml'
    assert not any(n.get('severity') in {'Error','Fatal'} for n in ET.parse(lint).getroot())
    files += tests+[lint]
    cases_per_api = []
    native_paths = ('app/build/outputs/apk/debug/app-debug.apk',
                    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    native_hashes = {name:sha(ROOT/name) for name in native_paths}
    for api,serial in ((30,'emulator-5602'),(36,'emulator-5582')):
        native = read(PROOF/f'api{api}-final-native.json'); successful(native)
        assert native['serial'] == serial and native['passed'] == 368 and len(native['isolatedBatches']) == 40
        contract = read(PROOF/f'api{api}-final-batches/contract.json')
        assert contract['serial'] == serial
        assert native['apkSha256'] == native['installedApkSha256'] == contract['apkSha256'] == native_hashes
        cases = {(row['class'],row['test']) for row in native['cases']}
        assert len(cases) == 368; cases_per_api.append(cases)
        files += [PROOF/f'api{api}-final-native.json',PROOF/f'api{api}-final-native.txt',PROOF/f'api{api}-final-batches/contract.json']
        for batch in native['isolatedBatches']:
            for pathkey,hashkey in (('report','reportSha256'),('log','logSha256')):
                path = ROOT/batch[pathkey]
                assert path.is_relative_to(PROOF/f'api{api}-final-batches') and sha(path) == batch[hashkey]
                files.append(path)
        captures = read(PROOF/f'api{api}/native-capture-hashes.json')
        assert captures['nativeApkSha256'] == native_hashes
        for name,digest in captures['files'].items():
            path = ROOT/name
            assert path.is_relative_to(PROOF/f'api{api}') and sha(path) == digest
        files += [PROOF/f'api{api}/native-capture-hashes.json',PROOF/f'api{api}/screenshots/contact-sources.json']+reviewed(PROOF/f'api{api}/screenshots')
        folder = PROOF/f'api{api}-optimized'
        opt = read(folder/'verification.json')
        assert opt['serial'] == serial and all(opt[k] for k in REQUIRED)
        assert opt['acceptanceScope'] == 'full optimized acceptance'
        assert opt['descriptionVisibility'] == 'strict interior scroll bounds with 2px inset'
        assert opt['grantBridgeApkSha256'] == native_hashes[native_paths[0]]
        assert opt['privateAuditTransport'] == 'time-bounded selected-tag log, unique invocation marker, memory only'
        assert opt['apkSha256'] == opt['restoredApkSha256'] == expected
        assert opt['checkerSha256'] == sha(ROOT/'scripts/check-storage-actions.py')
        assert opt['helpersSha256'] == sha(ROOT/'scripts/check-local-search-index.py')
        assert opt['previousApkSha256'] == sha(ROOT/'captures/local-search-index/public/Findroid-0.40.0-preview.apk')
        assert (opt['fromVersionCode'],opt['toVersionCode']) == (42,43) and opt['cleanupErrors'] == []
        assert {(r['widthDp'],float(r['fontScale'])) for r in opt['layouts']} == {(w,s) for w in (600,800,1280) for s in (1.0,1.6)}
        for row in opt['layouts']:
            assert row['fullTouchBounds'] and row['allSixActionsReachable'] and row['allSixActionDescriptionsReachable']
            key = f'{row["widthDp"]}dp-font{row["fontScale"]}'
            tree = ET.parse(folder/(key+'-details-capture.xml')).getroot()
            # Use the actual PNG dimensions, since density is device-specific.
            import struct
            width,height = struct.unpack('>II',(folder/(key+'-details.png')).read_bytes()[16:24])
            for label in ('Refresh access info','Storage locations…','Done','Close Available actions'):
                assert controls.full_control(tree,'Available actions',label,'Done',width,height,48*width/row['widthDp']), (api,key,label)
            for action,description in ACTION_DESCRIPTIONS.items():
                text_tree = ET.parse(folder/(key+'-'+action+'.xml')).getroot()
                assert readable_text(text_tree,action,width,height), (api,key,action)
                reason_tree = ET.parse(folder/(key+'-'+action+'-reason.xml')).getroot()
                assert readable_text(reason_tree,description,width,height), (api,key,action,'description')
        files += [folder/'verification.json',folder/'inspection-retries.json',folder/'contact-sources.json']+list(folder.glob('*.xml'))+reviewed(folder)
    assert cases_per_api[0] == cases_per_api[1]
    readers = read(PROOF/'reader-io-verification.json')
    assert {r['serial'] for r in readers} == {'emulator-5602','emulator-5582'}
    for row in readers:
        assert row['checkerSha256'] == sha(ROOT/'scripts/check-storage-actions.py')
        assert all(row[k] for k in ('actualReaderFunctionExecuted','binaryBytesPreserved','genuineAbsenceIsNone',
            'missingMandatoryFileIsError','directoryIsError','unreadableFileIsErrorNotAbsence','syntheticAuditFolderRemoved','onlyUniqueSyntheticFilesTouched'))
    audits=read(PROOF/'private-audit-transport.json')
    assert {row['serial'] for row in audits}=={'emulator-5602','emulator-5582'}
    for row in audits:
        assert row['checkerSha256']==sha(ROOT/'scripts/check-storage-actions.py')
        assert all(row[key] for key in ('actualNestedReaderBodyExecuted','actualNativeAuditExecutedTwice',
            'twoConsecutiveMapsMatch','deviceTimeWindowAndUniqueMarker','privateUriDataNotPrintedOrSaved'))
    files += [PROOF/'private-audit-transport.json',PROOF/'probe-grant-transport.py']
    visibility=read(PROOF/'description-visibility-correction.json')
    assert visibility['actualRetainedXmlExercised'] and visibility['previouslyClippedParagraphRejected']
    assert visibility['completeInteriorParagraphAccepted'] and visibility['viewportInsetPixels']==2
    assert [row['readable'] for row in visibility['samples']]==[False,True]
    for sample in visibility['samples']:
        path=ROOT/sample['path'];assert path.is_relative_to(PROOF/'api30-optimized-rejected-3')
        assert sha(path)==sample['sha256']
        assert readable_text(ET.parse(path).getroot(),ACTION_DESCRIPTIONS['Move to Trash'],sample['widthPixels'],sample['heightPixels'])==sample['readable']
        files.append(path)
    files += [PROOF/'description-visibility-correction.json',PROOF/'native-source-hashes.json',PROOF/'native-classes.txt',PROOF/'reader-io-verification.json',PROOF/'verify-reader-io.py']
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-local',action='store_true')
    parser.add_argument('--output',type=Path)
    args = parser.parse_args()
    expected = sha(ROOT/'app/build/outputs/apk/preview/app-preview.apk')
    files = validate_local(expected)
    if args.check_local:
        report = dict(previewApkSha256=expected,jvmCases=513,nativeCasesPerApi=368,batchesPerApi=40,
                      allLocalGatesPassed=True,validatedFiles=len(set(files)))
        (PROOF/'local-acceptance.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2));return
    assert args.output and not args.output.exists(), 'Choose a new immutable archive path'
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip(), 'Commit accepted source and docs first'
    commit = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    info = read(RELEASE/'BUILDINFO.json')
    assert info['version'] == '0.41.0-preview' and int(info['version_code']) == 43 and not info['working_tree_dirty']
    assert info['artifacts'][0]['sha256'] == expected
    for item in info['artifacts']: assert sha(RELEASE/item['name']) == item['sha256']
    for path in (ROOT/'app/src/main').rglob('*'):
        if path.is_file():assert subprocess.check_output(['git','show',info['source_commit']+':'+str(path.relative_to(ROOT))],cwd=ROOT) == path.read_bytes()
    assert subprocess.check_output(['git','show',info['source_commit']+':app/build.gradle.kts'],cwd=ROOT) == (ROOT/'app/build.gradle.kts').read_bytes()
    files += [ROOT/name for name in DOCS+SCRIPTS]
    files += list((ROOT/'app/src').rglob('*.kt'))+list((ROOT/'app/src/main').rglob('*.xml'))
    files += [ROOT/name for name in ('app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties')]
    files += [ROOT/'docs/screenshots'/(name+'.png') for name in IMAGES]
    files += [RELEASE/name for name in ('BUILDINFO.json','SHA256SUMS','findroid-update.json')]
    for api in (30,36):
        folder = PROOF/f'api{api}-ota';ota = read(folder/'verification.json')
        assert (ota['fromVersionCode'],ota['toVersionCode']) == (42,43) and ota['restoredApkSha256'] == expected
        assert all(ota[k] for k in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes','originalFileBytes',
            'legacyFavoritePreserved','sentinelPreferencePreserved','updatedSidebarVisible','noRepeatOffer','restoredOriginalSetup'))
        files += [folder/'verification.json']+list(folder.glob('*.png'))+list(folder.glob('*.xml'))
    public = read(PROOF/'public-downloads.json')
    assert public['public'] and public['sourceCommit'] == info['source_commit'] and len(public['assets']) == 5
    assert all(r['publicDownloadMatchesLocal'] and r['githubDigestMatches'] for r in public['assets'])
    live = read(PROOF/'api36-live-feed.json');successful(live)
    assert live['passed'] == 1 and live['downloadedVersionCode'] == 43 and live['downloadedApkSha256'] == expected
    restored = read(PROOF/'restored-public-tablets.json')
    assert len(restored) == 2 and all(r['privateOriginalMetadataAndPreferencesRestored'] and
        r['installedPublicApkSha256'] == expected and float(r['fontScale']) == 1 for r in restored)
    review = read(PROOF/'ota-visual-review.json')
    assert review['actualScreenshotInspected'] and review['sha256'] == sha(PROOF/'api30-ota/up-to-date.png')
    assert sha(ROOT/'docs/screenshots/app-updates-0410.png') == review['sha256']
    assert read(PROOF/'signing-continuity.json')['sameSigningCertificate']
    for name,digest in read(PROOF/'safety-review.json')['reviewedSources'].items():assert sha(ROOT/name) == digest
    rejected = read(PROOF/'rejected-attempts.json');assert rejected['rejectedAttemptsExcludedFromAcceptance']
    assert all(not row['countedTowardFinalAcceptance'] for row in rejected['attempts'])
    for name in ('build-summary.json','final-build.txt','grant-audit-test-build.txt','final-lint.txt','public-downloads.json','api36-live-feed.json',
        'api36-live-feed.txt','restored-public-tablets.json','signing-continuity.json','rejected-attempts.json',
        'local-acceptance.json','ota-visual-review.json','safety-review.json','verify-public.py','run-live-feed.py','restore-public-tablets.py'):
        files.append(PROOF/name)
    files = sorted(set(files))
    for path in files:
        assert path.is_file() and path.is_relative_to(ROOT),path
        assert path.suffix not in {'.apk','.db','.sqlite','.jks','.keystore','.bin'}
        assert 'native-original' not in path.parts and 'accepted-build' not in path.parts and 'bugreport' not in path.name
    native_commit = subprocess.check_output(['git','log','-1','--format=%H',info['source_commit'],'--','app/src/androidTest'],cwd=ROOT,text=True).strip()
    manifest = dict(applicationSourceCommit=info['source_commit'],nativeTestSourceCommit=native_commit,evidenceCommit=commit,
        previewApkSha256=expected,jvmCases=513,nativeCasesPerApi=368,isolatedBatchesPerApi=40,
        files=[dict(path=str(path.relative_to(ROOT)),bytes=path.stat().st_size,sha256=sha(path)) for path in files])
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.output,'x',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('EVIDENCE.json',json.dumps(manifest,indent=2)+'\n')
        for path in files:archive.write(path,path.relative_to(ROOT))
    report = dict(archive=args.output.name,bytes=args.output.stat().st_size,sha256=sha(args.output),entries=len(files)+1,
        **{key:manifest[key] for key in ('applicationSourceCommit','nativeTestSourceCommit','evidenceCommit','jvmCases','nativeCasesPerApi','isolatedBatchesPerApi')})
    (PROOF/'validation-package.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))


if __name__=='__main__':main()
