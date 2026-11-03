#!/usr/bin/env python3
"""Check an optimized 0.39 -> 0.40 local-index upgrade using synthetic files and native touch.

Private originals stay in memory and are restored byte-for-byte. Never clears/uninstalls
an app or selects a device implicitly. Run only after native acceptance has released both tablets.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import tarfile
import tempfile
import time
import xml.etree.ElementTree as ET
from android.acceptance import AndroidDevice, ROOT

FILES = {'tags.properties', 'workspaces.json', 'folder-views.json', 'folder-styles.properties',
         'rename-presets.properties', 'setup-previous.json', 'document-parents.properties'}
CACHE = ['no_backup/local-search-index/metadata.sqlite'+suffix for suffix in ('', '-wal', '-shm', '-journal')]


class InspectionDevice(AndroidDevice):
    def __init__(self, *args, **kwargs):
        self.retries = []
        self.fixture_extractor = None
        self.fixture_stops = []
        super().__init__(*args, **kwargs)

    def dump(self, name):
        for attempt in range(1, 4):
            try:
                return super().dump(name)
            except RuntimeError as error:
                if 'uiautomator dump' not in str(error):
                    raise
                self.retries.append({'capture': name, 'attempt': attempt, 'error': str(error)})
                if attempt == 3:
                    raise
                time.sleep(.2)


    def stop_fixture_extractor(self):
        if self.fixture_extractor is None: return
        source, marker = self.fixture_extractor
        result = self.call('exec-out', 'cat', marker, check=False)
        pid = result.stdout.decode().strip() if result.returncode == 0 else ''
        def is_fixture_writer():
            if not pid.isdigit(): return False
            found = self.call('exec-out', 'cat', '/proc/'+pid+'/cmdline', check=False)
            args = found.stdout.decode(errors='replace').split('\0') if found.returncode == 0 else []
            return source in args and any(Path(arg).name == 'tar' for arg in args)
        if is_fixture_writer():
            self.shell('kill', '-TERM', pid, check=False)
            for _ in range(10):
                if not is_fixture_writer(): break
                time.sleep(.1)
            if is_fixture_writer(): self.shell('kill', '-KILL', pid, check=False)
            for _ in range(10):
                if not is_fixture_writer(): break
                time.sleep(.1)
            assert not is_fixture_writer(), 'The synthetic fixture extractor did not stop.'
            self.fixture_stops.append({'pid': pid, 'onlyExactSyntheticTarCommandTargeted': True})
        self.shell('rm', '-f', marker)
        self.fixture_extractor = None

    def extract_fixture(self, source, archive):
        marker = '/data/local/tmp/findroid-index-fixture-'+str(time.time_ns())+'.pid'
        self.fixture_extractor = source, marker
        script = 'printf "%s\\n" "$$" > '+shlex.quote(marker)+'\nexec tar -xf - -C '+shlex.quote(source)
        # This deadline is only for writing the 50k synthetic files, not a product operation.
        # Record the remote PID so timeout/cancellation cannot leave a writer racing cleanup.
        started = time.monotonic(); completed = False
        print('Preparing 50,000 synthetic files (fixture-only deadline: 1800 seconds).', flush=True)
        try:
            result = subprocess.run([self.adb, '-s', self.serial, 'shell', 'sh -c '+shlex.quote(script)],
                input=archive, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=1800)
            if result.returncode: raise RuntimeError('Synthetic extraction failed: '+result.stderr.decode(errors='replace'))
            completed = True
            return round(time.monotonic()-started, 2)
        finally:
            if completed:
                self.shell('rm', '-f', marker); self.fixture_extractor = None
            else:
                self.stop_fixture_extractor()

    def remove_fixture(self, path):
        result = subprocess.run([self.adb, '-s', self.serial, 'shell', shlex.join(['rm', '-rf', path])],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=900)
        if result.returncode: raise RuntimeError('Synthetic cleanup failed: '+result.stderr.decode(errors='replace'))
        assert self.shell('test', '!', '-e', path, check=False).returncode == 0


class Dialogs:
    def __init__(self, device, density):
        self.device, self.density = device, density

    def panel(self, tree, title, footer):
        d = self.device
        parents = {child: parent for parent in tree.iter() for child in parent}
        def clickable_ancestor(node):
            while node in parents:
                node = parents[node]
                if node.get('clickable') == 'true': return True
            return False
        headings = [n for n in d.nodes(tree, title) if n.get('text') == title and not clickable_ancestor(n)]
        assert len(headings) == 1, (title, len(headings))
        node = headings[0]
        while not d.nodes(node, footer):
            assert node in parents, (title, footer)
            node = parents[node]
        return node

    def control(self, tree, title, footer, label, owner=None):
        d = self.device
        scope = self.panel(tree, title, footer)
        parents = {child: parent for parent in scope.iter() for child in parent}
        if owner is not None:
            owners = [n for n in scope.iter('node') if n.get('text') == owner]
            if not owners: return None
            assert len(owners) == 1, (owner, len(owners))
            scope = owners[0]
            while not d.nodes(scope, label):
                if scope not in parents: return None
                scope = parents[scope]
        controls = []
        for node in d.nodes(scope, label):
            while node.get('clickable') != 'true' and node in parents: node = parents[node]
            if node.get('clickable') == 'true' and node not in controls: controls.append(node)
        if owner is not None and not controls: return None
        assert len(controls) == 1, (title, label, len(controls))
        return controls[0]

    def full(self, tree, node):
        if node is None: return False
        d = self.device
        l, t, r, b = d.bounds(node); w, h = d.screen
        if not (0 <= l < r <= w and 0 <= t < b <= h and r-l >= 48*self.density and b-t >= 48*self.density): return False
        parents = {child: parent for parent in tree.iter() for child in parent}
        while node in parents:
            node = parents[node]
            if node.get('scrollable') == 'true':
                pl, pt, pr, pb = d.bounds(node)
                if not (pl <= l < r <= pr and pt <= t < b <= pb): return False
        return True

    def reach(self, name, title, footer, label, owner=None):
        d = self.device
        for direction in (1, -1):
            previous = None
            for _ in range(18):
                tree = d.dump(name); scope = self.panel(tree, title, footer)
                if d.nodes(scope, label):
                    node = self.control(tree, title, footer, label, owner)
                    if node is not None and node.get('enabled') == 'true' and self.full(tree, node): return tree, node
                panes = [n for n in scope.iter('node') if n.get('scrollable') == 'true']
                if not panes: break
                assert len(panes) == 1, (title, len(panes))
                pane = panes[0]; signature = ET.tostring(pane)
                if previous == signature: break
                previous = signature; l, t, r, b = d.bounds(pane)
                start, end = (.8, .3) if direction == 1 else (.3, .8)
                d.shell('input', 'swipe', (l+r)//2, round(t+(b-t)*start), (l+r)//2, round(t+(b-t)*end), 350)
        raise AssertionError((title, label, owner, 'no complete enabled 48 dp control'))

    def click(self, name, title, footer, label, owner=None):
        tree, node = self.reach(name, title, footer, label, owner)
        self.device.tap(node)
        return tree

    def footer(self, tree, title, footer, labels):
        for label in labels:
            node = self.control(tree, title, footer, label)
            assert node.get('enabled') == 'true' and self.full(tree, node), (title, label, node.attrib)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--previous-apk', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    assert args.serial in {'emulator-5602', 'emulator-5582'}
    assert not args.output.exists(), 'Preserve earlier attempts; use a fresh output directory.'
    d = InspectionDevice(args.serial, args.output, all_windows=True)
    density = int(re.findall(r'\d+', d.shell('wm', 'density').decode())[-1])/160
    ui = Dialogs(d, density)
    debug = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    demo = '/storage/emulated/0/Download/Findroid index demo '+str(time.time_ns())[-10:]
    source, target = demo+'/Writing project', demo+'/Research project'
    payload = b'Synthetic project needle in a text report.\n'*45
    paths = ['Writing project/Report current.txt', 'Writing project/Work/Report nested.txt',
             'Writing project/Cache/Report excluded.txt', 'Research project/Report other.txt']
    rules = dict(name='Report', kind='Text document', tag='', days=0, min=0, max=0,
                 content='needle', terms=[], tags=[], tagMatch='ALL', includeSubfolders=True,
                 excludedFolderNames=['Scratch'], excludedLocations=[dict(path=source+'/Cache', folder=True)],
                 locations=[source, target])
    report = dict(serial=args.serial, checkerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), apkSha256=hashlib.sha256(args.apk.read_bytes()).hexdigest(), previousApkSha256=hashlib.sha256(args.previous_apk.read_bytes()).hexdigest(), fromVersionCode=41, toVersionCode=42)
    original_scale = d.shell('settings', 'get', 'system', 'font_scale').decode().strip()
    original_screen = d.screen
    size = d.shell('wm', 'size').decode(); found = re.search(r'Override size: (\d+x\d+)', size)
    original_size = found.group(1) if found else 'reset'
    access = d.shell('appops', 'get', 'dev.findroid', 'MANAGE_EXTERNAL_STORAGE').decode()
    found = re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)', access)
    original_access = found.group(1) if found else 'default'
    saved = None
    report['originalStorageAccessMode'] = original_access
    report['fixturePreparationDeadlineSeconds'] = 1800

    def stop(): d.shell('am', 'force-stop', 'dev.findroid')
    def install(path, downgrade=False):
        stop(); d.call('install', '-r', *(['-d'] if downgrade else []), path)
    def launch(window='main', location=None):
        parts = ['am', 'start', '-W', '-f', '0x10008000', '--windowingMode', 1, '-n', 'dev.findroid/.MainActivity', '--es', 'finderWindowId', window]
        if location is not None: parts += ['--es', 'finderLocation', location]
        d.shell(*parts)
    def version():
        return int(re.search(r'versionCode=(\d+)', d.shell('dumpsys', 'package', 'dev.findroid').decode()).group(1))
    def installed_digest():
        path = d.shell('pm', 'path', 'dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        return hashlib.sha256(d.call('exec-out', 'cat', path)).hexdigest()
    def read(path, optional=False):
        # Raw exec-out does not reliably transport remote failure status. Shell-v2
        # preserves binary stdout and distinguishes absence from a failed read.
        script = ('if test -f '+shlex.quote(path)+'; then cat '+shlex.quote(path)+
                  '; elif test -e '+shlex.quote(path)+'; then exit 42; else exit 44; fi')
        result = d.call('shell', '-T', shlex.join(['run-as', 'dev.findroid', 'sh', '-c', script]), check=False)
        if optional and result.returncode == 44: return None
        assert result.returncode == 0, 'Could not audit '+path
        return result.stdout
    def write(path, data):
        d.shell('run-as', 'dev.findroid', 'mkdir', '-p', str(Path(path).parent))
        d.call('shell', 'run-as dev.findroid sh -c '+shlex.quote('cat > '+shlex.quote(path)), data=data)
    def snapshot():
        names = d.shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        names += ['files/'+name+suffix for name in FILES for suffix in ('', '.bak', '.new')]+CACHE
        return {name: read(name, True) for name in names}
    def restore():
        stop()
        current = d.shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        for name in set(current)-set(saved): d.shell('run-as', 'dev.findroid', 'rm', '-f', name)
        for name, data in saved.items():
            if data is None: d.shell('run-as', 'dev.findroid', 'rm', '-f', name)
            else: write(name, data)
        assert all(read(name, True) == data for name, data in saved.items()), 'Original setup/cache bytes were not restored.'
    def pref(tree, kind, name, value):
        for child in list(tree):
            if child.get('name') == name: tree.remove(child)
        row = ET.SubElement(tree, kind, {'name': name})
        if kind == 'string': row.text = value
        elif kind == 'set':
            for item in value: ET.SubElement(row, 'string').text = item
        else: row.set('value', str(value).lower())
    def seed():
        for name in FILES:
            for suffix in ('', '.bak', '.new'): d.shell('run-as', 'dev.findroid', 'rm', '-f', 'files/'+name+suffix)
        prefs = ET.fromstring(saved.get('shared_prefs/finder.xml') or b'<map/>')
        for old in list(prefs):
            if old.get('name') in {'recentSearchesV1', 'rememberRecentSearches'} or old.get('name', '').startswith('tag:'): prefs.remove(old)
        tab = dict(history=[source], index=0, view='LIST', sort='NAME', ascending=True, query='Report', rules=rules)
        for kind, name, value in [('string', 'tabs', json.dumps([tab])), ('int', 'activeTab', 0), ('boolean', 'dualPane', False),
            ('string', 'activePane', 'primary'), ('boolean', 'panesReversed', False),
            ('boolean', 'dark', False), ('boolean', 'sidebarVisible', True), ('boolean', 'tagsVisible', False),
            ('boolean', 'preview', False), ('string', 'density', 'COMFORTABLE'), ('string', 'view', 'LIST'), ('boolean', 'gestureHintDismissed', True),
            ('string', 'smartFolders', '[]'), ('string', 'indexAcceptanceSentinel', demo), ('boolean', 'rememberRecentSearches', True)]:
            pref(prefs, kind, name, value)
        sidebar = dict(version=1, favorites=[dict(id='index-writing', path=source, label='Writing project'), dict(id='index-research', path=target, label='Research project')],
            groups=[], builtins=['RECENTS', 'WORKSPACE', 'DOCUMENTS', 'DOWNLOADS', 'PICTURES', 'MOVIES', 'MUSIC'], hidden=['DOCUMENTS', 'DOWNLOADS', 'PICTURES', 'MOVIES', 'MUSIC'])
        pref(prefs, 'string', 'sidebarLayoutV1', json.dumps(sidebar)); pref(prefs, 'set', 'favorites', [source, target])
        write('shared_prefs/finder.xml', ET.tostring(prefs, encoding='utf-8', xml_declaration=True))
        write('shared_prefs/locations.xml', b'<?xml version="1.0" encoding="utf-8"?><map><string name="roots">[]</string></map>')
        for path in CACHE: d.shell('run-as', 'dev.findroid', 'rm', '-f', path)
        for name in paths:
            path = demo+'/'+name; d.shell('mkdir', '-p', str(Path(path).parent))
            d.call('shell', 'cat > '+shlex.quote(path), data=payload)
    def ready_browser(tree):
        if not d.ready(tree, 'Actions'): return False
        if any(window.get('type') == 'TYPE_INPUT_METHOD' for window in tree.iter('window')):
            d.shell('input', 'keyevent', 'BACK')
            return False
        return True
    def browser(name): return d.wait(name, ready_browser)
    def menu(name, label):
        d.click(name+'-open', 'Actions')
        for direction in (-1, 1):
            previous = None
            for _ in range(14):
                tree = d.wait(name, lambda t: bool(d.nodes(t, label)) or any(
                    n.get('scrollable') == 'true' and n.get('class') == 'android.widget.ScrollView'
                    for w in t.iter('window') if w.get('title') == 'Pop-Up Window' for n in w.iter('node')))
                if d.nodes(tree, label): d.tap(d.one(tree, label)); return
                panes = [n for w in tree.iter('window') if w.get('title') == 'Pop-Up Window'
                         for n in w.iter('node') if n.get('scrollable') == 'true' and n.get('class') == 'android.widget.ScrollView']
                assert len(panes) == 1, (name, len(panes))
                signature = ET.tostring(panes[0])
                if previous == signature: break
                previous = signature
                l, t, r, b = d.bounds(panes[0]); start, end = (.25, .8) if direction == -1 else (.8, .25)
                d.shell('input', 'swipe', (l+r)//2, round(t+(b-t)*start), (l+r)//2, round(t+(b-t)*end), 300)
        raise AssertionError('Menu action unreachable: '+label)
    def manager(name):
        menu(name, 'Search index…')
        return d.wait(name, lambda tree: bool(d.nodes(tree, 'Search index')) and d.ready(tree, 'Done'))
    def filters(name):
        menu(name, 'Search filters…')
        return d.wait(name, lambda tree: bool(d.nodes(tree, 'Search criteria')) and d.ready(tree, 'Apply filters'))
    def source_choice(name, source_name):
        filters(name)
        ui.click(name+'-source', 'Search criteria', 'Apply filters', source_name)
        d.click(name+'-apply', 'Apply filters')
        return browser(name+'-returned')
    def build_root(name, favorite=None):
        ui.click(name+'-add', 'Search index', 'Done', 'Add local folder…')
        d.wait(name+'-picker', lambda tree: bool(d.nodes(tree, 'Index this folder')) and d.ready(tree, 'Cancel'))
        if favorite: ui.click(name+'-favorite', 'Index this folder', 'Cancel', favorite)
        ui.click(name+'-confirm', 'Index this folder', 'Cancel', 'Index this folder')
        return d.wait(name+'-built', lambda tree: bool(d.nodes(tree, 'Snapshot available')) and not d.nodes(tree, 'Pause refresh'), seconds=120)
    def details(name):
        tree = d.wait(name+'-open', lambda t: len([n for n in t.iter('node') if n.get('content-desc') == 'Result details']) == 1)
        d.tap(next(n for n in tree.iter('node') if n.get('content-desc') == 'Result details'))
        return d.wait(name, lambda tree: bool(d.nodes(tree, 'Result details')) and bool(d.nodes(tree, 'Close Result details')))
    def matches(name, required, absent=()):
        return d.wait(name, lambda tree: ready_browser(tree) and all(d.nodes(tree, text) for text in required) and all(not d.nodes(tree, text) for text in absent), seconds=120)
    def click_root(name, label, root=source): return ui.click(name, 'Search index', 'Done', label, root)
    def audit():
        stop(); install(debug)
        prefs = ET.fromstring(read('shared_prefs/finder.xml'))
        rows = json.loads(prefs.find("./string[@name='tabs']").text)
        with tempfile.TemporaryDirectory(prefix='findroid-synthetic-index-audit-') as temp:
            file = Path(temp)/'metadata.sqlite'
            for name in CACHE:
                data = read(name, True)
                if data is not None: (Path(temp)/Path(name).name).write_bytes(data)
            roots = []
            if file.exists():
                with sqlite3.connect(file) as database:
                    database.row_factory = sqlite3.Row
                    roots = [dict(row) for row in database.execute('SELECT path,state,entries,generation,changed,refreshed FROM roots')]
        return prefs, rows, roots

    try:
        install(debug); saved = snapshot(); seed()
        d.shell('appops', 'set', 'dev.findroid', 'MANAGE_EXTERNAL_STORAGE', 'allow')
        d.shell('settings', 'put', 'system', 'font_scale', '1.0')
        install(args.previous_apk, True); assert version() == 41 and installed_digest() == report['previousApkSha256']
        launch(); matches('previous-live', ['Report current.txt', 'Report nested.txt', 'Report other.txt'], ['Report excluded.txt'])
        d.capture('previous-live')
        install(args.apk); assert version() == 42 and installed_digest() == report['apkSha256']
        launch(); matches('upgrade-live', ['Report current.txt', 'Report nested.txt', 'Report other.txt'], ['Report excluded.txt'])
        report['directPreviousPublicUpgrade'] = True
        manager('empty-index'); tree = d.dump('empty-index')
        assert d.nodes(tree, 'No folders indexed. Add a local folder to begin.'); d.capture('empty-index')
        d.click('close-empty', 'Done'); source_choice('unindexed-source', 'Local index')
        tree = details('unindexed-details'); assert d.nodes(tree, 'Unavailable'); d.capture('unindexed-details')
        ui.click('live-without-opt-in', 'Result details', 'Close Result details', 'Use live folders')
        matches('live-preserves-criteria', ['Report current.txt', 'Report nested.txt', 'Report other.txt'], ['Report excluded.txt'])
        report['sourceChoiceNeverOptsIn'] = True
        manager('build-manager'); build_root('writing'); d.capture('first-snapshot')
        build_root('research', 'Research project'); d.capture('two-snapshots')
        d.click('close-built', 'Done'); source_choice('use-index', 'Local index')
        matches('indexed-results', ['Report current.txt', 'Report nested.txt', 'Report other.txt'], ['Report excluded.txt']); d.capture('indexed-results')
        tree = details('indexed-details'); assert d.nodes(tree, 'Saved entries checked'); d.capture('indexed-details')
        d.click('close-details', 'Close Result details')
        report.update(actualFolderPicker=True, chosenRootsAndExclusions=True, snapshotDatesVisible=True)
        # Current metadata/content changes must be honored; a new unindexed name stays absent.
        d.call('shell', 'cat > '+shlex.quote(source+'/Report current.txt'), data=b'Changed current needle bytes\n')
        d.shell('rm', source+'/Work/Report nested.txt')
        d.call('shell', 'cat > '+shlex.quote(source+'/Report new.txt'), data=b'New unindexed needle document\n')
        d.shell('input', 'keyevent', 135)
        matches('stale-candidates', ['Report current.txt', 'Report other.txt'], ['Report nested.txt', 'Report new.txt'])
        tree = details('stale-details'); d.capture('stale-details'); d.click('close-stale', 'Close Result details')
        manager('refresh-manager'); click_root('refresh-writing', 'Refresh snapshot')
        d.wait('refreshed', lambda tree: bool(d.nodes(tree, 'Snapshot available')) and not d.nodes(tree, 'Pause refresh'), seconds=120)
        d.click('close-refreshed', 'Done')
        matches('refreshed-results', ['Report current.txt', 'Report new.txt', 'Report other.txt'], ['Report nested.txt', 'Report excluded.txt'])
        report.update(currentMetadataRevalidation=True, missingCandidatesNotActionable=True, newPathsRequireRefresh=True, manualRefreshFindsNewPaths=True)
        # Six actual window/font combinations, preserving the source draft through child dialogs.
        layouts = []
        for width, height in ((1280, 800), (800, 1000), (600, 800)):
            for scale in (1.0, 1.6):
                stop(); d.shell('wm', 'size', f'{round(width*density)}x{round(height*density)}'); d.screen = (round(width*density), round(height*density))
                d.shell('settings', 'put', 'system', 'font_scale', scale); launch(); browser('layout-browser')
                key = f'{width}dp-font{scale}'
                tree = filters(key+'-criteria'); ui.footer(tree, 'Search criteria', 'Apply filters', ['Cancel', 'Clear', 'Apply filters', 'Close Search criteria'])
                for label in ('Live folders', 'Local index', 'Manage index…'): ui.reach(key+'-source-'+label, 'Search criteria', 'Apply filters', label)
                d.capture(key+'-criteria')
                ui.click(key+'-manage', 'Search criteria', 'Apply filters', 'Manage index…')
                d.wait(key+'-manager', lambda tree: d.ready(tree, 'Done') and bool(d.nodes(tree, 'Search index')))
                ui.footer(d.dump(key+'-footer'), 'Search index', 'Done', ['Add local folder…', 'Check folders', 'Delete index cache…', 'Done', 'Close Search index'])
                ui.reach(key+'-refresh', 'Search index', 'Done', 'Refresh snapshot', source)
                ui.reach(key+'-remove', 'Search index', 'Done', 'Remove folder…', source); d.capture(key+'-manager')
                ui.click(key+'-picker', 'Search index', 'Done', 'Add local folder…')
                tree = d.wait(key+'-picker-ready', lambda t: d.ready(t, 'Cancel') and bool(d.nodes(t, 'Index this folder')))
                ui.reach(key+'-picker-confirm', 'Index this folder', 'Cancel', 'Index this folder'); d.capture(key+'-picker')
                ui.click(key+'-picker-cancel', 'Index this folder', 'Cancel', 'Cancel')
                d.click(key+'-done', 'Done'); d.wait(key+'-draft-returned', lambda t: bool(d.nodes(t, 'Search criteria')))
                d.click(key+'-cancel', 'Cancel'); browser(key+'-browser')
                tree = details(key+'-details'); ui.footer(tree, 'Result details', 'Close Result details', ['Close Result details'])
                for label in ('Manage index…', 'Use live folders'): ui.reach(key+'-detail-'+label, 'Result details', 'Close Result details', label)
                d.capture(key+'-details'); d.click(key+'-close-details', 'Close Result details')
                layouts.append(dict(widthDp=width, fontScale=scale, fullTouchBounds=True, managementAndPickerReachable=True, draftReturned=True))
                print('Verified layout '+key, flush=True)
        report['layouts'] = layouts
        stop(); d.shell('wm', 'size', original_size); d.screen = original_screen; d.shell('settings', 'put', 'system', 'font_scale', '1.0'); launch(); browser('normal-window')
        menu('save-indexed-smart', 'Save search as Smart Folder…')
        d.wait('smart-name', lambda tree: bool(d.nodes(tree, 'Save Smart Folder')) and d.ready(tree, 'Save'))
        d.click('save-smart', 'Save'); matches('saved-indexed-smart', ['Report current.txt', 'Report new.txt', 'Report other.txt'])
        stop(); launch(); matches('smart-after-restart', ['Report current.txt', 'Report new.txt', 'Report other.txt'])
        d.capture('indexed-smart-after-restart'); report['indexedSmartFolderSurvivesRestart'] = True
        # A single custom window must tolerate repeated task replacement and independent source choices.
        window = 'index-window-'+str(time.time_ns())
        for index in range(5):
            launch(window, target); matches('window-replacement-'+str(index), ['Report other.txt'], ['Report current.txt'])
        d.capture('window-replacement')
        report.update(customWindowTaskReplacement=True, customWindowIterations=5)
        prefs, tabs, roots = audit()
        window_prefs = ET.fromstring(read('shared_prefs/finder-window-'+window+'-primary.xml'))
        window_tabs = json.loads(window_prefs.find("./string[@name='tabs']").text)
        assert window_tabs[0]['rules'].get('source', 'LIVE') == 'LIVE'
        smart = json.loads(prefs.find("./string[@name='smartFolders']").text)
        assert len(smart) == 1 and smart[0]['rules']['source'] == 'LOCAL_INDEX'
        assert smart[0]['rules']['locations'] == [source, target]
        assert tabs[0]['history'][-1] == '@smart:'+smart[0]['id']
        assert prefs.find("./string[@name='indexAcceptanceSentinel']").text == demo
        assert len(roots) == 2 and all(root['state'] == 'READY' and root['generation'] for root in roots)
        (args.output/'synthetic-catalog.json').write_text(json.dumps(roots, indent=2)+'\n')
        (args.output/'synthetic-tabs.json').write_text(json.dumps(tabs, indent=2)+'\n')
        (args.output/'synthetic-smart-folders.json').write_text(json.dumps(smart, indent=2)+'\n')
        retained_generation = next(root['generation'] for root in roots if root['path'] == source)
        install(args.apk); launch(); browser('before-interruption')
        # A real process death during a large refresh must keep the previous completed generation.
        load_folder = source+'/Load'
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode='w') as output:
            for index in range(50_000):
                info = tarfile.TarInfo(f'Load/{index:05}.txt'); data = b'QA\n'; info.size = len(data)
                output.addfile(info, io.BytesIO(data))
        d.shell('mkdir', '-p', load_folder)
        report['fixturePreparationSeconds'] = d.extract_fixture(source, archive.getvalue())
        manager('interrupt-manager'); click_root('interrupt-refresh', 'Refresh snapshot')
        d.wait('refresh-before-process-death', lambda tree: d.ready(tree, 'Pause refresh'))
        stop(); launch(); browser('after-process-death'); manager('interrupted-manager')
        tree = d.wait('interrupted-state', lambda tree: bool(d.nodes(tree, 'Refresh interrupted')))
        d.capture('interrupted-previous-snapshot')
        click_root('resume-after-death', 'Resume (restart scan)')
        d.wait('resume-active', lambda tree: d.ready(tree, 'Pause refresh'))
        click_root('pause-refresh', 'Pause refresh')
        d.wait('paused-state', lambda tree: bool(d.nodes(tree, 'Paused')) and not d.nodes(tree, 'Pause refresh'))
        d.capture('paused-previous-snapshot')
        _, _, paused_roots = audit()
        retained = next(root for root in paused_roots if root['path'] == source)
        assert retained['state'] == 'PAUSED' and retained['generation'] == retained_generation
        (args.output/'synthetic-paused-catalog.json').write_text(json.dumps(paused_roots, indent=2)+'\n')
        report.update(actualProcessDeathDuringRefresh=True, previousGenerationRetainedAfterProcessDeath=True,
            actualPauseAndRestart=True, previousGenerationRetainedAfterPause=True, processDeathFixtureFiles=50_000)
        install(args.apk); launch(); browser('before-cache-removal'); manager('remove-manager')
        click_root('remove-during-refresh-start', 'Resume (restart scan)')
        d.wait('remove-refresh-active', lambda tree: d.ready(tree, 'Pause refresh'))
        click_root('remove-while-refreshing', 'Remove folder…')
        d.wait('remove-confirmation', lambda tree: d.ready(tree, 'Remove from index'))
        d.capture('remove-folder-review'); d.click('confirm-remove-folder', 'Remove from index')
        d.wait('one-root-remaining', lambda tree: not any(n.get('text') == source for n in tree.iter('node')) and d.ready(tree, 'Done'))
        d.click('delete-cache-review', 'Delete index cache…')
        d.wait('delete-review', lambda tree: d.ready(tree, 'Delete cache') and bool(d.nodes(tree, 'Delete index cache?')))
        d.capture('delete-cache-review'); d.click('delete-cache-cancel', 'Cancel')
        d.click('delete-cache-again', 'Delete index cache…'); d.click('confirm-delete-cache', 'Delete cache')
        d.wait('empty-after-delete', lambda tree: bool(d.nodes(tree, 'No folders indexed. Add a local folder to begin.')))
        d.capture('empty-after-delete')
        _, _, cleared = audit(); assert not cleared and all(read(path, True) is None for path in CACHE)
        assert d.call('exec-out', 'cat', load_folder+'/00000.txt') == b'QA\n'
        assert d.call('exec-out', 'cat', load_folder+'/49999.txt') == b'QA\n'
        count = d.call('shell', 'find '+shlex.quote(load_folder)+" -type f | wc -l").decode().strip()
        assert int(count) == 50_000
        report.update(removeDuringRefreshRejectsLateWorker=True, actualCacheDeletionKeepsDocuments=True,
            canceledCacheDeletionKeepsSnapshots=True, lateWorkerDoesNotRecreateDeletedCache=True)
        for name in ('Writing project/Cache/Report excluded.txt', 'Research project/Report other.txt'): assert d.call('exec-out', 'cat', demo+'/'+name) == payload
        assert d.call('exec-out', 'cat', source+'/Report current.txt') == b'Changed current needle bytes\n'
        assert d.call('exec-out', 'cat', source+'/Report new.txt') == b'New unindexed needle document\n'
        report.update(independentPersistedSources=True, originalSyntheticDocumentBytes=True)
        print('Verified source persistence, independent windows, current revalidation and layouts.', flush=True)
    finally:
        cleanup_errors = []
        def cleanup(label, action):
            try: action(); return True
            except Exception as failure:
                cleanup_errors.append({'step': label, 'error': str(failure)})
                return False
        def device_settings():
            stop(); d.shell('wm', 'size', original_size); d.screen = original_screen
            d.shell('settings', 'put', 'system', 'font_scale', original_scale)
        def original_setup():
            install(debug)
            if saved is not None:
                restore(); report['restoredOriginalSetupAndCache'] = True
        def preview_apk():
            install(args.apk)
            installed = d.shell('pm', 'path', 'dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
            report['restoredApkSha256'] = hashlib.sha256(d.call('exec-out', 'cat', installed)).hexdigest()
            assert report['restoredApkSha256'] == report['apkSha256']
        cleanup('stop synthetic extractor', d.stop_fixture_extractor)
        cleanup('restore display and font', device_settings)
        cleanup('restore original setup and cache', original_setup)
        report['syntheticFixtureRemoved'] = cleanup('remove synthetic fixture', lambda: d.remove_fixture(demo))
        cleanup('restore optimized preview APK', preview_apk)
        report['restoredOriginalStorageAccess'] = cleanup('restore storage access', lambda: d.shell('appops', 'set', 'dev.findroid', 'MANAGE_EXTERNAL_STORAGE', original_access))
        cleanup('remove native input helper', d.close)
        report['fixtureExtractorStops'] = d.fixture_stops
        report['cleanupErrors'] = cleanup_errors
        (args.output/'inspection-retries.json').write_text(json.dumps(d.retries, indent=2)+'\n')
        (args.output/'verification.json').write_text(json.dumps(report, indent=2)+'\n')
        if cleanup_errors: raise RuntimeError('Acceptance cleanup failed; inspect verification.json.')
    report['success'] = True
    (args.output/'verification.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__': main()
