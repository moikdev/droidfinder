#!/usr/bin/env python3
"""Package the 0.32.0 sidebar/navigation evidence; whitelist synthetic fixtures and public docs."""
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
PROOF = ROOT / 'captures/sidebar-management'
RELEASE = ROOT / 'captures/release/0.32.0'

def main():
    meta = json.loads((RELEASE / 'BUILDINFO.json').read_text())
    apk = RELEASE / meta['artifacts'][0]['name']
    expected = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert not meta['working_tree_dirty'] and expected == meta['artifacts'][0]['sha256']
    native_counts = {}
    for serial, api in [('emulator-5602', 30), ('emulator-5582', 36)]:
        native = json.loads((PROOF / f'{serial}-release-final.json').read_text())
        optimized = json.loads((PROOF / f'optimized-api{api}/verification.json').read_text())
        assert native['success'] and native['passed'] == 79 and native['failed'] == native['skipped'] == 0
        assert optimized['apkSha256'] == expected and optimized['nativeTouchReorder'] and optimized['restartOrder']
        assert optimized['optimizedLauncherDisabledReason']
        assert optimized['originalBytes'] and optimized['unrelatedPreference'] and optimized['restoredOriginalSetup']
        native_counts[f'api{api}'] = native['passed']
    counts = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for path in (ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        for key in counts: counts[key] += int(ET.parse(path).getroot().get(key, 0))
    assert counts == {'tests': 317, 'failures': 0, 'errors': 0, 'skipped': 0}
    lint = Counter(item.get('severity') for item in ET.parse(ROOT / 'app/build/reports/lint-results-debug.xml').getroot().findall('issue'))
    assert lint.get('Error', 0) == lint.get('Fatal', 0) == 0
    signer = Path.home() / 'Library/Android/sdk/build-tools/36.0.0/apksigner'
    env = {**os.environ, 'JAVA_HOME': str(Path.home() / 'Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1/Contents/Home')}
    def certificate(path):
        text = subprocess.check_output([str(signer), 'verify', '--print-certs', str(path)], env=env, text=True)
        return re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', text).group(1)
    cert = certificate(apk)
    assert cert == certificate(ROOT / 'captures/release/0.31.0/Findroid-0.31.0-preview.apk')
    summary = {'date': '2026-11-02', 'version': '0.32.0-preview', 'sourceCommit': meta['source_commit'],
        'sourceTreeCleanAtPackaging': True, 'apkSha256': expected, 'apkBytes': apk.stat().st_size,
        'signingCertificateSha256': cert, 'sameCertificateAs031': True, 'jvmTests': counts, 'lint': dict(lint),
        'nativeCasesPerApi': native_counts, 'optimizedLegacyMigrationReorderRestartAndOriginalBytes': {'api30': True, 'api36': True},
        'authorizedImprovements': 72, 'implementedInThisIncrement': [1, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16],
        'repository': 'moikdev/findroid', 'externalCoverage': 'Physical hardware, screen reader and actual third-party apps remain open.'}
    for relative, key in [('ota/verification.json', 'realPublic031To032Ota'), ('public-downloads.json', 'anonymousPublicAssets')]:
        path = PROOF / relative
        if path.exists(): summary[key] = json.loads(path.read_text())
    (PROOF / 'verification.json').write_text(json.dumps(summary, indent=2) + '\n')
    names = ['README.md', 'PARITY.md', 'VALIDATION.md', 'docs/SIDEBAR-AND-NAVIGATION.md', 'docs/DRAGGING.md',
        'docs/APP-UPDATES.md', 'docs/IMPROVEMENT-TRACKER.md', 'docs/NEXT-IMPROVEMENTS.md',
        'docs/releases/0.32.0.md', 'docs/releases/0.32.0-update.txt',
        'scripts/check-sidebar.py', 'scripts/check-ota-upgrade.py', 'scripts/package-sidebar-validation.py',
        'app/src/androidTest/java/dev/findroid/SidebarWorkflowTest.kt',
        'app/src/androidTest/java/dev/findroid/BrowsingClarityWorkflowTest.kt',
        'app/src/test/java/dev/findroid/SidebarLayoutTest.kt', 'app/src/test/java/dev/findroid/ExplorerLauncherTest.kt']
    files = [ROOT / name for name in names]
    files += [RELEASE / name for name in ['BUILDINFO.json', 'SHA256SUMS', 'findroid-update.json']]
    files += [PROOF / name for name in ['verification.json', 'build-final.txt', 'package.txt',
        'emulator-5602-release-final.txt', 'emulator-5602-release-final.json',
        'emulator-5582-release-final.txt', 'emulator-5582-release-final.json']]
    for folder in ['optimized-api30', 'optimized-api36', 'ota']:
        for extension in ['*.json', '*.png', '*.xml']:
            files += list((PROOF / folder).glob(extension))
    files += list((ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    files.append(ROOT / 'app/build/reports/lint-results-debug.xml')
    for name in ['sidebar-management-tablet.png', 'favorite-options-tablet.png', 'sidebar-large-text-tablet.png', 'action-launcher-tablet.png', 'app-updates-032.png']:
        path = ROOT / 'docs/screenshots' / name
        if path.exists(): files.append(path)
    for name in ['public-downloads.json', 'api36-live-feed.txt', 'api36-live-feed.json']:
        if (PROOF / name).exists(): files.append(PROOF / name)
    destination = RELEASE / 'Findroid-0.32.0-validation.zip'
    with ZipFile(destination, 'w', ZIP_DEFLATED) as archive:
        archive.writestr('README.txt', 'Findroid 0.32.0 local acceptance\n\n317 JVM tests; 79 native cases on each API30/API36 emulator.\nOptimized legacy Favorites migration, native reorder, restart, original bytes and preference preservation.\nSee VALIDATION.md and captures/sidebar-management/verification.json for exact scope.\nSynthetic UI captures only; no signing keys, credentials or private document bytes.\nPhysical devices and third-party/provider matrices remain separate open work.\n')
        for path in dict.fromkeys(files): archive.write(path, path.relative_to(ROOT))
    print(json.dumps(summary, indent=2))
    print('ZIP', destination)

if __name__ == '__main__': main()
