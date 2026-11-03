#!/usr/bin/env python3
"""Check optimized 0.41 -> 0.42 transfer progress using a separate synthetic provider.

Only assigned tablets are allowed. Never clears/uninstalls Findroid. Private managed
state stays in memory and is restored byte-for-byte, including original absences.
The provider is read-only, generates bounded data and is never in a shipping APK.
"""
import argparse
import base64
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import shlex
import sqlite3
import tarfile
import tempfile
import time
import xml.etree.ElementTree as ET
from android.acceptance import ROOT

spec = importlib.util.spec_from_file_location('transfer_inspection_helpers', ROOT/'scripts/check-local-search-index.py')
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
MANAGED = ('shared_prefs', 'databases', 'no_backup', 'cache', 'files')
EXCLUDED = ('files/Workspace', 'files/ProviderFixtures')
AUTHORITY = 'dev.findroid.transferqa.documents'
TREE = 'content://'+AUTHORITY+'/tree/root'
DOCUMENT = TREE+'/document/root'
PROVIDER = 'dev.findroid.transferqa'
SIZE = 8*1024*1024


def sha(data): return hashlib.sha256(data).hexdigest()


def managed(name):
    path=Path(name)
    return bool(path.parts) and not path.is_absolute() and '..' not in path.parts and path.parts[0] in MANAGED and not any(name==prefix or name.startswith(prefix+'/') for prefix in EXCLUDED)


