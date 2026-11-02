#!/usr/bin/env python3
"""Verify setup backup/restore through the optimized APK and Android's real document pickers.

Uses its own synthetic files; restores the exact original metadata and preferences without
uninstalling or clearing app data. Requires an already-built debug APK only for fixture I/O.
Never run on an unassigned device. Evidence intentionally excludes original private setup bytes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import tempfile
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FILES = {'tags.properties', 'workspaces.json', 'folder-views.json', 'folder-styles.properties', 'rename-presets.properties', 'setup-previous.json'}
KEYS = {'finder': {'sidebarLayoutV1', 'favorites', 'smartFolders', 'density', 'extensions', 'dark', 'sidebarWidth', 'sidebarVisible', 'tagsVisible', 'gestureHintDismissed', 'conflictPolicy'},
        'zip-limits': {'entries', 'total', 'member', 'ratio', 'preview'}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--previous-apk', type=Path)
    parser.add_argument('--fixture', type=Path, required=True, help='Synthetic portable-demo.json from SetupWorkflowTest, never a personal backup.')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
    debug = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    fixture = json.loads(args.fixture.read_text())
    assert fixture['format'] == 'dev.findroid.setup' and fixture['version'] == 1
    assert len(fixture['roots']) == 1 and fixture['roots'][0]['source'] == '/storage/OLD-TABLET'
    assert all(r['path'].startswith('/storage/OLD-TABLET/') for r in fixture['references'])
    stamp = str(time.time_ns())[-12:]
    relative = 'Workspace/Setup demo ' + stamp
    base = '/data/user/0/dev.findroid/files/' + relative
    destination = base + '/Restored tablet'
    source_name = 'Findroid-setup-source-' + stamp + '.json'
    export_name = 'Findroid-setup-export-' + stamp + '.json'
    public_source = '/sdcard/Download/' + source_name
    public_export = '/sdcard/Download/' + export_name
    originals = {'Projects/Keep.txt': b'Original synthetic project bytes survive setup restoration.\n',
                 'Reference/Notes.md': b'Reference notes stay in their folder.\n',
                 'Current folder/Original.txt': b'Existing favorite folder stays in place.\n'}
    saved = None
    native_tap = None
    report = {'serial': args.serial, 'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest()}

    def call(*parts, data=None, check=True):
        result = subprocess.run([adb, '-s', args.serial, *map(str, parts)], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
        if check and result.returncode:
            raise RuntimeError(f'adb {parts[:2]} failed: {result.stderr.decode(errors="replace")}')
        return result.stdout if check else result
    def shell(*parts, **kwargs): return call('shell', shlex.join(map(str, parts)), **kwargs)
    def stop(): shell('am', 'force-stop', 'dev.findroid')
    def read(name, optional=False):
        result = call('exec-out', 'run-as', 'dev.findroid', 'cat', name, check=False)
        if optional and result.returncode: return None
        if result.returncode: raise RuntimeError('Could not read private fixture state: ' + name)
        return result.stdout
    def write(name, data): call('shell', 'run-as dev.findroid sh -c ' + shlex.quote('cat > ' + shlex.quote(name)), data=data)
    def launch():
        shell('am', 'start', '-W', '-f', '0x10008000', '--windowingMode', 1, '-n', 'dev.findroid/.MainActivity', '--es', 'finderWindowId', 'main')
    def dump(name):
        shell('uiautomator', 'dump', '/sdcard/findroid-setup-qa.xml')
        data = call('exec-out', 'cat', '/sdcard/findroid-setup-qa.xml')
        (args.output / (name + '.xml')).write_bytes(data)
        return ET.fromstring(data)
    def bounds(node): return list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    def nodes(tree, label):
        return [n for n in tree.iter('node') if (n.get('text') == label or n.get('content-desc') == label) and len(bounds(n)) == 4 and bounds(n)[2] > bounds(n)[0] and bounds(n)[3] > bounds(n)[1]]
    def one(tree, label):
        found = nodes(tree, label)
        assert len(found) == 1, (label, len(found))
        return found[0]
    def tap(node):
        l, t, r, b = bounds(node)
        x, y = (l+r)//2, (t+b)//2
        if native_tap:
            shell('env', 'CLASSPATH=' + native_tap, 'app_process', '/system/bin', 'dev.findroid.qa.NativeTap', x, y)
        else:
            shell('input', 'tap', x, y)
    def wait(name, predicate, seconds=45):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            tree = dump(name)
            if predicate(tree): return tree
            time.sleep(.15)
        raise AssertionError('UI did not settle: ' + name)
    def ready(tree, label): return any(n.get('enabled') == 'true' for n in nodes(tree, label))
    def capture(name):
        previous = call('exec-out', 'screencap', '-p')
        for _ in range(4):
            time.sleep(.15)
            current = call('exec-out', 'screencap', '-p')
            if current == previous: break
            previous = current
        (args.output / (name + '.png')).write_bytes(current)
    def click(name, label): tap(one(wait(name, lambda t: ready(t, label)), label))
    def scroll_to(name, label, x=.5):
        for _ in range(8):
            tree = dump(name)
            if nodes(tree, label): return tree
            width, height = screen
            shell('input', 'swipe', int(width*x), int(height*.74), int(width*x), int(height*.40), 380)
        raise AssertionError('Could not scroll to ' + label)
    def preferences():
        click('browser', 'Actions')
        tree = scroll_to('more', 'Preferences…', x=.9); tap(one(tree, 'Preferences…'))
        click('preferences', 'Back up or restore setup…')
        return wait('setup-home', lambda t: bool(nodes(t, 'Create setup backup')))
    def downloads(name):
        tree = wait(name, lambda t: any(n.get('package') == 'com.google.android.documentsui' for n in t.iter('node')))
        def drawers(value):
            return [n for n in value.iter('node') if n.get('resource-id', '').endswith('/roots_list') and bounds(n)[2] > bounds(n)[0]]
        for label in ('Show roots', 'Show navigation drawer'):
            if nodes(tree, label):
                tap(one(tree, label))
                tree = wait(name + '-roots', lambda t: bool(drawers(t)))
                break
        drawer = drawers(tree)
        assert len(drawer) == 1, 'Android document picker did not show its roots drawer'
        tap(one(drawer[0], 'Downloads'))
        return wait(name + '-downloads', lambda t: bool(nodes(t, 'Downloads')) and not drawers(t))
    def set_pref(root, kind, name, value):
        for child in list(root):
            if child.get('name') == name: root.remove(child)
        child = ET.SubElement(root, kind, name=name)
        if kind == 'string': child.text = value
        elif kind == 'set':
            for item in value: ET.SubElement(child, 'string').text = item
        else: child.set('value', str(value).lower())
    def prefs_state():
        result = {}
        for store, keys in KEYS.items():
            data = read('shared_prefs/' + store + '.xml', optional=True)
            values = {}
            for item in ET.fromstring(data) if data else []:
                if item.get('name') not in keys: continue
                values[item.get('name')] = {'type': item.tag, 'values': sorted(n.text or '' for n in item) if item.tag == 'set' else [item.text or '' if item.tag == 'string' else item.get('value')]}
            result[store] = values
        return result
    def install_debug(): stop(); call('install', '-r', debug)
    def install_optimized(): stop(); call('install', '-r', args.apk)
    def private_snapshot():
        names = shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        names += ['files/' + f for f in FILES]
        return {name: read(name, optional=True) for name in names}
    def restore(snapshot):
        current = shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        for name in set(current)-set(snapshot): shell('run-as', 'dev.findroid', 'rm', '-f', name)
        for name, data in snapshot.items():
            if data is None: shell('run-as', 'dev.findroid', 'rm', '-f', name)
            else: write(name, data)
        for f in FILES: shell('run-as', 'dev.findroid', 'rm', '-f', 'files/'+f+'.bak', 'files/'+f+'.new')

    # API30 DocumentsUI routes gestures by tool type. Its shell input tap omits
    # TOOL_TYPE_FINGER, so use a transient shell helper without instrumenting or
    # restarting the optimized app or the external picker.
    if int(shell('getprop', 'ro.build.version.sdk').decode()) <= 30:
        sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or Path.home() / 'Library/Android/sdk')
        java = Path(os.environ.get('JAVA_HOME') or Path.home() / 'Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1/Contents/Home')
        android_jar = sdk / 'platforms/android-36/android.jar'
        helper_source = ROOT / 'scripts/android/NativeTap.java'
        with tempfile.TemporaryDirectory(prefix='findroid-native-tap-') as temporary:
            folder = Path(temporary)
            classes = folder / 'classes'; classes.mkdir()
            dex = folder / 'dex'; dex.mkdir()
            subprocess.run([str(java / 'bin/javac'), '-source', '8', '-target', '8', '-bootclasspath', str(android_jar), '-d', str(classes), str(helper_source)], check=True)
            subprocess.run([str(sdk / 'build-tools/36.0.0/d8'), '--lib', str(android_jar), '--output', str(dex), str(classes / 'dev/findroid/qa/NativeTap.class')], env={**os.environ, 'JAVA_HOME': str(java)}, check=True)
            archive = folder / 'native-tap.jar'
            with zipfile.ZipFile(archive, 'w') as output:
                output.write(dex / 'classes.dex', 'classes.dex')
            native_tap = '/data/local/tmp/findroid-setup-tap-' + stamp + '.jar'
            call('push', archive, native_tap)
    original_scale = shell('settings', 'get', 'system', 'font_scale').decode().strip()
    size = shell('wm', 'size').decode(); screen = list(map(int, re.findall(r'(\d+)x(\d+)', size)[-1]))
    try:
        install_debug()
        assert shell('run-as', 'dev.findroid', 'test', '!', '-e', 'files/.setup-transaction', check=False).returncode == 0, 'A pre-existing setup recovery needs attention first.'
        saved = private_snapshot()
        prefs = ET.fromstring(saved['shared_prefs/finder.xml'])
        set_pref(prefs, 'string', 'tabs', json.dumps([destination])); set_pref(prefs, 'int', 'activeTab', 0)
        set_pref(prefs, 'boolean', 'dualPane', False); set_pref(prefs, 'boolean', 'sidebarVisible', True)
        set_pref(prefs, 'boolean', 'dark', False); set_pref(prefs, 'boolean', 'gestureHintDismissed', True)
        set_pref(prefs, 'string', 'density', 'COMFORTABLE'); set_pref(prefs, 'string', 'view', 'LIST')
        set_pref(prefs, 'string', 'setupAcceptanceSentinel', stamp)
        sidebar = {'version':1,'favorites':[{'id':'current-project','path':destination+'/Current folder','label':'Current project'}],
                   'groups':[],'builtins':['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],
                   'hidden':['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC']}
        set_pref(prefs, 'string', 'sidebarLayoutV1', json.dumps(sidebar)); set_pref(prefs, 'set', 'favorites', [destination+'/Current folder'])
        write('shared_prefs/finder.xml', ET.tostring(prefs, encoding='utf-8', xml_declaration=True))
        write('files/workspaces.json', b'{"version":1,"workspaces":[]}')
        write('shared_prefs/locations.xml', b'<?xml version="1.0" encoding="utf-8"?><map><string name="roots">[]</string></map>')
        for name, data in originals.items():
            shell('run-as','dev.findroid','mkdir','-p','files/'+relative+'/Restored tablet/'+str(Path(name).parent))
            write('files/'+relative+'/Restored tablet/'+name, data)
        shell('mkdir', '-p', '/sdcard/Download')
        call('shell', 'cat > ' + shlex.quote(public_source), data=json.dumps(fixture).encode())
        if args.previous_apk:
            call('install','-r','-d',args.previous_apk); launch()
            wait('previous-version',lambda t:bool(nodes(t,'Options for Current project')))
            report['actualPreviousVersionUpgrade'] = True
        install_optimized(); launch()
        wait('upgraded',lambda t:bool(nodes(t,'Options for Current project')))
        installed = shell('pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        assert hashlib.sha256(call('exec-out','cat',installed)).hexdigest() == report['apkSha256']
        report['installedOptimizedBytes'] = True
        preferences(); capture('overview')
        click('preparing-export','Create setup backup'); click('ready-export','Save backup…')
        tree = downloads('android-save-picker')
        tree = wait('save-filename',lambda t:any(n.get('class')=='android.widget.EditText' for n in t.iter('node')))
        field = next(n for n in tree.iter('node') if n.get('class')=='android.widget.EditText')
        tap(field); shell('input','keyevent',123); shell('input','keyevent',*[67]*(len(field.get('text',''))+4)); shell('input','text',export_name)
        tree = wait('named-export',lambda t:any(n.get('class')=='android.widget.EditText' and n.get('text')==export_name for n in t.iter('node')))
        capture('android-save-picker')
        save_labels = [label for label in ('Save','SAVE') if nodes(tree,label)]; assert save_labels
        tap(one(tree,save_labels[0]))
        wait('exported',lambda t:any('Setup backup saved' in n.get('text','') for n in t.iter('node')))
        exported = shell('cat',public_export)
        exported_json = json.loads(exported)
        assert exported_json['format']=='dev.findroid.setup' and exported_json['version']==1
        assert not any(data.strip() in exported for data in originals.values())
        report.update(realAndroidCreateDocument=True,exportedBackupSha256=hashlib.sha256(exported).hexdigest(),fileBytesAbsentFromBackup=True)
        click('choose-backup','Choose backup to restore…'); downloads('android-open-picker')
        tree = scroll_to('source-backup',source_name,x=.65); capture('android-open-picker')
        # DocumentsUI's grid title is a separate text/selection region. Open the actual
        # item body, whose ID is shared by its grid and list representations.
        items = [n for n in tree.iter('node') if n.get('resource-id', '').endswith('/item_root') and nodes(n, source_name)]
        assert len(items) == 1, 'Could not identify the synthetic backup in the Android picker'
        tap(items[0])
        wait('import-loaded',lambda t:ready(t,'Review changes'))
        tree = scroll_to('replacement-root','Choose replacement folder…'); tap(one(tree,'Choose replacement folder…'))
        click('root-picker','Use this folder')
        tree = wait('root-selected',lambda t:ready(t,'Review changes')); capture('import-roots')
        tap(one(tree,'Review changes'))
        wait('review',lambda t:ready(t,'Restore setup')); capture('review')
        click('apply','Restore setup')
        wait('restored',lambda t:any('Setup restored.' in n.get('text','') for n in t.iter('node'))); capture('restored')
        stop(); launch(); wait('restart',lambda t:bool(nodes(t,'Options for Writing projects'))); capture('persisted-sidebar')
        report.update(realAndroidOpenDocument=True,reviewedRestore=True,restartPersistence=True)
        preferences()
        shell('settings','put','system','font_scale','1.6'); stop(); launch(); preferences(); capture('large-text')
        tree = dump('large-text'); assert ready(tree,'Close') and ready(tree,'Create setup backup')
        report['fontScale1_6ReachableActions'] = True
        shell('settings','put','system','font_scale',original_scale)
        install_debug()
        final_prefs = ET.fromstring(read('shared_prefs/finder.xml'))
        assert final_prefs.find("./string[@name='setupAcceptanceSentinel']").text == stamp
        layout = json.loads(final_prefs.find("./string[@name='sidebarLayoutV1']").text)
        assert any(x['label']=='Writing projects' and x['path']==destination+'/Projects' for x in layout['favorites'])
        assert any(x['label']=='Current project' for x in layout['favorites'])
        assert [v['name'] for v in json.loads(read('files/workspaces.json'))['workspaces'] if v['name'] in ('Writing desk','Restored open tabs')] == ['Writing desk','Restored open tabs']
        for name,data in originals.items(): assert read('files/'+relative+'/Restored tablet/'+name)==data
        assert storage_format_ok(read('files/setup-previous.json'))
        report.update(originalFileBytes=True,originalFavoriteKept=True,sentinelPreferencePreserved=True,previousSetupRecoveryCopy=True)
        # Simulate an abrupt process exit after some metadata was written. The shipping app
        # must roll back from its prepared journal before constructing any browser model.
        interrupted = {name:read('files/'+name,optional=True) for name in FILES}
        old_prefs = prefs_state()
        shell('run-as','dev.findroid','mkdir','-p','files/.setup-transaction/old')
        for name,data in interrupted.items():
            if data is not None: write('files/.setup-transaction/old/'+name,data)
        manifest={'version':1,'phase':'prepared','oldFiles':sorted(name for name,data in interrupted.items() if data is not None),'preferences':old_prefs}
        write('files/.setup-transaction/manifest.json',json.dumps(manifest).encode())
        write('files/workspaces.json',b'Interrupted incomplete workspace write')
        damaged = ET.fromstring(read('shared_prefs/finder.xml')); set_pref(damaged,'string','sidebarLayoutV1',json.dumps({**sidebar,'favorites':[]}))
        write('shared_prefs/finder.xml',ET.tostring(damaged,encoding='utf-8',xml_declaration=True))
        install_optimized(); launch(); wait('startup-recovered',lambda t:bool(nodes(t,'Options for Writing projects')))
        install_debug()
        for name,data in interrupted.items(): assert read('files/'+name,optional=True)==data
        assert prefs_state()==old_prefs
        assert shell('run-as','dev.findroid','test','!','-e','files/.setup-transaction',check=False).returncode==0
        report['shippingStartupRollsBackInterruptedRestore'] = True
        # A damaged recovery record blocks browsing and keeps the recovery files for retry.
        shell('run-as','dev.findroid','mkdir','-p','files/.setup-transaction')
        write('files/.setup-transaction/manifest.json',b'{"version":99,"phase":"prepared","oldFiles":[],"preferences":{}}')
        install_optimized(); launch()
        wait('recovery-gate',lambda t:ready(t,'Retry setup recovery')); capture('recovery-gate')
        install_debug(); shell('run-as','dev.findroid','rm','-rf','files/.setup-transaction')
        for name,data in interrupted.items(): assert read('files/'+name,optional=True)==data
        report['damagedRecoveryBlocksBrowsingWithoutOverwritingSetup'] = True
    except Exception:
        try: capture('failure')
        except Exception: pass
        raise
    finally:
        stop(); shell('settings','put','system','font_scale',original_scale)
        if saved is not None:
            install_debug()
            restore(saved)
            shell('run-as','dev.findroid','rm','-rf','files/'+relative,'files/.setup-transaction')
            for name,data in saved.items(): assert read(name,optional=True)==data
            report['restoredOriginalSetup'] = True
        shell('rm','-f',public_source,public_export,'/sdcard/findroid-setup-qa.xml')
        install_optimized()
        if native_tap: shell('rm', '-f', native_tap)
    (args.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


def storage_format_ok(data):
    value=json.loads(data)
    return value['format']=='dev.findroid.setup' and value['version']==1


if __name__ == '__main__': main()
