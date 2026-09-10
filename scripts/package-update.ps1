param(
    [Parameter(Mandatory=$true)][string]$PreviousApk,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$keyPath = Join-Path $projectRoot 'private-content/signing/tavern.keystore'
if (!(Test-Path -LiteralPath $keyPath)) { throw 'Original private signing key is missing. Do not generate a replacement for an existing installation.' }
$previousPath = (Resolve-Path -LiteralPath $PreviousApk).Path
if (!$SkipBuild) { & "$PSScriptRoot/build.ps1" }
$env:JAVA_HOME = (Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName
$buildTools = Join-Path $projectRoot '.tools/android-sdk/build-tools/35.0.0'
$newApk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'

function Read-ApkIdentity([string]$path) {
    $signature = & "$buildTools/apksigner.bat" verify --print-certs $path
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $certificate = [regex]::Match(($signature -join "`n"), 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)').Groups[1].Value
    $badging = & "$buildTools/aapt.exe" dump badging $path
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read APK metadata.' }
    $package = [regex]::Match(($badging -join "`n"), "package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'")
    if (!$certificate -or !$package.Success) { throw 'APK identity is incomplete.' }
    return @{ Certificate=$certificate; Package=$package.Groups[1].Value; Code=[long]$package.Groups[2].Value; Version=$package.Groups[3].Value }
}
$previous = Read-ApkIdentity $previousPath
$next = Read-ApkIdentity $newApk
if ($previous.Certificate -ne $next.Certificate -or $previous.Package -ne $next.Package) { throw 'Update package or signing certificate differs from previous APK.' }
if ($next.Code -le $previous.Code) { throw 'Increase versionCode before packaging an update.' }
if ($next.Version -notmatch '^[0-9A-Za-z._-]+$') { throw 'Version name is not safe for a filename.' }
New-Item -ItemType Directory -Force "$projectRoot/dist" | Out-Null
$destination = Join-Path $projectRoot "dist/AI-Tavern-$($next.Version)-private-debug.apk"
if (Test-Path -LiteralPath $destination) { throw 'Output APK already exists. Preserve existing deliveries and increment the version.' }
Copy-Item -LiteralPath $newApk -Destination $destination
Write-Output "UPDATE_PACKAGE_OK version=$($next.Version) versionCode=$($next.Code) signature_matches=true"
Get-FileHash -LiteralPath $destination -Algorithm SHA256
