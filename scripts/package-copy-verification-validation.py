#!/usr/bin/env python3
"""Validate and freeze 0.43 copy verification evidence from an explicit safe allowlist."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
PROOF=ROOT/'captures/copy-verification'
RELEASE=ROOT/'captures/release/0.43.0'
DOCS=('README.md','PARITY.md','VALIDATION.md','docs/COPY-VERIFICATION.md','docs/TRANSFER-PROGRESS.md',
    'docs/APP-UPDATES.md','docs/IMPROVEMENT-TRACKER.md','docs/NEXT-IMPROVEMENTS.md','docs/releases/0.43.0.md','docs/releases/0.43.0-update.txt')
SCRIPTS=('scripts/check-copy-verification.py','scripts/build-verification-fixture.py','scripts/android/VerificationDocumentsProvider.java',
    'scripts/check-local-search-index.py','scripts/check-device.py','scripts/check-device-batches.py','scripts/check-ota-upgrade.py',
    'scripts/package-preview.py','scripts/package-copy-verification-validation.py','scripts/android/acceptance.py',
    'scripts/android/NativeTap.java','scripts/verify-release-validation.py','captures/workspace-recovery/native-environment.py',
    'captures/copy-verification/archive-owned-native-captures.py','captures/copy-verification/private-native-state.py',
    'captures/copy-verification/native-to-optimized.py','captures/copy-verification/check-public-privacy.py')
REQUIRED=('success','separateOwnedProvider','directPreviousPublicUpgrade','legacyFavoritePreserved','actualSystemPickerConnection',
    'systemPickerPrivateNavigationNotRetained','cancelledReviewStartsNoProviderReadOrCopy','emptyNestedFolderPreserved','realProviderReadback','verificationPauseResume',
    'sameLengthCorruptionRejectsAndCleansUnpublishedCopy','unreadableDestinationRejectsAndCleansUnpublishedCopy',
    'readbackCancellationKeepsSourceAndCleansStaging','foregroundServiceStopsAfterEveryFinish','originalSyntheticDocumentBytes',
    'sourceOriginalsReadOnly','unrelatedUriGrantsUnchanged','restoredOriginalUriGrants','sentinelPreferencePreserved',
    'restoredOriginalManagedFiles','restoredOriginalFileAbsences','originalProviderApkAndAllManagedDataRestored',
    'originalProviderEmptyDirectoriesAndAbsencesRestored','syntheticFixtureRemoved','restoredOriginalStorageAccess',
    'grantCleanupUsesFixedSyntheticUriBridge','journalReadAfterOptimizedUiWithDebugBuild')
IMAGES=('copy-verification-review','copy-verification-readback','copy-verification-results','copy-verification-failure','app-updates-0430')


def read(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def successful(report):
    assert report['success'] and report['runnerCompleted'] and report['runnerSucceeded']
    assert report['failed']==report['skipped']==0 and all(row['code']==0 for row in report['cases'])
def bounds(node):return list(map(int,re.findall(r'-?\d+',node.get('bounds',''))))
def latest_card(tree):
    parents={c:p for p in tree.iter() for c in p}
    nodes=[n for n in tree.iter('node') if n.get('text') in ('Details','Hide details') and len(bounds(n))==4 and bounds(n)[2]>bounds(n)[0]]
    assert nodes
    node=nodes[0]
    while not any(n.get('text','').startswith('Copy verification · ') for n in node.iter('node')):
        assert node in parents;node=parents[node]
    assert sum(n.get('text','').startswith('Copy verification · ') for n in node.iter('node'))==1
    return node

def full_named_control(tree,scope,label,width,height,minimum):
    parents={c:p for p in tree.iter() for c in p};found=[]
    for node in scope.iter('node'):
        if label not in (node.get('text'),node.get('content-desc')):continue
        while node.get('clickable')!='true' and node in parents:node=parents[node]
        if node not in found:found.append(node)
    assert len(found)==1,(label,len(found))
    node=found[0];l,t,r,b=bounds(node)
    assert node.get('clickable')=='true' and node.get('enabled')=='true'
    assert 0<=l<r<=width and 0<=t<b<=height and r-l>=minimum and b-t>=minimum,(label,'control is clipped or too small')
    while node in parents:
        node=parents[node]
        if node.get('scrollable')=='true':
            pl,pt,pr,pb=bounds(node);assert pl<=l<r<=pr and pt<=t<b<=pb,(label,'clipped by scroll viewport')

def copy_review_scope(tree):
    parents={c:p for p in tree.iter() for c in p}
    titles=[n for n in tree.iter('node') if n.get('text')=='Review verified copy']
    assert len(titles)==1
    node=titles[0]
    while not any(n.get('text')=='Copy and verify' for n in node.iter('node')):
        assert node in parents;node=parents[node]
    return node

def complete_scoped_text(tree,scope,expected,width,height):
    parents={c:p for p in tree.iter() for c in p}
    def complete(node):
        l,t,r,b=bounds(node)
        if not (0<=l<r<=width and 0<=t<b<=height):return False
        while node in parents:
            node=parents[node]
            if node.get('scrollable')=='true':
                pl,pt,pr,pb=bounds(node)
                if not (pl<=l<r<=pr and pt+2<=t<b<=pb-2):return False
        return True
    assert any(expected in n.get('text','') and len(bounds(n))==4 and complete(n) for n in scope.iter('node')),(expected,'text clipped or missing')

def complete_review_text(tree,expected,width,height):
    complete_scoped_text(tree,copy_review_scope(tree),expected,width,height)

def complete_transfer_detail_text(tree,expected,width,height):
    parents={c:p for p in tree.iter() for c in p}
    close=[n for n in tree.iter('node') if n.get('content-desc')=='Close transfers']
    assert len(close)==1
    scope=close[0]
    while not any(n.get('scrollable')=='true' for n in scope.iter('node')):
        assert scope in parents;scope=parents[scope]
    complete_scoped_text(tree,scope,expected,width,height)

def reviewed(folder):
    report=read(folder/'visual-review.json');assert report['actualScreenshotsInspected']
    assert {r['name'] for r in report['images']}=={p.name for p in folder.glob('*.png')}
    paths=[folder/'visual-review.json']
    for row in report['images']:
        assert Path(row['name']).name==row['name'] and row['name'].endswith('.png')
        path=folder/row['name'];assert sha(path)==row['sha256'];paths.append(path)
    return paths

def local(expected):
    files=[];source=read(PROOF/'source-hashes.json')
    current=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','--','app/src','app/build.gradle.kts'],cwd=ROOT,text=True).splitlines()
    assert set(source)==set(current) and len(source)==322
    for name,digest in source.items():assert sha(ROOT/name)==digest,name
    build=read(PROOF/'build-summary.json')
    for name,info in build['artifacts'].items():assert sha(ROOT/name)==info['sha256'] and (ROOT/name).stat().st_size==info['bytes']
    assert build['artifacts']['app/build/outputs/apk/preview/app-preview.apk']['sha256']==expected
    tests=sorted((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    totals={k:sum(int(ET.parse(p).getroot().get(k,0)) for p in tests) for k in ('tests','failures','errors','skipped')}
    assert totals==dict(tests=549,failures=0,errors=0,skipped=0)
    lint=ROOT/'app/build/reports/lint-results-debug.xml'
    assert not any(n.get('severity') in ('Error','Fatal') for n in ET.parse(lint).getroot())
    files+=tests+[lint]
    native_paths=('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    pair={name:sha(ROOT/name) for name in native_paths};identities=[]
    checker=ROOT/'scripts/check-copy-verification.py'
    for api,serial in ((30,'emulator-5602'),(36,'emulator-5582')):
        native=read(PROOF/f'api{api}-final-native.json');successful(native)
        assert native['serial']==serial and native['passed']==426 and len(native['isolatedBatches'])==48
        contract=read(PROOF/f'api{api}-regression-batches/contract.json')
        assert native['apkSha256']==native['installedApkSha256']==contract['apkSha256']==pair
        cases={(r['class'],r['test']) for r in native['cases']};assert len(cases)==426
        assert sum(c[0]=='dev.findroid.CopyVerificationWorkflowTest' for c in cases)==17
        assert sum(c[0]=='dev.findroid.SqliteTransferStoreTest' for c in cases)==10
        identities.append(cases)
        files += [PROOF/f'api{api}-final-native.json',PROOF/f'api{api}-final-native.txt',PROOF/f'api{api}-regression.json',PROOF/f'api{api}-regression-batches/contract.json']
        for batch in native['isolatedBatches']:
            for pathkey,hashkey in (('report','reportSha256'),('log','logSha256')):
                path=ROOT/batch[pathkey];assert path.is_relative_to(PROOF/f'api{api}-regression-batches') and sha(path)==batch[hashkey];files.append(path)
        focused=read(PROOF/f'api{api}-focused.json');successful(focused);assert focused['passed']==27
        files += [PROOF/f'api{api}-focused.json',PROOF/f'api{api}-focused.txt']
        cleanup=read(PROOF/f'api{api}-owned-capture-cleanup.json')
        assert cleanup['serial']==serial and cleanup['nativeApkPair']==pair and cleanup['fullScreenshotsExcludedFromPublication']
        assert len(cleanup['files'])==3 and all(r['retainedPrivately'] and r['removedOnlyOwnedSharedCapture'] for r in cleanup['files'])
        files.append(PROOF/f'api{api}-owned-capture-cleanup.json')
        captures=read(PROOF/f'api{api}/native-capture-hashes.json');assert captures['nativeApkSha256']==pair
        for row in captures['files']:
            path=PROOF/f'api{api}/screenshots'/row['name'];assert sha(path)==row['sha256'];files.append(path)
        files.append(PROOF/f'api{api}/native-capture-hashes.json')
        folder=PROOF/f'api{api}-optimized';opt=read(folder/'verification.json')
        assert opt['serial']==serial and all(opt[k] for k in REQUIRED) and opt['cleanupErrors']==[]
        assert 'acceptanceFailure' not in opt and opt['apkSha256']==opt['restoredApkSha256']==expected
        assert opt['checkerSha256']==sha(checker)
        assert opt['helpersSha256']==sha(ROOT/'scripts/check-local-search-index.py')
        assert opt['privateEnvironmentRecoverySaved']
        titles=opt['completedProviderFriendlyTitles']
        assert len(titles)==8 and {r['case'] for r in titles}=={'known','unknown','nested','readback','corrupt','unreadable','cancel','replace'}
        for row in titles:
            assert row['title']=={'nested':'Nested originals','unknown':'Unknown size.bin','unreadable':'Unknown size.bin'}.get(row['case'],'Transfer sample.bin')
        assert opt['previousApkSha256']==sha(ROOT/'captures/release/0.42.0/Findroid-0.42.0-preview.apk')
        assert (opt['fromVersionCode'],opt['toVersionCode'])==(44,45)
        assert opt['knownSizeCopy']==opt['unknownSizeCopy'] and opt['knownSizeCopy']['bytes']==8*1024*1024
        journal=opt['syntheticJournal'];assert journal['schema']==3 and len(journal['jobs'])==8
        assert journal['verificationDoesNotDoubleCountCopiedBytes'] and journal['cancelledReviewsCreateNoJobs']
        assert {r['case'] for r in journal['jobs']}=={'known','unknown','nested','readback','corrupt','unreadable','cancel','replace'}
        assert opt['failedReplacementRestoredOriginal']['matchesExpectedSyntheticBytes']
        expected_jobs={
            'known':('COMPLETED',8*1024*1024,1,'VERIFIED',1),
            'unknown':('COMPLETED',8*1024*1024,1,'VERIFIED',1),
            'nested':('COMPLETED',3*1024*1024,5,'VERIFIED',2),
            'readback':('COMPLETED',8*1024*1024,1,'VERIFIED',1),
            'corrupt':('FAILED',8*1024*1024,1,'MISMATCH',1),
            'unreadable':('FAILED',8*1024*1024,1,'UNREADABLE',1),
            'cancel':('CANCELLED',8*1024*1024,1,'INCOMPLETE',1),
            'replace':('FAILED',8*1024*1024+opt['failedReplacementRestoredOriginal']['bytes'],3,'MISMATCH',1),
        }
        for row in journal['jobs']:
            state,size,items,status,count=expected_jobs[row['case']]
            assert row['state']==state and row['copiedBytes']==size and row['processedItems']==items and row['verifyCopies']
            assert len(row['results'])==count and all(r['status']==status for r in row['results'])
            for result in row['results']:
                assert result['expected'] and re.fullmatch('[0-9a-f]{64}',result['expected'])
                if status=='VERIFIED':assert result['destination'] and result['actual']==result['expected']
                else:
                    assert result['destination'] is None
                    if status=='MISMATCH':assert result['actual'] and result['actual']!=result['expected']
                    else:assert result['actual'] is None
        matrix={(w,s) for w in (600,800,1280) for s in (1.0,1.6)}
        for kind,key in (('review','reviewLayouts'),('paused','pausedLayouts'),('completed','completedLayouts')):
            assert {(r['widthDp'],float(r['fontScale'])) for r in opt[key]}==matrix
            for row in opt[key]:
                name=f'{kind}-{row["widthDp"]}dp-font{row["fontScale"]}'
                tree=ET.parse(folder/(name+'-capture.xml')).getroot()
                width,height=struct.unpack('>II',(folder/(name+'.png')).read_bytes()[16:24]);minimum=48*width/row['widthDp']
                if kind=='review':
                    assert all(row[k] for k in ('sourceAndDestinationReachable','limitReachable','namedFooterControlsComplete48dp'))
                    for label in ('Cancel','Copy and verify'):full_named_control(tree,tree,label,width,height,minimum)
                    assert any('10,000' in n.get('text','') for n in tree.iter('node'))
                    for suffix,expected_text in (('source-path','Transfer sample.bin'),('destination-path','Findroid verification demo')):
                        reached=ET.parse(folder/(name+'-'+suffix+'.xml')).getroot()
                        complete_review_text(reached,expected_text,width,height)
                else:
                    assert row['namedControlsComplete48dp'] and row['verificationSummaryVisible'] and row['friendlySourceFilenameVisible']
                    card=latest_card(tree);summary='\n'.join(n.get('text','') for n in card.iter('node'))
                    assert any(n.get('text')=='Transfer sample.bin' for n in card.iter('node'))
                    assert ('Paused ·' if kind=='paused' else 'Completed ·') in summary
                    assert ('Copy verification · 0 verified and copied · 1 not verified' if kind=='paused' else 'Copy verification · 1 verified and copied') in summary
                    full_named_control(tree,tree,'Close transfers',width,height,minimum)
                    for label in (('Resume','Cancel transfer','Details','Open folder') if kind=='paused' else ('Details','Open folder')):
                        full_named_control(tree,card,label,width,height,minimum)
        for case,expected_text in (('known','Verified and copied'),('readback','Copy SHA-256:'),('corrupt','Contents did not match'),
            ('unreadable','Could not read the copy'),('cancel','Verification incomplete'),('replace','Contents did not match')):
            detail=folder/(case+'-details');w,h=struct.unpack('>II',detail.with_suffix('.png').read_bytes()[16:24])
            complete_transfer_detail_text(ET.parse(folder/(case+'-details-capture.xml')).getroot(),expected_text,w,h)
        files += [folder/'verification.json',folder/'inspection-retries.json']+list(folder.glob('*.xml'))+reviewed(folder)
    assert identities[0]==identities[1]
    native_visual=read(PROOF/'native-visual-review.json')
    assert native_visual['actualScreenshotsInspected']
    native_images={str(p.relative_to(PROOF)) for api in (30,36) for p in (PROOF/f'api{api}/screenshots').glob('*.png')}
    assert {row['path'] for row in native_visual['images']}==native_images
    for row in native_visual['images']:
        path=PROOF/row['path'];assert path.is_relative_to(PROOF) and row['actualScreenshotInspected'] and sha(path)==row['sha256']
    restored=read(PROOF/'native-state-restoration.json')
    assert {r['serial'] for r in restored}=={'emulator-5602','emulator-5582'}
    assert all(r['originalManagedMetadataDatabasesAndCacheRestored'] and r['originalFileAbsencesRestored'] and r['privateOriginalSnapshotUnchanged'] for r in restored)
    safety=read(PROOF/'safety-review.json')
    for name,digest in safety['reviewedSources'].items():assert sha(ROOT/name)==digest
    assert safety['strictPrimaryPaneGetterUnchanged'] and safety['noCheckpointCallbackAfterAtomicPublication']
    fixture=read(PROOF/'provider/BUILDINFO.json')
    assert fixture['sourceSha256']==sha(ROOT/'scripts/android/VerificationDocumentsProvider.java')
    assert all(fixture[k] for k in ('noNetworkPermission','syntheticSourcesReadOnly','writesOnlyOwnedDestination','separateFromShippingApk'))
    files += [PROOF/name for name in ('source-hashes.json','native-classes.txt','build-summary.json','app-revised-build.txt','superseded-candidate.json','api36-interrupted-attempt-recovery.json',
        'native-state-restoration.json','native-visual-review.json','safety-review.json','provider/BUILDINFO.json','rejected-attempts.json')]
    rejected=read(PROOF/'rejected-attempts.json');assert rejected['rejectedAttemptsExcludedFromAcceptance']
    for row in rejected['files']:
        path=ROOT/row['path'];assert path.is_relative_to(PROOF) and sha(path)==row['sha256'];files.append(path)
    return files,426


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--local-only',action='store_true');p.add_argument('--list-files',action='store_true');p.add_argument('--output',type=Path)
    a=p.parse_args();expected=sha(ROOT/'app/build/outputs/apk/preview/app-preview.apk')
    files,native_count=local(expected)
    summary=dict(localAcceptancePassed=True,previewApkSha256=expected,jvmCases=549,nativeCasesPerApi=native_count,isolatedBatchesPerApi=48)
    (PROOF/'local-acceptance.json').write_text(json.dumps(summary,indent=2)+'\n')
    if a.local_only:print(json.dumps(summary,indent=2));return
    if not a.list_files:
        assert a.output is not None and not a.output.exists(), 'An immutable evidence archive is never overwritten'
        assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip(), 'Commit the exact evidence snapshot first'
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip();info=read(RELEASE/'BUILDINFO.json')
    assert info['version']=='0.43.0-preview' and int(info['version_code'])==45 and not info['working_tree_dirty']
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
        assert (ota['fromVersionCode'],ota['toVersionCode'])==(44,45) and ota['restoredApkSha256']==expected
        assert all(ota[key] for key in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes','installerCompletionConfirmedBeforeRelaunch',
            'originalFileBytes','legacyFavoritePreserved','sentinelPreferencePreserved','updatedSidebarVisible','noRepeatOffer','restoredOriginalSetup'))
        files += [folder/'verification.json']+list(folder.glob('*.png'))+list(folder.glob('*.xml'))
    public=read(PROOF/'public-downloads.json')
    assert public['public'] and public['sourceCommit']==info['source_commit'] and len(public['assets'])==5
    assert all(row['publicDownloadMatchesLocal'] and row['githubDigestMatches'] for row in public['assets'])
    live=read(PROOF/'api36-live-feed.json');successful(live)
    assert live['passed']==1 and live['downloadedVersionCode']==45 and live['downloadedApkSha256']==expected
    restored=read(PROOF/'restored-public-tablets.json')
    assert len(restored)==2 and all(row['originalManagedMetadataDatabasesAndCacheRestored'] and row['originalFileAbsencesRestored'] and row['installedPublicApkSha256']==expected for row in restored)
    ota_review=read(PROOF/'ota-visual-review.json');assert ota_review['actualScreenshotsInspected']
    actual_ota_images={str(path.relative_to(PROOF)) for api in (30,36) for path in (PROOF/f'api{api}-ota').glob('*.png')}
    assert {row['path'] for row in ota_review['images']}==actual_ota_images
    for row in ota_review['images']:
        path=PROOF/row['path'];assert path.is_relative_to(PROOF) and sha(path)==row['sha256']
    assert sha(ROOT/'docs/screenshots/app-updates-0430.png')==sha(PROOF/'api30-ota/up-to-date.png')
    screenshots=read(PROOF/'docs-screenshot-provenance.json')
    assert screenshots['actualOptimizedScreenshots'] and len(screenshots['images'])==len(IMAGES)
    assert {row['documentPath'] for row in screenshots['images']}=={'docs/screenshots/'+name+'.png' for name in IMAGES}
    for row in screenshots['images']:
        capture=ROOT/row['capturePath'];document=ROOT/row['documentPath']
        assert capture.is_relative_to(PROOF) and sha(capture)==sha(document)==row['sha256']
    assert read(PROOF/'signing-continuity.json')['sameSigningCertificate']
    for name in ('public-downloads.json','api36-live-feed.json','api36-live-feed.txt','restored-public-tablets.json','signing-continuity.json',
        'local-acceptance.json','ota-visual-review.json','verify-public.py','run-live-feed.py','restore-public-tablets.py','docs-screenshot-provenance.json'):
        files.append(PROOF/name)
    privacy_path=PROOF/'public-privacy-review.json'
    if privacy_path.exists():
        privacy=read(privacy_path)
        assert privacy['explicitAllowlistChecked'] and privacy['privateRecoveryFilesExcluded'] and privacy['literalIdentifierMatches']==0
        assert privacy['originalLocationPathAndUniqueLabelCount']>0
        files.append(privacy_path)
    else:assert a.list_files, 'Inspect the explicit publication allowlist against private original identifiers first'
    files=sorted(set(files))
    for path in files:
        assert path.is_file() and path.is_relative_to(ROOT),path
        assert path.suffix not in {'.apk','.db','.sqlite','.jks','.keystore','.bin','.tar'}
        assert not any(part in {'native-original','accepted-build','private-native-state','private-native-screenshots'} or 'PRIVATE' in part for part in path.parts)
        assert 'bugreport' not in path.name.lower()
    if a.list_files:print(json.dumps([str(path.relative_to(ROOT)) for path in files],indent=2));return
    native_commit=subprocess.check_output(['git','log','-1','--format=%H',info['source_commit'],'--','app/src/androidTest'],cwd=ROOT,text=True).strip()
    manifest=dict(applicationSourceCommit=info['source_commit'],nativeTestSourceCommit=native_commit,evidenceCommit=commit,previewApkSha256=expected,
        jvmCases=549,nativeCasesPerApi=native_count,isolatedBatchesPerApi=48,
        files=[dict(path=str(path.relative_to(ROOT)),bytes=path.stat().st_size,sha256=sha(path)) for path in files])
    a.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(a.output,'x',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('EVIDENCE.json',json.dumps(manifest,indent=2)+'\n')
        for path in files:archive.write(path,path.relative_to(ROOT))
    receipt=dict(archive=a.output.name,bytes=a.output.stat().st_size,sha256=sha(a.output),entries=len(files)+1,
        **{key:manifest[key] for key in ('applicationSourceCommit','nativeTestSourceCommit','evidenceCommit','jvmCases','nativeCasesPerApi','isolatedBatchesPerApi')})
    (PROOF/'validation-package.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))


if __name__=='__main__':main()
