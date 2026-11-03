"""Reset only private managed acceptance state to the immutable originals between native batches."""
from pathlib import Path
import subprocess,sys
assert len(sys.argv)==3 and sys.argv[1]=='restore' and sys.argv[2] in ('emulator-5602','emulator-5582')
subprocess.run([sys.executable,str(Path(__file__).with_name('private-native-state.py')),'restore','--serial',sys.argv[2]],check=True)
