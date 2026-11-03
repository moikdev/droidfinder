#!/usr/bin/env python3
"""Freeze accepted 0.39.0 nested-search evidence from explicit public-file allowlists; archives are immutable."""
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
PROOF=ROOT/'captures/nested-search-rules'
RELEASE=ROOT/'captures/release/0.39.0'
REQUIRED=('actual0381Upgrade','actualNestedEditor','emptyGroupsCannotApply','nestedSmartFolderSurvivesRestart','nestedGroupsAndAllCriteriaPersisted','installedOptimizedBytes','cancelPreservesScope','realFolderPicker','overlapDeduplicated',
          'literalExclusionsPreserved','unavailableFirstRootKeepsOtherResults','smartFolderScopeSurvivesRestart',
          'exactFullCriteriaPersisted','allRootsInHistory','noSavedResultBytes','originalFileBytes',
          'favoritePreserved','unrelatedPreferencePreserved','restoredOriginalSetup','customWindowTaskReplacement','customWindowIndependentTabsPreserved')
DOCS=('README.md','VALIDATION.md','PARITY.md','docs/SEARCH-RULE-GROUPS.md','docs/SEARCH-LOCATIONS.md','docs/RECENT-SEARCHES.md','docs/SEARCH-CRITERIA.md',
      'docs/SETUP-BACKUP.md','docs/IMPROVEMENT-TRACKER.md','docs/NEXT-IMPROVEMENTS.md','docs/APP-UPDATES.md',
      'docs/releases/0.39.0.md','docs/releases/0.39.0-update.txt')
SCRIPTS=('scripts/check-device.py','scripts/check-device-batches.py','scripts/check-nested-search-rules.py',
         'scripts/check-ota-upgrade.py','scripts/package-preview.py','scripts/package-nested-search-validation.py',
         'scripts/android/acceptance.py','scripts/android/NativeTap.java','captures/workspace-recovery/native-environment.py')
IMAGES=('nested-search-draft','nested-search-tablet','nested-search-large-text','nested-search-condition','app-updates-0390')

