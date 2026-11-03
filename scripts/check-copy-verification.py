#!/usr/bin/env python3
"""Check optimized 0.42 -> 0.43 optional copy verification using an owned synthetic provider.

Only assigned tablets are allowed. Never clears/uninstalls Findroid. Private managed
state is retained in memory and private recovery files, then restored byte-for-byte,
including original absences.
The provider generates bounded sources and writes only its private synthetic destination.
Its original APK and full managed data, including empty directories, are restored.
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
from urllib.parse import quote, unquote
from android.acceptance import ROOT

spec = importlib.util.spec_from_file_location('verification_inspection_helpers', ROOT/'scripts/check-local-search-index.py')
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
    p.add_argument('--review-only', action='store_true', help='Check reviewed selection/layouts and cancellation, then restore state without confirming any copy')
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
    demo='/storage/emulated/0/Documents/Findroid verification demo '+stamp
    destination=demo+'/Copy destination'
    original=b'Keep this original synthetic document through upgrade and transfers.\n'
    window='copy-verification-'+stamp
    debug=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    saved=None; saved_members=None; grants_before=None; fixture_created=False; connected=False
    provider_saved=None; provider_entries=None; provider_apk=None; provider_mutated=False; sessions=[]; errors=[]; expected_jobs=[]
    report=dict(serial=a.serial,checkerSha256=sha(Path(__file__).read_bytes()),helpersSha256=sha((ROOT/'scripts/check-local-search-index.py').read_bytes()),
        apkSha256=sha(a.apk.read_bytes()),previousApkSha256=sha(a.previous_apk.read_bytes()),providerApkSha256=sha(a.provider_apk.read_bytes()),
        fromVersionCode=44,toVersionCode=45,separateOwnedProvider=True,syntheticBytesPerStream=SIZE,reviewOnlyRequested=a.review_only)
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
        tab=dict(id='copy-verification-upgrade',history=[destination],index=0,view='LIST',query='',rules={})
        for kind,name,value in [('string','tabs',json.dumps([tab])),('int','activeTab',0),('boolean','dualPane',False),('string','activePane','primary'),('boolean','panesReversed',False),('boolean','dark',False),('boolean','sidebarVisible',True),('boolean','preview',False),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),('string','density','COMFORTABLE'),('string','view','LIST'),('string','conflictPolicy','ASK'),('string','smartFolders','[]'),('string','copyVerificationSentinel',stamp)]:pref(tree,kind,name,value)
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
        public_output=d.output
        with tempfile.TemporaryDirectory(prefix='findroid-private-picker-navigation-') as private_picker:
            d.output=Path(private_picker)
            try:
                d.click('connect-folder','Connect folder…')
                tree=d.wait('system-folder-picker',lambda t:any(n.get('package','').endswith('documentsui') for n in t.iter('node')))
                def drawers(t):return [n for n in t.iter('node') if n.get('resource-id','').endswith('/roots_list') and len(d.bounds(n))==4 and d.bounds(n)[2]>d.bounds(n)[0]]
                for label in ('Show roots','Show navigation drawer'):
                    if d.nodes(tree,label):d.tap(d.one(tree,label));tree=d.wait('system-picker-roots',lambda t:bool(drawers(t)));break
                drawer=drawers(tree);assert len(drawer)==1
                d.tap(d.one(drawer[0],'Verification test files'))
                def selectable(t):return d.ready(t,'USE THIS FOLDER') or d.ready(t,'Use this folder')
                tree=d.wait('system-picker-fixture',lambda t:selectable(t) or (d.ready(t,'Transfer sample.bin') and d.ready(t,'Unknown size.bin')))
                if not selectable(tree):
                    # Android11 can restore a provider directory before its PickFragment exists.
                    # Switching roots refreshes that fragment. Do not retain the local directory
                    # hierarchy: it may contain unrelated user filenames, even on a test tablet.
                    refresh_output=d.output
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
                            d.tap(d.one(drawers(tree)[0],'Verification test files'))
                        finally:d.output=refresh_output
                    report['systemPickerRecoveredMissingFolderAction']=True
                tree=d.wait('system-picker-fixture',selectable)
            finally:
                d.output=public_output
                report['systemPickerPrivateNavigationNotRetained']=True
        d.capture('system-picker-fixture')
        label='USE THIS FOLDER' if d.ready(tree,'USE THIS FOLDER') else 'Use this folder';d.tap(d.one(tree,label))
        tree=d.wait('system-picker-confirm',lambda t:d.ready(t,'ALLOW') or d.ready(t,'Allow'))
        d.tap(d.one(tree,'ALLOW' if d.ready(tree,'ALLOW') else 'Allow'));connected=True
        d.wait('connected-fixture',lambda t:d.ready(t,'Actions for Transfer sample.bin') and d.ready(t,'Actions for Unknown size.bin'))
    def configure(session, *, source_gate='open', readback_gate='open', source_delay=0, readback_delay=0, readback='normal'):
        assert re.fullmatch('[A-Za-z0-9_-]{1,80}',session)
        assert source_gate in ('open','closed') and readback_gate in ('open','closed')
        assert readback in ('normal','corrupt','unreadable') and all(0<=v<=500 for v in (source_delay,readback_delay))
        payload=f'session={session}\nsourceGate={source_gate}\nreadbackGate={readback_gate}\nsourceDelayMillis={source_delay}\nreadbackDelayMillis={readback_delay}\nreadback={readback}\n'
        write(PROVIDER,'files/control.properties.verification-new',payload.encode())
        d.shell('run-as',PROVIDER,'mv','files/control.properties.verification-new','files/control.properties')
    def marker(session, phase, seconds=30):
        deadline=time.monotonic()+seconds
        while read(PROVIDER,'files/opened-'+session+'-'+phase,True) is None:
            assert time.monotonic()<deadline, 'Actual synthetic provider stream did not open: '+phase
            time.sleep(.1)
    def sidebar(name,label):
        tree=d.wait(name,lambda t:any(d.bounds(n)[0]<260*density and d.bounds(n)[2]<=280*density for n in d.nodes(t,label)))
        choices=[n for n in d.nodes(tree,label) if d.bounds(n)[0]<260*density and d.bounds(n)[2]<=280*density]
        assert len(choices)==1, 'Expected one synthetic sidebar location'
        d.tap(choices[0])
    def browse(location,name):
        # A retained window keeps its current folder when relaunched. Navigate
        # using actual named sidebar/folder controls, as a user would.
        if location==DOCUMENT:
            sidebar(name+'-source','Verification test files')
            return d.wait(name,lambda t:d.ready(t,'Actions for Transfer sample.bin') and d.ready(t,'Actions for Unknown size.bin'))
        if location.startswith(destination+'/'):
            case=str(Path(location).relative_to(destination))
            assert '/' not in case
            sidebar(name+'-local-root','Copy destination')
            d.wait(name+'-local-child',lambda t:d.ready(t,'Actions for '+case))
        else:
            assert location.startswith(TREE+'/document/')
            document=unquote(location.split('/document/',1)[1]);assert document.startswith('out/')
            case=document.removeprefix('out/');assert '/' not in case
            sidebar(name+'-provider-root','Verification test files')
            d.click(name+'-provider-destination','Provider destination')
            d.wait(name+'-provider-child',lambda t:d.ready(t,'Actions for '+case))
        d.click(name+'-open-folder',case)
        return d.wait(name,lambda t:d.ready(t,'Actions') and bool(d.nodes(t,case)) and not d.ready(t,'Actions for '+case))
    def review(name, target, case):
        browse(DOCUMENT,case+'-source-browser')
        d.wait(case+'-source-ready',lambda t:d.ready(t,'Actions for '+name))
        menu(case+'-copy','Copy','Actions for '+name)
        browse(target,case+'-destination-browser')
        menu(case+'-paste','Paste and verify…')
        return d.wait(case+'-review',lambda t:d.ready(t,'Review verified copy') and d.ready(t,'Copy and verify'))
    def confirm(case):ui.click(case+'-confirm','Review verified copy','Copy and verify','Copy and verify')
    def center(name):
        tree=d.dump(name+'-current')
        if not d.ready(tree,'Close transfers'):
            # A short copy can finish between inspection and tapping its strip.
            # The named command remains available after completion.
            menu(name+'-menu','Transfers')
        return d.wait(name+'-center',lambda t:d.ready(t,'Close transfers'))
    def card(tree):
        # Anchor the newest card through its first Details control. Older cards may
        # also say Completed; none may satisfy the current job's UI assertions.
        controls=[n for n in tree.iter('node') if n.get('text') in ('Details','Hide details') and len(d.bounds(n))==4 and d.bounds(n)[2]>d.bounds(n)[0]]
        assert controls, 'Newest transfer card is missing'
        parents={c:p for p in tree.iter() for c in p};node=controls[0]
        while not any(n.get('text','').startswith('Copy verification · ') for n in node.iter('node')):
            assert node in parents, 'Newest card has no verification summary';node=parents[node]
        summaries=[n for n in node.iter('node') if n.get('text','').startswith('Copy verification · ')]
        assert len(summaries)==1, 'The selected card must identify one verification job'
        return node
    def text(tree):return '\n'.join(n.get('text','') for n in card(tree).iter('node'))
    def control(tree,label):
        scope=tree if label=='Close transfers' else card(tree)
        parents={c:p for p in scope.iter() for c in p};node=d.one(scope,label)
        while node.get('clickable')!='true' and node in parents:node=parents[node]
        assert node.get('clickable')=='true', 'Expected a named clickable transfer control'
        return node
    def wait_card(name,predicate,seconds=60):
        return d.wait(name,lambda t:d.ready(t,'Close transfers') and predicate(text(t)),seconds)
    def close_center(name):
        tree=d.dump(name)
        if d.ready(tree,'Close transfers'):d.tap(d.one(tree,'Close transfers'))
    def idle_service(name):
        deadline=time.monotonic()+30
        while re.search(r'ServiceRecord\{[^}\n]*dev\.findroid/(?:\.TransferService|dev\.findroid\.TransferService)',d.shell('dumpsys','activity','services','dev.findroid').decode(errors='replace')):
            assert time.monotonic()<deadline, 'Owned foreground transfer service did not stop after '+name
            time.sleep(.15)
    def finish(case,state='Completed',summary=None):
        center(case)
        tree=wait_card(case+'-finished',lambda s:state+' ·' in s and (summary is None or summary in s),seconds=120)
        assert not any(d.nodes(card(tree),label) for label in ('Pause','Resume','Cancel transfer'))
        expected_name={'nested':'Nested originals','unknown':'Unknown size.bin','unreadable':'Unknown size.bin'}.get(case,'Transfer sample.bin')
        assert d.nodes(card(tree),expected_name), 'Completed provider transfers must show the filename, not the provider document ID'
        assert not any(d.nodes(card(tree),opaque) for opaque in ('known','unknown','bundle'))
        report.setdefault('completedProviderFriendlyTitles',[]).append(dict(case=case,title=expected_name))
        d.capture(case+'-result');idle_service(case)
        return tree
    def expected(size):return bytes(range(251))*(size//251)+bytes(range(size%251))
    def local_payload(folder,name,size):
        data=d.call('shell','-T','cat '+shlex.quote(folder+'/'+name));assert data==expected(size)
        return dict(bytes=len(data),sha256=sha(data),matchesDeterministicSource=True)
    def provider_payload(folder,name,size=None,exact=None):
        data=read(PROVIDER,'files/destination/'+folder+'/'+name)
        assert data==(expected(size) if exact is None else exact)
        return dict(bytes=len(data),sha256=sha(data),matchesExpectedSyntheticBytes=True)
    def local_target(case):
        path=destination+'/'+case;d.shell('mkdir',path);return path
    def provider_target(case):
        assert re.fullmatch('[a-z-]+',case)
        assert d.shell('run-as',PROVIDER,'test','!','-e','files/destination/'+case,check=False).returncode==0, 'Use only a newly owned synthetic destination'
        d.shell('run-as',PROVIDER,'mkdir','-p','files/destination/'+case)
        return TREE+'/document/'+quote('out/'+case,safe='')
    def assert_provider_names(case,names):
        data=d.shell('run-as',PROVIDER,'find','files/destination/'+case,'-mindepth','1','-maxdepth','1').decode().splitlines()
        assert {Path(n).name for n in data}==set(names), 'Unpublished synthetic staging must be removed'
    def set_layout(width,scale):
        pixels=round(width*density);height=round(800*density)
        d.shell('wm','size',f'{pixels}x{height}');d.screen=(pixels,height)
        d.shell('settings','put','system','font_scale',str(scale))
    def reset_layout():
        d.shell('wm','size',original_size);d.screen=original_screen
        d.shell('settings','put','system','font_scale','1.0')
    def scroll_scope(name,scope_fn,predicate):
        for direction in (1,-1):
            previous=None
            for _ in range(18):
                tree=d.dump(name);scope=scope_fn(tree)
                if predicate(scope):return tree
                panes=[n for n in scope.iter('node') if n.get('scrollable')=='true']
                if not panes:break
                assert len(panes)==1, 'One owned content scroll region required'
                pane=panes[0];signature=ET.tostring(pane)
                if signature==previous:break
                previous=signature;l,t,r,b=d.bounds(pane);start,end=(.8,.25) if direction==1 else (.25,.8)
                d.shell('input','swipe',(l+r)//2,round(t+(b-t)*start),(l+r)//2,round(t+(b-t)*end),350)
        raise AssertionError('Owned content is not reachable: '+name)
    def review_layouts():
        layouts=[]
        for width in (1280,800,600):
            for scale in (1.0,1.6):
                key=f'review-{width}dp-font{scale}';set_layout(width,scale)
                tree=d.wait(key,lambda t:d.ready(t,'Review verified copy') and d.ready(t,'Copy and verify'))
                ui.footer(tree,'Review verified copy','Copy and verify',('Cancel','Copy and verify'))
                scope=lambda t:ui.panel(t,'Review verified copy','Copy and verify')
                for label in ('From','To'):
                    scroll_scope(key+'-'+label,scope,lambda s:bool(d.nodes(s,label)))
                def complete_text(t,node):
                    l,t0,r,b=d.bounds(node);parents={c:p for p in t.iter() for c in p}
                    if not (0<=l<r<=d.screen[0] and 0<=t0<b<=d.screen[1]):return False
                    while node in parents:
                        node=parents[node]
                        if node.get('scrollable')=='true':
                            pl,pt,pr,pb=d.bounds(node)
                            if not (pl<=l<r<=pr and pt+2<=t0<b<=pb-2):return False
                    return True
                for label,predicate in (('source-path',lambda value:'Transfer sample.bin' in value),('destination-path',lambda value:'Findroid verification demo' in value and 'known' in value)):
                    scroll_scope(key+'-'+label,scope,lambda s:any(predicate(n.get('text','')) and complete_text(s,n) for n in s.iter('node') if len(d.bounds(n))==4))
                tree=scroll_scope(key+'-limit',scope,lambda s:any('10,000' in n.get('text','') and complete_text(s,n) for n in s.iter('node') if len(d.bounds(n))==4))
                ui.footer(tree,'Review verified copy','Copy and verify',('Cancel','Copy and verify'));d.capture(key)
                layouts.append(dict(widthDp=width,fontScale=scale,sourceAndDestinationReachable=True,limitReachable=True,namedFooterControlsComplete48dp=True))
                print('Verified copy review layout '+key,flush=True)
        report['reviewLayouts']=layouts
    def transfer_layouts(paused):
        layouts=[]
        for width in (1280,800,600):
            for scale in (1.0,1.6):
                key=f'{"paused" if paused else "completed"}-{width}dp-font{scale}';set_layout(width,scale);center(key)
                tree=wait_card(key,lambda s:('Paused ·' if paused else 'Completed ·') in s)
                labels=('Close transfers','Resume','Cancel transfer','Details','Open folder') if paused else ('Close transfers','Details','Open folder')
                for label in labels:
                    node=control(tree,label);assert node.get('enabled')=='true' and ui.full(tree,node),(key,label,'complete 48dp control required')
                assert ('Copy verification · 0 verified and copied · 1 not verified' in text(tree)) if paused else ('Copy verification · 1 verified and copied' in text(tree))
                assert d.nodes(card(tree),'Transfer sample.bin'), 'Configuration changes must keep the friendly source filename'
                d.capture(key)
                layouts.append(dict(widthDp=width,fontScale=scale,namedControlsComplete48dp=True,verificationSummaryVisible=True,friendlySourceFilenameVisible=True,activeJobSurvivesConfiguration=paused))
                print('Verified transfer layout '+key,flush=True)
        report['pausedLayouts' if paused else 'completedLayouts']=layouts
    def result_details(case,expected_text):
        tree=d.dump(case+'-details-before');d.tap(control(tree,'Details'))
        # Details can extend beyond the first viewport. Find the text inside the
        # transfer dialog's scroll region without accepting a different job card.
        def scope(t):
            parents={c:p for p in t.iter() for c in p};node=d.one(t,'Close transfers')
            while not any(n.get('scrollable')=='true' for n in node.iter('node')):
                assert node in parents;node=parents[node]
            return node
        def complete(scope,node):
            l,t,r,b=d.bounds(node)
            if not (0<=l<r<=d.screen[0] and 0<=t<b<=d.screen[1]):return False
            parents={c:p for p in scope.iter() for c in p}
            while node in parents:
                node=parents[node]
                if node.get('scrollable')=='true':
                    pl,pt,pr,pb=d.bounds(node)
                    if not (pl<=l<r<=pr and pt+2<=t<b<=pb-2):return False
            return True
        tree=scroll_scope(case+'-details',scope,lambda s:any(expected_text in n.get('text','') and len(d.bounds(n))==4 and complete(s,n) for n in s.iter('node')))
        d.capture(case+'-details');close_center(case+'-details-close')
    def release_fixture_grant():
        nonlocal connected
        if not connected:return
        install(debug)
        raw=d.shell('am','instrument','-w','-r','-e','class','dev.findroid.StorageGrantRecoveryTest#releaseOnlySyntheticTransferProviderPermission','-e','transferQaGrantCleanup','true','dev.findroid.test/androidx.test.runner.AndroidJUnitRunner').decode(errors='replace')
        assert 'OK (1 test)' in raw and 'FAILURES' not in raw, 'Fixed synthetic URI cleanup bridge failed'
        connected=False;report['grantCleanupUsesFixedSyntheticUriBridge']=True
    def provider_snapshot():
        return d.shell('run-as',PROVIDER,'tar','-cf','-','--exclude=./lib','.')
    def provider_manifest(data):
        result={}
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            for item in archive:
                name=item.name.removeprefix('./').rstrip('/')
                if name in ('','.'):continue
                path=Path(name)
                assert not path.is_absolute() and '..' not in path.parts
                assert path.parts[0] in (*MANAGED,'code_cache') and (item.isfile() or item.isdir()), 'Unexpected private provider state scope/type'
                result[name]=('directory',None) if item.isdir() else ('file',sha(archive.extractfile(item).read()))
        return result
    def preserve_provider():
        nonlocal provider_saved,provider_entries,provider_apk
        d.shell('am','force-stop',PROVIDER)
        path=d.shell('pm','path',PROVIDER).decode().strip().removeprefix('package:')
        assert path.startswith('/') and '\n' not in path, 'The prior fixture APK must already exist'
        provider_apk=d.call('exec-out','cat',path)
        provider_saved=provider_snapshot();provider_entries=provider_manifest(provider_saved)
        private=a.output/'PRIVATE-provider-recovery';private.mkdir(mode=0o700)
        for name,data in [('original.apk',provider_apk),('original-state.tar',provider_saved)]:
            file=private/name;file.write_bytes(data);file.chmod(0o600)
        report.update(priorProviderApkSha256=sha(provider_apk),priorProviderEntryCount=len(provider_entries))
    def restore_provider():
        if provider_saved is None:return
        d.shell('am','force-stop',PROVIDER)
        if provider_mutated:d.call('install','-r','-d',a.output/'PRIVATE-provider-recovery/original.apk')
        d.shell('am','force-stop',PROVIDER)
        current=provider_manifest(provider_snapshot());extra=set(current)-set(provider_entries)
        for name in sorted((n for n in extra if current[n][0]=='file')):d.shell('run-as',PROVIDER,'rm','-f','--',name)
        for name in sorted((n for n in extra if current[n][0]=='directory'),key=lambda n:(len(Path(n).parts),n),reverse=True):d.shell('run-as',PROVIDER,'rmdir','--',name)
        d.shell('run-as',PROVIDER,'tar','-xf','-',data=provider_saved)
        assert provider_manifest(provider_snapshot())==provider_entries, 'All original provider files, directories and absences must match'
        path=d.shell('pm','path',PROVIDER).decode().strip().removeprefix('package:')
        assert sha(d.call('exec-out','cat',path))==sha(provider_apk), 'Exact prior provider APK must be restored'
        report.update(originalProviderApkAndAllManagedDataRestored=True,originalProviderEmptyDirectoriesAndAbsencesRestored=True)
    def audit_journal():
        install(debug);current=members(snapshot())
        with tempfile.TemporaryDirectory(prefix='findroid-synthetic-verification-journal-') as folder:
            db=Path(folder)/'transfer-jobs.db'
            for suffix in ('','-wal','-shm','-journal'):
                data=current.get('databases/transfer-jobs.db'+suffix)
                if data is not None:Path(str(db)+suffix).write_bytes(data)
            with sqlite3.connect(db) as connection:
                connection.row_factory=sqlite3.Row
                schema=connection.execute('PRAGMA user_version').fetchone()[0]
                jobs=[dict(row) for row in connection.execute('SELECT id,destination,state,bytes,items,verify_copies FROM jobs ORDER BY position')]
                rows=[]
                assert schema==3 and len(jobs)==len(expected_jobs), 'Only actual confirmed synthetic copies may create jobs'
                for wanted in expected_jobs:
                    found=[j for j in jobs if j['destination']==wanted['destination']];assert len(found)==1
                    job=found[0]
                    checks=[dict(r) for r in connection.execute('SELECT status,destination,bytes,expected,actual FROM copy_verifications WHERE job_id=? ORDER BY position',(job['id'],))]
                    assert job['verify_copies']==1 and job['state']==wanted['state'] and job['bytes']==wanted['bytes'] and job['items']==wanted['items'], (wanted['case'],'unexpected transfer record')
                    assert len(checks)==wanted['results'] and all(c['status']==wanted['status'] for c in checks),(wanted['case'],'unexpected verification records')
                    for check in checks:
                        assert check['expected'] is not None
                        if wanted['status']=='VERIFIED':
                            assert check['destination'] and check['actual']==check['expected'] and check['bytes']>0
                        else:
                            assert check['destination'] is None
                            if wanted['status']=='MISMATCH':assert check['actual'] and check['actual']!=check['expected']
                            else:assert check['actual'] is None
                    rows.append(dict(case=wanted['case'],state=job['state'],copiedBytes=job['bytes'],processedItems=job['items'],verifyCopies=True,results=checks))
        report.update(journalReadAfterOptimizedUiWithDebugBuild=True,syntheticJournal=dict(schema=schema,jobs=rows,verificationDoesNotDoubleCountCopiedBytes=True,cancelledReviewsCreateNoJobs=True))
    def record(case,target,state,size,items,status,results=1):
        expected_jobs.append(dict(case=case,destination=target,state=state,bytes=size,items=items,status=status,results=results))

    try:
        install(debug);saved=snapshot();saved_members=members(saved);grants_before=grants()
        assert TREE not in grants_before, 'The synthetic root grant must be newly owned'
        private=a.output/'PRIVATE-app-recovery';private.mkdir(mode=0o700)
        private_file=private/'original-state.tar';private_file.write_bytes(saved);private_file.chmod(0o600)
        environment=private/'PRIVATE-environment.json'
        environment.write_text(json.dumps(dict(serial=a.serial,originalGrants=grants_before,
            originalFontScale=original_scale,originalSize=original_size,originalScreen=original_screen,
            originalStorageAccess=original_access,ownedSyntheticDirectory=demo,originalStateSha256=sha(saved)),indent=2)+'\n')
        environment.chmod(0o600)
        report['privateEnvironmentRecoverySaved']=True
        seed();d.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE','allow')
        install(a.previous_apk,True);assert version()==44
        launch();d.wait('previous-public',lambda t:d.ready(t,'Actions for Original.txt') and d.ready(t,'Options for Copy destination'));d.capture('previous-public')
        install(a.apk);assert version()==45 and installed_hash()==report['apkSha256']
        launch();d.wait('upgraded-browser',lambda t:d.ready(t,'Actions for Original.txt') and d.ready(t,'Options for Copy destination'))
        report.update(directPreviousPublicUpgrade=True,legacyFavoritePreserved=True)
        preserve_provider();provider_mutated=True;d.call('install','-r',a.provider_apk)
        picker();report['actualSystemPickerConnection']=True

        target=local_target('known');session='review_'+stamp;sessions.append(session);configure(session)
        review('Transfer sample.bin',target,'cancel-review');review_layouts()
        ui.click('cancel-review','Review verified copy','Copy and verify','Cancel')
        assert read(PROVIDER,'files/opened-'+session+'-source',True) is None
        assert d.shell('test','!','-e',target+'/Transfer sample.bin',check=False).returncode==0
        report['cancelledReviewStartsNoProviderReadOrCopy']=True;reset_layout()
        if a.review_only:
            report['reviewOnlyAcceptance']=True
        else:
            review('Transfer sample.bin',target,'known');confirm('known');finish('known',summary='1 verified and copied')
            report['knownSizeCopy']=local_payload(target,'Transfer sample.bin',SIZE)
            record('known',target,'COMPLETED',SIZE,1,'VERIFIED')
            result_details('known','Verified and copied')

            target=local_target('unknown');session='unknown_'+stamp;sessions.append(session);configure(session)
            review('Unknown size.bin',target,'unknown');confirm('unknown');finish('unknown',summary='1 verified and copied')
            report['unknownSizeCopy']=local_payload(target,'Unknown size.bin',SIZE)
            record('unknown',target,'COMPLETED',SIZE,1,'VERIFIED');close_center('unknown-close')

            target=local_target('nested');session='nested_'+stamp;sessions.append(session);configure(session)
            review('Nested originals',target,'nested');confirm('nested');finish('nested',summary='2 verified and copied')
            report['nestedFolderCopy']=[local_payload(target+'/Nested originals','One.bin',1024*1024),local_payload(target+'/Nested originals/Sub','Two.bin',2*1024*1024)]
            assert d.shell('test','-d',target+'/Nested originals/Empty',check=False).returncode==0
            report['emptyNestedFolderPreserved']=True
            record('nested',target,'COMPLETED',3*1024*1024,5,'VERIFIED',2);close_center('nested-close')

            target=provider_target('readback');session='readback_'+stamp;sessions.append(session)
            configure(session,readback_gate='closed',readback_delay=500)
            review('Transfer sample.bin',target,'readback');confirm('readback');marker(session,'readback')
            center('readback');tree=wait_card('readback-active',lambda s:'Verifying copy' in s)
            d.capture('readback-active');d.tap(control(tree,'Pause'))
            tree=wait_card('readback-paused',lambda s:'Paused ·' in s and 'Resume' in s)
            # Open the gate while paused so its bounded initial wait cannot expire
            # during the layout matrix. Backpressure holds the finite pipe stream.
            configure(session,readback_delay=500);transfer_layouts(paused=True)
            reset_layout();center('readback-resume');tree=wait_card('readback-resumable',lambda s:'Paused ·' in s)
            d.tap(control(tree,'Resume'));finish('readback',summary='1 verified and copied')
            report['providerDestinationCopy']=provider_payload('readback','Transfer sample.bin',SIZE)
            record('readback',target,'COMPLETED',SIZE,1,'VERIFIED')
            transfer_layouts(paused=False);reset_layout();center('readback-details');result_details('readback','Copy SHA-256:')
            report.update(realProviderReadback=True,verificationPauseResume=True)

            target=provider_target('corrupt');session='corrupt_'+stamp;sessions.append(session);configure(session,readback='corrupt')
            review('Transfer sample.bin',target,'corrupt');confirm('corrupt');marker(session,'readback')
            finish('corrupt',state='Needs attention',summary='1 not verified');assert_provider_names('corrupt',[])
            record('corrupt',target,'FAILED',SIZE,1,'MISMATCH');result_details('corrupt','Contents did not match')
            report['sameLengthCorruptionRejectsAndCleansUnpublishedCopy']=True

            target=provider_target('unreadable');session='unreadable_'+stamp;sessions.append(session);configure(session,readback='unreadable')
            review('Unknown size.bin',target,'unreadable');confirm('unreadable');marker(session,'readback')
            finish('unreadable',state='Needs attention',summary='1 not verified');assert_provider_names('unreadable',[])
            record('unreadable',target,'FAILED',SIZE,1,'UNREADABLE');result_details('unreadable','Could not read the copy')
            report['unreadableDestinationRejectsAndCleansUnpublishedCopy']=True

            target=provider_target('cancel');session='cancel_'+stamp;sessions.append(session)
            configure(session,readback_gate='closed',readback_delay=500)
            review('Transfer sample.bin',target,'cancel');confirm('cancel');marker(session,'readback')
            center('cancel');tree=wait_card('cancel-readback',lambda s:'Verifying copy' in s)
            d.capture('cancel-readback');d.tap(control(tree,'Cancel transfer'))
            finish('cancel',state='Cancelled',summary='1 not verified');assert_provider_names('cancel',[])
            record('cancel',target,'CANCELLED',SIZE,1,'INCOMPLETE');result_details('cancel','Verification incomplete')
            report['readbackCancellationKeepsSourceAndCleansStaging']=True

            target=provider_target('replace');session='replace_'+stamp;sessions.append(session)
            replacement=b'Keep this reviewed existing destination after verification failure.\n'
            write(PROVIDER,'files/destination/replace/Transfer sample.bin',replacement)
            configure(session,readback='corrupt')
            review('Transfer sample.bin',target,'replace');confirm('replace')
            title='An item named “Transfer sample.bin” already exists'
            d.wait('replace-conflict',lambda t:d.ready(t,title))
            ui.click('replace-reviewed',title,'Cancel transfer','Replace with recovery')
            marker(session,'readback');finish('replace',state='Needs attention',summary='1 not verified')
            assert_provider_names('replace',['Transfer sample.bin'])
            report['failedReplacementRestoredOriginal']=provider_payload('replace','Transfer sample.bin',exact=replacement)
            record('replace',target,'FAILED',SIZE+len(replacement),3,'MISMATCH');result_details('replace','Contents did not match')
            assert d.call('shell','-T','cat '+shlex.quote(destination+'/Original.txt'))==original
            report.update(foregroundServiceStopsAfterEveryFinish=True,originalSyntheticDocumentBytes=True,sourceOriginalsReadOnly=True)
            audit_journal();release_fixture_grant();install(debug);assert grants()==grants_before
            prefs=ET.fromstring(read('dev.findroid','shared_prefs/finder.xml'))
            assert prefs.find("./string[@name='copyVerificationSentinel']").text==stamp
            report.update(unrelatedUriGrantsUnchanged=True,restoredOriginalUriGrants=True,sentinelPreferencePreserved=True)
    except BaseException as error:
        report['acceptanceFailure']=dict(type=type(error).__name__,message=str(error))
        raise
    finally:
        def cleanup(label,action):
            try:action();return True
            except Exception as error:errors.append(dict(step=label,error=str(error)));return False
        if connected:cleanup('release only the owned synthetic connection',release_fixture_grant)
        def originals():
            install(debug)
            try:
                if grants_before is not None:
                    assert grants()==grants_before, 'Original persisted permissions must match';report['restoredOriginalUriGrants']=True
            finally:
                if saved is not None:
                    restore();report.update(restoredOriginalManagedFiles=True,restoredOriginalFileAbsences=True,restoredManagedFileCount=len(saved_members))
        cleanup('restore all original private managed bytes',originals)
        cleanup('restore prior provider binary and all original provider data',restore_provider)
        if fixture_created:cleanup('remove only the owned synthetic document folder',lambda:d.shell('rm','-rf',demo))
        report['syntheticFixtureRemoved']=d.shell('test','!','-e',demo,check=False).returncode==0
        cleanup('restore original display size',lambda:d.shell('wm','size',original_size));d.screen=original_screen
        cleanup('restore original font size',lambda:d.shell('settings','put','system','font_scale',original_scale))
        def storage():
            d.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE',original_access)
            current=re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)',d.shell('appops','get','dev.findroid','MANAGE_EXTERNAL_STORAGE').decode())
            assert (current.group(1) if current else 'default')==original_access;report['restoredOriginalStorageAccess']=True
        cleanup('restore original storage access',storage)
        cleanup('restore exact optimized APK',lambda:install(a.apk));report['restoredApkSha256']=installed_hash()
        cleanup('remove owned native touch helper',d.close)
        report['cleanupErrors']=errors
        (a.output/'inspection-retries.json').write_text(json.dumps(d.retries,indent=2)+'\n')
        (a.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
        if errors:raise RuntimeError('Cleanup failed; inspect verification.json')
    assert report['restoredApkSha256']==report['apkSha256'] and report['syntheticFixtureRemoved']
    report['success']=True
    (a.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
