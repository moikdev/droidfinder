"""Generate synthetic OOXML fixtures using three independent document libraries.

Run with pinned python-docx 1.2.0, openpyxl 3.1.5 and python-pptx 1.0.2.
These are library-generated packages, not claims of Microsoft application output.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import tempfile
import zipfile
import docx
import openpyxl
import pptx
from pptx.util import Inches

ROOT=Path(__file__).resolve().parents[2]
output=ROOT/'app/src/androidTest/assets/office'
output.mkdir(parents=True,exist_ok=True)
fixed=datetime(2026,11,4,0,0,0,tzinfo=timezone.utc)

with tempfile.TemporaryDirectory(prefix='findroid-office-fixtures-') as temporary:
    folder=Path(temporary)
    word=docx.Document()
    word.core_properties.author='Findroid QA';word.core_properties.last_modified_by='Findroid QA'
    word.core_properties.created=fixed;word.core_properties.modified=fixed
    word.add_heading('Ocean light',0)
    word.add_paragraph('Far shore 日本語 🌊')
    table=word.add_table(rows=1,cols=2)
    table.cell(0,0).text='Morning';table.cell(0,1).text='Sea'
    word.save(folder/'LibraryNotes.docx')
    workbook=openpyxl.Workbook()
    workbook.properties.creator='Findroid QA';workbook.properties.lastModifiedBy='Findroid QA'
    workbook.properties.created=fixed.replace(tzinfo=None);workbook.properties.modified=fixed.replace(tzinfo=None)
    sheet=workbook.active;sheet.title='Field notes'
    sheet.append(['Ocean light','Far shore 日本語 🌊']);sheet.append([2,'=A2+1'])
    sheet['D4']=True
    workbook.create_sheet('Morning')['A1']='Morning sea'
    workbook.save(folder/'LibraryBudget.xlsx')
    slides=pptx.Presentation()
    slides.core_properties.author='Findroid QA';slides.core_properties.last_modified_by='Findroid QA'
    slides.core_properties.created=fixed;slides.core_properties.modified=fixed
    for title in ('Ocean light','Far shore 日本語 🌊'):
        slide=slides.slides.add_slide(slides.slide_layouts[6])
        slide.shapes.add_textbox(Inches(1),Inches(1),Inches(7),Inches(2)).text_frame.text=title
    # Slide file names and presentation order deliberately differ.
    ordered=slides.slides._sldIdLst
    ordered.insert(0,ordered[1])
    slides.save(folder/'LibraryJourney.pptx')
    rows=[]
    for source in sorted(folder.iterdir()):
        target=output/source.name
        with zipfile.ZipFile(source) as original,zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as packed:
            for name in sorted(original.namelist()):
                info=zipfile.ZipInfo(name,(2026,11,4,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                packed.writestr(info,original.read(name))
        rows.append(dict(name=target.name,bytes=target.stat().st_size,sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
receipt=dict(producers={'python-docx':docx.__version__,'openpyxl':openpyxl.__version__,'python-pptx':pptx.__version__},
    syntheticContentOnly=True,zipTimestampsNormalized=True,notMicrosoftApplicationOutput=True,files=rows)
(output/'PROVENANCE.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt,indent=2))
