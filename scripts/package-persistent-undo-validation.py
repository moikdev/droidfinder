#!/usr/bin/env python3
"""Verify and freeze the explicit public allowlist for the 0.45 recovery acceptance."""
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
PROOF=ROOT/'captures/persistent-undo'
RELEASE=ROOT/'captures/release/0.45.0'
SERIALS=(('api30','emulator-5602'),('api36','emulator-5582'))
DOCS=('README.md','VALIDATION.md','PARITY.md','docs/PERSISTENT-UNDO.md','docs/COMMANDS-AND-UNDO.md','docs/TRANSFER-CENTER.md',
      'docs/APP-UPDATES.md','docs/IMPROVEMENT-TRACKER.md','docs/NEXT-IMPROVEMENTS.md','docs/releases/0.45.0.md','docs/releases/0.45.0-update.txt')
SCRIPTS=('scripts/check-persistent-undo.py','scripts/package-persistent-undo-validation.py',
         'scripts/check-local-search-index.py','scripts/check-device.py','scripts/check-device-batches.py',
         'scripts/check-ota-upgrade.py','scripts/package-preview.py','scripts/verify-release-validation.py',
         'scripts/android/acceptance.py','scripts/android/NativeTap.java',
         'captures/persistent-undo/private-native-state.py','captures/persistent-undo/native-reset.py','captures/persistent-undo/after-native.py',
         'captures/persistent-undo/check-public-privacy.py',
         'captures/persistent-undo/verify-public.py','captures/persistent-undo/restore-public-tablets.py',
         'captures/persistent-undo/check-live-feed.py','captures/persistent-undo/run-final-acceptance.py','captures/persistent-undo/check-build-artifacts.py','captures/persistent-undo/derive-focused.py')
