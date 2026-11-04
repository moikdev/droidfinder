#!/usr/bin/env python3
"""Exercise an exact optimized reviewed-sync candidate with native Android controls.

Uses only the two assigned tablets, with the same exclusive reservation as native
acceptance. Originals are snapshotted and checked before mutation and restored in
finally. Debug is used while stopped for fixture setup/inspection; every user
operation runs on the supplied optimized APK. All test files are synthetic.
"""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import time
import xml.etree.ElementTree as ET

from android.acceptance import ROOT

MAIN = Path('/Users/LENOVO/.t3/projects/droidfinder')
PROOF = MAIN / 'captures/persistent-undo'
MANAGED = ('shared_prefs', 'databases', 'no_backup', 'cache', 'files')
EXCLUDED = ('files/Workspace', 'files/ProviderFixtures')


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


helpers = load('sync_native_inspection', ROOT / 'scripts/check-local-search-index.py')
originals = load('sync_original_restoration', PROOF / 'restore-public-tablets.py')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def entries(payload):
    result = {}
    with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
        for row in archive:
            name = row.name.rstrip('/')
            assert not Path(name).is_absolute() and '..' not in Path(name).parts
            assert row.isfile() or row.isdir(), 'Unexpected link/special file in acceptance snapshot'
            result[name] = archive.extractfile(row).read() if row.isfile() else None
    return result


