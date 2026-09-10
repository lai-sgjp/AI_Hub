"""Read-only assertions against this project's test app, restarting only cn.aitavern.app."""
import argparse
import json
import pathlib
import sqlite3
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', default='emulator-5580')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]

def adb(*cmd):
    return subprocess.run([args.adb, '-s', args.serial, *cmd], check=True, capture_output=True).stdout

def snapshot(folder):
    folder.mkdir(parents=True, exist_ok=True)
    for name in ('tavern.db', 'tavern.db-wal'):
        result = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'run-as', 'cn.aitavern.app', 'cat', 'databases/' + name], capture_output=True)
        if result.returncode == 0:
            (folder / name).write_bytes(result.stdout)
    with sqlite3.connect(str(folder / 'tavern.db')) as db:
        return dict(db.execute('SELECT id, payload FROM records').fetchall())

adb('shell', 'am', 'force-stop', 'cn.aitavern.app')
before = snapshot(root / '.tools/restart-check/before')
adb('shell', 'am', 'start', '-n', 'cn.aitavern.app/.MainActivity')
time.sleep(3)
adb('shell', 'am', 'force-stop', 'cn.aitavern.app')
after = snapshot(root / '.tools/restart-check/after')
assert before == after, 'Persisted records changed after process restart'
preferences = adb('exec-out', 'run-as', 'cn.aitavern.app', 'cat', 'shared_prefs/api-secrets.xml')
assert b'instrumentation-only-secret' not in preferences
print(json.dumps({'process_restart_records_preserved': len(after), 'encrypted_store_contains_no_plaintext_test_key': True}))
