param([switch]$Offline)
$ErrorActionPreference = 'Stop'
# Tests also locate a full JDK in the current process if JAVA_HOME is not configured.
& (Join-Path $PSScriptRoot 'test.ps1')
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path "$env:ANDROID_HOME\platforms\android-36\android.jar")) {
    throw 'Install Android SDK platform 36 and build-tools 36.0.0, or set ANDROID_HOME.'
}
$argsForGradle = @('--no-daemon', '--console=plain', 'assembleRelease', 'lintRelease')
if ($Offline) { $argsForGradle = @('--offline') + $argsForGradle }
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat @argsForGradle
    if ($LASTEXITCODE -ne 0) { throw 'Android build/lint failed.' }
    & .\verify-apk.ps1 -Apk (Join-Path $PSScriptRoot 'dist\device-info.apk')
    Write-Host "Verified APK: $PSScriptRoot\dist\device-info.apk"
} finally {
    Pop-Location
}
# Intentionally no adb install, default-home changes, or device commands.
