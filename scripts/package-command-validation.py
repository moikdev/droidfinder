#!/usr/bin/env python3
"""Package the accepted 0.35 command/history evidence from an explicit public-file allowlist.

Never includes APKs, private setup snapshots, original document payloads, signing keys or credentials.
The published archive is frozen: later increments must use a new destination and acceptance contract.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'captures/command-clarity'
REQUIRED = ('actual0341Upgrade', 'installedOptimizedBytes', 'primaryOrderAndBoundsAcrossSelectionSearchAddress',
            'realCreateUndoRedo', 'laterEditsPreservedByUndoGuard', 'failureExplanationRetained',
            'virtualDestinationExplainedAndRecovered', 'syntheticVirtualView', 'originalFileBytes', 'unrelatedPreferencePreserved',
            'favoritePreserved', 'historyResetsAfterProcessRestart', 'restoredOriginalSetup')
SOURCES = ('app/src/main/java/dev/findroid/CommandAvailability.kt',
           'app/src/main/java/dev/findroid/CommandExplanationDialog.kt',
           'app/src/main/java/dev/findroid/RecentActionsDialog.kt',
           'app/src/main/java/dev/findroid/FileHistory.kt',
           'app/src/main/java/dev/findroid/BrowsingGuidance.kt',
           'app/src/main/java/dev/findroid/RefinedChrome.kt',
           'app/src/main/java/dev/findroid/OrganizationDialog.kt',
           'app/src/main/java/dev/findroid/FileConflictDialog.kt',
           'app/src/androidTest/java/dev/findroid/OrganizationWorkflowTest.kt',
           'app/src/androidTest/java/dev/findroid/FileConflictWorkflowTest.kt',
           'app/src/androidTest/java/dev/findroid/SidebarWorkflowTest.kt',
           'app/src/androidTest/java/dev/findroid/AppUpdateWorkflowTest.kt',
           'captures/command-clarity/native-test-source/api36/AppUpdateWorkflowTest.kt',
           'captures/command-clarity/native-test-source/api36/DragWorkflowTest.kt',
           'captures/workspace-recovery/native-environment.py',
           'app/src/androidTest/java/dev/findroid/DragWorkflowTest.kt',
           'app/src/test/java/dev/findroid/CommandAvailabilityTest.kt',
           'app/src/test/java/dev/findroid/RecentFileActionsTest.kt',
           'app/src/androidTest/java/dev/findroid/CommandClarityWorkflowTest.kt',
           'scripts/check-command-clarity.py', 'scripts/check-device.py', 'scripts/check-ota-upgrade.py',
           'scripts/package-command-validation.py', 'scripts/check-device-batches.py', 'scripts/android/acceptance.py', 'scripts/android/NativeTap.java',
           'docs/COMMANDS-AND-UNDO.md', 'docs/IMPROVEMENT-TRACKER.md', 'docs/releases/0.35.0.md',
           'docs/releases/0.35.0-update.txt', 'README.md', 'VALIDATION.md', 'PARITY.md',
           'docs/screenshots/commands-tablet.png', 'docs/screenshots/recent-actions-tablet.png',
           'docs/screenshots/command-explanation-tablet.png')
UI = ('tablet-primary', 'tablet-selection', 'recent-completed', 'recent-guarded', 'unavailable-command')


def load(path): return json.loads(path.read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert not args.output.exists(), 'Keep the published evidence immutable; select a new path for a new package.'
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip(), 'Commit the final public source and acceptance documentation before freezing evidence.'
    evidence_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    files = [ROOT / source for source in SOURCES]
    info = load(ROOT / 'captures/release/0.35.0/BUILDINFO.json')
    assert info['version'] == '0.35.0-preview' and int(info['version_code']) == 36 and not info['working_tree_dirty']
    expected = info['artifacts'][0]['sha256']
    release = ROOT/'captures/release/0.35.0'
    for asset in info['artifacts']:
        assert hashlib.sha256((release/asset['name']).read_bytes()).hexdigest()==asset['sha256']
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or Path.home()/'Library/Android/sdk')
    def certificate(path):
        text = subprocess.check_output([str(sdk/'build-tools/36.0.0/apksigner'),'verify','--print-certs',str(path)],text=True)
        return re.search(r'Signer #1 certificate SHA-256 digest: (\w+)',text).group(1)
    signing = certificate(release/info['artifacts'][0]['name'])
    assert signing==certificate(ROOT/'captures/release/0.34.1/Findroid-0.34.1-preview.apk')
    assert signing=='e7bee2db540fcbb0633d82870b2c4e3d14ecc0c18474b1a7c73d47eb0bf92c08'
    public = load(EVIDENCE/'public-downloads.json')
    assert public['public'] and public['sourceCommit']==info['source_commit'] and len(public['assets'])==5
    assert all(asset['publicDownloadMatchesLocal'] and asset['githubDigestMatches'] for asset in public['assets'])
    restored = load(EVIDENCE/'restored-public-tablets.json')
    assert {row['serial'] for row in restored}=={'emulator-5582','emulator-5602'}
    assert all(row['privateOriginalMetadataAndPreferencesRestored'] and row['installedPublicApkSha256']==expected and float(row['fontScale'])==1.0 for row in restored)
    native_hashes = load(EVIDENCE/'native-source-hashes.json')
    assert native_hashes['app/build/outputs/apk/preview/app-preview.apk'] == expected
    for path, digest in native_hashes.items():
        assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest() == digest
    installed = load(EVIDENCE/'native-install-contracts.json')
    assert {row['serial'] for row in installed} == {'emulator-5582','emulator-5602'}
    assert all(row['versionCode'] == 36 and
               row['installedDebugApkSha256'] == native_hashes['app/build/outputs/apk/debug/app-debug.apk'] and
               row['installedTestApkSha256'] == native_hashes[row['testApkPath']]
               for row in installed)
    files += [EVIDENCE/'native-source-hashes.json', EVIDENCE/'native-install-contracts.json']
    contracts = load(EVIDENCE/'apk-contracts.json')
    assert {row['versionName'] for row in contracts}=={'0.35.0','0.35.0-preview'}
    assert all(int(row['versionCode'])==36 and all(row[key] for key in ('privateStateExcludedFromAutomaticBackups',
        'onlyShippingProviders','httpsUpdateContract','licenseAssetsIncluded')) for row in contracts)
    files.append(EVIDENCE/'apk-contracts.json')
    counts = {key: 0 for key in ('tests','failures','errors','skipped')}
    for path in (ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in counts: counts[key] += int(suite.get(key,0))
        files.append(path)
    assert counts == {'tests':384,'failures':0,'errors':0,'skipped':0}, counts
    lint = ROOT / 'app/build/reports/lint-results-debug.xml'
    assert not any(issue.get('severity') in ('Error','Fatal') for issue in ET.parse(lint).getroot())
    files += [lint, EVIDENCE / 'checks-friendly-scope.txt', EVIDENCE / 'native-classes.txt']
    for api in (30,36):
        prefix = f'api{api}'
        native = load(EVIDENCE / (prefix + '-final.json'))
        assert native['success'] and native['passed'] == 214 and native['failed'] == native['skipped'] == 0
        assert len([case for case in native['cases'] if case['class']=='dev.findroid.CommandClarityWorkflowTest']) == 16
        if api==30:
            assert len(native['isolatedBatches'])==22
            assert native['installedApkSha256']==native['apkSha256']=={p:native_hashes[p] for p in (
                'app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')}
            collected=[];selectors=[]
            for batch in native['isolatedBatches']:
                report=load(ROOT/batch['report'])
                assert report['success'] and report['failed']==report['skipped']==0 and report['passed']==batch['passed']
                for path_key,sha_key in (('report','reportSha256'),('log','logSha256')):
                    path=ROOT/batch[path_key]
                    assert path.is_relative_to(EVIDENCE/'api30-batches') and hashlib.sha256(path.read_bytes()).hexdigest()==batch[sha_key]
                    files.append(path)
                collected+=report['cases'];selectors+=batch['selector'].split(',')
            assert collected==native['cases'] and len({(c['class'],c['test']) for c in collected})==214
            assert sorted(selectors)==sorted((EVIDENCE/'native-classes.txt').read_text().strip().split(','))
            files.append(EVIDENCE/'api30-batches/contract.json')
        files += [EVIDENCE / (prefix + '-final.' + suffix) for suffix in ('txt','json')]
        files.append(EVIDENCE/(prefix+'-tag-keyboard-results.png'))
        folder = EVIDENCE / (prefix + '-optimized')
        acceptance = load(folder / 'verification.json')
        assert acceptance['apkSha256'] == expected and acceptance['fromVersionCode']==35 and acceptance['toVersionCode']==36
        assert all(acceptance.get(key) is True for key in REQUIRED), acceptance
        assert {(r['widthDp'],r['fontScale']) for r in acceptance['tabletLayouts']} == {
            (width,scale) for width in (600,800,1280) for scale in (1.0,1.6)}
        assert all(all(row[k] for k in ('primaryCommandsReachable','selectionActionsReachable','recentActionsReachable')) for row in acceptance['tabletLayouts'])
        files.append(folder / 'verification.json')
        names = list(UI) + [f'layout-{width}-{scale}{suffix}' for width in (600,800,1280)
                            for scale in ('1_0','1_6') for suffix in ('','-recent')]
        for name in names:
            files += [folder / (name + '.png'), folder / (name + '-capture.xml')]
        ota = EVIDENCE / (prefix + '-ota')
        receipt = load(ota/'verification.json')
        assert receipt['toVersionCode']==36 and receipt['restoredApkSha256']==expected
        assert all(receipt[k] for k in ('publicFeedDownloadAndAndroidInstall','installedApkMatchesPublicBytes',
            'originalFileBytes','legacyFavoritePreserved','sentinelPreferencePreserved','noRepeatOffer','restoredOriginalSetup'))
        files.append(ota/'verification.json')
        for name in ('available','ready','installer','upgraded-sidebar','up-to-date'):
            files += [ota/(name+'.xml'),ota/(name+'.png')]
    launch_stall = load(EVIDENCE/'api30-launch-stall.json')
    assert launch_stall['runnerCompleted'] is False and launch_stall['completedCasesBeforeStall'] == 49
    assert launch_stall['fullRerunPassed'] is True and launch_stall['fullRerunCases'] == 214 and launch_stall['fullRerunIsolatedBatches']==22
    files.append(EVIDENCE/'api30-launch-stall.json')
    sidebar = load(EVIDENCE/'api30-sidebar-ime-sync.json')
    assert sidebar['success'] and sidebar['passed']==1 and sidebar['failed']==sidebar['skipped']==0
    before_sidebar = load(EVIDENCE/'api30-before-sidebar-ime-sync.json')
    assert before_sidebar['runnerCompleted'] and before_sidebar['passed']==213 and before_sidebar['failed']==1
    assert [case['test'] for case in before_sidebar['cases'] if case['code'] not in (0,-3,-4)]==['createMoveCollapseAndRemoveGroupKeepsAllReferences']
    files += [EVIDENCE/(prefix+'.'+suffix) for prefix in ('api30-sidebar-ime-sync','api30-before-sidebar-ime-sync') for suffix in ('txt','json')]
    files.append(EVIDENCE/'build-sidebar-ime-sync-final.txt')
    conflicts = load(EVIDENCE/'api30-conflict-ime-sync.json')
    assert conflicts['success'] and conflicts['passed']==6 and conflicts['failed']==conflicts['skipped']==0
    before_conflict = load(EVIDENCE/'api30-before-conflict-ime-sync.json')
    assert before_conflict['runnerCompleted'] and before_conflict['passed']==213 and before_conflict['failed']==1
    assert [case['test'] for case in before_conflict['cases'] if case['code'] not in (0,-3,-4)]==['mergeDialogAndRetryKeepNestedDestinationsAndCompletedMembers']
    files += [EVIDENCE/(prefix+'.'+suffix) for prefix in ('api30-conflict-ime-sync','api30-before-conflict-ime-sync') for suffix in ('txt','json')]
    files.append(EVIDENCE/'build-conflict-ime-sync-final.txt')
    before_updater = load(EVIDENCE/'api30-before-updater-fixture-sync.json')
    assert before_updater['runnerCompleted'] and before_updater['passed']==213 and before_updater['failed']==1
    assert [case['test'] for case in before_updater['cases'] if case['code'] not in (0,-3,-4)]==['automaticChecksRespectTheDailyLimitAndCanBeDisabled']
    files += [EVIDENCE/('api30-before-updater-fixture-sync.'+suffix) for suffix in ('txt','json')]
    for api in (30,36):
        fixture = load(EVIDENCE/f'api{api}-updater-fixture-sync.json')
        assert fixture['success'] and fixture['passed']==6 and fixture['failed']==fixture['skipped']==0
        files += [EVIDENCE/(f'api{api}-updater-fixture-sync.'+suffix) for suffix in ('txt','json')]
    focused_installed = load(EVIDENCE/'focused-install-contracts.json')
    assert {row['serial'] for row in focused_installed} == {'emulator-5582','emulator-5602'}
    assert all(row['installedDebugApkSha256']==native_hashes['app/build/outputs/apk/debug/app-debug.apk'] and
               row['installedTestApkSha256']==native_hashes['app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
               for row in focused_installed)
    files += [EVIDENCE/'focused-install-contracts.json',EVIDENCE/'build-updater-fixture-sync.txt',EVIDENCE/'build-drag-cancel-sync.txt']
    before_cancel=load(EVIDENCE/'api30-before-drag-cancel-sync.json')
    assert before_cancel['runnerCompleted'] and before_cancel['passed']==213 and before_cancel['failed']==1
    assert [case['test'] for case in before_cancel['cases'] if case['code'] not in (0,-3,-4)]==['cancellingSpringFolderRestoresHistorySelectionAndView']
    files += [EVIDENCE/('api30-before-drag-cancel-sync.'+suffix) for suffix in ('txt','json')]
    for api in (30,36):
        drag=load(EVIDENCE/f'api{api}-drag-cancel-sync.json')
        assert drag['success'] and drag['passed']==13 and drag['failed']==drag['skipped']==0
        files += [EVIDENCE/(f'api{api}-drag-cancel-sync.'+suffix) for suffix in ('txt','json')]
    live = load(EVIDENCE/'api36-live-feed.json')
    assert live['success'] and live['passed']==1 and live['failed']==live['skipped']==0
    assert live['downloadedVersionCode']==36 and live['downloadedVersionName']=='0.35.0-preview' and live['downloadedApkSha256']==expected
    files += [EVIDENCE/'api36-live-feed.json', EVIDENCE/'api36-live-feed.txt',
              EVIDENCE/'public-downloads.json',EVIDENCE/'restored-public-tablets.json',
              ROOT/'captures/release/0.35.0/BUILDINFO.json',ROOT/'captures/release/0.35.0/findroid-update.json',
              ROOT/'captures/release/0.35.0/SHA256SUMS']
    for source, image in [('tablet-primary','commands-tablet'),('recent-guarded','recent-actions-tablet'),
                          ('unavailable-command','command-explanation-tablet')]:
        assert (ROOT/'docs/screenshots'/(image+'.png')).read_bytes() == (EVIDENCE/'api36-optimized'/(source+'.png')).read_bytes()
    files = sorted(set(files))
    for path in files: assert path.is_file(), path
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.output,'x',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('EVIDENCE.json',json.dumps({'applicationSourceCommit':info['source_commit'],
            'evidenceCommit':evidence_commit,'previewApkSha256':expected,'signingCertificateSha256':signing,
            'jvmCases':384,'nativeCasesPerApi':214,'api30IsolatedBatches':22,'api36AdditionalFocusedCases':19,
            'nativeApkRecords':installed,'focusedApkRecords':focused_installed},indent=2)+'\n')
        archive.writestr('README.txt',
            'Findroid 0.35.0-preview command and Recent actions acceptance evidence.\n'
            '384 JVM checks; 214 native cases on each API30/API36 emulator; no failures or skips.\n'
            'An earlier API30 run stalled in the Android test launcher after 49 cases; it is not counted.\n'
            'A sanitized diagnostic records that interruption and the completed rerun.\n'
            'API30 final acceptance uses 22 isolated class batches, all passing with the same APKs.\n'
            'Two earlier API30 attempts each had one keyboard-timing test failure (sidebar or\n'
            'conflict rename); focused cases and the complete bundle pass after synchronization.\n'
            'A later API30 test fixture enabled auto checks until construction completed; it is\n'
            'now disabled before construction. Six updater cases pass on both APIs afterward.\n'
            'A later API30 drag cleanup rejected an already-ended synthetic pointer cancellation.\n'
            'The helper now permits that terminal event while keeping restoration/byte assertions.\n'
            'All 13 drag cases pass on both APIs after the helper change.\n'
            'API36 full-bundle evidence uses its recorded preceding test APK. Its two source\n'
            'differences are that updater fixture and pointer cleanup. Focused final checks,\n'
            'per-run hashes and both preceding test sources are included.\n'
            'Optimized 0.34.1 upgrade, actual Undo/Redo and later-edit guards, command recovery,\n'
            '600/800/1280dp widths at font scales 1.0/1.6, real public OTA and exact APK bytes.\n'
            'Only synthetic UI captures and explicit evidence are included. Original private setup,\n'
            'document payloads, APKs, signing keys and credentials are excluded.\n'
            'Physical TalkBack/input/media and third-party providers remain separate acceptance.\n')
        for path in files: archive.write(path,path.relative_to(ROOT))
    report={'name':args.output.name,'bytes':args.output.stat().st_size,
            'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest(),'files':len(files)+2,'sourceCommit':info['source_commit'],'evidenceCommit':evidence_commit,'signingCertificateSha256':signing,
            'implementedProposals':[2,3,6],'implementedTotal':20,'openOrPartial':52}
    args.output.with_suffix('.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__': main()
