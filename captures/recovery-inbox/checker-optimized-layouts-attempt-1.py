#!/usr/bin/env python3
"""Exact optimized recovery acceptance with explicitly owned synthetic files and original restoration.

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
    private=a.output/'PRIVATE-recovery';private.mkdir(mode=0o700)
    d=sync.helpers.InspectionDevice(a.serial,private,all_windows=True)
    candidate=json.loads((ROOT/'captures/recovery-inbox/candidate-build-1.json').read_text())
    for name,row in candidate['files'].items():assert sha((ROOT/name).read_bytes())==row['sha256'],name
    for name,digest in candidate['sourceFiles'].items():assert sha((ROOT/name).read_bytes())==digest,name
    debug=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    pair=('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    public=MAIN/'captures/release/0.46.0/Findroid-0.46.0-preview.apk'
    assert a.previous_apk.resolve()==public.resolve()
    expected=candidate['files']['app/build/outputs/apk/preview/app-preview.apk']['sha256']
    assert sha(a.apk.read_bytes())==expected
    density_text=d.shell('wm','density').decode();match=re.search(r'Override density: (\d+)',density_text)
    original_density=match.group(1) if match else 'reset';density=int(re.findall(r'\d+',density_text)[-1])/160
    original_screen=d.screen;match=re.search(r'Override size: (\d+x\d+)',d.shell('wm','size').decode())
    original_size=match.group(1) if match else 'reset';original_scale=d.shell('settings','get','system','font_scale').decode().strip()
    ui=sync.NativeControls(d,density)
    stamp=str(time.time_ns());fixture_rel='files/Workspace/Recovery-acceptance-'+stamp;fixture='/data/user/0/dev.findroid/'+fixture_rel
    marker='files/active-transfer.txt';archive='files/.recovery-notices';original_bytes=b'synthetic original retained during recovery acceptance\n'
    report=dict(serial=a.serial,section=a.section,sourceCommit=candidate['sourceCommit'],checkerSha256=sha(Path(__file__).read_bytes()),
        checkerDependencies={name:sha((ROOT/name).read_bytes()) for name in ('scripts/check-reviewed-folder-sync.py','scripts/check-local-search-index.py','scripts/android/acceptance.py')},
        apkSha256=expected,previousApkSha256=sha(public.read_bytes()),fromVersionCode=48,toVersionCode=49,
        operationsUseExactOptimizedApk=True,acceptanceComplete=False,restorationComplete=False,checks=[],layouts=[],captures=[],densityChecks=[],screenChecks=[])
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
    def seed(name,*,dark=False,blocked=False,notice=True):
        install(debug);restore_metadata()
        base=fixture+'/'+name;relative=fixture_rel+'/'+name
        write(relative+'/Destination/Original.txt',original_bytes)
        # The originals exist in the immutable host snapshot before any synthetic replacement.
        current=entries(snapshot())
        old=[n for n in current if n.startswith('databases/transfer-jobs.db') or n.startswith('files/transfer-jobs.properties') or n.startswith('files/.undo-history/')]
        for path in sorted(old,key=lambda n:(-n.count('/'),n)):
            d.shell('run-as','dev.findroid',*(['rmdir','--',path] if current[path] is None else ['rm','-f','--',path]))
        assert not any(n==archive or n.startswith(archive+'/') for n in current),'Assigned original notice storage must remain absent'
        tree=ET.fromstring(entries(saved).get('shared_prefs/finder.xml') or b'<map/>')
        for child in list(tree):
            if child.get('name','').startswith('tag:') or child.get('name')=='acknowledgedRecoveryMarker':tree.remove(child)
        tab=dict(id='recovery-synthetic',history=[base+'/Destination'],index=0,view='LIST',query='',rules={})
        values=[('string','tabs',json.dumps([tab])),('int','activeTab',0),('boolean','dualPane',False),('string','activePane','primary'),('boolean','panesReversed',False),('boolean','dark',dark),('boolean','sidebarVisible',True),('boolean','preview',False),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),('string','density','COMFORTABLE'),('string','view','LIST'),('string','smartFolders','[]'),('string','recoveryAcceptanceSentinel',stamp)]
        sidebar=dict(version=1,favorites=[dict(id='recovery-destination',path=base+'/Destination',label='Recovery destination')],groups=[],builtins=['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],hidden=['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'])
        values += [('string','sidebarLayoutV1',json.dumps(sidebar)),('set','favorites',[base+'/Destination'])]
        for kind,key,value in values:pref(tree,kind,key,value)
        write('shared_prefs/finder.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True))
        write('shared_prefs/locations.xml',b'<map><string name="roots">[]</string></map>')
        payload=('Copy synthetic previous project\n'+base+'/Destination\n123').encode()
        if notice:write(marker,payload)
        if blocked:write(archive,b'synthetic blocked recovery archive\n')
        return base,relative,payload
    def active_ready(tree,label):
        try:return d.ready(ui.scope(tree),label)
        except AssertionError:return False
    def browser(name):return d.wait(name,lambda t:active_ready(t,'Actions'),seconds=90)
    def launch(name,*,notice=False):
        assert installed()==expected,'All actions require the exact optimized candidate'
        stop();d.shell('am','start','-W','-f','0x10008000','--windowingMode',1,'-n','dev.findroid/.MainActivity')
        return d.wait(name,lambda t:active_ready(t,'A previous operation needs review' if notice else 'Actions'),seconds=90)
    def optimized(name,*,notice=False):install(a.apk);return launch(name,notice=notice)
    def menu(name,label):ui.click(name+'-actions','Actions');ui.click(name+'-command',label)
    def inbox(name):
        menu(name,'Recovery inbox…');ui.text(name+'-title','Recovery inbox')
        ui.click(name+'-transfers-filter',lambda text,desc:text.startswith('Transfers needing review ('))
    def close_inbox(name):ui.click(name,'Close');browser(name+'-browser')
    def capture(name):
        assert not (a.output/(name+'-surface.png')).exists()
        d.capture(name);raw=private/(name+'.png');xml=private/(name+'-capture.xml');tree=ET.parse(xml).getroot();node=ui.scope(tree);parent=None
        while len(list(node))==1:parent,node=node,list(node)[0]
        assert parent is not None and parent.get('class')=='android.view.View' and node.get('class')=='android.view.View'
        assert any(n.get('text') in ('Close','Later','Recovery inbox','A previous operation needs review') for n in node.iter('node'))
        bounds=d.bounds(parent);content=d.bounds(node);image=Image.open(raw).convert('RGB');l,t,r,b=bounds
        assert 0<l<r<image.width and 0<t<b<image.height,'Surface must exclude the browser backdrop'
        assert l<=content[0]<content[2]<=r and t<=content[1]<content[3]<=b
        output=a.output/(name+'-surface.png');image.crop(tuple(bounds)).save(output)
        row=dict(path=output.name,sha256=sha(output.read_bytes()),surfaceBoundsPx=bounds,browserBackdropExcluded=True,privateRawCaptureSha256=sha(raw.read_bytes()),privateRawHierarchySha256=sha(xml.read_bytes()))
        report['captures'].append(row)
        return tree,image
    def inspect(name):
        install(debug);data=snapshot();(private/(name+'-managed.tar')).write_bytes(data)
        return entries(data)
    def notice_files(state):return {name:value for name,value in state.items() if name==marker or name==archive or name.startswith(archive+'/')}
    def checked(name,**facts):
        report['checks'].append(dict(name=name,**facts));(a.output/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(a.serial+': '+name,flush=True)
    def new_folder(name,value,expected_success=True):
        menu(name,'New folder');tree=d.wait(name+'-dialog',lambda t:active_ready(t,'Create'))
        fields=[n for n in ui.scope(tree).iter('node') if n.get('class')=='android.widget.EditText' and n.get('enabled')=='true']
        assert len(fields)==1;d.tap(fields[0]);d.shell('input','keyevent','123');d.shell('input','keyevent',*(['67']*len('Untitled folder')));d.shell('input','text',value)
        ui.click(name+'-create','Create')
        if expected_success:d.wait(name+'-created',lambda t:active_ready(t,'Actions for '+value),seconds=60)
        else:d.wait(name+'-safely-stopped',lambda t:any('The recovery notice folder changed.' in n.get('text','') for n in ui.scope(t).iter('node')),seconds=60)
        browser(name+'-settled')
    try:
        assert installed()==sha(public.read_bytes()),'Begin only from the exact restored public 0.46'
        install(debug);stop();saved=snapshot();(private/'PRIVATE-original-managed.tar').write_bytes(saved)
        baseline=json.loads((PROOF/'private-native-state'/a.serial/'PRIVATE-MANIFEST.json').read_text())['files']
        assert {n:sha(v) for n,v in entries(saved).items() if v is not None}==baseline,'Original managed state changed; preserve current data and stop'
        workspace=d.shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(private/'PRIVATE-workspace-before.tar').write_bytes(workspace)
        verified=True;report['immutableOriginalBytesVerifiedBeforeTests']=True;report['originalManagedFileCount']=len(baseline)
        assert sync.originals.grants(a.serial)==json.loads((PROOF/'private-native-state'/a.serial/'PRIVATE-ENVIRONMENT.json').read_text())['originalGrants']
        stop()
        if a.section in ('core','all'):
            base,relative,payload=seed('Core')
            install(public,True);install(a.apk);launch('direct-48-to49',notice=True)
            ui.text('legacy-notice','Copy synthetic previous project');capture('legacy-startup')
            ui.click('acknowledge-legacy','Later');browser('after-acknowledgment')
            state=inspect('acknowledged');notice_id=sha(payload);assert state[marker]==payload and state[archive+'/'+notice_id+'.txt']==payload
            preferences=ET.fromstring(state['shared_prefs/finder.xml']);value=lambda key:next((n.text for n in preferences if n.get('name')==key),None)
            assert value('acknowledgedRecoveryMarker')==notice_id and value('recoveryAcceptanceSentinel')==stamp
            assert read(relative+'/Destination/Original.txt')==original_bytes
            checked('direct-public48-to49-and-exact-legacy-acknowledgment',previousMarkerKept=True,immutableArchiveBytesMatch=True,preferenceSentinelKept=True,originalFileBytesKept=True)
            recorded=notice_files(state)
            optimized('cold-acknowledged-launch');inbox('cold-inbox');ui.text('old-operation-visible','Copy synthetic previous project')
            ui.click('expand-recorded-locations','Show recorded locations');ui.reach('review-destination','Review destination');ui.reach('review-transfers','Review transfers');capture('cold-inbox-record')
            close_inbox('close-cold-inbox');state=inspect('read-only-cold-review');assert notice_files(state)==recorded
            checked('cold-start-keeps-acknowledged-notice-without-repeating-startup',actualForceStop=True,noAutomaticResume=True,noticeAndArchiveBytesUnchanged=True)
            optimized('before-new-operation');new_folder('create-after-notice','SyntheticNewFolder')
            state=inspect('after-new-operation');assert marker not in state and state[archive+'/'+notice_id+'.txt']==payload
            assert d.shell('run-as','dev.findroid','test','-d',relative+'/Destination/SyntheticNewFolder',check=False).returncode==0
            assert read(relative+'/Destination/Original.txt')==original_bytes
            optimized('after-new-operation-cold');inbox('after-new-operation-inbox');ui.text('old-notice-still-visible','Copy synthetic previous project');capture('after-successful-operation');close_inbox('close-after-new-operation')
            checked('later-success-keeps-the-earlier-notice',newFolderCreated=True,onlyNewSuccessfulMarkerRemoved=True,olderNoticeStillDiscoverable=True,originalFileBytesKept=True)
            base,relative,payload=seed('Blocked',blocked=True)
            optimized('blocked-startup',notice=True);ui.click('blocked-acknowledgment','Later')
            ui.text('blocked-preservation-explained',lambda text,desc:'notice was kept for review' in text)
            ui.click('review-without-acknowledgment','Review without acknowledging');ui.text('blocked-inbox-title','Recovery inbox')
            ui.text('independent-source-error',lambda text,desc:'Preserved operation notices could not be listed safely.' in text)
            capture('preservation-failure-review');close_inbox('close-blocked-review');new_folder('blocked-new-operation','MustNotAppear',expected_success=False)
            state=inspect('blocked-operation-stopped');assert state[marker]==payload and state[archive]==b'synthetic blocked recovery archive\n'
            assert d.shell('run-as','dev.findroid','test','!', '-e',relative+'/Destination/MustNotAppear',check=False).returncode==0
            assert read(relative+'/Destination/Original.txt')==original_bytes
            checked('notice-preservation-failure-stops-before-document-work',priorMarkerAndDamagedArchiveKept=True,noNewFolderPublished=True,originalFileBytesKept=True)
        if a.section in ('layouts','all'):
            glyphs={}
            for width,height in ((1280,800),(800,1280),(600,800)):
                for font in (1.0,2.0):
                    for dark in (False,True):
                        theme='dark' if dark else 'light';name=f'layout-{width}x{height}-font{font}-{theme}'
                        base,relative,payload=seed(name,dark=dark)
                        apply_screen(width,height,round(density*160))
                        d.shell('settings','put','system','font_scale',str(font))
                        assert float(d.shell('settings','get','system','font_scale').decode())==font
                        optimized(name+'-startup',notice=True)
                        ui.reach(name+'-review-files','Review files');ui.reach(name+'-open-inbox','Open recovery inbox');ui.reach(name+'-later','Later');capture(name+'-startup')
                        ui.click(name+'-acknowledge','Open recovery inbox');ui.text(name+'-inbox-title','Recovery inbox')
                        ui.click(name+'-transfer-filter',lambda text,desc:text.startswith('Transfers needing review ('))
                        ui.reach(name+'-destination','Review destination');ui.reach(name+'-transfers','Review transfers');ui.click(name+'-locations','Show recorded locations')
                        ui.reach(name+'-refresh','Refresh records');ui.reach(name+'-storage','Review storage access');ui.reach(name+'-close','Close')
                        tree,image=capture(name+'-inbox');nodes=[n for n in ui.scope(tree).iter('node') if n.get('class')=='android.widget.TextView' and n.get('text')=='Close' and n.get('clickable')=='false'];assert len(nodes)==1
                        bounds=d.bounds(nodes[0]);crop=image.crop(tuple(bounds));glyph=a.output/(name+'-close-glyph.png');crop.save(glyph)
                        from PIL import ImageChops
                        background=tuple(sorted(crop.getpixel(p)[i] for p in ((0,0),(crop.width-1,0),(0,crop.height-1),(crop.width-1,crop.height-1)))[2] for i in range(3))
                        channels=ImageChops.difference(crop,Image.new('RGB',crop.size,background)).split();mask=ImageChops.lighter(ImageChops.lighter(channels[0],channels[1]),channels[2]).point(lambda v:255 if v>24 else 0);pixels=mask.getbbox();assert pixels
                        metrics=dict(systemFontScale=font,textBoundsPx=bounds,textWidth=bounds[2]-bounds[0],textHeight=bounds[3]-bounds[1],rasterGlyphWidth=pixels[2]-pixels[0],rasterGlyphHeight=pixels[3]-pixels[1],image=glyph.name,sha256=sha(glyph.read_bytes()))
                        glyphs.setdefault((width,height,theme),{})[font]=metrics
                        report['layouts'].append(dict(widthDp=width,heightDp=height,fontScale=font,dark=dark,allNamedControlsHaveComplete48DpBounds=True,systemFont=metrics))
            report['systemFontGroups']=[]
            for (width,height,theme),samples in glyphs.items():
                for key in ('textWidth','textHeight','rasterGlyphWidth','rasterGlyphHeight'):assert samples[2.0][key]>samples[1.0][key],(width,height,theme,key)
                report['systemFontGroups'].append(dict(widthDp=width,heightDp=height,theme=theme,actualTextAndGlyphGrowth=True,samples=[samples[1.0],samples[2.0]]))
            for dpi in (240,300):
                name='density-'+str(dpi);base,relative,payload=seed(name);d.shell('wm','density',dpi)
                density_readback=d.shell('wm','density').decode();actual_dpi=int(re.findall(r'(?:Physical|Override) density: (\d+)',density_readback)[-1]);assert actual_dpi==dpi
                ui.density=actual_dpi/160;apply_screen(800,1280,actual_dpi)
                d.shell('settings','put','system','font_scale',2.0);assert float(d.shell('settings','get','system','font_scale').decode())==2.0
                report['densityChecks'].append(dict(requestedDpi=dpi,actualDpi=actual_dpi,systemFontScale=2.0))
                optimized(name+'-startup',notice=True);ui.reach(name+'-later','Later');ui.click(name+'-open-inbox','Open recovery inbox');ui.text(name+'-title','Recovery inbox')
                ui.click(name+'-transfer-filter',lambda text,desc:text.startswith('Transfers needing review ('))
                for label in ('Review destination','Review transfers','Refresh records','Review storage access','Close'):ui.reach(name+'-'+label,label)
                capture(name+'-inbox')
            checked('system-font-tablet-layouts',twelveSizeFontThemeLayouts=True,twoActualDpiChanges=True,sixGroupsHaveRealTextAndRasterGrowth=True)
        report['acceptanceComplete']=True
    finally:
        try:
            stop()
            for relative_apk in pair:d.call('install','-r','-d',MAIN/relative_apk)
            if verified:
                if d.shell('run-as','dev.findroid','test','-d',fixture_rel,check=False).returncode==0:
                    assert fixture_rel.startswith('files/Workspace/Recovery-acceptance-') and stamp in fixture_rel
                    preserved=d.shell('run-as','dev.findroid','tar','-cf','-',fixture_rel);(private/'PRIVATE-synthetic-fixture-final.tar').write_bytes(preserved)
                    d.shell('run-as','dev.findroid','rm','-rf','--',fixture_rel)
                after=d.shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(private/'PRIVATE-workspace-after.tar').write_bytes(after)
                report['originalWorkspaceTreeKept']=entries(after)==entries(workspace)
                d.shell('wm','density',original_density);d.shell('wm','size',original_size);d.shell('settings','put','system','font_scale',original_scale)
                report['restoration']=sync.originals.restore_one(a.serial,public);report['restorationComplete']=True
                assert installed()==sha(public.read_bytes()) and report['originalWorkspaceTreeKept']
            else:d.call('install','-r','-d',public)
        finally:
            report['controlProofs']=ui.proofs;report['hierarchyRetries']=d.retries
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
            api='api30' if a.serial=='emulator-5602' else 'api36';report=json.loads((ROOT/'captures/recovery-inbox'/('candidate-1-'+api+'-native')/'native.json').read_text())
            assert report['success'] and report['passed']==532 and report['failed']==report['skipped']==0
        run(a)
if __name__=='__main__':main()
