#!/usr/bin/env python3
"""Package 0.34 workspace-recovery acceptance using an explicit public-evidence whitelist."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
PROOF = ROOT / 'captures/workspace-recovery'
RELEASE = ROOT / 'captures/release/0.34.0'
OPTIMIZED_CHECKS = (
    'actual033Upgrade', 'installedOptimizedBytes', 'openOnceLeavesSavedWorkspaceByteForByte',
    'realFolderPicker', 'reviewedRepair', 'restartPersistence',
    'reopeningRepairedLayoutNeedsNoRecovery', 'onlyMissingDestinationChanged',
    'otherWorkspaceUnchanged', 'pinnedSearchAndPaneSettingsPreserved', 'originalFileBytes',
    'sentinelPreferencePreserved', 'fontScale1_6ReachableReviewAndActions', 'restoredOriginalSetup',
)
UI_STATES = ('missing-locations', 'review', 'opened-once', 'restored-layout',
             'persisted-layout', 'large-text-choices', 'large-text-review')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    meta = json.loads((RELEASE / 'BUILDINFO.json').read_text())
    apk = RELEASE / meta['artifacts'][0]['name']
    expected = digest(apk)
    assert not meta['working_tree_dirty'] and expected == meta['artifacts'][0]['sha256']
    native_counts = {}
    for api in (30, 36):
        native = json.loads((PROOF / f'api{api}-final.json').read_text())
        optimized = json.loads((PROOF / f'optimized-api{api}/verification.json').read_text())
        assert native['success'] and native['passed'] == 132 and native['failed'] == native['skipped'] == 0
        assert optimized['apkSha256'] == expected
        assert all(optimized.get(key) is True for key in OPTIMIZED_CHECKS)
        native_counts[f'api{api}'] = native['passed']
    counts = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for path in (ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        for key in counts:
            counts[key] += int(ET.parse(path).getroot().get(key, 0))
    assert counts == {'tests': 360, 'failures': 0, 'errors': 0, 'skipped': 0}
    lint = Counter(item.get('severity') for item in ET.parse(ROOT / 'app/build/reports/lint-results-debug.xml').getroot().findall('issue'))
    assert lint.get('Error', 0) == lint.get('Fatal', 0) == 0
    signer = Path.home() / 'Library/Android/sdk/build-tools/36.0.0/apksigner'
    env = {**os.environ, 'JAVA_HOME': str(Path.home() / 'Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1/Contents/Home')}

    def certificate(path):
        text = subprocess.check_output([str(signer), 'verify', '--print-certs', str(path)], env=env, text=True)
        return re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', text).group(1)

    cert = certificate(apk)
    assert cert == certificate(ROOT / 'captures/release/0.33.0/Findroid-0.33.0-preview.apk')
    assert cert == 'e7bee2db540fcbb0633d82870b2c4e3d14ecc0c18474b1a7c73d47eb0bf92c08'
    fixture = json.loads((ROOT / 'docs/fixtures/workspace-recovery.json').read_text())
    assert fixture['version'] == 1 and len(fixture['workspaces']) == 2
    assert all(path.startswith('/storage/FINDROID-QA-LAYOUT/') for workspace in fixture['workspaces']
        for pane in ('primary','secondary') for tab in workspace[pane]['tabs'] for path in tab['history'])
    summary = {
        'date': '2026-11-02', 'version': '0.34.0-preview', 'sourceCommit': meta['source_commit'],
        'sourceTreeCleanAtPackaging': True, 'apkSha256': expected, 'apkBytes': apk.stat().st_size,
        'signingCertificateSha256': cert, 'sameCertificateAs033': True,
        'jvmTests': counts, 'lint': dict(lint), 'nativeCasesPerApi': native_counts,
        'optimizedChecksOnEachApi': list(OPTIMIZED_CHECKS),
        'authorizedImprovements': 72, 'implementedInThisIncrement': [18],
        'implementedTotal': 17, 'openOrPartial': 55, 'repository': 'moikdev/findroid',
        'externalCoverage': 'Physical hardware, TalkBack, removable drives and actual third-party providers remain open.',
    }
    for relative, key in [('ota/verification.json', 'realPublic033To034Ota'), ('public-downloads.json', 'anonymousPublicAssets')]:
        path = PROOF / relative
        if path.exists(): summary[key] = json.loads(path.read_text())
    live = PROOF / 'api36-live-feed.json'
    if live.exists():
        result = json.loads(live.read_text())
        assert result['success'] and result['passed'] == 1 and result['failed'] == result['skipped'] == 0
        summary['productionSourceLiveFeedApi36'] = True
    (PROOF / 'verification.json').write_text(json.dumps(summary, indent=2) + '\n')
    names = [
        'README.md', 'PARITY.md', 'VALIDATION.md', 'docs/WORKSPACE-RECOVERY.md',
        'docs/SETUP-BACKUP.md', 'docs/REFERENCES.md', 'docs/APP-UPDATES.md', 'docs/ROADMAP.md',
        'docs/IMPROVEMENT-TRACKER.md', 'docs/NEXT-IMPROVEMENTS.md',
        'docs/fixtures/workspace-recovery.json', 'docs/releases/0.34.0.md', 'docs/releases/0.34.0-update.txt',
        'scripts/check-workspace-recovery.py', 'scripts/check-ota-upgrade.py', 'scripts/package-workspace-validation.py',
        'scripts/android/acceptance.py', 'scripts/android/NativeTap.java',
        'app/src/androidTest/java/dev/findroid/WorkspaceRecoveryWorkflowTest.kt',
        'app/src/androidTest/java/dev/findroid/WorkspaceRecoveryPersistenceTest.kt',
        'app/src/androidTest/java/dev/findroid/WorkspaceSaveFailureTest.kt',
        'app/src/androidTest/java/dev/findroid/NavigationWorkflowTest.kt',
        'app/src/androidTest/java/dev/findroid/SetupWorkflowTest.kt',
        'app/src/test/java/dev/findroid/WorkspaceRecoveryTest.kt',
    ]
    files = [ROOT / name for name in names]
    files += [RELEASE / name for name in ['BUILDINFO.json', 'SHA256SUMS', 'findroid-update.json']]
    files += [PROOF / name for name in ['verification.json', 'build-final.txt', 'package.txt',
        'api30-final.txt', 'api30-final.json', 'api36-final.txt', 'api36-final.json']]
    for api in (30, 36):
        folder = PROOF / f'optimized-api{api}'
        files.append(folder / 'verification.json')
        files += [folder / f'{name}.png' for name in UI_STATES]
        files += [folder / f'{name}-capture.xml' for name in UI_STATES]
    files += list((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    files.append(ROOT / 'app/build/reports/lint-results-debug.xml')
    for name in ['workspace-recovery-tablet.png', 'workspace-recovery-review-tablet.png', 'workspace-recovery-large-text-tablet.png', 'app-updates-034.png']:
        path = ROOT / 'docs/screenshots' / name
        if path.exists(): files.append(path)
    for name in ['public-downloads.json', 'api36-live-feed.txt', 'api36-live-feed.json']:
        path = PROOF / name
        if path.exists(): files.append(path)
    for name in ('available', 'ready', 'installer', 'upgraded-sidebar', 'up-to-date'):
        path = PROOF / 'ota' / f'{name}.png'
        if path.exists(): files.append(path)
    if (PROOF / 'ota/verification.json').exists(): files.append(PROOF / 'ota/verification.json')
    destination = RELEASE / 'Findroid-0.34.0-validation.zip'
    with ZipFile(destination, 'w', ZIP_DEFLATED) as archive:
        archive.writestr('README.txt',
            'Findroid 0.34.0 saved-workspace recovery acceptance\n\n'
            '360 JVM tests; 132 native cases on each API30/API36 emulator.\n'
            'Optimized 0.33 upgrade, real touch/folder choice, Open once, reviewed saved repairs,\n'
            'restart, unchanged working tabs/other layout/original bytes/preferences and font scale 1.6.\n'
            'See VALIDATION.md and captures/workspace-recovery/verification.json for exact scope.\n'
            'Synthetic UI captures and a controlled layout fixture only. No private setup snapshots,\n'
            'document payloads, signing keys or credentials. Physical/provider acceptance remains separate.\n')
        for path in dict.fromkeys(files):
            assert path.is_file(), str(path)
            archive.write(path, path.relative_to(ROOT))
    print(json.dumps(summary, indent=2))
    print('ZIP', destination)


if __name__ == '__main__': main()
