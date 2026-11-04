#!/usr/bin/env python3
"""Exact optimized transfer-space acceptance with explicitly owned synthetic files and original restoration.

No uninstall/clear, grant changes, automatic recovery or private publication. The two assigned
serials share the native suite's reservation. Raw screenshots/hierarchies stay private; selected
images must pass Surface cropping and a separate actual visual inspection.
"""
import argparse
import fcntl
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import shlex
import sqlite3
import tarfile
import time
import xml.etree.ElementTree as ET
from PIL import Image
from android.acceptance import ROOT

MAIN=Path('/Users/LENOVO/.t3/projects/droidfinder')
PROOF=MAIN/'captures/persistent-undo'
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
sync=load('recovery_verified_device_controls',ROOT/'scripts/check-reviewed-folder-sync.py')
sha=lambda data:hashlib.sha256(data).hexdigest()
entries=sync.entries
MANAGED=sync.MANAGED
EXCLUDED=sync.EXCLUDED


def run(a):
    assert not a.output.exists(),'Preserve every earlier attempt'
    a.output.mkdir(parents=True)
    private=a.output/'PRIVATE-space';private.mkdir(mode=0o700)
    d=sync.helpers.InspectionDevice(a.serial,private,all_windows=True)
    candidate=json.loads((ROOT/'captures/transfer-space-plans/candidate-build-2.json').read_text())
    for name,row in candidate['files'].items():assert sha((ROOT/name).read_bytes())==row['sha256'],name
    for name,digest in candidate['sourceFiles'].items():assert sha((ROOT/name).read_bytes())==digest,name
    debug=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    pair=('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    public=MAIN/'captures/release/0.47.0/Findroid-0.47.0-preview.apk'
    assert a.previous_apk.resolve()==public.resolve()
    expected=candidate['files']['app/build/outputs/apk/preview/app-preview.apk']['sha256']
    assert sha(a.apk.read_bytes())==expected
    density_text=d.shell('wm','density').decode();match=re.search(r'Override density: (\d+)',density_text)
    original_density=match.group(1) if match else 'reset';density=int(re.findall(r'\d+',density_text)[-1])/160
    original_screen=d.screen;match=re.search(r'Override size: (\d+x\d+)',d.shell('wm','size').decode())
    original_size=match.group(1) if match else 'reset';original_scale=d.shell('settings','get','system','font_scale').decode().strip()
    ui=sync.NativeControls(d,density)
    stamp=str(time.time_ns());fixture_rel='files/Workspace/Space-acceptance-'+stamp;fixture='/data/user/0/dev.findroid/'+fixture_rel
    marker='files/active-transfer.txt';archive='files/.recovery-notices';original_bytes=b'synthetic original retained during transfer-space acceptance\n'
    report=dict(serial=a.serial,section=a.section,sourceCommit=candidate['sourceCommit'],checkerSha256=sha(Path(__file__).read_bytes()),
        checkerDependencies={name:sha((ROOT/name).read_bytes()) for name in ('scripts/check-reviewed-folder-sync.py','scripts/check-local-search-index.py','scripts/android/acceptance.py')},
        apkSha256=expected,previousApkSha256=sha(public.read_bytes()),fromVersionCode=49,toVersionCode=50,
        operationsUseExactOptimizedApk=True,acceptanceComplete=False,restorationComplete=False,checks=[],layouts=[],captures=[],densityChecks=[],screenChecks=[],controlProofs=[])
    saved=None;workspace=None;verified=False
    def apply_screen(width,height,dpi):
        requested=(round(width*dpi/160),round(height*dpi/160))
        d.shell('wm','size',f'{requested[0]}x{requested[1]}')
        text=d.shell('wm','size').decode()
        matches=re.findall(r'(?:Physical|Override) size: (\d+)x(\d+)',text)
        assert matches,text
        actual=tuple(map(int,matches[-1]));assert actual==requested,(requested,actual)
        d.screen=actual
        report['screenChecks'].append(dict(requestedWidthDp=width,requestedHeightDp=height,densityDpi=dpi,actualWidthPx=actual[0],actualHeightPx=actual[1]))
    def stop():d.shell('am','force-stop','dev.findroid')
    def installed():
        path=d.shell('pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        return sha(d.call('exec-out','cat',path))
    def install(path,downgrade=False):
        stop();d.call('install','-r',*(['-d'] if downgrade else []),path);stop()
    def read(path,optional=False):
        script='if test -f '+shlex.quote(path)+'; then cat '+shlex.quote(path)+'; elif test -e '+shlex.quote(path)+'; then exit 42; else exit 44; fi'
        result=d.call('shell','-T',shlex.join(['run-as','dev.findroid','sh','-c',script]),check=False)
        if optional and result.returncode==44:return None
        assert result.returncode==0,'Could not inspect owned synthetic file'
        return result.stdout
    def write(path,data):
        d.shell('run-as','dev.findroid','mkdir','-p',str(Path(path).parent))
        d.call('shell','-T',shlex.join(['run-as','dev.findroid','sh','-c','cat > '+shlex.quote(path)]),data=data)
    def snapshot():return d.shell('run-as','dev.findroid','tar','-cf','-','--exclude=files/Workspace','--exclude=files/ProviderFixtures',*MANAGED)
    def restore_metadata():
        assert saved is not None
        stop();before=entries(saved);current=entries(snapshot())
        extras=[n for n,v in current.items() if v is not None and n not in before]
        for first in range(0,len(extras),100):
            names=extras[first:first+100]
            assert all(Path(n).parts[0] in MANAGED and not any(n==x or n.startswith(x+'/') for x in EXCLUDED) for n in names)
            d.shell('run-as','dev.findroid','rm','-f','--',*names)
        d.shell('run-as','dev.findroid','tar','-xf','-',data=saved)
        current=entries(snapshot())
        for name in sorted(set(current)-set(before),key=lambda n:(-n.count('/'),n)):
            assert current[name] is None;d.shell('run-as','dev.findroid','rmdir','--',name)
        assert entries(snapshot())==before,'Original managed bytes and absences differ'
    def pref(tree,kind,name,value):
        for child in list(tree):
            if child.get('name')==name:tree.remove(child)
        node=ET.SubElement(tree,kind,name=name)
        if kind=='string':node.text=value
        elif kind=='set':
            for item in value:ET.SubElement(node,'string').text=item
        else:node.set('value',str(value).lower())
    source_bytes=b'Synthetic selected source, retained until an explicit transfer.\n'
    destination_bytes=b'Synthetic destination original, preserved during space review.\n'
    sparse_bytes=1 << 40
    def seed(name,dark=False):
        install(debug);restore_metadata();stop()
        relative=fixture_rel+'/'+name;base=fixture+'/'+name
        for folder in ('Source','Destination','Other'):d.shell('run-as','dev.findroid','mkdir','-p',relative+'/'+folder)
        write(relative+'/Source/Report.txt',source_bytes)
        write(relative+'/Destination/Report.txt',destination_bytes)
        current=entries(snapshot())
        owned_history=[n for n in current if n.startswith('databases/transfer-jobs.db') or n.startswith('files/transfer-jobs.properties') or n.startswith('files/.undo-history/') or n==marker]
        for path in sorted(owned_history,key=lambda n:(-n.count('/'),n)):
            d.shell('run-as','dev.findroid',*(['rmdir','--',path] if current[path] is None else ['rm','-f','--',path]))
        assert not any(n==archive or n.startswith(archive+'/') for n in current),'Keep original notice archives outside synthetic acceptance'
        tree=ET.fromstring(entries(saved).get('shared_prefs/finder.xml') or b'<map/>')
        for child in list(tree):
            if child.get('name','').startswith('tag:') or child.get('name')=='acknowledgedRecoveryMarker':tree.remove(child)
        tab=dict(id='space-synthetic',history=[base+'/Source'],index=0,view='LIST',query='',rules={})
        values=[('string','tabs',json.dumps([tab])),('int','activeTab',0),('boolean','dualPane',False),('string','activePane','primary'),('boolean','panesReversed',False),('boolean','dark',dark),('boolean','sidebarVisible',True),('boolean','preview',False),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),('string','density','COMFORTABLE'),('string','view','LIST'),('string','smartFolders','[]'),('string','conflictPolicy','ASK'),('string','spaceAcceptanceSentinel',stamp)]
        sidebar=dict(version=1,favorites=[],groups=[],builtins=['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],hidden=['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'])
        values += [('string','sidebarLayoutV1',json.dumps(sidebar)),('set','favorites',[])]
        for kind,key,value in values:pref(tree,kind,key,value)
        write('shared_prefs/finder.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True))
        write('shared_prefs/locations.xml',b'<map><string name="roots">[]</string></map>')
        return base,relative
    def active_ready(tree,label):
        try:return d.ready(ui.scope(tree),label)
        except AssertionError:return False
    def browser(name):return d.wait(name,lambda tree:active_ready(tree,'Actions'),seconds=90)
    def launch(name):
        assert installed()==expected,'Every user operation requires the exact optimized candidate'
        stop();d.shell('am','start','-W','-f','0x10008000','--windowingMode',1,'-n','dev.findroid/.MainActivity')
        return browser(name)
    def optimized(name):install(a.apk);return launch(name)
    def menu(name,label):ui.click(name+'-actions','Actions');ui.click(name+'-command',label)
    def open_space(name,move=False,destination='Destination'):
        ui.click(name+'-source-actions','Actions for Report.txt')
        ui.click(name+'-transfer-command','Move to…' if move else 'Copy to…')
        ui.click(name+'-parent','Parent destination folder')
        ui.click(name+'-destination-row',destination)
        ui.click(name+'-review-space','Review space and alternatives…')
        ui.text(name+'-space-ready','Working copies and recovery')
    def ready_controls(name,move=False):
        for label in ('Refresh space estimate','Choose another destination…','Close','Move to destination' if move else 'Copy to destination'):
            ui.reach(name+'-'+label,label)
    def close_review(name):ui.click(name,'Close');browser(name+'-browser')
    def capture(name):
        output=a.output/(name+'-surface.png');assert not output.exists()
        d.capture(name);raw=private/(name+'.png');xml=private/(name+'-capture.xml');tree=ET.parse(xml).getroot();node=ui.scope(tree);parent=None
        while len(list(node))==1:parent,node=node,list(node)[0]
        assert parent is not None and parent.get('class')=='android.view.View' and node.get('class') in ('android.view.View','android.widget.ScrollView')
        assert any(n.get('text') in ('Close','Review this destination','Review transfer space') for n in node.iter('node'))
        bounds=d.bounds(parent);content=d.bounds(node);image=Image.open(raw).convert('RGB');l,t,r,b=bounds
        assert 0<l<r<image.width and 0<t<b<image.height,'Surface crop must exclude the browser backdrop'
        assert l<=content[0]<content[2]<=r and t<=content[1]<content[3]<=b
        image.crop(tuple(bounds)).save(output)
        report['captures'].append(dict(path=output.name,sha256=sha(output.read_bytes()),surfaceBoundsPx=bounds,browserBackdropExcluded=True,privateRawCaptureSha256=sha(raw.read_bytes()),privateRawHierarchySha256=sha(xml.read_bytes())))
        return tree,image
    def inspect_jobs(name):
        install(debug);stop();directory=private/('sqlite-'+name);directory.mkdir()
        for suffix in ('','-wal','-shm'):
            data=read('databases/transfer-jobs.db'+suffix,optional=True)
            if data is not None:(directory/('transfer-jobs.db'+suffix)).write_bytes(data)
        if not (directory/'transfer-jobs.db').exists():return []
        connection=sqlite3.connect('file:'+str(directory/'transfer-jobs.db')+'?mode=ro',uri=True)
        try:return connection.execute('SELECT state,destination,move FROM jobs ORDER BY position').fetchall()
        finally:connection.close()
    def no_document_work(name,relative):
        assert inspect_jobs(name)==[],'Reviewing space must not submit a transfer'
        assert read(relative+'/Source/Report.txt')==source_bytes and read(relative+'/Destination/Report.txt')==destination_bytes
        assert d.shell('run-as','dev.findroid','find',relative,'-name','.findroid-*').decode().strip()==''
    def checked(name,**facts):
        report['checks'].append(dict(name=name,**facts));(a.output/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(a.serial+': '+name,flush=True)
    def finish_transfer(name):
        browser(name+'-browser');menu(name,'Transfers')
        ui.text(name+'-completed',lambda text,desc:text.startswith('Completed ·'))
        ui.click(name+'-close-transfers','Close transfers');browser(name+'-settled')
    def alternate(name):
        ui.click(name+'-choose-alternative','Choose another destination…')
        ui.click(name+'-parent-alternative','Parent destination folder')
        ui.reach(name+'-other-row','Other')
        ui.reach(name+'-review-this-destination','Review this destination')
        capture(name+'-destination-picker')
        ui.click(name+'-other-destination','Other')
        ui.click(name+'-review-other','Review this destination')
        ui.text(name+'-new-plan-ready','Working copies and recovery')
    try:
        assert installed()==sha(public.read_bytes()),'Begin only from the exact restored public 0.47 APK'
        install(debug);stop();saved=snapshot();(private/'PRIVATE-original-managed.tar').write_bytes(saved)
        baseline=json.loads((PROOF/'private-native-state'/a.serial/'PRIVATE-MANIFEST.json').read_text())['files']
        assert {n:sha(value) for n,value in entries(saved).items() if value is not None}==baseline,'Original state changed; preserve current bytes and stop'
        workspace=d.shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(private/'PRIVATE-workspace-before.tar').write_bytes(workspace)
        verified=True;report['immutableOriginalBytesVerifiedBeforeTests']=True;report['originalManagedFileCount']=len(baseline)
        assert sync.originals.grants(a.serial)==json.loads((PROOF/'private-native-state'/a.serial/'PRIVATE-ENVIRONMENT.json').read_text())['originalGrants']
        if a.section in ('core','all'):
            base,relative=seed('Core');install(public,True);install(a.apk);launch('direct-public49-to50')
            open_space('initial-review');ui.text('replacement-allowance',lambda text,desc:text.startswith('Possible replacement recovery copies:'))
            ui.text('same-volume-budget',lambda text,desc:text.startswith('Combined demand on the same observed storage volume:'))
            ready_controls('initial-controls');capture('read-only-replacement-budget');close_review('close-initial-review')
            no_document_work('initial-read-only',relative)
            preferences=ET.fromstring(read('shared_prefs/finder.xml'));assert next(n.text for n in preferences if n.get('name')=='spaceAcceptanceSentinel')==stamp
            optimized('cold-read-only');no_document_work('cold-read-only',relative)
            checked('direct-public49-to50-read-only-review',originalFileBytesKept=True,preferenceSentinelKept=True,noTransferSubmitted=True,coldStartStartsNoWork=True,replacementAllowanceShown=True,sameVolumeDemandCombined=True)
            optimized('before-copy');open_space('copy-review');alternate('copy-alternative');ready_controls('alternative-controls')
            ui.click('explicit-copy','Copy to destination');finish_transfer('copy-finished')
            assert inspect_jobs('copy-complete')==[('COMPLETED',base+'/Other',0)]
            assert read(relative+'/Source/Report.txt')==source_bytes and read(relative+'/Destination/Report.txt')==destination_bytes and read(relative+'/Other/Report.txt')==source_bytes
            checked('alternative-destination-and-explicit-copy',chosenAlternativeUsed=True,sourceBytesKept=True,priorDestinationOriginalKept=True,oneCompletedCopy=True)
            base,relative=seed('Sparse');d.shell('run-as','dev.findroid','truncate','-s',sparse_bytes,relative+'/Source/Report.txt')
            logical,blocks=map(int,d.shell('run-as','dev.findroid','stat','-c','%s %b',relative+'/Source/Report.txt').decode().split())
            assert logical==sparse_bytes and blocks*512<1024*1024,'Sparse fixture must not allocate a large payload'
            optimized('sparse-launch');open_space('sparse-review');ui.text('known-shortfall',lambda text,desc:text.startswith('Shortfall for this allowance:'))
            for label in ('Refresh space estimate','Choose another destination…','Close'):ui.reach('shortfall-'+label,label)
            tree=d.dump('disabled-start');scope=ui.scope(tree);parents={child:parent for parent in scope.iter() for child in parent};button=ui.node(scope,'Copy to destination',False)
            while button is not None and not (button.get('enabled')=='false' and d.bounds(button)[3]-d.bounds(button)[1]>=48*ui.density-1):button=parents.get(button)
            assert button is not None and ui.full(scope,button,True)
            report['controlProofs'].append(dict(name='disabled-shortfall-start',enabled=False,bounds=d.bounds(button),density=ui.density))
            capture('known-shortfall');close_review('close-shortfall');assert inspect_jobs('shortfall-no-work')==[]
            assert read(relative+'/Destination/Report.txt')==destination_bytes
            checked('known-shortfall-disables-start',logicalSourceBytes=logical,allocatedFixtureBytes=blocks*512,disabledComplete48DpStart=True,noTransferSubmitted=True,destinationOriginalKept=True)
            base,relative=seed('Move');optimized('move-launch');open_space('move-review',move=True,destination='Other');ready_controls('move-controls',move=True);capture('move-before-start');close_review('close-move')
            no_document_work('closed-move',relative)
            optimized('move-confirmation');open_space('move-confirmation-review',move=True,destination='Other');ui.click('explicit-move','Move to destination');finish_transfer('move-finished')
            assert inspect_jobs('move-complete')==[('COMPLETED',base+'/Other',1)]
            assert read(relative+'/Source/Report.txt',optional=True) is None and read(relative+'/Other/Report.txt')==source_bytes and read(relative+'/Destination/Report.txt')==destination_bytes
            checked('move-review-close-and-explicit-start',closingStartedNoWork=True,oneCompletedMove=True,selectedSourceMovedOnlyAfterConfirmation=True,unrelatedDestinationOriginalKept=True)
        if a.section in ('layouts','all'):
            glyphs={}
            for width,height in ((1280,800),(800,1280),(600,800)):
                for font in (1.0,2.0):
                    for dark in (False,True):
                        theme='dark' if dark else 'light';name=f'layout-{width}x{height}-font{font}-{theme}'
                        base,relative=seed(name,dark=dark);apply_screen(width,height,round(density*160))
                        d.shell('settings','put','system','font_scale',str(font));assert float(d.shell('settings','get','system','font_scale').decode())==font
                        optimized(name+'-launch');open_space(name);ready_controls(name+'-controls')
                        tree,image=capture(name+'-review');nodes=[n for n in ui.scope(tree).iter('node') if n.get('class')=='android.widget.TextView' and n.get('text')=='Close' and n.get('clickable')=='false'];assert len(nodes)==1
                        bounds=d.bounds(nodes[0]);crop=image.crop(tuple(bounds));glyph=a.output/(name+'-close-glyph.png');crop.save(glyph)
                        from PIL import ImageChops
                        background=tuple(sorted(crop.getpixel(point)[index] for point in ((0,0),(crop.width-1,0),(0,crop.height-1),(crop.width-1,crop.height-1)))[2] for index in range(3))
                        channels=ImageChops.difference(crop,Image.new('RGB',crop.size,background)).split();mask=ImageChops.lighter(ImageChops.lighter(channels[0],channels[1]),channels[2]).point(lambda value:255 if value>24 else 0);pixels=mask.getbbox();assert pixels
                        metrics=dict(systemFontScale=font,textBoundsPx=bounds,textWidth=bounds[2]-bounds[0],textHeight=bounds[3]-bounds[1],rasterGlyphWidth=pixels[2]-pixels[0],rasterGlyphHeight=pixels[3]-pixels[1],image=glyph.name,sha256=sha(glyph.read_bytes()))
                        glyphs.setdefault((width,height,theme),{})[font]=metrics
                        alternate(name);ready_controls(name+'-alternative-controls');close_review(name+'-close');no_document_work(name+'-read-only',relative)
                        report['layouts'].append(dict(widthDp=width,heightDp=height,fontScale=font,dark=dark,allNamedControlsHaveComplete48DpBounds=True,alternativePickerRowsHave48DpBounds=True,openingAndChangingDestinationStartNoWork=True,systemFont=metrics))
            report['systemFontGroups']=[]
            for (width,height,theme),samples in glyphs.items():
                for key in ('textWidth','textHeight','rasterGlyphWidth','rasterGlyphHeight'):assert samples[2.0][key]>samples[1.0][key],(width,height,theme,key)
                report['systemFontGroups'].append(dict(widthDp=width,heightDp=height,theme=theme,actualTextAndGlyphGrowth=True,samples=[samples[1.0],samples[2.0]]))
            for dpi in (240,300):
                name='density-'+str(dpi);base,relative=seed(name);d.shell('wm','density',dpi)
                readback=d.shell('wm','density').decode();actual=int(re.findall(r'(?:Physical|Override) density: (\d+)',readback)[-1]);assert actual==dpi
                ui.density=actual/160;apply_screen(800,1280,actual);d.shell('settings','put','system','font_scale',2.0);assert float(d.shell('settings','get','system','font_scale').decode())==2.0
                report['densityChecks'].append(dict(requestedDpi=dpi,actualDpi=actual,systemFontScale=2.0));optimized(name+'-launch');open_space(name);ready_controls(name+'-controls');capture(name+'-review');close_review(name+'-close');no_document_work(name+'-read-only',relative)
            checked('system-font-tablet-layouts',twelveSizeFontThemeLayouts=True,twoActualDpiChanges=True,sixGroupsHaveRealTextAndRasterGrowth=True,alternativeDestinationRowsVerified=True)
        report['acceptanceComplete']=True
    finally:
        try:
            stop()
            for relative_apk in pair:d.call('install','-r','-d',MAIN/relative_apk)
            if verified:
                if d.shell('run-as','dev.findroid','test','-d',fixture_rel,check=False).returncode==0:
                    assert fixture_rel.startswith('files/Workspace/Space-acceptance-') and stamp in fixture_rel
                    sparse=fixture_rel+'/Sparse/Source/Report.txt'
                    if d.shell('run-as','dev.findroid','test','-f',sparse,check=False).returncode==0:
                        size,blocks=map(int,d.shell('run-as','dev.findroid','stat','-c','%s %b',sparse).decode().split())
                        report['ownedSparseFixtureRemovedBeforeArchiving']=dict(logicalBytes=size,allocatedBytes=blocks*512,payloadNeverArchived=True)
                        d.shell('run-as','dev.findroid','rm','-f','--',sparse)
                    preserved=d.shell('run-as','dev.findroid','tar','-cf','-',fixture_rel);(private/'PRIVATE-synthetic-fixture-final.tar').write_bytes(preserved)
                    d.shell('run-as','dev.findroid','rm','-rf','--',fixture_rel)
                after=d.shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(private/'PRIVATE-workspace-after.tar').write_bytes(after)
                report['originalWorkspaceTreeKept']=entries(after)==entries(workspace)
                d.shell('wm','density',original_density);d.shell('wm','size',original_size);d.shell('settings','put','system','font_scale',original_scale)
                report['restoration']=sync.originals.restore_one(a.serial,public);report['restorationComplete']=True
                assert installed()==sha(public.read_bytes()) and report['originalWorkspaceTreeKept']
            else:d.call('install','-r','-d',public)
        finally:
            report['controlProofs']+=ui.proofs;report['hierarchyRetries']=d.retries
            (a.output/'result.json').write_text(json.dumps(report,indent=2)+'\n');d.close()
    print(json.dumps(dict(serial=a.serial,section=a.section,checks=len(report['checks']),layouts=len(report['layouts']),acceptanceComplete=report['acceptanceComplete'],restorationComplete=report['restorationComplete'])),flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True,choices=('emulator-5602','emulator-5582'))
    parser.add_argument('--apk',required=True,type=Path);parser.add_argument('--previous-apk',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path);parser.add_argument('--section',choices=('core','layouts','all'),default='all')
    parser.add_argument('--wait-for-native',action='store_true');a=parser.parse_args()
    with (PROOF/('.'+a.serial+'-acceptance.lock')).open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|(0 if a.wait_for_native else fcntl.LOCK_NB))
        if a.wait_for_native:
            api='api30' if a.serial=='emulator-5602' else 'api36';native=json.loads((ROOT/'captures/transfer-space-plans'/('candidate-2-'+api+'-native')/'native.json').read_text())
            assert native['success'] and native['passed']==549 and native['failed']==native['skipped']==0
        run(a)
if __name__=='__main__':main()
