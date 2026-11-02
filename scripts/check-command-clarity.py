#!/usr/bin/env python3
"""Exercise optimized tablet commands, guarded history and an actual previous-version upgrade.

Uses disposable synthetic files in Download, native touch and UI inspection. Private setup is
kept in memory and restored; no uninstall, data clearing or original document payload capture.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import time
import xml.etree.ElementTree as ET
from android.acceptance import AndroidDevice, ROOT

FILES = {'tags.properties', 'workspaces.json', 'folder-views.json', 'folder-styles.properties',
         'rename-presets.properties', 'setup-previous.json', 'document-parents.properties'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--previous-apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.serial != 'emulator-5580', 'This emulator belongs to another project.'
    device = AndroidDevice(args.serial, args.output)
    debug = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    stamp = str(time.time_ns())[-10:]
    demo_root = '/storage/emulated/0/Download/Findroid demo ' + stamp
    base = demo_root + '/Writing project'
    original_bytes = b'Original report bytes survive the update, Undo and layout changes.\n'
    later_bytes = b'Later edits must survive guarded Undo.\n'
    saved = None
    original_scale = device.shell('settings', 'get', 'system', 'font_scale').decode().strip()
    original_size = device.shell('wm', 'size').decode()
    override = re.search(r'Override size: (\d+x\d+)', original_size)
    old_size = override.group(1) if override else 'reset'
    appops = device.shell('appops', 'get', 'dev.findroid', 'MANAGE_EXTERNAL_STORAGE').decode()
    old_mode = re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)', appops)
    old_mode = old_mode.group(1) if old_mode else 'default'
    density = int(re.findall(r'\d+', device.shell('wm', 'density').decode())[-1]) / 160
    report = {'serial': args.serial, 'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest()}

    def stop(): device.shell('am', 'force-stop', 'dev.findroid')
    def install(apk, downgrade=False):
        stop(); device.call('install', '-r', *(['-d'] if downgrade else []), apk)
    def launch():
        device.shell('am', 'start', '-W', '-f', '0x10008000', '--windowingMode', 1,
                     '-n', 'dev.findroid/.MainActivity', '--es', 'finderWindowId', 'main')
    def read(path, optional=False):
        result = device.call('exec-out', 'run-as', 'dev.findroid', 'cat', path, check=False)
        if optional and result.returncode: return None
        assert result.returncode == 0, 'Could not read saved setup: ' + path
        return result.stdout
    def write(path, data):
        device.call('shell', 'run-as dev.findroid sh -c ' + shlex.quote('cat > ' + shlex.quote(path)), data=data)
    def snapshot():
        names = device.shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        names += ['files/' + name + suffix for name in FILES for suffix in ('', '.bak', '.new')]
        return {name: read(name, optional=True) for name in names}
    def restore():
        current = device.shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        for name in set(current)-set(saved): device.shell('run-as', 'dev.findroid', 'rm', '-f', name)
        for name, data in saved.items():
            if data is None: device.shell('run-as', 'dev.findroid', 'rm', '-f', name)
            else: write(name, data)
    def pref(root, kind, name, value):
        for old in list(root):
            if old.get('name') == name: root.remove(old)
        item = ET.SubElement(root, kind, name=name)
        if kind == 'string': item.text = value
        elif kind == 'set':
            for v in value: ET.SubElement(item, 'string').text = v
        else: item.set('value', str(value).lower())
    def seed():
        prefs = ET.fromstring(saved['shared_prefs/finder.xml'])
        values = [('string', 'tabs', json.dumps([base])), ('int', 'activeTab', 0),
            ('boolean', 'dualPane', False), ('string', 'activePane', 'primary'), ('boolean', 'panesReversed', False),
            ('boolean', 'dark', False), ('boolean', 'sidebarVisible', True), ('boolean', 'tagsVisible', False),
            ('boolean', 'gestureHintDismissed', True), ('boolean', 'preview', False),
            ('string', 'density', 'COMFORTABLE'), ('string', 'view', 'LIST'), ('string', 'commandAcceptanceSentinel', stamp),
            ('string', 'smartFolders', json.dumps([{'id':'command-demo','name':'Matching reports','root':base,'rules':{'name':'Report'}}]))]
        for kind, name, value in values: pref(prefs, kind, name, value)
        sidebar = {'version': 1, 'favorites': [{'id': 'exports', 'path': base + '/Exports', 'label': 'Exports'}],
            'groups': [], 'builtins': ['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],
            'hidden': ['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC']}
        pref(prefs, 'string', 'sidebarLayoutV1', json.dumps(sidebar)); pref(prefs, 'set', 'favorites', [base + '/Exports'])
        write('shared_prefs/finder.xml', ET.tostring(prefs, encoding='utf-8', xml_declaration=True))
        write('shared_prefs/locations.xml', b'<?xml version="1.0" encoding="utf-8"?><map><string name="roots">[]</string></map>')
        device.shell('mkdir', '-p', base + '/Exports')
        device.call('shell', 'cat > ' + shlex.quote(base + '/Report.txt'), data=original_bytes)
    def primary_node(tree, label):
        # A named control exposes both its accessible label and a child Text node.
        nodes = [n for n in device.nodes(tree, label) if n.get('content-desc') == label]
        assert len(nodes) == 1, (label, len(nodes))
        return nodes[0]
    def click_primary(name, label):
        device.tap(primary_node(device.wait(name, lambda t: device.ready(t, label)), label))
    def primary(tree):
        result = {label: device.bounds(primary_node(tree, label)) for label in ('Search','New folder','Actions')}
        for left, right in zip(result.values(), list(result.values())[1:]):
            assert (left[1],left[0]) < (right[1],right[0]), result
        return result
    def browser(name): return device.wait(name, lambda t: bool(device.nodes(t, 'Actions')) and bool(device.nodes(t, 'Report.txt')))
    def menu(name, label):
        device.click(name + '-open', 'Actions')
        # Compose retains a menu's scroll position after another command is chosen.
        # Return to its beginning before walking down to the requested named item.
        for _ in range(10):
            tree = device.dump(name + '-top')
            if device.nodes(tree, label):
                device.tap(device.one(tree, label)); return
            if device.nodes(tree, 'App updates'): break
            panes = [n for n in tree.iter('node') if n.get('class') == 'android.widget.ScrollView'
                     and n.get('scrollable') == 'true' and n.get('package') == 'dev.findroid']
            assert len(panes) == 1, 'Expected the open More menu'
            l,t,r,b = device.bounds(panes[0]); x=(l+r)//2
            device.shell('input','swipe',x,round(t+(b-t)*.25),x,round(t+(b-t)*.8),300)
        device.tap(device.one(device.scroll_to(name, label, x=.9), label))
    def dismiss(): device.shell('input', 'keyevent', 'BACK')
    def go_base():
        device.wait('browser-before-address', lambda t: device.ready(t, 'Actions'))
        device.shell('input', 'keyevent', 134)  # F4 is an existing address-bar shortcut on both supported APIs.
        device.wait('address', lambda t: bool(device.nodes(t, 'Address bar')))
        device.shell('input', 'text', base.replace(' ', '%s')); device.shell('input', 'keyevent', 66)
        return device.wait('back-in-demo', lambda t: not device.nodes(t,'Address bar') and bool(device.nodes(t,'Report.txt')))
    def recent():
        menu('recent-actions-menu', 'Recent actions…')
        return device.wait('recent-actions', lambda t: bool(device.nodes(t, 'Recent actions')) and bool(device.nodes(t,'This session only · Up to 50 actions · Shared across panes and windows')))

    try:
        install(debug)
        assert device.shell('run-as','dev.findroid','test','!','-e','files/.setup-transaction',check=False).returncode == 0, 'A setup recovery is pending.'
        saved = snapshot(); seed()
        device.shell('appops', 'set', 'dev.findroid', 'MANAGE_EXTERNAL_STORAGE', 'allow')
        device.shell('settings', 'put', 'system', 'font_scale', '1.0')
        install(args.previous_apk, downgrade=True); launch(); browser('before-upgrade')
        before_code = int(re.search(r'versionCode=(\d+)', device.shell('dumpsys','package','dev.findroid').decode()).group(1))
        assert before_code == 35, 'Expected the public 0.34.1 baseline'
        install(args.apk); launch()
        after_code = int(re.search(r'versionCode=(\d+)', device.shell('dumpsys','package','dev.findroid').decode()).group(1))
        assert after_code == 36, 'Expected the 0.35 optimized build'
        report.update(fromVersionCode=before_code,toVersionCode=after_code)
        initial = primary(browser('after-upgrade')); device.capture('tablet-primary')
        installed = device.shell('pm', 'path', 'dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        assert hashlib.sha256(device.call('exec-out','cat',installed)).hexdigest() == report['apkSha256']
        report.update(actual0341Upgrade=True, installedOptimizedBytes=True)
        device.click('select-report', 'Actions for Report.txt'); dismiss()
        selected = device.wait('selected', lambda t: bool(device.nodes(t,'Selection actions')))
        assert primary(selected) == initial
        device.capture('tablet-selection')
        click_primary('search', 'Search')
        assert primary(device.wait('search-open', lambda t: bool(device.nodes(t,'Close search')))) == initial
        device.click('search-close', 'Close search')
        device.shell('input', 'keyevent', 134)
        assert primary(device.wait('address-open', lambda t: bool(device.nodes(t,'Address bar')))) == initial
        device.click('address-close', 'Close address bar')
        report['primaryOrderAndBoundsAcrossSelectionSearchAddress'] = True
        click_primary('new-folder', 'New folder'); device.click('create-folder','Create')
        device.wait('created', lambda t: bool(device.nodes(t, 'Actions for Untitled folder')))
        recent(); device.capture('recent-completed')
        device.click('undo-folder', 'Undo New folder')
        device.wait('undone', lambda t: bool(device.nodes(t,'Undone · Next to redo')))
        device.click('redo-folder', 'Redo New folder')
        device.wait('redone', lambda t: bool(device.nodes(t,'Completed · Next to undo')))
        device.call('shell', 'cat > ' + shlex.quote(base + '/Untitled folder/Later edits.txt'), data=later_bytes)
        device.click('guarded-undo', 'Undo New folder')
        device.wait('guarded', lambda t: bool(device.nodes(t,'The last Undo or Redo stopped')))
        device.capture('recent-guarded')
        assert device.call('exec-out','cat',base + '/Untitled folder/Later edits.txt') == later_bytes
        device.click('close-recent', 'Close'); recent()
        device.wait('failure-retained', lambda t: bool(device.nodes(t,'The last Undo or Redo stopped')))
        device.click('close-recent-again', 'Close')
        report.update(realCreateUndoRedo=True, laterEditsPreservedByUndoGuard=True, failureExplanationRetained=True)
        device.tap(device.one(device.scroll_to('synthetic-smart-folder','Matching reports',x=.14),'Matching reports'))
        device.wait('synthetic-virtual-view', lambda t: len(device.nodes(t,'Matching reports')) >= 2 and bool(device.nodes(t,'Report.txt')))
        click_primary('virtual-new','New folder')
        device.wait('virtual-explanation', lambda t: bool(device.nodes(t,'New folder is unavailable')) and bool(device.nodes(t,'Location: Matching reports')))
        device.capture('unavailable-command')
        device.click('choose-real-folder','Choose a folder…')
        tree = device.wait('folder-launcher', lambda t: bool(device.nodes(t,'Jump to folder')))
        if not device.nodes(tree,'Exports'):
            # The Android 11 keyboard leaves room for only the first location.
            device.click('filter-destination','Search locations or enter a folder path')
            device.shell('input','text','export')
        tree = device.wait('folder-matches', lambda t: bool(device.nodes(t,'Jump to folder')) and bool(device.nodes(t,'Exports')))
        device.tap(device.scoped(tree,'Jump to folder','Exports'))
        device.wait('real-destination', lambda t: bool(device.nodes(t,'Actions')) and not device.nodes(t,'Jump to folder'))
        go_base(); report['virtualDestinationExplainedAndRecovered'] = True; report['syntheticVirtualView'] = True
        layout_results = []
        for width, height in [(1280,800),(800,1000),(600,800)]:
            for scale in (1.0,1.6):
                stop(); device.shell('wm','size',f'{round(width*density)}x{round(height*density)}')
                device.screen = (round(width*density),round(height*density))
                device.shell('settings','put','system','font_scale',scale)
                launch(); go_base()
                key = f'layout-{width}-{str(scale).replace(".","_")}'
                primary(device.dump(key))
                menu(key+'-select', 'Select all')
                tree = device.wait(key+'-selected', lambda t: bool(device.nodes(t,'Selection actions')))
                primary(tree); device.capture(key)
                recent()
                tree = device.wait(key+'-recent', lambda t: bool(device.nodes(t,'No recent actions in this session')) and bool(device.nodes(t,'Close')))
                device.capture(key+'-recent'); device.click(key+'-close','Close')
                layout_results.append({'widthDp':width,'heightDp':height,'fontScale':scale,'primaryCommandsReachable':True,
                    'selectionActionsReachable':True,'recentActionsReachable':True})
        report['tabletLayouts'] = layout_results
        assert device.call('exec-out','cat',base+'/Report.txt') == original_bytes
        assert device.call('exec-out','cat',base+'/Untitled folder/Later edits.txt') == later_bytes
        install(debug)
        prefs = ET.fromstring(read('shared_prefs/finder.xml'))
        assert prefs.find("./string[@name='commandAcceptanceSentinel']").text == stamp
        assert json.loads(prefs.find("./string[@name='sidebarLayoutV1']").text)['favorites'][0]['path'] == base+'/Exports'
        report.update(originalFileBytes=True, unrelatedPreferencePreserved=True, favoritePreserved=True, historyResetsAfterProcessRestart=True)
    except Exception:
        try: device.capture('failure')
        except Exception: pass
        raise
    finally:
        stop(); device.shell('settings','put','system','font_scale',original_scale); device.shell('wm','size',old_size)
        device.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE',old_mode)
        if saved is not None:
            install(debug); restore()
            for name, data in saved.items(): assert read(name, optional=True) == data
            report['restoredOriginalSetup'] = True
        device.shell('rm','-rf',demo_root)
        install(args.apk); device.close()
    (args.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__ == '__main__': main()
