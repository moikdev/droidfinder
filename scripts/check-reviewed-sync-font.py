#!/usr/bin/env python3
"""Measure raster glyph growth in the actual Android system-font sync matrix.

Reads accepted local captures only. Publishes the Close label crop and measured
bounds, never the complete hierarchy/background of an original private workspace.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--folder', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
a = parser.parse_args()
assert not a.output.exists(), 'Preserve earlier font receipts'
result = json.loads((a.folder/'result.json').read_text())
assert result['acceptanceComplete'] and result['restorationComplete'] and len(result['layouts']) == 12
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
groups = []
for width, height in ((1280,800), (800,1280), (600,800)):
    for theme in ('light','dark'):
        samples = []
        for font in (1.0,2.0):
            name = f'layout-{width}x{height}-font{font}-{theme}-empty-records'
            image_path = a.folder/(name+'.png')
            xml_path = a.folder/(name+'-capture.xml')
            tree = ET.parse(xml_path).getroot()
            windows = [w for w in tree.iter('window') if w.get('active') == 'true' and w.get('type') == 'TYPE_APPLICATION']
            assert len(windows) == 1
            labels = [n for n in windows[0].iter('node') if n.get('package') == 'dev.findroid'
                and n.get('class') == 'android.widget.TextView' and n.get('text') == 'Close' and n.get('clickable') == 'false']
            assert len(labels) == 1
            bounds = list(map(int,re.findall(r'-?\d+',labels[0].get('bounds'))))
            assert len(bounds) == 4
            image = Image.open(image_path).convert('RGB')
            l,t,r,b = bounds
            assert 0 <= l < r <= image.width and 0 <= t < b <= image.height
            crop = image.crop(tuple(bounds))
            public_crop = a.folder/(name+'-close-glyph.png')
            assert not public_crop.exists()
            crop.save(public_crop)
            corners = [crop.getpixel(p) for p in ((0,0),(crop.width-1,0),(0,crop.height-1),(crop.width-1,crop.height-1))]
            background = tuple(sorted(c[i] for c in corners)[2] for i in range(3))
            channels = ImageChops.difference(crop,Image.new('RGB',crop.size,background)).split()
            mask = ImageChops.lighter(ImageChops.lighter(channels[0],channels[1]),channels[2]).point(lambda value:255 if value>24 else 0)
            glyph = mask.getbbox()
            assert glyph is not None
            samples.append(dict(systemFontScale=font,image=public_crop.name,sha256=sha(public_crop),textBoundsPx=bounds,
                textWidth=r-l,textHeight=b-t,rasterGlyphBounds=list(glyph),rasterGlyphWidth=glyph[2]-glyph[0],rasterGlyphHeight=glyph[3]-glyph[1],
                privateRawCaptureSha256=sha(image_path),privateRawHierarchySha256=sha(xml_path)))
        for key in ('textWidth','textHeight','rasterGlyphWidth','rasterGlyphHeight'):
            assert samples[1][key] > samples[0][key] and samples[1][key]/samples[0][key] >= 1.2, (width,height,theme,key)
        groups.append(dict(widthDp=width,heightDp=height,theme=theme,samples=samples))
report = dict(serial=result['serial'],checkerSha256=sha(Path(__file__)),actualRasterGlyphsLarger=True,
    evidence='The native Close TextView and thresholded actual glyph pixels grow at real Android font settings 1.0 and 2.0 in all six tablet size/theme groups.',groups=groups)
a.output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(dict(serial=result['serial'],groups=len(groups),actualRasterGlyphsLarger=True)))
