#!/usr/bin/env python3
"""Check native Favorites pinning and process-restart persistence in an optimized APK.

Use an explicitly selected QA tablet. Seeds synthetic folders through the same-key debug
build, installs the requested preview, and drives its real touch UI. Never clears data.
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
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or
               Path.home() / 'Library/Android/sdk')
    adb = [shutil.which('adb') or str(sdk / 'platform-tools/adb'), '-s', args.serial]
    debug = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'

    def call(*parts, data=None):
        return subprocess.check_output(adb + list(map(str, parts)), input=data, timeout=45)

    def shell(*parts):
        return call('shell', shlex.join(str(part) for part in parts)).decode()

    def dump(name):
        shell('uiautomator', 'dump', '/sdcard/findroid-favorites-qa.xml')
        data = call('exec-out', 'cat', '/sdcard/findroid-favorites-qa.xml')
        (args.output / (name + '.xml')).write_bytes(data)
        return ET.fromstring(data)

    def nodes(tree, label):
        all_nodes = list(tree.iter('node'))
        return ([node for node in all_nodes if node.get('text') == label] or
                [node for node in all_nodes if node.get('content-desc') == label])

    def find(tree, label):
        matches = nodes(tree, label)
        assert len(matches) == 1, (label, [(node.get('text'), node.get('content-desc'))
                                         for node in tree.iter('node') if node.get('text')])
        return matches[0]

    def bounds(node):
        return list(map(int, re.findall(r'\d+', node.get('bounds'))))

    def point(node):
        left, top, right, bottom = bounds(node)
        return (left + right) // 2, (top + bottom) // 2

    def capture(name):
        (args.output / (name + '.png')).write_bytes(call('exec-out', 'screencap', '-p'))

    def wait_for(name, predicate):
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            tree = dump(name)
            if predicate(tree):
                return tree
            time.sleep(.2)
        raise AssertionError('UI condition did not settle: ' + name)

    def launch():
        shell('am', 'start', '-W', '-f', '0x10008000', '--windowingMode', 1,
              '-n', 'dev.findroid/.MainActivity', '--es', 'finderWindowId', window_id,
              '--es', 'finderLocation', full_folder)

    stamp = str(time.time_ns())[-8:]
    window_id = 'favorites-qa-' + stamp
    folder = 'Workspace/Sidebar demo ' + stamp + '/Projects'
    full_folder = '/data/user/0/dev.findroid/files/' + folder
    source = full_folder + '/Design project'
    original = b'Pinning saves a shortcut. This project stays in its original location.\n'
    shell('am', 'force-stop', 'dev.findroid')
    call('install', '-r', debug)
    previous = args.output / 'verification.json'
    if previous.exists():
        # Re-running this output directory removes only its own earlier synthetic
        # favorite, so the identical display name cannot hide the new shortcut.
        prior = json.loads(previous.read_text()).get('favoritePath', '')
        assert re.fullmatch(r'/data/user/0/dev\.findroid/files/Workspace/Sidebar demo \d+/Projects/Design project', prior)
        prefs = ET.fromstring(call('exec-out', 'run-as', 'dev.findroid', 'cat', 'shared_prefs/finder.xml'))
        for favorites in prefs.findall("./set[@name='favorites']"):
            for item in list(favorites):
                if item.text == prior:
                    favorites.remove(item)
        call('shell', 'run-as dev.findroid sh -c ' + shlex.quote('cat > shared_prefs/finder.xml'),
             data=ET.tostring(prefs, encoding='utf-8', xml_declaration=True))
    shell('run-as', 'dev.findroid', 'mkdir', '-p', 'files/' + folder + '/Design project',
          'files/' + folder + '/Research notes', 'files/' + folder + '/Archive')
    call('shell', 'run-as dev.findroid sh -c ' + shlex.quote(
        'cat > ' + shlex.quote('files/' + folder + '/Design project/Brief.txt')), data=original)
    call('install', '-r', args.apk.resolve())
    launch()
    tree = wait_for('before', lambda tree: bool(nodes(tree, 'Drag folders here')) and
                    bool(nodes(tree, 'Design project')))
    item = find(tree, 'Design project')
    parents = {child: parent for parent in tree.iter() for child in parent}
    row = item
    while row.get('clickable') != 'true':
        row = parents[row]
    density = int(re.findall(r'(\d+)', shell('wm', 'density'))[-1]) / 160
    left, top, right, bottom = bounds(row)
    start = int(left + 73.5 * density), (top + bottom) // 2
    target = point(find(tree, 'Drag folders here'))
    shell('input', 'touchscreen', 'motionevent', 'DOWN', *start)
    try:
        time.sleep(.9)
        # Separate adb processes are slow enough to spring-open folders crossed by
        # a many-step path. Lift, then go straight to the named pinning target.
        for step in (1, 12):
            at = [round(a + (b - a) * step / 12) for a, b in zip(start, target)]
            shell('input', 'touchscreen', 'motionevent', 'MOVE', *at)
        wait_for('hover', lambda tree: bool(nodes(tree, 'Release to add')) and
                 bool(nodes(tree, 'Add to Favorites')))
        capture('hover')
    finally:
        shell('input', 'touchscreen', 'motionevent', 'UP', *target)

    def sidebar_favorite(tree):
        # A source row and its new shortcut have the same name; the shortcut is left of the list.
        return [node for node in nodes(tree, 'Design project') if bounds(node)[0] < 200 * density]

    wait_for('saved', sidebar_favorite)
    capture('saved')
    shell('am', 'force-stop', 'dev.findroid')
    launch()
    tree = wait_for('restarted', sidebar_favorite)
    assert len(sidebar_favorite(tree)) == 1
    capture('restarted')
    shell('input', 'tap', *point(sidebar_favorite(tree)[0]))
    wait_for('opened', lambda tree: bool(nodes(tree, 'Brief.txt')))
    capture('opened')

    # Read private bytes/preferences only after exercising the exact optimized APK.
    shell('am', 'force-stop', 'dev.findroid')
    call('install', '-r', debug)
    assert call('exec-out', 'run-as', 'dev.findroid', 'cat',
                'files/' + folder + '/Design project/Brief.txt') == original
    prefs = ET.fromstring(call('exec-out', 'run-as', 'dev.findroid', 'cat', 'shared_prefs/finder.xml'))
    favorites = [node.text for node in prefs.findall("./set[@name='favorites']/string")]
    assert favorites.count(source) == 1, favorites
    call('install', '-r', args.apk.resolve())
    report = {'serial': args.serial, 'api': int(shell('getprop', 'ro.build.version.sdk')),
              'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              'gesture': 'touch-folder-icon-without-preselection', 'favoritePath': source,
              'folderPinned': True, 'restartPersistenceVerified': True,
              'favoriteOpensOriginalFolder': True, 'originalBytesPreserved': True,
              'privateBytesCheckedAfterSameKeyDebugReinstall': True,
              'exactOptimizedApkRestored': True, 'applicationDataCleared': False}
    (args.output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
