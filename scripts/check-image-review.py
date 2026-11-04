#!/usr/bin/env python3
"""Exercise the exact optimized file comparison APK with native controls and synthetic documents.

Uses only the reserved Findroid tablets. Original private bytes, Workspace, grants,
and display settings are preserved and checked through the existing restoration helper.
Raw window captures remain private; publication selects only the synthetic Quick Look surface.
"""
import argparse
import fcntl
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import tarfile
import time
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops
from android.acceptance import ROOT

MAIN = Path('/Users/LENOVO/.t3/projects/droidfinder')
ORIGINAL = MAIN / 'captures/persistent-undo'
PAIR = ('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load('comparison_inspection_and_restoration', ROOT / 'scripts/check-reviewed-folder-sync.py')
sha = lambda raw: hashlib.sha256(raw).hexdigest()


class ReviewControls(sync.NativeControls):
    def reach(self, name, label, *, clickable=True, enabled=True):
        for direction in (1, -1):
            previous = None
            for _ in range(60):
                tree = self.d.dump(name)
                scope = self.scope(tree)
                node = self.node(scope, label, clickable)
                if node is not None and (not enabled or node.get('enabled') == 'true') and self.full(scope, node, clickable):
                    return tree, node
                panes = [n for n in scope.iter('node') if n.get('scrollable') == 'true'
                         and len(self.d.bounds(n)) == 4 and self.d.bounds(n)[3] > self.d.bounds(n)[1]]
                viewer = label in ('Zoom in','Zoom out','Fit image','Actual pixels','Image information','Rotate view clockwise')
                markers = ('Zoom in','Fit image') if viewer else ('Previous image','Next image','Use as reference')
                control_panes=[p for p in panes if any(n.get('text') in markers or n.get('content-desc') in markers for n in p.iter('node'))]
                if control_panes:panes=control_panes
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


def fixture_documents():
    from PIL import ImageDraw
    docs = {}
    for index,name in enumerate(('01 Morning.png','02 Noon.png','03 Evening.png')):
        im=Image.new('RGB',(900,600),(160+index*30,205-index*12,225-index*25));draw=ImageDraw.Draw(im)
        draw.rectangle((0,280,900,600),fill=(38,115,145));x=220+index*220
        draw.ellipse((x-55,125,x+55,235),fill=(245,218,150))
        draw.polygon(((0,520),(360,310),(540,600),(0,600)),fill=(36,63,71))
        buffer=io.BytesIO();im.save(buffer,format='PNG');docs[name]=buffer.getvalue()
    return docs


def run(a):
    assert not a.output.exists(), 'Keep every earlier attempt'
    build = json.loads(a.build_receipt.read_text())
    for name, digest in build['sourceFiles'].items(): assert sha((ROOT/name).read_bytes()) == digest, name
    expected = sha(a.apk.read_bytes())
    assert expected == build['files']['app/build/outputs/apk/preview/app-preview.apk']['sha256']
    public = MAIN / 'captures/release/0.52.0/Findroid-0.52.0-preview.apk'
    assert sha(public.read_bytes()) == '5a44dfb8d00a862f8fb6e589ee88fb04552221f90daec8cde32e8e51c5a77c2b'
    original_build = build
    for name in PAIR: assert sha((MAIN/name).read_bytes()) == original_build['files'][name]['sha256']
    a.output.mkdir(parents=True)
    private = a.output/'PRIVATE-raw'; private.mkdir(mode=0o700)
    d = sync.helpers.InspectionDevice(a.serial, private, all_windows=True)
    density_raw = d.shell('wm','density').decode(); original_density = re.search(r'Override density: (\d+)', density_raw)
    dpi = int(re.findall(r'\d+',density_raw)[-1]); ui = ReviewControls(d,dpi/160)
    size = re.search(r'Override size: (\d+x\d+)',d.shell('wm','size').decode())
    scale = d.shell('settings','get','system','font_scale').decode().strip()
    stamp = str(time.time_ns()); relative = 'files/Workspace/CompareQA-'+stamp
    folder = '/data/user/0/dev.findroid/'+relative
    fixtures = fixture_documents(); case_fixtures = {}
    saved = None; workspace = None; verified = False
    report = dict(serial=a.serial, section=a.section, sourceCommit=build['sourceCommit'],
        checkerSha256=sha(Path(__file__).read_bytes()), buildReceiptSha256=sha(a.build_receipt.read_bytes()),
        apkSha256=expected, previousApkSha256=sha(public.read_bytes()), checks=[], layouts=[], captures=[], clipboardProofs=[],
        acceptanceComplete=False, restorationComplete=False, operationsUseExactOptimizedApk=True, textVisibilityProofs=[])
    def installed(package='dev.findroid'):
        path=d.shell('pm','path',package).decode().strip().splitlines()[0].removeprefix('package:')
        return sha(d.call('exec-out','cat',path))
    def stop(): d.shell('am','force-stop','dev.findroid')
    def install(path): stop(); d.call('install','-r','-d',path)
    def write(name, raw):
        assert not Path(name).is_absolute() and '..' not in Path(name).parts
        assert len(name.encode())<=100,'Keep owned fixture paths within the platform tar name field'
        buffer=io.BytesIO()
        with tarfile.open(fileobj=buffer,mode='w') as archive:
            info=tarfile.TarInfo(name); info.size=len(raw); info.mode=0o600; archive.addfile(info,io.BytesIO(raw))
        d.call('shell','-T','run-as dev.findroid tar -xf -',data=buffer.getvalue())
    def read(name): return d.call('exec-out','run-as','dev.findroid','cat',name)
    def snapshot():
        return d.shell('run-as','dev.findroid','tar','-cf','-','--exclude=files/Workspace','--exclude=files/ProviderFixtures',
                       'shared_prefs','databases','no_backup','cache','files')
    def restore_metadata():
        stop(); current=sync.entries(snapshot()); before=sync.entries(saved)
        for name in sorted(set(current)-set(before),key=lambda n:(n.count('/'),n),reverse=True):
            if current[name] is not None: d.shell('run-as','dev.findroid','rm','--',name)
            else: d.shell('run-as','dev.findroid','rmdir','--',name)
        d.call('shell','-T','run-as dev.findroid tar -xf -',data=saved)
        assert sync.entries(snapshot())==before
    def preference(tree, kind, key, value):
        for node in list(tree):
            if node.get('name')==key: tree.remove(node)
        node=ET.SubElement(tree,kind,name=key)
        if kind=='string':node.text=value
        else:node.set('value',str(value).lower())
    def seed(name, dark=False):
        install(ROOT/PAIR[0]); restore_metadata()
        d.shell('run-as','dev.findroid','mkdir','-p',relative+'/'+name)
        chosen=fixtures.copy();case_fixtures[relative+'/'+name]=chosen
        for filename, raw in chosen.items():write(relative+'/'+name+'/'+filename,raw)
        tree=ET.Element('map')
        tab=dict(id='comparison-synthetic',history=[folder+'/'+name],index=0,view='LIST',query='',rules={})
        for kind,key,value in [('string','tabs',json.dumps([tab])),('int','activeTab',0),('boolean','dualPane',False),
            ('string','activePane','primary'),('boolean','dark',dark),('boolean','sidebarVisible',False),
            ('boolean','preview',False),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),
            ('string','density','COMFORTABLE'),('string','view','LIST')]:preference(tree,kind,key,value)
        write('shared_prefs/finder.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True))
        write('shared_prefs/locations.xml',b'<map><string name="roots">[]</string></map>')
        write('shared_prefs/app-updates.xml',b'<map><boolean name="automatic" value="false"/></map>')
        tags='catalog=2\n'+''.join('definition\\:'+tag+'='+tag+'\n' for tag in ('Red','Orange','Yellow','Green','Blue','Purple'))
        write('files/tags.properties',tags.encode())
        return relative+'/'+name
    def ready(tree,label):
        try:
            scope=ui.scope(tree)
            if callable(label):
                node=ui.node(scope,label,True)
                return node is not None and node.get('enabled')=='true'
            return d.ready(scope,label)
        except AssertionError:return False
    def wait(name,label):return d.wait(name,lambda tree:ready(tree,label),seconds=50)
    def click(name,label):
        if label=='Actions':wait(name+'-ready',label)
        ui.click(name,label)
    def launch(name):
        assert installed()==expected
        stop();d.shell('am','start','-W','-f','0x10008000','--windowingMode',1,'-n','dev.findroid/.MainActivity')
        wait(name+'-browser','Actions')
    def open_review(name):
        click(name+'-actions','Actions');click(name+'-select-all','Select all')
        click(name+'-selected','Actions');click(name+'-review','Review images…')
        wait(name+'-loaded','Preview of 01 Morning.png')

    def capture(name):
        d.capture(name);tree=ET.parse(private/(name+'-capture.xml')).getroot();scope=ui.scope(tree)
        bounds=d.bounds(scope);image=Image.open(private/(name+'.png')).convert('RGB')
        l,t,r,b=bounds;assert 0<=l<r<=image.width and 0<=t<b<=image.height
        target=a.output/(name+'-preview.png');image.crop(tuple(bounds)).save(target)
        report['captures'].append(dict(path=target.name,sha256=sha(target.read_bytes()),surfaceBoundsPx=bounds,syntheticSetupOnly=True))
        return tree,image
    def glyph(name,tree,image,font_scale):
        scope=ui.scope(tree);node=ui.node(scope,'02 Noon.png',False)
        assert node is not None and ui.full(scope,node,False),'Complete current image name required'
        bounds=d.bounds(node);crop=image.crop(tuple(bounds))
        background=crop.getpixel((crop.width-1,crop.height-1))
        channels=ImageChops.difference(crop,Image.new('RGB',crop.size,background)).split()
        mask=ImageChops.lighter(ImageChops.lighter(channels[0],channels[1]),channels[2]).point(lambda v:255 if v>96 else 0)
        pixels=mask.getbbox();assert pixels is not None and pixels[3]-pixels[1]>=5*ui.density*font_scale
        assert 0<pixels[1]<pixels[3]<crop.height,'Complete glyph ink needs visible top and bottom margins'
        target=a.output/(name+'-glyph.png');crop.save(target)
        report['captures'].append(dict(path=target.name,sha256=sha(target.read_bytes()),surfaceBoundsPx=bounds,syntheticSetupOnly=True,visibleTextOnly=True))
        proof=dict(name=name,systemFontScale=font_scale,textBoundsPx=bounds,rasterGlyphHeight=pixels[3]-pixels[1],rasterGlyphWidth=pixels[2]-pixels[0],image=target.name,sha256=sha(target.read_bytes()),actualReadableGlyphs=True)
        report['textVisibilityProofs'].append(proof);return proof
    def unchanged(case):
        install(ROOT/PAIR[0])
        for name,raw in case_fixtures[case].items():assert read(case+'/'+name)==raw
    def checked(name,**facts):
        report['checks'].append(dict(name=name,**facts));(a.output/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(a.serial+': '+name,flush=True)
    try:
        assert installed()==sha(public.read_bytes()),'Require the exact restored public 0.51 APK'
        install(ROOT/PAIR[0]);saved=snapshot();(private/'PRIVATE-original-managed.tar').write_bytes(saved)
        baseline=json.loads((ORIGINAL/'private-native-state'/a.serial/'PRIVATE-MANIFEST.json').read_text())['files']
        assert {n:sha(v) for n,v in sync.entries(saved).items() if v is not None}==baseline
        workspace=d.shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(private/'PRIVATE-workspace-before.tar').write_bytes(workspace)
        verified=True;report['immutableOriginalBytesVerifiedBeforeTests']=True
        d.call('install','-r',ROOT/PAIR[1]);assert installed('dev.findroid.test')==build['files'][PAIR[1]]['sha256']
        if a.section in ('core','all'):
            name='core-review';case=seed(name)
            install(public);install(a.apk);launch(name);open_review(name)
            click(name+'-reference','Use as reference');click(name+'-flag-first','Flag image')
            click(name+'-next','Next image');click(name+'-last','Next image');click(name+'-flag-last','Flag image')
            click(name+'-filtered','Show flagged only');ui.text(name+'-summary','2 of 2 flagged · 2 flagged')
            capture(name+'-flagged')
            click(name+'-compare','Compare with reference…');wait(name+'-pair','Left image')
            ui.text(name+'-right','Right image');capture(name+'-comparison')
            click(name+'-close-comparison','Close file comparison');wait(name+'-returned','Preview of 03 Evening.png')
            click(name+'-tag','Tag flagged…');wait(name+'-organize','Change Green for selection')
            click(name+'-green','Change Green for selection');capture(name+'-tag-draft')
            click(name+'-cancel-tags','Close organization library');wait(name+'-cancelled','Preview of 03 Evening.png')
            click(name+'-tag-again','Tag flagged…');wait(name+'-organize-again','Change Green for selection')
            click(name+'-green-again','Change Green for selection');click(name+'-apply','Apply tags')
            wait(name+'-applied',lambda text,desc:'Tags updated for 2 items.' in text)
            capture(name+'-tags-applied');click(name+'-close-tags','Close organization library')
            wait(name+'-review-returned','Preview of 03 Evening.png');click(name+'-close','Close image review')
            # Reopen after closing: decisions are deliberately session-only.
            click(name+'-menu-reopen','Actions');click(name+'-reopen','Review images…');wait(name+'-reloaded','Preview of 01 Morning.png')
            ui.text(name+'-fresh','1 of 3 images · 0 flagged');click(name+'-close-new','Close image review')
            unchanged(case)
            data=read('files/tags.properties').decode('iso-8859-1')
            rows=[row for row in data.splitlines() if row.startswith('item')]
            assert len(rows)==2 and all(row.endswith('=Green') for row in rows)
            assert any('01\\ Morning.png=Green' in row for row in rows) and any('03\\ Evening.png=Green' in row for row in rows)
            checked(name,directPublic54To55Upgrade=True,sourceBytesKept=True,exactTwoFlaggedTagAssignments=True,
                    referencePairVisible=True,cancelledTagDraftKeptFlags=True,newReviewStartsWithoutFlags=True)
        if a.section in ('layouts','all'):
            for width,height in ((1280,800),(800,1280),(600,800)):
                for dark in (False,True):
                    for font_scale in (1.0,2.0):
                        name=f'layout-{width}x{height}-font{font_scale}-'+('dark' if dark else 'light')
                        case=seed(name,dark);requested=(round(width*ui.density),round(height*ui.density))
                        d.shell('wm','size',f'{requested[0]}x{requested[1]}');d.screen=requested
                        actual=tuple(map(int,re.findall(r'(?:Physical|Override) size: (\d+)x(\d+)',d.shell('wm','size').decode())[-1]));assert actual==requested
                        d.shell('settings','put','system','font_scale',font_scale);assert float(d.shell('settings','get','system','font_scale').decode())==font_scale
                        install(a.apk);launch(name);open_review(name)
                        click(name+'-reference','Use as reference');click(name+'-flag','Flag image');click(name+'-next','Next image')
                        labels=('Previous image','Next image','Flag image','Show flagged only','Tag flagged…','Clear flags',
                                'Use as reference','Compare with reference…','Clear reference','Close image review','Zoom in','Fit image','Image information')
                        for label in labels:
                            tree,node=ui.reach(name+'-control',label,enabled=False)
                            ui.proofs.append(dict(name=name,label=label,bounds=d.bounds(node),density=ui.density))
                        click(name+'-zoom','Zoom in');click(name+'-fit','Fit image')
                        ui.text(name+'-image','Preview of 02 Noon.png')
                        ui.text(name+'-name','02 Noon.png')
                        # Capture the overview and current-image label together.
                        tree,image=capture(name+'-result');scope=ui.scope(tree)
                        viewport=ui.node(scope,'Preview of 02 Noon.png',False);assert viewport is not None
                        bounds=d.bounds(viewport);assert bounds[3]-bounds[1]>=96*ui.density
                        crop=image.crop(tuple(bounds));assert len(crop.getcolors(crop.width*crop.height))>=4
                        font_proof=glyph(name,tree,image,font_scale)
                        click(name+'-close','Close image review');unchanged(case)
                        report['layouts'].append(dict(widthDp=width,heightDp=height,fontScale=font_scale,dark=dark,actualSizePx=list(actual),
                            activeImageActuallyVisible=True,viewportBoundsPx=bounds,allNamedControlsHaveComplete48DpBounds=True,sourceBytesKept=True,systemFont=font_proof))
                        checked(name)
            groups={}
            for row in report['layouts']:groups.setdefault((row['widthDp'],row['heightDp'],row['dark']),{})[row['fontScale']]=row['systemFont']
            report['systemFontGroups']=[]
            for (width,height,dark),samples in groups.items():
                for key in ('rasterGlyphHeight','rasterGlyphWidth'):assert samples[2.0][key]>samples[1.0][key]*1.2,(width,height,dark,key,samples)
                report['systemFontGroups'].append(dict(widthDp=width,heightDp=height,dark=dark,actualTextAndGlyphGrowth=True))
        report['acceptanceComplete']=True
    finally:
        try:
            stop()
            for name in PAIR:d.call('install','-r','-d',MAIN/name)
            if verified:
                if d.shell('run-as','dev.findroid','test','-d',relative,check=False).returncode==0:
                    (private/'PRIVATE-synthetic-fixtures-final.tar').write_bytes(d.shell('run-as','dev.findroid','tar','-cf','-',relative))
                    d.shell('run-as','dev.findroid','rm','-rf','--',relative)
                after=d.shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(private/'PRIVATE-workspace-after.tar').write_bytes(after)
                report['originalWorkspaceTreeKept']=sync.entries(after)==sync.entries(workspace)
                d.shell('wm','density',original_density.group(1) if original_density else 'reset')
                d.shell('wm','size',size.group(1) if size else 'reset');d.shell('settings','put','system','font_scale',scale)
                report['restoration']=sync.originals.restore_one(a.serial,public);report['restorationComplete']=True
                assert installed()==sha(public.read_bytes()) and report['originalWorkspaceTreeKept']
                assert installed('dev.findroid.test')==original_build['files'][PAIR[1]]['sha256']
            else:d.call('install','-r','-d',public)
        finally:
            report['controlProofs']=report.get('controlProofs',[])+ui.proofs;report['hierarchyRetries']=d.retries
            (a.output/'result.json').write_text(json.dumps(report,indent=2)+'\n');d.close()
    print(json.dumps({k:report[k] for k in ('serial','acceptanceComplete','restorationComplete')}),flush=True)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True,choices=('emulator-5602','emulator-5582'))
    p.add_argument('--apk',required=True,type=Path);p.add_argument('--build-receipt',required=True,type=Path)
    p.add_argument('--output',required=True,type=Path);p.add_argument('--section',choices=('core','layouts','all'),default='all')
    a=p.parse_args()
    with (ORIGINAL/('.'+a.serial+'-acceptance.lock')).open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);run(a)


if __name__=='__main__':main()
