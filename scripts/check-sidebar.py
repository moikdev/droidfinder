#!/usr/bin/env python3
"""Exercise legacy Favorites migration/reordering in an optimized APK; restore the user's setup."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--previous-apk', type=Path)
    parser.add_argument('--legacy-debug-apk', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
    debug = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    def call(*parts, data=None):
        result = subprocess.run([adb, '-s', args.serial, *map(str, parts)], input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
        if result.returncode:
            raise RuntimeError(f'adb {parts[:2]} failed: {result.stderr.decode(errors="replace")}')
        return result.stdout
    def shell(*parts): return call('shell', shlex.join(map(str, parts)))
    def write_private(path, data):
        return call('shell', 'run-as dev.findroid sh -c ' + shlex.quote('cat > ' + shlex.quote(path)), data=data)
    def stop(): shell('am', 'force-stop', 'dev.findroid')
    def launch():
        shell('am', 'start', '-W', '-f', '0x10008000', '--windowingMode', 1,
              '-n', 'dev.findroid/.MainActivity', '--es', 'finderWindowId', window,
              '--es', 'finderLocation', base)
    def tree(name):
        shell('uiautomator', 'dump', '/sdcard/sidebar-ui.xml')
        data = call('exec-out', 'cat', '/sdcard/sidebar-ui.xml')
        (args.output / f'{name}.xml').write_bytes(data)
        return ET.fromstring(data)
    def nodes(root, name):
        return [n for n in root.iter('node') if n.get('text') == name or n.get('content-desc') == name]
    def wait(name, predicate):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            root = tree(name)
            if predicate(root): return root
            time.sleep(.15)
        raise AssertionError('UI did not settle: ' + name)
    def bounds(node): return list(map(int, re.findall(r'\d+', node.get('bounds'))))
    def center(node):
        l, t, r, b = bounds(node); return (l + r) // 2, (t + b) // 2
    def capture(name):
        previous = call('exec-out', 'screencap', '-p')
        for _ in range(4):
            time.sleep(.15)
            current = call('exec-out', 'screencap', '-p')
            if current == previous: break
            previous = current
        (args.output / f'{name}.png').write_bytes(current)
    def sidebar_row(root, name):
        parents = {c: p for p in root.iter() for c in p}
        candidates = [n for n in nodes(root, name) if bounds(n)[0] < 260 * density]
        assert len(candidates) == 1, (name, len(candidates))
        node = candidates[0]
        while node.get('clickable') != 'true': node = parents[node]
        return node
    def set_pref(root, kind, name, value):
        for old in list(root):
            if old.get('name') == name: root.remove(old)
        element = ET.SubElement(root, kind, name=name)
        if kind == 'string': element.text = value
        elif kind == 'set':
            for item in value: ET.SubElement(element, 'string').text = item
        else: element.set('value', str(value).lower())
    def save_prefs(root): write_private('shared_prefs/finder.xml', ET.tostring(root, encoding='utf-8', xml_declaration=True))
    stamp = str(time.time_ns())[-10:]
    window = 'sidebar-acceptance-' + stamp
    relative_root = 'Workspace/Findroid demo ' + stamp
    relative = relative_root + '/Projects'
    base = '/data/user/0/dev.findroid/files/' + relative
    labels = ['Design project', 'Reference', 'Writing projects']
    originals = {name: ('Original ' + name + ' bytes\n').encode() for name in labels}
    density = int(re.findall(r'\d+', shell('wm', 'density').decode())[-1]) / 160
    original_prefs = None
    original_scale = shell('settings', 'get', 'system', 'font_scale').decode().strip()
    report = {'serial': args.serial, 'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest()}
    try:
        stop(); call('install', '-r', debug)
        original_prefs = call('exec-out', 'run-as', 'dev.findroid', 'cat', 'shared_prefs/finder.xml')
        if args.legacy_debug_apk:
            call('install', '-r', '-d', args.legacy_debug_apk)
        prefs = ET.fromstring(original_prefs)
        for item in list(prefs):
            if item.get('name') == 'sidebarLayoutV1': prefs.remove(item)
        set_pref(prefs, 'set', 'favorites', [base + '/' + name for name in labels])
        for key, value in [('sidebarVisible', True), ('gestureHintDismissed', True), ('dark', False)]: set_pref(prefs, 'boolean', key, value)
        set_pref(prefs, 'int', 'sidebarWidth', 240)
        set_pref(prefs, 'string', 'density', 'COMPACT')
        set_pref(prefs, 'string', 'sidebarAcceptanceSentinel', stamp)
        save_prefs(prefs)
        for name, data in originals.items():
            shell('run-as', 'dev.findroid', 'mkdir', '-p', 'files/' + relative + '/' + name)
            write_private('files/' + relative + '/' + name + '/Keep.txt', data)
        if args.previous_apk:
            call('install', '-r', '-d', args.previous_apk); launch()
            wait('before-upgrade', lambda t: bool(nodes(t, 'Writing projects'))); capture('before-upgrade')
            stop()
        call('install', '-r', args.apk); launch()
        root = wait('migrated', lambda t: bool(nodes(t, 'Options for Writing projects')))
        source = sidebar_row(root, 'Writing projects')
        target = sidebar_row(root, 'Reference')
        left, top, _, bottom = bounds(source)
        start = (round(left + 24 * density), (top + bottom) // 2)
        # Android expands semantic touch bounds into the 8dp insertion gaps.
        # Identical-height label centers locate the visual gap without those expansions.
        design_label = [n for n in nodes(root, 'Design project') if bounds(n)[0] < 260 * density][0]
        reference_label = [n for n in nodes(root, 'Reference') if bounds(n)[0] < 260 * density][0]
        destination = (round(bounds(target)[0] + 60 * density), round((center(design_label)[1] + center(reference_label)[1]) / 2))
        shell('input', 'touchscreen', 'motionevent', 'DOWN', *start)
        time.sleep(.85)
        try:
            shell('input', 'touchscreen', 'motionevent', 'MOVE', round(start[0] + 24 * density), start[1])
            shell('input', 'touchscreen', 'motionevent', 'MOVE', *destination)
            wait('insertion', lambda t: bool(nodes(t, 'Reorder Favorites'))); capture('insertion')
        finally: shell('input', 'touchscreen', 'motionevent', 'UP', *destination)
        expected = ['Design project', 'Writing projects', 'Reference']
        def ordered(root): return sorted(labels, key=lambda name: bounds(sidebar_row(root, name))[1]) == expected
        wait('ordered', ordered); capture('ordered')
        stop(); launch(); wait('restarted', ordered); capture('restarted')
        stop(); call('install', '-r', debug)
        prefs = ET.fromstring(call('exec-out', 'run-as', 'dev.findroid', 'cat', 'shared_prefs/finder.xml'))
        layout = json.loads(prefs.find("./string[@name='sidebarLayoutV1']").text)
        assert [item['path'].rsplit('/', 1)[1] for item in layout['favorites']] == expected
        assert prefs.find("./string[@name='sidebarAcceptanceSentinel']").text == stamp
        for name, expected_bytes in originals.items():
            assert call('exec-out', 'run-as', 'dev.findroid', 'cat', 'files/' + relative + '/' + name + '/Keep.txt') == expected_bytes
        report.update(legacyMigration=True, nativeTouchReorder=True, restartOrder=True, originalBytes=True, unrelatedPreference=True)
        layout['favorites'].sort(key=lambda item: ['Writing projects', 'Design project', 'Reference'].index(item['path'].rsplit('/', 1)[1]))
        layout['favorites'][0]['label'] = 'Writing & notes'
        layout['groups'] = [{'id': 'work', 'name': 'Work', 'collapsed': False}]
        layout['favorites'][2]['group'] = 'work'
        layout['favorites'].append({'id': 'missing', 'path': base + '/Disconnected folder', 'group': 'work'})
        layout['hidden'] = ['DOCUMENTS', 'DOWNLOADS', 'PICTURES', 'MOVIES', 'MUSIC']
        set_pref(prefs, 'string', 'density', 'COMFORTABLE')
        set_pref(prefs, 'string', 'sidebarLayoutV1', json.dumps(layout))
        set_pref(prefs, 'set', 'favorites', [item['path'] for item in layout['favorites']])
        save_prefs(prefs); call('install', '-r', args.apk); launch()
        root = wait('sidebar', lambda t: bool(nodes(t, 'Unavailable · options to repair'))); capture('sidebar')
        options = nodes(root, 'Options for Writing & notes'); assert len(options) == 1
        shell('input', 'tap', *center(options[0]))
        wait('options', lambda t: bool(nodes(t, 'Rename sidebar label…'))); capture('options')
        shell('input', 'keyevent', 4)
        root = tree('launcher-start'); actions = nodes(root, 'Actions'); assert len(actions) == 1
        shell('input', 'tap', *center(actions[0]))
        for _ in range(12):
            root = tree('launcher-menu')
            found = nodes(root, 'Find an action or folder…')
            if found: break
            scrolls = [n for n in root.iter('node') if n.get('scrollable') == 'true']
            assert scrolls, 'The action menu must be scrollable'
            menu = max(scrolls, key=lambda n: bounds(n)[0])
            l,t,r,b = bounds(menu)
            shell('input', 'swipe', (l+r)//2, round(b-20*density), (l+r)//2, round(t+20*density), 250)
        assert len(found) == 1, 'Action launcher is missing from More'
        shell('input', 'tap', *center(found[0]))
        wait('launcher-open', lambda t: bool(nodes(t, 'Search actions and folders')))
        shell('input', 'text', 'rename')
        root = wait('launcher-query', lambda t: bool(nodes(t, 'Rename selection')))
        shell('input', 'tap', *center(nodes(root, 'Rename selection')[0]))
        wait('launcher', lambda t: bool(nodes(t, 'Select a file or folder first.'))); capture('launcher')
        report['optimizedLauncherDisabledReason'] = True
        stop(); launch(); wait('launcher-closed', lambda t: bool(nodes(t, 'Writing & notes')))
        root = tree('edit-start'); edit = nodes(root, 'Edit'); assert len(edit) == 1
        shell('input', 'tap', *center(edit[0]))
        wait('editor', lambda t: bool(nodes(t, 'Restore built-in defaults'))); capture('editor')
        shell('input', 'keyevent', 4)
        shell('settings', 'put', 'system', 'font_scale', '1.6')
        stop(); launch(); wait('large-text', lambda t: bool(nodes(t, 'Writing & notes'))); capture('large-text')
        report.update(labelsGroupsUnavailable=True, contextualMenu=True, editor=True, largeTextCapture=True)
    finally:
        stop(); shell('settings', 'put', 'system', 'font_scale', original_scale if original_scale != 'null' else '1.0')
        if original_prefs is not None:
            call('install', '-r', debug)
            write_private('shared_prefs/finder.xml', original_prefs)
            # This run only owns this exact newly created synthetic directory.
            shell('run-as', 'dev.findroid', 'rm', '-rf', 'files/' + relative_root)
        call('install', '-r', args.apk)
    report['restoredOriginalSetup'] = True
    (args.output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__': main()
