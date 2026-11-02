"""Build tiny signed APKs for native update-verification and installer acceptance tests."""
from pathlib import Path
import argparse
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--keystore', type=Path, required=True)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--version-code', type=int, required=True)
args = parser.parse_args()
out = args.output / 'work'
out.mkdir(parents=True, exist_ok=True)
assets = args.output / 'assets/updates'
assets.mkdir(parents=True, exist_ok=True)
sdk = args.sdk
tools = sdk / 'build-tools/36.0.0'
key = args.keystore
other = out / 'wrong-signer.jks'
if not other.exists():
    subprocess.run([str(args.java_home / 'bin/keytool'),
        '-genkeypair','-keystore',str(other),'-storepass','fixture-only','-keypass','fixture-only',
        '-alias','fixture','-keyalg','RSA','-keysize','2048','-validity','100',
        '-dname','CN=Findroid negative test fixture'],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
for name,package,code,minimum,wrong in [
    ('valid','dev.findroid',args.version_code,30,False),('old','dev.findroid',args.version_code-2,30,False),
    ('wrong-package','dev.other.findroid',args.version_code,30,False),('wrong-signer','dev.findroid',args.version_code,30,True),
    ('newer-android','dev.findroid',args.version_code,37,False)]:
    directory=out/name
    directory.mkdir(exist_ok=True)
    manifest=directory/'AndroidManifest.xml'
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="{code}" android:versionName="update-fixture"><uses-sdk android:minSdkVersion="{minimum}" android:targetSdkVersion="36"/><application android:label="Findroid update fixture" android:debuggable="true" android:hasCode="false"/></manifest>''')
    unsigned=directory/'unsigned.apk'
    subprocess.run([str(tools/'aapt2'),'link','-o',str(unsigned),'--manifest',str(manifest),
        '-I',str(sdk/'platforms/android-36/android.jar')],check=True)
    apk=assets/f'{name}.apk'
    subprocess.run([str(tools/'apksigner'),'sign','--ks',str(other if wrong else key),
        '--ks-key-alias','fixture' if wrong else 'androiddebugkey',
        '--ks-pass','pass:fixture-only' if wrong else 'pass:android',
        '--key-pass','pass:fixture-only' if wrong else 'pass:android','--out',str(apk),str(unsigned)],check=True)
print('Built five signed update fixtures; private fixture key stays in the ignored build directory.')
