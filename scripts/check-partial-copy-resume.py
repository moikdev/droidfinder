#!/usr/bin/env python3
"""Check optimized partial-copy recovery after actual process death on the assigned tablets.

Uses only newly owned synthetic files. Private state, absences, URI grants, display settings,
and storage access are preserved. No application data is cleared or uninstalled. A temporary
installation of the matching debug APK permits stopped-app journal inspection; every user
operation and recovery action runs on the exact optimized APK.
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

spec=importlib.util.spec_from_file_location('resume_inspection_helpers', ROOT/'scripts/check-local-search-index.py')
helpers=importlib.util.module_from_spec(spec);spec.loader.exec_module(helpers)
MANAGED=('shared_prefs','databases','no_backup','cache','files')
EXCLUDED=('files/Workspace','files/ProviderFixtures')
SIZE=512*1024*1024
INTERRUPT_DELAY_SECONDS=1.5


def sha(data):return hashlib.sha256(data).hexdigest()
def managed(name):
    p=Path(name)
    return bool(p.parts) and not p.is_absolute() and '..' not in p.parts and p.parts[0] in MANAGED and not any(name==x or name.startswith(x+'/') for x in EXCLUDED)
def members(data):
    result={}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for item in archive:
            name=item.name.rstrip('/')
            assert managed(name) and (item.isdir() or item.isfile()), 'Unexpected private snapshot entry'
            if item.isfile():result[name]=archive.extractfile(item).read()
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True,choices=('emulator-5602','emulator-5582'))
    p.add_argument('--apk',required=True,type=Path)
    p.add_argument('--previous-apk',required=True,type=Path)
    p.add_argument('--output',required=True,type=Path)
    a=p.parse_args();assert not a.output.exists(), 'Preserve attempts; choose a fresh output'
    d=helpers.InspectionDevice(a.serial,a.output,all_windows=True)
    density=int(re.findall(r'\d+',d.shell('wm','density').decode())[-1])/160
    ui=helpers.Dialogs(d,density)
    debug=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    stamp=str(time.time_ns());window='partial-copy-'+stamp
    public='/storage/emulated/0/Documents/Findroid resume demo '+stamp
    original_name='Resumable original.bin'
    payload=b'Keep this synthetic document and its favorite through the upgrade.\n'
    saved=None;saved_members=None;before_grants=None;fixture=None;source=None;target=None;created=False
    errors=[];report=dict(serial=a.serial,checkerSha256=sha(Path(__file__).read_bytes()),
        helpersSha256=sha((ROOT/'scripts/check-local-search-index.py').read_bytes()),
        apkSha256=sha(a.apk.read_bytes()),previousApkSha256=sha(a.previous_apk.read_bytes()),
        debugInspectionApkSha256=sha(debug.read_bytes()),fromVersionCode=45,toVersionCode=46,
        sourceBytes=SIZE,interruptDelaySeconds=INTERRUPT_DELAY_SECONDS,operationsUseExactOptimizedApk=True)
    original_scale=d.shell('settings','get','system','font_scale').decode().strip();original_screen=d.screen
    override=re.search(r'Override size: (\d+x\d+)',d.shell('wm','size').decode());original_size=override.group(1) if override else 'reset'
    mode=re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)',d.shell('appops','get','dev.findroid','MANAGE_EXTERNAL_STORAGE').decode())
    original_access=mode.group(1) if mode else 'default'
    private=a.output/'PRIVATE-recovery';private.mkdir(mode=0o700)

    def stop():d.shell('am','force-stop','dev.findroid')
    def install(apk, downgrade=False):stop();d.call('install','-r',*(['-d'] if downgrade else []),apk)
    def read(path,optional=False):
        script='if test -f '+shlex.quote(path)+'; then cat '+shlex.quote(path)+'; elif test -e '+shlex.quote(path)+'; then exit 42; else exit 44; fi'
        r=d.call('shell','-T',shlex.join(['run-as','dev.findroid','sh','-c',script]),check=False)
        if optional and r.returncode==44:return None
        assert r.returncode==0,'Could not inspect an app-owned file'
        return r.stdout
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
        stop();extra=sorted(set(members(snapshot()))-set(saved_members))
        for start in range(0,len(extra),100):
            names=extra[start:start+100];assert all(managed(name) for name in names)
            d.shell('run-as','dev.findroid','rm','-f','--',*names)
        d.shell('run-as','dev.findroid','tar','-xf','-',data=saved)
        assert members(snapshot())==saved_members, 'Original private bytes and absences must match'
    def pref(tree,kind,name,value):
        for item in list(tree):
            if item.get('name')==name:tree.remove(item)
        item=ET.SubElement(tree,kind,name=name)
        if kind=='string':item.text=value
        elif kind=='set':
            for entry in value:ET.SubElement(item,'string').text=entry
        else:item.set('value',str(value).lower())
    def grants():
        marker='FINDROID_PRIVATE_GRANT_AUDIT_'+str(time.time_ns())
        since=d.shell('date','+%m-%d %H:%M:%S').decode().strip()+'.000'
        d.shell('log','-t','FindroidGrantAudit','-p','i',marker)
        raw=d.shell('am','instrument','-w','-r','-e','class','dev.findroid.StorageGrantRecoveryTest#auditPersistedPermissionsForSyntheticAcceptance','-e','storageQaAudit','true','dev.findroid.test/androidx.test.runner.AndroidJUnitRunner').decode(errors='replace')
        assert 'OK (1 test)' in raw and 'FAILURES' not in raw
        text=d.shell('logcat','-d','-T',since,'-v','raw','-s','FindroidGrantAudit:I','System.out:I').decode(errors='replace')
        found=re.findall(r'FINDROID_GRANT_AUDIT:([A-Za-z0-9+/=]+)',text.rsplit(marker,1)[1]);assert len(found)==1
        return json.loads(base64.b64decode(found[0],validate=True))
    def launch(location):
        d.shell('am','start','-W','-f','0x10008000','--windowingMode',1,'-n','dev.findroid/.MainActivity','--es','finderWindowId',window,'--es','finderLocation',location)
    def version():return int(re.search(r'versionCode=(\d+)',d.shell('dumpsys','package','dev.findroid').decode()).group(1))
    def installed_hash():
        path=d.shell('pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        return sha(d.call('exec-out','cat',path))
    def menu(name,label,opener='Actions'):
        d.click(name+'-open',opener)
        for direction in (-1,1):
            previous=None
            for _ in range(18):
                tree=d.dump(name)
                if d.ready(tree,label):d.tap(d.one(tree,label));return
                panes=[n for w in tree.iter('window') if w.get('title')=='Pop-Up Window' for n in w.iter('node') if n.get('scrollable')=='true']
                assert len(panes)==1,'Expected one named command menu'
                signature=ET.tostring(panes[0])
                if signature==previous:break
                previous=signature;l,t,r,b=d.bounds(panes[0]);start,end=(.25,.8) if direction==-1 else (.8,.25)
                d.shell('input','swipe',(l+r)//2,round(t+(b-t)*start),(l+r)//2,round(t+(b-t)*end),300)
        raise AssertionError('Unreachable command '+label)
    def sidebar(name,label):
        tree=d.wait(name,lambda t:any(d.bounds(n)[0]<260*density and d.bounds(n)[2]<=280*density for n in d.nodes(t,label)))
        nodes=[n for n in d.nodes(tree,label) if d.bounds(n)[0]<260*density and d.bounds(n)[2]<=280*density]
        assert len(nodes)==1;d.tap(nodes[0])
    def center(name):
        tree=d.dump(name+'-current')
        if not d.ready(tree,'Close transfers'):menu(name+'-menu','Transfers')
        return d.wait(name+'-center',lambda t:d.ready(t,'Close transfers'))
    def recovery():
        tree=d.dump('reopened')
        if d.ready(tree,'A transfer was interrupted'):d.click('review-interruption','Review files')
    def review_scope(tree):
        # UiAutomator starts a fresh accessibility connection on each dump; IDs change.
        scopes=[w for w in tree.iter('window') if w.get('type')=='TYPE_APPLICATION' and
            w.get('active')=='true' and w.get('focused')=='true' and
            any(n.get('package')=='dev.findroid' for n in w.iter('node'))]
        assert len(scopes)==1, 'Expected the focused Findroid recovery dialog'
        return scopes[0]
    def reach_review(name,label,seconds=180):
        deadline=time.monotonic()+seconds;direction=1;previous=None
        while time.monotonic()<deadline:
            tree=d.dump(name);scope=review_scope(tree)
            parents={c:p for p in scope.iter() for c in p};controls=[]
            for node in d.nodes(scope,label):
                while node.get('clickable')!='true' and node in parents:node=parents[node]
                if node.get('clickable')=='true' and node not in controls:controls.append(node)
            assert len(controls)<=1, 'The named recovery action is ambiguous'
            if controls and controls[0].get('enabled')=='true' and ui.full(tree,controls[0]):return tree,controls[0]
            panes=[n for n in scope.iter('node') if n.get('scrollable')=='true']
            if not panes:
                time.sleep(.15);continue
            assert len(panes)==1
            signature=ET.tostring(panes[0])
            if signature==previous:direction=-direction
            previous=signature;l,t,r,b=d.bounds(panes[0]);start,end=(.8,.25) if direction==1 else (.25,.8)
            d.shell('input','swipe',(l+r)//2,round(t+(b-t)*start),(l+r)//2,round(t+(b-t)*end),300)
        raise AssertionError('No complete enabled 48 dp saved-copy control: '+label)
    def review(name):
        center(name);d.click(name+'-open-review','Review saved copy…')
        tree=d.wait(name+'-review-open',lambda t:d.ready(t,'Review saved copy'))
        review_scope(tree)
        tree,_=reach_review(name+'-review-ready','Resume saved copy')
        return tree
    def click_review(name,label):
        tree,node=reach_review(name,label);d.tap(node);return tree
    def journal(name):
        install(debug)
        files={p:read('databases/'+p,True) for p in ('transfer-jobs.db','transfer-jobs.db-wal','transfer-jobs.db-shm')}
        with tempfile.TemporaryDirectory(prefix='findroid-resume-journal-') as temporary:
            directory=Path(temporary)
            for path,data in files.items():
                if data is not None:(directory/path).write_bytes(data)
            db=sqlite3.connect(directory/'transfer-jobs.db');db.row_factory=sqlite3.Row
            jobs=[dict(r) for r in db.execute('SELECT * FROM jobs ORDER BY position')]
            checks=[dict(r) for r in db.execute('SELECT * FROM copy_verifications ORDER BY job_id,position')]
            db.close()
        # This DB contains only newly seeded synthetic transfers. Publish its minimal receipt.
        allowed=('id','state','bytes','items','in_flight','verify_copies','reused_bytes','partial_copy_notice')
        receipt=dict(jobs=[{key:r[key] for key in allowed} | {'partialCopy':json.loads(r['partial_copy']) if r['partial_copy'] else None} for r in jobs],verifications=checks)
        (a.output/(name+'-journal.json')).write_text(json.dumps(receipt,indent=2)+'\n')
        return jobs,checks
    def optimized(location):install(a.apk);assert installed_hash()==report['apkSha256'];launch(location);recovery()
    def capture(name):d.dump(name);d.capture(name)
    def zero_hash():
        digest=hashlib.sha256();block=bytes(1024*1024)
        for _ in range(SIZE//len(block)):digest.update(block)
        return digest.hexdigest()
    def remote_hash(path):
        value=d.shell('run-as','dev.findroid','sha256sum',path).decode().split()[0]
        assert re.fullmatch('[a-f0-9]{64}',value);return value
    def seed():
        nonlocal fixture,source,target,created
        base=d.shell('run-as','dev.findroid','pwd').decode().strip();assert base.endswith('/dev.findroid')
        fixture=base+'/files/Workspace/Resume-'+stamp;source=public+'/'+original_name;target=fixture+'/Out/'+original_name
        assert d.shell('test','!','-e',public,check=False).returncode==0
        d.shell('mkdir','-p',public);created=True
        d.shell('dd','if=/dev/zero','of='+source,'bs=1048576','count='+str(SIZE//1048576))
        d.call('shell','-T','cat > '+shlex.quote(public+'/Original.txt'),data=payload)
        d.shell('run-as','dev.findroid','mkdir','-p',fixture+'/Out')
        write('files/Workspace/Resume-'+stamp+'/Out/Keep.txt',payload)
        for name in helpers.FILES:
            d.shell('run-as','dev.findroid','rm','-f',*['files/'+name+suffix for suffix in ('','.bak','.new')])
        temporary=[name for name in saved_members if name.startswith('databases/transfer-jobs.db') or name.startswith('files/transfer-jobs.properties') or name=='files/active-transfer.txt']
        if temporary:d.shell('run-as','dev.findroid','rm','-f','--',*temporary)
        tree=ET.fromstring(saved_members.get('shared_prefs/finder.xml',b'<map/>'))
        for item in list(tree):
            if item.get('name','').startswith('tag:'):tree.remove(item)
        tab=dict(id='partial-copy-upgrade',history=[public],index=0,view='LIST',query='',rules={})
        for kind,name,value in [('string','tabs',json.dumps([tab])),('int','activeTab',0),('boolean','dualPane',False),('string','activePane','primary'),('boolean','panesReversed',False),('boolean','dark',False),('boolean','sidebarVisible',True),('boolean','preview',False),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),('string','density','COMFORTABLE'),('string','view','LIST'),('string','conflictPolicy','ASK'),('string','smartFolders','[]'),('string','partialCopySentinel',stamp)]:pref(tree,kind,name,value)
        sidebar_setup=dict(version=1,favorites=[dict(id='resume-source',path=public,label='Resume source'),dict(id='resume-destination',path=fixture+'/Out',label='Resume destination')],groups=[],builtins=['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],hidden=['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'])
        pref(tree,'string','sidebarLayoutV1',json.dumps(sidebar_setup));pref(tree,'set','favorites',[public,fixture+'/Out'])
        write('shared_prefs/finder.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True))
        write('shared_prefs/locations.xml',b'<?xml version="1.0" encoding="utf-8"?><map><string name="roots">[]</string></map>')
    def layouts():
        rows=[]
        for width,height in ((1280,800),(800,1280),(600,800)):
            for scale in (1.0,1.5,2.0):
                for dark in (False,True):
                    # Apply only the owned preference, while stopped, retaining the pending stage.
                    install(debug)
                    prefs=ET.fromstring(read('shared_prefs/finder.xml'));pref(prefs,'boolean','dark',dark)
                    write('shared_prefs/finder.xml',ET.tostring(prefs,encoding='utf-8',xml_declaration=True))
                    d.shell('wm','size',f'{round(width*density)}x{round(height*density)}');d.screen=(round(width*density),round(height*density))
                    d.shell('settings','put','system','font_scale',str(scale));optimized(fixture+'/Out')
                    name=f'review-{width}x{height}-font{scale}-'+('dark' if dark else 'light')
                    review(name)
                    for label in ('Resume saved copy','Discard saved bytes…','Check again','Open destination folder','Close'):
                        tree,node=reach_review(name+'-'+label.split()[0],label)
                        assert ui.full(tree,node);capture(name+'-'+label.split()[0])
                    click_review(name+'-close','Close')
                    rows.append(dict(widthDp=width,heightDp=height,fontScale=scale,dark=dark,allNamedControlsReachable=True))
        d.shell('wm','size',original_size);d.screen=original_screen
        d.shell('settings','put','system','font_scale',original_scale)
        report['layouts']=rows

    try:
        install(debug);saved=snapshot();saved_members=members(saved);before_grants=grants()
        (private/'original-state.tar').write_bytes(saved)
        (private/'original-environment.json').write_text(json.dumps(dict(serial=a.serial,originalGrants=before_grants,originalAccess=original_access,originalFontScale=original_scale,originalDisplaySize=original_size),indent=2)+'\n')
        report['originalEnvironmentPersistedBeforeMutation']=True
        seed();d.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE','allow')
        install(a.previous_apk,True);assert version()==45;launch(public)
        d.wait('previous-public-sidebar',lambda t:d.ready(t,'Options for Resume source'));capture('previous-public-sidebar')
        install(a.apk);assert version()==46 and installed_hash()==report['apkSha256'];launch(public)
        d.wait('upgraded-sidebar',lambda t:d.ready(t,'Options for Resume source'));capture('upgraded-sidebar')
        report['directPreviousPublicUpgrade']=True
        menu('copy-source','Copy','Actions for '+original_name);sidebar('destination','Resume destination')
        d.wait('destination-ready',lambda t:d.ready(t,'Actions for Keep.txt'))
        menu('review-copy','Paste and verify…')
        tree,node=ui.reach('copy-confirm-ready','Review verified copy','Copy and verify','Copy and verify')
        d.tap(node);time.sleep(INTERRUPT_DELAY_SECONDS);stop()
        jobs,_=journal('actual-process-death');assert len(jobs)==1
        interrupted=jobs[0];record=json.loads(interrupted['partial_copy'])
        offset=int(record['offset']);assert 0<offset<SIZE and not record['publishing'], 'Force-stop must interrupt a durably checkpointed copy'
        assert record['source']==source and record['target']==target
        stage=read(record['staging']);assert len(stage)>=offset and len(stage)<=SIZE
        assert sha(stage[:offset])==record['prefixSha256']
        report.update(actualOptimizedProcessDeath=True,durableBytesBeforeDeath=offset,uncheckedTailBytes=len(stage)-offset,privateStageOutsideWorkspace='/files/.partial-copies/' in record['staging'],originalSourceBytesVerified=zero_hash()==d.shell('sha256sum',source).decode().split()[0])
        optimized(fixture+'/Out');capture('interrupted-after-reopen')
        # No replay: wait through inspection, then prove destination absence in the stopped app.
        review('cancelled-review');click_review('cancelled-review-close','Close')
        jobs,_=journal('after-cancelled-review');assert len(jobs)==1 and jobs[0]['partial_copy'] is not None
        assert read(target,True) is None and read(record['staging'])==stage
        report['restartAndCancelledReviewStartNoCopy']=True
        optimized(fixture+'/Out');layouts()
        # Layout checks can never resume work or create additional transfers.
        jobs,_=journal('after-layouts');assert len(jobs)==1 and read(record['staging'])==stage
        optimized(fixture+'/Out');review('resume-ready');capture('resume-ready')
        click_review('resume-confirm','Resume saved copy')
        center('resumed')
        d.wait('resumed-completed',lambda t:any(n.get('text','').startswith('Copy verification · 1 verified and copied') for n in t.iter('node')),seconds=240)
        capture('resumed-completed')
        jobs,checks=journal('completed-resume');assert len(jobs)==2
        resumed=jobs[-1];assert resumed['state']=='COMPLETED' and resumed['partial_copy'] is None
        assert jobs[0]['id']==interrupted['id'] and jobs[0]['partial_copy'] is None and jobs[0]['in_flight']==source
        assert int(resumed['reused_bytes'])==offset and int(resumed['bytes'])==SIZE-offset
        completed_checks=[row for row in checks if row['job_id']==resumed['id']]
        assert len(completed_checks)==1 and completed_checks[0]['status']=='VERIFIED' and completed_checks[0]['destination']==target
        expected=zero_hash();assert remote_hash(target)==expected
        assert completed_checks[0]['expected']==completed_checks[0]['actual']==expected
        assert read(record['staging'],True) is None
        assert d.shell('sha256sum',source).decode().split()[0]==expected
        assert read(fixture+'/Out/Keep.txt')==payload
        prefs=ET.fromstring(read('shared_prefs/finder.xml'));assert prefs.find("./string[@name='partialCopySentinel']").text==stamp
        assert {n.text for n in prefs.findall("./set[@name='favorites']/string")}=={public,fixture+'/Out'}
        report.update(manualResumePublishesExactBytes=True,reusedBytesSeparateFromNewWrites=True,readbackVerificationPreserved=True,oldHistoryRetained=True,ownedPrivateStageRemovedAfterPublication=True,unrelatedDestinationKept=True,legacyFavoritePreserved=True,sentinelPreferencePreserved=True)
        assert grants()==before_grants;report['unrelatedUriGrantsUnchanged']=True
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
                if saved is not None:restore();report.update(restoredOriginalManagedFiles=True,restoredOriginalFileAbsences=True,restoredManagedFileCount=len(saved_members))
            if fixture is not None:
                assert fixture.endswith('/Resume-'+stamp)
                d.shell('run-as','dev.findroid','rm','-rf','--',fixture)
        cleanup('restore original private state',originals)
        if created:cleanup('remove owned synthetic public folder',lambda:d.shell('rm','-rf','--',public))
        report['syntheticPublicFixtureRemoved']=d.shell('test','!','-e',public,check=False).returncode==0
        cleanup('restore display',lambda:d.shell('wm','size',original_size));d.screen=original_screen
        cleanup('restore font size',lambda:d.shell('settings','put','system','font_scale',original_scale))
        def access():
            d.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE',original_access)
            mode=re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)',d.shell('appops','get','dev.findroid','MANAGE_EXTERNAL_STORAGE').decode())
            assert (mode.group(1) if mode else 'default')==original_access;report['restoredOriginalStorageAccess']=True
        cleanup('restore storage access',access)
        cleanup('restore exact optimized APK',lambda:install(a.apk));report['restoredApkSha256']=installed_hash()
        cleanup('remove owned input helper',d.close)
        report['cleanupErrors']=errors
        (a.output/'inspection-retries.json').write_text(json.dumps(d.retries,indent=2)+'\n')
        (a.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
        if errors:raise RuntimeError('Cleanup needs review; inspect verification.json')
    assert report['restoredApkSha256']==report['apkSha256'] and report['syntheticPublicFixtureRemoved']
    report['success']=True;(a.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))


if __name__=='__main__':main()
