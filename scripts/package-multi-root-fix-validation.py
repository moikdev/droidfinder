#!/usr/bin/env python3
"""Freeze accepted 0.38.1 evidence from explicit public-file allowlists; archives are immutable."""
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
PROOF=ROOT/'captures/multi-root-search-0381'
RELEASE=ROOT/'captures/release/0.38.1'
REQUIRED=('actual037Upgrade','installedOptimizedBytes','cancelPreservesScope','realFolderPicker','overlapDeduplicated',
          'literalExclusionsPreserved','unavailableFirstRootKeepsOtherResults','smartFolderScopeSurvivesRestart',
          'exactFullCriteriaPersisted','allRootsInHistory','noSavedResultBytes','originalFileBytes',
          'favoritePreserved','unrelatedPreferencePreserved','restoredOriginalSetup')
DOCS=('README.md','VALIDATION.md','PARITY.md','docs/SEARCH-LOCATIONS.md','docs/RECENT-SEARCHES.md','docs/SEARCH-CRITERIA.md',
      'docs/SETUP-BACKUP.md','docs/IMPROVEMENT-TRACKER.md','docs/NEXT-IMPROVEMENTS.md','docs/APP-UPDATES.md',
      'docs/releases/0.38.1.md','docs/releases/0.38.1-update.txt')
SCRIPTS=('scripts/check-device.py','scripts/check-device-batches.py','scripts/check-multi-root-searches.py',
         'scripts/check-ota-upgrade.py','scripts/package-preview.py','scripts/package-multi-root-fix-validation.py',
         'scripts/android/acceptance.py','scripts/android/NativeTap.java','captures/workspace-recovery/native-environment.py')
IMAGES=('search-locations-draft','search-locations-tablet','search-locations-unavailable','search-locations-large-text','app-updates-0381')

