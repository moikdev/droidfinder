#!/usr/bin/env python3
"""Exercise optimized recent searches through Android touch and a real 0.36 upgrade.

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
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert args.serial in {'emulator-5602', 'emulator-5582'}, 'Use an assigned acceptance tablet.'
    device = AndroidDevice(args.serial, args.output)
    debug = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    demo = '/storage/emulated/0/Download/Findroid history demo ' + str(time.time_ns())[-10:]
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
        tab = {'id':'history-upgrade','history':[source],'index':0,'query':'Report','rules':dict(rules,name='')}
        values = [('string','tabs',json.dumps([tab])),('int','activeTab',0),('boolean','dualPane',False),
                  ('string','activePane','primary'),('boolean','panesReversed',False),('boolean','dark',False),
                  ('boolean','sidebarVisible',True),('boolean','tagsVisible',False),('boolean','gestureHintDismissed',True),
                  ('boolean','preview',False),('string','density','COMFORTABLE'),('string','view','LIST'),
                  ('string','smartFolders','[]'),('string','recentSearchAcceptanceSentinel',demo)]
        for kind,name,value in values: pref(prefs,kind,name,value)
        sidebar = {'version':1,'favorites':[{'id':'history-demo','path':source,'label':'Writing project'}],
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
        for _ in range(12):
            tree = device.dump(name+'-top')
            if device.nodes(tree,label): device.tap(device.one(tree,label)); return
            if device.nodes(tree,'App updates'): break
            panes = [n for n in tree.iter('node') if n.get('class')=='android.widget.ScrollView' and n.get('scrollable')=='true' and n.get('package')=='dev.findroid']
            assert len(panes)==1
            l,t,r,b=device.bounds(panes[0]); x=(l+r)//2
            device.shell('input','swipe',x,round(t+(b-t)*.25),x,round(t+(b-t)*.8),300)
        device.tap(device.one(device.scroll_to(name,label,x=.9),label))
    def dialog(tree):
        parents={child:parent for parent in tree.iter() for child in parent}
        node=device.one(tree,'Recent searches')
        while not (device.nodes(node,'Close') and device.nodes(node,'Clear history')):
            assert node in parents; node=parents[node]
        return node
    def recent(name):
        menu(name,'Recent searches…')
        return device.wait(name,lambda tree: bool(device.nodes(tree,'Recent searches')) and device.ready(tree,'Close'))
    def within(node,pane):
        l,t,r,b=device.bounds(node); pl,pt,pr,pb=device.bounds(pane)
        return pl<=l<r<=pr and pt<=t<b<=pb and b-t>=round(32*density)
    def history_control(name,label,root=None):
        for direction in (1,-1):
            previous=None
            for _ in range(24):
                tree=device.dump(name); surface=dialog(tree)
                panes=[n for n in surface.iter('node') if n.get('scrollable')=='true']
                assert len(panes)==1,[n.attrib for n in panes]
                pane=panes[0]; parents={child:parent for parent in pane.iter() for child in parent}
                scope=pane
                if root:
                    subtitles=[n for n in pane.iter('node') if n.get('text','').startswith(root+' · ')]
                    if len(subtitles)==1:
                        scope=subtitles[0]
                        while not device.nodes(scope,label) and scope in parents: scope=parents[scope]
                    else: scope=ET.Element('missing')
                matches=device.nodes(scope,label)
                if label=='Remember searches': matches=[n for n in matches if n.get('content-desc')==label and n.get('checkable')=='true']
                if len(matches)==1:
                    control=matches[0]
                    while control.get('clickable')!='true' and control in parents: control=parents[control]
                    if within(control,pane): return tree,control
                signature=tuple((n.get('text'),n.get('content-desc'),n.get('bounds')) for n in pane.iter('node'))
                if signature==previous: break
                previous=signature
                l,t,r,b=device.bounds(pane); x=(l+r)//2
                start,end=(.8,.3) if direction==1 else (.3,.8)
                device.shell('input','swipe',x,round(t+(b-t)*start),x,round(t+(b-t)*end),400)
        raise AssertionError('No fully visible history control: '+label+((' for '+root) if root else ''))
    def click_history(name,label,root=None):
        _,control=history_control(name,label,root); device.tap(control)
    def close_history(name):
        tree=device.dump(name); device.tap(device.one(dialog(tree),'Close'))
    def replace_field(name,description,text):
        tree=device.wait(name,lambda t: bool(device.nodes(t,description)))
        node=device.one(tree,description); old=node.get('text','')
        device.tap(node)
        device.shell('input','keyevent',123,*([67]*len(old)))
        if text: device.shell('input','text',text.replace(' ','%s'))
    def go(name,path):
        device.shell('input','keyevent',134)  # Existing F4 address shortcut.
        replace_field(name+'-address','Address bar',path)
        device.shell('input','keyevent',66); browser(name+'-arrived')
    def audit_history():
        install(debug)
        prefs=ET.fromstring(read('shared_prefs/finder.xml'))
        raw=prefs.find("./string[@name='recentSearchesV1']").text
        data=json.loads(raw)
        assert not any(token in raw for token in ('Synthetic project context','snippet','entries'))
        return prefs,data['searches']
    def optimized_resume(name): install(args.apk); launch(); browser(name)

    try:
        install(debug)
        assert device.shell('run-as','dev.findroid','test','!','-e','files/.setup-transaction',check=False).returncode==0
        saved=snapshot(); seed()
        device.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE','allow')
        device.shell('settings','put','system','font_scale','1.0')
        install(args.previous_apk,True); launch(); browser('before-upgrade','Report current.txt')
        assert version()==37,'Expected the public 0.36 baseline.'
        install(args.apk); launch(); browser('after-upgrade','Report current.txt')
        assert version()==38
        installed=device.shell('pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        assert hashlib.sha256(device.call('exec-out','cat',installed)).hexdigest()==report['apkSha256']
        report.update(actual036Upgrade=True,installedOptimizedBytes=True)
        recent('upgraded-history'); device.wait('initial-empty',lambda t: bool(device.nodes(t,'No recent searches')))
        device.capture('history-initial-empty'); close_history('close-initial')
        device.shell('input','keyevent',133)  # Explicit Enter on an unchanged restored query.
        device.wait('restored-query-field',lambda t: bool(device.nodes(t,'Search files')))
        device.shell('input','keyevent',66)
        recent('explicit-restored-submit')
        device.wait('restored-query-recorded',lambda t: any(n.get('text','').startswith('Writing project · ') for n in t.iter('node')))
        close_history('close-explicit-submit'); report['explicitRestoredQuerySubmit']=True
        menu('apply-existing-rules','Search filters…'); device.click('apply-filters','Apply filters')
        browser('finished-rules','Report current.txt'); recent('first-history')
        click_history('expand-filters','Show filters','Writing project')
        device.wait('full-criteria',lambda t: bool(device.nodes(t,'Excluded folder names: Scratch')))
        device.capture('recent-searches-tablet'); close_history('close-first-history')
        device.shell('input','keyevent',133); replace_field('type-prefix','Search files','Rep')
        device.wait('inline-suggestions',lambda t: bool(device.nodes(t,'Search here: Report')))
        device.capture('recent-search-suggestions')
        device.click('choose-suggestion','Search here: Report'); browser('reused-suggestion','Report current.txt')
        go('new-target',target); recent('history-here')
        click_history('reuse-here','Search here','Writing project'); browser('searched-here','Report here.txt')
        device.wait('exact-exclusion-retained',lambda t: bool(device.nodes(t,'Report nested.txt')))
        report.update(inlineTouchSuggestion=True,fullRulesSearchHere=True,literalExclusionsPreserved=True)
        recent('history-original'); click_history('reuse-original','Search original location','Writing project')
        browser('searched-original','Report current.txt'); report['originalLocationReuse']=True
        go('target-before-offline',target)
        device.shell('mv',source,source+' offline')
        recent('offline-history'); click_history('try-offline','Search original location','Writing project')
        device.wait('offline-error',lambda t: bool(device.nodes(t,"The original location isn't available. Reconnect storage, or choose Search here.")))
        device.capture('recent-search-unavailable')
        click_history('offline-use-here','Search here','Writing project'); browser('offline-kept-target','Report here.txt')
        device.shell('mv',source+' offline',source)
        report['unavailableOriginalPreservesTab']=True
        layouts=[]
        for width in (600,800,1280):
            for scale in ('1.0','1.6'):
                key=f'{width}dp-font{scale}'
                stop(); device.shell('wm','size',f'{round(width*density)}x{round(800*density)}')
                device.screen=(round(width*density),round(800*density))
                device.shell('settings','put','system','font_scale',scale); launch(); browser(key+'-browser')
                tree=recent(key+'-history')
                for label in ('Close','Clear history'):
                    node=device.one(dialog(tree),label); l,t,r,b=device.bounds(node)
                    assert 0<=l<r<=device.screen[0] and 0<=t<b<=device.screen[1] and b-t>=32*density
                click_history(key+'-expand','Show filters','Writing project')
                history_control(key+'-original','Search original location','Writing project')
                device.capture(key+'-history')
                history_control(key+'-remember','Remember searches'); device.capture(key+'-controls')
                close_history(key+'-close')
                layouts.append({'widthDp':width,'fontScale':scale,'footerVisible':True,'fullOriginalButtonVisible':True,'rememberToggleReachable':True})
        report['layouts']=layouts
        stop(); device.shell('wm','size',original_size); device.shell('settings','put','system','font_scale','1.0')
        device.screen=tuple(map(int,re.findall(r'(\d+)x(\d+)',device.shell('wm','size').decode())[-1]))
        prefs,searches=audit_history()
        assert len(searches)==2 and {s['root'] for s in searches}=={source,target}
        assert all(s['rules']==rules for s in searches),searches
        assert all(s['rules']['name']!='Rep' for s in searches)
        # This list was seeded and exercised entirely with disposable synthetic data.
        (args.output/'synthetic-history.json').write_text(json.dumps({'version':1,'searches':searches},indent=2)+'\n')
        report.update(exactFullCriteriaPersisted=True,noTypedPrefixPollution=True,noSavedResultBytes=True)
        optimized_resume('after-history-restart'); tree=recent('restarted-history')
        assert any(n.get('text','').startswith('Writing project · ') for n in tree.iter('node'))
        report['historySurvivesRestart']=True
        close_history('close-restarted-history')
        # Reuse must materialize a plain search rather than edit shared Smart Folder rules.
        device.shell('input','keyevent',133); replace_field('smart-query','Search files','Notes')
        device.shell('input','keyevent',66)
        menu('save-smart','Save search as Smart Folder…')
        tree=device.wait('smart-name',lambda t: bool(device.nodes(t,'Save Smart Folder')) and device.ready(t,'Save'))
        parents={child:parent for parent in tree.iter() for child in parent}
        scope=device.one(tree,'Save Smart Folder')
        while not any(n.get('class')=='android.widget.EditText' for n in scope.iter('node')):
            assert scope in parents; scope=parents[scope]
        fields=[n for n in scope.iter('node') if n.get('class')=='android.widget.EditText']
        assert len(fields)==1
        device.tap(fields[0]); device.shell('input','keyevent',123,*([67]*len(fields[0].get('text',''))))
        device.shell('input','text','Working%snotes')
        device.click('save-smart-name','Save')
        device.wait('saved-smart',lambda t: not device.nodes(t,'Save Smart Folder') and bool(device.nodes(t,'Working notes')))
        recent('history-from-smart'); click_history('reuse-smart-here','Search here','Writing project')
        browser('plain-search-after-smart','Report here.txt'); device.capture('recent-search-smart-copy')
        recent('history-to-pause')
        click_history('pause-recording','Remember searches'); close_history('close-paused')
        device.shell('input','keyevent',133); replace_field('paused-query','Search files','Notes')
        device.shell('input','keyevent',66); recent('paused-history')
        device.wait('paused-notice',lambda t: bool(device.nodes(t,'Recording is paused. Existing searches can still be reused or removed.')))
        tree=device.dump('clear-history'); device.tap(device.one(dialog(tree),'Clear history'))
        device.wait('cleared-history',lambda t: bool(device.nodes(t,'No recent searches')))
        device.capture('recent-search-paused-empty'); close_history('close-cleared')
        stop(); launch(); browser('cleared-restart'); recent('cleared-after-restart')
        device.wait('paused-after-restart',lambda t: bool(device.nodes(t,'No recent searches')) and bool(device.nodes(t,'Recording is paused. Existing searches can still be reused or removed.')))
        close_history('close-final-history')
        prefs,searches=audit_history()
        assert searches==[] and prefs.find("./boolean[@name='rememberRecentSearches']").get('value')=='false'
        smart=json.loads(prefs.find("./string[@name='smartFolders']").text)
        assert len(smart)==1 and smart[0]['name']=='Working notes' and smart[0]['root']==target
        assert smart[0]['rules']==dict(rules,name='Notes')
        (args.output/'synthetic-smart-folders.json').write_text(json.dumps(smart,indent=2)+'\n')
        report['smartFolderCopyIndependent']=True
        assert prefs.find("./string[@name='recentSearchAcceptanceSentinel']").text==demo
        assert json.loads(prefs.find("./string[@name='sidebarLayoutV1']").text)['favorites'][0]['path']==source
        for name in paths: assert device.call('exec-out','cat',demo+'/'+name)==payload
        report.update(pauseAndClearSurviveRestart=True,unrelatedPreferencePreserved=True,favoritePreserved=True,originalFileBytes=True)
    except Exception:
        try: device.capture('failure')
        except Exception: pass
        raise
    finally:
        stop(); device.shell('settings','put','system','font_scale',original_scale); device.shell('wm','size',original_size)
        device.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE',original_access)
        if saved is not None:
            install(debug); restore(); report['restoredOriginalSetup']=True
        device.shell('rm','-rf',demo)
        install(args.apk); device.close()
    (args.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__': main()