def members(data):
    result={}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for item in archive:
            name=item.name.rstrip('/')
            assert managed(name) and (item.isdir() or item.isfile()), 'Unexpected private snapshot scope/type'
            if item.isfile(): result[name]=archive.extractfile(item).read()
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True, choices=('emulator-5602','emulator-5582'))
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--previous-apk', type=Path, required=True)
    p.add_argument('--provider-apk', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a=p.parse_args()
    assert not a.output.exists(), 'Use a fresh output; rejected attempts must remain intact'
    d=helpers.InspectionDevice(a.serial,a.output,all_windows=True)
    density=int(re.findall(r'\d+',d.shell('wm','density').decode())[-1])/160
    ui=helpers.Dialogs(d,density)
    stamp=str(time.time_ns())
    demo='/storage/emulated/0/Documents/Findroid transfer demo '+stamp
    destination=demo+'/Transfer destination'
    original=b'Keep this original synthetic document through upgrade and transfers.\n'
    window='transfer-progress-'+stamp
    debug=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    saved=None; saved_members=None; grants_before=None; fixture_created=False; connected=False
    fixture_control=None; fixture_control_read=False; sessions=[]; errors=[]
    report=dict(serial=a.serial,checkerSha256=sha(Path(__file__).read_bytes()),helpersSha256=sha((ROOT/'scripts/check-local-search-index.py').read_bytes()),
        apkSha256=sha(a.apk.read_bytes()),previousApkSha256=sha(a.previous_apk.read_bytes()),providerApkSha256=sha(a.provider_apk.read_bytes()),
        fromVersionCode=43,toVersionCode=44,separateReadOnlyProvider=True,syntheticBytesPerStream=SIZE)
    original_scale=d.shell('settings','get','system','font_scale').decode().strip()
    original_screen=d.screen
    size=re.search(r'Override size: (\d+x\d+)',d.shell('wm','size').decode())
    original_size=size.group(1) if size else 'reset'
    access=re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)',d.shell('appops','get','dev.findroid','MANAGE_EXTERNAL_STORAGE').decode())
    original_access=access.group(1) if access else 'default'

    def stop(): d.shell('am','force-stop','dev.findroid')
    def install(apk,downgrade=False):
        stop();d.call('install','-r',*(['-d'] if downgrade else []),apk)
    def launch(location=destination):
        d.shell('am','start','-W','-f','0x10008000','--windowingMode',1,'-n','dev.findroid/.MainActivity','--es','finderWindowId',window,'--es','finderLocation',location)
    def version(): return int(re.search(r'versionCode=(\d+)',d.shell('dumpsys','package','dev.findroid').decode()).group(1))
    def installed_hash():
        path=d.shell('pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        return sha(d.call('exec-out','cat',path))
    def read(package,path,optional=False):
        script='if test -f '+shlex.quote(path)+'; then cat '+shlex.quote(path)+'; elif test -e '+shlex.quote(path)+'; then exit 42; else exit 44; fi'
        result=d.call('shell','-T',shlex.join(['run-as',package,'sh','-c',script]),check=False)
        if optional and result.returncode==44: return None
        assert result.returncode==0, 'Could not read app-owned acceptance state'
        return result.stdout
    def write(package,path,data):
        d.shell('run-as',package,'mkdir','-p',str(Path(path).parent))
        d.call('shell','-T',shlex.join(['run-as',package,'sh','-c','cat > '+shlex.quote(path)]),data=data)
    def snapshot():
        existing=[]
        for name in MANAGED:
            result=d.shell('run-as','dev.findroid','test','-d',name,check=False)
            assert result.returncode in (0,1)
            if result.returncode==0: existing.append(name)
        assert existing
        return d.shell('run-as','dev.findroid','tar','-cf','-',*['--exclude='+name for name in EXCLUDED],*existing)
    def restore():
        stop();current=members(snapshot())
        extra=sorted(set(current)-set(saved_members))
        for start in range(0,len(extra),100):
            names=extra[start:start+100];assert all(managed(name) for name in names)
            d.shell('run-as','dev.findroid','rm','-f','--',*names)
        d.shell('run-as','dev.findroid','tar','-xf','-',data=saved)
        assert members(snapshot())==saved_members, 'Original managed files and absences must be exact'
    def pref(tree,kind,name,value):
        for item in list(tree):
            if item.get('name')==name: tree.remove(item)
        item=ET.SubElement(tree,kind,name=name)
        if kind=='string': item.text=value
        elif kind=='set':
            for entry in value: ET.SubElement(item,'string').text=entry
        else: item.set('value',str(value).lower())
    def grants():
        marker='FINDROID_PRIVATE_GRANT_AUDIT_'+str(time.time_ns())
        since=d.shell('date','+%m-%d %H:%M:%S').decode().strip()+'.000'
        assert re.fullmatch(r'[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.000',since)
        d.shell('log','-t','FindroidGrantAudit','-p','i',marker)
        raw=d.shell('am','instrument','-w','-r','-e','class','dev.findroid.StorageGrantRecoveryTest#auditPersistedPermissionsForSyntheticAcceptance','-e','storageQaAudit','true','dev.findroid.test/androidx.test.runner.AndroidJUnitRunner').decode(errors='replace')
        assert 'OK (1 test)' in raw and 'FAILURES' not in raw, 'Private grant audit did not pass'
        for _ in range(10):
            payload=d.shell('logcat','-d','-T',since,'-v','raw','-s','FindroidGrantAudit:I','System.out:I')
            assert len(payload)<=1024*1024
            text=payload.decode(errors='replace');assert marker in text
            found=re.findall(r'FINDROID_GRANT_AUDIT:([A-Za-z0-9+/=]+)',text.rsplit(marker,1)[1])
            if found:
                assert len(found)==1
                return json.loads(base64.b64decode(found[0],validate=True))
            time.sleep(.1)
        raise AssertionError('Private in-memory grant audit was not returned')
    def seed():
        nonlocal fixture_created
        d.shell('mkdir','-p',str(Path(demo).parent));d.shell('mkdir',demo);fixture_created=True
        d.shell('mkdir',destination)
        d.call('shell','-T','cat > '+shlex.quote(destination+'/Original.txt'),data=original)
        for name in helpers.FILES:
            d.shell('run-as','dev.findroid','rm','-f',*['files/'+name+suffix for suffix in ('','.bak','.new')])
        temporary_history=[name for name in saved_members if name.startswith('databases/transfer-jobs.db') or name.startswith('files/transfer-jobs.properties') or name=='files/active-transfer.txt']
        if temporary_history:d.shell('run-as','dev.findroid','rm','-f','--',*temporary_history)
        tree=ET.fromstring(saved_members.get('shared_prefs/finder.xml',b'<map/>'))
        for item in list(tree):
            if item.get('name','').startswith('tag:'):tree.remove(item)
        tab=dict(id='transfer-progress-upgrade',history=[destination],index=0,view='LIST',query='',rules={})
        for kind,name,value in [('string','tabs',json.dumps([tab])),('int','activeTab',0),('boolean','dualPane',False),('string','activePane','primary'),('boolean','panesReversed',False),('boolean','dark',False),('boolean','sidebarVisible',True),('boolean','preview',False),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),('string','density','COMFORTABLE'),('string','view','LIST'),('string','smartFolders','[]'),('string','transferProgressSentinel',stamp)]:pref(tree,kind,name,value)
        sidebar=dict(version=1,favorites=[dict(id='transfer-destination',path=destination,label='Copy destination')],groups=[],builtins=['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],hidden=['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'])
        pref(tree,'string','sidebarLayoutV1',json.dumps(sidebar));pref(tree,'set','favorites',[destination])
        write('dev.findroid','shared_prefs/finder.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True))
        write('dev.findroid','shared_prefs/locations.xml',b'<?xml version="1.0" encoding="utf-8"?><map><string name="roots">[]</string></map>')
    def menu(name,label,opener='Actions'):
        d.click(name+'-open',opener)
        for direction in (-1,1):
            previous=None
            for _ in range(18):
                tree=d.dump(name)
                if d.ready(tree,label):d.tap(d.one(tree,label));return
                panes=[n for w in tree.iter('window') if w.get('title')=='Pop-Up Window' for n in w.iter('node') if n.get('scrollable')=='true']
                assert len(panes)==1, 'Expected one named command menu'
                signature=ET.tostring(panes[0])
                if signature==previous:break
                previous=signature;l,t,r,b=d.bounds(panes[0]);start,end=(.25,.8) if direction==-1 else (.8,.25)
                d.shell('input','swipe',(l+r)//2,round(t+(b-t)*start),(l+r)//2,round(t+(b-t)*end),300)
        raise AssertionError('Unreachable command '+label)
    def picker():
        nonlocal connected
        d.click('connect-folder','Connect folder…')
        tree=d.wait('system-folder-picker',lambda t:any(n.get('package','').endswith('documentsui') for n in t.iter('node')))
        def drawers(t):return [n for n in t.iter('node') if n.get('resource-id','').endswith('/roots_list') and len(d.bounds(n))==4 and d.bounds(n)[2]>d.bounds(n)[0]]
        for label in ('Show roots','Show navigation drawer'):
            if d.nodes(tree,label):d.tap(d.one(tree,label));tree=d.wait('system-picker-roots',lambda t:bool(drawers(t)));break
        drawer=drawers(tree);assert len(drawer)==1
        d.tap(d.one(drawer[0],'Transfer test files'))
        def selectable(t):return d.ready(t,'USE THIS FOLDER') or d.ready(t,'Use this folder')
        tree=d.wait('system-picker-fixture',lambda t:selectable(t) or (d.ready(t,'Transfer sample.bin') and d.ready(t,'Unknown size.bin')))
        if not selectable(tree):
            # Android11 can restore a provider directory before its PickFragment exists.
            # Switching roots refreshes that fragment. Do not retain the local directory
            # hierarchy: it may contain unrelated user filenames, even on a test tablet.
            original_output=d.output
            with tempfile.TemporaryDirectory(prefix='findroid-private-picker-refresh-') as temporary:
                d.output=Path(temporary)
                try:
                    label=next(label for label in ('Show roots','Show navigation drawer') if d.nodes(tree,label))
                    d.tap(d.one(tree,label));tree=d.wait('roots',lambda t:bool(drawers(t)))
                    local=d.shell('getprop','ro.product.model').decode().strip()
                    assert local in ('sdk_gphone_arm64','sdk_gphone64_arm64')
                    d.tap(d.one(drawers(tree)[0],local))
                    tree=d.wait('local-root',lambda t:bool(d.nodes(t,'USE THIS FOLDER') or d.nodes(t,'Use this folder')))
                    label=next(label for label in ('Show roots','Show navigation drawer') if d.nodes(tree,label))
                    d.tap(d.one(tree,label));tree=d.wait('roots',lambda t:bool(drawers(t)))
                    d.tap(d.one(drawers(tree)[0],'Transfer test files'))
                finally:d.output=original_output
            report['systemPickerRecoveredMissingFolderAction']=True
        tree=d.wait('system-picker-fixture',selectable)
        d.capture('system-picker-fixture')
        label='USE THIS FOLDER' if d.ready(tree,'USE THIS FOLDER') else 'Use this folder';d.tap(d.one(tree,label))
        tree=d.wait('system-picker-confirm',lambda t:d.ready(t,'ALLOW') or d.ready(t,'Allow'))
        d.tap(d.one(tree,'ALLOW' if d.ready(tree,'ALLOW') else 'Allow'));connected=True
        d.wait('connected-fixture',lambda t:d.ready(t,'Actions for Transfer sample.bin') and d.ready(t,'Actions for Unknown size.bin'))
    def configure(session,gate,delay):
        assert re.fullmatch('[A-Za-z0-9_-]{1,80}',session) and gate in ('open','closed') and 0<=delay<=500
        write(PROVIDER,'files/control.properties',f'session={session}\ngate={gate}\ndelayMillis={delay}\n'.encode())
    def sidebar(name,label):
        tree=d.wait(name,lambda t:any(d.bounds(n)[0]<260*density and d.bounds(n)[2]<=280*density for n in d.nodes(t,label)))
        choices=[n for n in d.nodes(tree,label) if d.bounds(n)[0]<260*density and d.bounds(n)[2]<=280*density]
        assert len(choices)==1, 'Expected one synthetic sidebar location'
        d.tap(choices[0])
    def copy(name,session):
        sidebar(name+'-source','Transfer test files')
        d.wait(name+'-source-ready',lambda t:d.ready(t,'Actions for '+name))
        menu(name+'-copy','Copy','Actions for '+name)
        sidebar(name+'-destination','Copy destination')
        d.wait(name+'-destination-ready',lambda t:d.ready(t,'Actions for Original.txt'))
        menu(name+'-paste','Paste')
        deadline=time.monotonic()+20
        file_id='known' if name=='Transfer sample.bin' else 'unknown'
        while read(PROVIDER,'files/opened-'+session+'-'+file_id,True) is None:
            assert time.monotonic()<deadline, 'The actual provider stream was not opened';time.sleep(.1)
    def center(name):
        tree=d.dump(name+'-current')
        if not d.ready(tree,'Close transfers'):
            # The compact strip opens the same dialog without changing its active job.
            if d.ready(tree,'Transfers'):d.tap(d.one(tree,'Transfers'))
            else:menu(name+'-menu','Transfers')
        return d.wait(name+'-panel',lambda t:d.ready(t,'Close transfers'))
    def panel(tree):
        parents={c:p for p in tree.iter() for c in p};node=d.one(tree,'Close transfers')
        while not d.nodes(node,'Details') and not d.nodes(node,'Hide details'):
            assert node in parents,'Transfer card is missing';node=parents[node]
        return node
    def text(tree):return '\n'.join(n.get('text','') for n in panel(tree).iter('node'))
    def control(tree,label):
        scope=panel(tree);parents={c:p for p in scope.iter() for c in p};node=d.one(scope,label)
        while node.get('clickable')!='true' and node in parents:node=parents[node]
        assert node.get('clickable')=='true','Expected the actual named transfer control'
        return node
    def capture(name):d.capture(name)
    def wait_panel(name,predicate,seconds=45):return d.wait(name,lambda t:d.ready(t,'Close transfers') and predicate(text(t)),seconds)
    def pause(name):
        tree=wait_panel(name+'-pausable',lambda s:'left in this phase' in s)
        d.tap(control(tree,'Pause'))
        tree=wait_panel(name+'-paused',lambda s:'Paused ·' in s and 'Resume' in s)
        assert 'left in this phase' not in text(tree) and '/s' not in text(tree)
        return tree
    def resume(name):
        tree=d.dump(name+'-resume');d.tap(control(tree,'Resume'))
    def idle_service(name):
        deadline=time.monotonic()+30
        while re.search(r'ServiceRecord\{[^}\n]*dev\.findroid/(?:\.TransferService|dev\.findroid\.TransferService)',d.shell('dumpsys','activity','services','dev.findroid').decode(errors='replace')):
            assert time.monotonic()<deadline, 'The owned transfer foreground service did not stop after '+name
            time.sleep(.15)
    def wait_finished(name):
        tree=wait_panel(name+'-finished',lambda s:'Completed ·' in s,seconds=120)
        assert not d.nodes(panel(tree),'Cancel transfer') and not d.nodes(panel(tree),'Pause') and not d.nodes(panel(tree),'Resume')
        d.tap(d.one(tree,'Close transfers'));idle_service(name)
    def check_payload(name):
        data=d.call('shell','-T','cat '+shlex.quote(destination+'/'+name));assert len(data)==SIZE
        expected=bytes(range(251))*(SIZE//251)+bytes(range(SIZE%251));assert data==expected
        return dict(bytes=len(data),sha256=sha(data),matchesDeterministicSource=True)
    def close_center(name):
        tree=d.dump(name)
        if d.ready(tree,'Close transfers'):d.tap(d.one(tree,'Close transfers'))
    def release_fixture_grant():
        nonlocal connected
        if not connected:return
        install(debug)
        raw=d.shell('am','instrument','-w','-r','-e','class','dev.findroid.StorageGrantRecoveryTest#releaseOnlySyntheticTransferProviderPermission','-e','transferQaGrantCleanup','true','dev.findroid.test/androidx.test.runner.AndroidJUnitRunner').decode(errors='replace')
        assert 'OK (1 test)' in raw and 'FAILURES' not in raw, 'The fixed-URI synthetic grant cleanup bridge failed'
        connected=False;report['grantCleanupUsesFixedSyntheticUriBridge']=True
    def audit_journal():
        # All optimized UI work is finished. Read its private journal through the same-signed debug build.
        install(debug);current=members(snapshot());report['journalReadAfterOptimizedUiWithDebugBuild']=True
        with tempfile.TemporaryDirectory(prefix='findroid-synthetic-transfer-journal-') as folder:
            db=Path(folder)/'transfer-jobs.db'
            for suffix in ('','-wal','-shm','-journal'):
                data=current.get('databases/transfer-jobs.db'+suffix)
                if data is not None:Path(str(db)+suffix).write_bytes(data)
            with sqlite3.connect(db) as connection:
                jobs=[dict(zip(('state','bytes','items'),row)) for row in connection.execute('SELECT state,bytes,items FROM jobs ORDER BY position')]
                estimates=[dict(zip(('knownBytes','unknownFiles','unreadable','limited'),row)) for row in connection.execute('SELECT known_bytes,unknown_files,unreadable,limited FROM estimates ORDER BY rowid')]
        assert len(jobs)==3 and sorted(j['state'] for j in jobs)==['CANCELLED','COMPLETED','COMPLETED']
        assert len([j for j in jobs if j['state']=='COMPLETED' and j['bytes']==SIZE and j['items']==1])==2
        assert next(j for j in jobs if j['state']=='CANCELLED')['bytes']==0
        assert any(e['knownBytes']==SIZE and e['unknownFiles']==0 and e['unreadable']==0 and e['limited']==0 for e in estimates)
        assert any(e['unknownFiles']==1 for e in estimates)
        report['syntheticJournal']=dict(jobs=jobs,estimates=estimates,verificationDoesNotDoubleCountCopiedBytes=True)

    try:
        install(debug);saved=snapshot();saved_members=members(saved);grants_before=grants()
        assert TREE not in grants_before,'The provider acceptance grant must be newly owned'
        seed();d.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE','allow')
        install(a.previous_apk,True);assert version()==43
        launch();d.wait('previous-public',lambda t:d.ready(t,'Actions for Original.txt') and d.ready(t,'Options for Copy destination'));capture('previous-public')
        install(a.apk);assert version()==44 and installed_hash()==report['apkSha256']
        launch();d.wait('upgraded-browser',lambda t:d.ready(t,'Actions for Original.txt') and d.ready(t,'Options for Copy destination'))
        report.update(directPreviousPublicUpgrade=True,legacyFavoritePreserved=True)
        d.call('install','-r',a.provider_apk)
        fixture_control=read(PROVIDER,'files/control.properties',True);fixture_control_read=True
        picker();connected=True;report['actualSystemPickerConnection']=True
        known='known_'+stamp;sessions.append(known);configure(known,'closed',400);copy('Transfer sample.bin',known);center('known')
        tree=wait_panel('known-provider-wait',lambda s:'Waiting for the storage provider' in s,seconds=25)
        assert '0 bytes / 8.0 MB' in text(tree) and 'left in this phase' not in text(tree) and '/s' not in text(tree)
        capture('provider-wait');report['visibleProviderWaitWithoutInventedEta']=True
        configure(known,'open',400)
        tree=wait_panel('known-recovered-rate',lambda s:'left in this phase' in s and '/s' in s,seconds=35)
        capture('recent-rate');report['recentRateAndPhaseEtaAfterRecovery']=True
        tree=pause('known');capture('paused');report['pauseHidesRateAndEta']=True
        layouts=[]
        for width in (1280,800,600):
            for scale in (1.0,1.6):
                key=f'{width}dp-font{scale}'
                # Keep the transfer/service alive across real activity configuration changes.
                pixels=round(width*density);height=round(800*density)
                d.shell('wm','size',f'{pixels}x{height}');d.screen=(pixels,height)
                d.shell('settings','put','system','font_scale',str(scale))
                center(key);tree=wait_panel(key+'-paused',lambda s:'Paused ·' in s and 'Resume' in s)
                capture(key+'-paused')
                for label in ('Close transfers','Resume','Cancel transfer','Details','Open folder'):
                    node=control(tree,label);assert node.get('enabled')=='true' and ui.full(tree,node),(key,label,'full 48dp control required')
                assert 'left in this phase' not in text(tree) and '/s' not in text(tree)
                d.tap(control(tree,'Close transfers'))
                tree=d.wait(key+'-strip',lambda t:d.ready(t,'Transfers') and d.ready(t,'Resume') and d.ready(t,'Cancel transfer'))
                capture(key+'-strip')
                for label in ('Transfers','Resume','Cancel transfer'):
                    nodes=d.nodes(tree,label);assert len(nodes)==1
                    parents={c:p for p in tree.iter() for c in p};node=nodes[0]
                    while node.get('clickable')!='true' and node in parents:node=parents[node]
                    assert node.get('clickable')=='true' and ui.full(tree,node),(key,label,'complete strip control required')
                labels=[n for n in tree.iter('node') if n.get('text','').startswith('Paused ·') and ('Transfer sample.bin' in n.get('text','') or ' / 8.0 MB' in n.get('text',''))]
                minimum=min(120,80*scale)
                widths=[(d.bounds(n)[2]-d.bounds(n)[0])/density for n in labels]
                assert len(widths)==2 and min(widths)>=minimum,(key,'progress text is squeezed',widths)
                layouts.append(dict(widthDp=width,fontScale=scale,pausedTransferSurvivesConfiguration=True,dialogControlsFullyReachable=True,stripControlsReachable=True,stripProgressReadable=True,stripProgressTextWidthDp=widths,minimumProgressTextWidthDp=minimum))
                print('Verified active transfer layout '+key,flush=True)
        report['layouts']=layouts
        d.shell('wm','size',original_size);d.screen=original_screen;d.shell('settings','put','system','font_scale','1.0')
        center('known-before-resume');resume('known');wait_finished('known')
        report['knownSizeCopy']=check_payload('Transfer sample.bin')
        unknown='unknown_'+stamp;sessions.append(unknown);configure(unknown,'open',400);copy('Unknown size.bin',unknown);center('unknown')
        tree=wait_panel('unknown-live-rate',lambda s:'/s' in s and 'processed' in s,seconds=35)
        assert 'left in this phase' not in text(tree) and ' / 8.0 MB' not in text(tree)
        capture('unknown-size');report['unknownSizeHasRateWithoutInventedTotalOrEta']=True
        wait_finished('unknown');report['unknownSizeCopy']=check_payload('Unknown size.bin')
        # A different empty destination avoids a filename-conflict prompt before cancellation.
        d.shell('rm','--',destination+'/Transfer sample.bin')
        cancel='cancel_'+stamp;sessions.append(cancel);configure(cancel,'closed',100);copy('Transfer sample.bin',cancel);center('cancel')
        tree=wait_panel('cancel-provider-wait',lambda s:'Waiting for the storage provider' in s,seconds=25);capture('cancel-wait')
        d.tap(control(tree,'Cancel transfer'))
        tree=wait_panel('cancel-finished',lambda s:'Cancelled ·' in s,seconds=35);capture('cancelled')
        assert d.shell('test','!','-e',destination+'/Transfer sample.bin',check=False).returncode==0
        names=d.shell('find',destination,'-mindepth',1,'-maxdepth',1,'-type','f').decode().splitlines()
        assert set(Path(n).name for n in names)=={'Original.txt','Unknown size.bin'}, 'Cancelled staging must be removed'
        assert d.call('shell','-T','cat '+shlex.quote(destination+'/Original.txt'))==original
        report.update(waitingCancellationKeepsOriginal=True,cancelledDestinationAndStagingAbsent=True)
        d.tap(d.one(tree,'Close transfers'))
        idle_service('cancellation');report['foregroundServiceStopsAfterFinish']=True
        audit_journal();launch(destination);release_fixture_grant()
        install(debug);assert grants()==grants_before
        prefs=ET.fromstring(read('dev.findroid','shared_prefs/finder.xml'))
        assert prefs.find("./string[@name='transferProgressSentinel']").text==stamp
        report.update(unrelatedUriGrantsUnchanged=True,restoredOriginalUriGrants=True,sentinelPreferencePreserved=True,originalSyntheticDocumentBytes=True)
    finally:
        def cleanup(label,action):
            try:action();return True
            except Exception as error:errors.append(dict(step=label,error=str(error)));return False
        if connected:
            cleanup('release only the owned fixture connection',release_fixture_grant)
        def originals():
            install(debug)
            try:
                if grants_before is not None:
                    assert grants()==grants_before,'Original persisted permissions were not restored';report['restoredOriginalUriGrants']=True
            finally:
                if saved is not None:
                    restore();report.update(restoredOriginalManagedFiles=True,restoredOriginalFileAbsences=True,restoredManagedFileCount=len(saved_members))
        cleanup('restore all private original managed bytes',originals)
        def provider_cleanup():
            d.shell('am','force-stop',PROVIDER)
            for session in sessions:
                d.shell('run-as',PROVIDER,'rm','-f','files/opened-'+session+'-known','files/opened-'+session+'-unknown')
            if fixture_control_read:
                if fixture_control is None:d.shell('run-as',PROVIDER,'rm','-f','files/control.properties')
                else:write(PROVIDER,'files/control.properties',fixture_control)
                assert read(PROVIDER,'files/control.properties',True)==fixture_control
            report['providerControlsAndOwnedMarkersRestored']=True
        cleanup('stop owned test provider and restore its controls',provider_cleanup)
        if fixture_created:cleanup('remove only the synthetic document folder',lambda:d.shell('rm','-rf',demo))
        report['syntheticFixtureRemoved']=d.shell('test','!','-e',demo,check=False).returncode==0
        cleanup('restore original display size',lambda:d.shell('wm','size',original_size));d.screen=original_screen
        cleanup('restore original font size',lambda:d.shell('settings','put','system','font_scale',original_scale))
        def storage():
            d.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE',original_access)
            current=re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)',d.shell('appops','get','dev.findroid','MANAGE_EXTERNAL_STORAGE').decode())
            assert (current.group(1) if current else 'default')==original_access;report['restoredOriginalStorageAccess']=True
        cleanup('restore original storage access',storage)
        cleanup('restore exact optimized APK',lambda:install(a.apk));report['restoredApkSha256']=installed_hash()
        cleanup('remove owned native input helper',d.close)
        report['cleanupErrors']=errors
        (a.output/'inspection-retries.json').write_text(json.dumps(d.retries,indent=2)+'\n')
        (a.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
        if errors:raise RuntimeError('Cleanup failed; inspect verification.json')
    assert report['restoredApkSha256']==report['apkSha256'] and report['syntheticFixtureRemoved']
    report['success']=True
    (a.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
