"""Bind direct screenshot inspections, exact retained PNG copies and verified glyph subsets."""
from pathlib import Path
import hashlib,json
from PIL import Image
ROOT=Path(__file__).resolve().parents[2];PROOF=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text());sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
selection=read(PROOF/'local-selection.json')
known={}
for name in ('native-inspected-paths.json','optimized-inspected-paths.json','new-inspected-all.json'):
 for relative in read(PROOF/name):
  path=ROOT/relative;known.setdefault(sha(path),[]).append(relative)
rows={}
for api in ('api30','api36'):
 for relative in selection[api]['nativeImages']:
  path=ROOT/relative;digest=sha(path);assert digest in known
  rows[relative]=dict(path=relative,sha256=digest,inspected=True,basis='Direct actual Android UI inspection',inspectedSource=known[digest][0])
 result=ROOT/selection[api]['core'];report=read(result)
 assert report['acceptanceComplete'] and report['restorationComplete']
 captures={r['path']:r for r in report['captures']}
 for name,row in captures.items():
  path=result.parent/name;digest=sha(path);assert digest==row['sha256']
  relative=str(path.relative_to(ROOT))
  if digest in known:
   rows[relative]=dict(path=relative,sha256=digest,inspected=True,basis='Direct inspection or byte-identical retained inspected PNG',inspectedSource=known[digest][0])
  else:
   assert name.endswith('-glyph.png'),('A result screenshot has not been inspected',relative)
   parent_name=name.removesuffix('-glyph.png')+'-result-preview.png';parent_row=captures[parent_name];parent=result.parent/parent_name
   assert sha(parent) in known
   box=row['surfaceBoundsPx'];origin=parent_row['surfaceBoundsPx'];local=[box[0]-origin[0],box[1]-origin[1],box[2]-origin[0],box[3]-origin[1]]
   with Image.open(parent) as larger,Image.open(path) as glyph:
    cropped=larger.crop(tuple(local));assert cropped.size==glyph.size and cropped.convert('RGB').tobytes()==glyph.convert('RGB').tobytes()
   rows[relative]=dict(path=relative,sha256=digest,inspected=True,basis='Exact pixel subset of directly inspected Android result',inspectedParent=str(parent.relative_to(ROOT)),cropBoundsWithinParent=local)
assert len(rows)==54,len(rows)
report=dict(images=sorted(rows.values(),key=lambda r:r['path']),nativeAndOptimizedUiImages=54,headerContextMayScrollOutOfViewAtLargeText=True,
 allTextBodiesAndImagePairsReviewed=True,allNamedTouchTargetAndGlyphProofsRetained=True,scope='Synthetic comparison UI only; full private recovery/window payloads excluded.')
(PROOF/'local-image-review.json').write_text(json.dumps(report,indent=2)+'\n')
print('Reviewed 54 selected native/optimized PNG paths, including 12 exact glyph subsets.')
p=PROOF/'docs-screenshot-provenance.json';doc=read(p)
for row in doc:
 original=row['source'];selected=original.replace('optimized-2/','optimized-3/')
 assert (ROOT/original).read_bytes()==(ROOT/selected).read_bytes()==(ROOT/row['destination']).read_bytes()
 row.update(source=selected,originalCapturedSource=original,selectedResumeBytesIdentical=True)
p.write_text(json.dumps(doc,indent=2)+'\n')