OPT_REQUIRED=('success','operationsUseExactOptimizedApk','freshAndroidHierarchies','originalEnvironmentPersistedBeforeMutation',
    'directPreviousPublicUpgrade','actualOptimizedProcessDeath','restartStartsNoAction','copyRenameTrashSaved',
    'coldProcessUndoAndRedoAllThreeActions','originalSourceBytesVerified','occupiedDestinationKeptAndJournalUnchanged',
    'corruptHistoryReadOnly','cancelledResetKeepsExactJournal','manualResetPreservesRecordAndDocuments',
    'legacyFavoritePreserved','sentinelPreferencePreserved','unrelatedUriGrantsUnchanged',
    'restoredOriginalUriGrants','restoredOriginalManagedFiles','restoredOriginalFileAbsences',
    'restoredOriginalDirectoryAbsences','restoredOriginalStorageAccess')

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
    labels={'Undo':'Undo Move to Trash','Reset':'Reset Undo list…','Close':'Close',
            'Keep':'Keep Undo list','Confirm':'Reset Undo list'}
    expected={name+'.png' for name in ('previous-public-sidebar','upgraded-sidebar','copy-saved','rename-saved',
        'trash-saved','reopened-saved','occupied-destination','damaged-review','reset-completed')}
    count=0
    for layout in layouts:
        base=f"review-{layout['widthDp']}x{layout['heightDp']}-font{layout['fontScale']}-"+('dark' if layout['dark'] else 'light')
        assert {(p['image'],p['label']) for p in layout['controls']}=={(base+'-'+key+'.png',label) for key,label in labels.items()}
        for key,label in labels.items():
            image=folder/(base+'-'+key+'.png');expected.add(image.name)
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
    frozen=read(PROOF/'candidate-build-5.json')
    assert (frozen['version'],frozen['versionCode'])==('0.45.0-preview',47)
    assert frozen['apkMetadata']['preview']=={'versionName':'0.45.0-preview','versionCode':47}
    assert frozen['apkMetadata']['release']=={'versionName':'0.45.0','versionCode':47}
    for name,row in frozen['files'].items():assert sha(ROOT/name)==row['sha256']
    for name,digest in frozen['sourceFiles'].items():assert sha(ROOT/name)==digest
    files=[PROOF/'candidate-build-5.json'];totals=dict(tests=0,failures=0,errors=0,skipped=0)
    for path in sorted((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
        root=ET.parse(path).getroot()
        for key in totals:totals[key]+=int(root.get(key,'0'))
        files.append(path)
    assert totals==dict(tests=660,failures=0,errors=0,skipped=0)
    lint=ROOT/'app/build/reports/lint-results-debug.xml';assert not any(row.get('severity')=='Error' for row in ET.parse(lint).getroot().findall('issue'))
    files += [lint,ROOT/'app/build/reports/lint-results-debug.html']
    pair={str(p.relative_to(ROOT)):sha(p) for p in (ROOT/'app/build/outputs/apk/debug/app-debug.apk',ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')}
    expected=sha(ROOT/'app/build/outputs/apk/preview/app-preview.apk');sets=[];screens=[]
    for api,serial in SERIALS:
        native=read(PROOF/(api+'-regression.json'));successful(native)
        assert native['serial']==serial and native['passed']==485 and len(native['isolatedBatches'])==52
        assert native['apkSha256']==native['installedApkSha256']==pair
        cases={(r['class'],r['test']) for r in native['cases']};assert len(cases)==485;sets.append(cases)
        files += [PROOF/(api+'-regression.json'),PROOF/(api+'-regression.txt'),PROOF/(api+'-final-native.json'),PROOF/(api+'-final-native.txt'),PROOF/(api+'-native-batches/contract.json')]
        for batch in native['isolatedBatches']:
            for pathkey,hashkey in (('report','reportSha256'),('log','logSha256')):
                path=ROOT/batch[pathkey];assert path.is_relative_to(PROOF/(api+'-native-batches')) and sha(path)==batch[hashkey];files.append(path)
        focused=read(PROOF/(api+'-focused.json'));successful(focused);assert focused['passed']==46
        assert focused['derivedFromFullNativeAcceptance'] and focused['fullAcceptanceSha256']==sha(PROOF/(api+'-regression.json'))
        assert {(c['class'],c['test']) for c in focused['cases']}=={c for c in cases if c[0] in {'dev.findroid.PersistentUndoNativeTest','dev.findroid.PersistentUndoWorkflowTest','dev.findroid.CommandClarityWorkflowTest'}}
        files += [PROOF/(api+'-focused.json'),PROOF/(api+'-focused.txt')]
        image_dir=PROOF/(api+'-native-images')
        images=list(image_dir.glob('*.png'));assert {p.name for p in images}=={'saved.png','reset-review.png','changed-file.png','interrupted-review.png','session-boundary.png','large-text-reset.png'}
        provenance=read(image_dir/'capture-provenance.json');assert provenance['serial']==serial
        assert provenance['installedApkSha256']=={'dev.findroid':pair['app/build/outputs/apk/debug/app-debug.apk'],'dev.findroid.test':pair['app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']}
        assert {r['path'] for r in provenance['images']}=={p.name for p in images}
        for row in provenance['images']:assert sha(image_dir/row['path'])==row['sha256']
        files+=images+[image_dir/'capture-provenance.json'];screens+=images
        opt_dir=PROOF/(api+'-optimized');opt=read(opt_dir/'verification.json')
        assert opt['serial']==serial and all(opt[key] for key in OPT_REQUIRED) and opt['cleanupErrors']==[]
        assert opt['apkSha256']==opt['restoredApkSha256']==expected and opt['checkerSha256']==sha(ROOT/'scripts/check-persistent-undo.py')
        assert (opt['fromVersionCode'],opt['toVersionCode'])==(46,47) and not opt['controllerPreflightOnly']
        layouts=opt['layoutChecks'];assert len(layouts)==18
        assert {(r['widthDp'],r['heightDp'],r['fontScale'],r['dark']) for r in layouts}=={(w,h,s,d) for w,h in ((1280,800),(800,1280),(600,800)) for s in (1.0,1.5,2.0) for d in (False,True)}
        assert all(r['allNamedControlsReachable'] for r in layouts)
        check_layout_captures(opt_dir,layouts)
        captures=list(opt_dir.glob('*.png'));assert captures;screens+=captures
        files += [path for path in opt_dir.iterdir() if path.is_file() and path.suffix in ('.png','.xml','.json')]
        for name in ('completed','reopened-no-action','undo-after-0','undo-after-1','undo-after-2',
                     'redo-after-0','redo-after-1','redo-after-2','before-collision','after-collision'):
            assert (opt_dir/(name+'-history.json')).is_file()
        assert (opt_dir/'completed-history.json').read_bytes()==(opt_dir/'reopened-no-action-history.json').read_bytes()
        assert (opt_dir/'before-collision-history.json').read_bytes()==(opt_dir/'after-collision-history.json').read_bytes()
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
        assert report['apkSha256']==sha(ROOT/('app/build/outputs/apk/'+kind+'/app-'+kind+('-unsigned' if kind=='release' else '')+'.apk'))
        assert report['zipAlignmentPassed'] and report['zipAlignmentLogSha256']==sha(PROOF/(kind+'-zipalign.txt'))
        files += [PROOF/(kind+'-native-libraries.json'),PROOF/(kind+'-zipalign.txt')]
    signing=read(PROOF/'signing-continuity.json');assert signing['sameSigningCertificate']
    assert signing['artifacts'][1]['apkSha256']==expected
    for label,row in zip(('previous','preview'),signing['artifacts']):
        log=PROOF/(label+'-apksigner.txt');assert sha(log)==row['signatureLogSha256'];files.append(log)
    files.append(PROOF/'signing-continuity.json')
    return files,expected


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--local-only',action='store_true');p.add_argument('--list-files',action='store_true');p.add_argument('--output',type=Path);a=p.parse_args()
    files,expected=local()
    summary=dict(localAcceptancePassed=True,previewApkSha256=expected,jvmCases=660,nativeCasesPerApi=485,isolatedBatchesPerApi=52,focusedCasesPerApi=46,namedUndoControlsPerApi=90)
    (PROOF/'local-acceptance.json').write_text(json.dumps(summary,indent=2)+'\n')
    if a.local_only:print(json.dumps(summary,indent=2));return
    info=read(RELEASE/'BUILDINFO.json');assert info['version']=='0.45.0-preview' and int(info['version_code'])==47 and not info['working_tree_dirty']
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
        assert ota['serial']==serial and (ota['fromVersionCode'],ota['toVersionCode'])==(46,47) and ota['restoredApkSha256']==expected
        assert ota['checkerSha256']==sha(ROOT/'scripts/check-ota-upgrade.py')
        assert ota['freshAndroidHierarchies'] and ota['verifiedUpdateDialogOpened']
        assert all(ota[key] for key in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes','installerCompletionConfirmedBeforeRelaunch','originalFileBytes','legacyFavoritePreserved','sentinelPreferencePreserved','updatedSidebarVisible','noRepeatOffer','restoredOriginalSetup'))
        images=list(folder.glob('*.png'));ota_images+=images
        files += [folder/'verification.json']+images+list(folder.glob('*.xml'))
    files += reviewed('ota-visual-review.json',ota_images)
    public=read(PROOF/'public-downloads.json');assert public['public'] and public['sourceCommit']==info['source_commit'] and len(public['assets'])==5
    assert all(r['publicDownloadMatchesLocal'] and r['githubDigestMatches'] for r in public['assets'])
    live=read(PROOF/'api36-live-feed.json');successful(live);assert live['passed']==1 and live['downloadedVersionCode']==47 and live['downloadedApkSha256']==expected
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
        assert path.is_file() and not any('PRIVATE' in part or 'private-native-screenshots' in part or part in ('private-native-state','candidate-build','candidate-build-2','candidate-build-3','candidate-build-4','candidate-build-5','accepted-build','private-native-screenshots') for part in path.parts)
        assert path.suffix not in ('.apk','.tar','.db','.sqlite','.keystore','.jks','.bin')
    if a.list_files:print(json.dumps(relative,indent=2));return
    assert a.output is not None and not a.output.exists(), 'Never overwrite an immutable archive'
    assert not git('status','--porcelain').strip(), 'Commit the exact source and evidence snapshot first'
    commit=git('rev-parse','HEAD').decode().strip()
    tracked=set(git('ls-files').decode().splitlines());assert set(relative)<=tracked
    manifest=dict(applicationSourceCommit=info['source_commit'],nativeTestSourceCommit=info['source_commit'],evidenceCommit=commit,
        previewApkSha256=expected,jvmCases=660,nativeCasesPerApi=485,isolatedBatchesPerApi=52,
        files=[dict(path=str(path.relative_to(ROOT)),bytes=path.stat().st_size,sha256=sha(path)) for path in files])
    with zipfile.ZipFile(a.output,'x',zipfile.ZIP_DEFLATED) as archive:
        for path in files:archive.write(path,str(path.relative_to(ROOT)))
        archive.writestr('EVIDENCE.json',json.dumps(manifest,indent=2)+'\n')
    receipt=dict(archive=a.output.name,bytes=a.output.stat().st_size,sha256=sha(a.output),entries=len(files)+1,
        **{key:manifest[key] for key in ('applicationSourceCommit','nativeTestSourceCommit','evidenceCommit','jvmCases','nativeCasesPerApi','isolatedBatchesPerApi')})
    (PROOF/'validation-package.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))


if __name__=='__main__':main()
