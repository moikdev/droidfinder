#!/usr/bin/env python3
"""Check an optimized APK's real split-screen drop on an explicitly selected QA tablet.

Installs the debug APK to seed synthetic files, then the optimized APK to drag.
The separate interop-client must be built. No application data is cleared.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import struct
import time
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--icon', action='store_true', help='Hold a selected list icon instead of Drag.')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or
               Path.home() / 'Library/Android/sdk')
    adb = [shutil.which('adb') or str(sdk / 'platform-tools/adb'), '-s', args.serial]
    debug = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    receiver = ROOT / 'interop-client/build/outputs/apk/debug/interop-client-debug.apk'

    def call(*parts, data=None):
        return subprocess.check_output(adb + list(parts), input=data, timeout=45)

    def shell(*parts):
        return call('shell', shlex.join(str(part) for part in parts)).decode()

    def seed(path, data):
        call('shell', 'run-as dev.findroid sh -c ' + shlex.quote('cat > ' + shlex.quote(path)), data=data)

    def dump():
        shell('uiautomator', 'dump', '/sdcard/findroid-drop-qa.xml')
        data = call('exec-out', 'cat', '/sdcard/findroid-drop-qa.xml')
        (args.output / 'screen.xml').write_bytes(data)
        return ET.fromstring(data)

    def find(tree, label):
        nodes = list(tree.iter('node'))
        exact = [n for n in nodes if n.get('text') == label] or [n for n in nodes if n.get('content-desc') == label]
        matches = exact or [n for n in nodes if label in n.get('text', '').splitlines()]
        assert len(matches) == 1, (label, [(n.get('text'), n.get('content-desc')) for n in nodes if n.get('text')])
        return matches[0]

    def bounds(node):
        return list(map(int, re.findall(r'\d+', node.get('bounds'))))

    def point(node):
        x1, y1, x2, y2 = bounds(node)
        return (x1 + x2) // 2, (y1 + y2) // 2

    def tap(node):
        shell('input', 'tap', *point(node))

    api = int(shell('getprop', 'ro.build.version.sdk').strip())
    assert api >= 30
    window_id = 'drop-qa-' + str(time.time_ns())
    folder = 'Workspace/Drag demo ' + str(time.time_ns())[-6:]
    def png_chunk(kind, payload):
        return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload))

    png = (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>2I5B', 4, 4, 8, 6, 0, 0, 0)) +
           png_chunk(b'IDAT', zlib.compress((b'\x00' + b'\x50\x91\xf9\xff' * 4) * 4)) + png_chunk(b'IEND', b''))
    files = {
        'Project notes.txt': b'A file copied through Android global drag from the optimized APK.\n',
        'Diagram.png': png,
        'Private.txt': b'This file was not selected or granted.\n',
    }
    for package in ('dev.findroid', 'dev.findroid.interop'):
        shell('am', 'force-stop', package)
    call('install', '-r', str(debug))
    call('install', '-r', str(receiver))
    shell('run-as', 'dev.findroid', 'mkdir', '-p', 'files/' + folder)
    for name, data in files.items():
        seed('files/' + folder + '/' + name, data)
    seed('shared_prefs/drag-upgrade-probe.xml', b'<?xml version="1.0"?><map><string name="marker">preserved</string></map>')
    call('install', '-r', str(args.apk.resolve()))
    source_uid = int(re.search(r'^package:dev\.findroid uid:(\d+)',
                               shell('pm', 'list', 'packages', '-U', 'dev.findroid'), re.MULTILINE).group(1))
    from urllib.parse import quote
    probe = 'content://dev.findroid.files/workspace/' + quote(folder.removeprefix('Workspace/') + '/Project notes.txt')
    private = 'content://dev.findroid.files/workspace/' + quote(folder.removeprefix('Workspace/') + '/Private.txt')
    shell('run-as', 'dev.findroid.interop', 'rm', '-f', 'files/drop-report.json')
    shell('am', 'start', '-W', '-f', '0x10008000', '--windowingMode', 3 if api == 30 else 1,
          '-n', 'dev.findroid/.MainActivity', '--es', 'finderWindowId', window_id,
          '--es', 'finderLocation', '/data/user/0/dev.findroid/files/' + folder)
    shell('am', 'start', '-W', '--windowingMode', 4 if api == 30 else 1,
          '-n', 'dev.findroid.interop/.DropReceiverActivity', '--es', 'probeUri', probe,
          '--es', 'protectedUri', private)
    if api > 30:
        task = re.search(r'#(\d+)[^\n]*A=\d+:dev\.findroid\.interop\.drop\b',
                         shell('dumpsys', 'activity', 'activities')).group(1)
        shell('cmd', 'window', 'shell', 'splitscreen', 'moveToSideStage', task, 1)
    tree = dump()
    target = point(find(tree, 'Drop files here'))
    # UIAutomator's dump reports the focused window on Android 11.
    source_focus = (bounds(find(tree, 'Drop files here'))[0] // 2, 80)
    shell('input', 'tap', *source_focus)
    tree = dump()
    # Name holds select; icon holds lift. Select both without opening their previews.
    x, y = point(find(tree, 'Project notes.txt'))
    shell('input', 'swipe', x, y, x, y, 900)
    tree = dump()
    tap(find(tree, 'Diagram.png'))
    tree = dump()
    find(tree, '2 selected')
    start = point(find(tree, 'Hold to drag selected files'))
    if args.icon:
        row = find(tree, 'Project notes.txt')
        parents = {child: parent for parent in tree.iter() for child in parent}
        while row.get('clickable') != 'true':
            row = parents[row]
        density = int(re.findall(r'(\d+)', shell('wm', 'density'))[-1]) / 160
        x1, y1, x2, y2 = bounds(row)
        start = (int(x1 + 73.5 * density), (y1 + y2) // 2)
    # Separate native events hold past Android/Compose's long-press deadline.
    # `input draganddrop` moves at that exact deadline and can race recognition.
    shell('input', 'touchscreen', 'motionevent', 'DOWN', *start)
    try:
        time.sleep(.9)
        for step in range(1, 13):
            point_at = [round(a + (b - a) * step / 12) for a, b in zip(start, target)]
            shell('input', 'touchscreen', 'motionevent', 'MOVE', *point_at)
    finally:
        shell('input', 'touchscreen', 'motionevent', 'UP', *target)
    deadline = time.monotonic() + 20
    report = None
    while time.monotonic() < deadline:
        try:
            report = json.loads(call('exec-out', 'run-as', 'dev.findroid.interop', 'cat', 'files/drop-report.json'))
            break
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            time.sleep(.2)
    assert report and report.get('success'), report
    assert report['receiverUid'] != source_uid
    for key in ('readDeniedBeforeDrop', 'unselectedReadDeniedBeforeDrop',
                'unselectedReadDeniedDuringDrop', 'readDeniedAfterRelease', 'sourceLocalStateHidden'):
        assert report[key], (key, report)
    assert {item['name'] for item in report['files']} == {'Project notes.txt', 'Diagram.png'}
    for item in report['files']:
        assert item['sha256'] == hashlib.sha256(files[item['name']]).hexdigest()
        assert item['writeDenied']
    shell('input', 'tap', *source_focus)
    tree = dump()
    find(tree, '2 selected')
    for name in files:
        find(tree, name)
    (args.output / 'dropped.png').write_bytes(call('exec-out', 'screencap', '-p'))
    (args.output / 'receiver.json').write_text(json.dumps(report, indent=2) + '\n')
    # Reinstall the same-key debug build only after testing the exact optimized APK.
    call('install', '-r', str(debug))
    for name, data in files.items():
        assert call('exec-out', 'run-as', 'dev.findroid', 'cat', 'files/' + folder + '/' + name) == data
    call('install', '-r', str(args.apk.resolve()))
    summary = {'serial': args.serial, 'api': api, 'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
               'gesture': 'touch-selected-icon' if args.icon else 'touch-drag-control',
               'filesCopied': 2, 'receiverIsSeparateUid': True, 'grantChecksPassed': True,
               'visibleSelectionPreserved': True, 'originalBytesPreserved': True,
               'sourceBytesCheckedAfterSameKeyDebugReinstall': True, 'optimizedApkRestored': True,
               'fixtureFolder': folder}
    (args.output / 'verification.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
