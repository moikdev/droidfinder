#!/usr/bin/env python3
"""Verify and freeze the explicit public allowlist for the 0.44 recovery acceptance."""
import argparse
import hashlib
import json
import re
import struct
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
PROOF=ROOT/'captures/partial-copy-resume'
RELEASE=ROOT/'captures/release/0.44.0'
SERIALS=(('api30','emulator-5602'),('api36','emulator-5582'))
DOCS=('README.md','VALIDATION.md','PARITY.md','docs/PARTIAL-COPY-RESUME.md','docs/TRANSFER-CENTER.md',
      'docs/APP-UPDATES.md','docs/IMPROVEMENT-TRACKER.md','docs/NEXT-IMPROVEMENTS.md','docs/releases/0.44.0.md','docs/releases/0.44.0-update.txt')
SCRIPTS=('scripts/check-partial-copy-resume.py','scripts/package-partial-copy-validation.py',
         'scripts/check-local-search-index.py','scripts/check-device.py','scripts/check-device-batches.py',
         'scripts/check-ota-upgrade.py','scripts/package-preview.py','scripts/verify-release-validation.py',
         'scripts/android/acceptance.py','scripts/android/NativeTap.java',
         'captures/partial-copy-resume/private-native-state.py','captures/partial-copy-resume/native-reset.py','captures/partial-copy-resume/after-native.py',
         'captures/partial-copy-resume/check-public-privacy.py',
         'captures/partial-copy-resume/verify-public.py','captures/partial-copy-resume/restore-public-tablets.py',
         'captures/partial-copy-resume/check-live-feed.py')
OPT_REQUIRED=('success','operationsUseExactOptimizedApk','originalEnvironmentPersistedBeforeMutation',
    'directPreviousPublicUpgrade','actualOptimizedProcessDeath','privateStageOutsideWorkspace','originalSourceBytesVerified',
    'restartAndCancelledReviewStartNoCopy','manualResumePublishesExactBytes','reusedBytesSeparateFromNewWrites',
    'readbackVerificationPreserved','oldHistoryRetained','ownedPrivateStageRemovedAfterPublication',
    'unrelatedDestinationKept','legacyFavoritePreserved','sentinelPreferencePreserved','unrelatedUriGrantsUnchanged',
    'restoredOriginalUriGrants','restoredOriginalManagedFiles','restoredOriginalFileAbsences','syntheticPublicFixtureRemoved',
    'restoredOriginalStorageAccess')


