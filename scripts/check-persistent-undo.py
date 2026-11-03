#!/usr/bin/env python3
"""Exercise the exact optimized persistent-Undo APK through real native tablet controls.

Completed copy/rename/Trash actions, cold-process Undo/Redo, occupied names, corruption,
manual reset and 18 tablet layouts are checked with byte receipts. All original private
state, directory absences, grants and display/storage settings are restored. Debug is
used only while stopped for fixture setup/inspection, never to perform a user action.
"""
import argparse,base64,hashlib,importlib.util,io,json,re,shlex,tarfile,time
from pathlib import Path
import xml.etree.ElementTree as ET
from android.acceptance import ROOT

spec=importlib.util.spec_from_file_location('undo_inspection_helpers',ROOT/'scripts/check-local-search-index.py')
helpers=importlib.util.module_from_spec(spec);spec.loader.exec_module(helpers)
MANAGED=('shared_prefs','databases','no_backup','cache','files')
EXCLUDED=('files/Workspace','files/ProviderFixtures')
def sha(data):return hashlib.sha256(data).hexdigest()
def managed(name):
    p=Path(name)
    return bool(p.parts) and not p.is_absolute() and '..' not in p.parts and p.parts[0] in MANAGED and not any(name==x or name.startswith(x+'/') for x in EXCLUDED)