def read(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def accepted(report):
    assert report['success'] and report['failed']==report['skipped']==0 and report['runnerCompleted'] and report['runnerSucceeded']
def bounds(node):return list(map(int,re.findall(r'-?\d+',node.get('bounds',''))))
def full_control(tree,title,label,footer,width,height,minimum):
    parents={child:parent for parent in tree.iter() for child in parent}
    def clickable_ancestor(node):
        while node in parents:
            node=parents[node]
            if node.get('clickable')=='true':return True
        return False
    heads=[node for node in tree.iter('node') if node.get('text')==title
           and len(bounds(node))==4 and bounds(node)[3]>bounds(node)[1] and not clickable_ancestor(node)]
    assert len(heads)==1,(title,len(heads))
    scope=heads[0]
    while not any(n.get('text')==footer or n.get('content-desc')==footer for n in scope.iter('node')):scope=parents[scope]
    for node in scope.iter('node'):
        if node.get('text')!=label and node.get('content-desc')!=label:continue
        while node.get('clickable')!='true' and node in parents:node=parents[node]
        box=bounds(node)
        if len(box)!=4:continue
        l,t,r,b=box
        if node.get('clickable')!='true' or node.get('enabled')!='true' or not (0<=l<r<=width and 0<=t<b<=height and r-l>=minimum and b-t>=minimum):continue
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
    info=read(RELEASE/'BUILDINFO.json');assert info['version']=='0.39.0-preview' and int(info['version_code'])==41 and not info['working_tree_dirty']
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
    assert totals=={'tests':472,'failures':0,'errors':0,'skipped':0},totals
    lint=ROOT/'app/build/reports/lint-results-debug.xml';assert not any(n.get('severity') in ('Error','Fatal') for n in ET.parse(lint).getroot())
    files+=tests+[lint]
    source=read(PROOF/'native-source-hashes.json')
    for name,digest in source.items():assert sha(ROOT/name)==digest,name
    native_ids=[]
    for api in (30,36):
        report=read(PROOF/f'api{api}-native.json');accepted(report);assert report['passed']==324 and len(report['isolatedBatches'])==34
        assert report['apkSha256']==report['installedApkSha256']=={key:value for key,value in source.items() if key.endswith('.apk')}
        identities={(row['class'],row['test']) for row in report['cases']};assert len(identities)==324;native_ids.append(identities)
        for row in report['isolatedBatches']:
            for path_key,hash_key in (('report','reportSha256'),('log','logSha256')):
                path=ROOT/row[path_key];assert sha(path)==row[hash_key];files.append(path)
        files += [PROOF/f'api{api}-native.json',PROOF/f'api{api}-native.txt',PROOF/f'api{api}-batches/contract.json']
        folder=PROOF/f'api{api}-optimized';opt=read(folder/'verification.json')
        assert opt['apkSha256']==expected and all(opt[key] for key in REQUIRED)
        assert opt['customWindowIterations']==5
        assert {(r['widthDp'],float(r['fontScale'])) for r in opt['layouts']}=={(w,s) for w in (600,800,1280) for s in (1.0,1.6)}
        for row in opt['layouts']:
            assert all(row[k] for k in ('footerVisible','fullTouchBounds','addFolderReachable','detailsVisible','criteriaFooterVisible','groupFooterVisible','groupActionsReachable','groupModesReachable','conditionFooterVisible','conditionTypesReachable'))
            key=f'{row["widthDp"]}dp-font{row["fontScale"]}'
            png=folder/(key+'-scope.png');w,h=struct.unpack('>II',png.read_bytes()[16:24]);density=w/row['widthDp']
            tree=ET.parse(folder/(key+'-scope-capture.xml')).getroot()
            for label in ('Search','Cancel','Close Search locations','Add folder…'):
                assert full_control(tree,'Search locations',label,'Cancel',w,h,48*density),(api,key,label)
            png=folder/(key+'-details.png');w,h=struct.unpack('>II',png.read_bytes()[16:24])
            tree=ET.parse(folder/(key+'-details-capture.xml')).getroot()
            assert full_control(tree,'Result details','Close Result details','Close Result details',w,h,48*density)
            for suffix,title,footer,labels in (
                ('criteria','Search criteria','Apply filters',('Cancel','Clear','Apply filters','Close Search criteria')),
                ('groups','Rule groups','Cancel',('Cancel','Remove rule groups','Apply groups','Add rule…','Add group','Close Rule groups')),
                ('group-modes','Rule groups','Cancel',('All','Any','None')),
                ('condition','Add rule','Cancel',('Cancel','Add rule','Close Add rule'))):
                png=folder/(key+'-'+suffix+'.png');w,h=struct.unpack('>II',png.read_bytes()[16:24])
                tree=ET.parse(folder/(key+'-'+suffix+'-capture.xml')).getroot()
                for label in labels:assert full_control(tree,title,label,footer,w,h,48*density),(api,key,title,label)
        smart=read(folder/'synthetic-smart-folders.json');assert len(smart)==1
        roots=smart[0]['rules']['locations'];assert len(roots)==3 and smart[0]['root']==roots[0]
        assert roots[2]==roots[1]+'/Cache' and smart[0]['rules']['excludedLocations']==[{'path':roots[0]+'/Cache','folder':True}]
        history=read(folder/'synthetic-history.json');assert any(r['rules']==smart[0]['rules'] for r in history['searches'])
        review=read(folder/'visual-review.json');assert review['api']==api and review['actualScreenshotsInspected']
        for image in review['images']:
            assert Path(image['name']).name==image['name'] and Path(image['name']).suffix=='.png'
            assert sha(folder/image['name'])==image['sha256']
        files += [folder/name for name in ('verification.json','synthetic-smart-folders.json','synthetic-history.json','visual-review.json','inspection-retries.json','contact-sources.json')]+list(folder.glob('*.png'))+list(folder.glob('*.xml'))
        for suffix,from_code in (('ota',40),):
            ota=PROOF/f'api{api}-{suffix}';report=read(ota/'verification.json')
            assert report['fromVersionCode']==from_code and report['toVersionCode']==41 and report['restoredApkSha256']==expected
            assert all(report[k] for k in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes','originalFileBytes','legacyFavoritePreserved','sentinelPreferencePreserved','updatedSidebarVisible','noRepeatOffer','restoredOriginalSetup'))
            files += [ota/'verification.json']+list(ota.glob('*.png'))+list(ota.glob('*.xml'))
    assert native_ids[0]==native_ids[1]
    public=read(PROOF/'public-downloads.json');assert public['public'] and public['sourceCommit']==info['source_commit']
    assert len(public['assets'])==5 and all(r['publicDownloadMatchesLocal'] and r['githubDigestMatches'] for r in public['assets'])
    live=read(PROOF/'api36-live-feed.json');accepted(live);assert live['passed']==1 and live['downloadedVersionCode']==41 and live['downloadedApkSha256']==expected
    restored=read(PROOF/'restored-public-tablets.json');assert len(restored)==2 and all(r['privateOriginalMetadataAndPreferencesRestored'] and r['installedPublicApkSha256']==expected and float(r['fontScale'])==1 for r in restored)
    signing=read(PROOF/'signing-continuity.json');assert signing['sameSigningCertificate']
    for name in ('native-source-hashes.json','native-classes.txt','final-debug-checks.txt','release-build.txt',
                 'public-downloads.json','api36-live-feed.json','api36-live-feed.txt','restored-public-tablets.json','signing-continuity.json','rejected-attempts.json',
                 'local-acceptance.json','ota-visual-review.json','verify-local.py','verify-public.py','verify-validation.py','run-live-feed.py',
                 'restore-public-tablets.py','write-release-body.py','finalize-docs.py','prepare-visual-review.py','release-package-after-test-correction.txt'):
        files.append(PROOF/name)
    rejected=read(PROOF/'rejected-attempts.json');assert rejected['rejectedAttemptsExcludedFromAcceptance']
    for attempt in rejected['attempts']:
        assert attempt['countedTowardFinalAcceptance'] is False
        for name in attempt['files']:
            path=ROOT/name
            assert path.is_relative_to(PROOF) and path.suffix in ('.txt','.json','.xml','.png')
            files.append(path)
    for name,api,target in (('nested-search-draft',36,'nested-search-draft'),('nested-search-tablet',30,'nested-search-tablet'),
                            ('nested-search-large-text',36,'600dp-font1.6-groups'),('nested-search-condition',36,'600dp-font1.6-condition')):
        assert sha(ROOT/'docs/screenshots'/(name+'.png'))==sha(PROOF/f'api{api}-optimized'/(target+'.png'))
    assert sha(ROOT/'docs/screenshots/app-updates-0390.png')==sha(PROOF/'api30-ota/up-to-date.png')
    ota_review=read(PROOF/'ota-visual-review.json');assert ota_review['actualScreenshotInspected'] and ota_review['sha256']==sha(PROOF/'api30-ota/up-to-date.png')
    files=sorted(set(files))
    for path in files:
        assert path.is_file() and path.resolve().is_relative_to(ROOT),path
        assert path.suffix not in ('.apk','.jks','.keystore','.bin') and 'native-original' not in path.parts and 'bugreport' not in path.name
    native_commit=subprocess.check_output(['git','log','-1','--format=%H','--','app/src/androidTest'],cwd=ROOT,text=True).strip()
    manifest={'applicationSourceCommit':info['source_commit'],'nativeTestSourceCommit':native_commit,'evidenceCommit':commit,
        'previewApkSha256':expected,'jvmCases':472,'nativeCasesPerApi':324,'isolatedBatchesPerApi':34,
        'files':[{'path':str(p.relative_to(ROOT)),'bytes':p.stat().st_size,'sha256':sha(p)} for p in files]}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.output,'x',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('EVIDENCE.json',json.dumps(manifest,indent=2)+'\n')
        for path in files:archive.write(path,path.relative_to(ROOT))
    report={'archive':args.output.name,'bytes':args.output.stat().st_size,'sha256':sha(args.output),'entries':len(files)+1,
        **{k:manifest[k] for k in ('applicationSourceCommit','nativeTestSourceCommit','evidenceCommit','jvmCases','nativeCasesPerApi','isolatedBatchesPerApi')}}
    (PROOF/'validation-package.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))

if __name__=='__main__':main()
