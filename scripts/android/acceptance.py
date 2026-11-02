"""Read-only UI inspection and explicit native touch for dedicated Android acceptance devices."""
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class AndroidDevice:
    def __init__(self, serial, output):
        self.serial = serial
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or Path.home() / 'Library/Android/sdk')
        self.adb = str(self.sdk / 'platform-tools/adb')
        self.touch_jar = None
        self.xml = '/sdcard/findroid-native-acceptance.xml'
        self.screen = tuple(map(int, re.findall(r'(\d+)x(\d+)', self.shell('wm', 'size').decode())[-1]))
        if int(self.shell('getprop', 'ro.build.version.sdk')) <= 30:
            self._prepare_finger_input()

    def call(self, *parts, data=None, check=True):
        result = subprocess.run([self.adb, '-s', self.serial, *map(str, parts)], input=data,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
        if check and result.returncode:
            raise RuntimeError(f'adb {parts[:2]} failed: {result.stderr.decode(errors="replace")}')
        return result.stdout if check else result

    def shell(self, *parts, **kwargs):
        return self.call('shell', shlex.join(map(str, parts)), **kwargs)

    def _prepare_finger_input(self):
        java = Path(os.environ.get('JAVA_HOME') or Path.home() / 'Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1/Contents/Home')
        library = self.sdk / 'platforms/android-36/android.jar'
        with tempfile.TemporaryDirectory(prefix='findroid-native-touch-') as temporary:
            folder = Path(temporary)
            classes = folder / 'classes'; classes.mkdir()
            dex = folder / 'dex'; dex.mkdir()
            subprocess.run([str(java / 'bin/javac'), '-source', '8', '-target', '8', '-bootclasspath', str(library), '-d', str(classes), str(ROOT / 'scripts/android/NativeTap.java')], check=True)
            subprocess.run([str(self.sdk / 'build-tools/36.0.0/d8'), '--lib', str(library), '--output', str(dex), str(classes / 'dev/findroid/qa/NativeTap.class')], env={**os.environ, 'JAVA_HOME': str(java)}, check=True)
            archive = folder / 'native-touch.jar'
            with zipfile.ZipFile(archive, 'w') as output:
                output.write(dex / 'classes.dex', 'classes.dex')
            self.touch_jar = '/data/local/tmp/findroid-touch-' + str(time.time_ns()) + '.jar'
            self.call('push', archive, self.touch_jar)

    @staticmethod
    def bounds(node):
        return list(map(int, re.findall(r'\d+', node.get('bounds', ''))))

    def nodes(self, tree, label):
        return [n for n in tree.iter('node') if (n.get('text') == label or n.get('content-desc') == label)
            and len(self.bounds(n)) == 4 and self.bounds(n)[2] > self.bounds(n)[0] and self.bounds(n)[3] > self.bounds(n)[1]]

    def one(self, tree, label):
        nodes = self.nodes(tree, label)
        assert len(nodes) == 1, (label, len(nodes))
        return nodes[0]

    def ready(self, tree, label):
        return any(n.get('enabled') == 'true' for n in self.nodes(tree, label))

    def dump(self, name):
        self.shell('uiautomator', 'dump', self.xml)
        data = self.call('exec-out', 'cat', self.xml)
        (self.output / (name + '.xml')).write_bytes(data)
        return ET.fromstring(data)

    def wait(self, name, predicate, seconds=45):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            tree = self.dump(name)
            if predicate(tree):
                return tree
            time.sleep(.15)
        raise AssertionError('UI did not settle: ' + name)

    def tap(self, node):
        l, t, r, b = self.bounds(node)
        x, y = (l+r)//2, (t+b)//2
        if self.touch_jar:
            self.shell('env', 'CLASSPATH=' + self.touch_jar, 'app_process', '/system/bin', 'dev.findroid.qa.NativeTap', x, y)
        else:
            self.shell('input', 'tap', x, y)

    def click(self, name, label):
        self.tap(self.one(self.wait(name, lambda t: self.ready(t, label)), label))

    def scroll_to(self, name, label, x=.5):
        for _ in range(10):
            tree = self.dump(name)
            if self.nodes(tree, label):
                return tree
            width, height = self.screen
            self.shell('input', 'swipe', int(width*x), int(height*.74), int(width*x), int(height*.40), 380)
        raise AssertionError('Could not scroll to ' + label)

    def scoped(self, tree, title, label):
        """Find a named choice inside its dialog rather than the browser behind it."""
        parents = {child: parent for parent in tree.iter() for child in parent}
        node = self.one(tree, title)
        while not self.nodes(node, label):
            assert node in parents, (title, label)
            node = parents[node]
        return self.one(node, label)

    def capture(self, name):
        self.dump(name + '-capture')
        previous = self.call('exec-out', 'screencap', '-p')
        for _ in range(4):
            time.sleep(.15)
            current = self.call('exec-out', 'screencap', '-p')
            if current == previous:
                break
            previous = current
        (self.output / (name + '.png')).write_bytes(current)

    def close(self):
        if self.touch_jar:
            self.shell('rm', '-f', self.touch_jar)
        self.shell('rm', '-f', self.xml)
