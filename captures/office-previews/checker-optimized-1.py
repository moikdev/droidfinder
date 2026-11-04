#!/usr/bin/env python3
"""Exercise the exact optimized Office APK with native controls and synthetic documents.

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


sync = load('office_inspection_and_restoration', ROOT / 'scripts/check-reviewed-folder-sync.py')
sha = lambda raw: hashlib.sha256(raw).hexdigest()


def fixture_documents():
    return {name:(ROOT/'app/src/androidTest/assets/office'/name).read_bytes() for name in
            ('LibraryNotes.docx','LibraryBudget.xlsx','LibraryJourney.pptx')}


def run(a):
    assert not a.output.exists(), 'Keep every earlier attempt'
    build = json.loads(a.build_receipt.read_text())
    for name, digest in build['sourceFiles'].items(): assert sha((ROOT/name).read_bytes()) == digest, name
    expected = sha(a.apk.read_bytes())
    assert expected == build['files']['app/build/outputs/apk/preview/app-preview.apk']['sha256']
    public = MAIN / 'captures/release/0.50.0/Findroid-0.50.0-preview.apk'
    assert sha(public.read_bytes()) == '14d3419e3032eab962ddecdb4fef354cd66409497a402ddb8851c363740697e8'
    original_build = json.loads((ORIGINAL/'candidate-build-6.json').read_text())
    for name in PAIR: assert sha((MAIN/name).read_bytes()) == original_build['files'][name]['sha256']
    a.output.mkdir(parents=True)
    private = a.output/'PRIVATE-raw'; private.mkdir(mode=0o700)
    d = sync.helpers.InspectionDevice(a.serial, private, all_windows=True)
    density_raw = d.shell('wm','density').decode(); original_density = re.search(r'Override density: (\d+)', density_raw)
    dpi = int(re.findall(r'\d+',density_raw)[-1]); ui = sync.NativeControls(d,dpi/160)
    size = re.search(r'Override size: (\d+x\d+)',d.shell('wm','size').decode())
    scale = d.shell('settings','get','system','font_scale').decode().strip()
    stamp = str(time.time_ns()); relative = 'files/Workspace/Office-acceptance-'+stamp
    folder = '/data/user/0/dev.findroid/'+relative
    fixtures = fixture_documents()
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
        for filename, raw in fixtures.items():write(relative+'/'+name+'/'+filename,raw)
        tree=ET.Element('map')
        tab=dict(id='office-synthetic',history=[folder+'/'+name],index=0,view='LIST',query='',rules={})
        for kind,key,value in [('string','tabs',json.dumps([tab])),('int','activeTab',0),('boolean','dualPane',False),
            ('string','activePane','primary'),('boolean','dark',dark),('boolean','sidebarVisible',False),
            ('boolean','preview',False),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),
            ('string','density','COMFORTABLE'),('string','view','LIST')]:preference(tree,kind,key,value)
        write('shared_prefs/finder.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True))
        write('shared_prefs/locations.xml',b'<map><string name="roots">[]</string></map>')
        write('shared_prefs/app-updates.xml',b'<map><boolean name="automatic" value="false"/></map>')
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
        wait(name+'-ready',label)
        ui.click(name,label)
    def launch(name):
        assert installed()==expected
        stop();d.shell('am','start','-W','-f','0x10008000','--windowingMode',1,'-n','dev.findroid/.MainActivity')
        wait(name+'-browser','Actions')
    def open_office(name, filename):
        action='Actions for '+filename
        for index in range(8):
            tree=d.dump(name+'-file-actions');scope=ui.scope(tree);node=ui.node(scope,action,True)
            if node is not None and ui.full(scope,node,True):d.tap(node);break
            panes=[n for n in scope.iter('node') if n.get('class')=='android.widget.HorizontalScrollView' and any(c.get('text')==filename or c.get('content-desc')==action for c in n.iter('node'))]
            assert len(panes)==1
            l,t,r,b=d.bounds(panes[0]);d.shell('input','swipe',round(l+(r-l)*.82),(t+b)//2,round(l+(r-l)*.22),(t+b)//2,300)
        else:raise AssertionError('File actions unavailable')
        click(name+'-peek','Quick Look')
        wait(name+'-document','Copy loaded section')

    def capture(name):
        d.capture(name);tree=ET.parse(private/(name+'-capture.xml')).getroot();scope=ui.scope(tree)
        bounds=d.bounds(scope);image=Image.open(private/(name+'.png')).convert('RGB')
        l,t,r,b=bounds;assert 0<=l<r<=image.width and 0<=t<b<=image.height
        target=a.output/(name+'-preview.png');image.crop(tuple(bounds)).save(target)
        report['captures'].append(dict(path=target.name,sha256=sha(target.read_bytes()),surfaceBoundsPx=bounds,syntheticSetupOnly=True))
        return tree,image
    def glyph(name,tree,image,font_scale):
        scope=ui.scope(tree);node=ui.node(scope,'Ocean light',False)
        assert node is not None and ui.full(scope,node,False),'Readable Ocean light passage required'
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
    def copy(name,tokens):
        click(name+'-copy','Copy loaded section');ui.text(name+'-copied','Copied loaded section')
        d.shell('am','start','-W','-n','dev.findroid.test/dev.findroid.PdfClipboardProbeActivity')
        tree=d.wait(name+'-clipboard',lambda tree:any(all(token in n.get('text','') for token in tokens) for n in d.nodes(tree,'PDF copied text')))
        actual=d.one(tree,'PDF copied text').get('text');assert all(token in actual for token in tokens)
        report['clipboardProofs'].append(dict(name=name,actualSyntheticText=actual,sha256=sha(actual.encode()),readByForegroundTestOnlyActivity=True))
        d.shell('input','keyevent','BACK');wait(name+'-returned','Copy loaded section')
        return actual
    def find(name,query):
        click(name+'-search','Find in loaded section');d.shell('input','text',query)
        d.shell('input','keyevent','BACK');wait(name+'-keyboard-dismissed','Close Quick Look')
        ui.text(name+'-match','1 of 1 matching rows')
    def unchanged(case):
        install(ROOT/PAIR[0])
        for name,raw in fixtures.items():assert read(case+'/'+name)==raw
    def checked(name,**facts):
        report['checks'].append(dict(name=name,**facts));(a.output/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(a.serial+': '+name,flush=True)
    try:
        assert installed()==sha(public.read_bytes()),'Require the exact restored public 0.50 APK'
        install(ROOT/PAIR[0]);saved=snapshot();(private/'PRIVATE-original-managed.tar').write_bytes(saved)
        baseline=json.loads((ORIGINAL/'private-native-state'/a.serial/'PRIVATE-MANIFEST.json').read_text())['files']
        assert {n:sha(v) for n,v in sync.entries(saved).items() if v is not None}==baseline
        workspace=d.shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(private/'PRIVATE-workspace-before.tar').write_bytes(workspace)
        verified=True;report['immutableOriginalBytesVerifiedBeforeTests']=True
        d.call('install','-r',ROOT/PAIR[1]);assert installed('dev.findroid.test')==build['files'][PAIR[1]]['sha256']
        if a.section in ('core','all'):
            for filename,format_name,tokens in (
                ('LibraryNotes.docx','DOCX',('Ocean light','Far shore','日本語')),
                ('LibraryBudget.xlsx','XLSX',('Ocean light','Far shore','[No cached formula result]','TRUE')),
                ('LibraryJourney.pptx','PPTX',('Far shore','日本語'))):
                name='core-'+format_name;case=seed(name)
                install(public);install(a.apk);launch(name);open_office(name,filename)
                copy(name,tokens)
                if format_name=='DOCX':find(name,'shore')
                capture(name+'-first-section')
                if format_name!='DOCX':
                    click(name+'-next','Next section')
                    copy(name+'-next',('Morning sea',) if format_name=='XLSX' else ('Ocean light',))
                    capture(name+'-second-section')
                    click(name+'-previous','Previous section')
                click(name+'-limits','Preview limits…');ui.text(name+'-limits-title','Office preview limits')
                ui.reach(name+'-close-limits','Close preview limits');capture(name+'-limits')
                click(name+'-dismiss-limits','Close preview limits');click(name+'-close','Close Quick Look')
                unchanged(case)
                checked(name,format=format_name,public52To53DirectUpgrade=True,actualClipboardRead=True,sourceBytesKept=True,sectionNavigation=format_name!='DOCX',findMatch=format_name=='DOCX',limitsReviewed=True)
        if a.section in ('layouts','all'):
            formats={
                (1280,False):'LibraryNotes.docx',(1280,True):'LibraryJourney.pptx',
                (800,False):'LibraryBudget.xlsx',(800,True):'LibraryNotes.docx',
                (600,False):'LibraryJourney.pptx',(600,True):'LibraryBudget.xlsx'}
            for width,height in ((1280,800),(800,1280),(600,800)):
                for font_scale in (1.0,2.0):
                    for dark in (False,True):
                        name=f'layout-{width}x{height}-font{font_scale}-'+('dark' if dark else 'light')
                        case=seed(name,dark);requested=(round(width*dpi/160),round(height*dpi/160))
                        d.shell('wm','size',f'{requested[0]}x{requested[1]}');d.screen=requested
                        actual=tuple(map(int,re.findall(r'(?:Physical|Override) size: (\d+)x(\d+)',d.shell('wm','size').decode())[-1]));assert actual==requested
                        d.shell('settings','put','system','font_scale',font_scale);assert float(d.shell('settings','get','system','font_scale').decode())==font_scale
                        filename=formats[width,dark];install(a.apk);launch(name);open_office(name,filename)
                        if filename.endswith('.pptx'):click(name+'-slide-two','Next section')
                        for label in ('Preview limits…','Previous section','Next section','Copy loaded section','Find in loaded section','Close Quick Look'):
                            tree,node=ui.reach(name+'-control',label,enabled=False)
                            ui.proofs.append(dict(name=name,label=label,bounds=d.bounds(node),density=ui.density))
                        copy(name,('Ocean light',));ui.text(name+'-passage','Ocean light')
                        tree,image=capture(name+'-result');font_proof=glyph(name,tree,image,font_scale)
                        click(name+'-close','Close Quick Look');unchanged(case)
                        report['layouts'].append(dict(widthDp=width,heightDp=height,fontScale=font_scale,dark=dark,previewDocument=filename,actualSizePx=list(actual),actualClipboardTextChecked=True,allNamedControlsHaveComplete48DpBounds=True,sourceBytesKept=True,bodyPassageActuallyVisible=True,systemFont=font_proof))
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
