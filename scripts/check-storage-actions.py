#!/usr/bin/env python3
"""Exercise optimized 0.40 -> 0.41 storage explanations and real synthetic Android tree grants.

Only the two assigned tablets are accepted. Originals remain in memory; no app data is cleared.
The opt-in native grant bridge operates only on a uniquely named synthetic Documents folder.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from android.acceptance import ROOT

# Reuse the previously exercised full-control and multi-window inspection helpers.
spec = importlib.util.spec_from_file_location('index_dialog_helpers', ROOT/'scripts/check-local-search-index.py')
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)


def read_app_file(device, path, optional=False):
    script = ('if test -f '+shlex.quote(path)+'; then cat '+shlex.quote(path)+
              '; elif test -e '+shlex.quote(path)+'; then exit 42; else exit 44; fi')
    result = device.call('shell', '-T', shlex.join(['run-as', 'dev.findroid', 'sh', '-c', script]), check=False)
    if optional and result.returncode == 44:
        return None
    assert result.returncode == 0, 'Could not read app-owned metadata: '+path
    return result.stdout


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--previous-apk', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--permissions-only', action='store_true', help='Focused fixture diagnostic; excluded from full release acceptance')
    args = p.parse_args()
    assert args.serial in {'emulator-5602', 'emulator-5582'}
    assert not args.output.exists(), 'Use a fresh directory; retain rejected attempts.'
    d = helpers.InspectionDevice(args.serial, args.output, all_windows=True)
    density = int(re.findall(r'\d+', d.shell('wm', 'density').decode())[-1])/160
    ui = helpers.Dialogs(d, density)
    debug = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    stamp = str(time.time_ns())
    relative = 'Documents/Findroid actions demo '+stamp
    demo = '/storage/emulated/0/'+relative
    payload = b'Keep these exact synthetic document bytes through permission changes.\n'
    saved = None
    grants_before = None
    qa_tree = None
    fixture_created = False
    cleanup_errors = []
    report = dict(serial=args.serial, checkerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        helpersSha256=hashlib.sha256((ROOT/'scripts/check-local-search-index.py').read_bytes()).hexdigest(),
        apkSha256=hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        previousApkSha256=hashlib.sha256(args.previous_apk.read_bytes()).hexdigest(), fromVersionCode=None if args.permissions_only else 42, toVersionCode=43,
        acceptanceScope='focused permissions only' if args.permissions_only else 'full optimized acceptance',
        grantBridgeApkSha256=hashlib.sha256(debug.read_bytes()).hexdigest(),
        descriptionVisibility='strict interior scroll bounds with 2px inset')
    original_scale = d.shell('settings', 'get', 'system', 'font_scale').decode().strip()
    original_screen = d.screen
    match = re.search(r'Override size: (\d+x\d+)', d.shell('wm', 'size').decode())
    original_size = match.group(1) if match else 'reset'
    mode = re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)', d.shell('appops', 'get', 'dev.findroid', 'MANAGE_EXTERNAL_STORAGE').decode())
    original_access = mode.group(1) if mode else 'default'
    report['originalStorageAccessMode']=original_access
    window = 'storage-actions-'+stamp
    provider_window = window+'-provider'

    def stop(): d.shell('am', 'force-stop', 'dev.findroid')
    def install(apk, downgrade=False):
        stop()
        d.call('install', '-r', *(['-d'] if downgrade else []), apk)
    def launch(location=demo, name=window):
        d.shell('am', 'start', '-W', '-f', '0x10008000', '--windowingMode', 1, '-n', 'dev.findroid/.MainActivity',
            '--es', 'finderWindowId', name, '--es', 'finderLocation', location)
    def browser(name, unavailable=False):
        return d.wait(name, lambda t: d.ready(t, 'Actions') and (
            bool(d.nodes(t, 'Folder access expired') or d.nodes(t, 'Location unavailable')) if unavailable
            else bool(d.nodes(t, 'Original.txt'))))
    def version():
        return int(re.search(r'versionCode=(\d+)', d.shell('dumpsys', 'package', 'dev.findroid').decode()).group(1))
    def installed_hash():
        path = d.shell('pm', 'path', 'dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        return hashlib.sha256(d.call('exec-out', 'cat', path)).hexdigest()
    def write(path, data):
        d.shell('run-as', 'dev.findroid', 'mkdir', '-p', str(Path(path).parent))
        d.call('shell', 'run-as dev.findroid sh -c '+shlex.quote('cat > '+shlex.quote(path)), data=data)
    def snapshot():
        names = d.shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        names += ['files/'+name+suffix for name in helpers.FILES for suffix in ('', '.bak', '.new')]+helpers.CACHE
        return {name: read_app_file(d, name, True) for name in names}
    def restore():
        stop()
        current = d.shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        for name in set(current)-set(saved): d.shell('run-as', 'dev.findroid', 'rm', '-f', name)
        for name, data in saved.items():
            if data is None: d.shell('run-as', 'dev.findroid', 'rm', '-f', name)
            else: write(name, data)
        assert snapshot() == saved, 'Original metadata, preferences, cache bytes and absences must be exact'
    def pref(tree, kind, name, value):
        for old in list(tree):
            if old.get('name') == name: tree.remove(old)
        item = ET.SubElement(tree, kind, name=name)
        if kind == 'string': item.text = value
        elif kind == 'set':
            for entry in value: ET.SubElement(item, 'string').text = entry
        else: item.set('value', str(value).lower())
    def seed():
        nonlocal fixture_created
        d.shell('mkdir','-p',str(Path(demo).parent))
        d.shell('mkdir',demo)
        fixture_created = True
        for name in helpers.FILES:
            for suffix in ('', '.bak', '.new'): d.shell('run-as', 'dev.findroid', 'rm', '-f', 'files/'+name+suffix)
        prefs = ET.fromstring(saved.get('shared_prefs/finder.xml') or b'<map/>')
        for old in list(prefs):
            if old.get('name', '').startswith('tag:'): prefs.remove(old)
        tab = dict(id='storage-action-baseline', history=[demo], index=0, view='LIST', query='', rules={})
        for kind, name, value in [('string','tabs',json.dumps([tab])),('int','activeTab',0),
            ('boolean','dualPane',False),('string','activePane','primary'),('boolean','panesReversed',False),
            ('boolean','dark',False),('boolean','sidebarVisible',True),('boolean','preview',False),
            ('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),('string','density','COMFORTABLE'),
            ('string','view','LIST'),('string','smartFolders','[]'),('string','storageActionsSentinel',stamp)]:
            pref(prefs, kind, name, value)
        sidebar = dict(version=1, favorites=[dict(id='action-demo',path=demo,label='Permission project')],groups=[],
            builtins=['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],
            hidden=['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'])
        pref(prefs,'string','sidebarLayoutV1',json.dumps(sidebar)); pref(prefs,'set','favorites',[demo])
        write('shared_prefs/finder.xml',ET.tostring(prefs,encoding='utf-8',xml_declaration=True))
        write('shared_prefs/locations.xml',b'<?xml version="1.0" encoding="utf-8"?><map><string name="roots">[]</string></map>')
        d.shell('mkdir','-p',demo+'/Destination')
        d.call('shell','cat > '+shlex.quote(demo+'/Original.txt'),data=payload)
        d.call('shell','cat > '+shlex.quote(demo+'/Second.txt'),data=payload)
    def menu(name, label):
        d.click(name+'-open','Actions')
        for direction in (-1,1):
            previous = None
            for _ in range(14):
                tree=d.dump(name)
                if d.ready(tree,label): d.tap(d.one(tree,label)); return
                panes=[n for w in tree.iter('window') if w.get('title')=='Pop-Up Window'
                       for n in w.iter('node') if n.get('scrollable')=='true' and n.get('class')=='android.widget.ScrollView']
                assert len(panes)==1,(name,len(panes))
                signature=ET.tostring(panes[0])
                if signature==previous: break
                previous=signature; l,t,r,b=d.bounds(panes[0]); start,end=(.25,.8) if direction==-1 else (.8,.25)
                d.shell('input','swipe',(l+r)//2,round(t+(b-t)*start),(l+r)//2,round(t+(b-t)*end),300)
        raise AssertionError('Unreachable named menu command: '+label)
    def details(name):
        menu(name,'Available actions…')
        return d.wait(name+'-ready',lambda t: bool(d.nodes(t,'Available actions')) and d.ready(t,'Done'))
    def footer(tree):
        ui.footer(tree,'Available actions','Done',['Refresh access info','Storage locations…','Done','Close Available actions'])
    def find_text(name,text):
        # Descriptive text has no click target, but must become fully visible by scrolling.
        for direction in (1,-1):
            previous=None
            for _ in range(20):
                tree=d.dump(name); scope=ui.panel(tree,'Available actions','Done')
                for node in d.nodes(scope,text):
                    l,t,r,b=d.bounds(node); w,h=d.screen
                    if 0<=l<r<=w and 0<=t<b<=h:
                        parents={c:p for p in scope.iter() for c in p}
                        parent=node; clipped=False
                        while parent in parents:
                            parent=parents[parent]
                            # Accessibility bounds are already clipped to the viewport.
                            # Touching an edge cannot prove the entire paragraph is visible.
                            if parent.get('scrollable')=='true':
                                pl,pt,pr,pb=d.bounds(parent); clipped |= not (pl+2<=l<r<=pr-2 and pt+2<=t<b<=pb-2)
                        if not clipped:return tree
                panes=[n for n in scope.iter('node') if n.get('scrollable')=='true']
                assert len(panes)==1,(name,len(panes))
                signature=ET.tostring(panes[0])
                if signature==previous:break
                previous=signature; l,t,r,b=d.bounds(panes[0]); start,end=(.8,.25) if direction==1 else (.25,.8)
                d.shell('input','swipe',(l+r)//2,round(t+(b-t)*start),(l+r)//2,round(t+(b-t)*end),300)
        raise AssertionError('Description cannot be reached: '+text)
    def bridge(method,flag):
        parts=['shell','am','instrument','-w','-r','-e','class','dev.findroid.StorageGrantRecoveryTest#'+method,
            '-e',flag,'true','-e','storageQaRoot',relative,'dev.findroid.test/androidx.test.runner.AndroidJUnitRunner']
        raw=d.shell(*parts[1:]).decode(errors='replace')
        assert 'OK (1 test)' in raw and 'FAILURES' not in raw, 'The opt-in synthetic grant bridge failed'
        return raw
    def grants():
        # Android redirects the test's println to System.out rather than am instrument's
        # result stream. Read only these two tags, since this invocation, after our own
        # unique marker. Never print/save the raw output or accept a stale audit.
        marker='FINDROID_PRIVATE_GRANT_AUDIT_'+str(time.time_ns())
        since=d.shell('date','+%m-%d %H:%M:%S').decode().strip()+'.000'
        assert re.fullmatch(r'[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.000',since)
        d.shell('log','-t','FindroidGrantAudit','-p','i',marker)
        bridge('auditPersistedPermissionsForSyntheticAcceptance','storageQaAudit')
        for _ in range(10):
            payload=d.shell('logcat','-d','-T',since,'-v','raw','-s','FindroidGrantAudit:I','System.out:I')
            assert len(payload)<=1024*1024,'The selected-tag audit output exceeded its memory bound'
            raw=payload.decode(errors='replace')
            assert marker in raw,'The bounded private audit marker was not retained'
            matches=re.findall(r'FINDROID_GRANT_AUDIT:([A-Za-z0-9+/=]+)',raw.rsplit(marker,1)[1])
            if matches:
                assert len(matches)==1,'The private audit must belong to this invocation only'
                report['privateAuditTransport']='time-bounded selected-tag log, unique invocation marker, memory only'
                return json.loads(base64.b64decode(matches[0],validate=True))
            time.sleep(.1)
        raise AssertionError('No private in-memory permission audit was returned')
    def only_qa_changed(current):
        assert {k:v for k,v in current.items() if k!=qa_tree} == grants_before, 'An unrelated persisted URI grant changed'

    try:
        install(debug); saved=snapshot(); grants_before=grants(); seed()
        d.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE','allow')
        if not args.permissions_only:
            install(args.previous_apk,True); assert version()==42
            launch(); browser('previous-public'); d.capture('previous-public')
            install(args.apk); assert version()==43 and installed_hash()==report['apkSha256']
            launch(); browser('upgraded-browser')
            d.wait('preserved-favorite',lambda t:d.ready(t,'Options for Permission project'))
            tree=details('local-details'); footer(tree); d.capture('local-folder-actions')
            find_text('local-trash-explanation','Move to Trash'); d.capture('local-trash-explanation'); d.click('local-done','Done')
            report['directPreviousPublicUpgrade']=True
            report['legacyFavoritePreserved']=True
            layouts=[]
            for width in (1280,800,600):
                for scale in (1.0,1.6):
                    key=f'{width}dp-font{scale}'
                    stop(); pixels=round(width*density)
                    d.shell('wm','size',f'{pixels}x{round(800*density)}'); d.screen=(pixels,round(800*density))
                    d.shell('settings','put','system','font_scale',str(scale)); launch(); browser(key+'-browser')
                    tree=details(key+'-details'); footer(tree); d.capture(key+'-details')
                    reasons = {
                        'Read': 'The local filesystem reported read access when this item was loaded.',
                        'Create items': 'The local filesystem reported the access used for creating children in this folder when this item was loaded. The operation checks again before changes.',
                        'Change contents': 'Create items describes adding children to this folder. Change contents applies to files.',
                        'Rename': 'The local filesystem reported the access used for renaming this item when this item was loaded. The operation checks again before changes.',
                        'Delete': 'The local filesystem reported the access used for deleting this item when this item was loaded. The operation checks again before changes.',
                        'Move to Trash': 'Findroid moves this item to its Trash. The operation checks the source and recovery destination before changing files.',
                    }
                    for label, reason in reasons.items():
                        find_text(key+'-'+label,label)
                        find_text(key+'-'+label+'-reason',reason)
                    d.capture(key+'-scrolled'); footer(d.dump(key+'-footer')); d.click(key+'-done','Done')
                    layouts.append(dict(widthDp=width,fontScale=scale,fullTouchBounds=True,allSixActionsReachable=True,allSixActionDescriptionsReachable=True))
                    print('Verified layout '+key,flush=True)
            report['layouts']=layouts
        stop(); d.shell('wm','size',original_size);d.screen=original_screen;d.shell('settings','put','system','font_scale','1.0')
        install(debug)
        assert grants()==grants_before,'Persisted URI grants changed during setup or local upgrade/layout acceptance'
        from urllib.parse import quote, unquote
        qa_tree='content://com.android.externalstorage.documents/tree/'+quote('primary:'+relative,safe='')
        assert qa_tree not in grants_before,'The unique synthetic tree already has a grant'
        bridge('grantExplicitSyntheticTreeThroughSystemPicker','storageQaGrant')
        after=grants()
        added=set(after)-set(grants_before)
        assert added=={qa_tree},'Exactly the synthetic tree must receive a new grant'
        only_qa_changed(after);assert after[qa_tree]==[True,True]
        assert unquote(qa_tree).endswith('primary:'+relative)
        document=qa_tree+'/document/'+quote('primary:'+relative,safe='')
        install(args.apk);launch(document,provider_window);browser('provider-read-write')
        tree=details('provider-actions');footer(tree)
        find_text('provider-read', 'Android reports saved read permission. The provider checks document access when it is opened.')
        find_text('provider-create', 'The provider reports support for creating children in this folder and the loaded access information allows it. The operation checks again before changes.')
        d.capture('provider-read-write');report['providerWindowRouteVerified']=True
        d.click('provider-done','Done')
        # Platform permission changes use the fixed debug bridge. Reinstall the exact
        # optimized APK before each UI assertion; never instrument the optimized app.
        install(debug);bridge('restrictExplicitSyntheticTreeToReadOnly','storageQaReadOnly')
        after=grants();only_qa_changed(after);assert after[qa_tree]==[True,False]
        install(args.apk);launch(document,provider_window);browser('provider-read-only-browser');tree=details('provider-read-only-details');footer(tree)
        find_text('read-only-create',"Android's saved folder permission is read only. It does not allow creating children in this folder.")
        d.capture('provider-read-only');d.click('read-only-done','Done')
        tree=d.wait('read-only-new',lambda t:d.ready(t,'New folder'))
        # The disabled-action surface exposes both a label and a described wrapper.
        # Target its named wrapper once, rather than counting the label as a second control.
        targets=[n for n in d.nodes(tree,'New folder') if n.get('content-desc')=='New folder']
        assert len(targets)==1,'Expected one described New folder control'
        d.tap(targets[0])
        d.wait('read-only-command',lambda t:bool(d.nodes(t,'New folder is unavailable')))
        d.capture('read-only-command');d.click('read-only-command-back','Back to files')
        install(debug);bridge('revokeExplicitSyntheticTreeOnly','storageQaRevoke')
        after=grants();only_qa_changed(after);assert qa_tree not in after
        install(args.apk);launch(document,provider_window);browser('provider-revoked-browser',unavailable=True);details('provider-revoked-details')
        find_text('revoked-read','Read')
        find_text('revoked-reason',"Choose this folder again to restore Android's permission.")
        find_text('revoked-status','Unavailable');d.capture('provider-revoked');d.click('revoked-done','Done')
        report.update(actualSystemPickerReadWriteGrant=True,actualReadOnlyGrant=True,actualGrantRevocation=True,
            unrelatedUriGrantsUnchanged=True,grantsAuditedBeforeSetup=True,readOnlyCreationExplainsRecovery=True,
            grantChangesUseDebugBridgeOptimizedUiChecked=True)
        install(debug);stop();prefs=ET.fromstring(read_app_file(d,'shared_prefs/finder.xml'))
        assert prefs.find("./string[@name='storageActionsSentinel']").text==stamp
        assert all(d.call('shell','-T',shlex.join(['cat',demo+'/'+name]))==payload for name in ('Original.txt','Second.txt'))
        report.update(originalSyntheticDocumentBytes=True,sentinelPreferencePreserved=True)
    finally:
        def cleanup(label,action):
            try:action();return True
            except Exception as error:cleanup_errors.append(dict(step=label,error=str(error)));return False
        def release_qa_grant():
            if grants_before is not None:
                install(debug)
                current=grants()
                if qa_tree is not None and qa_tree in current:
                    bridge('revokeExplicitSyntheticTreeOnly','storageQaRevoke')
                assert grants()==grants_before,'Original persisted permissions were not restored'
                report['restoredOriginalUriGrants']=True
        def originals():
            install(debug)
            if saved is not None:restore();report['restoredOriginalSetupAndCache']=True
        cleanup('release only the synthetic grant',release_qa_grant)
        cleanup('restore original metadata/cache',originals)
        if fixture_created:cleanup('remove synthetic documents',lambda:d.shell('rm','-rf',demo))
        report['syntheticFixtureRemoved']=d.shell('test','!','-e',demo,check=False).returncode==0
        cleanup('restore size',lambda:d.shell('wm','size',original_size));d.screen=original_screen
        cleanup('restore font',lambda:d.shell('settings','put','system','font_scale',original_scale))
        def restore_storage_access():
            d.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE',original_access)
            current=re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)',d.shell('appops','get','dev.findroid','MANAGE_EXTERNAL_STORAGE').decode())
            assert (current.group(1) if current else 'default')==original_access
            report['restoredOriginalStorageAccess']=True
        cleanup('restore storage access',restore_storage_access)
        cleanup('restore optimized APK',lambda:install(args.apk))
        report['restoredApkSha256']=installed_hash()
        cleanup('remove native input helper',d.close)
        report['cleanupErrors']=cleanup_errors
        (args.output/'inspection-retries.json').write_text(json.dumps(d.retries,indent=2)+'\n')
        (args.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
        if cleanup_errors:raise RuntimeError('Cleanup failed; inspect verification.json')
    assert report['restoredApkSha256']==report['apkSha256'] and report['syntheticFixtureRemoved']
    report['success']=True
    (args.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