def read(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def accepted(report):
    assert report['success'] and report['failed']==report['skipped']==0 and report['runnerCompleted'] and report['runnerSucceeded']
def bounds(node):return list(map(int,re.findall(r'-?\d+',node.get('bounds',''))))
def full_control(tree,title,label,footer,width,height,minimum):
    parents={child:parent for parent in tree.iter() for child in parent}
    heads=[node for node in tree.iter('node') if node.get('text')==title and len(bounds(node))==4 and bounds(node)[3]>bounds(node)[1]]
    assert len(heads)==1,(title,len(heads))
    scope=heads[0]
    while not any(n.get('text')==footer or n.get('content-desc')==footer for n in scope.iter('node')):scope=parents[scope]
    for node in scope.iter('node'):
        if node.get('text')!=label and node.get('content-desc')!=label:continue
        while node.get('clickable')!='true' and node in parents:node=parents[node]
        box=bounds(node)
        if len(box)!=4:continue
        l,t,r,b=box
        if node.get('enabled')!='true' or not (0<=l<r<=width and 0<=t<b<=height and r-l>=minimum and b-t>=minimum):continue
        parent=node;clipped=False
        while parent in parents:
            parent=parents[parent]
            if parent.get('scrollable')=='true':
                pl,pt,pr,pb=bounds(parent)
                clipped|=not(pl<=l<r<=pr and pt<=t<b<=pb)
        if not clipped:return True
    return False

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    assert not args.output.exists(),'Never overwrite a frozen archive.'
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip(),'Commit accepted source and documentation first.'
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    info=read(RELEASE/'BUILDINFO.json');assert info['version']=='0.38.1-preview' and int(info['version_code'])==40 and not info['working_tree_dirty']
    for item in info['artifacts']:assert sha(RELEASE/item['name'])==item['sha256']
    expected=info['artifacts'][0]['sha256']
    for path in (ROOT/'app/src/main').rglob('*'):
        if path.is_file():assert subprocess.check_output(['git','show',info['source_commit']+':'+str(path.relative_to(ROOT))],cwd=ROOT)==path.read_bytes(),path
    assert subprocess.check_output(['git','show',info['source_commit']+':app/build.gradle.kts'],cwd=ROOT)==(ROOT/'app/build.gradle.kts').read_bytes()
    files=[ROOT/name for name in DOCS+SCRIPTS]
    files += [ROOT/'docs/screenshots'/(name+'.png') for name in IMAGES]
    files += list((ROOT/'app/src').rglob('*.kt'))+list((ROOT/'app/src/main').rglob('*.xml'))
    files += [ROOT/name for name in ('app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties')]
    files += [RELEASE/name for name in ('BUILDINFO.json','SHA256SUMS','findroid-update.json')]
    tests=list((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    totals={key:0 for key in ('tests','failures','errors','skipped')}
    for path in tests:
        suite=ET.parse(path).getroot()
        for key in totals:totals[key]+=int(suite.get(key,0))
    assert totals=={'tests':451,'failures':0,'errors':0,'skipped':0},totals
    lint=ROOT/'app/build/reports/lint-results-debug.xml';assert not any(n.get('severity') in ('Error','Fatal') for n in ET.parse(lint).getroot())
    files+=tests+[lint]
    source=read(PROOF/'native-source-hashes.json')
    for name,digest in source.items():assert sha(ROOT/name)==digest,name
    native_ids=[]
    for api in (30,36):
        report=read(PROOF/f'api{api}-native.json');accepted(report);assert report['passed']==308 and len(report['isolatedBatches'])==32
        assert report['apkSha256']==report['installedApkSha256']=={key:value for key,value in source.items() if key.endswith('.apk')}
        identities={(row['class'],row['test']) for row in report['cases']};assert len(identities)==308;native_ids.append(identities)
        for row in report['isolatedBatches']:
            for path_key,hash_key in (('report','reportSha256'),('log','logSha256')):
                path=ROOT/row[path_key];assert sha(path)==row[hash_key];files.append(path)
        files += [PROOF/f'api{api}-native.json',PROOF/f'api{api}-native.txt',PROOF/f'api{api}-batches/contract.json']
        folder=PROOF/f'api{api}-optimized';opt=read(folder/'verification.json')
        assert opt['apkSha256']==expected and all(opt[key] for key in REQUIRED)
        assert {(r['widthDp'],float(r['fontScale'])) for r in opt['layouts']}=={(w,s) for w in (600,800,1280) for s in (1.0,1.6)}
        for row in opt['layouts']:
            assert all(row[k] for k in ('footerVisible','fullTouchBounds','addFolderReachable','detailsVisible'))
            key=f'{row["widthDp"]}dp-font{row["fontScale"]}'
            png=folder/(key+'-scope.png');w,h=struct.unpack('>II',png.read_bytes()[16:24]);density=w/row['widthDp']
            tree=ET.parse(folder/(key+'-scope-capture.xml')).getroot()
            for label in ('Search','Cancel','Close Search locations','Add folder…'):
                assert full_control(tree,'Search locations',label,'Cancel',w,h,48*density),(api,key,label)
            png=folder/(key+'-details.png');w,h=struct.unpack('>II',png.read_bytes()[16:24])
            tree=ET.parse(folder/(key+'-details-capture.xml')).getroot()
            assert full_control(tree,'Result details','Close Result details','Close Result details',w,h,48*density)
        smart=read(folder/'synthetic-smart-folders.json');assert len(smart)==1
        roots=smart[0]['rules']['locations'];assert len(roots)==3 and smart[0]['root']==roots[0]
        assert roots[2]==roots[1]+'/Cache' and smart[0]['rules']['excludedLocations']==[{'path':roots[0]+'/Cache','folder':True}]
        history=read(folder/'synthetic-history.json');assert any(r['rules']==smart[0]['rules'] for r in history['searches'])
        files += [folder/name for name in ('verification.json','synthetic-smart-folders.json','synthetic-history.json')]+list(folder.glob('*.png'))+list(folder.glob('*.xml'))
        ota=PROOF/f'api{api}-ota';report=read(ota/'verification.json')
        assert report['fromVersionCode']==38 and report['toVersionCode']==40 and report['restoredApkSha256']==expected
        assert all(report[k] for k in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes','originalFileBytes','legacyFavoritePreserved','sentinelPreferencePreserved','updatedSidebarVisible','noRepeatOffer','restoredOriginalSetup'))
        files += [ota/'verification.json']+list(ota.glob('*.png'))+list(ota.glob('*.xml'))
    assert native_ids[0]==native_ids[1]
    public=read(PROOF/'public-downloads.json');assert public['public'] and public['sourceCommit']==info['source_commit']
    assert len(public['assets'])==5 and all(r['publicDownloadMatchesLocal'] and r['githubDigestMatches'] for r in public['assets'])
    live=read(PROOF/'api36-live-feed.json');accepted(live);assert live['passed']==1 and live['downloadedVersionCode']==40 and live['downloadedApkSha256']==expected
    restored=read(PROOF/'restored-public-tablets.json');assert len(restored)==2 and all(r['privateOriginalMetadataAndPreferencesRestored'] and r['installedPublicApkSha256']==expected and float(r['fontScale'])==1 for r in restored)
    signing=read(PROOF/'signing-continuity.json');assert signing['sameSigningCertificate']
    for name in ('native-source-hashes.json','native-classes.txt','final-debug-checks.txt','release-build.txt',
                 'public-downloads.json','api36-live-feed.json','api36-live-feed.txt','restored-public-tablets.json','signing-continuity.json','rejected-attempts.json',
                 'local-acceptance.json','verify-local.py','verify-public.py','verify-validation.py','run-live-feed.py',
                 'restore-public-tablets.py','write-release-body.py','finalize-docs.py'):
        files.append(PROOF/name)
    for api in (30,36):
        focused=read(PROOF/f'api{api}-window-focused.json');accepted(focused);assert focused['passed']==4
        for ext in ('txt','json'):files.append(PROOF/f'api{api}-window-focused.{ext}')
    withdrawn=ROOT/'captures/multi-root-search'
    retirement=read(withdrawn/'withdrawn-release.json');assert retirement['releaseWithdrawn'] and retirement['regressionReproducer']['correctedFixtureReproducesSameCrash']
    for name,digest in retirement['releaseArtifactsUnchanged'].items():assert sha(ROOT/'captures/release/0.38.0'/name)==digest
    files += [withdrawn/name for name in ('REJECTED.md','withdrawn-release.json','local-acceptance.json','native-source-hashes.json','public-downloads.json','rejected-attempts.json','api30-optimized-console-before-uiautomator-retry.txt')]
    for api in (30,36):
        files += [withdrawn/f'api{api}-native.json',withdrawn/f'api{api}-window-crash.txt',
                  withdrawn/f'api{api}-ota-console-before-window-fix.txt',withdrawn/f'api{api}-optimized/verification.json']
        folder=withdrawn/f'api{api}-ota-before-window-fix'
        files += [path for path in folder.iterdir() if path.is_file() and path.suffix in ('.png','.xml','.json')]
        for suffix in ('first','second','third'):
            for ext in ('txt','json'):files.append(withdrawn/f'api{api}-focused-{suffix}.{ext}')
    for name in ('api30-window-regression-before-fix','api30-window-regression-corrected-before-fix'):
        for ext in ('txt','json'):files.append(withdrawn/(name+'.'+ext))
        files.append(withdrawn/(name+'-console.txt'))
    rejected=read(PROOF/'rejected-attempts.json');assert rejected['rejectedAttemptsExcludedFromAcceptance']
    for attempt in rejected['optimizedAttempts']:
        assert attempt['countedTowardFinalAcceptance'] is False
        log=ROOT/attempt['console'];folder=ROOT/attempt['directory']
        assert log.parent==PROOF and re.fullmatch(r'api(30|36)-optimized-console-before-[a-z0-9-]+\.txt',log.name)
        assert folder.parent==PROOF and re.fullmatch(r'api(30|36)-optimized-before-[a-z0-9-]+',folder.name)
        files.append(log)
        files += [path for path in folder.iterdir() if path.is_file() and path.suffix in ('.png','.xml','.json')]
    for name,target in (('search-locations-draft','search-locations-draft'),('search-locations-tablet','search-locations-tablet'),
                        ('search-locations-unavailable','search-locations-unavailable'),('search-locations-large-text','600dp-font1.6-scope')):
        assert sha(ROOT/'docs/screenshots'/(name+'.png'))==sha(PROOF/'api36-optimized'/(target+'.png'))
    assert sha(ROOT/'docs/screenshots/app-updates-0381.png')==sha(PROOF/'api30-ota/up-to-date.png')
    files=sorted(set(files))
    for path in files:
        assert path.is_file() and path.resolve().is_relative_to(ROOT),path
        assert path.suffix not in ('.apk','.jks','.keystore','.bin') and 'native-original' not in path.parts and 'bugreport' not in path.name
    native_commit=subprocess.check_output(['git','log','-1','--format=%H','--','app/src/androidTest'],cwd=ROOT,text=True).strip()
    manifest={'applicationSourceCommit':info['source_commit'],'nativeTestSourceCommit':native_commit,'evidenceCommit':commit,
        'previewApkSha256':expected,'jvmCases':451,'nativeCasesPerApi':308,'isolatedBatchesPerApi':32,
        'files':[{'path':str(p.relative_to(ROOT)),'bytes':p.stat().st_size,'sha256':sha(p)} for p in files]}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.output,'x',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('EVIDENCE.json',json.dumps(manifest,indent=2)+'\n')
        for path in files:archive.write(path,path.relative_to(ROOT))
    report={'archive':args.output.name,'bytes':args.output.stat().st_size,'sha256':sha(args.output),'entries':len(files)+1,
        **{k:manifest[k] for k in ('applicationSourceCommit','nativeTestSourceCommit','evidenceCommit','jvmCases','nativeCasesPerApi','isolatedBatchesPerApi')}}
    (PROOF/'validation-package.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))

if __name__=='__main__':main()
