"""Bind inspected actual Android captures and exact glyph subsets to final candidate receipts."""
from pathlib import Path
import hashlib,json
from PIL import Image

ROOT=Path(__file__).resolve().parents[2]
PROOF=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text())
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
inspected=read(PROOF/'candidate-3-native-inspected-paths.json')+read(PROOF/'candidate-3-optimized-inspected-paths.json')
assert len(inspected)==len(set(inspected))==38
known={sha(ROOT/name):name for name in inspected}
images={}
for name in read(PROOF/'candidate-3-native-inspected-paths.json'):
 p=ROOT/name;images[name]=dict(path=name,sha256=sha(p),inspected=True,basis='Actual native review UI inspected directly.')
for api in ('api30','api36'):
 folder=PROOF/('candidate-3-'+api+'-optimized');result=read(folder/'result.json')
 assert result['acceptanceComplete'] and result['restorationComplete'] and len(result['layouts'])==12
 captures={row['path']:row for row in result['captures']}
 for row in result['captures']:
  p=folder/row['path'];digest=sha(p);assert digest==row['sha256']
  name=str(p.relative_to(ROOT))
  if digest in known:
   images[name]=dict(path=name,sha256=digest,inspected=True,basis='Actual optimized Android UI inspected directly or in a labelled source-image contact sheet.',inspectedSource=known[digest])
  else:
   assert p.name.endswith('-glyph.png')
   parent_name=p.name.removesuffix('-glyph.png')+'-result-preview.png';parent=folder/parent_name
   assert sha(parent) in known
   box=row['surfaceBoundsPx'];origin=captures[parent_name]['surfaceBoundsPx'];local=[box[0]-origin[0],box[1]-origin[1],box[2]-origin[0],box[3]-origin[1]]
   with Image.open(parent) as frame,Image.open(p) as glyph:
    crop=frame.crop(tuple(local));assert crop.size==glyph.size and crop.convert('RGB').tobytes()==glyph.convert('RGB').tobytes()
   images[name]=dict(path=name,sha256=digest,inspected=True,basis='Exact pixel subset of an inspected actual Android frame.',inspectedParent=str(parent.relative_to(ROOT)),cropBoundsWithinParent=local)
assert len(images)==62
docs=read(PROOF/'docs-screenshot-provenance.json')
assert sha(ROOT/docs['destination'])==sha(ROOT/docs['source'])==docs['sha256'] and docs['sha256'] in known
report=dict(applicationSourceCommit=read(PROOF/'candidate-build-3.json')['sourceCommit'],
 images=sorted(images.values(),key=lambda row:row['path']),nativeAndOptimizedCapturePaths=62,
 actualUiFramesInspected=38,exactGlyphSubsets=24,headerContextMayScrollOutOfViewAtLargeText=True,
 thumbnailNamesMayEllipsize=True,currentNamesAndPhotosActuallyVisible=True,documentationCopyVerified=True,
 scope='Synthetic photo/review/tag UI only. Private native originals, full diagnostics and source document contents are excluded.')
p=PROOF/'local-image-review.json';assert not p.exists();p.write_text(json.dumps(report,indent=2)+'\n')
print('62 native/optimized capture paths bound: 38 inspected actual frames and 24 exact glyph subsets.')