def read(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
def successful(report):
    assert report['success'] and report['runnerCompleted'] and report['runnerSucceeded']
    assert report['failed']==report['skipped']==0 and all(row['code']==0 for row in report['cases'])
def reviewed(name,actual):
    report=read(PROOF/name)
    assert report['actualScreenshotsInspected']
    assert {row['path'] for row in report['images']}=={str(path.relative_to(PROOF)) for path in actual}
    for row in report['images']:
        path=PROOF/row['path'];assert path.is_relative_to(PROOF) and sha(path)==row['sha256']
    return [PROOF/name]

def check_layout_captures(folder, layouts):
    labels=('Resume saved copy','Discard saved bytes…','Check again','Open destination folder','Close')
    expected={name+'.png' for name in ('previous-public-sidebar','upgraded-sidebar','interrupted-after-reopen','resume-ready','resumed-completed')}
    count=0
    for layout in layouts:
        base=f"review-{layout['widthDp']}x{layout['heightDp']}-font{layout['fontScale']}-"+('dark' if layout['dark'] else 'light')
        for label in labels:
            image=folder/(base+'-'+label.split()[0]+'.png');expected.add(image.name)
            data=image.read_bytes();assert data[:8]==b'\x89PNG\r\n\x1a\n'
            width,height=struct.unpack('>II',data[16:24]);density=width/layout['widthDp']
            tree=ET.parse(image.with_name(image.stem+'-capture.xml')).getroot()
            scopes=[w for w in tree.iter('window') if w.get('type')=='TYPE_APPLICATION' and w.get('active')=='true' and w.get('focused')=='true' and any(n.get('package')=='dev.findroid' for n in w.iter('node'))]
            assert len(scopes)==1;scope=scopes[0];parents={c:p for p in scope.iter() for c in p};controls=[]
            for node in scope.iter('node'):
                if node.get('text')!=label and node.get('content-desc')!=label:continue
                while node.get('clickable')!='true' and node in parents:node=parents[node]
                if node.get('clickable')=='true' and node not in controls:controls.append(node)
            assert len(controls)==1 and controls[0].get('enabled')=='true',(image.name,label)
            node=controls[0];l,t,r,b=map(int,re.findall(r'\d+',node.get('bounds')))
            assert 0<=l<r<=width and 0<=t<b<=height and r-l>=48*density and b-t>=48*density,(image.name,'48 dp control')
            while node in parents:
                node=parents[node]
                if node.get('scrollable')=='true':
                    pl,pt,pr,pb=map(int,re.findall(r'\d+',node.get('bounds')))
                    assert pl<=l<r<=pr and pt<=t<b<=pb,(image.name,'clipped control')
            count+=1
    assert {path.name for path in folder.glob('*.png')}==expected
    assert count==90
    return count


def local():
    files=[];totals=dict(tests=0,failures=0,errors=0,skipped=0)
    for path in sorted((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
        root=ET.parse(path).getroot()
        for key in totals:totals[key]+=int(root.get(key,'0'))
        files.append(path)
    assert totals==dict(tests=613,failures=0,errors=0,skipped=0)
    lint=ROOT/'app/build/reports/lint-results-debug.xml';assert not any(row.get('severity')=='Error' for row in ET.parse(lint).getroot().findall('issue'))
    files += [lint,ROOT/'app/build/reports/lint-results-debug.html']
    pair={str(p.relative_to(ROOT)):sha(p) for p in (ROOT/'app/build/outputs/apk/debug/app-debug.apk',ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')}
    expected=sha(ROOT/'app/build/outputs/apk/preview/app-preview.apk');sets=[];screens=[]
    for api,serial in SERIALS:
        native=read(PROOF/(api+'-regression.json'));successful(native)
        assert native['serial']==serial and native['passed']==455 and len(native['isolatedBatches'])==50
        assert native['apkSha256']==native['installedApkSha256']==pair
        cases={(r['class'],r['test']) for r in native['cases']};assert len(cases)==455;sets.append(cases)
        files += [PROOF/(api+'-regression.json'),PROOF/(api+'-regression.txt'),PROOF/(api+'-final-native.json'),PROOF/(api+'-final-native.txt'),PROOF/(api+'-native-batches/contract.json')]
        for batch in native['isolatedBatches']:
            for pathkey,hashkey in (('report','reportSha256'),('log','logSha256')):
                path=ROOT/batch[pathkey];assert path.is_relative_to(PROOF/(api+'-native-batches')) and sha(path)==batch[hashkey];files.append(path)
        focused=read(PROOF/(api+'-focused.json'));successful(focused);assert focused['passed']==39
        files += [PROOF/(api+'-focused.json'),PROOF/(api+'-focused.txt')]
        images=list((PROOF/(api+'-native-images')).glob('*.png'));assert len(images)==4;files+=images;screens+=images
        opt_dir=PROOF/(api+'-optimized');opt=read(opt_dir/'verification.json')
        assert opt['serial']==serial and all(opt[key] for key in OPT_REQUIRED) and opt['cleanupErrors']==[]
        assert opt['apkSha256']==opt['restoredApkSha256']==expected and opt['checkerSha256']==sha(ROOT/'scripts/check-partial-copy-resume.py')
        assert (opt['fromVersionCode'],opt['toVersionCode'])==(45,46) and 0<opt['durableBytesBeforeDeath']<opt['sourceBytes']
        layouts=opt['layouts'];assert len(layouts)==18
        assert {(r['widthDp'],r['heightDp'],r['fontScale'],r['dark']) for r in layouts}=={(w,h,s,d) for w,h in ((1280,800),(800,1280),(600,800)) for s in (1.0,1.5,2.0) for d in (False,True)}
        assert all(r['allNamedControlsReachable'] for r in layouts)
        check_layout_captures(opt_dir,layouts)
        captures=list(opt_dir.glob('*.png'));assert captures;screens+=captures
        files += [path for path in opt_dir.iterdir() if path.is_file() and path.suffix in ('.png','.xml','.json')]
        for name in ('actual-process-death','after-cancelled-review','after-layouts','completed-resume'):
            assert (opt_dir/(name+'-journal.json')).is_file()
        env=read(PROOF/(api+'-native-environment.json'));assert env['originalEnvironmentMatchesPersistedBaseline'] and env['originalManagedBytesRestoredBeforeOptimizedAcceptance']
        files.append(PROOF/(api+'-native-environment.json'))
        cleanup=read(PROOF/(api+'-owned-capture-cleanup.json'));assert cleanup['serial']==serial and cleanup['nativeApkPair']==pair
        assert cleanup['fullScreenshotsExcludedFromPublication'] and len(cleanup['files'])==3
        assert all(r['retainedPrivately'] and r['removedOnlyOwnedSharedCapture'] for r in cleanup['files'])
        files.append(PROOF/(api+'-owned-capture-cleanup.json'))
    assert sets[0]==sets[1]
    files+=reviewed('visual-review.json',screens)
    for kind in ('preview','release'):
        report=read(PROOF/(kind+'-native-libraries.json'));assert report['allLoadSegmentsAlignedAtLeast16384'] and len(report['libraries'])==4
        files.append(PROOF/(kind+'-native-libraries.json'))
    return files,expected


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--local-only',action='store_true');p.add_argument('--list-files',action='store_true');p.add_argument('--output',type=Path);a=p.parse_args()
    files,expected=local()
    summary=dict(localAcceptancePassed=True,previewApkSha256=expected,jvmCases=613,nativeCasesPerApi=455,isolatedBatchesPerApi=50,focusedCasesPerApi=39,namedRecoveryControlsPerApi=90)
    (PROOF/'local-acceptance.json').write_text(json.dumps(summary,indent=2)+'\n')
    if a.local_only:print(json.dumps(summary,indent=2));return
    info=read(RELEASE/'BUILDINFO.json');assert info['version']=='0.44.0-preview' and int(info['version_code'])==46 and not info['working_tree_dirty']
    assert info['artifacts'][0]['sha256']==expected
    for row in info['artifacts']:assert sha(RELEASE/row['name'])==row['sha256']
    source=[]
    for path in (ROOT/'app/src').rglob('*'):
        if path.is_file() and (path.suffix in ('.kt','.java','.xml','.c') or path.name=='CMakeLists.txt'):source.append(path)
    source += [ROOT/name for name in ('app/build.gradle.kts','app/proguard-rules.pro','build.gradle.kts','settings.gradle.kts','gradle.properties','.gitignore')]
    for path in source:assert git('show',info['source_commit']+':'+str(path.relative_to(ROOT)))==path.read_bytes()
    files += source+[ROOT/name for name in DOCS+SCRIPTS]
    files += [RELEASE/name for name in ('BUILDINFO.json','SHA256SUMS','findroid-update.json')]
    ota_images=[]
    for api,serial in SERIALS:
        folder=PROOF/(api+'-ota');ota=read(folder/'verification.json')
        assert ota['serial']==serial and (ota['fromVersionCode'],ota['toVersionCode'])==(45,46) and ota['restoredApkSha256']==expected
        assert ota['checkerSha256']==sha(ROOT/'scripts/check-ota-upgrade.py')
        assert ota['freshAndroidHierarchies'] and ota['verifiedUpdateDialogOpened']
        assert all(ota[key] for key in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes','installerCompletionConfirmedBeforeRelaunch','originalFileBytes','legacyFavoritePreserved','sentinelPreferencePreserved','updatedSidebarVisible','noRepeatOffer','restoredOriginalSetup'))
        images=list(folder.glob('*.png'));ota_images+=images
        files += [folder/'verification.json']+images+list(folder.glob('*.xml'))
    files += reviewed('ota-visual-review.json',ota_images)
    public=read(PROOF/'public-downloads.json');assert public['public'] and public['sourceCommit']==info['source_commit'] and len(public['assets'])==5
    assert all(r['publicDownloadMatchesLocal'] and r['githubDigestMatches'] for r in public['assets'])
    live=read(PROOF/'api36-live-feed.json');successful(live);assert live['passed']==1 and live['downloadedVersionCode']==46 and live['downloadedApkSha256']==expected
    restored=read(PROOF/'restored-public-tablets.json');assert len(restored)==2
    assert all(r['originalManagedMetadataDatabasesAndCacheRestored'] and r['originalFileAbsencesRestored'] and r['installedPublicApkSha256']==expected and r['originalEnvironmentMatchesPersistedBaseline'] for r in restored)
    provenance=read(PROOF/'docs-screenshot-provenance.json');assert provenance['actualOptimizedScreenshots']
    for row in provenance['images']:
        capture=ROOT/row['capturePath'];document=ROOT/row['documentPath']
        assert capture.is_relative_to(PROOF) and sha(capture)==sha(document)==row['sha256'];files.append(document)
    assert read(PROOF/'signing-continuity.json')['sameSigningCertificate']
    for name in ('public-downloads.json','api36-live-feed.json','api36-live-feed.txt','restored-public-tablets.json','docs-screenshot-provenance.json','signing-continuity.json','public-privacy-review.json','local-acceptance.json'):
        files.append(PROOF/name)
    files=sorted(set(files));relative=[str(path.relative_to(ROOT)) for path in files]
    for path in files:
        assert path.is_file() and not any('PRIVATE' in part or 'private-native-screenshots' in part or part in ('private-native-state','candidate-build','accepted-build','private-native-screenshots') for part in path.parts)
        assert path.suffix not in ('.apk','.tar','.db','.sqlite','.keystore','.jks','.bin')
    if a.list_files:print(json.dumps(relative,indent=2));return
    assert a.output is not None and not a.output.exists(), 'Never overwrite an immutable archive'
    assert not git('status','--porcelain').strip(), 'Commit the exact source and evidence snapshot first'
    commit=git('rev-parse','HEAD').decode().strip()
    tracked=set(git('ls-files').decode().splitlines());assert set(relative)<=tracked
    manifest=dict(applicationSourceCommit=info['source_commit'],nativeTestSourceCommit=info['source_commit'],evidenceCommit=commit,
        previewApkSha256=expected,jvmCases=613,nativeCasesPerApi=455,isolatedBatchesPerApi=50,
        files=[dict(path=str(path.relative_to(ROOT)),bytes=path.stat().st_size,sha256=sha(path)) for path in files])
    with zipfile.ZipFile(a.output,'x',zipfile.ZIP_DEFLATED) as archive:
        for path in files:archive.write(path,str(path.relative_to(ROOT)))
        archive.writestr('EVIDENCE.json',json.dumps(manifest,indent=2)+'\n')
    receipt=dict(archive=a.output.name,bytes=a.output.stat().st_size,sha256=sha(a.output),entries=len(files)+1,
        **{key:manifest[key] for key in ('applicationSourceCommit','nativeTestSourceCommit','evidenceCommit','jvmCases','nativeCasesPerApi','isolatedBatchesPerApi')})
    (PROOF/'validation-package.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))


if __name__=='__main__':main()
