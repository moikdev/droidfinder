#!/usr/bin/env python3
"""Query OSV for the resolved release runtime graph. Requires network access and JDK 17."""
import argparse
import datetime
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
INIT = """
allprojects { project ->
    afterEvaluate {
        if (project.path == ':app') {
            tasks.register('findroidAuditRuntimeCoordinates') {
                doLast {
                    configurations.releaseRuntimeClasspath.incoming.resolutionResult.allComponents.each { component ->
                        def id = component.id
                        if (id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                            println("AUDIT_COORDINATE=" + id.group + ":" + id.module + ":" + id.version)
                        }
                    }
                }
            }
        }
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'captures/dependencies-audit.json')
    args = parser.parse_args()
    report = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'database': 'https://api.osv.dev/v1/querybatch', 'status': 'incomplete'}
    exit_code = 2
    try:
        with tempfile.TemporaryDirectory(prefix='findroid-dependency-audit-') as tmp:
            init = Path(tmp) / 'audit.init.gradle'
            init.write_text(INIT)
            result = subprocess.run([str(ROOT / 'gradlew'), '-q', '-I', str(init),
                                     ':app:findroidAuditRuntimeCoordinates'],
                                    cwd=ROOT, text=True, capture_output=True, timeout=180)
            if result.returncode:
                raise RuntimeError('Gradle could not resolve the release graph:\n' +
                                   result.stdout[-4000:] + result.stderr[-4000:])
        coordinates = sorted({line.split('=', 1)[1] for line in result.stdout.splitlines()
                              if line.startswith('AUDIT_COORDINATE=')})
        if not coordinates:
            raise RuntimeError('Gradle returned no runtime coordinates; no clean result is available.')
        report['coordinates'] = coordinates
        queries = []
        for coordinate in coordinates:
            group, name, version = coordinate.split(':', 2)
            queries.append({'package': {'name': f'{group}:{name}', 'ecosystem': 'Maven'},
                            'version': version})
        request = urllib.request.Request(report['database'],
                                         data=json.dumps({'queries': queries}).encode(),
                                         headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=45) as response:
            results = json.load(response)['results']
        if len(results) != len(coordinates):
            raise RuntimeError('OSV returned an incomplete response.')
        report['results'] = dict(zip(coordinates, results))
        report['findings'] = {name: value['vulns'] for name, value in report['results'].items()
                              if value.get('vulns')}
        report['status'] = 'findings' if report['findings'] else 'no-known-matches'
        exit_code = 1 if report['findings'] else 0
        print(f"Checked {len(coordinates)} runtime packages; {len(report['findings'])} with OSV matches.")
        for name, vulnerabilities in report['findings'].items():
            print(name + ': ' + ', '.join(v['id'] for v in vulnerabilities))
        print('Database results require reachability review and may lag newly published advisories.')
    except Exception as error:
        report['error'] = str(error)
        print(str(error), file=sys.stderr)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(f'Report: {args.output.resolve()}')
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
