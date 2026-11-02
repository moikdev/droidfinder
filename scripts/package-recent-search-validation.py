#!/usr/bin/env python3
"""Freeze accepted 0.37 recent-search evidence from explicit public-file allowlists.

A published archive is immutable. Never include private original metadata, document
payloads, APKs, credentials, signing keys, or full bugreports in validation archives.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT/'captures/recent-searches'
RELEASE = ROOT/'captures/release/0.37.0'
REQUIRED = ('actual036Upgrade','keyboardFocusAfterSubmit','keyboardFocusAfterSuggestion','explicitRestoredQuerySubmit','installedOptimizedBytes','inlineTouchSuggestion','fullRulesSearchHere',
            'literalExclusionsPreserved','originalLocationReuse','unavailableOriginalPreservesTab',
            'smartFolderCopyIndependent','exactFullCriteriaPersisted','noTypedPrefixPollution','noSavedResultBytes','historySurvivesRestart',
            'pauseAndClearSurviveRestart','unrelatedPreferencePreserved','favoritePreserved','originalFileBytes','restoredOriginalSetup')
DOCS = ('README.md','VALIDATION.md','PARITY.md','docs/RECENT-SEARCHES.md','docs/SEARCH-CRITERIA.md',
        'docs/SETUP-BACKUP.md','docs/IMPROVEMENT-TRACKER.md','docs/NEXT-IMPROVEMENTS.md','docs/APP-UPDATES.md',
        'docs/releases/0.37.0.md','docs/releases/0.37.0-update.txt')
IMAGES = ('recent-searches-tablet','recent-search-suggestions','recent-search-unavailable','recent-search-large-text','app-updates-037')
SCRIPTS = ('scripts/check-device.py','scripts/check-device-batches.py','scripts/check-recent-searches.py',
           'scripts/check-ota-upgrade.py','scripts/package-preview.py','scripts/package-recent-search-validation.py',
           'scripts/android/acceptance.py','scripts/android/NativeTap.java')


def load(path): return json.loads(path.read_text())
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def accepted(report):
    assert report['success'] and report['failed']==report['skipped']==0 and report['runnerCompleted'] and report['runnerSucceeded']
def text(tree): return '\n'.join(node.get('text','') for node in tree.iter('node'))
def bounds(node): return list(map(int,re.findall(r'-?\d+',node.get('bounds',''))))
def png_size(path): return struct.unpack('>II',path.read_bytes()[16:24])
def visible_control(tree,label,width,height,min_height):
    parents={child:parent for parent in tree.iter() for child in parent}
    candidates=[node for node in tree.iter('node') if node.get('text')==label or node.get('content-desc')==label]
    for node in candidates:
        while node.get('clickable')!='true' and node in parents: node=parents[node]
        box=bounds(node)
        if len(box)==4:
            l,t,r,b=box
            if node.get('enabled')=='true' and 0<=l<r<=width and 0<=t<b<=height and b-t>=min_height: return True
    return False


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    assert not args.output.exists(),'Preserve frozen archives; choose a new destination.'
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip(),'Commit the accepted source and documentation first.'
    evidence_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    info=load(RELEASE/'BUILDINFO.json')
    assert info['version']=='0.37.0-preview' and int(info['version_code'])==38 and not info['working_tree_dirty']
    for item in info['artifacts']: assert sha(RELEASE/item['name'])==item['sha256']
    expected=info['artifacts'][0]['sha256']
    files=[ROOT/path for path in DOCS+SCRIPTS]
    files += [ROOT/'docs/screenshots'/(name+'.png') for name in IMAGES]
    files += list((ROOT/'app/src').rglob('*.kt'))
    files += list((ROOT/'app/src/main').rglob('*.xml'))
    files += [ROOT/name for name in ('app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties')]
    files += [RELEASE/name for name in ('BUILDINFO.json','SHA256SUMS','findroid-update.json')]
    counts={key:0 for key in ('tests','failures','errors','skipped')}
    for path in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        suite=ET.parse(path).getroot()
        for key in counts: counts[key]+=int(suite.get(key,0))
        files.append(path)
    assert counts=={'tests':422,'failures':0,'errors':0,'skipped':0},counts
    lint=ROOT/'app/build/reports/lint-results-debug.xml'
    assert not any(issue.get('severity') in ('Error','Fatal') for issue in ET.parse(lint).getroot())
    files.append(lint)
    # Native tests may be strengthened after the application build. Require every
    # application source byte to remain identical to the clean APK source commit.
    for path in (ROOT/'app/src/main').rglob('*'):
        if path.is_file():
            tracked=str(path.relative_to(ROOT))
            data=subprocess.check_output(['git','show',info['source_commit']+':'+tracked],cwd=ROOT)
            assert hashlib.sha256(data).hexdigest()==sha(path),tracked
    source_hashes=load(EVIDENCE/'native-source-hashes.json')
    for path,digest in source_hashes.items(): assert sha(ROOT/path)==digest,path
    assert source_hashes['app/build/outputs/apk/preview/app-preview.apk']==expected
    debug_hashes={key:value for key,value in source_hashes.items() if key.endswith('.apk') and '/preview/' not in key and '/release/' not in key}
    native_ids=[]
    for api in (30,36):
        path=EVIDENCE/f'api{api}-native.json'; native=load(path); accepted(native)
        assert native['passed']==279 and len(native['isolatedBatches'])==29
        assert native['apkSha256']==native['installedApkSha256']==debug_hashes
        identities={(row['class'],row['test']) for row in native['cases']}
        assert len(identities)==native['passed']; native_ids.append(identities); files.append(path)
        for batch in native['isolatedBatches']:
            for key,hash_key in (('report','reportSha256'),('log','logSha256')):
                path=ROOT/batch[key]; assert sha(path)==batch[hash_key]; files.append(path)
            accepted(load(ROOT/batch['report']))
        focus=load(EVIDENCE/f'api{api}-shortcut-focus-after-fix.json'); accepted(focus)
        assert focus['passed']==2 and focus['apkSha256']==focus['installedApkSha256']==debug_hashes
        assert {case['test'] for case in focus['cases']}=={'choosingSuggestionRestoresExplorerKeyboardFocus','submittingQueryRestoresExplorerKeyboardFocus'}
        files += [EVIDENCE/f'api{api}-shortcut-focus-after-fix.json',EVIDENCE/f'api{api}-shortcut-focus-after-fix.txt']
        folder=EVIDENCE/f'api{api}-optimized'; report=load(folder/'verification.json')
        assert report['apkSha256']==expected and all(report[key] for key in REQUIRED)
        initial=ET.parse(folder/'initial-empty.xml').getroot()
        assert 'No recent searches' in text(initial)
        full=ET.parse(folder/'recent-searches-tablet-capture.xml').getroot()
        for token in ('Name contains “Report”','Text contains “needle”','Today','Under 1 MB','Past 24 hours','At least 1025 bytes','Excluded folder names: Scratch','Excluded folder:'):
            assert token in text(full),(api,token)
        rows=load(folder/'synthetic-history.json')['searches']
        assert len(rows)==2 and len({row['root'] for row in rows})==2
        for row in rows:
            rules=row['rules']; assert rules['name']=='Report' and rules['content']=='needle' and rules['min']==1025
            assert rules['days']==1 and rules['date']=='TODAY' and rules['size']=='UNDER_1_MB'
            assert rules['excludedFolderNames']==['Scratch'] and len(rules['excludedLocations'])==1
        assert rows[0]['rules']==rows[1]['rules']
        smart=load(folder/'synthetic-smart-folders.json')
        assert len(smart)==1 and smart[0]['name']=='Working notes' and smart[0]['rules']==dict(rows[0]['rules'],name='Notes')
        layouts=report['layouts']
        assert {(row['widthDp'],float(row['fontScale'])) for row in layouts}=={(w,s) for w in (600,800,1280) for s in (1.0,1.6)}
        for row in layouts:
            assert all(row[key] for key in ('footerVisible','fullOriginalButtonVisible','rememberToggleReachable','originalRootFiltered'))
            key=f'{row["widthDp"]}dp-font{row["fontScale"]}'
            width,height=png_size(folder/(key+'-history.png')); density=width/row['widthDp']
            filtered=ET.parse(folder/(key+'-filtered-history.xml')).getroot()
            assert any(n.get('class')=='android.widget.EditText' and n.get('text')=='Writing project' for n in filtered.iter('node'))
            assert any(n.get('text','').startswith('Writing project · ') for n in filtered.iter('node'))
            assert not any(n.get('text','').startswith('Research project · ') for n in filtered.iter('node'))
            tree=ET.parse(folder/(key+'-history-capture.xml')).getroot()
            for label in ('Close','Clear history','Search original location'):
                assert visible_control(tree,label,width,height,48*density),(api,key,label)
            controls=ET.parse(folder/(key+'-controls-capture.xml')).getroot()
            assert visible_control(controls,'Remember searches',width,height,48*density),(api,key)
        # Inspect the actual nonfocused popup, independently of the workflow receipt.
        width,height=png_size(folder/'recent-search-suggestions.png')
        popup=ET.parse(folder/'recent-search-suggestions-capture.xml').getroot()
        assert popup.tag=='displays'
        assert visible_control(popup,'Search here: Report',width,height,48*density),(api,'suggestion bounds')
        final=ET.parse(folder/'paused-after-restart.xml').getroot()
        assert 'No recent searches' in text(final) and 'Recording is paused.' in text(final)
        files += [folder/name for name in ('verification.json','synthetic-history.json','synthetic-smart-folders.json')]+list(folder.glob('*.png'))+list(folder.glob('*.xml'))
        ota=EVIDENCE/f'api{api}-ota'; report=load(ota/'verification.json')
        assert report['fromVersionCode']==37 and report['toVersionCode']==38 and report['restoredApkSha256']==expected
        assert all(report[key] for key in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes','originalFileBytes',
            'legacyFavoritePreserved','sentinelPreferencePreserved','updatedSidebarVisible','noRepeatOffer','restoredOriginalSetup'))
        files += [ota/'verification.json']+list(ota.glob('*.png'))+list(ota.glob('*.xml'))
    assert native_ids[0]==native_ids[1]
    public=load(EVIDENCE/'public-downloads.json')
    assert public['public'] and public['sourceCommit']==info['source_commit'] and len(public['assets'])==5
    assert all(row['publicDownloadMatchesLocal'] and row['githubDigestMatches'] for row in public['assets'])
    live=load(EVIDENCE/'api36-live-feed.json'); accepted(live)
    assert live['passed']==1 and live['downloadedVersionCode']==38 and live['downloadedVersionName']=='0.37.0-preview' and live['downloadedApkSha256']==expected
    signing=load(EVIDENCE/'signing-continuity.json')
    assert signing['sameSigningCertificate'] and len({row['certificateSha256'] for row in signing['versions']})==1
    assert signing['versions'][-1]['apkSha256']==expected
    restored=load(EVIDENCE/'restored-public-tablets.json')
    assert {row['serial'] for row in restored}=={'emulator-5582','emulator-5602'}
    assert all(row['privateOriginalMetadataAndPreferencesRestored'] and row['installedPublicApkSha256']==expected and float(row['fontScale'])==1.0 for row in restored)
    files += [ROOT/'captures/workspace-recovery/native-environment.py']
    files += [EVIDENCE/name for name in ('native-source-hashes.json','native-classes.txt','public-downloads.json','api36-live-feed.json',
        'api36-live-feed.txt','restored-public-tablets.json','final-debug-checks.txt','release-build.txt','history-visibility-test-build.txt','signing-continuity.json','rejected-attempts.json',
        'verify-public.py','verify-local.py','local-acceptance.json','restore-public-tablets.py','run-live-feed.py','verify-validation.py','write-release-body.py','finalize-docs.py')]
    # Retain rejected candidate failures separately from the accepted build's counts.
    files += [EVIDENCE/name for name in ('rejected-native/api30-organization-first.json','rejected-native/api30-organization-first.txt',
        'api30-organization-diagnostic.json','api30-organization-diagnostic.txt',
        'rejected-native/api30-input-first.json','rejected-native/api30-input-first.txt',
        'rejected-native/api36-shortcut-focus-before-fix.json','rejected-native/api36-shortcut-focus-before-fix.txt')]
    rejected=load(EVIDENCE/'rejected-native/api30-history-filter-first.json')
    assert not rejected['success'] and rejected['passed']==19 and rejected['failed']==1
    files += [EVIDENCE/'rejected-native/api30-history-filter-first.json',EVIDENCE/'rejected-native/api30-history-filter-first.txt']
    second=load(EVIDENCE/'rejected-native/api30-history-filter-second.json')
    assert not second['success'] and second['passed']==19 and second['failed']==1
    files += [EVIDENCE/'rejected-native/api30-history-filter-second.json',EVIDENCE/'rejected-native/api30-history-filter-second.txt']
    loading=load(EVIDENCE/'rejected-native/api30-loading-first.json')
    assert not loading['success'] and loading['passed']==9 and loading['failed']==1
    files += [EVIDENCE/'rejected-native/api30-loading-first.json',EVIDENCE/'rejected-native/api30-loading-first.txt']
    for attempt in range(1,4):
        report=load(EVIDENCE/f'api30-history-filter-diagnostic-{attempt}.json'); accepted(report)
        assert report['passed']==1 and report['apkSha256']==report['installedApkSha256']
        assert report['apkSha256']['app/build/outputs/apk/debug/app-debug.apk']==debug_hashes['app/build/outputs/apk/debug/app-debug.apk']
        assert report['apkSha256']['app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']=='277ab45c961e5d29f8e49165760708bae69edcc86b44ff166305a05ed8cd3ab8'
        assert report['cases'][0]['test']=='historyDialogSupportsFilteringPausingRemovingAndClearing'
        files += [EVIDENCE/f'api30-history-filter-diagnostic-{attempt}.json',EVIDENCE/f'api30-history-filter-diagnostic-{attempt}.txt']
    for name,source in [('recent-searches-tablet','recent-searches-tablet'),('recent-search-suggestions','recent-search-suggestions'),
                        ('recent-search-unavailable','recent-search-unavailable'),('recent-search-large-text','600dp-font1.6-history')]:
        assert sha(ROOT/'docs/screenshots'/(name+'.png'))==sha(EVIDENCE/'api36-optimized'/(source+'.png'))
    assert sha(ROOT/'docs/screenshots/app-updates-037.png')==sha(EVIDENCE/'api30-ota/up-to-date.png')
    files=sorted(set(files))
    for path in files:
        assert path.is_file() and path.is_relative_to(ROOT),path
        assert path.suffix not in ('.apk','.jks','.keystore','.bin')
        assert 'native-original' not in path.parts and 'bugreport' not in path.name
    test_commit=subprocess.check_output(['git','log','-1','--format=%H','--','app/src/androidTest'],cwd=ROOT,text=True).strip()
    manifest={'applicationSourceCommit':info['source_commit'],'nativeTestSourceCommit':test_commit,'evidenceCommit':evidence_commit,'previewApkSha256':expected,
              'jvmCases':422,'nativeCasesPerApi':279,'isolatedBatchesPerApi':29,
              'files':[{'path':str(path.relative_to(ROOT)),'sha256':sha(path),'bytes':path.stat().st_size} for path in files]}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.output,'x',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('EVIDENCE.json',json.dumps(manifest,indent=2)+'\n')
        for path in files: archive.write(path,path.relative_to(ROOT))
    result={'archive':args.output.name,'bytes':args.output.stat().st_size,'sha256':sha(args.output),'entries':len(files)+1,
            'applicationSourceCommit':info['source_commit'],'nativeTestSourceCommit':test_commit,'evidenceCommit':evidence_commit,'jvmCases':422,'nativeCasesPerApi':279,'isolatedBatchesPerApi':29}
    (EVIDENCE/'validation-package.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__': main()
