"""Download isolated Android build tools from official publishers; no system changes."""
import concurrent.futures
import hashlib
import json
import pathlib
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)

def fetch(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()

def install(name, url, destination, digest=None):
    archive = TOOLS / (name + '.zip')
    if not archive.exists():
        print('Downloading ' + name, flush=True)
        partial = archive.with_suffix('.zip.partial')
        with urllib.request.urlopen(url, timeout=120) as response, partial.open('wb') as output:
            while block := response.read(1024 * 1024):
                output.write(block)
        partial.replace(archive)
    if digest and hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
        raise RuntimeError('Checksum mismatch: ' + str(archive))
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(destination)
    print('Ready: ' + name, flush=True)

def jdk():
    release = json.loads(fetch('https://api.github.com/repos/adoptium/temurin17-binaries/releases/tags/jdk-17.0.20.1%2B1'))
    package = next(a for a in release['assets'] if a['name'] == 'OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip')
    checksum = next(a for a in release['assets'] if a['name'] == package['name'] + '.sha256.txt')
    digest = fetch(checksum['browser_download_url']).decode().split()[0]
    install('jdk', package['browser_download_url'], TOOLS / 'jdk', digest)

def gradle():
    url = 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip'
    install('gradle', url, TOOLS, fetch(url + '.sha256').decode().strip())

def android():
    install('cmdtools', 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip', TOOLS / 'android-sdk')

with concurrent.futures.ThreadPoolExecutor(3) as pool:
    for result in pool.map(lambda f: f(), [jdk, gradle, android]):
        pass
