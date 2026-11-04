"""Build private inspection sheets and check exact glyph subsets of completed layout captures."""
import argparse,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[2];proof=ROOT/'captures/office-previews'
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--api',choices=('api30','api36'),required=True);a=p.parse_args()
folder=proof/f'candidate-3-{a.api}-optimized-layouts-1';result=json.loads((folder/'result.json').read_text())
groups={}
for row in result['layouts']:groups.setdefault((row['widthDp'],row['heightDp'],row['dark']),{})[row['fontScale']]=row
font=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',20)
for (width,height,dark),samples in groups.items():
 if set(samples)!={1.0,2.0}:continue
 theme='dark' if dark else 'light';sheet=Image.new('RGB',(1920,1060),'#ddd');draw=ImageDraw.Draw(sheet)
 for col,scale in enumerate((1.0,2.0)):
  row=samples[scale];name=f'layout-{width}x{height}-font{scale}-{theme}'
  capture=next(c for c in result['captures'] if c['path']==name+'-result-preview.png');image=Image.open(folder/capture['path']).convert('RGB')
  glyph=row['systemFont'];box=glyph['textBoundsPx'];origin=capture['surfaceBoundsPx'];relative=(box[0]-origin[0],box[1]-origin[1],box[2]-origin[0],box[3]-origin[1])
  assert image.crop(relative).tobytes()==Image.open(folder/glyph['image']).convert('RGB').tobytes()
  bottom=min(image.height,relative[3]+350);shown=image.crop((0,0,image.width,bottom));shown.thumbnail((950,960))
  sheet.paste(shown,(col*960,50));draw.text((col*960+8,8),f'{a.api} {width}x{height} {theme} font {scale} / '+row['previewDocument'],font=font,fill='black')
 target=proof/f'PRIVATE-{a.api}-layout-review-{width}-{theme}.png'
 if target.exists():
  assert Image.open(target).convert('RGB').tobytes()==sheet.tobytes()
 else:sheet.save(target)
 print(target.relative_to(ROOT))