def contents(data):
    files={};directories=set()
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for item in archive:
            name=item.name.rstrip('/');assert managed(name) and (item.isdir() or item.isfile())
            if item.isfile():files[name]=archive.extractfile(item).read()
            else:directories.add(name)
    return files,directories


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True,choices=('emulator-5602','emulator-5582'))
    p.add_argument('--layout-limit',type=int,choices=(0,18),default=18,help='0 is a controller preflight, never release acceptance')
    p.add_argument('--apk',required=True,type=Path);p.add_argument('--previous-apk',required=True,type=Path);p.add_argument('--output',required=True,type=Path)
    a=p.parse_args();assert not a.output.exists(),'Preserve prior attempts; choose a fresh output'
    d=helpers.InspectionDevice(a.serial,a.output,all_windows=True)
    density=int(re.findall(r'\d+',d.shell('wm','density').decode())[-1])/160;ui=helpers.Dialogs(d,density)
    debug=ROOT/'app/build/outputs/apk/debug/app-debug.apk';stamp=str(time.time_ns());window='persistent-undo-'+stamp
    payload=(b'Original synthetic report: keep its bytes through every Undo and Redo.\n'*4096)
    saved=None;saved_files=None;saved_dirs=None;fixture=None;before_grants=None;errors=[]
    report=dict(serial=a.serial,checkerSha256=sha(Path(__file__).read_bytes()),
        helpersSha256=sha((ROOT/'scripts/check-local-search-index.py').read_bytes()),nativeInspectionSha256=sha((ROOT/'scripts/android/acceptance.py').read_bytes()),
        apkSha256=sha(a.apk.read_bytes()),previousApkSha256=sha(a.previous_apk.read_bytes()),debugInspectionApkSha256=sha(debug.read_bytes()),
        fromVersionCode=46,toVersionCode=47,operationsUseExactOptimizedApk=True,freshAndroidHierarchies=True)
    original_scale=d.shell('settings','get','system','font_scale').decode().strip();original_screen=d.screen
    override=re.search(r'Override size: (\d+x\d+)',d.shell('wm','size').decode());original_size=override.group(1) if override else 'reset'
    mode=re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)',d.shell('appops','get','dev.findroid','MANAGE_EXTERNAL_STORAGE').decode());original_access=mode.group(1) if mode else 'default'
    private=a.output/'PRIVATE-recovery';private.mkdir(mode=0o700)
    journal_path='files/.undo-history/history.json'
    def stop():d.shell('am','force-stop','dev.findroid')
    def install(apk,downgrade=False):stop();d.call('install','-r',*(['-d'] if downgrade else []),apk)
    def read(path,optional=False):
        command='if test -f '+shlex.quote(path)+'; then cat '+shlex.quote(path)+'; elif test -e '+shlex.quote(path)+'; then exit 42; else exit 44; fi'
        r=d.call('shell','-T',shlex.join(['run-as','dev.findroid','sh','-c',command]),check=False)
        if optional and r.returncode==44:return None
        assert r.returncode==0,'Could not inspect the owned file';return r.stdout
    def write(path,data):
        d.shell('run-as','dev.findroid','mkdir','-p',str(Path(path).parent))
        d.call('shell','-T',shlex.join(['run-as','dev.findroid','sh','-c','cat > '+shlex.quote(path)]),data=data)
    def snapshot():
        names=[]
        for name in MANAGED:
            r=d.shell('run-as','dev.findroid','test','-d',name,check=False);assert r.returncode in (0,1)
            if r.returncode==0:names.append(name)
        return d.shell('run-as','dev.findroid','tar','-cf','-',*['--exclude='+name for name in EXCLUDED],*names)
    def restore():
        current,current_dirs=contents(snapshot());extra=sorted(set(current)-set(saved_files))
        for start in range(0,len(extra),100):
            names=extra[start:start+100];assert all(managed(name) for name in names)
            d.shell('run-as','dev.findroid','rm','-f','--',*names)
        d.shell('run-as','dev.findroid','tar','-xf','-',data=saved)
        _,current_dirs=contents(snapshot())
        for name in sorted(current_dirs-saved_dirs,key=lambda n:(-n.count('/'),n)):
            assert managed(name);d.shell('run-as','dev.findroid','rmdir','--',name)
        assert contents(snapshot())==(saved_files,saved_dirs),'Original bytes and directory/file absences must match'
    def pref(tree,kind,name,value):
        for item in list(tree):
            if item.get('name')==name:tree.remove(item)
        item=ET.SubElement(tree,kind,name=name)
        if kind=='string':item.text=value
        elif kind=='set':
            for entry in value:ET.SubElement(item,'string').text=entry
        else:item.set('value',str(value).lower())
    def grants():
        marker='FINDROID_PRIVATE_GRANT_AUDIT_'+str(time.time_ns());since=d.shell('date','+%m-%d %H:%M:%S').decode().strip()+'.000'
        d.shell('log','-t','FindroidGrantAudit','-p','i',marker)
        raw=d.shell('am','instrument','-w','-r','-e','class','dev.findroid.StorageGrantRecoveryTest#auditPersistedPermissionsForSyntheticAcceptance','-e','storageQaAudit','true','dev.findroid.test/androidx.test.runner.AndroidJUnitRunner').decode(errors='replace')
        assert 'OK (1 test)' in raw and 'FAILURES' not in raw
        logs=d.shell('logcat','-d','-T',since,'-v','raw','-s','FindroidGrantAudit:I','System.out:I').decode(errors='replace')
        found=re.findall(r'FINDROID_GRANT_AUDIT:([A-Za-z0-9+/=]+)',logs.rsplit(marker,1)[1]);assert len(found)==1
        return json.loads(base64.b64decode(found[0],validate=True))
    def launch(location):d.shell('am','start','-W','-f','0x10008000','--windowingMode',1,'-n','dev.findroid/.MainActivity','--es','finderWindowId',window,'--es','finderLocation',location)
    def installed_hash():
        path=d.shell('pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:');return sha(d.call('exec-out','cat',path))
    def version():return int(re.search(r'versionCode=(\d+)',d.shell('dumpsys','package','dev.findroid').decode()).group(1))
    def optimized(location):install(a.apk);assert installed_hash()==report['apkSha256'];launch(location)
    def capture(name):d.dump(name);d.capture(name)
    def scope(tree):
        scopes=[w for w in tree.iter('window') if w.get('type')=='TYPE_APPLICATION' and w.get('active')=='true' and w.get('focused')=='true' and any(n.get('package')=='dev.findroid' for n in w.iter('node'))]
        assert len(scopes)==1,'Expected one focused Findroid dialog';return scopes[0]
    def reach(name,label,seconds=90):
        deadline=time.monotonic()+seconds;direction=1;previous=None
        while time.monotonic()<deadline:
            tree=d.dump(name);panel=scope(tree);parents={c:p for p in panel.iter() for c in p};controls=[]
            for node in d.nodes(panel,label):
                while node.get('clickable')!='true' and node in parents:node=parents[node]
                if node.get('clickable')=='true' and node not in controls:controls.append(node)
            assert len(controls)<=1,'Ambiguous named control: '+label
            if controls and controls[0].get('enabled')=='true' and ui.full(tree,controls[0]):return tree,controls[0]
            panes=[n for n in panel.iter('node') if n.get('scrollable')=='true']
            if not panes:time.sleep(.15);continue
            assert len(panes)==1;signature=ET.tostring(panes[0])
            if signature==previous:direction=-direction
            previous=signature;l,t,r,b=d.bounds(panes[0]);start,end=(.8,.25) if direction==1 else (.25,.8)
            d.shell('input','swipe',(l+r)//2,round(t+(b-t)*start),(l+r)//2,round(t+(b-t)*end),300)
        raise AssertionError('No complete enabled 48 dp control: '+label)
    def click(name,label):tree,node=reach(name,label);d.tap(node);return tree
    def menu(name,label,opener='Actions'):
        d.click(name+'-open',opener)
        for direction in (-1,1):
            previous=None
            for _ in range(18):
                tree=d.dump(name)
                if d.ready(tree,label):d.tap(d.one(tree,label));return
                panes=[n for w in tree.iter('window') if w.get('title')=='Pop-Up Window' for n in w.iter('node') if n.get('scrollable')=='true']
                assert len(panes)==1;signature=ET.tostring(panes[0])
                if signature==previous:break
                previous=signature;l,t,r,b=d.bounds(panes[0]);start,end=(.25,.8) if direction==-1 else (.8,.25)
                d.shell('input','swipe',(l+r)//2,round(t+(b-t)*start),(l+r)//2,round(t+(b-t)*end),300)
        raise AssertionError('Unreachable command: '+label)
    def recent(name):
        menu(name+'-menu','Recent actions…');return d.wait(name+'-opened',lambda t:d.ready(t,'Recent actions'))
    def next_saved(tree,prefix):
        panel=scope(tree);parents={c:p for p in panel.iter() for c in p}
        for node in panel.iter('node'):
            if not node.get('text','').startswith(prefix):continue
            while node.get('clickable')!='true' and node in parents:node=parents[node]
            if node.get('clickable')=='true' and d.nodes(node,'Saved for restart') and d.nodes(node,'Completed · Next to undo'):return True
        return False
    def wait_saved(name,prefix):
        recent(name);return d.wait(name+'-saved',lambda t:next_saved(t,prefix))
    def close(name):click(name,'Close');d.wait(name+'-browser',lambda t:d.ready(t,'Actions'))
    def inspect(name):
        install(debug);raw=read(journal_path);assert read('files/.undo-history/pending.txt',True) is None
        document=json.loads(raw);assert document['schema']==1
        (a.output/(name+'-history.json')).write_bytes(raw)
        assert read(fixture+'/Original.txt')==payload and read(fixture+'/Out/Keep.txt')==b'Keep destination'
        return document,raw
    def row(name,label):
        tree=d.wait(name,lambda t:d.ready(t,'Options for '+label));nodes=d.nodes(tree,label)
        matching=[n for n in nodes if d.bounds(n)[0]<280*density];assert len(matching)==1;d.tap(matching[0])
    def seed():
        nonlocal fixture
        home=d.shell('run-as','dev.findroid','pwd').decode().strip();assert home.endswith('/dev.findroid')
        fixture=home+'/files/Workspace/Undo-'+stamp;d.shell('run-as','dev.findroid','mkdir','-p',fixture+'/Out')
        write(fixture+'/Original.txt',payload);write(fixture+'/Out/Keep.txt',b'Keep destination')
        for name in helpers.FILES:
            d.shell('run-as','dev.findroid','rm','-f',*['files/'+name+suffix for suffix in ('','.bak','.new')])
        # Only original metadata is temporarily replaced. Its exact files/absences are in the private snapshot.
        for name in saved_files:
            if name.startswith(('databases/transfer-jobs.db','no_backup/local-search-index/','files/.undo-history/')):
                d.shell('run-as','dev.findroid','rm','-f','--',name)
        tree=ET.fromstring(read('shared_prefs/finder.xml',True) or b'<map/>')
        for item in list(tree):
            if item.get('name','').startswith('tag:'):tree.remove(item)
        tab=dict(id='undo-upgrade',history=[fixture],index=0,view='LIST',query='',rules={})
        for kind,name,value in [('string','tabs',json.dumps([tab])),('int','activeTab',0),('boolean','dualPane',False),('string','activePane','primary'),('boolean','panesReversed',False),('boolean','dark',False),('boolean','sidebarVisible',True),('boolean','preview',False),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),('string','density','COMFORTABLE'),('string','view','LIST'),('string','smartFolders','[]'),('string','persistentUndoSentinel',stamp)]:pref(tree,kind,name,value)
        sidebar=dict(version=1,favorites=[dict(id='undo-source',path=fixture,label='Undo source'),dict(id='undo-destination',path=fixture+'/Out',label='Undo destination')],groups=[],builtins=['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],hidden=['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'])
        pref(tree,'string','sidebarLayoutV1',json.dumps(sidebar));pref(tree,'set','favorites',[fixture,fixture+'/Out'])
        write('shared_prefs/finder.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True));write('shared_prefs/locations.xml',b'<map><string name="roots">[]</string></map>')
    def layouts(undo_button):
        rows=[]
        for width,height in ((1280,800),(800,1280),(600,800)):
            for font in (1.0,1.5,2.0):
                for dark in (False,True):
                    install(debug);tree=ET.fromstring(read('shared_prefs/finder.xml'));pref(tree,'boolean','dark',dark);write('shared_prefs/finder.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True))
                    d.shell('wm','size',f'{round(width*density)}x{round(height*density)}');d.screen=(round(width*density),round(height*density));d.shell('settings','put','system','font_scale',str(font))
                    optimized(fixture+'/Out');name=f'review-{width}x{height}-font{font}-'+('dark' if dark else 'light');recent(name)
                    proofs=[]
                    for key,label in [('Undo',undo_button),('Reset','Reset Undo list…'),('Close','Close')]:
                        tree,node=reach(name+'-'+key,label);assert ui.full(tree,node);capture(name+'-'+key);proofs.append(dict(image=name+'-'+key+'.png',label=label))
                    click(name+'-open-reset','Reset Undo list…');d.wait(name+'-reset-open',lambda t:d.ready(t,'Reset Undo list?'))
                    for key,label in [('Keep','Keep Undo list'),('Confirm','Reset Undo list')]:
                        tree,node=reach(name+'-'+key,label);assert ui.full(tree,node);capture(name+'-'+key);proofs.append(dict(image=name+'-'+key+'.png',label=label))
                    click(name+'-keep','Keep Undo list');close(name+'-close')
                    rows.append(dict(widthDp=width,heightDp=height,fontScale=font,dark=dark,allNamedControlsReachable=True,controls=proofs))
        d.shell('wm','size',original_size);d.screen=original_screen;d.shell('settings','put','system','font_scale',original_scale)
        install(debug);tree=ET.fromstring(read('shared_prefs/finder.xml'));pref(tree,'boolean','dark',False);write('shared_prefs/finder.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True))
        return rows
    try:
        install(debug);stop();saved=snapshot();saved_files,saved_dirs=contents(saved);(private/'PRIVATE-original-state.tar').write_bytes(saved)
        before_grants=grants();(private/'PRIVATE-ENVIRONMENT.json').write_text(json.dumps(dict(originalGrants=before_grants,originalAccess=original_access,fontScale=original_scale,originalSizeOverride=original_size,originalScreen=original_screen),indent=2)+'\n')
        report['originalEnvironmentPersistedBeforeMutation']=True;stop();seed()
        install(a.previous_apk,True);assert version()==46;launch(fixture);d.wait('previous-public-sidebar',lambda t:d.ready(t,'Options for Undo source'));capture('previous-public-sidebar')
        install(a.apk);assert version()==47 and installed_hash()==report['apkSha256'];launch(fixture);d.wait('upgraded-sidebar',lambda t:d.ready(t,'Options for Undo source'));capture('upgraded-sidebar');report['directPreviousPublicUpgrade']=True
        menu('copy-original','Copy','Actions for Original.txt');row('destination','Undo destination');d.wait('destination',lambda t:d.ready(t,'Actions for Keep.txt'));menu('paste','Paste')
        wait_saved('copied','Copy');capture('copy-saved');close('copied-close')
        menu('rename','Rename…','Actions for Original.txt');tree=d.wait('rename-dialog',lambda t:any(n.get('class')=='android.widget.EditText' for n in t.iter('node')))
        edits=[n for n in scope(tree).iter('node') if n.get('class')=='android.widget.EditText'];assert len(edits)==1;d.tap(edits[0])
        d.shell('input','keyevent','123');d.shell('input','keyevent',*(['67']*len('Original.txt')));d.shell('input','text','Renamed.txt')
        typed=d.dump('rename-typed')
        if any(w.get('type')=='TYPE_INPUT_METHOD' for w in typed.iter('window')):d.shell('input','keyevent','4')
        click('rename-confirm','Rename')
        d.wait('renamed',lambda t:d.ready(t,'Actions for Renamed.txt'));wait_saved('renamed','Rename');capture('rename-saved');close('renamed-close')
        menu('trash','Move to Trash','Actions for Renamed.txt');wait_saved('trashed','Move to Trash');capture('trash-saved');close('trashed-close')
        completed,raw=inspect('completed');assert [step['kind'] for command in completed['undo'] for step in command['steps']]==['CREATED','MOVED','TRASHED'] and not completed['redo']
        trash=completed['undo'][-1]['steps'][0]['paths'][1];assert read(trash)==payload
        optimized(fixture+'/Out');recent('reopened');capture('reopened-saved');close('reopened-close');again,unchanged=inspect('reopened-no-action');assert raw==unchanged
        report.update(actualOptimizedProcessDeath=True,restartStartsNoAction=True,copyRenameTrashSaved=True)
        report['layoutChecks']=layouts('Undo '+completed['undo'][-1]['label']) if a.layout_limit else []
        report['controllerPreflightOnly']=a.layout_limit==0
        for direction in ('Undo','Redo'):
            for index in range(3):
                state,_=inspect(direction.lower()+'-before-'+str(index));stack=state['undo' if direction=='Undo' else 'redo'];label=direction+' '+stack[-1]['label']
                optimized(fixture+'/Out');recent(direction.lower()+'-'+str(index));click(direction.lower()+'-confirm-'+str(index),label)
                expected=3-index-1 if direction=='Undo' else index+1
                d.wait(direction.lower()+'-settled-'+str(index),lambda t:not any(n.get('text','').startswith('Wait for the current operation:') for n in t.iter('node')))
                # A newly completed next action or next Redo appears only after the shared operation has finished.
                time.sleep(.4);close(direction.lower()+'-close-'+str(index));state,_=inspect(direction.lower()+'-after-'+str(index));assert len(state['undo'])==expected
                if direction=='Undo':
                    if index==0:assert read(fixture+'/Out/Renamed.txt')==payload
                    elif index==1:assert read(fixture+'/Out/Original.txt')==payload
                    else:assert read(fixture+'/Out/Original.txt',True) is None and read(fixture+'/Out/Renamed.txt',True) is None
                else:
                    if index==0:assert read(fixture+'/Out/Original.txt')==payload
                    elif index==1:assert read(fixture+'/Out/Renamed.txt')==payload
                    else:assert read(state['undo'][-1]['steps'][0]['paths'][1])==payload
        report.update(coldProcessUndoAndRedoAllThreeActions=True,originalSourceBytesVerified=True)
        optimized(fixture+'/Out');recent('occupied-prepare');click('restore-before-collision','Undo Move to Trash');time.sleep(.4);close('restored-close')
        state,before=inspect('before-collision');write(fixture+'/Out/Original.txt',b'Keep the occupied destination')
        optimized(fixture+'/Out');recent('occupied');click('occupied-undo','Undo '+state['undo'][-1]['label'])
        # Undo controls are below the timeline; reopen at the heading to inspect the
        # retained refusal instead of waiting for off-screen lazy content.
        close('occupied-result-close');recent('occupied-result')
        d.wait('guarded-undo',lambda t:d.ready(t,'The last Undo or Redo stopped'));capture('occupied-destination')
        close('occupied-close');after,after_bytes=inspect('after-collision');assert before==after_bytes and read(fixture+'/Out/Original.txt')==b'Keep the occupied destination' and read(fixture+'/Out/Renamed.txt')==payload
        report['occupiedDestinationKeptAndJournalUnchanged']=True
        corrupt=b'owned synthetic broken Undo record';write(journal_path,corrupt);optimized(fixture+'/Out');recent('damaged');d.wait('damaged-review',lambda t:d.ready(t,'Saved Undo needs review'));capture('damaged-review')
        click('damaged-reset','Reset Undo list…');click('damaged-keep','Keep Undo list');close('damaged-close');install(debug);assert read(journal_path)==corrupt
        optimized(fixture+'/Out');recent('manual-reset');click('manual-reset-review','Reset Undo list…');click('manual-reset-confirm','Reset Undo list');d.wait('reset-completed',lambda t:d.ready(t,'No recent actions'));capture('reset-completed');close('reset-close');install(debug)
        assert read(journal_path,True) is None and read(fixture+'/Out/Renamed.txt')==payload and read(fixture+'/Out/Original.txt')==b'Keep the occupied destination'
        paths=d.shell('run-as','dev.findroid','find','files','-maxdepth','2','-name','history.json').decode().splitlines()
        assert any(name.startswith('files/.undo-history-preserved-') and read(name)==corrupt for name in paths)
        report.update(corruptHistoryReadOnly=True,cancelledResetKeepsExactJournal=True,manualResetPreservesRecordAndDocuments=True)
        tree=ET.fromstring(read('shared_prefs/finder.xml'));assert tree.find("./string[@name='persistentUndoSentinel']").text==stamp
        assert {n.text for n in tree.findall("./set[@name='favorites']/string")}=={fixture,fixture+'/Out'}
        assert grants()==before_grants;report.update(legacyFavoritePreserved=True,sentinelPreferencePreserved=True,unrelatedUriGrantsUnchanged=True)
    finally:
        def cleanup(label,work):
            try:work()
            except Exception as error:errors.append(label+': '+str(error))
        cleanup('stop app',stop)
        def originals():
            install(debug)
            try:
                if before_grants is not None:assert grants()==before_grants;report['restoredOriginalUriGrants']=True
            finally:
                if saved is not None:restore();report.update(restoredOriginalManagedFiles=True,restoredOriginalFileAbsences=True,restoredOriginalDirectoryAbsences=True,restoredManagedFileCount=len(saved_files))
            if fixture is not None:
                assert fixture.endswith('/Undo-'+stamp);d.shell('run-as','dev.findroid','rm','-rf','--',fixture)
        cleanup('restore original state',originals)
        cleanup('restore display',lambda:d.shell('wm','size',original_size));d.screen=original_screen
        cleanup('restore font',lambda:d.shell('settings','put','system','font_scale',original_scale))
        def access():
            d.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE',original_access)
            mode=re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)',d.shell('appops','get','dev.findroid','MANAGE_EXTERNAL_STORAGE').decode());assert (mode.group(1) if mode else 'default')==original_access;report['restoredOriginalStorageAccess']=True
        cleanup('restore storage access',access);cleanup('restore exact optimized APK',lambda:install(a.apk));report['restoredApkSha256']=installed_hash();cleanup('remove owned input helper',d.close)
        report['cleanupErrors']=errors;(a.output/'inspection-retries.json').write_text(json.dumps(d.retries,indent=2)+'\n');(a.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
        if errors:raise RuntimeError('Restoration needs review; inspect verification.json')
    assert report['restoredApkSha256']==report['apkSha256'];report['success']=True;(a.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))


if __name__=='__main__':main()
