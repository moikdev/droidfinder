#!/usr/bin/env python3
"""Independently verify the immutable public 0.40.0 validation archive and source identities."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from urllib.request import Request, urlopen
import zipfile

REPOSITORY = 'https://api.github.com/repos/moikdev/findroid'
TAG = 'v0.40.0-preview'
NAME = 'Findroid-0.40.0-validation.zip'
EXPECTED_SHA256 = 'd7b915f66039d065ef6359581dbee1e0d68aa9ec591e3c3bf8bc3241845cf357'
APPLICATION_COMMIT = '783780aca7a3432fa52fde983b44c9be4646ca4f'
NATIVE_COMMIT = '5cef85e067ded3f9a395ee19f0164e9cf92ba571'
EVIDENCE_COMMIT = '4a4ee8f285add584ede234238f7a7bc973ff49f3'


def fetch(url):
    with urlopen(Request(url, headers={'User-Agent': 'Findroid-independent-validation'}), timeout=180) as response:
        return response.read()


def git_blob(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode('ascii') + b'\0' + data).hexdigest()


def source_tree(commit):
    result = json.loads(fetch(REPOSITORY + '/git/trees/' + commit + '?recursive=1'))
    assert not result['truncated'], 'Incomplete public source tree'
    return {row['path']: row['sha'] for row in result['tree'] if row['type'] == 'blob'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, help='Also compare the anonymous download with this local ZIP')
    parser.add_argument('--output', type=Path, help='Write a JSON verification receipt')
    args = parser.parse_args()
    release = json.loads(fetch(REPOSITORY + '/releases/tags/' + TAG))
    assert not release['draft'] and release['prerelease']
    assets = {row['name']: row for row in release['assets']}
    assert len(assets) == 6
    asset = assets[NAME]
    data = fetch(asset['browser_download_url'])
    digest = hashlib.sha256(data).hexdigest()
    assert digest == EXPECTED_SHA256 and len(data) == asset['size']
    assert asset['digest'] == 'sha256:' + digest
    if args.archive:
        assert data == args.archive.read_bytes(), 'Public and local archive bytes differ'
    buildinfo = fetch(assets['BUILDINFO.json']['browser_download_url'])
    assert hashlib.sha256(buildinfo).hexdigest() == assets['BUILDINFO.json']['digest'].removeprefix('sha256:')
    application_tree = source_tree(APPLICATION_COMMIT)
    native_tree = source_tree(NATIVE_COMMIT)
    native_paths = {path for path in native_tree if path.startswith('app/src/androidTest/')}
    assert native_paths and native_paths == {path for path in application_tree if path.startswith('app/src/androidTest/')}
    assert all(native_tree[path] == application_tree[path] for path in native_paths), 'Native sources changed'
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        assert archive.testzip() is None
        manifest = json.loads(archive.read('EVIDENCE.json'))
        rows = {row['path']: row for row in manifest['files']}
        names = archive.namelist()
        assert len(rows) == len(manifest['files']) and len(names) == len(set(names)) == 1235
        assert set(names) == {'EVIDENCE.json', *rows}
        for path, row in rows.items():
            parts = Path(path)
            assert not parts.is_absolute() and '..' not in parts.parts and 'native-original' not in parts.parts
            assert parts.suffix not in ('.apk', '.jks', '.keystore', '.bin') and 'bugreport' not in parts.name
            payload = archive.read(path)
            assert len(payload) == row['bytes'] and hashlib.sha256(payload).hexdigest() == row['sha256'], path
            if path.startswith('app/src/') or path in ('app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties'):
                assert application_tree.get(path) == git_blob(payload), ('Source bytes differ', path)
        expected_sources = {path for path in application_tree if path.startswith('app/src/') and
                            (path.endswith('.kt') or path.startswith('app/src/main/') and path.endswith('.xml'))}
        assert expected_sources.issubset(rows), 'Archived Kotlin/XML sources are incomplete'
        assert archive.read('captures/release/0.40.0/BUILDINFO.json') == buildinfo
        info = json.loads(buildinfo)
        assert not info['working_tree_dirty'] and info['source_commit'] == APPLICATION_COMMIT
        assert manifest['applicationSourceCommit'] == APPLICATION_COMMIT
        assert manifest['nativeTestSourceCommit'] == NATIVE_COMMIT
        assert manifest['evidenceCommit'] == EVIDENCE_COMMIT
        assert manifest['previewApkSha256'] == info['artifacts'][0]['sha256']
        assert (manifest['jvmCases'], manifest['nativeCasesPerApi'], manifest['isolatedBatchesPerApi']) == (489, 353, 38)
    report = dict(release=TAG, archive=NAME, bytes=len(data), sha256=digest, entries=len(names),
                  anonymousDownloadMatchesLocal=bool(args.archive), githubDigestMatches=True,
                  allManifestEntriesVerified=True, archivedSourcesMatchPublicCommit=True,
                  nativeSourcesIdenticalAcrossRecordedCommits=True,
                  applicationSourceCommit=APPLICATION_COMMIT, nativeTestSourceCommit=NATIVE_COMMIT,
                  evidenceCommit=EVIDENCE_COMMIT,
                  verificationScript='scripts/verify-local-index-validation.py',
                  verificationScriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
