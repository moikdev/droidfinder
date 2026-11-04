#!/usr/bin/env python3
"""Compare every explicitly named preview artifact with anonymous GitHub downloads.

Reads public bytes and writes a new receipt. Does not install, extract, execute,
replace local files or change a release.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--version', required=True)
parser.add_argument('--directory', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--include-validation', action='store_true')
a = parser.parse_args()
assert re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+',a.version)
assert not a.output.exists(), 'Preserve existing public receipts'


def fetch(url, maximum):
    with urlopen(Request(url,headers={'User-Agent':'Findroid-public-artifact-verification'}),timeout=180) as response:
        data = response.read(maximum+1)
    assert len(data) <= maximum, 'Public response exceeds its expected bound'
    return data


tag = 'v'+a.version+'-preview'
release = json.loads(fetch('https://api.github.com/repos/moikdev/findroid/releases/tags/'+tag, 2*1024*1024))
assert release['tag_name'] == tag and release['prerelease'] and not release['draft']
assets = {row['name']:row for row in release['assets']}
names = ['Findroid-'+a.version+'-preview.apk','Findroid-'+a.version+'-unsigned.apk',
         'findroid-update.json','SHA256SUMS','BUILDINFO.json']
if a.include_validation:
    names.append('Findroid-'+a.version+'-validation.zip')
assert set(names).issubset(assets)
rows = []
for name in names:
    local = (a.directory/name).read_bytes()
    remote = fetch(assets[name]['browser_download_url'],len(local))
    expected = hashlib.sha256(local).hexdigest()
    assert remote == local and len(remote) == assets[name]['size'] and assets[name]['digest'] == 'sha256:'+expected
    rows.append(dict(name=name,bytes=len(local),sha256=expected,anonymousBytesIdentical=True,githubDigestMatches=True))
info = json.loads((a.directory/'BUILDINFO.json').read_text())
manifest = json.loads((a.directory/'findroid-update.json').read_text())
assert info['version'] == a.version+'-preview'
assert manifest['sha256'] == next(r['sha256'] for r in rows if r['name'].endswith('-preview.apk'))
assert manifest['bytes'] == next(r['bytes'] for r in rows if r['name'].endswith('-preview.apk'))
reference = json.loads(fetch('https://api.github.com/repos/moikdev/findroid/git/ref/tags/'+tag,1024*1024))
assert reference['object']['type'] == 'commit' and reference['object']['sha'] == info['source_commit']
report = dict(release=tag,sourceCommit=info['source_commit'],allSelectedPublicBytesAndDigestsMatch=True,
    publicTagMatchesBuildInfo=True,artifacts=rows,checkerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(dict(release=tag,verifiedArtifacts=len(rows),allSelectedPublicBytesAndDigestsMatch=True)))
