"""Install an update on the explicitly selected test device and compare private state.

Only counts are printed; copied database and encrypted preferences stay in .tools.
The already installed application must be the previous version.
"""
import argparse
import json
from contextlib import closing
import pathlib
import sqlite3
import subprocess
import tempfile
import time
import zipfile

from upgrade_state import verify_preserved

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--apk', required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
with zipfile.ZipFile(args.apk) as apk:
    expected_bundles = {
        'bundle:' + json.loads(apk.read(name))['id']
        for name in apk.namelist()
        if name == 'assets/bundled/manifest.json'
        or (name.startswith('assets/bundled/bundles/')
            and name.count('/') == 4 and name.endswith('/manifest.json'))
    }

def adb(*cmd):
    return subprocess.run([args.adb, '-s', args.serial, *cmd], check=True, capture_output=True).stdout

def state():
    with tempfile.TemporaryDirectory(dir=root / '.tools') as tmp:
        folder = pathlib.Path(tmp)
        for name in ('tavern.db', 'tavern.db-wal'):
            result = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'run-as', 'cn.aitavern.app', 'cat', 'databases/' + name], capture_output=True)
            if result.returncode == 0:
                (folder / name).write_bytes(result.stdout)
            elif name == 'tavern.db':
                raise RuntimeError('Cannot read the installed app database')
        with closing(sqlite3.connect(folder / 'tavern.db')) as db:
            records = {key: (kind, payload) for key, kind, payload in db.execute('SELECT id, kind, payload FROM records')}
    preferences = adb('exec-out', 'run-as', 'cn.aitavern.app', 'sh', '-c',
                      'if [ -f shared_prefs/api-secrets.xml ]; then cat shared_prefs/api-secrets.xml; else printf ABSENT; fi')
    if preferences == b'ABSENT':
        preferences = None
    return records, preferences

adb('shell', 'am', 'force-stop', 'cn.aitavern.app')
before = state()
adb('install', '-r', str(pathlib.Path(args.apk).resolve()))
deadline = time.monotonic() + 90
while True:
    adb('shell', 'am', 'start', '-W', '-n', 'cn.aitavern.app/.MainActivity')
    time.sleep(5)
    if not adb('shell', 'pidof', 'cn.aitavern.app').strip():
        raise RuntimeError('Updated app exited before verification')
    # Read a coherent snapshot only after the app has stopped writing.
    adb('shell', 'am', 'force-stop', 'cn.aitavern.app')
    after = state()
    added = verify_preserved(before, after)
    if expected_bundles.issubset(after[0]):
        break
    if time.monotonic() >= deadline:
        raise RuntimeError('Timed out waiting for bundled worlds to initialize')
print(f'UPGRADE_OK records_preserved={len(before[0])} records_added={added} bundles_ready={len(expected_bundles)} encrypted_credentials_unchanged=true')
