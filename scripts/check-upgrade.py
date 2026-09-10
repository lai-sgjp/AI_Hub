"""Install an update on the explicitly selected test device and compare private state.

Only counts are printed; copied database and encrypted preferences stay in .tools.
The already installed application must be the previous version.
"""
import argparse
from contextlib import closing
import pathlib
import sqlite3
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--apk', required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]

def adb(*cmd):
    return subprocess.run([args.adb, '-s', args.serial, *cmd], check=True, capture_output=True).stdout

def state():
    with tempfile.TemporaryDirectory(dir=root / '.tools') as tmp:
        folder = pathlib.Path(tmp)
        for name in ('tavern.db', 'tavern.db-wal'):
            result = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'run-as', 'cn.aitavern.app', 'cat', 'databases/' + name], capture_output=True)
            if result.returncode == 0:
                (folder / name).write_bytes(result.stdout)
        with closing(sqlite3.connect(folder / 'tavern.db')) as db:
            records = dict(db.execute('SELECT id, payload FROM records').fetchall())
    preferences = adb('exec-out', 'run-as', 'cn.aitavern.app', 'cat', 'shared_prefs/api-secrets.xml')
    return records, preferences

adb('shell', 'am', 'force-stop', 'cn.aitavern.app')
before = state()
adb('install', '-r', str(pathlib.Path(args.apk).resolve()))
adb('shell', 'am', 'start', '-n', 'cn.aitavern.app/.MainActivity')
time.sleep(3)
adb('shell', 'am', 'force-stop', 'cn.aitavern.app')
after = state()
assert before == after, 'Upgrade changed existing records or encrypted credentials'
print(f'UPGRADE_OK records_preserved={len(after[0])} encrypted_credentials_unchanged=true')
