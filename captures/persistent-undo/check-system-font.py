"""Measure actual Close text and raster glyph enlargement in the accepted system-font matrix."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops

proof = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument("--api", required=True, choices=("api30", "api36"))
args = parser.parse_args()
folder = proof / (args.api + "-optimized")
verification = json.loads((folder / "verification.json").read_text())
assert verification["success"] and len(verification["layoutChecks"]) == 18

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

groups = []
for width, height in ((1280, 800), (800, 1280), (600, 800)):
    for theme in ("light", "dark"):
        samples = []
        for scale in (1.0, 1.5, 2.0):
            name = f"review-{width}x{height}-font{scale}-{theme}-Close"
            image_path = folder / (name + ".png")
            xml = folder / (name + "-capture.xml")
            tree = ET.parse(xml).getroot()
            windows = [w for w in tree.iter("window") if w.get("type") == "TYPE_APPLICATION" and
                w.get("active") == w.get("focused") == "true"]
            assert len(windows) == 1
            labels = [n for n in windows[0].iter("node") if n.get("package") == "dev.findroid" and
                n.get("class") == "android.widget.TextView" and n.get("text") == "Close" and n.get("clickable") == "false"]
            assert len(labels) == 1
            bounds = list(map(int, re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", labels[0].get("bounds")).groups()))
            image = Image.open(image_path).convert("RGB")
            x1, y1, x2, y2 = bounds
            assert 0 <= x1 < x2 <= image.width and 0 <= y1 < y2 <= image.height
            crop = image.crop(tuple(bounds))
            corners = [crop.getpixel(p) for p in ((0, 0), (crop.width - 1, 0), (0, crop.height - 1), (crop.width - 1, crop.height - 1))]
            background = tuple(sorted(c[i] for c in corners)[len(corners) // 2] for i in range(3))
            difference = ImageChops.difference(crop, Image.new("RGB", crop.size, background))
            channels = difference.split()
            mask = ImageChops.lighter(ImageChops.lighter(channels[0], channels[1]), channels[2]).point(lambda value: 255 if value > 24 else 0)
            glyph = mask.getbbox()
            assert glyph is not None
            samples.append(dict(fontScale=scale, image=str(image_path.relative_to(proof)), sha256=sha(image_path),
                captureXml=str(xml.relative_to(proof)), captureXmlSha256=sha(xml), textBounds=bounds,
                textWidth=x2-x1, textHeight=y2-y1, rasterGlyphBounds=list(glyph), rasterGlyphWidth=glyph[2]-glyph[0], rasterGlyphHeight=glyph[3]-glyph[1]))
        for metric in ("textWidth", "textHeight", "rasterGlyphWidth", "rasterGlyphHeight"):
            assert samples[0][metric] < samples[1][metric] < samples[2][metric], (width, height, theme, metric, samples)
            assert samples[2][metric] / samples[0][metric] >= 1.2
        groups.append(dict(widthDp=width, heightDp=height, theme=theme, samples=samples))
report = dict(serial=verification["serial"], checkerSha256=sha(Path(__file__)), actualRasterGlyphsLarger=True,
    evidence="Actual named TextView bounds and thresholded raster glyph dimensions increase at system font scales 1.0, 1.5 and 2.0 for all six size/theme groups. No native filename or composition override is used as font evidence.", groups=groups)
output = proof / (args.api + "-system-font.json")
output.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(dict(api=args.api, groups=len(groups), actualRasterGlyphsLarger=True), indent=2))
