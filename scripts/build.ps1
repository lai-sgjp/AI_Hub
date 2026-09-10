param([string[]]$Tasks = @(':core:test', ':core:jacocoTestCoverageVerification', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$jdkPath = Get-ChildItem "$projectRoot/.tools/jdk" -Directory | Select-Object -First 1
if (-not $jdkPath) { throw 'Run scripts/bootstrap.py first (Python 3 required).' }
$env:JAVA_HOME = $jdkPath.FullName
$env:ANDROID_HOME = "$projectRoot/.tools/android-sdk"
$env:GRADLE_USER_HOME = "$projectRoot/.tools/gradle-home"
$env:ANDROID_USER_HOME = "$projectRoot/.tools/android-user"
Push-Location $projectRoot
try {
    if (Test-Path './.tools/gradle-8.11.1/bin/gradle.bat') { & ./.tools/gradle-8.11.1/bin/gradle.bat @Tasks --console=plain }
    else { & ./gradlew.bat @Tasks --console=plain }
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with $LASTEXITCODE" }
} finally { Pop-Location }
