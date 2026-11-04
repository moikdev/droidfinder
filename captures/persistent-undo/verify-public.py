from concurrent.futures import ThreadPoolExecutor
import hashlib,json
from datetime import datetime
from pathlib import Path
from urllib.request import Request,urlopen
ROOT=Path(__file__).resolve().parents[2]
proof=ROOT/'captures/persistent-undo';release=ROOT/'captures/release/0.45.0';public=proof/'public';public.mkdir(exist_ok=True)
def fetch(url):
 with urlopen(Request(url,headers={'User-Agent':'Findroid-public-acceptance','Accept':'application/vnd.github+json'}),timeout=120) as r:return r.read()
repo=json.loads(fetch('https://api.github.com/repos/moikdev/findroid'));assert repo['private'] is False
info=json.loads(fetch('https://api.github.com/repos/moikdev/findroid/releases/tags/v0.45.0-preview'))
assert not info['draft'] and info['prerelease']
tag=json.loads(fetch('https://api.github.com/repos/moikdev/findroid/git/ref/tags/v0.45.0-preview'))
assert tag['object']['sha']==json.loads((release/'BUILDINFO.json').read_text())['source_commit']
assets={a['name']:a for a in info['assets']}
names=['Findroid-0.45.0-preview.apk','Findroid-0.45.0-unsigned.apk','BUILDINFO.json','SHA256SUMS','findroid-update.json']
def verify(name):
 asset=assets[name];data=fetch(asset['browser_download_url']);local=(release/name).read_bytes();sha=hashlib.sha256(data).hexdigest()
 assert data==local and asset['size']==len(data)
 assert asset['digest']=='sha256:'+sha
 (public/name).write_bytes(data)
 return {'name':name,'bytes':len(data),'sha256':sha,'publicDownloadMatchesLocal':True,'githubDigestMatches':True}
with ThreadPoolExecutor(max_workers=5) as pool:results=list(pool.map(verify,names))
report={'date':datetime.now().astimezone().date().isoformat(),'release':'v0.45.0-preview','public':True,'sourceCommit':json.loads((release/'BUILDINFO.json').read_text())['source_commit'],'assets':results}
(proof/'public-downloads.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
