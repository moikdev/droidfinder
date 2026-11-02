#!/usr/bin/env python3
"""Run an explicitly selected native suite in fresh processes and retain every batch result.

A failed batch stops acceptance. --resume reuses only successful batches with matching APK
hashes and selectors; it preserves a failed attempt before retrying it. A reset script may
restore the dedicated tablet's original metadata between batches; its private data stays out
of the report. This never chooses an emulator implicitly or uninstalls/clears application data.
"""
import argparse
from collections import OrderedDict
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--classes-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--batch-directory', type=Path, required=True)
    parser.add_argument('--reset-script', type=Path)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    args.classes_file=args.classes_file.resolve(); args.output=args.output.resolve(); args.batch_directory=args.batch_directory.resolve()
    if args.reset_script: args.reset_script=args.reset_script.resolve()
    assert args.serial != 'emulator-5580', 'This emulator belongs to another project.'
    adb = shutil.which('adb') or str(Path.home()/'Library/Android/sdk/platform-tools/adb')
    grouped = OrderedDict()
    for selector in args.classes_file.read_text().strip().split(','):
        grouped.setdefault(selector.partition('#')[0], []).append(selector)
    # Legacy split-screen acceptance finishes the suite, after all ActivityScenario launches.
    external = 'dev.findroid.ExternalDragWorkflowTest'
    if external in grouped: grouped.move_to_end(external)
    selections = [','.join(items) for items in grouped.values()]
    paths = ['app/build/outputs/apk/debug/app-debug.apk',
             'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    hashes = {path:hashlib.sha256((ROOT/path).read_bytes()).hexdigest() for path in paths}
    def installed_hashes():
        found={}
        for package,path in zip(('dev.findroid','dev.findroid.test'),paths):
            installed=subprocess.check_output([adb,'-s',args.serial,'shell','pm','path',package],text=True).strip().removeprefix('package:')
            found[path]=hashlib.sha256(subprocess.check_output([adb,'-s',args.serial,'exec-out','cat',installed])).hexdigest()
        assert found==hashes, 'Install the exact built app/test APKs on this dedicated tablet before acceptance.'
        return found
    installed_hashes()
    contract = {'serial':args.serial,'selectors':selections,'apkSha256':hashes}
    args.batch_directory.mkdir(parents=True,exist_ok=True)
    contract_path = args.batch_directory/'contract.json'
    if contract_path.exists():
        assert args.resume and json.loads(contract_path.read_text()) == contract, 'Existing batches need --resume and the exact same source APKs/selectors.'
    else: contract_path.write_text(json.dumps(contract,indent=2)+'\n')
    receipts = []
    started = time.monotonic()
    def reset():
        subprocess.run([adb,'-s',args.serial,'shell','am','force-stop','dev.findroid'],check=True)
        if args.reset_script:
            subprocess.run([sys.executable,str(args.reset_script),'restore',args.serial],check=True)
    try:
        for index,selector in enumerate(selections,1):
            name=f'{index:02}-{selector.partition(",")[0].partition("#")[0].rpartition(".")[2]}'
            log=args.batch_directory/(name+'.txt'); report_path=log.with_suffix('.json')
            previous=json.loads(report_path.read_text()) if report_path.exists() else None
            if not (args.resume and previous and previous['success']):
                if log.exists() or report_path.exists():
                    attempt=1
                    while log.with_name(f'{name}-before-retry-{attempt}.txt').exists(): attempt+=1
                    for path in (log,report_path):
                        if path.exists(): path.rename(path.with_name(f'{name}-before-retry-{attempt}{path.suffix}'))
                reset()
                subprocess.run([adb,'-s',args.serial,'shell','am','start','--windowingMode','1','-n','dev.findroid/.MainActivity'],check=True)
                result=subprocess.run([sys.executable,str(ROOT/'scripts/check-device.py'),'--serial',args.serial,
                    '--classes',selector,'--output',str(log)])
                assert result.returncode==0, f'Batch failed; inspect {report_path}. It is not accepted.'
            report=json.loads(report_path.read_text())
            assert report['success'] and report['failed']==report['skipped']==0
            receipts.append({'selector':selector,'report':str(report_path.relative_to(ROOT)),
                             'log':str(log.relative_to(ROOT)),'reportSha256':hashlib.sha256(report_path.read_bytes()).hexdigest(),
                             'logSha256':hashlib.sha256(log.read_bytes()).hexdigest(),'passed':report['passed']})
            print(f"Accepted batch {index}/{len(selections)}: {selector} ({report['passed']} cases)",flush=True)
    finally: reset()
    cases=[]; duration=0
    for receipt in receipts:
        report=json.loads((ROOT/receipt['report']).read_text());cases.extend(report['cases']);duration+=report['durationSeconds']
    identities=[(case['class'],case['test']) for case in cases]
    assert len(identities)==len(set(identities)), 'A native case was counted twice.'
    for path,digest in hashes.items(): assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==digest
    aggregate={'success':True,'passed':len(cases),'skipped':0,'failed':0,'runnerCompleted':True,
        'runnerSucceeded':True,'adbExitCode':0,'cases':cases,'serial':args.serial,'durationSeconds':round(duration,1),
        'wallSecondsThisInvocation':round(time.monotonic()-started,1),'timedOut':False,
        'isolatedBatches':receipts,'apkSha256':hashes,'installedApkSha256':installed_hashes()}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text('Isolated native acceptance; individual runner results follow.\n'+''.join(
        f"\nBatch: {row['selector']}\n"+(ROOT/row['log']).read_text() for row in receipts))
    args.output.with_suffix('.json').write_text(json.dumps(aggregate,indent=2)+'\n')
    print(f"{args.serial}: {len(cases)} passed in {len(receipts)} isolated batches; zero failures/skips.",flush=True)


if __name__=='__main__': main()
