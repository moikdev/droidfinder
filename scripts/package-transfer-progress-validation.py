#!/usr/bin/env python3
"""Validate and freeze the 0.42 transfer-progress evidence from an explicit allowlist."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
PROOF=ROOT/'captures/transfer-progress'
RELEASE=ROOT/'captures/release/0.42.0'
spec=importlib.util.spec_from_file_location('transfer_evidence_controls',ROOT/'scripts/package-local-index-validation.py')
controls=importlib.util.module_from_spec(spec);spec.loader.exec_module(controls)
DOCS=('README.md','PARITY.md','VALIDATION.md','docs/TRANSFER-PROGRESS.md','docs/TRANSFER-PLANNING.md',
    'docs/APP-UPDATES.md','docs/IMPROVEMENT-TRACKER.md','docs/NEXT-IMPROVEMENTS.md','docs/releases/0.42.0.md','docs/releases/0.42.0-update.txt')
SCRIPTS=('scripts/check-transfer-progress.py','scripts/build-transfer-fixture.py','scripts/android/TransferDocumentsProvider.java',
    'scripts/check-local-search-index.py','scripts/check-device.py','scripts/check-device-batches.py','scripts/check-ota-upgrade.py',
    'scripts/package-preview.py','scripts/package-local-index-validation.py','scripts/package-transfer-progress-validation.py',
    'scripts/android/acceptance.py','scripts/android/NativeTap.java','scripts/verify-release-validation.py',
    'captures/workspace-recovery/native-environment.py','captures/transfer-progress/archive-owned-native-captures.py')
REQUIRED=('success','separateReadOnlyProvider','directPreviousPublicUpgrade','legacyFavoritePreserved','actualSystemPickerConnection',
    'visibleProviderWaitWithoutInventedEta','recentRateAndPhaseEtaAfterRecovery','pauseHidesRateAndEta',
    'unknownSizeHasRateWithoutInventedTotalOrEta','waitingCancellationKeepsOriginal','cancelledDestinationAndStagingAbsent',
    'unrelatedUriGrantsUnchanged','restoredOriginalUriGrants','sentinelPreferencePreserved','originalSyntheticDocumentBytes',
    'restoredOriginalManagedFiles','restoredOriginalFileAbsences','providerControlsAndOwnedMarkersRestored','syntheticFixtureRemoved',
    'restoredOriginalStorageAccess','foregroundServiceStopsAfterFinish','grantCleanupUsesFixedSyntheticUriBridge','journalReadAfterOptimizedUiWithDebugBuild')
IMAGES=('transfer-provider-wait','transfer-recent-rate','transfer-paused-large-text','transfer-unknown-size','app-updates-0420')


def read(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def successful(report):
    assert report['success'] and report['runnerCompleted'] and report['runnerSucceeded']
    assert report['failed']==report['skipped']==0 and all(row['code']==0 for row in report['cases'])


def reviewed(folder):
    report=read(folder/'visual-review.json')
    assert report['actualScreenshotsInspected'] and report['images']
    assert {row['name'] for row in report['images']}=={p.name for p in folder.glob('*.png')}
    files=[folder/'visual-review.json']
    for row in report['images']:
        assert Path(row['name']).name==row['name'] and row['name'].endswith('.png')
        path=folder/row['name'];assert sha(path)==row['sha256'];files.append(path)
    return files


def full_named_control(tree,label,width,height,minimum):
    parents={child:parent for parent in tree.iter() for child in parent}
    found=[]
    for node in tree.iter('node'):
        if node.get('text')!=label and node.get('content-desc')!=label:continue
        while node.get('clickable')!='true' and node in parents:node=parents[node]
        if node in found:continue
        found.append(node)
        box=controls.bounds(node)
        if len(box)!=4:continue
        l,t,r,b=box
        if node.get('clickable')!='true' or node.get('enabled')!='true' or not (0<=l<r<=width and 0<=t<b<=height and r-l>=minimum and b-t>=minimum):continue
        parent=node;clipped=False
        while parent in parents:
            parent=parents[parent]
            if parent.get('scrollable')=='true':
                pl,pt,pr,pb=controls.bounds(parent);clipped |= not (pl<=l<r<=pr and pt<=t<b<=pb)
        if not clipped:return True
    return False


def local(expected):
    files=[]
    source=read(PROOF/'source-hashes.json')
    current=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','--','app/src','app/build.gradle.kts'],cwd=ROOT,text=True).splitlines()
    assert set(source)==set(current) and len(source)==318
    for name,digest in source.items():assert sha(ROOT/name)==digest,name
    build=read(PROOF/'build-summary.json')
    for name,info in build['artifacts'].items():assert sha(ROOT/name)==info['sha256'] and (ROOT/name).stat().st_size==info['bytes']
    assert build['artifacts']['app/build/outputs/apk/preview/app-preview.apk']['sha256']==expected
    tests=sorted((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    totals={k:sum(int(ET.parse(p).getroot().get(k,0)) for p in tests) for k in ('tests','failures','errors','skipped')}
    assert totals==dict(tests=525,failures=0,errors=0,skipped=0)
    lint=ROOT/'app/build/reports/lint-results-debug.xml'
    assert not any(n.get('severity') in ('Error','Fatal') for n in ET.parse(lint).getroot())
    files+=tests+[lint]
    native_paths=('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    native_hashes={name:sha(ROOT/name) for name in native_paths};identities=[];counts=[]
    for api,serial in ((30,'emulator-5602'),(36,'emulator-5582')):
        native=read(PROOF/f'api{api}-final-native.json');successful(native)
        assert native['serial']==serial and native['passed']>=370 and len(native['isolatedBatches'])==47
        contract=read(PROOF/f'api{api}-final-batches/contract.json')
        assert native['apkSha256']==native['installedApkSha256']==contract['apkSha256']==native_hashes
        cases={(row['class'],row['test']) for row in native['cases']};assert len(cases)==native['passed']
        assert len([row for row in cases if row[0]=='dev.findroid.TransferProgressWorkflowTest'])==2
        identities.append(cases);counts.append(native['passed'])
        files += [PROOF/f'api{api}-final-native.json',PROOF/f'api{api}-final-native.txt',PROOF/f'api{api}-final-batches/contract.json']
        for batch in native['isolatedBatches']:
            for pathkey,hashkey in (('report','reportSha256'),('log','logSha256')):
                path=ROOT/batch[pathkey];assert path.is_relative_to(PROOF/f'api{api}-final-batches') and sha(path)==batch[hashkey];files.append(path)
        cleanup=read(PROOF/f'api{api}-owned-capture-cleanup.json')
        assert cleanup['serial']==serial and cleanup['nativeApkPair']==native_hashes
        assert cleanup['fullScreenshotsExcludedFromPublication'] and len(cleanup['files'])==3
        assert all(row['retainedPrivately'] and row['removedOnlyOwnedSharedCapture'] for row in cleanup['files'])
        files.append(PROOF/f'api{api}-owned-capture-cleanup.json')
        captures=read(PROOF/f'api{api}/native-capture-hashes.json');assert captures['nativeApkSha256']==native_hashes
        for row in captures['files']:
            path=PROOF/f'api{api}/screenshots'/row['name'];assert sha(path)==row['sha256']
        files += [PROOF/f'api{api}/native-capture-hashes.json',PROOF/f'api{api}-focused-final.json',PROOF/f'api{api}-focused-final.txt']+reviewed(PROOF/f'api{api}/screenshots')
        folder=PROOF/f'api{api}-optimized';opt=read(folder/'verification.json')
        assert opt['serial']==serial and all(opt[key] for key in REQUIRED) and opt['cleanupErrors']==[]
        assert opt['apkSha256']==opt['restoredApkSha256']==expected
        assert opt['checkerSha256']==sha(ROOT/'scripts/check-transfer-progress.py')
        assert opt['helpersSha256']==sha(ROOT/'scripts/check-local-search-index.py')
        assert opt['previousApkSha256']==sha(ROOT/'captures/release/0.41.0/Findroid-0.41.0-preview.apk')
        assert (opt['fromVersionCode'],opt['toVersionCode'])==(43,44)
        assert opt['knownSizeCopy']==opt['unknownSizeCopy'] and opt['knownSizeCopy']['matchesDeterministicSource']
        assert opt['knownSizeCopy']['bytes']==8*1024*1024
        assert opt['syntheticJournal']['verificationDoesNotDoubleCountCopiedBytes']
        assert {(r['widthDp'],float(r['fontScale'])) for r in opt['layouts']}=={(w,s) for w in (600,800,1280) for s in (1.0,1.6)}
        for row in opt['layouts']:
            assert all(row[key] for key in ('pausedTransferSurvivesConfiguration','dialogControlsFullyReachable','stripControlsReachable','stripProgressReadable'))
            key=f'{row["widthDp"]}dp-font{row["fontScale"]}'
            tree=ET.parse(folder/(key+'-paused-capture.xml')).getroot()
            width,height=struct.unpack('>II',(folder/(key+'-paused.png')).read_bytes()[16:24])
            for label in ('Close transfers','Resume','Cancel transfer','Details','Open folder'):
                assert controls.full_control(tree,'Transfers',label,'Open folder',width,height,48*width/row['widthDp']),(api,key,label)
            strip=ET.parse(folder/(key+'-strip-capture.xml')).getroot()
            for label in ('Transfers','Resume','Cancel transfer'):
                assert full_named_control(strip,label,width,height,48*width/row['widthDp']),(api,key,'strip',label)
            labels=[n for n in strip.iter('node') if n.get('text','').startswith('Paused ·') and ('Transfer sample.bin' in n.get('text','') or ' / 8.0 MB' in n.get('text',''))]
            assert len(labels)==2
            density=width/row['widthDp'];minimum=min(120,80*float(row['fontScale']))
            assert all((controls.bounds(n)[2]-controls.bounds(n)[0])/density>=minimum for n in labels),(api,key,'progress text squeezed')
        files += [folder/'verification.json',folder/'inspection-retries.json',folder/'contact-sources.json']+list(folder.glob('*.xml'))+reviewed(folder)
    assert identities[0]==identities[1] and counts[0]==counts[1]
    for name in ('source-hashes.json','native-classes.txt','build-summary.json','full-build.txt','native-build-v2.txt','test-build-v2.txt','native-reconnect-build.txt','final-layout-build.txt','final-lint.txt','native-state-restoration.json','safety-review.json','rejected-attempts.json','provider-fixture-provenance.json'):
        files.append(PROOF/name)
    archive_retry=read(PROOF/'api36-archive-focused-retry.json');successful(archive_retry)
    assert archive_retry['passed']==1 and archive_retry['cases'][0]['class']=='dev.findroid.ArchiveWorkflowTest'
    rejected_archive=read(PROOF/'api36-final-batches/07-ArchiveWorkflowTest-before-retry-1.json')
    assert rejected_archive['passed']==5 and rejected_archive['failed']==1 and not rejected_archive['success']
    files += [PROOF/'api36-archive-focused-retry.json',PROOF/'api36-archive-focused-retry.txt',PROOF/'api36-final-batches/07-ArchiveWorkflowTest-before-retry-1.json',PROOF/'api36-final-batches/07-ArchiveWorkflowTest-before-retry-1.txt']
    reconnect_retry=read(PROOF/'api36-single-pane-reconnect-retry.json');successful(reconnect_retry)
    assert reconnect_retry['passed']==1 and reconnect_retry['cases'][0]['test']=='disconnectAndSameTreeReconnectRefreshASecondaryProviderWithoutNavigatingAway'
    rejected_reconnect=read(PROOF/'api36-final-batches/16-MultiRootSearchWorkflowTest-before-retry-1.json')
    assert rejected_reconnect['passed']==16 and rejected_reconnect['failed']==1 and not rejected_reconnect['success']
    files += [PROOF/'api36-single-pane-reconnect-retry.json',PROOF/'api36-single-pane-reconnect-retry.txt',PROOF/'api36-final-batches/16-MultiRootSearchWorkflowTest-before-retry-1.json',PROOF/'api36-final-batches/16-MultiRootSearchWorkflowTest-before-retry-1.txt']
    restored=read(PROOF/'native-state-restoration.json');assert len(restored)==2 and all(row['originalManagedMetadataDatabasesAndCacheRestored'] and row['originalFileAbsencesRestored'] for row in restored)
    correction=read(PROOF/'strip-readability-correction.json')
    assert correction['actualRetainedXmlExercised'] and correction['previouslySqueezedTextRejected']
    old_xml=ROOT/correction['xml'];old_png=ROOT/correction['png']
    assert old_xml.is_relative_to(PROOF/'api30-optimized-rejected-strip') and old_png.is_relative_to(PROOF/'api30-optimized-rejected-strip')
    assert sha(old_xml)==correction['xmlSha256'] and sha(old_png)==correction['pngSha256']
    rejected=ET.parse(old_xml).getroot()
    labels=[n for n in rejected.iter('node') if n.get('text','').startswith('Paused ·')]
    widths=[(controls.bounds(n)[2]-controls.bounds(n)[0])/1.5 for n in labels]
    assert widths==correction['actualTextWidthsDp'] and len(widths)==2 and max(widths)<correction['minimumTextWidthDp']
    files += [PROOF/'strip-readability-correction.json',old_xml,old_png,PROOF/'owned-fixture-grant-recovery.json',PROOF/'interrupted-native-fixture-cleanup.json']
    picker=read(PROOF/'picker-recovery-review.json')
    assert picker['actualMissingActionXmlExercised'] and picker['actualFinalRecoveryPassed']
    assert picker['localDirectoryHierarchiesNotRetained'] and picker['actualGrantOnlyForSyntheticProvider']
    assert picker['checkerSha256']==sha(ROOT/'scripts/check-transfer-progress.py')
    assert read(PROOF/'api30-optimized/verification.json')['systemPickerRecoveredMissingFolderAction']
    for row in picker['files']:
        path=ROOT/row['path'];assert path.is_relative_to(PROOF/'api30-optimized-picker-missing-action') and sha(path)==row['sha256'];files.append(path)
    missing=ET.parse(PROOF/'api30-optimized-picker-missing-action/system-picker-fixture.xml').getroot()
    labels={n.get('text') for n in missing.iter('node')}
    assert {'Transfer sample.bin','Unknown size.bin'}<=labels and not {'USE THIS FOLDER','Use this folder'}&labels
    assert not any(p.name.startswith('private-') for api in (30,36) for p in (PROOF/f'api{api}-optimized').glob('*.xml'))
    files.append(PROOF/'picker-recovery-review.json')
    safety=read(PROOF/'safety-review.json')
    for name,digest in safety['reviewedSources'].items():assert sha(ROOT/name)==digest
    assert read(PROOF/'rejected-attempts.json')['rejectedAttemptsExcludedFromAcceptance']
    fixture=read(PROOF/'provider-fixture-provenance.json')
    assert fixture['sourceSha256']==sha(ROOT/'scripts/android/TransferDocumentsProvider.java')
    assert fixture['readOnly'] and fixture['noNetworkPermission'] and fixture['separateFromShippingApk']
    return files,counts[0]


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--local-only',action='store_true');p.add_argument('--output',type=Path);a=p.parse_args()
    preview=ROOT/'app/build/outputs/apk/preview/app-preview.apk';expected=sha(preview)
    files,native_count=local(expected)
    summary=dict(localAcceptancePassed=True,previewApkSha256=expected,jvmCases=525,nativeCasesPerApi=native_count,isolatedBatchesPerApi=47)
    (PROOF/'local-acceptance.json').write_text(json.dumps(summary,indent=2)+'\n')
    if a.local_only:print(json.dumps(summary,indent=2));return
    assert a.output is not None and not a.output.exists()
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip(),'Commit the exact evidence snapshot first'
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip();info=read(RELEASE/'BUILDINFO.json')
    assert info['version']=='0.42.0-preview' and int(info['version_code'])==44 and not info['working_tree_dirty']
    assert info['artifacts'][0]['sha256']==expected
    for row in info['artifacts']:assert sha(RELEASE/row['name'])==row['sha256']
    for path in list((ROOT/'app/src').rglob('*.kt'))+list((ROOT/'app/src/main').rglob('*.xml'))+[ROOT/'app/build.gradle.kts']:
        assert subprocess.check_output(['git','show',info['source_commit']+':'+str(path.relative_to(ROOT))],cwd=ROOT)==path.read_bytes()
    files += [ROOT/name for name in DOCS+SCRIPTS]
    files += list((ROOT/'app/src').rglob('*.kt'))+list((ROOT/'app/src/main').rglob('*.xml'))
    files += [ROOT/name for name in ('app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties')]
    files += [ROOT/'docs/screenshots'/(name+'.png') for name in IMAGES]
    files += [RELEASE/name for name in ('BUILDINFO.json','SHA256SUMS','findroid-update.json')]
    for api in (30,36):
        folder=PROOF/f'api{api}-ota';ota=read(folder/'verification.json')
        assert (ota['fromVersionCode'],ota['toVersionCode'])==(43,44) and ota['restoredApkSha256']==expected
        assert all(ota[key] for key in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes','originalFileBytes','legacyFavoritePreserved','sentinelPreferencePreserved','updatedSidebarVisible','noRepeatOffer','restoredOriginalSetup'))
        files += [folder/'verification.json']+list(folder.glob('*.png'))+list(folder.glob('*.xml'))
    public=read(PROOF/'public-downloads.json')
    assert public['public'] and public['sourceCommit']==info['source_commit'] and len(public['assets'])==5
    assert all(row['publicDownloadMatchesLocal'] and row['githubDigestMatches'] for row in public['assets'])
    live=read(PROOF/'api36-live-feed.json');successful(live)
    assert live['passed']==1 and live['downloadedVersionCode']==44 and live['downloadedApkSha256']==expected
    restored=read(PROOF/'restored-public-tablets.json')
    assert len(restored)==2 and all(row['originalManagedMetadataDatabasesAndCacheRestored'] and row['originalFileAbsencesRestored'] and row['installedPublicApkSha256']==expected for row in restored)
    ota_review=read(PROOF/'ota-visual-review.json')
    assert ota_review['actualScreenshotInspected'] and ota_review['sha256']==sha(PROOF/'api30-ota/up-to-date.png')
    assert sha(ROOT/'docs/screenshots/app-updates-0420.png')==ota_review['sha256']
    assert read(PROOF/'signing-continuity.json')['sameSigningCertificate']
    for name in ('public-downloads.json','api36-live-feed.json','api36-live-feed.txt','restored-public-tablets.json','signing-continuity.json','local-acceptance.json','ota-visual-review.json','verify-public.py','run-live-feed.py','restore-public-tablets.py','docs-screenshot-provenance.json'):
        files.append(PROOF/name)
    files=sorted(set(files))
    for path in files:
        assert path.is_file() and path.is_relative_to(ROOT),path
        assert path.suffix not in {'.apk','.db','.sqlite','.jks','.keystore','.bin','.tar'}
        assert not any(part in {'native-original','accepted-build','private-native-state','private-native-screenshots'} for part in path.parts)
        assert 'PRIVATE' not in path.name and 'bugreport' not in path.name
    native_commit=subprocess.check_output(['git','log','-1','--format=%H',info['source_commit'],'--','app/src/androidTest'],cwd=ROOT,text=True).strip()
    manifest=dict(applicationSourceCommit=info['source_commit'],nativeTestSourceCommit=native_commit,evidenceCommit=commit,previewApkSha256=expected,jvmCases=525,nativeCasesPerApi=native_count,isolatedBatchesPerApi=47,files=[dict(path=str(path.relative_to(ROOT)),bytes=path.stat().st_size,sha256=sha(path)) for path in files])
    a.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(a.output,'x',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('EVIDENCE.json',json.dumps(manifest,indent=2)+'\n')
        for path in files:archive.write(path,path.relative_to(ROOT))
    receipt=dict(archive=a.output.name,bytes=a.output.stat().st_size,sha256=sha(a.output),entries=len(files)+1,**{key:manifest[key] for key in ('applicationSourceCommit','nativeTestSourceCommit','evidenceCommit','jvmCases','nativeCasesPerApi','isolatedBatchesPerApi')})
    (PROOF/'validation-package.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))


if __name__=='__main__':main()
