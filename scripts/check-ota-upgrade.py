#!/usr/bin/env python3
"""Verify a real public-feed update and Android installation with synthetic legacy setup."""
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
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--from-apk', type=Path, required=True)
    p.add_argument('--to-apk', type=Path, required=True)
    p.add_argument('--expected-version-code', type=int, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
    debug = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    def call(*parts, data=None):
        result = subprocess.run([adb, '-s', args.serial, *map(str, parts)], input=data,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
        if result.returncode: raise RuntimeError(result.stderr.decode(errors='replace'))
        return result.stdout
    def shell(*parts): return call('shell', shlex.join(map(str, parts)))
    def stop(): shell('am', 'force-stop', 'dev.findroid')
    def read(path): return call('exec-out', 'run-as', 'dev.findroid', 'cat', path)
    def write(path, data): call('shell', 'run-as dev.findroid sh -c ' + shlex.quote('cat > ' + shlex.quote(path)), data=data)
    def version(): return int(re.search(r'versionCode=(\d+)', shell('dumpsys', 'package', 'dev.findroid').decode()).group(1))
    def dump(name):
        shell('uiautomator', 'dump', '/sdcard/findroid-ota-qa.xml')
        data = call('exec-out', 'cat', '/sdcard/findroid-ota-qa.xml')
        (args.output / f'{name}.xml').write_bytes(data); return ET.fromstring(data)
    def nodes(root, name): return [n for n in root.iter('node') if n.get('text') == name or n.get('content-desc') == name]
    def wait(name, predicate, seconds=45):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            root = dump(name)
            if predicate(root): return root
            time.sleep(.2)
        raise AssertionError('Timed out: ' + name)
    def tap(node):
        l,t,r,b = map(int,re.findall(r'\d+',node.get('bounds')));shell('input','tap',(l+r)//2,(t+b)//2)
    def one(root, name):
        result = nodes(root, name); assert len(result) == 1, (name, len(result)); return result[0]
    def capture(name): (args.output / f'{name}.png').write_bytes(call('exec-out', 'screencap', '-p'))
    stamp = str(time.time_ns())[-10:]
    relative = 'Workspace/OTA sidebar proof ' + stamp
    full = '/data/user/0/dev.findroid/files/' + relative
    original_bytes = b'Original file bytes survive this real OTA installation.\n'
    original_setup = None
    metadata = ('tags.properties','workspaces.json','folder-views.json','folder-styles.properties',
                'rename-presets.properties','setup-previous.json','document-parents.properties')
    def snapshot():
        paths=shell('run-as','dev.findroid','find','shared_prefs','-maxdepth',1,'-type','f').decode().splitlines()
        paths += ['files/'+name+suffix for name in metadata for suffix in ('','.bak','.new')]
        result={}
        for path in paths:
            value=subprocess.run([adb,'-s',args.serial,'exec-out','run-as','dev.findroid','cat',path],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120)
            result[path]=value.stdout if value.returncode==0 else None
        return result
    def restore():
        paths=shell('run-as','dev.findroid','find','shared_prefs','-maxdepth',1,'-type','f').decode().splitlines()
        for path in set(paths)-set(original_setup): shell('run-as','dev.findroid','rm','-f',path)
        for path,data in original_setup.items():
            if data is None: shell('run-as','dev.findroid','rm','-f',path)
            else: write(path,data)
        assert snapshot()==original_setup, 'Original setup restoration must be exact'

    def launch():
        shell('am', 'start', '-W', '-f', '0x10008000', '--windowingMode', 1, '-n', 'dev.findroid/.MainActivity',
            '--es', 'finderWindowId', 'ota-sidebar-' + stamp, '--es', 'finderLocation', full)
    def updates():
        launch(); root = wait('browser', lambda t: bool(nodes(t, 'Actions'))); tap(one(root, 'Actions'))
        root = wait('more', lambda t: bool(nodes(t, 'App updates'))); tap(one(root, 'App updates'))
    try:
        stop(); call('install', '-r', debug)
        assert subprocess.run([adb,'-s',args.serial,'shell','run-as','dev.findroid','test','!','-e','files/.setup-transaction'],stdout=subprocess.PIPE,stderr=subprocess.PIPE).returncode==0
        original_setup = snapshot()
        # Public evidence shows a synthetic location and the default tag catalog only.
        for name in metadata:
            for suffix in ('','.bak','.new'): shell('run-as','dev.findroid','rm','-f','files/'+name+suffix)
        write('shared_prefs/locations.xml',b'<?xml version="1.0" encoding="utf-8"?><map><string name="roots">[]</string></map>')
        prefs = ET.fromstring(original_setup['shared_prefs/finder.xml'])
        for item in list(prefs):
            if item.get('name') in ('favorites', 'sidebarLayoutV1', 'otaAcceptanceSentinel','smartFolders','tagsVisible','sidebarVisible','dark','tabs','closedTabs','activeTab','dualPane','activePane','panesReversed','view'): prefs.remove(item)
        ET.SubElement(prefs,'string',name='smartFolders').text='[]'
        ET.SubElement(prefs,'string',name='closedTabs').text='[]'
        ET.SubElement(prefs,'string',name='tabs').text=json.dumps([{'id':'ota-baseline','history':[full],'index':0,'query':'','rules':{}}])
        ET.SubElement(prefs,'int',name='activeTab',value='0')
        ET.SubElement(prefs,'string',name='activePane').text='primary'
        ET.SubElement(prefs,'string',name='view').text='LIST'
        for name,value in [('tagsVisible','false'),('sidebarVisible','true'),('dark','false'),('dualPane','false'),('panesReversed','false')]: ET.SubElement(prefs,'boolean',name=name,value=value)
        saved = ET.SubElement(prefs, 'set', name='favorites'); ET.SubElement(saved, 'string').text = full
        ET.SubElement(prefs, 'string', name='otaAcceptanceSentinel').text = stamp
        write('shared_prefs/finder.xml', ET.tostring(prefs, encoding='utf-8', xml_declaration=True))
        shell('run-as', 'dev.findroid', 'mkdir', '-p', 'files/' + relative)
        write('files/' + relative + '/Original.txt', original_bytes)
        call('install', '-r', '-d', args.from_apk); old_version = version()
        assert old_version < args.expected_version_code
        updates(); root = wait('available', lambda t: bool(nodes(t, 'Download update'))); capture('available')
        tap(one(root, 'Download update'))
        root = wait('ready', lambda t: bool(nodes(t, 'Install update')), 90); capture('ready')
        tap(one(root, 'Install update'))
        deadline = time.monotonic() + 80
        while version() != args.expected_version_code and time.monotonic() < deadline:
            root = dump('installer')
            if nodes(root, 'Settings'): tap(one(root, 'Settings'))
            elif nodes(root, 'Allow from this source'):
                switches = [n for n in root.iter('node') if n.get('checkable') == 'true']; assert len(switches) == 1
                if switches[0].get('checked') != 'true': tap(switches[0])
                shell('input', 'keyevent', 'BACK')
            elif nodes(root, 'Install'): capture('installer'); tap(one(root, 'Install'))
            elif nodes(root, 'Update'): capture('installer'); tap(one(root, 'Update'))
            else: time.sleep(.25)
        assert version() == args.expected_version_code, 'Android did not install the public update'
        installed_path = shell('pm', 'path', 'dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        assert hashlib.sha256(call('exec-out', 'cat', installed_path)).digest() == hashlib.sha256(args.to_apk.read_bytes()).digest()
        stop(); launch()
        wait('upgraded-sidebar', lambda t: bool(nodes(t, 'Options for ' + relative.split('/')[-1]))); capture('upgraded-sidebar')
        updates()
        root = wait('up-to-date', lambda t: any("up to date" in n.get('text', '').lower() for n in t.iter('node')), 60)
        capture('up-to-date')
        stop(); call('install', '-r', debug)
        assert read('files/' + relative + '/Original.txt') == original_bytes
        prefs = ET.fromstring(read('shared_prefs/finder.xml'))
        assert prefs.find("./string[@name='otaAcceptanceSentinel']").text == stamp
        assert [n.text for n in prefs.findall("./set[@name='favorites']/string")] == [full]
        report = {'serial': args.serial, 'fromVersionCode': old_version, 'toVersionCode': args.expected_version_code,
            'publicFeedDownloadAndAndroidInstall': True, 'installedApkMatchesPublicBytes': True, 'originalFileBytes': True, 'legacyFavoritePreserved': True,
            'sentinelPreferencePreserved': True, 'updatedSidebarVisible': True, 'noRepeatOffer': True,
            'restoredApkSha256': hashlib.sha256(args.to_apk.read_bytes()).hexdigest()}
    finally:
        stop()
        if original_setup is not None:
            call('install', '-r', debug); restore()
            shell('run-as', 'dev.findroid', 'rm', '-rf', 'files/' + relative)
        call('install', '-r', args.to_apk)
    report['restoredOriginalSetup'] = True
    (args.output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__': main()
