"""Fail if private bundled data or local artifacts enter the Git index."""
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]

def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, check=True).stdout

files = git('ls-files', '-z').decode().split('\0')
files = [name for name in files if name]
prefixes = ('private-content/', 'app/src/main/assets/bundled/', 'dist/', 'docs/private/', 'docs/screenshots/', '.tools/', '.gradle/', '.kotlin/')
for name in files:
    if name.startswith(prefixes) or '/build/' in name or pathlib.Path(name).suffix.lower() in ('.apk', '.aab', '.db', '.keystore', '.jks'):
        raise SystemExit('Private/generated file is staged: ' + name)

config_path = ROOT / 'private-content/bundle.json'
snippets = []
if config_path.exists():
    config = json.loads(config_path.read_text(encoding='utf-8-sig'))
    snippets.extend(config[key].encode() for key in ('worldDescription', 'playerDescription', 'charactersDir', 'worldDir') if config.get(key))
    source = pathlib.Path(config['charactersDir'])
    for path in source.rglob('*.json'):
        card = json.loads(path.read_text(encoding='utf-8-sig'))
        description = card.get('data', card).get('description', '')
        if len(description) >= 80:
            snippets.append(description[:80].encode())
for name in files:
    payload = git('show', ':' + name)
    if any(snippet in payload for snippet in snippets):
        raise SystemExit('Private content detected in staged file: ' + name)
print(f'PRIVATE_STAGING_OK files={len(files)} private_files=0 private_snippets=0')
