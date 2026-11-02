#!/usr/bin/env python3
"""Verify optimized search UI, a real 0.35 upgrade, saved rules, and tablet text scaling.

Uses disposable synthetic files and explicit native touch. Original setup bytes stay in memory,
are restored exactly, and never enter the evidence directory. No uninstall or data clearing.
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
    assert args.serial in {'emulator-5602', 'emulator-5582'}, 'Use an assigned search acceptance tablet.'
    device = AndroidDevice(args.serial, args.output)
    debug = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    stamp = str(time.time_ns())[-10:]
    demo_root = '/storage/emulated/0/Download/Findroid search demo ' + stamp
    base = demo_root + '/Writing project'
    payload = b'Synthetic project context around needle in this report.\n' * 45
    files = ['Report current.txt', 'Cache/Report cached.txt', 'Work/Cache/Report retained.txt']
    saved = None
    original_scale = device.shell('settings', 'get', 'system', 'font_scale').decode().strip()
    size = device.shell('wm', 'size').decode()
    match = re.search(r'Override size: (\d+x\d+)', size)
    original_size = match.group(1) if match else 'reset'
    appops = device.shell('appops', 'get', 'dev.findroid', 'MANAGE_EXTERNAL_STORAGE').decode()
    match = re.search(r'MANAGE_EXTERNAL_STORAGE: (\w+)', appops)
    original_access = match.group(1) if match else 'default'
    density = int(re.findall(r'\d+', device.shell('wm', 'density').decode())[-1])/160
    report = {'serial': args.serial, 'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest()}

    def stop(): device.shell('am', 'force-stop', 'dev.findroid')
    def install(path, downgrade=False):
        stop(); device.call('install', '-r', *(['-d'] if downgrade else []), path)
    def launch():
        device.shell('am', 'start', '-W', '-f', '0x10008000', '--windowingMode', 1,
                     '-n', 'dev.findroid/.MainActivity', '--es', 'finderWindowId', 'main')
    def read(path, optional=False):
        result = device.call('exec-out', 'run-as', 'dev.findroid', 'cat', path, check=False)
        if optional and result.returncode: return None
        assert result.returncode == 0, 'Cannot read setup: ' + path
        return result.stdout
    def write(path, data):
        device.call('shell', 'run-as dev.findroid sh -c ' + shlex.quote('cat > ' + shlex.quote(path)), data=data)
    def snapshot():
        paths = device.shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        paths += ['files/'+name+suffix for name in FILES for suffix in ('', '.bak', '.new')]
        return {path: read(path, optional=True) for path in paths}
    def restore():
        current = device.shell('run-as', 'dev.findroid', 'find', 'shared_prefs', '-maxdepth', 1, '-type', 'f').decode().splitlines()
        for path in set(current)-set(saved): device.shell('run-as', 'dev.findroid', 'rm', '-f', path)
        for path, data in saved.items():
            if data is None: device.shell('run-as', 'dev.findroid', 'rm', '-f', path)
            else: write(path, data)
    def pref(root, kind, name, value):
        for old in list(root):
            if old.get('name') == name: root.remove(old)
        node = ET.SubElement(root, kind, name=name)
        if kind == 'string': node.text = value
        elif kind == 'set':
            for item in value: ET.SubElement(node, 'string').text = item
        else: node.set('value', str(value).lower())
    def seed():
        # Public captures use synthetic/default organization only. Exact original files
        # remain in the in-memory snapshot and are restored in finally.
        for name in FILES:
            for suffix in ('', '.bak', '.new'):
                device.shell('run-as','dev.findroid','rm','-f','files/'+name+suffix)
        prefs = ET.fromstring(saved['shared_prefs/finder.xml'])
        # All of these rules existed in 0.35. Calendar/size enums and exact exclusions are
        # chosen through the new UI after upgrading, rather than invented in old metadata.
        rules = {'terms':['Report'], 'kind':'Text document', 'days':1, 'min':1025, 'content':'needle'}
        tab = {'id':'search-upgrade', 'history':[base], 'index':0, 'query':'', 'rules':rules}
        values = [('string','tabs',json.dumps([tab])), ('int','activeTab',0), ('boolean','dualPane',False),
                  ('string','activePane','primary'), ('boolean','panesReversed',False), ('boolean','dark',False),
                  ('boolean','sidebarVisible',True), ('boolean','tagsVisible',False), ('boolean','gestureHintDismissed',True),
                  ('boolean','preview',False), ('string','density','COMFORTABLE'), ('string','view','LIST'),
                  ('string','smartFolders','[]'), ('string','searchAcceptanceSentinel',stamp)]
        for kind,name,value in values: pref(prefs,kind,name,value)
        sidebar = {'version':1,'favorites':[{'id':'search-demo','path':base,'label':'Writing project'}],
                   'groups':[], 'builtins':['RECENTS','WORKSPACE','DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC'],
                   'hidden':['DOCUMENTS','DOWNLOADS','PICTURES','MOVIES','MUSIC']}
        pref(prefs,'string','sidebarLayoutV1',json.dumps(sidebar)); pref(prefs,'set','favorites',[base])
        write('shared_prefs/finder.xml',ET.tostring(prefs,encoding='utf-8',xml_declaration=True))
        write('shared_prefs/locations.xml',b'<?xml version="1.0" encoding="utf-8"?><map><string name="roots">[]</string></map>')
        for name in files:
            path = base+'/'+name
            device.shell('mkdir','-p',path.rsplit('/',1)[0])
            device.call('shell','cat > '+shlex.quote(path),data=payload)
    def version():
        return int(re.search(r'versionCode=(\d+)',device.shell('dumpsys','package','dev.findroid').decode()).group(1))
    def browser(name):
        return device.wait(name, lambda tree: device.ready(tree,'Actions') and bool(device.nodes(tree,'Report current.txt')))
    def menu(name, label):
        device.click(name+'-open','Actions')
        for _ in range(10):
            tree = device.dump(name+'-top')
            if device.nodes(tree,label): device.tap(device.one(tree,label)); return
            if device.nodes(tree,'App updates'): break
            panes = [node for node in tree.iter('node') if node.get('class')=='android.widget.ScrollView' and node.get('scrollable')=='true' and node.get('package')=='dev.findroid']
            assert len(panes)==1
            l,t,r,b = device.bounds(panes[0]); x=(l+r)//2
            device.shell('input','swipe',x,round(t+(b-t)*.25),x,round(t+(b-t)*.8),300)
        device.tap(device.one(device.scroll_to(name,label,x=.9),label))
    def filters(name):
        menu(name,'Search filters…')
        return device.wait(name+'-criteria',lambda tree: device.ready(tree,'Apply filters') and bool(device.nodes(tree,'Search criteria')))
    def choose_view(name,mode):
        tree=browser(name+'-before')
        device.tap(device.one(tree,'Change view'))
        label=mode+' view'
        tree=device.wait(name+'-menu',lambda tree: any(node.get('content-desc')==label for node in tree.iter('node')))
        # Compose exposes a labeled semantics node and its child text. Target the
        # one menu semantics node rather than treating both as distinct choices.
        choices=[node for node in tree.iter('node') if node.get('content-desc')==label]
        assert len(choices)==1, (label,len(choices))
        device.tap(choices[0])
    def criteria_scroll_to(name,label):
        # Use the form bounds and keep the named control away from clipped edges.
        # Accessibility may expose a label even when only a few pixels are visible.
        for direction in (1,-1):
            previous=None
            for _ in range(20):
                tree=device.dump(name)
                panes=[node for node in tree.iter('node') if node.get('class')=='android.widget.ScrollView' and node.get('package')=='dev.findroid' and node.get('scrollable')=='true']
                assert len(panes)==1, [node.attrib for node in panes]
                pane=panes[0]; l,t,r,b=device.bounds(pane)
                parents={child:parent for parent in pane.iter() for child in parent}
                candidates=device.nodes(pane,label)
                control=None
                if len(candidates)==1:
                    node=candidates[0]; control=node
                    while control.get('clickable')!='true' and control in parents:
                        control=parents[control]
                    nl,nt,nr,nb=device.bounds(node)
                    cl,ct,cr,cb=device.bounds(control)
                    if t+round(50*density)<=nt<nb<=b-round(50*density) and cb-ct>=round(40*density):
                        return tree
                signature=tuple((node.get('text'),node.get('content-desc'),node.get('bounds')) for node in pane.iter('node'))
                if signature==previous:
                    # The last control can sit at the form's end. Accept its full
                    # touch target there, while rejecting a clipped date chip.
                    if control is not None and t<=ct<cb<=b and cb-ct>=round(40*density):
                        return tree
                    break
                previous=signature
                move=direction
                if control is not None:
                    move=1 if (nt+nb)//2>(t+b)//2 else -1
                x=r-max(4,round(6*density))
                start,end=(.78,.48) if move==1 else (.22,.52)
                device.shell('input','swipe',x,round(t+(b-t)*start),x,round(t+(b-t)*end),450)
        raise AssertionError('Could not fully reveal the criteria control '+label)
    def close_search():
        tree = device.dump('close-search-if-open')
        if device.nodes(tree,'Close search'): device.tap(device.one(tree,'Close search'))
    def assert_inside(tree,label):
        node = device.one(tree,label); l,t,r,b = device.bounds(node)
        assert node.get('enabled')=='true' and 0 <= l < r <= device.screen[0] and 0 <= t < b <= device.screen[1], (label,device.bounds(node))
    def rules_from_prefs():
        prefs = ET.fromstring(read('shared_prefs/finder.xml'))
        tabs = json.loads(prefs.find("./string[@name='tabs']").text)
        return prefs,tabs[0]['rules']

    try:
        install(debug)
        assert device.shell('run-as','dev.findroid','test','!','-e','files/.setup-transaction',check=False).returncode==0
        saved = snapshot(); seed()
        device.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE','allow')
        device.shell('settings','put','system','font_scale','1.0')
        install(args.previous_apk,downgrade=True); launch(); browser('before-upgrade')
        assert version()==36, 'Expected the public 0.35 baseline'
        install(args.apk); launch(); browser('after-upgrade')
        assert version()==37
        installed = device.shell('pm','path','dev.findroid').decode().strip().splitlines()[0].removeprefix('package:')
        assert hashlib.sha256(device.call('exec-out','cat',installed)).hexdigest()==report['apkSha256']
        device.capture('search-matches-tablet')
        report.update(actual035Upgrade=True,installedOptimizedBytes=True)
        # The old rolling day and non-multiple byte bound remain intact on opening the editor.
        filters('upgraded-filters')
        legacy=criteria_scroll_to('legacy-day','Past 24 hours')
        parents={child:parent for parent in legacy.iter() for child in parent}
        assert parents[device.one(legacy,'Past 24 hours')].get('checked')=='true'
        assert_inside(criteria_scroll_to('legacy-size','1.0009765625'),'1.0009765625')
        device.tap(device.one(criteria_scroll_to('calendar-section','This week'),'This week'))
        device.capture('calendar-criteria-tablet')
        device.tap(device.one(criteria_scroll_to('exact-section','Exclude a folder…'),'Exclude a folder…'))
        tree = device.wait('exclusion-picker',lambda tree: bool(device.nodes(tree,'Choose a folder to exclude')) and bool(device.nodes(tree,'Cache')))
        device.tap(device.scoped(tree,'Choose a folder to exclude','Cache'))
        device.wait('excluded-folder-chosen',lambda tree: device.ready(tree,'Exclude this folder') and not device.nodes(tree,'Cache'))
        device.click('exclude-confirm','Exclude this folder')
        device.wait('criteria-draft',lambda tree: device.ready(tree,'Apply filters'))
        device.capture('exact-exclusion-criteria-tablet')
        device.click('apply-exact','Apply filters')
        device.wait('excluded-results',lambda tree: bool(device.nodes(tree,'Report current.txt')) and bool(device.nodes(tree,'Report retained.txt')) and not device.nodes(tree,'Report cached.txt'))
        device.capture('exact-exclusion-results-tablet')
        close_search()
        for mode in ('Icons','List','Columns','Gallery'):
            choose_view('choose-'+mode.lower(),mode)
            browser('view-'+mode.lower()); device.capture('matches-'+mode.lower())
        # Return to a list for useful enlarged-text result screenshots.
        choose_view('choose-list-again','List')
        menu('save-search','Save search as Smart Folder…')
        device.wait('save-search-name',lambda tree: device.ready(tree,'Save') and bool(device.nodes(tree,'Save Smart Folder')))
        device.click('save-search-confirm','Save'); browser('smart-folder-results')
        stop(); launch(); browser('saved-search-reopened')
        report.update(legacyRollingDayPreserved=True,exactByteBoundPreserved=True,realExactFolderPicker=True,
                      sameNamedSiblingPreserved=True,allFourViews=True,savedSearchSurvivesRestart=True)
        layouts=[]
        for width,height in ((1280,800),(800,1000),(600,800)):
            for scale in (1.0,1.6):
                stop(); device.shell('wm','size',f'{round(width*density)}x{round(height*density)}')
                device.screen=(round(width*density),round(height*density))
                device.shell('settings','put','system','font_scale',scale); launch(); tree=browser('layout-browser')
                key=f'layout-{width}-{str(scale).replace(".","_")}'
                assert_inside(tree,'Actions'); device.capture(key+'-results')
                filters(key+'-filters')
                tree=criteria_scroll_to(key+'-calendar','This week')
                assert_inside(tree,'Apply filters'); assert_inside(tree,'Cancel')
                device.capture(key+'-criteria')
                tree=criteria_scroll_to(key+'-excluded','Remove exclusion')
                assert_inside(tree,'Remove exclusion'); assert_inside(tree,'Apply filters')
                device.capture(key+'-exclusion')
                device.click(key+'-cancel','Cancel')
                layouts.append({'widthDp':width,'heightDp':height,'fontScale':scale,
                                'resultReasonsReachable':True,'calendarCriteriaReachable':True,'exactExclusionControlsReachable':True})
        report['tabletLayouts']=layouts
        for name in files: assert device.call('exec-out','cat',base+'/'+name)==payload
        install(debug)
        prefs,_=rules_from_prefs()
        assert prefs.find("./string[@name='searchAcceptanceSentinel']").text==stamp
        searches=json.loads(prefs.find("./string[@name='smartFolders']").text)
        rules=searches[0]['rules']
        assert rules['date']=='THIS_WEEK' and rules['days']==0 and rules['min']==1025
        assert rules['excludedLocations']==[{'path':base+'/Cache','folder':True}]
        assert 'snippet' not in rules and 'Synthetic project context' not in json.dumps(searches)
        assert json.loads(prefs.find("./string[@name='sidebarLayoutV1']").text)['favorites'][0]['path']==base
        report.update(originalFileBytes=True,unrelatedPreferencePreserved=True,favoritePreserved=True,
                      savedSemanticRulesVerified=True,snippetsAbsentFromSavedRules=True)
        install(args.apk); launch(); browser('before-natural-size')
        filters('natural-size')
        note='1 KB = 1,024 bytes. Presets replace custom limits; imported combined limits must all match.'
        device.tap(device.one(criteria_scroll_to('size-choice','Over 1 GB'),'Over 1 GB'))
        device.tap(device.one(criteria_scroll_to('type-choice','Images'),'Images'))
        device.click('apply-natural-size','Apply filters')
        device.wait('natural-empty',lambda tree: device.ready(tree,'Actions') and not device.nodes(tree,'Report current.txt') and not device.nodes(tree,'Apply filters'))
        device.capture('natural-size-and-type')
        install(debug)
        prefs,_=rules_from_prefs()
        rules=json.loads(prefs.find("./string[@name='smartFolders']").text)[0]['rules']
        assert rules['size']=='OVER_1_GB' and rules['kind']=='Image' and rules['min']==0 and rules['max']==0
        report['naturalSizeAndTypeChoices']=True
    except Exception:
        try: device.capture('failure')
        except Exception: pass
        raise
    finally:
        stop(); device.shell('settings','put','system','font_scale',original_scale); device.shell('wm','size',original_size)
        device.shell('appops','set','dev.findroid','MANAGE_EXTERNAL_STORAGE',original_access)
        if saved is not None:
            install(debug); restore()
            for path,data in saved.items(): assert read(path,optional=True)==data
            report['restoredOriginalSetup']=True
        device.shell('rm','-rf',demo_root)
        install(args.apk); device.close()
    (args.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__': main()
