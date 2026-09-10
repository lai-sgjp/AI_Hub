param(
    [string]$Serial = 'emulator-5580',
    [string]$AdbPath = '',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $AdbPath) { $AdbPath = Join-Path $projectRoot '.tools/android-sdk/platform-tools/adb.exe' }
if (-not (Test-Path -LiteralPath $AdbPath)) { throw 'ADB not found. Install platform-tools or pass -AdbPath.' }
if (-not $SkipBuild) { & "$PSScriptRoot/build.ps1" -Tasks ':app:assembleDebug', ':app:assembleDebugAndroidTest' }
& $AdbPath -s $Serial install -r "$projectRoot/app/build/outputs/apk/debug/app-debug.apk"
if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
& $AdbPath -s $Serial install -r "$projectRoot/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
$result = & $AdbPath -s $Serial shell am instrument -w cn.aitavern.app.test/androidx.test.runner.AndroidJUnitRunner
$result | Set-Content -LiteralPath "$projectRoot/.tools/device-tests-latest.log" -Encoding utf8
$result
if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(\d+ tests\)') { throw 'Android instrumentation tests failed. See .tools/device-tests-latest.log.' }
