#!/usr/bin/env python3
"""Exercise an exact optimized PDF-selection candidate using owned synthetic PDFs.

Only the two assigned tablets are accepted, under the shared exclusive reservation.
Original bytes, absences, grants, and settings are verified and restored. Raw captures
stay private; only the bounded Quick Look surface and synthetic text glyphs are selected.
"""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import time
import subprocess
import tempfile
import zipfile
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


sync = load('pdf_native_inspection_and_restoration', ROOT / 'scripts/check-reviewed-folder-sync.py')
sha = lambda data: hashlib.sha256(data).hexdigest()


def synthetic_pdf():
    """Three bounded ASCII pages with known 600 x 800 point geometry."""
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Count 3 /Kids [3 0 R 5 0 R 7 0 R] >>']
    for number, words in ((3, b'Ocean light'), (5, b'Second page'), (7, None)):
        objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 9 0 R >> >> /Contents {number+1} 0 R >>'.encode())
        stream = b'BT /F1 24 Tf 60 720 Td (' + words + b') Tj 0 -60 Td (Far shore) Tj ET' if words else b''
        objects.append(b'<< /Length ' + str(len(stream)).encode() + b' >>\nstream\n' + stream + b'\nendstream')
    objects.append(b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
    result = bytearray(b'%PDF-1.4\n')
    offsets = [0]
    for number, content in enumerate(objects, 1):
        offsets.append(len(result)); result.extend(f'{number} 0 obj\n'.encode() + content + b'\nendobj\n')
    start = len(result)
    result.extend(f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode())
    for offset in offsets[1:]: result.extend(f'{offset:010d} 00000 n \n'.encode())
    result.extend(f'trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode())
    return bytes(result)


def run(a):
    assert not a.output.exists(), 'Preserve every earlier attempt'
    candidate = json.loads(a.build_receipt.read_text())
    for name, row in candidate['files'].items(): assert sha((ROOT/name).read_bytes()) == row['sha256'], name
    for name, digest in candidate['sourceFiles'].items(): assert sha((ROOT/name).read_bytes()) == digest, name
    expected = candidate['files']['app/build/outputs/apk/preview/app-preview.apk']['sha256']
    assert sha(a.apk.read_bytes()) == expected
    public = MAIN / 'captures/release/0.48.0/Findroid-0.48.0-preview.apk'
    assert a.previous_apk.resolve() == public.resolve() and sha(public.read_bytes()) == '3e624f98fcbe354d45d3581cd066cb0f2d02e0d6b2c44a66d79b6955e3a8653a'
    frozen_original = json.loads((ORIGINAL/'candidate-build-6.json').read_text())
    for name in PAIR: assert sha((MAIN/name).read_bytes()) == frozen_original['files'][name]['sha256']
    a.output.mkdir(parents=True)
    private = a.output/'PRIVATE-pdf'; private.mkdir(mode=0o700)
    d = sync.helpers.InspectionDevice(a.serial, private, all_windows=True)
    density_readback = d.shell('wm', 'density').decode()
    match = re.search(r'Override density: (\d+)', density_readback)
    original_density = match.group(1) if match else 'reset'
    density = int(re.findall(r'\d+', density_readback)[-1])/160
    match = re.search(r'Override size: (\d+x\d+)', d.shell('wm', 'size').decode())
    original_size = match.group(1) if match else 'reset'
    original_scale = d.shell('settings', 'get', 'system', 'font_scale').decode().strip()
    ui = sync.NativeControls(d, density)
    platform_api = int(d.shell('getprop','ro.build.version.sdk'))
    input_class = 'PdfSelectionInput' if platform_api <= 30 else 'PdfSelectionInputGlobal'
    stamp = str(time.time_ns()); fixture_rel = 'files/Workspace/Pdf-selection-acceptance-'+stamp
    fixture = '/data/user/0/dev.findroid/'+fixture_rel
    pdf_bytes = synthetic_pdf()
    saved = None; workspace = None; verified = False; input_jar = None
    report = dict(serial=a.serial, section=a.section, sourceCommit=candidate['sourceCommit'], checkerSha256=sha(Path(__file__).read_bytes()),
        buildReceiptSha256=sha(a.build_receipt.read_bytes()), apkSha256=expected, previousApkSha256=sha(public.read_bytes()), fromVersionCode=50, toVersionCode=51,
        checkerDependencies={name:sha((ROOT/name).read_bytes()) for name in ('scripts/check-reviewed-folder-sync.py','scripts/check-local-search-index.py','scripts/android/acceptance.py','scripts/android/PdfSelectionInput.java','scripts/android/PdfSelectionInputGlobal.java')},
        operationsUseExactOptimizedApk=True, acceptanceComplete=False, restorationComplete=False, checks=[], layouts=[], captures=[], clipboardProofs=[], controlProofs=[], selectionHandleProofs=[], screenChecks=[], densityChecks=[], browserHorizontalScrolls=[])

    def prepare_input():
        nonlocal input_jar
        java=Path(os.environ.get('JAVA_HOME') or '/Users/LENOVO/Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1/Contents/Home')
        library=d.sdk/'platforms/android-36/android.jar'
        with tempfile.TemporaryDirectory(prefix='findroid-pdf-input-') as temporary:
            folder=Path(temporary);classes=folder/'classes';classes.mkdir();dex=folder/'dex';dex.mkdir()
            subprocess.run([str(java/'bin/javac'),'-source','8','-target','8','-bootclasspath',str(library),'-d',str(classes),str(ROOT/'scripts/android'/(input_class+'.java'))],check=True)
            subprocess.run([str(d.sdk/'build-tools/36.0.0/d8'),'--lib',str(library),'--output',str(dex),str(classes/'dev/findroid/qa'/(input_class+'.class'))],env={**os.environ,'JAVA_HOME':str(java)},check=True)
            archive=folder/'input.jar'
            with zipfile.ZipFile(archive,'w') as output:output.write(dex/'classes.dex','classes.dex')
            input_jar='/data/local/tmp/findroid-pdf-input-'+stamp+'.jar';d.call('push',archive,input_jar)
    def native_input(*arguments):
        assert input_jar is not None
        d.shell('env','CLASSPATH='+input_jar,'app_process','/system/bin','dev.findroid.qa.'+input_class,*arguments)
    def drag(first,last,hold=0,duration=350):native_input('drag',*first,*last,hold,duration)
    def stop(): d.shell('am', 'force-stop', 'dev.findroid')
    def installed(package='dev.findroid'):
        path = d.shell('pm','path',package).decode().strip().splitlines()[0].removeprefix('package:')
        return sha(d.call('exec-out','cat',path))
    def install(path, downgrade=False):
        stop(); d.call('install','-r',*(['-d'] if downgrade else []),path); stop()
    def read(path): return d.shell('run-as','dev.findroid','cat',path)
    def write(path, data):
        d.shell('run-as','dev.findroid','mkdir','-p',str(Path(path).parent))
        d.call('shell','-T',shlex.join(['run-as','dev.findroid','sh','-c','cat > '+shlex.quote(path)]),data=data)
    def snapshot(): return d.shell('run-as','dev.findroid','tar','-cf','-','--exclude=files/Workspace','--exclude=files/ProviderFixtures',*sync.MANAGED)
    def restore_metadata():
        assert saved is not None
        stop(); before=sync.entries(saved); current=sync.entries(snapshot())
        extras=[n for n,v in current.items() if v is not None and n not in before]
        for offset in range(0,len(extras),100):
            names=extras[offset:offset+100]
            assert all(Path(n).parts[0] in sync.MANAGED and not any(n==x or n.startswith(x+'/') for x in sync.EXCLUDED) for n in names)
            d.shell('run-as','dev.findroid','rm','-f','--',*names)
        d.shell('run-as','dev.findroid','tar','-xf','-',data=saved)
        current=sync.entries(snapshot())
        for name in sorted(set(current)-set(before),key=lambda n:(-n.count('/'),n)):
            assert current[name] is None; d.shell('run-as','dev.findroid','rmdir','--',name)
        assert sync.entries(snapshot())==before
    def pref(tree,kind,name,value):
        for child in list(tree):
            if child.get('name')==name: tree.remove(child)
        node=ET.SubElement(tree,kind,name=name)
        if kind=='string': node.text=value
        elif kind=='set':
            for item in value: ET.SubElement(node,'string').text=item
        else: node.set('value',str(value).lower())
    def seed(name,dark=False):
        install(ROOT/PAIR[0]); restore_metadata()
        relative=fixture_rel+'/'+name; base=fixture+'/'+name
        write(relative+'/Field guide.pdf',pdf_bytes)
        tree=ET.fromstring(sync.entries(saved).get('shared_prefs/finder.xml') or b'<map/>')
        for child in list(tree):
            if child.get('name','').startswith('tag:'): tree.remove(child)
        tab=dict(id='pdf-synthetic',history=[base],index=0,view='LIST',query='',rules={})
        sidebar=dict(version=1,favorites=[],groups=[],builtins=['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],hidden=['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'])
        values=[('string','tabs',json.dumps([tab])),('int','activeTab',0),('boolean','dualPane',False),('string','activePane','primary'),('boolean','panesReversed',False),('boolean','dark',dark),('boolean','sidebarVisible',False),('boolean','preview',False),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),('string','density','COMFORTABLE'),('string','view','LIST'),('string','smartFolders','[]'),('string','sidebarLayoutV1',json.dumps(sidebar)),('set','favorites',[]),('string','pdfAcceptanceSentinel',stamp)]
        for kind,key,value in values: pref(tree,kind,key,value)
        write('shared_prefs/finder.xml',ET.tostring(tree,encoding='utf-8',xml_declaration=True))
        write('shared_prefs/locations.xml',b'<map><string name="roots">[]</string></map>')
        return relative
    def ready(tree,label):
        try: return d.ready(ui.scope(tree),label)
        except AssertionError: return False
    def launch(name):
        assert installed()==expected
        stop(); d.shell('am','start','-W','-f','0x10008000','--windowingMode',1,'-n','dev.findroid/.MainActivity')
        d.wait(name+'-browser',lambda tree:ready(tree,'Actions'),seconds=90)
    def open_preview(name):
        label='Actions for Field guide.pdf'
        for attempt in range(8):
            tree=d.dump(name+'-file-actions-'+str(attempt));scope=ui.scope(tree);node=ui.node(scope,label,True)
            if node is not None and node.get('enabled')=='true' and ui.full(scope,node,True):
                ui.proofs.append(dict(name=name+'-file-actions',text=node.get('text'),description=node.get('content-desc'),bounds=d.bounds(node),density=ui.density));d.tap(node);break
            panes=[n for n in scope.iter('node') if n.get('class')=='android.widget.HorizontalScrollView' and n.get('scrollable')=='true' and any(c.get('text')=='Field guide.pdf' or c.get('content-desc')==label for c in n.iter('node'))]
            assert len(panes)==1,'Expected the synthetic file table horizontal viewport'
            l,t,r,b=d.bounds(panes[0]);first,last=(.82,.22) if attempt<4 else (.22,.82)
            report['browserHorizontalScrolls'].append(dict(name=name,attempt=attempt,paneBoundsPx=[l,t,r,b],namedControlRecheckedBeforeTap=True))
            d.shell('input','swipe',round(l+(r-l)*first),(t+b)//2,round(l+(r-l)*last),(t+b)//2,300)
        else:raise AssertionError('Named file actions unavailable after horizontal navigation')
        ui.click(name+'-quick-look','Quick Look')
        d.wait(name+'-preview',lambda tree:ready(tree,'Select page text'))
        ui.text(name+'-rendered-page','PDF page text selection')
    def optimized(name): install(a.apk); launch(name); open_preview(name)
    def selecting(name,*,empty=False):
        ui.click(name+'-select-page-text','Select page text')
        d.wait(name+'-map',lambda tree:ready(tree,'Select all page text') or empty and bool(d.nodes(tree,'This page has no text layer to select.')))
    def point(name,x,y):
        tree=d.dump(name); node=d.one(ui.scope(tree),'PDF page text selection'); l,t,r,b=d.bounds(node)
        assert abs((b-t)/(r-l)-800/600)<.03, 'Use exact fitted page geometry before transforming'
        return round(l+(r-l)*x),round(t+(b-t)*y)
    def tap_xy(x,y):
        node=ET.Element('node',bounds=f'[{x-1},{y-1}][{x+1},{y+1}]');d.tap(node)
    def word(name):
        x,y=point(name+'-page-bounds',.15,.09);tap_xy(x,y)
        d.wait(name+'-word',lambda tree:ready(tree,'Copy selection'))
    def capture(name):
        output=a.output/(name+'-surface.png');assert not output.exists()
        d.capture(name);raw=private/(name+'.png');xml=private/(name+'-capture.xml');tree=ET.parse(xml).getroot();scope=ui.scope(tree)
        parents={child:parent for parent in scope.iter() for child in parent};node=d.one(scope,'Close Quick Look')
        while node is not None and not (d.nodes(node,'Close Quick Look') and d.nodes(node,'Field guide.pdf') and (d.nodes(node,'Select page text') or d.nodes(node,'Pan and zoom'))):node=parents.get(node)
        assert node is not None
        bounds=d.bounds(node);image=Image.open(raw).convert('RGB');l,t,r,b=bounds
        assert 0<l<r<image.width and 0<t<b<image.height,'Crop must contain only the Quick Look surface'
        image.crop(tuple(bounds)).save(output)
        report['captures'].append(dict(path=output.name,sha256=sha(output.read_bytes()),surfaceBoundsPx=bounds,browserBackdropExcluded=True,privateRawCaptureSha256=sha(raw.read_bytes()),privateRawHierarchySha256=sha(xml.read_bytes())))
        return tree,image
    def copy(name,expected_text,*,page_end_breaks=False):
        ui.click(name+'-copy','Copy selection');ui.text(name+'-copied','Copied selected text')
        assert installed('dev.findroid.test')==candidate['files'][PAIR[1]]['sha256']
        d.shell('am','start','-W','-n','dev.findroid.test/dev.findroid.PdfClipboardProbeActivity')
        tree=d.wait(name+'-foreground-clipboard',lambda tree:len(d.nodes(tree,'PDF copied text'))==1 and (d.one(tree,'PDF copied text').get('text','').rstrip('\n') if page_end_breaks else d.one(tree,'PDF copied text').get('text'))==expected_text)
        actual=d.one(tree,'PDF copied text').get('text');assert (actual.rstrip('\n') if page_end_breaks else actual)==expected_text
        report['clipboardProofs'].append(dict(name=name,expectedText=expected_text,actualTextSha256=sha(actual.encode()),actualSyntheticText=actual,pageEndLineBreaksAllowed=page_end_breaks,readByForegroundTestOnlyActivity=True,operationsUsedOptimizedApp=True))
        d.shell('input','keyevent','BACK');d.wait(name+'-returned-to-preview',lambda tree:ready(tree,'Close Quick Look'))
    def controls(name,selection=True):
        labels=['Close Quick Look','Next PDF page','Zoom in','Pan and zoom' if selection else 'Select page text']
        if selection: labels+=['Copy selection','Select all page text','Clear selection']
        for label in labels:ui.reach(name+'-'+label,label)
        if selection:
            for label in ('Start of PDF text selection','End of PDF text selection'):
                tree,node=ui.reach(name+'-'+label,label,clickable=False,enabled=False)
                l,t,r,b=d.bounds(node);assert r-l>=48*ui.density-1 and b-t>=48*ui.density-1
                report['selectionHandleProofs'].append(dict(name=name,label=label,boundsPx=[l,t,r,b],density=ui.density,complete48DpTarget=True))
    def unchanged(relative):
        install(ROOT/PAIR[0]);assert read(relative+'/Field guide.pdf')==pdf_bytes
        assert installed('dev.findroid')==candidate['files'][PAIR[0]]['sha256']
    def checked(name,**facts):
        report['checks'].append(dict(name=name,**facts));(a.output/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(a.serial+': '+name,flush=True)
    def screen(width,height,dpi):
        requested=(round(width*dpi/160),round(height*dpi/160));d.shell('wm','size',f'{requested[0]}x{requested[1]}')
        actual=tuple(map(int,re.findall(r'(?:Physical|Override) size: (\d+)x(\d+)',d.shell('wm','size').decode())[-1]));assert actual==requested;d.screen=actual
        report['screenChecks'].append(dict(requestedWidthDp=width,requestedHeightDp=height,densityDpi=dpi,actualWidthPx=actual[0],actualHeightPx=actual[1]))
    try:
        assert installed()==sha(public.read_bytes()),'Begin only from the exact restored public 0.48 APK'
        install(ROOT/PAIR[0]);saved=snapshot();(private/'PRIVATE-original-managed.tar').write_bytes(saved)
        baseline=json.loads((ORIGINAL/'private-native-state'/a.serial/'PRIVATE-MANIFEST.json').read_text())['files']
        assert {n:sha(v) for n,v in sync.entries(saved).items() if v is not None}==baseline
        workspace=d.shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(private/'PRIVATE-workspace-before.tar').write_bytes(workspace)
        assert sync.originals.grants(a.serial)==json.loads((ORIGINAL/'private-native-state'/a.serial/'PRIVATE-ENVIRONMENT.json').read_text())['originalGrants']
        verified=True;report['immutableOriginalBytesVerifiedBeforeTests']=True
        d.call('install','-r',ROOT/PAIR[1]);assert installed('dev.findroid.test')==candidate['files'][PAIR[1]]['sha256']
        prepare_input()
        report['inputHelper']=dict(platformApi=platform_api,className=input_class,sourceSha256=sha((ROOT/'scripts/android'/(input_class+'.java')).read_bytes()),shellOnly=True)
        if a.section in ('core','all'):
            relative=seed('Core');install(public,True);optimized('public50-to51')
            selecting('word');word('word');controls('word-controls');copy('word','Ocean');capture('word-and-handles')
            ui.click('clear-word','Clear selection')
            first=point('range-first',.15,.09);last=point('range-last',.15,.165)
            drag(first,last);copy('two-lines','Ocean light\nFar');capture('two-lines')
            checked('direct-public50-to51-word-and-range',wordClipboardExact=True,twoLineClipboardExact=True,allControlsHave48DpBounds=True)
            ui.click('clear-range','Clear selection');word('pan-word');ui.click('zoom','Zoom in');ui.click('pan-mode','Pan and zoom')
            tree=d.dump('before-pan');scope=ui.scope(tree);assert not d.nodes(scope,'End of PDF text selection')
            page=d.one(scope,'PDF page text selection');l,t,r,b=d.bounds(page)
            drag(((l+r)//2,round(t+(b-t)*.5)),((l+r)//2,round(t+(b-t)*.85)))
            copy('pan-kept-word','Ocean');capture('retained-selection-pan-mode')
            ui.click('resume-selection','Select page text');ui.text('resumed-end-handle','End of PDF text selection');copy('zoom-kept-word','Ocean');capture('visible-zoomed-selection')
            checked('zoom-and-pan-retain-selection',realPageDrag=True,clipboardStayedExact=True,handlesReturnWhenAdjusting=True)
            ui.click('fit','125%');ui.click('long-press-pan-mode','Pan and zoom');ui.click('clear-before-long-press','Clear selection')
            first=point('long-press-point',.15,.09);drag(first,first,hold=900,duration=0)
            d.wait('long-press-loaded',lambda tree:ready(tree,'Copy selection'));copy('first-long-press','Ocean')
            ui.click('next-page','Next PDF page');ui.reach('page-cleared-selection','Select page text')
            tree=d.dump('cleared-page');assert not d.nodes(ui.scope(tree),'Copy selection')
            ui.click('blank-page','Last PDF page');selecting('no-text-layer',empty=True);ui.text('no-text-explanation','This page has no text layer to select.')
            tree=d.dump('no-text-disabled');scope=ui.scope(tree);node=ui.node(scope,'Copy selection',True);assert node is not None and node.get('enabled')=='false'
            capture('no-text-layer')
            checked('first-long-press-and-page-clear',firstLongPressClipboardExact=True,pageChangeClearedSelection=True,noTextLayerExplained=True,emptyPageCopyDisabled=True)
            ui.click('first-page','First PDF page');selecting('keyboard');word('keyboard-word')
            native_input('key',29,4096);copy('keyboard-all','Ocean light\nFar shore',page_end_breaks=True)
            d.shell('input','keyevent','KEYCODE_ESCAPE');ui.reach('escape-left-selection','Select page text');ui.click('close-preview','Close Quick Look')
            open_preview('reopen');tree=d.dump('reopened-clean');assert not d.nodes(ui.scope(tree),'Copy selection')
            ui.click('close-reopened','Close Quick Look');unchanged(relative)
            checked('keyboard-and-preview-scope',keyboardSelectAllPassageAndLineBreaksVerified=True,escapeLeavesSelection=True,reopenHasNoStaleSelection=True,originalPdfBytesKept=True)
        if a.section in ('layouts','all'):
            glyphs={}
            for width,height in ((1280,800),(800,1280),(600,800)):
                for font in (1.0,2.0):
                    for dark in (False,True):
                        theme='dark' if dark else 'light';name=f'layout-{width}x{height}-font{font}-{theme}'
                        relative=seed(name,dark=dark);screen(width,height,round(density*160));d.shell('settings','put','system','font_scale',font)
                        assert float(d.shell('settings','get','system','font_scale').decode())==font
                        optimized(name);selecting(name);word(name);controls(name+'-controls');tree,image=capture(name+'-selection')
                        nodes=[n for n in ui.scope(tree).iter('node') if n.get('class')=='android.widget.TextView' and n.get('text')=='Copy selection' and n.get('clickable')=='false'];assert len(nodes)==1
                        bounds=d.bounds(nodes[0]);crop=image.crop(tuple(bounds));glyph=a.output/(name+'-copy-glyph.png');crop.save(glyph)
                        background=tuple(sorted(crop.getpixel(point)[index] for point in ((0,0),(crop.width-1,0),(0,crop.height-1),(crop.width-1,crop.height-1)))[2] for index in range(3))
                        channels=ImageChops.difference(crop,Image.new('RGB',crop.size,background)).split();mask=ImageChops.lighter(ImageChops.lighter(channels[0],channels[1]),channels[2]).point(lambda value:255 if value>24 else 0);pixels=mask.getbbox();assert pixels
                        metrics=dict(systemFontScale=font,textBoundsPx=bounds,textWidth=bounds[2]-bounds[0],textHeight=bounds[3]-bounds[1],rasterGlyphWidth=pixels[2]-pixels[0],rasterGlyphHeight=pixels[3]-pixels[1],image=glyph.name,sha256=sha(glyph.read_bytes()))
                        glyphs.setdefault((width,height,theme),{})[font]=metrics
                        copy(name,'Ocean');ui.click(name+'-pan','Pan and zoom');controls(name+'-pan-controls',selection=False);capture(name+'-pan');copy(name+'-pan','Ocean')
                        ui.click(name+'-close','Close Quick Look');unchanged(relative)
                        report['layouts'].append(dict(widthDp=width,heightDp=height,fontScale=font,dark=dark,allNamedControlsHaveComplete48DpBounds=True,wordAndRetainedPanClipboardExact=True,originalPdfBytesKept=True,systemFont=metrics))
            report['systemFontGroups']=[]
            for (width,height,theme),samples in glyphs.items():
                for key in ('textWidth','textHeight','rasterGlyphWidth','rasterGlyphHeight'):assert samples[2.0][key]>samples[1.0][key],(width,height,theme,key)
                report['systemFontGroups'].append(dict(widthDp=width,heightDp=height,theme=theme,actualTextAndGlyphGrowth=True,samples=[samples[1.0],samples[2.0]]))
            for dpi in (240,300):
                name='density-'+str(dpi);relative=seed(name);d.shell('wm','density',dpi);actual=int(re.findall(r'(?:Physical|Override) density: (\d+)',d.shell('wm','density').decode())[-1]);assert actual==dpi
                ui.density=actual/160;screen(800,1280,actual);d.shell('settings','put','system','font_scale',2.0);assert float(d.shell('settings','get','system','font_scale').decode())==2.0
                report['densityChecks'].append(dict(requestedDpi=dpi,actualDpi=actual,systemFontScale=2.0));optimized(name);selecting(name);word(name);controls(name+'-controls');capture(name+'-selection');copy(name,'Ocean');ui.click(name+'-close','Close Quick Look');unchanged(relative)
            checked('system-font-and-tablet-layouts',twelveSizeFontThemeLayouts=True,twoActualDpiChanges=True,sixGroupsHaveActualTextAndRasterGrowth=True,allClipboardReadsForegroundAndExact=True)
        report['acceptanceComplete']=True
    finally:
        try:
            stop()
            for name in PAIR:d.call('install','-r','-d',MAIN/name)
            if verified:
                if d.shell('run-as','dev.findroid','test','-d',fixture_rel,check=False).returncode==0:
                    assert fixture_rel.startswith('files/Workspace/Pdf-selection-acceptance-') and stamp in fixture_rel
                    (private/'PRIVATE-synthetic-fixture-final.tar').write_bytes(d.shell('run-as','dev.findroid','tar','-cf','-',fixture_rel))
                    d.shell('run-as','dev.findroid','rm','-rf','--',fixture_rel)
                after=d.shell('run-as','dev.findroid','tar','-cf','-','files/Workspace');(private/'PRIVATE-workspace-after.tar').write_bytes(after)
                report['originalWorkspaceTreeKept']=sync.entries(after)==sync.entries(workspace)
                d.shell('wm','density',original_density);d.shell('wm','size',original_size);d.shell('settings','put','system','font_scale',original_scale)
                report['restoration']=sync.originals.restore_one(a.serial,public);report['restorationComplete']=True
                assert installed()==sha(public.read_bytes()) and report['originalWorkspaceTreeKept']
                assert installed('dev.findroid.test')==frozen_original['files'][PAIR[1]]['sha256']
            else:d.call('install','-r','-d',public)
        finally:
            report['controlProofs']+=ui.proofs;report['hierarchyRetries']=d.retries
            (a.output/'result.json').write_text(json.dumps(report,indent=2)+'\n')
            if input_jar is not None:d.shell('rm','-f','--',input_jar)
            d.close()
    print(json.dumps(dict(serial=a.serial,section=a.section,checks=len(report['checks']),layouts=len(report['layouts']),acceptanceComplete=report['acceptanceComplete'],restorationComplete=report['restorationComplete'])),flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True,choices=('emulator-5602','emulator-5582'))
    parser.add_argument('--apk',required=True,type=Path);parser.add_argument('--previous-apk',required=True,type=Path)
    parser.add_argument('--build-receipt',required=True,type=Path);parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--section',choices=('core','layouts','all'),default='all');a=parser.parse_args()
    with (ORIGINAL/('.'+a.serial+'-acceptance.lock')).open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);run(a)
if __name__=='__main__':main()
