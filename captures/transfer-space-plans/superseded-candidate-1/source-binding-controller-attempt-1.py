"""Bind the complete frozen 0.47 input set to Git without changing the honest dirty-build flag."""
from pathlib import Path
import hashlib,json,subprocess
root=Path(__file__).resolve().parents[2];proof=root/'captures/recovery-inbox';manifest=proof/'candidate-build-1.json';frozen=json.loads(manifest.read_text())
sha=lambda data:hashlib.sha256(data).hexdigest()
commit=frozen['sourceCommit'];rows=[]
for name,expected in frozen['sourceFiles'].items():
    data=(root/name).read_bytes();assert sha(data)==expected
    stored=subprocess.check_output(['git','show',commit+':'+name],cwd=root)
    normalized=data
    if name.endswith('.bat'):
        assert b'*.bat text eol=crlf' in (root/'.gitattributes').read_bytes().splitlines()
        normalized=data.replace(b'\r\n',b'\n');assert data==normalized.replace(b'\n',b'\r\n')
    assert normalized==stored,name
    blob=sha(data) # Actual frozen bytes remain the archive identity, distinct from a normalized Git object.
    git_hash=hashlib.sha1(b'blob '+str(len(stored)).encode()+b'\0'+stored).hexdigest()
    rows.append(dict(path=name,sha256=blob,gitBlob=git_hash,bytes=len(data)))
app_sources={str(p.relative_to(root)) for p in (root/'app/src').rglob('*') if p.is_file()}
assert app_sources.issubset(frozen['sourceFiles'])
assert set(subprocess.check_output(['git','ls-tree','-r','--name-only',commit,'app/src'],cwd=root,text=True).splitlines())==app_sources
for name,row in frozen['files'].items():assert sha((root/name).read_bytes())==row['sha256'] and (root/name).stat().st_size==row['bytes']
info=json.loads((root/'captures/release/0.47.0/BUILDINFO.json').read_text());assert info['source_commit']==commit and info['working_tree_dirty'] is True
report=dict(sourceCommit=commit,frozenManifestSha256=sha(manifest.read_bytes()),frozenSourceCount=len(rows),allFrozenSourceInputsMatchCommittedGitObjectsAndCheckoutRules=True,allAppSourcesByteIdenticalToGit=True,allAppSourcesIncluded=True,additionalProguardAndAttributesBindingsVerified=True,allFrozenOutputsUnchanged=True,buildInfoDirtyFlagPreserved=True,bindings=rows)
target=proof/'candidate-1-source-binding.json';assert not target.exists();target.write_text(json.dumps(report,indent=2)+'\n')
print(f'{len(rows)} frozen source inputs match the committed source; all {len(frozen["files"])} outputs remain unchanged.')
