#!/usr/bin/env python3
"""Exercise the exact optimized OCR APK with native controls and synthetic documents.

Uses only the reserved Findroid tablets. Original private bytes, Workspace, grants,
and display settings are preserved and checked through the existing restoration helper.
Raw window captures remain private; publication selects only the OCR dialog surface.
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
import zlib
from PIL import Image, ImageChops, ImageDraw, ImageFont
from android.acceptance import ROOT

MAIN = Path('/Users/LENOVO/.t3/projects/droidfinder')
ORIGINAL = MAIN / 'captures/persistent-undo'
PAIR = ('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load('ocr_inspection_and_restoration', ROOT / 'scripts/check-reviewed-folder-sync.py')
sha = lambda raw: hashlib.sha256(raw).hexdigest()


def fixture_documents():
    image = Image.new('RGB', (1000, 400), 'white')
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf', 64)
    draw.text((70, 60), 'Ocean light', font=font, fill='black')
    draw.text((70, 170), 'Far shore', font=font, fill='black')
    png = io.BytesIO(); image.save(png, format='PNG')
    pixels = zlib.compress(image.tobytes())
    content = b'q 1000 0 0 400 0 0 cm /Scan Do Q'
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Count 1 /Kids [3 0 R] >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 1000 400] /Resources << /XObject << /Scan 4 0 R >> >> /Contents 5 0 R >>',
        b'<< /Type /XObject /Subtype /Image /Width 1000 /Height 400 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length '+str(len(pixels)).encode()+b' >>\nstream\n'+pixels+b'\nendstream',
        b'<< /Length '+str(len(content)).encode()+b' >>\nstream\n'+content+b'\nendstream']
    pdf = bytearray(b'%PDF-1.4\n'); offsets = [0]
    for number, value in enumerate(objects, 1):
        offsets.append(len(pdf)); pdf.extend(f'{number} 0 obj\n'.encode()+value+b'\nendobj\n')
    start = len(pdf); pdf.extend(b'xref\n0 6\n0000000000 65535 f \n')
    for offset in offsets[1:]: pdf.extend(f'{offset:010d} 00000 n \n'.encode())
    pdf.extend(f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode())
    return {'Scan.png': png.getvalue(), 'Scanned page.pdf': bytes(pdf)}


def run(a):
    assert not a.output.exists(), 'Keep every earlier attempt'
    build = json.loads(a.build_receipt.read_text())
    for name, digest in build['sourceFiles'].items(): assert sha((ROOT/name).read_bytes()) == digest, name
    expected = sha(a.apk.read_bytes())
    assert expected == build['files']['app/build/outputs/apk/preview/app-preview.apk']['sha256']
    public = MAIN / 'captures/release/0.49.0/Findroid-0.49.0-preview.apk'
    assert sha(public.read_bytes()) == 'c3e201f9129dfeef3d9e755c13a52eee684de7521834a0655b400a59cc5facf3'
    original_build = json.loads((ORIGINAL/'candidate-build-6.json').read_text())
    for name in PAIR: assert sha((MAIN/name).read_bytes()) == original_build['files'][name]['sha256']
    a.output.mkdir(parents=True)
    private = a.output/'PRIVATE-raw'; private.mkdir(mode=0o700)
    d = sync.helpers.InspectionDevice(a.serial, private, all_windows=True)
    density_raw = d.shell('wm','density').decode(); original_density = re.search(r'Override density: (\d+)', density_raw)
    dpi = int(re.findall(r'\d+',density_raw)[-1]); ui = sync.NativeControls(d,dpi/160)
    size = re.search(r'Override size: (\d+x\d+)',d.shell('wm','size').decode())
    scale = d.shell('settings','get','system','font_scale').decode().strip()
    stamp = str(time.time_ns()); relative = 'files/Workspace/OCR-acceptance-'+stamp
    folder = '/data/user/0/dev.findroid/'+relative
    fixtures = fixture_documents()
    saved = None; workspace = None; verified = False
    report = dict(serial=a.serial, section=a.section, sourceCommit=build['sourceCommit'],
        checkerSha256=sha(Path(__file__).read_bytes()), buildReceiptSha256=sha(a.build_receipt.read_bytes()),
        apkSha256=expected, previousApkSha256=sha(public.read_bytes()), checks=[], layouts=[], captures=[], clipboardProofs=[],
        acceptanceComplete=False, restorationComplete=False, operationsUseExactOptimizedApk=True, textVisibilityProofs=[])
    reused=set()
    if a.resume_layouts_from:
        import shutil
        prior_path=a.resume_layouts_from.resolve();assert prior_path.is_relative_to(ROOT/'captures/ocr')
        prior=json.loads(prior_path.read_text())
        assert a.section=='layouts' and prior['section']=='layouts' and not prior['acceptanceComplete']
        assert prior['restorationComplete'] and prior['originalWorkspaceTreeKept'] and 0<len(prior['layouts'])<12
        assert prior['operationsUseExactOptimizedApk'] and prior['immutableOriginalBytesVerifiedBeforeTests']
        for key in ('serial','sourceCommit','buildReceiptSha256','apkSha256','previousApkSha256'):
            assert prior[key]==report[key],key
        for row in prior['layouts']:
            assert row['recognizedPassageActuallyVisible'] and row['systemFont']['twoCompleteGlyphLines']
            assert row['actualClipboardTextChecked'] and row['allNamedControlsHaveComplete48DpBounds'] and row['sourceBytesKept']
            reused.add(f"layout-{row['widthDp']}x{row['heightDp']}-font{row['fontScale']}-"+('dark' if row['dark'] else 'light'))
        chosen=lambda name:any(name==prefix or name.startswith(prefix+'-') for prefix in reused)
        for key in ('layouts','checks'):report[key]=[row for row in prior[key] if key=='layouts' or chosen(row['name'])]
        for key in ('clipboardProofs','controlProofs','textVisibilityProofs'):
            report[key]=[row for row in prior[key] if chosen(row['name'])]
        copied=[]
        for row in prior['captures']:
            if chosen(row['path']):
                origin=prior_path.parent/row['path'];assert origin.name==row['path'] and sha(origin.read_bytes())==row['sha256']
                shutil.copy2(origin,a.output/origin.name);assert sha((a.output/origin.name).read_bytes())==row['sha256']
                report['captures'].append(row);copied.append(dict(path=origin.name,sha256=row['sha256']))
        report['explicitLayoutResume']=dict(previousResult=str(prior_path.relative_to(ROOT)),previousResultSha256=sha(prior_path.read_bytes()),previousCheckerSha256=prior['checkerSha256'],reusedLayouts=sorted(reused),copiedCaptures=copied,unfinishedLayoutRepeatedInFull=True,applicationApkUnchanged=True)

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
    def seed(name, dark=False, model=False):
        install(ROOT/PAIR[0]); restore_metadata()
        d.shell('run-as','dev.findroid','mkdir','-p',relative+'/'+name)
        for filename, raw in fixtures.items():write(relative+'/'+name+'/'+filename,raw)
        tree=ET.fromstring(sync.entries(saved).get('shared_prefs/finder.xml') or b'<map/>')
        tab=dict(id='ocr-synthetic',history=[folder+'/'+name],index=0,view='LIST',query='',rules={})
        for kind,key,value in [('string','tabs',json.dumps([tab])),('int','activeTab',0),('boolean','dualPane',False),
            ('string','activePane','primary'),('boolean','dark',dark),('boolean','sidebarVisible',False),
            ('boolean','preview',False),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),
            ('string','density','COMFORTABLE'),('string','view','LIST')]:preference(tree,kind,key,value)
        write('shared_prefs/finder.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True))
        write('shared_prefs/locations.xml',b'<map><string name="roots">[]</string></map>')
        if model:
            d.shell('run-as','dev.findroid','mkdir','-p','no_backup/ocr/tessdata')
            write('no_backup/ocr/tessdata/eng.traineddata',(ROOT/'app/src/androidTest/assets/ocr/eng.traineddata').read_bytes())
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
        # This control can be above the current scroll position after reading the result.
        if label!='Language and recognition options…':wait(name+'-ready',label)
        ui.click(name,label)
    def launch(name):
        assert installed()==expected
        stop();d.shell('am','start','-W','-f','0x10008000','--windowingMode',1,'-n','dev.findroid/.MainActivity')
        wait(name+'-browser','Actions')
    def open_ocr(name, filename='Scan.png'):
        action='Actions for '+filename
        for index in range(8):
            tree=d.dump(name+'-file-actions');scope=ui.scope(tree);node=ui.node(scope,action,True)
            if node is not None and ui.full(scope,node,True):d.tap(node);break
            panes=[n for n in scope.iter('node') if n.get('class')=='android.widget.HorizontalScrollView' and any(c.get('text')==filename or c.get('content-desc')==action for c in n.iter('node'))]
            assert len(panes)==1
            l,t,r,b=d.bounds(panes[0]);d.shell('input','swipe',round(l+(r-l)*.82),(t+b)//2,round(l+(r-l)*.22),(t+b)//2,300)
        else:raise AssertionError('File actions unavailable')
        click(name+'-peek','Quick Look')
        label='Recognize page text…' if filename.endswith('.pdf') else 'Recognize image text…'
        click(name+'-ocr',label);wait(name+'-dialog','Close OCR')
    def capture(name):
        d.capture(name);tree=ET.parse(private/(name+'-capture.xml')).getroot();scope=ui.scope(tree)
        parents={child:parent for parent in scope.iter() for child in parent};node=d.one(scope,'Recognize text')
        while not d.nodes(node,'Close OCR'):
            assert node in parents;node=parents[node]
        bounds=d.bounds(node);image=Image.open(private/(name+'.png')).convert('RGB')
        l,t,r,b=bounds;assert 0<l<r<image.width and 0<t<b<image.height
        target=a.output/(name+'-dialog.png');image.crop(tuple(bounds)).save(target)
        report['captures'].append(dict(path=target.name,sha256=sha(target.read_bytes()),surfaceBoundsPx=bounds,browserBackdropExcluded=True))
        return tree,image
    def reveal_result(name, font_scale):
        previous=None
        minimum=round(2*16*ui.density*font_scale)
        for attempt in range(12):
            tree=d.dump(name+'-text-visibility');scope=ui.scope(tree)
            node=ui.node(scope,lambda text,desc:'Ocean light' in text and 'Far shore' in text,False)
            bounds=d.bounds(node) if node is not None else None
            complete=bounds is not None and bounds[3]-bounds[1]>=minimum and ui.full(scope,node,False)
            if complete and bounds==previous:
                return minimum
            previous=bounds
            panes=[n for n in scope.iter('node') if n.get('scrollable')=='true' and len(d.bounds(n))==4]
            if not panes:
                assert complete,'Recognized passage must have room for both lines'
                return minimum
            pane=max(panes,key=lambda n:(d.bounds(n)[2]-d.bounds(n)[0])*(d.bounds(n)[3]-d.bounds(n)[1]))
            l,t,r,b=d.bounds(pane)
            d.shell('input','swipe',(l+r)//2,round(t+(b-t)*.82),(l+r)//2,round(t+(b-t)*.28),300)
        raise AssertionError('Could not reveal the full two-line recognized passage')
    def glyph_proof(name,tree,image,font_scale,minimum):
        scope=ui.scope(tree);node=ui.node(scope,lambda text,desc:'Ocean light' in text and 'Far shore' in text,False)
        assert node is not None
        bounds=d.bounds(node);l,t,r,b=bounds;assert b-t>=minimum and ui.full(scope,node,False)
        crop=image.crop(tuple(bounds));background=crop.getpixel((crop.width-1,crop.height-1))
        channels=ImageChops.difference(crop,Image.new('RGB',crop.size,background)).split()
        mask=ImageChops.lighter(ImageChops.lighter(channels[0],channels[1]),channels[2]).point(lambda v:255 if v>32 else 0)
        occupied=[y for y in range(mask.height) if mask.crop((0,y,mask.width,y+1)).getbbox()]
        bands=[]
        for y in occupied:
            if not bands or y-bands[-1][1]>max(3,round(3*ui.density)):bands.append([y,y])
            else:bands[-1][1]=y
        assert len(bands)==2 and all(end-start+1>=8*ui.density*font_scale for start,end in bands),('Both actual glyph lines must be readable',bands)
        pixels=mask.getbbox();target=a.output/(name+'-recognized-glyph.png');crop.save(target)
        report['captures'].append(dict(path=target.name,sha256=sha(target.read_bytes()),surfaceBoundsPx=bounds,browserBackdropExcluded=True,recognizedTextOnly=True))
        proof=dict(name=name,systemFontScale=font_scale,textBoundsPx=bounds,textHeight=b-t,textWidth=r-l,rasterGlyphHeight=pixels[3]-pixels[1],rasterGlyphWidth=pixels[2]-pixels[0],glyphBands=bands,twoCompleteGlyphLines=True,minimumReadableTextHeightPx=minimum,image=target.name,sha256=sha(target.read_bytes()))
        report['textVisibilityProofs'].append(proof)
        return proof
    def recognize(name):
        click(name+'-recognize','Recognize this image or page')
        wait(name+'-result','Copy recognized text')
        ui.text(name+'-first-line',lambda text,desc:'Ocean light' in text and 'Far shore' in text)
    def copy(name):
        click(name+'-copy','Copy recognized text');ui.text(name+'-copied','Copied recognized text')
        d.shell('am','start','-W','-n','dev.findroid.test/dev.findroid.PdfClipboardProbeActivity')
        tree=d.wait(name+'-clipboard',lambda tree:any('Ocean light' in n.get('text','') and 'Far shore' in n.get('text','') for n in d.nodes(tree,'PDF copied text')))
        actual=d.one(tree,'PDF copied text').get('text');report['clipboardProofs'].append(dict(name=name,actualSyntheticText=actual,sha256=sha(actual.encode()),readByForegroundTestOnlyActivity=True))
        d.shell('input','keyevent','BACK');wait(name+'-returned','Close OCR')
    def unchanged(case):
        install(ROOT/PAIR[0])
        for name,raw in fixtures.items():assert read(case+'/'+name)==raw
    def checked(name,**facts):
        report['checks'].append(dict(name=name,**facts));(a.output/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(a.serial+': '+name,flush=True)
    def import_from_workspace(case):
        click('import-model','Import downloaded language…')
        tree=d.wait('model-picker',lambda t:any(n.get('package')=='com.google.android.documentsui' for n in t.iter('node')))
        drawers=lambda t:[n for n in t.iter('node') if n.get('resource-id','').endswith('/roots_list') and d.bounds(n)[2]>d.bounds(n)[0]]
        for label in ('Show roots','Show navigation drawer'):
            if d.nodes(tree,label):
                d.tap(d.one(tree,label));tree=d.wait('model-picker-roots',lambda t:bool(drawers(t)));break
        roots=drawers(tree);assert len(roots)==1
        d.tap(d.one(roots[0],'Findroid'))
        for index,label in enumerate((relative.rpartition('/')[2],case.rpartition('/')[2],'English.traineddata')):
            tree=d.wait('model-picker-item-'+str(index),lambda t:bool(d.nodes(t,label)))
            items=[n for n in tree.iter('node') if n.get('resource-id','').endswith('/item_root') and d.nodes(n,label)]
            assert len(items)==1,('synthetic picker item',label,len(items));d.tap(items[0])
        wait('imported-model','Recognize this image or page')
    try:
        assert installed()==sha(public.read_bytes()),'Require the exact restored public 0.49 APK'
        install(ROOT/PAIR[0]);saved=snapshot();(private/'PRIVATE-original-managed.tar').write_bytes(saved)
        baseline=json.loads((ORIGINAL/'private-native-state'/a.serial/'PRIVATE-MANIFEST.json').read_text())['files']
        assert {n:sha(v) for n,v in sync.entries(saved).items() if v is not None}==baseline
        workspace=d.shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(private/'PRIVATE-workspace-before.tar').write_bytes(workspace)
        verified=True;report['immutableOriginalBytesVerifiedBeforeTests']=True
        d.call('install','-r',ROOT/PAIR[1]);assert installed('dev.findroid.test')==build['files'][PAIR[1]]['sha256']
        if a.section in ('core','all'):
            case=seed('Core')
            model_bytes=(ROOT/'app/src/androidTest/assets/ocr/eng.traineddata').read_bytes()
            write(case+'/English.traineddata',model_bytes)
            install(public);install(a.apk);launch('upgrade');open_ocr('missing-language')
            ui.reach('missing-disabled','Recognize this image or page',enabled=False)
            tree=d.dump('no-implicit-work');scope=ui.scope(tree);node=ui.node(scope,'Recognize this image or page',True)
            assert node.get('enabled')=='false';assert not d.shell('pidof','dev.findroid:ocr',check=False).stdout.strip()
            capture('explicit-model-choice')
            click('download',lambda text,desc:text.startswith('Download language ('))
            wait('language-ready','Recognize this image or page')
            recognize('image');copy('image');capture('image-result')
            click('search','Find in recognized text');d.shell('input','text','shore');d.shell('input','keyevent','BACK')
            ui.text('search-hit','1 of 1 matches');click('next-match','Next OCR match');capture('image-search-result')
            click('close-image-ocr','Close OCR');click('close-image-peek','Close Quick Look')
            open_ocr('pdf','Scanned page.pdf');recognize('pdf');copy('pdf');capture('scanned-pdf-result')
            click('model-options','Language and recognition options…');click('review-remove','Remove language…')
            ui.text('remove-review','Remove English language?');click('keep-model','Keep language')
            click('review-remove-again','Remove language…');click('remove-model','Remove language')
            wait('model-removed',lambda text,desc:text.startswith('Download language ('))
            import_from_workspace(case);capture('imported-model-ready')
            click('close-pdf-ocr','Close OCR');click('close-pdf-peek','Close Quick Look')
            launch('cold-reopen');open_ocr('offline-reopen');recognize('offline-reopen');copy('offline-reopen')
            click('close-offline-ocr','Close OCR');click('close-offline-peek','Close Quick Look');unchanged(case)
            assert read(case+'/English.traineddata')==model_bytes
            assert read('no_backup/ocr/tessdata/eng.traineddata')==model_bytes
            checked('public51-to52-local-image-and-scanned-pdf',actualPinnedHttpsDownload=True,realAndroidModelImport=True,reviewedRemovalAndCancellation=True,installedModelSurvivesColdStart=True,actualClipboardRead=True,findMatch=True,sourceBytesKept=True,noImplicitRecognition=True)
        if a.section in ('layouts','all'):
            for width,height in ((1280,800),(800,1280),(600,800)):
                for font_scale in (1.0,2.0):
                    for dark in (False,True):
                        name=f'layout-{width}x{height}-font{font_scale}-'+('dark' if dark else 'light')
                        if name in reused:continue
                        case=seed(name,dark,model=True)
                        requested=(round(width*dpi/160),round(height*dpi/160));d.shell('wm','size',f'{requested[0]}x{requested[1]}');d.screen=requested
                        actual=tuple(map(int,re.findall(r'(?:Physical|Override) size: (\d+)x(\d+)',d.shell('wm','size').decode())[-1]));assert actual==requested
                        d.shell('settings','put','system','font_scale',font_scale);assert float(d.shell('settings','get','system','font_scale').decode())==font_scale
                        filename='Scanned page.pdf' if dark else 'Scan.png'
                        install(a.apk);launch(name);open_ocr(name,filename);recognize(name)
                        for label in ('Copy recognized text','Find in recognized text','Language and recognition options…','Close OCR'):
                            tree,node=ui.reach(name+'-control',label)
                            ui.proofs.append(dict(name=name,label=label,bounds=d.bounds(node),density=ui.density))
                        copy(name);minimum=reveal_result(name,font_scale);tree,image=capture(name+'-result')
                        font_proof=glyph_proof(name,tree,image,font_scale,minimum)
                        click(name+'-options','Language and recognition options…');ui.reach(name+'-remove','Remove language…');capture(name+'-options')
                        click(name+'-close','Close OCR');click(name+'-close-preview','Close Quick Look');unchanged(case)
                        report['layouts'].append(dict(widthDp=width,heightDp=height,fontScale=font_scale,dark=dark,previewDocument=filename,actualSizePx=list(actual),actualClipboardTextChecked=True,allNamedControlsHaveComplete48DpBounds=True,sourceBytesKept=True,recognizedPassageActuallyVisible=True,systemFont=font_proof))
                        checked(name)
            groups={}
            for row in report['layouts']:groups.setdefault((row['widthDp'],row['heightDp'],row['dark']),{})[row['fontScale']]=row['systemFont']
            report['systemFontGroups']=[]
            for (width,height,dark),samples in groups.items():
                for key in ('textHeight','textWidth','rasterGlyphHeight','rasterGlyphWidth'):assert samples[2.0][key]>samples[1.0][key]*1.2
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
    p.add_argument('--resume-layouts-from',type=Path)
    a=p.parse_args()
    with (ORIGINAL/('.'+a.serial+'-acceptance.lock')).open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);run(a)


if __name__=='__main__':main()
