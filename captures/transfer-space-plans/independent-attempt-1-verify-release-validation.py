#!/usr/bin/env python3
"""Verify an immutable public validation ZIP using its independently supplied SHA-256.

Example: --tag v0.40.0-preview --sha256 <digest published in VALIDATION.md>.
No files are extracted and no downloaded code is executed.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET
import zipfile

REPOSITORY = 'https://api.github.com/repos/moikdev/findroid'


def fetch(url):
    with urlopen(Request(url, headers={'User-Agent': 'Findroid-independent-validation'}), timeout=180) as response:
        return response.read()


def git_blob(data):
    return hashlib.sha1(b'blob '+str(len(data)).encode('ascii')+b'\0'+data).hexdigest()


def source_tree(commit):
    assert re.fullmatch(r'[0-9a-f]{40}', commit)
    tree = json.loads(fetch(REPOSITORY+'/git/trees/'+commit+'?recursive=1'))
    assert not tree['truncated'], 'Incomplete public source tree'
    return {row['path']:row['sha'] for row in tree['tree'] if row['type']=='blob'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag',required=True)
    parser.add_argument('--sha256',required=True,help='The expected ZIP digest from the published validation record')
    parser.add_argument('--archive',type=Path,help='Also compare the anonymous download with this local copy')
    parser.add_argument('--output',type=Path,help='Write a JSON verification receipt')
    args = parser.parse_args()
    assert re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+-preview',args.tag)
    assert re.fullmatch(r'[0-9a-f]{64}',args.sha256)
    version = args.tag.removeprefix('v').removesuffix('-preview')
    name = 'Findroid-'+version+'-validation.zip'
    release = json.loads(fetch(REPOSITORY+'/releases/tags/'+args.tag))
    assert not release['draft'] and release['prerelease']
    assets = {row['name']:row for row in release['assets']}
    assert len(assets)==6
    data = fetch(assets[name]['browser_download_url'])
    digest = hashlib.sha256(data).hexdigest()
    assert digest==args.sha256 and len(data)==assets[name]['size']
    assert assets[name]['digest']=='sha256:'+digest
    if args.archive:assert data==args.archive.read_bytes(),'Local and anonymous archive bytes differ'
    info_bytes = fetch(assets['BUILDINFO.json']['browser_download_url'])
    assert hashlib.sha256(info_bytes).hexdigest()==assets['BUILDINFO.json']['digest'].removeprefix('sha256:')
    info = json.loads(info_bytes)
    assert info['version']==version+'-preview'
    assert type(info['working_tree_dirty']) is bool
    tag = json.loads(fetch(REPOSITORY+'/git/ref/tags/'+args.tag))
    assert tag['object']['sha']==info['source_commit'],'Release tag and BUILDINFO source differ'
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        assert archive.testzip() is None
        manifest = json.loads(archive.read('EVIDENCE.json'))
        rows = {row['path']:row for row in manifest['files']}
        names = archive.namelist()
        assert len(rows)==len(manifest['files']) and len(names)==len(set(names))
        assert set(names)=={'EVIDENCE.json',*rows}
        application_tree = source_tree(manifest['applicationSourceCommit'])
        native_tree = source_tree(manifest['nativeTestSourceCommit'])
        evidence_tree = source_tree(manifest['evidenceCommit'])
        if info['working_tree_dirty']:
            # Preserve the honest BUILDINFO flag. A dirty build needs explicit,
            # complete source bindings rather than a rewritten clean-tree claim.
            assert manifest.get('frozenSourceBindingsComplete') is True
            frozen_path = manifest['frozenCandidateManifest']
            assert frozen_path in rows
            frozen = json.loads(archive.read(frozen_path))
            assert frozen['sourceCommit'] == info['source_commit']
            assert frozen['sourceFiles'] and all(path in rows for path in frozen['sourceFiles'])
            for path, expected_hash in frozen['sourceFiles'].items():
                payload = archive.read(path)
                assert hashlib.sha256(payload).hexdigest() == expected_hash, ('Frozen source hash',path)
                normalized = payload
                if path.endswith('.bat'):
                    attributes = archive.read('.gitattributes')
                    assert application_tree.get('.gitattributes') == git_blob(attributes)
                    assert b'*.bat text eol=crlf' in attributes.splitlines()
                    normalized = payload.replace(b'\r\n',b'\n')
                    assert payload == normalized.replace(b'\n',b'\r\n'), 'Unexpected Windows-wrapper line endings'
                assert application_tree.get(path) == git_blob(normalized), ('Frozen public source',path)
            required_build = {'app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle/wrapper/gradle-wrapper.properties','gradlew','gradlew.bat'}
            assert required_build.issubset(frozen['sourceFiles'])
        native_paths = {path for path in native_tree if path.startswith('app/src/androidTest/')}
        assert native_paths and native_paths=={path for path in application_tree if path.startswith('app/src/androidTest/')}
        assert all(native_tree[path]==application_tree[path] for path in native_paths),'Native source bytes differ'
        for path,row in rows.items():
            parts = Path(path)
            assert not parts.is_absolute() and '..' not in parts.parts and 'native-original' not in parts.parts
            assert parts.suffix not in {'.apk','.jks','.keystore','.bin','.db','.sqlite'} and 'bugreport' not in parts.name
            payload = archive.read(path)
            assert len(payload)==row['bytes'] and hashlib.sha256(payload).hexdigest()==row['sha256'],path
            evidence_payload=payload
            if path.endswith('.bat'):
                attributes=archive.read('.gitattributes')
                assert evidence_tree.get('.gitattributes')==git_blob(attributes)
                assert b'*.bat text eol=crlf' in attributes.splitlines()
                evidence_payload=payload.replace(b'\r\n',b'\n')
                assert payload==evidence_payload.replace(b'\n',b'\r\n')
            assert evidence_tree.get(path)==git_blob(evidence_payload),('Archived evidence differs from its public commit',path)
            if path.startswith('app/src/') or path in {'app/build.gradle.kts','build.gradle.kts','settings.gradle.kts','gradle.properties'}:
                assert application_tree.get(path)==git_blob(payload),('Archived source bytes differ',path)
        sources = {path for path in application_tree if path.startswith('app/src/') and
                   (path.endswith('.kt') or path.startswith('app/src/main/') and path.endswith('.xml') or
                    path.startswith('app/src/main/cpp/') and (path.endswith(('.c','.h')) or path.endswith('/CMakeLists.txt')))}
        assert sources.issubset(rows),'Archived Kotlin/XML/native source is incomplete'
        if info['working_tree_dirty']:
            assert sources.issubset(frozen['sourceFiles']), 'Dirty build source bindings are incomplete'
        if any(path.startswith('app/src/main/cpp/') for path in sources):
            assert 'app/proguard-rules.pro' in rows
            assert application_tree['app/proguard-rules.pro']==git_blob(archive.read('app/proguard-rules.pro'))
        assert archive.read('captures/release/'+version+'/BUILDINFO.json')==info_bytes
        assert manifest['applicationSourceCommit']==info['source_commit']
        assert manifest['previewApkSha256']==info['artifacts'][0]['sha256']
        tests = [ET.fromstring(archive.read(path)) for path in rows
                 if path.startswith('app/build/test-results/testDebugUnitTest/TEST-') and path.endswith('.xml')]
        totals = {key:sum(int(test.get(key,0)) for test in tests) for key in ('tests','failures','errors','skipped')}
        assert totals==dict(tests=manifest['jvmCases'],failures=0,errors=0,skipped=0)
        reports = {int(re.search(r'api(30|36)-final-native.json$',path).group(1)):json.loads(archive.read(path))
                   for path in rows if re.search(r'/api(30|36)-final-native.json$',path)}
        assert set(reports)=={30,36}
        identities=[];pairs=[]
        for report in reports.values():
            assert report['success'] and report['runnerCompleted'] and report['runnerSucceeded']
            assert report['failed']==report['skipped']==0 and report['passed']==manifest['nativeCasesPerApi']
            cases={(row['class'],row['test']) for row in report['cases']}
            assert len(cases)==manifest['nativeCasesPerApi']
            assert len(report['isolatedBatches'])==manifest['isolatedBatchesPerApi']
            assert report['apkSha256']==report['installedApkSha256']
            assert all(row['code']==0 for row in report['cases'])
            batch_cases=[]
            for batch in report['isolatedBatches']:
                for pathkey,hashkey in (('report','reportSha256'),('log','logSha256')):
                    payload=archive.read(batch[pathkey])
                    assert hashlib.sha256(payload).hexdigest()==batch[hashkey]
                child=json.loads(archive.read(batch['report']))
                assert child['success'] and child['runnerCompleted'] and child['runnerSucceeded']
                assert child['failed']==child['skipped']==0
                assert child['passed']==batch['passed']==len(child['cases'])
                assert all(row['code']==0 for row in child['cases'])
                batch_cases.extend((row['class'],row['test']) for row in child['cases'])
            assert len(batch_cases)==len(set(batch_cases))==report['passed']
            assert set(batch_cases)==cases
            contract_path=str(Path(report['isolatedBatches'][0]['report']).parent/'contract.json')
            contract=json.loads(archive.read(contract_path))
            assert contract['serial']==report['serial'] and contract['apkSha256']==report['apkSha256']
            identities.append(cases);pairs.append(report['apkSha256'])
        assert identities[0]==identities[1] and pairs[0]==pairs[1]
        for key in ('jvmCases','nativeCasesPerApi','isolatedBatchesPerApi'):
            assert isinstance(manifest[key],int) and manifest[key]>0
        assert re.fullmatch(r'[0-9a-f]{40}',manifest['evidenceCommit'])
    report = dict(release=args.tag,archive=name,bytes=len(data),sha256=digest,entries=len(names),
        anonymousDownloadMatchesLocal=bool(args.archive),githubDigestMatches=True,allManifestEntriesVerified=True,
        archivedSourcesMatchPublicCommit=True,evidenceSnapshotMatchesPublicCommit=True,nativeSourcesIdenticalAcrossRecordedCommits=True,
        recordedJvmAndNativeTotalsVerified=True,
        **{key:manifest[key] for key in ('applicationSourceCommit','nativeTestSourceCommit','evidenceCommit')},
        verificationScript='scripts/verify-release-validation.py',
        verificationScriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
