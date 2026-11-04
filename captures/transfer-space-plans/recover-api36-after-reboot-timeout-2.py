"""Recover only the two assigned AVDs, preserving current/private originals before restoration."""
from pathlib import Path
import argparse,fcntl,hashlib,importlib.util,io,json,os,re,shlex,subprocess,tarfile,time,signal
ROOT=Path(__file__).resolve().parents[2];MAIN=Path('/Users/LENOVO/.t3/projects/droidfinder');PROOF=MAIN/'captures/persistent-undo'
p=argparse.ArgumentParser();p.add_argument('--serial',required=True,choices=('emulator-5582',));a=p.parse_args()
api='api30' if a.serial=='emulator-5602' else 'api36';avd='FindroidAudit_API30_20261102' if api=='api30' else 'DroidFinder_Tablet_API_36';port=5602 if api=='api30' else 5582
OUTPUT=ROOT/'captures/transfer-space-plans'/('candidate-2-'+api+'-disconnect-recovery-2');assert not OUTPUT.exists()
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb');emulator=str(Path.home()/'Library/Android/sdk/emulator/emulator')
sha=lambda payload:hashlib.sha256(payload).hexdigest()
public=MAIN/'captures/release/0.47.0/Findroid-0.47.0-preview.apk'
pair=[MAIN/'app/build/outputs/apk/debug/app-debug.apk',MAIN/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
legacy=json.loads((PROOF/'candidate-build-6.json').read_text())
for file in pair:assert sha(file.read_bytes())==legacy['files'][str(file.relative_to(MAIN))]['sha256']
failed=ROOT/'captures/transfer-space-plans'/('candidate-2-api30-optimized-layouts-2' if api=='api30' else 'candidate-2-api36-native')
original_workspace=failed/('PRIVATE-space/PRIVATE-workspace-before.tar' if api=='api30' else 'PRIVATE-workspace-before.tar')
assert original_workspace.is_file()
def call(*args,timeout=30,check=True,data=None):
 result=subprocess.run([adb,'-s',a.serial,*map(str,args)],input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
 if check and result.returncode:raise RuntimeError('Assigned-device command failed: '+str(args[:2])+'; inspect private driver log')
 return result.stdout if check else result

def shell(*args,**kwargs):return call('shell','-T',shlex.join(map(str,args)),**kwargs)
def entries(data):
 result={}
 with tarfile.open(fileobj=io.BytesIO(data)) as archive:
  for member in archive:
   name=member.name.rstrip('/');assert not Path(name).is_absolute() and '..' not in Path(name).parts and (member.isfile() or member.isdir())
   result[name]=sha(archive.extractfile(member).read()) if member.isfile() else None
 return result

def process():
 text=subprocess.check_output(['ps','-axo','pid=,command='],text=True)
 matches=[line for line in text.splitlines() if 'qemu-system-' in line and '-avd '+avd+' ' in line and '-port '+str(port)+' ' in line]
 assert len(matches)<=1
 return int(matches[0].strip().split()[0]) if matches else None

def boot():
 assert process() is None,'Keep an existing assigned AVD process; never start a second one'
 log=(OUTPUT/'PRIVATE-emulator-startup.txt').open('xb')
 child=subprocess.Popen([emulator,'-avd',avd,'-port',str(port),'-no-window','-no-audio','-no-snapshot-load','-no-snapshot-save','-no-boot-anim','-memory','2048','-cores','2','-gpu','swiftshader_indirect'],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 log.close();report['startedExistingAvdProcess']=child.pid;report['existingUserdataPreserved']=True

spec=importlib.util.spec_from_file_location('original_restoration',PROOF/'restore-public-tablets.py');restore=importlib.util.module_from_spec(spec);spec.loader.exec_module(restore)
with (PROOF/('.'+a.serial+'-acceptance.lock')).open('a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);OUTPUT.mkdir(parents=True)
 report=dict(serial=a.serial,controllerSha256=sha(Path(__file__).read_bytes()),restorationComplete=False,originalSnapshotsPreserved=True,scope='Assigned AVD only; no wipe, uninstall, clear-data, global adb restart, or automatic recovery replay.')
 try:
  pid=process()
  if pid is None:boot()
  else:
   report['rebootRequestedForExistingAssignedAvd']=True
   try:
    result=call('reboot',timeout=15,check=False);(OUTPUT/'PRIVATE-reboot-command.txt').write_bytes(result.stdout+result.stderr)
   except subprocess.TimeoutExpired:
    result=None;report['guestRebootCommandTimedOut']=True
   if result is None or result.returncode:
    try:
     stopped=call('emu','kill',timeout=15,check=False);(OUTPUT/'PRIVATE-console-stop-command.txt').write_bytes(stopped.stdout+stopped.stderr)
     report['assignedConsoleStopExitCode']=stopped.returncode
    except subprocess.TimeoutExpired:report['assignedConsoleStopTimedOut']=True
    deadline=time.monotonic()+40
    while process() is not None and time.monotonic()<deadline:time.sleep(1)
    remaining=process()
    if remaining is not None:
     assert remaining==pid,'Assigned AVD identity changed; stop without touching a replacement process'
     report['onlyRecordedAssignedAvdReceivedSigterm']=remaining
     os.kill(remaining,signal.SIGTERM)
     deadline=time.monotonic()+40
     while process() is not None and time.monotonic()<deadline:time.sleep(1)
    assert process() is None,'Assigned AVD did not stop after SIGTERM; existing userdata remains preserved'
    boot()
  deadline=time.monotonic()+360
  while time.monotonic()<deadline:
   try:
    status=call('get-state',timeout=6,check=False)
    if status.returncode==0 and status.stdout.strip()==b'device':
     ready=shell('getprop','sys.boot_completed',timeout=6,check=False)
     if ready.returncode==0 and ready.stdout.strip()==b'1':break
   except subprocess.TimeoutExpired:pass
   time.sleep(1)
  else:raise RuntimeError('Assigned AVD did not finish booting; current userdata kept')
  name=call('emu','avd','name',timeout=15).decode().splitlines()[0].strip();assert name==avd
  report['assignedAvdIdentityAndBootVerified']=True
  shell('am','force-stop','dev.findroid',timeout=30)
  for file in pair:call('install','-r','-d',file,timeout=180)
  shell('am','force-stop','dev.findroid',timeout=30)
  # Preserve the current post-disconnect metadata before restoring an original byte.
  current=shell('run-as','dev.findroid','tar','-cf','-','--exclude=files/Workspace','--exclude=files/ProviderFixtures','shared_prefs','databases','no_backup','cache','files',timeout=180)
  (OUTPUT/'PRIVATE-current-managed-before-restoration.tar').write_bytes(current)
  report['currentManagedStatePreservedPrivatelyBeforeRestoration']=True
  before=entries(original_workspace.read_bytes())
  roots=shell('run-as','dev.findroid','find','files/Workspace','-mindepth','1','-maxdepth','1','-print',timeout=30).decode().splitlines()
  old_roots={'/'.join(name.split('/')[:3]) for name in before if len(name.split('/'))>=3}
  extra_roots=set(roots)-old_roots
  pattern=r'files/Workspace/Space-acceptance-\d{15,22}' if api=='api30' else r'files/Workspace/External-drag-\d{10,22}'
  assert all(re.fullmatch(pattern,name) for name in extra_roots),'Unexpected Workspace addition: keep it for review'
  # The failed API30 section was layouts only. Refuse a large/sparse owned payload before tar.
  for root in extra_roots:
   sizes=shell('run-as','dev.findroid','find',root,'-type','f','-exec','stat','-c','%s','{}',';',timeout=30).decode().splitlines()
   assert all(int(size)<1024*1024 for size in sizes),'An unexpected large fixture stays in place for review'
  current_workspace=shell('run-as','dev.findroid','tar','-cf','-','files/Workspace',timeout=180)
  (OUTPUT/'PRIVATE-current-workspace-before-restoration.tar').write_bytes(current_workspace)
  after=entries(current_workspace)
  assert all(name in after and after[name]==value for name,value in before.items()),'An original Workspace byte changed; preserve current state and stop'
  extra=set(after)-set(before)
  assert all(any(name==root or name.startswith(root+'/') for root in extra_roots) for name in extra)
  report['allOriginalWorkspaceBytesVerifiedBeforeCleanup']=True
  for root in sorted(extra_roots):
   preserved=shell('run-as','dev.findroid','tar','-cf','-',root,timeout=180)
   (OUTPUT/('PRIVATE-owned-fixture-'+Path(root).name+'.tar')).write_bytes(preserved)
   shell('run-as','dev.findroid','rm','-rf','--',root,timeout=30)
  final=shell('run-as','dev.findroid','tar','-cf','-','files/Workspace',timeout=180)
  assert entries(final)==before
  report['ownedFixturesPreservedBeforeRemoval']=len(extra_roots)
  report['originalWorkspaceTreeKept']=True
  report['restoration']=restore.restore_one(a.serial,public)
  assert report['restoration']['installedPublicApkSha256']==sha(public.read_bytes())
  report['restorationComplete']=True
 except BaseException as error:
  report['error']=type(error).__name__+': '+str(error)
  raise
 finally:
  (OUTPUT/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps(dict(serial=a.serial,restorationComplete=report['restorationComplete'],originalWorkspaceTreeKept=report.get('originalWorkspaceTreeKept')),indent=2),flush=True)
