#!/usr/bin/env python3
"""Build a separate synthetic source and writable destination for verified-copy acceptance.

Only the provider's private owned destination is writable. Sources generate deterministic
bounded bytes. Private control.properties supports independent source/readback gates,
0..500ms delays and synthetic unreadable/corrupt readback. This replaces the assigned
transfer test provider temporarily; the controller must restore its previous APK and data.
It has no network permission and is never part of a shipping APK.
"""
import argparse, hashlib, json, os, subprocess, zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk',type=Path,required=True)
p.add_argument('--java-home',type=Path,required=True)
p.add_argument('--keystore',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
assert a.keystore.is_file()
assert not a.output.exists(), 'Use a new fixture output; retain prior accepted bytes'
a.output.mkdir(parents=True)
package='dev.findroid.transferqa'
manifest=a.output/'AndroidManifest.xml'
manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="2" android:versionName="synthetic-verification-fixture">
<uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/>
<application android:label="Findroid verification test files" android:debuggable="true" android:allowBackup="false">
<provider android:name="dev.findroid.qa.VerificationDocumentsProvider" android:authorities="{package}.documents" android:exported="true" android:grantUriPermissions="true" android:permission="android.permission.MANAGE_DOCUMENTS">
<intent-filter><action android:name="android.content.action.DOCUMENTS_PROVIDER"/></intent-filter>
</provider></application></manifest>''')
source=ROOT/'scripts/android/VerificationDocumentsProvider.java'
platform=a.sdk/'platforms/android-36/android.jar';tools=a.sdk/'build-tools/36.0.0'
classes=a.output/'classes';classes.mkdir();dex=a.output/'dex';dex.mkdir()
def run(*parts): subprocess.run([str(part) for part in parts],check=True,env={**os.environ,'JAVA_HOME':str(a.java_home)})
run(a.java_home/'bin/javac','-source','8','-target','8','-bootclasspath',platform,'-d',classes,source)
run(tools/'d8','--lib',platform,'--output',dex,*sorted(classes.rglob('*.class')))
unsigned=a.output/'unsigned.apk'
run(tools/'aapt2','link','-o',unsigned,'--manifest',manifest,'-I',platform)
with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as archive: archive.write(dex/'classes.dex','classes.dex')
aligned=a.output/'aligned.apk';run(tools/'zipalign','-p','4',unsigned,aligned)
apk=a.output/'Findroid-verification-test-provider.apk'
run(tools/'apksigner','sign','--ks',a.keystore,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',apk,aligned)
run(tools/'apksigner','verify',apk)
report=dict(package=package,apk=str(apk),bytes=apk.stat().st_size,sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),source=str(source.relative_to(ROOT)),sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),syntheticBytesPerStream=8*1024*1024,noNetworkPermission=True,syntheticSourcesReadOnly=True,writesOnlyOwnedDestination=True,separateFromShippingApk=True)
(a.output/'BUILDINFO.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
