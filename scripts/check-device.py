#!/usr/bin/env python3
"""Run native tests and reject failures even when adb itself exits successfully."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def summarize(text, adb_code):
    cases = []
    fields = {}
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if separator:
                fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.partition(': ')[2])
            if code != 1 and 'class' in fields and 'test' in fields:
                cases.append({'class': fields['class'], 'test': fields['test'], 'code': code,
                              **({'error': fields['stack']} if 'stack' in fields else {})})
            fields = {}
    passed = sum(case['code'] == 0 for case in cases)
    skipped = sum(case['code'] in (-3, -4) for case in cases)
    failed = [case for case in cases if case['code'] not in (0, -3, -4)]
    runner_passed = bool(re.search(r'\bOK \(\d+ tests?\)', text))
    completed = runner_passed or bool(re.search(r'FAILURES!!!\s*Tests run:\s*\d+', text))
    success = adb_code == 0 and runner_passed and not failed and bool(cases)
    return {'success': success, 'passed': passed, 'skipped': skipped, 'failed': len(failed),
            'runnerCompleted': completed, 'runnerSucceeded': runner_passed,
            'adbExitCode': adb_code, 'cases': cases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit dedicated test device; never picks another project\'s emulator.')
    parser.add_argument('--install', action='store_true', help='Install already-built debug and test APKs on this serial before testing.')
    parser.add_argument('--classes', help='Comma-separated fully qualified classes or Class#method.')
    parser.add_argument('--output', type=Path, required=True, help='Instrumentation log; JSON is written beside it.')
    parser.add_argument('--timeout', type=int, default=7200, help='Overall seconds before reporting an incomplete run.')
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or
               Path.home() / 'Library/Android/sdk')
    adb = shutil.which('adb') or str(sdk / 'platform-tools/adb')
    if args.install:
        for relative in ['app/build/outputs/apk/debug/app-debug.apk',
                         'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
            subprocess.run([adb, '-s', args.serial, 'install', '-r', str(ROOT / relative)], check=True)
    command = [adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r']
    if args.classes:
        command += ['-e', 'class', args.classes]
    command += ['dev.findroid.test/androidx.test.runner.AndroidJUnitRunner']
    args.output.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    timed_out = False
    with args.output.open('w') as log:
        try:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                    timeout=args.timeout)
            code = result.returncode
        except subprocess.TimeoutExpired:
            timed_out = True
            code = 124
    report = summarize(args.output.read_text(errors='replace'), code)
    report.update(serial=args.serial, durationSeconds=round(time.monotonic() - started, 1),
                  timedOut=timed_out)
    args.output.with_suffix('.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f"{args.serial}: {report['passed']} passed, {report['skipped']} skipped, {report['failed']} failed; "
          f"runner complete: {report['runnerCompleted']}")
    for case in report['cases']:
        if case['code'] not in (0, -3, -4):
            print(f"{case['class']}#{case['test']}: {case.get('error', 'failed')}")
    print(f'Log: {args.output.resolve()}')
    return 0 if report['success'] else 1


if __name__ == '__main__':
    sys.exit(main())
