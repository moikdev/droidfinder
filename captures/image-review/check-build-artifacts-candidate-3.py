"""Check frozen APK identity, native segment alignment, ZIP alignment, and signing continuity."""
import hashlib,json,os,re,struct,subprocess,zipfile
from pathlib import Path

root=Path(__file__).resolve().parents[2];proof=Path(__file__).resolve().parent
frozen=json.loads((proof/'candidate-build-3.json').read_text())
proof=proof/'candidate-3-artifact-checks';proof.mkdir(exist_ok=True)
sdk=Path.home()/'Library/Android/sdk';tools=sdk/'build-tools/37.0.0'
env=dict(os.environ,JAVA_HOME=str(Path.home()/'Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1/Contents/Home'))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def command(parts,path):
    result=subprocess.run(list(map(str,parts)),stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=env,check=True)
    if path.exists():assert path.read_bytes()==result.stdout, 'Preserve an earlier differing tool result'
    else:path.write_bytes(result.stdout)
    return result.stdout.decode()

def elf_alignment(data, minimum):
    assert data[:4]==b'\x7fELF' and data[4] in (1,2) and data[5] in (1,2)
    order='<' if data[5]==1 else '>'
    if data[4]==2:
        offset=struct.unpack_from(order+'Q',data,32)[0];stride,count=struct.unpack_from(order+'HH',data,54);alignment_offset=48;fmt='Q'
    else:
        offset=struct.unpack_from(order+'I',data,28)[0];stride,count=struct.unpack_from(order+'HH',data,42);alignment_offset=28;fmt='I'
    found=[]
    for i in range(count):
        position=offset+i*stride
        if struct.unpack_from(order+'I',data,position)[0]==1:found.append(struct.unpack_from(order+fmt,data,position+alignment_offset)[0])
    assert found and all(value>=minimum and value&(value-1)==0 for value in found)
    return found

for kind in ('preview','release'):
    name='app/build/outputs/apk/'+kind+'/app-'+kind+('-unsigned' if kind=='release' else '')+'.apk';apk=root/name
    assert sha(apk)==frozen['files'][name]['sha256']
    with zipfile.ZipFile(apk) as archive:
        libraries=sorted(n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so'))
        bridge=[n for n in libraries if n.endswith('/libfindroid_files.so')]
        assert {n.split('/')[1] for n in bridge}=={'arm64-v8a','armeabi-v7a','x86','x86_64'} and len(bridge)==4
        alignments={n:elf_alignment(archive.read(n), 16384 if n.split('/')[1] in ('arm64-v8a','x86_64') or n.endswith('/libfindroid_files.so') else 4096) for n in libraries}
        assert not any(n.endswith('.traineddata') for n in archive.namelist()), 'Language models must remain opt-in'
    ziplog=proof/(kind+'-zipalign.txt');command([tools/'zipalign','-c','-P','16','-v','4',apk],ziplog)
    report=dict(apkSha256=sha(apk),libraries=bridge,allPackagedLibraries=libraries,loadSegmentAlignments=alignments,
                all64BitLoadSegmentsAlignedAtLeast16384=True,languageModelsNotBundled=True,zipAlignmentPassed=True,zipAlignmentLogSha256=sha(ziplog))
    (proof/(kind+'-native-libraries.json')).write_text(json.dumps(report,indent=2)+'\n')

artifacts=[]
for label,apk in (('previous',Path('/Users/LENOVO/.t3/projects/droidfinder/captures/release/0.52.0/Findroid-0.52.0-preview.apk')),('preview',root/'app/build/outputs/apk/preview/app-preview.apk')):
    log=proof/(label+'-apksigner.txt');text=command([tools/'apksigner','verify','--verbose','--print-certs',apk],log)
    certs=sorted(set(re.findall(r'certificate SHA-256 digest: ([0-9a-f]+)',text)));assert len(certs)==1 and 'Number of signers: 1' in text
    artifacts.append(dict(apk=apk.name,apkSha256=sha(apk),signingCertificateSha256=certs[0],signatureLogSha256=sha(log)))
assert artifacts[0]['signingCertificateSha256']==artifacts[1]['signingCertificateSha256']
(proof/'signing-continuity.json').write_text(json.dumps(dict(sameSigningCertificate=True,artifacts=artifacts),indent=2)+'\n')
print('Frozen preview/release: 64-bit native libraries pass 16 KiB load alignment; four ABIs checked; ZIP alignment and preview signing continuity pass.')
