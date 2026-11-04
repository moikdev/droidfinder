"""Compare the explicit publication allowlist with private original location identifiers.

No original values, hashes of individual values, permission payloads or document
contents are printed. Binary screenshots require the separate actual visual review.
"""
import argparse,hashlib,json,re,tarfile
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2];PROOF=ROOT/'captures/persistent-undo'
p=argparse.ArgumentParser();p.add_argument('--file-list',type=Path,required=True);a=p.parse_args()
paths=[(ROOT/name).resolve() for name in json.loads(a.file_list.read_text())]
for path in paths:
    assert path.is_file() and path.is_relative_to(ROOT)
    assert not any('PRIVATE' in part or 'private-native-screenshots' in part or part in ('private-native-state','private-native-screenshots','native-original','accepted-build','candidate-build','candidate-build-2','candidate-build-3','candidate-build-4','candidate-build-5','candidate-build-6') for part in path.parts)
    assert path.suffix not in ('.apk','.tar','.db','.sqlite','.jks','.keystore','.bin')
tokens=set()
standard_paths={'/storage/emulated/0','/storage/emulated/0/Documents','/storage/emulated/0/Download','/storage/emulated/0/Downloads','/storage/emulated/0/Pictures','/storage/emulated/0/Movies','/storage/emulated/0/Music','/data/user/0/dev.findroid/files/Workspace','/data/data/dev.findroid/files/Workspace'}
def walk(value):
    if isinstance(value,dict):
        for key,child in value.items():
            if isinstance(child,str) and key in ('name','label','path','uri','treeUri','tree','root'):
                concrete_path=child.startswith(('/','content://')) and child not in standard_paths
                unique_label=bool(re.search(r'[0-9]{10,}|[^\s@]+@[^\s@]+\.[^\s@]+',child))
                if concrete_path or unique_label:tokens.add(child)
            walk(child)
    elif isinstance(value,list):
        for child in value:walk(child)
for serial in ('emulator-5602','emulator-5582'):
    private=PROOF/'private-native-state'/serial/'PRIVATE-STATE.tar'
    with tarfile.open(private) as archive:
        for name in ('shared_prefs/finder.xml','shared_prefs/locations.xml'):
            tree=ET.fromstring(archive.extractfile(name).read())
            for node in tree.findall('string'):
                if node.text:
                    try:walk(json.loads(node.text))
                    except (ValueError,TypeError):pass
# These fixed owned test-provider roots are public fixture identifiers, not account locations.
tokens={v for v in tokens if not v.startswith(('content://dev.findroid.fixtures/','content://dev.findroid.transferqa.documents/','content://dev.findroid.documents/'))}
assert tokens, 'The private original identifier extraction must inspect actual saved locations'
texts=[path for path in paths if path.suffix in ('.json','.txt','.xml','.py','.kt','.kts','.md','.java','.properties','.c','.pro') or path.name=='SHA256SUMS']
matches=[]
for path in texts:
    data=path.read_text(errors='replace')
    if any(token in data for token in tokens):matches.append(str(path.relative_to(ROOT)))
assert not matches, 'Private original location identifiers need review in: '+', '.join(matches)
report=dict(explicitAllowlistChecked=True,privateRecoveryFilesExcluded=True,
    publicationAllowlistSha256=hashlib.sha256(a.file_list.read_bytes()).hexdigest(),publishedFilesChecked=len(paths),
    originalLocationPathAndUniqueLabelCount=len(tokens),publishedTextFilesChecked=len(texts),literalIdentifierMatches=0,
    screenshotInspectionRecordedSeparately=True,scope='Exact original location paths and timestamp/account-like labels. No claim of automated image or universal content sanitization.')
(PROOF/'public-privacy-review.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