class NativeControls:
    """Work only inside the active application window, including scrolled dialogs."""
    def __init__(self, device, density):
        self.d = device
        self.density = density
        self.proofs = []

    def scope(self, tree):
        windows = [w for w in tree.iter('window')
                   if w.get('active') == 'true' and w.get('type') == 'TYPE_APPLICATION'
                   and any(n.get('package') == 'dev.findroid' for n in w.iter('node'))]
        assert len(windows) == 1, ('active Findroid window', len(windows))
        return windows[0]

    def matching(self, scope, label):
        return [n for n in scope.iter('node')
                if (label(n.get('text', ''), n.get('content-desc', '')) if callable(label)
                    else label in (n.get('text'), n.get('content-desc')))
                and len(self.d.bounds(n)) == 4 and self.d.bounds(n)[2] > self.d.bounds(n)[0]]

    def node(self, scope, label, clickable):
        parents = {child: parent for parent in scope.iter() for child in parent}
        found = []
        for node in self.matching(scope, label):
            if clickable:
                while node.get('clickable') != 'true' and node in parents:
                    node = parents[node]
                if node.get('clickable') != 'true':
                    continue
            if node not in found:
                found.append(node)
        if not found:
            return None
        assert len(found) == 1, (str(label), len(found))
        return found[0]

    def full(self, scope, node, clickable):
        if node is None:
            return False
        l, t, r, b = self.d.bounds(node)
        w, h = self.d.screen
        if not (0 <= l < r <= w and 0 <= t < b <= h):
            return False
        if clickable and (r-l < 48*self.density-1 or b-t < 48*self.density-1):
            return False
        parents = {child: parent for parent in scope.iter() for child in parent}
        while node in parents:
            node = parents[node]
            if node.get('scrollable') == 'true' or node.tag == 'window':
                pl, pt, pr, pb = self.d.bounds(node)
                if not (pl <= l < r <= pr and pt <= t < b <= pb):
                    return False
        return True

    def reach(self, name, label, *, clickable=True, enabled=True):
        for direction in (1, -1):
            previous = None
            for _ in range(30):
                tree = self.d.dump(name)
                scope = self.scope(tree)
                node = self.node(scope, label, clickable)
                if node is not None and (not enabled or node.get('enabled') == 'true') and self.full(scope, node, clickable):
                    return tree, node
                panes = [n for n in scope.iter('node') if n.get('scrollable') == 'true'
                         and len(self.d.bounds(n)) == 4 and self.d.bounds(n)[3] > self.d.bounds(n)[1]]
                if not panes:
                    break
                pane = max(panes, key=lambda n: (self.d.bounds(n)[2]-self.d.bounds(n)[0])*(self.d.bounds(n)[3]-self.d.bounds(n)[1]))
                signature = ET.tostring(pane)
                if signature == previous:
                    break
                previous = signature
                l, t, r, b = self.d.bounds(pane)
                first, last = (.82, .28) if direction == 1 else (.28, .82)
                self.d.shell('input', 'swipe', (l+r)//2, round(t+(b-t)*first), (l+r)//2, round(t+(b-t)*last), 300)
        raise AssertionError((name, str(label), 'complete named 48 dp control unavailable'))

    def click(self, name, label):
        tree, node = self.reach(name, label)
        self.proofs.append(dict(name=name, text=node.get('text'), description=node.get('content-desc'), bounds=self.d.bounds(node), density=self.density))
        self.d.tap(node)
        return tree

    def text(self, name, label):
        return self.reach(name, label, clickable=False, enabled=False)

    def capture(self, name, label=None):
        if label is not None:
            self.reach(name, label)
        self.d.capture(name)
        tree = self.d.dump(name+'-crop')
        bounds = self.d.bounds(self.scope(tree))
        from PIL import Image
        with Image.open(self.d.output/(name+'.png')) as image:
            image.crop(tuple(bounds)).save(self.d.output/(name+'-dialog.png'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, choices=('emulator-5602', 'emulator-5582'))
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--previous-apk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--section', choices=('core', 'interruptions', 'layouts', 'all'), default='all')
    parser.add_argument('--wait-for-native', action='store_true', help='Wait for the assigned reservation, then require completed candidate native acceptance')
    args = parser.parse_args()
    assert not args.output.exists(), 'Preserve earlier attempts; use a fresh output directory'
    with (PROOF / ('.'+args.serial+'-acceptance.lock')).open('a') as reservation:
        fcntl.flock(reservation, fcntl.LOCK_EX | (0 if args.wait_for_native else fcntl.LOCK_NB))
        if args.wait_for_native:
            api = 'api30' if args.serial == 'emulator-5602' else 'api36'
            native = json.loads((ROOT/'captures/reviewed-folder-sync'/('candidate-1-'+api+'-native')/'native.json').read_text())
            assert native['success'] and native['passed'] == 505 and native['failed'] == native['skipped'] == 0
        run(args)


def run(a):
    d = helpers.InspectionDevice(a.serial, a.output, all_windows=True)
    private = a.output / 'PRIVATE-recovery'
    private.mkdir(mode=0o700)
    candidate = json.loads((ROOT / 'captures/reviewed-folder-sync/candidate-build-1.json').read_text())
    debug = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    test = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    public = MAIN / 'captures/release/0.45.0/Findroid-0.45.0-preview.apk'
    assert a.previous_apk.resolve() == public.resolve()
    assert digest(a.apk.read_bytes()) == 'd394b2b078da375335057baa349a01c2db58388a6463f3b15f8e0475f848a0d2'
    for path in (debug, test):
        assert digest(path.read_bytes()) == candidate['files'][str(path.relative_to(ROOT))]['sha256']
    density_text = d.shell('wm', 'density').decode()
    original_density = re.search(r'Override density: (\d+)', density_text)
    original_density = original_density.group(1) if original_density else 'reset'
    density = int(re.findall(r'\d+', density_text)[-1])/160
    ui = NativeControls(d, density)
    original_screen = d.screen
    size_match = re.search(r'Override size: (\d+x\d+)', d.shell('wm', 'size').decode())
    original_size = size_match.group(1) if size_match else 'reset'
    original_scale = d.shell('settings', 'get', 'system', 'font_scale').decode().strip()
    report = dict(serial=a.serial, section=a.section, checkerSha256=digest(Path(__file__).read_bytes()),
                  apkSha256=digest(a.apk.read_bytes()), previousApkSha256=digest(public.read_bytes()),
                  sourceCommit=candidate['sourceCommit'], fromVersionCode=47, toVersionCode=48,
                  operationsUseExactOptimizedApk=True, acceptanceComplete=False, restorationComplete=False,
                  checks=[], layouts=[])
    stamp = str(time.time_ns())
    fixture_rel = 'files/Workspace/Sync-acceptance-'+stamp
    fixture = '/data/user/0/dev.findroid/'+fixture_rel
    external = '/storage/emulated/0/Download/Findroid-sync-acceptance-'+stamp
    saved = None
    original_workspace = None
    baseline_verified = False

    def stop():
        d.shell('am', 'force-stop', 'dev.findroid')

    def installed(package='dev.findroid'):
        path = d.shell('pm', 'path', package).decode().strip().splitlines()[0].removeprefix('package:')
        return digest(d.call('exec-out', 'cat', path))

    def install(path, downgrade=False):
        stop()
        d.call('install', '-r', *(['-d'] if downgrade else []), path)
        stop()

    def read(path, optional=False):
        script = 'if test -f '+shlex.quote(path)+'; then cat '+shlex.quote(path)+'; elif test -e '+shlex.quote(path)+'; then exit 42; else exit 44; fi'
        result = d.call('shell', '-T', shlex.join(['run-as', 'dev.findroid', 'sh', '-c', script]), check=False)
        if optional and result.returncode == 44:
            return None
        assert result.returncode == 0, 'Could not inspect owned file '+path
        return result.stdout

    def write(path, data):
        d.shell('run-as', 'dev.findroid', 'mkdir', '-p', str(Path(path).parent))
        d.call('shell', '-T', shlex.join(['run-as', 'dev.findroid', 'sh', '-c', 'cat > '+shlex.quote(path)]), data=data)

    def snapshot():
        return d.shell('run-as', 'dev.findroid', 'tar', '-cf', '-', '--exclude=files/Workspace', '--exclude=files/ProviderFixtures', *MANAGED)

    def restore_metadata():
        assert saved is not None
        stop()
        current = entries(snapshot())
        original = entries(saved)
        extras = [name for name, data in current.items() if data is not None and name not in original]
        for first in range(0, len(extras), 100):
            names = extras[first:first+100]
            assert all(Path(n).parts[0] in MANAGED and not any(n == x or n.startswith(x+'/') for x in EXCLUDED) for n in names)
            d.shell('run-as', 'dev.findroid', 'rm', '-f', '--', *names)
        d.shell('run-as', 'dev.findroid', 'tar', '-xf', '-', data=saved)
        current = entries(snapshot())
        for name in sorted(set(current)-set(original), key=lambda n: (-n.count('/'), n)):
            assert current[name] is None
            d.shell('run-as', 'dev.findroid', 'rmdir', '--', name)
        assert entries(snapshot()) == original, 'Original metadata bytes and absences did not match'

    def pref(tree, kind, name, value):
        for child in list(tree):
            if child.get('name') == name:
                tree.remove(child)
        node = ET.SubElement(tree, kind, name=name)
        if kind == 'string':
            node.text = value
        elif kind == 'set':
            for item in value:
                ET.SubElement(node, 'string').text = item
        else:
            node.set('value', str(value).lower())

    def seed_prefs(source, destination, *, dark=False):
        prefs = ET.fromstring(entries(saved).get('shared_prefs/finder.xml') or b'<map/>')
        def tab(path):
            return dict(history=[path], index=0, view='LIST', sort='NAME', ascending=True, query='')
        for kind, name, value in [('string', 'tabs', json.dumps([tab(source)])), ('int', 'activeTab', 0),
                ('boolean', 'dualPane', True), ('string', 'activePane', 'primary'), ('boolean', 'panesReversed', False),
                ('boolean', 'dark', dark), ('boolean', 'sidebarVisible', True), ('boolean', 'tagsVisible', False),
                ('boolean', 'preview', False), ('boolean', 'gestureHintDismissed', True),
                ('string', 'density', 'COMFORTABLE'), ('string', 'view', 'LIST'),
                ('string', 'syncAcceptanceSentinel', stamp)]:
            pref(prefs, kind, name, value)
        write('shared_prefs/finder.xml', ET.tostring(prefs, encoding='utf-8', xml_declaration=True))
        secondary = ET.Element('map')
        pref(secondary, 'string', 'tabs', json.dumps([tab(destination)]))
        pref(secondary, 'int', 'activeTab', 0)
        write('shared_prefs/finder-pane-secondary.xml', ET.tostring(secondary, encoding='utf-8', xml_declaration=True))
        # Preserve original Undo data in the immutable host snapshot before replacing this test fixture.
        old = [n for n in entries(snapshot()) if n.startswith('files/.undo-history/')]
        undo_entries = entries(snapshot())
        for name in sorted(old, key=lambda n: (-n.count('/'), n)):
            if undo_entries.get(name) is None:
                d.shell('run-as', 'dev.findroid', 'rmdir', '--', name)
            else:
                d.shell('run-as', 'dev.findroid', 'rm', '-f', '--', name)

    def setup(name, files, *, outside=False, dark=False):
        install(debug)
        restore_metadata()
        base = (external if outside else fixture)+'/'+name
        source, target = base+'/Source', base+'/Destination'
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w') as archive:
            for directory in ('Source', 'Destination'):
                row = tarfile.TarInfo(directory); row.type = tarfile.DIRTYPE; row.mode = 0o700
                archive.addfile(row)
            for path, payload in files.items():
                row = tarfile.TarInfo(path); row.size = len(payload); row.mode = 0o600
                archive.addfile(row, io.BytesIO(payload))
        if outside:
            d.shell('mkdir', '-p', base)
            d.shell('tar', '-xf', '-', '-C', base, data=data.getvalue())
        else:
            d.shell('run-as', 'dev.findroid', 'mkdir', '-p', base)
            d.shell('run-as', 'dev.findroid', 'tar', '-xf', '-', '-C', base, data=data.getvalue())
        seed_prefs(source, target, dark=dark)
        return base, source, target

    def launch():
        assert installed() == report['apkSha256'], 'All user actions require the exact optimized candidate'
        d.shell('am', 'start', '-W', '-f', '0x10008000', '--windowingMode', 1, '-n', 'dev.findroid/.MainActivity')
        d.wait('browser-ready', lambda t: d.ready(t, 'Actions'), seconds=90)

    def optimized():
        install(a.apk)
        launch()

    def menu(name, label):
        d.click(name+'-actions', 'Actions')
        ui.click(name+'-menu', label)

    def review(name):
        menu(name, 'Compare folders…')
        ui.click(name+'-open-review', 'Review synchronization…')
        ui.text(name+'-review-title', 'Review folder synchronization')
        ui.reach(name+'-direction', 'Left → right')
        # Reaching Review changes proves the complete comparison has finished.
        ui.reach(name+'-ready', lambda text, desc: text.startswith('Review changes ('))

    def confirm(name, count):
        ui.click(name+'-choices', 'Review changes ('+str(count)+')')
        ui.text(name+'-confirm-title', 'Confirm selected changes')

    def applied(name, count):
        ui.click(name+'-apply', 'Apply changes ('+str(count)+')')
        d.wait(name+'-ended', lambda t: any(n.get('text') in {'Synchronization finished', 'Synchronization stopped'} for n in t.iter('node')), seconds=180)

    def inspect(name, base, outside=False):
        install(debug)
        payload = d.shell(*([] if outside else ['run-as', 'dev.findroid']), 'tar', '-cf', '-', '-C', base, '.')
        (private/(name+'-fixture.tar')).write_bytes(payload)
        managed = snapshot()
        (private/(name+'-managed.tar')).write_bytes(managed)
        return entries(payload), entries(managed)

    def checked(name, **facts):
        report['checks'].append(dict(name=name, **facts))
        print(a.serial+': '+name, flush=True)
        (a.output/'result.json').write_text(json.dumps(report, indent=2)+'\n')

    try:
        assert installed() == digest(public.read_bytes()), 'Start from exact public 0.45'
        install(debug)
        saved = snapshot()
        original_workspace = d.shell('run-as', 'dev.findroid', 'tar', '-cf', '-', 'files/Workspace')
        (private/'PRIVATE-managed-before.tar').write_bytes(saved)
        (private/'PRIVATE-workspace-before.tar').write_bytes(original_workspace)
        current = {n: digest(v) for n, v in entries(saved).items() if v is not None}
        immutable = json.loads((PROOF/'private-native-state'/a.serial/'PRIVATE-MANIFEST.json').read_text())['files']
        assert current == immutable, 'Original managed bytes changed; preserve them without overwriting'
        baseline_verified = True
        report['immutableOriginalBytesVerifiedBeforeTests'] = True
        report['originalManagedFileCount'] = len(current)
        if a.section in ('core', 'all'):
            base, source, target = setup('Complete', {'Source/New.txt': b'new checked data\n',
                'Source/Update.txt': b'new reviewed contents\n', 'Destination/Update.txt': b'original destination\n',
                'Destination/Keep.txt': b'destination-only original\n'})
            install(public, True)
            d.shell('am', 'start', '-W', '-n', 'dev.findroid/.MainActivity')
            d.wait('public045-browser', lambda t: d.ready(t, 'Actions'))
            d.capture('public045-before-upgrade')
            optimized()
            checked('direct-public-47-to-48-upgrade', exactOptimizedApk=True)
            review('default-addition')
            ui.capture('default-addition-review', 'Review changes (1)')
            confirm('default-addition', 1)
            applied('default-addition', 1)
            ui.capture('addition-finished', 'Open destination')
            tree, managed = inspect('addition', base)
            assert tree['./Destination/New.txt'] == tree['./Source/New.txt']
            assert tree['./Destination/Update.txt'] == b'original destination\n' and tree['./Destination/Keep.txt'] == b'destination-only original\n'
            checked('default-additions-keep-originals', sourceAndDestinationBytesVerified=True)
            optimized()
            review('confirmed-originals')
            ui.click('choose-update', 'Update.txt')
            ui.click('choose-trash', 'Keep.txt')
            confirm('confirmed-originals', 2)
            replace = 'Replace 1 destination item; preserve the original copies in Trash first.'
            trash = 'Move 1 destination-only item to Trash. Keep the original copies there.'
            ui.click('confirm-replacement', replace)
            ui.click('confirm-trash', trash)
            ui.capture('independent-confirmations', 'Apply changes (2)')
            applied('confirmed-originals', 2)
            ui.capture('confirmed-finished', 'Open Trash')
            tree, managed = inspect('confirmed', base)
            assert tree['./Destination/Update.txt'] == b'new reviewed contents\n' and './Destination/Keep.txt' not in tree
            assert b'original destination\n' in managed.values() and b'destination-only original\n' in managed.values()
            records = {n:v for n,v in managed.items() if n.startswith('files/.folder-sync-') and v is not None}
            assert len([n for n in records if n.startswith('files/.folder-sync-runs/')]) == 2
            checked('confirmed-replacement-and-trash', originalsPreservedInTrash=True, immutableRunRecords=2)
            optimized()
            menu('cold-records', 'Synchronization records…')
            ui.text('manual-records-warning', lambda t,c: 'never retries, restores or removes files' in t)
            ui.capture('cold-manual-records', 'Close')
            ui.click('close-cold-records', 'Close')
            _, after = inspect('cold-records', base)
            assert {n:v for n,v in after.items() if n in records} == records
            checked('cold-record-review-does-not-replay', recordsAndJournalsUnchanged=True)
            base, source, target = setup('Stale', {'Source/New.txt': b'reviewed source\n'}, outside=True)
            optimized(); review('stale'); confirm('stale', 1)
            d.shell('sh', '-c', 'printf %s '+shlex.quote('arrived after review')+' > '+shlex.quote(target+'/New.txt'))
            applied('stale', 1)
            ui.text('stale-stop', 'Synchronization stopped')
            ui.capture('stale-review-stopped', 'Close')
            tree, _ = inspect('stale', base, outside=True)
            assert tree['./Destination/New.txt'] == b'arrived after review' and tree['./Source/New.txt'] == b'reviewed source\n'
            checked('occupied-name-after-review', bothOriginalsKept=True)
        if a.section in ('interruptions', 'all'):
            for phase in ('preparation', 'publication', 'cancellation', 'access-loss'):
                files = {'Source/Part%03d.txt'%i: (('synthetic part %03d\n'%i).encode()*4096) for i in range(160)}
                base, source, target = setup('Interrupted-'+phase, files, outside=True)
                optimized(); review(phase); confirm(phase, 160)
                ui.click(phase+'-apply', 'Apply changes (160)')
                deadline = time.monotonic()+180
                observed = None
                while time.monotonic() < deadline:
                    dirs = d.shell('find', base, '-maxdepth', 1, '-type', 'd', '-name', '.findroid-sync-*').decode().splitlines()
                    published = d.shell('find', target, '-maxdepth', 1, '-type', 'f').decode().splitlines()
                    if phase == 'publication' and 0 < len(published) < 160:
                        observed = dict(workingDirectories=len(dirs), publishedBeforeKill=len(published)); stop(); break
                    if phase != 'publication' and dirs and not published:
                        prepared = d.shell('test', '-e', dirs[0]+'/prepared.json', check=False).returncode == 0
                        if prepared:
                            continue
                        observed = dict(workingDirectories=len(dirs), publishedBeforeInterruption=0, preparedMarkerAbsent=True)
                        if phase == 'cancellation':
                            ui.click(phase+'-stop', 'Stop synchronization')
                            d.wait(phase+'-stopped', lambda t: any(n.get('text') == 'Synchronization stopped' for n in t.iter('node')), seconds=120)
                        elif phase == 'access-loss':
                            d.shell('appops', 'set', 'dev.findroid', 'MANAGE_EXTERNAL_STORAGE', 'deny')
                            # AppOps may kill the process. Both outcomes require a cold manual review.
                            stop()
                        else:
                            stop()
                        break
                    time.sleep(.05)
                assert observed is not None, 'Did not observe the requested real in-flight phase: '+phase
                tree, managed = inspect(phase+'-interrupted', base, outside=True)
                source_files = {n:v for n,v in tree.items() if n.startswith('./Source/') and v is not None}
                assert len(source_files) == 160
                for name, value in source_files.items():
                    assert value == files[name.removeprefix('./')]
                published_files = {n:v for n,v in tree.items() if n.startswith('./Destination/') and v is not None}
                for name, value in published_files.items():
                    assert value == files['Source/'+name.rsplit('/',1)[1]]
                run_records = {n:v for n,v in managed.items() if n.startswith('files/.folder-sync-') and v is not None}
                assert any(n.startswith('files/.folder-sync-runs/') for n in run_records)
                if phase == 'publication':
                    assert 0 < len(published_files) < 160
                    assert any(n.startswith('files/.folder-sync-journals/') for n in run_records)
                d.shell('appops', 'set', 'dev.findroid', 'MANAGE_EXTERNAL_STORAGE', 'allow')
                optimized()
                menu(phase+'-records', 'Synchronization records…')
                ui.text(phase+'-manual-warning', lambda t,c: 'never retries, restores or removes files' in t)
                ui.capture(phase+'-cold-records', 'Close')
                ui.click(phase+'-close-records', 'Close')
                second, after = inspect(phase+'-after-cold-review', base, outside=True)
                assert second == tree, 'Cold restart/record reading changed synthetic files'
                assert {n:v for n,v in after.items() if n in run_records} == run_records
                checked('real-'+phase+'-interruption', **observed, completedFiles=len(published_files), sourceBytesKept=True,
                        coldProcessManualReview=True, noAutomaticReplayOrCleanup=True)
        if a.section in ('layouts', 'all'):
            for width, height in ((1280,800), (800,1280), (600,800)):
                for font in (1.0, 2.0):
                    for dark in (False, True):
                        name = f'layout-{width}x{height}-font{font}-'+('dark' if dark else 'light')
                        base, source, target = setup(name, {'Source/New.txt': b'new', 'Source/Update.txt': b'new contents',
                            'Destination/Update.txt': b'original contents', 'Destination/Keep.txt': b'original only'}, dark=dark)
                        d.shell('wm', 'size', f'{round(width*density)}x{round(height*density)}')
                        d.screen = (round(width*density), round(height*density))
                        d.shell('settings', 'put', 'system', 'font_scale', str(font))
                        assert float(d.shell('settings', 'get', 'system', 'font_scale')) == font
                        optimized(); review(name)
                        ui.click(name+'-select-update', 'Update.txt'); ui.click(name+'-select-trash', 'Keep.txt')
                        confirm(name, 3)
                        measured_labels = []
                        for key,label in [('replace','Replace 1 destination item; preserve the original copies in Trash first.'),
                                          ('trash','Move 1 destination-only item to Trash. Keep the original copies there.')]:
                            ui.click(name+'-'+key, label)
                            measured_tree, measured_control = ui.reach(name+'-'+key+'-measured', label)
                            text_nodes = ui.matching(ui.scope(measured_tree), label)
                            assert len(text_nodes) == 1
                            text_bounds = d.bounds(text_nodes[0])
                            measured_labels.append(dict(label=label, textBoundsPx=text_bounds, textHeightPx=text_bounds[3]-text_bounds[1]))
                            ui.capture(name+'-'+key, label)
                        ui.capture(name+'-apply', 'Apply changes (3)')
                        ui.click(name+'-back', 'Back to choices'); ui.click(name+'-close', 'Close')
                        menu(name+'-records', 'Synchronization records…'); ui.capture(name+'-empty-records', 'Close')
                        ui.click(name+'-records-close', 'Close')
                        report['layouts'].append(dict(widthDp=width, heightDp=height, systemFontScale=font, dark=dark,
                            namedControlsCompleteAnd48Dp=True, noFileOperationApplied=True, confirmationLabels=measured_labels))
                        checked(name, actualAndroidFontScale=font)
            for width, height in ((1280,800), (800,1280), (600,800)):
                for dark in (False,True):
                    normal = next(r for r in report['layouts'] if (r['widthDp'],r['heightDp'],r['systemFontScale'],r['dark']) == (width,height,1.0,dark))
                    large = next(r for r in report['layouts'] if (r['widthDp'],r['heightDp'],r['systemFontScale'],r['dark']) == (width,height,2.0,dark))
                    assert all(b['textHeightPx'] > a['textHeightPx'] for a,b in zip(normal['confirmationLabels'],large['confirmationLabels'])), 'System font setting did not measurably enlarge both confirmation labels'
            report['actualSystemFontEnlargementMeasured'] = True
            # Change Android display density itself, independently of the app font setting.
            for multiplier in (1.0, 1.25):
                changed = round(density*160*multiplier)
                d.shell('wm', 'density', changed)
                ui.density = changed/160
                name = 'display-density-'+str(changed)
                d.shell('wm', 'size', f'{round(800*ui.density)}x{round(1000*ui.density)}')
                d.screen = (round(800*ui.density), round(1000*ui.density))
                d.shell('settings', 'put', 'system', 'font_scale', '2.0')
                base, source, target = setup(name, {'Source/New.txt': b'new'})
                optimized(); review(name); confirm(name, 1)
                ui.capture(name+'-apply', 'Apply changes (1)'); ui.click(name+'-close', 'Close')
                checked(name, actualDensityDpi=changed, systemFontScale=2.0, namedControlsCompleteAnd48Dp=True)
        report['acceptanceComplete'] = True
    except BaseException as error:
        report['error'] = type(error).__name__+': '+str(error)
        raise
    finally:
        stop()
        try:
            # The accepted restoration helper expects the main debug/test pair, code 47.
            for path in (MAIN/'app/build/outputs/apk/debug/app-debug.apk', MAIN/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
                d.call('install', '-r', '-d', path)
            stop()
            if baseline_verified:
                # Only remove our uniquely named synthetic roots, after retaining private evidence.
                assert fixture_rel.startswith('files/Workspace/Sync-acceptance-') and stamp in fixture_rel
                d.shell('run-as', 'dev.findroid', 'rm', '-rf', '--', fixture_rel)
                if d.shell('test', '-e', external, check=False).returncode == 0:
                    assert external.startswith('/storage/emulated/0/Download/Findroid-sync-acceptance-') and stamp in external
                    d.shell('rm', '-rf', '--', external)
                after = d.shell('run-as', 'dev.findroid', 'tar', '-cf', '-', 'files/Workspace')
                (private/'PRIVATE-workspace-after.tar').write_bytes(after)
                report['originalWorkspaceTreeKept'] = entries(after) == entries(original_workspace)
                # Keep both Workspace snapshots even if a fixture escaped its owned root.
                # Restore the independent managed state and environment before reporting it.
                d.shell('wm', 'density', original_density)
                d.shell('wm', 'size', original_size)
                d.shell('settings', 'put', 'system', 'font_scale', original_scale)
                report['restoration'] = originals.restore_one(a.serial, public)
                report['restorationComplete'] = True
                assert installed() == digest(public.read_bytes())
                assert report['originalWorkspaceTreeKept'], 'Original Workspace tree differs; preserve snapshots'
            else:
                d.call('install', '-r', '-d', public)
        finally:
            report['controlProofs'] = ui.proofs
            report['hierarchyRetries'] = d.retries
            (a.output/'result.json').write_text(json.dumps(report, indent=2)+'\n')
            d.close()
    print(json.dumps(dict(serial=a.serial, section=a.section, checks=len(report['checks']),
                          layouts=len(report['layouts']), acceptanceComplete=report['acceptanceComplete'],
                          restorationComplete=report['restorationComplete'])), flush=True)


if __name__ == '__main__':
    main()
