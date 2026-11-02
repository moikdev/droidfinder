#!/usr/bin/env python3
"""Verify optimized saved-workspace recovery using synthetic files and real native touch.

Preserves original metadata/preferences without uninstalling or clearing app data.
Requires an explicit dedicated device serial and an already-built debug APK for private fixture I/O.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shlex
import time
import xml.etree.ElementTree as ET
from android.acceptance import AndroidDevice, ROOT

FILES = {'tags.properties', 'workspaces.json', 'folder-views.json', 'folder-styles.properties',
         'rename-presets.properties', 'setup-previous.json', 'document-parents.properties'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--previous-apk', type=Path, required=True)
    p.add_argument('--fixture', type=Path, default=ROOT / 'docs/fixtures/workspace-recovery.json')
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    device = AndroidDevice(args.serial, args.output)
    debug = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    stamp = str(time.time_ns())[-12:]
    relative = 'Workspace/Layout demo ' + stamp
    base = '/data/user/0/dev.findroid/files/' + relative
    replacement = base + '/Replacement'
    template = json.loads(args.fixture.read_text())
    assert template['version'] == 1 and len(template['workspaces']) == 2
    assert {v['id'] for v in template['workspaces']} == {'layout-recovery', 'unaffected-layout'}
    def mapped(value):
        if isinstance(value, dict): return {k: mapped(v) for k, v in value.items()}
        if isinstance(value, list): return [mapped(v) for v in value]
        if isinstance(value, str) and value.startswith('/storage/FINDROID-QA-LAYOUT'):
            return base + value.removeprefix('/storage/FINDROID-QA-LAYOUT')
        return value
    fixture = mapped(template)
    layout = next(v for v in fixture['workspaces'] if v['id'] == 'layout-recovery')
    missing_tab = layout['primary']['tabs'][1]
    layout['primary']['tabs'][0]['pinned'] = True
    missing_tab['pinned'] = True
    missing_tab['query'] = 'Notes'
    fixture_bytes = json.dumps(fixture).encode()
    original_files = {'Working project/Keep.txt': b'Working project bytes remain unchanged.\n',
                      'Replacement/Notes.md': b'Replacement notes remain unchanged.\n'}
    saved = None
    original_scale = device.shell('settings', 'get', 'system', 'font_scale').decode().strip()
    report = {'serial': args.serial, 'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest()}

    def stop(): device.shell('am', 'force-stop', 'dev.findroid')
    def read(path, optional=False):
        result = device.call('exec-out', 'run-as', 'dev.findroid', 'cat', path, check=False)
        if optional and result.returncode: return None
        assert result.returncode == 0, 'Could not read private fixture state: ' + path
        return result.stdout
    def write(path, data):
        device.call('shell', 'run-as dev.findroid sh -c ' + shlex.quote('cat > ' + shlex.quote(path)), data=data)
    def install(apk): stop(); device.call('install', '-r', apk)
    def launch():
        device.shell('am', 'start', '-W', '-f', '0x10008000', '--windowingMode', 1, '-n', 'dev.findroid/.MainActivity', '--es', 'finderWindowId', 'main')
    def snapshot():
        names = device.shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        names += ['files/' + name + suffix for name in FILES for suffix in ('', '.bak', '.new')]
        return {name: read(name, optional=True) for name in names}
    def restore(values):
        current = device.shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        for name in set(current)-set(values): device.shell('run-as', 'dev.findroid', 'rm', '-f', name)
        for name, data in values.items():
            if data is None: device.shell('run-as', 'dev.findroid', 'rm', '-f', name)
            else: write(name, data)
    def pref(root, kind, name, value):
        for item in list(root):
            if item.get('name') == name: root.remove(item)
        item = ET.SubElement(root, kind, name=name)
        if kind == 'string': item.text = value
        elif kind == 'set':
            for v in value: ET.SubElement(item, 'string').text = v
        else: item.set('value', str(value).lower())
    def seed():
        install(debug)
        prefs = ET.fromstring(saved['shared_prefs/finder.xml'])
        for kind, name, value in [('string', 'tabs', json.dumps([base])), ('int', 'activeTab', 0),
            ('boolean', 'dualPane', False), ('string', 'activePane', 'primary'), ('boolean', 'panesReversed', False),
            ('boolean', 'dark', False), ('boolean', 'sidebarVisible', True), ('boolean', 'gestureHintDismissed', True),
            ('string', 'density', 'COMFORTABLE'), ('string', 'view', 'LIST'), ('string', 'workspaceAcceptanceSentinel', stamp)]:
            pref(prefs, kind, name, value)
        sidebar = {'version':1, 'favorites':[{'id':'current-project','path':base+'/Working project','label':'Current project'}],
            'groups':[], 'builtins':['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],
            'hidden':['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC']}
        pref(prefs, 'string', 'sidebarLayoutV1', json.dumps(sidebar)); pref(prefs, 'set', 'favorites', [base+'/Working project'])
        write('shared_prefs/finder.xml', ET.tostring(prefs, encoding='utf-8', xml_declaration=True))
        write('shared_prefs/locations.xml', b'<?xml version="1.0" encoding="utf-8"?><map><string name="roots">[]</string></map>')
        write('files/workspaces.json', fixture_bytes)
        for name, data in original_files.items():
            device.shell('run-as', 'dev.findroid', 'mkdir', '-p', 'files/'+relative+'/'+str(Path(name).parent))
            write('files/'+relative+'/'+name, data)
    def workspaces():
        device.click('browser', 'Actions')
        tree = device.scroll_to('actions', 'Workspaces…', x=.9)
        device.tap(device.one(tree, 'Workspaces…'))
        device.click('workspace-list', 'Writing layout')
    def choices():
        workspaces()
        device.wait('missing-locations', lambda t: device.ready(t, 'Review locations'))
    def choose_replacement():
        tree = device.scroll_to('replacement-choice', 'Choose replacement folder…')
        device.tap(device.one(tree, 'Choose replacement folder…'))
        tree = device.wait('folder-picker', lambda t: bool(device.nodes(t, 'Replacement workspace folder')) and bool(device.nodes(t, 'Replacement')))
        device.tap(device.scoped(tree, 'Replacement workspace folder', 'Replacement'))
        device.click('replacement-selected', 'Use this folder')
        device.click('prepare-review', 'Review locations')
        device.wait('review', lambda t: device.ready(t, 'Save repairs and open'))
    def opened(name):
        return device.wait(name, lambda t: not device.nodes(t, 'Review workspace locations') and not device.nodes(t, 'Open saved workspace') and bool(device.nodes(t, 'Replacement')) and bool(device.nodes(t, 'Working project')))
    def unchanged_files():
        for name, data in original_files.items(): assert read('files/'+relative+'/'+name) == data
        prefs = ET.fromstring(read('shared_prefs/finder.xml'))
        assert prefs.find("./string[@name='workspaceAcceptanceSentinel']").text == stamp
        return prefs
    def current_layout(values): return next(v for v in values['workspaces'] if v['id'] == 'layout-recovery')
    def canonical(path): return path.replace('/data/data/dev.findroid/', '/data/user/0/dev.findroid/')

    try:
        install(debug)
        assert device.shell('run-as','dev.findroid','test','!','-e','files/.setup-transaction',check=False).returncode == 0, 'A setup recovery is pending.'
        saved = snapshot()
        seed()
        device.call('install', '-r', '-d', args.previous_apk); launch()
        device.wait('previous-version', lambda t: bool(device.nodes(t,'Actions')))
        install(args.apk); launch()
        installed = device.shell('pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        assert hashlib.sha256(device.call('exec-out','cat',installed)).hexdigest() == report['apkSha256']
        report.update(actual033Upgrade=True, installedOptimizedBytes=True)
        choices(); device.capture('missing-locations')
        choose_replacement(); device.capture('review')
        device.click('open-once','Open once'); opened('opened-once'); device.capture('opened-once')
        install(debug)
        assert read('files/workspaces.json') == fixture_bytes
        unchanged_files()
        report['openOnceLeavesSavedWorkspaceByteForByte'] = True
        seed(); install(args.apk); launch(); choices(); choose_replacement()
        device.click('save-repairs','Save repairs and open'); opened('restored-layout'); device.capture('restored-layout')
        stop(); launch(); opened('restart'); device.capture('persisted-layout')
        workspaces(); opened('reopen-saved-layout')
        process = device.shell('pidof', 'dev.findroid').decode().strip()
        assert process
        for attempt in range(3):
            launch(); opened('replaced-activity-' + str(attempt))
            assert device.shell('pidof', 'dev.findroid').decode().strip() == process, 'Task replacement restarted the app process'
        report['repeatedTaskReplacementKeepsProcessAndRecoveryController'] = True
        report.update(realFolderPicker=True,reviewedRepair=True,restartPersistence=True,reopeningRepairedLayoutNeedsNoRecovery=True)
        install(debug)
        actual = json.loads(read('files/workspaces.json'))
        assert next(v for v in actual['workspaces'] if v['id']=='unaffected-layout') == fixture['workspaces'][0]
        changed = current_layout(actual)
        assert changed['primary']['tabs'][0] == layout['primary']['tabs'][0]
        assert changed['secondary'] == layout['secondary']
        for key in ('active','mode','layout','sort','ascending','group','hidden','foldersFirst','preview','details','icons','column','inspector','columns','fit'):
            assert changed['primary'][key] == layout['primary'][key], key
        repaired_tab = changed['primary']['tabs'][1]
        assert canonical(repaired_tab['history'][repaired_tab['index']]) == replacement
        assert repaired_tab['pinned'] and repaired_tab['query']=='Notes' and repaired_tab['rules']==missing_tab['rules']
        for key in ('dual','reversed','ratio','activePane'): assert changed[key] == layout[key]
        prefs = unchanged_files()
        assert prefs.find("./boolean[@name='dualPane']").get('value')=='true'
        assert prefs.find("./string[@name='activePane']").text=='secondary'
        report.update(onlyMissingDestinationChanged=True,otherWorkspaceUnchanged=True,pinnedSearchAndPaneSettingsPreserved=True,originalFileBytes=True,sentinelPreferencePreserved=True)
        seed(); device.shell('settings','put','system','font_scale','1.6'); install(args.apk); launch()
        choices(); device.capture('large-text-choices'); choose_replacement()
        tree=device.dump('large-text-review'); assert device.ready(tree,'Save repairs and open') and device.ready(tree,'Cancel')
        device.capture('large-text-review')
        report['fontScale1_6ReachableReviewAndActions'] = True
    except Exception:
        try: device.capture('failure')
        except Exception: pass
        raise
    finally:
        stop(); device.shell('settings','put','system','font_scale',original_scale)
        if saved is not None:
            install(debug); restore(saved)
            device.shell('run-as','dev.findroid','rm','-rf','files/'+relative)
            for name,data in saved.items(): assert read(name,optional=True)==data
            report['restoredOriginalSetup'] = True
        install(args.apk); device.close()
    (args.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__ == '__main__':
    main()
