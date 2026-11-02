#!/usr/bin/env python3
"""Freeze accepted 0.36 search evidence from explicit public-file allowlists.

Never includes private originals, document payloads, APKs, signing keys or full bugreports.
A published archive is immutable. Use a new contract and destination for later increments.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT/'captures/search-experience'
RELEASE = ROOT/'captures/release/0.36.0'
REQUIRED = ('actual035Upgrade','installedOptimizedBytes','legacyRollingDayPreserved','exactByteBoundPreserved',
            'realExactFolderPicker','sameNamedSiblingPreserved','allFourViews','savedSearchSurvivesRestart',
            'originalFileBytes','unrelatedPreferencePreserved','favoritePreserved','savedSemanticRulesVerified',
            'snippetsAbsentFromSavedRules','naturalSizeAndTypeChoices','restoredOriginalSetup')
DOCS = ('README.md','VALIDATION.md','PARITY.md','docs/SEARCH-CRITERIA.md','docs/SETUP-BACKUP.md',
        'docs/IMPROVEMENT-TRACKER.md','docs/NEXT-IMPROVEMENTS.md','docs/APP-UPDATES.md',
        'docs/releases/0.36.0.md','docs/releases/0.36.0-update.txt')
IMAGES = ('search-matches-tablet','search-criteria-tablet','search-exclusions-tablet','search-large-text-tablet','app-updates-036')
SCRIPTS = ('scripts/check-device.py','scripts/check-device-batches.py','scripts/check-search-experience.py',
           'scripts/check-ota-upgrade.py','scripts/package-preview.py','scripts/package-search-validation.py',
           'scripts/android/acceptance.py','scripts/android/NativeTap.java')


def load(path): return json.loads(path.read_text())
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def accepted(report):
    assert report['success'] and report['failed']==report['skipped']==0 and report['runnerCompleted'] and report['runnerSucceeded']


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    assert not args.output.exists(), 'Preserve published archives; choose another destination.'
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip(), 'Commit source and accepted documentation first.'
    evidence_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    info=load(RELEASE/'BUILDINFO.json')
    assert info['version']=='0.36.0-preview' and int(info['version_code'])==37 and not info['working_tree_dirty']
    for artifact in info['artifacts']: assert sha(RELEASE/artifact['name'])==artifact['sha256']
    expected=info['artifacts'][0]['sha256']
    files=[ROOT/path for path in DOCS+SCRIPTS]
    files += [ROOT/'docs/screenshots'/(image+'.png') for image in IMAGES]
    files += list((ROOT/'app/src').rglob('*.kt'))
    files += [ROOT/'app/build.gradle.kts',ROOT/'build.gradle.kts',ROOT/'settings.gradle.kts',ROOT/'gradle.properties']
    files += [RELEASE/name for name in ('BUILDINFO.json','SHA256SUMS','findroid-update.json')]
    counts={key:0 for key in ('tests','failures','errors','skipped')}
    for path in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        suite=ET.parse(path).getroot()
        for key in counts: counts[key]+=int(suite.get(key,0))
        files.append(path)
    assert counts=={'tests':404,'failures':0,'errors':0,'skipped':0},counts
    lint=ROOT/'app/build/reports/lint-results-debug.xml'
    assert not any(issue.get('severity') in ('Error','Fatal') for issue in ET.parse(lint).getroot())
    files.append(lint)
    native_hashes=load(EVIDENCE/'native-source-hashes.json')
    for path,digest in native_hashes.items(): assert sha(ROOT/path)==digest
    assert native_hashes['app/build/outputs/apk/preview/app-preview.apk']==expected
    native_cases=[]
    for api in (30,36):
        path=EVIDENCE/f'api{api}-native.json'; report=load(path); accepted(report)
        assert report['passed']>=240 and len(report['isolatedBatches'])==27,report['passed']
        assert report['apkSha256']==report['installedApkSha256']=={k:v for k,v in native_hashes.items() if '/preview/' not in k and '/release/' not in k}
        ids={(case['class'],case['test']) for case in report['cases']}
        assert len(ids)==report['passed']; native_cases.append(ids)
        files.append(path)
        for batch in report['isolatedBatches']:
            for key,digest_key in (('report','reportSha256'),('log','logSha256')):
                item=ROOT/batch[key]; assert sha(item)==batch[digest_key]; files.append(item)
            accepted(load(ROOT/batch['report']))
        folder=EVIDENCE/f'api{api}-optimized'; result=load(folder/'verification.json')
        assert result['apkSha256']==expected and all(result[key] for key in REQUIRED)
        layouts=result['tabletLayouts']
        assert {(r['widthDp'],r['fontScale']) for r in layouts}=={(w,s) for w in (600,800,1280) for s in (1.0,1.6)}
        assert all(all(row[k] for k in ('resultReasonsReachable','calendarCriteriaReachable','exactExclusionControlsReachable')) for row in layouts)
        files += [folder/'verification.json']+list(folder.glob('*.png'))+list(folder.glob('*.xml'))
        ota=EVIDENCE/f'api{api}-ota'; result=load(ota/'verification.json')
        assert result['fromVersionCode']==36 and result['toVersionCode']==37 and result['restoredApkSha256']==expected
        assert all(result[key] for key in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes','originalFileBytes',
            'legacyFavoritePreserved','sentinelPreferencePreserved','updatedSidebarVisible','noRepeatOffer','restoredOriginalSetup'))
        files += [ota/'verification.json']+list(ota.glob('*.png'))+list(ota.glob('*.xml'))
    assert native_cases[0]==native_cases[1]
    public=load(EVIDENCE/'public-downloads.json')
    assert public['public'] and public['sourceCommit']==info['source_commit'] and len(public['assets'])==5
    assert all(row['publicDownloadMatchesLocal'] and row['githubDigestMatches'] for row in public['assets'])
    live=load(EVIDENCE/'api36-live-feed.json'); accepted(live)
    assert live['downloadedVersionCode']==37 and live['downloadedVersionName']=='0.36.0-preview' and live['downloadedApkSha256']==expected
    restored=load(EVIDENCE/'restored-public-tablets.json')
    assert {row['serial'] for row in restored}=={'emulator-5582','emulator-5602'}
    assert all(row['privateOriginalMetadataAndPreferencesRestored'] and row['installedPublicApkSha256']==expected and float(row['fontScale'])==1.0 for row in restored)
    files += [EVIDENCE/name for name in ('native-source-hashes.json','native-classes.txt','public-downloads.json','api36-live-feed.json','api36-live-feed.txt','restored-public-tablets.json','final-checks.txt','release-build.txt')]
    # Earlier failed attempts remain labeled as rejected diagnostics. No private full bugreports.
    for name in ('api30-focused-first','api36-focused-first','api30-focused-second','api36-focused-second'):
        files += [EVIDENCE/(name+ext) for ext in ('.txt','.json')]
    for api in (30,36):
        rejected=EVIDENCE/f'api{api}-batches-before-loading-selector'
        files += list(rejected.glob('*.json'))+list(rejected.glob('*.txt'))
    files += [ROOT/'captures/workspace-recovery/native-environment.py']
    for image,source in [('search-matches-tablet','search-matches-tablet'),('search-criteria-tablet','calendar-criteria-tablet'),
                         ('search-exclusions-tablet','exact-exclusion-criteria-tablet'),('search-large-text-tablet','layout-600-1_6-criteria')]:
        assert sha(ROOT/'docs/screenshots'/(image+'.png'))==sha(EVIDENCE/'api36-optimized'/(source+'.png'))
    assert sha(ROOT/'docs/screenshots/app-updates-036.png')==sha(EVIDENCE/'api30-ota/up-to-date.png')
    files=sorted(set(files))
    for path in files:
        assert path.is_file(),path
        assert path.is_relative_to(ROOT)
        assert path.suffix not in ('.apk','.jks','.keystore','.bin')
        assert 'native-original' not in path.parts and 'bugreport' not in path.name
    args.output.parent.mkdir(parents=True,exist_ok=True)
    manifest={'applicationSourceCommit':info['source_commit'],'evidenceCommit':evidence_commit,'previewApkSha256':expected,
              'jvmCases':404,'nativeCasesPerApi':len(native_cases[0]),'isolatedBatchesPerApi':27,
              'files':[{'path':str(path.relative_to(ROOT)),'sha256':sha(path),'bytes':path.stat().st_size} for path in files]}
    with zipfile.ZipFile(args.output,'x',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('EVIDENCE.json',json.dumps(manifest,indent=2)+'\n')
        for path in files: archive.write(path,path.relative_to(ROOT))
    result={'archive':args.output.name,'bytes':args.output.stat().st_size,'sha256':sha(args.output),'entries':len(files)+1,
            'applicationSourceCommit':info['source_commit'],'evidenceCommit':evidence_commit,'jvmCases':404,'nativeCasesPerApi':len(native_cases[0])}
    (EVIDENCE/'validation-package.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__': main()
