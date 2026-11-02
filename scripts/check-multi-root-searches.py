#!/usr/bin/env python3
"""Exercise optimized chosen search locations through touch and a real 0.37 upgrade.

Only synthetic files enter captures. Original metadata/preferences stay in memory, are
restored byte-for-byte in finally, and are never included in public reports. No app clear
or uninstall. The debug APK is used only to snapshot/audit private metadata while stopped.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import time
import xml.etree.ElementTree as ET
from android.acceptance import AndroidDevice, ROOT

FILES = {'tags.properties', 'workspaces.json', 'folder-views.json', 'folder-styles.properties',
         'rename-presets.properties', 'setup-previous.json', 'document-parents.properties'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--previous-apk', type=Path, required=True)
    parser.add_argument('--expected-version-code', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.serial in {'emulator-5602', 'emulator-5582'}, 'Use an assigned acceptance tablet.'
    assert not args.output.exists(), 'Retain earlier attempts; use a fresh output directory.'
    device = AndroidDevice(args.serial, args.output, all_windows=True)
    debug = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    demo = '/storage/emulated/0/Download/Findroid search locations demo ' + str(time.time_ns())[-10:]
    source, target = demo+'/Writing project', demo+'/Research project'
    payload = b'Synthetic project context around needle in this report.\n' * 45
    paths = ['Writing project/Report current.txt', 'Writing project/Cache/Report cached.txt',
             'Research project/Report here.txt', 'Research project/Cache/Report nested.txt']
    rules = {'name':'Report','kind':'Text document','tag':'','days':1,'min':1025,'max':0,
             'content':'needle','terms':[],'tags':[],'tagMatch':'ALL','includeSubfolders':True,
             'excludedFolderNames':['Scratch'],'excludedLocations':[{'path':source+'/Cache','folder':True}],
             'date':'TODAY','size':'UNDER_1_MB'}
    original_scale = device.shell('settings','get','system','font_scale').decode().strip()
    size = device.shell('wm','size').decode()
    match = re.search(r'Override size: (\d+x\d+)', size)
    original_size = match.group(1) if match else 'reset'
    access = device.shell('appops','get','dev.findroid','MANAGE_EXTERNAL_STORAGE').decode()
    match = re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)', access)
    original_access = match.group(1) if match else 'default'
    density = int(re.findall(r'\d+',device.shell('wm','density').decode())[-1])/160
    saved = None
    report = {'serial':args.serial, 'apkSha256':hashlib.sha256(args.apk.read_bytes()).hexdigest()}

    def stop(): device.shell('am','force-stop','dev.findroid')
    def install(path, downgrade=False):
        stop(); device.call('install','-r', *(['-d'] if downgrade else []), path)
    def launch():
        device.shell('am','start','-W','-f','0x10008000','--windowingMode',1,
                     '-n','dev.findroid/.MainActivity','--es','finderWindowId','main')
    def version():
        return int(re.search(r'versionCode=(\d+)',device.shell('dumpsys','package','dev.findroid').decode()).group(1))
    def read(path, optional=False):
        result = device.call('exec-out','run-as','dev.findroid','cat',path,check=False)
        if optional and result.returncode: return None
        assert result.returncode == 0, 'Cannot read setup: '+path
        return result.stdout
    def write(path,data):
        device.call('shell','run-as dev.findroid sh -c '+shlex.quote('cat > '+shlex.quote(path)),data=data)
    def snapshot():
        names = device.shell('run-as','dev.findroid','find','shared_prefs','-maxdepth',1,'-type','f').decode().splitlines()
        names += ['files/'+name+suffix for name in FILES for suffix in ('','.bak','.new')]
        return {name:read(name,True) for name in names}
    def restore():
        current = device.shell('run-as','dev.findroid','find','shared_prefs','-maxdepth',1,'-type','f').decode().splitlines()
        for name in set(current)-set(saved): device.shell('run-as','dev.findroid','rm','-f',name)
        for name,data in saved.items():
            if data is None: device.shell('run-as','dev.findroid','rm','-f',name)
            else: write(name,data)
        assert snapshot() == saved, 'Original metadata/preferences were not restored exactly.'
    def pref(root,kind,name,value):
        for old in list(root):
            if old.get('name') == name: root.remove(old)
        node = ET.SubElement(root,kind,name=name)
        if kind == 'string': node.text = value
        elif kind == 'set':
            for item in value: ET.SubElement(node,'string').text = item
        else: node.set('value',str(value).lower())
    def seed():
        for name in FILES:
            for suffix in ('','.bak','.new'): device.shell('run-as','dev.findroid','rm','-f','files/'+name+suffix)
        prefs = ET.fromstring(saved['shared_prefs/finder.xml'])
        for old in list(prefs):
            if old.get('name') in {'recentSearchesV1','rememberRecentSearches'}: prefs.remove(old)
        tab = {'id':'locations-upgrade','history':[source],'index':0,'query':'Report','rules':dict(rules,name='')}
        values = [('string','tabs',json.dumps([tab, {'id':'research-tab','history':[target],'index':0,'query':'','rules':{'name':''}}])),('int','activeTab',0),('boolean','dualPane',False),
                  ('string','activePane','primary'),('boolean','panesReversed',False),('boolean','dark',False),
                  ('boolean','sidebarVisible',True),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),
                  ('boolean','preview',False),('string','density','COMFORTABLE'),('string','view','LIST'),
                  ('string','smartFolders','[]'),('string','searchLocationsAcceptanceSentinel',demo)]
        for kind,name,value in values: pref(prefs,kind,name,value)
        sidebar = {'version':1,'favorites':[{'id':'locations-demo','path':source,'label':'Writing project'}],
                   'groups':[],'builtins':['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],
                   'hidden':['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC']}
        pref(prefs,'string','sidebarLayoutV1',json.dumps(sidebar)); pref(prefs,'set','favorites',[source])
        write('shared_prefs/finder.xml',ET.tostring(prefs,encoding='utf-8',xml_declaration=True))
        write('shared_prefs/locations.xml',b'<?xml version="1.0" encoding="utf-8"?><map><string name="roots">[]</string></map>')
        for name in paths:
            path = demo+'/'+name
            device.shell('mkdir','-p',str(Path(path).parent))
            device.call('shell','cat > '+shlex.quote(path),data=payload)
    def browser(name, label=None):
        return device.wait(name,lambda tree: device.ready(tree,'Actions') and (label is None or bool(device.nodes(tree,label))))
    def menu(name,label):
        device.click(name+'-open','Actions')
        # Start from the top even when DropdownMenu retained its previous scroll position.
        def menu_panes(tree):
            # Focus/IME transitions can briefly expose no application window.
            # The actual popup title is retained on both assigned Android APIs.
            return [n for w in tree.iter('window') if w.get('title')=='Pop-Up Window'
                    for n in w.iter('node') if n.get('class')=='android.widget.ScrollView'
                    and n.get('scrollable')=='true' and n.get('package')=='dev.findroid']
        for _ in range(12):
            tree = device.wait(name+'-top',lambda t: len(menu_panes(t))==1)
            if device.nodes(tree,label): device.tap(device.one(tree,label)); return
            if device.nodes(tree,'App updates'): break
            panes = menu_panes(tree)
            assert len(panes)==1
            l,t,r,b=device.bounds(panes[0]); x=(l+r)//2
            device.shell('input','swipe',x,round(t+(b-t)*.25),x,round(t+(b-t)*.8),300)
        device.tap(device.one(device.scroll_to(name,label,x=.9),label))
    def panel(tree,title,footer):
        parents={child:parent for parent in tree.iter() for child in parent}
        headings=[node for node in tree.iter('node') if node.get('text')==title and len(device.bounds(node))==4 and device.bounds(node)[3]>device.bounds(node)[1]]
        assert len(headings)==1,(title,len(headings))
        node=headings[0]
        while not device.nodes(node,footer):
            assert node in parents,(title,footer)
            node=parents[node]
        return node
    def control(tree,title,label,footer):
        scope=panel(tree,title,footer)
        parents={child:parent for parent in scope.iter() for child in parent}
        node=device.one(scope,label)
        while node.get('clickable')!='true' and node in parents: node=parents[node]
        return node
    def fully_visible(tree,node):
        l,t,r,b=device.bounds(node);w,h=device.screen
        if not (0<=l<r<=w and 0<=t<b<=h and r-l>=48*density and b-t>=48*density):return False
        parents={child:parent for parent in tree.iter() for child in parent}
        parent=node
        while parent in parents:
            parent=parents[parent]
            if parent.get('scrollable')=='true':
                pl,pt,pr,pb=device.bounds(parent)
                if not (pl<=l<r<=pr and pt<=t<b<=pb):return False
        return True
    def assert_control(tree,title,label,footer):
        node=control(tree,title,label,footer)
        assert node.get('enabled')=='true' and fully_visible(tree,node),(title,label,node.attrib)
        return node
    def scope_dialog(name):
        menu(name,'Search locations…')
        return device.wait(name,lambda t: bool(device.nodes(t,'Search locations')) and device.ready(t,'Search') and device.ready(t,'Cancel'))
    def scope_scroll(name,label):
        for direction in (1,-1):
            previous=None
            for _ in range(16):
                tree=device.dump(name); scope=panel(tree,'Search locations','Cancel')
                if device.nodes(scope,label):
                    node=control(tree,'Search locations',label,'Cancel')
                    if node.get('enabled')=='true' and fully_visible(tree,node):return tree,node
                panes=[n for n in scope.iter('node') if n.get('scrollable')=='true']
                if not panes:break
                assert len(panes)==1
                pane=panes[0]; signature=ET.tostring(pane)
                if previous==signature:break
                previous=signature; l,t,r,b=device.bounds(pane); x=(l+r)//2
                start,end=(.8,.3) if direction==1 else (.3,.8)
                device.shell('input','swipe',x,round(t+(b-t)*start),x,round(t+(b-t)*end),350)
        raise AssertionError('No full scope control: '+label)
    def add_target(name,nested=False):
        _,node=scope_scroll(name+'-add','Add folder…'); device.tap(node)
        tree=device.wait(name+'-picker',lambda t: bool(device.nodes(t,'Add search folder')) and bool(device.nodes(t,'Tab: Research project')))
        device.tap(device.one(tree,'Tab: Research project'))
        device.wait(name+'-target',lambda t: device.ready(t,'Add folder') and bool(device.nodes(t,'Cache')))
        if nested:
            device.click(name+'-nested','Cache')
            device.wait(name+'-nested-ready',lambda t: device.ready(t,'Add folder') and not device.nodes(t,'Cache'))
        device.click(name+'-confirm','Add folder')
        return device.wait(name+'-draft',lambda t: bool(device.nodes(t,'Search locations')) and bool(device.nodes(t,'Cancel')))
    def all_matches(name):
        expected=('Report current.txt','Report here.txt','Report nested.txt')
        tree=device.wait(name,lambda t: device.ready(t,'Actions') and
                         all(bool(device.nodes(t,label)) for label in expected))
        for label in expected: assert len(device.nodes(tree,label))==1,(name,label)
        assert not device.nodes(tree,'Report cached.txt')
        return tree
    def details(name):
        device.click(name+'-open','Result details')
        return device.wait(name,lambda t: bool(device.nodes(t,'Result details')) and device.ready(t,'Close Result details'))
    def close_details(name): device.click(name,'Close Result details')
    def audit():
        install(debug)
        prefs=ET.fromstring(read('shared_prefs/finder.xml'))
        tabs=json.loads(prefs.find("./string[@name='tabs']").text)
        smart=json.loads(prefs.find("./string[@name='smartFolders']").text)
        return prefs,tabs,smart

    try:
        install(debug)
        assert device.shell('run-as','dev.findroid','test','!','-e','files/.setup-transaction',check=False).returncode==0
        saved=snapshot(); seed()
        device.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE','allow')
        device.shell('settings','put','system','font_scale','1.0')
        install(args.previous_apk,True); launch(); browser('before-upgrade','Report current.txt')
        assert version()==38,'Expected frozen public 0.37.'
        install(args.apk); launch(); browser('after-upgrade','Report current.txt')
        assert version()==args.expected_version_code
        installed=device.shell('pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        assert hashlib.sha256(device.call('exec-out','cat',installed)).hexdigest()==report['apkSha256']
        report.update(actual037Upgrade=True,installedOptimizedBytes=True)
        scope_dialog('initial-scope'); add_target('cancel-target')
        device.click('cancel-draft','Cancel')
        tree=browser('cancel-preserved','Report current.txt');assert not device.nodes(tree,'Report here.txt')
        report['cancelPreservesScope']=True
        scope_dialog('choose-scope');add_target('choose-target');add_target('choose-overlap',nested=True)
        device.capture('search-locations-draft')
        tree=device.dump('apply-scope');device.tap(assert_control(tree,'Search locations','Search','Cancel'));all_matches('combined-results')
        device.capture('search-locations-tablet')
        details('complete-details');device.capture('search-locations-complete');close_details('close-complete')
        report.update(realFolderPicker=True,overlapDeduplicated=True,literalExclusionsPreserved=True)
        # A missing first anchor must not run the old single-folder preflight.
        device.shell('mv',source,source+' offline');stop();launch()
        tree=browser('missing-anchor','Report here.txt');assert not device.nodes(tree,'Report current.txt')
        tree=details('unavailable-details');assert device.nodes(tree,'Unavailable')
        device.capture('search-locations-unavailable');close_details('close-unavailable')
        report['unavailableFirstRootKeepsOtherResults']=True
        device.shell('mv',source+' offline',source)
        device.shell('input','keyevent',135);all_matches('restored-anchor')
        menu('save-smart','Save search as Smart Folder…')
        device.wait('smart-name',lambda t: device.ready(t,'Save') and bool(device.nodes(t,'Save Smart Folder')))
        device.click('confirm-smart','Save');all_matches('smart-results')
        stop();launch();all_matches('smart-after-restart')
        report['smartFolderScopeSurvivesRestart']=True
        layouts=[]
        for width,height in ((1280,800),(800,1000),(600,800)):
            for scale in (1.0,1.6):
                stop();device.shell('wm','size',f'{round(width*density)}x{round(height*density)}')
                device.screen=(round(width*density),round(height*density))
                device.shell('settings','put','system','font_scale',scale);launch();browser('layout-browser')
                key=f'{width}dp-font{scale}'
                tree=scope_dialog(key+'-scope')
                for label in ('Search','Cancel','Close Search locations'):assert_control(tree,'Search locations',label,'Cancel')
                tree,node=scope_scroll(key+'-add','Add folder…');device.capture(key+'-scope')
                for label in ('Search','Cancel'):assert_control(tree,'Search locations',label,'Cancel')
                device.tap(control(tree,'Search locations','Cancel','Cancel'))
                tree=details(key+'-details');device.capture(key+'-details')
                assert_control(tree,'Result details','Close Result details','Close Result details')
                close_details(key+'-close-details')
                layouts.append({'widthDp':width,'fontScale':scale,'footerVisible':True,'fullTouchBounds':True,'addFolderReachable':True,'detailsVisible':True})
        report['layouts']=layouts
        stop();device.shell('wm','size',original_size);device.screen=(round(1280*density),round(800*density))
        device.shell('settings','put','system','font_scale','1.0')
        # Exercise the same custom-window CLEAR_TASK sequence that rejected 0.38.
        # Keep the process alive between replacements so outgoing/incoming owners overlap.
        window_id='locations-window-'+str(time.time_ns())
        for index in range(5):
            device.shell('am','start','-W','-f','0x10008000','--windowingMode',1,
                         '-n','dev.findroid/.MainActivity','--es','finderWindowId',window_id,
                         '--es','finderLocation',target)
            tree=browser('window-replacement-'+str(index+1),'Report here.txt')
            assert not device.nodes(tree,'Report current.txt'),'The new window must show its own folder.'
        device.capture('window-replacement')
        report.update(customWindowTaskReplacement=True,customWindowIterations=5)
        prefs,tabs,smart=audit()
        window_prefs=ET.fromstring(read('shared_prefs/finder-window-'+window_id+'-primary.xml'))
        window_tabs=json.loads(window_prefs.find("./string[@name='tabs']").text)
        assert window_tabs[0]['history'][-1]==target
        assert not window_tabs[0]['rules'].get('locations')
        report['customWindowIndependentTabsPreserved']=True
        expected=dict(rules,locations=[source,target,target+'/Cache'])
        assert len(smart)==1 and smart[0]['root']==source and smart[0]['rules']==expected,smart
        assert tabs[0]['history'][-1]=='@smart:'+smart[0]['id']
        history=json.loads(prefs.find("./string[@name='recentSearchesV1']").text)['searches']
        assert any(row['root']==source and row['rules']==expected for row in history)
        raw=json.dumps({'tabs':tabs,'smart':smart,'history':history})
        assert not any(token in raw for token in ('Synthetic project context','snippet','entries'))
        assert prefs.find("./string[@name='searchLocationsAcceptanceSentinel']").text==demo
        sidebar=json.loads(prefs.find("./string[@name='sidebarLayoutV1']").text)
        assert sidebar['favorites'][0]['path']==source
        for name in paths:assert device.call('exec-out','cat',demo+'/'+name)==payload
        (args.output/'synthetic-smart-folders.json').write_text(json.dumps(smart,indent=2)+'\n')
        (args.output/'synthetic-history.json').write_text(json.dumps({'searches':history},indent=2)+'\n')
        report.update(exactFullCriteriaPersisted=True,allRootsInHistory=True,noSavedResultBytes=True,originalFileBytes=True,
                      favoritePreserved=True,unrelatedPreferencePreserved=True)
    finally:
        stop()
        if saved is not None:
            install(debug);restore();install(args.apk)
            report['restoredOriginalSetup']=True
        device.shell('settings','put','system','font_scale',original_scale)
        device.shell('wm','size',original_size)
        device.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE',original_access)
        device.shell('rm','-rf',demo)
        device.close()
    (args.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

if __name__=='__main__':main()
