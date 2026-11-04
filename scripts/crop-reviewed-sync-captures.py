#!/usr/bin/env python3
"""Crop explicit synthetic sync dialogs to their Surface, excluding the private browser.

Android's accessibility window includes the dimmed browser behind a Compose
Dialog. A window-bound crop is therefore insufficient. The sync dialogs share
one known Surface/list hierarchy; this checker verifies that hierarchy and
publishes only its Surface pixels. Actual image inspection remains separate.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--folder', required=True, type=Path)
parser.add_argument('--images', required=True, type=Path, help='Explicit JSON list of raw PNG basenames')
parser.add_argument('--output', required=True, type=Path, help='New crop-provenance JSON')
a = parser.parse_args()
assert not a.output.exists(), 'Preserve earlier crop receipts'
names = json.loads(a.images.read_text())
assert names and len(names) == len(set(names))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
rows = []
for name in names:
    assert Path(name).name == name and name.endswith('.png') and not name.endswith(('-dialog.png','-surface.png'))
    image_path = a.folder/name
    xml_path = a.folder/(Path(name).stem+'-capture.xml')
    tree = ET.parse(xml_path).getroot()
    windows = [w for w in tree.iter('window') if w.get('active') == 'true' and w.get('type') == 'TYPE_APPLICATION'
        and any(n.get('package') == 'dev.findroid' for n in w.iter('node'))]
    assert len(windows) == 1
    node = windows[0]
    parent = None
    while True:
        children = list(node)
        if len(children) != 1:
            break
        parent, node = node, children[0]
    assert parent is not None and parent.get('class') == 'android.view.View' and node.get('class') == 'android.view.View'
    assert len(children) > 1, 'Expected the sync LazyColumn as the first branching content container'
    assert any(n.get('text') in ('Close','Continue in background','Confirm selected changes','Synchronization records') or n.get('text','').startswith(('Replace 1 destination item; preserve','Move 1 destination-only item to Trash.')) for n in node.iter('node')), 'The explicit capture needs a named synchronization control or heading'
    bounds = list(map(int,re.findall(r'-?\d+',parent.get('bounds'))))
    list_bounds = list(map(int,re.findall(r'-?\d+',node.get('bounds'))))
    image = Image.open(image_path).convert('RGB')
    l,t,r,b = bounds
    assert 0 <= l < r <= image.width and 0 <= t < b <= image.height
    assert l < list_bounds[0] < list_bounds[2] < r and t == list_bounds[1] and b == list_bounds[3], 'Expected the Surface with horizontal list padding'
    # The dialog Surface must exclude the backdrop on every side.
    assert 0 < l and r < image.width and 0 < t and b < image.height
    output = a.folder/(Path(name).stem+'-surface.png')
    assert not output.exists()
    image.crop(tuple(bounds)).save(output)
    rows.append(dict(path=str(output),sha256=sha(output),surfaceBoundsPx=bounds,
        privateRawCaptureSha256=sha(image_path),privateRawHierarchySha256=sha(xml_path),
        browserBackdropExcluded=True))
report = dict(explicitSyntheticImages=len(rows),surfaceHierarchyVerified=True,actualImageInspectionRequired=True,
    checkerSha256=sha(Path(__file__)),images=rows)
a.output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(dict(croppedImages=len(rows),browserBackdropExcluded=True)))
